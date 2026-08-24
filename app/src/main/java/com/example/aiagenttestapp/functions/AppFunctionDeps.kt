package com.example.aiagenttestapp.functions

import com.example.aiagent.engine.core.DeviceMemoryProfile
import com.example.aiagent.engine.core.EngineRegistry
import com.example.aiagent.engine.core.InferenceEngine
import com.example.aiagent.engine.core.ModelSpec
import com.example.aiagenttestapp.data.AppSettings
import com.example.aiagenttestapp.data.ModelDirectory
import com.example.aiagenttestapp.data.ModelRepository
import com.example.aiagenttestapp.data.NetworkMonitor
import com.example.aiagenttestapp.data.SettingsStore
import com.example.aiagenttestapp.data.WebSearchClient
import com.example.aiagenttestapp.data.WebSearchResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What an [AppFunction] body is allowed to touch.
 *
 * The functions used to be handed the whole container, which meant the set of things a model could
 * reach was "everything in the app". This names it instead: adding a capability to a function is a
 * visible change to this type rather than an invisible one at a call site.
 *
 * Each capability is its own interface rather than the concrete class that provides it, and the
 * interfaces are declared *here*, by the code that consumes them. That is what makes a function
 * body testable: `SettingsStore`, `ModelRepository` and `NetworkMonitor` all need a `Context`, so a
 * body typed against them can only be run on a device. Typed against these, a test supplies six
 * small fakes and runs the real body.
 *
 * They are also deliberately narrow -- [ModelCatalogue] is one method, not all of `ModelDirectory`
 * -- so what a function can do is bounded by what it can even name.
 */
interface AppFunctionDeps {
    val settingsStore: SettingsAccess
    val modelRepository: DownloadedModels
    val models: ModelCatalogue
    val engines: LoadedEngines
    val deviceMemory: DeviceMemoryProfile
    val networkMonitor: NetworkStatus
    val webSearch: WebAccess
}

/** Reading and changing the user's settings. */
interface SettingsAccess {
    val current: AppSettings
    fun update(transform: (AppSettings) -> AppSettings)
}

/** Whether a model's files are on disk. */
interface DownloadedModels {
    fun isDownloaded(model: ModelSpec): Boolean
}

/** Every model the app knows about, built-in and user-added. */
interface ModelCatalogue {
    fun snapshot(): List<ModelSpec>
}

/** The engines, for asking which of them is holding a model. */
interface LoadedEngines {
    val all: List<InferenceEngine>
}

/** Whether the device can reach the internet. */
interface NetworkStatus {
    fun isOnline(): Boolean
}

/** The web, as the two web functions use it. */
interface WebAccess {
    suspend fun search(query: String, apiKey: String): Result<WebSearchResult>
    suspend fun fetchUrl(url: String, apiKey: String): Result<WebSearchResult>
}

/**
 * The real thing, wiring each capability to the class that provides it.
 *
 * The adapters are one-liners because the concrete classes already have these methods -- the
 * interfaces were named after what the functions were already doing, not invented for them.
 */
@Singleton
class RealAppFunctionDeps @Inject constructor(
    private val settings: SettingsStore,
    private val repository: ModelRepository,
    private val directory: ModelDirectory,
    private val engineRegistry: EngineRegistry,
    override val deviceMemory: DeviceMemoryProfile,
    private val network: NetworkMonitor,
    private val web: WebSearchClient,
) : AppFunctionDeps {

    override val settingsStore = object : SettingsAccess {
        override val current: AppSettings get() = settings.settings.value
        override fun update(transform: (AppSettings) -> AppSettings) = settings.update(transform)
    }

    override val modelRepository = object : DownloadedModels {
        override fun isDownloaded(model: ModelSpec) = repository.isDownloaded(model)
    }

    override val models = object : ModelCatalogue {
        override fun snapshot() = directory.snapshot()
    }

    override val engines = object : LoadedEngines {
        override val all get() = engineRegistry.all
    }

    override val networkMonitor = object : NetworkStatus {
        override fun isOnline() = network.isOnline()
    }

    override val webSearch = object : WebAccess {
        override suspend fun search(query: String, apiKey: String) = web.search(query, apiKey)
        override suspend fun fetchUrl(url: String, apiKey: String) = web.fetchUrl(url, apiKey)
    }
}
