package com.example.aiagenttestapp.data.notes

import com.example.aiagenttestapp.functions.MarkerKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parser's job is reading small on-device models, which decorate headings, forget bullets, drop
 * sections and sometimes answer in prose. Every malformed case here is one a 1-3 B model actually
 * produces.
 */
class NoteAnalysisParserTest {

    private fun nc(analysis: NoteAnalysis) =
        analysis.findings.filter { it.kind == MarkerKind.NonConformity }

    private fun actions(analysis: NoteAnalysis) =
        analysis.findings.filter { it.kind == MarkerKind.Action }

    @Test
    fun `parses the requested three-section format`() {
        val analysis = NoteAnalysisParser.parse(
            """
            ## Summary
            - Walked bay 3 and the loading dock.
            - Two problems found.

            ## Non-conformities
            - The bay 3 extinguisher expired last month.
            - The fire exit sign is unlit.

            ## Actions
            - Order a replacement extinguisher — owner: Bob
            - Report the sign to facilities
            """.trimIndent(),
        )

        assertTrue(analysis.summary.contains("Walked bay 3"))
        assertTrue(analysis.summary.contains("Two problems found."))
        // Section headings must not leak into the summary text.
        assertTrue(!analysis.summary.contains("Non-conformities"))

        assertEquals(2, nc(analysis).size)
        assertEquals("The bay 3 extinguisher expired last month.", nc(analysis)[0].text)

        assertEquals(2, actions(analysis).size)
        assertEquals("Order a replacement extinguisher", actions(analysis)[0].text)
        assertEquals("Bob", actions(analysis)[0].owner)
        assertNull(actions(analysis)[1].owner)
    }

    @Test
    fun `tolerates decorated headings and mixed bullet markers`() {
        val analysis = NoteAnalysisParser.parse(
            """
            **Summary:**
            * Checked the dock.

            ### NON-CONFORMITIES
            1. Guard rail is loose.
            2) Floor marking worn away.

            _Actions_
            • Tighten the rail — owner: Alice
            """.trimIndent(),
        )

        assertEquals(2, nc(analysis).size)
        assertEquals("Guard rail is loose.", nc(analysis)[0].text)
        assertEquals("Floor marking worn away.", nc(analysis)[1].text)
        assertEquals(1, actions(analysis).size)
        assertEquals("Alice", actions(analysis)[0].owner)
    }

    @Test
    fun `none under a heading yields no findings`() {
        val analysis = NoteAnalysisParser.parse(
            """
            ## Summary
            - All clear.

            ## Non-conformities
            none

            ## Actions
            - N/A
            """.trimIndent(),
        )

        assertEquals(emptyList<ParsedFinding>(), analysis.findings)
        assertTrue(analysis.summary.contains("All clear."))
    }

    @Test
    fun `a missing section is simply absent`() {
        val analysis = NoteAnalysisParser.parse(
            """
            ## Summary
            - Quick check.

            ## Non-conformities
            - Extinguisher expired.
            """.trimIndent(),
        )

        assertEquals(1, nc(analysis).size)
        assertEquals(emptyList<ParsedFinding>(), actions(analysis))
    }

    @Test
    fun `prose with no headings is kept entirely as the summary`() {
        val prose = "The inspector walked bay 3 and found an expired extinguisher."

        val analysis = NoteAnalysisParser.parse(prose)

        assertEquals(prose, analysis.summary)
        assertEquals(emptyList<ParsedFinding>(), analysis.findings)
    }

    @Test
    fun `empty output parses to an empty analysis`() {
        val analysis = NoteAnalysisParser.parse("   \n \n")
        assertEquals("", analysis.summary)
        assertEquals(emptyList<ParsedFinding>(), analysis.findings)
    }

    @Test
    fun `owner variants are all recognised and unassigned reads as no owner`() {
        val analysis = NoteAnalysisParser.parse(
            """
            ## Actions
            - Fix the rail — owner: Alice
            - Order parts (owner: Bob)
            - Call the supplier - owner: Carol
            - Something vague — owner: unassigned
            """.trimIndent(),
        )

        assertEquals(
            listOf("Alice", "Bob", "Carol", null),
            actions(analysis).map { it.owner },
        )
        assertEquals("Fix the rail", actions(analysis)[0].text)
        assertEquals("Order parts", actions(analysis)[1].text)
        assertEquals("Something vague", actions(analysis)[3].text)
    }

