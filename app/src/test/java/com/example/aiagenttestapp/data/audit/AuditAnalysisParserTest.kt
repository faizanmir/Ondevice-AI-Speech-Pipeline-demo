package com.example.aiagenttestapp.data.audit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parser is the seam between an unreliable model and a structured UI. A small model wraps its
 * JSON in fences, precedes it with a `<think>` block, and picks its own key names and array shapes,
 * so the tolerance here is the feature -- these pin the shapes we have to survive in the field.
 */
class AuditAnalysisParserTest {

    @Test
    fun `parses a clean object`() {
        val raw = """
            {"summary":"Fire drill audit.",
             "nonConformities":[{"title":"No extinguisher check","detail":"Clause 7.2"}],
             "actions":[{"title":"Schedule monthly checks","detail":"Owner: facilities"}],
             "faqs":["When was the last drill?","Who signed off?"]}
        """.trimIndent()

        val result = AuditAnalysisParser.parse(raw)!!

        assertEquals("Fire drill audit.", result.summary)
        assertEquals(1, result.nonConformities.size)
        assertEquals("No extinguisher check", result.nonConformities[0].title)
        assertEquals("Clause 7.2", result.nonConformities[0].detail)
        assertEquals("Schedule monthly checks", result.actions[0].title)
        assertEquals(listOf("When was the last drill?", "Who signed off?"), result.faqs)
    }

    @Test
    fun `tolerates code fences, a think block and trailing prose`() {
        val raw = """
            <think>Let me review the transcript.</think>
            Sure, here is the analysis:
            ```json
            {"summary":"Ok","nonConformities":[],"actions":[],"faqs":["Why?"]}
            ```
            Hope that helps!
        """.trimIndent()

        val result = AuditAnalysisParser.parse(raw)!!

        assertEquals("Ok", result.summary)
        assertEquals(listOf("Why?"), result.faqs)
        assertTrue(result.nonConformities.isEmpty())
    }

    @Test
    fun `accepts plain-string findings, not just objects`() {
        val raw = """{"nonConformities":["Extinguisher expired","Exit blocked"],"actions":["Replace it"]}"""

        val result = AuditAnalysisParser.parse(raw)!!

        assertEquals(listOf("Extinguisher expired", "Exit blocked"), result.nonConformities.map { it.title })
        assertEquals("Replace it", result.actions.single().title)
    }

    @Test
    fun `accepts alternate key names and object-shaped faqs`() {
        val raw = """
            {"conversationSummary":"S","non_conformities":[{"name":"X","description":"d"}],
             "correctiveActions":[],"questions":[{"question":"Q1?"}]}
        """.trimIndent()

        val result = AuditAnalysisParser.parse(raw)!!

        assertEquals("S", result.summary)
        assertEquals("X", result.nonConformities.single().title)
        assertEquals("d", result.nonConformities.single().detail)
        assertEquals(listOf("Q1?"), result.faqs)
    }

    @Test
    fun `reads the standards array on findings, string or object shaped`() {
        val raw = """
            {"nonConformities":[
                {"title":"Uncalibrated gauge","detail":"d","standards":["ISO 9001:2015 §7.1.5"]},
                {"title":"No PPE","standards":[{"standard":"ISO 45001","clause":"8.1"}]}],
             "actions":[{"title":"Recalibrate","standards":[]}]}
        """.trimIndent()

        val result = AuditAnalysisParser.parse(raw)!!

        assertEquals(listOf("ISO 9001:2015 §7.1.5"), result.nonConformities[0].standards)
        assertEquals(listOf("ISO 45001"), result.nonConformities[1].standards)
        assertTrue(result.actions.single().standards.isEmpty())
    }

    @Test
    fun `a finding with no standards yields an empty list, not null`() {
        val result = AuditAnalysisParser.parse("""{"actions":["Do the thing"]}""")!!
        assertTrue(result.actions.single().standards.isEmpty())
    }

