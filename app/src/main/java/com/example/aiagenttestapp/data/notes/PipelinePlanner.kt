package com.example.aiagenttestapp.data.notes

/**
 * Decides which parts of a recording can be transcribed while it is still being recorded.
 *
 * ### The problem this solves
 *
 * Transcription only starts when the user presses stop, and on the measured audit recordings it runs
 * at about twice real time: a twenty-minute walkthrough is followed by forty minutes of waiting. All
 * of that work could have been done while the person was still talking.
 *
 * ### Why it is not simply "decode as you go"
 *
 * Because slice boundaries are decided globally. [SpokenMarkers.slice] draws edges from every spoken
 * marker, every speaker turn and the recording's total length at once, and a transcript is only
 * correct if the text it stores lines up with the boundaries the final pass computes. Decode against
 * a boundary that later moves and the work is wasted -- or worse, kept and wrong.
 *
 * So the question this answers is narrower and answerable: **which slices are already final?** A
 * slice is final when both its edges are fixed for good, which is true when:
 *
 *  - both edges come from markers that have already fired, or from the start of the recording, and
 *  - the whole slice sits far enough behind the moment of recording that no more audio can change it.
 *
 * The last slice of any prefix is never final -- its far edge is "wherever we happen to have got to"
 * -- so it is always withheld. Everything before it is safe.
 *
 * ### Why the results are reusable rather than provisional
 *
 * The final pass looks its slices up in [TranscriptionCheckpoint] by exact sample range before
 * decoding them, because that is how it resumes after a process death. Pipelined work therefore needs
 * no new storage and no new plumbing: a range decoded early and recorded under its exact bounds is
 * simply found already done. A range whose bounds turn out differently is a cache miss and is
 * decoded normally, so being wrong costs time, never correctness.
 */
object PipelinePlanner {

    /**
     * How much of the newest audio to leave alone, in samples.
     *
     * Two seconds. The recorder writes through a buffer, a marker is reported a moment after the
     * phrase that triggered it, and a cut placed at the very edge of what has been captured would sit
     * mid-word. None of those are worth racing for a couple of seconds of lead.
     */
    const val TAIL_SAMPLES = 32_000

    /** Below this there is not enough audio for a slice worth decoding. */
    const val MIN_READY_SAMPLES = 16_000

    /**
     * What one pass may do: the slices to decode, and how far the settled prefix now reaches.
     *
     * [frontier] exists because "nothing to decode" and "nothing settled" are different answers.
     * A stretch the VAD ruled silent is settled -- no later audio can change it -- but produces no
     * range; without the frontier the caller's watermark could only advance past ranges it decoded,
     * so silence pinned it in place and every subsequent pass re-read a window that grew with the
     * recording.
     */
    data class ReadyPlan(
        /** Slices safe to decode now, in order, in recording coordinates. Spoken ones only. */
        val ranges: List<IntRange>,
        /**
         * Absolute end (exclusive) of the settled prefix. Everything before it is final -- decoded,
         * silent, or a trigger phrase -- so the caller's watermark advances here even when [ranges]
         * is empty.
         */
        val frontier: Int,
    )

    /**
     * The slices that are safe to decode now, given what has been captured and heard so far.
     *
     * [windowStart] is where previous passes finished; ranges are always returned in order and never
     * overlap it, and the caller advances its watermark to [ReadyPlan.frontier] once the pass is
     * done.
     *
     * [markers] are only those whose phrases have already fired. A marker that fires later adds edges
     * *after* everything returned here, so it cannot invalidate them -- which is precisely the
     * property that makes early decoding safe.
     *
     * [speechRegions] marks where speech has been found so far, or null when nothing is watching.
     * It changes which settled slices are *offered*, never where their boundaries lie --
     * [SpokenMarkers.slice] guarantees regions mark slices silent rather than adding cuts, which is
     * what keeps every range returned here identical to the one the final pass computes and looks
     * up. The caller is expected to hand in [SpeechRegions.provisional] output: audio the VAD has
     * not ruled on yet must arrive claimed as speech, or a sentence still being spoken would be
     * skipped as silence and paid for after stop.
     */
    fun readyRanges(
        windowStart: Int,
        capturedTotal: Int,
        markers: List<SpokenMarker>,
        excludedRanges: List<IntRange>,
        speechRegions: List<IntRange>?,
        maxSliceSamples: Int,
        cutLongSlice: (from: Int, until: Int) -> Int,
    ): ReadyPlan {
        val nothing = ReadyPlan(ranges = emptyList(), frontier = windowStart)
        val safeEnd = capturedTotal - TAIL_SAMPLES
        val length = safeEnd - windowStart
        if (length < MIN_READY_SAMPLES) return nothing

        // Sliced in *window* coordinates, not recording coordinates.
        //
        // The distinction is the whole correctness story here, and getting it wrong crashed a
        // recording: slicing the prefix from zero makes the slicer ask for cut points anywhere in
        // it, including long before the window the caller actually holds samples for, and the
        // resulting negative index reaches straight into an array. Slicing the window means every
        // position the cut function sees is one the caller can answer.
        //
        // It is also cheaper, and stays cheap. The alternative is re-reading the whole recording
        // every few seconds, which grows without limit exactly as the recording does.
        //
        // Correct because [windowStart] is always a real boundary: it is where the previous pass
        // finished, so the final slicer arrives at it too, and continues from it with the same
        // deterministic cuts over the same audio.
        val slices = SpokenMarkers.slice(
            totalSamples = length,
            markers = markers
                .filter { it.startSample >= windowStart && it.endSample <= safeEnd }
                .map {
                    it.copy(
                        startSample = it.startSample - windowStart,
                        endSample = it.endSample - windowStart,
                    )
                },
            excludedRanges = excludedRanges
                .filter { it.first >= windowStart && it.last < safeEnd }
                .map { (it.first - windowStart)..(it.last - windowStart) },
            // Clamped rather than filtered, unlike the markers above: a region straddling the
            // window edge must still mark the overlap it has inside the window as speech, and the
            // slicer's test is any-overlap. Dropping it would call the straddled slice silent.
            speechRegions = speechRegions
                ?.map { it.first.coerceAtLeast(windowStart)..it.last.coerceAtMost(safeEnd - 1) }
                ?.filter { it.first <= it.last }
                ?.map { (it.first - windowStart)..(it.last - windowStart) },
            maxSliceSamples = maxSliceSamples,
            cutLongSlice = cutLongSlice,
        )

        // The final slice ends at the window boundary rather than at anything real, so its far
        // edge will move as soon as more audio arrives. Never claim it -- for decoding *or* for
        // the frontier.
        val settled = slices.dropLast(1)

        return ReadyPlan(
            ranges = settled
                .filter { it.isSpoken }
                // Back to recording coordinates: the checkpoint is keyed by absolute sample range,
                // and that is what the final pass will look up.
                .map { (it.range.first + windowStart)..(it.range.last + windowStart) },
            frontier = windowStart + (settled.lastOrNull()?.range?.last?.plus(1) ?: 0),
        )
    }
}
