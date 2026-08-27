package com.example.aiagenttestapp.data.speakers

import com.example.aiagenttestapp.data.benchmark.Wer

/**
 * What a finished run scored against a reference: the words, and who they were given to.
 *
 * Both halves are needed and neither substitutes for the other. A run can hear every word and file
 * them all under one person; it can also attribute perfectly a transcript that is half missing.
 * Reported together, and read in the order the benchmark's protocol already fixes for this app --
 * coverage first, because under about 90% the error rate is measuring what is absent rather than
 * what was heard.
 *
 * Computed from the stored blocks rather than during the run, which is what lets a reference be
 * attached to a recording that finished days ago and have it scored without spending the minutes
 * of two models again.
 */
data class DiarizationScore(
    /** Transcript words over reference words. Read before [werPercent]. */
    val coveragePercent: Double,

    /** Number-normalised word error rate -- the pass that does not charge "19.4" against "nineteen point four". */
    val werPercent: Double,

    /** Share of agreed words filed under the right speaker, or null when the reference names none. */
    val speakerAccuracyPercent: Double?,

) {
    companion object {

        /**
         * Scores [blocks] against [reference], or null when there is nothing to score.
         *
         * The hypothesis handed to [Wer] is the speech alone, with no speaker names joined into
         * it. The reference's own `[S1]` tags are stripped before it is counted, so a hypothesis
         * carrying names would be charged an insertion for every turn -- a penalty for text nobody
         * ever said, which is the exact mistake that regex in [Wer] exists to avoid.
         */
        fun of(reference: String, language: String, blocks: List<DiarizedBlock>): DiarizationScore? {
            if (reference.isBlank() || blocks.isEmpty()) return null

            val ordered = blocks.sortedBy { it.startSample }
            val hypothesis = ordered.joinToString(" ") { it.text }
            if (hypothesis.isBlank()) return null

            // Scored through `normalise`/`score` rather than `Wer.report`, which is built for the
            // benchmark screen and computes three things this row has nowhere to show: a second
            // unexpanded pass, the top error pairs, and a character error rate. That last one is
            // not merely wasted -- it is a *character*-level edit distance, so its banded table is
            // roughly reference-characters x band. On a 3,000-word reference with a poor transcript
            // that reaches hundreds of megabytes, and the reference is user-supplied text capped at
            // 2 MB. It was the one part of this path that could take the process down, to produce a
            // number nothing reads. Same arithmetic as `report` does for the two figures kept.
            val ref = Wer.normalise(
                Wer.DIRECTIVE.replace(reference, ""),
                expandNumbers = true,
                lang = language,
            )
            val hyp = Wer.normalise(hypothesis, expandNumbers = true, lang = language)
            if (ref.isEmpty()) return null

            val speakers = SpeakerAccuracy.score(reference, ordered, language)

            return DiarizationScore(
                coveragePercent = 100.0 * hyp.size / ref.size,
                werPercent = Wer.score(ref, hyp).werPercent,
                speakerAccuracyPercent = speakers?.percent,
            )
        }
    }
}
