package com.example.aiagenttestapp.data.speakers

import com.example.aiagenttestapp.stt.DiarizedSegment
import com.example.aiagenttestapp.stt.TimedWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which words belong to which speaker.
 *
 * The failure this class exists to prevent is attribution that reads perfectly and is wrong: a
 * transcript that says the site representative admitted a non-conformity when it was the auditor who
 * raised it is not a garbled sentence, it is a false record. Nothing downstream can detect that, so
 * the boundary rules are pinned here.
 */
class SpeakerAlignmentTest {

    private val rate = 16_000

    /** A word occupying whole seconds, so every boundary in these cases is exact by construction. */
    private fun word(text: String, from: Float, to: Float) = TimedWord(text, from, to)

    private fun turn(fromSec: Float, toSec: Float, cluster: Int) = DiarizedSegment(
        startSample = (fromSec * rate).toInt(),
        endSample = (toSec * rate).toInt(),
        cluster = cluster,
    )

    @Test
    fun `groups consecutive words by speaker`() {
        val words = listOf(
            word("good", 0f, 1f), word("morning", 1f, 2f),
            word("thank", 4f, 5f), word("you", 5f, 6f),
        )
        val turns = listOf(turn(0f, 3f, cluster = 0), turn(3f, 7f, cluster = 1))

        val blocks = SpeakerAlignment.blocks(words, turns, rate)

        assertEquals(2, blocks.size)
        assertEquals("good morning", blocks[0].text)
        assertEquals(0, blocks[0].cluster)
        assertEquals("thank you", blocks[1].text)
        assertEquals(1, blocks[1].cluster)
    }

    @Test
    fun `evidence offset clears a boundary without following the whole reported duration`() {
        // The 200 ms cap moves the evidence just inside the next turn. A raw start remains in cluster
        // 0, while an uncapped midpoint would move farther according to an ASR-estimated word end.
        val words = listOf(word("handover", 2.8f, 4.0f))
        val turns = listOf(turn(0f, 3f, cluster = 0), turn(3f, 7f, cluster = 1))

        val blocks = SpeakerAlignment.blocks(words, turns, rate)

        assertEquals(1, blocks.single().cluster)
    }

    @Test
    fun `a long pause after a word cannot drag it away from the speaker who said it`() {
        // ASR gives each word the next word's start as its end. "yes" was spoken inside cluster 0,
        // but its synthetic 1s..5s range spans a long pause and its midpoint belongs to no turn.
        val words = listOf(word("yes", 1f, 5f))
        val turns = listOf(turn(0.5f, 1.5f, cluster = 0), turn(4.5f, 6f, cluster = 1))

        val blocks = SpeakerAlignment.blocks(words, turns, rate)

        assertEquals(0, blocks.single().cluster)
    }

    @Test
    fun `an overlap keeps the established speaker instead of choosing the earliest segment`() {
        val words = listOf(word("before", 1f, 1.4f), word("overlap", 4f, 4.4f))
        val turns = listOf(
            turn(0f, 2f, cluster = 1),
            turn(2f, 6f, cluster = 0),
            turn(3f, 7f, cluster = 1),
        )

        val blocks = SpeakerAlignment.blocks(words, turns, rate)

        assertEquals(listOf(1), blocks.map { it.cluster })
        assertEquals("before overlap", blocks.single().text)
    }

    @Test
    fun `an overlap with no established speaker is left unattributed`() {
        val words = listOf(word("together", 3f, 3.4f))
        val turns = listOf(turn(2f, 4f, cluster = 0), turn(2.5f, 4.5f, cluster = 1))

        val blocks = SpeakerAlignment.blocks(words, turns, rate)

        assertEquals(SpeakerAlignment.UNATTRIBUTED, blocks.single().cluster)
    }

    @Test
    fun `the first word of a turn is not a coin flip`() {
        // A word starting exactly on the boundary. With a start-based test this lands on whichever
        // side floating-point rounding puts it, and the first word of every turn is exactly this
        // case -- that is what a turn boundary is.
        val words = listOf(word("yes", 3.0f, 3.6f))
        val turns = listOf(turn(0f, 3f, cluster = 0), turn(3f, 7f, cluster = 1))

        val blocks = SpeakerAlignment.blocks(words, turns, rate)

        assertEquals("must belong to the turn it sits inside", 1, blocks.single().cluster)
    }

    @Test
    fun `a gap between turns keeps the previous speaker rather than splitting the block`() {
        // Diarisation reports confident speech only, so a pause mid-sentence leaves a hole. Starting
        // a new block at every hole shreds one person's paragraph into fragments belonging to nobody.
        val words = listOf(
            word("the", 0f, 1f),
            word("register", 4f, 5f), // inside the gap
            word("was", 8f, 9f),
        )
        val turns = listOf(turn(0f, 3f, cluster = 0), turn(7f, 10f, cluster = 0))

        val blocks = SpeakerAlignment.blocks(words, turns, rate)

        assertEquals(1, blocks.size)
        assertEquals("the register was", blocks.single().text)
    }

