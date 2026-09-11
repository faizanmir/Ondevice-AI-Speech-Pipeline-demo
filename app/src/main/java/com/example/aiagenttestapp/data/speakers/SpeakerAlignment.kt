package com.example.aiagenttestapp.data.speakers

import com.example.aiagenttestapp.stt.DiarizedSegment
import com.example.aiagenttestapp.stt.FrameConfidence
import com.example.aiagenttestapp.stt.TimedWord
import com.example.aiagenttestapp.stt.TimedWords

/** A run of consecutive words the same person said. */
data class SpeakerBlock(
    val cluster: Int,
    val startSample: Int,
    val endSample: Int,
    val text: String,
)

/**
 * Puts the transcript and the diarisation back together.
 *
 * These two run over the same audio and know nothing about each other: diarisation answers "one
 * voice held the floor from 12.4 s to 19.8 s" without a single word, and the recogniser answers with
 * words and their times but no idea how many people are in the room. This is the only place they
 * meet.
 *
 * Doing it here, on word times, is what the whole feature rests on. The previous attempt cut the
 * *audio* at every speaker change and transcribed each turn as its own clip, on the belief that no
 * recogniser here reported timestamps. That belief was wrong -- sherpa reports them for Parakeet
 * directly, and for Whisper once `enableTokenTimestamps` is set -- but the design was worse for a
 * reason that survives the correction: it made speaker boundaries into slice boundaries, so
 * diarisation quality decided transcription quality. A turn misplaced by a second cut a word in half
 * and both sides lost it. Aligning afterwards leaves the recogniser free to slice wherever it
 * decodes best, and a misplaced turn now moves a word from one speaker to another -- visible,
 * correctable, and not destructive.
 */
object SpeakerAlignment {

    /**
     * Assigns each word to a diarisation cluster and groups consecutive words by speaker.
     *
     * A word is attributed near its start, but a little way inside it. ASR reports word starts, not
     * true word ends: [TimedWords] closes each word at the next word's start, so a pause can make a
     * one-syllable word appear several seconds long. Using the midpoint of that synthetic range used
     * to drag the word into silence or the next speaker. The short capped offset avoids both that
     * failure and the floating-point coin flip of testing the exact start boundary.
     *
     * Pyannote can report overlapping turns. If the established speaker is one of the candidates,
     * they keep the word; otherwise an overlap is honestly unattributed. Choosing whichever segment
     * started first invented certainty the model did not provide.
     *
     * Words falling in no turn inherit a speaker when the turns on both sides agree -- diarisation
     * reports confident speech rather than continuous coverage, and a breath mid-sentence must not
     * shred a paragraph. When the two sides **disagree**, the word goes to whichever turn edge is
     * nearer, provided that edge is within [MAX_GAP_REACH_SECONDS]; otherwise it is unattributed.
     *
     * This used to leave every disagreeing gap unattributed. On the 22-minute German recording that
     * produced 144 "Unknown Speaker ?" blocks holding 76 s of words -- every one under two seconds,
     * 68 of them under half a second -- each a turn boundary where one person stopped and the other
     * started and the recogniser heard a word in between. A transcript with a hundred and forty-four
     * headers for nobody is unreadable, and the smoothing pass could not help: its rule also refuses
     * to choose between two different neighbours. The nearer edge is the honest guess -- the word
     * before a hand-over belongs to whoever was already speaking -- and the reach keeps it a guess
     * about a boundary rather than a licence to carry a speaker across a long unsupported hole.
     */
    /**
     * How the words of one block got their speaker, for the trace log. Which rule placed a word is
     * invisible in the output -- a block reads the same whether its words sat inside one turn or were
     * kept by the established speaker through an overlap -- and that is exactly what has to be known
     * to tell a late turn boundary (a diarisation fact) from an alignment rule carrying the previous
     * speaker across a hand-over.
     */
    data class Trace(
        val block: SpeakerBlock,
        /** Words covered by exactly one turn. */
        val single: Int,
        /** Words inside overlapping turns, kept by the speaker already talking. */
        val overlapKept: Int,
        /** Words inside overlapping turns with no established speaker among them: unattributed. */
        val overlapNone: Int,
        /** Words in no turn, given the speaker both neighbouring turns agree on. */
        val gapAgreed: Int,
        /** Words in no turn, given the nearer edge's speaker because the neighbours disagree. */
        val gapNearest: Int,
        /** Words in no turn and out of reach of both edges. */
        val gapNone: Int,
        /** Words placed by confidence-weighted mass over the whole word rather than by one instant. */
        val byMass: Int = 0,
    )

