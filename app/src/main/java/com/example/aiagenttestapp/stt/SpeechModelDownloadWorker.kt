package com.example.aiagenttestapp.stt

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
 * Downloads one speech model in a WorkManager foreground job -- the same shape as
 * [com.example.aiagenttestapp.data.ModelDownloadWorker] gives the language models. The transfer
 * survives leaving the record screen, backgrounding the app and process death, waits for a network
 * connection, and can be cancelled. The bytes-to-disk logic lives in
 * [SpeechModelRepository.performDownload]; this class is the shell around it -- progress reporting
 * and the ongoing notification.
 */
@HiltWorker
class SpeechModelDownloadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: SpeechModelRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val modelId = inputData.getString(SpeechModelRepository.KEY_MODEL_ID)
            ?: return Result.failure()

        val model = repository.modelWithId(modelId)
            ?: return Result.failure(
                workDataOf(SpeechModelRepository.KEY_ERROR to "Unknown speech model."),
            )

        val notifId = NOTIF_BASE + (modelId.hashCode().absoluteValue % 1_000)

        // Go foreground: a few-hundred-MB transfer must not be killed when the app is backgrounded.
        setForeground(foregroundInfo(model.label, 0f, notifId))

        var lastNotifMs = 0L
        return try {
            repository.performDownload(model) { progress ->
                setProgress(workDataOf(SpeechModelRepository.KEY_PROGRESS to progress))
                // The notification updates far less often than progress -- once a second is plenty.
                val now = System.currentTimeMillis()
                if (now - lastNotifMs >= NOTIF_INTERVAL_MS) {
                    lastNotifMs = now
                    runCatching { setForeground(foregroundInfo(model.label, progress, notifId)) }
                }
            }
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // WorkManager marks the work CANCELLED; already-complete files are kept.
        } catch (e: Exception) {
            Result.failure(
                workDataOf(SpeechModelRepository.KEY_ERROR to (e.message ?: "Download failed")),
            )
        }
    }

    private fun foregroundInfo(label: String, progress: Float, notifId: Int): ForegroundInfo {
        val channelId = ensureChannel()
        val percent = (progress * 100).toInt().coerceIn(0, 100)

        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle("Downloading $label speech model")
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

    /** Same channel as the language-model downloads: to the user they are the same kind of thing. */
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

        /** Distinct base from ModelDownloadWorker's (4200), so the notifications never collide. */
        const val NOTIF_BASE = 5300
        const val NOTIF_INTERVAL_MS = 1_000L
    }
}
