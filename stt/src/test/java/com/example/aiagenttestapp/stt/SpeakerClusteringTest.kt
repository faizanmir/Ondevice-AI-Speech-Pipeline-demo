package com.example.aiagenttestapp.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * The geometry the frame diariser rests on.
 *
 * Every failure these pin is one a user reads as a wrong name on a sentence, and none of them needs
 * a model to reproduce: clustering sees only unit vectors, their durations and their order in time.
 * That is the whole reason [SpeakerClustering] is a separate file from [FrameDiarizer].
 */
class SpeakerClusteringTest {

    /** Two voices a long way apart, and near-misses of each, all unit length. */
    private val voiceA = unit(1f, 0f, 0f)
    private val voiceB = unit(0f, 1f, 0f)
    private val nearA = unit(0.97f, 0.24f, 0f)
    private val nearB = unit(0.20f, 0.98f, 0f)
    private val between = unit(0.71f, 0.71f, 0f)

    private fun unit(vararg v: Float): FloatArray {
        var norm = 0f
        for (x in v) norm += x * x
        norm = sqrt(norm)
        return FloatArray(v.size) { v[it] / norm }
    }

    private fun durations(vararg seconds: Float) = seconds

    // ---------------------------------------------------------------- agglomerate

    @Test
    fun `two well-separated voices come out as two clusters`() {
        val embeddings = listOf(voiceA, nearA, voiceB, nearB)

        val labels = SpeakerClustering.agglomerate(embeddings, threshold = 0.60f)

        assertEquals(2, labels.toSet().size)
        assertEquals(labels[0], labels[1])
        assertEquals(labels[2], labels[3])
        assertNotEquals(labels[0], labels[2])
    }

    @Test
    fun `a threshold that merges everything leaves one cluster`() {
        val labels = SpeakerClustering.agglomerate(listOf(voiceA, voiceB), threshold = 2f)

        assertEquals(1, labels.toSet().size)
    }

    /**
     * Over-segmenting is the recoverable direction and under-segmenting is not, so a threshold tight
     * enough to doubt two takes of one voice must split them rather than guess.
     */
    @Test
    fun `a tight threshold over-segments rather than merging two voices`() {
        val labels = SpeakerClustering.agglomerate(listOf(voiceA, nearA, voiceB), threshold = 0.01f)

        assertEquals(3, labels.toSet().size)
    }

    @Test
    fun `labels are numbered densely from zero`() {
        val labels = SpeakerClustering.agglomerate(listOf(voiceA, voiceB, nearA, nearB), 0.60f)

        assertEquals((0 until labels.toSet().size).toSet(), labels.toSet())
    }

    @Test
    fun `an empty recording clusters into nothing`() {
        assertEquals(0, SpeakerClustering.agglomerate(emptyList(), 0.60f).size)
    }

    // ---------------------------------------------------------------- absorbCrumbs

    @Test
    fun `a crumb is absorbed into the voice it sounds like`() {
        val embeddings = listOf(voiceA, voiceB, nearA)
        val labels = intArrayOf(0, 1, 2)

        val moved = SpeakerClustering.absorbCrumbs(embeddings, labels, durations(60f, 40f, 0.5f))

        assertEquals(1, moved)
        assertEquals(labels[0], labels[2])
    }

    /**
     * A crumb is absorbed on size alone, however poorly it fits. That is safe only because five
     * percent of a conversation is a floor no real speaker falls under -- the band where similarity
     * has to be checked belongs to [SpeakerClustering.foldPhantoms].
     */
    @Test
    fun `a crumb that sounds like nobody is still absorbed`() {
        val embeddings = listOf(voiceA, voiceB, unit(0f, 0f, 1f))
        val labels = intArrayOf(0, 1, 2)

        SpeakerClustering.absorbCrumbs(embeddings, labels, durations(60f, 40f, 0.2f))

        assertTrue(labels[2] == labels[0] || labels[2] == labels[1])
    }

    @Test
    fun `two real speakers are left alone`() {
        val labels = intArrayOf(0, 1)

        val moved = SpeakerClustering.absorbCrumbs(listOf(voiceA, voiceB), labels, durations(60f, 40f))

        assertEquals(0, moved)
        assertEquals(listOf(0, 1), labels.toList())
    }

    // ---------------------------------------------------------------- foldPhantoms

