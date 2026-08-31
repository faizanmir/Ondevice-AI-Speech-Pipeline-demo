package com.example.aiagenttestapp.data.speakers

import com.example.aiagenttestapp.stt.DiarizedSegment
import org.junit.Assert.assertEquals
import org.junit.Test

class ClusterNamingTieBreakTest {

    private fun unit(vararg v: Float): FloatArray {
        val n = Math.sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        return FloatArray(v.size) { v[it] / n }
    }

    private val voice = unit(1f, 0.1f, 0f)
    private val sessionA = unit(1f, 0.15f, 0f)   // ~0.99 to voice
    private val sessionB = unit(1f, 0.05f, 0f)   // ~0.99 to voice -- A and B were one voice, split live
    private val bob = unit(1f, 0f, 0.1f)          // an enrolled man who also sounds close
    private val turns = listOf(DiarizedSegment(0, 16_000, cluster = 0))

    private fun name(enrolled: Map<String, List<FloatArray>>, tieBreakable: Set<String>) =
        nameClustersByVoiceprint(
            turns = turns,
            voiceprints = mapOf(0 to voice),
            enrolled = enrolled,
            threshold = 0.6f,
            minimumMargin = 0.05f,
            unknownPrefix = "Unknown Speaker",
            startingPlaceholder = 0,
            tieBreakable = tieBreakable,
        ).names.getValue(0)

    @Test
    fun `a near tie between two session labels takes the best rather than a placeholder`() {
        val named = name(
            enrolled = mapOf("Speaker A" to listOf(sessionA), "Speaker B" to listOf(sessionB)),
            tieBreakable = setOf("Speaker A", "Speaker B"),
        )
        assertEquals("Speaker A", named)
    }

    @Test
    fun `the same near tie without the tie-break rule is still a placeholder`() {
        val named = name(
            enrolled = mapOf("Speaker A" to listOf(sessionA), "Speaker B" to listOf(sessionB)),
            tieBreakable = emptySet(),
        )
        assertEquals("Unknown Speaker 1", named)
    }

    @Test
    fun `a near tie between an enrolled person and a session label stays unknown`() {
        // Bob is a real person; guessing between him and a letter is the coin flip the margin forbids.
        val named = name(
            enrolled = mapOf("Bob" to listOf(bob), "Speaker A" to listOf(sessionA)),
            tieBreakable = setOf("Speaker A"),
        )
        assertEquals("Unknown Speaker 1", named)
    }

    @Test
    fun `a clear winner is accepted as before, tie-breakable or not`() {
        val named = name(
            enrolled = mapOf("Speaker A" to listOf(sessionA), "Stranger" to listOf(unit(0f, 0f, 1f))),
            tieBreakable = setOf("Speaker A", "Stranger"),
        )
        assertEquals("Speaker A", named)
    }
}
