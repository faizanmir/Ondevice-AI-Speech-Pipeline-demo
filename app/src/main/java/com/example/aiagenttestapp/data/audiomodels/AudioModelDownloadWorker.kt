package com.example.aiagenttestapp.data.audiomodels

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlin.math.absoluteValue

/**
 * Downloads one audio-model bundle in a WorkManager foreground job -- the same shape as
 * [com.example.aiagenttestapp.stt.SpeechModelDownloadWorker] gives the ASR models. The bytes-to-disk
 * logic lives in [AudioModelRepository.performDownload]; this is the shell around it: progress
 * reporting and the ongoing notification.
 */
@HiltWorker
class AudioModelDownloadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: AudioModelRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val bundleId = inputData.getString(AudioModelRepository.KEY_BUNDLE_ID)
            ?: return Result.failure()

        val bundle = repository.bundleWithId(bundleId)
            ?: return Result.failure(
                workDataOf(AudioModelRepository.KEY_ERROR to "Unknown model bundle."),
            )

        val notifId = NOTIF_BASE + (bundleId.hashCode().absoluteValue % 1_000)

        setForeground(foregroundInfo(bundle.label, 0f, notifId))

        var lastNotifMs = 0L
        return try {
            repository.performDownload(bundle) { progress ->
                setProgress(workDataOf(AudioModelRepository.KEY_PROGRESS to progress))
                val now = System.currentTimeMillis()
                if (now - lastNotifMs >= NOTIF_INTERVAL_MS) {
                    lastNotifMs = now
                    runCatching { setForeground(foregroundInfo(bundle.label, progress, notifId)) }
                }
            }
            repository.refresh()
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // WorkManager marks the work CANCELLED; completed files are kept.
        } catch (e: Exception) {
            Result.failure(
                workDataOf(AudioModelRepository.KEY_ERROR to (e.message ?: "Download failed")),
            )
        }
    }

    private fun foregroundInfo(label: String, progress: Float, notifId: Int): ForegroundInfo {
        val channelId = ensureChannel()
        val percent = (progress * 100).toInt().coerceIn(0, 100)

        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle("Downloading $label")
            .setContentText(if (progress > 0f) "$percent%" else "Starting…")
            .setSmallIcon(android.R.drawable.stat_sys_download)
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

    /** The same channel as every other model download: to the user they are the same kind of thing. */
    private fun ensureChannel(): String {
        if (Build.VERSION.SDK_INT >= 26) {
            val mgr = applicationContext.getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "Model downloads",
                        NotificationManager.IMPORTANCE_LOW,
                    ),
                )
            }
        }
        return CHANNEL_ID
    }

    private companion object {
        const val CHANNEL_ID = "model_downloads"

        /** Distinct from ModelDownloadWorker (4200) and SpeechModelDownloadWorker (5300). */
        const val NOTIF_BASE = 6400
        const val NOTIF_INTERVAL_MS = 1_000L
    }
}
