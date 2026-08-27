package com.example.aiagenttestapp.data.speakers

import com.example.aiagenttestapp.stt.DiarizedSegment
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the naming half of the diarisation post-process: that a cluster is named from the voiceprint
 * folding already computed, that the unnamed are numbered by first appearance, and that the counter
 * threads across chunks so per-chunk naming produces the same global numbering a single pass would.
 */
class ClusterNamingTest {

    private fun turn(start: Int, end: Int, cluster: Int) = DiarizedSegment(start, end, cluster)

    /** A voiceprint that matches [name]'s enrolment below is just that person's own vector. */
    private val daniel = floatArrayOf(1f, 0f, 0f)
    private val sarah = floatArrayOf(0f, 1f, 0f)
    private val enrolled = mapOf("Daniel" to listOf(daniel), "Sarah" to listOf(sarah))

    @Test
    fun `matched clusters take the enrolled name, unmatched are numbered by first appearance`() {
        // Cluster 2 speaks first, then 0, then 1. 0 and 2 are enrolled voices; 1 is a stranger.
        val turns = listOf(
            turn(0, 100, 2),
            turn(100, 200, 0),
            turn(200, 300, 1),
        )
        val voiceprints = mapOf(
            0 to daniel,
            2 to sarah,
            1 to floatArrayOf(0f, 0f, 1f), // matches nobody
        )

        val result = nameClustersByVoiceprint(
            turns = turns,
            voiceprints = voiceprints,
            enrolled = enrolled,
            threshold = 0.6f,
            minimumMargin = 0.05f,
            unknownPrefix = "Unknown Speaker",
            startingPlaceholder = 0,
        )

        assertEquals("Sarah", result.names[2])
        assertEquals("Daniel", result.names[0])
        // The stranger appeared third but is the first *unnamed* cluster, so it is number 1.
        assertEquals("Unknown Speaker 1", result.names[1])
        assertEquals(1, result.nextPlaceholder)
    }

    @Test
    fun `the placeholder counter threads across chunks`() {
        // First chunk left off having numbered one stranger; a second chunk of two more strangers
        // must continue from there rather than restart at 1.
        val turns = listOf(turn(0, 100, 7), turn(100, 200, 8))
        val result = nameClustersByVoiceprint(
            turns = turns,
            voiceprints = mapOf(7 to floatArrayOf(0f, 0f, 1f), 8 to floatArrayOf(0f, 0f, 1f)),
            enrolled = enrolled,
            threshold = 0.6f,
            minimumMargin = 0.05f,
            unknownPrefix = "Unknown Speaker",
            startingPlaceholder = 1,
        )

        assertEquals("Unknown Speaker 2", result.names[7])
        assertEquals("Unknown Speaker 3", result.names[8])
        assertEquals(3, result.nextPlaceholder)
    }

    @Test
    fun `a cluster absent from the voiceprints is treated as unnameable`() {
        // The embedder could not describe cluster 0 -- its key is missing, not null-valued -- and it
        // must fall through to a placeholder rather than crash or match by accident.
        val turns = listOf(turn(0, 100, 0))
        val result = nameClustersByVoiceprint(
            turns = turns,
            voiceprints = emptyMap(),
            enrolled = enrolled,
            threshold = 0.6f,
            minimumMargin = 0.05f,
            unknownPrefix = "Unknown Speaker",
            startingPlaceholder = 0,
        )

        assertEquals("Unknown Speaker 1", result.names[0])
        assertEquals(emptyMap<Int, Any>(), result.decisions)
    }

    @Test
    fun `a near-tie is left unnamed even above threshold`() {
        // Both enrolled voices score high and within the margin of each other; the native search
        // would return one name, but a transcript should not guess between two similar voices.
        val ambiguous = floatArrayOf(1f, 0.98f, 0f)
        val result = nameClustersByVoiceprint(
            turns = listOf(turn(0, 100, 0)),
            voiceprints = mapOf(0 to ambiguous),
            enrolled = enrolled,
            threshold = 0.6f,
            minimumMargin = 0.05f,
            unknownPrefix = "Unknown Speaker",
            startingPlaceholder = 0,
        )

        assertEquals("Unknown Speaker 1", result.names[0])
        // The decision is still recorded, so the log can show why the name was withheld.
        assertEquals(1, result.decisions.size)
    }
}
