package com.example.aiagenttestapp.data.speakers

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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
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

        val diarizer = SpeakerDiarizer()
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
            // Which branch is the long pole depends on the embedding model, so the split does
            // too: CAM++ compares voices about 2.8x faster and hands the lead to transcription.
            val threads = ThreadBudget.concurrent(
                weights = if (bundle.id == AudioModelCatalog.SPEAKER_CAMPP_BUNDLE_ID) {
                    ThreadBudget.Weights.FAST_EMBEDDER
                } else {
                    ThreadBudget.Weights.SLOW_EMBEDDER
                },
            )
            Log.i(
                TAG,
                "thread budget: diarise ${threads.diarise}, transcribe ${threads.transcribe} " +
                    "(${bundle.id})",
            )

            val wallStarted = System.currentTimeMillis()
            val (diarised, transcribed) = coroutineScope {
                val diarisation = async(Dispatchers.Default) {
                    diarizer.load(
                        segmentationModel = audioModels.fileFor(bundle, AudioModelCatalog.SEGMENTATION),
                        embeddingModel = audioModels.fileFor(bundle, AudioModelCatalog.EMBEDDING),
                        expectedSpeakers = recording.expectedSpeakers,
                        threadCount = threads.diarise,
                        // The whole sherpa-onnx side of a run takes the one provider Settings names:
                        // segmentation and embedding here, the VAD in compactToSpeech, the naming
                        // embedder in SpeakerRepository, and the recogniser on the other branch.
                        provider = settings.settings.value.onnxProvider.slug,
                    )

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

                    var diariseMillis = 0L
                    var attributeMillis = 0L
                    var nextCluster = 0
                    var placeholder = 0
                    var rawTurnCount = 0
                    val turns = mutableListOf<DiarizedSegment>()
                    val names = mutableMapOf<Int, String>()

                    for (chunk in chunks) {
                        // The only place a long diarisation can be stopped. sherpa's process() is
                        // one uninterruptible native call -- 267 seconds of it on a twenty-minute
                        // recording -- so without chunks a cancelled run has to be waited out.
                        ensureActive()

                        // One chunk is the whole recording, and copying it to say so would double
                        // peak memory on the largest recordings for nothing.
                        val slice = if (chunks.size == 1) {
                            compacted.samples
                        } else {
                            compacted.samples.copyOfRange(chunk.startSample, chunk.endSample)
                        }

                        val diariseStarted = System.currentTimeMillis()
                        val local = diarizer.diarize(slice)
                        diariseMillis += System.currentTimeMillis() - diariseStarted
                        rawTurnCount += local.size

                        // Back into compacted coordinates before anything reads audio by these
                        // ranges: `slice` starts at zero, `compacted.samples` does not.
                        val inCompacted = DiarizationChunks.toCompacted(local, chunk)

                        // Fold fragments and name the survivors in one pass over one set of
                        // voiceprints -- naming no longer re-embeds what folding already did. Per
                        // chunk, and naming among them: matching each cluster to an enrolled voice
                        // is independent of the other chunks, and the placeholder counter is threaded
                        // through so the "Unknown Speaker N" numbering stays global. See
                        // [SpeakerRepository.foldAndName].
                        val attributeStarted = System.currentTimeMillis()
                        val attribution =
                            speakers.foldAndName(compacted.samples, inCompacted, placeholder)
                        attributeMillis += System.currentTimeMillis() - attributeStarted
                        placeholder = attribution.nextPlaceholder

                        // Namespace the folded turns and their names by the same offset: sherpa
                        // numbers clusters from zero in every chunk, so without this the second
                        // chunk's cluster 0 would silently merge with the first's.
                        val base = nextCluster
                        val (namespaced, next) = DiarizationChunks.namespaced(attribution.turns, base)
                        attribution.names.forEach { (cluster, name) -> names[cluster + base] = name }
                        nextCluster = next
                        turns += namespaced
                    }
                    diarizer.release()

                    Log.i(
                        TAG,
                        "diarisation over ${chunks.size} chunk(s) found $rawTurnCount turns, " +
                            "${turns.map { it.cluster }.distinct().size} clusters after folding " +
                            "fragments, ${names.values.distinct().size} distinct names",
                    )
                    // Everything above ran in compacted coordinates -- clustering, folding and
                    // naming all read audio, so they must read the array they were measured on.
                    // Only here, where turns stop being audio and start being a timeline the
                    // transcript is joined against, do they become recording coordinates again.
                    Diarised(expandToRecording(compacted, turns), names, diariseMillis, attributeMillis)
                }

                // Recognition owns the progress bar outright now. Diarisation cannot report any --
                // see [SpeakerDiarizer.diarize] for why asking it to takes the process down -- and
                // with the two running together there is no longer a diarisation phase for the row
                // to sit still through.
                val transcription = async(Dispatchers.Default) {
                    val started = System.currentTimeMillis()
                    // Thread count as well as model id: a session built for a different share
                    // keeps it until it is rebuilt, so without this the budget would apply only to
                    // the first run after a cold start.
                    if (recognizer.loadedModelId != model.id ||
                        recognizer.loadedThreadCount != threads.transcribe
                    ) {
                        recognizer.load(speechModels.selectedPaths(), threadCount = threads.transcribe)
                    }
                    val bounds = recognizer.segmentBounds(samples)
                    val pieces = recognizer.transcribeSegments(samples, bounds) { done, total, _ ->
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
            val transcribeMillis = transcribed.millis

            val blocks = SpeakerAlignment.blocks(words, turns, AudioRecorder.SAMPLE_RATE)

            // Timed per phase because the two halves of this pipeline are data-independent -- nothing
            // flows between diarisation and recognition until SpeakerAlignment joins them -- so
            // whether running them concurrently is worth the peak memory is decided entirely by
            // which one is the long pole. That is a per-device answer, so the numbers have to come
            // from the device rather than from a guess.
            //
            // Folding and naming are one number now: they share a single embedding pass, so timing
            // them apart would split a cost that is no longer divisible. "attribute" is that shared
            // fold-and-name.
            Log.i(
                TAG,
                ("phases over %.1fs of audio: diarise %.1fs + attribute %.1fs = %.1fs " +
                    "|| transcribe %.1fs -> wall %.1fs (sequential would be %.1fs)").format(
                    samples.size / AudioRecorder.SAMPLE_RATE.toFloat(),
                    diariseMillis / 1000f,
                    attributeMillis / 1000f,
                    (diariseMillis + attributeMillis) / 1000f,
                    transcribeMillis / 1000f,
                    (System.currentTimeMillis() - wallStarted) / 1000f,
                    (diariseMillis + attributeMillis + transcribeMillis) / 1000f,
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
            diarizer.release()
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
    /** Folding and naming together: they share one embedding pass, so they share one number. */
    val attributeMillis: Long,
)

/** What the recognition branch produced. */
private data class Transcribed(
    val words: List<TimedWord>,
    val millis: Long,
)
