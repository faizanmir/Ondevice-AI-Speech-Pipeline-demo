package com.example.aiagenttestapp.data.speakers

import com.example.aiagenttestapp.stt.DiarizedSegment
import com.example.aiagenttestapp.stt.FrameConfidence
import com.example.aiagenttestapp.stt.TimedWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Attributing a word by its own audio rather than by one instant inside it.
 *
 * The failure being prevented is the one the boundary work kept running into from the other side: a
 * hand-over where the diariser's turn edge and the recogniser's word start disagree by a hair, so
 * the first word or two of a turn is scored against the previous speaker. Weighing the word's frames
 * lets the bulk of it decide instead of its leading edge.
 *
 * All of it is arithmetic over turns, words and a float array, so none of it needs a model.
 */
class SpeakerAlignmentMassTest {

    private val rate = 16_000

    /** Even confidence everywhere, so mass is proportional to time inside a turn. */
    private fun evenConfidence(seconds: Float, weight: Float = 1f): FrameConfidence {
        val samplesPerFrame = 256
        val builder = FrameConfidence.Builder((seconds * rate).toInt(), samplesPerFrame)
        var at = 0
        while (at < (seconds * rate).toInt()) {
            builder.put(at, weight)
            at += samplesPerFrame
        }
        return builder.build()
    }

    private fun word(text: String, from: Float, to: Float) =
        TimedWord(text = text, startSeconds = from, endSeconds = to)

    private fun turn(from: Float, to: Float, cluster: Int) =
        DiarizedSegment((from * rate).toInt(), (to * rate).toInt(), cluster)

    @Test
    fun `a word sitting inside one turn is attributed to it`() {
        val blocks = SpeakerAlignment.blocks(
            words = listOf(word("hello", 1f, 1.5f)),
            turns = listOf(turn(0f, 5f, 7)),
            sampleRate = rate,
            confidence = evenConfidence(5f),
        )

        assertEquals(1, blocks.size)
        assertEquals(7, blocks.first().cluster)
    }

    /**
     * The straddle. The word starts a hair inside the outgoing speaker's turn -- which is all the
     * instant-based rule looks at -- but four fifths of it is the incoming speaker's.
     */
    @Test
    fun `a word straddling a hand-over goes to whoever holds most of it`() {
        // The boundary sits past the evidence point (start + 0.2 s), so the instant rule still sees
        // the outgoing speaker while seven tenths of the word belongs to the incoming one.
        val turns = listOf(turn(0f, 2.3f, 0), turn(2.3f, 6f, 1))
        val words = listOf(word("right", 2.0f, 3.0f))

        val withMass = SpeakerAlignment.blocks(words, turns, rate, evenConfidence(6f))
        val withoutMass = SpeakerAlignment.blocks(words, turns, rate, FrameConfidence.NONE)

        assertEquals("mass should follow the bulk of the word", 1, withMass.single().cluster)
        assertEquals("the instant rule follows the word's start", 0, withoutMass.single().cluster)
    }

    /** A word split near evenly is not settled by the audio, so the measured rules keep it. */
    @Test
    fun `an evenly split word falls back to the timestamp rules`() {
        val turns = listOf(turn(0f, 2.5f, 0), turn(2.5f, 6f, 1))
        val words = listOf(word("um", 2.0f, 3.0f))

        val blocks = SpeakerAlignment.blocks(words, turns, rate, evenConfidence(6f))

        assertEquals(0, blocks.single().cluster)
    }

    /** With no track at all, every answer must match what the pipeline gave before. */
    @Test
    fun `an empty track changes nothing`() {
        val turns = listOf(turn(0f, 2f, 0), turn(2f, 4f, 1))
        val words = listOf(word("a", 0.5f, 1f), word("b", 2.5f, 3f))

        val before = SpeakerAlignment.blocks(words, turns, rate)
        val after = SpeakerAlignment.blocks(words, turns, rate, FrameConfidence.NONE)

        assertEquals(before.map { it.cluster }, after.map { it.cluster })
        assertEquals(listOf(0, 1), after.map { it.cluster })
    }

    // ---------------------------------------------------------------- wordless turns

    @Test
    fun `a turn the recogniser found no words in becomes a backchannel marker`() {
        // The interjection sits inside the other speaker's turn -- that competing turn is what
        // makes it a backchannel rather than a hole in recognition.
        val turns = listOf(turn(0f, 3f, 0), turn(1.5f, 2.2f, 1))
        val words = listOf(word("so", 0.2f, 0.6f))

        val blocks = SpeakerAlignment.blocks(words, turns, rate, evenConfidence(5f))

        val marker = blocks.single { it.text == SpeakerAlignment.BACKCHANNEL_MARKER }
        assertEquals(1, marker.cluster)
    }

    @Test
    fun `a wordless turn the model heard two voices in is marked as overlap`() {
        val turns = listOf(turn(0f, 3f, 0), turn(1.5f, 2.2f, 1))
        val words = listOf(word("so", 0.2f, 0.6f))

        val blocks = SpeakerAlignment.blocks(
            words = words,
            turns = turns,
            sampleRate = rate,
            confidence = evenConfidence(5f),
            overlaps = listOf((1.4f * rate).toInt()..(2.3f * rate).toInt()),
        )

        assertEquals(
            SpeakerAlignment.OVERLAP_MARKER,
            blocks.single { it.cluster == 1 }.text,
        )
    }

    @Test
    fun `markers are placed in time order among the spoken blocks`() {
        val turns = listOf(turn(0f, 2.1f, 0), turn(1.2f, 2f, 1), turn(2.2f, 4f, 0))
        val words = listOf(word("first", 0.2f, 0.6f), word("second", 2.4f, 3f))

        val blocks = SpeakerAlignment.blocks(words, turns, rate, evenConfidence(5f))

        assertEquals(blocks.sortedBy { it.startSample }, blocks)
        assertEquals(3, blocks.size)
        assertEquals(SpeakerAlignment.BACKCHANNEL_MARKER, blocks[1].text)
    }

    /** A turn that no cluster owns has no speaker to attribute a marker to. */
    @Test
    fun `an unattributed wordless turn produces no marker`() {
        val turns = listOf(
            turn(0f, 2f, 0),
            turn(1.2f, 1.8f, SpeakerAlignment.UNATTRIBUTED),
        )
        val words = listOf(word("first", 0.2f, 0.6f))

        val blocks = SpeakerAlignment.blocks(words, turns, rate, evenConfidence(3f))

        assertTrue(blocks.none { it.text == SpeakerAlignment.BACKCHANNEL_MARKER })
        assertEquals(1, blocks.size)
    }
}
