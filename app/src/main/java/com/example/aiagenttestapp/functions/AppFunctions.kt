package com.example.aiagenttestapp.functions

import com.example.aiagent.engine.core.ParamBudget
import com.example.aiagent.engine.core.Quantization
import com.example.aiagent.engine.core.SamplingParams
import com.example.aiagent.engine.core.ToolCall
import com.example.aiagent.engine.core.ToolDefinition
import com.example.aiagent.engine.core.ToolParameter
import com.example.aiagenttestapp.functions.AppFunctionDeps
import com.example.aiagenttestapp.data.Source
import com.example.aiagenttestapp.ui.components.formatBytes
import com.example.aiagenttestapp.ui.components.formatParams

/** Where a function wants the app to go, if anywhere. Collected by the nav host. */
sealed interface AppNavigation {
    data object Settings : AppNavigation
    data object Catalog : AppNavigation
    data class HuggingFace(val query: String?) : AppNavigation
}

/**
 * What running a function did.
 *
 * [output] is written for the *model* to read back, so it is dense and factual. The chat shows
 * [summary] to the user instead -- one short line, in their language.
 */
data class AppFunctionResult(
    val summary: String,
    val output: String,
    val navigation: AppNavigation? = null,
    val isError: Boolean = false,
    /** Web pages this call drew on, surfaced to the user as citations under the reply. */
    val sources: List<Source> = emptyList(),
)

/**
 * One capability the model can invoke.
 *
 * Shaped like an Android AppFunction on purpose: a name, a description written for an agent to
 * read, typed parameters, and a body with a side effect. Today the caller is the on-device model in
 * this app; the same registry is what you would expose through `androidx.appfunctions` to let
 * system agents call it too, which is why the definition is kept separate from the invocation.
 */
class AppFunction(
    val definition: ToolDefinition,
    val invoke: suspend (args: Map<String, String>, deps: AppFunctionDeps) -> AppFunctionResult,
)

/**
 * Everything the model is allowed to do to this app.
 *
 * Kept small and blunt. A 1B model asked to choose between twenty overlapping tools picks badly, so
 * each entry here is a distinct user intent with an unmistakable description, and there are no two
 * tools that could plausibly answer the same request.
 */
object AppFunctions {

    private val openSettings = AppFunction(
        ToolDefinition(
            name = "open_settings",
            description = "Open the app's Settings screen: engine, accelerator and sampling.",
        ),
    ) { _, _ ->
        AppFunctionResult(
            summary = "Opened Settings",
            output = "The Settings screen is now open.",
            navigation = AppNavigation.Settings,
        )
    }

    private val openModelCatalog = AppFunction(
        ToolDefinition(
            name = "open_model_catalog",
            description = "Show the AI models that can be downloaded and run on this phone.",
        ),
    ) { _, _ ->
        AppFunctionResult(
            summary = "Opened the model catalogue",
            output = "The model catalogue is now open.",
            navigation = AppNavigation.Catalog,
        )
    }

    private val searchHuggingFace = AppFunction(
        ToolDefinition(
            name = "search_huggingface",
            description = "Search HuggingFace for new AI models to add to the app.",
            parameters = listOf(
                ToolParameter(
                    name = "query",
                    description = "What to search for, e.g. \"qwen\" or \"phi\".",
                    required = false,
                ),
            ),
        ),
    ) { args, _ ->
        val query = args["query"]?.takeIf { it.isNotBlank() }
        AppFunctionResult(
            summary = if (query != null) "Searched HuggingFace for \"$query\"" else "Opened HuggingFace",
            output = "The HuggingFace browser is now open" +
                (query?.let { ", searching for \"$it\"." } ?: "."),
            navigation = AppNavigation.HuggingFace(query),
        )
    }

    /**
     * The one function that genuinely needs the app's own knowledge. A model cannot know how much
     * RAM the phone has, so this is the difference between it guessing and it being right.
     */
    private val getDeviceMemory = AppFunction(
        ToolDefinition(
            name = "get_device_memory",
            description = "How much RAM and storage this phone has, and the largest model it " +
                "can run. Use whenever the user asks about RAM, storage, or what size of model " +
                "fits on their device.",
        ),
    ) { _, deps ->
        val device = deps.deviceMemory
        val maxParams = ParamBudget.maxRunnableParams(
            device = device,
            quantization = Quantization.Q4,
            contextTokens = 4096,
        )

        AppFunctionResult(
            summary = "Checked this device's memory",
            output = buildString {
                appendLine("RAM: ${device.advertisedRamGb} GB")
                appendLine("Usable for a model: ${formatBytes(device.modelRamBudgetBytes)}")
                appendLine("Free storage: ${formatBytes(device.freeStorageBytes)}")
                appendLine(
                    "Largest model that fits: about ${formatParams(maxParams)} parameters " +
                        "at 4-bit precision.",
                )
                device.socModel?.let { appendLine("Chipset: $it") }
            },
        )
    }

