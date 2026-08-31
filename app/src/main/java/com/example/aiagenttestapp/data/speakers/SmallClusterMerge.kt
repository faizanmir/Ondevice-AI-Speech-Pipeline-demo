package com.example.aiagenttestapp.data.speakers

import com.example.aiagenttestapp.stt.DiarizedSegment
import com.example.aiagenttestapp.stt.cosineSimilarity

/**
 * Folds clusters too small to be a speaker into whichever real speaker they sound most like.
 *
 * ## Why this is here and not in sherpa
 *
 * sherpa's clustering is a port of pyannote's pipeline, and this step did not come with it. pyannote
 * finishes agglomerative clustering with a post-processing pass -- `min_cluster_size: 12` in its
 * published 3.1 configuration -- that reassigns every under-size cluster to the nearest large one by
 * cosine distance between centroids:
 *
 * ```python
 * small_clusters = cluster_unique[cluster_counts < min_cluster_size]
 * centroids_cdist = cdist(large_centroids, small_centroids, metric=self.metric)
 * for small_k, large_k in enumerate(np.argmin(centroids_cdist, axis=0)):
 *     clusters[clusters == small_clusters[small_k]] = large_clusters[large_k]
 * ```
 *
 * `fast-clustering.cc` has no equivalent: it cuts the dendrogram and returns those labels unchanged.
 * The only `min_cluster_size` anywhere in sherpa-onnx is in a reference script that reproduces
 * pyannote in PyTorch, so the parameter was known and simply did not reach the shipped runtime.
 *
 * The consequence is visible in every transcript this app has produced. A two-person recording came
 * back with clusters of 52.8 s, 47.6 s, 71.4 s, 24.2 s and 14.1 s -- and alongside them 5.7 s, 1.0 s
 * and 0.8 s, each of which became its own "Unknown Speaker" in the middle of somebody's sentence.
 * Under pyannote's algorithm those three could not have survived as speakers.
 *
 * ## Why by voice rather than by position
 *
 * [smoothShortBlocks] does something similar on the finished transcript, using duration and which
 * blocks happen to be adjacent. This is the better evidence and runs first: a fragment is assigned to
 * whichever enrolled-or-not cluster its *voice* is closest to, which is what the audio actually says.
 * Position is only a proxy for that, and a poor one at a turn boundary -- exactly where these
 * fragments occur.
 *
 * @param sizes total speech, in samples, held by each cluster.
 * @param centroids one voiceprint per cluster. A cluster the embedder could not describe is absent,
 *   and is left alone rather than guessed at.
 * @return the clusters to rewrite, as old id to new id. Empty when nothing should move.
 */
internal fun smallClusterRemap(
    sizes: Map<Int, Int>,
    centroids: Map<Int, FloatArray>,
    minClusterSamples: Int,
): Map<Int, Int> {
    val small = sizes.filter { it.value < minClusterSamples }.keys
    val large = sizes.filter { it.value >= minClusterSamples }.keys
    // Nothing to move, or nothing to move it into. A recording that is *all* fragments is one the
    // diariser failed on outright, and inventing a winner among them would not make it right.
    if (small.isEmpty() || large.isEmpty()) return emptyMap()

    return small.mapNotNull { from ->
        val source = centroids[from] ?: return@mapNotNull null
        val nearest = large
            .filter { centroids[it] != null }
            .maxByOrNull { cosineSimilarity(source, centroids.getValue(it)) }
            ?: return@mapNotNull null
        from to nearest
    }.toMap()
}

/**
 * Folds a **minor** cluster into the major cluster it sounds like.
 *
 * [smallClusterRemap] catches fragments by size alone: anything under a few seconds is not a
 * speaker. It cannot catch the other thing clustering produces on a long recording -- a *phantom*: a
 * cluster of a minute or so made of scraps of two real voices, which the threshold cut left standing
 * because its mixed voiceprint sits between them. On the 22-minute German recording that phantom
 * held 30-60 s and appeared as a third "Unknown Speaker" with thirty-odd short turns.
 *
 * Two conditions, both required. The cluster must be **minor** -- under [maxShare] of all the speech
 * in what was diarised, because a real participant in a conversation holds far more than a few per
 * cent of it. And its voiceprint must be at least [minSimilarity] to the major cluster it would join,
 * so that a quiet third person who genuinely sounds different is left alone. Neither alone is safe:
 * size alone would swallow a taciturn participant; similarity alone would merge the two same-gender
 * voices that sit at ~0.73 on this app's benchmark file.
 *
 * @param sizes total speech per cluster in samples, after the size fold has been applied.
 * @param centroids one voiceprint per cluster; a cluster without one is left alone.
 * @return old id to new id for every minor cluster that has a lookalike major one.
 */
internal fun lookalikeClusterRemap(
    sizes: Map<Int, Int>,
    centroids: Map<Int, FloatArray>,
    maxShare: Float,
    minSimilarity: Float,
): Map<Int, Int> {
    val total = sizes.values.sumOf { it.toLong() }
    if (total <= 0L) return emptyMap()
    val minor = sizes.filter { it.value < total * maxShare }.keys
    val major = sizes.keys - minor
    if (minor.isEmpty() || major.isEmpty()) return emptyMap()

    return minor.mapNotNull { from ->
        val source = centroids[from] ?: return@mapNotNull null
        val (nearest, similarity) = major
            .filter { centroids[it] != null }
            .map { it to cosineSimilarity(source, centroids.getValue(it)) }
            .maxByOrNull { it.second }
            ?: return@mapNotNull null
        if (similarity >= minSimilarity) from to nearest else null
    }.toMap()
}

/** Applies [smallClusterRemap]'s decision to the turns themselves. */
internal fun applyClusterRemap(
    turns: List<DiarizedSegment>,
    remap: Map<Int, Int>,
): List<DiarizedSegment> {
    if (remap.isEmpty()) return turns
    return turns.map { turn -> remap[turn.cluster]?.let { turn.copy(cluster = it) } ?: turn }
}

/** Total speech each cluster holds, which is what decides whether it is a speaker or a fragment. */
internal fun clusterSizes(turns: List<DiarizedSegment>): Map<Int, Int> =
    turns.groupBy { it.cluster }
        .mapValues { (_, its) -> its.sumOf { it.endSample - it.startSample } }
