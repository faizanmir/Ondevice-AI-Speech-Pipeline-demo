package com.example.aiagenttestapp.prompts

import com.example.aiagenttestapp.prompts.audit.AuditExtractionPrompts
import com.example.aiagenttestapp.prompts.audit.AuditPromptBudget
import com.example.aiagenttestapp.prompts.audit.AuditSummaryPrompts
import com.example.aiagenttestapp.prompts.audit.AuditSystemPrompts
import com.example.aiagent.engine.core.ContextWindow
import com.example.aiagenttestapp.data.audit.AuditAnalysisParser
import com.example.aiagenttestapp.data.audit.AuditChunker
import com.example.aiagenttestapp.data.audit.AuditFinding
import com.example.aiagenttestapp.data.audit.AuditMode
import com.example.aiagenttestapp.data.audit.AuditOutputFormat
import com.example.aiagenttestapp.data.audit.AuditPromptProfile
import com.example.aiagenttestapp.data.audit.AuditProtocolVocabulary
import com.example.aiagenttestapp.data.audit.AuditRecordParser
import com.example.aiagenttestapp.data.audit.AuditResultType
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
        //
        // The threshold drops as SHARED instruction grows, and that is arithmetic rather than
        // regression: the profiles differ only in worked examples, so every line added to both
        // shrinks the examples' share of the whole. It fell from 15% to 14% when the rule telling an
        // element's grade from a finding's went in. Lower it for that reason and no other -- a fall
        // caused by examples being trimmed is the failure this test is here to catch.
        val saved = 1 - lean.length.toDouble() / rich.length
        assertTrue("LEAN saves only ${(saved * 100).toInt()}% of RICH", saved > 0.13)
    }

    @Test
    fun `neither preamble grows back past its measured ceiling`() {
        // The preamble is charged against the window of every chunk, so growth here is paid for in
        // transcript the model never sees. These ceilings are the current sizes plus a little room;
        // moving one is a decision to make deliberately, not by accumulation.
        //
        // Raised once, deliberately, for both: they took the resultType field and its two rules,
        // which is
        // what lets classification happen here instead of in a separate grading turn per finding.
        // A preamble paid once per chunk is cheaper than a turn paid once per finding, and a
        // verdict decided where the evidence is cannot be softened by a second opinion later.
        //
        // Lowered once since, by a pass that took out restated instruction and the format block's
        // decorative blank lines -- 71 tokens, which is what a prompt this dense had left to give.
        // Everything larger that was considered (the cue-word list, the worked example, the schema
        // the example fills in) decides what a finding IS, and is not a saving, it is a different
        // audit.
        //
        // Then raised, by more than the trim ever saved: +616 on RICH and +562 on LEAN for the
        // protocol -- the elements, what was merely stated, what was left unresolved, and the
        // action fields. That is what the capability costs, and it is worth recording plainly:
        // roughly a third of it is the instruction, the rest is the shape it has to be answered in
        // and example C demonstrating it. Chunks shrink accordingly, so this bought fewer sections
        // of transcript per window; see AuditChunker.chunkCharBudget for where that lands.
        //
        // Raised once more, by ~60 tokens, for the four lines that separate an element's grade from
        // a finding's. Bought with a real defect: one document read twice graded the same missing
        // signature minorNonconformity and resultOkForDocumentation, because the preamble supported
        // both readings and nothing chose between them.
        //
        // And again, ~65 tokens, to name each passing grade the rule forbids on a non-conformity
        // and to say the verdict is the document's own words. Both bought with observed defects:
        // findings badged "Improvement", and a "Stated result" holding a vocabulary name the
        // document never used.
        assertTrue("RICH is ${ContextWindow.estimateTokens(rich)} tok", ContextWindow.estimateTokens(rich) < 2650)
        assertTrue("LEAN is ${ContextWindow.estimateTokens(lean)} tok", ContextWindow.estimateTokens(lean) < 2270)
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
            "if the text names none, leave it out",
        ).forEach { line ->
            assertTrue("RICH is missing: $line", rich.contains(line))
            assertTrue("LEAN is missing: $line", lean.contains(line))
        }
    }

    @Test
    fun `both profiles ask for the protocol, not just the failures`() {
        // The point of the protocol half is that an audit reaches a conclusion about a requirement,
        // pass or fail, not just a list of problems. A profile that asked only for findings would
        // produce a report whose Protocol Element section is empty -- which reads as "nothing was
        // examined". The one-per-section wording matters too: the report shows exactly one, and a
        // model asked for several would have the extras collapsed away.
        listOf(
            "Also recognise the PROTOCOL ELEMENT this section audits",
            "whether or not that conclusion is a failure",
            "One element per section",
            "STATED",
            "UNRESOLVED",
        ).forEach { line ->
            assertTrue("RICH is missing: $line", rich.contains(line))
            assertTrue("LEAN is missing: $line", lean.contains(line))
        }
    }

    @Test
    fun `every vocabulary the prompt offers is one the parser reads back`() {
        // The prompt lists the element types, priorities, statuses and result names it will accept.
        // A word offered here that AuditProtocolVocabulary does not know comes back as an
        // unrecognised value -- silently, as a blank field in the finished report -- so the prompt
        // is built from those lists rather than typed out beside them. This is that wiring's guard.
        (
            AuditProtocolVocabulary.ELEMENT_TYPES +
                AuditProtocolVocabulary.ACTION_PRIORITIES +
                AuditProtocolVocabulary.ACTION_STATUSES +
                AuditResultType.entries.map { it.wireName }
            ).forEach { word ->
            assertTrue("RICH never offers: $word", rich.contains(word))
        }
    }

    @Test
    fun `the worked examples answer in blocks the record parser can read`() {
        // An example is the shape a model copies, so an example the parser cannot read is a section
        // lost on every document. Parsing RICH's own example C proves the two agree.
        val reply = rich.substringAfter("Worked example C").substringBefore("Now analyse")
        val parsed = AuditRecordParser.parse(reply)

        assertEquals(1, parsed.protocolElements.size)
        assertEquals(AuditResultType.OK_FOR_DOCUMENTATION, parsed.protocolElements.single().result)
        assertEquals("Auditor", parsed.protocolElements.single().speaker)
        assertEquals(1, parsed.nonConformities.size)
        assertEquals(2, parsed.actions.size)
        assertEquals(1, parsed.alsoStated.size)
        assertEquals(1, parsed.unresolvedItems.size)
        // The action fields the screenshot's report renders, demonstrated and read back.
        assertEquals("Agreed", parsed.actions.first().status)
        assertEquals(true, parsed.actions.first().accepted)
        // Priority is deliberately absent from the example: the dialogue states none, and a field
        // invented to fill the shape is what every other rule in the preamble exists to prevent.
        assertEquals("", parsed.actions.first().priority)
    }

    @Test
    fun `a non-conformity is never graded as a pass`() {
        // One file, two runs, the same missing signature graded minorNonconformity once and
        // resultOkForDocumentation the other. The prompt supported both: the cue list calls "not
        // signed" a non-conformity, the result rule calls weak approval OK for documentation, and
        // example C graded the FINDING with the ELEMENT's conclusion -- so a model could take
        // either, and temperature decided which.
        //
        // Read through the parser rather than by regex, so this asserts what a model copying the
        // example would actually produce. Also a type-level contradiction the old example carried:
        // isNonconformity is false for both passing grades, so a NONCONFORMITY block graded with one
        // says it is not the thing it is.
        val reply = rich.substringAfter("Worked example C").substringBefore("Now analyse")
        val parsed = AuditRecordParser.parse(reply)

        val element = parsed.protocolElements.single()
        val finding = parsed.nonConformities.single()
        // The element judges the requirement as a whole: the calibration happened, so it passes on
        // substance and fails only on paperwork.
        assertEquals(AuditResultType.OK_FOR_DOCUMENTATION, element.result)
        // The finding names the gap, and a gap is a non-conformity whatever the element concluded.
        assertTrue(
            "example grades its non-conformity ${finding.resultType?.wireName}",
            finding.resultType?.isNonconformity == true,
        )
    }

    @Test
    fun `both profiles say how an element and a finding differ`() {
        // The rule the examples now demonstrate, stated as well as shown -- one worked example is a
        // prior, not an instruction, and this is the pair a small model most often confuses.
        // Every passing grade named one by one, not "as OK": the rule said that, and a model
        // graded non-conformities resultPotentialImprovement instead -- not OK, and equally not a
        // non-conformity. A prohibition has to name what it prohibits.
        listOf(
            "graded separately",
            "ONLY ever minorNonconformity or",
            "Never resultOkForDocumentation or",
            "no name for a plain pass",
            "verdict is the document's OWN words",
        ).forEach {
            assertTrue("RICH is missing: $it", rich.contains(it))
            assertTrue("LEAN is missing: $it", lean.contains(it))
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
    fun `an extraction turn fits the smallest context that can hold one`() {
        // Nothing refuses a small window up front any more, so the guarantee this test protects is
        // narrower than it was: whenever a window CAN hold the preamble, a reply and a floor-sized
        // chunk, the chunk sizing must not push the turn back over it. Below that the floor takes
        // over and a turn deliberately overflows -- see AuditChunker.chunkCharBudget.
        val promptTokens = AuditPromptBudget.fixedPromptTokens()
        val smallest = promptTokens +
            AuditChunker.MIN_OUTPUT_RESERVE_TOKENS +
            AuditChunker.MIN_CHUNK_TOKENS

        val chunkChars = AuditChunker.chunkCharBudget(smallest, promptTokens)
        val turn = promptTokens +
            AuditChunker.outputReserveTokens(smallest, promptTokens) +
            ContextWindow.estimateTokens("x".repeat(chunkChars))

        assertTrue("a turn of $turn tokens does not fit $smallest", turn <= smallest)
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
