package com.example.aiagenttestapp.ui.notes

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the fixed-size capture window that replaced the unbounded recording buffer.
 *
 * The old buffer kept every sample, so "give me the last four seconds" was trivially correct and
 * untested. This one wraps, and a wraparound bug would not crash -- it would quietly hand the voice
 * command detector a window stitched together in the wrong order, which presents as commands
 * mysteriously not being recognised.
 */
class RollingSampleWindowTest {

    private fun ramp(n: Int, from: Int = 0) = FloatArray(n) { i -> (from + i).toFloat() }

    @Test
    fun `holds everything while it still fits`() {
        val window = RollingSampleWindow(capacity = 100)
        window.append(ramp(30))
        window.append(ramp(20, 30))

        assertArrayEquals(ramp(50), window.takeLast(50), 0f)
        assertArrayEquals(ramp(50), window.takeLast(999), 0f)
    }

    @Test
    fun `keeps the newest samples once full and drops the oldest`() {
        val window = RollingSampleWindow(capacity = 100)
        // 250 samples through a 100-sample ring: only 150..249 can survive.
        for (start in 0 until 250 step 25) window.append(ramp(25, start))

        assertArrayEquals(ramp(100, 150), window.takeLast(100), 0f)
        assertArrayEquals(ramp(10, 240), window.takeLast(10), 0f)
    }

    /**
     * The case the ring exists for: chunks that do not divide the capacity, so nearly every append
     * straddles the wrap point.
     */
    @Test
    fun `chunk sizes coprime with the capacity still read back in order`() {
        val window = RollingSampleWindow(capacity = 64)
        var next = 0
        repeat(40) {
            window.append(ramp(7, next))
            next += 7
        }

        val last = window.takeLast(64)
        assertArrayEquals(ramp(64, next - 64), last, 0f)
        // Monotonic: the real failure mode is a window that is stitched out of order rather than
        // one that is missing samples.
        for (i in 1 until last.size) {
            assertTrue("sample $i must follow ${i - 1}", last[i] > last[i - 1])
        }
    }

    @Test
    fun `a chunk larger than the ring contributes only its tail`() {
        val window = RollingSampleWindow(capacity = 50)
        window.append(ramp(500))

        assertArrayEquals(ramp(50, 450), window.takeLast(50), 0f)
    }

    @Test
    fun `clear empties it and the ring is reusable afterwards`() {
        val window = RollingSampleWindow(capacity = 32)
        window.append(ramp(100))
        window.clear()

        assertEquals(0, window.takeLast(32).size)

        window.append(ramp(10, 1000))
        assertArrayEquals(ramp(10, 1000), window.takeLast(32), 0f)
    }

    @Test
    fun `asking for more than has been captured yields only what there is`() {
        val window = RollingSampleWindow(capacity = 100)
        window.append(ramp(5))

        assertArrayEquals(ramp(5), window.takeLast(80), 0f)
    }

    @Test
    fun `an empty window yields nothing rather than throwing`() {
        assertEquals(0, RollingSampleWindow(capacity = 16).takeLast(16).size)
    }

    /** The memory promise: capacity is fixed no matter how much audio goes through it. */
    @Test
    fun `capacity never grows`() {
        val window = RollingSampleWindow(capacity = 64)
        repeat(1000) { window.append(ramp(37, it * 37)) }

        assertEquals(64, window.takeLast(10_000).size)
    }
}
