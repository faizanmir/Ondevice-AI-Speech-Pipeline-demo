package com.example.aiagenttestapp.data.speakers

import com.example.aiagenttestapp.stt.DiarizedSegment
import com.example.aiagenttestapp.stt.TimedWord
import com.example.aiagenttestapp.stt.cosineSimilarity

/**
 * Moves a late hand-over boundary back to where the voice actually changed.
 *
 * **Why this exists.** On the bbg audit recording, scored against an independent transcript, our
 * turn boundaries sat within a second of the true hand-over at 24 of 48 hand-overs and within two
 * at 35 -- but at six they were **3-6 seconds late**: the previous speaker's turn covered the new
 * speaker's first sentence, so 92 of the recording's 137 wrong-speaker words were the opening 5-17
 * words of a turn. Two instrumented runs cleared everything of ours: the fold never touched those
 * stretches and the alignment placed 2,453 of 2,535 words inside exactly one turn. The late edge is
 * the diariser's own. sherpa embeds each 10-second segmentation window's local speaker and clusters
 * those embeddings; the windows straddling a hand-over hold only a short, mixed tail of the new
 * speaker, whose embedding lands in the old speaker's cluster, and those windows outvote until clean
 * ones arrive several seconds later. Every model pair inherits it.
 *
 * **What it deliberately does not do: re-cluster short turns.** A first version had a stage before
 * this one that re-embedded every turn of one to ten seconds and relabelled it when it sounded more
 * like another speaker by a margin. It was built from two turns on the bbg recording (`c4 Sp1
 * 12:11-12:14` holding the auditor's question, `c9 SP2 15:12-15:14` the auditee's answer) and
 * dropped on 2026-09-01 as an overfit: on that recording the two voices are mixed-gender and half a
 * cosine apart, so a two-second embedding picks a side reliably; on the app's same-gender benchmark
 * pair the speakers sit at 0.73 to each other and enrolment margins were 0.003, so the same test on
 * two seconds of audio is noise -- and there every turn of two seconds or more was already right, so
 * the stage could only flip correct turns. A whole turn on the wrong voice is the clustering's
 * call, and a rule that second-guesses it needs evidence from more than one recording.
 *
 * **Edges.** At each hand-over A -> B, one cheap probe: embed the last [PROBE_SECONDS] of A
 * and compare with A's and B's voiceprints. Most hand-overs are right and end there, at one
 * embedding. When the tail sounds like B, A's last [MAX_SHIFT_SECONDS] are cut at word starts into
 * pieces of about [PIECE_SECONDS] and judged **independently**, walking back from the boundary: the
 * boundary moves earlier across every piece that sounds like B and stops at the first that does
 * not. Independent pieces rather than a growing tail, because a tail that is mostly B still sounds
 * like B with a second of A inside it, and judging tails would carry the boundary into A's real
 * speech. Anything under [MARGIN] either way is "not sure", and not sure means the boundary stays.
 *
 * **What it cannot do.** It never invents a speaker, never moves a boundary later, never leaves A
 * with less than [MIN_KEEP_SECONDS], and does not see a leak shorter than the probe -- a one- or
 * two-word tail on the wrong side is the alignment's business, not this pass's. Nor does it recover
 * a one-word interjection the diariser never segmented: there is no voice to compare in half a
 * second, and that remains the known sub-2 s limit.
 *
 * Pure apart from [embed], so the geometry is pinned by a JVM test with a fake embedder and only the
 * embedder's judgement is left to the device.
 */
object BoundaryRefinement {

    /** How far a boundary may move earlier. The measured late edges were 3-6 s; beyond that is not a late edge. */
    const val MAX_SHIFT_SECONDS = 6f

    /** The first, cheap test: does the very end of the outgoing turn sound like the incoming speaker? */
    const val PROBE_SECONDS = 3f

    /** Target length of the pieces the tail is judged in. Shorter and CAM++ has too little voice to say. */
    const val PIECE_SECONDS = 1.2f

    /** Never judge less than this much audio. */
    const val MIN_SEGMENT_SECONDS = 1.0f

    /** The outgoing turn keeps at least this much: a turn that is all the other person is the fold's case, not a late edge. */
    const val MIN_KEEP_SECONDS = 1.0f

    /** How close together two turn edges may be and still be one hand-over. */
    const val HAND_OVER_TOLERANCE_SECONDS = 1.0f

    /** How much more like B than A a stretch must sound before it is called B's. Symmetric: below -MARGIN it is A's. */
    const val MARGIN = 0.10f

    data class Move(
        val fromCluster: Int,
        val toCluster: Int,
        val oldBoundary: Int,
        val newBoundary: Int,
        /** The weakest piece the boundary crossed: how sure the move is. */
        val weakestMargin: Float,
    )

