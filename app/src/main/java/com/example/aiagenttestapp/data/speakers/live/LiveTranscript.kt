package com.example.aiagenttestapp.data.speakers.live

import com.example.aiagenttestapp.data.speakers.NamedBlock
import com.example.aiagenttestapp.data.speakers.SpeakerAlignment
import com.example.aiagenttestapp.data.speakers.SpeakerBlock
import com.example.aiagenttestapp.data.speakers.nameBlocks
import com.example.aiagenttestapp.data.speakers.smoothShortBlocks

/**
 * The provisional transcript of a live session, one chunk at a time.
 *
 * Holds each chunk's aligned blocks with the **session speaker id** in the cluster slot, and renders
 * the whole recording's blocks on demand with whatever labels the tracker currently gives those ids.
 * Rendering late, rather than storing names, is what lets an earlier chunk's "Speaker A" become
 * "Bob" the moment a later chunk recognises him -- nothing has to go back and rewrite anything.
 *
 * Chunks may be recorded out of order (a lane can finish chunk 3 before chunk 2 in a backlog); the
 * render sorts by chunk index, so what is shown is always in time order and never has a gap that
 * later fills in behind text already on screen.
 */
class LiveTranscript {

    private val chunks = sortedMapOf<Int, List<SpeakerBlock>>()

    fun record(chunkIndex: Int, blocks: List<SpeakerBlock>) {
        chunks[chunkIndex] = blocks
    }

    val chunkCount: Int get() = chunks.size

    /**
     * Every block so far, named and smoothed exactly as the batch pipeline names and smooths its
     * final transcript, so the provisional view has the same shape as what will replace it.
     *
     * @param labels session speaker id to display label, from the tracker.
     * @param unknownLabel what an unattributed block ([SpeakerAlignment.UNATTRIBUTED]) is called.
     * @param unknownPrefix the prefix smoothing uses to recognise an unattributed block.
     * @param minSamples blocks shorter than this are folded into a neighbour.
     */
    fun render(
        labels: Map<Int, String>,
        unknownLabel: String,
        unknownPrefix: String,
        minSamples: Int,
    ): List<NamedBlock> {
        val all = chunks.values.flatten()
        if (all.isEmpty()) return emptyList()
        return smoothShortBlocks(nameBlocks(all, labels, unknownLabel), unknownPrefix, minSamples)
    }
}
