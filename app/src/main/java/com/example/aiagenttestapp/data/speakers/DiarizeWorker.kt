package com.example.aiagenttestapp.data.speakers

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.aiagenttestapp.data.audiomodels.AudioModelCatalog
import com.example.aiagenttestapp.data.audiomodels.AudioModelRepository
import com.example.aiagenttestapp.data.SettingsStore
import com.example.aiagenttestapp.data.notes.WavFile
import com.example.aiagenttestapp.stt.AudioRecorder
import com.example.aiagenttestapp.stt.CompactedAudio
import com.example.aiagenttestapp.stt.SpeechActivityDetector
import com.example.aiagenttestapp.stt.SpeechRegions
import com.example.aiagenttestapp.stt.DiarizedSegment
import com.example.aiagenttestapp.stt.SpeakerDiarizer
import com.example.aiagenttestapp.stt.SpeechEngineKind
import com.example.aiagenttestapp.stt.SpeechModelRepository
import com.example.aiagenttestapp.stt.SpeechRecognizer
import com.example.aiagenttestapp.stt.ThreadBudget
import com.example.aiagenttestapp.stt.TimedWord
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.absoluteValue

/**
 * Diarises a recording and transcribes it, then writes one block per speaker turn.
 *
 * A worker rather than screen-scoped work, for the reason every long job here is one: a 20-minute
 * recording takes minutes to get through two models, and a user who backs out of the screen or takes
 * a call should come back to a finished transcript rather than to nothing.
 *
 * Clustering is a global judgement -- it decides how many voices exist by comparing every stretch
 * against every other -- so diarising a slice at a time invents a new speaker per slice. That holds
 * unless the speakers are enrolled, in which case naming can put the slices back together, and
 * [DiarizationChunks] then splits long recordings to bound the part of the cost that does not grow
 * linearly. With nobody enrolled the whole recording is diarised at once, as it always was.
 *
 * Transcription runs on its own slicing, chosen for what decodes best, and the two are reconciled
 * afterwards by [SpeakerAlignment].
 */
