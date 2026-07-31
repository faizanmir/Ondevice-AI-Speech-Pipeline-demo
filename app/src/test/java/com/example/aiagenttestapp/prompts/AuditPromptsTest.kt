package com.example.aiagenttestapp.prompts

import com.example.aiagenttestapp.prompts.audit.AuditExtractionPrompts
import com.example.aiagenttestapp.prompts.audit.AuditPromptBudget
import com.example.aiagenttestapp.prompts.audit.AuditSeverityPrompts
import com.example.aiagenttestapp.prompts.audit.AuditSummaryPrompts
import com.example.aiagenttestapp.prompts.audit.AuditSystemPrompts
import com.example.aiagent.engine.core.ContextWindow
import com.example.aiagenttestapp.data.audit.AuditAnalysisParser
import com.example.aiagenttestapp.data.audit.AuditChunker
import com.example.aiagenttestapp.data.audit.AuditFinding
import com.example.aiagenttestapp.data.audit.AuditMode
import com.example.aiagenttestapp.data.audit.AuditOutputFormat
import com.example.aiagenttestapp.data.audit.AuditPromptProfile
import com.example.aiagenttestapp.data.audit.AuditSeverity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the two things about these prompts that are easy to break silently: the instructions that
 * decide what counts as a finding must be identical in both profiles, and the prompts must fit the
 * context windows the pipeline sizes itself against.
 */
class AuditPromptsTest {

    private val rich = AuditExtractionPrompts.preamble(AuditPromptProfile.RICH)
    private val lean = AuditExtractionPrompts.preamble(AuditPromptProfile.LEAN)

    @Test
    fun `the lean profile is materially smaller than the rich one`() {
        // The point of LEAN is to cost less on engines that re-prefill it per chunk. It once
        // differed by 4% -- two worked examples' "detail" fields -- which bought nothing for the
        // complexity of carrying two profiles. Dropping two of the three examples takes it to ~20%,
        // and that is the ceiling while the instructions stay byte-identical (they are ~55% of the
        // preamble and are what decides whether a finding is found at all).
        val saved = 1 - lean.length.toDouble() / rich.length
        assertTrue("LEAN saves only ${(saved * 100).toInt()}% of RICH", saved > 0.15)
    }

    @Test
    fun `neither preamble grows back past its measured ceiling`() {
        // The preamble is charged against the window of every chunk, so growth here is paid for in
        // transcript the model never sees. These ceilings are the current sizes plus a little room;
        // moving one is a decision to make deliberately, not by accumulation.
        assertTrue("RICH is ${ContextWindow.estimateTokens(rich)} tok", ContextWindow.estimateTokens(rich) < 1850)
        assertTrue("LEAN is ${ContextWindow.estimateTokens(lean)} tok", ContextWindow.estimateTokens(lean) < 1500)
    }

    @Test
    fun `both profiles carry the same definition of a finding`() {
        // These lines decide WHAT counts, not how it is formatted. A profile that trimmed them would
        // let the same transcript produce different findings on two builds.
        listOf(
            "not calibrated, not trained, out of date, out of specification, not followed",
            "Count issues, not mentions",
            "Also find EVERY action",
            "Every non-conformity must include",
            "Never invent one; use [] if none is named",
        ).forEach { line ->
            assertTrue("RICH is missing: $line", rich.contains(line))
            assertTrue("LEAN is missing: $line", lean.contains(line))
        }
    }

    @Test
    fun `the lean profile still states the range the dropped examples demonstrated`() {
        // LEAN carries one worked example, which on its own would set a prior of one finding per
        // section. These assertions stand in for examples A and B.
        assertTrue(lean.contains("may hold many issues or none"))
        assertTrue(lean.contains("that is a correct answer"))
        assertTrue(lean.contains("always two items"))
    }

