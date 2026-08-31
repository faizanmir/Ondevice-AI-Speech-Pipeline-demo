package com.example.aiagenttestapp.data.speakers.live

import com.example.aiagenttestapp.data.speakers.SpeakerAlignment
import com.example.aiagenttestapp.data.speakers.SpeakerBlock
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveTranscriptTest {

    private val rate = 16_000
    private fun block(cluster: Int, from: Int, until: Int, text: String) =
        SpeakerBlock(cluster, from * rate, until * rate, text)

    @Test
    fun `renders chunks in index order however they were recorded`() {
        val t = LiveTranscript()
        t.record(1, listOf(block(1, 30, 40, "sure I have the numbers")))
        t.record(0, listOf(block(0, 0, 30, "let's start with the budget")))
        val out = t.render(mapOf(0 to "Speaker A", 1 to "Speaker B"), "Unknown Speaker ?", "Unknown Speaker", 2 * rate)
        assertEquals(listOf("Speaker A", "Speaker B"), out.map { it.name })
        assertEquals(0, out.first().startSample)
    }

    @Test
    fun `labels are applied at render time so a rename reaches earlier chunks`() {
        val t = LiveTranscript()
        t.record(0, listOf(block(0, 0, 30, "hello")))
        assertEquals("Speaker A", t.render(mapOf(0 to "Speaker A"), "?", "Unknown Speaker", 0).single().name)
        assertEquals("Bob", t.render(mapOf(0 to "Bob"), "?", "Unknown Speaker", 0).single().name)
    }

    @Test
    fun `unattributed blocks take the unknown label`() {
        val t = LiveTranscript()
        t.record(0, listOf(block(SpeakerAlignment.UNATTRIBUTED, 0, 10, "um")))
        assertEquals("Unknown Speaker ?", t.render(emptyMap(), "Unknown Speaker ?", "Unknown Speaker", 0).single().name)
    }

    @Test
    fun `empty when nothing has been recorded`() {
        assertEquals(emptyList<Any>(), LiveTranscript().render(emptyMap(), "?", "Unknown Speaker", 0))
    }
}
