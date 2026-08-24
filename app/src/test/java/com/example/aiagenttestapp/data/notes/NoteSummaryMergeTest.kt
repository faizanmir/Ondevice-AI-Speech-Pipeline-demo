package com.example.aiagenttestapp.data.notes

import com.example.aiagenttestapp.functions.MarkerKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteSummaryMergeTest {

    private fun analysis(summary: String, vararg findings: ParsedFinding) =
        NoteAnalysis(summary = summary, findings = findings.toList())

    private fun action(text: String, owner: String? = null, tagged: Boolean = false) =
        ParsedFinding(
            kind = MarkerKind.Action,
            text = text,
            owner = owner,
            source = if (tagged) FindingSource.Tagged else FindingSource.Inferred,
        )

    private fun nonConformity(text: String) =
        ParsedFinding(kind = MarkerKind.NonConformity, text = text)

    @Test
    fun `a single section is returned untouched`() {
        val one = analysis("- only point", action("do the thing"))
        assertEquals(one, mergeAnalyses(listOf(one)))
    }

    @Test
    fun `points from every section are kept in order`() {
        val merged = mergeAnalyses(
            listOf(analysis("- first point\n- second point"), analysis("- third point")),
        )
        assertEquals(listOf("- first point", "- second point", "- third point"), merged.summary.lines())
    }

    /** Sections overlap, so a boundary point is read twice and must not ship twice. */
    @Test
    fun `a point restated across an overlap appears once`() {
        val merged = mergeAnalyses(
            listOf(analysis("- the seal is split"), analysis("- The seal is split\n- and ice formed")),
        )
        assertEquals(listOf("- the seal is split", "- and ice formed"), merged.summary.lines())
    }

    @Test
    fun `the same finding worded differently across sections folds together`() {
        val merged = mergeAnalyses(
            listOf(
                analysis("", nonConformity("chamber four door seal is split and leaking")),
                analysis("", nonConformity("the door seal in chamber four is split")),
            ),
        )
        assertEquals(1, merged.findings.size)
    }

    @Test
    fun `genuinely different findings both survive`() {
        val merged = mergeAnalyses(
            listOf(
                analysis("", nonConformity("chamber four door seal is split")),
                analysis("", nonConformity("training records for two operatives have expired")),
            ),
        )
        assertEquals(2, merged.findings.size)
    }

    /** A non-conformity and an action that read alike are still two different things. */
    @Test
    fun `findings of different kinds never merge`() {
        val merged = mergeAnalyses(
            listOf(
                analysis("", nonConformity("the extinguisher tag expired last month")),
                analysis("", action("the extinguisher tag expired last month")),
            ),
        )
        assertEquals(2, merged.findings.size)
    }

    @Test
    fun `an owner named in only one section survives the merge`() {
        val merged = mergeAnalyses(
            listOf(
                analysis("", action("retrieve the traceability record")),
                analysis("", action("retrieve the traceability record", owner = "Anders")),
            ),
        )
        assertEquals(1, merged.findings.size)
        assertEquals("Anders", merged.findings.single().owner)
    }

    /** The detailed path's end-to-end order: merge the sections, then the tagged floor. */
    @Test
    fun `a spoken non-conformity survives the merge`() {
        val tagged = listOf(
            TaggedItem(MarkerKind.NonConformity, "the door seal in chamber four is split"),
        )
        val result = mergeAnalyses(listOf(analysis("- chilled storage was inspected")))
            .withTaggedFloor(tagged)

        val nc = result.findings.single { it.kind == MarkerKind.NonConformity }
        assertEquals(FindingSource.Tagged, nc.source)
        assertFalse(nc.text.isBlank())
    }
}
