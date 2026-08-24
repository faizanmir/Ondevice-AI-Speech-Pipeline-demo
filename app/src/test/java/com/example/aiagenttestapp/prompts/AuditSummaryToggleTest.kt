package com.example.aiagenttestapp.prompts

import com.example.aiagent.engine.core.ContextWindow
import com.example.aiagenttestapp.data.audit.AuditAnalysis
import com.example.aiagenttestapp.data.audit.AuditMode
import com.example.aiagenttestapp.data.audit.AuditPromptProfile
import com.example.aiagenttestapp.data.audit.AuditRecordParser
import com.example.aiagenttestapp.data.audit.AuditResultCodec
import com.example.aiagenttestapp.prompts.audit.AuditExtractionPrompts
import com.example.aiagenttestapp.prompts.audit.AuditPromptBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The summary toggle, which is really a toggle on two things at once: the summary turn, and the
 * per-section facts that turn is written from.
 *
 * They are one switch because they are one decision -- facts are rendered nowhere and feed nothing
 * else, so facts without a summary is output nobody reads, and a summary without facts cannot be
 * written. These tests hold that coupling in place, since it would be easy to "fix" one half later
 * and leave the pipeline paying for notes it never uses.
 */
class AuditSummaryToggleTest {

    private val withFacts = AuditExtractionPrompts.preamble(AuditPromptProfile.LEAN, facts = true)
    private val withoutFacts = AuditExtractionPrompts.preamble(AuditPromptProfile.LEAN, facts = false)

    @Test
    fun `turning the summary off takes the facts out of the prompt entirely`() {
        // Out, not shortened. An instruction the report has no use for still costs output on every
        // section, which is where the saving is -- the summary itself is one turn.
        assertTrue(withFacts.contains("- facts:"))
        assertFalse(withoutFacts.contains("- facts:"))
        assertTrue(withFacts.contains("FACTS"))
        assertFalse("the answer shape still asks for a FACTS block", withoutFacts.contains("FACTS"))
    }

    @Test
    fun `the worked examples stop demonstrating facts too`() {
        // An example is the shape a model copies. One that kept its FACTS block while the
        // instructions dropped it would reinstate the cost silently.
        assertFalse(withoutFacts.contains("- Torque wrench calibrated 12 May"))
        assertTrue(withFacts.contains("- Torque wrench calibrated 12 May"))
    }

    @Test
    fun `everything that decides a finding survives the toggle`() {
        // The toggle is about what a section REPORTS, never about what counts as a finding. A
        // difference here would mean the same transcript yields different findings depending on
        // whether someone wanted prose.
        listOf(
            "not calibrated, not trained, out of date, out of specification, not followed",
            "Count issues, not mentions",
            "Also find EVERY action",
            "Every non-conformity must include",
            "Also recognise the PROTOCOL ELEMENT this section audits",
            "UNRESOLVED",
        ).forEach {
            assertTrue("lost with the facts: $it", withoutFacts.contains(it))
        }
    }

    @Test
    fun `the shorter prompt is what the chunk sizing is told about`() {
        // Chunks are cut at enqueue from what the preamble leaves. If the budget kept reporting the
        // longer prompt, a facts-free run would simply get smaller sections than it had earned --
        // and the toggle's whole point is more room for the document.
        val long = AuditPromptBudget.fixedPromptTokens(AuditMode.DETAILED, AuditPromptProfile.RICH, facts = true)
        val short = AuditPromptBudget.fixedPromptTokens(AuditMode.DETAILED, AuditPromptProfile.RICH, facts = false)

        assertTrue("facts-free prompt is $short tok against $long", short < long)
    }

    @Test
    fun `quick mode ignores the toggle`() {
        // POINTS are quick mode's whole deliverable, not raw material for a summary. A quick read
        // without them is a blank report rather than a faster one.
        val on = AuditPromptBudget.fixedPromptTokens(AuditMode.QUICK, AuditPromptProfile.LEAN, facts = true)
        val off = AuditPromptBudget.fixedPromptTokens(AuditMode.QUICK, AuditPromptProfile.LEAN, facts = false)

        assertEquals(on, off)
    }

    @Test
    fun `a section answered without facts still parses`() {
        // The records parser must not need a FACTS block to find the blocks after it.
        val analysis = AuditRecordParser.parse(
            """
            RECORDS
            NONCONFORMITY
            title: Certificate not signed off
            quote: the certificate was never signed off
            """.trimIndent(),
        )

        assertTrue(analysis.facts.isEmpty())
        assertEquals(1, analysis.nonConformities.size)
    }

    @Test
    fun `a report knows the summary was skipped rather than lost`() {
        // The two look identical in the data -- an empty string either way -- and the report says
        // very different things about them. Old reports, which carry no flag at all, must read as
        // having had one, because they did.
        val skipped = AuditResultCodec.decode(
            AuditResultCodec.encode(AuditAnalysis(summary = "", includeSummary = false)),
        )
        val legacy = AuditAnalysisParserFixture.parse("""{"summary":"an older report"}""")

        assertFalse(skipped!!.includeSummary)
        assertTrue(legacy.includeSummary)
    }

    @Test
    fun `dropping the facts is worth measurably more than nothing`() {
        // The saving this toggle exists for, stated as a number so it cannot quietly become zero.
        // Per section, not per document -- that is the whole argument for coupling it to the facts.
        val saved = ContextWindow.estimateTokens(withFacts) - ContextWindow.estimateTokens(withoutFacts)

        assertTrue("only $saved tokens off the preamble", saved > 40)
    }

    /** Named indirection so the intent of the legacy case reads at the call site. */
    private object AuditAnalysisParserFixture {
        fun parse(json: String): AuditAnalysis =
            com.example.aiagenttestapp.data.audit.AuditAnalysisParser.parse(json)!!
    }
}
