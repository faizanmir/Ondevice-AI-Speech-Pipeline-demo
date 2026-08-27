package com.example.aiagenttestapp.data.speakers

import com.example.aiagenttestapp.stt.DiarizedSegment
import com.example.aiagenttestapp.stt.SpeakerMatchDecision
import com.example.aiagenttestapp.stt.matchSpeaker

/**
 * The outcome of naming one set of clusters: a name for each, the next free "Unknown Speaker"
 * number, and the raw match decisions for the clusters that had a voiceprint.
 *
 * [decisions] is carried out separately from [names] for one reason: the per-cluster match log line
 * is the thing that tells a clustering collapse apart from an alignment bug, and the memory note
 * says to keep it. Returning the decisions lets the caller log them without this function taking a
 * dependency on Android's `Log`, which is what keeps the rule below testable on the JVM.
 */
internal data class ClusterNaming(
    val names: Map<Int, String>,
    val nextPlaceholder: Int,
    val decisions: Map<Int, SpeakerMatchDecision>,
)

/**
 * One chunk's folded turns and the name of each surviving cluster, with the next free "Unknown
 * Speaker" number so the following chunk carries the count on.
 *
 * The turns and names are still in the chunk's own cluster space -- sherpa numbers clusters from
 * zero in every chunk -- so the caller shifts both by the same offset before merging them into the
 * recording-wide result. See [DiarizationChunks.namespaced].
 */
internal data class ClusterAttribution(
    val turns: List<DiarizedSegment>,
    val names: Map<Int, String>,
    val nextPlaceholder: Int,
)

/**
 * Puts a name to each cluster from a **precomputed** voiceprint, numbering the unnamed by first
 * appearance.
 *
 * The naming half of the diarisation post-process, kept pure for the same reason [smallClusterRemap]
 * is: the numbering rule and the accept/reject margin are judgement calls whose failure a unit test
 * should be able to demonstrate without loading a native embedder.
 *
 * **Why it takes voiceprints rather than audio.** Folding already embedded every cluster from its
 * longest turns to decide what to merge, and naming used to embed every survivor a second time from
 * the identical audio -- on a twenty-minute recording that is the single largest avoidable cost in
 * the speaker branch. Passing the map folding built means each cluster is embedded once and both
 * stages read it. A cluster the embedder could not describe is simply absent from [voiceprints],
 * which is treated the same as an unmatched one: it becomes a numbered placeholder.
 *
 * **Why [startingPlaceholder] threads through.** Naming now runs per chunk, and the placeholder
 * counter has to survive the chunk boundary or every chunk would restart at "Unknown Speaker 1" and
 * a transcript could carry two of them. The caller feeds the previous chunk's [nextPlaceholder] in
 * here; the numbering is therefore by first appearance across the whole recording, exactly as a
 * single global pass produced, because chunks are contiguous in time and named in order.
 *
 * @param voiceprints one voiceprint per cluster, as folding computed them; a missing key means the
 *   cluster is unnameable and takes a placeholder.
 * @param enrolled every enrolled person's stored takes, to match each voiceprint against.
 */
internal fun nameClustersByVoiceprint(
    turns: List<DiarizedSegment>,
    voiceprints: Map<Int, FloatArray>,
    enrolled: Map<String, List<FloatArray>>,
    threshold: Float,
    minimumMargin: Float,
    unknownPrefix: String,
    startingPlaceholder: Int,
): ClusterNaming {
    // Numbered by first appearance, so "Speaker 2" is the second person heard rather than whatever
    // index the clustering happened to assign.
    val order = turns.sortedBy { it.startSample }.map { it.cluster }.distinct()

    val names = mutableMapOf<Int, String>()
    val decisions = mutableMapOf<Int, SpeakerMatchDecision>()
    var placeholder = startingPlaceholder

    for (cluster in order) {
        val decision = voiceprints[cluster]?.let { voiceprint ->
            matchSpeaker(
                embedding = voiceprint,
                enrolled = enrolled,
                threshold = threshold,
                minimumMargin = minimumMargin,
            )
        }
        decision?.let { decisions[cluster] = it }
        names[cluster] = decision?.acceptedName ?: "$unknownPrefix ${++placeholder}"
    }

    return ClusterNaming(names, placeholder, decisions)
}
