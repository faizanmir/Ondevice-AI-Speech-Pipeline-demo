package com.example.aiagenttestapp.stt

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.example.aiagenttestapp.data.notes.WavFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Runs the frame diariser against the real models on the device.
 *
 * **The one thing only a device can answer.** [FrameDiarizer] drives two separate ONNX Runtimes in
 * one process -- the app's own, through [PyannoteSegmenter], and the one compiled inside sherpa's
 * JNI library, through the embedding extractor. Nothing on the JVM can show those two coexisting,
 * and if they did not, the failure would be an `UnsatisfiedLinkError` or a native abort on the first
 * real run rather than anything a unit test would catch. One successful `diarize` proves it.
 *
 * **The audio is the enrolment takes already on the device**, concatenated into an alternating
 * two-speaker conversation. That makes the ground truth exact -- eight seconds of one person, then
 * eight of the other, twice -- without shipping a fixture or depending on the benchmark corpus,
 * which is not versioned.
 *
 * Skipped rather than failed when the speaker models have not been downloaded or the enrolment takes
 * are absent: that is a device that cannot run this, not a regression.
 *
 * ## Running this uninstalls the app
 *
 * `connectedDebugAndroidTest` removes both APKs when it finishes, and uninstalling takes the app's
 * data with it -- **every downloaded model, every enrolled voiceprint, every recording and the whole
 * Room database**. On a device holding a few gigabytes of models that is a long re-download, and
 * enrolments cannot be recovered at all. It also means a second run of this class skips everything,
 * because the fixtures it just deleted are what it asks for: a green run straight after a red one
 * may be four skips rather than four passes, and the result XML is where to check
 * (`skipped="4"` rather than `tests="4" failures="0"`).
 *
 * Run it on a device you are willing to re-provision, and check the skip count before believing a
 * pass.
 */
class FrameDiarizerDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val speakerModels = File(context.filesDir, "audio-models/speaker")
    private val segmentation = File(speakerModels, "segmentation.onnx")
    private val embedding = File(speakerModels, "embedding.onnx")
    private val enrolments = File(context.filesDir, "enroll")

    /** Eight seconds each, alternating, so a boundary sits at every multiple of eight. */
    private val takes = listOf("bob-take1.wav", "tim-take1.wav", "bob-take2.wav", "tim-take2.wav")

    private fun conversation(): FloatArray {
        val parts = takes.map { WavFile.read(File(enrolments, it)) }
        val total = parts.sumOf { it.size }
        val out = FloatArray(total)
        var at = 0
        for (part in parts) {
            part.copyInto(out, at)
            at += part.size
        }
        return out
    }

    private fun requireFixtures() {
        assumeTrue("speaker models not downloaded", segmentation.exists() && embedding.exists())
        assumeTrue(
            "enrolment takes not on this device",
            takes.all { File(enrolments, it).exists() },
        )
    }

    private fun diarizer() = FrameDiarizer(
        segmentationModel = segmentation,
        embeddingModel = embedding,
        threadCount = 2,
        clusterThreshold = FrameDiarizer.thresholdFor("3dspeaker-eres2net-base-16k"),
        expectedSpeakers = 0,
    )

    @Test
    fun twoSpeakersInOneRecordingComeOutAsTwoClusters() {
        requireFixtures()
        val samples = conversation()
        val rate = AudioRecorder.SAMPLE_RATE

        val turns = diarizer().use { it.diarize(samples) }

        Log.i(TAG, "turns: " + turns.joinToString { "%.1f-%.1fs c%d".format(it.startSample / rate.toFloat(), it.endSample / rate.toFloat(), it.cluster) })

        assertTrue("nothing was attributed", turns.isNotEmpty())
        assertEquals("expected two voices", 2, turns.map { it.cluster }.toSet().size)

        // The takes alternate every eight seconds, so a point in the middle of the first must not
        // share a speaker with the middle of the second.
        val firstSpeaker = clusterAt(turns, (4f * rate).toInt())
        val secondSpeaker = clusterAt(turns, (12f * rate).toInt())
        assertNotEquals("the two takes were given the same speaker", firstSpeaker, secondSpeaker)

        // ...and the same person coming back must be recognised as the same cluster rather than a
        // third voice. This is what clustering is for, and what per-window labelling cannot do.
        assertEquals("the first speaker was not recognised on their second turn", firstSpeaker, clusterAt(turns, (20f * rate).toInt()))
        assertEquals("the second speaker was not recognised on their second turn", secondSpeaker, clusterAt(turns, (28f * rate).toInt()))
    }

    /** A single voice must not be split into a conversation. */
    @Test
    fun oneSpeakerAloneStaysOneCluster() {
        requireFixtures()
        val samples = WavFile.read(File(enrolments, "bob-take1.wav"))

        val turns = diarizer().use { it.diarize(samples) }

        assumeTrue("no speech found in the take", turns.isNotEmpty())
        assertEquals("one voice was split", 1, turns.map { it.cluster }.toSet().size)
    }

    /** Silence is not a speaker, and must not become one. */
    @Test
    fun silenceProducesNoSpeakers() {
        requireFixtures()

        val turns = diarizer().use { it.diarize(FloatArray(AudioRecorder.SAMPLE_RATE * 10)) }

        assertTrue("silence was attributed to somebody", turns.isEmpty())
    }

    @Test
    fun aRecordingShorterThanOneWindowDoesNotCrash() {
        requireFixtures()
        val samples = WavFile.read(File(enrolments, "bob-take1.wav")).copyOfRange(0, 16_000 * 3)

        // The contract is only that it answers rather than throwing: three seconds may or may not
        // hold a segment the model will commit to.
        diarizer().use { it.diarize(samples) }
    }

    private fun clusterAt(turns: List<DiarizedSegment>, sample: Int): Int =
        turns.firstOrNull { sample >= it.startSample && sample < it.endSample }?.cluster ?: -1

    private inline fun <T> FrameDiarizer.use(block: (FrameDiarizer) -> T): T =
        try {
            block(this)
        } finally {
            release()
        }

    private companion object {
        const val TAG = "FrameDiarizerDeviceTest"
    }
}
