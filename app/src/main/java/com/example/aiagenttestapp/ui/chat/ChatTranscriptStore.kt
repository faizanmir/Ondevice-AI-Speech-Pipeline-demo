package com.example.aiagenttestapp.ui.chat

import com.example.aiagent.engine.core.GenerationEvent
import com.example.aiagent.engine.core.InferenceEngine
import com.example.aiagenttestapp.data.chat.ChatDao
import com.example.aiagenttestapp.data.chat.Conversation
import com.example.aiagenttestapp.data.chat.StoredMessage
import com.example.aiagenttestapp.prompts.ChatPrompts

/**
 * What a chat writes down: the conversation row, its turns, and the summary it is rolled up into.
 *
 * Pulled out of the view model because it is the only part of a chat that outlives the screen, and
 * it was tangled with the part that does not. The conversation id in particular is a piece of
 * persistence state the view model held and passed back into itself; here it belongs to the thing
 * that owns the rows.
 *
 * Created lazily on purpose: a chat the user opens and abandons should leave nothing behind, so no
 * row exists until the first message is actually stored.
 */
class ChatTranscriptStore(
    private val chatDao: ChatDao,
    private val modelId: String,
) {

    /** The conversation being written to, or null until the first message creates it. */
    var conversationId: Long? = null
        private set

    /** Adopts an existing conversation, when a chat is resumed rather than started. */
    fun resume(id: Long) {
        conversationId = id
    }

    /**
     * Appends one turn, creating the conversation on the first call.
     *
     * [title] names a *new* conversation and is ignored once one exists -- it is the first user
     * message, trimmed, which is what the history list shows.
     */
    suspend fun append(role: String, content: String, title: String) {
        if (content.isBlank()) return
        val id = conversationId ?: create(title)
        val now = System.currentTimeMillis()
        chatDao.insertMessage(
            StoredMessage(conversationId = id, role = role, content = content, createdAtMillis = now),
        )
        chatDao.touchConversation(id, now)
    }

    private suspend fun create(title: String): Long {
        val now = System.currentTimeMillis()
        return chatDao.insertConversation(
            Conversation(
                modelId = modelId,
                title = title.trim().replace('\n', ' ').take(60).ifBlank { "New chat" },
                createdAtMillis = now,
                updatedAtMillis = now,
            ),
        ).also { conversationId = it }
    }

    /**
     * Whether the conversation has grown enough to be worth summarising on the way out.
     *
     * Measured against what the model is actually holding rather than the message count, because
     * that is what decides whether reopening the chat will fit.
     */
    fun needsSummary(engine: InferenceEngine, contextTotal: Int): Boolean =
        contextTotal > 0 && engine.contextTokensUsed() > contextTotal * SUMMARY_TRIGGER_FRACTION

    /**
     * Asks the loaded model to summarise the conversation it already holds, and stores the result.
     *
     * The model summarises itself in a single turn because its context *is* the conversation --
     * nothing has to be re-sent. Does nothing if there is no conversation to write to.
     */
    suspend fun rollUpSummary(engine: InferenceEngine) {
        val id = conversationId ?: return
        val builder = StringBuilder()
        engine.generate(ChatPrompts.SUMMARISE_PROMPT).collect { event ->
            if (event is GenerationEvent.Token) builder.append(event.text)
        }
        val summary = builder.toString().trim()
        if (summary.isNotBlank()) chatDao.updateSummary(id, summary)
    }

    private companion object {
        /** Context fraction past which a chat is summarised on close, so reopening stays in budget. */
        const val SUMMARY_TRIGGER_FRACTION = 0.5f
    }
}
