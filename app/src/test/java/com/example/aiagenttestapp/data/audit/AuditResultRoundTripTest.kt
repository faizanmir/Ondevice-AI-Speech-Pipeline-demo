package com.example.aiagenttestapp.data.audit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The result type surviving the round trip a real audit makes: model reply -> parsed finding ->
 * stored JSON -> parsed finding again.
 *
 * Both halves have to agree on how absence is spelled. If the encoder wrote an empty string where
 * the decoder expects a missing key, every unclassified finding would come back as a conclusion --
 * quietly, and in the direction that matters most.
 */
class AuditResultRoundTripTest {

    @Test
    fun `a result type in a model reply reaches the finding`() {
        val analysis = AuditAnalysisParser.parse(
            """
            {"nonConformities":[
              {"title":"Torque wrench uncalibrated","resultType":"majorNonconformity"}
            ]}
            """.trimIndent(),
        )

        assertEquals(
            AuditResultType.MAJOR_NONCONFORMITY,
            analysis?.nonConformities?.single()?.resultType,
        )
    }

    @Test
    fun `a reply with no result type yields no conclusion rather than a default`() {
        val analysis = AuditAnalysisParser.parse(
            """{"nonConformities":[{"title":"Exit partially blocked"}]}""",
        )

        assertNull(analysis?.nonConformities?.single()?.resultType)
    }

    @Test
    fun `an invented result type is not guessed at`() {
        val analysis = AuditAnalysisParser.parse(
            """{"nonConformities":[{"title":"x","resultType":"catastrophic"}]}""",
        )

        // The five names are a closed vocabulary; a near miss is no conclusion, not the nearest one.
        assertNull(analysis?.nonConformities?.single()?.resultType)
    }

    @Test
    fun `the severity aliases do not leak into the result type`() {
        val analysis = AuditAnalysisParser.parse(
            """{"nonConformities":[{"title":"x","classification":"major"}]}""",
        )

        val finding = analysis?.nonConformities?.single()
        // "classification" is a severity alias. It must grade the old field and say nothing about
        // the new one, or every legacy reply would arrive carrying a verdict it never stated.
        assertTrue(finding?.severity?.isNotEmpty() == true)
        assertNull(finding?.resultType)
    }

    @Test
    fun `a stored analysis comes back with its result types intact`() {
        val original = AuditAnalysis(
            nonConformities = listOf(
                AuditFinding(title = "a", resultType = AuditResultType.MINOR_NONCONFORMITY),
                AuditFinding(title = "b", resultType = null),
            ),
        )

        val restored = AuditResultCodec.decode(AuditResultCodec.encode(original))

        assertEquals(
            AuditResultType.MINOR_NONCONFORMITY,
            restored?.nonConformities?.get(0)?.resultType,
        )
        // Absent stays absent across the round trip: the encoder omits the key rather than writing
        // a null, and the decoder reads a missing key as no conclusion.
        assertNull(restored?.nonConformities?.get(1)?.resultType)
    }

    @Test
    fun `a section's cost survives the checkpoint it is banked in`() {
        // This is the round trip that makes a resumed document report its whole cost: each section's
        // turn is stored with that section's findings and added up again at the end.
        val chunk = AuditAnalysis(
            facts = listOf("Calibration log signed 3 March."),
            runStats = AuditRunStats(
                turns = 1,
                promptTokens = 3_600,
                generatedTokens = 900,
                prefillMillis = 4_000,
                decodeMillis = 50_000,
            ),
        )

        val restored = AuditResultCodec.decode(AuditResultCodec.encode(chunk))

        assertEquals(chunk.runStats, restored?.runStats)
    }

    @Test
    fun `a report with no measurements says so rather than claiming zero throughput`() {
        // Reports saved before the runtime's counters were switched on, and any engine that reports
        // none, must not render as "0 tok/s" -- a measurement nobody made.
        val restored = AuditResultCodec.decode(AuditResultCodec.encode(AuditAnalysis(summary = "x")))

        assertTrue(restored?.runStats?.isEmpty == true)
    }

    @Test
    fun `section costs add up across a document`() {
        val a = AuditRunStats(1, promptTokens = 3_600, generatedTokens = 900, prefillMillis = 4_000, decodeMillis = 50_000)
        val b = AuditRunStats(1, promptTokens = 3_400, generatedTokens = 800, prefillMillis = 3_800, decodeMillis = 44_000)

        val total = a + b

        assertEquals(2, total.turns)
        assertEquals(7_000, total.promptTokens)
        assertEquals(1_700, total.generatedTokens)
        // 7,000 prompt tokens in 7.8 seconds.
        assertEquals(897.4, total.prefillTokensPerSecond, 0.1)
        // 1,700 generated in 94 seconds.
        assertEquals(18.1, total.decodeTokensPerSecond, 0.1)
    }
}
