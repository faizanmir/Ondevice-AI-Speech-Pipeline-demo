package com.example.aiagenttestapp.data.notes

/**
 * What kind of summary to write for a voice note.
 *
 * The same choice the audit queue offers, and — for [QUICK] — literally the same pipeline: prompt,
 * parser, merges, caps and collapse all come from
 * [QuickRead][com.example.aiagenttestapp.data.audit.QuickRead], which the audit queue runs too. A
 * quick voice note and a quick document audit reach their conclusion by one route, so "quick" means
 * one thing in this app rather than two things that resemble each other.
 *
 * Unlike the audit queue, the mode is *not* pinned onto stored work. An audit's chunk boundaries are
 * fixed at enqueue and checkpointed per chunk, so a later switch would reinterpret sections sized for
 * the other mode; a note is summarised in one foreground run from a transcript that is already
 * finished, so re-summarising simply re-chunks from scratch and the user may switch freely.
 *
 * The two are not the same job at different lengths:
 *
 *  - [DETAILED] is the note's own read: key points, every non-conformity and every action, uncapped.
 *    What a note produced before this choice existed, and still the complete inspection record.
 *  - [QUICK] is the shared quick read: the ONE conclusion the note reached, stated in the shared
 *    [AuditResultType][com.example.aiagenttestapp.data.audit.AuditResultType] vocabulary with its
 *    reason and what it rests on, plus at most
 *    [QuickAudit.MAX_ACTIONS][com.example.aiagenttestapp.data.audit.QuickAudit.MAX_ACTIONS] actions
 *    and the same number of unresolved items.
 *
 * A walkthrough covering several areas therefore comes back with one conclusion, not one per area —
 * the most severe, on the never-downgrade rule, exactly as a document does. That is the point of the
 * shared path rather than a limitation of it: [DETAILED] is where the per-area record lives.
 *
 * Spoken markers survive either way. Every non-conformity the speaker tagged out loud is re-inserted
 * by [withTaggedFloor] whatever the model returned, so choosing quick narrows what the *model*
 * reports and never what the *user* recorded.
 */
enum class NoteSummaryMode {

    /** The note's own read: key points, every non-conformity, every action. Uncapped. */
    DETAILED,

    /** The shared quick read: one conclusion, at most two actions, at most two unresolved items. */
    QUICK,
    ;

    val label: String
        get() = when (this) {
            DETAILED -> "In detail"
            QUICK -> "Quick summary"
        }

    /** One line for the picker, saying what the choice costs and buys. */
    val blurb: String
        get() = when (this) {
            DETAILED -> "Every non-conformity and action, in full. Slower."
            QUICK -> "One conclusion, its reason, and what to do. Much faster."
        }

    companion object {
        /** Reads a stored name back, defaulting to [DETAILED] -- what every note was before this. */
        fun from(name: String?): NoteSummaryMode =
            entries.firstOrNull { it.name == name } ?: DETAILED
    }
}
