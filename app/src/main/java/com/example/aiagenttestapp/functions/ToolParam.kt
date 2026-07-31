package com.example.aiagenttestapp.functions

import com.example.aiagent.engine.core.ToolParameter

/**
 * One parameter a function takes, declared once and used for both halves of its life: describing it
 * to the model, and reading it back out of the call the model made.
 *
 * Those two halves used to be written separately -- a `ToolParameter("query", ...)` in the
 * definition and an `args["query"]` in the body -- with nothing but the spelling holding them
 * together. Rename one and the function still compiles, still gets offered to the model, and simply
 * never sees its argument again. Declaring the parameter as a property removes the string literal
 * from the body, so the two cannot drift apart.
 *
 * Arguments arrive as strings whatever the model quoted them as, so reading is also where a value
 * is coerced -- once, by the parameter that knows what it wants.
 */
sealed class ToolParam<T>(
    val name: String,
    val description: String,
    val required: Boolean,
    val allowedValues: List<String>?,
) {

    /** The JSON-schema type, as both engines' tool formats spell it. */
    protected abstract val type: String

    /** This parameter as the engines describe it to a model. */
    fun declaration(): ToolParameter = ToolParameter(
        name = name,
        description = description,
        type = type,
        required = required,
        allowedValues = allowedValues,
    )

    /** The value the model passed, coerced. Null when it passed nothing usable. */
    abstract fun read(arguments: Map<String, String>): T

    /** Trimmed, with blank treated as absent -- a model that means "nothing" often sends "". */
    protected fun raw(arguments: Map<String, String>): String? =
        arguments[name]?.trim()?.takeIf { it.isNotEmpty() }
}

/** A free-text parameter. */
class TextParam(
    name: String,
    description: String,
    required: Boolean = true,
    allowedValues: List<String>? = null,
) : ToolParam<String?>(name, description, required, allowedValues) {

    override val type = "string"

    override fun read(arguments: Map<String, String>): String? = raw(arguments)
}

/** A numeric parameter. Null when the model sent prose where a number was asked for. */
class NumberParam(
    name: String,
    description: String,
    required: Boolean = true,
) : ToolParam<Float?>(name, description, required, allowedValues = null) {

    override val type = "number"

    override fun read(arguments: Map<String, String>): Float? = raw(arguments)?.toFloatOrNull()
}