    @Test
    fun `a minor cluster that sounds like a speaker is folded into them`() {
        val embeddings = listOf(voiceA, voiceB, nearA)
        val labels = intArrayOf(0, 1, 2)

        val folded = SpeakerClustering.foldPhantoms(embeddings, labels, durations(60f, 35f, 5f))

        assertEquals(1, folded)
        assertEquals(labels[0], labels[2])
    }

    /** The whole point of the similarity check: a quiet stranger stays a stranger. */
    @Test
    fun `a quiet but genuinely different voice survives the fold`() {
        val embeddings = listOf(voiceA, voiceB, unit(0f, 0f, 1f))
        val labels = intArrayOf(0, 1, 2)

        val folded = SpeakerClustering.foldPhantoms(embeddings, labels, durations(60f, 35f, 5f))

        assertEquals(0, folded)
        assertEquals(3, labels.toSet().size)
    }

    // ---------------------------------------------------------------- mergeLookalikes

    @Test
    fun `one voice split into two large clusters is merged back`() {
        val embeddings = listOf(voiceA, voiceA, nearA, nearA, voiceB, voiceB)
        val labels = intArrayOf(0, 0, 1, 1, 2, 2)

        val merges = SpeakerClustering.mergeLookalikes(embeddings, labels)

        assertEquals(1, merges)
        assertEquals(2, labels.toSet().size)
        assertEquals(labels[0], labels[2])
        assertNotEquals(labels[0], labels[4])
    }

    @Test
    fun `two different speakers are never merged`() {
        val labels = intArrayOf(0, 1)

        val merges = SpeakerClustering.mergeLookalikes(listOf(voiceA, voiceB), labels)

        assertEquals(0, merges)
        assertEquals(listOf(0, 1), labels.toList())
    }

    /** Folding shifts a centroid, which can bring a third piece of the same voice into range. */
    @Test
    fun `merging repeats until it is stable`() {
        val embeddings = listOf(voiceA, nearA, unit(0.99f, 0.14f, 0f), voiceB)
        val labels = intArrayOf(0, 1, 2, 3)

        SpeakerClustering.mergeLookalikes(embeddings, labels)

        assertEquals(2, labels.toSet().size)
        assertEquals(labels[0], labels[1])
        assertEquals(labels[0], labels[2])
    }

    // ---------------------------------------------------------------- smoothTemporally

    /**
     * The failure this prevents: one short segment in the middle of somebody's uninterrupted flow
     * scoring marginally closer to the other speaker, and taking a sentence with it.
     */
    @Test
    fun `a lone flip inside one speaker's flow is smoothed away`() {
        val embeddings = listOf(voiceA, nearA, voiceA, voiceA)
        val labels = intArrayOf(0, 1, 0, 0)
        val starts = floatArrayOf(0f, 3f, 4f, 6f)
        val ends = floatArrayOf(3f, 4f, 6f, 9f)

        val changed = SpeakerClustering.smoothTemporally(
            embeddings, labels, listOf(0, 1, 2, 3), starts, ends,
        )

        assertEquals(1, changed)
        assertEquals(1, labels.toSet().size)
    }

    /**
     * The other side of that line, and the reason the penalty is deliberately small: it breaks ties
     * in favour of continuity, it does not overrule evidence. Leaving and re-entering a speaker
     * costs the penalty twice, so a segment is only pulled back when it scores within `2 * penalty`
     * of the surrounding voice -- cosine 0.84 at the default. This one sits at 0.71 and keeps its
     * label, which is what stops smoothing from swallowing a genuine one-line interjection.
     */
    @Test
    fun `a segment that really does sound like someone else keeps its label`() {
        val embeddings = listOf(voiceA, between, voiceA, voiceA)
        val labels = intArrayOf(0, 1, 0, 0)
        val starts = floatArrayOf(0f, 3f, 4f, 6f)
        val ends = floatArrayOf(3f, 4f, 6f, 9f)

        val changed = SpeakerClustering.smoothTemporally(
            embeddings, labels, listOf(0, 1, 2, 3), starts, ends,
        )

        assertEquals(0, changed)
        assertEquals(2, labels.toSet().size)
    }

