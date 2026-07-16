package com.example.aiagenttestapp.ui.hub

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.aiagent.engine.core.EngineDescriptor
import com.example.aiagent.engine.core.ModelFit
import com.example.aiagent.engine.core.ModelFitEvaluator
import com.example.aiagent.engine.core.ModelFormat
import com.example.aiagent.engine.core.ModelSpec
import com.example.aiagenttestapp.AppContainer
import com.example.aiagenttestapp.data.HfModelFile
import com.example.aiagenttestapp.data.HfRepo
import com.example.aiagenttestapp.data.HfRepoDetail
import com.example.aiagenttestapp.data.toModelSpec
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A file inside a repo, with the fit verdict for *this* device already worked out. */
data class HubFile(
    val file: HfModelFile,
    val spec: ModelSpec,
    val fit: ModelFit,
    val isAdded: Boolean,
)

data class HubUiState(
    val query: String = "",
    val format: ModelFormat = ModelFormat.GGUF,
    val isSearching: Boolean = false,
    val results: List<HfRepo> = emptyList(),
    val error: String? = null,

    /** The repo whose files are expanded, if any. */
    val openRepo: HfRepoDetail? = null,
    val openRepoFiles: List<HubFile> = emptyList(),
    val isLoadingRepo: Boolean = false,
    val hasSearched: Boolean = false,
    val isSignedIn: Boolean = false,
)

class HubViewModel(private val container: AppContainer) : ViewModel() {

    private val _uiState = MutableStateFlow(HubUiState())
    val uiState: StateFlow<HubUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

    init {
        // Something to look at on arrival. An empty screen with a search box gives no sense of what
        // is even findable here.
        search()

        container.huggingFaceAuth.token
            .onEach { token -> _uiState.update { it.copy(isSignedIn = token != null) } }
            .launchIn(viewModelScope)
    }

    fun onQueryChange(query: String) {
        _uiState.update { it.copy(query = query) }

        // Debounced: HuggingFace rate-limits, and firing a request per keystroke would both hit
        // that limit and race results into the wrong order.
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            search()
        }
    }

    fun onFormatChange(format: ModelFormat) {
        _uiState.update { it.copy(format = format, openRepo = null, openRepoFiles = emptyList()) }
        search()
    }

    fun search() {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            val state = _uiState.value
            _uiState.update { it.copy(isSearching = true, error = null) }
            try {
                val results = container.huggingFace.search(state.query, state.format)
                _uiState.update {
                    it.copy(results = results, isSearching = false, hasSearched = true)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isSearching = false,
                        hasSearched = true,
                        error = e.message ?: "Could not reach HuggingFace",
                    )
                }
            }
        }
    }

    /** Expands a repo to show its model files, each already judged against this device. */
    fun openRepo(repo: HfRepo) {
        if (_uiState.value.openRepo?.id == repo.id) {
            _uiState.update { it.copy(openRepo = null, openRepoFiles = emptyList()) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingRepo = true, error = null) }
            try {
                val detail = container.huggingFace.repoDetail(repo.id)
                _uiState.update {
                    it.copy(
                        openRepo = detail,
                        openRepoFiles = detail.files.map { file -> detail.toHubFile(file) },
                        isLoadingRepo = false,
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoadingRepo = false,
                        error = e.message ?: "Could not open ${repo.id}",
                    )
                }
            }
        }
    }

    /**
     * Judges a HuggingFace file against this device, exactly as a built-in model would be.
     *
     * The engine is chosen by file format, and the fit falls through to the computed RAM formula
     * because a community model carries no curated tier. This is the payoff of that design: an
     * arbitrary GGUF off the Hub gets the same green/amber/red verdict as a curated one, with no
     * special-casing.
     */
    private fun HfRepoDetail.toHubFile(file: HfModelFile): HubFile {
        val spec = toModelSpec(file)
        val engine: EngineDescriptor? = container.engines.defaultFor(spec)?.descriptor
            ?: container.engines.all.map { it.descriptor }
                .firstOrNull { it.canLoad(spec.format) }

        val fit = engine
            ?.let { ModelFitEvaluator.evaluateBest(spec, it, container.deviceMemory) }
            ?: ModelFitEvaluator.evaluate(
                model = spec,
                engine = NO_ENGINE,
                accelerator = com.example.aiagent.engine.core.Accelerator.CPU,
                device = container.deviceMemory,
            )

        return HubFile(
            file = file,
            spec = spec,
            fit = fit,
            isAdded = container.customModelStore.contains(spec.id),
        )
    }

    fun add(hubFile: HubFile) {
        container.customModelStore.add(hubFile.spec)
        // Recompute so the row flips to "Added" without a round-trip to HuggingFace.
        _uiState.update { state ->
            state.copy(
                openRepoFiles = state.openRepoFiles.map {
                    if (it.spec.id == hubFile.spec.id) it.copy(isAdded = true) else it
                },
            )
        }
    }

    fun remove(hubFile: HubFile) {
        container.customModelStore.remove(hubFile.spec.id)
        _uiState.update { state ->
            state.copy(
                openRepoFiles = state.openRepoFiles.map {
                    if (it.spec.id == hubFile.spec.id) it.copy(isAdded = false) else it
                },
            )
        }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 350L

        /** Stands in when no compiled-in engine can read the format, so the verdict is UNSUPPORTED. */
        val NO_ENGINE = EngineDescriptor(
            id = com.example.aiagent.engine.core.EngineId.LLAMA_CPP,
            displayName = "No engine",
            vendor = "",
            supportedFormats = emptySet(),
            supportedAccelerators = emptySet(),
            supportsVision = false,
            blurb = "",
        )
    }
}
