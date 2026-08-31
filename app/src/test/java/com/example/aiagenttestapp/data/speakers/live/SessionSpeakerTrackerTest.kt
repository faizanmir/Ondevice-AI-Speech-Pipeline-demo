package com.example.aiagenttestapp.data.speakers.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionSpeakerTrackerTest {

    // Unit vectors far apart; "near" variants are the same voice heard again with a little noise.
    private fun vec(vararg v: Float): FloatArray {
        val n = Math.sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        return FloatArray(v.size) { v[it] / n }
    }
    private val bob = vec(1f, 0f, 0f, 0f)
    private val bobAgain = vec(0.95f, 0.2f, 0.1f, 0f)
    private val tim = vec(0f, 1f, 0f, 0f)
    private val timAgain = vec(0.1f, 0.95f, 0.15f, 0f)
    private val stranger = vec(0f, 0f, 0f, 1f)

    private fun obs(cluster: Int, print: FloatArray?, seconds: Int, name: String? = null) =
        ClusterObservation(cluster, seconds * 16_000, print, name)

    @Test
    fun `first chunk opens one lettered speaker per cluster`() {
        val t = SessionSpeakerTracker()
        val ids = t.assign(listOf(obs(0, bob, 60), obs(1, tim, 50)))
        assertEquals(mapOf(0 to 0, 1 to 1), ids)
        assertEquals(mapOf(0 to "Speaker A", 1 to "Speaker B"), t.labels())
    }

    @Test
    fun `the same voice in the next chunk keeps its letter whatever sherpa numbered it`() {
        val t = SessionSpeakerTracker()
        t.assign(listOf(obs(0, bob, 60), obs(1, tim, 50)))
        // sherpa numbers from zero again, and this time Tim is cluster 0
        val ids = t.assign(listOf(obs(0, timAgain, 40), obs(1, bobAgain, 45)))
        assertEquals(1, ids[0])
        assertEquals(0, ids[1])
        assertEquals(2, t.speakers.size)
    }

    @Test
    fun `an enrolled match names the speaker and earlier letters follow`() {
        val t = SessionSpeakerTracker()
        t.assign(listOf(obs(0, bob, 60), obs(1, tim, 50)))
        assertEquals("Speaker A", t.labels()[0])
        t.assign(listOf(obs(0, bobAgain, 40, name = "Bob"), obs(1, timAgain, 40)))
        assertEquals("Bob", t.labels()[0])
        assertEquals("Speaker B", t.labels()[1])
        assertEquals(2, t.speakers.size)
    }

    @Test
    fun `a new voice opens a new speaker rather than joining the nearest`() {
        val t = SessionSpeakerTracker()
        t.assign(listOf(obs(0, bob, 60), obs(1, tim, 50)))
        val ids = t.assign(listOf(obs(0, stranger, 30)))
        assertEquals(2, ids[0])
        assertEquals("Speaker C", t.labels()[2])
    }

    @Test
    fun `two clusters in one chunk never share an unnamed speaker`() {
        val t = SessionSpeakerTracker()
        t.assign(listOf(obs(0, bob, 60)))
        // both clusters resemble Bob; the diariser said they are different voices
        val ids = t.assign(listOf(obs(0, bobAgain, 40), obs(1, vec(0.9f, 0.3f, 0.3f, 0f), 30)))
        assertEquals(0, ids[0])
        assertNotEquals(0, ids[1])
    }

    @Test
    fun `two clusters that both matched the same enrolled name are both that person`() {
        val t = SessionSpeakerTracker()
        val ids = t.assign(listOf(obs(0, bob, 60, "Bob"), obs(1, bobAgain, 10, "Bob")))
        assertEquals(ids[0], ids[1])
        assertEquals(1, t.speakers.size)
    }

    @Test
    fun `a cluster without a voiceprint is left out`() {
        val t = SessionSpeakerTracker()
        val ids = t.assign(listOf(obs(0, bob, 60), obs(1, null, 2)))
        assertEquals(setOf(0), ids.keys)
    }

    @Test
    fun `a near tie between two known speakers opens a new one instead of guessing`() {
        val t = SessionSpeakerTracker()
        t.assign(listOf(obs(0, bob, 60), obs(1, tim, 50)))
        val between = vec(1f, 1f, 0f, 0f) // equally like Bob and Tim
        val ids = t.assign(listOf(obs(0, between, 20)))
        assertEquals(2, ids[0])
    }

    @Test
    fun `a scrap of sound that matches nobody does not open a speaker`() {
        val t = SessionSpeakerTracker()
        t.assign(listOf(obs(0, bob, 60), obs(1, tim, 50)))
        // a silent chunk: two blips under a second each, sounding like neither
        val ids = t.assign(listOf(
            ClusterObservation(0, 8_000, stranger, null),
            ClusterObservation(1, 11_000, vec(0f, 0f, 1f, 1f), null),
        ))
        assertTrue(ids.isEmpty())
        assertEquals(2, t.speakers.size)
    }

    @Test
    fun `a scrap that does match an existing speaker still binds to them`() {
        val t = SessionSpeakerTracker()
        t.assign(listOf(obs(0, bob, 60)))
        val ids = t.assign(listOf(ClusterObservation(0, 8_000, bobAgain, null)))
        assertEquals(0, ids[0])
    }

    @Test
    fun `a short cluster with an accepted enrolled name may open a speaker`() {
        val t = SessionSpeakerTracker()
        val ids = t.assign(listOf(ClusterObservation(0, 8_000, tim, "Tim")))
        assertEquals(0, ids[0])
        assertEquals("Tim", t.labels()[0])
    }

    @Test
    fun `letters run past Z`() {
        val t = SessionSpeakerTracker()
        repeat(27) { i ->
            val v = FloatArray(27).also { it[i] = 1f }
            t.assign(listOf(obs(0, v, 10)))
        }
        assertTrue(t.labels().values.contains("Speaker AA"))
    }
}