    fun blocks(
        words: List<TimedWord>,
        turns: List<DiarizedSegment>,
        sampleRate: Int,
        /**
         * Per-frame confidence from the frame diariser, or [FrameConfidence.NONE] on an engine that
         * has none. When present it decides a word before the timestamp rules get a look in -- see
         * [massCluster].
         */
        confidence: FrameConfidence = FrameConfidence.NONE,
        /**
         * Stretches the model heard two voices in, in recording samples. Decides which marker a
         * wordless turn gets; empty means every one of them reads as a backchannel.
         */
        overlaps: List<IntRange> = emptyList(),
        onBlock: (Trace) -> Unit = {},
    ): List<SpeakerBlock> {
        if (words.isEmpty()) return emptyList()

        val ordered = turns.sortedBy { it.startSample }
        val blocks = mutableListOf<SpeakerBlock>()

        var currentCluster: Int? = null
        var currentWords = mutableListOf<TimedWord>()
        var currentStart = 0

        /** A turn boundary next to a gap: which voice, and where the turn ended or begins. */
        data class Edge(val cluster: Int, val sample: Int)

        fun edgeBefore(sample: Int): Edge? {
            val nearestEnd = ordered.asSequence()
                .filter { it.endSample <= sample }
                .maxOfOrNull { it.endSample }
                ?: return null
            val cluster = ordered.asSequence()
                .filter { it.endSample == nearestEnd }
                .map { it.cluster }
                .distinct()
                .singleOrNull()
                ?: return null
            return Edge(cluster, nearestEnd)
        }

        fun edgeAfter(sample: Int): Edge? {
            val nearestStart = ordered.asSequence()
                .filter { it.startSample > sample }
                .minOfOrNull { it.startSample }
                ?: return null
            val cluster = ordered.asSequence()
                .filter { it.startSample == nearestStart }
                .map { it.cluster }
                .distinct()
                .singleOrNull()
                ?: return null
            return Edge(cluster, nearestStart)
        }

        /**
         * The speaker for a word in no turn: both neighbours if they agree, else the nearer edge if
         * it is within reach, else nobody. Ties go to the turn before -- the person already speaking.
         */
        fun gapCluster(sample: Int): Int? {
            val before = edgeBefore(sample)
            val after = edgeAfter(sample)
            if (before != null && after != null && before.cluster == after.cluster) return before.cluster

            val reach = (MAX_GAP_REACH_SECONDS * sampleRate).toInt()
            val nearest = listOfNotNull(
                before?.let { it.cluster to (sample - it.sample) },
                after?.let { it.cluster to (it.sample - sample) },
            ).minByOrNull { it.second } ?: return null
            return nearest.first.takeIf { nearest.second <= reach }
        }

        /**
         * The speaker holding the most confidence-weighted audio across the whole word.
         *
         * **Why a span rather than an instant.** Everything below this asks which turn covers one
         * point inside the word, which is a fair question only while the point is in the right turn.
         * At a hand-over it often is not: the diariser places boundaries to the nearest segment, the
         * recogniser places word starts to the nearest frame, and the two disagree by a hair on
         * exactly the words a reader notices -- the first few of a turn. Summing the word's own
         * frames instead lets the bulk of it decide, and weighting each frame by the model's
         * top-versus-second margin lets the *confident* bulk decide.
         *
         * **Why it can decline.** A word split near evenly is a word the audio does not settle, and
         * overruling the timestamp rules on a coin flip would trade a visible error for an invisible
         * one. Below [MASS_MARGIN] this returns null and the rules below run unchanged.
         *
         * Returns null when there is no track, no turn overlaps the word, or nothing is confident
         * enough -- so a caller on the sherpa engine never reaches a different answer than before.
         */
        fun massCluster(fromSample: Int, toSample: Int): Int? {
            if (confidence.isEmpty || toSample <= fromSample) return null
            var best: Int? = null
            var bestMass = 0f
            var runnerUp = 0f
            for (turn in ordered) {
                if (turn.endSample <= fromSample) continue
                if (turn.startSample >= toSample) break
                val mass = confidence.massBetween(
                    maxOf(turn.startSample, fromSample),
                    minOf(turn.endSample, toSample),
                )
                if (mass > bestMass) {
                    runnerUp = bestMass
                    bestMass = mass
                    best = turn.cluster
                } else if (mass > runnerUp) {
                    runnerUp = mass
                }
            }
            if (best == null || bestMass <= 0f) return null
            val margin = (bestMass - runnerUp) / (bestMass + runnerUp)
            return best.takeIf { margin >= MASS_MARGIN }
        }

        /**
         * The word stream with markers folded in, in time order.
         *
         * Markers have to join the stream *before* blocks are built rather than being appended
         * afterwards. A backchannel usually lands in the middle of a long stretch of one speaker,
         * and a block is only cut where the speaker changes -- so a marker added to the finished
         * list sits inside a block that already spans it, and the two silently overlap. Put in the
         * stream, the marker's own cluster cuts the block in two around it, which is what a reader
         * needs to see: the interruption between the words either side of it.
         */
        val stream = (words.map { it to null as Int? } + markerWords(words, ordered, overlaps, sampleRate))
            .sortedBy { it.first.startSeconds }

        // Per-block tallies of which rule placed each word; reset with the block.
        val tally = IntArray(7)

        fun flush() {
            if (currentWords.isEmpty()) return
            val block = SpeakerBlock(
                cluster = currentCluster ?: UNATTRIBUTED,
                startSample = currentStart,
                endSample = (currentWords.last().endSeconds * sampleRate).toInt(),
                text = currentWords.joinToString(" ") { it.text },
            )
            blocks += block
            onBlock(Trace(block, tally[0], tally[1], tally[2], tally[3], tally[4], tally[5], tally[6]))
            tally.fill(0)
            currentWords = mutableListOf()
        }

        for ((word, fixedCluster) in stream) {
            val reportedDuration = (word.endSeconds - word.startSeconds).coerceAtLeast(0f)
            val evidenceSeconds = word.startSeconds +
                minOf(reportedDuration / 2f, MAX_WORD_EVIDENCE_OFFSET_SECONDS)
            val evidenceSample = (evidenceSeconds * sampleRate).toInt()
            val candidates = ordered.asSequence()
                .filter { evidenceSample >= it.startSample && evidenceSample < it.endSample }
                .map { it.cluster }
                .distinct()
                .toList()
            val wordStart = (word.startSeconds * sampleRate).toInt()
            val wordEnd = (word.endSeconds * sampleRate).toInt()
            val byMass = massCluster(wordStart, wordEnd)

            var rule = 0
            val cluster = when {
                // A marker already knows whose turn it stands for; nothing may re-attribute it.
                fixedCluster != null -> fixedCluster
                // The word's own audio, when it is confident enough to have an opinion.
                byMass != null -> { rule = 6; byMass }
                candidates.size == 1 -> candidates.single()
                currentCluster != null && currentCluster in candidates -> { rule = 1; currentCluster }
                candidates.size > 1 -> { rule = 2; null }
                // No turn covers it: only matching evidence on both sides can fill the gap.
                else -> {
                    val before = edgeBefore(evidenceSample)
                    val after = edgeAfter(evidenceSample)
                    val filled = gapCluster(evidenceSample)
                    rule = when {
                        filled == null -> 5
                        before != null && after != null && before.cluster == after.cluster -> 3
                        else -> 4
                    }
                    filled
                }
            }

            if (currentWords.isNotEmpty() && cluster != currentCluster) {
                flush()
            }
            tally[rule]++
            if (currentWords.isEmpty()) {
                currentStart = (word.startSeconds * sampleRate).toInt()
                currentCluster = cluster
            }
            currentWords += word
        }
        flush()

        return blocks
    }

