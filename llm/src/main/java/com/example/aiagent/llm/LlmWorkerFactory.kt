package com.example.aiagent.llm

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters

/**
 * Builds this library's workers, so a consumer does not have to adopt a DI framework to get one.
 *
 * WorkManager constructs workers by reflection from a class name, which is why a worker that needs
 * dependencies needs a factory at all. The obvious answer -- `@HiltWorker` -- is the wrong one for a
 * library: it makes Hilt a hard requirement of *using* the library, and Hilt is a choice a host has
 * already made or already rejected by the time it reaches for this.
 *
 * **An app installs exactly one `WorkerFactory`**, so this cannot simply be set. Compose it:
 *
 * ```kotlin
 * // Configuration.Provider
 * override val workManagerConfiguration = Configuration.Builder()
 *     .setWorkerFactory(
 *         DelegatingWorkerFactory().apply {
 *             addFactory(LlmWorkerFactory(models, modelRepository))
 *             addFactory(hiltWorkerFactory) // whatever the host already had
 *         },
 *     )
 *     .build()
 * ```
 *
 * Returning null for an unrecognised class name is the contract, not a failure: it is how a
 * delegating factory knows to try the next one.
 */
class LlmWorkerFactory(
    private val models: ModelDirectory,
    private val modelRepository: ModelRepository,
) : WorkerFactory() {

    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? = when (workerClassName) {
        ModelDownloadWorker::class.java.name ->
            ModelDownloadWorker(appContext, workerParameters, models, modelRepository)

        else -> null
    }
}
