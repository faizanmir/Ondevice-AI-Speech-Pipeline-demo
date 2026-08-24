package com.example.aiagenttestapp.functions

import com.example.aiagent.engine.core.Accelerator
import com.example.aiagent.engine.core.ModelFormat
import com.example.aiagent.engine.core.ModelSpec
import com.example.aiagent.engine.core.Quantization
import com.example.aiagent.engine.core.ToolCall
import com.example.aiagenttestapp.data.Source
import com.example.aiagenttestapp.data.WebSearchResult
import com.example.aiagenttestapp.functions.tools.FetchUrl
import com.example.aiagenttestapp.functions.tools.ListDownloadedModels
import com.example.aiagenttestapp.functions.tools.OpenSettings
import com.example.aiagenttestapp.functions.tools.ResetSampling
import com.example.aiagenttestapp.functions.tools.SearchHuggingFace
import com.example.aiagenttestapp.functions.tools.SetTemperature
import com.example.aiagenttestapp.functions.tools.WebSearch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The function bodies, run for real against fake capabilities.
 *
 * These are the app's side effects -- the model changes a setting, reads the web, moves the user --
 * so the assertions are as much about what the deps *saw* as about what came back. The other half
 * is the `output` string, which is not cosmetic: it is the only thing the model reads, and it is
 * what decides whether it recovers from a failure or retries the same call forever.
 */
class AppFunctionBodyTest {

    private val deps = FakeAppFunctionDeps()

    private fun model(id: String) = ModelSpec(
        id = id,
        name = id,
        vendor = "test",
        paramsBillions = 1.0,
        quantization = Quantization.Q4,
        format = ModelFormat.GGUF,
        downloadUrl = "",
        fileName = "$id.gguf",
        sizeBytes = 1_000_000L,
        contextTokens = 4096,
        minDeviceMemoryGb = 4,
        accelerators = setOf(Accelerator.CPU),
        license = "",
        description = "",
    )

    // ---- navigation ---------------------------------------------------------------------------

    @Test
    fun `open_settings asks the app to navigate`() = runTest {
        val result = OpenSettings().run(emptyMap(), deps)

        assertEquals(AppNavigation.Settings, result.navigation)
        assertFalse(result.isError)
    }

    @Test
    fun `search_huggingface carries the query into the navigation`() = runTest {
        val result = SearchHuggingFace().run(mapOf("query" to "qwen"), deps)

        assertEquals(AppNavigation.HuggingFace("qwen"), result.navigation)
        assertTrue(result.summary.contains("qwen"))
    }

    @Test
    fun `search_huggingface with no query still opens the browser`() = runTest {
        // The parameter is optional, so a bare call is a legitimate use, not a malformed one.
        val result = SearchHuggingFace().run(emptyMap(), deps)

        assertEquals(AppNavigation.HuggingFace(null), result.navigation)
        assertFalse(result.isError)
    }

    // ---- settings side effects ----------------------------------------------------------------

    @Test
    fun `set_temperature changes the setting`() = runTest {
        val result = SetTemperature().run(mapOf("value" to "0.4"), deps)

        assertEquals(0.4f, deps.settings.sampling.temperature)
        assertFalse(result.isError)
    }

    @Test
    fun `set_temperature rejects a value out of range and changes nothing`() = runTest {
        val before = deps.settings.sampling.temperature

        val result = SetTemperature().run(mapOf("value" to "5.0"), deps)

        // Clamping silently would hide the misunderstanding from the model *and* the user.
        assertTrue(result.isError)
        assertEquals(before, deps.settings.sampling.temperature)
        assertTrue("the model needs to see what it sent", result.output.contains("5.0"))
    }

    @Test
    fun `set_temperature rejects prose where a number was asked for`() = runTest {
        val result = SetTemperature().run(mapOf("value" to "very creative"), deps)

        assertTrue(result.isError)
        assertTrue(result.output.contains("very creative"))
    }

    @Test
    fun `reset_sampling puts the defaults back`() = runTest {
        SetTemperature().run(mapOf("value" to "1.9"), deps)

        ResetSampling().run(emptyMap(), deps)

        assertEquals(0.8f, deps.settings.sampling.temperature)
    }

    // ---- reading app state --------------------------------------------------------------------

