package com.example.aiagenttestapp.ui.history

import androidx.lifecycle.viewModelScope
import com.example.aiagent.engine.core.ModelSpec
import com.example.aiagenttestapp.data.audit.AuditDocument
import com.example.aiagenttestapp.data.chat.Conversation
import com.example.aiagenttestapp.ui.mvi.MviViewModel
import com.example.aiagenttestapp.ui.mvi.UiIntent
import com.example.aiagenttestapp.ui.mvi.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.example.aiagenttestapp.data.ModelDirectory
import com.example.aiagenttestapp.data.ModelRepository
import com.example.aiagenttestapp.data.audit.AuditQueue
import com.example.aiagenttestapp.data.chat.ChatDao
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** A saved conversation, with its model id resolved to a display name for the list. */
data class ChatHistoryItem(
    val id: Long,
    val title: String,
    val modelId: String,
    val modelName: String,
    val updatedAtMillis: Long,
)

/** One row of the home list: either a chat or an audit document. Sorted together, newest first. */
sealed interface HistoryEntry {
    val sortKey: Long

    data class Chat(val item: ChatHistoryItem) : HistoryEntry {
        override val sortKey get() = item.updatedAtMillis
    }

    /** [modelName] is the model that produced the report, resolved the same way chats resolve theirs. */
    data class Audit(val doc: AuditDocument, val modelName: String) : HistoryEntry {
        override val sortKey get() = doc.createdAtMillis
    }
}

data class HistoryUiState(
    /** Chats and audits interleaved by time -- audits live in their own table but browse here. */
    val entries: List<HistoryEntry> = emptyList(),

    /**
     * Every model a new chat could start on *right now*: the system-managed ones (Gemini Nano,
     * which is always "downloaded" -- the OS owns it) plus whatever the user has fully on disk.
     * Feeds the New-chat fan-out menu, so the choice it offers is never a model that would fail
     * with "not downloaded" one screen later.
     */
    val chatModels: List<ModelSpec> = emptyList(),
) : UiState

sealed interface HistoryIntent : UiIntent {
    data class DeleteChat(val id: Long) : HistoryIntent
    data class DeleteAudit(val id: Long) : HistoryIntent
}

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val models: ModelDirectory,
    private val auditQueue: AuditQueue,
    private val chatDao: ChatDao,
    private val modelRepository: ModelRepository,
) : MviViewModel<HistoryUiState, HistoryIntent, Nothing>(HistoryUiState()) {

    init {
        combine(
            chatDao.observeConversations().map { list -> list.map { it.toHistoryItem() } },
            auditQueue.documents,
        ) { chats, audits ->
            (chats.map { HistoryEntry.Chat(it) } + audits.map { HistoryEntry.Audit(it, it.modelDisplayName()) })
                .sortedByDescending { it.sortKey }
        }.collectIntoState { entries -> copy(entries = entries) }

        combine(models.all, modelRepository.downloadStates) { models, _ ->
            models.filter { modelRepository.isDownloaded(it) }
        }
            // isDownloaded stats files on disk; keep it off the main thread.
            .flowOn(Dispatchers.IO)
            .collectIntoState { chatModels -> copy(chatModels = chatModels) }
    }

    override fun reduce(intent: HistoryIntent) = when (intent) {
        is HistoryIntent.DeleteChat -> deleteChat(intent.id)
        is HistoryIntent.DeleteAudit -> deleteAudit(intent.id)
    }

    private fun deleteChat(id: Long) {
        viewModelScope.launch { chatDao.deleteConversation(id) }
    }

    private fun deleteAudit(id: Long) {
        viewModelScope.launch { auditQueue.cancel(id) }
    }

    /** Same fallback rule as chats: a deleted model still produced this report, so show its id. */
    private fun AuditDocument.modelDisplayName(): String =
        models.find(modelId)?.name ?: modelId

    private fun Conversation.toHistoryItem() = ChatHistoryItem(
        id = id,
        title = title,
        modelId = modelId,
        // A model the user has since deleted still has chats; fall back to the id.
        modelName = models.find(modelId)?.name ?: modelId,
        updatedAtMillis = updatedAtMillis,
    )
}
