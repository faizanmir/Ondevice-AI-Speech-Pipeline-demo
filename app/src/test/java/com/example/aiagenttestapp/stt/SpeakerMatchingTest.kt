package com.example.aiagenttestapp.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeakerMatchingTest {

    @Test
    fun `accepts a clear enrolled speaker above the threshold`() {
        val decision = matchSpeaker(
            embedding = floatArrayOf(1f, 0f),
            enrolled = mapOf(
                "Alice" to listOf(floatArrayOf(1f, 0f)),
                "Bob" to listOf(floatArrayOf(0f, 1f)),
            ),
            threshold = 0.6f,
            minimumMargin = 0.05f,
        )

        assertEquals("Alice", decision.acceptedName)
        assertEquals(1f, decision.bestScore, 1e-6f)
        assertEquals(0f, decision.runnerUpScore!!, 1e-6f)
    }

    @Test
    fun `rejects the closest speaker when the absolute score is too low`() {
        val decision = matchSpeaker(
            embedding = floatArrayOf(1f, 0f),
            enrolled = mapOf("Alice" to listOf(floatArrayOf(0.5f, 0.8660254f))),
            threshold = 0.6f,
            minimumMargin = 0.05f,
        )

        assertNull(decision.acceptedName)
        assertEquals("Alice", decision.bestName)
        assertEquals(0.5f, decision.bestScore, 1e-4f)
    }

    @Test
    fun `rejects an ambiguous winner that barely beats another person`() {
        val decision = matchSpeaker(
            embedding = floatArrayOf(1f, 0f),
            enrolled = mapOf(
                "Alice" to listOf(floatArrayOf(1f, 0f)),
                "Bob" to listOf(floatArrayOf(0.999f, 0.0447f)),
            ),
            threshold = 0.6f,
            minimumMargin = 0.05f,
        )

        assertNull(decision.acceptedName)
        assertEquals("Alice", decision.bestName)
        assertEquals("Bob", decision.runnerUpName)
    }

    @Test
    fun `one enrolled person needs the threshold but no imaginary runner-up`() {
        val decision = matchSpeaker(
            embedding = floatArrayOf(1f, 0f),
            enrolled = mapOf("Alice" to listOf(floatArrayOf(0.8f, 0.6f))),
            threshold = 0.6f,
            minimumMargin = 0.05f,
        )

        assertEquals("Alice", decision.acceptedName)
        assertNull(decision.runnerUpScore)
    }
}
