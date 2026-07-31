package com.example.aiagenttestapp.prompts.audit

/**
 * What the model is told it *is* for an audit, once per load.
 *
 * Separate from the task prompts because it has a different lifetime: this is handed to the engine
 * when the model is loaded and lives for the whole session, while everything in
 * [AuditExtractionPrompts] and [AuditSummaryPrompts] is built per chunk and thrown away.
 */
object AuditSystemPrompts {

    /** The system prompt the audit session loads the model with: tools off, transcript-grounded. */
    const val SYSTEM_PROMPT =
        "You are an audit assistant. You read audit transcripts and report strictly from their " +
            "contents -- identifying non-conformities and the corrective actions required. Be " +
            "precise and concise, and never invent details that are not in the transcript."


    // ---- Quick mode ------------------------------------------------------------------------------
    //
    // A different job, not a shorter version of the same one. Detailed extraction has to DECIDE what
    // counts as a non-conformity and defend it with a verified quote and a grade; quick only has to
    // report what the section says and what it says will be done. Everything that made detailed
    // expensive is therefore absent here rather than merely trimmed:
    //
    //   - no plain-text draft, which doubles the output tokens of every section
    //   - no evidence quotes, which are the largest field in a detailed finding
    //   - no severity pass at all, which on a long document is a whole extra sweep of the findings
    //   - no worked examples of what a non-conformity is, which is most of the detailed preamble
    //
    // The chunking is unchanged. Quick reads the whole document section by section exactly as
    // detailed does; only what it asks of each section, and what it does afterwards, is different.

    /** The system prompt a quick run loads: reporting, not judging. */
    const val QUICK_SYSTEM_PROMPT =
        "You summarise documents and transcripts. You report strictly from their contents, keeping " +
            "the specifics, and you never invent detail that is not there."
}
