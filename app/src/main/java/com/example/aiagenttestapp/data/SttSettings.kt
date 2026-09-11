package com.example.aiagenttestapp.data

import com.example.aiagenttestapp.stt.SttConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * The one place this app's settings are translated into what the speech pipeline asks for.
 *
 * The whole point of [SttConfig] is that the pipeline does not know `AppSettings` exists. This file
 * is the seam that keeps it that way, and it is deliberately the *only* thing on either side that
 * knows both types. If a speech setting is added, it is added in two places -- the field on
 * `AppSettings`, and one line here -- and the compiler names the second one.
 */
fun AppSettings.toSttConfig() = SttConfig(
    onnxProvider = onnxProvider,
    speechModelId = speechModelId,
    sliceWindow = sliceWindow,
    diarizationEngine = diarizationEngine,
    platformFeedPace = platformFeedPace,
    platformFeedChunk = platformFeedChunk,
    platformLanguage = platformLanguage,
    vadEnabled = vadEnabled,
    keywordMarkersEnabled = keywordMarkersEnabled,
    threadCount = threadCount,
    diarizeChunkMinutes = diarizeChunkMinutes,
)

/**
 * The live view the pipeline is handed.
 *
 * `distinctUntilChanged` matters more than it looks: `AppSettings` carries sampling parameters, the
 * active model and an API key, none of which the speech path cares about. Without it, typing a
 * Tavily key one character at a time would re-emit a config that has not changed and re-point every
 * flow derived from it.
 */
fun SettingsStore.sttConfig(scope: CoroutineScope): StateFlow<SttConfig> =
    settings
        .map { it.toSttConfig() }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, settings.value.toSttConfig())
