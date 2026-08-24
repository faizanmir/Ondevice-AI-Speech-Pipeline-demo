package com.example.aiagenttestapp.data.audit

/**
 * What an audit concluded about one protocol element.
 *
 * Kotlin names in code, the iOS spec's spellings on the wire ([wireName]). The two apps have to
 * emit the same JSON so an audit means the same thing on either platform, but `resultOkForDocumentation`
 * reads wrong as a Kotlin constant and would be inherited forever if it leaked past the serializer.
 *
 * ## Four states, and none of them is a bare pass
 *
 * There is no `resultOK`. It existed here and was removed: the vocabulary records what an audit had
 * to SAY about an element, and "nothing to say" is not one of those things -- it is the absence of
 * them. An element that conformed outright therefore carries no result at all, which the report
 * shows by omitting the line rather than by asserting a pass.
 *
 * A stored `resultOK` from a report written before this decodes to null, on the same rule as any
 * other unrecognised value. Deliberate: mapping it onto one of the four would be re-grading a
 * finished artefact, and every one of the four says something stronger than the value it replaced.
 *
 * ## Absence is a verdict
 *
 * There is deliberately no `UNKNOWN` case. A conclusion that is not clear is represented by a
 * *null* result type, everywhere -- an audit assistant that guesses is worse than one that abstains,
 * because a false OK is read and believed while a blank is read and checked. Callers therefore
 * handle `AuditResultType?`, and [fromWire] answers null for an absent key, an explicit null, and a
 * value this build does not recognise alike.
 */
enum class AuditResultType(val wireName: String) {

    /** The activity itself is sound; its documentation, approval or traceability is weak. */
    OK_FOR_DOCUMENTATION("resultOkForDocumentation"),

    /** Requirement, evidence and gap are all present, or the auditor said minor. */
    MINOR_NONCONFORMITY("minorNonconformity"),

    /** As minor, but the auditor said major, or the gap is systemic. */
    MAJOR_NONCONFORMITY("majorNonconformity"),

    /** Worth improving, but not a finding against a requirement. */
    POTENTIAL_IMPROVEMENT("resultPotentialImprovement"),
    ;

    /** Whether this is a finding against a requirement, as opposed to a pass or a suggestion. */
    val isNonconformity: Boolean
        get() = this == MINOR_NONCONFORMITY || this == MAJOR_NONCONFORMITY

    /**
     * The conclusion written out, for a report line that has room for it ("Result: OK for
     * documentation").
     *
     * Not the same string as the badge on a finding, which is abbreviated to fit a tag, and kept
     * here rather than in either renderer so the screen and the PDF cannot word it differently.
     */
    val label: String
        get() = when (this) {
            OK_FOR_DOCUMENTATION -> "OK for documentation"
            MINOR_NONCONFORMITY -> "Minor non-conformity"
            MAJOR_NONCONFORMITY -> "Major non-conformity"
            POTENTIAL_IMPROVEMENT -> "Potential for improvement"
        }

    companion object {

        /**
         * The type for a wire value, or null when there is no clear conclusion.
         *
         * An unrecognised value is null rather than an error on purpose: it arrives from a model,
         * which will occasionally invent a case, and inventing a *verdict* in response would be the
         * one failure this whole scheme exists to prevent.
         */
        fun fromWire(value: String?): AuditResultType? {
            val trimmed = value?.trim()?.takeIf { it.isNotEmpty() && !it.equals("nil", true) }
                ?: return null
            return entries.firstOrNull { it.wireName.equals(trimmed, ignoreCase = true) }
        }

        /**
         * The grade a NON-CONFORMITY is allowed to carry: itself when it is one, null otherwise.
         *
         * A block that names a gap and grades it `resultPotentialImprovement` or
         * `resultOkForDocumentation` is telling both halves of a contradiction, and the report
         * renders the grade rather than the block, so the finding arrives badged as a pass. The
         * prompt forbids it; this is the same rule where it cannot be ignored.
         *
         * Cleared, never rewritten. Promoting it to a minor would be inventing the conclusion this
         * whole vocabulary exists to stop inventing -- the model said the finding is real and said
         * something incoherent about how serious it is, and only the second part is unusable. Same
         * trade [AuditEvidence] makes with a quote that will not verify: drop the claim, keep the
         * finding.
         */
        fun asNonconformity(result: AuditResultType?): AuditResultType? =
            result?.takeIf { it.isNonconformity }

        /**
         * Whether [text] is one of the wire names rather than a document's own wording.
         *
         * The verdict field holds what the document called its result, verbatim ("OK for
         * documentation"). A model that has just been shown five vocabulary names will sometimes put
         * one of them there instead, and the report then prints `Stated result:
         * "resultPotentialImprovement"` -- attributing to the document a word it never used, in a
         * field whose entire purpose is that it is the document's own.
         */
        fun isWireName(text: String): Boolean = fromWire(text) != null

        /**
         * The more severe of two conclusions about the same element -- never the milder one.
         *
         * Audits are read once and acted on; a finding that softens between the section it was
         * raised in and the report it lands in is a finding that quietly disappears. So severity
         * only ever moves one way, whether the second opinion comes from a later chunk discussing
         * the same item, a summarising pass, or a re-read.
         *
         * A null never wins: "no clear conclusion" cannot overwrite a conclusion that was reached.
         */
        fun neverDowngrade(
            current: AuditResultType?,
            proposed: AuditResultType?,
        ): AuditResultType? = when {
            current == null -> proposed
            proposed == null -> current
            else -> if (proposed.severity > current.severity) proposed else current
        }

        /**
         * Ranking used only by [neverDowngrade].
         *
         * Not the enum's own ordinal, so reordering the cases -- or adding one -- cannot silently
         * change which verdict survives a merge.
         */
        private val AuditResultType.severity: Int
            get() = when (this) {
                POTENTIAL_IMPROVEMENT -> 0
                OK_FOR_DOCUMENTATION -> 1
                MINOR_NONCONFORMITY -> 2
                MAJOR_NONCONFORMITY -> 3
            }
    }
}
