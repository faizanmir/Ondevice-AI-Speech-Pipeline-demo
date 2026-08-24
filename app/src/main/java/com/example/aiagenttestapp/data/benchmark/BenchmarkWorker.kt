package com.example.aiagenttestapp.data.benchmark

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
import com.example.aiagenttestapp.data.notes.SttBackend
import com.example.aiagenttestapp.data.notes.TranscriptionCheckpoint
import com.example.aiagenttestapp.data.notes.TranscriptionRun
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.io.File
import kotlin.math.absoluteValue

/**
 * Runs one benchmark: transcribe a clip through [TranscriptionRun] -- the identical machinery a
 * real note goes through -- and score the result against the clip's reference with [Wer].
 *
 * A foreground worker for the same reason note transcription is: a benchmark of a 20-minute
 * reference on the Gemma path is the better part of an hour, and it should survive the screen
 * turning off. The checkpoint gives it the same resume-after-process-death a note gets.
 *
 * The one place it deliberately differs from the note worker: **each run starts from a clean
 * sidecar.** A leftover checkpoint from a previous run would make every slice a cache hit, and
 * the "benchmark" would measure a file read. The delete is guarded by [getRunAttemptCount] so a
 * retry after process death still resumes rather than starting over -- fresh means fresh per
 * *run*, not per attempt.
 */
