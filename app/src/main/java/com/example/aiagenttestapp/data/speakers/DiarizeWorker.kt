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
import com.example.aiagenttestapp.stt.TimedWord
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
 * The order matters and is not interchangeable. Diarisation runs **first and over the whole
 * recording**, because clustering is a global judgement -- it decides how many voices exist by
 * comparing every stretch against every other, and cannot be done a slice at a time without
 * inventing a new speaker per slice. Transcription then runs on its own slicing, chosen for what
 * decodes best, and the two are reconciled afterwards by [SpeakerAlignment].
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
                "${model.label} reports no word timings. Choose Parakeet v3 or Whisper Small."
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
            val wallStarted = System.currentTimeMillis()
            val (diarised, transcribed) = coroutineScope {
                val diarisation = async(Dispatchers.Default) {
                    diarizer.load(
                        segmentationModel = audioModels.fileFor(bundle, AudioModelCatalog.SEGMENTATION),
                        embeddingModel = audioModels.fileFor(bundle, AudioModelCatalog.EMBEDDING),
                        expectedSpeakers = recording.expectedSpeakers,
                    )

                    val diariseStarted = System.currentTimeMillis()
                    val rawTurns = diarizer.diarize(compacted.samples)
                    diarizer.release()
                    val diariseMillis = System.currentTimeMillis() - diariseStarted

                    // The step sherpa's clustering leaves out. Fragments too short to be a speaker
                    // are folded into whichever cluster they sound like, before anything downstream
                    // inherits their ids -- see [SpeakerRepository.mergeSmallClusters].
                    val foldStarted = System.currentTimeMillis()
                    val turns = speakers.mergeSmallClusters(compacted.samples, rawTurns)
                    val foldMillis = System.currentTimeMillis() - foldStarted

                    val nameStarted = System.currentTimeMillis()
                    val names = speakers.labelClusters(compacted.samples, turns)
                    val nameMillis = System.currentTimeMillis() - nameStarted

                    Log.i(
                        TAG,
                        "diarisation found ${rawTurns.map { it.cluster }.distinct().size} clusters " +
                            "in ${rawTurns.size} turns, " +
                            "${turns.map { it.cluster }.distinct().size} after folding fragments",
                    )
                    // Everything above ran in compacted coordinates -- clustering, folding and
                    // naming all read audio, so they must read the array they were measured on.
                    // Only here, where turns stop being audio and start being a timeline the
                    // transcript is joined against, do they become recording coordinates again.
                    Diarised(expandToRecording(compacted, turns), names, diariseMillis, foldMillis, nameMillis)
                }

                // Recognition owns the progress bar outright now. Diarisation cannot report any --
                // see [SpeakerDiarizer.diarize] for why asking it to takes the process down -- and
                // with the two running together there is no longer a diarisation phase for the row
                // to sit still through.
                val transcription = async(Dispatchers.Default) {
                    val started = System.currentTimeMillis()
                    if (recognizer.loadedModelId != model.id) {
                        recognizer.load(speechModels.selectedPaths())
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
            val foldMillis = diarised.foldMillis
            val nameMillis = diarised.nameMillis
            val transcribeMillis = transcribed.millis

            val blocks = SpeakerAlignment.blocks(words, turns, AudioRecorder.SAMPLE_RATE)

            // Timed per phase because the two halves of this pipeline are data-independent -- nothing
            // flows between diarisation and recognition until SpeakerAlignment joins them -- so
            // whether running them concurrently is worth the peak memory is decided entirely by
            // which one is the long pole. That is a per-device answer, so the numbers have to come
            // from the device rather than from a guess.
            Log.i(
                TAG,
                ("phases over %.1fs of audio: diarise %.1fs + fold %.1fs + name %.1fs = %.1fs " +
                    "|| transcribe %.1fs -> wall %.1fs (sequential would be %.1fs)").format(
                    samples.size / AudioRecorder.SAMPLE_RATE.toFloat(),
                    diariseMillis / 1000f,
                    foldMillis / 1000f,
                    nameMillis / 1000f,
                    (diariseMillis + foldMillis + nameMillis) / 1000f,
                    transcribeMillis / 1000f,
                    (System.currentTimeMillis() - wallStarted) / 1000f,
                    (diariseMillis + foldMillis + nameMillis + transcribeMillis) / 1000f,
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
    private fun compactToSpeech(samples: FloatArray): CompactedAudio {
        if (!settings.settings.value.vadEnabled) return CompactedAudio.untouched(samples)

        val detector = SpeechActivityDetector(context.assets)
        val detected = try {
            detector.load(
                // No slice cap applies here -- diarisation has no per-clip limit, unlike the
                // transcribers. A forced close only ever splits one region into two touching ones,
                // which CompactedAudio merges straight back, so the value cannot change the output.
                maxSpeechSamples = MAX_SPEECH_SAMPLES,
                // "cpu", matching SpeakerDiarizer rather than Settings: the diarisation models are
                // pinned there, and running the detector on a different provider from the models it
                // feeds would make the phase timings describe two configurations at once.
                provider = "cpu",
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
    val foldMillis: Long,
    val nameMillis: Long,
)

/** What the recognition branch produced. */
private data class Transcribed(
    val words: List<TimedWord>,
    val millis: Long,
)
