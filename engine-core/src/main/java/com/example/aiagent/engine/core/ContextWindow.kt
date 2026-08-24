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

    /**
     * Chars per token for Latin text. Low on purpose -- real English runs nearer 4 -- so the estimate
     * comes out high and a prompt lands under its budget rather than over.
     */
    const val LATIN_CHARS_PER_TOKEN = 3.5

    /**
     * Chars per token for Han, kana and hangul. A single ideograph is usually a whole token, so a
     * Latin ratio over-counts the budget by roughly 3x: a chunk sized at 3.5 chars/token would be
     * three times the tokens it was budgeted for and overflow the window on its first turn.
     */
    private const val CJK_CHARS_PER_TOKEN = 1.2

    /**
     * Everything else non-Latin -- Devanagari, Arabic, Cyrillic, Thai, Hebrew. Byte-pair vocabularies
     * are trained overwhelmingly on Latin text, so these fragment into far more tokens per character
     * than English does, though less severely than CJK.
     */
    private const val OTHER_SCRIPT_CHARS_PER_TOKEN = 2.0

    /**
     * Characters per token in [text], weighted by the scripts it actually contains.
     *
     * A single ratio is only right for one alphabet, and this app transcribes and audits in whatever
     * language the user speaks. A flat Latin ratio applied to a Hindi or Chinese transcript sizes
     * every chunk two to three times too large, and nothing downstream would catch it -- the prompt
     * simply overflows and the section is lost.
     *
     * Sampled rather than fully scanned: this runs per turn on the chat path, and a few hundred
     * characters settle the script mix of a document as well as a full pass would.
     */
    fun charsPerToken(text: String): Double {
        if (text.isEmpty()) return LATIN_CHARS_PER_TOKEN

        val step = maxOf(1, text.length / SAMPLE_POINTS)
        var latin = 0
        var cjk = 0
        var other = 0
        var index = 0
        while (index < text.length) {
            val c = text[index]
            when {
                // Digits, punctuation and whitespace tokenise like Latin whatever surrounds them.
                c.code < 0x80 || !c.isLetter() -> latin++
                isCjk(c) -> cjk++
                else -> other++
            }
            index += step
        }

        val total = latin + cjk + other
        if (total == 0) return LATIN_CHARS_PER_TOKEN
        return (
            latin * LATIN_CHARS_PER_TOKEN +
                cjk * CJK_CHARS_PER_TOKEN +
                other * OTHER_SCRIPT_CHARS_PER_TOKEN
            ) / total
    }

    private fun isCjk(c: Char): Boolean = when (Character.UnicodeBlock.of(c)) {
        Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS,
        Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A,
        Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS,
        Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION,
        Character.UnicodeBlock.HIRAGANA,
        Character.UnicodeBlock.KATAKANA,
        Character.UnicodeBlock.HANGUL_SYLLABLES,
        Character.UnicodeBlock.HANGUL_JAMO,
        -> true

        else -> false
    }

    /** Sample points for [charsPerToken]. Enough to settle a script mix; cheap enough to call often. */
    private const val SAMPLE_POINTS = 512

    fun estimateTokens(text: String): Int =
        kotlin.math.ceil(text.length / charsPerToken(text)).toInt()

    /**
     * Inverse of [estimateTokens]: the largest number of characters whose estimate still fits within
     * [tokens]. Used to size how much of an attached file can be fed to a model of a given context
     * before it must be truncated. Rounds down, so the estimate never claims more room than there is.
     *
     * [charsPerToken] must be the ratio of the text that will actually fill those characters --
     * measure it with [charsPerToken] on the document itself. It defaults to the Latin ratio only
     * because a caller with no text in hand has nothing better to assume.
     */
    fun estimateChars(tokens: Int, charsPerToken: Double = LATIN_CHARS_PER_TOKEN): Int =
        (tokens.coerceAtLeast(0) * charsPerToken).toInt()

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
