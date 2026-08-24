package com.example.aiagenttestapp.data.audit

import com.example.aiagent.engine.core.ContextWindow
import com.example.aiagenttestapp.prompts.audit.AuditQuickPrompts
import com.example.aiagenttestapp.prompts.audit.AuditSystemPrompts

/**
 * The quick read: one pass over a text for the conclusion it reached, its actions, and what it left
 * open.
 *
 * **The single implementation.** Both the audit queue and voice notes run this, rather than each
 * owning a quick mode that merely resembles the other's. That matters more here than it usually
 * would, because "quick" is not a size — it is a specific claim about a text (one conclusion, in the
 * shared [AuditResultType] vocabulary, with at most [QuickAudit.MAX_ACTIONS] actions and
 * [QuickAudit.MAX_UNRESOLVED] unresolved items) and two features making that claim differently would
 * put two different meanings behind one word in the same app.
 *
 * It is deliberately three separate steps rather than one function that runs the model. What to send,
 * how to read a reply, and how to combine the replies are all pure; *running* the model is not, and
 * the two callers do it very differently — the audit queue is a WorkManager job with Room
 * checkpointing per section, the record screen is a foreground coroutine streaming into UI state.
 * Sharing the pure parts is what centralises the behaviour; forcing the impure part into a shared
 * function would only have moved the difference somewhere less honest.
 *
 * Lives in `data/audit` with the vocabulary and the parser it is built from. The name says what it
 * does rather than who asked for it.
 */
object QuickRead {

    /**
     * What to send for one section. [totalParts] > 1 tells the model it is reading a part, so it
     * reports what is in front of it instead of writing a conclusion for a text it has only seen a
     * fraction of.
     */
    fun prompt(part: String, partNumber: Int, totalParts: Int): String =
        AuditQuickPrompts.quickExtraction(part, partNumber, totalParts)

    /**
     * What the fixed parts of a quick prompt cost, measured from the real builder so the sizing and
     * the prompt cannot drift apart.
     */
    fun promptTokens(): Int =
        ContextWindow.estimateTokens(AuditSystemPrompts.QUICK_SYSTEM_PROMPT) +
            ContextWindow.estimateTokens(prompt("", partNumber = 1, totalParts = MULTI_PART))

    /**
     * A stand-in "total parts" for measuring only. Two digits, so a text needing ten or more
     * sections is not charged a character less than it costs.
     */
    private const val MULTI_PART = 10

    /**
     * Splits [text] into sections for a quick read on a model with [contextTokens] of context.
     *
     * Sized through [AuditChunker.chunkCharBudget] with [AuditMode.QUICK], which is what makes quick
     * genuinely faster rather than merely shorter: its smaller output reserve and shorter preamble
     * leave more of every window for text, so the same document needs fewer sections.
     *
     * The audit queue does not call this — it pins its chunk boundaries at enqueue and checkpoints
     * against them, so a plan computed later could not be allowed to disagree with the one already
     * on disk. It sizes through the same [AuditChunker.chunkCharBudget] call underneath.
     */
    fun plan(
        text: String,
        contextTokens: Int,
        maxChunks: Int,
    ): AuditChunker.ChunkPlan {
        // Measured from the text: a German or Hindi transcript packs fewer characters into a token,
        // and sizing it as Latin builds sections that overflow the window they were sized for.
        val charsPerToken = ContextWindow.charsPerToken(text)
        val budget = AuditChunker.chunkCharBudget(
            contextTokens = contextTokens,
            promptTokens = promptTokens(),
            charsPerToken = charsPerToken,
            mode = AuditMode.QUICK,
        )
        return AuditChunker.plan(text, maxChars = budget, maxChunks = maxChunks)
    }

    /**
     * Reads one section's reply into a partial analysis.
     *
     * The same [AuditRecordParser] the detailed path uses, on the same RECORDS shape — there is no
     * second parser and no second grammar to keep in step.
     */
    fun parseSection(raw: String): AuditAnalysis = AuditRecordParser.parse(raw)

    /**
     * What a quick read produces, once every section has been read.
     *
     * A narrow type rather than a whole [AuditAnalysis]: these three are the entire deliverable, and
     * handing back the larger record would invite callers to fill fields the quick path deliberately
     * never populates.
     */
    data class Result(
        /**
         * The one conclusion, or null when no section reached one. Null is a real answer here — see
         * [AuditResultType] — so a text that concluded nothing shows nothing rather than a guess.
         */
        val element: AuditProtocolElement?,
        val actions: List<AuditFinding>,
        val unresolved: List<String>,
    )

    /**
     * Combines the per-section partials into the finished quick read.
     *
     * Everything is merged in code rather than by a second model turn. That was once a reduce pass,
     * and removing it removed the failure it owned: a summarising turn reads the notes with no memory
     * of the source and can only generalise them, which on a compliance artefact loses information in
     * the one direction that matters.
     *
     * The caps are applied here, after the merge, not merely asked for in the prompt — a small model
     * asked for "at most two" returns five, and a cap that lives only in a prompt is a request. The
     * survivors are the ones raised earliest, because the merges preserve order of first appearance
     * and nothing at this point knows which mattered most.
     */
    fun reduce(partials: List<AuditAnalysis>): Result = Result(
        // Collapsed to one: the per-section elements are a single conclusion seen from wherever its
        // evidence happened to fall, not several conclusions. collapseElements keeps the most severe
        // on the never-downgrade rule, so a section that saw the qualification cannot be outvoted by
        // sections that did not.
        element = AuditChunker.collapseElements(
            AuditChunker.mergeElements(partials.map { it.protocolElements }),
        ),
        actions = AuditChunker.mergeFindings(partials.map { it.actions })
            .take(QuickAudit.MAX_ACTIONS),
        unresolved = AuditChunker.mergeStrings(partials.map { it.unresolvedItems })
            .take(QuickAudit.MAX_UNRESOLVED),
    )
}
