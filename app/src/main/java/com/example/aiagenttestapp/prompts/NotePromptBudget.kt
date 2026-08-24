package com.example.aiagenttestapp.prompts

import com.example.aiagent.engine.core.ContextWindow
import com.example.aiagenttestapp.data.notes.TaggedItem

/**
 * What the fixed parts of a note-summary prompt cost in tokens, so the chunker knows how much of the
 * window is left for transcript.
 *
 * Derived by measuring the real prompt builder rather than carrying its own copy of the numbers --
 * edit [NotePrompts.analysisPrompt] and this follows, which is the only arrangement in which the two
 * cannot drift apart. It is the chunker's entire interest in prompts, so it is the only thing the
 * chunker has to import.
 *
 * The counterpart to [com.example.aiagenttestapp.prompts.audit.AuditPromptBudget], and it exists for
 * the same reason: a chunk sized against a guessed preamble is a chunk that overflows the window it
 * was sized for.
 *
 * Detailed only. The quick path does not come through here at all -- it sends the shared quick prompt
 * and sizes itself with [com.example.aiagenttestapp.data.audit.QuickRead.promptTokens], so there is
 * one measurement per prompt rather than one per feature.
 */
object NotePromptBudget {

    /**
     * Tokens one summarisation turn spends before a single character of transcript: the system
     * prompt, the instructions, the language directive, and the tagged-item list.
     *
     * [tagged] is charged in full, at the whole note's list rather than any one section's, and that
     * is deliberate over-reserving. The list is rebuilt per section from the markers that section
     * actually contains, so every section's real cost is at most this. Sizing against the maximum
     * means a note whose markers all cluster in one section still fits; sizing against an average
     * would fit until exactly that note arrived.
     *
     * Measured with the multi-part banner always present ([MULTI_PART]), because whether a note needs
     * more than one section is decided *by* this number -- a budget computed without the banner and
     * then used for a chunked run would under-reserve by exactly the banner, on every section.
     */
    fun fixedPromptTokens(
        tagged: List<TaggedItem> = emptyList(),
        language: String? = null,
    ): Int =
        ContextWindow.estimateTokens(NotePrompts.SYSTEM_PROMPT) +
            ContextWindow.estimateTokens(
                NotePrompts.analysisPrompt(
                    transcript = "",
                    tagged = tagged,
                    language = language,
                    partNumber = 1,
                    totalParts = MULTI_PART,
                ),
            )

    /**
     * A stand-in "total parts" used only for measuring. Two digits rather than one, so a note that
     * turns out to need ten or more sections is not charged a character less than it costs.
     */
    const val MULTI_PART = 10
}
