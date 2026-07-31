package com.example.aiagenttestapp.data

import android.content.Context
import com.example.aiagent.engine.core.Accelerator
import com.example.aiagent.engine.core.EngineId
import com.example.aiagent.engine.core.SamplingParams
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
     * Whether recordings are diarised and labelled with enrolled speakers' names.
     *
     * Off by default, and it gates a ~35 MB download: someone who only ever dictates solo notes
     * should not pay for two models they will never use.
     */
    val speakerIdEnabled: Boolean = false,
    /**
     * Whether the keyword spotter listens for spoken markers ("start non conformity") while
     * recording. Off by default; gates a ~16 MB download.
     */
    val keywordMarkersEnabled: Boolean = false,
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
            threadCount = prefs.getInt(KEY_THREAD_COUNT, 0),
            reproducibleOutput = prefs.getBoolean(KEY_REPRODUCIBLE, false),
            maxToolHops = prefs.getInt(KEY_MAX_TOOL_HOPS, 4).coerceAtLeast(1),
            thinkingEnabled = prefs.getBoolean(KEY_THINKING, true),
            speakerIdEnabled = prefs.getBoolean(KEY_SPEAKER_ID, false),
            keywordMarkersEnabled = prefs.getBoolean(KEY_KEYWORD_MARKERS, false),
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
            putInt(KEY_THREAD_COUNT, next.threadCount)
            putBoolean(KEY_REPRODUCIBLE, next.reproducibleOutput)
            putInt(KEY_MAX_TOOL_HOPS, next.maxToolHops)
            putBoolean(KEY_THINKING, next.thinkingEnabled)
            putBoolean(KEY_SPEAKER_ID, next.speakerIdEnabled)
            putBoolean(KEY_KEYWORD_MARKERS, next.keywordMarkersEnabled)
        }
        _settings.value = next
    }

    /** Stop strings are stored one per line, so a stop string cannot itself contain a newline. */
    private fun decodeStops(raw: String?): List<String> =
        raw?.split("\n")?.filter { it.isNotEmpty() } ?: emptyList()

    companion object {
        /** Any fixed non-[SamplingParams.SEED_RANDOM] value; the number itself is arbitrary. */
        const val REPRODUCIBLE_SEED = 1234

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
        private const val KEY_THREAD_COUNT = "thread_count"
        private const val KEY_REPRODUCIBLE = "reproducible_output"
        private const val KEY_MAX_TOOL_HOPS = "max_tool_hops"
        private const val KEY_THINKING = "thinking_enabled"
        private const val KEY_SPEAKER_ID = "speaker_id_enabled"
        private const val KEY_KEYWORD_MARKERS = "keyword_markers_enabled"
    }
}
