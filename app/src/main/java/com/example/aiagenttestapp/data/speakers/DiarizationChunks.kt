package com.example.aiagenttestapp.data.speakers

import com.example.aiagenttestapp.stt.DiarizedSegment

/** One stretch of the compacted recording to diarise on its own, in compacted samples. */
data class DiarizationChunk(
    val startSample: Int,
    val endSample: Int,
) {
    val length: Int get() = endSample - startSample
}

/**
 * Splits a long recording into stretches that are diarised separately.
 *
 * **Why this exists.** Embedding the segmentation windows is linear in duration and is most of the
 * work, so splitting cannot make it cheaper. Clustering and fragment folding are not linear -- both
 * compare turns against each other -- and that is what makes a long recording disproportionately
 * slow. Measured on the Xiaomi: a 288.6s recording ran at 0.209 seconds of work per second of
 * audio, a 1236s one at 0.367. Scaled from the short run the long one should have taken 250s; it
 * took 454. Splitting bounds the quadratic term to whatever fits in one chunk.
 *
 * Those figures are from before CAM++, the 0.5 window shift and XNNPACK. Re-measured on 2026-08-31
 * on the same Xiaomi, the whole 1322s German recording diarised in 60.6s against 48.5s for four
 * chunks on four lanes: the quadratic term is now a quarter of the time rather than most of it, and
 * chunking earns its keep through the lanes and the fold overlap more than through the clustering
 * bound. A recording that cannot be chunked is slower, not hopeless.
 *
 * **Why it is only safe when everyone is enrolled.** Cluster ids are meaningless across chunks:
 * cluster 0 in the first chunk has no relationship to cluster 0 in the second, and nothing
 * downstream can discover that they are the same person. Naming is what stitches the chunks back
 * together, and naming only works for a voice that has been enrolled. With nobody enrolled the
 * chunks would come back as a row of strangers, so [DiarizeWorker] diarises the whole recording at
 * once in that case, and accepts the cost.
 *
 * **A stranger in an enrolled recording still fragments.** Someone with no voiceprint gets a fresh
 * "Speaker N" in every chunk they appear in, because there is nothing to match them against. That
 * is the known cost of this split and the reason chunks are as long as they are: fewer boundaries,
 * fewer duplicates of an unenrolled voice.
 *
 * Cuts prefer a splice left by silence compaction. A splice is where at least 1.5 seconds of
 * silence used to be, which makes it the least likely place in the whole recording to be in the
 * middle of a word, and usually a turn boundary as well.
 */
object DiarizationChunks {

    /**
     * Long enough that the quadratic term stays small, short enough that a few of them fit in a
     * twenty-minute recording. Also the unit an unenrolled voice fragments into, so shorter is not
     * automatically better.
     *
     * **Raised from 150 after measuring what smaller chunks cost.** Eight chunks over a 20:36
     * recording cut diarisation from 267.0s to 186.4s, but produced 16 clusters where the whole-file
     * run produced 8 -- and every one of those clusters has to be folded and then named. Folding
     * went 13.0s to 24.7s and naming 12.9s to 33.1s, giving back 32 of the 81 seconds saved. Four
     * chunks keep most of the diarisation saving and roughly halve that give-back.
     */
    const val TARGET_SECONDS = 300

    /** No chunk shorter than this: a stub has too little voice for clustering to mean anything. */
    const val MIN_SECONDS = 45

    /** How far from the ideal cut a splice may be and still be worth cutting at instead. */
    const val SNAP_SECONDS = 20

    /**
     * Plans the chunk boundaries, in compacted coordinates.
     *
     * Returns a single chunk covering everything when the recording is too short to be worth
     * splitting, so callers have one code path rather than two.
     *
     * @param spliceBoundaries compacted offsets where compaction joined two pieces of speech.
     */
    fun plan(
        totalSamples: Int,
        sampleRate: Int,
        spliceBoundaries: List<Int> = emptyList(),
        targetSeconds: Int = TARGET_SECONDS,
        minSeconds: Int = MIN_SECONDS,
        snapSeconds: Int = SNAP_SECONDS,
    ): List<DiarizationChunk> {
        if (totalSamples <= 0) return emptyList()

        val target = targetSeconds * sampleRate
        val min = minSeconds * sampleRate
        val snap = snapSeconds * sampleRate

        // Splitting is only worth it if the tail left over is itself a usable chunk.
        if (totalSamples < target + min) return listOf(DiarizationChunk(0, totalSamples))

        val splices = spliceBoundaries.filter { it > 0 && it < totalSamples }.sorted()
        val chunks = mutableListOf<DiarizationChunk>()
        var start = 0

        while (totalSamples - start >= target + min) {
            val ideal = start + target
            val cut = splices
                .filter { it in (ideal - snap)..(ideal + snap) && it - start >= min }
                .minByOrNull { kotlin.math.abs(it - ideal) }
                ?: ideal
            chunks += DiarizationChunk(start, cut)
            start = cut
        }
        chunks += DiarizationChunk(start, totalSamples)
        return chunks
    }

    /**
     * Shifts a chunk's cluster ids clear of every id already used.
     *
     * sherpa numbers clusters from zero in every chunk, so without this the second chunk's
     * "cluster 0" would silently merge with the first chunk's. Returns the next free id alongside
     * the shifted turns so the caller can thread it through without tracking it separately.
     */
    fun namespaced(
        turns: List<DiarizedSegment>,
        nextFreeCluster: Int,
    ): Pair<List<DiarizedSegment>, Int> {
        if (turns.isEmpty()) return turns to nextFreeCluster
        val shifted = turns.map { it.copy(cluster = it.cluster + nextFreeCluster) }
        return shifted to (shifted.maxOf { it.cluster } + 1)
    }

    /** Moves a chunk's turns from chunk-local samples back into compacted coordinates. */
    fun toCompacted(
        turns: List<DiarizedSegment>,
        chunk: DiarizationChunk,
    ): List<DiarizedSegment> = turns.map {
        it.copy(
            startSample = it.startSample + chunk.startSample,
            endSample = it.endSample + chunk.startSample,
        )
    }
}
