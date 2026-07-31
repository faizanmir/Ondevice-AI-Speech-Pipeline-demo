package com.example.aiagenttestapp.prompts.audit

import com.example.aiagent.engine.core.ContextWindow
import com.example.aiagenttestapp.data.audit.QuickAudit

/**
 * Quick mode: one pass over the document for the headline points, instead of the full extraction.
 *
 * A separate object rather than a flag on the detailed prompts, because almost nothing is shared --
 * different system prompt, different output shape, different budget. A `if (quick)` running through
 * the extraction prompts would make both harder to read and neither easier to change.
 */
object AuditQuickPrompts {

    /**
     * Quick MAP stage, run once per chunk: the section's key points and any actions it states.
     *
     * Same RECORDS shape detailed uses -- POINTS is read as facts, ACTION as an action -- so the one
     * existing [AuditRecordParser] reads both modes and there is no second parser to keep in step.
     */
    fun quickExtraction(part: String, partNumber: Int, totalParts: Int): String = buildString {
        append(quickPreamble())
        appendLine()
        if (totalParts > 1) {
            appendLine("----- BEGIN TEXT (section $partNumber of $totalParts) -----")
        } else {
            appendLine("----- BEGIN TEXT -----")
        }
        appendLine(part)
        appendLine("----- END TEXT -----")
    }


    /** Built once and reused, for the same prefix-reuse reason as the detailed preamble. */
    private val QUICK_PREAMBLE by lazy { buildQuickPreamble() }


    fun quickPreamble(): String = QUICK_PREAMBLE


    private fun buildQuickPreamble(): String = buildString {
        appendLine("You are reading one section of a document.")
        appendLine("Report only what appears in the section you are given. Other sections are read separately.")
        appendLine()
        appendLine("Give two things:")
        appendLine("- POINTS: the key content of this section -- what it covers, what was checked or")
        appendLine("  discussed, what happened, and what state things were in. Keep the specifics:")
        appendLine("  dates, numbers, names, equipment, decisions and any problems noted. Short lines,")
        appendLine("  specific not general. These are the raw material for a later overall summary,")
        appendLine("  so do not generalise them away.")
        appendLine("- ACTIONS: every step the text itself says will be taken, should be taken, or is")
        appendLine("  recommended -- something to be completed, corrected, signed, recorded, reviewed,")
        appendLine("  verified, checked, or followed up. Actions usually sit near the end, in what is")
        appendLine("  recommended and in what the other party commits to in reply. Both count, but a")
        appendLine("  recommendation and the reply accepting it are ONE action, not two.")
        appendLine()
        appendLine("Rules:")
        appendLine("- Use only what the text says. Never add, infer, or generalise beyond it.")
        appendLine("- Do not invent an action the text does not state. If it states none, write no")
        appendLine("  ACTION blocks at all -- that is a correct answer, not a failure to look.")
        appendLine("- Do not grade or judge anything. Report what is there.")
        appendLine()
        appendLine("Answer in this exact form, and nothing else:")
        appendLine()
        appendLine("RECORDS")
        appendLine("POINTS")
        appendLine("- a short point")
        appendLine("- a short point")
        appendLine()
        appendLine("ACTION")
        appendLine("title: a short title")
        appendLine()
        appendLine("Repeat the ACTION block for each action. One field per line. No brackets, no")
        appendLine("braces, no quotation marks around values, no commas between fields.")
        appendLine("Write nothing after the records. No code fences.")
        appendLine()

        // One worked example, carrying the two behaviours that actually go wrong here: an action
        // that lives in a closing recommend-and-accept exchange counting once rather than twice, and
        // points that keep their numbers instead of being smoothed into prose. Deliberately not a
        // second "clean text" example -- quick mode has no empty-list failure mode to counterweight,
        // since every section has points.
        appendLine("Worked example:")
        appendLine(
            "  \"Auditor: The torque wrench was calibrated on 12 May, but the certificate was never " +
                "signed off by the quality manager. The quality manager should sign it off, and you " +
                "should check the other certificates for the same gap. Customer: It will be signed " +
                "this week, and we will review the rest of the file.\"",
        )
        appendLine("a correct reply is:")
        appendLine("RECORDS")
        appendLine("POINTS")
        appendLine("- Torque wrench calibrated 12 May.")
        appendLine("- Calibration certificate not signed off by the quality manager.")
        appendLine()
        appendLine("ACTION")
        appendLine("title: Quality manager to sign off the certificate")
        appendLine()
        appendLine("ACTION")
        appendLine("title: Check the other calibration certificates for the same gap")
        appendLine()
        appendLine("Now read the text below the same way.")
    }


