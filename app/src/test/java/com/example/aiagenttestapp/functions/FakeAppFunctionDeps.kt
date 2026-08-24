package com.example.aiagenttestapp.functions

import com.example.aiagent.engine.core.DeviceMemoryProfile
import com.example.aiagent.engine.core.InferenceEngine
import com.example.aiagent.engine.core.ModelSpec
import com.example.aiagenttestapp.data.AppSettings
import com.example.aiagenttestapp.data.WebSearchResult

/**
 * A whole [AppFunctionDeps] built out of plain values, so a function body can be run on the JVM.
 *
 * Every field is a `var` a test can set before the call and read after it -- what the function
 * changed is as much the subject as what it returned, since several of these functions exist
 * precisely for their side effect.
 */
class FakeAppFunctionDeps(
    var settings: AppSettings = AppSettings(),
    var catalogue: List<ModelSpec> = emptyList(),
    var downloaded: Set<String> = emptySet(),
    var loadedEngines: List<InferenceEngine> = emptyList(),
    override var deviceMemory: DeviceMemoryProfile = DeviceMemoryProfile(
        advertisedRamBytes = 8L * GB,
        totalRamBytes = 8L * GB,
        availableRamBytes = 4L * GB,
        lowMemoryThresholdBytes = 512L * 1024 * 1024,
        isLowRamDevice = false,
        freeStorageBytes = 32L * GB,
        socModel = "Test SoC",
        supportedAbis = listOf("arm64-v8a"),
    ),
    var online: Boolean = true,
    /** What the web calls return. Failure by default is wrong -- most tests want the happy path. */
    var searchResult: Result<WebSearchResult> = Result.success(WebSearchResult("results", emptyList())),
    var fetchResult: Result<WebSearchResult> = Result.success(WebSearchResult("page text", emptyList())),
) : AppFunctionDeps {

    /** Every search this test made, so a test can assert what the function actually asked for. */
    val searches = mutableListOf<Pair<String, String>>()
    val fetches = mutableListOf<Pair<String, String>>()

    private companion object {
        const val GB = 1024L * 1024L * 1024L
    }

    override val settingsStore = object : SettingsAccess {
        override val current: AppSettings get() = settings
        override fun update(transform: (AppSettings) -> AppSettings) {
            settings = transform(settings)
        }
    }

    override val modelRepository = object : DownloadedModels {
        override fun isDownloaded(model: ModelSpec) = model.id in downloaded
    }

    override val models = object : ModelCatalogue {
        override fun snapshot() = catalogue
    }

    override val engines = object : LoadedEngines {
        override val all get() = loadedEngines
    }

    override val networkMonitor = object : NetworkStatus {
        override fun isOnline() = online
    }

    override val webSearch = object : WebAccess {
        override suspend fun search(query: String, apiKey: String): Result<WebSearchResult> {
            searches += query to apiKey
            return searchResult
        }

        override suspend fun fetchUrl(url: String, apiKey: String): Result<WebSearchResult> {
            fetches += url to apiKey
            return fetchResult
        }
    }
}
