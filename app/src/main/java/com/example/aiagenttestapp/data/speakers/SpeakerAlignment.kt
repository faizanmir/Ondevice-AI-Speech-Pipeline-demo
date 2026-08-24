package com.example.aiagenttestapp.data.speakers

import com.example.aiagenttestapp.stt.DiarizedSegment
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
     * Words falling in no turn inherit a speaker only when the turns on both sides agree. Diarisation
     * reports confident speech rather than continuous coverage, so this preserves a breath in one
     * person's paragraph without carrying that person indefinitely across an unsupported gap into
     * somebody else's turn.
     */
    fun blocks(
        words: List<TimedWord>,
        turns: List<DiarizedSegment>,
        sampleRate: Int,
    ): List<SpeakerBlock> {
        if (words.isEmpty()) return emptyList()

        val ordered = turns.sortedBy { it.startSample }
        val blocks = mutableListOf<SpeakerBlock>()

        var currentCluster: Int? = null
        var currentWords = mutableListOf<TimedWord>()
        var currentStart = 0

        fun nearestClusterBefore(sample: Int): Int? {
            val nearestEnd = ordered.asSequence()
                .filter { it.endSample <= sample }
                .maxOfOrNull { it.endSample }
                ?: return null
            return ordered.asSequence()
                .filter { it.endSample == nearestEnd }
                .map { it.cluster }
                .distinct()
                .singleOrNull()
        }

        fun nearestClusterAfter(sample: Int): Int? {
            val nearestStart = ordered.asSequence()
                .filter { it.startSample > sample }
                .minOfOrNull { it.startSample }
                ?: return null
            return ordered.asSequence()
                .filter { it.startSample == nearestStart }
                .map { it.cluster }
                .distinct()
                .singleOrNull()
        }

        fun flush() {
            if (currentWords.isEmpty()) return
            blocks += SpeakerBlock(
                cluster = currentCluster ?: UNATTRIBUTED,
                startSample = currentStart,
                endSample = (currentWords.last().endSeconds * sampleRate).toInt(),
                text = currentWords.joinToString(" ") { it.text },
            )
            currentWords = mutableListOf()
        }

        for (word in words) {
            val reportedDuration = (word.endSeconds - word.startSeconds).coerceAtLeast(0f)
            val evidenceSeconds = word.startSeconds +
                minOf(reportedDuration / 2f, MAX_WORD_EVIDENCE_OFFSET_SECONDS)
            val evidenceSample = (evidenceSeconds * sampleRate).toInt()
            val candidates = ordered.asSequence()
                .filter { evidenceSample >= it.startSample && evidenceSample < it.endSample }
                .map { it.cluster }
                .distinct()
                .toList()
            val bracketedGapCluster = nearestClusterBefore(evidenceSample)
                ?.takeIf { cluster -> nearestClusterAfter(evidenceSample) == cluster }
            val cluster = when {
                candidates.size == 1 -> candidates.single()
                currentCluster != null && currentCluster in candidates -> currentCluster
                candidates.size > 1 -> null
                // No turn covers it: only matching evidence on both sides can fill the gap.
                else -> bracketedGapCluster
            }

            if (currentWords.isNotEmpty() && cluster != currentCluster) {
                flush()
            }
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
     * The cluster id used when diarisation had nothing to say about a stretch of speech.
     *
     * Only reachable when the *first* words of a recording fall outside every turn -- after that
     * there is always a previous speaker to carry forward. Negative so it can never collide with a
     * real cluster index, which sherpa numbers from zero.
     */
    const val UNATTRIBUTED = -1

    /** Far enough inside a word to clear a rounded boundary, but never far enough to cross a pause. */
    private const val MAX_WORD_EVIDENCE_OFFSET_SECONDS = 0.2f
}
