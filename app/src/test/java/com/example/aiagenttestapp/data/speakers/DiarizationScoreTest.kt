package com.example.aiagenttestapp.data.speakers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Pins what the two halves of a score do and do not charge each other for. */
class DiarizationScoreTest {

    private var next = 0

    private fun block(speaker: String, text: String): DiarizedBlock {
        val start = next
        next += 16_000
        return DiarizedBlock(
            recordingId = 1,
            startSample = start,
            endSample = next,
            cluster = 0,
            speakerName = speaker,
            text = text,
        )
    }

    @Test
    fun `speaker names are not scored as words`() {
        // The hypothesis handed to the scorer is the speech alone. Joining the names into it would
        // charge an insertion per turn for text nobody said -- the same mistake the reference's own
        // tags would cause, which is why they are stripped.
        val score = DiarizationScore.of(
            reference = "[S1] alpha beta [S2] gamma delta",
            language = "en",
            blocks = listOf(block("Bob", "alpha beta"), block("Alice", "gamma delta")),
        )!!

        assertEquals(0.0, score.werPercent, 0.001)
        assertEquals(100.0, score.coveragePercent, 0.001)
        assertEquals(100.0, score.speakerAccuracyPercent!!, 0.001)
    }

    @Test
    fun `a plain reference still scores the words`() {
        // No tags means attribution cannot be judged, which must not stop the transcript being
        // scored -- and must not be reported as an attribution of zero.
        val score = DiarizationScore.of(
            reference = "alpha beta gamma delta",
            language = "en",
            blocks = listOf(block("Bob", "alpha beta gamma delta")),
        )!!

        assertEquals(0.0, score.werPercent, 0.001)
        assertNull(score.speakerAccuracyPercent)
    }

    @Test
    fun `a truncated transcript shows in coverage, not only in the error rate`() {
        val score = DiarizationScore.of(
            reference = "[S1] alpha beta gamma delta [S2] epsilon zeta eta theta",
            language = "en",
            blocks = listOf(block("Bob", "alpha beta gamma delta")),
        )!!

        assertEquals(50.0, score.coveragePercent, 0.001)
        // Every missing word is a deletion: half the reference is gone, so the rate is 50% too.
        assertEquals(50.0, score.werPercent, 0.001)
        // ...while attribution is perfect on what did survive. Read coverage first.
        assertEquals(100.0, score.speakerAccuracyPercent!!, 0.001)
    }

    @Test
    fun `nothing to score is null rather than a perfect result`() {
        assertNull(DiarizationScore.of("", "en", listOf(block("Bob", "alpha"))))
        assertNull(DiarizationScore.of("[S1] alpha", "en", emptyList()))
        assertNotNull(DiarizationScore.of("[S1] alpha", "en", listOf(block("Bob", "alpha"))))
    }
}