    /**
     * Quick REDUCE stage: condense every section's points into at most [QuickAudit.MAX_POINTS].
     *
     * Points only. The actions are unioned and deduplicated in code
     * ([AuditChunker.mergeFindings]), exactly as detailed does with its findings -- handing a small
     * model the whole action list and asking it to consolidate is where recall silently dies, and
     * there is no reason to take that risk for a list the code can merge exactly.
     *
     * [maxNoteChars] bounds the notes the same way [finalSummary] does and for the same reason: a
     * document may reach here with up to [AuditQueue.MAX_CHUNKS] sections of points, and an
     * over-long prompt loses its *start* to eviction rather than its tail.
     */
    fun quickSummary(
        pointsByPart: List<List<String>>,
        maxPoints: Int = QuickAudit.MAX_POINTS,
        maxNoteChars: Int = Int.MAX_VALUE,
    ): String = buildString {
        appendLine("Below are notes taken from each section of one document, in order.")
        appendLine()
        appendLine("Write the overall summary of the document as at most $maxPoints bullet points.")
        appendLine()
        appendLine("Rules:")
        appendLine("- At most $maxPoints points. Fewer is fine if the document does not support more.")
        appendLine("- Cover the document as a whole, start to end -- not just its opening sections.")
        appendLine("- Merge notes that repeat the same thing into one point.")
        appendLine("- Keep the specifics: dates, numbers, names and equipment stay in.")
        appendLine("- Use only the notes below. Every point must trace back to a note. Do not add")
        appendLine("  context, do not speculate, do not fill gaps.")
        appendLine("- One line per point, each starting with \"- \". No headings, no numbering, no")
        appendLine("  JSON, no code fences, and no text before or after the points.")
        appendLine()
        appendLine("----- BEGIN NOTES -----")
        AuditSummaryPrompts.trimNotes(pointsByPart, maxNoteChars).forEachIndexed { index, points ->
            append(AuditSummaryPrompts.sectionHeader(index))
            points.forEach { appendLine("- $it") }
        }
        appendLine("----- END NOTES -----")
    }


    /**
     * Characters of notes [quickSummary] may carry, mirroring [summaryNoteBudget]. Its own function
     * because the two prompts are different sizes and sharing one number would silently over- or
     * under-feed whichever mode did not own it.
     */
    fun quickSummaryNoteBudget(contextTokens: Int): Int {
        val free = contextTokens -
            ContextWindow.estimateTokens(AuditSystemPrompts.QUICK_SYSTEM_PROMPT) -
            ContextWindow.estimateTokens(quickSummary(emptyList())) -
            QUICK_SUMMARY_OUTPUT_RESERVE_TOKENS
        return ContextWindow.estimateChars(free.coerceAtLeast(AuditSummaryPrompts.MIN_SUMMARY_NOTE_TOKENS))
    }


    /**
     * Tokens held back for the quick summary, and the cap its turn is stopped at.
     *
     * Smaller than [SUMMARY_OUTPUT_RESERVE_TOKENS] because the output is bounded by design -- ten
     * bullet points, not open-ended prose -- but not tiny, since a reasoning model spends part of
     * the allowance thinking before the first bullet appears.
     */
    const val QUICK_SUMMARY_OUTPUT_RESERVE_TOKENS = 1024
}
