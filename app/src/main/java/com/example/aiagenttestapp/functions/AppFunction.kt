package com.example.aiagenttestapp.functions

import com.example.aiagent.engine.core.ToolDefinition
import com.example.aiagenttestapp.data.Source

/** Where a function wants the app to go, if anywhere. Collected by the nav host. */
sealed interface AppNavigation {
    data object Settings : AppNavigation
    data object Catalog : AppNavigation
    data class HuggingFace(val query: String?) : AppNavigation
}

/**
 * What running a function did.
 *
 * [output] is written for the *model* to read back, so it is dense and factual. The chat shows
 * [summary] to the user instead -- one short line, in their language.
 */
data class AppFunctionResult(
    val summary: String,
    val output: String,
    val navigation: AppNavigation? = null,
    val isError: Boolean = false,
    /** Web pages this call drew on, surfaced to the user as citations under the reply. */
    val sources: List<Source> = emptyList(),
)

/**
 * One capability the model can invoke.
 *
 * Shaped like an Android AppFunction on purpose: a name, a description written for an agent to
 * read, typed parameters, and a body with a side effect. Today the caller is the on-device model in
 * this app; the same registry is what you would expose through `androidx.appfunctions` to let
 * system agents call it too, which is why [definition] is derived rather than hand-written.
 *
 * A class per function rather than a lambda in a list. Each one then has somewhere to put its own
 * parameters as [ToolParam] properties, its own doc explaining why it exists, and a name that turns
 * up in a stack trace -- and adding one is a new file rather than an edit to a growing table.
 */
abstract class AppFunction {

    /** What the model calls it. Snake case, because that is what both tool formats expect. */
    abstract val name: String

    /** Written for the model, not the user. It is the only thing telling it *when* to call this. */
    abstract val description: String

    /** Declared as properties on the subclass, then listed here. Empty for a function with none. */
    open val parameters: List<ToolParam<*>> = emptyList()

    /**
     * The function reaches the network, so it is offered only when web access is configured -- a
     * model without a key is never told about a tool it cannot use.
     */
    open val requiresWebAccess: Boolean = false

    /** How the engines describe this function to a model. Derived, never written twice. */
    val definition: ToolDefinition by lazy {
        ToolDefinition(
            name = name,
            description = description,
            parameters = parameters.map { it.declaration() },
        )
    }

    /**
     * Does the thing. Read arguments through the [ToolParam] properties rather than by key.
     *
     * May throw -- [AppFunctionRegistry] turns a failure into a result the model can read, so a
     * function is free to be written for the case where everything works.
     */
    abstract suspend fun run(
        arguments: Map<String, String>,
        deps: AppFunctionDeps,
    ): AppFunctionResult
}
