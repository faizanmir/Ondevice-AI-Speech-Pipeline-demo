package com.example.aiagenttestapp.ui.chat

import com.example.aiagent.engine.core.InferenceEngine
import com.example.aiagent.engine.core.LoadRequest
import com.example.aiagenttestapp.data.ChatLoadPlan
import com.example.aiagenttestapp.data.ChatLoadPlanner
import com.example.aiagenttestapp.data.ModelLoadPlan
import com.example.aiagenttestapp.data.ModelResidency
import com.example.aiagenttestapp.data.chat.ChatDao
import com.example.aiagenttestapp.data.chat.Conversation
import com.example.aiagenttestapp.data.chat.ConversationWithMessages
import com.example.aiagenttestapp.data.chat.StoredMessage
import javax.inject.Inject

/**
 * What [ChatSession] needs, declared by the code that uses it rather than by the classes that
 * provide it.
 *
 * The load path is a sequence whose *order* is the thing that can be wrong -- unbind before the
 * screen dies, summarise before detaching, publish the engine before the load can fail -- and none
 * of that was checkable, because every collaborator reached a `Context`: the planner through
 * settings, the store through Room, residency through both. Behind these three it runs on a plain
 * JVM against fakes, and the ordering becomes an assertion instead of a comment.
 *
 * Narrow on purpose. Residency has a memory-pressure API and a preloader; a chat only ever attaches,
 * opens, detaches and takes the lock, so that is all it can name.
 */
interface ChatModelPlanner {
    fun plan(modelId: String): ChatLoadPlan
}

/** Reading and writing one chat's rows. */
interface ChatStore {
    suspend fun conversation(id: Long): ConversationWithMessages?
    suspend fun insertConversation(conversation: Conversation): Long
    suspend fun insertMessage(message: StoredMessage)
    suspend fun touch(id: Long, at: Long)
    suspend fun updateSummary(id: Long, summary: String)
}

/** The resident model, as a chat uses it. */
interface ChatResidency {
    fun attach()
    fun detach()
    suspend fun open(
        plan: ModelLoadPlan.Resolved,
        request: LoadRequest,
        reuseWhenResident: Boolean,
    ): InferenceEngine

    /** Runs [block] with the engine to itself, so no chat can reset it midway. */
    suspend fun runExclusive(block: suspend () -> Unit)
}

class RealChatModelPlanner @Inject constructor(
    private val planner: ChatLoadPlanner,
) : ChatModelPlanner {
    override fun plan(modelId: String) = planner.plan(modelId)
}

class RealChatStore @Inject constructor(private val dao: ChatDao) : ChatStore {
    override suspend fun conversation(id: Long) = dao.conversationById(id)
    override suspend fun insertConversation(conversation: Conversation) =
        dao.insertConversation(conversation)

    override suspend fun insertMessage(message: StoredMessage) {
        dao.insertMessage(message)
    }

    override suspend fun touch(id: Long, at: Long) = dao.touchConversation(id, at)
    override suspend fun updateSummary(id: Long, summary: String) = dao.updateSummary(id, summary)
}

class RealChatResidency @Inject constructor(
    private val residency: ModelResidency,
) : ChatResidency {
    override fun attach() = residency.attach()
    override fun detach() = residency.detach()
    override suspend fun open(
        plan: ModelLoadPlan.Resolved,
        request: LoadRequest,
        reuseWhenResident: Boolean,
    ) = residency.open(plan, request, reuseWhenResident)

    override suspend fun runExclusive(block: suspend () -> Unit) = residency.runExclusive(block)
}
