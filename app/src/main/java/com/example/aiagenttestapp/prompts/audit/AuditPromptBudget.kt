package com.example.aiagenttestapp.prompts.audit

import com.example.aiagent.engine.core.ContextWindow
import com.example.aiagenttestapp.data.audit.AuditMode
import com.example.aiagenttestapp.data.audit.AuditOutputFormat
import com.example.aiagenttestapp.data.audit.AuditPromptProfile

/**
 * What the fixed parts of an audit prompt cost in tokens, so the chunker knows how much room is
 * left for the document.
 *
 * Derived by measuring the real prompt builders rather than by carrying its own copy of the
 * numbers -- edit a prompt and this follows, which is the only way the two cannot drift apart.
 * It is the chunker's whole interest in prompts, so it is the only thing the chunker has to import.
 */
object AuditPromptBudget {

    /**
     * Tokens one AuditExtractionPrompts.extraction turn spends before a single character of transcript: the system prompt
     * plus the AuditExtractionPrompts.preamble and section markers. Measured from the prompts themselves so it cannot
     * drift when they are edited.
     *
     * Does NOT include the `<think>` block a reasoning model may emit -- that is output, and is
     * covered by [AuditChunker.outputReserveTokens], which is sized for it.
     *
     * Defaults to [AuditPromptProfile.RICH] -- the larger of the two -- because chunk sizes are
     * pinned at enqueue while the engine, and so the profile, is resolved later. Reserving for the
     * largest AuditExtractionPrompts.preamble is the only sizing that stays safe if that resolution changes.
     */
    fun fixedPromptTokens(
        profile: AuditPromptProfile = AuditPromptProfile.RICH,
        facts: Boolean = true,
    ): Int =
        ContextWindow.estimateTokens(AuditSystemPrompts.SYSTEM_PROMPT) +
            ContextWindow.estimateTokens(
                // Measured with the draft, the larger of the two, so a run that drops it has more
                // room than reserved rather than less.
                AuditExtractionPrompts.extraction(
                    "",
                    partNumber = 1,
                    totalParts = AuditExtractionPrompts.MULTI_PART,
                    profile = profile,
                    draft = true,
                    facts = facts,
                ),
            )


    /**
     * The same measurement for whichever mode is running. Quick's AuditExtractionPrompts.preamble is a fraction of
     * detailed's, and that difference is most of why quick needs fewer sections to cover the same
     * document: the AuditExtractionPrompts.preamble is charged against every section's window, so a shorter one leaves more
     * of each window for text.
     */
    fun fixedPromptTokens(
        mode: AuditMode,
        profile: AuditPromptProfile,
        // Quick mode ignores it: its POINTS are its whole deliverable, not raw material for a
        // summary, so there is nothing to drop.
        facts: Boolean = true,
    ): Int = when (mode) {
        AuditMode.DETAILED -> fixedPromptTokens(profile, facts)
        AuditMode.QUICK -> quickFixedPromptTokens()
    }


    fun quickFixedPromptTokens(): Int =
        ContextWindow.estimateTokens(AuditSystemPrompts.QUICK_SYSTEM_PROMPT) +
            ContextWindow.estimateTokens(
                AuditQuickPrompts.quickExtraction("", partNumber = 1, totalParts = AuditExtractionPrompts.MULTI_PART),
            )
}
