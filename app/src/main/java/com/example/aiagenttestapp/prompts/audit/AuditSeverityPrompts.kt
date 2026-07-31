package com.example.aiagenttestapp.prompts.audit

import com.example.aiagenttestapp.data.audit.AuditFinding
import com.example.aiagenttestapp.prompts.ReasoningPrompts

/**
 * The prompts that ask the model to grade a finding major / minor / observation.
 *
 * Three of them, because grading is where the cost/quality trade is sharpest: one finding at a time
 * with reasoning, a batch, or a single fast pass. Which one runs is the caller's choice.
 */
object AuditSeverityPrompts {

    /**
     * The second pass: grade one already-found non-conformity as major / minor / observation. Split
     * out of [extraction] on purpose -- grading one finding in isolation is a tiny, unambiguous ask a
     * small model answers well, where folding it into the find-everything step measurably hurt recall.
     * The reply is one word, read back through [AuditSeverity.normalise].
     */
    /**
     * Grades a whole batch of non-conformities in one call, which is what makes the severity pass
     * affordable: one call costs a single conversation rebuild and one system-prompt prefill, where
     * grading one at a time paid both per finding for a one-word answer.
     *
     * The reply is read back by index, and anything the model skips or garbles is re-graded
     * individually with [gradeSeverity] -- so batching buys the speed without betting correctness on
     * a small model keeping a numbered list aligned.
     */
    fun gradeSeverityBatch(findings: List<AuditFinding>): String = buildString {
        appendLine("Classify how serious each audit non-conformity below is. For each one choose:")
        appendLine("- major: a requirement is not met in a way that breaks or defeats the process")
        appendLine("- minor: an isolated lapse or partial gap that does not break the whole process")
        appendLine("- observation: a concern or improvement opportunity, not yet an actual breach")
        appendLine()
        appendLine("Reply with one line per item, numbered exactly as below, in the form:")
        appendLine("1: minor")
        appendLine("2: major")
        appendLine("Give every number a grade. One word per line, no other text.")
        appendLine()
        findings.forEachIndexed { index, finding ->
            append("${index + 1}. ")
            appendLine(finding.title)
            if (finding.evidence.isNotBlank()) appendLine("   quote: ${finding.evidence}")
        }
        appendLine()
        append(NO_THINKING)
    }


    /**
     * One finding, one word, no reasoning. The per-finding companion to [gradeSeverityBatch], used
     * for whatever the batch skipped or garbled.
     *
     * [gradeSeverity] asks the same question and lets the model reason first; this does not, which is
     * the whole point -- reasoning is what made grading cost hundreds of decode tokens to produce one
     * word out of three. That one is kept as the last resort for findings neither fast pass settles.
     */
    fun gradeSeverityFast(finding: AuditFinding): String = buildString {
        appendLine("Classify how serious this audit non-conformity is. Choose exactly one:")
        appendLine("- major: a requirement is not met in a way that breaks or defeats the process")
        appendLine("- minor: an isolated lapse or partial gap that does not break the whole process")
        appendLine("- observation: a concern or improvement opportunity, not yet an actual breach")
        appendLine()
        append("Non-conformity: ")
        appendLine(finding.title)
        if (finding.evidence.isNotBlank()) appendLine("Quote: ${finding.evidence}")
        appendLine()
        appendLine("Reply with exactly one word: major, minor, or observation. No other text.")
        appendLine()
        append(NO_THINKING)
    }


    /**
     * Thinking off, for the grading prompts only.
     *
     * It rides on the prompt rather than the system prompt because that is the only place it can be
     * stage-specific: one system prompt is loaded for the whole run, and extraction needs its
     * reasoning -- taking that away cost findings outright when it was tried. Grading is a different
     * task, picking one of three words about a finding that has already been found, and a `<think>`
     * block there is pure cost. It also has to go: a reasoning model writes its thinking *before* the
     * answer, so an eight-token cap with thinking left on would truncate the thinking and never reach
     * the word.
     */
    private val NO_THINKING = ReasoningPrompts.NO_THINK_DIRECTIVE


    fun gradeSeverity(finding: AuditFinding): String = buildString {
        appendLine("Classify how serious this audit non-conformity is. Choose exactly one:")
        appendLine("- major: a requirement is not met in a way that breaks or defeats the process")
        appendLine("- minor: an isolated lapse or partial gap that does not break the whole process")
        appendLine("- observation: a concern or improvement opportunity, not yet an actual breach")
        appendLine()
        append("Non-conformity: ")
        appendLine(finding.title)
        if (finding.detail.isNotBlank()) {
            append("Details: ")
            appendLine(finding.detail)
        }
        appendLine()
        // Reasoning before the verdict, not after: a grade emitted as the very first token has to be
        // decided with no working room. One sentence is enough, and the trailing word is what counts
        // -- AuditSeverity.normalise reads the last grade mentioned, so the conclusion wins.
        appendLine("Give one short sentence of reasoning, then end your reply with the grade alone on")
        appendLine("the final line: exactly one of major, minor, or observation.")
    }
}
