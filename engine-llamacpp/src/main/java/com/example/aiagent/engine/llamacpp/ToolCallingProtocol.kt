package com.example.aiagent.engine.llamacpp

import com.example.aiagent.engine.core.ToolCall
import com.example.aiagent.engine.core.ToolDefinition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * How this app and a llama.cpp model agree to talk about tools.
 *
 * Tool calling here is arranged entirely in the prompt: the tools are described in the system
 * prompt, the model answers with a JSON object instead of prose, and the result is fed back as a
 * new turn. That is not a preference, it is the only option -- llama.cpp exposes no tool API to
 * call, so the format has to live somewhere the model can see it.
 *
 * It lives in this module rather than in engine-core because it is llama.cpp's mechanism and
 * nobody else's. LiteRT-LM declares tools to its runtime as schemas
 * (`AppFunctionTool`), which is a better deal wherever it is available: the model is trained
 * against its own tool format, and the runtime runs the call itself. Keeping this in the shared
 * module implied both engines used it, which stopped being true.
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