    data class Result(
        val turns: List<DiarizedSegment>,
        val moves: List<Move>,
        val handOvers: Int,
        val embeddings: Int,
    )

    /**
     * @param identity the speaker a cluster stands for; two clusters with the same identity (one
     *   person across chunks) are not a hand-over.
     * @param centroid a cluster's voiceprint, or null when it has none -- such hand-overs are skipped.
     * @param embed voiceprint of a sample range of the recording, or null when it cannot be embedded.
     */
    suspend fun refine(
        turns: List<DiarizedSegment>,
        words: List<TimedWord>,
        sampleRate: Int,
        identity: (Int) -> String,
        centroid: (Int) -> FloatArray?,
        embed: suspend (IntRange) -> FloatArray?,
        similarity: (FloatArray, FloatArray) -> Float = ::cosineSimilarity,
    ): Result {
        if (turns.size < 2 || words.isEmpty()) return Result(turns, emptyList(), 0, 0)

        val probe = (PROBE_SECONDS * sampleRate).toInt()
        val maxShift = (MAX_SHIFT_SECONDS * sampleRate).toInt()
        val piece = (PIECE_SECONDS * sampleRate).toInt()
        val minSegment = (MIN_SEGMENT_SECONDS * sampleRate).toInt()
        val minKeep = (MIN_KEEP_SECONDS * sampleRate).toInt()
        val tolerance = (HAND_OVER_TOLERANCE_SECONDS * sampleRate).toInt()

        val wordStarts = words.map { (it.startSeconds * sampleRate).toInt() }.sorted()
        val work = turns.sortedBy { it.startSample }.toMutableList()
        val moves = mutableListOf<Move>()
        var handOvers = 0
        var embeddings = 0

        suspend fun score(range: IntRange, a: FloatArray, b: FloatArray): Float? {
            if (range.last - range.first + 1 < minSegment) return null
            embeddings++
            val print = embed(range) ?: return null
            return similarity(print, b) - similarity(print, a)
        }

        // One hand-over at a time, in recording order.
        for (bIndex in work.indices) {
            val b = work[bIndex]
            if (b.cluster == SpeakerAlignment.UNATTRIBUTED) continue
            // The outgoing turn: the one whose end is nearest B's start, within tolerance, and by
            // someone else. Two turns ending together by different people is not a hand-over anyone
            // can refine. Unattributed turns are nobody's and take no part.
            val ending = work.withIndex().filter { (i, t) ->
                i != bIndex && t.cluster != SpeakerAlignment.UNATTRIBUTED &&
                    identity(t.cluster) != identity(b.cluster) &&
                    t.endSample in (b.startSample - tolerance)..(b.startSample + tolerance)
            }
            val nearest = ending.minByOrNull { (_, t) -> kotlin.math.abs(t.endSample - b.startSample) } ?: continue
            if (ending.count { (_, t) -> t.endSample == nearest.value.endSample } > 1) continue
            val (aIndex, a) = nearest
            handOvers++

            val printA = centroid(a.cluster) ?: continue
            val printB = centroid(b.cluster) ?: continue

            val floor = a.startSample + minKeep
            val probeStart = maxOf(floor, a.endSample - probe)
            val probeScore = score(probeStart until a.endSample, printA, printB) ?: continue
            if (probeScore < MARGIN) continue

            // Cut the tail at word starts, about a piece apart, working back from the boundary so
            // the pieces nearest the hand-over -- the ones that decide -- sit on word edges.
            val windowStart = maxOf(floor, a.endSample - maxShift)
            val cuts = mutableListOf<Int>()
            var lastCut = a.endSample
            for (start in wordStarts.asReversed()) {
                if (start >= a.endSample) continue
                if (start < windowStart) break
                if (lastCut - start >= piece) {
                    cuts += start
                    lastCut = start
                }
            }

            var boundary = a.endSample
            var weakest = Float.MAX_VALUE
            for (cut in cuts) {
                val pieceScore = score(cut until boundary, printA, printB) ?: break
                if (pieceScore < MARGIN) break
                weakest = minOf(weakest, pieceScore)
                boundary = cut
            }
            // The probe said B but no whole piece did: the boundary stays. Moving it on the probe
            // alone would put it at an arbitrary three seconds rather than at a word.
            if (boundary >= a.endSample) continue

            moves += Move(a.cluster, b.cluster, a.endSample, boundary, weakest)
            work[aIndex] = a.copy(endSample = boundary)
            work[bIndex] = b.copy(startSample = minOf(b.startSample, boundary))
        }

        return Result(work, moves, handOvers, embeddings)
    }
}
