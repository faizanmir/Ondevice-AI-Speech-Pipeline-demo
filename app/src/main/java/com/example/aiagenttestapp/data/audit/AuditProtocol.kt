package com.example.aiagenttestapp.data.audit

/**
 * The protocol shape of an audited document: what each part of it examined, what was concluded, what
 * was merely stated, and what was left open.
 *
 * A transcript is not a flat list of problems. It follows a protocol -- an element is examined, the
 * parties say what they have, an auditor concludes, actions are agreed -- and a report that only
 * lists non-conformities throws away the structure that makes the conclusion defensible. These types
 * carry that structure alongside the findings, which are unchanged.
 *
 * ## The spellings here are derived from a screenshot, not from the iOS spec
 *
 * [AuditResultType] came from the spec and is authoritative. Everything in [AuditProtocolVocabulary]
 * below did not: the element types, action statuses and priorities were read off one rendered report
 * and their sibling cases are inferred. They are gathered in one object so correcting them against
 * the real spec is a single edit in a single file.
 *
 * Because they are guesses, nothing here is a closed enum. An unrecognised value is *kept as the
 * document wrote it* rather than dropped -- the opposite of [AuditResultType.fromWire], and
 * deliberately so. A wrong guess in a closed vocabulary deletes real content silently; a wrong guess
 * here shows up as an unfamiliar word on screen, which is a bug you can see.
 */
data class AuditProtocolElement(
    /** What this element of the protocol concluded or asserted, in one sentence. */
    val statement: String,
    /** What kind of element it is -- "Result", "Statement", ... See [AuditProtocolVocabulary]. */
    val type: String = "",
    /** Who it belongs to: "Auditor", "The organization". Blank when the document does not say. */
    val speaker: String = "",
    /**
     * The conclusion in the shared vocabulary, when the element carries one. Null is a verdict --
     * see [AuditResultType] -- so an element that concluded nothing shows no result rather than a
     * guessed one.
     */
    val result: AuditResultType? = null,
    /** Why the element concluded what it did, in the document's own terms. */
    val reason: String = "",
    /**
     * What the conclusion rests on.
     *
     * NOT a verbatim quote, unlike [AuditFinding.evidence], and deliberately never passed through
     * [AuditEvidence]: a protocol element's evidence is a summary of what was produced ("the
     * procedure, risk register, treatment plan, ..."), which no quote check could ever match. Running
     * it through one would silently blank every element in the report.
     */
    val evidence: String = "",
    /** Standards or clauses the document cites for this element. */
    val standards: List<String> = emptyList(),
)

/**
 * Something a party said that is not itself a finding or a conclusion -- the "Also stated" of a
 * report.
 *
 * Kept because an audit is a record of what was claimed as well as what was concluded: "we maintain
 * a centralized risk register" is not a finding, but it is what the organisation asserted, and a
 * reader deciding whether to trust the conclusion needs it.
 */
data class AuditStatement(
    val speaker: String,
    val text: String,
) {
    companion object {

        /**
         * "Customer: we calibrate annually" split into who and what, from the one line a model
         * writes whichever format it was asked for. Both parsers call this rather than carrying the
         * rule twice.
         *
         * The rule has to be tighter than "text before the first colon", because statements contain
         * colons: "The requirement we work to is ISO 9001:2015 clause 7.1.5" would otherwise be
         * attributed to a speaker called "The requirement we work to is ISO 9001". A label is a
         * NAME -- short, and a few words at most -- so length alone is not enough to tell them
         * apart, and a claim wrongly split loses its opening words entirely.
         *
         * When in doubt the whole line is the claim, attributed to nobody. Losing the attribution
         * costs a label; losing the split costs the sentence.
         */
        fun of(line: String): AuditStatement? {
            val trimmed = line.trim().removePrefix("-").trim().ifBlank { return null }
            val at = trimmed.indexOf(':').takeIf { it in 1..SPEAKER_MAX_CHARS }
                ?: return AuditStatement("", trimmed)
            val candidate = trimmed.take(at).trim()
            val looksLikeName = candidate.split(WHITESPACE).size <= SPEAKER_MAX_WORDS &&
                // A label with digits in it is "Speaker 2"; one with a decimal is a clause number.
                !candidate.contains('.')
            return if (looksLikeName) {
                AuditStatement(candidate, trimmed.substring(at + 1).trim())
            } else {
                AuditStatement("", trimmed)
            }
        }

        private const val SPEAKER_MAX_CHARS = 40
        private const val SPEAKER_MAX_WORDS = 4
        private val WHITESPACE = Regex("\\s+")
    }
}

/**
 * The one place a spelling guessed from the screenshot appears.
 *
 * Correct against the iOS spec here and nowhere else. Each list is the set this build knows how to
 * spell consistently; a value outside it is preserved verbatim by [canonical], never discarded.
 */
object AuditProtocolVocabulary {

    /**
     * The most "also stated" points a report carries.
     *
     * The section is a handful of things the summary would otherwise miss, not a transcript of the
     * conversation -- a document that put thirty lines here would bury the two that mattered. Asked
     * for in the prompt AND enforced after the merge, on the same reasoning as
     * [QuickAudit.MAX_ACTIONS]: a cap that only lives in a prompt is a request, not a limit.
     */
    const val MAX_ALSO_STATED = 5

    /**
     * Element kinds. "Result" is the only one actually observed; the rest are inferred from what an
     * audit protocol contains, and are here so the same concept is spelled one way across a report.
     */
    val ELEMENT_TYPES = listOf("Result", "Statement", "Question", "Observation")

    /** Where an action stands. "Proposed" is observed; the rest are inferred. */
    val ACTION_STATUSES = listOf("Proposed", "Agreed", "Open", "Completed")

    /** "Medium" is observed; the other two are the obvious siblings. */
    val ACTION_PRIORITIES = listOf("Low", "Medium", "High")

    /**
     * [value] in this build's spelling if it recognises it, otherwise [value] trimmed and unchanged.
     *
     * Case- and punctuation-tolerant, because the value comes from a model: "MEDIUM", "medium." and
     * "Medium" are one priority, and a report that showed all three would look like three.
     */
    fun canonical(value: String?, known: List<String>): String {
        val trimmed = value?.trim()?.trim('.', ',', ';')?.trim().orEmpty()
        if (trimmed.isEmpty()) return ""
        return known.firstOrNull { it.equals(trimmed, ignoreCase = true) } ?: trimmed
    }

    /**
     * A yes/no field as a Boolean, or null when the document did not say.
     *
     * Null is not "no": an action nobody accepted and an action whose acceptance was never recorded
     * are different facts, and only one of them is a gap.
     */
    fun acceptance(value: String?): Boolean? = when (value?.trim()?.lowercase()?.trim('.', ',')) {
        null, "" -> null
        "yes", "true", "accepted", "y" -> true
        "no", "false", "rejected", "n" -> false
        else -> null
    }
}
