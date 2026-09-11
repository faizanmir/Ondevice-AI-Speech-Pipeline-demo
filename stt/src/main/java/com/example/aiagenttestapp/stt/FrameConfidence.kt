package com.example.aiagenttestapp.stt

/**
 * How sure the segmentation model was, frame by frame, across a whole recording.
 *
 * ## What this is for
 *
 * Word attribution has to answer "who said this word", and a word that straddles a hand-over gets
 * that answer from whichever speaker holds more of it. *More* is the question this array settles:
 * counting seconds treats a frame the model was certain about the same as one it nearly tossed a
 * coin over, and at a hand-over the uncertain frames are exactly the ones in dispute. Weighting each
 * frame by the model's own top-versus-second margin makes the confident part of the word decide it.
 *
 * ## Why it carries no speaker
 *
 * The obvious design is a per-frame *cluster* track, which is what the diariser computes internally.
 * It is the wrong thing to hand across a pipeline boundary: cluster ids are rewritten three times
 * after diarisation -- folded together, shifted clear of the previous chunk's numbering, and
 * eventually named -- so a track carrying ids would have to be dragged through all three rewrites
 * and would be silently wrong if any of them changed.
 *
 * It is also unnecessary, because the turns already say which speaker holds which stretch, and a
 * frame's speaker *is* the speaker of the turn covering it -- both come from the same segments. So
 * the cluster comes from the turns, which are already correct at the point of use, and only the
 * weight comes from here. That leaves this a plain array of floats in recording time, which needs
 * nothing but coordinate arithmetic to survive chunking.
 *
 * ## Coordinates
 *
 * Recording samples, always -- [Builder] is the only way to build a populated one, and the caller
 * converts before it gets here. Diarisation runs in *compacted* time, where silence has been cut
 * out, so a track built in that space and read against a transcript would drift by however much
 * silence preceded each word.
 */
class FrameConfidence private constructor(
    private val weights: FloatArray,
    private val samplesPerFrame: Int,
) {

    /** True when there is nothing to weigh with, and every caller should fall back. */
    val isEmpty: Boolean get() = weights.isEmpty()

    /**
     * Total confidence-weighted mass in `[fromSample, toSample)`.
     *
     * Zero for a span past the end of the track or one holding no frames, which callers read as
     * "no opinion" rather than as "nobody spoke".
     */
    fun massBetween(fromSample: Int, toSample: Int): Float {
        if (weights.isEmpty() || toSample <= fromSample) return 0f
        val first = (fromSample / samplesPerFrame).coerceIn(0, weights.size - 1)
        val last = ((toSample - 1) / samplesPerFrame).coerceIn(0, weights.size - 1)
        var sum = 0f
        for (frame in first..last) sum += weights[frame]
        return sum
    }

    companion object {

        /** The answer when no diarisation produced a track -- the sherpa engine, or a failed run. */
        val NONE = FrameConfidence(FloatArray(0), 1)
    }

    /**
     * Collects a recording-length track one frame at a time.
     *
     * Frames are placed individually rather than as contiguous blocks, because they are not
     * contiguous: diarisation runs on *compacted* audio with the silences cut out, so consecutive
     * frames of one chunk can land either side of a splice and be seconds apart in the recording.
     * Scattering each one through the caller's own mapping is the only placement that survives that.
     *
     * Frames that fall outside the recording are dropped rather than clamped -- clamping would pile
     * a chunk's tail onto the last frame and invent a very confident final word.
     */
    class Builder(totalSamples: Int, private val samplesPerFrame: Int) {

        private val frames =
            FloatArray(if (samplesPerFrame > 0) totalSamples / samplesPerFrame + 1 else 0)

        fun put(recordingSample: Int, weight: Float) {
            if (samplesPerFrame <= 0) return
            val at = recordingSample / samplesPerFrame
            if (at in frames.indices) frames[at] = weight
        }

        fun build(): FrameConfidence =
            if (frames.isEmpty() || samplesPerFrame <= 0) NONE
            else FrameConfidence(frames, samplesPerFrame)
    }
}
