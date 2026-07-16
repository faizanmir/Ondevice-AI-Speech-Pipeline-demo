package com.example.aiagenttestapp

import android.app.Application
import android.content.ComponentCallbacks2
import androidx.room.Room
import androidx.startup.AppInitializer
import com.example.aiagent.engine.aicore.AiCoreEngine
import com.example.aiagent.engine.core.DeviceMemoryProbe
import com.example.aiagent.engine.core.EngineRegistry
import com.example.aiagent.engine.core.ModelSpec
import com.example.aiagent.engine.litertlm.LiteRtLmEngine
import com.example.aiagent.engine.llamacpp.LlamaCppEngine
import com.example.aiagent.engine.mnn.MnnEngine
import com.example.aiagenttestapp.data.CustomModelStore
import com.example.aiagenttestapp.data.HuggingFaceAuth
import com.example.aiagenttestapp.data.chat.ChatDatabase
import com.example.aiagenttestapp.data.HuggingFaceClient
import com.example.aiagenttestapp.data.MnnMarketClient
import com.example.aiagenttestapp.data.ModelCatalog
import com.example.aiagenttestapp.data.ModelResidency
import com.example.aiagenttestapp.data.ModelRepository
import com.example.aiagenttestapp.data.NetworkMonitor
import com.example.aiagenttestapp.data.SettingsStore
import com.example.aiagenttestapp.data.WebSearchClient
import com.example.aiagenttestapp.data.notes.NotesDatabase
import com.example.aiagenttestapp.startup.ModelPreloadInitializer
import com.example.aiagenttestapp.stt.AudioRecorder
import com.example.aiagenttestapp.stt.SpeechModelRepository
import com.example.aiagenttestapp.stt.SpeechRecognizer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Manual dependency container. There are five things to wire and one process-wide lifetime, which
 * is well under the threshold where a DI framework earns its build-time cost.
 */
class AppContainer(application: Application) {

    /**
     * Registration order is the fallback order: a `.litertlm` model goes to LiteRT-LM, a `.gguf`
     * to llama.cpp, an MNN export to MNN, and the system-managed Gemini Nano to AICore, because
     * those are the only engines that can load each. Adding a backend means adding it to this
     * list and nothing else.
     */
    val engines = EngineRegistry(
        listOf(
            LiteRtLmEngine(),
            LlamaCppEngine(),
            MnnEngine(),
            AiCoreEngine(),
        ),
    )

    /** Read once: RAM and ABI do not change while the process is alive. */
    val deviceMemory = DeviceMemoryProbe.read(application)

    val huggingFaceAuth = HuggingFaceAuth(application)

    val modelRepository = ModelRepository(application, huggingFaceAuth)

    val settingsStore = SettingsStore(application)

    /**
     * Keeps the default model resident in its engine for the session, so opening its chat is instant
     * after the first load. Frees it under memory pressure while no chat is on screen.
     */
    val modelResidency = ModelResidency()

    val customModelStore = CustomModelStore(application)

    val huggingFace = HuggingFaceClient(huggingFaceAuth)

    /** Alibaba's MNN model market -- the catalogue behind the Models screen's MNN tab. */
    val mnnMarket = MnnMarketClient()

    /** Lets a tool-capable model search the web via Tavily, when a key is set in Settings. */
    val webSearch = WebSearchClient()

    /** Whether the device can actually reach the internet -- gates the web tools when offline. */
    val networkMonitor = NetworkMonitor(application)

    /**
     * The catalogue: the curated built-ins plus whatever the user has added from HuggingFace.
     * Everything downstream reads models from here rather than from the hardcoded list, so an added
     * model behaves exactly like a built-in one -- same fit check, same download, same chat.
     */
    val allModels: Flow<List<ModelSpec>> =
        customModelStore.models.map { custom -> ModelCatalog.builtIn + custom }

    fun findModel(id: String): ModelSpec? =
        ModelCatalog.byId(id) ?: customModelStore.models.value.firstOrNull { it.id == id }

    /** The catalogue right now, without collecting a Flow -- for app functions, which are one-shot. */
    fun allModelsSnapshot(): List<ModelSpec> = ModelCatalog.builtIn + customModelStore.models.value

    // ---- Speech to text ----------------------------------------------------------------------

    val audioRecorder = AudioRecorder()

    // Needs the settings store: which speech model to download and load is a Settings choice.
    val speechModels = SpeechModelRepository(application, settingsStore)

    val speechRecognizer = SpeechRecognizer()

    // ---- Saved notes -------------------------------------------------------------------------

    private val notesDatabase: NotesDatabase = Room.databaseBuilder(
        application,
        NotesDatabase::class.java,
        "notes.db",
    ).build()

    val noteDao = notesDatabase.noteDao()

    // Chat history lives in its own database, so it never forces a migration on notes.db.
    private val chatDatabase: ChatDatabase = Room.databaseBuilder(
        application,
        ChatDatabase::class.java,
        "chat.db",
    ).build()

    val chatDao = chatDatabase.chatDao()

    /** Engines cache compiled graphs here, which makes the second load of a model much faster. */
    val cacheDirPath: String = application.cacheDir.absolutePath

    /** LiteRT-LM's NPU backend dlopen()s vendor libraries out of the APK's native library dir. */
    val nativeLibraryDir: String = application.applicationInfo.nativeLibraryDir
}

class AIAgentApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        instance = this

        // Warm the default model into memory now, off the main thread, so the first chat is ready
        // to type into instead of pausing to load. Driven manually (not auto-initialised) because
        // App Startup's automatic initializers run before this point -- before `container` exists.
        AppInitializer.getInstance(this)
            .initializeComponent(ModelPreloadInitializer::class.java)
    }

    /**
     * The resident model can be gigabytes, so hand it back under real memory pressure -- foreground
     * low/critical, or the system reclaiming from us in the background. [ModelResidency] only acts on
     * this while no chat is on screen, so an open chat is never pulled out from under the user, and a
     * routine app-switch (UI_HIDDEN) is deliberately left alone so the warm model survives it.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val underPressure = level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level == ComponentCallbacks2.TRIM_MEMORY_COMPLETE
        if (underPressure) container.modelResidency.onMemoryPressure()
    }

    companion object {
        /**
         * The running Application.
         *
         * AppFunctions instantiates the provider class itself, and constructor injection there
         * needs a Hilt-backed custom factory. A process-wide Application handle is the smaller
         * price: it is not a leak (the Application outlives everything anyway) and it keeps the
         * app free of a DI framework it does not otherwise need.
         */
        lateinit var instance: AIAgentApplication
            private set
    }
}
