package com.example.aiagenttestapp.data.speakers

import com.example.aiagenttestapp.data.benchmark.Wer

/**
 * What a transcript got wrong against its reference, itemised -- the detail behind the score line.
 *
 * [DiarizationScore] answers with three numbers, and three numbers cannot say *what* to fix: a 10%
 * word error rate could be a hundred mishearings or one dropped paragraph, and a 96% speaker
 * accuracy could be a few boundary words or one whole turn given to the wrong person. The
 * `.errors.md` files under `docs/data/` have been the answer to that on the desk; this is the same
 * itemisation on the device, from the same aligner ([Wer.opcodes]) and the same label mapping
 * ([SpeakerAccuracy]), so the two never disagree.
 *
 * Computed on demand from the blocks and the reference, never stored: it is a view of two things
 * the row already holds, and re-deriving it costs milliseconds.
 */
data class TranscriptComparison(
    val referenceWords: Int,
    val hypothesisWords: Int,
    val coveragePercent: Double,
    val werPercent: Double,
    val substitutions: Int,
    val deletions: Int,
    val insertions: Int,
    /** Null when the reference carries no speaker tags. */
    val speakerAccuracyPercent: Double?,
    /** One row per reference speaker, in the order they first speak. */
    val speakers: List<SpeakerRow>,
    /** Stretches of correctly recognised words that went to the wrong speaker, in time order. */
    val misattributed: List<Misattribution>,
    /** Every word error, in reference order, in the style of the `.errors.md` files. */
    val wordErrors: List<WordError>,
    /** The most repeated (reference, transcript) word pairs -- a systematic mishearing shows up here. */
    val topPairs: List<Triple<String, String, Int>>,
) {
    /** Which transcript label a reference speaker was matched to, and how well that held. */
    data class SpeakerRow(
        val referenceLabel: String,
        /** Null when no transcript label was left for this speaker to map to. */
        val transcriptLabel: String?,
        val comparedWords: Int,
        val matchedWords: Int,
    ) {
        val percent: Double get() = if (comparedWords == 0) 0.0 else 100.0 * matchedWords / comparedWords
    }

    /** A run of matched words the reference gives to one speaker and the transcript to another. */
    data class Misattribution(
        val startSample: Int,
        val referenceLabel: String,
        val expectedLabel: String,
        val actualLabel: String,
        val words: Int,
        val excerpt: String,
    )

    enum class ErrorKind { Substitution, Deletion, Insertion }

    data class WordError(
        val kind: ErrorKind,
        /** The reference word, or "" for an insertion. */
        val reference: String,
        /** The transcript word, or "" for a deletion. */
        val hypothesis: String,
        /** The few reference words before the error, so it can be found in the text. */
        val context: String,
    )

    companion object {
        /** Words of context before an error; three is what the desk-side script prints. */
        private const val CONTEXT_WORDS = 3
        private const val EXCERPT_WORDS = 8
        private const val TOP_PAIRS = 8

        fun of(reference: String, language: String, blocks: List<DiarizedBlock>): TranscriptComparison? {
            if (reference.isBlank() || blocks.isEmpty()) return null
            val ordered = blocks.sortedBy { it.startSample }

            val refTagged = SpeakerAccuracy.parseReference(reference, language)
            val refWords = if (refTagged.isNotEmpty()) {
                refTagged.map { it.word }
            } else {
                Wer.normalise(Wer.DIRECTIVE.replace(reference, ""), expandNumbers = true, lang = language)
            }
            if (refWords.isEmpty()) return null

            // Each transcript word remembers its block, so a misattribution can say when it happened.
            val hypWords = mutableListOf<String>()
            val hypLabel = mutableListOf<String>()
            val hypStart = mutableListOf<Int>()
            for (block in ordered) {
                for (w in Wer.normalise(block.text, expandNumbers = true, lang = language)) {
                    hypWords += w; hypLabel += block.speakerName; hypStart += block.startSample
                }
            }
            if (hypWords.isEmpty()) return null

            val ops = Wer.opcodes(refWords, hypWords)
            val pass = Wer.score(refWords, hypWords)

            val errors = mutableListOf<WordError>()
            fun context(i: Int) = refWords.subList(maxOf(0, i - CONTEXT_WORDS), i).joinToString(" ")
            for (op in ops) {
                when (op.tag) {
                    "replace" -> {
                        val r = refWords.subList(op.i1, op.i2)
                        val h = hypWords.subList(op.j1, op.j2)
                        val paired = minOf(r.size, h.size)
                        for (k in 0 until paired) errors += WordError(ErrorKind.Substitution, r[k], h[k], context(op.i1 + k))
                        for (k in paired until r.size) errors += WordError(ErrorKind.Deletion, r[k], "", context(op.i1 + k))
                        for (k in paired until h.size) errors += WordError(ErrorKind.Insertion, "", h[k], context(op.i2))
                    }
                    "delete" -> for (i in op.i1 until op.i2) errors += WordError(ErrorKind.Deletion, refWords[i], "", context(i))
                    "insert" -> for (j in op.j1 until op.j2) errors += WordError(ErrorKind.Insertion, "", hypWords[j], context(op.i1))
                }
            }

            val topPairs = pass.pairs
                .filter { it.first.isNotEmpty() && it.second.isNotEmpty() && !it.first.startsWith("<") && !it.second.startsWith("<") }
                .groupingBy { it }
                .eachCount()
                .entries
                .sortedWith(compareByDescending<Map.Entry<Pair<String, String>, Int>> { it.value }.thenBy { it.key.first })
                .take(TOP_PAIRS)
                .map { Triple(it.key.first, it.key.second, it.value) }

            // Speakers, only when the reference says who spoke.
            var speakerPercent: Double? = null
            val speakerRows = mutableListOf<SpeakerRow>()
            val misattributed = mutableListOf<Misattribution>()
            if (refTagged.isNotEmpty()) {
                val result = SpeakerAccuracy.score(reference, ordered, language)
                if (result != null) {
                    speakerPercent = result.percent
                    val order = refTagged.map { it.speaker }.distinct()
                    for (ref in order) {
                        val mapped = result.mapping[ref]
                        val compared = result.counts.filterKeys { it.first == ref }.values.sum()
                        val matched = mapped?.let { result.counts[ref to it] } ?: 0
                        speakerRows += SpeakerRow(ref, mapped, compared, matched)
                    }

                    // Runs of matched words whose transcript label is not the one this reference
                    // speaker maps to. Contiguous in the transcript, same pair of labels.
                    var run: Misattribution? = null
                    var runWords = mutableListOf<String>()
                    var lastJ = -2
                    fun close() {
                        run?.let { misattributed += it.copy(words = runWords.size, excerpt = runWords.take(EXCERPT_WORDS).joinToString(" ") + if (runWords.size > EXCERPT_WORDS) " …" else "") }
                        run = null; runWords = mutableListOf()
                    }
                    for (op in ops) {
                        if (op.tag != "equal") continue
                        for (k in 0 until (op.i2 - op.i1)) {
                            val i = op.i1 + k; val j = op.j1 + k
                            val refSpeaker = refTagged[i].speaker
                            val expected = result.mapping[refSpeaker] ?: continue
                            val actual = hypLabel[j]
                            if (actual == expected) { close(); lastJ = j; continue }
                            val continues = run != null && run!!.referenceLabel == refSpeaker && run!!.actualLabel == actual && j == lastJ + 1
                            if (!continues) { close(); run = Misattribution(hypStart[j], refSpeaker, expected, actual, 0, "") }
                            runWords += hypWords[j]; lastJ = j
                        }
                    }
                    close()
                }
            }

            return TranscriptComparison(
                referenceWords = refWords.size,
                hypothesisWords = hypWords.size,
                coveragePercent = 100.0 * hypWords.size / refWords.size,
                werPercent = pass.werPercent,
                substitutions = pass.substitutions,
                deletions = pass.deletions,
                insertions = pass.insertions,
                speakerAccuracyPercent = speakerPercent,
                speakers = speakerRows,
                misattributed = misattributed,
                wordErrors = errors,
                topPairs = topPairs,
            )
        }
    }
}
