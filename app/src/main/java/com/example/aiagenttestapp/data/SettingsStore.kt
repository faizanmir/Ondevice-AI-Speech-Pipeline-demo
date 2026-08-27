package com.example.aiagenttestapp.data

import android.content.Context
import com.example.aiagent.engine.core.Accelerator
import com.example.aiagent.engine.core.EngineId
import com.example.aiagent.engine.core.SamplingParams
import com.example.aiagenttestapp.data.notes.NoteSummaryMode
import com.example.aiagenttestapp.data.notes.SttBackend
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.core.content.edit

data class AppSettings(
    /** Null = let the app pick the first engine that can load the chosen model. */
    val preferredEngine: EngineId? = null,
    val preferredAccelerator: Accelerator = Accelerator.GPU,
    val sampling: SamplingParams = SamplingParams(),
    /**
     * Whether the model may drive the app.
     *
     * A toggle rather than always-on, because tool calling costs the model something even when it
     * is not used: the tool list occupies system-prompt tokens on every turn, and small models will
     * sometimes emit a spurious tool call mid-conversation. Someone who just wants to chat should
     * be able to turn it off.
     */
    val appFunctionsEnabled: Boolean = true,
    /** The model new chats use, chosen in Settings. Null until one is picked. */
    val activeModelId: String? = null,
    /**
     * Tavily API key. When set (and app functions are on), a tool-capable model gains a `web_search`
     * function and can look things up on the internet. Blank/null = the model stays fully offline.
     */
    val tavilyApiKey: String? = null,
    /**
     * The speech-to-text model, by [com.example.aiagenttestapp.stt.SpeechModel.id]. Null = the
     * default (SenseVoice). A plain string rather than the type to keep this layer free of stt.
     */
    val speechModelId: String? = null,

    /**
     * Which speaker-embedding bundle identifies voices, by [AudioModelBundle.id].
     *
     * Lives in Settings rather than being pinned in the catalogue because the two models trade
     * against each other rather than one being better: CAM++ compares voices about 2.8x faster,
     * ERes2Net-base is what sherpa's clustering threshold was calibrated against. Switching does
     * not convert anybody's voiceprint -- the vectors have different dimensions -- so people
     * enrolled under the other model need enrolling again, which the app detects and says.
     */
    val speakerBundleId: String? = null,
    /**
     * Which recogniser the record screen starts a new recording with, or null when the user has
     * never picked one.
     *
     * Null is a distinct state rather than a default, and it has to be: defaulting to the speech
     * model meant someone who already had an audio-capable model on disk was shown a 240 MB
     * download prompt for a job their device could already do. The record screen resolves null
     * against what is actually available (see `RecordViewModel.resolveInitialBackend`); the moment
     * the user touches the picker this becomes non-null and their choice is honoured from then on.
     *
     * Only a starting point either way -- a recording carries its own backend once it stops (see
     * [com.example.aiagenttestapp.data.notes.SttBackend]).
     */
    val sttBackend: SttBackend? = null,
    /**
     * Whether a voice note is summarised in full or quickly.
     *
     * Remembered rather than asked each time: someone who wants short summaries wants them for every
     * note, and re-picking on each recording is the kind of friction that makes a good default
     * pointless. Non-null with a real default, unlike [sttBackend], because there is nothing to
     * resolve against the device -- both modes work with any model that can summarise at all.
     */
    val noteSummaryMode: NoteSummaryMode = NoteSummaryMode.DETAILED,
    /**
     * CPU decode threads for llama.cpp. 0 = automatic (the engine picks from the core
     * count). Carried into [com.example.aiagent.engine.core.LoadRequest.threadCount].
     */
    val threadCount: Int = 0,
    /**
     * When on, generation switches to argmax decoding with a fixed seed, so the same prompt yields
     * the same reply -- for evals and bug reports. Off = the sampling values below, with a fresh
     * random seed each chat. This flag is what the UI toggles; [effectiveSampling] is what engines
     * actually receive.
     */
    val reproducibleOutput: Boolean = false,
    /**
     * Most tool calls a model may chain in a single turn before it must answer -- search, read a
     * result, search again, then reply. Bounds runaway chaining by a small model that keeps calling
     * tools. Minimum 1.
     */
    val maxToolHops: Int = 4,
    /**
     * Whether reasoning models (Qwen3, DeepSeek-R1) may work through a `<think>` block before
     * answering. Off appends a directive asking them to answer directly. Baked into the system
     * prompt, so it takes effect on the next conversation.
     */
    val thinkingEnabled: Boolean = true,
    /**
     * Whether the keyword spotter listens for spoken markers ("start non conformity") while
     * recording. Off by default; gates a ~16 MB download.
     */
    val keywordMarkersEnabled: Boolean = false,
    /**
     * Whether silence is detected and skipped before transcription.
     *
     * On by default, unlike the two above: it needs no download (the 644 KB model is in the APK) and
     * it improves both recognisers rather than adding a feature. The switch exists because its
     * failure mode is the one thing a user cannot recover from -- speech mistaken for silence is
     * never transcribed and leaves no gap to notice -- so someone who suspects it is eating quiet
     * talkers needs a way to rule it out.
     */
    val vadEnabled: Boolean = true,
    /**
     * Which ONNX Runtime execution provider the speech models run on.
     *
     * Here so the three can be compared on a real device against the same recording. Which is
     * fastest is a property of the chipset and the model rather than something this app can decide
     * in advance -- see [OnnxProvider].
     */
    val onnxProvider: OnnxProvider = OnnxProvider.DEFAULT,
    /**
     * How fast audio is fed to the system recogniser, for the platform STT backend.
     *
     * Here for the same reason [onnxProvider] is: the rates have to be compared on a real device
     * against the same recording, and rebuilding per rate makes that comparison too expensive to
     * run. Read when a transcriber is constructed -- once per recording for pre-decoding, once per
     * job in the worker -- so a single run is never fed at two different rates. See
     * [PlatformFeedPace] for why the feed is paced at all.
     */
    val platformFeedPace: PlatformFeedPace = PlatformFeedPace.DEFAULT,
    /**
     * How much audio goes into each write on that same path.
     *
     * Separate from [platformFeedPace] because the two were confounded in the measurement that
     * produced the pacing default -- see [PlatformFeedChunk] for the table of what that leaves
     * untested. Read at the same moment [platformFeedPace] is, so one run is fed one way throughout.
     */
    val platformFeedChunk: PlatformFeedChunk = PlatformFeedChunk.DEFAULT,
    /**
     * Which language pack the system recogniser is asked for, as a BCP-47 tag. Null means the
     * device locale.
     *
     * Named rather than inherited because inheriting it is a measured mistake: a shared scoring
     * protocol for this engine requires the exact installed pack, and reports 4.0% against 11.6% on
     * identical audio when a generic "en" resolved to whichever regional pack was preinstalled. The
     * device locale here is `en-IN`, which is that failure exactly.
     */
    val platformLanguage: String? = null,
    /**
     * How long one slice of audio may be before it is transcribed.
     *
     * Only Parakeet reads it -- see [SliceWindow] for why the other recognisers have no say in the
     * matter. Kept here rather than on the benchmark screen that sets it because a benchmark is only
     * worth running if it measures what real recordings do; a screen-local copy would let the two
     * diverge, which is the failure the platform feed settings already document.
     */
    val sliceWindow: SliceWindow = SliceWindow.DEFAULT,
) {
    /**
     * What engines load with, as opposed to [sampling], which is what the Settings sliders show.
     *
     * Keeping the two apart is what lets "Reproducible output" override the sampler without
     * destroying the values the user chose: flip it off and their temperature/top-K/top-P are still
     * there, because they were never overwritten -- only bypassed.
     */
    val effectiveSampling: SamplingParams
        get() = if (reproducibleOutput) {
            sampling.greedy(SettingsStore.REPRODUCIBLE_SEED)
        } else {
            sampling.copy(seed = SamplingParams.SEED_RANDOM)
        }
}