    @Test
    fun `every worked example quotes evidence that appears in its own text`() {
        // The pipeline drops a quote it cannot find in the source (AuditEvidence). An example whose
        // quote fails that check teaches the model to produce findings the app will then discard.
        listOf(
            "last inspected 14 months ago; the annual check was missed",
            "Two new staff had not completed the induction training",
            "the certificate was never signed off by the quality manager",
        ).forEach { quote ->
            // Twice: once in the example's text, once in the example's JSON.
            assertTrue("not quotable from its own example: $quote", rich.split(quote).size >= 3)
        }
    }

    @Test
    fun `a document that fits is passed through unchanged`() {
        val facts = listOf(listOf("Calibration log signed 3 March."), listOf("Line ran at 22 units."))
        val prompt = AuditSummaryPrompts.finalSummary(facts, maxNoteChars = 10_000)

        facts.flatten().forEach { assertTrue(prompt.contains(it)) }
    }

    @Test
    fun `oversized notes are trimmed to fit, and every section still appears`() {
        // 80 sections is MAX_CHUNKS: the worst case the reduce prompt can actually be handed.
        val facts = (1..80).map { section -> (1..10).map { "Section $section fact $it: ${"x".repeat(60)}" } }
        val budget = 4_000
        val prompt = AuditSummaryPrompts.finalSummary(facts, maxNoteChars = budget)

        val notes = notes(prompt)
        assertTrue("notes were ${notes.length} chars against a $budget budget", notes.length <= budget)
        // Trimmed to an even share rather than cut at the tail: the last section is as present as
        // the first, so the summary still spans the document.
        assertTrue(notes.contains("Section 1 fact 1"))
        assertTrue(notes.contains("Section 80 fact 1"))
    }

    @Test
    fun `a section whose first fact outruns its share is truncated, never dropped`() {
        val facts = listOf(listOf("a".repeat(500)), listOf("b".repeat(500)))
        val prompt = AuditSummaryPrompts.finalSummary(facts, maxNoteChars = 100)

        // Both sections still speak -- and neither pushes the prompt back over the budget.
        assertTrue(prompt.contains("aaa"))
        assertTrue(prompt.contains("bbb"))
        assertTrue("notes were ${notes(prompt).length} chars against a 100 budget", notes(prompt).length <= 100)
    }

    @Test
    fun `an extraction turn fits the smallest context the planner admits`() {
        val promptTokens = AuditPromptBudget.fixedPromptTokens()
        val minimum = AuditChunker.minimumContextTokens(promptTokens)

        val chunkChars = AuditChunker.chunkCharBudget(minimum, promptTokens)
        val turn = promptTokens +
            AuditChunker.outputReserveTokens(minimum, promptTokens) +
            ContextWindow.estimateTokens("x".repeat(chunkChars))

        assertTrue("a turn of $turn tokens does not fit $minimum", turn <= minimum)
    }

    @Test
    fun `the summary note budget leaves room for the reply`() {
        val context = 4096
        val budget = AuditSummaryPrompts.summaryNoteBudget(context, verdict = "OK for documentation")
        val prompt = AuditSummaryPrompts.finalSummary(
            listOf(listOf("x".repeat(budget))),
            verdict = "OK for documentation",
            maxNoteChars = budget,
        )

        val used = ContextWindow.estimateTokens(AuditSystemPrompts.SYSTEM_PROMPT) +
            ContextWindow.estimateTokens(prompt)
        assertEquals(
            "summary turn leaves ${context - used} tokens for a reply",
            true,
            context - used >= AuditSummaryPrompts.SUMMARY_OUTPUT_RESERVE_TOKENS,
        )
    }

    /** Just the notes block, without the fence lines the budget does not cover. */
    private fun notes(prompt: String) =
        prompt.substringAfter("BEGIN NOTES -----\n").substringBefore("----- END NOTES")

    @Test
    fun `both fast grading prompts ask for one word and suppress thinking`() {
        val finding = AuditFinding("Calibration certificate not signed", evidence = "never signed off")
        val single = AuditSeverityPrompts.gradeSeverityFast(finding)
        val batch = AuditSeverityPrompts.gradeSeverityBatch(listOf(finding, AuditFinding("Exit blocked")))

        // The cap that makes these cheap is ~8 tokens; a model that thinks first would spend all of
        // it before reaching the word. Suppression is what makes the cap safe, not an optimisation.
        assertTrue(single.contains("/no_think"))
        assertTrue(batch.contains("/no_think"))
        assertTrue(single.contains("exactly one word"))
        assertTrue(single.contains("Calibration certificate not signed"))
        // The reasoned prompt is the fallback and must keep its reasoning.
        assertTrue(AuditSeverityPrompts.gradeSeverity(finding).contains("reasoning"))
    }

