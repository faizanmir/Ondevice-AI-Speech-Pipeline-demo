package com.example.aiagenttestapp.functions

import com.example.aiagent.engine.core.DeviceMemoryProfile
import com.example.aiagent.engine.core.EngineRegistry
import com.example.aiagenttestapp.data.ModelDirectory
import com.example.aiagenttestapp.data.ModelRepository
import com.example.aiagenttestapp.data.NetworkMonitor
import com.example.aiagenttestapp.data.SettingsStore
import com.example.aiagenttestapp.data.WebSearchClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What an [AppFunction] body is allowed to touch.
 *
 * The functions used to be handed the whole container, which meant the set of things a model could
 * reach was "everything in the app". This names it instead: adding a capability to a function is now
 * a visible change to this type rather than an invisible one at a call site.
 */
@Singleton
class AppFunctionDeps @Inject constructor(
    val settingsStore: SettingsStore,
    val modelRepository: ModelRepository,
    val models: ModelDirectory,
    val engines: EngineRegistry,
    val deviceMemory: DeviceMemoryProfile,
    val networkMonitor: NetworkMonitor,
    val webSearch: WebSearchClient,
)
