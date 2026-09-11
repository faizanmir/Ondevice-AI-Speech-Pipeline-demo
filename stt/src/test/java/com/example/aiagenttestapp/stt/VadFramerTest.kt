package com.example.aiagenttestapp.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the chunk-to-frame reassembly the live VAD depends on.
 *
 * The property that matters is exact sample accounting: Silero's running position is the
 * recording's position, so a framer that dropped or duplicated even one sample per chunk would put
 * every region boundary progressively further from the audio it describes — silently, because
 * nothing downstream can tell a shifted boundary from a real one.
 */
class VadFramerTest {

    private val frame = 512

    /** Feeds [chunks] through a framer and returns every emitted frame, in order. */
    private fun framesOf(framer: VadFramer, chunks: List<FloatArray>): List<FloatArray> {
        val out = mutableListOf<FloatArray>()
        chunks.forEach { framer.accept(it) { f -> out += f } }
        return out
    }

    /** A stream numbered by position, so reassembly errors show up as value mismatches. */
    private fun numbered(from: Int, count: Int) = FloatArray(count) { (from + it).toFloat() }

    @Test
    fun `coprime chunk sizes reassemble the stream in order`() {
        val framer = VadFramer(frame)
        val chunks = (0 until 5).map { numbered(from = it * 700, count = 700) }

        val frames = framesOf(framer, chunks)

        assertTrue(frames.all { it.size == frame })
        val reassembled = frames.flatMap { it.toList() } + (framer.flush()?.toList() ?: emptyList())
        assertEquals(List(3500) { it.toFloat() }, reassembled)
    }

    /** The real case: capture chunks are 1600 samples, 3 frames plus a 64-sample carry each. */
    @Test
    fun `capture-sized chunks carry their remainder across chunks`() {
        val framer = VadFramer(frame)

        val afterOne = framesOf(framer, listOf(numbered(0, 1600)))
        assertEquals(3, afterOne.size)

        val afterTwo = framesOf(framer, listOf(numbered(1600, 1600)))
        // 3200 total = 6 full frames + 128 carried; the second chunk completes frames 4..6.
        assertEquals(3, afterTwo.size)
        assertEquals(3 * frame.toFloat(), afterTwo.first()[0])
    }

    @Test
    fun `flush returns exactly the carried tail`() {
        val framer = VadFramer(frame)
        framesOf(framer, listOf(numbered(0, frame + 100)))

        val tail = framer.flush()
        assertEquals(100, tail?.size)
        assertEquals(frame.toFloat(), tail?.first())
    }

    @Test
    fun `flush is null when the chunks divide evenly`() {
        val framer = VadFramer(frame)
        framesOf(framer, listOf(numbered(0, frame * 4)))
        assertNull(framer.flush())
    }

    /** Flush resets the carry, so one instance can serve stream after stream. */
    @Test
    fun `the framer is reusable after flush`() {
        val framer = VadFramer(frame)
        framesOf(framer, listOf(numbered(0, 300)))
        framer.flush()

        val frames = framesOf(framer, listOf(numbered(0, frame)))
        assertEquals(1, frames.size)
        assertEquals(0f, frames.first()[0])
    }

    @Test
    fun `an empty chunk emits nothing and carries nothing`() {
        val framer = VadFramer(frame)
        assertTrue(framesOf(framer, listOf(FloatArray(0))).isEmpty())
        assertNull(framer.flush())
    }
}