    /**
     * Turns that no word landed in, as marker pseudo-words carrying the turn's own speaker.
     *
     * **What is being recovered.** Diarisation regularly finds a second voice holding the floor for
     * a second while somebody else is talking -- "mhm", "yeah", "okay", or two people starting at
     * once. The recogniser, decoding a slice that is mostly the other speaker, returns no words for
     * it. The turn then has nothing to attach to and disappears, and the transcript reads as though
     * the interruption never happened. On a conversation full of backchannels that is most of the
     * turns lost while word accuracy stays high, which is the exact shape of this app's own
     * benchmark: 98.5% of words right, 26-29 of 36 turns.
     *
     * **Which marker.** [OVERLAP_MARKER] when the segmentation model reported two speakers active
     * there, [BACKCHANNEL_MARKER] otherwise. The distinction is the model's rather than a guess: one
     * is somebody talking over the speaker, the other is somebody agreeing in a gap.
     *
     * **What this costs.** These are words in the transcript that nobody said, so a word error rate
     * measured against a reference without them counts every one as an insertion. `wer.py` already
     * knows how to exclude marker phrases; a run scored with them left in is not comparable to the
     * published figures.
     */
    private fun markerWords(
        words: List<TimedWord>,
        turns: List<DiarizedSegment>,
        overlaps: List<IntRange>,
        sampleRate: Int,
    ): List<Pair<TimedWord, Int?>> = turns.mapNotNull { turn ->
        if (turn.cluster == UNATTRIBUTED) return@mapNotNull null
        // Only a short turn may be stood in for. A backchannel is a word long; a wordless turn of
        // several seconds is the recogniser having failed on speech that was really there, and
        // writing "(mhm)" over it would put a word in someone's mouth to cover a gap. Those stay
        // dropped, exactly as before.
        if (turn.endSample - turn.startSample > MAX_MARKER_SECONDS * sampleRate) return@mapNotNull null
        // A backchannel is by definition a *second* voice: somebody making a noise while another
        // person holds the floor. Requiring a competing turn is what separates that from the two
        // cases which look identical once the words are missing -- the speaker's own turn that the
        // recogniser found nothing in, and a trailing turn after the last word, both of which would
        // otherwise have "(mhm)" written into them.
        //
        // The cost is that a backchannel landing in a clean gap, with no competing turn over it, is
        // not marked. Telling that apart from a recognition gap needs evidence this stage does not
        // have, and inventing a word is the worse error of the two.
        val contested = turns.any {
            it !== turn && it.cluster != turn.cluster &&
                it.startSample < turn.endSample && it.endSample > turn.startSample
        }
        if (!contested) return@mapNotNull null
        val spoken = words.any { word ->
            (word.endSeconds * sampleRate).toInt() > turn.startSample &&
                (word.startSeconds * sampleRate).toInt() < turn.endSample
        }
        if (spoken) return@mapNotNull null

        val overlapped = overlaps.any { it.first < turn.endSample && it.last > turn.startSample }
        TimedWord(
            text = if (overlapped) OVERLAP_MARKER else BACKCHANNEL_MARKER,
            startSeconds = turn.startSample / sampleRate.toFloat(),
            endSeconds = turn.endSample / sampleRate.toFloat(),
        ) to turn.cluster
    }

