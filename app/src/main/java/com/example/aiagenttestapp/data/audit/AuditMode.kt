package com.example.aiagenttestapp.data.audit

/**
 * What kind of read to perform on a document.
 *
 * Pinned onto each document at enqueue, exactly like the model id and for the same reason: the mode
 * decides how the document was chunked, so a later switch cannot be allowed to reinterpret sections
 * that were sized for the other mode. One queue can therefore hold both kinds side by side.
 *
 * The two are not the same job at different lengths. Both now reach a conclusion -- quick states its
 * result in the same [AuditResultType] vocabulary detailed does -- but [DETAILED] also enumerates
 * *what counts as a non-conformity* and defends each one with a verified quote, a grade and the
 * clause it cites. [QUICK] answers one question about the document and stops: what was evaluated,
 * what was concluded, why, on what evidence, plus the actions and what was left open.
 *
 * That difference is why quick is fast: it drops the draft pass, the per-finding evidence quotes,
 * the severity grading, the cited standards and the whole prose-summary turn, and its far shorter
 * preamble leaves more of every window for text -- so a document also needs fewer sections to cover.
 *
 * What quick does NOT drop is chunking. It reads the whole document section by section like detailed
 * does, and the per-section elements are merged and collapsed in code afterwards; a "quick" mode that
 * only read the first window would be a different and much worse feature.
 */
enum class AuditMode {
    /** The full audit: non-conformities, severity grades, verified quotes, cited standards. */
    DETAILED,

    /** One evaluation -- result, reason, evidence -- plus the actions and the unresolved items. */
    QUICK,
    ;

    val label: String
        get() = when (this) {
            DETAILED -> "In detail"
            QUICK -> "Quick summary"
        }

    /** One line for the picker, saying what the choice actually costs and buys. */
    val blurb: String
        get() = when (this) {
            DETAILED -> "Non-conformities with severity, quotes and clauses. Slower."
            QUICK -> "The evaluation, its result, reason and evidence. Much faster."
        }

    companion object {
        /** Reads a stored name back, defaulting to [DETAILED] -- what every document was before this. */
        fun from(name: String?): AuditMode =
            entries.firstOrNull { it.name == name } ?: DETAILED
    }
}

/** Shared constants for the quick read, so the prompt, the cap and the UI copy cannot disagree. */
object QuickAudit {
    /**
     * The most actions a quick report may carry.
     *
     * Asked for in the prompt *and* enforced in code after the merge: a small model asked for "at
     * most two" hands back five, and a cap that only lives in a prompt is a request, not a limit.
     *
     * The cap is applied to the merged list, so the two that survive are the two raised earliest in
     * the document. That is a choice, not an accident: it is the only ordering the pipeline can
     * defend without a second model turn, since [AuditChunker.mergeFindings] preserves order of
     * first appearance and nothing downstream knows which action mattered most.
     */
    const val MAX_ACTIONS = 2

    /** The most unresolved items a quick report may carry. Capped exactly as [MAX_ACTIONS] is. */
    const val MAX_UNRESOLVED = 2
}
