package com.example.aiagenttestapp.stt

/**
 * Turns what the VAD found into what the transcriber is allowed to skip.
 *
 * Separate from [SpeechActivityDetector], and pure, because these are not acoustics -- they are
 * decisions about how much benefit of the doubt to give a model that is about to delete part of
 * someone's recording. Every rule here exists to make that deletion harder.
 *
 * The asymmetry is the whole design. Transcribing silence costs a wasted decode and risks an
 * invented sentence, which is bad. Skipping speech loses words that were said once, on a walkthrough
 * that cannot be repeated, and does it *silently* -- there is no gap in the transcript to notice, the
 * sentence simply is not there. So every judgement call below is settled in favour of transcribing
 * more than strictly necessary.
 */
object SpeechRegions {

    /**
     * Added to each end of a detected region.
     *
     * A VAD marks where speech is *confidently* present, which is not the same as where a word
     * starts: quiet onsets ("...and the bracket's cracked") and trailing consonants sit under the
     * threshold and get clipped. A fifth of a second either side is far more than a syllable and
     * costs nothing, because adjacent padded regions simply merge.
     */
    val PAD_SAMPLES = AudioRecorder.SAMPLE_RATE / 5

    /**
     * Gaps shorter than this are absorbed rather than treated as silence.
     *
     * People pause mid-sentence -- to think, to point at something, to draw breath. Cutting at every
     * such gap would fragment one utterance into a dozen regions, and on the Gemma path each fragment
     * is a separate decode with no idea what preceded it, which is exactly how "the valve on the
     * north side is leaking" becomes three disconnected clauses. Only a gap long enough to be a real
     * silence is worth the saving.
     */
    val MIN_GAP_SAMPLES = AudioRecorder.SAMPLE_RATE * 3 / 2

    /**
     * Resolves [detected] into the regions that will actually be transcribed.
     *
     * [protectedRanges] are stretches the user deliberately marked out loud -- the content of a
     * "start non conformity" span. Those are kept whatever the VAD thought, and the reason is worth
     * being explicit about: a spoken marker is the strongest signal in the whole recording that this
     * bit matters, and a VAD that disagreed with it would be overruling the one thing the user went
     * out of their way to say.
     *
     * Returns null for "no useful restriction -- transcribe everything", which is deliberately the
     * same answer for three different situations: the VAD found nothing, it found speech everywhere,
     * or there was nothing to analyse. A caller cannot mishandle them differently because it never
     * learns which one happened, and the safe behaviour is identical in all three.
     */
    fun resolve(
        detected: List<IntRange>,
        totalSamples: Int,
        protectedRanges: List<IntRange> = emptyList(),
        padSamples: Int = PAD_SAMPLES,
        minGapSamples: Int = MIN_GAP_SAMPLES,
    ): List<IntRange>? {
        if (totalSamples <= 0) return null

        // A recording the VAD heard nothing in is the case that must not produce an empty note.
        // Either it is genuinely silent -- in which case transcribing it costs a little and returns
        // little -- or the VAD failed, and falling back to the old behaviour is the only answer that
        // cannot lose anything.
        if (detected.isEmpty()) return null

        val padded = detected
            .filter { !it.isEmpty() }
            .map { region ->
                (region.first - padSamples).coerceAtLeast(0) until
                    (region.last + 1 + padSamples).coerceAtMost(totalSamples)
            }

        val merged = merge(padded + protectedRanges.map { it.clampTo(totalSamples) }, minGapSamples)
            .filter { !it.isEmpty() }

        // Everything ended up covered anyway, so there is nothing to skip and no reason to make the
        // slicer reason about regions.
        if (merged.size == 1 && merged.first().first == 0 && merged.first().last == totalSamples - 1) {
            return null
        }

        return merged.takeIf { it.isNotEmpty() }
    }

    /**
     * Regions for planning against a recording still in progress.
     *
     * A live VAD only reports a speech region when it *closes*, so mid-recording there are two
     * kinds of unspoken-for audio and they must be treated oppositely: behind [classifiedUpTo] the
     * verdict is final and absence from [settled] really means silence, while everything from
     * [classifiedUpTo] on is an open question -- possibly the middle of a sentence being spoken
     * right now. The open stretch is claimed as speech wholesale, per this file's asymmetry: the
     * cost of being wrong that way is a wasted pre-decode, where the other way is a skipped word.
     */
    fun provisional(
        settled: List<IntRange>,
        classifiedUpTo: Int,
        totalSamples: Int,
    ): List<IntRange> {
        if (classifiedUpTo >= totalSamples) return settled
        // listOf, not `+ range`: an IntRange is an Iterable<Int>, and bare plus would concatenate
        // its millions of elements instead of appending one region.
        return settled + listOf(classifiedUpTo.coerceAtLeast(0) until totalSamples)
    }

    /** Sorts, then folds together anything overlapping or separated by less than [minGapSamples]. */
    private fun merge(ranges: List<IntRange>, minGapSamples: Int): List<IntRange> {
        val sorted = ranges.filter { !it.isEmpty() }.sortedBy { it.first }
        if (sorted.isEmpty()) return emptyList()

        val out = mutableListOf<IntRange>()
        var current = sorted.first()

        for (next in sorted.drop(1)) {
            // `next.first - (current.last + 1)` is the gap between them; negative means they overlap.
            if (next.first - (current.last + 1) < minGapSamples) {
                current = current.first..maxOf(current.last, next.last)
            } else {
                out += current
                current = next
            }
        }
        out += current
        return out
    }

    private fun IntRange.clampTo(totalSamples: Int): IntRange =
        first.coerceIn(0, totalSamples) until (last + 1).coerceIn(0, totalSamples)
}
