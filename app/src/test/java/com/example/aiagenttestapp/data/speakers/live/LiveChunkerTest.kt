package com.example.aiagenttestapp.data.speakers.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveChunkerTest {

    private val rate = 16_000
    private fun s(seconds: Double) = (seconds * rate).toInt()
    private fun chunker() = LiveChunker(minSamples = s(30.0), maxSamples = s(45.0), padSamples = s(0.25))

    @Test
    fun `nothing is cut before thirty seconds have been classified`() {
        val c = chunker()
        val regions = listOf(0 until s(12.0), s(12.5) until s(28.0))
        assertNull(c.cutPoint(regions, classifiedUpTo = s(29.0), consumed = s(29.0)))
    }

    @Test
    fun `the first settled pause after thirty seconds is the cut, a quarter second in`() {
        val c = chunker()
        // speech to 31.0 s, silence settled to 32.0 s
        val regions = listOf(0 until s(12.0), s(12.5) until s(31.0))
        val cut = c.cutPoint(regions, classifiedUpTo = s(32.0), consumed = s(32.0))
        assertEquals(LiveChunker.Cut.AtSilence(s(31.25)), cut)
    }

    @Test
    fun `a pause that ended before thirty seconds is not used`() {
        val c = chunker()
        val regions = listOf(0 until s(29.0), s(29.6) until s(33.0))
        // the 29.0-29.6 gap is too early; speech is still in progress at 33.0
        assertNull(c.cutPoint(regions, classifiedUpTo = s(33.0), consumed = s(33.4)))
    }

    @Test
    fun `a pause still in progress is not trusted until it is a quarter second long`() {
        val c = chunker()
        val regions = listOf(0 until s(31.0))
        assertNull(c.cutPoint(regions, classifiedUpTo = s(31.1), consumed = s(31.1)))
        assertEquals(
            LiveChunker.Cut.AtSilence(s(31.25)),
            c.cutPoint(regions, classifiedUpTo = s(31.3), consumed = s(31.3)),
        )
    }

    @Test
    fun `a speaker who never pauses is cut at the cap with a search window`() {
        val c = chunker()
        val regions = listOf(0 until s(44.0))
        val cut = c.cutPoint(regions, classifiedUpTo = s(44.0), consumed = s(45.0))
        assertEquals(LiveChunker.Cut.AtCap(s(30.0), s(45.0)), cut)
    }

    @Test
    fun `committing moves the chunk start and numbers chunks in order`() {
        val c = chunker()
        val first = c.commit(s(31.25))
        assertEquals(LiveChunk(0, 0, s(31.25)), first)
        assertEquals(s(31.25), c.chunkStart)
        // the next chunk needs its own thirty seconds
        val regions = listOf(s(31.5) until s(50.0))
        assertNull(c.cutPoint(regions, classifiedUpTo = s(51.0), consumed = s(51.0)))
        val second = c.cutPoint(regions + listOf(s(50.4) until s(62.0)), classifiedUpTo = s(62.5), consumed = s(62.5))
        assertEquals(LiveChunker.Cut.AtSilence(s(62.25)), second)
        assertEquals(1, c.commit(s(62.25)).index)
    }

    @Test
    fun `finish hands back the tail and nothing when there is none`() {
        val c = chunker()
        c.commit(s(31.0))
        assertEquals(LiveChunk(1, s(31.0), s(40.0)), c.finish(s(40.0)))
        assertNull(c.finish(s(40.0)))
    }

    @Test
    fun `the silence cut never runs past the audio consumed`() {
        val c = chunker()
        val regions = listOf(0 until s(31.0))
        val cut = c.cutPoint(regions, classifiedUpTo = s(31.3), consumed = s(31.1)) as LiveChunker.Cut.AtSilence
        assertTrue(cut.sample <= s(31.1))
    }
}