    @Test
    fun `a model echoing the options is not read as the last one`() {
        // The realistic failure of a one-word prompt: the model repeats the choices back. Last-wins
        // would silently grade this "observation" -- the mildest of the three.
        val echo = "major, minor, or observation"

        assertEquals(AuditSeverity.OBSERVATION, AuditSeverity.normalise(echo))
        assertEquals(AuditSeverity.MAJOR, AuditSeverity.normaliseFirst(echo))
    }

    @Test
    fun `a reasoned reply still resolves to its conclusion`() {
        // The mirror case, and why both rules exist: here the first grade word is one the model went
        // on to reject, so the reasoned prompt must keep reading to the end.
        val reasoned = "This is not major, an isolated lapse only. minor"

        assertEquals(AuditSeverity.MINOR, AuditSeverity.normalise(reasoned))
    }

    @Test
    fun `one-word batch replies parse back by index`() {
        val reply = "1: minor\n2: major\n3: observation"

        assertEquals(
            mapOf(0 to AuditSeverity.MINOR, 1 to AuditSeverity.MAJOR, 2 to AuditSeverity.OBSERVATION),
            AuditSeverity.parseBatch(reply),
        )
    }

    @Test
    fun `the output contract asks for the fields the profile demonstrates, in either format`() {
        // These disagreed once: LEAN dropped "detail" from its worked examples while the schema kept
        // asking for it, and the model followed the schema -- so the profile that exists to save
        // room spent it on prose, and dense sections were truncated mid-object. The check has to
        // hold for whichever format is in use, since both describe the same fields.
        AuditOutputFormat.entries.forEach { format ->
            val richPrompt = AuditExtractionPrompts.preamble(AuditPromptProfile.RICH, format)
            val leanPrompt = AuditExtractionPrompts.preamble(AuditPromptProfile.LEAN, format)
            val detail = if (format == AuditOutputFormat.RECORDS) "detail:" else """"detail":"..."""

            assertTrue("$format: RICH should ask for detail", richPrompt.contains(detail))
            assertTrue("$format: LEAN should not ask for detail", !leanPrompt.contains(detail))
            // Both profiles keep the fields a finding is actually judged on.
            listOf(richPrompt, leanPrompt).forEach {
                val quote = if (format == AuditOutputFormat.RECORDS) "quote:" else """"evidence":"..."""
                assertTrue("$format: missing the quote field", it.contains(quote))
                assertTrue("$format: missing standards", it.contains("standard"))
            }
        }
    }

    @Test
    fun `a malformed reply reports where the parser stopped`() {
        // An unescaped quotation mark inside a value: the reply closes its object, so nothing about
        // its shape says it is broken, and the tail of it looks perfectly well formed.
        val reply = """{"facts":[],"nonConformities":[{"title":"Log "gap" found","evidence":"x"}],"actions":[]}"""

        val detail = AuditAnalysisParser.parseFailureDetail(reply)

        assertTrue("expected a parser message, got $detail", detail != null && detail.contains("parser:"))
        // The offset localises it, which is the entire point -- the tail could not.
        assertTrue("expected the offending text, got $detail", detail!!.contains("gap"))
    }

    @Test
    fun `a reply that parses reports no parser error`() {
        val reply = """{"facts":["a"],"nonConformities":[],"actions":[]}"""

        assertEquals(null, AuditAnalysisParser.parseFailureDetail(reply))
    }

