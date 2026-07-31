package com.example.aiagenttestapp.data

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
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import androidx.work.workDataOf
import kotlin.math.absoluteValue

/**
 * Downloads one model in a WorkManager foreground job.
 *
 * Running here rather than in a screen's coroutine is the whole point: the transfer survives the
 * user leaving the catalogue, backgrounding the app, or the process being killed, and it can be
 * cancelled. The actual bytes-to-disk logic lives in [ModelRepository.performDownload]; this class
 * is the WorkManager shell around it -- progress reporting and the ongoing notification.
 */
@HiltWorker
class ModelDownloadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val models: ModelDirectory,
    private val modelRepository: ModelRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val modelId = inputData.getString(ModelRepository.KEY_MODEL_ID)
            ?: return Result.failure()

        val model = models.find(modelId)
            ?: return Result.failure(
                workDataOf(ModelRepository.KEY_ERROR to "That model is no longer in the catalogue."),
            )

        val notifId = NOTIF_BASE + (modelId.hashCode().absoluteValue % 10_000)

        // Go foreground: a multi-hundred-MB transfer must not be killed when the app is backgrounded.
        setForeground(foregroundInfo(model.name, 0, model.sizeBytes, notifId))

        var lastNotifMs = 0L
        return try {
            modelRepository.performDownload(model) { downloaded, total, rate ->
                setProgress(
                    workDataOf(
                        ModelRepository.KEY_DOWNLOADED to downloaded,
                        ModelRepository.KEY_TOTAL to total,
                        ModelRepository.KEY_RATE to rate,
                    ),
                )
                // The notification updates far less often than progress -- once a second is plenty.
                val now = System.currentTimeMillis()
                if (now - lastNotifMs >= NOTIF_INTERVAL_MS) {
                    lastNotifMs = now
                    runCatching { setForeground(foregroundInfo(model.name, downloaded, total, notifId)) }
                }
            }
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // WorkManager marks the work CANCELLED; the .part is kept for resume.
        } catch (e: Exception) {
            Result.failure(workDataOf(ModelRepository.KEY_ERROR to (e.message ?: "Download failed")))
        }
    }

    private fun foregroundInfo(name: String, downloaded: Long, total: Long, notifId: Int): ForegroundInfo {
        val channelId = ensureChannel()
        val percent = if (total > 0) (downloaded * 100 / total).toInt() else 0

        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle("Downloading $name")
            .setContentText(if (total > 0) "$percent%" else "Starting…")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, percent, /* indeterminate = */ total <= 0)
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
        const val NOTIF_BASE = 4200
        const val NOTIF_INTERVAL_MS = 1_000L
    }
}
