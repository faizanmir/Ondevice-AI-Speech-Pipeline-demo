package com.example.aiagenttestapp.stt

/**
 * One run of kept audio, and where it came from.
 *
 * [compactedStart] and [originalStart] are both inclusive; the run covers [length] samples in each
 * space. Two coordinate systems for the same speech is the whole difficulty here, and naming them
 * apart at every use is what keeps it survivable -- the same discipline `NoteTranscribeWorker`
 * already applies to window-relative against recording-absolute sample ranges.
 */
data class SpeechPiece(
    val compactedStart: Int,
    val originalStart: Int,
    val length: Int,
) {
    val compactedEnd: Int get() = compactedStart + length
    val originalEnd: Int get() = originalStart + length
}

/**
 * Speech-only audio, plus the map back to the recording it was cut from.
 *
 * Diarisation holds the **entire** recording in memory as floats, because clustering is a global
 * judgement -- it decides how many voices exist by comparing every stretch against every other, so
 * it cannot be done a piece at a time. At 16 kHz that is 3.8 MB a minute: fine for the 20-minute
 * recordings this screen was measured on, 228 MB for an hour-long meeting, and an out-of-memory
 * kill for two hours. `DiarizeWorker` names sherpa's streaming diarisation API as the way past that
 * ceiling; sherpa-onnx has no such API, so the ceiling is real and this is the way past it.
 *
 * Removing silence keeps the property that matters. The clustering still sees all of the speech at
 * once -- only the gaps between it are gone -- so it is the same global comparison over a shorter
 * array, not a per-piece diarisation that would invent a new speaker per piece.
 */
class CompactedAudio(
    val samples: FloatArray,
    val pieces: List<SpeechPiece>,
    /** Length of the recording this was cut from, so callers can report what was saved. */
    val originalSamples: Int,
) {

    /** What fraction of the recording was dropped, 0f when nothing was. */
    val removedFraction: Float
        get() = if (originalSamples == 0) 0f else 1f - samples.size.toFloat() / originalSamples

    /**
     * Where a compacted sample sits in the original recording.
     *
     * Clamped rather than throwing: a rounded boundary landing one sample past the end is an
     * ordinary consequence of converting seconds to samples, and taking down a run that has already
     * cost minutes of two models over it is not a proportionate answer to it.
     */
    fun toOriginal(compactedSample: Int): Int {
        if (pieces.isEmpty()) return compactedSample
        val piece = pieces.lastOrNull { compactedSample >= it.compactedStart }
            ?: return pieces.first().originalStart
        val offset = (compactedSample - piece.compactedStart).coerceAtMost(piece.length)
        return piece.originalStart + offset
    }

    /**
     * Where a compacted range sits in the original recording -- as **one range per piece it spans**.
     *
     * A stretch the diariser attributed to one voice can cross a splice, and in the recording those
     * are two separated stretches with removed silence between them. Returning one range spanning
     * the gap would claim the speaker held the floor through audio that was cut for having no
     * speech in it -- and [com.example.aiagenttestapp.data.speakers.SpeakerAlignment] would then
     * hand any word the recogniser did find in that silence to that speaker.
     *
     * Splitting instead leaves the removed silence covered by no turn at all, which is exactly the
     * state alignment already knows how to handle: it fills a gap only when the turns on both sides
     * agree, and otherwise marks the words unattributed. The two mechanisms compose, and neither
     * had to learn about the other.
     */
    fun toOriginal(compactedRange: IntRange): List<IntRange> {
        if (compactedRange.isEmpty()) return emptyList()
        if (pieces.isEmpty()) return listOf(compactedRange)

        val out = mutableListOf<IntRange>()
        for (piece in pieces) {
            val from = maxOf(compactedRange.first, piece.compactedStart)
            val until = minOf(compactedRange.last + 1, piece.compactedEnd)
            if (from >= until) continue
            val originalFrom = piece.originalStart + (from - piece.compactedStart)
            out += originalFrom until (originalFrom + (until - from))
        }
        return out
    }

    companion object {

        /**
         * Keeps only [regions] of [samples], concatenated, with the map back.
         *
         * **An empty or unusable region list returns the recording untouched.** A voice-activity
         * detector that finds nothing is far more likely to be a detector that failed than a
         * recording with nobody in it, and the two are indistinguishable from here. Compacting to
         * an empty array on that reading would delete the meeting -- the most noticeable bug this
         * class could have, and the same rule `SpeechActivityDetector` already states for itself.
         *
         * Regions are sorted, clamped and merged on the way in rather than trusted. They usually
         * arrive from [SpeechRegions], which already guarantees all three, but a caller assembling
         * them another way should not be able to produce overlapping pieces whose offsets silently
         * disagree.
         */
        fun of(samples: FloatArray, regions: List<IntRange>): CompactedAudio {
            val usable = regions
                .mapNotNull { region ->
                    val first = region.first.coerceIn(0, samples.size)
                    val last = (region.last + 1).coerceIn(0, samples.size)
                    if (last > first) first until last else null
                }
                .sortedBy { it.first }
                .fold(mutableListOf<IntRange>()) { merged, region ->
                    val previous = merged.lastOrNull()
                    if (previous != null && region.first <= previous.last + 1) {
                        merged[merged.lastIndex] = previous.first..maxOf(previous.last, region.last)
                    } else {
                        merged += region
                    }
                    merged
                }

            if (usable.isEmpty()) return untouched(samples)

            val kept = usable.sumOf { it.last - it.first + 1 }
            val out = FloatArray(kept)
            val pieces = mutableListOf<SpeechPiece>()
            var cursor = 0
            for (region in usable) {
                val length = region.last - region.first + 1
                samples.copyInto(out, cursor, region.first, region.first + length)
                pieces += SpeechPiece(
                    compactedStart = cursor,
                    originalStart = region.first,
                    length = length,
                )
                cursor += length
            }
            return CompactedAudio(out, pieces, samples.size)
        }

        /** The recording as it stands, with an identity map -- see the empty-regions rule in [of]. */
        fun untouched(samples: FloatArray) = CompactedAudio(
            samples = samples,
            pieces = listOf(SpeechPiece(0, 0, samples.size)),
            originalSamples = samples.size,
        )
    }
}
