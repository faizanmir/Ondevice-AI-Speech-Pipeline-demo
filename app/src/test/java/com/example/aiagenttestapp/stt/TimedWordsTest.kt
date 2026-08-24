package com.example.aiagenttestapp.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sub-word tokens into words, which is the step that makes speaker attribution possible at all.
 *
 * Every failure here is silent in the output: a dropped word-start marker merges two words into one
 * that still reads plausibly, and a control token turned into a word puts "<pad>" in a transcript
 * with a real timestamp on it. Neither raises anything.
 */
class TimedWordsTest {

    /** The real marker, U+2581 -- not an underscore, and the tests would pass against one. */
    private val M = '▁'

    @Test
    fun `joins the pieces of one word and keeps the first piece's time`() {
        val words = TimedWords.fromTokens(
            tokens = listOf("${M}re", "cert", "ification"),
            timestamps = floatArrayOf(1.0f, 1.2f, 1.4f),
            clipEndSeconds = 2.0f,
        )

        assertEquals(1, words.size)
        assertEquals("recertification", words[0].text)
        assertEquals("a word starts when its first piece does", 1.0f, words[0].startSeconds, 1e-4f)
    }

    @Test
    fun `splits on the word-start marker and not on anything else`() {
        val words = TimedWords.fromTokens(
            tokens = listOf("${M}good", "${M}morn", "ing"),
            timestamps = floatArrayOf(0.5f, 1.0f, 1.3f),
            clipEndSeconds = 2.0f,
        )

        assertEquals(listOf("good", "morning"), words.map { it.text })
        assertEquals(0.5f, words[0].startSeconds, 1e-4f)
        assertEquals(1.0f, words[1].startSeconds, 1e-4f)
    }

    @Test
    fun `control tokens never become words`() {
        // These carry timestamps like any other token. A "<pad>" in a transcript would be visible;
        // a "<pad>" holding a turn boundary open would not.
        val words = TimedWords.fromTokens(
            tokens = listOf("<|startoftranscript|>", "${M}yes", "<pad>", "${M}indeed", "<|endoftext|>"),
            timestamps = floatArrayOf(0.0f, 0.4f, 0.8f, 1.0f, 1.5f),
            clipEndSeconds = 2.0f,
        )

        assertEquals(listOf("yes", "indeed"), words.map { it.text })
    }

    @Test
    fun `a word runs up to the next word, and the last one to the clip end`() {
        val words = TimedWords.fromTokens(
            tokens = listOf("${M}one", "${M}two"),
            timestamps = floatArrayOf(1.0f, 3.0f),
            clipEndSeconds = 5.0f,
        )

        assertEquals(3.0f, words[0].endSeconds, 1e-4f)
        assertEquals(5.0f, words[1].endSeconds, 1e-4f)
    }

    @Test
    fun `a clip end before the last word does not invert the range`() {
        // An inverted range matches no diarisation turn, so the word would silently lose its speaker
        // rather than fail. Reachable from rounding when a word starts in the final frame.
        val words = TimedWords.fromTokens(
            tokens = listOf("${M}trailing"),
            timestamps = floatArrayOf(4.0f),
            clipEndSeconds = 3.0f,
        )

        assertTrue("end must not precede start", words[0].endSeconds >= words[0].startSeconds)
    }

    @Test
    fun `stops at the shorter of tokens and timestamps`() {
        // Rather than placing a word at a guessed time: a word attributed to the wrong speaker is
        // worse than a word left out, because only one of the two is visible to the reader.
        val words = TimedWords.fromTokens(
            tokens = listOf("${M}a", "${M}b", "${M}c"),
            timestamps = floatArrayOf(0.1f, 0.2f),
            clipEndSeconds = 1.0f,
        )

        assertEquals(listOf("a", "b"), words.map { it.text })
    }

    @Test
    fun `a leading piece with no marker still opens a word`() {
        // sherpa strips the leading space from a result, so the very first token of a clip can arrive
        // without its marker. Dropping it would lose the first word of every decode.
        val words = TimedWords.fromTokens(
            tokens = listOf("good", "${M}morning"),
            timestamps = floatArrayOf(0.2f, 0.9f),
            clipEndSeconds = 2.0f,
        )

        assertEquals(listOf("good", "morning"), words.map { it.text })
    }

    // -------- Whisper's vocabulary, which marks words the other way --------

    @Test
    fun `a leading space starts a word, the way Whisper marks one`() {
        // Byte-level BPE: the marker is a real space and sherpa joins tokens with no separator of
        // its own. Splitting on U+2581 alone would fuse this whole clip into one "word" holding one
        // timestamp -- readable text, and speaker attribution pinned to the first syllable.
        val words = TimedWords.fromTokens(
            tokens = listOf(" Good", " morn", "ing", " everyone"),
            timestamps = floatArrayOf(0.2f, 0.6f, 0.8f, 1.1f),
            clipEndSeconds = 2.0f,
        )

        assertEquals(listOf("Good", "morning", "everyone"), words.map { it.text })
        assertEquals(0.6f, words[1].startSeconds, 1e-4f)
    }

    @Test
    fun `both vocabularies work through the same function`() {
        // The two markers cannot collide -- sentencepiece never emits a leading space and byte-level
        // BPE never emits U+2581 -- so neither model needs to be told apart here.
        val sentencepiece = TimedWords.fromTokens(
            listOf("${M}audit", "${M}report"), floatArrayOf(0f, 1f), 2f,
        )
        val byteLevel = TimedWords.fromTokens(
            listOf(" audit", " report"), floatArrayOf(0f, 1f), 2f,
        )

        assertEquals(sentencepiece.map { it.text }, byteLevel.map { it.text })
    }

    @Test
    fun `a space-only token does not become an empty word`() {
        // Byte-level BPE can emit a bare separator. Stripped it is blank, and a blank word with a
        // real timestamp would take a turn boundary with it.
        val words = TimedWords.fromTokens(
            tokens = listOf(" hello", " ", " world"),
            timestamps = floatArrayOf(0f, 0.5f, 0.6f),
            clipEndSeconds = 1f,
        )

        assertEquals(listOf("hello", "world"), words.map { it.text })
    }

    @Test
    fun `handles empty input`() {
        assertEquals(emptyList<TimedWord>(), TimedWords.fromTokens(emptyList(), floatArrayOf(), 1f))
    }

    @Test
    fun `offsetting moves a slice into recording coordinates`() {
        // Slices are decoded independently and each reports times from its own zero. Without this
        // every slice's words would claim to be at the start of the recording.
        val words = listOf(TimedWord("word", 1.0f, 2.0f))

        val moved = TimedWords.offsetBySamples(words, offsetSamples = 32_000, sampleRate = 16_000)

        assertEquals(3.0f, moved[0].startSeconds, 1e-4f)
        assertEquals(4.0f, moved[0].endSeconds, 1e-4f)
    }
}
