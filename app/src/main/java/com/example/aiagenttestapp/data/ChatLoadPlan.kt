package com.example.aiagenttestapp.data

import com.example.aiagent.engine.core.Accelerator
import com.example.aiagent.engine.core.EngineId
import com.example.aiagent.engine.core.InferenceEngine
import com.example.aiagent.engine.core.LoadRequest
import com.example.aiagent.engine.core.ModelSpec
import com.example.aiagent.engine.core.SamplingParams
import com.example.aiagent.engine.core.ToolCallingProtocol
import com.example.aiagenttestapp.AppContainer
import com.example.aiagenttestapp.functions.AppFunctions
import java.io.File

/**
 * How a model would be loaded into an engine, worked out once and shared by the two callers that
 * MUST agree byte-for-byte: [com.example.aiagenttestapp.ui.chat.ChatViewModel.openChat] and the
 * startup [ModelResidency]. If those two computed the engine, accelerator or system prompt even
 * slightly differently, the resident model would never match what a fresh chat asks for, and the
 * whole optimisation would silently never fire.
 */
sealed interface ChatLoadPlan {

    /** No model in the catalogue -- built-in or user-added -- has this id. */
    data object UnknownModel : ChatLoadPlan

    /** The format has no usable engine in this build (a GGUF model with llama.cpp excluded). */
    data class NoEngine(val model: ModelSpec) : ChatLoadPlan

    /**
     * Known, and an engine can load it. [downloaded] says whether the file is actually on disk yet:
     * carried as a flag rather than a separate result so the chat screen can still show the resolved
     * engine and accelerator on the "not downloaded" error, exactly as it did before.
     */
    data class Resolved(
        val model: ModelSpec,
        val engine: InferenceEngine,
        val accelerator: Accelerator,
        /** Whether this model is told about the app's tools on every turn. */
        val toolsEnabled: Boolean,
        /** Set when app functions are on globally but this particular model cannot use them. */
        val toolsUnavailableReason: String?,
        /**
         * The system prompt for a *fresh* chat: the user's prompt, plus the tool section when tools
         * are on. A resumed chat folds a rolling summary in on top of this before loading.
         */
        val systemPrompt: String,
        val file: File,
        val sampling: SamplingParams,
        val cacheDir: String,
        val nativeLibraryDir: String,
        val downloaded: Boolean,
    ) : ChatLoadPlan {

        val engineId: EngineId get() = engine.descriptor.id
        val engineName: String get() = engine.descriptor.displayName

        /**
         * The load a brand-new chat performs: empty history, no summary. Two equal
         * [freshLoadRequest]s describe two identical resident models, which is exactly what the warm
         * handoff checks -- a preloaded model is reused only when its request equals this one.
         */
        fun freshLoadRequest(): LoadRequest = LoadRequest(
            modelPath = file.absolutePath,
            accelerator = accelerator,
            contextTokens = model.contextTokens,
            sampling = sampling,
            systemPrompt = systemPrompt,
            cacheDir = cacheDir,
            nativeLibraryDir = nativeLibraryDir,
            initialHistory = emptyList(),
        )
    }
}

/**
 * Works out how [modelId] would be loaded, using the same rules the chat screen uses: the user's
 * preferred engine when it can load the format, else the first that can; the preferred accelerator
 * when the engine and model both support it, else GPU, else CPU; and the tool section only for a
 * model that can call tools while app functions are enabled.
 */
fun planChatLoad(container: AppContainer, modelId: String): ChatLoadPlan {
    val model = container.findModel(modelId) ?: return ChatLoadPlan.UnknownModel
    val settings = container.settingsStore.settings.value

    val engine = settings.preferredEngine
        ?.let { container.engines[it] }
        ?.takeIf { it.descriptor.canLoad(model.format) }
        ?: container.engines.defaultFor(model)
        ?: return ChatLoadPlan.NoEngine(model)

    // The preference ladder ends in the engine's own list rather than CPU, because not every
    // engine reaches down that far: AICore is NPU-only, so for it the ladder's GPU and CPU rungs
    // both miss and the engine's first (only) supported accelerator is the answer.
    val accelerator = (
        listOf(settings.preferredAccelerator, Accelerator.GPU, Accelerator.CPU) +
            engine.descriptor.supportedAccelerators
        ).firstOrNull { it in engine.descriptor.supportedAccelerators && it in model.accelerators }
        ?: Accelerator.CPU

    // Tools go into the system prompt, so they are fixed for the life of a loaded model. A model
    // that cannot do tool calling is not given the tool section at all -- it is several hundred
    // system-prompt tokens on every turn, wasted on a model that will never emit a call.
    val toolsEnabled = settings.appFunctionsEnabled && model.canCallTools
    val toolsUnavailableReason = when {
        !settings.appFunctionsEnabled -> null
        !model.canCallTools ->
            "${model.name} cannot run app functions -- it is too small, or its family was " +
                "not trained for tool calling. Try FunctionGemma 270M, Gemma 4, or Qwen 2.5 1.5B."
        else -> null
    }

    // Web search is a tool like any other, but opt-in: offered only when a Tavily key is set.
    val webAccessEnabled = toolsEnabled && !settings.tavilyApiKey.isNullOrBlank()

    val systemPrompt = buildString {
        append(settings.systemPrompt)
        if (toolsEnabled) {
            ToolCallingProtocol.systemPromptSection(
                AppFunctions.definitionsFor(webAccessEnabled),
            )?.let {
                append("\n\n")
                append(it)
            }
        }
    }

    return ChatLoadPlan.Resolved(
        model = model,
        engine = engine,
        accelerator = accelerator,
        toolsEnabled = toolsEnabled,
        toolsUnavailableReason = toolsUnavailableReason,
        systemPrompt = systemPrompt,
        file = container.modelRepository.fileFor(model),
        sampling = settings.sampling,
        cacheDir = container.cacheDirPath,
        nativeLibraryDir = container.nativeLibraryDir,
        downloaded = container.modelRepository.isDownloaded(model),
    )
}
