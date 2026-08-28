package com.example.aiagenttestapp.data.speakers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class InOrderChunksTest {

    @Test
    fun `in-order arrivals pass straight through`() {
        val buffer = InOrderChunks<String>()

        assertEquals(listOf("a"), buffer.offer(0, "a"))
        assertEquals(listOf("b"), buffer.offer(1, "b"))
        assertEquals(listOf("c"), buffer.offer(2, "c"))
        assertTrue(buffer.isDrained)
    }

    @Test
    fun `an early chunk is held until the gap before it fills`() {
        val buffer = InOrderChunks<String>()

        // Lane 1 finishes chunk 1 first -- nothing can be released yet.
        assertEquals(emptyList<String>(), buffer.offer(1, "b"))
        // Chunk 0 lands and unblocks both.
        assertEquals(listOf("a", "b"), buffer.offer(0, "a"))
        assertTrue(buffer.isDrained)
    }

    @Test
    fun `two round-robin lanes interleave and everything comes out in order`() {
        // The real shape: lane 0 owns chunks 0 and 2, lane 1 owns 1 and 3, and lane 1 runs ahead.
        val buffer = InOrderChunks<Int>()
        val released = mutableListOf<Int>()

        released += buffer.offer(1, 1)
        released += buffer.offer(0, 0)
        released += buffer.offer(3, 3)
        released += buffer.offer(2, 2)

        assertEquals(listOf(0, 1, 2, 3), released)
        assertTrue(buffer.isDrained)
    }

    @Test
    fun `a single chunk is the degenerate case and just passes through`() {
        val buffer = InOrderChunks<String>()

        assertEquals(listOf("only"), buffer.offer(0, "only"))
        assertTrue(buffer.isDrained)
    }

    @Test
    fun `a duplicate index is a programming error, not a silent overwrite`() {
        val buffer = InOrderChunks<String>()
        buffer.offer(1, "b")

        assertThrows(IllegalArgumentException::class.java) { buffer.offer(1, "again") }

        // Released indices are refused too: re-offering them could not be delivered in order.
        buffer.offer(0, "a")
        assertThrows(IllegalArgumentException::class.java) { buffer.offer(0, "again") }
    }

    @Test
    fun `a gap holds everything behind it`() {
        val buffer = InOrderChunks<Int>()

        assertEquals(emptyList<Int>(), buffer.offer(2, 2))
        assertEquals(emptyList<Int>(), buffer.offer(3, 3))
        assertEquals(emptyList<Int>(), buffer.offer(1, 1))
        assertEquals(listOf(0, 1, 2, 3), buffer.offer(0, 0))
        assertTrue(buffer.isDrained)
    }
}
