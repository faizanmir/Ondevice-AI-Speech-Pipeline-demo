package com.example.aiagenttestapp.data.audit

/**
 * What an audit concluded about one protocol element.
 *
 * Kotlin names in code, the iOS spec's spellings on the wire ([wireName]). The two apps have to
 * emit the same JSON so an audit means the same thing on either platform, but `resultOK` reads
 * wrong as a Kotlin constant and would be inherited forever if it leaked past the serializer.
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

    /** Clear pass. */
    OK("resultOK"),

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
                OK -> 0
                POTENTIAL_IMPROVEMENT -> 1
                OK_FOR_DOCUMENTATION -> 2
                MINOR_NONCONFORMITY -> 3
                MAJOR_NONCONFORMITY -> 4
            }
    }
}
