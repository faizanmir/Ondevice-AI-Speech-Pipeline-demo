package com.example.aiagenttestapp.functions.tools

import com.example.aiagenttestapp.functions.AppFunction
import com.example.aiagenttestapp.functions.AppFunctionDeps
import com.example.aiagenttestapp.functions.AppFunctionResult
import com.example.aiagenttestapp.functions.AppNavigation
import com.example.aiagenttestapp.functions.TextParam

/** Takes the user to Settings. */
class OpenSettings : AppFunction() {

    override val name = "open_settings"

    override val description =
        "Open the app's Settings screen: engine, accelerator and sampling."

    override suspend fun run(
        arguments: Map<String, String>,
        deps: AppFunctionDeps,
    ) = AppFunctionResult(
        summary = "Opened Settings",
        output = "The Settings screen is now open.",
        navigation = AppNavigation.Settings,
    )
}

/** Takes the user to the built-in model catalogue. */
class OpenModelCatalog : AppFunction() {

    override val name = "open_model_catalog"

    override val description =
        "Show the AI models that can be downloaded and run on this phone."

    override suspend fun run(
        arguments: Map<String, String>,
        deps: AppFunctionDeps,
    ) = AppFunctionResult(
        summary = "Opened the model catalogue",
        output = "The model catalogue is now open.",
        navigation = AppNavigation.Catalog,
    )
}

/** Opens the HuggingFace browser, optionally with a search already run. */
class SearchHuggingFace : AppFunction() {

    override val name = "search_huggingface"

    override val description =
        "Search HuggingFace for new AI models to add to the app."

    /** Optional: with nothing to search for, the browser simply opens. */
    val query = TextParam(
        name = "query",
        description = "What to search for, e.g. \"qwen\" or \"phi\".",
        required = false,
    )

    override val parameters = listOf(query)

    override suspend fun run(
        arguments: Map<String, String>,
        deps: AppFunctionDeps,
    ): AppFunctionResult {
        val searched = query.read(arguments)
        return AppFunctionResult(
            summary = if (searched != null) {
                "Searched HuggingFace for \"$searched\""
            } else {
                "Opened HuggingFace"
            },
            output = "The HuggingFace browser is now open" +
                (searched?.let { ", searching for \"$it\"." } ?: "."),
            navigation = AppNavigation.HuggingFace(searched),
        )
    }
}
