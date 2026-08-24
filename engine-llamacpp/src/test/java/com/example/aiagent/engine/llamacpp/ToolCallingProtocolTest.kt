package com.example.aiagent.engine.llamacpp

import com.example.aiagent.engine.core.ToolDefinition
import com.example.aiagent.engine.core.ToolParameter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parser is the whole risk surface of app functions.
 *
 * A 0.5B model does not emit clean JSON just because the prompt told it to -- it wraps it in code
 * fences, says "Sure!" first, and adds a sentence afterwards. Every case below is a shape a real
 * small model actually produces. Being too strict here means the feature silently does nothing;
 * being too loose means ordinary conversation gets mistaken for a command.
 */
class ToolCallingProtocolTest {

    @Test
    fun `parses a clean call`() {
        val call = ToolCallingProtocol.parse("""{"tool": "open_settings", "args": {}}""")
        assertEquals("open_settings", call?.name)
        assertTrue(call!!.arguments.isEmpty())
    }

    @Test
    fun `parses arguments`() {
        val call = ToolCallingProtocol.parse(
            """{"tool": "set_temperature", "args": {"value": "1.2"}}""",
        )
        assertEquals("set_temperature", call?.name)
        assertEquals("1.2", call?.arguments?.get("value"))
    }

    @Test
    fun `survives a code fence`() {
        val call = ToolCallingProtocol.parse(
            """
            ```json
            {"tool": "open_settings", "args": {}}
            ```
            """.trimIndent(),
        )
        assertEquals("open_settings", call?.name)
    }

    @Test
    fun `survives chatter around the json`() {
        val call = ToolCallingProtocol.parse(
            """Sure! I'll do that for you. {"tool": "open_model_catalog", "args": {}} Let me know!""",
        )
        assertEquals("open_model_catalog", call?.name)
    }

    /** The `args` object nests, so a regex over `\{.*\}` would stop at the wrong brace. */
    @Test
    fun `handles the nested args object`() {
        val call = ToolCallingProtocol.parse(
            """{"tool": "search_huggingface", "args": {"query": "qwen"}}""",
        )
        assertEquals("search_huggingface", call?.name)
        assertEquals("qwen", call?.arguments?.get("query"))
    }

    /** A brace inside a string must not be read as structure. */
    @Test
    fun `handles braces inside argument values`() {
        val call = ToolCallingProtocol.parse(
            """{"tool": "search_huggingface", "args": {"query": "a } brace"}}""",
        )
        assertEquals("search_huggingface", call?.name)
        assertEquals("a } brace", call?.arguments?.get("query"))
    }

    /**
     * The important negative case. Ordinary prose must never be mistaken for a command, or the
     * model would drive the app while it was merely talking about it.
     */
    @Test
    fun `plain prose is not a tool call`() {
        assertNull(ToolCallingProtocol.parse("The capital of France is Paris."))
        assertNull(ToolCallingProtocol.parse("You can open settings from the gear icon."))
        assertNull(ToolCallingProtocol.parse("Here is some JSON: {\"a\": 1}"))
        assertNull(ToolCallingProtocol.parse(""))
    }

    /** A JSON object that is not a tool call must be skipped, and a later real one still found. */
    @Test
    fun `skips a leading non-tool json object`() {
        val call = ToolCallingProtocol.parse(
            """{"thinking": "the user wants settings"} {"tool": "open_settings", "args": {}}""",
        )
        assertEquals("open_settings", call?.name)
    }

    @Test
    fun `unbalanced json does not hang or throw`() {
        assertNull(ToolCallingProtocol.parse("""{"tool": "open_settings", "args": {"""))
    }

    @Test
    fun `no tools means no instructions`() {
        assertNull(ToolCallingProtocol.systemPromptSection(emptyList()))
    }

    @Test
    fun `the prompt names every tool and parameter`() {
        val section = ToolCallingProtocol.systemPromptSection(
            listOf(
                ToolDefinition(name = "open_settings", description = "Open settings."),
                ToolDefinition(
                    name = "set_temperature",
                    description = "Change creativity.",
                    parameters = listOf(
                        ToolParameter(name = "value", description = "0.0 to 2.0", type = "number"),
                    ),
                ),
            ),
        )!!

        // The model can only call what the prompt told it about, so anything missing here is a tool
        // that silently does not exist.
        assertTrue(section.contains("open_settings"))
        assertTrue(section.contains("set_temperature"))
        assertTrue(section.contains("value"))
        assertTrue(section.contains("number"))
    }
}
