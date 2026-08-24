package com.example.aiagenttestapp.ui.chat

import com.example.aiagent.engine.core.ContextWindow
import com.example.aiagent.engine.core.HistoryTurn

/**
 * How a saved conversation is turned back into something a model can continue.
 *
 * Two decisions, and the order between them is the whole point: the stored summary is folded into
 * the system prompt *first*, and the history is then fitted against what that longer prompt costs.
 * Fitting against the base prompt instead would over-fill by exactly the summary's length -- a
 * quiet overflow that only shows up as a model losing the start of a long conversation.
 *
 * Separated from the loading itself because it is the part with a right answer. Everything around
 * it -- attaching to residency, moving the screen through its load states, binding a tool runner --
 * is sequencing, and reads better where the state it drives lives.
 */
object ChatResume {

    /**
     * The system prompt a resumed chat loads with: the chat's own, plus the rolling summary of the
     * turns too old to send.
     *
     * Returns [base] unchanged when there is no summary, which is what makes a *fresh* chat's
     * request equal to the one the model was warmed with -- and that equality is what lets the
     * residency manager hand back a resident model instead of loading it again.
     */
    fun systemPrompt(base: String, storedSummary: String?): String {
        val summary = storedSummary?.takeIf { it.isNotBlank() } ?: return base
        return "$base\n\nSummary of the earlier part of this conversation:\n$summary"
    }

    /**
     * The tail of [past] that fits alongside [systemPrompt] in a [contextTokens] window.
     *
     * The whole transcript is still restored to the *display* -- the user sees everything they
     * wrote. This is only what the model is re-sent, which is why dropping the oldest turns is
     * acceptable: the summary above already stands in for them.
     */
    fun fittedHistory(
        past: List<HistoryTurn>,
        contextTokens: Int,
        systemPrompt: String,
    ): List<HistoryTurn> = ContextWindow.fit(
        history = past,
        contextTokens = contextTokens,
        systemPromptTokens = ContextWindow.estimateTokens(systemPrompt),
    )
}
