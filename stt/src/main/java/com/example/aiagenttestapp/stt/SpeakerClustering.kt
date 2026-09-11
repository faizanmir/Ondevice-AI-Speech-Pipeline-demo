package com.example.aiagenttestapp.stt

/**
 * Turns a bag of segment voiceprints into speaker labels, and then repairs them.
 *
 * Every function here is pure arithmetic over `FloatArray`s and an `IntArray` of labels: no models,
 * no audio, no Android. That is deliberate and is the same bargain
 * [com.example.aiagenttestapp.data.speakers.BoundaryRefinement] makes -- the geometry is the part
 * that goes wrong, and it is only cheap to pin in a test if nothing here needs a device.
 *
 * ## What clustering has to survive
 *
 * The input is one voiceprint per segment the segmentation model found, in the order the recording
 * produced them. Nothing says how many people are in the room, and the honest answer is that a
 * recording usually contains a couple of real speakers plus a tail of junk: half-second blips, a
 * breath, a chair, the mixed tail of a hand-over. A raw dendrogram cut treats all of it as speakers.
 * The four repair passes below exist because that tail is the normal case, not the exceptional one,
 * and each removes a different way it shows up:
 *
 * | pass | what it removes | evidence it uses |
 * |---|---|---|
 * | [absorbCrumbs] | clusters under 5% of speech | nearest centroid, unconditionally |
 * | [foldPhantoms] | clusters under 10% that *sound like* a real one | size **and** similarity |
 * | [mergeLookalikes] | two big clusters that are one person | centroid similarity alone |
 * | [smoothTemporally] | single segments flipped mid-flow | neighbours in time |
 *
 * They run in that order and the order carries meaning: absorption clears the noise floor so the
 * phantom fold's share arithmetic is not skewed by dozens of crumbs, and both run before the
 * lookalike merge so that merge is comparing settled centroids rather than fragments.
 *
 * ## The vectors are unit length
 *
 * Every function assumes L2-normalised input, which [FrameDiarizer] guarantees at the point of
 * embedding. That makes cosine similarity a plain dot product, which matters: [agglomerate] is
 * O(n^2) in segments and [smoothTemporally] O(n*k^2), and re-deriving two norms per comparison
 * roughly triples both. [cosineSimilarity] is the safe version for callers that cannot promise it.
 */
object SpeakerClustering {

