package com.example.aiagenttestapp.data.speakers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the two rules that make an attribution score mean anything.
 *
 * Both are the kind of thing that looks like an implementation detail and decides the number
 * outright: which speaker in the reference is "the same person" as which speaker in the run, and
 * which words are allowed to count.
 */
class SpeakerAccuracyTest {

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
    fun `a perfect run scores full marks even though it named nobody the same`() {
        // The reference says S1 and S2; the run says Bob and Alice. Comparing labels directly would
        // score this at 0, and which cluster got which number is an accident of the audio anyway.
        val result = SpeakerAccuracy.score(
            reference = "[S1] so where did we land [S2] i pushed the branch this morning",
            blocks = listOf(
                block("Bob", "so where did we land"),
                block("Alice", "i pushed the branch this morning"),
            ),
            lang = "en",
        )!!

        assertEquals(11, result.comparedWords)
        assertEquals(100.0, result.percent, 0.001)
        assertEquals(mapOf("s1" to "Bob", "s2" to "Alice"), result.mapping)
    }

    @Test
    fun `two people heard as one keeps only the half it can claim`() {
        // The classic failure this screen exists to show: every word correct, all filed under one
        // person. Only one of the two reference speakers can be mapped to the single cluster.
        val result = SpeakerAccuracy.score(
            reference = "[S1] alpha beta gamma [S2] delta epsilon zeta",
            blocks = listOf(block("Bob", "alpha beta gamma delta epsilon zeta")),
            lang = "en",
        )!!

        assertEquals(6, result.comparedWords)
        assertEquals(50.0, result.percent, 0.001)
    }

    @Test
    fun `words the recogniser never produced are not charged to attribution`() {
        // A missing word has no speaker to be wrong about. Counting it here would fold the
        // transcription failure into the attribution number and leave neither diagnosable -- the
        // reason coverage is printed beside this figure rather than folded into it.
        val result = SpeakerAccuracy.score(
            reference = "[S1] alpha beta gamma [S2] delta epsilon zeta",
            blocks = listOf(block("Bob", "alpha beta gamma")),
            lang = "en",
        )!!

        assertEquals(3, result.comparedWords)
        assertEquals(100.0, result.percent, 0.001)
    }

    @Test
    fun `a reference that names nobody is not scored as everybody being wrong`() {
        assertNull(
            SpeakerAccuracy.score(
                reference = "so where did we land i pushed the branch this morning",
                blocks = listOf(block("Bob", "so where did we land")),
                lang = "en",
            ),
        )
    }

    @Test
    fun `a say directive is silence, not a speaker`() {
        // [[slnc 500]] is double-bracketed. Taking the outer pair apart first would leave [slnc 500]
        // looking exactly like a speaker tag, and silence would be credited with the opening words.
        val result = SpeakerAccuracy.score(
            reference = "[[slnc 500]] [S1] alpha beta [[slnc 300]] gamma",
            blocks = listOf(block("Bob", "alpha beta gamma")),
            lang = "en",
        )!!

        assertEquals(setOf("s1"), result.mapping.keys)
        assertEquals(3, result.comparedWords)
        assertEquals(100.0, result.percent, 0.001)
    }

    @Test
    fun `a run that split one person in two is not credited for both halves`() {
        // One reference speaker, two clusters. Only one cluster can be mapped, so the other's words
        // are counted as wrong -- which is the honest reading: half the turns say a second person
        // was speaking, and nobody else was.
        val result = SpeakerAccuracy.score(
            reference = "[S1] alpha beta gamma delta",
            blocks = listOf(
                block("Unknown Speaker 1", "alpha beta"),
                block("Unknown Speaker 2", "gamma delta"),
            ),
            lang = "en",
        )!!

        assertEquals(4, result.comparedWords)
        assertEquals(50.0, result.percent, 0.001)
    }
}
