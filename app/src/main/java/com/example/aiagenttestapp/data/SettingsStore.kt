package com.example.aiagenttestapp.data

import android.content.Context
import com.example.aiagent.engine.core.Accelerator
import com.example.aiagent.engine.core.EngineId
import com.example.aiagent.engine.core.SamplingParams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppSettings(
    /** Null = let the app pick the first engine that can load the chosen model. */
    val preferredEngine: EngineId? = null,
    val preferredAccelerator: Accelerator = Accelerator.GPU,
    val sampling: SamplingParams = SamplingParams(),
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
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
) {
    companion object {
        const val DEFAULT_SYSTEM_PROMPT =
            "You are a helpful assistant running entirely on the user's phone. " +
                "Be concise and accurate."
    }
}

/** Four settings and no async needs, so SharedPreferences rather than a DataStore dependency. */
class SettingsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("ai_agent_settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private fun load(): AppSettings = AppSettings(
        preferredEngine = prefs.getString(KEY_ENGINE, null)
            ?.let { slug -> EngineId.entries.firstOrNull { it.slug == slug } },
        preferredAccelerator = prefs.getString(KEY_ACCELERATOR, null)
            ?.let { name -> Accelerator.entries.firstOrNull { it.name == name } }
            ?: Accelerator.GPU,
        sampling = SamplingParams(
            temperature = prefs.getFloat(KEY_TEMPERATURE, 0.8f),
            topK = prefs.getInt(KEY_TOP_K, 40),
            topP = prefs.getFloat(KEY_TOP_P, 0.95f),
            seed = SamplingParams.SEED_RANDOM,
        ),
        systemPrompt = prefs.getString(KEY_SYSTEM_PROMPT, null)
            ?: AppSettings.DEFAULT_SYSTEM_PROMPT,
        appFunctionsEnabled = prefs.getBoolean(KEY_APP_FUNCTIONS, true),
        activeModelId = prefs.getString(KEY_ACTIVE_MODEL, null),
        tavilyApiKey = prefs.getString(KEY_TAVILY_KEY, null),
        speechModelId = prefs.getString(KEY_SPEECH_MODEL, null),
    )

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        prefs.edit().apply {
            putString(KEY_ENGINE, next.preferredEngine?.slug)
            putString(KEY_ACCELERATOR, next.preferredAccelerator.name)
            putFloat(KEY_TEMPERATURE, next.sampling.temperature)
            putInt(KEY_TOP_K, next.sampling.topK)
            putFloat(KEY_TOP_P, next.sampling.topP)
            putString(KEY_SYSTEM_PROMPT, next.systemPrompt)
            putBoolean(KEY_APP_FUNCTIONS, next.appFunctionsEnabled)
            putString(KEY_ACTIVE_MODEL, next.activeModelId)
            putString(KEY_TAVILY_KEY, next.tavilyApiKey)
            putString(KEY_SPEECH_MODEL, next.speechModelId)
        }.apply()
        _settings.value = next
    }

    private companion object {
        const val KEY_ENGINE = "engine"
        const val KEY_ACCELERATOR = "accelerator"
        const val KEY_TEMPERATURE = "temperature"
        const val KEY_TOP_K = "top_k"
        const val KEY_TOP_P = "top_p"
        const val KEY_SYSTEM_PROMPT = "system_prompt"
        const val KEY_APP_FUNCTIONS = "app_functions"
        const val KEY_ACTIVE_MODEL = "active_model"
        const val KEY_TAVILY_KEY = "tavily_api_key"
        const val KEY_SPEECH_MODEL = "speech_model"
    }
}