    @Test
    fun `list_downloaded_models reports only what is on disk`() = runTest {
        deps.catalogue = listOf(model("here"), model("not-here"))
        deps.downloaded = setOf("here")

        val result = ListDownloadedModels().run(emptyMap(), deps)

        assertTrue(result.output.contains("here"))
        assertFalse(result.output.contains("not-here"))
    }

    @Test
    fun `list_downloaded_models says so plainly when there are none`() = runTest {
        deps.catalogue = listOf(model("a"))
        deps.downloaded = emptySet()

        // "No models" has to be a sentence the model can relay, not an empty string it will
        // interpret as the call having failed.
        assertEquals("No models are downloaded yet.", ListDownloadedModels().run(emptyMap(), deps).output)
    }

    // ---- the web ------------------------------------------------------------------------------

    @Test
    fun `web_search passes the query and key through and returns its sources`() = runTest {
        deps.settings = deps.settings.copy(tavilyApiKey = "key-123")
        deps.searchResult = Result.success(
            WebSearchResult("the answer", listOf(Source("Title", "https://example.com"))),
        )

        val result = WebSearch().run(mapOf("query" to "tide times"), deps)

        assertEquals(listOf("tide times" to "key-123"), deps.searches)
        assertEquals("the answer", result.output)
        assertEquals(1, result.sources.size)
    }

    @Test
    fun `web_search fails fast offline and tells the model not to retry`() = runTest {
        deps.settings = deps.settings.copy(tavilyApiKey = "key-123")
        deps.online = false

        val result = WebSearch().run(mapOf("query" to "tide times"), deps)

        assertTrue(result.isError)
        // Never reached the network: the point is to not make the user wait out a timeout.
        assertTrue(deps.searches.isEmpty())
        // A model told only "that failed" calls the same tool again.
        assertTrue(result.output.contains("do not try to search again"))
    }

    @Test
    fun `web_search without a key explains why rather than failing blankly`() = runTest {
        deps.settings = deps.settings.copy(tavilyApiKey = null)

        val result = WebSearch().run(mapOf("query" to "tide times"), deps)

        assertTrue(result.isError)
        assertTrue(deps.searches.isEmpty())
        assertTrue(result.output.contains("Tavily"))
    }

    @Test
    fun `web_search surfaces a failure as a readable result`() = runTest {
        deps.settings = deps.settings.copy(tavilyApiKey = "key-123")
        deps.searchResult = Result.failure(RuntimeException("rate limited"))

        val result = WebSearch().run(mapOf("query" to "x"), deps)

        assertTrue(result.isError)
        assertEquals("rate limited", result.output)
    }

    @Test
    fun `fetch_url refuses anything that is not an http url`() = runTest {
        deps.settings = deps.settings.copy(tavilyApiKey = "key-123")

        val result = FetchUrl().run(mapOf("url" to "example.com"), deps)

        assertTrue(result.isError)
        assertTrue(deps.fetches.isEmpty())
    }

    @Test
    fun `fetch_url names the host it read`() = runTest {
        deps.settings = deps.settings.copy(tavilyApiKey = "key-123")
        deps.fetchResult = Result.success(WebSearchResult("page body", emptyList()))

        val result = FetchUrl().run(mapOf("url" to "https://example.com/a/b"), deps)

        assertEquals("Read example.com", result.summary)
        assertEquals("page body", result.output)
    }

    // ---- through the registry -----------------------------------------------------------------

    @Test
    fun `an unknown function comes back as a result listing the real ones`() = runTest {
        val result = AppFunctionRegistry.Default.execute(ToolCall("open_setttings"), deps)

        // A misremembered name is an ordinary small-model mistake, and the list is what lets it
        // correct itself on the next hop.
        assertTrue(result.isError)
        assertTrue(result.output.contains("open_settings"))
    }

    @Test
    fun `a thrown function becomes a result rather than killing the turn`() = runTest {
        val throwing = object : AppFunction() {
            override val name = "boom"
            override val description = "throws"
            override suspend fun run(arguments: Map<String, String>, deps: AppFunctionDeps) =
                error("exploded")
        }

        val result = AppFunctionRegistry(listOf(throwing)).execute(ToolCall("boom"), deps)

        assertTrue(result.isError)
        assertEquals("exploded", result.output)
        assertNull(result.navigation)
    }
}
