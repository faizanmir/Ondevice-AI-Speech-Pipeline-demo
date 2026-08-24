package com.example.aiagenttestapp.ui.hub

import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.example.aiagent.engine.core.EngineDescriptor
import com.example.aiagent.engine.core.ModelFit
import com.example.aiagent.engine.core.ModelFitEvaluator
import com.example.aiagent.engine.core.ModelFormat
import com.example.aiagent.engine.core.ModelSpec
import com.example.aiagenttestapp.data.HfModelFile
import com.example.aiagenttestapp.data.HfPagingSource
import com.example.aiagenttestapp.data.HfRef
import com.example.aiagenttestapp.data.HfRepo
import com.example.aiagenttestapp.data.HfRepoDetail
import com.example.aiagenttestapp.data.parseHuggingFaceRef
import com.example.aiagenttestapp.data.toModelSpec
import com.example.aiagenttestapp.ui.mvi.MviViewModel
import com.example.aiagenttestapp.ui.mvi.UiIntent
import com.example.aiagenttestapp.ui.mvi.UiState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import com.example.aiagent.engine.core.DeviceMemoryProfile
import com.example.aiagent.engine.core.EngineRegistry
import com.example.aiagenttestapp.data.CustomModelStore
import com.example.aiagenttestapp.data.HuggingFaceAuth
import com.example.aiagenttestapp.data.HuggingFaceClient
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

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
    val error: String? = null,

    /** The repo whose files are expanded, if any. */
    val openRepo: HfRepoDetail? = null,
    val openRepoFiles: List<HubFile> = emptyList(),
    val isLoadingRepo: Boolean = false,
    val isSignedIn: Boolean = false,

    /** Non-null when the query is a HuggingFace link or `owner/repo` id, not search text. */
    val pastedRef: HfRef? = null,
    /** The pasted repo once opened, so it can render above the (empty) paged list. */
    val pastedRepo: HfRepo? = null,
) : UiState

sealed interface HubIntent : UiIntent {
    data class QueryChanged(val query: String) : HubIntent
    data class FormatChanged(val format: ModelFormat) : HubIntent
    /** Opens the repo a pasted HuggingFace link or `owner/repo` id names, bypassing search. */
    data object OpenPastedRef : HubIntent
    /** Expands a repo, or collapses it when it is already the open one. */
    data class OpenRepo(val repo: HfRepo) : HubIntent
    data class AddFile(val file: HubFile) : HubIntent
    data class RemoveFile(val file: HubFile) : HubIntent
}

