package com.example.aiagenttestapp.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class WarmPoolTest {

    private val evicted = mutableListOf<String>()
    private val pool = WarmPool<String>(capacity = 4) { evicted += it }

    @Test
    fun `a stashed instance comes back for the same key`() {
        pool.stash("k", "model")

        assertSame("model", pool.acquire("k"))
        assertEquals(0, pool.size())
        assertEquals(emptyList<String>(), evicted)
    }

    @Test
    fun `an empty pool says load fresh`() {
        assertNull(pool.acquire("k"))
    }

    @Test
    fun `several instances under one key serve several lanes`() {
        // The real shape: four lanes stash four diarizers under the identical configuration.
        repeat(4) { pool.stash("k", "lane$it") }

        assertEquals(4, pool.size())
        val handed = (0 until 4).mapNotNull { pool.acquire("k") }
        assertEquals(4, handed.size)
        assertNull(pool.acquire("k"))
    }

    @Test
    fun `a configuration change evicts what will never match again`() {
        pool.stash("old-config", "stale")

        // First acquire under the new configuration cleans up; nothing subscribes to Settings.
        assertNull(pool.acquire("new-config"))
        assertEquals(listOf("stale"), evicted)
        assertEquals(0, pool.size())
    }

    @Test
    fun `the capacity is a ceiling, not a rotation`() {
        repeat(4) { pool.stash("k", "kept$it") }
        pool.stash("k", "overflow")

        assertEquals(listOf("overflow"), evicted)
        assertEquals(4, pool.size())
    }

    @Test
    fun `clear evicts everything`() {
        pool.stash("k", "a")
        pool.stash("k", "b")

        pool.clear()

        assertEquals(listOf("a", "b"), evicted)
        assertEquals(0, pool.size())
        assertNull(pool.acquire("k"))
    }
}
