package com.example.aiagenttestapp.data.speakers

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Turning cluster-numbered blocks into the paragraphs a person actually reads.
 *
 * Alignment cuts a block wherever the *cluster* changes, which is not the same question as "did the
 * speaker change". One person legitimately arrives as several clusters, and naming quite correctly
 * puts one name on all of them -- so cutting on the id split one continuous sentence across several
 * blocks and the transcript said the auditor's question was asked by three different people, purely
 * as a numbering artefact.
 */
class SpeakerBlockNamingTest {

    private fun block(from: Int, to: Int, cluster: Int, text: String) =
        SpeakerBlock(startSample = from, endSample = to, cluster = cluster, text = text)

    private val unknown = "Unknown Speaker ?"

    @Test
    fun `consecutive blocks naming the same person become one`() {
        val blocks = listOf(
            block(0, 100, cluster = 12, text = "Well, as you know, we currently"),
            block(100, 200, cluster = 1, text = "are performing our audit"),
        )

        val named = nameBlocks(blocks, mapOf(12 to "Tim", 1 to "Tim"), unknown)

        assertEquals(1, named.size)
        assertEquals("Tim", named.single().name)
        assertEquals("Well, as you know, we currently are performing our audit", named.single().text)
        assertEquals(0, named.single().startSample)
        assertEquals(200, named.single().endSample)
    }

    @Test
    fun `a real change of speaker still splits`() {
        val blocks = listOf(
            block(0, 100, cluster = 12, text = "would you mind telling me"),
            block(100, 200, cluster = 13, text = "well I'll be honest"),
        )

        val named = nameBlocks(blocks, mapOf(12 to "Tim", 13 to "Bob"), unknown)

        assertEquals(listOf("Tim", "Bob"), named.map { it.name })
    }

    @Test
    fun `a gap between two turns of one person is absorbed rather than named a stranger`() {
        // "But" fell outside every turn. Both sides are Tim, so calling it a separate unknown speaker
        // invents a person and cuts the sentence in half.
        val blocks = listOf(
            block(0, 100, cluster = 1, text = "you've been told"),
            block(100, 120, cluster = SpeakerAlignment.UNATTRIBUTED, text = "But"),
            block(120, 200, cluster = 1, text = "that this major change is taken up"),
        )

        val named = nameBlocks(blocks, mapOf(1 to "Tim"), unknown)

        assertEquals(1, named.size)
        assertEquals("Tim", named.single().name)
        assertEquals("you've been told But that this major change is taken up", named.single().text)
    }

    @Test
    fun `a gap between two different people stays unattributed`() {
        // Honest: nothing here says which of them said it, and guessing would be a false record.
        val blocks = listOf(
            block(0, 100, cluster = 12, text = "so what changed"),
            block(100, 120, cluster = SpeakerAlignment.UNATTRIBUTED, text = "well"),
            block(120, 200, cluster = 13, text = "I could not tell you"),
        )

        val named = nameBlocks(blocks, mapOf(12 to "Tim", 13 to "Bob"), unknown)

        assertEquals(listOf("Tim", unknown, "Bob"), named.map { it.name })
    }

    @Test
    fun `an unattributed run at the very start has no one to inherit from`() {
        val blocks = listOf(
            block(0, 50, cluster = SpeakerAlignment.UNATTRIBUTED, text = "I'm"),
            block(50, 200, cluster = 12, text = "not really sure how I can help"),
        )

        val named = nameBlocks(blocks, mapOf(12 to "Tim"), unknown)

        assertEquals(listOf(unknown, "Tim"), named.map { it.name })
    }

    @Test
    fun `a cluster with no name keeps the unknown label and does not merge into a neighbour`() {
        val blocks = listOf(
            block(0, 100, cluster = 12, text = "one"),
            block(100, 200, cluster = 6, text = "two"),
        )

        val named = nameBlocks(blocks, mapOf(12 to "Tim"), unknown)

        assertEquals(listOf("Tim", unknown), named.map { it.name })
    }

    @Test
    fun `no blocks is not an error`() {
        assertEquals(emptyList<NamedBlock>(), nameBlocks(emptyList(), emptyMap(), unknown))
    }

    // ---- smoothing fragments too short to be evidence -------------------------------------------

    private fun named(from: Int, to: Int, name: String, text: String) =
        NamedBlock(startSample = from, endSample = to, cluster = 0, name = name, text = text)

    private val prefix = "Unknown Speaker"
    private val twoSeconds = 32_000

    @Test
    fun `a fragment flanked by one person on both sides becomes that person`() {
        val blocks = listOf(
            named(0, 100_000, "Bob", "not really sure how I can"),
            named(100_000, 116_000, "Tim", "help you here, but"),
            named(116_000, 200_000, "Bob", "feel free to ask me some questions"),
        )

        val out = smoothShortBlocks(blocks, prefix, twoSeconds)

        assertEquals(1, out.size)
        assertEquals("Bob", out.single().name)
        assertEquals("not really sure how I can help you here, but feel free to ask me some questions", out.single().text)
    }

    @Test
    fun `an unnamed fragment touching one identified person joins them`() {
        val blocks = listOf(
            named(0, 100_000, "Bob", "supervisor mentioned it"),
            named(100_000, 110_000, "Unknown Speaker ?", "My"),
        )

        val out = smoothShortBlocks(blocks, prefix, twoSeconds)

        assertEquals(listOf("Bob"), out.map { it.name })
    }

    @Test
    fun `a fragment between two different people is left alone`() {
        // The audio genuinely does not say who this was; guessing would be a false record.
        val blocks = listOf(
            named(0, 100_000, "Bob", "so what changed"),
            named(100_000, 110_000, "Unknown Speaker ?", "well"),
            named(110_000, 200_000, "Tim", "I could not tell you"),
        )

        val out = smoothShortBlocks(blocks, prefix, twoSeconds)

        assertEquals(listOf("Bob", "Unknown Speaker ?", "Tim"), out.map { it.name })
    }

    @Test
    fun `a long block is never absorbed however its neighbours are labelled`() {
        val blocks = listOf(
            named(0, 100_000, "Bob", "one"),
            named(100_000, 300_000, "Tim", "a genuinely long turn"),
            named(300_000, 400_000, "Bob", "three"),
        )

        val out = smoothShortBlocks(blocks, prefix, twoSeconds)

        assertEquals(listOf("Bob", "Tim", "Bob"), out.map { it.name })
    }

    @Test
    fun `absorbing one fragment lets its neighbours merge and settle`() {
        // Runs to a fixed point: after the middle two are absorbed, the outer Bob blocks touch.
        val blocks = listOf(
            named(0, 100_000, "Bob", "a"),
            named(100_000, 110_000, "Unknown Speaker ?", "b"),
            named(110_000, 124_000, "Unknown Speaker 1", "c"),
            named(124_000, 220_000, "Bob", "d"),
        )

        val out = smoothShortBlocks(blocks, prefix, twoSeconds)

        assertEquals(1, out.size)
        assertEquals("Bob", out.single().name)
        assertEquals("a b c d", out.single().text)
    }

    @Test
    fun `two speakers alternating in long turns are untouched`() {
        val blocks = listOf(
            named(0, 200_000, "Bob", "one"),
            named(200_000, 400_000, "Tim", "two"),
            named(400_000, 600_000, "Bob", "three"),
        )

        assertEquals(blocks, smoothShortBlocks(blocks, prefix, twoSeconds))
    }
}
