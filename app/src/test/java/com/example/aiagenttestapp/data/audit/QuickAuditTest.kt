package com.example.aiagenttestapp.data.audit

import com.example.aiagenttestapp.prompts.audit.AuditPromptBudget
import com.example.aiagenttestapp.prompts.audit.AuditQuickPrompts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The quick read's own moving parts: what its sections are asked for, how the reply is read back,
 * how the caps hold, and how its sections are sized.
 *
 * These are the pieces that fail *silently* -- a cap that does not hold, an element read as
 * preamble, a section sized for the wrong mode -- and each produces a plausible-looking report
 * rather than an error.
 */
class QuickAuditTest {

    // ---- The per-section records -----------------------------------------------------------------

    @Test
    fun `a quick section's records are read by the same parser detailed mode uses`() {
        val reply = """
            RECORDS
            ELEMENT
            statement: Torque wrench calibration is carried out but its certificate is not signed off.
            result: resultOkForDocumentation
            reason: The calibration was performed on 12 May; only the sign-off is missing.
            evidence: Calibration dated 12 May, and the unsigned certificate.

            ACTION
            title: Quality manager to sign off the certificate

            ACTION
            title: Check the other certificates

            UNRESOLVED
            - The calibration certificate is still unsigned
        """.trimIndent()

        val parsed = AuditRecordParser.parse(reply)

        val element = parsed.protocolElements.single()
        assertEquals(
            "Torque wrench calibration is carried out but its certificate is not signed off.",
            element.statement,
        )
        assertEquals(AuditResultType.OK_FOR_DOCUMENTATION, element.result)
        assertEquals(
            "The calibration was performed on 12 May; only the sign-off is missing.",
            element.reason,
        )
        assertEquals("Calibration dated 12 May, and the unsigned certificate.", element.evidence)
        // Never asked for, so never invented: a quick element carries no clause and no attribution,
        // and the report omits those lines rather than showing them blank.
        assertTrue(element.standards.isEmpty())
        assertEquals("", element.speaker)
        assertEquals("", element.type)

        assertEquals(
            listOf(
                "Quality manager to sign off the certificate",
                "Check the other certificates",
            ),
            parsed.actions.map { it.title },
        )
        assertEquals(listOf("The calibration certificate is still unsigned"), parsed.unresolvedItems)
        // Quick mode never asks for these, and must not invent them.
        assertTrue(parsed.nonConformities.isEmpty())
    }

    @Test
    fun `a section that states no action and nothing unresolved yields neither`() {
        val parsed = AuditRecordParser.parse(
            """
            RECORDS
            ELEMENT
            statement: The line ran at 22 units per hour throughout the observed period.
            """.trimIndent(),
        )

        assertEquals(1, parsed.protocolElements.size)
        assertTrue(parsed.actions.isEmpty())
        assertTrue(parsed.unresolvedItems.isEmpty())
    }

    @Test
    fun `an element with no clear conclusion carries no result rather than a guessed one`() {
        // Absence is a verdict: the report shows no Result line instead of asserting a pass.
        val parsed = AuditRecordParser.parse(
            """
            RECORDS
            ELEMENT
            statement: The parties discussed the calibration schedule without reaching a conclusion.
            reason: Neither side stated a result.
            """.trimIndent(),
        )

        assertNull(parsed.protocolElements.single().result)
    }

    // ---- The caps --------------------------------------------------------------------------------

    @Test
    fun `the action cap is enforced in code, not merely asked for in the prompt`() {
        // A model asked for "at most two" that hands back five -- the observed failure this guards.
        // The merge preserves order of first appearance, so the survivors are the earliest raised.
        val perChunk = listOf((1..5).map { AuditFinding("action $it") })

        val capped = AuditChunker.mergeFindings(perChunk).take(QuickAudit.MAX_ACTIONS)

        assertEquals(QuickAudit.MAX_ACTIONS, capped.size)
        assertEquals("action 1", capped.first().title)
        assertEquals("action ${QuickAudit.MAX_ACTIONS}", capped.last().title)
    }

    @Test
    fun `the unresolved cap is enforced in code and keeps the earliest items`() {
        val perChunk = listOf(listOf("gap 1", "gap 2"), listOf("gap 3"))

        val capped = AuditChunker.mergeStrings(perChunk).take(QuickAudit.MAX_UNRESOLVED)

        assertEquals(listOf("gap 1", "gap 2"), capped)
    }

    @Test
    fun `the quick preamble teaches every result name the parser reads back`() {
        // Two lists that must not drift: a name taught here but unknown to fromWire is read as no
        // conclusion at all, which is the one failure the shared vocabulary exists to prevent.
        val preamble = AuditQuickPrompts.quickPreamble()

        AuditResultType.entries.forEach { type ->
            assertTrue("preamble omits ${type.wireName}", preamble.contains(type.wireName))
        }
    }

    @Test
    fun `the quick preamble states its caps rather than leaving them to code alone`() {
        val preamble = AuditQuickPrompts.quickPreamble()

        assertTrue(preamble.contains("at most ${QuickAudit.MAX_ACTIONS}"))
        assertTrue(preamble.contains("at most ${QuickAudit.MAX_UNRESOLVED}"))
    }

    // ---- Sizing ----------------------------------------------------------------------------------

