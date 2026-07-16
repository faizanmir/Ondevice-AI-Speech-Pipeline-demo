package com.example.aiagenttestapp.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Web search via Tavily, an LLM-oriented search API: it returns clean snippets and a synthesised
 * answer rather than raw HTML, which matters because the on-device model has only a few thousand
 * tokens of context to spend on results.
 *
 * The model never touches the network itself. It emits a `web_search` tool call, this makes the
 * request, and the text comes back into its context. The search *query* leaves the device; nothing
 * else does.
 */
class WebSearchClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Runs one search. Returns text formatted for the model, or a failure with a user-facing reason. */
    suspend fun search(query: String, apiKey: String): Result<String> = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("query", query)
            .put("search_depth", "basic")
            .put("max_results", MAX_RESULTS)
            .put("include_answer", true)
            .toString()

        val request = Request.Builder()
            .url("https://api.tavily.com/search")
            .header("Authorization", "Bearer $apiKey")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val reason = when (response.code) {
                        401, 403 -> "The Tavily API key was rejected -- check it in Settings."
                        429 -> "Tavily's rate limit was hit. Try again in a moment."
                        in 500..599 -> "Tavily is having trouble right now (HTTP ${response.code})."
                        else -> "Web search failed (HTTP ${response.code})."
                    }
                    return@withContext Result.failure(IOException(reason))
                }
                Result.success(formatForModel(query, JSONObject(body)))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Reads one page via Tavily's extract endpoint, which returns clean article text instead of raw
     * HTML -- again because the model has little context to spend. Returns text formatted for the
     * model, or a failure with a user-facing reason.
     */
    suspend fun fetchUrl(url: String, apiKey: String): Result<String> = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("urls", url) // Tavily accepts a single URL string or an array
            .put("extract_depth", "basic")
            .toString()

        val request = Request.Builder()
            .url("https://api.tavily.com/extract")
            .header("Authorization", "Bearer $apiKey")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val reason = when (response.code) {
                        401, 403 -> "The Tavily API key was rejected -- check it in Settings."
                        429 -> "Tavily's rate limit was hit. Try again in a moment."
                        in 500..599 -> "Tavily is having trouble right now (HTTP ${response.code})."
                        else -> "Could not read that page (HTTP ${response.code})."
                    }
                    return@withContext Result.failure(IOException(reason))
                }

                val json = JSONObject(body)
                val content = json.optJSONArray("results")
                    ?.takeIf { it.length() > 0 }
                    ?.getJSONObject(0)
                    ?.optString("raw_content")
                    .orEmpty()

                if (content.isBlank()) {
                    val failure = json.optJSONArray("failed_results")
                        ?.takeIf { it.length() > 0 }
                        ?.getJSONObject(0)
                        ?.optString("error")
                    return@withContext Result.failure(
                        IOException(failure?.takeIf { it.isNotBlank() } ?: "That page could not be read."),
                    )
                }

                Result.success(formatPage(url, content))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun formatPage(url: String, content: String): String = buildString {
        appendLine("Content of $url:")
        appendLine()
        append(content.take(PAGE_CHARS))
        if (content.length > PAGE_CHARS) append("\n\n[Page truncated to fit -- ask to read more if needed.]")
    }

    /** Compresses the response into the few hundred tokens the model can actually fit and cite. */
    private fun formatForModel(query: String, json: JSONObject): String = buildString {
        appendLine("Web search results for \"$query\":")
        appendLine()

        json.optString("answer").takeIf { it.isNotBlank() }?.let {
            appendLine("Summary: $it")
            appendLine()
        }

        val results = json.optJSONArray("results")
        if (results == null || results.length() == 0) {
            append("No results were found.")
            return@buildString
        }

        val count = minOf(results.length(), MAX_RESULTS)
        for (i in 0 until count) {
            val result = results.getJSONObject(i)
            val title = result.optString("title").ifBlank { "Untitled" }
            val content = result.optString("content").take(SNIPPET_CHARS)
            val url = result.optString("url")
            appendLine("${i + 1}. $title")
            appendLine("   $content")
            if (url.isNotBlank()) appendLine("   ($url)")
        }
        appendLine()
        append("Answer the user from these results, and mention the sources you relied on.")
    }

    private companion object {
        const val MAX_RESULTS = 4
        const val SNIPPET_CHARS = 300

        /** A single page is the model's main input for the turn, so it gets more room than a snippet
         *  -- but still bounded, or it swamps the few-thousand-token context. ~1k tokens. */
        const val PAGE_CHARS = 4_000
    }
}