    @Test
    fun `a gap between different speakers goes to the nearer turn when it is within reach`() {
        // "uncertain" starts 1.2 s after cluster 0 stopped and 2.8 s before cluster 1 began: it is
        // the tail of the first person's turn, not a header for nobody.
        val words = listOf(
            word("before", 0.5f, 1f),
            word("uncertain", 4f, 5f),
            word("after", 8f, 9f),
        )
        val turns = listOf(turn(0f, 3f, cluster = 0), turn(7f, 10f, cluster = 1))

        val blocks = SpeakerAlignment.blocks(words, turns, rate)

        assertEquals(listOf(0, 1), blocks.map { it.cluster })
        assertEquals("before uncertain", blocks.first().text)
    }

    @Test
    fun `a gap word nearer the next speaker opens their turn`() {
        val words = listOf(word("before", 0.5f, 1f), word("well", 6.2f, 6.5f), word("after", 8f, 9f))
        val turns = listOf(turn(0f, 3f, cluster = 0), turn(7f, 10f, cluster = 1))

        val blocks = SpeakerAlignment.blocks(words, turns, rate)

        assertEquals(listOf(0, 1), blocks.map { it.cluster })
        assertEquals("well after", blocks.last().text)
    }

    @Test
    fun `a gap word beyond reach of both turns stays unattributed`() {
        // 2.7 s from the turn before and 3.3 s from the turn after: a genuine hole, not a boundary.
        val words = listOf(word("before", 0.5f, 1f), word("lost", 5.5f, 6f), word("after", 10f, 11f))
        val turns = listOf(turn(0f, 3f, cluster = 0), turn(9f, 12f, cluster = 1))

        val blocks = SpeakerAlignment.blocks(words, turns, rate)

        assertEquals(listOf(0, SpeakerAlignment.UNATTRIBUTED, 1), blocks.map { it.cluster })
    }

    @Test
    fun `a gap word the same distance from both sides stays with the person already speaking`() {
        val words = listOf(word("before", 0.5f, 1f), word("hmm", 4.8f, 5f), word("after", 8f, 9f))
        val turns = listOf(turn(0f, 4f, cluster = 0), turn(6f, 10f, cluster = 1))

        val blocks = SpeakerAlignment.blocks(words, turns, rate)

        assertEquals(0, blocks[0].cluster)
        assertEquals("before hmm", blocks[0].text)
    }

    @Test
    fun `a nested earlier turn cannot hide the nearest speaker before a gap`() {
        val words = listOf(word("bridged", 6f, 6.4f))
        val turns = listOf(
            turn(0f, 5f, cluster = 0),
            turn(2f, 3f, cluster = 1),
            turn(7f, 9f, cluster = 0),
        )

        val blocks = SpeakerAlignment.blocks(words, turns, rate)

        assertEquals(0, blocks.single().cluster)
    }

    @Test
    fun `words before any turn are marked unattributed rather than guessed`() {
        val words = listOf(word("hello", 0f, 1f), word("there", 5f, 6f))
        val turns = listOf(turn(4f, 8f, cluster = 3))

        val blocks = SpeakerAlignment.blocks(words, turns, rate)

        assertEquals(SpeakerAlignment.UNATTRIBUTED, blocks.first().cluster)
        assertTrue("must never collide with a real cluster", SpeakerAlignment.UNATTRIBUTED < 0)
        assertEquals(3, blocks.last().cluster)
    }

    @Test
    fun `the same speaker returning starts a new block`() {
        // A B A must be three blocks, not two: merging the two A stretches would silently delete B's
        // turn from between them and reorder the conversation.
        val words = listOf(
            word("a-one", 0f, 1f),
            word("b-one", 4f, 5f),
            word("a-two", 8f, 9f),
        )
        val turns = listOf(turn(0f, 3f, 0), turn(3f, 7f, 1), turn(7f, 10f, 0))

        val blocks = SpeakerAlignment.blocks(words, turns, rate)

        assertEquals(3, blocks.size)
        assertEquals(listOf(0, 1, 0), blocks.map { it.cluster })
    }

    @Test
    fun `block ranges cover their words and never run backwards`() {
        val words = listOf(word("one", 1f, 2f), word("two", 2f, 3.5f))
        val turns = listOf(turn(0f, 5f, 0))

        val block = SpeakerAlignment.blocks(words, turns, rate).single()

        assertEquals((1f * rate).toInt(), block.startSample)
        assertEquals((3.5f * rate).toInt(), block.endSample)
        assertTrue(block.endSample > block.startSample)
    }

    @Test
    fun `no diarisation at all still returns the transcript`() {
        // The models being absent or the diariser failing must cost speaker labels, never the words.
        val words = listOf(word("still", 0f, 1f), word("here", 1f, 2f))

        val blocks = SpeakerAlignment.blocks(words, emptyList(), rate)

        assertEquals("still here", blocks.single().text)
        assertEquals(SpeakerAlignment.UNATTRIBUTED, blocks.single().cluster)
    }

    @Test
    fun `handles empty input`() {
        assertEquals(emptyList<SpeakerBlock>(), SpeakerAlignment.blocks(emptyList(), emptyList(), rate))
    }
}
