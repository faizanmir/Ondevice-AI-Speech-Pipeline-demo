package com.example.aiagenttestapp.data.speakers

/** What one voice did across a recording. */
data class SpeakerStat(
    val name: String,
    val cluster: Int,
    /** How many separate times they held the floor. */
    val turns: Int,
    val speakingMillis: Long,
    /** Their share of the *spoken* time, 0..1 -- silence is not in the denominator. */
    val share: Float,
    /** When they first said anything, from the start of the recording. */
    val firstAtMillis: Long,
    /**
     * Whether the app put a name to them or fell back to a placeholder.
     *
     * Derived from the label rather than stored, because the label is what was decided at the time
     * the run finished -- see [DiarizedBlock.speakerName]. Re-deriving it from today's enrolled list
     * would relabel an old transcript when someone is enrolled or deleted afterwards.
     */
    val enrolled: Boolean,
)

/**
 * Turns a finished transcript into a per-speaker summary.
 *
 * Computed from the blocks rather than stored alongside them: every number here is a sum over what
 * is already on disk, and a second copy would be one more thing to keep true when a run is repeated.
 *
 * Share is of *speech*, not of the recording. A conversation with long pauses would otherwise report
 * everyone as a minority of the time and leave the reader doing arithmetic to find out who dominated
 * it -- which is the one question this summary exists to answer.
 */
object SpeakerStats {

    fun from(blocks: List<DiarizedBlock>, sampleRate: Int): List<SpeakerStat> {
        if (blocks.isEmpty()) return emptyList()

        fun millis(samples: Int): Long = samples * 1000L / sampleRate

        val byName = blocks.groupBy { it.speakerName }
        val spokenMillis = blocks.sumOf { millis(it.endSample - it.startSample) }

        return byName
            .map { (name, theirs) ->
                val speaking = theirs.sumOf { millis(it.endSample - it.startSample) }
                SpeakerStat(
                    name = name,
                    cluster = theirs.first().cluster,
                    turns = theirs.size,
                    speakingMillis = speaking,
                    // Guarded because a transcript of blocks that all round to zero milliseconds is
                    // reachable on a very short clip, and a share of NaN renders as "NaN%".
                    share = if (spokenMillis > 0) speaking.toFloat() / spokenMillis else 0f,
                    firstAtMillis = theirs.minOf { millis(it.startSample) },
                    enrolled = !name.startsWith(SpeakerRepository.UNKNOWN_SPEAKER_PREFIX),
                )
            }
            // In the order they first spoke, which is how someone reads a conversation back. Sorting
            // by talk time would put the summary in a different order from the transcript below it.
            .sortedBy { it.firstAtMillis }
    }
}
