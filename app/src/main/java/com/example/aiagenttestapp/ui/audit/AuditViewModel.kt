package com.example.aiagenttestapp.ui.audit

import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.example.aiagent.engine.core.ModelSpec
import com.example.aiagenttestapp.data.FileTextExtractor
import com.example.aiagenttestapp.data.ModelDirectory
import com.example.aiagenttestapp.data.SettingsStore
import com.example.aiagenttestapp.data.audit.AuditDocument
import com.example.aiagenttestapp.data.audit.AuditMode
import com.example.aiagenttestapp.data.audit.AuditModelPlan
import com.example.aiagenttestapp.data.audit.AuditStatus
import com.example.aiagenttestapp.ui.mvi.MviViewModel
import com.example.aiagenttestapp.ui.mvi.UiIntent
import com.example.aiagenttestapp.ui.mvi.UiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.example.aiagenttestapp.data.ModelResidency
import com.example.aiagenttestapp.data.audit.AuditChunker
import com.example.aiagenttestapp.data.audit.AuditLoadPlanner
import com.example.aiagenttestapp.data.audit.AuditQueue
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

sealed interface AuditLoadState {
    data class Loading(val message: String) : AuditLoadState
    data object Ready : AuditLoadState
    data class Failed(val message: String) : AuditLoadState
}

data class AuditUiState(
    val model: ModelSpec? = null,
    val engineName: String = "",
    /**
     * The read the picker offers by default, seeded from the route. Only a default: the mode is
     * chosen at the moment of attach and pinned onto that document, so this never retroactively
     * changes anything already queued.
     */
    val mode: AuditMode = AuditMode.DETAILED,
    val loadState: AuditLoadState = AuditLoadState.Loading("Loading model"),
    /**
     * Models an audit could run right now, for the switcher. Filtered through the same
     * [AuditLoadPlanner.plan] the drain worker applies, so the picker can never offer a model the
     * worker would then refuse.
     */
    val availableModels: List<ModelSpec> = emptyList(),

    /** Whether a picked file is being read/extracted right now. Attached files are queued directly. */
    val isExtractingFile: Boolean = false,
    val lastAdded: String? = null,
    /** The mode [lastAdded] was queued in, so the confirmation names the read the user actually got. */
    val lastAddedMode: AuditMode = AuditMode.DETAILED,
    val addTruncated: Boolean = false,
    val attachmentError: String? = null,

    /** The whole queue, newest first: queued, analysing, done and failed documents. */
    val documents: List<AuditDocument> = emptyList(),
    /**
     * Display names for the models pinned on the queued documents, keyed by model id. Per document,
     * not one for the screen: each row keeps the model chosen when it was enqueued, which can differ
     * from the top bar's current selection. A model since deleted is simply absent here -- the row
     * falls back to showing its raw id, because that model still wrote the report.
     */
    val modelNames: Map<String, String> = emptyMap(),
) : UiState {
    val canAttach: Boolean
        get() = loadState is AuditLoadState.Ready && !isExtractingFile

    /**
     * A queued document is in its summarise phase right now. The model switcher is disabled while
     * this holds: the reduce stage is one long generation on the resident engine, and swapping a
     * multi-gigabyte model in underneath it contends for memory exactly at its peak. Guarded on
     * ANALYSING because a run that fails mid-summary keeps summarising=true on its row -- without
     * the status check that dead row would disable switching forever.
     */
    val anySummarising: Boolean
        get() = documents.any { it.status == AuditStatus.ANALYSING && it.summarising }
}

sealed interface AuditIntent : UiIntent {
    /**
     * The route's starting model and mode. Honoured only the first time -- see
     * [AuditViewModel.openAudit].
     */
    data class Open(val modelId: String, val mode: AuditMode) : AuditIntent
    /** Switches the model that NEW documents will be pinned to. */
    data class SwitchModel(val modelId: String) : AuditIntent
    /**
     * Queues a picked file in [mode]. The mode travels with the intent rather than being read from
     * state, so what gets pinned is exactly the option the user tapped -- no window in which a
     * default could change between the tap and the file coming back from the picker.
     */
    data class AttachFile(val uri: Uri, val mode: AuditMode) : AuditIntent
    data class Cancel(val id: Long) : AuditIntent
    data class Retry(val id: Long) : AuditIntent
}

/**
 * Backs the Audit queue screen. Warms the active model (used to size chunks and pinned onto each
 * enqueued document), and drives everything through the durable [com.example.aiagenttestapp.data.audit.AuditQueue]:
 * adding a file or pasted transcript enqueues a document, and the UI observes the queue from Room, so
 * results survive leaving the screen, backgrounding, and process death.
 */
