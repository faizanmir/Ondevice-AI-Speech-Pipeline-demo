package com.example.aiagenttestapp.functions.tools

import com.example.aiagent.engine.core.ParamBudget
import com.example.aiagent.engine.core.Quantization
import com.example.aiagenttestapp.functions.AppFunction
import com.example.aiagenttestapp.functions.AppFunctionDeps
import com.example.aiagenttestapp.functions.AppFunctionResult
import com.example.aiagenttestapp.ui.components.formatBytes
import com.example.aiagenttestapp.ui.components.formatParams

/**
 * The one function that genuinely needs the app's own knowledge. A model cannot know how much RAM
 * the phone has, so this is the difference between it guessing and it being right.
 */
class GetDeviceMemory : AppFunction() {

    override val name = "get_device_memory"

    override val description =
        "How much RAM and storage this phone has, and the largest model it can run. Use whenever " +
            "the user asks about RAM, storage, or what size of model fits on their device."

    override suspend fun run(
        arguments: Map<String, String>,
        deps: AppFunctionDeps,
    ): AppFunctionResult {
        val device = deps.deviceMemory
        val maxParams = ParamBudget.maxRunnableParams(
            device = device,
            quantization = Quantization.Q4,
            contextTokens = 4096,
        )

        return AppFunctionResult(
            summary = "Checked this device's memory",
            output = buildString {
                appendLine("RAM: ${device.advertisedRamGb} GB")
                appendLine("Usable for a model: ${formatBytes(device.modelRamBudgetBytes)}")
                appendLine("Free storage: ${formatBytes(device.freeStorageBytes)}")
                appendLine(
                    "Largest model that fits: about ${formatParams(maxParams)} parameters " +
                        "at 4-bit precision.",
                )
                device.socModel?.let { appendLine("Chipset: $it") }
            },
        )
    }
}

/** What is already on disk, so the model can talk about the user's actual models. */
class ListDownloadedModels : AppFunction() {

    override val name = "list_downloaded_models"

    override val description = "List the AI models already downloaded on this phone."

    override suspend fun run(
        arguments: Map<String, String>,
        deps: AppFunctionDeps,
    ): AppFunctionResult {
        val models = deps.models.snapshot()
            .filter { deps.modelRepository.isDownloaded(it) }

        return AppFunctionResult(
            summary = "Listed downloaded models",
            output = if (models.isEmpty()) {
                "No models are downloaded yet."
            } else {
                models.joinToString("\n") { model ->
                    "- ${model.name} (${model.paramsLabel}, ${formatBytes(model.sizeBytes)})"
                }
            },
        )
    }
}

/** Which model is answering right now -- something the model cannot introspect for itself. */
class GetCurrentModel : AppFunction() {

    override val name = "get_current_model"

    override val description =
        "Which model, engine and accelerator are running now. Use when the user asks what model " +
            "they are talking to."

    override suspend fun run(
        arguments: Map<String, String>,
        deps: AppFunctionDeps,
    ): AppFunctionResult {
        val engine = deps.engines.all.firstOrNull { it.loadedModelPath != null }

        return AppFunctionResult(
            summary = "Checked the running model",
            output = if (engine == null) {
                "No model is loaded."
            } else {
                val fileName = engine.loadedModelPath?.substringAfterLast('/')
                "Model file: $fileName\n" +
                    "Engine: ${engine.descriptor.displayName}\n" +
                    "Running on: ${engine.activeAccelerator?.label ?: "unknown"}"
            },
        )
    }
}
