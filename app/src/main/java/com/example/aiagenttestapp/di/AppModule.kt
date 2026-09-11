package com.example.aiagenttestapp.di

import android.content.Context
import com.example.aiagent.engine.core.DeviceMemoryProfile
import com.example.aiagent.engine.core.DeviceMemoryProbe
import com.example.aiagent.engine.core.EngineRegistry
import com.example.aiagent.engine.litertlm.LiteRtLmEngine
import com.example.aiagent.llm.CustomModelStore
import com.example.aiagenttestapp.data.FileTextExtractor
import com.example.aiagent.llm.HuggingFaceAuth
import com.example.aiagent.llm.HuggingFaceClient
import com.example.aiagent.llm.LlmConfigSource
import com.example.aiagent.llm.LlmWorkerFactory
import com.example.aiagenttestapp.bridge.GemmaTranscriberFactory
import com.example.aiagenttestapp.stt.LlmTranscriberFactory
import com.example.aiagent.llm.LlmPaths
import com.example.aiagenttestapp.data.ModelCatalog
import com.example.aiagent.llm.ModelDirectory
import com.example.aiagent.llm.ModelLoadPlanner
import com.example.aiagent.llm.ModelRepository
import com.example.aiagent.llm.ModelResidency
import com.example.aiagenttestapp.data.asLlmConfigSource
import javax.inject.Provider
import com.example.aiagenttestapp.data.NetworkMonitor
import com.example.aiagenttestapp.data.SettingsStore
import com.example.aiagenttestapp.data.sttConfig
import com.example.aiagenttestapp.stt.SttConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import com.example.aiagenttestapp.data.WebSearchClient
import com.example.aiagenttestapp.data.audiomodels.AudioModelRepository
import com.example.aiagenttestapp.functions.AppFunctionDeps
import com.example.aiagenttestapp.ui.chat.ChatModelPlanner
import com.example.aiagenttestapp.ui.chat.ChatResidency
import com.example.aiagenttestapp.ui.chat.ChatStore
import com.example.aiagenttestapp.ui.chat.RealChatModelPlanner
import com.example.aiagenttestapp.ui.chat.RealChatResidency
import com.example.aiagenttestapp.ui.chat.RealChatStore
import com.example.aiagenttestapp.functions.AppFunctionRegistry
import com.example.aiagenttestapp.functions.RealAppFunctionDeps
import com.example.aiagenttestapp.data.speakers.SpeakerDao
import com.example.aiagenttestapp.data.speakers.SpeakerRepository
import com.example.aiagenttestapp.stt.AudioRecorder
import com.example.aiagenttestapp.stt.KeywordDetector
import com.example.aiagenttestapp.stt.SpeakerDiarizer
import com.example.aiagenttestapp.stt.SpeechModelRepository
import com.example.aiagenttestapp.stt.SpeechRecognizer
import com.example.aiagenttestapp.stt.Punctuator
import com.example.aiagenttestapp.stt.StreamingRecognizer
import com.example.aiagenttestapp.stt.SttWorkerFactory
import com.example.aiagenttestapp.stt.WarmPool
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton

/** Where engines cache compiled graphs; the second load of a model is much faster because of it. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CacheDirPath

/** LiteRT-LM's NPU backend dlopen()s vendor libraries out of the APK's native library dir. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class NativeLibraryDir

/**
 * The process-wide graph that used to be `AppContainer`. Everything here is a singleton with the
 * lifetime of the app, exactly as before -- the change is that consumers now declare the two or
 * three things they actually need instead of being handed the whole container and helping
 * themselves.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * Registration order is the fallback order: the first engine that can load a model's format
     * gets it. LiteRT-LM is currently the only one, which is why `.litertlm` is the only
     * [com.example.aiagent.engine.core.ModelFormat]. Adding a backend means adding it to this list
     * and nothing else -- MNN, AICore and llama.cpp were each removed by deleting one line here.
     */
    @Provides
    @Singleton
    fun engineRegistry(): EngineRegistry = EngineRegistry(
        listOf(
            LiteRtLmEngine(),
        ),
    )

    /**
     * The functions the model may call. Injected rather than reached for statically, so a screen or
     * a test can be given a different set without the app's own list being in the way.
     */
    @Provides
    @Singleton
    fun appFunctionRegistry(): AppFunctionRegistry = AppFunctionRegistry.Default

    /** The capabilities a function body may reach, behind the interfaces it is written against. */
    @Provides
    @Singleton
    fun appFunctionDeps(real: RealAppFunctionDeps): AppFunctionDeps = real

    /** What a chat's ChatSession may reach, behind the interfaces it is written against. */
    @Provides
    fun chatModelPlanner(real: RealChatModelPlanner): ChatModelPlanner = real

    @Provides
    fun chatStore(real: RealChatStore): ChatStore = real

    @Provides
    fun chatResidency(real: RealChatResidency): ChatResidency = real

    /** Read once: RAM and ABI do not change while the process is alive. */
    @Provides
    @Singleton
    fun deviceMemory(@ApplicationContext context: Context): DeviceMemoryProfile =
        DeviceMemoryProbe.read(context)

    @Provides
    @Singleton
    fun huggingFaceAuth(@ApplicationContext context: Context) = HuggingFaceAuth(context)

    @Provides
    @Singleton
    fun modelRepository(@ApplicationContext context: Context, auth: HuggingFaceAuth) =
        ModelRepository(context, auth)

    @Provides
    @Singleton
    fun settingsStore(@ApplicationContext context: Context) = SettingsStore(context)

    // ---- Model hosting -------------------------------------------------------------------------
    //
    // These four used to carry `@Inject constructor` and be built by Hilt directly. They are
    // constructed here instead so that nothing in the model-hosting layer carries a DI annotation:
    // a library that forces Hilt on its callers is a library most callers cannot take. The wiring
    // that was implicit is now four lines, and it is the *app* saying how its own graph fits
    // together, which is where that belongs.

    /** The curated list is this app's editorial choice; the directory merely serves it. */
    @Provides
    @Singleton
    fun modelDirectory(customModelStore: CustomModelStore) =
        ModelDirectory(builtIn = ModelCatalog.builtIn, customModelStore = customModelStore)

    @Provides
    @Singleton
    fun llmConfigSource(settings: SettingsStore): LlmConfigSource = settings.asLlmConfigSource()

    /** Both are host facts -- this APK's cache and its unpacked native libraries. */
    @Provides
    @Singleton
    fun llmPaths(
        @CacheDirPath cacheDir: String,
        @NativeLibraryDir nativeLibraryDir: String,
    ) = LlmPaths(cacheDir = cacheDir, nativeLibraryDir = nativeLibraryDir)

    @Provides
    @Singleton
    fun modelLoadPlanner(
        models: ModelDirectory,
        config: LlmConfigSource,
        engines: EngineRegistry,
        modelRepository: ModelRepository,
        paths: LlmPaths,
    ) = ModelLoadPlanner(models, config, engines, modelRepository, paths)

    @Provides
    @Singleton
    fun modelResidency(deviceMemory: Provider<DeviceMemoryProfile>) =
        ModelResidency { deviceMemory.get() }

    /**
     * The seam between speech and language.
     *
     * `stt/` declares [LlmTranscriberFactory] and knows nothing else about models; this binds the
     * app's implementation. Remove this line and the voice-note pipeline still builds and runs --
     * with three of its four backends -- which is the test of whether the seam is real.
     */
    @Provides
    @Singleton
    fun llmTranscriberFactory(real: GemmaTranscriberFactory): LlmTranscriberFactory = real

    /** Composed into WorkManager's factory in [AIAgentApplication]; see the note there. */
    @Provides
    @Singleton
    fun llmWorkerFactory(models: ModelDirectory, modelRepository: ModelRepository) =
        LlmWorkerFactory(models, modelRepository)

    @Provides
    @Singleton
    fun sttWorkerFactory(speechModels: SpeechModelRepository) = SttWorkerFactory(speechModels)

    @Provides
    @Singleton
    fun customModelStore(@ApplicationContext context: Context) = CustomModelStore(context)

    @Provides
    @Singleton
    fun huggingFaceClient(auth: HuggingFaceAuth) = HuggingFaceClient(auth)

    /** Lets a tool-capable model search the web via Tavily, when a key is set in Settings. */
    @Provides
    @Singleton
    fun webSearchClient() = WebSearchClient()

    /** Extracts text from files the user attaches (text formats directly, PDFs via PDFBox). */
    @Provides
    @Singleton
    fun fileTextExtractor(@ApplicationContext context: Context) = FileTextExtractor(context)

    /** Whether the device can actually reach the internet -- gates the web tools when offline. */
    @Provides
    @Singleton
    fun networkMonitor(@ApplicationContext context: Context) = NetworkMonitor(context)

    @Provides
    @Singleton
    fun audioRecorder() = AudioRecorder()

    /**
     * The speech pipeline's view of Settings, and the only place the two are joined.
     *
     * Nothing under `stt/` knows `SettingsStore` exists any more -- it is handed [SttConfig], which
     * is its own type with its own defaults, so the same code runs for a caller that has no settings
     * store at all. `sttConfig` is what maps one onto the other.
     *
     * An app-lifetime scope, never cancelled, matching the singletons that collect it.
     */
    @Provides
    @Singleton
    fun sttConfig(settings: SettingsStore): StateFlow<SttConfig> =
        settings.sttConfig(CoroutineScope(SupervisorJob() + Dispatchers.Default))

    /** Which speech model to download and load is a Settings choice, so this reads the config. */
    @Provides
    @Singleton
    fun speechModelRepository(@ApplicationContext context: Context, config: StateFlow<SttConfig>) =
        SpeechModelRepository(context, config)

    @Provides
    @Singleton
    fun speechRecognizer(config: StateFlow<SttConfig>) = SpeechRecognizer(config)

    /**
     * The streaming counterpart. A singleton for the same reason as the offline one: it holds a
     * native model, and the record screen's live transcript and the transcription worker must share
     * one copy rather than each loading their own.
     */
    @Provides
    @Singleton
    fun streamingRecognizer(config: StateFlow<SttConfig>) = StreamingRecognizer(config)

    /** Restores capitals and full stops on streaming transcripts. Optional; a no-op without its model. */
    @Provides
    @Singleton
    fun punctuator(config: StateFlow<SttConfig>) = Punctuator(config)

    /** Spots spoken markers and commands during a recording, far more cheaply than re-running ASR. */
    @Provides
    @Singleton
    fun keywordDetector() = KeywordDetector()

    /** Enrolled voices, and the search index used to put names on a diarised recording. */
    @Provides
    @Singleton
    fun speakerRepository(
        dao: SpeakerDao,
        audioModels: AudioModelRepository,
        settings: SettingsStore,
    ) = SpeakerRepository(dao, audioModels, settings)

    /**
     * Diarizer lanes kept warm between runs, so a re-run skips their model loads. Capacity matches
     * the largest lane fleet a run builds (DiarizeWorker.MAX_DIARIZE_LANES); freed under memory
     * pressure by [com.example.aiagenttestapp.AIAgentApplication.onTrimMemory].
     */
    @Provides
    @Singleton
    fun diarizerPool(): WarmPool<SpeakerDiarizer> = WarmPool(capacity = 4) { it.release() }

    /** Optional bundles: speaker identification, keyword spotting and punctuation. */
    @Provides
    @Singleton
    fun audioModelRepository(
        @ApplicationContext context: Context,
        settings: SettingsStore,
    ) = AudioModelRepository(context, settings)

    @Provides
    @CacheDirPath
    fun cacheDirPath(@ApplicationContext context: Context): String =
        context.cacheDir.absolutePath

    @Provides
    @NativeLibraryDir
    fun nativeLibraryDir(@ApplicationContext context: Context): String =
        context.applicationInfo.nativeLibraryDir
}
