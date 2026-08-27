package com.example.aiagenttestapp.di

import android.content.Context
import com.example.aiagent.engine.core.DeviceMemoryProfile
import com.example.aiagent.engine.core.DeviceMemoryProbe
import com.example.aiagent.engine.core.EngineRegistry
import com.example.aiagent.engine.litertlm.LiteRtLmEngine
import com.example.aiagent.engine.llamacpp.LlamaCppEngine
import com.example.aiagenttestapp.data.CustomModelStore
import com.example.aiagenttestapp.data.FileTextExtractor
import com.example.aiagenttestapp.data.HuggingFaceAuth
import com.example.aiagenttestapp.data.HuggingFaceClient
import com.example.aiagenttestapp.data.ModelRepository
import com.example.aiagenttestapp.data.NetworkMonitor
import com.example.aiagenttestapp.data.SettingsStore
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
import com.example.aiagenttestapp.stt.SpeechModelRepository
import com.example.aiagenttestapp.stt.SpeechRecognizer
import com.example.aiagenttestapp.stt.Punctuator
import com.example.aiagenttestapp.stt.StreamingRecognizer
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
     * Registration order is the fallback order: a `.litertlm` model goes to LiteRT-LM and a
     * `.gguf` to llama.cpp, because those are the only engines that can load each. Adding a
     * backend means adding it to this list and nothing else.
     */
    @Provides
    @Singleton
    fun engineRegistry(): EngineRegistry = EngineRegistry(
        listOf(
            LiteRtLmEngine(),
            LlamaCppEngine(),
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

    /** Needs the settings store: which speech model to download and load is a Settings choice. */
    @Provides
    @Singleton
    fun speechModelRepository(@ApplicationContext context: Context, settings: SettingsStore) =
        SpeechModelRepository(context, settings)

    @Provides
    @Singleton
    fun speechRecognizer(settings: SettingsStore) = SpeechRecognizer(settings)

    /**
     * The streaming counterpart. A singleton for the same reason as the offline one: it holds a
     * native model, and the record screen's live transcript and the transcription worker must share
     * one copy rather than each loading their own.
     */
    @Provides
    @Singleton
    fun streamingRecognizer(settings: SettingsStore) = StreamingRecognizer(settings)

    /** Restores capitals and full stops on streaming transcripts. Optional; a no-op without its model. */
    @Provides
    @Singleton
    fun punctuator(settings: SettingsStore) = Punctuator(settings)

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