    @Test
    fun `returns null when there is no json at all`() {
        assertNull(AuditAnalysisParser.parse("I could not analyse that transcript."))
    }

    @Test
    fun `an empty object parses to an empty analysis`() {
        val result = AuditAnalysisParser.parse("{}")!!
        assertTrue(result.isEmpty)
    }

    @Test
    fun `reads and normalises the severity of a non-conformity`() {
        val raw = """
            {"nonConformities":[
                {"title":"Missing sign-off","severity":"Minor non-conformity"},
                {"title":"Uncontrolled document","classification":"MAJOR"},
                {"title":"Untidy store","severity":"opportunity for improvement"},
                {"title":"Ungraded finding"}]}
        """.trimIndent()

        val nc = AuditAnalysisParser.parse(raw)!!.nonConformities

        assertEquals(AuditSeverity.MINOR, nc[0].severity)
        assertEquals(AuditSeverity.MAJOR, nc[1].severity)
        assertEquals(AuditSeverity.OBSERVATION, nc[2].severity)
        assertEquals("", nc[3].severity)
    }

    @Test
    fun `takes the real object when a plain-text draft precedes the json`() {
        val raw = """
            FINDINGS
            - Gauge out of calibration {see log}
            - Records not signed
            JSON
            {"summary":"Line audit.","nonConformities":[{"title":"Gauge out of calibration"},
             {"title":"Records not signed"}],"actions":[]}
        """.trimIndent()

        val result = AuditAnalysisParser.parse(raw)!!

        assertEquals("Line audit.", result.summary)
        assertEquals(
            listOf("Gauge out of calibration", "Records not signed"),
            result.nonConformities.map { it.title },
        )
    }

    @Test
    fun `severity reads the concluding grade, not one mentioned and rejected`() {
        assertEquals(
            AuditSeverity.MINOR,
            AuditSeverity.normalise("This is not a major breach, just an isolated lapse.\nminor"),
        )
        assertEquals(
            AuditSeverity.MAJOR,
            AuditSeverity.normalise("More than a minor issue -- the process is defeated.\nmajor"),
        )
        assertEquals(AuditSeverity.OBSERVATION, AuditSeverity.normalise("Not yet a breach.\nobservation"))
    }

    @Test
    fun `severity survives a codec round-trip`() {
        val original = AuditAnalysis(
            summary = "s",
            nonConformities = listOf(
                AuditFinding("Major thing", severity = AuditSeverity.MAJOR),
                AuditFinding("Minor thing", severity = AuditSeverity.MINOR),
            ),
        )

        val decoded = AuditResultCodec.decode(AuditResultCodec.encode(original))!!

        assertEquals(AuditSeverity.MAJOR, decoded.nonConformities[0].severity)
        assertEquals(AuditSeverity.MINOR, decoded.nonConformities[1].severity)
    }

    @Test
    fun `reads facts and per-finding evidence`() {
        val raw = """
            {"facts":["Log signed 3 March.","Line ran at 22 units per hour."],
             "nonConformities":[{"title":"Gauge uncalibrated","detail":"d",
                                 "evidence":"the gauge had not been calibrated"}],
             "actions":[]}
        """.trimIndent()

        val result = AuditAnalysisParser.parse(raw)!!

        assertEquals(listOf("Log signed 3 March.", "Line ran at 22 units per hour."), result.facts)
        assertEquals("the gauge had not been calibrated", result.nonConformities.single().evidence)
        // evidence is its own field now: it must not have been swallowed as the detail.
        assertEquals("d", result.nonConformities.single().detail)
    }

    @Test
    fun `a section with facts but no findings is not treated as empty`() {
        // The parser uses isEmpty to choose between candidate objects, so a clean section -- facts,
        // no findings -- has to count as a real answer or a stray object could outrank it.
        val result = AuditAnalysisParser.parse("""{"facts":["All four operators certified."],
            "nonConformities":[],"actions":[]}""")!!

        assertFalse(result.isEmpty)
        assertEquals(1, result.facts.size)
    }

