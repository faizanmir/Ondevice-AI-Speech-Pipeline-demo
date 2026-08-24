package com.example.aiagenttestapp.stt

/** One word and where it sits in the audio, in seconds from the start of the decoded clip. */
data class TimedWord(
    val text: String,
    val startSeconds: Float,
    val endSeconds: Float,
)

/**
 * Turns a recogniser's sub-word tokens and their frame times into whole words with times.
 *
 * sherpa returns one timestamp per *token*, and a token is a BPE piece rather than a word --
 * "recertification" arrives as five of them. Nothing downstream wants pieces: speaker attribution
 * asks "who was talking when this word was said", and half a word has no answer to that.
 *
 * Two vocabularies reach here and they mark a word start differently, which is the whole reason this
 * is a shared function rather than a detail of one model:
 *
 *  - **Parakeet** uses sentencepiece: a word-initial token carries [WORD_START], U+2581.
 *  - **Whisper** uses byte-level BPE: a word-initial token carries a leading *space*, and sherpa
 *    joins tokens with `text += s` and no separator of its own.
 *
 * Splitting on only one of them is silent rather than loud. Point the sentencepiece rule at Whisper
 * and every token joins into one enormous "word" holding a single timestamp -- the transcript still
 * reads correctly, and speaker attribution collapses onto whoever was talking at the first syllable.
 */
object TimedWords {

    /**
     * The BPE word-start marker, U+2581 LOWER ONE EIGHTH BLOCK.
     *
     * Not a decorative underscore: it is how the vocabulary distinguishes "▁the" (a new word) from
     * "the" (the tail of another, as in "brea▁the" vs "breathe"). 4,172 of Parakeet's 8,193 tokens
     * carry it, and dropping the distinction merges every word in the transcript into its neighbour.
     */
    private const val WORD_START = '▁'

    /**
     * Whether [token] begins a new word under either vocabulary.
     *
     * Both markers are accepted for every model rather than switched on the model, because the token
     * is the only evidence available here and the two conventions cannot collide: sentencepiece
     * never emits a leading space, and byte-level BPE never emits U+2581.
     */
    private fun startsWord(token: String): Boolean =
        token[0] == WORD_START || token[0] == ' '

    /** The token without whichever word-start marker it carried. */
    private fun strip(token: String): String =
        if (startsWord(token)) token.substring(1) else token

    /**
     * Assembles [tokens] into words.
     *
     * [timestamps] is parallel to [tokens] -- one entry each, in seconds. A shorter array is treated
     * as the authority on how many tokens can be placed, because a word whose time we are guessing is
     * worse for attribution than a word we leave out.
     *
     * [clipEndSeconds] closes the final word. Every other word ends where the next one starts, which
     * is deliberately generous: the gap between two words belongs to whoever spoke the first of them
     * far more often than to nobody, and a diarisation turn boundary that lands in that gap should
     * still cut between the words rather than inside the pause.
     */
    fun fromTokens(
        tokens: List<String>,
        timestamps: FloatArray,
        clipEndSeconds: Float,
    ): List<TimedWord> {
        val usable = minOf(tokens.size, timestamps.size)
        if (usable <= 0) return emptyList()

        val texts = mutableListOf<StringBuilder>()
        val starts = mutableListOf<Float>()

        for (i in 0 until usable) {
            val raw = tokens[i]
            // Control tokens -- <unk>, <pad>, <|startoftranscript|> and friends -- carry a timestamp
            // like any other but are not speech. Kept out rather than stripped later, so they cannot
            // become a word with a real time attached to it.
            if (raw.isEmpty() || raw.startsWith('<')) continue

            val isNewWord = startsWord(raw)
            val piece = strip(raw)

            if (isNewWord || texts.isEmpty()) {
                texts += StringBuilder(piece)
                starts += timestamps[i]
            } else {
                texts.last().append(piece)
            }
        }

        return texts.indices.mapNotNull { index ->
            val text = texts[index].toString()
            if (text.isBlank()) return@mapNotNull null

            val start = starts[index]
            val end = starts.getOrNull(index + 1) ?: clipEndSeconds
            TimedWord(
                text = text,
                startSeconds = start,
                // A clip end that arrives before the last word's start would invert the range, and an
                // inverted range silently matches no diarisation turn at all.
                endSeconds = maxOf(end, start),
            )
        }
    }

    /** Shifts words from clip-relative seconds into whole-recording sample positions. */
    fun offsetBySamples(words: List<TimedWord>, offsetSamples: Int, sampleRate: Int): List<TimedWord> {
        if (offsetSamples == 0) return words
        val offset = offsetSamples.toFloat() / sampleRate
        return words.map {
            it.copy(startSeconds = it.startSeconds + offset, endSeconds = it.endSeconds + offset)
        }
    }
}
