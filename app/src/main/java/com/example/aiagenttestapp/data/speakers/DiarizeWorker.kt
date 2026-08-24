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
import com.example.aiagenttestapp.data.notes.WavFile
import com.example.aiagenttestapp.stt.AudioRecorder
import com.example.aiagenttestapp.stt.SpeakerDiarizer
import com.example.aiagenttestapp.stt.SpeechEngineKind
import com.example.aiagenttestapp.stt.SpeechModelRepository
import com.example.aiagenttestapp.stt.SpeechRecognizer
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
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
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getLong(KEY_RECORDING_ID, -1L)
        if (id < 0) return Result.failure()

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

            // A count the user supplied is evidence about this recording and is independent of which
            // voices happen to be enrolled. The old branch discarded that evidence whenever *any*
            // enrolment existed, even if the other people in this recording were strangers. Naming
            // can merge duplicate clusters, but it cannot split two real voices already merged into
            // one, so the explicit count must reach sherpa unchanged.
            diarizer.load(
                segmentationModel = audioModels.fileFor(bundle, AudioModelCatalog.SEGMENTATION),
                embeddingModel = audioModels.fileFor(bundle, AudioModelCatalog.EMBEDDING),
                expectedSpeakers = recording.expectedSpeakers,
            )

            // Diarisation reports no progress -- see [SpeakerDiarizer.diarize] for why asking it to
            // takes the process down -- so the row sits at 0.05 for the whole phase and the screen
            // shows an indeterminate bar. Honest, and better than a bar that claims to know: the
            // alternative was a fabricated ramp, which is a worse lie on the one screen whose job is
            // reporting what the models actually did.
            val rawTurns = withContext(Dispatchers.Default) { diarizer.diarize(samples) }
            diarizer.release()

            // The step sherpa's clustering leaves out. Fragments too short to be a speaker are
            // folded into whichever cluster they sound like, before anything downstream inherits
            // their ids -- see [SpeakerRepository.mergeSmallClusters].
            val turns = speakers.mergeSmallClusters(samples, rawTurns)
            Log.i(
                TAG,
                "diarisation found ${rawTurns.map { it.cluster }.distinct().size} clusters in " +
                    "${rawTurns.size} turns, ${turns.map { it.cluster }.distinct().size} after " +
                    "folding fragments",
            )

            if (recognizer.loadedModelId != model.id) {
                recognizer.load(speechModels.selectedPaths())
            }
            val bounds = recognizer.segmentBounds(samples)
            val pieces = recognizer.transcribeSegments(samples, bounds) { done, total, _ ->
                dao.updateProgress(id, 0.5f + (done.toFloat() / total) * 0.45f)
            }

            val words = pieces.flatMap { it.words }

            val blocks = SpeakerAlignment.blocks(words, turns, AudioRecorder.SAMPLE_RATE)
            val names = speakers.labelClusters(samples, turns)

            // One person is routinely several clusters, all correctly given the same name, and a
            // block cut at every hop between them splits their sentence across duplicate labels.
            // See [nameBlocks].
            val named = smoothShortBlocks(
                nameBlocks(blocks, names, "${SpeakerRepository.UNKNOWN_SPEAKER_PREFIX} ?"),
                unknownPrefix = SpeakerRepository.UNKNOWN_SPEAKER_PREFIX,
                minSamples = SHORT_BLOCK_SECONDS * AudioRecorder.SAMPLE_RATE,
            )
            Log.i(TAG, "transcript: ${blocks.size} aligned blocks became ${named.size} after naming")

            dao.deleteBlocksFor(id)
            dao.insertBlocks(
                named.map { block ->
                    DiarizedBlock(
                        recordingId = id,
                        startSample = block.startSample,
                        endSample = block.endSample,
                        cluster = block.cluster,
                        speakerName = block.name,
                        text = block.text,
                    )
                },
            )
            dao.update(
                recording.copy(status = DiarizedStatus.Done, progress = 1f, error = null),
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
