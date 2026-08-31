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
    /**
     * The voiceprint each surviving cluster was named from, so the caller can carry an unnamed voice
     * into the next chunk's naming as a known one -- see `DiarizeWorker`'s consumer loop. Absent for
     * a cluster the embedder could not describe.
     */
    val voiceprints: Map<Int, FloatArray> = emptyMap(),
)

/**
 * One surviving cluster of a chunk, described rather than named: how much it spoke, what it sounds
 * like, and what enrolment made of it.
 *
 * The live pipeline's input. Where [ClusterAttribution] answers "what is this cluster called" for a
 * batch run that will never see these voiceprints again, this keeps the voiceprint so a
 * [com.example.aiagenttestapp.data.speakers.live.SessionSpeakerTracker] can match the cluster against
 * the voices heard in *earlier chunks* -- the comparison the batch path never makes because naming
 * does its stitching for it. [enrolled] is the full decision, not just a name, so the caller can see
 * a near-miss rather than only its absence.
 */
internal data class ClusterProfile(
    val cluster: Int,
    val samples: Int,
    val voiceprint: FloatArray?,
    val enrolled: SpeakerMatchDecision?,
)

/** A chunk's folded turns and a [ClusterProfile] for every cluster that survived folding. */
internal data class ClusterProfiles(
    val turns: List<DiarizedSegment>,
    val profiles: List<ClusterProfile>,
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
 * **Why [tieBreakable] exists.** The margin rule refuses a winner that barely beats the runner-up,
 * because between two *enrolled people* that is a coin flip and a wrong name in a transcript is
 * worse than no name. Between two *live-session labels* it is the opposite situation: if a cluster
 * matches "Speaker B" at 0.948 and "Speaker A" at 0.932, A and B were one voice the live tracker
 * happened to split, and either letter is a label the user has already been shown for it. Numbering
 * that cluster afresh -- which is what the margin rule did on the first hand-over -- threw away the
 * only thing the roster was for. So when the best and the runner-up are both tie-breakable labels
 * and the best clears the threshold, the best is accepted. An enrolled name in either position keeps
 * the strict rule.
 *
 * @param voiceprints one voiceprint per cluster, as folding computed them; a missing key means the
 *   cluster is unnameable and takes a placeholder.
 * @param enrolled every enrolled person's stored takes, to match each voiceprint against -- plus any
 *   voices that exist only for this recording, keyed by their labels.
 * @param tieBreakable the labels in [enrolled] that are session labels rather than enrolled people.
 */
internal fun nameClustersByVoiceprint(
    turns: List<DiarizedSegment>,
    voiceprints: Map<Int, FloatArray>,
    enrolled: Map<String, List<FloatArray>>,
    threshold: Float,
    minimumMargin: Float,
    unknownPrefix: String,
    startingPlaceholder: Int,
    tieBreakable: Set<String> = emptySet(),
): ClusterNaming {
    // Numbered by first appearance, so "Speaker 2" is the second person heard rather than whatever
    // index the clustering happened to assign.
    val order = turns.sortedBy { it.startSample }.map { it.cluster }.distinct()

    val names = mutableMapOf<Int, String>()
    val decisions = mutableMapOf<Int, SpeakerMatchDecision>()
    var placeholder = startingPlaceholder

    for (cluster in order) {
        val decision = voiceprints[cluster]?.let { voiceprint ->
            val raw = matchSpeaker(
                embedding = voiceprint,
                enrolled = enrolled,
                threshold = threshold,
                minimumMargin = minimumMargin,
            )
            val tieBroken = raw.acceptedName == null &&
                raw.bestName != null &&
                raw.bestName in tieBreakable &&
                raw.bestScore >= threshold &&
                (raw.runnerUpName == null || raw.runnerUpName in tieBreakable)
            if (tieBroken) raw.copy(acceptedName = raw.bestName) else raw
        }
        decision?.let { decisions[cluster] = it }
        names[cluster] = decision?.acceptedName ?: "$unknownPrefix ${++placeholder}"
    }

    return ClusterNaming(names, placeholder, decisions)
}