/** Four settings and no async needs, so SharedPreferences rather than a DataStore dependency. */
class SettingsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("ai_agent_settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    // NOTE: an earlier version of this class deleted KEY_LEGACY_SYSTEM_PROMPT here, to tidy away a
    // key nothing reads any more. That destroyed a user's saved prompt on first launch of the new
    // build, with no way back. A stale key costs a few bytes; the text someone wrote costs them
    // their work. Orphaned keys are left alone.

    private fun load(): AppSettings {
        return AppSettings(
            preferredEngine = prefs.getString(KEY_ENGINE, null)
                ?.let { slug -> EngineId.entries.firstOrNull { it.slug == slug } },
            preferredAccelerator = prefs.getString(KEY_ACCELERATOR, null)
                ?.let { name -> Accelerator.entries.firstOrNull { it.name == name } }
                ?: Accelerator.GPU,
            sampling = SamplingParams(
                temperature = prefs.getFloat(KEY_TEMPERATURE, 0.8f),
                topK = prefs.getInt(KEY_TOP_K, 40),
                topP = prefs.getFloat(KEY_TOP_P, 0.95f),
                maxOutputTokens = prefs.getInt(KEY_MAX_OUTPUT_TOKENS, SamplingParams.UNLIMITED),
                stopSequences = decodeStops(prefs.getString(KEY_STOP_SEQUENCES, null)),
            ),
            appFunctionsEnabled = prefs.getBoolean(KEY_APP_FUNCTIONS, true),
            activeModelId = prefs.getString(KEY_ACTIVE_MODEL, null),
            tavilyApiKey = prefs.getString(KEY_TAVILY_KEY, null),
            speechModelId = prefs.getString(KEY_SPEECH_MODEL, null),
            speakerBundleId = prefs.getString(KEY_SPEAKER_BUNDLE, null),
            // Absent means never chosen, which the record screen resolves for itself. An
            // unrecognised slug is treated the same way rather than silently becoming the default.
            sttBackend = prefs.getString(KEY_STT_BACKEND, null)
                ?.let { slug -> SttBackend.entries.firstOrNull { it.slug == slug } },
            // An unrecognised name falls back to DETAILED, which is what every note was before the
            // choice existed -- see NoteSummaryMode.from.
            noteSummaryMode = NoteSummaryMode.from(prefs.getString(KEY_NOTE_SUMMARY_MODE, null)),
            threadCount = prefs.getInt(KEY_THREAD_COUNT, 0),
            reproducibleOutput = prefs.getBoolean(KEY_REPRODUCIBLE, false),
            maxToolHops = prefs.getInt(KEY_MAX_TOOL_HOPS, 4).coerceAtLeast(1),
            thinkingEnabled = prefs.getBoolean(KEY_THINKING, true),
            keywordMarkersEnabled = prefs.getBoolean(KEY_KEYWORD_MARKERS, false),
            vadEnabled = prefs.getBoolean(KEY_VAD, true),
            onnxProvider = OnnxProvider.fromSlug(prefs.getString(KEY_ONNX_PROVIDER, null)),
            platformFeedPace = PlatformFeedPace.fromSlug(prefs.getString(KEY_PLATFORM_FEED_PACE, null)),
            platformFeedChunk = PlatformFeedChunk.fromSlug(prefs.getString(KEY_PLATFORM_FEED_CHUNK, null)),
            platformLanguage = prefs.getString(KEY_PLATFORM_LANGUAGE, null),
            sliceWindow = SliceWindow.fromSlug(prefs.getString(KEY_SLICE_WINDOW, null)),
        )
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        // What is stored is always the user's own sampling values, never the reproducible override --
        // AppSettings.effectiveSampling derives that on read. Persisting the override instead would
        // silently overwrite their sliders the moment the toggle went on.
        val next = transform(_settings.value)
        prefs.edit {
            putString(KEY_ENGINE, next.preferredEngine?.slug)
            putString(KEY_ACCELERATOR, next.preferredAccelerator.name)
            putFloat(KEY_TEMPERATURE, next.sampling.temperature)
            putInt(KEY_TOP_K, next.sampling.topK)
            putFloat(KEY_TOP_P, next.sampling.topP)
            putInt(KEY_MAX_OUTPUT_TOKENS, next.sampling.maxOutputTokens)
            putString(KEY_STOP_SEQUENCES, next.sampling.stopSequences.joinToString("\n"))
            putBoolean(KEY_APP_FUNCTIONS, next.appFunctionsEnabled)
            putString(KEY_ACTIVE_MODEL, next.activeModelId)
            putString(KEY_TAVILY_KEY, next.tavilyApiKey)
            putString(KEY_SPEECH_MODEL, next.speechModelId)
            putString(KEY_SPEAKER_BUNDLE, next.speakerBundleId)
            putString(KEY_STT_BACKEND, next.sttBackend?.slug)
            putString(KEY_NOTE_SUMMARY_MODE, next.noteSummaryMode.name)
            putInt(KEY_THREAD_COUNT, next.threadCount)
            putBoolean(KEY_REPRODUCIBLE, next.reproducibleOutput)
            putInt(KEY_MAX_TOOL_HOPS, next.maxToolHops)
            putBoolean(KEY_THINKING, next.thinkingEnabled)
            putBoolean(KEY_KEYWORD_MARKERS, next.keywordMarkersEnabled)
            putBoolean(KEY_VAD, next.vadEnabled)
            putString(KEY_ONNX_PROVIDER, next.onnxProvider.slug)
            putString(KEY_PLATFORM_FEED_PACE, next.platformFeedPace.slug)
            putString(KEY_PLATFORM_FEED_CHUNK, next.platformFeedChunk.slug)
            putString(KEY_PLATFORM_LANGUAGE, next.platformLanguage)
            putString(KEY_SLICE_WINDOW, next.sliceWindow.slug)
        }
        _settings.value = next
    }

    /** Stop strings are stored one per line, so a stop string cannot itself contain a newline. */
    private fun decodeStops(raw: String?): List<String> =
        raw?.split("\n")?.filter { it.isNotEmpty() } ?: emptyList()

    companion object {
        /** Any fixed non-[SamplingParams.SEED_RANDOM] value; the number itself is arbitrary. */
        const val REPRODUCIBLE_SEED = 1234

        private const val KEY_SPEAKER_BUNDLE = "speaker_bundle"
        private const val KEY_ENGINE = "engine"
        private const val KEY_ACCELERATOR = "accelerator"
        private const val KEY_TEMPERATURE = "temperature"
        private const val KEY_TOP_K = "top_k"
        private const val KEY_TOP_P = "top_p"
        private const val KEY_MAX_OUTPUT_TOKENS = "max_output_tokens"
        private const val KEY_STOP_SEQUENCES = "stop_sequences"
        /** Written by builds that let the user edit the system prompt; read by nothing, cleared on load. */
        private const val KEY_LEGACY_SYSTEM_PROMPT = "system_prompt"
        private const val KEY_APP_FUNCTIONS = "app_functions"
        private const val KEY_ACTIVE_MODEL = "active_model"
        private const val KEY_TAVILY_KEY = "tavily_api_key"
        private const val KEY_SPEECH_MODEL = "speech_model"
        private const val KEY_STT_BACKEND = "stt_backend"
        private const val KEY_NOTE_SUMMARY_MODE = "note_summary_mode"
        private const val KEY_THREAD_COUNT = "thread_count"
        private const val KEY_REPRODUCIBLE = "reproducible_output"
        private const val KEY_MAX_TOOL_HOPS = "max_tool_hops"
        private const val KEY_THINKING = "thinking_enabled"
        private const val KEY_KEYWORD_MARKERS = "keyword_markers_enabled"
        private const val KEY_VAD = "vad_enabled"
        private const val KEY_ONNX_PROVIDER = "onnx_provider"
        private const val KEY_PLATFORM_FEED_PACE = "platform_feed_pace"
        private const val KEY_PLATFORM_FEED_CHUNK = "platform_feed_chunk"
        private const val KEY_PLATFORM_LANGUAGE = "platform_language"
        private const val KEY_SLICE_WINDOW = "slice_window"
    }
}
