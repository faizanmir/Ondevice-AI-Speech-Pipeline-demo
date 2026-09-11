package com.example.aiagenttestapp.data

import com.example.aiagent.llm.LlmConfig
import com.example.aiagent.llm.LlmConfigSource

/**
 * The one place this app's settings become [LlmConfig] -- the twin of `SttSettings`.
 *
 * It lives here rather than in `:llm` for the reason the whole split exists: the library states what
 * it needs, the host decides how to answer. `AppSettings` has twenty-odd fields and only these four
 * mean anything to model loading.
 */
fun AppSettings.toLlmConfig() = LlmConfig(
    preferredEngine = preferredEngine,
    preferredAccelerator = preferredAccelerator,
    // `effectiveSampling`, not `sampling`: the reproducible-output flag is resolved here, so the
    // planner receives what an engine should actually be loaded with rather than the raw values the
    // Settings sliders hold.
    sampling = effectiveSampling,
    threadCount = threadCount,
)

fun SettingsStore.asLlmConfigSource() = LlmConfigSource { settings.value.toLlmConfig() }
