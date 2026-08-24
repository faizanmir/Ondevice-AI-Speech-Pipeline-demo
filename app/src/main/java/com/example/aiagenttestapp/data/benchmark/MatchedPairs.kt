package com.example.aiagenttestapp.data.benchmark

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Whether two runs on the same clip actually differ, or only appear to.
 *
 * A WER is a point estimate from one sample, and this rig produces point estimates that move on
 * their own: the *same* configuration scored 25.0% and 28.1% on the same clip on different days.
 * Against that, 25.7% versus 26.1% is not a result, and nothing in the app said so -- two numbers
 * printed side by side invite a comparison the numbers cannot support.
 *
 * This is the matched-pairs test the speech field already uses for exactly this question (NIST's
 * MAPSSWE, shipped in SCTK alongside `sclite`). The idea is that the two systems transcribed the
 * *same* audio, so they are not independent samples and must not be compared as though they were:
 * instead the reference is cut into segments, each system's errors are counted per segment, and the
 * test asks whether the per-segment *difference* is distinguishable from zero. Pairing removes the
 * variance that comes from some passages simply being harder than others, which is most of it.
 *
 * Two deliberate choices, both visible in the output so a reader can discount them:
 *
 *  - **Segments are fixed windows of reference words**, not sentences. Sentences would be the
 *    better unit, but [Wer.normalise] removes the punctuation that would define them, and a
 *    reference is not guaranteed to have any. The test only needs enough segments for the mean
 *    difference to be roughly normal.
 *  - **Errors are attributed to reference positions**, so the two systems are scored against a
 *    common frame even when their decoders sliced the audio differently -- which they do whenever
 *    the backends differ. Insertions land in the segment they were inserted into.
 *
 * What this cannot do is make a single run reliable. It compares two runs on one clip; a claim
 * about a *setting* still wants more than one clip.
 */
object MatchedPairs {

    data class Result(
        /** How many reference segments the comparison is over. */
        val segments: Int,
        val werA: Double,
        val werB: Double,
        /** Mean per-segment error difference, A minus B. Negative means A made fewer errors. */
        val meanDifference: Double,
        /** The test statistic: mean difference over its standard error. */
        val statistic: Double,
        /** Two-tailed probability of a difference this large if the two were equivalent. */
        val pValue: Double,
    ) {
        /** The conventional threshold. Stated as a field so a caller cannot pick a kinder one. */
        val significant: Boolean get() = pValue < 0.05

        /**
         * Whether the normal approximation behind [pValue] is worth believing. Below this the test
         * is reported anyway -- suppressing it would hide the comparison entirely -- but a reader
         * should treat it as a hint rather than a result.
         */
        val reliable: Boolean get() = segments >= MIN_SEGMENTS
    }

    /**
     * Compares [hypothesisA] and [hypothesisB] against the same [reference].
     *
     * Both are normalised exactly as the reported (number-normalised) WER is, so the errors counted
     * here are the errors on the run rows. Returns null when there is nothing to compare -- an
     * empty reference, or a reference shorter than one segment.
     */
    fun compare(
        reference: String,
        hypothesisA: String,
        hypothesisB: String,
        lang: String,
        segmentWords: Int = SEGMENT_WORDS,
    ): Result? {
        val ref = Wer.normalise(reference, expandNumbers = true, lang = lang)
        if (ref.size < segmentWords) return null

        val a = Wer.errorProfile(ref, Wer.normalise(hypothesisA, expandNumbers = true, lang = lang))
        val b = Wer.errorProfile(ref, Wer.normalise(hypothesisB, expandNumbers = true, lang = lang))

        val differences = mutableListOf<Double>()
        var errorsA = 0
        var errorsB = 0
        var at = 0
        while (at < ref.size) {
            val end = minOf(at + segmentWords, ref.size)
            var segA = 0
            var segB = 0
            for (i in at until end) {
                segA += a[i]
                segB += b[i]
            }
            errorsA += segA
            errorsB += segB
            differences += (segA - segB).toDouble()
            at = end
        }

        val n = differences.size
        val mean = differences.average()

        // Sample variance, n-1. Zero variance has two opposite meanings and they must not be
        // collapsed: every segment differing by *nothing* is no evidence of a difference, while
        // every segment differing by the *same non-zero amount* is the strongest evidence there
        // could be. Treating both as p = 1 said "not significant" about a system that was worse in
        // every single segment.
        val variance = if (n < 2) 0.0 else differences.sumOf { (it - mean) * (it - mean) } / (n - 1)
        val standardError = sqrt(variance / n)

        val statistic = when {
            standardError > 0.0 -> mean / standardError
            mean == 0.0 -> 0.0
            else -> Double.POSITIVE_INFINITY * (if (mean > 0) 1.0 else -1.0)
        }
        val pValue = when {
            standardError > 0.0 -> 2.0 * (1.0 - normalCdf(abs(statistic)))
            mean == 0.0 -> 1.0
            else -> 0.0
        }

        return Result(
            segments = n,
            werA = 100.0 * errorsA / ref.size,
            werB = 100.0 * errorsB / ref.size,
            meanDifference = mean,
            statistic = statistic,
            pValue = pValue.coerceIn(0.0, 1.0),
        )
    }

    private fun normalCdf(x: Double): Double = 0.5 * (1.0 + erf(x / sqrt(2.0)))

    /**
     * Abramowitz & Stegun 7.1.26 -- maximum error 1.5e-7, which is four orders of magnitude finer
     * than any decision made on the p-value here, and avoids a dependency for one function.
     */
    private fun erf(x: Double): Double {
        val t = 1.0 / (1.0 + 0.3275911 * abs(x))
        val y = 1.0 - (
            (
                (
                    (
                        (1.061405429 * t - 1.453152027) * t + 1.421413741
                        ) * t - 0.284496736
                    ) * t + 0.254829592
                ) * t
            ) * exp(-x * x)
        return if (x >= 0) y else -y
    }

    /** Long enough that a segment holds a sentence or two, short enough to give plenty of them. */
    const val SEGMENT_WORDS = 50

    /** The usual rule of thumb for leaning on a normal approximation. */
    const val MIN_SEGMENTS = 30
}
