package com.example.aiagenttestapp.data.speakers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptComparisonTest {

    private val rate = 16_000
    private fun block(id: Long, from: Int, name: String, text: String) =
        DiarizedBlock(id = id, recordingId = 1, startSample = from * rate, endSample = (from + 5) * rate, cluster = 0, speakerName = name, text = text)

    private val reference = """
        [S1] hello there my friend how are you today
        [S2] good morning to you I am well thanks
        [S1] yes indeed it is a fine day
    """.trimIndent()

    @Test
    fun `a perfect transcript has no errors and every speaker at one hundred`() {
        val blocks = listOf(
            block(1, 0, "Bob", "hello there my friend how are you today"),
            block(2, 10, "Tim", "good morning to you I am well thanks"),
            block(3, 20, "Bob", "yes indeed it is a fine day"),
        )
        val c = TranscriptComparison.of(reference, "en", blocks)!!
        assertEquals(0.0, c.werPercent, 1e-9)
        assertEquals(100.0, c.speakerAccuracyPercent!!, 1e-9)
        assertTrue(c.wordErrors.isEmpty())
        assertTrue(c.misattributed.isEmpty())
        assertEquals(listOf("s1" to "Bob", "s2" to "Tim"), c.speakers.map { it.referenceLabel to it.transcriptLabel })
    }

    @Test
    fun `word errors are itemised with their kind and the words before them`() {
        val blocks = listOf(
            block(1, 0, "Bob", "hello there my fiend how are you"),          // friend->fiend, today dropped
            block(2, 10, "Tim", "good morning to you I am very well thanks"), // very inserted
            block(3, 20, "Bob", "yes indeed it is a fine day"),
        )
        val c = TranscriptComparison.of(reference, "en", blocks)!!
        val kinds = c.wordErrors.map { it.kind }
        assertEquals(1, kinds.count { it == TranscriptComparison.ErrorKind.Substitution })
        assertEquals(1, kinds.count { it == TranscriptComparison.ErrorKind.Deletion })
        assertEquals(1, kinds.count { it == TranscriptComparison.ErrorKind.Insertion })
        val sub = c.wordErrors.first { it.kind == TranscriptComparison.ErrorKind.Substitution }
        assertEquals("friend" to "fiend", sub.reference to sub.hypothesis)
        assertEquals("hello there my", sub.context)
        assertEquals(1, c.substitutions); assertEquals(1, c.deletions); assertEquals(1, c.insertions)
    }

    @Test
    fun `a stretch given to the wrong speaker is reported with its time and words`() {
        val blocks = listOf(
            block(1, 0, "Bob", "hello there my friend how are you today"),
            block(2, 10, "Tim", "good morning to you"),
            block(3, 13, "Bob", "I am well thanks"),          // Tim's words, labelled Bob
            block(4, 20, "Bob", "yes indeed it is a fine day"),
        )
        val c = TranscriptComparison.of(reference, "en", blocks)!!
        assertEquals(1, c.misattributed.size)
        val m = c.misattributed.single()
        assertEquals(13 * rate, m.startSample)
        assertEquals("s2", m.referenceLabel)
        assertEquals("Tim", m.expectedLabel)
        assertEquals("Bob", m.actualLabel)
        assertEquals(4, m.words)
        assertEquals("i am well thanks", m.excerpt)
        val tim = c.speakers.first { it.referenceLabel == "s2" }
        assertEquals(8, tim.comparedWords); assertEquals(4, tim.matchedWords)
    }

    @Test
    fun `a reference without speaker tags still itemises words and reports no speakers`() {
        val blocks = listOf(block(1, 0, "Bob", "hello there my friend"))
        val c = TranscriptComparison.of("hello there my friend how", "en", blocks)!!
        assertNull(c.speakerAccuracyPercent)
        assertTrue(c.speakers.isEmpty())
        assertEquals(1, c.deletions)
    }

    @Test
    fun `nothing to compare gives null`() {
        assertNull(TranscriptComparison.of("", "en", listOf(block(1, 0, "Bob", "hi"))))
        assertNull(TranscriptComparison.of("[S1] hi", "en", emptyList()))
        assertNotNull(TranscriptComparison.of("[S1] hi", "en", listOf(block(1, 0, "Bob", "hi"))))
    }
}
