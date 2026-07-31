package com.example.aiagenttestapp.functions.tools

import com.example.aiagenttestapp.functions.AppFunction
import com.example.aiagenttestapp.functions.AppFunctionDeps
import com.example.aiagenttestapp.functions.AppFunctionResult
import com.example.aiagenttestapp.functions.TextParam
import java.net.URI

/**
 * The one function that reaches outside the device. The model never touches the network itself: it
 * emits this call, the app runs the Tavily request, and the results come back into its context.
 */
class WebSearch : AppFunction() {

    override val name = "web_search"

    override val description =
        "Search the web for current, real-time or niche information -- news, prices, recent " +
            "events, documentation, specific facts. Use it whenever the answer may have changed " +
            "since your training, or you are unsure of a fact: search rather than guessing or " +
            "replying that you do not know."

    val query = TextParam(
        name = "query",
        description = "The search query, phrased as you would type it into a search engine.",
    )

    override val parameters = listOf(query)

    override val requiresWebAccess = true

    override suspend fun run(
        arguments: Map<String, String>,
        deps: AppFunctionDeps,
    ): AppFunctionResult {
        val searchFor = query.read(arguments)
            ?: return AppFunctionResult(
                summary = "No search query",
                output = "No search query was provided.",
                isError = true,
            )

        deps.offlineResult("the web search could not run", andThen = ANSWER_FROM_MEMORY)
            ?.let { return it }

        val key = deps.tavilyKey()
            ?: return AppFunctionResult(
                summary = "Web search is not set up",
                output = "Web search is unavailable: no Tavily API key is configured in Settings.",
                isError = true,
            )

        return deps.webSearch.search(searchFor, key).fold(
            onSuccess = { result ->
                AppFunctionResult(
                    summary = "Searched the web for \"$searchFor\"",
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

    private companion object {
        const val ANSWER_FROM_MEMORY =
            " Then answer from what you already know if you can, and do not try to search again."
    }
}

/**
 * Reads one page. Pairs with [WebSearch]: the model searches, gets URLs, then fetches the one it
 * wants. Also handles a URL the user pastes directly. Opt-in on the same Tavily key.
 */
class FetchUrl : AppFunction() {

    override val name = "fetch_url"

    override val description =
        "Read the full text of a web page you already have the URL for -- from web_search " +
            "results, or one the user gave you. Use web_search first if you do not yet have a URL."

    val url = TextParam(
        name = "url",
        description = "The full URL, including the https:// prefix.",
    )

    override val parameters = listOf(url)

    override val requiresWebAccess = true

    override suspend fun run(
        arguments: Map<String, String>,
        deps: AppFunctionDeps,
    ): AppFunctionResult {
        val page = url.read(arguments).orEmpty()
        if (!page.startsWith("http://") && !page.startsWith("https://")) {
            return AppFunctionResult(
                summary = "Invalid URL",
                output = "\"$page\" is not a valid http(s) URL.",
                isError = true,
            )
        }

        deps.offlineResult("the page could not be read")?.let { return it }

        val key = deps.tavilyKey()
            ?: return AppFunctionResult(
                summary = "Web access is not set up",
                output = "Reading pages is unavailable: no Tavily API key is configured in Settings.",
                isError = true,
            )

        val host = runCatching { URI(page).host }.getOrNull() ?: "the page"
        return deps.webSearch.fetchUrl(page, key).fold(
            onSuccess = { fetched ->
                AppFunctionResult(summary = "Read $host", output = fetched.text, sources = fetched.sources)
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
}

/**
 * Fails fast when offline instead of making the model wait out a connection timeout.
 *
 * The model cannot see the network state itself, so it reaches for the web while disconnected. The
 * output tells it what to say *and* what not to do next, because a model told only "that failed"
 * retries the same call.
 */
private fun AppFunctionDeps.offlineResult(
    what: String,
    andThen: String = "",
): AppFunctionResult? = if (networkMonitor.isOnline()) {
    null
} else {
    AppFunctionResult(
        summary = "Sorry, device is not connected",
        output = "This device is not connected to the internet, so $what. Tell the user, in " +
            "their language: \"Sorry, device is not connected.\"$andThen",
        isError = true,
    )
}

private fun AppFunctionDeps.tavilyKey(): String? =
    settingsStore.current.tavilyApiKey?.takeIf { it.isNotBlank() }
