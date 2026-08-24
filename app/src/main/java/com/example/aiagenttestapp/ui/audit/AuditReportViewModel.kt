package com.example.aiagenttestapp.ui.audit

import androidx.lifecycle.viewModelScope
import com.example.aiagenttestapp.data.audit.AuditDocument
import com.example.aiagenttestapp.ui.mvi.MviViewModel
import com.example.aiagenttestapp.ui.mvi.UiIntent
import com.example.aiagenttestapp.ui.mvi.UiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.example.aiagenttestapp.data.ModelDirectory
import com.example.aiagenttestapp.data.audit.AuditQueue
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

data class AuditReportUiState(
    val document: AuditDocument? = null,
    /**
     * The display name of the model that produced the report. Resolved from the id pinned on the
     * document, not from whatever is active now -- and falling back to the raw id, since a model the
     * user has since deleted still wrote this report and the reader deserves to know which one.
     *
     * Carried in the same state as [document] rather than its own flow: the two are read together
     * on every frame, and separate flows would let a report render for a moment under the previous
     * document's model name.
     */
    val modelName: String? = null,
) : UiState

sealed interface AuditReportIntent : UiIntent {
    data class Load(val id: Long) : AuditReportIntent
}

/** Observes one saved audit document, for the read-only report screen (reachable from chat too). */
@HiltViewModel
class AuditReportViewModel @Inject constructor(
    private val models: ModelDirectory,
    private val auditQueue: AuditQueue,
) : MviViewModel<AuditReportUiState, AuditReportIntent, Nothing>(AuditReportUiState()) {

    private var job: Job? = null

    /** Which document [job] is following, so re-selecting the open one does not restart it. */
    private var loadedId: Long? = null

    override fun reduce(intent: AuditReportIntent) = when (intent) {
        is AuditReportIntent.Load -> load(intent.id)
    }

    private fun load(id: Long) {
        // Cancel and restart rather than ignore. The guard used to be `if (job != null) return`,
        // which was true while this screen was only ever reached by a route carrying one document
        // id -- one instance, one document, for its whole life. In a detail pane the same instance
        // is asked for a second document the moment the user picks another row, and the old guard
        // answered by silently continuing to show the first.
        if (loadedId == id) return
        loadedId = id
        job?.cancel()
        job = viewModelScope.launch {
            auditQueue.document(id).collect { doc ->
                setState {
                    copy(
                        document = doc,
                        modelName = doc?.let { models.find(it.modelId)?.name ?: it.modelId },
                    )
                }
            }
        }
    }
}
