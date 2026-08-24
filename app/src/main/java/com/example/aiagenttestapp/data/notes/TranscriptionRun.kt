package com.example.aiagenttestapp.data.notes

import android.content.Context
import android.os.Build
import android.util.Log
import com.example.aiagenttestapp.data.ModelResidency
import com.example.aiagenttestapp.data.SettingsStore
import com.example.aiagenttestapp.data.audiomodels.AudioModelCatalog
import com.example.aiagenttestapp.data.audiomodels.AudioModelRepository
import com.example.aiagenttestapp.stt.AudioRecorder
import com.example.aiagenttestapp.stt.AudioSegmenter
import com.example.aiagenttestapp.stt.KeywordDetector
import com.example.aiagenttestapp.stt.OnnxTranscriber
import com.example.aiagenttestapp.stt.PlatformSpeech
import com.example.aiagenttestapp.stt.PlatformTranscriber
import com.example.aiagenttestapp.stt.Punctuator
import com.example.aiagenttestapp.stt.SegmentTranscription
import com.example.aiagenttestapp.stt.SpeechActivityDetector
import com.example.aiagenttestapp.stt.SpeechModelRepository
import com.example.aiagenttestapp.stt.SpeechRecognizer
import com.example.aiagenttestapp.stt.SpeechRegions
import com.example.aiagenttestapp.stt.StreamingRecognizer
import com.example.aiagenttestapp.stt.StreamingTranscriber
import com.example.aiagenttestapp.stt.SttLoadPlanner
import com.example.aiagenttestapp.stt.SttModelPlan
import com.example.aiagenttestapp.stt.Transcriber
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * One transcription of one recording: open the right recogniser, find the speech, slice it,
 * decode each slice with checkpointed resume.
 *
 * Extracted from [NoteTranscribeWorker], which owned all of this privately, the day a second
 * durable consumer appeared (the benchmark runner). The alternative -- a benchmark pipeline of
 * its own -- was rejected on fidelity grounds: a benchmark number is only a measurement of the
 * product if it comes from the *same* VAD, the same slicing, the same checkpoint writes and the
 * same transcriber construction a real note gets, and two copies of that logic would drift the
 * first time one of them was tuned.
 *
 * The consumer-specific edges stay with the consumers: what to do with progress ([transcribe]'s
 * callback), and what a failure means (the [Outcome] variants; the worker writes them to the note
 * row, the benchmark to its run row). Everything checkpoint-shaped stays here, because the
 * checkpoint's exact-sample-range contract is precisely the thing that must not fork.
 */
