package com.example.aiagenttestapp.data.audit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuditChunkerTest {

    @Test
    fun `text that already fits is a single chunk`() {
        val chunks = AuditChunker.chunk("short transcript", maxChars = 1000)
        assertEquals(listOf("short transcript"), chunks)
    }

    @Test
    fun `blank text yields no chunks`() {
        assertTrue(AuditChunker.chunk("   \n  ", maxChars = 100).isEmpty())
    }

    @Test
    fun `long text is split into multiple chunks, each within the limit`() {
        val text = (1..200).joinToString("\n") { "line $it with some audit content" }
        val chunks = AuditChunker.chunk(text, maxChars = 400, overlapChars = 40)

        assertTrue("expected several chunks, got ${chunks.size}", chunks.size > 1)
        // The limit is a soft target broken on boundaries; allow a little slack for the boundary + trim.
        assertTrue(chunks.all { it.length <= 400 })
        // Every original line survives in at least one chunk.
        assertTrue(chunks.any { it.contains("line 1 ") })
        assertTrue(chunks.any { it.contains("line 200 ") })
    }

    @Test
    fun `chunks overlap so a boundary line is not lost`() {
        val text = (1..60).joinToString("\n") { "sentence number $it here" }
        val chunks = AuditChunker.chunk(text, maxChars = 300, overlapChars = 60)
        // Consecutive chunks should share some trailing/leading text (overlap > 0).
        val joined = chunks.joinToString("\n")
        assertTrue(chunks.size >= 2)
        assertTrue(joined.contains("sentence number 30"))
    }

    @Test
    fun `chunk budget scales with the context window and is floored`() {
        val small = AuditChunker.chunkCharBudget(contextTokens = 4096, promptTokens = 1500)
        val large = AuditChunker.chunkCharBudget(contextTokens = 131072, promptTokens = 1500)

        // A larger context window must buy a proportionally larger chunk (the whole point).
        assertTrue("large ($large) should exceed small ($small)", large > small)
        // A tiny or over-reserved context still yields a usable, positive budget -- never zero.
        assertTrue(AuditChunker.chunkCharBudget(contextTokens = 512, promptTokens = 2000) > 0)
    }

    @Test
    fun `a chunk plus its prompt and reply reserve fit the context window`() {
        // The property that matters: nothing the budget hands out can overflow the window it was
        // sized against. Checked across the catalog's real context sizes.
        listOf(4096, 8192, 32768, 131072).forEach { context ->
            val prompt = 1800
            val chunkTokens = AuditChunker.chunkCharBudget(context, prompt) / 3.5
            val total = prompt + AuditChunker.outputReserveTokens(context, prompt) + chunkTokens
            assertTrue("$context: turn of $total tokens overflows the window", total <= context)
        }
    }

    @Test
    fun `the reply gets as much room as the chunk it answers`() {
        // The measured reply/chunk ratio for this pipeline is ~0.92 (draft + JSON + reasoning), so
        // a chunk must never be sized larger than the reply it will produce has room for. A 1:4
        // split shipped once and cost a whole document's findings to context overflow.
        listOf(4096, 8192, 32768, 131072).forEach { context ->
            val prompt = 1800
            val reserve = AuditChunker.outputReserveTokens(context, prompt)
            val chunkTokens = AuditChunker.chunkCharBudget(context, prompt) / 3.5
            assertTrue("$context: reserve $reserve cannot hold a reply to $chunkTokens", reserve >= chunkTokens)
        }
        // An over-reserved window never yields a negative or zero reserve.
        assertEquals(
            AuditChunker.MIN_OUTPUT_RESERVE_TOKENS,
            AuditChunker.outputReserveTokens(contextTokens = 512, promptTokens = 2000),
        )
    }

    @Test
    fun `a section is never sized larger than its reply has room for`() {
        // This replaces a pin on an absolute section size (1000-1300 tokens on a 4K model), which
        // was set from a single measurement and has since been deliberately moved: a second model
        // needed twice its section length to answer, and a half split cut it off mid-object. The
        // durable property is the relationship, not the number -- whatever the split, the reply must
        // have room for a reply this pipeline actually produces.
        listOf(4096, 8192, 32768).forEach { context ->
            val prompt = 1857
            val sectionTokens = AuditChunker.chunkCharBudget(context, prompt) / 3.5
            val reserve = AuditChunker.outputReserveTokens(context, prompt)
            assertTrue(
                "$context: reserve $reserve cannot hold 1.5x a $sectionTokens-token section",
                reserve >= sectionTokens * 1.5,
            )
        }
    }

    @Test
    fun `a context too small for the prompt still yields a floor-sized chunk`() {
        // Nothing refuses such a model up front any more, so this path is reachable: the window
        // cannot hold the preamble plus a reply, and the budget must still be a usable number
        // rather than zero or negative.
        val chunkTokens = AuditChunker.chunkCharBudget(1024, promptTokens = 1800) / 3.5

        assertTrue("chunk was $chunkTokens tokens", chunkTokens >= AuditChunker.MIN_CHUNK_TOKENS)
    }

    @Test
    fun `mergeFindings de-duplicates by normalised title and unions standards`() {
        val a = listOf(
            AuditFinding("Extinguisher expired", "seen in reception", listOf("ISO 45001")),
            AuditFinding("Exit blocked"),
        )
        val b = listOf(
            AuditFinding("  extinguisher   EXPIRED ", "also in warehouse", listOf("ISO 45001 §8.1")),
            AuditFinding("Missing PPE"),
        )

        val merged = AuditChunker.mergeFindings(listOf(a, b))

        assertEquals(3, merged.size)
        val extinguisher = merged.first { it.title == "Extinguisher expired" }
        assertEquals(listOf("ISO 45001", "ISO 45001 §8.1"), extinguisher.standards)
        assertEquals("seen in reception", extinguisher.detail) // first non-blank detail kept
        assertTrue(merged.any { it.title == "Exit blocked" })
        assertTrue(merged.any { it.title == "Missing PPE" })
    }

    @Test
    fun `prefers a speaker turn over a mid-line cut`() {
        // A transcript's real unit is the speaker turn: cutting there keeps one person's statement --
        // and any evidence quote inside it -- whole, which a mid-sentence cut would split across two
        // chunks and make unquotable in either.
        val text = buildString {
            append("Auditor: ")
            append("a".repeat(300))
            appendLine()
            append("Site Manager: ")
            append("b".repeat(300))
            appendLine()
            append("Auditor: ")
            append("c".repeat(300))
        }

        val chunks = AuditChunker.chunk(text, maxChars = 500, overlapChars = 0)

        // Every chunk after the first begins at a speaker label, never mid-utterance.
        chunks.drop(1).forEach { chunk ->
            assertTrue("chunk started mid-turn: ${chunk.take(30)}", chunk.startsWith("Auditor:") ||
                chunk.startsWith("Site Manager:"))
        }
    }

    @Test
    fun `mergeFindings carries the evidence quote across a matched finding`() {
        val a = listOf(AuditFinding("Gauge uncalibrated"))
        val b = listOf(AuditFinding("gauge uncalibrated", evidence = "the gauge was not calibrated"))

        val merged = AuditChunker.mergeFindings(listOf(a, b))

        assertEquals(1, merged.size)
        assertEquals("the gauge was not calibrated", merged.single().evidence)
    }

    @Test
    fun `mergeFindings keeps the more severe grade for a matched finding`() {
        val a = listOf(AuditFinding("Uncalibrated gauge", severity = AuditSeverity.MINOR))
        val b = listOf(AuditFinding("uncalibrated gauge", severity = AuditSeverity.MAJOR))

        val merged = AuditChunker.mergeFindings(listOf(a, b))

        assertEquals(1, merged.size)
        assertEquals(AuditSeverity.MAJOR, merged.single().severity)
    }

    @Test
    fun `mergeFindings folds differently worded mentions of one issue into one`() {
        // The observed failure: a dialogue transcript restated one missing record three ways and
        // the exact-title merge shipped all three. These are the real titles it produced.
        val a = listOf(AuditFinding("Missing formal residual risk acceptance record"))
        val b = listOf(
            AuditFinding("Missing formal electronic residual risk acceptance record"),
            AuditFinding("Missing approval record for residual risk", standards = listOf("ISO/IEC 27001:2022 Clause 6.1")),
        )

        val merged = AuditChunker.mergeFindings(listOf(a, b))

        assertEquals(1, merged.size)
        // The merge must not lose what only one wording carried.
        assertEquals(listOf("ISO/IEC 27001:2022 Clause 6.1"), merged.single().standards)
    }

    @Test
    fun `mergeFindings never merges findings that differ by a number`() {
        val a = listOf(AuditFinding("Extinguisher 12 inspection overdue"))
        val b = listOf(AuditFinding("Extinguisher 14 inspection overdue"))

        assertEquals(2, AuditChunker.mergeFindings(listOf(a, b)).size)
    }

    @Test
    fun `mergeFindings merges two wordings that quote the same sentence`() {
        // Titles share almost nothing, but both verified quotes come from the same sentence --
        // the strongest available signal that they report the same finding.
        val a = listOf(
            AuditFinding("Acceptance record missing", evidence = "the electronic approval is missing"),
        )
        val b = listOf(
            AuditFinding("No approval was recorded", evidence = "electronic approval is missing"),
        )

        assertEquals(1, AuditChunker.mergeFindings(listOf(a, b)).size)
    }

    @Test
    fun `the cap reports what it left behind instead of dropping it silently`() {
        val text = (1..400).joinToString("\n") { "line $it with some audit content here" }
        val plan = AuditChunker.plan(text, maxChars = 400, maxChunks = 3)

        assertEquals(3, plan.chunks.size)
        assertTrue("a capped document must report its dropped tail", plan.isTruncated)
        // The number is the actual remainder, not a guess: chunks overlap, so coverage is where the
        // last kept chunk ends.
        val covered = text.indexOf(plan.chunks.last()) + plan.chunks.last().length
        assertEquals(text.trim().length - covered, plan.droppedChars)
    }

    @Test
    fun `a document that fits the cap reports no loss`() {
        val plan = AuditChunker.plan("short transcript", maxChars = 1000, maxChunks = 80)

        assertEquals(listOf("short transcript"), plan.chunks)
        assertFalse(plan.isTruncated)
        assertEquals(0, plan.droppedChars)
    }

    @Test
    fun `prose advances predictably rather than varying two to one`() {
        // A line or paragraph break may pull a chunk back by at most a fifth of the window. Before
        // that floor, a break anywhere past the halfway mark was accepted, so the same document
        // could need twice the sections depending only on where its line breaks fell. (A speaker
        // turn still gets the looser floor -- see the speaker-turn test -- because cutting one in
        // half costs evidence quotes, which matters more than a predictable section count.)
        val text = (1..300).joinToString("\n") { "sentence number $it in the transcript" }
        val limit = 600
        val chunks = AuditChunker.chunk(text, maxChars = limit, overlapChars = 0)

        chunks.dropLast(1).forEach { chunk ->
            assertTrue("chunk was only ${chunk.length} of $limit", chunk.length >= limit * 4 / 5 - 40)
        }
    }

    @Test
    fun `two findings quoting different sentences need a closer title match to merge`() {
        // The known false-merge shape: same defect word, different subject noun, Jaccard 0.6. With a
        // verified quote each, from different sentences, these must stay apart.
        val a = listOf(
            AuditFinding("forklift daily check missed", evidence = "the forklift check was not done"),
        )
        val b = listOf(
            AuditFinding("crane daily check missed", evidence = "the crane check was not done"),
        )

        assertEquals(2, AuditChunker.mergeFindings(listOf(a, b)).size)
    }

    @Test
    fun `differing quotes do not block a merge when the titles really do match`() {
        val a = listOf(
            AuditFinding("Calibration certificate not signed", evidence = "the certificate is unsigned"),
        )
        val b = listOf(
            AuditFinding("calibration certificate not signed", evidence = "nobody signed the certificate"),
        )

        assertEquals(1, AuditChunker.mergeFindings(listOf(a, b)).size)
    }

    @Test
    fun `a bigger window buys bigger sections, with no ceiling of its own`() {
        // The audit used to clamp itself to 8192 however much the model offered. It does not any
        // more, so a large window has to keep producing larger sections all the way up -- that is
        // the whole point of removing the clamp: fewer sections, and each one is minutes of work.
        val prompt = 1854
        val at8k = AuditChunker.chunkCharBudget(8192, prompt)
        val at32k = AuditChunker.chunkCharBudget(32768, prompt)
        val at128k = AuditChunker.chunkCharBudget(131072, prompt)

        assertTrue("32k section $at32k was not bigger than 8k's $at8k", at32k > at8k)
        assertTrue("128k section $at128k was not bigger than 32k's $at32k", at128k > at32k)
    }

    @Test
    fun `the audit window leaves a workable section and a matching reply`() {
        val prompt = 1854
        val context = 8192
        val chunkTokens = AuditChunker.chunkCharBudget(context, prompt) / 3.5
        val reserve = AuditChunker.outputReserveTokens(context, prompt)

        // Sections big enough to be worth a turn, and a reply that can answer them in full. The
        // floor moved down when the reply was given two thirds of the window rather than half --
        // fewer characters per section, but sections that finish.
        assertTrue("section was $chunkTokens tokens", chunkTokens > 1800)
        assertTrue("reserve $reserve cannot answer $chunkTokens", reserve >= chunkTokens)
        // The preamble's share of the window stays reasonable: at 4K it was 45%, which is what made
        // small-context models split documents into twice the sections they needed.
        assertTrue("preamble is ${100 * prompt / context}% of the window", prompt * 100 / context < 30)
    }

    /**
     * A model answering "none" under an empty heading was being carried through as an item, so a
     * report with nothing unresolved rendered "Unresolved items (1): None". Affects the audit's quick
     * and detailed paths and a voice note's quick summary, all three of which merge through here.
     */
    @Test
    fun `an empty answer is not an unresolved item`() {
        assertTrue(AuditChunker.mergeStrings(listOf(listOf("None"))).isEmpty())
        assertTrue(AuditChunker.mergeStrings(listOf(listOf("none."))).isEmpty())
        assertTrue(AuditChunker.mergeStrings(listOf(listOf("N/A"))).isEmpty())
        assertTrue(AuditChunker.mergeStrings(listOf(listOf("keine"))).isEmpty())
        assertTrue(AuditChunker.mergeStrings(listOf(listOf("Nothing"))).isEmpty())
    }

    @Test
    fun `a real unresolved item that merely mentions none survives`() {
        val kept = AuditChunker.mergeStrings(
            listOf(listOf("None of the three deviation records were produced")),
        )
        assertEquals(1, kept.size)
    }

    @Test
    fun `an empty answer in one section does not hide a real item in another`() {
        val kept = AuditChunker.mergeStrings(
            listOf(listOf("None"), listOf("The approval record was never produced")),
        )
        assertEquals(listOf("The approval record was never produced"), kept)
    }
}
