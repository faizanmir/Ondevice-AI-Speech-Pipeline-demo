package com.example.aiagenttestapp.ui.catalog

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
import com.example.aiagenttestapp.data.DownloadState
import com.example.aiagenttestapp.ui.mvi.MviViewModel
import com.example.aiagenttestapp.ui.mvi.UiIntent
import com.example.aiagenttestapp.ui.mvi.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import com.example.aiagenttestapp.data.CustomModelStore
import com.example.aiagenttestapp.data.HuggingFaceAuth
import com.example.aiagenttestapp.data.ModelDirectory
import com.example.aiagenttestapp.data.ModelRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** How the catalogue list is ordered. [BEST_FIT] is the default the screen opens on. */
enum class CatalogSort(val label: String) {
    BEST_FIT("Best fit"),
    NEWEST("Newest first"),
    NAME("Name (A–Z)"),
    PARAMS("Parameters"),
    SIZE_DESC("Size: largest"),
    SIZE_ASC("Size: smallest"),
}

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
    /** The order [entries] is in. */
    val sort: CatalogSort = CatalogSort.BEST_FIT,
) : UiState {
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

sealed interface CatalogIntent : UiIntent {
    /** Null shows every engine's models. */
    data class EngineFilterChanged(val id: EngineId?) : CatalogIntent
    data class SortChanged(val order: CatalogSort) : CatalogIntent
    data class BudgetQuantizationChanged(val quantization: Quantization) : CatalogIntent
    data class Download(val model: ModelSpec) : CatalogIntent
    data class CancelDownload(val model: ModelSpec) : CatalogIntent
    data class DeleteDownload(val model: ModelSpec) : CatalogIntent
    /** Removes a user-added model from the catalogue, and its file from disk with it. */
    data class RemoveCustom(val model: ModelSpec) : CatalogIntent
}

@HiltViewModel
class CatalogViewModel @Inject constructor(
    private val models: ModelDirectory,
    private val customModelStore: CustomModelStore,
    private val deviceMemory: DeviceMemoryProfile,
    private val engines: EngineRegistry,
    private val huggingFaceAuth: HuggingFaceAuth,
    private val modelRepository: ModelRepository,
) : MviViewModel<CatalogUiState, CatalogIntent, Nothing>(CatalogUiState(device = deviceMemory)) {

    // The filters are flows, not plain state reads, because the catalogue is *derived*: every one
    // of them re-runs the combine below. They mirror into the published state, which is what the
    // screen renders -- so the state stays the single source of truth for the UI either way.
    private val engineFilter = MutableStateFlow<EngineId?>(null)
    private val budgetQuantization = MutableStateFlow(Quantization.Q4)
    private val sort = MutableStateFlow(CatalogSort.BEST_FIT)

    init {
        val base = combine(
            models.all,
            modelRepository.downloadStates,
            engineFilter,
            budgetQuantization,
            huggingFaceAuth.token,
        ) { models, downloadStates, filter, quant, token ->
            buildState(models, downloadStates, filter, quant, signedIn = token != null)
        }
            // buildState checks the disk for each model, so keep it off the main thread.
            .flowOn(Dispatchers.IO)

        // Sort is applied after the (disk-touching) build: reordering an in-memory list is cheap and
        // does not need to re-run the fit checks, so changing the order never re-hits the disk.
        combine(base, sort) { state, order ->
            state.copy(sort = order, entries = sortEntries(state.entries, order))
        }
            .collectIntoState { built -> built }
    }

    override fun reduce(intent: CatalogIntent) = when (intent) {
        is CatalogIntent.EngineFilterChanged -> { engineFilter.value = intent.id }
        is CatalogIntent.SortChanged -> { sort.value = intent.order }
        is CatalogIntent.BudgetQuantizationChanged -> { budgetQuantization.value = intent.quantization }
        is CatalogIntent.Download -> modelRepository.enqueueDownload(intent.model)
        is CatalogIntent.CancelDownload -> modelRepository.cancelDownload(intent.model)
        is CatalogIntent.DeleteDownload -> delete(intent.model)
        is CatalogIntent.RemoveCustom -> removeCustom(intent.model)
    }

    private fun buildState(
        models: List<ModelSpec>,
        downloadStates: Map<String, DownloadState>,
        filter: EngineId?,
        quant: Quantization,
        signedIn: Boolean,
    ): CatalogUiState {
        val device = deviceMemory
        val registry: EngineRegistry = engines

        val entries = models
            .map { model ->
                val engine = registry.defaultFor(model)?.descriptor
                    // Fall back to *any* registered engine that understands the format, so an
                    // unavailable engine still yields an honest "needs llama.cpp" verdict rather
                    // than the model vanishing from the list with no explanation.
                    ?: registry.all.map { it.descriptor }.firstOrNull { it.canLoad(model.format) }

                // Downloaded is the disk's call, not WorkManager's -- a finished job whose file was
                // since deleted must not still read as Downloaded.
                val isDownloaded = modelRepository.isDownloaded(model)
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

    private fun sortEntries(entries: List<CatalogEntry>, sort: CatalogSort): List<CatalogEntry> =
        when (sort) {
            // Best fit first, then largest: the strongest thing the phone can actually run should be
            // the first thing the user sees. This is the default the list opens on.
            CatalogSort.BEST_FIT -> entries.sortedWith(
                compareByDescending<CatalogEntry> { it.fit.verdict.ordinal }
                    .thenByDescending { it.model.paramsBillions },
            )
            // Most-recently-added first. The source list runs oldest -> newest, so a plain
            // sortedByDescending would leave equal timestamps (built-ins, and customs saved before
            // add-time was tracked, all carry 0) in their oldest-first order -- which reads as
            // reversed. Breaking ties by descending list position puts the later-added entry first,
            // so newest-first is right even for models with no real timestamp.
            CatalogSort.NEWEST -> entries.withIndex()
                .sortedWith(
                    compareByDescending<IndexedValue<CatalogEntry>> { it.value.model.addedAtMillis }
                        .thenByDescending { it.index },
                )
                .map { it.value }
            CatalogSort.NAME -> entries.sortedBy { it.model.name.lowercase() }
            CatalogSort.PARAMS -> entries.sortedByDescending { it.model.paramsBillions }
            CatalogSort.SIZE_DESC -> entries.sortedByDescending { it.model.sizeBytes }
            CatalogSort.SIZE_ASC -> entries.sortedBy { it.model.sizeBytes }
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

    private fun delete(model: ModelSpec) {
        viewModelScope.launch { modelRepository.delete(model) }
    }

    private fun removeCustom(model: ModelSpec) {
        viewModelScope.launch {
            modelRepository.delete(model)
            customModelStore.remove(model.id)
        }
    }
}