@HiltWorker
class DiarizeWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val dao: DiarizedDao,
    private val speakers: SpeakerRepository,
    private val audioModels: AudioModelRepository,
    private val speechModels: SpeechModelRepository,
    private val recognizer: SpeechRecognizer,
    private val settings: SettingsStore,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getLong(KEY_RECORDING_ID, -1L)
        if (id < 0) return Result.failure()

        // From here rather than from the tap: what the row reports is what the models cost on this
        // device, not how long WorkManager sat on the job behind whatever else was queued.
        val runStarted = System.currentTimeMillis()

        val recording = dao.byId(id) ?: return Result.success() // deleted while queued
        val audio = File(recording.audioPath)
        if (!audio.exists()) {
            dao.fail(id, "The audio for this recording is no longer available.")
            return Result.success()
        }

        runCatching { setForeground(foregroundInfo(id, 0f)) }

        return try {
            // Refused rather than degraded, and on the capability rather than on a model name. A
            // recogniser with no word timings leaves every word outside every turn, and the whole
            // transcript collapses into one unattributed block -- which reads as a diarisation that
            // found one speaker rather than as a model that cannot do this at all.
            val model = speechModels.selected
            check(model.kind.reportsWordTimings) {
                "${model.label} reports no word timings. Choose ${speechModels.wordTimingChoices}."
            }
            check(speechModels.isDownloaded(model)) { "${model.label} is not downloaded yet." }

            val bundle = audioModels.speaker
            check(audioModels.isReady(bundle)) {
                "The speaker identification models are not downloaded yet."
            }

            // The whole recording in memory, which diarisation genuinely requires -- see the class
            // note. At 16 kHz mono that is 3.8 MB a minute, so a 20-minute recording costs about
            // 77 MB on top of two models. That is affordable and it is also the ceiling on how long
            // a recording this screen can take; if that becomes the limit, the fix is sherpa's
            // streaming diarisation API rather than a bigger array.
            val samples = withProgress(id, 0.05f) { WavFile.read(audio) }
            check(samples.isNotEmpty()) { "The recording is empty." }

            // Silence removed from what diarisation sees, and only from that. Recognition keeps the
            // whole recording: its slicing is chosen for what decodes best, and a word the detector
            // did not think was speech should still be transcribed if the recogniser hears one.
            //
            // Measured against a perfect detector on `eleven_two_voice.wav` -- every inter-turn
            // pause removed, so every speaker change became a zero-silence splice -- turn accuracy
            // was unchanged at 27/36 and the block count went up rather than down, which is the
            // opposite of the merging that `minDurationOff` losing its silence cue would cause. See
            // `docs/diarization-benchmark.md`.
            val compacted = compactToSpeech(samples)

            // The two halves run concurrently. Nothing flows between them -- diarisation makes
            // turns, recognition makes words, and they meet only at SpeakerAlignment -- so the wall
            // clock is the longer branch rather than their sum.
            //
            // This is not free, and the ceiling is lower than it looks. Both are CPU-saturating ONNX
            // inference asking for recommendedThreadCount() threads each, so on an eight-core device
            // the two together want all eight with nothing left over. The gain is bounded by spare
            // cores, not by the work: measured sequentially at 44.4s of diarisation against 20.0s of
            // recognition, the best case is the 20 seconds, not half the total.
            //
            // Peak memory is the cost. Both model sets are resident at once -- roughly 46 MB of
            // segmentation and embedding alongside the recogniser -- on top of the whole recording
            // as floats. If that ever has to be given back, this is the first thing to undo.
            //
            // A count the user supplied is evidence about this recording and is independent of which
            // voices happen to be enrolled. The old branch discarded that evidence whenever *any*
            // enrolment existed, even if the other people in this recording were strangers. Naming
            // can merge duplicate clusters, but it cannot split two real voices already merged into
            // one, so the explicit count must reach sherpa unchanged.
            // Read before the branches start, because it decides whether diarisation may be split
            // into chunks and both branches then run concurrently. A count of zero is not a
            // failure: it means nothing could be named, so the recording has to be clustered whole.
            val enrolledSpeakers = speakers.enrolledCount()

            // One budget rather than two independent guesses. Both branches used to size their own
            // pool from availableProcessors() and cap it at 4, which on an eight-core device asked
            // for eight ONNX threads plus the dispatcher's own pool -- measured at 590-615% of
            // 800%, thirteen threads at 7-93%, none of them saturating. See [ThreadBudget].
            //
            // The split leans toward whichever branch is the long pole, and that depends on both
            // models -- the embedder decides the diarisation cost, the recogniser the transcription
            // cost -- so each contributes its own weight. This used to key only on the embedder and
            // silently assumed Parakeet: with FastConformer's ~39s transcription against a ~63s
            // diarisation branch, the odd thread now belongs to diarisation, which the old presets
            // could not express.
            // Sized by the fast cores, not the raw count: a big.LITTLE chip with only a couple of
            // performance cores must not spill ONNX threads onto its slow companions. See
            // [ThreadBudget.concurrent].
            val fastCores = ThreadBudget.detectFastCores()
            val threads = ThreadBudget.concurrent(
                weights = ThreadBudget.Weights(
                    diarise = bundle.diariseWeight,
                    transcribe = model.transcribeWeight,
                ),
                fastCores = fastCores,
            )
            Log.i(
                TAG,
                "thread budget: diarise ${threads.diarise}, transcribe ${threads.transcribe} " +
                    "(${bundle.id} / ${model.id}, $fastCores fast cores)",
            )

            val wallStarted = System.currentTimeMillis()
            val (diarised, transcribed) = coroutineScope {
                val diarisation = async(Dispatchers.Default) {
                    // Clustering and folding both compare turns against each other, so their cost
                    // grows faster than the recording does. Chunking bounds that to whatever fits
                    // in one chunk -- but only naming can tell the chunks apart afterwards, so this
                    // is allowed only when there is somebody to name. See [DiarizationChunks].
                    val chunks = if (enrolledSpeakers > 0) {
                        DiarizationChunks.plan(
                            totalSamples = compacted.samples.size,
                            sampleRate = AudioRecorder.SAMPLE_RATE,
                            spliceBoundaries = compacted.pieces.drop(1).map { it.compactedStart },
                        )
                    } else {
                        listOf(DiarizationChunk(0, compacted.samples.size))
                    }

                    // How many chunks to diarise at once. sherpa's process() is one unbatched ONNX
                    // run per window, so a single chunk on all the diarise threads leaves the machine
                    // underused -- XNNPACK moved a 20-minute diarisation by only 3%, which is the
                    // tell. Splitting the threads across lanes that each own a chunk keeps more cores
                    // busy, at the cost of one resident model set per extra lane. Never more chunks
                    // than there are, never more than the thread budget can halve, never more than
                    // the memory cap.
                    val lanes = if (chunks.size > 1) {
                        minOf(MAX_DIARIZE_LANES, chunks.size, threads.diarise)
                    } else {
                        1
                    }
                    // The provider every sherpa-onnx model on this side of the run takes, from
                    // Settings: segmentation and embedding here, the VAD in compactToSpeech, the
                    // naming embedder in SpeakerRepository, and the recogniser on the other branch.
                    val provider = settings.settings.value.onnxProvider.slug

                    // The parallelisable half feeds the sequential half through a channel, chunk by
                    // chunk, instead of being awaited whole first. Model load is inside
                    // diarizeChunks, and counted, because with lanes it happens concurrently and is
                    // genuinely part of the branch's wall clock.
                    //
                    // Fold-and-name must still run in chunk order -- the placeholder counter numbers
                    // strangers by first appearance, which is only "across the recording" if chunks
                    // are folded in the order they were spoken -- but that is a constraint on the
                    // order of consumption, not a reason to wait for everything. The old
                    // awaitAll-then-fold shape left 9.8 measured seconds of folding sitting entirely
                    // after 45 seconds of diarisation it could have hidden behind. [InOrderChunks]
                    // re-sequences the lanes' out-of-order completions so each chunk is folded the
                    // moment it and its predecessors exist, while later chunks are still on the
                    // lanes. The folds contend with those lanes for CPU, which is accepted: a fold
                    // is a short burst, and by the tail of a run a lane is usually already idle.
                    var nextCluster = 0
                    var placeholder = 0
                    var rawTurnCount = 0
                    var diariseMillis = 0L
                    var foldMillis = 0L
                    val turns = mutableListOf<DiarizedSegment>()
                    val names = mutableMapOf<Int, String>()

                    val diariseStarted = System.currentTimeMillis()
                    coroutineScope {
                        val diarisedChunks =
                            Channel<Pair<Int, List<DiarizedSegment>>>(Channel.UNLIMITED)
                        launch {
                            try {
                                diarizeChunks(
                                    chunks = chunks,
                                    compacted = compacted,
                                    segmentationModel =
                                        audioModels.fileFor(bundle, AudioModelCatalog.SEGMENTATION),
                                    embeddingModel =
                                        audioModels.fileFor(bundle, AudioModelCatalog.EMBEDDING),
                                    expectedSpeakers = recording.expectedSpeakers,
                                    diariseThreads = threads.diarise,
                                    provider = provider,
                                    lanes = lanes,
                                ) { index, chunkTurns -> diarisedChunks.send(index to chunkTurns) }
                            } finally {
                                // In the finally so a lane failure still closes the channel: the
                                // consumer below sits in a `for` over it and would otherwise wait
                                // for a producer that is never coming back.
                                diariseMillis = System.currentTimeMillis() - diariseStarted
                                diarisedChunks.close()
                            }
                        }

                        val inOrder = InOrderChunks<List<DiarizedSegment>>()
                        for ((index, arrived) in diarisedChunks) {
                            for (inCompacted in inOrder.offer(index, arrived)) {
                                ensureActive()
                                rawTurnCount += inCompacted.size

                                // Fold fragments and name the survivors in one pass over one set of
                                // voiceprints -- naming no longer re-embeds what folding already
                                // did. Per chunk, and naming among them: matching each cluster to an
                                // enrolled voice is independent of the other chunks, and the
                                // placeholder counter is threaded through so the "Unknown Speaker N"
                                // numbering stays global. See [SpeakerRepository.foldAndName].
                                val foldStarted = System.currentTimeMillis()
                                val attribution =
                                    speakers.foldAndName(compacted.samples, inCompacted, placeholder)
                                foldMillis += System.currentTimeMillis() - foldStarted
                                placeholder = attribution.nextPlaceholder

                                // Namespace the folded turns and their names by the same offset:
                                // sherpa numbers clusters from zero in every chunk, so without this
                                // the second chunk's cluster 0 would silently merge with the
                                // first's.
                                val base = nextCluster
                                val (namespaced, next) =
                                    DiarizationChunks.namespaced(attribution.turns, base)
                                attribution.names.forEach { (cluster, name) ->
                                    names[cluster + base] = name
                                }
                                nextCluster = next
                                turns += namespaced
                            }
                        }
                    }
                    // Only the folds the overlap could not hide -- the ones that ran after the last
                    // chunk was diarised. Kept as "attribute" so the two numbers still sum to the
                    // branch's wall clock, which is what the run row stores.
                    val attributeMillis =
                        (System.currentTimeMillis() - diariseStarted - diariseMillis)
                            .coerceAtLeast(0)

                    Log.i(
                        TAG,
                        "diarisation over ${chunks.size} chunk(s) in $lanes lane(s) found " +
                            "$rawTurnCount turns, " +
                            "${turns.map { it.cluster }.distinct().size} clusters after folding " +
                            "fragments, ${names.values.distinct().size} distinct names",
                    )
                    // Everything above ran in compacted coordinates -- clustering, folding and
                    // naming all read audio, so they must read the array they were measured on.
                    // Only here, where turns stop being audio and start being a timeline the
                    // transcript is joined against, do they become recording coordinates again.
                    Diarised(
                        expandToRecording(compacted, turns),
                        names,
                        diariseMillis,
                        attributeMillis,
                        foldMillis,
                    )
                }

                // Recognition owns the progress bar outright now. Diarisation cannot report any --
                // see [SpeakerDiarizer.diarize] for why asking it to takes the process down -- and
                // with the two running together there is no longer a diarisation phase for the row
                // to sit still through.
                val transcription = async(Dispatchers.Default) {
                    val started = System.currentTimeMillis()

                    // A second decoding lane when there are threads to split and memory for a
                    // second resident model. sherpa has no batch decode, so a second instance is
                    // the only way this branch can put more than one core on a slice -- see
                    // [SpeechRecognizer.transcribeSegments]. The thread budget is unchanged; like
                    // the diarise lanes, only its division is.
                    val transcribeLanes =
                        if (threads.transcribe >= 2 && roomForSecondRecognizer()) 2 else 1
                    if (transcribeLanes == 1 && threads.transcribe >= 2) {
                        Log.i(TAG, "second transcribe lane refused: not enough free memory")
                    }
                    val laneThreads = ThreadBudget.share(threads.transcribe, transcribeLanes)

                    // Thread count as well as model id: a session built for a different share
                    // keeps it until it is rebuilt, so without this the budget would apply only to
                    // the first run after a cold start. The resident recogniser is lane 0, so it
                    // is sized to the lane's share, not the branch total.
                    if (recognizer.loadedModelId != model.id ||
                        recognizer.loadedThreadCount != laneThreads[0]
                    ) {
                        recognizer.load(speechModels.selectedPaths(), threadCount = laneThreads[0])
                    }
                    val bounds = recognizer.segmentBounds(samples)
                    val pieces = recognizer.transcribeSegments(
                        samples,
                        bounds,
                        extraLaneThreads = laneThreads.getOrNull(1),
                    ) { done, total, _ ->
                        dao.updateProgress(id, 0.05f + (done.toFloat() / total) * 0.9f)
                    }
                    Transcribed(
                        pieces.flatMap { it.words },
                        System.currentTimeMillis() - started,
                    )
                }

                diarisation.await() to transcription.await()
            }

            val turns = diarised.turns
            val names = diarised.names
            val words = transcribed.words
            val diariseMillis = diarised.diariseMillis
            val attributeMillis = diarised.attributeMillis
            val foldMillis = diarised.foldMillis
            val transcribeMillis = transcribed.millis

            val blocks = SpeakerAlignment.blocks(words, turns, AudioRecorder.SAMPLE_RATE)

            // Timed per phase because the two halves of this pipeline are data-independent -- nothing
            // flows between diarisation and recognition until SpeakerAlignment joins them -- so
            // whether running them concurrently is worth the peak memory is decided entirely by
            // which one is the long pole. That is a per-device answer, so the numbers have to come
            // from the device rather than from a guess.
            //
            // Folding and naming are one number: they share a single embedding pass, so timing
            // them apart would split a cost that is no longer divisible. And since the folds run
            // while later chunks are still being diarised, "attribute" is only the tail that ran
            // after the last chunk -- the part the wall clock actually paid for -- with the full
            // fold cost and how much of it was hidden reported alongside.
            Log.i(
                TAG,
                ("phases over %.1fs of audio: diarise %.1fs + attribute %.1fs = %.1fs " +
                    "(folding %.1fs, %.1fs hidden behind diarisation) " +
                    "|| transcribe %.1fs -> wall %.1fs (sequential would be %.1fs)").format(
                    samples.size / AudioRecorder.SAMPLE_RATE.toFloat(),
                    diariseMillis / 1000f,
                    attributeMillis / 1000f,
                    (diariseMillis + attributeMillis) / 1000f,
                    foldMillis / 1000f,
                    (foldMillis - attributeMillis).coerceAtLeast(0) / 1000f,
                    transcribeMillis / 1000f,
                    (System.currentTimeMillis() - wallStarted) / 1000f,
                    (diariseMillis + foldMillis + transcribeMillis) / 1000f,
                ),
            )

            // One person is routinely several clusters, all correctly given the same name, and a
            // block cut at every hop between them splits their sentence across duplicate labels.
            // See [nameBlocks].
            val named = smoothShortBlocks(
                nameBlocks(blocks, names, "${SpeakerRepository.UNKNOWN_SPEAKER_PREFIX} ?"),
                unknownPrefix = SpeakerRepository.UNKNOWN_SPEAKER_PREFIX,
                minSamples = SHORT_BLOCK_SECONDS * AudioRecorder.SAMPLE_RATE,
            )
            Log.i(TAG, "transcript: ${blocks.size} aligned blocks became ${named.size} after naming")

            val rows = named.map { block ->
                DiarizedBlock(
                    recordingId = id,
                    startSample = block.startSample,
                    endSample = block.endSample,
                    cluster = block.cluster,
                    speakerName = block.name,
                    text = block.text,
                )
            }
            dao.deleteBlocksFor(id)
            dao.insertBlocks(rows)

            // Re-read rather than scoring against the row fetched at the top of doWork. A reference
            // can be attached while a run is in flight -- it is a text field on a screen the user is
            // looking at, and this job takes minutes -- and the stale row would score against a
            // reference that is no longer the one attached, or against none at all.
            val current = dao.byId(id) ?: recording
            val score = current.referenceText
                ?.let { DiarizationScore.of(it, current.language, rows) }

            dao.finishRun(
                id = id,
                // The whole run, including reading the WAV -- not `wallStarted`, which begins after
                // it. That read is minutes of the user's wait on a long recording, and a number
                // that quietly leaves out a phase is worse than none.
                runMillis = System.currentTimeMillis() - runStarted,
                // The whole "who spoke" branch, not just sherpa's part: folding and naming are as
                // much a cost of answering that question as the clustering is, and splitting them
                // on a list row would say less than one honest number does.
                diariseMillis = diariseMillis + attributeMillis,
                transcribeMillis = transcribeMillis,
                coveragePercent = score?.coveragePercent,
                werPercent = score?.werPercent,
                speakerAccuracyPercent = score?.speakerAccuracyPercent,
            )

            // No transcript is a result, not a crash: an empty recording or one the models heard
            // nothing in should say so on its row.
            if (blocks.isEmpty()) {
                dao.fail(id, "Nothing was recognised in this recording.")
            }
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "diarisation run $id failed", e)
            dao.fail(id, e.message ?: "The run failed.")
            Result.success()
        } finally {
            // Each diarisation lane releases its own diarizer in [diarizeChunks]; there is no
            // longer a run-scoped one to release here.
            //
            // Work replacement and deletion cancel this coroutine. Native cleanup still has to wait
            // for any concurrent enrolment operation and run to completion; abandoning it can retain
            // the model indefinitely or tempt a later unsynchronised release.
            withContext(NonCancellable) { speakers.release() }
        }
    }

    /**
     * Removes the silence before diarisation sees the recording.
     *
     * The whole recording has to be in memory at once, because clustering compares every stretch
     * against every other and cannot be done a piece at a time. At 16 kHz that is 3.8 MB a minute:
     * 77 MB for the twenty-minute recordings this screen was measured on, 228 MB for an hour-long
     * meeting, and a kill for two hours. The comment on the array read above names sherpa's
     * streaming diarisation API as the way past that ceiling. sherpa-onnx has no such API, so this
     * is the way past it.
     *
     * Compacting rather than diarising piecewise is what keeps the clustering global: it still sees
     * all of the speech at once, with only the gaps between it gone.
     *
     * **Every failure here falls back to the whole recording.** A detector that will not load, one
     * that throws, and one that honestly found nothing are three different events with one right
     * answer, and [SpeechRegions.resolve] deliberately returns the same `null` for all three so a
     * caller cannot mishandle them differently. Speech mistaken for silence is never transcribed
     * and leaves no gap to notice, which is why [SettingsStore.vadEnabled] can turn this off
     * outright -- the same switch, for the same reason, as the note recorder's.
     */
    private suspend fun compactToSpeech(samples: FloatArray): CompactedAudio {
        if (!settings.settings.value.vadEnabled) return CompactedAudio.untouched(samples)

        val detector = SpeechActivityDetector(context.assets)
        val detected = try {
            detector.load(
                // No slice cap applies here -- diarisation has no per-clip limit, unlike the
                // transcribers. A forced close only ever splits one region into two touching ones,
                // which CompactedAudio merges straight back, so the value cannot change the output.
                maxSpeechSamples = MAX_SPEECH_SAMPLES,
                // The same provider as SpeakerDiarizer and the naming embedder, from Settings. The
                // detector runs inside the diarisation branch and feeds the models it splits audio
                // for, so running it on a different provider would make a run's timings describe two
                // configurations at once. Kept together, they describe one.
                provider = settings.settings.value.onnxProvider.slug,
            )
            detector.detect(samples.size) { from, until -> samples.copyOfRange(from, until) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "voice-activity detection unavailable; diarising the whole recording", e)
            return CompactedAudio.untouched(samples)
        } finally {
            detector.release()
        }

        val compacted = CompactedAudio.of(
            samples,
            SpeechRegions.resolve(detected = detected, totalSamples = samples.size).orEmpty(),
        )
        Log.i(
            TAG,
            "compaction: %.1fs of audio -> %.1fs of speech in %d regions (%.1f%% removed)".format(
                samples.size / AudioRecorder.SAMPLE_RATE.toFloat(),
                compacted.samples.size / AudioRecorder.SAMPLE_RATE.toFloat(),
                compacted.pieces.size,
                compacted.removedFraction * 100,
            ),
        )
        return compacted
    }

    /**
     * Diarises every chunk, running [lanes] of them at once, handing each chunk's turns to [emit]
     * in compacted coordinates as it finishes.
     *
     * **Why lanes.** sherpa's `process()` is one ONNX run per segmentation window and one per
     * (window, speaker) embedding, none of it batched, so a single chunk given the whole diarise
     * budget cannot keep the cores fed -- the per-window overhead stalls them. Two chunks on half
     * the threads each run two of those streams side by side, so one lane's stalls are the other
     * lane's work. The total thread count is unchanged; only its division is.
     *
     * **The cost.** One [SpeakerDiarizer] per lane, because a single instance cannot `process()` two
     * clips at once, and each carries its own copy of the segmentation and embedding models -- so a
     * second lane is a second resident model set. The caller caps [lanes] to keep that bounded and
     * only pays it when there is more than one chunk to spread.
     *
     * **[emit] is called in completion order, not chunk order.** This used to await every lane and
     * sort, which held the first chunk hostage to the last: nothing downstream could start until the
     * whole recording was diarised. Emitting on completion is what lets the caller fold early chunks
     * behind the diarisation of later ones; the caller re-sequences with [InOrderChunks], since the
     * fold pass genuinely needs chunk order. [emit] may be called from several lanes concurrently --
     * a channel send is safe, mutable accumulation is not. Lanes are assigned round-robin so they
     * finish close together when chunk sizes vary.
     */
    private suspend fun diarizeChunks(
        chunks: List<DiarizationChunk>,
        compacted: CompactedAudio,
        segmentationModel: File,
        embeddingModel: File,
        expectedSpeakers: Int,
        diariseThreads: Int,
        provider: String,
        lanes: Int,
        emit: suspend (index: Int, turns: List<DiarizedSegment>) -> Unit,
    ) {
        fun sliceFor(chunk: DiarizationChunk): FloatArray =
            // One chunk is the whole recording, and copying it to say so would double peak memory on
            // the largest recordings for nothing.
            if (chunks.size == 1) {
                compacted.samples
            } else {
                compacted.samples.copyOfRange(chunk.startSample, chunk.endSample)
            }

        fun newDiarizer(threads: Int) = SpeakerDiarizer().apply {
            load(
                segmentationModel = segmentationModel,
                embeddingModel = embeddingModel,
                expectedSpeakers = expectedSpeakers,
                threadCount = threads,
                provider = provider,
            )
        }

        // One diarizer, sequential: the whole-recording case, and any case where the budget cannot
        // give each lane a thread. `ensureActive` between chunks is the only place a long
        // diarisation can be stopped -- process() itself is an uninterruptible native call.
        if (lanes <= 1) {
            val diarizer = newDiarizer(diariseThreads)
            try {
                chunks.forEachIndexed { index, chunk ->
                    currentCoroutineContext().ensureActive()
                    emit(index, DiarizationChunks.toCompacted(diarizer.diarize(sliceFor(chunk)), chunk))
                }
            } finally {
                diarizer.release()
            }
            return
        }

        // Several lanes, each its own diarizer over a round-robin share of the chunks, each on its
        // share of the threads, each emitting its chunks the moment they are done.
        val laneThreads = ThreadBudget.share(diariseThreads, lanes)
        coroutineScope {
            (0 until lanes).forEach { lane ->
                launch(Dispatchers.Default) {
                    val laneChunks = chunks.withIndex().filter { it.index % lanes == lane }
                    val diarizer = newDiarizer(laneThreads[lane])
                    try {
                        laneChunks.forEach { (index, chunk) ->
                            currentCoroutineContext().ensureActive()
                            emit(
                                index,
                                DiarizationChunks.toCompacted(diarizer.diarize(sliceFor(chunk)), chunk),
                            )
                        }
                    } finally {
                        diarizer.release()
                    }
                }
            }
        }
    }

    /**
     * Puts turns back into recording coordinates, splitting any that crossed a splice.
     *
     * A turn spanning removed silence is two separated stretches in the recording, and reporting it
     * as one would claim the speaker held the floor through audio dropped for having no speech in
     * it. Split, the removed silence is covered by no turn -- which is the state
     * [SpeakerAlignment] already handles, filling a gap only when the turns either side agree.
     */
    private fun expandToRecording(
        compacted: CompactedAudio,
        turns: List<DiarizedSegment>,
    ): List<DiarizedSegment> = turns
        .flatMap { turn ->
            compacted.toOriginal(turn.startSample until turn.endSample)
                .map { range -> turn.copy(startSample = range.first, endSample = range.last + 1) }
        }
        .sortedBy { it.startSample }

    /** Runs [block] having first moved the bar, so a slow step does not start at a dead zero. */
    private suspend fun <T> withProgress(id: Long, to: Float, block: () -> T): T {
        dao.updateProgress(id, to)
        return block()
    }

    /**
     * Whether a second transcribe recogniser fits in memory right now.
     *
     * The gate is deliberately blunt -- free memory above the system's own low-memory threshold --
     * and deliberately generous, because what it guards against is not a slow run but the
     * low-memory killer taking the process and the run with it. Refusing the lane costs seconds;
     * being killed costs the whole recording's work.
     */
    private fun roomForSecondRecognizer(): Boolean {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return false
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        return info.availMem - info.threshold >= SECOND_RECOGNIZER_HEADROOM_BYTES
    }

    private fun foregroundInfo(runId: Long, progress: Float): ForegroundInfo {
        val channelId = ensureChannel()
        val percent = (progress * 100).toInt().coerceIn(0, 100)
        val notifId = NOTIF_BASE + (runId.hashCode().absoluteValue % 1_000)

        val notification = NotificationCompat.Builder(context, channelId)
            .setContentTitle("Working out who is speaking")
            .setContentText(if (progress > 0f) "$percent%" else "Starting…")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setProgress(100, percent, /* indeterminate = */ progress <= 0f)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

        return if (Build.VERSION.SDK_INT >= 34) {
            ForegroundInfo(notifId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notifId, notification)
        }
    }

    private fun ensureChannel(): String {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager?.getNotificationChannel(CHANNEL_ID) == null) {
            manager?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Speaker transcripts", NotificationManager.IMPORTANCE_LOW),
            )
        }
        return CHANNEL_ID
    }

    companion object {
        /**
         * Below this, a block is a fragment of somebody's sentence rather than a turn of speech.
         *
         * Two seconds is where the measurements put the boundary: turns the embedding model commits
         * to average four seconds, and the ones it declines average 1.2. Under two seconds there is
         * not enough voice for an attribution to be evidence, so the speech around the fragment is
         * better evidence than the fragment itself. See [smoothShortBlocks].
         */
        private const val SHORT_BLOCK_SECONDS = 2

        /**
         * How long a single detected speech region may run before the detector closes it.
         *
         * Generous because nothing downstream cares: a forced close produces two touching regions
         * and [CompactedAudio.of] merges touching regions back into one piece.
         */
        private const val MAX_SPEECH_SAMPLES = 5 * 60 * AudioRecorder.SAMPLE_RATE

        /**
         * How many chunks may be diarised at once. The working cap is the thread budget -- lanes
         * never exceed [ThreadBudget.Split.diarise], so a two-fast-core device still runs two --
         * and this constant only bounds the memory: each lane is another resident copy of the
         * segmentation and embedding models (~46 MB).
         *
         * Raised from 2 because the single-lane tell repeats one level up: sherpa's `process()`
         * cannot keep even a couple of threads fed (XNNPACK moved a 20-minute diarisation by only
         * 3%, and splitting into two 2-thread lanes was worth ~15%), so lanes of one thread each
         * are the shape that gives every budgeted core its own unbatched stream to own. Four rather
         * than unbounded because ~184 MB of lane models is where the memory bill stops being
         * incidental on the devices this app targets. See [diarizeChunks].
         */
        private const val MAX_DIARIZE_LANES = 4

        /**
         * Free memory required, beyond the low-memory threshold, before transcription spins up its
         * second recogniser. Sized for the largest model offered (Whisper Small is roughly 750 MB
         * resident) plus margin, not for the usual case: the gate cannot know a model's resident
         * size without loading it, and erring small here is how a run gets killed instead of
         * slowed. See [roomForSecondRecognizer].
         */
        private const val SECOND_RECOGNIZER_HEADROOM_BYTES = 1_500L * 1024 * 1024

        private const val TAG = "DiarizeWorker"
        private const val CHANNEL_ID = "speaker_diarization"

        /** Distinct from the other workers' bases (4200, 5300, 6400, 7500, 8600). */
        private const val NOTIF_BASE = 9700

        internal const val KEY_RECORDING_ID = "recordingId"

        fun uniqueName(id: Long) = "diarize:$id"

        fun enqueue(context: Context, id: Long) {
            val request = OneTimeWorkRequestBuilder<DiarizeWorker>()
                .setInputData(workDataOf(KEY_RECORDING_ID to id))
                .addTag(uniqueName(id))
                .build()

            // REPLACE, not KEEP. Every run here is a deliberate tap, and KEEP answers a second tap
            // by silently doing nothing if WorkManager still holds *any* record under this name --
            // which it does after a process death, an app update, or a cancelled job. The symptom
            // is a row that sits on a progress bar and a Run button that appears to do nothing,
            // with not one line in the log to say why. Asking again should mean "do it now".
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(uniqueName(id), ExistingWorkPolicy.REPLACE, request)
        }

        /** Stops a run, for the one case there is: the recording under it is being deleted. */
        fun cancel(context: Context, id: Long) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(uniqueName(id))
        }

        /**
         * Marks Running rows whose job no longer exists as failed.
         *
         * The same reasoning as the benchmark's reconcile and deliberately without its re-enqueue:
         * these runs are started deliberately and cost minutes of the device's attention, so one
         * restarting itself hours later would be work nobody asked for.
         */
        suspend fun reconcile(context: Context, dao: DiarizedDao) {
            val stuck = dao.running()
            if (stuck.isEmpty()) return

            val workManager = WorkManager.getInstance(context.applicationContext)
            for (row in stuck) {
                // The Flow variant, not `getWorkInfosForUniqueWork(...).get()`. The blocking one
                // was called from `viewModelScope`, which is the main thread: it either stalls the
                // UI or comes back wrong, and the row it was meant to rescue stays on a progress
                // bar forever. The benchmark's reconcile already used the suspend form; this one
                // did not, and a recording sat at 5% with no worker behind it until it was noticed.
                val live = runCatching {
                    workManager.getWorkInfosForUniqueWorkFlow(uniqueName(row.id)).first()
                        .any { info -> !info.state.isFinished }
                }.getOrDefault(false)

                if (!live) dao.fail(row.id, "The run was interrupted.")
            }
        }
    }
}

/** What the diarisation branch produced, so it can be awaited as one value. */
private data class Diarised(
    val turns: List<DiarizedSegment>,
    val names: Map<Int, String>,
    val diariseMillis: Long,
    /**
     * The fold-and-name tail that ran after the last chunk was diarised -- the only part of
     * attribution the wall clock still pays for now that folding overlaps diarisation. Sums with
     * [diariseMillis] to the branch's wall clock, which is what the run row stores.
     */
    val attributeMillis: Long,
    /** All the fold-and-name work wherever it ran; the log reports how much of it was hidden. */
    val foldMillis: Long,
)

/** What the recognition branch produced. */
private data class Transcribed(
    val words: List<TimedWord>,
    val millis: Long,
)