class TranscriptionRun @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val speechModels: SpeechModelRepository,
    private val speechRecognizer: SpeechRecognizer,
    private val streamingRecognizer: StreamingRecognizer,
    private val keywordDetector: KeywordDetector,
    private val punctuator: Punctuator,
    private val sttLoadPlanner: SttLoadPlanner,
    private val modelResidency: ModelResidency,
    private val audioModels: AudioModelRepository,
    private val settings: SettingsStore,
) {

    sealed interface Outcome {
        data class Done(
            val transcript: String,
            val durationMillis: Long,
            /** ISO 639 code the recognisers voted for, or null. See [dominantLanguage]. */
            val language: String?,
            /** Wall time of the whole run, load and VAD included -- what a user actually waits. */
            val wallMillis: Long,
        ) : Outcome

        /**
         * The audio held nothing decodable. Deliberately not an exception: it is an answer about
         * the recording, not a failure of the run, and the caller decides what it costs. The
         * audio and checkpoint are left in place -- [TranscriptionCheckpoint.clearSpeechActivity]
         * has already been called on the empty-verdict path, so a retry genuinely re-listens.
         */
        data class NothingRecognised(val message: String) : Outcome
    }

    /**
     * Transcribes [audio] under [backend]. Throws for run-level failures (a model that no longer
     * loads, a refused platform request); returns [Outcome.NothingRecognised] for empty audio.
     * [onProgress] fires once per slice with completion in 0..1.
     */
    suspend fun transcribe(
        audio: File,
        markers: List<SpokenMarker>,
        excluded: List<IntRange>,
        backend: SttBackend,
        preferredModelId: String?,
        checkpoint: TranscriptionCheckpoint,
        onProgress: suspend (Float) -> Unit,
    ): Outcome {
        val startedAt = System.currentTimeMillis()

        // The two independent start-up costs run together rather than in sequence: opening the
        // transcriber is the long pole on the Gemma path -- a multi-gigabyte load whenever the
        // model is not already resident -- and the VAD is a serial read of the whole recording.
        // Neither needs the other, so paying them back to back was pure wait. The VAD sits in
        // the async because it cannot throw (it degrades to null); the one failure that must
        // not leak a loaded model -- openTranscriber throwing -- therefore unwinds through
        // coroutineScope with nothing held.
        var transcriber: Transcriber? = null
        try {
            var speechRegions: List<IntRange>? = null
            coroutineScope {
                val regions = async(Dispatchers.Default) {
                    WavFile.Reader(audio).use { reader ->
                        val totalSamples = reader.sampleCount
                        if (totalSamples <= 0) return@use null
                        resolveSpeechRegions(
                            reader = reader,
                            totalSamples = totalSamples,
                            markers = markers,
                            // From the backend, not the transcriber -- the whole point is that
                            // the transcriber does not exist yet. See [AudioSegmenter.capFor].
                            maxSliceSamples = AudioSegmenter.capFor(
                                backend,
                                speechModels.selected.kind,
                                settings.settings.value.sliceWindow,
                            ),
                            checkpoint = checkpoint,
                        )
                    }
                }
                transcriber = openTranscriber(backend, preferredModelId)
                speechRegions = regions.await()
            }
            return decodeAll(
                audio = audio,
                markers = markers,
                excluded = excluded,
                transcriber = transcriber!!,
                speechRegions = speechRegions,
                checkpoint = checkpoint,
                onProgress = onProgress,
                startedAt = startedAt,
            )
        } finally {
            // NonCancellable because every release() suspends and this runs on the cleanup path.
            // A suspension point inside the finally block of a *cancelled* coroutine throws before
            // the body reaches it, and WorkManager stopping a job is cancellation -- so the release
            // that looks unconditional here never actually ran. On the platform backend that left
            // the system recogniser bound to a session nothing would ever finish, and the next run
            // in the same process failed with "the recogniser is busy", pointing at a leak two runs
            // upstream. On the sherpa path it stranded Whisper Small's ~750 MB.
            withContext(NonCancellable) { transcriber?.release() }
        }
    }

    /**
     * Readies the recogniser this recording asked for.
     *
     * The Gemma path resolves and loads a language model, which can fail for reasons the ONNX path
     * never had -- the model was deleted since the recording, or no longer fits. That failure is
     * thrown rather than quietly answered with the ONNX transcriber: the two produce visibly
     * different transcripts, and silently substituting one for the other would leave the user with
     * a result they cannot account for. The callers turn it into an error on their own row.
     */
    private suspend fun openTranscriber(backend: SttBackend, preferredModelId: String?): Transcriber =
        when (backend) {
            // Which sherpa recogniser depends on the model, not on the backend: they share the ONNX
            // backend and the whole pipeline around it, and differ only in whether audio goes in as
            // clips or as a stream.
            SttBackend.ONNX -> {
                // Checked here rather than trusted, because the failure mode below this line is not
                // an exception. sherpa is handed absolute paths and opens them natively; a file that
                // is absent, or present as a half-finished download, takes the process down inside
                // the loader with nothing thrown to catch -- the same reason
                // [SpeechModelRepository.isDownloaded] compares exact sizes rather than existence.
                //
                // Nothing enforced this until models became large enough to still be arriving when
                // a run starts: Parakeet is 670 MB, so "selected but not yet on disk" is a state the
                // user can now reach by tapping two things in the order anyone would.
                val model = speechModels.selected
                check(speechModels.isDownloaded(model)) {
                    "${model.label} is not downloaded yet"
                }

                if (model.kind.isStreaming) {
                    StreamingTranscriber(streamingRecognizer, speechModels, loadPunctuator())
                } else {
                    OnnxTranscriber(
                        speechRecognizer,
                        speechModels,
                        settings.settings.value.sliceWindow,
                    )
                }
            }

            SttBackend.GEMMA -> when (val plan = sttLoadPlanner.plan(preferredModelId)) {
                is SttModelPlan.Unavailable -> error(plan.reason)
                is SttModelPlan.Ready -> {
                    Log.i(TAG, "transcribing with ${plan.modelName}")
                    sttLoadPlanner.open(plan)
                }
            }

            // Re-checked here rather than trusted from the record screen: the system recogniser and
            // its language packs can be disabled or removed between the tap that started the
            // recording and the worker that picks it up, and a re-enqueue after a process death may
            // run days later.
            SttBackend.PLATFORM -> {
                val availability = PlatformSpeech.availability(context)
                if (availability is PlatformSpeech.Availability.Unsupported) {
                    error(availability.reason)
                }
                // Redundant -- availability() has already established the API level -- but lint's
                // flow analysis cannot see through that call, and an explicit test is worth more
                // than a @SuppressLint, which would also hide the next NewApi mistake in this file.
                if (Build.VERSION.SDK_INT < PlatformSpeech.MIN_API) {
                    error("The system recogniser needs Android 13 or newer.")
                }
                Log.i(TAG, "transcribing with the system on-device recogniser")
                // Punctuated for the same reason the streaming path is: this recogniser returns one
                // unbroken lowercase stream, and the summariser and the marker scan both read it.
                PlatformTranscriber(
                    context,
                    punctuator = loadPunctuator(),
                    // Read here, once per run, so the whole job is fed at one rate. The per-slice
                    // segment log is what verifies a faster choice actually kept every sentence.
                    language = settings.settings.value.platformLanguage,
                    feedPace = settings.settings.value.platformFeedPace.multiplier,
                    chunkMillis = settings.settings.value.platformFeedChunk.millis,
                )
            }
        }

    /**
     * Readies the punctuator, or returns null when its bundle is not downloaded.
     *
     * Null rather than an error: punctuation is an optional extra, and a streaming note taken before
     * the user fetched it should still be a note -- merely an uppercase one.
     */
    private suspend fun loadPunctuator(): Punctuator? {
        val bundle = audioModels.punctuation
        if (!audioModels.isReady(bundle)) return null

        val ok = punctuator.load(
            model = audioModels.fileFor(bundle, AudioModelCatalog.PUNCT_MODEL),
            vocab = audioModels.fileFor(bundle, AudioModelCatalog.PUNCT_VOCAB),
        )
        return punctuator.takeIf { ok }
    }

    private suspend fun decodeAll(
        audio: File,
        markers: List<SpokenMarker>,
        excluded: List<IntRange>,
        transcriber: Transcriber,
        /** Resolved by [transcribe], concurrently with the transcriber's own load. */
        speechRegions: List<IntRange>?,
        checkpoint: TranscriptionCheckpoint,
        onProgress: suspend (Float) -> Unit,
        startedAt: Long,
    ): Outcome = withContext(Dispatchers.Default) {
        // The recording is never held whole. It used to be -- one `WavFile.read` of the entire file,
        // which costs 6 bytes per sample at its peak and put the whole-recording allocation straight
        // back after streaming capture had removed it. Every stage below wants a bounded window
        // anyway, so each one now reads the window it needs and nothing else.
        WavFile.Reader(audio).use { reader ->
            val totalSamples = reader.sampleCount
            if (totalSamples <= 0) {
                return@withContext Outcome.NothingRecognised("The recording was empty.")
            }

            val slices = SpokenMarkers.slice(
                totalSamples = totalSamples,
                markers = markers,
                excludedRanges = excluded,
                speechRegions = speechRegions,
                // From the transcriber, not from a shared constant: the backends tolerate very
                // different clip lengths, and using the wrong cap is not a quality problem but a crash.
                maxSliceSamples = transcriber.maxSliceSamples,
                cutLongSlice = { from, until -> cutBetween(reader, transcriber, from, until) },
            )

            val spoken = slices.filter { it.isSpoken }
            if (spoken.isEmpty()) {
                // The VAD's verdict is what produced this failure, and it is checkpointed -- so
                // without this, "Try again" reloaded the same regions and failed identically every
                // time. Cleared, the retry listens to the audio afresh; the slices already
                // transcribed (there are none on this path, but the invariant matters) still resume.
                checkpoint.clearSpeechActivity()
                return@withContext Outcome.NothingRecognised("Nothing was recognised. Try again.")
            }

            val texts = mutableListOf<String?>()

            // Every slice's guess, not just the first one to have an opinion. See [dominantLanguage]
            // for what first-non-null cost: a recording whose every other slice said English was
            // stored as Malay, and summarised in it.
            val languageVotes = mutableListOf<LanguageVote>()

            var cachedSlices = 0
            var decodedSamples = 0L
            var decodedMillis = 0L

            spoken.forEachIndexed { index, slice ->
                // Resume rather than redo: on a long recording this is the difference between a process
                // death costing one slice and costing the whole run.
                val cached = checkpoint.textFor(slice.range)
                if (cached != null) {
                    cachedSlices++
                    texts += cached.text
                    languageVotes += LanguageVote(cached.language, slice.range.count())
                } else {
                    val decodeStarted = System.currentTimeMillis()
                    val piece = decode(reader, transcriber, slice.range)
                    decodedMillis += System.currentTimeMillis() - decodeStarted
                    decodedSamples += slice.range.count()

                    texts += piece?.text
                    languageVotes += LanguageVote(piece?.language, slice.range.count())
                    checkpoint.record(slice.range, piece?.text.orEmpty(), piece?.language)
                }

                onProgress((index + 1).toFloat() / spoken.size)
            }

            // The comparison line for backend and execution-provider experiments: the same recording
            // transcribed under two settings differs here or nowhere. Cached slices are counted but
            // not timed -- they measure the pre-decode pipeline's coverage, not this run's speed.
            if (decodedSamples > 0) {
                val audioSeconds = decodedSamples.toDouble() / AudioRecorder.SAMPLE_RATE
                Log.i(
                    TAG,
                    "decoded %.1fs of audio in %.1fs (%.2fx real time, provider=%s, %d of %d slices pre-decoded)".format(
                        audioSeconds,
                        decodedMillis / 1000.0,
                        (decodedMillis / 1000.0) / audioSeconds,
                        settings.settings.value.onnxProvider.slug,
                        cachedSlices,
                        spoken.size,
                    ),
                )
            } else {
                Log.i(TAG, "every slice was pre-decoded during recording; nothing to transcribe")
            }

            val blocks = spoken.zip(texts) { slice, text ->
                TranscriptBlock(text = text.orEmpty(), tags = slice.tags)
            }.filter { it.text.isNotBlank() }

            val rendered = TranscriptMarkup.render(blocks)

            // No marker was heard acoustically -- either the keyword spotter is off, or the user was
            // dictating in a language it has no model for. The marker words are in the recognised text
            // instead, so look for them there.
            val text = if (markers.isEmpty()) {
                TranscriptMarkup.wrapSpokenMarkers(rendered)
            } else {
                rendered
            }

            Outcome.Done(
                transcript = text,
                durationMillis = totalSamples * 1000L / AudioRecorder.SAMPLE_RATE,
                language = dominantLanguage(languageVotes),
                wallMillis = System.currentTimeMillis() - startedAt,
            )
        }
    }

    /**
     * Transcribes one slice, reading only that slice off disk.
     *
     * The coordinate switch is the thing to be careful about, and it has bitten this codebase before:
     * what comes back from [WavFile.Reader] starts at index zero, so the range handed to the
     * transcriber is *window*-relative while [range] stays in the recording's own timeline. Passing
     * the absolute range against a windowed array would index off the front of it.
     *
     * The returned [SegmentTranscription.range] is therefore window-relative too, and deliberately
     * ignored -- the caller checkpoints against [range], which is the coordinate the resume looks up.
     */
    private suspend fun decode(
        reader: WavFile.Reader,
        transcriber: Transcriber,
        range: IntRange,
    ): SegmentTranscription? {
        val window = reader.read(range.first, range.last + 1)
        if (window.isEmpty()) return null

        return transcriber.transcribe(window, listOf(window.indices)).firstOrNull()
    }

    /**
     * Where to cut a slice that is longer than the backend can take, reading only the window searched.
     *
     * Bounded by construction: [SpokenMarkers] never asks about more than one slice cap at a time, so
     * this reads at most ~28 s however long the recording is. Same window-versus-recording coordinate
     * switch as [decode] -- the search runs on a zero-based array and its answer is shifted back.
     */
    private fun cutBetween(
        reader: WavFile.Reader,
        transcriber: Transcriber,
        from: Int,
        until: Int,
    ): Int {
        val window = reader.read(from, until)
        // Nothing to search. Answering with the far end keeps the splitter making forward progress,
        // which is the one thing it needs from this to terminate.
        if (window.isEmpty()) return until

        return from + transcriber.quietestCutBetween(window, 0, window.size)
    }

    /**
     * Finds the parts of the recording worth transcribing, or null for "all of it".
     *
     * Runs for both backends. Whisper invents phrases when handed silence just as readily as a
     * language model does -- it is the origin of the "thank you for watching" artefact in quiet
     * recordings -- so this is not a Gemma workaround.
     *
     * Checkpointed for correctness rather than speed. It costs a couple of seconds even on a long
     * recording, but its output decides every slice boundary, and the checkpoint resumes by matching
     * a slice's exact sample range against what has already been transcribed. Regions recomputed
     * even slightly differently after a process death would shift every boundary and re-transcribe a
     * recording that was nearly done.
     *
     * A failure here returns null rather than propagating: skipping silence is an optimisation, and
     * losing it costs some wasted decodes, where failing the job costs the user their recording.
     */
    private suspend fun resolveSpeechRegions(
        reader: WavFile.Reader,
        totalSamples: Int,
        markers: List<SpokenMarker>,
        /**
         * From [AudioSegmenter.capFor], not from a [Transcriber] -- this runs concurrently with the
         * transcriber's own load, which is exactly why it cannot be handed one.
         */
        maxSliceSamples: Int,
        checkpoint: TranscriptionCheckpoint,
    ): List<IntRange>? {
        if (!settings.settings.value.vadEnabled) return null

        checkpoint.speechActivity()?.let { done -> return done.regions }

        val detector = SpeechActivityDetector(context.assets)
        val detected = try {
            detector.load(
                maxSpeechSamples = maxSliceSamples,
                provider = settings.settings.value.onnxProvider.slug,
            )
            // Streamed off disk a block at a time. The VAD is the first thing to touch the audio and
            // it consumes the whole recording, so materialising it here would reintroduce exactly the
            // allocation the rest of this function goes out of its way to avoid.
            detector.detect(totalSamples) { from, until -> reader.read(from, until) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "voice-activity detection unavailable; transcribing everything", e)
            return null
        } finally {
            detector.release()
        }

        val regions = SpeechRegions.resolve(
            detected = detected,
            totalSamples = totalSamples,
            // The content the user tagged out loud is kept whatever the VAD concluded. A spoken
            // marker is the strongest evidence in the recording that a stretch matters, and a model
            // that disagreed with it would be overruling the one thing they went out of their way
            // to say.
            protectedRanges = SpokenMarkers.pair(markers, totalSamples).map { it.range },
        )

        Log.i(
            TAG,
            if (regions == null) {
                "VAD found no silence worth skipping; transcribing everything"
            } else {
                // Long arithmetic: kept * 100 overflows Int past ~22 minutes of kept speech.
                val kept = regions.sumOf { it.count().toLong() }
                "VAD kept ${kept * 100 / totalSamples.coerceAtLeast(1)}% of the recording " +
                    "in ${regions.size} regions"
            },
        )

        checkpoint.recordSpeechActivity(regions)
        return regions
    }

    /**
     * Releases the shared native models.
     *
     * Only for consumers like the benchmark runner that want a clean state for the next run.
     * Not for [NoteTranscribeWorker], which shares these with the record screen.
     */
    suspend fun releaseSharedRecognizers(): Unit = withContext(NonCancellable) {
        // NonCancellable for the same reason [transcribe]'s finally is, and with an extra edge: these
        // are five sequential suspending releases, so a cancellation arriving at the first one did
        // not merely skip that release, it skipped the four behind it. The benchmark calls this from
        // its own finally, which is exactly when cancellation is most likely to be pending.
        speechRecognizer.release()
        streamingRecognizer.release()
        keywordDetector.release()
        punctuator.release()
        modelResidency.releaseIfIdle()
    }

    private companion object {
        const val TAG = "TranscriptionRun"
    }
}
