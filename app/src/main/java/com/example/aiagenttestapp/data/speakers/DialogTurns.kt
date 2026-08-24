package com.example.aiagenttestapp.data.speakers

/** One uninterrupted stretch of one person talking, as a reader meets it. */
data class DialogTurn(
    /** The first block's id, so the list has a key that is stable while a run is being read. */
    val id: Long,
    val speakerName: String,
    /** The first block's cluster. Only the name is shown; this is here for anything that maps back. */
    val cluster: Int,
    val startSample: Int,
    val endSample: Int,
    val text: String,
)

/**
 * Groups a transcript into the turns of a conversation, for reading.
 *
 * A [DiarizedBlock] is a *diarisation* unit, not a conversational one: [SpeakerAlignment] ends one
 * wherever the cluster changes, so two adjacent blocks always come from two different clusters. Two
 * different clusters are not two different people. The diariser over-splits -- habitually, on short
 * or noisy audio, which is the entire reason the screen asks how many people are in the recording --
 * and when both halves are then matched to the same enrolled voice, the transcript reads as that
 * person interrupting themselves every few sentences. Nothing about the audio changed; the page just
 * became hard to follow.
 *
 * So the grouping is on the name the reader can actually see, and it happens here rather than in the
 * database. The blocks on disk stay one-per-cluster because that is what a re-run and the per-speaker
 * summary need -- the cluster is the identity the models decided on, and collapsing it at write time
 * would throw away the evidence that the split happened at all.
 */
object DialogTurns {

    fun from(blocks: List<DiarizedBlock>): List<DialogTurn> {
        val turns = mutableListOf<DialogTurn>()

        for (block in blocks) {
            val open = turns.lastOrNull()
            if (open != null && open.speakerName == block.speakerName) {
                turns[turns.lastIndex] = open.copy(
                    // The end moves; the start and the id belong to the turn's first block, because
                    // that is where the reader's eye and the list's key both go.
                    endSample = maxOf(open.endSample, block.endSample),
                    text = joined(open.text, block.text),
                )
            } else {
                turns += DialogTurn(
                    id = block.id,
                    speakerName = block.speakerName,
                    cluster = block.cluster,
                    startSample = block.startSample,
                    endSample = block.endSample,
                    text = block.text.trim(),
                )
            }
        }

        return turns
    }

    private fun joined(before: String, after: String): String = when {
        before.isBlank() -> after.trim()
        after.isBlank() -> before
        else -> "$before ${after.trim()}"
    }
}