@HiltViewModel
class AuditViewModel @Inject constructor(
    private val auditQueue: AuditQueue,
    private val loadPlanner: AuditLoadPlanner,
    private val fileTextExtractor: FileTextExtractor,
    private val modelResidency: ModelResidency,
    private val models: ModelDirectory,
    private val settingsStore: SettingsStore,
) : MviViewModel<AuditUiState, AuditIntent, Nothing>(AuditUiState()) {

    private var currentModelId: String? = null
    private var attachedResidency = false
    private var observeJob: Job? = null
    private var modelsJob: Job? = null

    /** The in-flight load, so a re-entry or a double-tap cannot start a second, racing one. */
    private var loadJob: Job? = null

    override fun reduce(intent: AuditIntent): Unit = when (intent) {
        is AuditIntent.Open -> openAudit(intent.modelId, intent.mode)
        is AuditIntent.SwitchModel -> switchModel(intent.modelId)
        is AuditIntent.AttachFile -> attachFile(intent.uri, intent.mode)
        is AuditIntent.Cancel -> cancel(intent.id)
        is AuditIntent.Retry -> retry(intent.id)
    }

    /** Keeps the switcher's list current -- a download finishing mid-session makes its model appear. */
    private fun observeModels() {
        if (modelsJob != null) return
        modelsJob = viewModelScope.launch {
            models.all.collect { specs ->
                val usable = specs.filter { loadPlanner.plan(it.id) is AuditModelPlan.Ready }
                setState { copy(availableModels = usable) }
            }
        }
    }

    private fun openAudit(modelId: String, mode: AuditMode) {
        observeQueue()
        observeModels()
        // The route's model and mode are only the STARTING point. Both are baked into the navigation
        // route, and the caller's LaunchedEffect re-fires it every time this entry recomposes --
        // coming back from a report, most visibly -- so honouring it after a model has loaded would
        // silently revert whatever the user switched to on this screen.
        if (currentModelId != null || loadJob?.isActive == true) return
        setState { copy(mode = mode) }
        // Warmed in the route's mode. The two modes load different system prompts, so a user who
        // then queues in the other one costs the drain worker a reload -- an optimisation missing,
        // not a wrong result, and far cheaper than re-warming a multi-gigabyte model on every tap
        // of the picker.
        loadJob = viewModelScope.launch { loadModel(modelId, mode) }
    }

    /**
     * Switches the model that NEW documents will be pinned to. Documents already queued keep the
     * model they were enqueued with -- the pin exists precisely so a later switch cannot shift
     * their chunk sizes or change which model their report claims to come from.
     *
     * A successful switch also becomes the app's active model, matching what starting a chat from
     * the hub does -- so the choice made here survives leaving the screen instead of being undone
     * by the next navigation that reads the default.
     */
    private fun switchModel(modelId: String) {
        if (modelId == currentModelId && currentState.loadState is AuditLoadState.Ready) return
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            // Persisted only after the load succeeds: the default must never end up pointing at a
            // model that just failed to load here.
            if (loadModel(modelId, currentState.mode)) {
                settingsStore.update { it.copy(activeModelId = modelId) }
            }
        }
    }

    /** Loads [modelId] for auditing in [mode]. Returns true when it ended in [AuditLoadState.Ready]. */
    private suspend fun loadModel(modelId: String, mode: AuditMode): Boolean {
        setState { copy(loadState = AuditLoadState.Loading("Loading model")) }

        // One audit-shaped resolution, shared with the drain worker, so the two cannot disagree on
        // which engine or which load request an audit run uses.
        val plan = when (val result = loadPlanner.plan(modelId)) {
            is AuditModelPlan.Unavailable -> {
                fail(result.reason)
                return false
            }

            is AuditModelPlan.Ready -> result
        }

        setState {
            copy(
                model = plan.resolved.model,
                engineName = plan.engineName,
                loadState = AuditLoadState.Loading("Loading ${plan.modelName}"),
            )
        }

        return try {
            loadPlanner.open(plan, mode)
            currentModelId = modelId
            if (!attachedResidency) {
                modelResidency.attach()
                attachedResidency = true
            }
            setState { copy(loadState = AuditLoadState.Ready) }
            // Resume anything left pending by a prior process.
            auditQueue.ensureDrainerRunning()
            true
        } catch (t: Throwable) {
            fail(t.message ?: "Could not load the model.")
            false
        }
    }

    private fun fail(message: String) {
        setState { copy(loadState = AuditLoadState.Failed(message)) }
    }

    private fun observeQueue() {
        if (observeJob != null) return
        observeJob = viewModelScope.launch {
            auditQueue.documents.collect { docs ->
                // Resolved here, once per queue update, not in the row composable: the lookup is a
                // directory scan, and every row doing its own on every recomposition would repeat it.
                val names = docs.map { it.modelId }.distinct()
                    .mapNotNull { id -> models.find(id)?.let { spec -> id to spec.name } }
                    .toMap()
                setState { copy(documents = docs, modelNames = names) }
            }
        }
    }

    /** Reads a picked file and enqueues it as its own document -- so multiple files fan out cleanly. */
    private fun attachFile(uri: Uri, mode: AuditMode) {
        val model = currentState.model ?: return
        setState {
            copy(isExtractingFile = true, attachmentError = null, lastAdded = null, mode = mode)
        }
        viewModelScope.launch {
            when (val result = fileTextExtractor.extract(uri, maxChars = MAX_TRANSCRIPT_CHARS)) {
                is FileTextExtractor.Result.Success -> {
                    // Chunked against the window an audit run actually opens, not the model's
                    // maximum -- AuditModelPlan loads with the same capped value -- and for the mode
                    // being pinned here, which decides the reserve and so the section size.
                    auditQueue.enqueue(
                        result.name,
                        result.text,
                        model.id,
                        AuditChunker.auditContextTokens(model.contextTokens),
                        mode,
                    )
                    setState {
                        copy(
                            isExtractingFile = false,
                            lastAdded = result.name,
                            lastAddedMode = mode,
                            addTruncated = result.truncated,
                        )
                    }
                }

                is FileTextExtractor.Result.Failure -> setState {
                    copy(isExtractingFile = false, attachmentError = result.message)
                }
            }
        }
    }

    private fun cancel(id: Long) {
        viewModelScope.launch { auditQueue.cancel(id) }
    }

    private fun retry(id: Long) {
        viewModelScope.launch { auditQueue.retry(id) }
    }

    override fun onCleared() {
        // The queue keeps running on WorkManager; we only release the screen's hold on the model.
        if (attachedResidency) {
            modelResidency.detach()
            attachedResidency = false
        }
    }

    private companion object {
        /** Upper bound on one attached document (~100+ pages). Beyond this the file is truncated. */
        const val MAX_TRANSCRIPT_CHARS = 400_000
    }
}
