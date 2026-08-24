package com.example.aiagenttestapp.functions

import com.example.aiagenttestapp.functions.tools.FetchUrl
import com.example.aiagenttestapp.functions.tools.OpenSettings
import com.example.aiagenttestapp.functions.tools.SearchHuggingFace
import com.example.aiagenttestapp.functions.tools.SetTemperature
import com.example.aiagenttestapp.functions.tools.WebSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AppFunctionRegistryTest {

    private val registry = AppFunctionRegistry.Default

    @Test
    fun `web functions are offered only when web access is configured`() {
        val withoutWeb = registry.definitions(webAccessEnabled = false).map { it.name }
        val withWeb = registry.definitions(webAccessEnabled = true).map { it.name }

        // A model told about a tool it has no key for will call it, and every call fails.
        assertFalse("web_search" in withoutWeb)
        assertFalse("fetch_url" in withoutWeb)
        assertTrue("web_search" in withWeb)
        assertTrue("fetch_url" in withWeb)

        // Everything else is unaffected either way.
        assertTrue("open_settings" in withoutWeb)
        assertEquals(withWeb.size - 2, withoutWeb.size)
    }

    @Test
    fun `every function is reachable by the name it declares`() {
        registry.definitions(webAccessEnabled = true).forEach { definition ->
            assertNotNull(
                "${definition.name} is offered to the model but cannot be found",
                registry.find(definition.name),
            )
        }
    }

    @Test
    fun `two functions cannot share a name`() {
        // Order would decide which one ran, so the loser is dead code that the model is still told
        // about. Caught where the list is written rather than at the call that silently misfires.
        val error = assertThrows(IllegalArgumentException::class.java) {
            AppFunctionRegistry(listOf(OpenSettings(), OpenSettings()))
        }
        assertTrue(error.message!!.contains("open_settings"))
    }

    @Test
    fun `a registry can be built from any subset`() {
        val one = AppFunctionRegistry(listOf(OpenSettings()))

        assertEquals(listOf("open_settings"), one.definitions(webAccessEnabled = true).map { it.name })
        assertNull(one.find("web_search"))
    }

    @Test
    fun `declared parameters carry into the definition the model sees`() {
        val search = SearchHuggingFace()
        val parameter = search.definition.parameters.single()

        // One declaration feeds both the schema and the read, so this is also the name the body
        // uses -- they cannot disagree.
        assertEquals(search.query.name, parameter.name)
        assertEquals("string", parameter.type)
        assertFalse("the query is optional", parameter.required)
    }

    @Test
    fun `a number parameter declares its type and coerces what the model sent`() {
        val setTemperature = SetTemperature()

        assertEquals("number", setTemperature.definition.parameters.single().type)
        assertEquals(0.7f, setTemperature.value.read(mapOf("value" to "0.7")))
        // Models quote numbers as often as not, and pad them.
        assertEquals(0.7f, setTemperature.value.read(mapOf("value" to " 0.7 ")))
        // Prose where a number was asked for reads as absent, not as zero.
        assertNull(setTemperature.value.read(mapOf("value" to "quite creative")))
        assertNull(setTemperature.value.read(emptyMap()))
    }

    @Test
    fun `a blank argument counts as absent`() {
        val search = WebSearch()

        // A model that means "nothing" often sends "" rather than omitting the key, and a blank
        // search is worth rejecting rather than running.
        assertNull(search.query.read(mapOf("query" to "   ")))
        assertEquals("tide times", search.query.read(mapOf("query" to "  tide times  ")))
    }

    @Test
    fun `functions that reach the network say so`() {
        assertTrue(WebSearch().requiresWebAccess)
        assertTrue(FetchUrl().requiresWebAccess)
        assertFalse(OpenSettings().requiresWebAccess)
    }
}
