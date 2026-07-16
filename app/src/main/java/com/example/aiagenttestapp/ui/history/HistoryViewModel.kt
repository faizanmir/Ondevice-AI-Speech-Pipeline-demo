package com.example.aiagenttestapp.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.aiagent.engine.core.ModelSpec
import com.example.aiagenttestapp.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A saved conversation, with its model id resolved to a display name for the list. */
data class ChatHistoryItem(
    val id: Long,
    val title: String,
    val modelId: String,
    val modelName: String,
    val updatedAtMillis: Long,
)

class HistoryViewModel(private val container: AppContainer) : ViewModel() {

    val conversations: StateFlow<List<ChatHistoryItem>> =
        container.chatDao.observeConversations()
            .map { list ->
                list.map { c ->
                    ChatHistoryItem(
                        id = c.id,
                        title = c.title,
                        modelId = c.modelId,
                        // A model the user has since deleted still has chats; fall back to the id.
                        modelName = container.findModel(c.modelId)?.name ?: c.modelId,
                        updatedAtMillis = c.updatedAtMillis,
                    )
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Every model a new chat could start on *right now*: the system-managed ones (Gemini Nano,
     * which is always "downloaded" -- the OS owns it) plus whatever the user has fully on disk.
     * Feeds the New-chat fan-out menu, so the choice it offers is never a model that would fail
     * with "not downloaded" one screen later.
     *
     * Recomputed when the catalogue changes *or* a download finishes -- the disk check is the
     * authority, downloadStates is just the trigger to re-run it.
     */
    val chatModels: StateFlow<List<ModelSpec>> =
        combine(container.allModels, container.modelRepository.downloadStates) { models, _ ->
            models.filter { container.modelRepository.isDownloaded(it) }
        }
            // isDownloaded stats files on disk; keep it off the main thread.
            .flowOn(Dispatchers.IO)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun delete(id: Long) {
        viewModelScope.launch { container.chatDao.deleteConversation(id) }
    }
}
