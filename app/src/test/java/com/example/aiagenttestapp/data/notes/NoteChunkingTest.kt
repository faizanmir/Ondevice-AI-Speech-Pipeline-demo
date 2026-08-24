package com.example.aiagenttestapp.data.notes

import com.example.aiagenttestapp.prompts.NotePromptBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteChunkingTest {

    /** Quick sizes through the shared audit budget, and must fit more text than the note's own. */
    @Test
    fun `the shared quick read fits more text per section than a detailed note`() {
        val transcript = "The chamber is holding at two degrees. ".repeat(400)
        val detailed = NoteChunking.plan(transcript, contextTokens = 4096, promptTokens = 400)
        val quick = com.example.aiagenttestapp.data.audit.QuickRead.plan(
            text = transcript,
            contextTokens = 4096,
            maxChunks = NoteChunking.MAX_CHUNKS,
        )
        assertTrue(
            "quick should need no more sections than detailed",
            quick.chunks.size <= detailed.chunks.size,
        )
    }

    @Test
    fun `a short note is read in one section`() {
        val plan = NoteChunking.plan("A short note about a broken seal.", 4096, 400)
        assertEquals(1, plan.chunks.size)
        assertFalse(plan.isTruncated)
    }

    /**
     * The regression this whole file exists for. A 22-minute inspection note transcribes to ~17,800
     * characters, which does not fit a 4,096-token window and used to be sent as one prompt anyway.
     */
    @Test
    fun `a twenty minute note is split rather than sent whole`() {
        val transcript = ("The chamber is holding at two degrees. " +
            "Stock rotation is first in first out. ").repeat(230)
        assertTrue("fixture should be about a 20-minute note", transcript.length > 17_000)

        val plan = NoteChunking.plan(transcript, contextTokens = 4096, promptTokens = 400)

        assertTrue("should need several sections", plan.chunks.size > 1)
        assertFalse("well under the cap", plan.isTruncated)
        plan.chunks.forEach {
            assertTrue("no section may exceed its own budget", it.length <= 8_192)
        }
    }

    /** Sections overlap, so every character of the note reaches at least one of them. */
    @Test
    fun `sections cover the whole transcript`() {
        val transcript = (1..400).joinToString("\n") { "Line $it of the walkthrough." }
        val plan = NoteChunking.plan(transcript, contextTokens = 4096, promptTokens = 400)

        val covered = plan.chunks.joinToString(" ")
        (1..400).forEach { assertTrue("line $it missing", covered.contains("Line $it of")) }
    }

    /** A cap that ate the tail silently is the failure droppedChars exists to prevent. */
    @Test
    fun `hitting the section cap reports what was left unread`() {
        val transcript = (1..2000).joinToString("\n") { "Line $it of a very long walkthrough." }
        val plan = NoteChunking.plan(transcript, 4096, 400, maxChunks = 2)

        assertEquals(2, plan.chunks.size)
        assertTrue(plan.isTruncated)
        assertTrue(plan.droppedChars > 0)
    }

    /**
     * A window too small to hold the preamble and a reply still makes progress rather than
     * returning nothing, matching AuditChunker's load-bearing floor.
     */
    @Test
    fun `a tiny context still produces sections`() {
        val plan = NoteChunking.plan("word ".repeat(500), contextTokens = 512, promptTokens = 400)
        assertTrue(plan.chunks.isNotEmpty())
    }

    /** The reserve is what stops a reply running off the end of the window. */
    @Test
    fun `the output reserve never falls below the floor`() {
        val reserve = NoteChunking.outputReserveTokens(contextTokens = 600, promptTokens = 590)
        assertEquals(NoteChunking.MIN_OUTPUT_RESERVE_TOKENS, reserve)
    }

    /**
     * The budget is measured from the real prompt builder, so a prompt that grows must shrink the
     * space left for transcript. If this ever stops holding, the two have been allowed to drift.
     */
    @Test
    fun `the prompt budget is measured from the real prompt`() {
        val bare = NotePromptBudget.fixedPromptTokens()
        val withTags = NotePromptBudget.fixedPromptTokens(
            tagged = listOf(
                TaggedItem(
                    kind = com.example.aiagenttestapp.functions.MarkerKind.NonConformity,
                    text = "The door seal on chamber four is split along its lower edge.",
                ),
            ),
        )
        assertTrue("tagged items cost prompt tokens", withTags > bare)
        assertTrue("a bare prompt is not free", bare > 0)
    }

    /**
     * A German or Hindi note packs fewer characters into a token, so its sections must be shorter in
     * characters to hold the same number of tokens. Sizing every language as Latin is how a section
     * overflows the window it was sized for.
     */
    @Test
    fun `a denser script gets shorter sections`() {
        val latin = "The chamber is holding at two degrees Celsius. ".repeat(120)
        val cjk = "冷蔵室は摂氏二度を保っています。".repeat(120)

        val latinPlan = NoteChunking.plan(latin, 4096, 400)
        val cjkPlan = NoteChunking.plan(cjk, 4096, 400)

        assertTrue(
            "a CJK section must hold fewer characters than a Latin one",
            cjkPlan.chunks.first().length < latinPlan.chunks.first().length,
        )
    }
}
