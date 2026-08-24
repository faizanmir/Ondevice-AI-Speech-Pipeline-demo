package com.example.aiagenttestapp.data.speakers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-speaker summary shown above a finished transcript.
 *
 * Worth pinning because the numbers are the part a reader will quote without checking: "she spoke
 * for two thirds of the meeting" is the kind of claim that gets repeated, and a share computed
 * against the wrong denominator is wrong in a way that still looks plausible.
 */
class SpeakerStatsTest {

    private val rate = 16_000

    private fun block(name: String, cluster: Int, fromSec: Int, toSec: Int) = DiarizedBlock(
        id = fromSec.toLong() * 1000 + cluster,
        recordingId = 1,
        startSample = fromSec * rate,
        endSample = toSec * rate,
        cluster = cluster,
        speakerName = name,
        text = "words",
    )

    @Test
    fun `counts turns and talk time per speaker`() {
        val stats = SpeakerStats.from(
            listOf(
                block("Ada", 0, 0, 10),
                block("Grace", 1, 10, 20),
                block("Ada", 0, 20, 30),
            ),
            rate,
        )

        val ada = stats.single { it.name == "Ada" }
        assertEquals(2, ada.turns)
        assertEquals(20_000L, ada.speakingMillis)
        assertEquals(1, stats.single { it.name == "Grace" }.turns)
    }

    @Test
    fun `share is of speech, not of the recording`() {
        // Two speakers, 10 s each, with a 40 s silence between them that no block covers. Against
        // the recording's length each would read as 17%; against speech they are half each, which is
        // the true answer to "who dominated this conversation".
        val stats = SpeakerStats.from(
            listOf(
                block("Ada", 0, 0, 10),
                block("Grace", 1, 50, 60),
            ),
            rate,
        )

        stats.forEach { assertEquals(0.5f, it.share, 1e-3f) }
        assertEquals(1f, stats.sumOf { it.share.toDouble() }.toFloat(), 1e-3f)
    }

    @Test
    fun `ordered by who spoke first, not by who spoke most`() {
        // The summary sits directly above the transcript. Sorting by talk time would list them in a
        // different order from the conversation underneath it.
        val stats = SpeakerStats.from(
            listOf(
                block("Quiet", 0, 0, 1),
                block("Loud", 1, 5, 60),
            ),
            rate,
        )

        assertEquals(listOf("Quiet", "Loud"), stats.map { it.name })
        assertEquals(0L, stats.first().firstAtMillis)
        assertEquals(5_000L, stats.last().firstAtMillis)
    }

    @Test
    fun `an unnamed speaker is marked unenrolled and keeps its number`() {
        // Two unrecognised people must stay two people. A single shared "Unknown Speaker" label
        // would merge them, which is precisely the distinction diarisation just made.
        val stats = SpeakerStats.from(
            listOf(
                block("${SpeakerRepository.UNKNOWN_SPEAKER_PREFIX} 1", 0, 0, 10),
                block("${SpeakerRepository.UNKNOWN_SPEAKER_PREFIX} 2", 1, 10, 20),
                block("Ada", 2, 20, 30),
            ),
            rate,
        )

        assertEquals(3, stats.size)
        assertFalse(stats.first { it.name.endsWith("1") }.enrolled)
        assertFalse(stats.first { it.name.endsWith("2") }.enrolled)
        assertTrue(stats.single { it.name == "Ada" }.enrolled)
    }

    @Test
    fun `a speaker named like a person is not mistaken for a placeholder`() {
        // Enrolment refuses names matching the placeholder pattern, so this is the other direction:
        // an ordinary name that merely contains the word must still count as enrolled.
        val stats = SpeakerStats.from(listOf(block("Speaker Jenkins", 0, 0, 5)), rate)

        assertTrue(stats.single().enrolled)
    }

    @Test
    fun `a transcript too short to measure does not report NaN`() {
        // Reachable on a clip where every block rounds to zero milliseconds; a share of NaN renders
        // as "NaN%" on screen rather than failing anywhere a test would catch.
        val stats = SpeakerStats.from(listOf(block("Ada", 0, 0, 0)), rate)

        assertEquals(0f, stats.single().share, 1e-6f)
        assertFalse(stats.single().share.isNaN())
    }

    @Test
    fun `handles an empty transcript`() {
        assertEquals(emptyList<SpeakerStat>(), SpeakerStats.from(emptyList(), rate))
    }
}
