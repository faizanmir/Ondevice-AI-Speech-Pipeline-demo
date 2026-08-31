package com.example.aiagenttestapp.data.speakers.live

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ChunkBufferTest {

    private fun block(from: Int, size: Int) = FloatArray(size) { (from + it).toFloat() }

    @Test
    fun `take hands back the range and drops what came before it`() {
        val b = ChunkBuffer(capacity = 16)
        b.append(0, block(0, 8))
        b.append(8, block(8, 8))
        assertArrayEquals(block(0, 10), b.take(0, 10), 0f)
        assertEquals(10, b.startSample)
        assertEquals(16, b.endSample)
        assertArrayEquals(block(10, 6), b.take(10, 16), 0f)
        assertEquals(16, b.startSample)
        assertEquals(16, b.endSample)
    }

    @Test
    fun `peek leaves the buffer as it was`() {
        val b = ChunkBuffer(capacity = 16)
        b.append(100, block(100, 8))
        assertArrayEquals(block(102, 4), b.peek(102, 106), 0f)
        assertEquals(100, b.startSample)
        assertEquals(108, b.endSample)
    }

    /**
     * The cap path of the live chunker: look at the whole buffered stretch to find a quiet frame,
     * then take the chunk up to that frame. Done with two takes this indexed before the array.
     */
    @Test
    fun `peek then take is the cap path and stays in bounds`() {
        val b = ChunkBuffer(capacity = 16)
        b.append(0, block(0, 8))
        b.append(8, block(8, 8))
        val whole = b.peek(0, 16)
        assertEquals(16, whole.size)
        val chunk = b.take(0, 12)
        assertArrayEquals(block(0, 12), chunk, 0f)
        assertEquals(12, b.startSample)
        assertArrayEquals(block(12, 4), b.peek(12, 16), 0f)
    }

    @Test
    fun `an empty buffer starts wherever the next block lands`() {
        val b = ChunkBuffer(capacity = 8)
        b.append(0, block(0, 8))
        b.take(0, 8)
        b.append(8, block(8, 4))
        assertEquals(8, b.startSample)
        assertArrayEquals(block(8, 4), b.take(8, 12), 0f)
    }

    @Test
    fun `grows past its capacity when a chunk runs long`() {
        val b = ChunkBuffer(capacity = 4)
        b.append(0, block(0, 4))
        b.append(4, block(4, 4))
        b.append(8, block(8, 4))
        assertArrayEquals(block(0, 12), b.take(0, 12), 0f)
    }

    @Test(expected = IllegalStateException::class)
    fun `reading outside the buffer is an error, not a silent wrong slice`() {
        val b = ChunkBuffer(capacity = 8)
        b.append(100, block(100, 8))
        b.peek(90, 104)
    }
}