    @Test
    fun `a stray empty string where a key belongs is repaired, not rejected`() {
        // The real reply, reduced: the verdict's value, then a second empty string, then the next
        // key. JSON wants a colon after a key and finds a comma, so the whole object was rejected
        // and a section that had been extracted perfectly well was recorded as unreadable.
        val reply = """{"facts":["a fact"],"verdict":"","","nonConformities":""" +
            """[{"title":"Login not registered","evidence":"x"}],"actions":[]}"""

        val parsed = AuditAnalysisParser.parse(reply)

        assertTrue("reply should now parse", parsed != null)
        assertEquals(1, parsed!!.nonConformities.size)
        assertEquals("Login not registered", parsed.nonConformities.single().title)
        assertEquals(listOf("a fact"), parsed.facts)
    }

    @Test
    fun `an empty string inside an array is left alone`() {
        // The reason the repair has to be structural. Here "" is ordinary data, and a textual
        // find-and-replace would corrupt a reply that parses perfectly well.
        val reply = """{"facts":["","kept"],"nonConformities":[],"actions":[]}"""

        val parsed = AuditAnalysisParser.parse(reply)

        assertTrue(parsed != null)
        // The blank is dropped by the field reader, not by the repair -- what matters is that the
        // reply still parses and the real fact survives.
        assertEquals(listOf("kept"), parsed!!.facts)
    }

    @Test
    fun `a key with no value at all is repaired, not rejected`() {
        // The other real reply: the model was told to "leave it empty" and did so literally,
        // emitting a colon with nothing after it. Five sections in eight died this way -- more than
        // twice the rate of the wording it replaced, which is why the prompt went back.
        val reply = """{"facts":["a fact"],"verdict":,"nonConformities":""" +
            """[{"title":"No documented review","evidence":"x"}],"actions":[]}"""

        val parsed = AuditAnalysisParser.parse(reply)

        assertTrue("reply should now parse", parsed != null)
        assertEquals("", parsed!!.verdict)
        assertEquals(1, parsed.nonConformities.size)
        assertEquals(listOf("a fact"), parsed.facts)
    }

    @Test
    fun `dropping the draft removes it from the instructions and the examples`() {
        val drafted = AuditExtractionPrompts.preamble(AuditPromptProfile.RICH, AuditOutputFormat.RECORDS, draft = true)
        val direct = AuditExtractionPrompts.preamble(AuditPromptProfile.RICH, AuditOutputFormat.RECORDS, draft = false)

        assertTrue(drafted.contains("Answer in two steps"))
        assertTrue(drafted.contains("FINDINGS"))
        // Not just the instruction -- an example still showing a draft would teach one anyway.
        assertTrue("the examples still draft", !direct.contains("FINDINGS"))
        assertTrue(direct.contains("Answer with records only"))
        assertTrue("both must still ask for records", direct.contains("NONCONFORMITY"))
    }

    @Test
    fun `dropping the draft leaves the recall instructions untouched`() {
        // The draft is scratch space, not part of what counts as a finding. Removing it must not
        // quietly remove the cue words that decide what the model looks for.
        val direct = AuditExtractionPrompts.preamble(AuditPromptProfile.RICH, AuditOutputFormat.RECORDS, draft = false)

        listOf(
            "not calibrated, not trained, out of date, out of specification, not followed",
            "Count issues, not mentions",
            "Also find EVERY action",
            "Every non-conformity must include",
        ).forEach { assertTrue("draft-free prompt is missing: $it", direct.contains(it)) }
    }

    @Test
    fun `budgets are measured against the larger, drafting prompt`() {
        // Sections are sized before the accelerator is known, so the reserve must assume the bigger
        // prompt: a run that drops the draft then has more room than reserved, never less.
        val drafted = AuditExtractionPrompts.preamble(AuditPromptProfile.RICH, AuditOutputFormat.RECORDS, draft = true)
        val direct = AuditExtractionPrompts.preamble(AuditPromptProfile.RICH, AuditOutputFormat.RECORDS, draft = false)

        assertTrue(drafted.length > direct.length)
        assertTrue(
            "fixedPromptTokens must cover the drafting prompt",
            AuditPromptBudget.fixedPromptTokens(AuditPromptProfile.RICH) >=
                ContextWindow.estimateTokens(drafted),
        )
    }
}