@HiltViewModel
class HubViewModel @Inject constructor(
    private val customModelStore: CustomModelStore,
    private val deviceMemory: DeviceMemoryProfile,
    private val engines: EngineRegistry,
    private val huggingFace: HuggingFaceClient,
    private val huggingFaceAuth: HuggingFaceAuth,
) : MviViewModel<HubUiState, HubIntent, Nothing>(HubUiState()) {

    private val queryFlow = MutableStateFlow("")
    private val formatFlow = MutableStateFlow(ModelFormat.GGUF)

    /**
     * The paged repo list for the current query and format. `flatMapLatest` swaps in a fresh pager
     * when either changes; each pager follows the Hub's `Link` cursor page by page, so the whole
     * matching set is reachable by scrolling rather than the fixed first-30 the old search returned.
     * A pasted link or id yields an empty page, since it is opened directly instead of searched.
     *
     * The one thing on this screen that is NOT part of [HubUiState], deliberately. `PagingData` is a
     * stream of page loads that Paging itself owns -- `collectAsLazyPagingItems` keeps the load
     * state, the placeholders and the retry handle. Snapshotting it into an immutable state object
     * would mean re-emitting the whole pager on every unrelated state change, which resets the list
     * to page one. Everything the screen renders *around* the list is in the state.
     */
    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    val repos: Flow<PagingData<HfRepo>> =
        combine(queryFlow.debounce(SEARCH_DEBOUNCE_MS), formatFlow) { q, f -> q to f }
            .distinctUntilChanged()
            .flatMapLatest { (query, format) ->
                if (parseHuggingFaceRef(query) != null) {
                    flowOf(PagingData.empty())
                } else {
                    Pager(PagingConfig(pageSize = PAGE_SIZE, enablePlaceholders = false)) {
                        HfPagingSource(huggingFace, query, format)
                    }.flow
                }
            }
            .cachedIn(viewModelScope)

    init {
        huggingFaceAuth.token.collectIntoState { token -> copy(isSignedIn = token != null) }
    }

    override fun reduce(intent: HubIntent) = when (intent) {
        is HubIntent.QueryChanged -> onQueryChange(intent.query)
        is HubIntent.FormatChanged -> onFormatChange(intent.format)
        HubIntent.OpenPastedRef -> openPastedRef()
        is HubIntent.OpenRepo -> openRepo(intent.repo)
        is HubIntent.AddFile -> add(intent.file)
        is HubIntent.RemoveFile -> remove(intent.file)
    }

    private fun onQueryChange(query: String) {
        queryFlow.value = query
        // The pager reacts to queryFlow; here we only track the text and whether it is a pasted ref.
        setState {
            copy(query = query, pastedRef = parseHuggingFaceRef(query), pastedRepo = null)
        }
    }

    private fun onFormatChange(format: ModelFormat) {
        formatFlow.value = format
        setState { copy(format = format, openRepo = null, openRepoFiles = emptyList()) }
    }

    /**
     * Opens the repo named by a pasted link or id, bypassing search: the repo is fetched by id and
     * expanded above the (empty) paged list. The file the link pointed at, if any, is floated to the
     * top of the file list.
     *
     * This is the escape hatch for a model search never surfaces -- a specific small quant that the
     * relevance ranking still ranks below what fits on screen -- when the user already has its URL.
     */
    private fun openPastedRef() {
        val ref = currentState.pastedRef ?: return
        viewModelScope.launch {
            setState { copy(isLoadingRepo = true, error = null) }
            try {
                val repo = huggingFace.repo(ref.repoId)
                val detail = huggingFace.repoDetail(ref.repoId)
                val files = detail.files.map { detail.toHubFile(it) }
                    .sortedByDescending { ref.filePath != null && it.file.path == ref.filePath }
                setState {
                    copy(
                        pastedRepo = repo,
                        openRepo = detail,
                        openRepoFiles = files,
                        isLoadingRepo = false,
                        error = null,
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                setState {
                    copy(
                        isLoadingRepo = false,
                        error = e.message ?: "Could not open ${ref.repoId}",
                    )
                }
            }
        }
    }

    /** Expands a repo to show its model files, each already judged against this device. */
    private fun openRepo(repo: HfRepo) {
        if (currentState.openRepo?.id == repo.id) {
            setState { copy(openRepo = null, openRepoFiles = emptyList()) }
            return
        }

        viewModelScope.launch {
            setState { copy(isLoadingRepo = true, error = null) }
            try {
                val detail = huggingFace.repoDetail(repo.id)
                setState {
                    copy(
                        openRepo = detail,
                        openRepoFiles = detail.files.map { file -> detail.toHubFile(file) },
                        isLoadingRepo = false,
                    )
                }
            } catch (e: Exception) {
                setState {
                    copy(
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
        val engine: EngineDescriptor? = engines.defaultFor(spec)?.descriptor
            ?: engines.all.map { it.descriptor }
                .firstOrNull { it.canLoad(spec.format) }

        val fit = engine
            ?.let { ModelFitEvaluator.evaluateBest(spec, it, deviceMemory) }
            ?: ModelFitEvaluator.evaluate(
                model = spec,
                engine = NO_ENGINE,
                accelerator = com.example.aiagent.engine.core.Accelerator.CPU,
                device = deviceMemory,
            )

        return HubFile(
            file = file,
            spec = spec,
            fit = fit,
            isAdded = customModelStore.contains(spec.id),
        )
    }

    private fun add(hubFile: HubFile) {
        customModelStore.add(hubFile.spec)
        // Recompute so the row flips to "Added" without a round-trip to HuggingFace.
        setState {
            copy(
                openRepoFiles = openRepoFiles.map {
                    if (it.spec.id == hubFile.spec.id) it.copy(isAdded = true) else it
                },
            )
        }
    }

    private fun remove(hubFile: HubFile) {
        customModelStore.remove(hubFile.spec.id)
        setState {
            copy(
                openRepoFiles = openRepoFiles.map {
                    if (it.spec.id == hubFile.spec.id) it.copy(isAdded = false) else it
                },
            )
        }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 350L

        /** Results per page. Bigger than the old fixed 30, and more pages load as the user scrolls. */
        const val PAGE_SIZE = 50

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