    /**
     * Average-linkage agglomerative clustering on cosine distance, cut at [threshold].
     *
     * **Why a threshold and not a speaker count.** A count is a hard budget -- it has to spend every
     * slot, so on a two-person recording with one stray fragment it hands one of its two slots to
     * the fragment and merges the two real speakers into what is left. A threshold over-segments
     * instead, producing more clusters than there are people, and everything after this function
     * exists to merge them back down using evidence. Over-segmenting is the recoverable direction;
     * under-segmenting is not, because two voices averaged into one centroid cannot be separated
     * again afterwards.
     *
     * **Average linkage rather than single or complete.** Single linkage chains -- one borderline
     * pair welds two speakers together through it. Complete linkage refuses to merge a cluster with
     * any outlier in it, which on real speech is every cluster. Average is what pyannote uses, and
     * the merged distance is updated incrementally by the usual weighted mean rather than recomputed
     * from members, so the whole thing stays O(n^2).
     *
     * @param embeddings one unit-length voiceprint per segment.
     * @param threshold cosine *distance* at which merging stops; 1 - similarity.
     * @return a label per segment, numbered densely from zero.
     */
    fun agglomerate(embeddings: List<FloatArray>, threshold: Float): IntArray {
        val n = embeddings.size
        if (n == 0) return IntArray(0)
        if (n == 1) return IntArray(1)

        val active = BooleanArray(n) { true }
        val members = Array(n) { arrayListOf(it) }
        val distance = Array(n) { FloatArray(n) }
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val d = 1f - dot(embeddings[i], embeddings[j])
                distance[i][j] = d
                distance[j][i] = d
            }
        }

        while (true) {
            var bestI = -1
            var bestJ = -1
            var best = Float.MAX_VALUE
            for (i in 0 until n) {
                if (!active[i]) continue
                for (j in i + 1 until n) {
                    if (active[j] && distance[i][j] < best) {
                        best = distance[i][j]
                        bestI = i
                        bestJ = j
                    }
                }
            }
            if (bestI < 0 || best > threshold) break

            val sizeI = members[bestI].size
            val sizeJ = members[bestJ].size
            for (x in 0 until n) {
                if (!active[x] || x == bestI || x == bestJ) continue
                val merged = (distance[bestI][x] * sizeI + distance[bestJ][x] * sizeJ) / (sizeI + sizeJ)
                distance[bestI][x] = merged
                distance[x][bestI] = merged
            }
            members[bestI].addAll(members[bestJ])
            active[bestJ] = false
        }

        val out = IntArray(n)
        var next = 0
        for (c in 0 until n) {
            if (!active[c]) continue
            val id = next++
            for (m in members[c]) out[m] = id
        }
        return out
    }

    /**
     * Cosine k-means with farthest-point seeding, for when the speaker count is actually known.
     *
     * The caller is responsible for only passing a count it has real evidence for, and the reason is
     * the same one the sherpa path documents: k is a hard budget, not a hint. Every slot gets spent,
     * so an over-stated count invents a speaker out of noise and an under-stated one merges two real
     * people beyond recovery. When the count is unknown, [agglomerate] plus the repair passes is the
     * right answer and this is not called at all.
     *
     * Farthest-point seeding rather than random: the segments are few and the run has to be
     * reproducible, so the seeds are chosen deterministically as the points furthest from what is
     * already seeded -- which on speech is reliably one seed per voice.
     */
    fun kMeans(embeddings: List<FloatArray>, k: Int, iterations: Int = 30): IntArray {
        val n = embeddings.size
        if (n == 0) return IntArray(0)
        val clusters = k.coerceIn(1, n)
        val dim = embeddings[0].size

        val centres = ArrayList<FloatArray>(clusters)
        centres += embeddings[0].copyOf()
        while (centres.size < clusters) {
            var pick = 0
            var furthest = -1f
            for (i in 0 until n) {
                var nearest = Float.MAX_VALUE
                for (c in centres) {
                    val d = 1f - dot(embeddings[i], c)
                    if (d < nearest) nearest = d
                }
                if (nearest > furthest) {
                    furthest = nearest
                    pick = i
                }
            }
            centres += embeddings[pick].copyOf()
        }

        val labels = IntArray(n)
        repeat(iterations) {
            for (i in 0 until n) {
                var best = 0
                var bestSim = -2f
                for (c in centres.indices) {
                    val sim = dot(embeddings[i], centres[c])
                    if (sim > bestSim) {
                        bestSim = sim
                        best = c
                    }
                }
                labels[i] = best
            }
            for (c in centres.indices) {
                val acc = FloatArray(dim)
                var members = 0
                for (i in 0 until n) {
                    if (labels[i] != c) continue
                    members++
                    val e = embeddings[i]
                    for (d in 0 until dim) acc[d] += e[d]
                }
                if (members == 0) continue
                var norm = 0f
                for (d in 0 until dim) norm += acc[d] * acc[d]
                norm = kotlin.math.sqrt(norm)
                if (norm > 0f) for (d in 0 until dim) centres[c][d] = acc[d] / norm
            }
        }
        return labels
    }

    /**
     * Reassigns every cluster below [minShare] of total speech to whichever surviving cluster it
     * sounds most like. Mutates [labels]; returns how many segments moved.
     *
     * This is the unconditional pass: a cluster holding under a twentieth of the recording is not a
     * participant, whatever it sounds like, so it is emptied into the nearest real one rather than
     * tested for similarity first. That is safe *here* and nowhere else, because five percent of a
     * conversation is a floor no actual speaker in these recordings falls under -- and it is exactly
     * the band that fills with half-second blips a dendrogram could not place.
     *
     * [foldPhantoms] handles the 5-10% band, where similarity does have to be checked.
     */
    fun absorbCrumbs(
        embeddings: List<FloatArray>,
        labels: IntArray,
        durations: FloatArray,
        minShare: Float = 0.05f,
    ): Int {
        val total = durations.sum()
        if (total <= 0f) return 0

        val byLabel = HashMap<Int, Float>()
        for (i in labels.indices) byLabel[labels[i]] = (byLabel[labels[i]] ?: 0f) + durations[i]

        val majors = byLabel.filterValues { it / total >= minShare }.keys
        if (majors.isEmpty() || majors.size == byLabel.size) return 0

        val centres = centroids(embeddings, labels, majors.toList())
        var moved = 0
        for (i in embeddings.indices) {
            if (labels[i] in majors) continue
            var bestLabel = labels[i]
            var bestSim = -2f
            for ((label, centre) in centres) {
                val sim = dot(embeddings[i], centre)
                if (sim > bestSim) {
                    bestSim = sim
                    bestLabel = label
                }
            }
            labels[i] = bestLabel
            moved++
        }
        return moved
    }

    /**
     * Folds a minor cluster into a major one when it *also* sounds like it. Mutates [labels];
     * returns how many clusters were folded away.
     *
     * This is pyannote's `min_cluster_size` post-pass with a voice check added. pyannote reassigns
     * every under-size cluster to the nearest large one however far away that is; measured on real
     * audit audio that forced merges at cosine 0.23 through 0.43 -- blips that belonged to nobody
     * being handed to whoever happened to be closest. Requiring [minSim] as well means a quiet but
     * genuinely different voice stays its own speaker, and a fragment that fits nobody stays
     * unfolded rather than being given to the wrong person.
     *
     * Runs after [absorbCrumbs], so it sees only the 5-10% share band that absorption leaves alone.
     */
    fun foldPhantoms(
        embeddings: List<FloatArray>,
        labels: IntArray,
        durations: FloatArray,
        maxShare: Float = 0.10f,
        minSim: Float = 0.60f,
    ): Int {
        val total = durations.sum()
        if (total <= 0f) return 0

        val byLabel = HashMap<Int, Float>()
        for (i in labels.indices) byLabel[labels[i]] = (byLabel[labels[i]] ?: 0f) + durations[i]
        if (byLabel.size < 2) return 0

        val majors = byLabel.filterValues { it / total >= maxShare }.keys
        val minors = byLabel.keys - majors
        if (majors.isEmpty() || minors.isEmpty()) return 0

        val centres = centroids(embeddings, labels, byLabel.keys.toList())
        val remap = HashMap<Int, Int>()
        for (minor in minors) {
            val minorCentre = centres[minor] ?: continue
            var bestLabel = -1
            var bestSim = -2f
            for (major in majors) {
                val sim = dot(minorCentre, centres[major] ?: continue)
                if (sim > bestSim) {
                    bestSim = sim
                    bestLabel = major
                }
            }
            if (bestLabel >= 0 && bestSim >= minSim) remap[minor] = bestLabel
        }
        if (remap.isEmpty()) return 0

        for (i in labels.indices) remap[labels[i]]?.let { labels[i] = it }
        return remap.size
    }

    /**
     * Merges any two clusters whose centroids still match at [minSim]. Mutates [labels]; returns the
     * number of merges.
     *
     * **The failure this fixes is one voice split into two large clusters**, which is the real
     * microphone case rather than a modelling curiosity: someone turns their head, moves further
     * from the phone, or leans in, and their voiceprints drift far enough apart that the linkage cut
     * keeps the two halves separate. Neither half is small, so [absorbCrumbs] and [foldPhantoms]
     * both leave them alone, and the transcript comes out with one person under two names.
     *
     * 0.70 is well above where two different speakers on one microphone land, including the
     * same-gender case, so this cannot merge two people. It repeats until stable because folding two
     * halves together shifts the centroid, which can bring a third fragment of the same voice into
     * range.
     */
    fun mergeLookalikes(
        embeddings: List<FloatArray>,
        labels: IntArray,
        minSim: Float = 0.70f,
        onMerge: (from: Int, into: Int, similarity: Float) -> Unit = { _, _, _ -> },
    ): Int {
        var merges = 0
        while (true) {
            val present = labels.toSortedSet().toList()
            if (present.size < 2) return merges

            val centres = centroids(embeddings, labels, present)
            var from = -1
            var into = -1
            var bestSim = minSim
            for (i in present.indices) {
                for (j in i + 1 until present.size) {
                    val a = centres[present[i]] ?: continue
                    val b = centres[present[j]] ?: continue
                    val sim = dot(a, b)
                    if (sim >= bestSim) {
                        bestSim = sim
                        into = present[i]
                        from = present[j]
                    }
                }
            }
            if (from < 0) return merges

            for (i in labels.indices) if (labels[i] == from) labels[i] = into
            onMerge(from, into, bestSim)
            merges++
        }
    }

    /**
     * Unit-length mean voiceprint per label. Labels with no members are absent from the result.
     */
    fun centroids(
        embeddings: List<FloatArray>,
        labels: IntArray,
        of: List<Int>,
    ): Map<Int, FloatArray> {
        if (embeddings.isEmpty()) return emptyMap()
        val dim = embeddings[0].size
        val out = HashMap<Int, FloatArray>(of.size)

        for (label in of) {
            val acc = FloatArray(dim)
            var members = 0
            for (i in embeddings.indices) {
                if (labels[i] != label) continue
                members++
                val e = embeddings[i]
                for (d in 0 until dim) acc[d] += e[d]
            }
            if (members == 0) continue

            var norm = 0f
            for (d in 0 until dim) norm += acc[d] * acc[d]
            norm = kotlin.math.sqrt(norm)
            if (norm > 0f) for (d in 0 until dim) acc[d] /= norm
            out[label] = acc
        }
        return out
    }

    /**
     * Viterbi over time-ordered segments: lets continuity outvote a weak fingerprint. Mutates
     * [labels]; returns how many changed.
     *
     * A two-second segment in the middle of one person's uninterrupted flow can easily score
     * marginally closer to somebody else's centroid -- short audio makes a noisy voiceprint, and the
     * per-segment decision has no idea what surrounds it. This gives that decision a memory: the
     * best path pays [penalty] every time it changes speaker across a gap shorter than
     * [continuityGapSec], so a flip has to be worth more than the penalty in similarity to happen at
     * all. Across a longer silence the penalty is waived, because a pause *is* where speakers change.
     *
     * The penalty is deliberately small. It is there to break ties in favour of continuity, not to
     * paper over a segment that genuinely sounds like someone else.
     */
    fun smoothTemporally(
        embeddings: List<FloatArray>,
        labels: IntArray,
        order: List<Int>,
        startsSec: FloatArray,
        endsSec: FloatArray,
        penalty: Float = 0.08f,
        continuityGapSec: Float = 1.0f,
    ): Int {
        val present = labels.toSortedSet().toList()
        if (present.size < 2 || order.size < 3) return 0

        val centres = centroids(embeddings, labels, present)
        val states = present.size
        val n = order.size
        val score = Array(n) { FloatArray(states) }
        val back = Array(n) { IntArray(states) }

        for (s in 0 until states) {
            score[0][s] = dot(embeddings[order[0]], centres[present[s]] ?: return 0)
        }
        for (i in 1 until n) {
            val gap = startsSec[order[i]] - endsSec[order[i - 1]]
            val switchCost = if (gap < continuityGapSec) penalty else 0f
            for (s in 0 until states) {
                val emission = dot(embeddings[order[i]], centres[present[s]] ?: return 0)
                var best = Float.NEGATIVE_INFINITY
                var bestFrom = 0
                for (j in 0 until states) {
                    val v = score[i - 1][j] + emission - (if (j != s) switchCost else 0f)
                    if (v > best) {
                        best = v
                        bestFrom = j
                    }
                }
                score[i][s] = best
                back[i][s] = bestFrom
            }
        }

        var s = 0
        var best = Float.NEGATIVE_INFINITY
        for (j in 0 until states) {
            if (score[n - 1][j] > best) {
                best = score[n - 1][j]
                s = j
            }
        }

        var changed = 0
        for (i in n - 1 downTo 0) {
            if (labels[order[i]] != present[s]) changed++
            labels[order[i]] = present[s]
            if (i > 0) s = back[i][s]
        }
        return changed
    }

    /** One piece of a segment that resegmentation split off, in recording seconds. */
    data class Piece(val startSec: Float, val endSec: Float, val label: Int)

    /**
     * Splits one segment where the voice inside it changes. Returns the pieces in time order, or an
     * empty list when the evidence never switches -- which is the overwhelmingly common answer.
     *
     * **What this recovers.** Segmentation cuts turns on acoustic change, so it misses a hand-over
     * between two people it hears as similar -- the same-gender case -- and emits one long segment
     * holding both. Clustering then labels that segment once, and every word in it goes to whoever
     * won. Nothing later in the pipeline can undo that, because by then the two speakers are one
     * turn.
     *
     * So the segment is re-examined at a finer grain: sub-window voiceprints taken across it are
     * Viterbi'd against the *final* cluster centroids, and where the path changes state the segment
     * is cut. Using settled centroids is the whole point -- this asks "which of the people we now
     * know are in this recording does each moment sound like", which is a much easier question than
     * the one clustering had to answer.
     *
     * @param subEmbeddings unit-length voiceprints of sliding sub-windows, in time order.
     * @param subStartsSec each sub-window's start, in recording seconds.
     * @param subWindowSec length of one sub-window; with the hop it places a cut mid-overlap.
     */
    fun resegment(
        subEmbeddings: List<FloatArray>,
        subStartsSec: FloatArray,
        segmentStartSec: Float,
        segmentEndSec: Float,
        centres: Map<Int, FloatArray>,
        majors: List<Int>,
        subWindowSec: Float,
        hopSec: Float,
        penalty: Float = 0.08f,
    ): List<Piece> {
        if (subEmbeddings.size < 2 || majors.size < 2) return emptyList()

        val n = subEmbeddings.size
        val states = majors.size
        val score = Array(n) { FloatArray(states) }
        val back = Array(n) { IntArray(states) }

        for (s in 0 until states) {
            score[0][s] = dot(subEmbeddings[0], centres[majors[s]] ?: return emptyList())
        }
        for (w in 1 until n) {
            for (s in 0 until states) {
                val emission = dot(subEmbeddings[w], centres[majors[s]] ?: return emptyList())
                var best = Float.NEGATIVE_INFINITY
                var bestFrom = 0
                for (j in 0 until states) {
                    val v = score[w - 1][j] + emission - (if (j != s) penalty else 0f)
                    if (v > best) {
                        best = v
                        bestFrom = j
                    }
                }
                score[w][s] = best
                back[w][s] = bestFrom
            }
        }

        var s = 0
        var best = Float.NEGATIVE_INFINITY
        for (j in 0 until states) {
            if (score[n - 1][j] > best) {
                best = score[n - 1][j]
                s = j
            }
        }
        val path = IntArray(n)
        for (w in n - 1 downTo 0) {
            path[w] = majors[s]
            if (w > 0) s = back[w][s]
        }

        if (path.toSet().size < 2) return emptyList()

        val pieces = ArrayList<Piece>()
        var runStart = segmentStartSec
        for (w in 1 until n) {
            if (path[w] == path[w - 1]) continue
            // Halfway through the overlap between the last sub-window of the old speaker and the
            // first of the new one: neither window's start is the boundary, and the truth is
            // somewhere between them.
            val boundary = subStartsSec[w - 1] + (subWindowSec + hopSec) / 2f
            if (boundary <= runStart || boundary >= segmentEndSec) continue
            pieces += Piece(runStart, boundary, path[w - 1])
            runStart = boundary
        }
        if (pieces.isEmpty()) return emptyList()
        pieces += Piece(runStart, segmentEndSec, path[n - 1])
        return pieces
    }

    /**
     * Dot product, which is cosine similarity **only because callers pass unit-length vectors**.
     * [cosineSimilarity] is the one to use when that cannot be promised.
     */
    internal fun dot(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var sum = 0f
        for (i in a.indices) sum += a[i] * b[i]
        return sum
    }
}
