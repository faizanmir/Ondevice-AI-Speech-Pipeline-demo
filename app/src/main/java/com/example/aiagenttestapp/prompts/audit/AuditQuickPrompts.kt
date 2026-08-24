package com.example.aiagenttestapp.prompts.audit

import com.example.aiagenttestapp.data.audit.QuickAudit

/**
 * Quick mode: one pass over the document for the conclusion it reached, instead of the full audit.
 *
 * A separate object rather than a flag on the detailed prompts, because almost nothing is shared --
 * different system prompt, different output shape, different budget. A `if (quick)` running through
 * the extraction prompts would make both harder to read and neither easier to change.
 *
 * ## One stage, not two
 *
 * There is no quick REDUCE prompt here, and that is the whole difference from what this used to be.
 * Quick mode once gathered key points per section and spent a second turn condensing them into a
 * ten-point summary; it now answers one question per section -- what was evaluated, what was
 * concluded, why, on what evidence -- and the per-section answers are merged and collapsed in code
 * ([AuditChunker.mergeElements] and [AuditChunker.collapseElements]), exactly as detailed's are.
 *
 * That removes a whole model turn from every quick run, and removes with it the failure that turn
 * owned: a summarising pass reads the notes with no memory of the document and can only ever
 * generalise them, which on a compliance artefact is the direction that loses information.
 */
object AuditQuickPrompts {

    /**
     * Quick MAP stage, run once per chunk: the section's evaluation, its actions and what it left
     * open.
     *
     * Same RECORDS shape detailed uses -- ELEMENT, ACTION and UNRESOLVED are read by the one
     * existing [AuditRecordParser], and constrained by the one existing [AuditRecordGrammar] -- so
     * there is no second parser and no second grammar to keep in step.
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
        appendLine("Give three things:")
        // The element is asked for even where the section is clean, on the same reasoning detailed
        // uses: the conclusion is the part that says the document was evaluated at all, and reading
        // only for problems keeps the failures while throwing away what makes them defensible.
        appendLine("- ELEMENT: what this section evaluates, and the ONE conclusion it reaches about")
        appendLine("  it -- whether or not that conclusion is a failure. Give the statement it")
        appendLine("  reached, the result, why it was reached, and what it rests on. One element for")
        appendLine("  this section, never several.")
        appendLine("- ACTIONS: at most ${QuickAudit.MAX_ACTIONS}. Every step the text itself says will be taken, should")
        appendLine("  be taken, or is recommended -- something to be completed, corrected, signed,")
        appendLine("  recorded, reviewed, verified, checked, or followed up. Actions usually sit near")
        appendLine("  the end, in what is recommended and in what the other party commits to in")
        appendLine("  reply. Both count, but a recommendation and the reply accepting it are ONE")
        appendLine("  action, not two.")
        // Distinct from an action, and the distinction is the whole reason the section exists: an
        // action is a step somebody committed to, an unresolved item is a gap nobody has.
        appendLine("- UNRESOLVED: at most ${QuickAudit.MAX_UNRESOLVED}. What the section left open -- a record still missing,")
        appendLine("  an approval never produced, a question raised and not answered. Not the same as")
        appendLine("  an action, which is a step someone committed to.")
        appendLine()

        // The shared vocabulary, spelled from the enum so this cannot teach a name the parser will
        // not read back. Quick mode states a conclusion now, which is the substantive change here:
        // it used to report content and refuse to judge, and a report with no result was the one
        // thing the shared vocabulary existed to prevent.
        appendLine("The result is one of ${AuditExtractionPrompts.RESULT_TYPES_LINE}.")
        appendLine("There is no name for a plain pass -- a section that simply conformed carries no")
        appendLine("result line at all. Omit the line if the text supports no clear conclusion: that")
        appendLine("is an answer, not a gap to fill. Use resultOkForDocumentation when the work was")
        appendLine("sound but its records, approval or traceability were weak. If the document")
        appendLine("stated a result itself, it stands.")
        appendLine()
        appendLine("Rules:")
        appendLine("- Use only what the text says. Never add, infer, or generalise beyond it.")
        appendLine("- Do not invent an action or an unresolved item the text does not state. If it")
        appendLine("  states none, write no ACTION or UNRESOLVED block at all -- that is a correct")
        appendLine("  answer, not a failure to look.")
        // Deliberately NOT a quotation, unlike a detailed finding's. An element's evidence is a
        // summary of what was produced ("the procedure, the risk register"), and AuditEvidence never
        // checks it -- asking for a verbatim quote here would promise a check nothing performs.
        appendLine("- evidence is what the conclusion rests on, said briefly in your own words -- the")
        appendLine("  records, certificates or checks produced. It is not a quotation.")
        appendLine()
        appendLine("Answer in this exact form, and nothing else:")
        appendLine()
        appendLine("RECORDS")
        appendLine("ELEMENT")
        appendLine("statement: what this section evaluated and concluded")
        appendLine("result: one of the names above -- omit this line if the text supports none")
        appendLine("reason: why that conclusion, in one short sentence")
        appendLine("evidence: what the conclusion rests on")
        appendLine()
        appendLine("ACTION")
        appendLine("title: a short title")
        appendLine()
        appendLine("UNRESOLVED")
        appendLine("- a short line")
        appendLine()
        appendLine("Repeat the ACTION block for each action, ${QuickAudit.MAX_ACTIONS} at most. One field per line. No")
        appendLine("brackets, no braces, no quotation marks around values, no commas between fields.")
        appendLine("Write nothing after the records. No code fences.")
        appendLine()

        // One worked example, carrying the behaviours that actually go wrong here: an element that
        // concludes resultOkForDocumentation rather than a non-conformity when the work was done and
        // only its paperwork was not, evidence written as a summary rather than as a quote, and an
        // unresolved item that is NOT simply a restatement of the action above it. Deliberately not
        // a second "clean text" example -- the empty-list failure mode is covered by the rule above,
        // and every token here is a token of transcript the section cannot carry.
        appendLine("Worked example:")
        appendLine(
            "  \"Auditor: The torque wrench was calibrated on 12 May, but the certificate was never " +
                "signed off by the quality manager. The quality manager should sign it off, and you " +
                "should check the other certificates for the same gap. Customer: It will be signed " +
                "this week, and we will review the rest of the file.\"",
        )
        appendLine("a correct reply is:")
        appendLine("RECORDS")
        appendLine("ELEMENT")
        appendLine("statement: Torque wrench calibration is carried out but its certificate is not signed off.")
        appendLine("result: resultOkForDocumentation")
        appendLine("reason: The calibration itself was performed on 12 May; only the sign-off is missing.")
        appendLine("evidence: Calibration dated 12 May, and the unsigned calibration certificate.")
        appendLine()
        appendLine("ACTION")
        appendLine("title: Quality manager to sign off the certificate")
        appendLine()
        appendLine("ACTION")
        appendLine("title: Check the other calibration certificates for the same gap")
        appendLine()
        appendLine("UNRESOLVED")
        appendLine("- The calibration certificate is still unsigned")
        appendLine()
        appendLine("Now read the text below the same way.")
    }
}
