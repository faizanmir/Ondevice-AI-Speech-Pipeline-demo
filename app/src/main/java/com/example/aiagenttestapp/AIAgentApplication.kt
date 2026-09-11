package com.example.aiagenttestapp

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.DelegatingWorkerFactory
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import com.example.aiagent.llm.LlmWorkerFactory
import com.example.aiagent.llm.ModelResidency
import com.example.aiagenttestapp.data.speakers.SpeakerRepository
import com.example.aiagenttestapp.stt.SpeakerDiarizer
import com.example.aiagenttestapp.stt.SpeechRecognizer
import com.example.aiagenttestapp.stt.SttWorkerFactory
import com.example.aiagenttestapp.stt.WarmPool

@HiltAndroidApp
class AIAgentApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var modelResidency: ModelResidency

    @Inject
    lateinit var diarizerPool: WarmPool<SpeakerDiarizer>

    @Inject
    lateinit var speechRecognizer: SpeechRecognizer

    @Inject
    lateinit var speakerRepository: SpeakerRepository

    /**
     * The three worker factories, reached at the moment a worker is built rather than injected here.
     *
     * `:llm` and `:stt` own workers that used to be `@HiltWorker`s and are not any more -- a library
     * that requires Hilt to download a model is one most hosts cannot take -- so each ships a plain
     * `WorkerFactory` and they are composed, since WorkManager accepts exactly one.
     *
     * They are **not** `@Inject lateinit var` fields, and that is load-bearing rather than a style
     * choice. Field injection is re-entrant here: building `SttWorkerFactory` constructs
     * `SpeechModelRepository`, whose initialiser calls `WorkManager.getInstance()`, which -- on a
     * cold start where WorkManager has not initialised yet -- reads [workManagerConfiguration] back.
     * Reading an injected field from there while injection is still in progress threw
     * `UninitializedPropertyAccessException` and took the process down before the first screen.
     * `ModelRepository` has the same shape, so this is not one unlucky class.
     */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WorkerFactories {
        fun hilt(): HiltWorkerFactory
        fun llm(): LlmWorkerFactory
        fun stt(): SttWorkerFactory
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(DeferredWorkerFactory(this))
            .build()

    override fun onCreate() {
        super.onCreate()
        // The default model's warm-up is NOT kicked off here: the splash screen drives it (via
        // ModelPreloadInitializer) as part of its model-pathway initialization, alongside the
        // permission flow, so startup work is visible in one place instead of split across two.
    }

    /**
     * The resident model can be gigabytes, so hand it back under real memory pressure -- foreground
     * low/critical, or the system reclaiming from us in the background. [ModelResidency] only acts on
     * this while no chat is on screen, so an open chat is never pulled out from under the user, and a
     * routine app-switch (UI_HIDDEN) is deliberately left alone so the warm model survives it.
     *
     * The speech side keeps its own warm set for the same reload-avoidance reason -- diarizer lanes,
     * the second transcribe recogniser, the naming embedder, a few hundred MB together -- and this
     * callback is what takes all of it back. Deliberately the same trigger levels: these are cheap
     * to reload compared to the LLM, so there is no case where they should out-survive it.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val underPressure = level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level == ComponentCallbacks2.TRIM_MEMORY_COMPLETE
        if (underPressure) {
            modelResidency.onMemoryPressure()
            diarizerPool.clear()
            speechRecognizer.onMemoryPressure()
            speakerRepository.onMemoryPressure()
        }
    }

}

/**
 * Resolves the real worker factories on first use, not when WorkManager asks for its configuration.
 *
 * WorkManager reads [Configuration.Provider.workManagerConfiguration] the first time anything calls
 * `WorkManager.getInstance()`, and in this app that can happen *during* Hilt's field injection --
 * `SpeechModelRepository` and `ModelRepository` both call it from a property initialiser. Building
 * the factory eagerly there means touching the graph mid-construction, which is what crashed on
 * launch.
 *
 * `createWorker` is only ever called when WorkManager actually instantiates a worker, which is long
 * after the singleton component exists, so resolving lazily from there is always safe. The `lazy`
 * also keeps the composition to one object rather than rebuilding it per worker.
 */
private class DeferredWorkerFactory(private val app: Application) : WorkerFactory() {

    private val delegate: DelegatingWorkerFactory by lazy {
        val graph = EntryPointAccessors.fromApplication(
            app,
            AIAgentApplication.WorkerFactories::class.java,
        )
        DelegatingWorkerFactory().apply {
            // Library factories first: each answers only for its own worker classes and returns
            // null otherwise, which is how a delegating factory knows to fall through to Hilt's.
            addFactory(graph.llm())
            addFactory(graph.stt())
            addFactory(graph.hilt())
        }
    }

    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? = delegate.createWorker(appContext, workerClassName, workerParameters)
}
