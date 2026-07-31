package com.example.aiagent.engine.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputGuardTest {

    @Test
    fun `passes everything through when unbounded and no stops`() {
        val guard = OutputGuard(SamplingParams.UNLIMITED, emptyList())
        assertEquals("Hello", guard.push("Hello"))
        assertEquals(" world", guard.push(" world"))
        assertFalse(guard.isDone)
        assertEquals("", guard.drain())
    }

    @Test
    fun `stops at the token cap and ignores further chunks`() {
        val guard = OutputGuard(2, emptyList())
        assertEquals("a", guard.push("a"))
        assertFalse(guard.isDone)
        assertEquals("b", guard.push("b"))
        assertTrue(guard.isDone)
        // Anything after the cap is dropped, not emitted.
        assertEquals("", guard.push("c"))
    }

    @Test
    fun `trims at a stop string inside a single chunk`() {
        val guard = OutputGuard(SamplingParams.UNLIMITED, listOf("STOP"))
        assertEquals("keep ", guard.push("keep STOP drop this"))
        assertTrue(guard.isDone)
    }

    @Test
    fun `matches a stop string that spans two chunks, leaking nothing after it`() {
        val guard = OutputGuard(SamplingParams.UNLIMITED, listOf("<end>"))
        val out = StringBuilder()
        out.append(guard.push("hello <e"))
        assertFalse(guard.isDone)
        out.append(guard.push("nd> bye"))
        assertTrue(guard.isDone)
        // Everything before "<end>" is kept; the marker and the trailing " bye" are dropped.
        assertEquals("hello ", out.toString())
    }

    @Test
    fun `drain releases the held-back tail when the stream ends on its own`() {
        val guard = OutputGuard(SamplingParams.UNLIMITED, listOf("XYZ"))
        val out = StringBuilder()
        out.append(guard.push("abcd")) // holds back the last two chars in case "XYZ" starts there
        out.append(guard.drain())
        assertEquals("abcd", out.toString())
        assertFalse(guard.isDone)
    }

    @Test
    fun `the token cap flushes held-back stop-guard text`() {
        val guard = OutputGuard(1, listOf("QQ"))
        // One chunk hits the cap; nothing may be left stranded in the hold-back buffer.
        assertEquals("done", guard.push("done"))
        assertTrue(guard.isDone)
        assertEquals("", guard.drain())
    }
}
