package com.example.aiagenttestapp.data.audit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The protocol half of an extraction: elements, what a party merely stated, and what was left open.
 *
 * These travel the same road as the findings -- model reply, per-chunk checkpoint, merge, report --
 * and each leg is a place they can silently vanish. Every test here is one of those legs.
 */
class AuditProtocolTest {

    @Test
    fun `an element block reaches the analysis with every field`() {
        val analysis = AuditRecordParser.parse(
            """
            RECORDS
            ELEMENT
            statement: Calibration was carried out, but the certificate was not signed off
            type: Result
            speaker: Auditor
            result: resultOkForDocumentation
            reason: The work was done; only the approval record is missing
            evidence: The calibration date and the blank sign-off line
            """.trimIndent(),
        )

        val element = analysis.protocolElements.single()
        assertEquals("Result", element.type)
        assertEquals("Auditor", element.speaker)
        assertEquals(AuditResultType.OK_FOR_DOCUMENTATION, element.result)
        assertTrue(element.reason.startsWith("The work was done"))
        assertTrue(element.evidence.startsWith("The calibration date"))
    }

    @Test
    fun `an element that passed is kept, not read as an empty section`() {
        // The prompt asks for elements that were satisfactory, and this is what one looks like: no
        // findings, no actions, one element. If isEmpty counted it as nothing the drain worker would
        // record the section as unreadable and the report would say a section was never analysed.
        val analysis = AuditRecordParser.parse(
            """
            RECORDS
            ELEMENT
            statement: Calibration records and operator certification were in order
            type: Result
            result: resultPotentialImprovement
            """.trimIndent(),
        )

        assertEquals(AuditResultType.POTENTIAL_IMPROVEMENT, analysis.protocolElements.single().result)
        assertTrue(analysis.nonConformities.isEmpty())
        assertTrue("an element-only section must not read as empty", !analysis.isEmpty)
    }

    @Test
    fun `a stated line keeps who said it`() {
        val analysis = AuditRecordParser.parse(
            """
            RECORDS
            STATED
            - Customer: We calibrate every torque wrench annually
            - The line ran at 22 units per hour
            """.trimIndent(),
        )

        assertEquals("Customer", analysis.alsoStated[0].speaker)
        assertEquals("We calibrate every torque wrench annually", analysis.alsoStated[0].text)
        // No attribution offered: the claim survives anyway, unattributed. Losing the statement to
        // save the speaker would be the wrong way round.
        assertEquals("", analysis.alsoStated[1].speaker)
        assertEquals("The line ran at 22 units per hour", analysis.alsoStated[1].text)
    }

    @Test
    fun `a clause inside a statement is not mistaken for a speaker`() {
        val analysis = AuditRecordParser.parse(
            """
            RECORDS
            STATED
            - The requirement we work to is ISO 9001:2015 clause 7.1.5 and we meet it in full
            """.trimIndent(),
        )

        // The colon in "9001:2015" sits well past a name's length, so the whole line is the claim.
        assertEquals("", analysis.alsoStated.single().speaker)
        assertTrue(analysis.alsoStated.single().text.startsWith("The requirement"))
    }

    @Test
    fun `an action carries its priority, status and acceptance`() {
        val analysis = AuditRecordParser.parse(
            """
            RECORDS
            ACTION
            title: Quality manager to sign off the certificate
            priority: medium
            status: PROPOSED
            accepted: yes
            """.trimIndent(),
        )

        val action = analysis.actions.single()
        // Spelled the way this build spells it, whatever case the model chose -- a report showing
        // "medium" and "Medium" as different priorities would look like two.
        assertEquals("Medium", action.priority)
        assertEquals("Proposed", action.status)
        assertEquals(true, action.accepted)
    }

    @Test
    fun `an unstated acceptance is not read as a refusal`() {
        val analysis = AuditRecordParser.parse(
            """
            RECORDS
            ACTION
            title: Check the other certificates
            """.trimIndent(),
        )

        // Null, not false: nobody refusing and nobody being asked are different facts, and only one
        // of them belongs in a report as a rejected action.
        assertNull(analysis.actions.single().accepted)
    }

    @Test
    fun `a record-format finding now carries its result`() {
        // Records never had a result line, so every report produced in the default format graded
        // nothing at all -- the shared vocabulary existed only on the JSON path.
        val analysis = AuditRecordParser.parse(
            """
            RECORDS
            NONCONFORMITY
            title: Calibration certificate not signed off
            quote: the certificate was never signed off
            result: minorNonconformity
            """.trimIndent(),
        )

        assertEquals(
            AuditResultType.MINOR_NONCONFORMITY,
            analysis.nonConformities.single().resultType,
        )
    }

