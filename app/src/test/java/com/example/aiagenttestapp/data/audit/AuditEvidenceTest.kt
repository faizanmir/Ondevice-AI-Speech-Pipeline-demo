package com.example.aiagenttestapp.data.audit

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The evidence check is the one place a model's claim is verified rather than trusted. The prompt
 * asks for a word-for-word quote; a model can ignore that, so these pin what counts as quoting --
 * reformatting is forgiven, invention is not.
 */
class AuditEvidenceTest {

    private val source = """
        The extinguisher was last inspected 14 months ago; the annual check was missed.
        Two new staff had not completed the induction training.
    """.trimIndent()

    @Test
    fun `keeps a quote that appears verbatim`() {
        val result = AuditEvidence.verify(
            listOf(AuditFinding("Overdue", evidence = "the annual check was missed")),
            source,
        )

        assertEquals(0, result.rejected)
        assertEquals("the annual check was missed", result.findings.single().evidence)
    }

    @Test
    fun `forgives case, whitespace and wrapping quote marks`() {
        val result = AuditEvidence.verify(
            listOf(AuditFinding("Overdue", evidence = "\"The Annual   Check\nwas Missed\"")),
            source,
        )

        assertEquals(0, result.rejected)
        assertEquals("\"The Annual   Check\nwas Missed\"", result.findings.single().evidence)
    }

    @Test
    fun `clears an invented quote but keeps the finding`() {
        val result = AuditEvidence.verify(
            listOf(AuditFinding("Overdue", "d", evidence = "the extinguisher was condemned")),
            source,
        )

        assertEquals(1, result.rejected)
        val finding = result.findings.single()
        assertEquals("Overdue", finding.title) // the finding itself survives
        assertEquals("d", finding.detail)
        assertEquals("", finding.evidence)
    }

    @Test
    fun `a finding with no quote is left alone and not counted as rejected`() {
        val result = AuditEvidence.verify(listOf(AuditFinding("Overdue")), source)

        assertEquals(0, result.rejected)
        assertEquals("", result.findings.single().evidence)
    }

    @Test
    fun `keeps a standard whose numbers the source names, forgiving an added year`() {
        val text = "The gauge failed the check required by ISO 9001 clause 7.1.5."
        val result = AuditEvidence.verify(
            // ":2015" is not in the text; expanding a recognised name is forgiven, so this stays.
            listOf(AuditFinding("Gauge check failed", standards = listOf("ISO 9001:2015 clause 7.1.5"))),
            text,
        )

        assertEquals(0, result.rejectedStandards)
        assertEquals(listOf("ISO 9001:2015 clause 7.1.5"), result.findings.single().standards)
    }

    @Test
    fun `drops a standard the source never names`() {
        // The worked examples cite ISO 45001 §7.2; a model echoing that into a document that never
        // mentions it must lose the citation here, in code.
        val result = AuditEvidence.verify(
            listOf(AuditFinding("Overdue", standards = listOf("ISO 45001 clause 7.2"))),
            source,
        )

        assertEquals(1, result.rejectedStandards)
        assertEquals(emptyList<String>(), result.findings.single().standards)
    }

    @Test
    fun `a citation with no numbers falls back to a substring check`() {
        val text = "Processing here is covered by the GDPR."

        val kept = AuditEvidence.verify(listOf(AuditFinding("Gap", standards = listOf("GDPR"))), text)
        assertEquals(0, kept.rejectedStandards)

        val dropped = AuditEvidence.verify(listOf(AuditFinding("Gap", standards = listOf("HIPAA"))), text)
        assertEquals(1, dropped.rejectedStandards)
        assertEquals(emptyList<String>(), dropped.findings.single().standards)
    }
}
