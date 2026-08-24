package com.example.aiagenttestapp.functions

import com.example.aiagent.engine.core.ToolCall
import com.example.aiagent.engine.core.ToolDefinition
import com.example.aiagenttestapp.functions.tools.FetchUrl
import com.example.aiagenttestapp.functions.tools.GetCurrentModel
import com.example.aiagenttestapp.functions.tools.GetDeviceMemory
import com.example.aiagenttestapp.functions.tools.ListDownloadedModels
import com.example.aiagenttestapp.functions.tools.OpenModelCatalog
import com.example.aiagenttestapp.functions.tools.OpenSettings
import com.example.aiagenttestapp.functions.tools.ResetSampling
import com.example.aiagenttestapp.functions.tools.SearchHuggingFace
import com.example.aiagenttestapp.functions.tools.SetTemperature
import com.example.aiagenttestapp.functions.tools.WebSearch

/**
 * Everything the model is allowed to do to this app, and the only way to run any of it.
 *
 * Kept small and blunt. A 1B model asked to choose between twenty overlapping tools picks badly, so
 * each entry is a distinct user intent with an unmistakable description, and there are no two that
 * could plausibly answer the same request.
 *
 * A class rather than an object, taking its functions as an argument, so a caller can register a
 * different set -- a test with one fake function, or a screen that should only offer a subset. The
 * app's own set is [Default].
 */
class AppFunctionRegistry(private val functions: List<AppFunction>) {

    private val byName: Map<String, AppFunction> = functions.associateBy { it.name }

    init {
        // Two functions answering to one name means one of them is unreachable, and which one
        // depends on list order -- a bug that would show up as a tool that silently does the wrong
        // thing. Cheap to catch here, at construction, where the list is written.
        require(byName.size == functions.size) {
            val duplicates = functions.groupBy { it.name }.filterValues { it.size > 1 }.keys
            "Duplicate app function names: ${duplicates.joinToString(", ")}"
        }
    }

    /**
     * What the model is told it can do. The web functions appear only when web access is
     * configured, so a model without a key is never offered a tool it cannot use.
     */
    fun definitions(webAccessEnabled: Boolean): List<ToolDefinition> = functions
        .filter { webAccessEnabled || !it.requiresWebAccess }
        .map { it.definition }

    /** The function by the name the model used, or null when it invented one. */
    fun find(name: String): AppFunction? = byName[name]

    /**
     * Runs [call], turning both an unknown name and a thrown exception into a result the model can
     * read. Neither is exceptional: a small model misremembers a function name, and a function can
     * fail for ordinary reasons. Both are things to tell the model so it can recover, rather than
     * failures to propagate into the turn.
     */
    suspend fun execute(call: ToolCall, deps: AppFunctionDeps): AppFunctionResult {
        val function = find(call.name)
            ?: return AppFunctionResult(
                summary = "Unknown function \"${call.name}\"",
                output = "There is no function called \"${call.name}\". " +
                    "Available: ${byName.keys.joinToString(", ")}.",
                isError = true,
            )

        return try {
            function.run(call.arguments, deps)
        } catch (e: Exception) {
            AppFunctionResult(
                summary = "\"${call.name}\" failed",
                output = e.message ?: "The function threw an error.",
                isError = true,
            )
        }
    }

    companion object {

        /** The app's own set. Order is the order the model sees them in. */
        val Default = AppFunctionRegistry(
            listOf(
                OpenSettings(),
                OpenModelCatalog(),
                SearchHuggingFace(),
                GetDeviceMemory(),
                ListDownloadedModels(),
                GetCurrentModel(),
                SetTemperature(),
                ResetSampling(),
                WebSearch(),
                FetchUrl(),
            ),
        )
    }
}