    private val listDownloadedModels = AppFunction(
        ToolDefinition(
            name = "list_downloaded_models",
            description = "List the AI models already downloaded on this phone.",
        ),
    ) { _, deps ->
        val models = deps.models.snapshot()
            .filter { deps.modelRepository.isDownloaded(it) }

        AppFunctionResult(
            summary = "Listed downloaded models",
            output = if (models.isEmpty()) {
                "No models are downloaded yet."
            } else {
                models.joinToString("\n") { model ->
                    "- ${model.name} (${model.paramsLabel}, ${formatBytes(model.sizeBytes)})"
                }
            },
        )
    }

    private val getCurrentModel = AppFunction(
        ToolDefinition(
            name = "get_current_model",
            description = "Which model, engine and accelerator are running now. Use when the " +
                "user asks what model they are talking to.",
        ),
    ) { _, deps ->
        val engine = deps.engines.all.firstOrNull { it.loadedModelPath != null }

        AppFunctionResult(
            summary = "Checked the running model",
            output = if (engine == null) {
                "No model is loaded."
            } else {
                val fileName = engine.loadedModelPath?.substringAfterLast('/')
                "Model file: $fileName\n" +
                    "Engine: ${engine.descriptor.displayName}\n" +
                    "Running on: ${engine.activeAccelerator?.label ?: "unknown"}"
            },
        )
    }

    /**
     * A function with a *visible* side effect the user can go and verify. Navigation is easy to
     * fake convincingly; changing a number that then shows up on the Settings screen is not.
     */
    private val setTemperature = AppFunction(
        ToolDefinition(
            name = "set_temperature",
            description = "Change how creative or focused replies are: lower is more focused " +
                "and repeatable, higher more varied.",
            parameters = listOf(
                ToolParameter(
                    name = "value",
                    description = "A number between 0.0 and 2.0.",
                    type = "number",
                ),
            ),
        ),
    ) { args, deps ->
        val value = args["value"]?.toFloatOrNull()

        if (value == null || value !in 0f..2f) {
            AppFunctionResult(
                summary = "Could not set the temperature",
                output = "\"${args["value"]}\" is not a number between 0.0 and 2.0.",
                isError = true,
            )
        } else {
            deps.settingsStore.update { settings ->
                settings.copy(sampling = settings.sampling.copy(temperature = value))
            }
            AppFunctionResult(
                summary = "Set temperature to $value",
                output = "Temperature is now $value. It applies to the next conversation.",
            )
        }
    }

    private val resetSampling = AppFunction(
        ToolDefinition(
            name = "reset_sampling",
            description = "Put the AI's creativity settings back to their defaults.",
        ),
    ) { _, deps ->
        deps.settingsStore.update { it.copy(sampling = SamplingParams()) }
        AppFunctionResult(
            summary = "Reset sampling to defaults",
            output = "Sampling is back to the defaults: temperature 0.8, top-P 0.95, top-K 40.",
        )
    }

    /**
     * The one function that reaches outside the device. The model never touches the network itself:
     * it emits this call, the app runs the Tavily request, and the results come back into its
     * context. Opt-in -- offered only when a key is set (see [definitionsFor]).
     */
    private val webSearch = AppFunction(
        ToolDefinition(
            name = "web_search",
            description = "Search the web for current, real-time or niche information -- news, " +
                "prices, recent events, documentation, specific facts. Use it whenever the answer " +
                "may have changed since your training, or you are unsure of a fact: search rather " +
                "than guessing or replying that you do not know.",
            parameters = listOf(
                ToolParameter(
                    name = "query",
                    description = "The search query, phrased as you would type it into a search engine.",
                ),
            ),
        ),
    ) { args, deps ->
        val query = args["query"]?.trim().orEmpty()
        if (query.isBlank()) {
            return@AppFunction AppFunctionResult(
                summary = "No search query",
                output = "No search query was provided.",
                isError = true,
            )
        }

        // Fail fast when offline rather than making the model wait out a connection timeout. The
        // model cannot see the network state itself, so it may reach for search while disconnected.
        if (!deps.networkMonitor.isOnline()) {
            return@AppFunction AppFunctionResult(
                summary = "Sorry, device is not connected",
                output = "This device is not connected to the internet, so the web search could not " +
                    "run. Tell the user, in their language: \"Sorry, device is not connected.\" Then " +
                    "answer from what you already know if you can, and do not try to search again.",
                isError = true,
            )
        }

        val key = deps.settingsStore.settings.value.tavilyApiKey?.takeIf { it.isNotBlank() }
            ?: return@AppFunction AppFunctionResult(
                summary = "Web search is not set up",
                output = "Web search is unavailable: no Tavily API key is configured in Settings.",
                isError = true,
            )

        deps.webSearch.search(query, key).fold(
            onSuccess = { result ->
                AppFunctionResult(
                    summary = "Searched the web for \"$query\"",
                    output = result.text,
                    sources = result.sources,
                )
            },
            onFailure = { e ->
                AppFunctionResult(
                    summary = "Web search failed",
                    output = e.message ?: "The web search failed.",
                    isError = true,
                )
            },
        )
    }

