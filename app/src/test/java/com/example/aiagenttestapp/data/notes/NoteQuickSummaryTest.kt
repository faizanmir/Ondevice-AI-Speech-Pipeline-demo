package com.example.aiagenttestapp.data.notes

import com.example.aiagenttestapp.data.audit.AuditAnalysis
import com.example.aiagenttestapp.data.audit.AuditFinding
import com.example.aiagenttestapp.data.audit.AuditProtocolElement
import com.example.aiagenttestapp.data.audit.AuditResultType
import com.example.aiagenttestapp.data.audit.QuickAudit
import com.example.aiagenttestapp.data.audit.QuickRead
import com.example.aiagenttestapp.functions.MarkerKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A voice note's quick summary is the audit queue's quick read with a different renderer on the end.
 * These pin the crossing: that notes really do go through [QuickRead], and that its result becomes a
 * note without losing the conclusion.
 */
class NoteQuickSummaryTest {

    private fun element(
        statement: String,
        result: AuditResultType?,
        reason: String = "",
    ) = AuditProtocolElement(statement = statement, result = result, reason = reason)

    private fun section(
        element: AuditProtocolElement? = null,
        actions: List<AuditFinding> = emptyList(),
        unresolved: List<String> = emptyList(),
    ) = AuditAnalysis(
        protocolElements = listOfNotNull(element),
        actions = actions,
        unresolvedItems = unresolved,
    )

    @Test
    fun `a walkthrough collapses to one conclusion, the most severe`() {
        val reduced = QuickRead.reduce(
            listOf(
                section(element("Pest control was in order", AuditResultType.OK_FOR_DOCUMENTATION)),
                section(element("Chamber 3 had standing condensate", AuditResultType.MAJOR_NONCONFORMITY)),
                section(element("Line 4 swabbed 11 of 12 sites", AuditResultType.MINOR_NONCONFORMITY)),
            ),
        )
        assertNotNull(reduced.element)
        assertEquals(AuditResultType.MAJOR_NONCONFORMITY, reduced.element!!.result)
    }

    @Test
    fun `actions are capped at the shared quick limit`() {
        val many = (1..7).map { AuditFinding(title = "action $it") }
        val reduced = QuickRead.reduce(listOf(section(actions = many)))
        assertEquals(QuickAudit.MAX_ACTIONS, reduced.actions.size)
    }

    @Test
    fun `unresolved items are capped at the shared quick limit`() {
        val many = (1..6).map { "open question $it" }
        val reduced = QuickRead.reduce(listOf(section(unresolved = many)))
        assertEquals(QuickAudit.MAX_UNRESOLVED, reduced.unresolved.size)
    }

    @Test
    fun `a non-conformity conclusion becomes a note finding`() {
        val note = QuickRead.Result(
            element = element("Chamber 3 had standing condensate", AuditResultType.MAJOR_NONCONFORMITY),
            actions = emptyList(),
            unresolved = emptyList(),
        ).toNoteAnalysis()

        val nc = note.findings.single()
        assertEquals(MarkerKind.NonConformity, nc.kind)
        assertTrue(nc.text.contains("condensate"))
    }

    /**
     * Two of the four values in the shared vocabulary are not failures. Filing every conclusion as a
     * finding would report "OK for documentation" as a defect.
     */
    @Test
    fun `a clean conclusion produces no finding`() {
        val note = QuickRead.Result(
            element = element("Pest control was in order", AuditResultType.OK_FOR_DOCUMENTATION),
            actions = emptyList(),
            unresolved = emptyList(),
        ).toNoteAnalysis()

        assertTrue(note.findings.isEmpty())
        assertTrue(note.summary.contains("OK for documentation"))
    }

    @Test
    fun `the conclusion leads the summary line`() {
        val note = QuickRead.Result(
            element = element(
                "Chamber 3 had standing condensate",
                AuditResultType.MAJOR_NONCONFORMITY,
                reason = "Water was in contact with uncovered ready-to-eat product",
            ),
            actions = emptyList(),
            unresolved = emptyList(),
        ).toNoteAnalysis()

        assertTrue(note.summary.lines().first().startsWith("- Major non-conformity:"))
        assertTrue(note.summary.contains("Why: Water was in contact"))
    }

    @Test
    fun `actions become action findings`() {
        val note = QuickRead.Result(
            element = null,
            actions = listOf(AuditFinding(title = "Retrieve the traceability record")),
            unresolved = emptyList(),
        ).toNoteAnalysis()

        val action = note.findings.single()
        assertEquals(MarkerKind.Action, action.kind)
        assertEquals("Retrieve the traceability record", action.text)
    }

    /**
     * An unresolved item is a gap nobody committed to closing. Filing it as an action would invent a
     * commitment that was never made.
     */
    @Test
    fun `unresolved items go to the prose, never to findings`() {
        val note = QuickRead.Result(
            element = null,
            actions = emptyList(),
            unresolved = listOf("Whether released stock is affected"),
        ).toNoteAnalysis()

        assertTrue(note.findings.isEmpty())
        assertTrue(note.summary.contains("Left open: Whether released stock is affected"))
    }

    /** No conclusion is a real answer, not a reason to invent one. */
    @Test
    fun `an empty read produces an empty note rather than a guess`() {
        val note = QuickRead.reduce(listOf(section())).toNoteAnalysis()
        assertTrue(note.summary.isBlank())
        assertTrue(note.findings.isEmpty())
    }

    /** Whatever quick reports, a marker the speaker actually spoke still reaches the note. */
    @Test
    fun `spoken markers survive a quick summary`() {
        val note = QuickRead.Result(
            element = element("Pest control was in order", AuditResultType.OK_FOR_DOCUMENTATION),
            actions = emptyList(),
            unresolved = emptyList(),
        ).toNoteAnalysis()
            .withTaggedFloor(
                listOf(TaggedItem(MarkerKind.NonConformity, "the door seal in chamber four is split")),
            )

        val nc = note.findings.single { it.kind == MarkerKind.NonConformity }
        assertEquals(FindingSource.Tagged, nc.source)
    }
}
