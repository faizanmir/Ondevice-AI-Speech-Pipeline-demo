package com.example.aiagent.engine.core

import kotlinx.serialization.Serializable

@Serializable
data class ToolParameter(
    val name: String,
    val description: String,
    /**
     * One of "string", "number", "boolean". Deliberately small: llama.cpp's prompt protocol has to
     * describe this to a 0.5B model in words, and LiteRT-LM's schema has to be something the same
     * class of model can fill in. Neither is helped by nested objects or arrays.
     */
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
