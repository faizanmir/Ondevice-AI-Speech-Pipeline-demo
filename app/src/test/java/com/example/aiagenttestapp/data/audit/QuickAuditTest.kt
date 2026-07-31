package com.example.aiagenttestapp.data.audit

import com.example.aiagenttestapp.prompts.audit.AuditPromptBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The quick read's own moving parts: how its summary reply is read back, how its sections are
 * sized, and that its records go through the one shared parser.
 *
 * These are the pieces that fail *silently* -- a cap that does not hold, a point list read as
 * preamble, a section sized for the wrong mode -- and each produces a plausible-looking report
 * rather than an error.
 */
class QuickAuditTest {

    // ---- Reading the summary reply ---------------------------------------------------------------

    @Test
    fun `points are read from the bullet markers a model actually emits`() {
        val reply = """
            - Calibration log signed 3 March.
            * Two operators lacked current certificates.
            1. Line ran at 22 units per hour.
            2) Extinguisher inspection overdue by 14 months.
        """.trimIndent()

        assertEquals(
            listOf(
                "Calibration log signed 3 March.",
                "Two operators lacked current certificates.",
                "Line ran at 22 units per hour.",
                "Extinguisher inspection overdue by 14 months.",
            ),
            QuickPointsParser.parseQuickPoints(reply),
        )
    }

    @Test
    fun `preamble and trailing chatter are not mistaken for points`() {
        val reply = """
            Here are the key points from the document:

            - The audit covered the calibration process.

            Let me know if you would like more detail.
        """.trimIndent()

        assertEquals(
            listOf("The audit covered the calibration process."),
            QuickPointsParser.parseQuickPoints(reply),
        )
    }

    @Test
    fun `the cap is enforced in code, not merely asked for in the prompt`() {
        // A model asked for "at most 10" that hands back 14 -- the observed failure this guards.
        val reply = (1..14).joinToString("\n") { "- point $it" }

        val points = QuickPointsParser.parseQuickPoints(reply)

        assertEquals(QuickAudit.MAX_POINTS, points.size)
        assertEquals("point 1", points.first())
        assertEquals("point ${QuickAudit.MAX_POINTS}", points.last())
    }

    @Test
    fun `markdown decoration around a point is stripped`() {
        assertEquals(
            listOf("Torque wrench calibrated 12 May."),
            QuickPointsParser.parseQuickPoints("**- Torque wrench calibrated 12 May.**"),
        )
    }

    @Test
    fun `a reply with no readable points yields an empty list rather than junk`() {
        // The worker falls back to the raw section notes on empty; it must not be handed prose.
        assertTrue(
            QuickPointsParser.parseQuickPoints("I could not summarise this document.").isEmpty(),
        )
    }

    // ---- The per-section records -----------------------------------------------------------------

    @Test
    fun `POINTS records are read by the same parser detailed mode uses`() {
        val reply = """
            RECORDS
            POINTS
            - Torque wrench calibrated 12 May.
            - Certificate not signed off.

            ACTION
            title: Quality manager to sign off the certificate

            ACTION
            title: Check the other certificates
        """.trimIndent()

        val parsed = AuditRecordParser.parse(reply)

        assertEquals(
            listOf("Torque wrench calibrated 12 May.", "Certificate not signed off."),
            parsed.facts,
        )
        assertEquals(
            listOf(
                "Quality manager to sign off the certificate",
                "Check the other certificates",
            ),
            parsed.actions.map { it.title },
        )
        // Quick mode never asks for these, and must not invent them.
        assertTrue(parsed.nonConformities.isEmpty())
    }

    @Test
    fun `a section that states no action yields no actions`() {
        val parsed = AuditRecordParser.parse(
            """
            RECORDS
            POINTS
            - The line ran at 22 units per hour.
            """.trimIndent(),
        )

        assertEquals(listOf("The line ran at 22 units per hour."), parsed.facts)
        assertTrue(parsed.actions.isEmpty())
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
        val context = AuditChunker.AUDIT_CONTEXT_TOKENS

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
    fun `quick's preamble is materially smaller than detailed's`() {
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
    fun `key points and mode survive the codec`() {
        val analysis = AuditAnalysis(
            keyPoints = listOf("first point", "second point"),
            mode = AuditMode.QUICK.name,
            actions = listOf(AuditFinding("Sign the certificate")),
        )

        val decoded = AuditResultCodec.decode(AuditResultCodec.encode(analysis))

        assertEquals(listOf("first point", "second point"), decoded?.keyPoints)
        assertEquals(AuditMode.QUICK, decoded?.auditMode)
        assertEquals(listOf("Sign the certificate"), decoded?.actions?.map { it.title })
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
    fun `a quick analysis carrying only points is not considered empty`() {
        // isEmpty decides whether a section's reply was usable; missing keyPoints here would let a
        // good quick result be discarded as nothing.
        assertTrue(AuditAnalysis().isEmpty)
        assertTrue(!AuditAnalysis(keyPoints = listOf("a point")).isEmpty)
    }
}