    @Test
    fun `the protocol survives the checkpoint it is banked in`() {
        val chunk = AuditAnalysis(
            protocolElements = listOf(
                AuditProtocolElement(
                    statement = "Calibration was carried out",
                    type = "Result",
                    speaker = "Auditor",
                    result = AuditResultType.OK_FOR_DOCUMENTATION,
                    reason = "Only the approval record is missing",
                    evidence = "The calibration date",
                    standards = listOf("ISO 9001:2015 clause 7.1.5"),
                ),
            ),
            alsoStated = listOf(AuditStatement("Customer", "We calibrate annually")),
            unresolvedItems = listOf("The certificate is still unsigned"),
            actions = listOf(
                AuditFinding(title = "Sign it off", status = "Agreed", accepted = true),
            ),
        )

        val restored = AuditResultCodec.decode(AuditResultCodec.encode(chunk))

        assertEquals(chunk.protocolElements, restored?.protocolElements)
        assertEquals(chunk.alsoStated, restored?.alsoStated)
        assertEquals(chunk.unresolvedItems, restored?.unresolvedItems)
        assertEquals("Agreed", restored?.actions?.single()?.status)
        assertEquals(true, restored?.actions?.single()?.accepted)
    }

    @Test
    fun `the same element read from two overlapping chunks appears once`() {
        val first = AuditProtocolElement(
            statement = "Calibration was carried out",
            type = "Result",
            result = AuditResultType.POTENTIAL_IMPROVEMENT,
        )
        val second = first.copy(
            speaker = "Auditor",
            result = AuditResultType.OK_FOR_DOCUMENTATION,
            reason = "The approval record is missing",
        )

        val merged = AuditChunker.mergeElements(listOf(listOf(first), listOf(second)))

        val element = merged.single()
        // Fields fill in from whichever chunk had them...
        assertEquals("Auditor", element.speaker)
        assertEquals("The approval record is missing", element.reason)
        // ...but the conclusion only ever hardens. The chunk that saw the qualification wins over
        // the one that read it as a clean pass.
        assertEquals(AuditResultType.OK_FOR_DOCUMENTATION, element.result)
    }

    @Test
    fun `a document collapses to the one element the standard belongs to`() {
        // Sections produce an element each; the report shows one. The element carrying the cited
        // standard leads, because the element IS the requirement the audit was run against.
        val plain = AuditProtocolElement(
            statement = "The register was reviewed",
            result = AuditResultType.POTENTIAL_IMPROVEMENT,
        )
        val cited = AuditProtocolElement(
            statement = "Risk assessment was carried out and approved",
            result = AuditResultType.POTENTIAL_IMPROVEMENT,
            standards = listOf("ISO/IEC 27001:2022 Clause 6.1"),
        )
        val qualified = AuditProtocolElement(
            statement = "The residual risk acceptance record was missing",
            result = AuditResultType.OK_FOR_DOCUMENTATION,
        )

        val element = AuditChunker.collapseElements(listOf(plain, cited, qualified))!!

        assertEquals("Risk assessment was carried out and approved", element.statement)
        // Nothing is lost by collapsing: the worst conclusion any section reached survives onto the
        // one that leads, so a section that saw the gap cannot be outvoted by sections that did not.
        assertEquals(AuditResultType.OK_FOR_DOCUMENTATION, element.result)
        assertEquals(listOf("ISO/IEC 27001:2022 Clause 6.1"), element.standards)
    }

    @Test
    fun `a document that produced no element collapses to none`() {
        // Not an empty placeholder element: the report shows no section at all rather than a
        // heading over a blank conclusion.
        assertNull(AuditChunker.collapseElements(emptyList()))
    }

    @Test
    fun `two different conclusions about the same subject stay separate`() {
        // The findings merge is fuzzy by design; this one must not be. "Records were in order" and
        // "records were not in order" share almost every word.
        val merged = AuditChunker.mergeElements(
            listOf(
                listOf(AuditProtocolElement("The records were in order")),
                listOf(AuditProtocolElement("The records were not in order")),
            ),
        )

        assertEquals(2, merged.size)
    }

    @Test
    fun `an element's cited clause is dropped when the document never names it`() {
        val elements = listOf(
            AuditProtocolElement(
                statement = "Risk assessment was carried out",
                standards = listOf("ISO 9001:2015 clause 7.1.5", "ISO/IEC 27001:2022 Clause 6.1"),
            ),
        )

        val (checked, rejected) = AuditEvidence.verifyElements(
            elements,
            "The auditor reviewed the risk assessment against ISO/IEC 27001:2022 Clause 6.1.",
        )

        // The clause the text names survives; the one echoed in from elsewhere does not. Standards
        // get a section of their own in the report, so an invented one would be presented as the
        // requirement the audit was run against.
        assertEquals(listOf("ISO/IEC 27001:2022 Clause 6.1"), checked.single().standards)
        assertEquals(1, rejected)
    }

    @Test
    fun `an element's evidence is never quote-checked`() {
        // It is a summary of what was produced, not a quote from the text, so a substring check
        // could never pass. Running one would blank the field on every element in every report.
        val elements = listOf(
            AuditProtocolElement(
                statement = "Risk assessment was carried out",
                evidence = "The procedure, the risk register, and the treatment plan",
            ),
        )

        val (checked, _) = AuditEvidence.verifyElements(elements, "nothing resembling that text")

        assertEquals(
            "The procedure, the risk register, and the treatment plan",
            checked.single().evidence,
        )
    }
}
