package com.example.aiagenttestapp.data.speakers.live

/** One stretch of the recording handed to the models on its own, in recording samples. */
data class LiveChunk(
    val index: Int,
    val startSample: Int,
    val endSample: Int,
) {
    val length: Int get() = endSample - startSample
}

/**
 * Decides where a live stream is cut into chunks, from what the streaming VAD has settled so far.
 *
 * The batch pipeline plans its chunks up front from the whole recording ([com.example.aiagenttestapp.data.speakers.DiarizationChunks]).
 * Here the recording does not exist yet -- audio arrives on a clock -- so the decision is made
 * incrementally: after every block fed to the VAD, [cutPoint] asks whether the audio classified so
 * far ends a chunk.
 *
 * **The rule.** A chunk must hold at least [minSamples] of audio before it may be cut, and is then
 * cut at the first **pause** after that point -- the end of a settled speech region that is followed
 * by at least [padSamples] of classified silence, cutting [padSamples] into the pause so the last
 * word is never clipped. A speaker who does not pause is cut at [maxSamples] regardless; that case
 * is returned as [Cut.AtCap] with the window the caller should search for the quietest frame, since
 * this class sees regions and not samples.
 *
 * **Why 30 s.** Longer chunks give the diariser more to cluster (its clustering wants ~45 s to be
 * meaningful; 30 s is the compromise with latency) and give the speaker tracker cleaner voiceprints;
 * shorter ones show words sooner. The floor is a product choice made on 2026-08-31; the cap keeps a
 * monologue from becoming a two-minute chunk.
 *
 * Pure and stateful only in where the current chunk starts, so the cutting rule is pinned by a JVM
 * test rather than reproduced on a device with a stopwatch.
 */
class LiveChunker(
    private val minSamples: Int,
    private val maxSamples: Int,
    private val padSamples: Int,
) {
    init {
        require(minSamples > 0 && maxSamples > minSamples) { "need 0 < min < max, got $minSamples / $maxSamples" }
        require(padSamples >= 0)
    }

    /** Where the chunk being built starts, in recording samples. */
    var chunkStart: Int = 0
        private set

    private var nextIndex = 0

    sealed interface Cut {
        /** Cut exactly here: inside a pause the VAD has already settled. */
        data class AtSilence(val sample: Int) : Cut

        /** No pause turned up in time; cut somewhere in `searchFrom until searchUntil`, the quieter the better. */
        data class AtCap(val searchFrom: Int, val searchUntil: Int) : Cut
    }

    /**
     * @param regions settled speech regions in recording samples, ascending and non-overlapping.
     * @param classifiedUpTo the VAD has decided everything before this sample; audio past it may
     *   still be speech in progress, so a gap that reaches it is not yet known to be a pause.
     * @param consumed samples fed to the VAD so far.
     */
    fun cutPoint(regions: List<IntRange>, classifiedUpTo: Int, consumed: Int): Cut? {
        if (consumed - chunkStart >= maxSamples) {
            return Cut.AtCap(chunkStart + minSamples, chunkStart + maxSamples)
        }
        val earliest = chunkStart + minSamples
        if (classifiedUpTo < earliest) return null

        for (i in regions.indices) {
            val end = regions[i].last + 1
            if (end <= chunkStart) continue
            if (end < earliest) continue
            if (end > classifiedUpTo) break
            val nextStart = regions.getOrNull(i + 1)?.first ?: classifiedUpTo
            val pause = nextStart - end
            if (pause >= padSamples) {
                return Cut.AtSilence((end + padSamples).coerceAtMost(consumed))
            }
        }
        return null
    }

    /** Closes the chunk at [cutAt] and starts the next one there. */
    fun commit(cutAt: Int): LiveChunk {
        require(cutAt > chunkStart) { "cut at $cutAt is not past the chunk start $chunkStart" }
        val chunk = LiveChunk(nextIndex++, chunkStart, cutAt)
        chunkStart = cutAt
        return chunk
    }

    /** The tail once the stream has ended, or null when nothing is left over. */
    fun finish(consumed: Int): LiveChunk? = if (consumed > chunkStart) commit(consumed) else null
}