    /**
     * Reads one page. Pairs with [webSearch]: the model searches, gets URLs, then fetches the one it
     * wants. Also handles a URL the user pastes directly. Opt-in on the same Tavily key.
     */
    private val fetchUrl = AppFunction(
        ToolDefinition(
            name = "fetch_url",
            description = "Read the full text of a web page you already have the URL for -- " +
                "from web_search results, or one the user gave you. Use web_search first if you " +
                "do not yet have a URL.",
            parameters = listOf(
                ToolParameter(
                    name = "url",
                    description = "The full URL, including the https:// prefix.",
                ),
            ),
        ),
    ) { args, deps ->
        val url = args["url"]?.trim().orEmpty()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return@AppFunction AppFunctionResult(
                summary = "Invalid URL",
                output = "\"$url\" is not a valid http(s) URL.",
                isError = true,
            )
        }

        // Same as web_search: reading a page needs the network, so fail fast and clearly when offline.
        if (!deps.networkMonitor.isOnline()) {
            return@AppFunction AppFunctionResult(
                summary = "Sorry, device is not connected",
                output = "This device is not connected to the internet, so the page could not be " +
                    "read. Tell the user, in their language: \"Sorry, device is not connected.\"",
                isError = true,
            )
        }

        val key = deps.settingsStore.settings.value.tavilyApiKey?.takeIf { it.isNotBlank() }
            ?: return@AppFunction AppFunctionResult(
                summary = "Web access is not set up",
                output = "Reading pages is unavailable: no Tavily API key is configured in Settings.",
                isError = true,
            )

        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: "the page"
        deps.webSearch.fetchUrl(url, key).fold(
            onSuccess = { page ->
                AppFunctionResult(summary = "Read $host", output = page.text, sources = page.sources)
            },
            onFailure = { e ->
                AppFunctionResult(
                    summary = "Could not read the page",
                    output = e.message ?: "The page could not be read.",
                    isError = true,
                )
            },
        )
    }

    /** The app-driving functions, always available whenever tool calling is on. */
    private val local: List<AppFunction> = listOf(
        openSettings,
        openModelCatalog,
        searchHuggingFace,
        getDeviceMemory,
        listDownloadedModels,
        getCurrentModel,
        setTemperature,
        resetSampling,
    )

    val all: List<AppFunction> = local + webSearch + fetchUrl

    /**
     * Tool definitions offered to the model. The web tools are included only when web access is
     * configured, so a model without a key is never told about tools it cannot use.
     */
    fun definitionsFor(webAccessEnabled: Boolean): List<ToolDefinition> =
        local.map { it.definition } +
            if (webAccessEnabled) listOf(webSearch.definition, fetchUrl.definition) else emptyList()

    private val byName: Map<String, AppFunction> = all.associateBy { it.definition.name }

    /** Runs [call], or reports back that no such function exists so the model can recover. */
    suspend fun execute(call: ToolCall, deps: AppFunctionDeps): AppFunctionResult {
        val function = byName[call.name]
            ?: return AppFunctionResult(
                summary = "Unknown function \"${call.name}\"",
                output = "There is no function called \"${call.name}\". " +
                    "Available: ${byName.keys.joinToString(", ")}.",
                isError = true,
            )

        return try {
            function.invoke(call.arguments, deps)
        } catch (e: Exception) {
            AppFunctionResult(
                summary = "\"${call.name}\" failed",
                output = e.message ?: "The function threw an error.",
                isError = true,
            )
        }
    }
}