@HiltWorker
class BenchmarkWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val clipDao: BenchmarkClipDao,
    private val runDao: BenchmarkRunDao,
    private val transcriptionRun: TranscriptionRun,
) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo =
        foregroundInfo(inputData.getLong(KEY_RUN_ID, 0L), 0f)

    override suspend fun doWork(): Result {
        val runId = inputData.getLong(KEY_RUN_ID, -1L)
        if (runId < 0) return Result.failure()

        val run = runDao.byId(runId) ?: return Result.success() // deleted while queued
        val clip = clipDao.byId(run.clipId)
        val audio = clip?.audioPath?.let(::File)

        if (clip == null || audio == null || !audio.exists()) {
            // Nothing ran, so nothing took any time: null rather than a two-millisecond duration
            // that would sit in the same column as real measurements.
            runDao.fail(runId, "The clip's audio is no longer available.", wallMillis = null)
            return Result.success()
        }

        // Stamped here, not taken from run.startedAtMillis. That field is the *enqueue* time, so a
        // job WorkManager held for a minute would report a minute of transcription it never did --
        // and the failure rows would then be measuring something different from the finished ones,
        // in the same column, with nothing to say so.
        val workerStartedAt = System.currentTimeMillis()

        runCatching { setForeground(foregroundInfo(runId, 0f)) }

        return try {
            // Fresh per run, resumed per attempt -- see the class doc.
            if (runAttemptCount == 0) {
                TranscriptionCheckpoint.forAudio(audio).delete()
                // Ensure the models are also fresh: release anything leftover from a previous run
                // or from the user dictating on another screen.
                transcriptionRun.releaseSharedRecognizers()
            }

            val backend = SttBackend.fromSlug(run.backend)
            val checkpoint = TranscriptionCheckpoint.forAudio(audio).apply { load() }
            checkpoint.recordRequest(
                TranscriptionCheckpoint.Request(
                    markers = emptyList(),
                    excludedRanges = emptyList(),
                    sttBackend = backend,
                    sttModelId = run.sttModelId,
                ),
            )

            val outcome = transcriptionRun.transcribe(
                audio = audio,
                markers = emptyList(),
                excluded = emptyList(),
                backend = backend,
                preferredModelId = run.sttModelId,
                checkpoint = checkpoint,
            ) { progress ->
                runDao.updateProgress(runId, progress)
                runCatching { setForeground(foregroundInfo(runId, progress)) }
            }

            when (outcome) {
                is TranscriptionRun.Outcome.Done -> {
                    val report = Wer.report(clip.referenceText, outcome.transcript, clip.language)
                    Log.i(
                        TAG,
                        // Coverage first, per the shared protocol: a low coverage makes the WER a
                        // measure of how much is missing rather than of how well it heard.
                        "clip '%s': coverage %.1f%%, WER %.1f%% raw, %.1f%% normalised, CER %.1f%%, %d ms wall".format(
                            clip.name,
                            report.coverage,
                            report.raw.werPercent,
                            report.normalised.werPercent,
                            report.cerPercent,
                            outcome.wallMillis,
                        ),
                    )
                    runDao.update(
                        run.copy(
                            status = BenchmarkRunStatus.Done,
                            progress = 1f,
                            transcript = outcome.transcript,
                            rawWerPercent = report.raw.werPercent,
                            normalisedWerPercent = report.normalised.werPercent,
                            substitutions = report.normalised.substitutions,
                            deletions = report.normalised.deletions,
                            insertions = report.normalised.insertions,
                            referenceWords = report.normalised.referenceWords,
                            coveragePercent = report.coverage,
                            cerPercent = report.cerPercent,
                            topPairs = report.topPairs.joinToString("\n") { (ref, hyp, n) ->
                                "$n× “$ref” → “$hyp”"
                            },
                            wallMillis = outcome.wallMillis,
                            error = null,
                        ),
                    )
                    // The clip's audio stays -- that is what makes re-runs possible -- but the
                    // finished run's scratch state is done with.
                    checkpoint.delete()
                }

                is TranscriptionRun.Outcome.NothingRecognised ->
                    // Timed like a finished run, because it is one: it decoded the whole clip and
                    // found nothing in it, which costs exactly as long as finding something and is
                    // worth being able to compare.
                    runDao.fail(
                        runId,
                        outcome.message,
                        wallMillis = System.currentTimeMillis() - workerStartedAt,
                    )
            }
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "benchmark run $runId failed", e)
            // How long it ran before throwing, which is the question asked of a run that dies part
            // way through -- a model that fails to load fails in seconds, one that runs out of
            // memory on a long slice fails after minutes, and the row should be able to tell them
            // apart without the log.
            runDao.fail(
                runId,
                e.message ?: "The benchmark run failed.",
                wallMillis = System.currentTimeMillis() - workerStartedAt,
            )
            Result.success()
        } finally {
            // Releasing the models after the run ensures the next run (even with the same model)
            // pays the full load cost again, making the wall-time measurement fair. It also
            // frees the ~750 MB of native memory Whisper Small takes.
            transcriptionRun.releaseSharedRecognizers()
        }
    }

    private fun foregroundInfo(runId: Long, progress: Float): ForegroundInfo {
        val channelId = ensureChannel()
        val percent = (progress * 100).toInt().coerceIn(0, 100)
        val notifId = NOTIF_BASE + (runId.hashCode().absoluteValue % 1_000)

        val notification = NotificationCompat.Builder(context, channelId)
            .setContentTitle("Running STT benchmark")
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
        if (Build.VERSION.SDK_INT >= 26) {
            val mgr = context.getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "STT benchmark",
                        NotificationManager.IMPORTANCE_LOW,
                    ),
                )
            }
        }
        return CHANNEL_ID
    }

    companion object {
        private const val TAG = "BenchmarkWorker"
        private const val CHANNEL_ID = "stt_benchmark"

        /** Distinct from the other workers' bases (4200, 5300, 6400, 7500). */
        private const val NOTIF_BASE = 8600

        internal const val KEY_RUN_ID = "runId"

        fun uniqueName(runId: Long) = "stt-benchmark:$runId"

        fun enqueue(context: Context, runId: Long) {
            val request = OneTimeWorkRequestBuilder<BenchmarkWorker>()
                .setInputData(workDataOf(KEY_RUN_ID to runId))
                .addTag(uniqueName(runId))
                .build()

            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(uniqueName(runId), ExistingWorkPolicy.KEEP, request)
        }

        /**
         * Marks Running rows whose job no longer exists as failed, so a row cannot sit on a
         * progress bar forever after WorkManager lost the work -- the same reasoning as
         * `NoteTranscribeWorker.reconcileOrphans`, without the re-enqueue: a benchmark is an
         * attended experiment, and silently re-running it hours later would produce a row the
         * user cannot place.
         */
        suspend fun reconcile(context: Context, runDao: BenchmarkRunDao) {
            val stuck = runDao.running()
            if (stuck.isEmpty()) return

            val workManager = WorkManager.getInstance(context.applicationContext)
            for (run in stuck) {
                val live = runCatching {
                    workManager.getWorkInfosForUniqueWorkFlow(uniqueName(run.id))
                        .first()
                        .any { info -> !info.state.isFinished }
                }.getOrDefault(false)

                // No duration: the process died at an unknown point, possibly hours before this
                // reconciliation noticed. Measuring to now would time the gap between the crash and
                // the next app launch and file it as transcription time.
                if (!live) runDao.fail(run.id, "The run was interrupted.", wallMillis = null)
            }
        }
    }
}
