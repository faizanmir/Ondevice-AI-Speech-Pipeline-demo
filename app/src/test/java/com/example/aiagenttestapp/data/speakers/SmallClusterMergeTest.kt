package com.example.aiagenttestapp.data.speakers

import com.example.aiagenttestapp.stt.DiarizedSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deciding which clusters are speakers and which are debris.
 *
 * The failure being prevented is a transcript that reports strangers. A two-person recording
 * produced clusters of 0.8 s and 1.0 s beside ones of 71.4 s and 52.8 s, and every one of those
 * fragments was presented to the user as another person who spoke -- in the middle of a sentence
 * somebody else was still saying. pyannote's pipeline cannot do that, because it folds under-size
 * clusters into the nearest large one before anything sees them; this is that step.
 */
class SmallClusterMergeTest {

    /** Distinguishable unit vectors, so "nearest by voice" is unambiguous in these cases. */
    private val voiceA = floatArrayOf(1f, 0f, 0f)
    private val voiceB = floatArrayOf(0f, 1f, 0f)
    private val nearlyA = floatArrayOf(0.95f, 0.31f, 0f)

    private val minSamples = 16_000 * 6 // six seconds

    @Test
    fun `a fragment joins the large cluster it sounds like, not the nearest in time`() {
        val sizes = mapOf(0 to 16_000 * 40, 1 to 16_000 * 30, 2 to 16_000)
        val centroids = mapOf(0 to voiceA, 1 to voiceB, 2 to nearlyA)

        val remap = smallClusterRemap(sizes, centroids, minSamples)

        assertEquals(mapOf(2 to 0), remap)
    }

    @Test
    fun `large clusters are never moved`() {
        val sizes = mapOf(0 to 16_000 * 40, 1 to 16_000 * 30)
        val centroids = mapOf(0 to voiceA, 1 to voiceB)

        assertTrue(smallClusterRemap(sizes, centroids, minSamples).isEmpty())
    }

    @Test
    fun `every fragment is placed, even several of them`() {
        val sizes = mapOf(0 to 16_000 * 40, 1 to 16_000 * 30, 2 to 16_000, 3 to 8_000)
        val centroids = mapOf(0 to voiceA, 1 to voiceB, 2 to nearlyA, 3 to voiceB)

        val remap = smallClusterRemap(sizes, centroids, minSamples)

        assertEquals(mapOf(2 to 0, 3 to 1), remap)
    }

    @Test
    fun `a recording of nothing but fragments is left alone`() {
        // No large cluster means no evidence about who anyone is; picking a winner among fragments
        // would dress a failed diarisation up as a confident answer.
        val sizes = mapOf(0 to 16_000, 1 to 8_000)
        val centroids = mapOf(0 to voiceA, 1 to voiceB)

        assertTrue(smallClusterRemap(sizes, centroids, minSamples).isEmpty())
    }

    @Test
    fun `a fragment with no voiceprint is left where it is`() {
        val sizes = mapOf(0 to 16_000 * 40, 1 to 16_000 * 30, 2 to 16_000)
        val centroids = mapOf(0 to voiceA, 1 to voiceB) // cluster 2 could not be embedded

        assertTrue(smallClusterRemap(sizes, centroids, minSamples).isEmpty())
    }

    @Test
    fun `sizes are the speech a cluster holds, summed across its turns`() {
        val turns = listOf(
            DiarizedSegment(0, 16_000, cluster = 0),
            DiarizedSegment(32_000, 48_000, cluster = 0),
            DiarizedSegment(48_000, 56_000, cluster = 1),
        )

        assertEquals(mapOf(0 to 32_000, 1 to 8_000), clusterSizes(turns))
    }

    @Test
    fun `applying a remap rewrites only the clusters it names`() {
        val turns = listOf(
            DiarizedSegment(0, 16_000, cluster = 0),
            DiarizedSegment(16_000, 20_000, cluster = 2),
            DiarizedSegment(20_000, 40_000, cluster = 1),
        )

        val out = applyClusterRemap(turns, mapOf(2 to 0))

        assertEquals(listOf(0, 0, 1), out.map { it.cluster })
        assertEquals(turns[0], out[0])
    }
}