    /**
     * The cluster id used when diarisation had nothing to say about a stretch of speech.
     *
     * Reached by a word farther than [MAX_GAP_REACH_SECONDS] from every turn -- the opening words of
     * a recording, or a hole diarisation left in the middle -- and by a word inside two overlapping
     * turns with no established speaker to keep it. Negative so it can never collide with a real
     * cluster index, which sherpa numbers from zero.
     */
    const val UNATTRIBUTED = -1

    /** Far enough inside a word to clear a rounded boundary, but never far enough to cross a pause. */
    private const val MAX_WORD_EVIDENCE_OFFSET_SECONDS = 0.2f

    /**
     * How far a word in a gap may be from a turn's edge and still be given that turn's speaker when
     * the other side disagrees. The boundary scraps this exists for were all under two seconds long,
     * so a second and a half reaches every one of them from its nearer side while leaving a genuine
     * hole -- a stretch neither neighbour plausibly owns -- unattributed as before.
     */
    const val MAX_GAP_REACH_SECONDS = 1.5f

    /**
     * How decisively one speaker must hold a word's audio before mass overrules the timestamp rules.
     *
     * The value is a normalised top-versus-second margin, so 0.2 means the leader holds 1.5x the
     * runner-up. Low enough that an ordinary word sitting inside one turn is decided here -- there
     * is no runner-up at all, so the margin is 1.0 -- and high enough that a word genuinely split
     * across a hand-over falls through to the rules that were measured.
     */
    private const val MASS_MARGIN = 0.2f

    /**
     * Longest wordless turn that may be replaced by a marker.
     *
     * Two seconds is the same line [DiarizeWorker]'s short-block smoothing draws, and the same one
     * this codebase's boundary work kept running into from the other side: under it a turn is an
     * interjection nobody transcribed, over it there is real speech the recogniser missed.
     */
    const val MAX_MARKER_SECONDS = 2f

    /** A turn the recogniser found no words in, where two voices were active at once. */
    const val OVERLAP_MARKER = "(overlap)"

    /** A turn the recogniser found no words in, with no second voice: an agreement noise. */
    const val BACKCHANNEL_MARKER = "(mhm)"
}
