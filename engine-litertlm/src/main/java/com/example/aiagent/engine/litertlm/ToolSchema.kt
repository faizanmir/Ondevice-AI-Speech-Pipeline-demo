package com.example.aiagent.engine.litertlm

import com.example.aiagent.engine.core.ToolDefinition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Translation between the app's [ToolDefinition]s and the JSON LiteRT-LM's tool API speaks.
 *
 * Deliberately free of any LiteRT-LM type, so it can be tested on a plain JVM: the runtime's
 * classes are built for a newer bytecode level than this module's unit tests run on, and loading
 * one drags in the whole AAR. Keeping the translation here means the part that is easy to get
 * quietly wrong is also the part that is easy to test.
 */
internal object ToolSchema {

    /**
     * The function declaration, in the OpenAPI subset the runtime parses: a name, a description,
     * and an object schema of parameters.
     *
     * [ToolDefinition] deliberately carries only string/number/boolean parameters -- no nested
     * objects, no arrays -- because the models this app runs are small and fumble anything richer.
     * That maps onto this schema exactly, with no lossy translation in between.
     */
    fun declaration(definition: ToolDefinition): JsonObject = buildJsonObject {
        put("name", definition.name)
        put("description", definition.description)
        putJsonObject("parameters") {
            put("type", "object")
            putJsonObject("properties") {
                definition.parameters.forEach { parameter ->
                    putJsonObject(parameter.name) {
                        put("type", parameter.type)
                        put(
                            "description",
                            // The allowed values ride along in the description as well as in
                            // `enum`. The enum is what constrains a runtime that honours it; the
                            // prose is what a 1B model actually reads.
                            parameter.allowedValues?.let {
                                "${parameter.description} One of: ${it.joinToString(", ")}."
                            } ?: parameter.description,
                        )
                        parameter.allowedValues?.let { values ->
                            put("enum", buildJsonArray { values.forEach { add(JsonPrimitive(it)) } })
                        }
                    }
                }
            }
            put(
                "required",
                buildJsonArray {
                    definition.parameters
                        .filter { it.required }
                        .forEach { add(JsonPrimitive(it.name)) }
                },
            )
        }
    }

    /**
     * Arguments as a flat map of strings.
     *
     * Everything is stringified rather than typed, because that is what
     * [com.example.aiagent.engine.core.ToolCall] carries and what the app's functions coerce from
     * -- and because models are unreliable about the difference anyway, quoting numbers as often as
     * not. Coercion happens once, in the function that knows what it wants.
     *
     * Nulls are dropped rather than passed on as "null": a model that names an optional argument
     * and leaves it empty means the same as one that omits it, and the functions read a missing key
     * as "not given".
     */
    fun arguments(json: String): Map<String, String> {
        if (json.isBlank()) return emptyMap()
        return Json.parseToJsonElement(json).jsonObject.mapNotNull { (key, value) ->
            when {
                value is JsonPrimitive && value.isString -> key to value.content
                value is JsonPrimitive && value.content == "null" -> null
                value is JsonArray || value is JsonObject -> key to value.toString()
                else -> key to value.toString()
            }
        }.toMap()
    }

    fun errorJson(message: String): String = buildJsonObject { put("error", message) }.toString()
}
