package com.example.aiagenttestapp.stt

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters

/**
 * Builds this library's workers, so a consumer does not have to adopt a DI framework to use it.
 *
 * The twin of `LlmWorkerFactory`, and for the same reason: WorkManager constructs workers by
 * reflection, so one with dependencies needs a factory, and making that factory Hilt's would make
 * Hilt a requirement of downloading a speech model.
 *
 * An app installs exactly one `WorkerFactory`, so compose this into a `DelegatingWorkerFactory`
 * alongside whatever the host already has -- returning null for an unrecognised class is the
 * contract that lets the next factory try.
 */
class SttWorkerFactory(
    private val speechModels: SpeechModelRepository,
) : WorkerFactory() {

    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? = when (workerClassName) {
        SpeechModelDownloadWorker::class.java.name ->
            SpeechModelDownloadWorker(appContext, workerParameters, speechModels)

        else -> null
    }
}
