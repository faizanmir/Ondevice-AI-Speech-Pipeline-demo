package com.example.aiagent.engine.litertlm

import com.example.aiagent.engine.core.ToolDefinition
import com.example.aiagent.engine.core.ToolParameter
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the JSON the runtime is handed for each tool.
 *
 * Worth testing precisely because getting it wrong is silent. A misspelled key or a missing
 * `required` array does not throw -- the runtime simply never emits a call for that function, and
 * the app looks like a model that has decided not to use its tools. The failure would surface as
 * "tool calling doesn't work on LiteRT-LM", days later, with nothing in the log.
 */
class ToolSchemaTest {

    private val openSettings = ToolDefinition(
        name = "open_settings",
        description = "Opens the app's settings screen.",
    )

    private val search = ToolDefinition(
        name = "web_search",
        description = "Searches the web.",
        parameters = listOf(
            ToolParameter("query", "What to search for."),
            ToolParameter("depth", "How thorough.", required = false, allowedValues = listOf("basic", "deep")),
        ),
    )

    @Test
    fun `a tool with no parameters still declares an object schema`() {
        val schema = ToolSchema.declaration(openSettings)

        assertEquals("open_settings", schema["name"]?.jsonPrimitive?.content)
        assertEquals("Opens the app's settings screen.", schema["description"]?.jsonPrimitive?.content)

        // The parameters object is not optional even when empty: a declaration without it is not a
        // valid function schema, and a runtime that rejects one drops the tool silently.
        val parameters = schema["parameters"]!!.jsonObject
        assertEquals("object", parameters["type"]?.jsonPrimitive?.content)
        assertTrue(parameters["properties"]!!.jsonObject.isEmpty())
        assertTrue(parameters["required"]!!.jsonArray.isEmpty())
    }

    @Test
    fun `parameters carry their type, and only the required ones are listed as required`() {
        val parameters = ToolSchema.declaration(search)["parameters"]!!.jsonObject
        val properties = parameters["properties"]!!.jsonObject

        assertEquals(setOf("query", "depth"), properties.keys)
        assertEquals("string", properties["query"]!!.jsonObject["type"]?.jsonPrimitive?.content)

        val required = parameters["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("query"), required)
    }

    @Test
    fun `allowed values appear as an enum and in the description`() {
        val depth = ToolSchema.declaration(search)["parameters"]!!.jsonObject["properties"]!!
            .jsonObject["depth"]!!.jsonObject

        assertEquals(
            listOf("basic", "deep"),
            depth["enum"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        // Spelled out in prose too: a small model reads the description far more reliably than it
        // honours the enum.
        val description = depth["description"]!!.jsonPrimitive.content
        assertTrue("$description should list the choices", description.contains("basic, deep"))
    }

    @Test
    fun `arguments arrive as strings whatever the model quoted them as`() {
        val args = ToolSchema.arguments(
            """{"query": "tide times", "count": 3, "deep": true}""",
        )

        // The app's functions coerce from strings, so a number must not arrive as `3.0` or a
        // quoted-vs-unquoted difference the caller then has to care about.
        assertEquals("tide times", args["query"])
        assertEquals("3", args["count"])
        assertEquals("true", args["deep"])
    }

    @Test
    fun `an omitted optional argument is absent rather than the string null`() {
        val args = ToolSchema.arguments("""{"query": "x", "depth": null}""")

        assertEquals(setOf("query"), args.keys)
        assertFalse("null must not reach a function as a value", args.containsKey("depth"))
    }

    @Test
    fun `empty parameters parse to no arguments rather than throwing`() {
        assertEquals(emptyMap<String, String>(), ToolSchema.arguments(""))
        assertEquals(emptyMap<String, String>(), ToolSchema.arguments("{}"))
    }
}