    /** A pause is where speakers actually change, so the penalty is waived across one. */
    @Test
    fun `a real hand-over across a long silence survives smoothing`() {
        val embeddings = listOf(voiceA, voiceA, voiceB, voiceB)
        val labels = intArrayOf(0, 0, 1, 1)
        val starts = floatArrayOf(0f, 3f, 20f, 23f)
        val ends = floatArrayOf(3f, 6f, 23f, 26f)

        SpeakerClustering.smoothTemporally(embeddings, labels, listOf(0, 1, 2, 3), starts, ends)

        assertEquals(2, labels.toSet().size)
        assertEquals(labels[0], labels[1])
        assertEquals(labels[2], labels[3])
    }

    @Test
    fun `a single-speaker recording is left alone`() {
        val labels = intArrayOf(0, 0, 0)

        val changed = SpeakerClustering.smoothTemporally(
            listOf(voiceA, voiceA, voiceA), labels, listOf(0, 1, 2),
            floatArrayOf(0f, 2f, 4f), floatArrayOf(2f, 4f, 6f),
        )

        assertEquals(0, changed)
    }

    // ---------------------------------------------------------------- resegment

    @Test
    fun `a segment holding two voices is split where the voice changes`() {
        val centres = mapOf(0 to voiceA, 1 to voiceB)
        val subEmbeddings = listOf(voiceA, voiceA, voiceB, voiceB)
        val starts = floatArrayOf(10f, 10.8f, 11.6f, 12.4f)

        val pieces = SpeakerClustering.resegment(
            subEmbeddings = subEmbeddings,
            subStartsSec = starts,
            segmentStartSec = 10f,
            segmentEndSec = 14f,
            centres = centres,
            majors = listOf(0, 1),
            subWindowSec = 1.6f,
            hopSec = 0.8f,
        )

        assertEquals(2, pieces.size)
        assertEquals(0, pieces[0].label)
        assertEquals(1, pieces[1].label)
        assertEquals(10f, pieces[0].startSec, 1e-4f)
        assertEquals(14f, pieces[1].endSec, 1e-4f)
        // Contiguous: a split must not lose or double-count audio.
        assertEquals(pieces[0].endSec, pieces[1].startSec, 1e-4f)
    }

    /** The common case by far, and the one that has to be free. */
    @Test
    fun `a segment holding one voice is not split`() {
        val pieces = SpeakerClustering.resegment(
            subEmbeddings = listOf(voiceA, voiceA, voiceA),
            subStartsSec = floatArrayOf(0f, 0.8f, 1.6f),
            segmentStartSec = 0f,
            segmentEndSec = 4f,
            centres = mapOf(0 to voiceA, 1 to voiceB),
            majors = listOf(0, 1),
            subWindowSec = 1.6f,
            hopSec = 0.8f,
        )

        assertTrue(pieces.isEmpty())
    }

    @Test
    fun `a single-speaker recording cannot be resegmented`() {
        val pieces = SpeakerClustering.resegment(
            subEmbeddings = listOf(voiceA, voiceB),
            subStartsSec = floatArrayOf(0f, 0.8f),
            segmentStartSec = 0f,
            segmentEndSec = 3f,
            centres = mapOf(0 to voiceA),
            majors = listOf(0),
            subWindowSec = 1.6f,
            hopSec = 0.8f,
        )

        assertTrue(pieces.isEmpty())
    }

    // ---------------------------------------------------------------- kMeans

    @Test
    fun `a stated speaker count is honoured exactly`() {
        val labels = SpeakerClustering.kMeans(listOf(voiceA, nearA, voiceB, nearB), k = 2)

        assertEquals(2, labels.toSet().size)
        assertEquals(labels[0], labels[1])
        assertEquals(labels[2], labels[3])
    }

    @Test
    fun `k is clamped to the number of segments`() {
        val labels = SpeakerClustering.kMeans(listOf(voiceA), k = 4)

        assertEquals(1, labels.size)
        assertEquals(1, labels.toSet().size)
    }

    // ---------------------------------------------------------------- centroids

    @Test
    fun `a centroid is the unit-length mean of its members`() {
        val centres = SpeakerClustering.centroids(
            listOf(voiceA, voiceA, voiceB), intArrayOf(0, 0, 1), listOf(0, 1),
        )

        assertEquals(1f, SpeakerClustering.dot(centres.getValue(0), voiceA), 1e-4f)
        assertEquals(1f, SpeakerClustering.dot(centres.getValue(1), voiceB), 1e-4f)
    }

    @Test
    fun `a label with no members has no centroid`() {
        val centres = SpeakerClustering.centroids(listOf(voiceA), intArrayOf(0), listOf(0, 7))

        assertEquals(setOf(0), centres.keys)
    }
}
