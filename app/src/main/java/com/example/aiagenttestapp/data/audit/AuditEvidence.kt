package com.example.aiagenttestapp.data.audit

/**
 * Checks that a finding's quote really occurs in the text it was taken from, and that every
 * standard it cites is actually named there.
 *
 * The extraction prompt requires every non-conformity to carry a word-for-word quote and forbids
 * inventing a standard. Those are instructions, and a model can ignore an instruction -- but it
 * cannot fake a substring match. So both claims are verified here in plain code rather than
 * trusted: no model involved, no cost, and no way to argue with the result.
 *
 * A quote or citation that does not check out is cleared rather than the finding being discarded.
 * The finding may well be real and merely paraphrased, and under-reporting is the failure mode this
 * pipeline is already fighting -- so the conservative move is to drop the unverifiable claim, not
 * the finding. ([Result.rejected] and [Result.rejectedStandards] are logged, so the rate is visible
 * before that trade is revisited.)
 */
object AuditEvidence {

    data class Result(
        val findings: List<AuditFinding>,
        /** How many quotes were offered but could not be found in the source. */
        val rejected: Int,
        /** How many cited standards were dropped because the source never names them. */
        val rejectedStandards: Int = 0,
    )

    fun verify(findings: List<AuditFinding>, sourceText: String): Result {
        if (findings.isEmpty()) return Result(findings, 0)

        val haystack = normalise(sourceText)
        var rejected = 0
        var rejectedStandards = 0
        val checked = findings.map { finding ->
            val quote = normalise(finding.evidence)
            val quoteOk = quote.isEmpty() || haystack.contains(quote)
            if (!quoteOk) rejected++
            val standards = finding.standards.filter { isNamedIn(it, haystack) }
            rejectedStandards += finding.standards.size - standards.size
            when {
                quoteOk && standards.size == finding.standards.size -> finding
                else -> finding.copy(
                    evidence = if (quoteOk) finding.evidence else "",
                    standards = standards,
                )
            }
        }
        return Result(checked, rejected, rejectedStandards)
    }

    /**
     * A cited standard must be named by the source, and the check keys on its numbers: every
     * digit-bearing token of the citation ("9001", "7.2") must appear in the text. Year tokens
     * (":2015", ":2022") are exempt -- a model expanding "ISO 9001" to "ISO 9001:2015" is
     * normalising a name it recognised, not inventing a requirement -- but a clause number the text
     * never states is an invention, and a worked example's clause echoed into the wrong document
     * dies here too. A citation with no numbers at all falls back to a plain substring check.
     */
    private fun isNamedIn(standard: String, haystack: String): Boolean {
        val numbers = NUMBER.findAll(standard.lowercase())
            .map { it.value }
            .filterNot { YEAR.matches(it) }
            .toList()
        if (numbers.isEmpty()) return haystack.contains(normalise(standard))
        return numbers.all { haystack.contains(it) }
    }

    private val NUMBER = Regex("""\d+(?:\.\d+)*""")
    private val YEAR = Regex("""(19|20)\d\d""")

    /**
     * Case and whitespace are normalised away -- a model reflowing a line across a newline, or
     * changing capitalisation at a sentence start, is still quoting. Wrapping quote marks it added
     * itself are stripped. Anything beyond that (a swapped word, an invented clause) fails, which is
     * the point.
     */
    private fun normalise(text: String): String =
        text.trim()
            .trim('"', '\'', '“', '”', '‘', '’')
            .lowercase()
            .replace(WHITESPACE, " ")
            .trim()

    private val WHITESPACE = Regex("\\s+")
}
