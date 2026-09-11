package com.example.aiagenttestapp.stt

/**
 * Finds where two people are talking at once, from the segmentation model's own activity curves.
 *
 * ## Why this can exist at all
 *
 * pyannote's powerset output has classes for *pairs* of speakers -- {0,1}, {0,2}, {1,2} -- so
 * simultaneous speech is something the model states rather than something to be guessed at. Reducing
 * its output to argmax segments, which is all sherpa's diarizer hands back, throws that away: two
 * overlapping turns come out as one turn for whoever won each frame. This reads
 * [PyannoteSegmenter.FrameTrack.act] instead, where every speaker's probability mass is kept
 * separately and two can be high at the same time.
 *
 * ## Hysteresis, not a threshold
 *
 * A single cutoff on a noisy curve produces a burst of one-frame regions every time it wobbles
 * across the line. So a speaker turns **on** at [ONSET] and only back **off** at [OFFSET], the lower
 * of the two: once the model has committed to someone talking, it takes a clearer retraction than it
 * took to start. Runs shorter than [MIN_ON_SEC] are then dropped entirely -- below about 150 ms
 * there is no word to attribute, and what is left is the model's uncertainty rather than a person.
 *
 * ## What the output is for, and what it is not
 *
 * These regions are an **additive** track. They never move a boundary, relabel a word or change
 * which speaker holds a turn -- the argmax segmentation is untouched, and diarisation proceeds
 * exactly as if this had not run. All they do is let the transcript say that a stretch was
 * contested, so an unattributed patch reads as `(overlap)` rather than vanishing.
 *
 * Pure arithmetic over the activity array, so the hysteresis is pinned by a JVM test.
 */
object OverlapDetector {

    /** Probability at which a speaker is taken to have started talking. */
    const val ONSET = 0.3f

    /** The lower bar for stopping. The gap between this and [ONSET] is the hysteresis. */
    const val OFFSET = 0.2f

    /** Shorter than this and there is no word in it to attribute. */
    const val MIN_ON_SEC = 0.15f

    /** Two regions closer than this are one contested stretch with a breath in it. */
    const val JOIN_GAP_SEC = 1.0f

    /** A stretch of simultaneous speech, in recording seconds. */
    data class Region(val startSec: Float, val endSec: Float)

    /**
     * Regions of [track] where at least two window-local speakers are active at once, offset into
     * recording time by [windowStartSec].
     *
     * Returned in time order and never overlapping each other.
     */
    fun detect(
        track: PyannoteSegmenter.FrameTrack,
        windowStartSec: Float,
    ): List<Region> {
        val frames = track.cls.size
        if (frames == 0) return emptyList()

        val active = Array(PyannoteSegmenter.SPEAKERS_PER_WINDOW) { BooleanArray(frames) }
        for (speaker in 0 until PyannoteSegmenter.SPEAKERS_PER_WINDOW) {
            val curve = track.act[speaker]
            var on = false
            var start = 0
            for (frame in 0 until frames) {
                val v = curve[frame]
                if (!on && v >= ONSET) {
                    on = true
                    start = frame
                } else if (on && v < OFFSET) {
                    markIfLongEnough(active[speaker], start, frame, track.hopSec)
                    on = false
                }
            }
            if (on) markIfLongEnough(active[speaker], start, frames, track.hopSec)
        }

        val out = ArrayList<Region>()
        var frame = 0
        while (frame < frames) {
            if (activeCount(active, frame) < 2) {
                frame++
                continue
            }
            var end = frame
            while (end < frames && activeCount(active, end) >= 2) end++

            val startSec = windowStartSec + frame * track.hopSec
            val endSec = windowStartSec + end * track.hopSec
            val last = out.lastOrNull()
            if (last != null && startSec - last.endSec < JOIN_GAP_SEC) {
                out[out.size - 1] = Region(last.startSec, maxOf(last.endSec, endSec))
            } else {
                out += Region(startSec, endSec)
            }
            frame = end
        }
        return out
    }

    /**
     * Folds [addition] into [into], joining anything within [JOIN_GAP_SEC] of what is already there.
     *
     * Windows are processed in order and a contested stretch routinely spans the seam between two of
     * them, so regions are accumulated across windows rather than per window.
     */
    fun accumulate(into: MutableList<Region>, addition: List<Region>) {
        for (region in addition) {
            val last = into.lastOrNull()
            if (last != null && region.startSec - last.endSec < JOIN_GAP_SEC) {
                into[into.size - 1] = Region(last.startSec, maxOf(last.endSec, region.endSec))
            } else {
                into += region
            }
        }
    }

    /** Whether any region in [regions] intersects `[startSec, endSec)`. */
    fun intersects(regions: List<Region>, startSec: Float, endSec: Float): Boolean =
        regions.any { it.startSec < endSec && it.endSec > startSec }

    private fun markIfLongEnough(into: BooleanArray, from: Int, until: Int, hopSec: Float) {
        if ((until - from) * hopSec < MIN_ON_SEC) return
        for (i in from until until) into[i] = true
    }

    private fun activeCount(active: Array<BooleanArray>, frame: Int): Int {
        var count = 0
        for (speaker in active.indices) if (active[speaker][frame]) count++
        return count
    }
}
