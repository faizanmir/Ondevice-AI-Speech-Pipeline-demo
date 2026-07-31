package com.example.aiagenttestapp.data

import com.example.aiagent.engine.core.Accelerator
import com.example.aiagent.engine.core.EngineId
import com.example.aiagent.engine.core.EngineRegistry
import com.example.aiagent.engine.core.InferenceEngine
import com.example.aiagent.engine.core.LoadRequest
import com.example.aiagent.engine.core.ModelSpec
import com.example.aiagent.engine.core.SamplingParams
import com.example.aiagenttestapp.di.CacheDirPath
import com.example.aiagenttestapp.di.NativeLibraryDir
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * How a model would be loaded into an engine: which engine, which accelerator, which file, and the
 * settings-derived sampling and thread count. Feature-neutral on purpose -- chat and audit both ask
 * this question and get the same answer, then each adds its own system prompt on top
 * ([ChatLoadPlan], [com.example.aiagenttestapp.data.audit.AuditModelPlan]).
 *
 * Callers that must agree byte-for-byte -- a fresh chat and the startup warm-up -- do so because
 * they build their request from the same plan. If they computed the engine or accelerator even
 * slightly differently, the resident model would never match and the optimisation would never fire.
 */
sealed interface ModelLoadPlan {

    /** No model in the catalogue -- built-in or user-added -- has this id. */
    data object UnknownModel : ModelLoadPlan

    /** The format has no usable engine in this build (a GGUF model with llama.cpp excluded). */
    data class NoEngine(val model: ModelSpec) : ModelLoadPlan

    /**
     * Known, and an engine can load it. [downloaded] says whether the file is actually on disk yet:
     * carried as a flag rather than a separate result so the chat screen can still show the resolved
     * engine and accelerator on the "not downloaded" error, exactly as it did before.
     */
    data class Resolved(
        val model: ModelSpec,
        val engine: InferenceEngine,
        val accelerator: Accelerator,
        val file: File,
        val sampling: SamplingParams,
        val threadCount: Int,
        val cacheDir: String,
        val nativeLibraryDir: String,
        val downloaded: Boolean,
    ) : ModelLoadPlan {

        val engineId: EngineId get() = engine.descriptor.id
        val engineName: String get() = engine.descriptor.displayName

        /**
         * Everything about the load that is not feature-specific: empty history, and no system
         * prompt -- that is the caller's to supply, because it is the one part chat and audit
         * genuinely disagree about. Callers copy their own onto this.
         *
         * Two equal requests describe two identical resident models, which is what the warm handoff
         * checks -- a preloaded model is reused only when its request equals the one being asked for.
         */
        fun baseLoadRequest(): LoadRequest = LoadRequest(
            modelPath = file.absolutePath,
            accelerator = accelerator,
            contextTokens = model.contextTokens,
            sampling = sampling,
            systemPrompt = null,
            cacheDir = cacheDir,
            nativeLibraryDir = nativeLibraryDir,
            initialHistory = emptyList(),
            threadCount = threadCount,
        )
    }
}

/**
 * Works out how a model would be loaded, using the same rules everywhere: the user's preferred
 * engine when it can load the format, else the first that can; the preferred accelerator when the
 * engine and model both support it, else GPU, else CPU.
 *
 * A type rather than a free function taking the whole container, so it declares the five things it
 * actually reads and can be constructed with fakes in a test.
 */
@Singleton
class ModelLoadPlanner @Inject constructor(
    private val models: ModelDirectory,
    private val settingsStore: SettingsStore,
    private val engines: EngineRegistry,
    private val modelRepository: ModelRepository,
    @param:CacheDirPath private val cacheDirPath: String,
    @param:NativeLibraryDir private val nativeLibraryDir: String,
) {

    fun plan(modelId: String): ModelLoadPlan {
        val model = models.find(modelId) ?: return ModelLoadPlan.UnknownModel
        val settings = settingsStore.settings.value

        val engine = settings.preferredEngine
            ?.let { engines[it] }
            ?.takeIf { it.descriptor.canLoad(model.format) }
            ?: engines.defaultFor(model)
            ?: return ModelLoadPlan.NoEngine(model)

        // The preference ladder ends in the engine's own list rather than CPU, because not every
        // engine reaches down that far: AICore is NPU-only, so for it the ladder's GPU and CPU rungs
        // both miss and the engine's first (only) supported accelerator is the answer.
        val accelerator = (
            listOf(settings.preferredAccelerator, Accelerator.GPU, Accelerator.CPU) +
                engine.descriptor.supportedAccelerators
            ).firstOrNull { it in engine.descriptor.supportedAccelerators && it in model.accelerators }
            ?: Accelerator.CPU

        return ModelLoadPlan.Resolved(
            model = model,
            engine = engine,
            accelerator = accelerator,
            file = modelRepository.fileFor(model),
            sampling = settings.effectiveSampling,
            threadCount = settings.threadCount,
            cacheDir = cacheDirPath,
            nativeLibraryDir = nativeLibraryDir,
            downloaded = modelRepository.isDownloaded(model),
        )
    }
}