    @Test
    fun `parsed findings are marked inferred until the tagged floor promotes them`() {
        val analysis = NoteAnalysisParser.parse("## Non-conformities\n- Extinguisher expired.")

        assertEquals(FindingSource.Inferred, analysis.findings[0].source)
    }

    // -------- the deterministic floor --------

    @Test
    fun `a dropped tagged item is put back`() {
        val analysis = NoteAnalysis(summary = "s", findings = emptyList())
            .withTaggedFloor(
                listOf(TaggedItem(MarkerKind.NonConformity, "the extinguisher expired last month")),
            )

        // The model reported nothing; the user's spoken marker still reaches the note.
        assertEquals(1, analysis.findings.size)
        assertEquals(FindingSource.Tagged, analysis.findings[0].source)
        assertEquals("the extinguisher expired last month", analysis.findings[0].text)
    }

    @Test
    fun `a rephrased tagged item is promoted rather than duplicated`() {
        val analysis = NoteAnalysis(
            summary = "s",
            findings = listOf(
                ParsedFinding(
                    MarkerKind.NonConformity,
                    "The extinguisher in bay 3 expired last month and was never swapped.",
                ),
            ),
        ).withTaggedFloor(
            listOf(TaggedItem(MarkerKind.NonConformity, "extinguisher expired last month")),
        )

        assertEquals(1, analysis.findings.size)
        assertEquals(FindingSource.Tagged, analysis.findings[0].source)
        // The model's better-phrased version is what survives.
        assertTrue(analysis.findings[0].text.contains("bay 3"))
    }

    @Test
    fun `a tagged item is not matched against the wrong kind`() {
        val analysis = NoteAnalysis(
            summary = "s",
            // Same words, but reported as an action.
            findings = listOf(ParsedFinding(MarkerKind.Action, "extinguisher expired last month")),
        ).withTaggedFloor(
            listOf(TaggedItem(MarkerKind.NonConformity, "extinguisher expired last month")),
        )

        assertEquals(2, analysis.findings.size)
        assertEquals(1, nc(analysis).size)
        assertEquals(FindingSource.Tagged, nc(analysis)[0].source)
    }

    @Test
    fun `unrelated findings are left alone and inferred ones stay inferred`() {
        val analysis = NoteAnalysis(
            summary = "s",
            findings = listOf(
                ParsedFinding(MarkerKind.NonConformity, "The guard rail on the mezzanine is loose."),
            ),
        ).withTaggedFloor(
            listOf(TaggedItem(MarkerKind.NonConformity, "extinguisher expired last month")),
        )

        assertEquals(2, analysis.findings.size)
        assertEquals(FindingSource.Inferred, analysis.findings[0].source)
        assertEquals(FindingSource.Tagged, analysis.findings[1].source)
    }

    @Test
    fun `every tagged item survives when the model reports only some of them`() {
        val tagged = listOf(
            TaggedItem(MarkerKind.NonConformity, "extinguisher expired last month"),
            TaggedItem(MarkerKind.NonConformity, "fire exit sign is unlit"),
            TaggedItem(MarkerKind.Action, "order a replacement extinguisher"),
        )

        val analysis = NoteAnalysisParser
            .parse("## Non-conformities\n- The extinguisher expired last month.")
            .withTaggedFloor(tagged)

        // This is the guarantee: three markers spoken, three findings, whatever the model did.
        assertEquals(3, analysis.findings.count { it.source == FindingSource.Tagged })
        assertEquals(2, nc(analysis).size)
        assertEquals(1, actions(analysis).size)
    }

    @Test
    fun `an empty tagged list changes nothing`() {
        val original = NoteAnalysisParser.parse("## Non-conformities\n- Rail loose.")
        assertEquals(original, original.withTaggedFloor(emptyList()))
    }
}
