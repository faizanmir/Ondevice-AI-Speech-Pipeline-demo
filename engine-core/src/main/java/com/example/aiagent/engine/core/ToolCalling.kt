package com.example.aiagent.engine.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class ToolParameter(
    val name: String,
    val description: String,
    /** One of "string", "number", "boolean". Kept deliberately small -- see [ToolCallingProtocol]. */
    val type: String = "string",
    val required: Boolean = true,
    /** When set, the model must pick one of these values. */
    val allowedValues: List<String>? = null,
)

/** One capability the app exposes to the model. */
@Serializable
data class ToolDefinition(
    val name: String,
    /** Written for the model, not the user. It is the only thing telling it *when* to call this. */
    val description: String,
    val parameters: List<ToolParameter> = emptyList(),
)

/** A model's request to run a tool. Arguments arrive as strings and are coerced by the caller. */
data class ToolCall(
    val name: String,
    val arguments: Map<String, String> = emptyMap(),
)

/**
 * How the app and the model agree to talk about tools.
 *
 * Deliberately protocol-over-prompt rather than using either engine's native tool API. LiteRT-LM
 * has a real tool-calling interface and llama.cpp does not, so anything built on the native path
 * would work on one engine and not the other -- and "the model can control the app" turning on and
 * off depending on which runtime you picked is exactly the kind of leak the [InferenceEngine]
 * abstraction exists to prevent. A prompt protocol is engine-agnostic by construction.
 *
 * The format is the simplest thing a 0.5B model can reliably emit: a single JSON object. No nested
 * schemas, no arrays of calls, no XML tags to balance. Small models fail at all of those.
 */
object ToolCallingProtocol {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * The instructions appended to the system prompt. Returns null when there are no tools, so the
     * model is not primed to invent calls it has no way to make.
     */
    fun systemPromptSection(tools: List<ToolDefinition>): String? {
        if (tools.isEmpty()) return null

        return buildString {
            appendLine("You can control this app by calling a tool.")
            appendLine()
            appendLine("Available tools:")
            tools.forEach { tool ->
                append("- ").append(tool.name).append(": ").appendLine(tool.description)
                tool.parameters.forEach { param ->
                    append("    ").append(param.name)
                    append(" (").append(param.type)
                    if (!param.required) append(", optional")
                    param.allowedValues?.let { append(", one of: ").append(it.joinToString("|")) }
                    append("): ").appendLine(param.description)
                }
            }
            appendLine()
            appendLine("To call a tool, reply with ONLY this JSON and nothing else:")
            appendLine("""{"tool": "tool_name", "args": {"param": "value"}}""")
            appendLine()
            // Small models will otherwise narrate a tool call in prose, or call one on every turn.
            // Every rule here earns its place; the wording is as short as it can be said, because
            // this section sits in the system prompt of every turn of every chat.
            appendLine("Rules:")
            appendLine("- Call a tool only when the user asks you to DO something in the app.")
            appendLine("- Otherwise reply normally, in plain text.")
            appendLine("- Never explain the JSON, wrap it in code fences, or add text around it.")
            appendLine("- Use {} for args when a tool takes no parameters.")
        }
    }

    /** Phrased as a user turn: the models here are chat-tuned and follow user turns far better. */
    fun toolResultPrompt(call: ToolCall, output: String): String =
        "The tool \"${call.name}\" ran and returned:\n$output\n\n" +
            "Tell the user what happened, briefly and in plain language. Do not call another tool."

    /**
     * Pulls a tool call out of a model response, or null if it is ordinary prose.
     *
     * Tolerant on purpose. Models wrap JSON in ```json fences, prepend "Sure!", and add trailing
     * commentary, no matter how firmly the prompt forbids it -- so rather than demand a clean
     * response, we scan for the first balanced JSON object that carries a "tool" key.
     */
    fun parse(response: String): ToolCall? {
        val candidate = findJsonObjectWithToolKey(response) ?: return null

        return try {
            val obj = json.parseToJsonElement(candidate).jsonObject
            val name = obj["tool"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                ?: return null

            val args = obj["args"]?.jsonObject
                ?.mapValues { (_, value) -> value.jsonPrimitive.content }
                ?: emptyMap()

            ToolCall(name = name, arguments = args)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Scans for the first `{...}` that parses and contains a "tool" key.
     *
     * Brace-counting rather than a regex: a tool call's `args` object nests, and a regex cannot
     * match balanced braces. String-awareness matters too -- a brace inside a quoted argument value
     * would otherwise end the object early.
     */
    private fun findJsonObjectWithToolKey(text: String): String? {
        var index = text.indexOf('{')

        while (index >= 0) {
            var depth = 0
            var inString = false
            var escaped = false

            for (i in index until text.length) {
                val c = text[i]

                when {
                    escaped -> escaped = false
                    c == '\\' && inString -> escaped = true
                    c == '"' -> inString = !inString
                    inString -> Unit
                    c == '{' -> depth++
                    c == '}' -> {
                        depth--
                        if (depth == 0) {
                            val candidate = text.substring(index, i + 1)
                            if (candidate.contains("\"tool\"")) return candidate
                            break // balanced, but not a tool call -- start again after this one
                        }
                    }
                }
            }

            index = text.indexOf('{', index + 1)
        }

        return null
    }
}

/**
 * Runs one tool the model asked for, for a runtime that executes tool calls itself.
 *
 * Synchronous on purpose, and the reason is not style. LiteRT-LM's automatic tool calling invokes
 * the tool from inside its own decode loop and waits for the string before it carries on
 * generating, so there is no suspension point to hand back to. An implementation must therefore do
 * its work and return -- it must never bounce onto the main thread and wait for the result, which
 * deadlocks if that thread is what started generation.
 *
 * The return value is fed back to the model verbatim, so it should be JSON, or at least something a
 * model can read. Failures are results too: return an object saying what went wrong rather than
 * throwing, and the model gets a chance to recover instead of the turn dying.
 */
fun interface ToolRunner {
    fun run(call: ToolCall): String
}
