package com.example.aiagenttestapp.data.audit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuditRecordParserTest {

    @Test
    fun `reads facts, verdict, non-conformities and actions`() {
        val reply = """
            FINDINGS
            - certificate not signed off
            ACTIONS
            - quality manager to sign it off
            RECORDS
            FACTS
            - Torque wrench calibrated 12 May.

            VERDICT
            OK for documentation

            NONCONFORMITY
            title: Calibration certificate not signed off
            quote: the certificate was never signed off by the quality manager
            standard: ISO 9001:2015 clause 7.1.5

            ACTION
            title: Quality manager to sign off the certificate
        """.trimIndent()

        val a = AuditRecordParser.parse(reply)

        assertEquals(listOf("Torque wrench calibrated 12 May."), a.facts)
        assertEquals("OK for documentation", a.verdict)
        assertEquals(1, a.nonConformities.size)
        assertEquals("Calibration certificate not signed off", a.nonConformities.single().title)
        assertEquals(
            "the certificate was never signed off by the quality manager",
            a.nonConformities.single().evidence,
        )
        assertEquals(listOf("ISO 9001:2015 clause 7.1.5"), a.nonConformities.single().standards)
        assertEquals(1, a.actions.size)
    }

    @Test
    fun `the plain-text draft above RECORDS is not read as content`() {
        // The draft restates every finding. Read as content it would double every count.
        val reply = """
            FINDINGS
            - extinguisher overdue
            - training incomplete
            ACTIONS
            - none stated
            RECORDS
            NONCONFORMITY
            title: Fire extinguisher inspection overdue
            quote: last inspected 14 months ago
        """.trimIndent()

        val a = AuditRecordParser.parse(reply)

        assertEquals(1, a.nonConformities.size)
        assertEquals(0, a.actions.size)
    }

    @Test
    fun `a reply cut off mid-record keeps every record before the cut`() {
        // The property the whole format change exists for. Under JSON this reply parsed to nothing
        // at all -- every finding in it thrown away because the document never closed.
        val reply = """
            RECORDS
            NONCONFORMITY
            title: First finding
            quote: first quote

            NONCONFORMITY
            title: Second finding
            quote: second quote

            NONCONFORMITY
            title: Third finding cut off here
            quote: the model stopped mid
        """.trimIndent()

        val a = AuditRecordParser.parse(reply)

        assertEquals(3, a.nonConformities.size)
        assertEquals("Third finding cut off here", a.nonConformities.last().title)
    }

    @Test
    fun `a clause on its own line joins the value it follows, not a field of its own`() {
        // "ISO 9001:2015" contains a colon. Split naively it becomes a field called "ISO 9001".
        val reply = """
            RECORDS
            NONCONFORMITY
            title: Calibration certificate not signed
            quote: the certificate was never signed off
            ISO 9001:2015 clause 7.1.5
        """.trimIndent()

        val a = AuditRecordParser.parse(reply)

        val finding = a.nonConformities.single()
        assertEquals("Calibration certificate not signed", finding.title)
        assertTrue(
            "the trailing line should join the quote, got '${finding.evidence}'",
            finding.evidence.endsWith("ISO 9001:2015 clause 7.1.5"),
        )
    }

    @Test
    fun `a record with no title is dropped, as it is in JSON`() {
        val reply = """
            RECORDS
            NONCONFORMITY
            quote: a quote with nothing to attach it to

            NONCONFORMITY
            title: A real finding
            quote: a real quote
        """.trimIndent()

        assertEquals(1, AuditRecordParser.parse(reply).nonConformities.size)
    }

    @Test
    fun `markdown decoration, casing and trailing colons are tolerated`() {
        val reply = """
            ```
            ## RECORDS
            **NONCONFORMITY:**
            Title: A finding written loosely
            Evidence: the quote
            ```
        """.trimIndent()

        val finding = AuditRecordParser.parse(reply).nonConformities.single()
        assertEquals("A finding written loosely", finding.title)
        assertEquals("the quote", finding.evidence)
    }

    @Test
    fun `a clean section yields no findings rather than failing`() {
        val reply = """
            RECORDS
            FACTS
            - Calibration log signed 3 March.
            - Four operators held current certificates.
        """.trimIndent()

        val a = AuditRecordParser.parse(reply)

        assertEquals(2, a.facts.size)
        assertTrue(a.nonConformities.isEmpty())
        assertTrue(a.actions.isEmpty())
        assertTrue("a clean section is not empty -- it has facts", !a.isEmpty)
    }

    @Test
    fun `an unreadable reply yields an empty analysis rather than throwing`() {
        val a = AuditRecordParser.parse("I'm sorry, I can't help with that.")

        assertTrue(a.isEmpty)
    }

    @Test
    fun `the worked examples the prompt teaches parse back into what they depict`() {
        // The examples are the contract. If the format they demonstrate does not survive this
        // parser, the prompt is teaching the model to produce something the app cannot read.
        val preamble = AuditPrompts.preamble(AuditPromptProfile.RICH, AuditOutputFormat.RECORDS)
        val exampleA = preamble
            .substringAfter("Worked example A")
            .substringAfter("RECORDS")
            .substringBefore("Worked example B")

        val a = AuditRecordParser.parse("RECORDS\n$exampleA")

        assertEquals(2, a.facts.size)
        assertEquals(2, a.nonConformities.size)
        assertEquals(
            listOf("ISO 45001 clause 7.2"),
            a.nonConformities.last().standards,
        )
        assertTrue(a.actions.isEmpty())
    }
}
