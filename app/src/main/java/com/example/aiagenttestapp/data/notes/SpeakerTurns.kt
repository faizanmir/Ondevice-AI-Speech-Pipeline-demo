package com.example.aiagenttestapp.data.notes

/**
 * Turns raw diarisation output into the speaker turns a transcriber can afford to run.
 *
 * This is where the accuracy/speed trade lives. Attribution requires transcribing each speaker's audio
 * separately, and Whisper pays a *fixed* cost per call -- its encoder pads every input to 30 seconds
 * regardless of how little audio it was given. So a lively two-person conversation diarised into 120
 * turns costs 120 full encoder runs, several times what the same audio costs in 28-second chunks.
 *
 * Two rules bring that back under control:
 *
 *  - **Merge adjacent turns from the same speaker.** Free: diarisation splits on every pause, and
 *    rejoining them changes nothing about who said what.
 *  - **Absorb turns shorter than a floor into their neighbour.** Not free: a two-second "yeah, agreed"
 *    from the other person ends up attributed to whoever was speaking around it. That is the accepted
 *    cost -- short interjections carry little content, and the alternative is a transcription slow enough
 *    that nobody waits for it.
 *
 * Absorption never crosses a spoken marker boundary, which is the one place the trade is not acceptable:
 * if someone opens a non-conformity, the identity of whoever is speaking inside it is the point.
 */
object SpeakerTurns {

    /**
     * @param segments raw diarisation output, in samples, any order.
     * @param totalSamples length of the recording, so the last turn can be extended to the end.
     * @param minTurnSamples the absorption floor. Turns shorter than this lose their identity.
     * @param protectedBoundaries sample offsets that must not be merged across -- the edges of spoken
     *   marker phrases.
     */
    fun build(
        segments: List<DiarizedRange>,
        totalSamples: Int,
        minTurnSamples: Int,
        protectedBoundaries: Set<Int> = emptySet(),
    ): List<SpeakerTurn> {
        if (segments.isEmpty() || totalSamples <= 0) return emptyList()

        val ordered = segments
            .filter { it.until > it.from }
            .sortedBy { it.from }
            .map { SpeakerTurn(it.from.coerceIn(0, totalSamples) until it.until.coerceIn(0, totalSamples), it.cluster) }
            .filter { it.range.last >= it.range.first }

        if (ordered.isEmpty()) return emptyList()

        // Diarisation reports only speech, so there are gaps. Each gap is given to the turn before it,
        // which keeps the turns tiling the recording -- otherwise every pause becomes its own slice and
        // gets its own (empty) transcription call.
        val tiled = mutableListOf<SpeakerTurn>()
        ordered.forEachIndexed { index, turn ->
            val from = if (index == 0) 0 else tiled.last().range.last + 1
            val until = if (index == ordered.lastIndex) {
                totalSamples
            } else {
                maxOf(turn.range.last + 1, from + 1)
            }
            if (until > from) tiled += turn.copy(range = from until until)
        }

        // Merge, absorb, then merge again. Absorption hands a short turn's audio to a neighbour, which
        // can leave two same-speaker turns sitting next to each other -- and every extra turn is another
        // full Whisper encoder run, which is the cost this whole function exists to control. Merging
        // afterwards cannot create new short turns, since it only ever lengthens them.
        val merged = mergeSame(tiled, protectedBoundaries)
        val absorbed = absorbShort(merged, minTurnSamples, protectedBoundaries)
        return mergeSame(absorbed, protectedBoundaries)
    }

    /** Collapses runs of the same cluster, except across a protected boundary. */
    private fun mergeSame(
        turns: List<SpeakerTurn>,
        protectedBoundaries: Set<Int>,
    ): List<SpeakerTurn> {
        val merged = mutableListOf<SpeakerTurn>()

        for (turn in turns) {
            val last = merged.lastOrNull()
            val junction = last?.range?.last?.plus(1)

            if (last != null && last.cluster == turn.cluster && junction !in protectedBoundaries) {
                merged[merged.lastIndex] = last.copy(range = last.range.first until turn.range.last + 1)
            } else {
                merged += turn
            }
        }

        return merged
    }

    /**
     * Repeatedly folds the shortest under-floor turn into its longer neighbour.
     *
     * Shortest-first rather than left-to-right: a run of three short turns processed in order would fold
     * the first into the second, then re-examine a now-longer turn and stop, leaving the rest short.
     * Taking the shortest each time converges on the same result regardless of order.
     */
    private fun absorbShort(
        turns: List<SpeakerTurn>,
        minTurnSamples: Int,
        protectedBoundaries: Set<Int>,
    ): List<SpeakerTurn> {
        val working = turns.toMutableList()

        while (working.size > 1) {
            val index = working
                .withIndex()
                .filter { (i, turn) ->
                    turn.range.count() < minTurnSamples && hasMergeableNeighbour(working, i, protectedBoundaries)
                }
                .minByOrNull { it.value.range.count() }
                ?.index
                ?: break

            val target = mergeTargetFor(working, index, protectedBoundaries) ?: break
            val short = working[index]
            val keep = working[target]

            val from = minOf(short.range.first, keep.range.first)
            val until = maxOf(short.range.last, keep.range.last) + 1

            working[target] = keep.copy(range = from until until)
            working.removeAt(index)
        }

        return working
    }

    private fun hasMergeableNeighbour(
        turns: List<SpeakerTurn>,
        index: Int,
        protectedBoundaries: Set<Int>,
    ): Boolean = mergeTargetFor(turns, index, protectedBoundaries) != null

    /**
     * Which neighbour a short turn should join: the longer of the two, if it may be absorbed at all.
     *
     * The longer neighbour wins because it is the more likely owner of the surrounding conversation, and
     * because attributing two seconds to somebody who has been talking for a minute misleads less than
     * the reverse.
     *
     * A turn touching a marker boundary is never absorbed, in *either* direction. Absorption always
     * replaces the short turn's speaker with its neighbour's -- that is what it is -- so allowing it to
     * merge forwards instead of backwards would destroy exactly the same attribution. Inside a tagged
     * non-conformity, who is speaking is the point of the tag.
     */
    private fun mergeTargetFor(
        turns: List<SpeakerTurn>,
        index: Int,
        protectedBoundaries: Set<Int>,
    ): Int? {
        val current = turns[index]

        if (current.range.first in protectedBoundaries) return null
        if (current.range.last + 1 in protectedBoundaries) return null

        val previousLength = if (index > 0) turns[index - 1].range.count() else -1
        val nextLength = if (index < turns.lastIndex) turns[index + 1].range.count() else -1

        return when {
            previousLength < 0 && nextLength < 0 -> null
            previousLength >= nextLength -> index - 1
            else -> index + 1
        }
    }
}

/** A diarisation segment as plain numbers, so turn building needs nothing from the stt layer. */
data class DiarizedRange(
    val from: Int,
    val until: Int,
    val cluster: Int,
)
