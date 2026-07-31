package com.example.aiagenttestapp.ui.chat

import com.example.aiagent.engine.core.Accelerator
import com.example.aiagent.engine.core.EngineException
import com.example.aiagent.engine.core.InferenceEngine
import com.example.aiagent.engine.core.NativeToolEngine
import com.example.aiagent.engine.core.ToolRunner
import com.example.aiagenttestapp.data.ChatLoadPlan
import com.example.aiagenttestapp.data.chat.StoredMessage
import com.example.aiagenttestapp.data.chat.toHistoryTurn
import com.example.aiagenttestapp.functions.ToolCallingStrategy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * One chat's model, from opening it to handing it back.
 *
 * The load path is a sequence with a lot of *ordering* in it and very little branching: publish the
 * engine before anything can fail, restore the conversation, fold its summary into the prompt, fit
 * the history, attach to residency, open, bind a tool runner, report ready. Undoing it has an order
 * too, and the two are mirror images -- which was impossible to see when the second half lived in
 * `onCleared` next to unrelated teardown.
 *
 * The session owns everything with the *model's* lifetime: the engine, whether this screen is
 * holding residency, the tool strategy, and the runner it bound. What the screen shows while all
 * that happens is reported through [Listener] and stays the view model's decision.
 */
class ChatSession(
    private val planner: ChatModelPlanner,
    private val store: ChatStore,
    private val residency: ChatResidency,
    private val scope: CoroutineScope,
    private val listener: Listener,
) {

    /** What the session tells the screen, in the order it happens. */
    interface Listener {
        /** No model with that id, or no engine in this build that can load it. */
        fun onUnloadable(plan: ChatLoadPlan)

        /**
         * The engine and accelerator are settled. Reported before the load is attempted so a
         * failure screen still reads "LiteRT-LM · GPU" rather than a bare "· CPU".
         */
        fun onPlanned(plan: ChatLoadPlan.Ready)

        /** A saved conversation's turns, oldest first, for the display. */
        fun onRestored(messages: List<StoredMessage>)

        /**
         * The tools the runtime will call, or null when this model gets none. Asked for only when
         * the engine runs tools itself; a prompt-driven one is told about its tools in the prompt.
         */
        fun createToolRunner(): ToolRunner

        /** Loaded. [accelerator] is what the engine actually used, which may not be what was asked. */
        fun onReady(accelerator: Accelerator)

        fun onFailed(message: String)
    }

    /** The loaded engine, or null before [open] succeeds and after [close]. */
    var engine: InferenceEngine? = null
        private set

    /** How this model is offered the app's functions. Fixed for the life of the load. */
    var toolStrategy: ToolCallingStrategy = com.example.aiagenttestapp.functions.PromptToolCalling
        private set

    var toolsEnabled: Boolean = false
        private set

    /** What this chat writes down. Null until a model is resolved, since it is named after one. */
    var transcript: ChatTranscriptStore? = null
        private set

    private var boundEngine: NativeToolEngine? = null
    private var holdsResidency = false

    /** Kept so the conversation can be reloaded mid-chat when it outgrows the window. */
    private var loadedPlan: ChatLoadPlan.Ready? = null

    /**
     * Resolves [modelId], restores [resumeConversationId] if given, and loads.
     *
     * Returns immediately; everything after planning happens on [scope].
     */
    fun open(modelId: String, resumeConversationId: Long?) {
        val plan = when (val result = planner.plan(modelId)) {
            is ChatLoadPlan.Ready -> result
            else -> {
                listener.onUnloadable(result)
                return
            }
        }

        toolsEnabled = plan.toolsEnabled
        toolStrategy = ToolCallingStrategy.forEngine(plan.engine.descriptor)
        listener.onPlanned(plan)

        if (!plan.downloaded) {
            listener.onFailed("${plan.model.name} is not downloaded")
            return
        }

        engine = plan.engine
        transcript = ChatTranscriptStore(store, modelId)

        // Tell residency a chat is using the engine, so the resident model is not released under
        // memory pressure while this chat is on screen. Balanced by close().
        if (!holdsResidency) {
            residency.attach()
            holdsResidency = true
        }

        loadedPlan = plan
        scope.launch { load(plan, resumeConversationId) }
    }

    /**
     * Rolls the conversation up and reloads on it when it has outgrown the model's window.
     *
     * Called before a turn, because a conversation that no longer fits does not fail loudly -- it
     * runs out of room mid-reply. llama.cpp simply stops emitting tokens once `n_past` reaches
     * `n_ctx`, which reads as the model having finished, and LiteRT-LM raises a generation error
     * from somewhere the user cannot connect to what they typed.
     *
     * Compaction is a summary, not a truncation: the older turns become the rolling summary that a
     * resumed chat already uses, and the recent ones are refitted beneath it. So the model keeps
     * what was decided fifty turns ago instead of losing it -- which dropping the oldest messages
     * would do silently.
     *
     * The threshold is higher than the on-close one. Closing is free, so it summarises early;
     * compacting mid-chat costs the user a wait before their message is answered, so it holds off
     * until the window really is nearly gone.
     *
     * Returns true when it compacted, so the screen can say why the turn is slow.
     */
    suspend fun compactIfNeeded(contextTotal: Int): Boolean {
        val plan = loadedPlan ?: return false
        val active = engine ?: return false
        val store = transcript ?: return false
        if (contextTotal <= 0) return false
        if (active.contextTokensUsed() < contextTotal * COMPACT_AT) return false

        // Summarise on the model that still holds the conversation, then reload onto the summary.
        // Under the lock, so no other screen resets the model between the two halves.
        residency.runExclusive { runCatching { store.rollUpSummary(active) } }
        val conversationId = store.conversationId ?: return false
        load(plan, conversationId)
        return true
    }

    private suspend fun load(plan: ChatLoadPlan.Ready, resumeConversationId: Long?) {
        // The whole transcript is restored to the display; only a fitted tail is fed to the model,
        // with the summary of the older turns folded into the system prompt ahead of it.
        val restored = resumeConversationId
            ?.let { id -> runCatching { store.conversation(id) }.getOrNull() }
        val past = restored?.messages.orEmpty().sortedBy { it.id }
        restored?.conversation?.id?.let { transcript?.resume(it) }
        if (past.isNotEmpty()) listener.onRestored(past)

        val systemPrompt = ChatResume.systemPrompt(plan.systemPrompt, restored?.conversation?.summary)
        val history = ChatResume.fittedHistory(
            past = past.map { it.toHistoryTurn() },
            contextTokens = plan.contextTokens,
            systemPrompt = systemPrompt,
        )

        // A fresh chat's request is exactly plan.freshLoadRequest(), which is what the model was
        // warmed with -- so residency can hand it back with a conversation reset and no load at
        // all. A resumed chat overrides the prompt and seeds history, so it always loads.
        val request = plan.freshLoadRequest().copy(
            systemPrompt = systemPrompt,
            initialHistory = history,
        )

        try {
            residency.open(
                plan = plan.resolved,
                request = request,
                reuseWhenResident = resumeConversationId == null,
            )
            bindToolRunner(plan.engine)
            listener.onReady(plan.engine.activeAccelerator ?: plan.accelerator)
        } catch (e: EngineException.OutOfMemory) {
            listener.onFailed(
                "${plan.model.name} ran out of memory while loading. Close other apps and try " +
                    "again, or pick a smaller model.",
            )
        } catch (e: Exception) {
            listener.onFailed(e.message ?: "Could not load ${plan.model.name}")
        }
    }

    /**
     * After the load, not as part of the request: the model may have been warmed in the background
     * before this screen existed, and the runner belongs to this screen.
     *
     * The cast cannot silently miss -- an engine claiming native tool support without implementing
     * [NativeToolEngine] is rejected when the engine registry is built.
     */
    private fun bindToolRunner(engine: InferenceEngine) {
        boundEngine = engine as? NativeToolEngine
        boundEngine?.toolRunner = if (toolsEnabled && toolStrategy is ToolCallingStrategy.RuntimeDriven) {
            listener.createToolRunner()
        } else {
            null
        }
    }

    private companion object {
        /**
         * Context fraction past which a live conversation is compacted. Deliberately close to full:
         * every compaction costs a summary turn plus a reload, so it is worth paying only when the
         * alternative is a reply that stops mid-sentence.
         */
        const val COMPACT_AT = 0.85f
    }

    /** Forgets the conversation without touching the loaded model. */
    fun resetConversation(modelId: String) {
        transcript = ChatTranscriptStore(store, modelId)
    }

    /**
     * Hands the model back: unbind, roll up a summary if the conversation earned one, detach.
     *
     * The mirror of [open], and the order is as load-bearing. The runner goes first so a screen
     * that no longer exists cannot be called into. The summary runs under residency's lock, so a
     * chat opening right now waits rather than resetting the model mid-summary, and the detach
     * happens only after it -- staying attached is what stops the model being released out from
     * under the summary. Nothing unloads: the model stays resident for the next chat.
     *
     * [teardownScope] must outlive the view model's, which is already cancelled by this point.
     */
    fun close(teardownScope: CoroutineScope, isGenerating: Boolean, contextTotal: Int) {
        boundEngine?.toolRunner = null
        boundEngine = null

        val active = engine
        val store = transcript
        active?.cancel() // stop any in-flight decode before the engine is reused for the summary

        val shouldSummarise = active != null && store != null && !isGenerating &&
            store.needsSummary(active, contextTotal)

        engine = null
        if (!holdsResidency) return
        holdsResidency = false

        teardownScope.launch {
            try {
                if (shouldSummarise && active != null && store != null) {
                    residency.runExclusive { runCatching { store.rollUpSummary(active) } }
                }
            } finally {
                residency.detach()
            }
        }
    }
}
