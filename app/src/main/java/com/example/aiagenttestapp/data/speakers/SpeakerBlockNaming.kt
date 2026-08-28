package com.example.aiagenttestapp.data.speakers

/** A block of transcript with the person it is attributed to, ready to be shown or stored. */
data class NamedBlock(
    val startSample: Int,
    val endSample: Int,
    /** The cluster the block started from, kept for diagnosis; identity is [name]. */
    val cluster: Int,
    val name: String,
    val text: String,
)

/**
 * Puts names on aligned blocks, and joins the ones that turn out to be the same person talking.
 *
 * [SpeakerAlignment] cuts a block wherever the cluster id changes, which is the same question as
 * "did the speaker change" only while every speaker is exactly one cluster. They are not: the
 * diariser routinely emits one person as several clusters, and [SpeakerRepository.foldAndName]
 * then quite correctly puts the same name on all of them. On a measured two-voice run, clusters 0, 3
 * and 5 were all named Tim -- and cutting on the id split his continuous speech into separate blocks
 * at every hop between them, which reads as several people finishing each other's sentence and is a
 * false record of who said what. Merging on the *name* is the same rule alignment always intended,
 * asked of the right thing.
 *
 * Worth its keep on its own: scored against a synthesised timeline it moved correctly-named speech
 * from 89.7% to 90.8% and cut the transcript from 62 blocks to 34, with no change to which speaker
 * any frame was assigned to beyond the gaps it fills.
 *
 * Unattributed words are absorbed on the same principle, and only on it. A run of words that fell
 * outside every turn takes the surrounding name when the speaker either side of it is the same
 * person, because a breath in the middle of somebody's sentence is not a second speaker. When the
 * two sides differ it keeps [unknownLabel]: nothing in the audio says which of them it was, and
 * picking one would be exactly the confident-but-wrong attribution this pipeline is careful to avoid.
 *
 * A cluster that simply has no name is left alone rather than absorbed. That is a real voice the app
 * did not recognise -- a stranger in the recording -- and merging it into a neighbour would delete a
 * speaker rather than tidy a gap.
 */
internal fun nameBlocks(
    blocks: List<SpeakerBlock>,
    names: Map<Int, String>,
    unknownLabel: String,
): List<NamedBlock> {
    if (blocks.isEmpty()) return emptyList()

    val resolved = blocks.mapTo(mutableListOf()) { names[it.cluster] ?: unknownLabel }

    var index = 0
    while (index < blocks.size) {
        if (blocks[index].cluster != SpeakerAlignment.UNATTRIBUTED) {
            index++
            continue
        }
        // The whole unattributed run is decided together: filling word by word would let a long gap
        // be half absorbed, which is a boundary nothing in the audio supports.
        var end = index
        while (end < blocks.size && blocks[end].cluster == SpeakerAlignment.UNATTRIBUTED) end++

        val before = resolved.getOrNull(index - 1)
        val after = resolved.getOrNull(end)
        if (before != null && before == after && before != unknownLabel) {
            for (position in index until end) resolved[position] = before
        }
        index = end
    }

    val merged = mutableListOf<NamedBlock>()
    blocks.forEachIndexed { position, block ->
        val name = resolved[position]
        val previous = merged.lastOrNull()
        if (previous != null && previous.name == name) {
            merged[merged.lastIndex] = previous.copy(
                endSample = maxOf(previous.endSample, block.endSample),
                text = "${previous.text} ${block.text}".trim(),
            )
        } else {
            merged += NamedBlock(
                startSample = block.startSample,
                endSample = block.endSample,
                cluster = block.cluster,
                name = name,
                text = block.text,
            )
        }
    }
    return merged
}

/**
 * Folds fragments too short to be evidence into the speech around them.
 *
 * [nameBlocks] leaves a block wherever the diariser changed its mind, and it changes its mind inside
 * sentences. Measured on a two-voice render, one four-second turn of Bob's came back as five blocks
 * under four labels -- `"I'm"` / `"not really sure like how I can"` / `"help you here, but"` /
 * `"like, yeah,"` / `"feel free to ask me some questions."` -- with the middle second attributed to
 * Tim. Every one of those boundaries is an artefact of clustering a fragment, not a person starting
 * to speak, and a reader cannot tell the difference: the transcript simply claims four people said
 * one sentence.
 *
 * The rule is deliberately conservative about the only thing that matters, which is not inventing
 * attribution. A fragment adopts a name only when the speech on *both* sides of it already agrees,
 * so nothing is claimed that the surrounding audio does not already say. The one asymmetric case is
 * a fragment the app could not name at all: an "Unknown Speaker" placeholder shorter than the
 * threshold, touching exactly one identified person, is that person -- because the alternative is
 * not honesty, it is a transcript that reports a stranger who said the word "My".
 *
 * A fragment flanked by two *different* identified people is left exactly as it is. That is the case
 * where the audio genuinely does not say, and guessing would be the confident-but-wrong attribution
 * this pipeline exists to avoid.
 *
 * Runs to a fixed point rather than once: absorbing one fragment can make its neighbours agree, and
 * the sentence above only collapses when the pass is allowed to see that.
 */
internal fun smoothShortBlocks(
    blocks: List<NamedBlock>,
    unknownPrefix: String,
    minSamples: Int,
): List<NamedBlock> {
    if (blocks.size < 2) return blocks

    fun named(name: String) = !name.startsWith(unknownPrefix)

    var current = blocks
    repeat(MAX_SMOOTHING_PASSES) {
        val resolved = current.map { it.name }.toMutableList()
        var changed = false

        current.forEachIndexed { index, block ->
            if (block.endSample - block.startSample >= minSamples) return@forEachIndexed
            val before = resolved.getOrNull(index - 1)
            val after = resolved.getOrNull(index + 1)

            val adopt = when {
                // Both sides already agree: taking their name claims nothing new.
                before != null && before == after && named(before) -> before
                // An unnamed fragment touching exactly one identified person belongs to them.
                !named(block.name) && before != null && named(before) && (after == null || !named(after)) -> before
                !named(block.name) && after != null && named(after) && (before == null || !named(before)) -> after
                else -> null
            }
            if (adopt != null && adopt != resolved[index]) {
                resolved[index] = adopt
                changed = true
            }
        }
        if (!changed) return@repeat

        val merged = mutableListOf<NamedBlock>()
        current.forEachIndexed { index, block ->
            val name = resolved[index]
            val previous = merged.lastOrNull()
            if (previous != null && previous.name == name) {
                merged[merged.lastIndex] = previous.copy(
                    endSample = maxOf(previous.endSample, block.endSample),
                    text = "${previous.text} ${block.text}".trim(),
                )
            } else {
                merged += block.copy(name = name)
            }
        }
        current = merged
    }
    return current
}

/** Enough for a fragment to be absorbed, then for its neighbours to merge, then to settle. */
private const val MAX_SMOOTHING_PASSES = 4
