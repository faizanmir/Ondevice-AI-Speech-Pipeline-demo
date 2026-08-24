package com.example.aiagenttestapp.data.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the comparison the benchmark screen makes between two runs.
 *
 * The properties that matter are not the exact p-values -- those follow from the arithmetic -- but
 * the three answers a reader will act on: identical output is never called a difference, a large
 * consistent difference is, and a difference smaller than the rig's own run-to-run wobble is not.
 */
class MatchedPairsTest {

    /** 40 segments' worth, so the normal approximation is in its stated range. */
    private val reference = (1..2000).joinToString(" ") { "word$it" }

    /**
     * Which reference words a run got wrong, scattered by a seeded generator.
     *
     * Scattered rather than every-nth on purpose. Regularly placed errors give every segment the
     * identical error count, which makes the per-segment difference constant, the variance zero and
     * the test trivially decisive -- correctly, but for a reason no real transcript has. Two real
     * runs differ in *where* they go wrong, and that scatter is the noise the test has to see
     * through.
     */
    private fun errorMask(percent: Int, seed: Long): BooleanArray {
        var state = seed
        return BooleanArray(2000) {
            state = state * 6364136223846793005L + 1442695040888963407L
            (((state ushr 33).toInt() and 0x7FFFFFFF) % 100) < percent
        }
    }

    private fun transcript(mask: BooleanArray): String =
        (1..2000).joinToString(" ") { if (mask[it - 1]) "wrong$it" else "word$it" }

    /** The same errors, moved. Keeps the rate identical and changes only where the run went wrong. */
    private fun shifted(mask: BooleanArray, by: Int): BooleanArray =
        BooleanArray(mask.size) { mask[(it + mask.size - by) % mask.size] }

    @Test
    fun `identical transcripts are never called a difference`() {
        val same = transcript(errorMask(percent = 10, seed = 1))
        val result = MatchedPairs.compare(reference, same, same, "en")!!

        assertEquals(0.0, result.meanDifference, 1e-9)
        assertEquals(1.0, result.pValue, 1e-9)
        assertTrue(!result.significant)
        assertEquals(result.werA, result.werB, 1e-9)
    }

    @Test
    fun `a large consistent difference is significant`() {
        // 20% of words wrong against 2%: the sort of gap between a paced and an unpaced feed.
        val result = MatchedPairs.compare(
            reference,
            transcript(errorMask(percent = 20, seed = 1)),
            transcript(errorMask(percent = 2, seed = 2)),
            "en",
        )!!

        assertTrue("A should be worse", result.meanDifference > 0)
        assertTrue("p was ${result.pValue}", result.significant)
        assertTrue(result.reliable)
        assertTrue("werA was ${result.werA}", result.werA > 3 * result.werB)
    }

    /**
     * The case this exists for. Two runs a few tenths of a point apart -- 25.7% against 26.1% on the
     * real rig -- must not read as a finding, because the same configuration twice differs by more.
     * Same error rate, different places: exactly what repeating a run produces.
     */
    @Test
    fun `a difference smaller than the rig's own wobble is not significant`() {
        // The same errors moved a few words along, then a handful of them fixed: the same run
        // repeated, give or take. Anything the test calls significant here is noise.
        val a = errorMask(percent = 8, seed = 3)
        val b = shifted(a, by = 3).also { moved ->
            var dropped = 0
            for (i in moved.indices) if (moved[i] && dropped < 4 && i % 137 == 0) { moved[i] = false; dropped++ }
        }
        val result = MatchedPairs.compare(reference, transcript(a), transcript(b), "en")!!

        assertTrue("the gap should be under a point", kotlin.math.abs(result.werA - result.werB) < 1.0)
        assertTrue("p was ${result.pValue}", !result.significant)
    }

    @Test
    fun `a reference shorter than one segment has nothing to compare`() {
        assertNull(MatchedPairs.compare("only a few words here", "only a few words", "a few words", "en"))
    }

    @Test
    fun `too few segments are reported but flagged unreliable`() {
        val short = (1..200).joinToString(" ") { "word$it" }     // 4 segments
        val result = MatchedPairs.compare(short, short, short.replace("word7 ", ""), "en")!!

        assertEquals(4, result.segments)
        assertTrue(!result.reliable)
    }

    /** The profile is the scoring, redistributed: it has to add up to what the run row says. */
    @Test
    fun `the error profile sums to the reported error count`() {
        val ref = Wer.normalise(reference, expandNumbers = true, lang = "en")
        val hyp = Wer.normalise(transcript(errorMask(percent = 14, seed = 5)), expandNumbers = true, lang = "en")

        assertEquals(Wer.score(ref, hyp).errors, Wer.errorProfile(ref, hyp).sum())
    }

    /** Insertions have no reference word of their own; they must still be counted somewhere. */
    @Test
    fun `insertions are attributed and never lost`() {
        val ref = listOf("a", "b", "c")
        val hyp = listOf("a", "x", "y", "b", "c", "z")
        val profile = Wer.errorProfile(ref, hyp)

        assertEquals(Wer.score(ref, hyp).errors, profile.sum())
        assertEquals(3, profile.size)
    }
}
