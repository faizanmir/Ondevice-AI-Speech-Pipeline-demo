package com.example.aiagenttestapp.ui.catalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.aiagent.engine.core.Accelerator
import com.example.aiagent.engine.core.DeviceMemoryProfile
import com.example.aiagent.engine.core.EngineAvailability
import com.example.aiagent.engine.core.EngineDescriptor
import com.example.aiagent.engine.core.EngineId
import com.example.aiagent.engine.core.EngineRegistry
import com.example.aiagent.engine.core.ModelFit
import com.example.aiagent.engine.core.ModelFitEvaluator
import com.example.aiagent.engine.core.ModelSpec
import com.example.aiagent.engine.core.ParamBudget
import com.example.aiagent.engine.core.Quantization
import com.example.aiagenttestapp.AppContainer
import com.example.aiagenttestapp.data.DownloadState
import com.example.aiagenttestapp.data.ModelCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/** One row in the catalogue: the model, whether it fits, and where its download has got to. */
data class CatalogEntry(
    val model: ModelSpec,
    val fit: ModelFit,
    val downloadState: DownloadState,
    /** Null when no engine in this build can load the model's format. */
    val engine: EngineDescriptor?,
    /** Gated on HuggingFace and the user is not signed in. */
    val isLocked: Boolean = false,
) {
    /** On disk *and* runnable. Both conditions gate every route into the chat screen. */
    val isReadyToChat: Boolean
        get() = downloadState is DownloadState.Downloaded && fit.canRun
}

data class EngineOption(
    val descriptor: EngineDescriptor,
    val availability: EngineAvailability,
) {
    val isAvailable: Boolean get() = availability is EngineAvailability.Available
}

data class CatalogUiState(
    val device: DeviceMemoryProfile,
    val entries: List<CatalogEntry> = emptyList(),
    val engineOptions: List<EngineOption> = emptyList(),
    /** Null = show every engine's models. */
    val engineFilter: EngineId? = null,
    /** Which quantization the headline parameter budget is quoted at. */
    val budgetQuantization: Quantization = Quantization.Q4,
    val isSignedIn: Boolean = false,
) {
    /**
     * The headline: how many parameters this device can actually run.
     *
     * This is the inverse of the RAM estimate -- rather than making the user compare file sizes to
     * a number they do not know, it answers the question they actually have.
     */
    val maxParamsBillions: Double
        get() = ParamBudget.maxRunnableParams(
            device = device,
            quantization = budgetQuantization,
            contextTokens = HEADLINE_CONTEXT_TOKENS,
            engine = EngineId.LITE_RT_LM,
            accelerator = Accelerator.CPU,
        )

    companion object {
        /** The context length the headline budget assumes. Named so the UI can say so out loud. */
        const val HEADLINE_CONTEXT_TOKENS = 4096
    }
}

class CatalogViewModel(private val container: AppContainer) : ViewModel() {

    private val engineFilter = MutableStateFlow<EngineId?>(null)
    private val budgetQuantization = MutableStateFlow(Quantization.Q4)

    private val _uiState = MutableStateFlow(CatalogUiState(device = container.deviceMemory))
    val uiState: StateFlow<CatalogUiState> = _uiState

    init {
        combine(
            container.allModels,
            container.modelRepository.downloadStates,
            engineFilter,
            budgetQuantization,
            container.huggingFaceAuth.token,
        ) { models, downloadStates, filter, quant, token ->
            buildState(models, downloadStates, filter, quant, signedIn = token != null)
        }
            // buildState checks the disk for each model, so keep it off the main thread.
            .flowOn(Dispatchers.IO)
            .onEach { _uiState.value = it }
            .launchIn(viewModelScope)
    }

    private fun buildState(
        models: List<ModelSpec>,
        downloadStates: Map<String, DownloadState>,
        filter: EngineId?,
        quant: Quantization,
        signedIn: Boolean,
    ): CatalogUiState {
        val device = container.deviceMemory
        val registry: EngineRegistry = container.engines

        val entries = models
            .map { model ->
                val engine = registry.defaultFor(model)?.descriptor
                    // Fall back to *any* registered engine that understands the format, so an
                    // unavailable engine still yields an honest "needs llama.cpp" verdict rather
                    // than the model vanishing from the list with no explanation.
                    ?: registry.all.map { it.descriptor }.firstOrNull { it.canLoad(model.format) }

                // Downloaded is the disk's call, not WorkManager's -- a finished job whose file was
                // since deleted must not still read as Downloaded.
                val isDownloaded = container.modelRepository.isDownloaded(model)
                val downloadState = when {
                    isDownloaded -> DownloadState.Downloaded
                    else -> downloadStates[model.id] ?: DownloadState.NotDownloaded
                }

                CatalogEntry(
                    model = model,
                    fit = engine
                        ?.let { ModelFitEvaluator.evaluateBest(model, it, device, isDownloaded) }
                        ?: unsupportedFit(model, device),
                    downloadState = downloadState,
                    engine = engine,
                    // A gated model the user cannot yet download is not broken -- it is locked, and
                    // the card has to offer the key rather than a dead Download button.
                    isLocked = model.requiresAuth && !signedIn,
                )
            }
            .filter { filter == null || it.engine?.id == filter }
            // Best fit first, then largest model: the strongest thing the phone can actually run
            // should be the first thing the user sees.
            .sortedWith(
                compareByDescending<CatalogEntry> { it.fit.verdict.ordinal }
                    .thenByDescending { it.model.paramsBillions },
            )

        return CatalogUiState(
            device = device,
            entries = entries,
            engineOptions = registry.all.map {
                EngineOption(it.descriptor, it.availability())
            },
            engineFilter = filter,
            budgetQuantization = quant,
            isSignedIn = signedIn,
        )
    }

    private fun unsupportedFit(model: ModelSpec, device: DeviceMemoryProfile) =
        ModelFitEvaluator.evaluate(
            model = model,
            engine = EngineDescriptor(
                id = EngineId.LLAMA_CPP,
                displayName = "No engine",
                vendor = "",
                supportedFormats = emptySet(),
                supportedAccelerators = emptySet(),
                supportsVision = false,
                blurb = "",
            ),
            accelerator = Accelerator.CPU,
            device = device,
        )

    fun setEngineFilter(id: EngineId?) {
        engineFilter.value = id
    }

    fun setBudgetQuantization(quant: Quantization) {
        budgetQuantization.value = quant
    }

    fun download(model: ModelSpec) {
        container.modelRepository.enqueueDownload(model)
    }

    fun cancelDownload(model: ModelSpec) {
        container.modelRepository.cancelDownload(model)
    }

    fun delete(model: ModelSpec) {
        viewModelScope.launch { container.modelRepository.delete(model) }
    }

    /** Removes a user-added model from the catalogue, and its file from disk with it. */
    fun removeCustom(model: ModelSpec) {
        viewModelScope.launch {
            container.modelRepository.delete(model)
            container.customModelStore.remove(model.id)
        }
    }
}
