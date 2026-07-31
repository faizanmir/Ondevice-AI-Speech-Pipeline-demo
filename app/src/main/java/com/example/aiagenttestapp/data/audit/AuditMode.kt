package com.example.aiagenttestapp.data.audit

/**
 * What kind of read to perform on a document.
 *
 * Pinned onto each document at enqueue, exactly like the model id and for the same reason: the mode
 * decides how the document was chunked, so a later switch cannot be allowed to reinterpret sections
 * that were sized for the other mode. One queue can therefore hold both kinds side by side.
 *
 * The two are not the same job at different lengths. [DETAILED] decides *what counts as a
 * non-conformity* and has to defend each one with a verified quote and a grade; [QUICK] only has to
 * say what the document contains and what it says will be done. That difference is why quick is
 * fast: it drops the draft pass, the evidence quotes and the whole severity-grading stage, and its
 * far shorter preamble leaves more of every window for text -- so a document also needs fewer
 * sections to cover.
 *
 * What quick does NOT drop is chunking. It reads the whole document section by section like detailed
 * does, then condenses; a "quick" mode that only read the first window would be a different and much
 * worse feature.
 */
enum class AuditMode {
    /** The full audit: non-conformities, severity grades, verified quotes, cited standards. */
    DETAILED,

    /** Key points and actions only, condensed to at most [QuickAudit.MAX_POINTS] points. */
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
            QUICK -> "Up to ${QuickAudit.MAX_POINTS} key points and the actions. Much faster."
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
     * The most points a quick summary may hold.
     *
     * Asked for in the prompt *and* enforced in code after the reply: a small model asked for "at
     * most 10" will hand back 14, and a cap that only lives in a prompt is a request, not a limit.
     */
    const val MAX_POINTS = 10
}
