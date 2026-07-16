package com.example.aiagent.engine.core

/**
 * One past turn as the model should see it. Role strings match the chat-template convention the
 * engines already use ("user" / "assistant"), so a turn can be handed straight to either backend.
 */
data class HistoryTurn(val role: String, val content: String) {
    companion object {
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
    }
}

/**
 * Chooses how much of a conversation's history to feed the model when a chat is reopened.
 *
 * On-device context is tiny (a few thousand tokens) and prefill is slow, so the whole transcript
 * cannot be replayed. This picks the most recent turns that fit, leaving room for the live exchange.
 * The distant past is meant to be covered by a rolling summary (folded into the system prompt by the
 * caller), not by this -- this only decides the *verbatim* tail.
 *
 * There is no tokenizer on the Kotlin side, so token counts are estimated, and deliberately
 * *over*-counted (few chars per token) so the real prompt lands under the budget rather than over.
 */
object ContextWindow {

    /** Tokens a chat template spends on the scaffolding around each turn (role headers, EOT). */
    private const val PER_TURN_OVERHEAD = 8

    /** Low chars-per-token on purpose: the estimate runs high, so we under-fill, never overflow. */
    private const val CHARS_PER_TOKEN = 3.5

    fun estimateTokens(text: String): Int = kotlin.math.ceil(text.length / CHARS_PER_TOKEN).toInt()

    /**
     * The longest *suffix* of [history] whose estimated tokens, plus [systemPromptTokens] and
     * [reserveTokens] of headroom for the next prompt and its reply, fit in [contextTokens].
     *
     * Whole turns are kept, newest-first, then chronological order is restored. Any leading
     * assistant turn is trimmed so the history always opens on a user turn -- an assistant reply with
     * no preceding question reads as broken to the model.
     */
    fun fit(
        history: List<HistoryTurn>,
        contextTokens: Int,
        systemPromptTokens: Int,
        reserveTokens: Int = 768,
    ): List<HistoryTurn> {
        val budget = contextTokens - systemPromptTokens - reserveTokens
        if (budget <= 0 || history.isEmpty()) return emptyList()

        val kept = ArrayDeque<HistoryTurn>()
        var used = 0
        for (turn in history.asReversed()) {
            val cost = estimateTokens(turn.content) + PER_TURN_OVERHEAD
            if (used + cost > budget) break
            used += cost
            kept.addFirst(turn)
        }
        while (kept.isNotEmpty() && kept.first().role != HistoryTurn.ROLE_USER) kept.removeFirst()
        return kept.toList()
    }
}
