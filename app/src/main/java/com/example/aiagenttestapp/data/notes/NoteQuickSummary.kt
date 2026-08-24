package com.example.aiagenttestapp.data.notes

import com.example.aiagenttestapp.data.audit.AuditResultType
import com.example.aiagenttestapp.data.audit.QuickRead
import com.example.aiagenttestapp.functions.MarkerKind

/**
 * Renders a [QuickRead.Result] as a note.
 *
 * The only note-specific code on the quick path. Everything before it -- the prompt, the parser, the
 * merges, the caps, the collapse -- is [QuickRead], shared byte for byte with the audit queue, so a
 * quick voice note and a quick document audit reach their conclusion by exactly the same route. This
 * translates that one result into the two things a note is made of: prose the user reads, and
 * [ParsedFinding]s the note list can count.
 *
 * A translation is needed at all because the two features store different things. An audit keeps a
 * whole [com.example.aiagenttestapp.data.audit.AuditAnalysis] in `audit.db`; a note keeps a summary
 * string and rows of findings keyed by [MarkerKind]. Nothing is lost in the crossing except fields
 * a note has nowhere to put -- see below.
 */
fun QuickRead.Result.toNoteAnalysis(): NoteAnalysis {
    val lines = mutableListOf<String>()

    element?.let { e ->
        val statement = e.statement.trim()
        if (statement.isNotEmpty()) {
            // The result leads the line when there is one. It is the whole point of a quick read, and
            // burying it at the end of a sentence is how a major non-conformity gets skimmed past.
            lines += e.result?.let { "- ${it.label}: $statement" } ?: "- $statement"
        }
        e.reason.trim().takeIf { it.isNotEmpty() }?.let { lines += "- Why: $it" }
        e.evidence.trim().takeIf { it.isNotEmpty() }?.let { lines += "- Based on: $it" }
    }

    // Unresolved items go in the prose rather than into findings. A note's findings are
    // non-conformities and actions, and an unresolved item is neither -- it is a gap nobody has
    // committed to closing. Filing it as an action would invent a commitment that was never made.
    unresolved.forEach { item ->
        item.trim().takeIf { it.isNotEmpty() }?.let { lines += "- Left open: $it" }
    }

    val findings = mutableListOf<ParsedFinding>()

    // The conclusion becomes a non-conformity finding only when it actually is one. The shared
    // vocabulary has four values and two of them are not failures, so reading every conclusion as a
    // finding would file "OK for documentation" as a defect.
    if (element?.result?.isNonConformity == true) {
        val text = listOf(element.statement.trim(), element.reason.trim())
            .filter { it.isNotEmpty() }
            .joinToString(" — ")
        if (text.isNotEmpty()) {
            findings += ParsedFinding(kind = MarkerKind.NonConformity, text = text)
        }
    }

    actions.forEach { action ->
        val text = listOf(action.title.trim(), action.detail.trim())
            .filter { it.isNotEmpty() }
            .joinToString(" — ")
        if (text.isNotEmpty()) {
            // No owner: the RECORDS shape carries none for an action, and a note that invented one
            // would be assigning work to somebody who never accepted it. The user can add it while
            // reviewing, which is where an owner should come from anyway.
            findings += ParsedFinding(kind = MarkerKind.Action, text = text)
        }
    }

    return NoteAnalysis(summary = lines.joinToString("\n"), findings = findings)
}

/** Whether a conclusion in the shared vocabulary is a failure, as opposed to a clean or soft result. */
private val AuditResultType.isNonConformity: Boolean
    get() = this == AuditResultType.MAJOR_NONCONFORMITY || this == AuditResultType.MINOR_NONCONFORMITY