    @Test
    fun `parse failure and unanalysed count survive a codec round-trip`() {
        // The parse-failure marker is the whole partial-failure mechanism: if it does not survive
        // the chunk checkpoint, a section that was never read is silently reported as analysed.
        val chunk = AuditResultCodec.decode(
            AuditResultCodec.encode(AuditAnalysis(parseFailed = true)),
        )!!
        assertTrue(chunk.parseFailed)

        val report = AuditResultCodec.decode(
            AuditResultCodec.encode(
                AuditAnalysis(
                    summary = "s",
                    unanalysedSections = 3,
                    engineName = "llama.cpp",
                    promptProfile = "rich",
                ),
            ),
        )!!
        assertEquals(3, report.unanalysedSections)
        assertEquals("llama.cpp", report.engineName)
        assertEquals("rich", report.promptProfile)
    }

    @Test
    fun `a successfully parsed chunk is not marked as failed`() {
        val result = AuditAnalysisParser.parse("""{"facts":["a"],"nonConformities":[]}""")!!
        assertFalse(result.parseFailed)
    }

    @Test
    fun `facts and evidence survive a codec round-trip`() {
        // This encoding is the per-chunk checkpoint: anything it drops is lost between the map and
        // reduce stages, which is exactly how facts would silently never reach the summary.
        val original = AuditAnalysis(
            facts = listOf("Extinguisher last inspected 14 months ago."),
            nonConformities = listOf(
                AuditFinding("Inspection overdue", "d", evidence = "last inspected 14 months ago"),
            ),
        )

        val decoded = AuditResultCodec.decode(AuditResultCodec.encode(original))!!

        assertEquals(original.facts, decoded.facts)
        assertEquals("last inspected 14 months ago", decoded.nonConformities.single().evidence)
    }

    @Test
    fun `reads the verdict field and it survives a codec round-trip`() {
        // A reply carrying only a stated verdict is still a real (non-empty) analysis: a clean
        // section can legitimately report nothing but the auditor's own classification.
        val parsed = AuditAnalysisParser.parse("""{"verdict":"OK for documentation"}""")!!
        assertEquals("OK for documentation", parsed.verdict)
        assertFalse(parsed.isEmpty)

        val decoded = AuditResultCodec.decode(AuditResultCodec.encode(parsed))!!
        assertEquals("OK for documentation", decoded.verdict)
    }

    @Test
    fun `diagnose explains why a reply was unreadable`() {
        assertEquals(
            "the model returned an empty reply",
            AuditAnalysisParser.diagnose("   \n "),
        )
        assertEquals(
            "the model's reply contained no JSON to read",
            AuditAnalysisParser.diagnose("I found two issues in this section."),
        )
        // An opened-but-never-closed object is the token budget's signature.
        assertEquals(
            "the model's JSON was cut off before it closed",
            AuditAnalysisParser.diagnose("""FINDINGS ok JSON {"facts":["the log was"""),
        )
    }

    @Test
    fun `parse failure reasons survive a codec round-trip`() {
        val chunk = AuditAnalysis(
            parseFailed = true,
            parseError = "the model's JSON was cut off before it closed",
        )
        val decodedChunk = AuditResultCodec.decode(AuditResultCodec.encode(chunk))!!
        assertTrue(decodedChunk.parseFailed)
        assertEquals(chunk.parseError, decodedChunk.parseError)

        val final = AuditAnalysis(
            summary = "s",
            unanalysedSections = 1,
            unanalysedReasons = listOf("Section 2: the model returned an empty reply."),
        )
        val decodedFinal = AuditResultCodec.decode(AuditResultCodec.encode(final))!!
        assertEquals(final.unanalysedReasons, decodedFinal.unanalysedReasons)
    }
}