    @Test
    fun `quick reserves less of the window for its reply than detailed does`() {
        val context = 8192
        val prompt = 1000

        val detailed = AuditChunker.outputReserveTokens(context, prompt, AuditMode.DETAILED)
        val quick = AuditChunker.outputReserveTokens(context, prompt, AuditMode.QUICK)

        assertTrue("quick ($quick) should reserve less than detailed ($detailed)", quick < detailed)
        // Never below the floor: a reply with nowhere to go costs a whole section.
        assertTrue(quick >= AuditChunker.MIN_OUTPUT_RESERVE_TOKENS)
    }

    @Test
    fun `the reply reserve never falls below the floor on a small window`() {
        val quick = AuditChunker.outputReserveTokens(
            contextTokens = 1200,
            promptTokens = 1000,
            mode = AuditMode.QUICK,
        )
        assertEquals(AuditChunker.MIN_OUTPUT_RESERVE_TOKENS, quick)
    }

    @Test
    fun `a quick section carries more text than a detailed one`() {
        val context = 8192

        val detailed = AuditChunker.chunkCharBudget(
            context,
            AuditPromptBudget.fixedPromptTokens(AuditMode.DETAILED, AuditPromptProfile.RICH),
            mode = AuditMode.DETAILED,
        )
        val quick = AuditChunker.chunkCharBudget(
            context,
            AuditPromptBudget.fixedPromptTokens(AuditMode.QUICK, AuditPromptProfile.RICH),
            mode = AuditMode.QUICK,
        )

        // Both effects compound: a shorter preamble AND a smaller reply reserve. This is where the
        // "fewer sections, so faster" claim actually comes from, so it is worth pinning down.
        assertTrue("quick=$quick should exceed detailed=$detailed", quick > detailed)
    }

    @Test
    fun `quick's preamble stays materially smaller than detailed's`() {
        // It grew when quick started asking for an element and a result vocabulary. The margin is
        // the whole speed claim, so it is pinned rather than left to be noticed on a device.
        val detailed = AuditPromptBudget.fixedPromptTokens(AuditMode.DETAILED, AuditPromptProfile.RICH)
        val quick = AuditPromptBudget.fixedPromptTokens(AuditMode.QUICK, AuditPromptProfile.RICH)

        assertTrue("quick=$quick should be well under detailed=$detailed", quick < detailed / 2)
    }

    // ---- Mode round-tripping ---------------------------------------------------------------------

    @Test
    fun `an unknown or absent mode reads as detailed`() {
        // Old rows, old routes and old saved reports all arrive here.
        assertEquals(AuditMode.DETAILED, AuditMode.from(null))
        assertEquals(AuditMode.DETAILED, AuditMode.from(""))
        assertEquals(AuditMode.DETAILED, AuditMode.from("SOMETHING_ELSE"))
        assertEquals(AuditMode.QUICK, AuditMode.from("QUICK"))
    }

    @Test
    fun `a quick report's element, actions and unresolved items survive the codec`() {
        val analysis = AuditAnalysis(
            mode = AuditMode.QUICK.name,
            includeSummary = false,
            protocolElements = listOf(
                AuditProtocolElement(
                    statement = "Calibration is performed but not signed off.",
                    result = AuditResultType.OK_FOR_DOCUMENTATION,
                    reason = "Only the sign-off is missing.",
                    evidence = "Calibration dated 12 May.",
                ),
            ),
            actions = listOf(AuditFinding("Sign the certificate")),
            unresolvedItems = listOf("The certificate is still unsigned"),
        )

        val decoded = AuditResultCodec.decode(AuditResultCodec.encode(analysis))

        assertEquals(AuditMode.QUICK, decoded?.auditMode)
        assertEquals(false, decoded?.includeSummary)
        val element = decoded?.protocolElements?.single()
        assertEquals("Calibration is performed but not signed off.", element?.statement)
        assertEquals(AuditResultType.OK_FOR_DOCUMENTATION, element?.result)
        assertEquals("Only the sign-off is missing.", element?.reason)
        assertEquals("Calibration dated 12 May.", element?.evidence)
        assertEquals(listOf("Sign the certificate"), decoded?.actions?.map { it.title })
        assertEquals(listOf("The certificate is still unsigned"), decoded?.unresolvedItems)
    }

    @Test
    fun `a quick report saved when quick still wrote key points still decodes them`() {
        // The field is kept for exactly this: those reports carry their whole result here, and a
        // migration that dropped it would blank a finished artefact.
        val legacy = AuditResultCodec.encode(
            AuditAnalysis(
                keyPoints = listOf("first point", "second point"),
                mode = AuditMode.QUICK.name,
            ),
        )

        val decoded = AuditResultCodec.decode(legacy)

        assertEquals(listOf("first point", "second point"), decoded?.keyPoints)
        assertEquals(AuditMode.QUICK, decoded?.auditMode)
    }

    @Test
    fun `a report saved before quick mode existed still reads as detailed`() {
        val legacy = AuditResultCodec.encode(
            AuditAnalysis(
                summary = "A prose summary.",
                nonConformities = listOf(AuditFinding("Certificate not signed")),
            ),
        )

        val decoded = AuditResultCodec.decode(legacy)

        assertEquals(AuditMode.DETAILED, decoded?.auditMode)
        assertTrue(decoded?.keyPoints.orEmpty().isEmpty())
    }

    @Test
    fun `a quick analysis carrying only an element is not considered empty`() {
        // isEmpty decides whether a section's reply was usable. A quick section now yields nothing
        // but an element, so treating that as empty would discard every clean section.
        assertTrue(AuditAnalysis().isEmpty)
        assertTrue(
            !AuditAnalysis(
                protocolElements = listOf(AuditProtocolElement(statement = "A conclusion.")),
            ).isEmpty,
        )
    }
}
