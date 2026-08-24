package com.example.aiagenttestapp.data.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Pins the checkpoint's one dangerous property: [TranscriptionCheckpoint.persist] rewrites the whole
 * sidecar from memory, and the file has several writers that each construct their own instance --
 * the recorder at start, the pre-decode pipeline during the recording, the stop handler, the worker.
 *
 * The failure that motivated this test was entirely silent: the stop handler's fresh instance wrote
 * its request and, knowing nothing of the slices the pipeline had spent the whole recording
 * pre-decoding, persisted an empty slice list over them. The worker then missed on every lookup and
 * transcribed the recording from scratch -- the exact wait pre-decoding exists to remove, with
 * nothing anywhere reporting a problem. Every mutator now loads before it writes, and this test is
 * what keeps that true.
 */
class TranscriptionCheckpointTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun checkpointFor(name: String = "note.wav"): TranscriptionCheckpoint =
        TranscriptionCheckpoint.forAudio(File(temp.root, name))

    // ---- Independent writers ----------------------------------------------------------------------

    /**
     * The real sequence during a recording: start writes the request, the pipeline records slices
     * through its own instance, stop rewrites the request through a third. Each writer knowing only
     * its own section must still leave the others' work standing.
     */
    @Test
    fun `a second writer does not wipe what the first recorded`() {
        checkpointFor().recordRequest(
            TranscriptionCheckpoint.Request(markers = emptyList(), excludedRanges = emptyList()),
        )

        // The pipeline's instance, never explicitly loaded -- exactly as in RecordViewModel.
        checkpointFor().record(0..15999, "first slice", "en")

        // The stop handler's instance, rewriting the request.
        checkpointFor().recordRequest(
            TranscriptionCheckpoint.Request(
                markers = emptyList(),
                excludedRanges = emptyList(),
                sttBackend = SttBackend.PLATFORM,
            ),
        )

        // The worker's view, loaded fresh from disk: both sections survived.
        val worker = checkpointFor().apply { load() }
        assertEquals("first slice", worker.textFor(0..15999)?.text)
        assertEquals(SttBackend.PLATFORM, worker.requestOrNull()?.sttBackend)
    }

    // ---- Exact-range keying -----------------------------------------------------------------------

    /**
     * Resume matches by exact sample range -- that is the whole contract between the pipeline, the
     * checkpoint and the final pass. A near-miss must be a miss, or a boundary that drifted by one
     * sample would resume with the wrong text and nobody would ever know.
     */
    @Test
    fun `a slice is found only by its exact range`() {
        val checkpoint = checkpointFor()
        checkpoint.record(100..199, "text", "en")

        assertNotNull(checkpoint.textFor(100..199))
        assertNull(checkpoint.textFor(100..200))
        assertNull(checkpoint.textFor(99..199))
    }

    /** Re-recording a range replaces its text rather than accumulating duplicates. */
    @Test
    fun `recording the same range again replaces the text`() {
        val checkpoint = checkpointFor()
        checkpoint.record(0..99, "first", null)
        checkpoint.record(0..99, "second", null)

        val reloaded = checkpointFor().apply { load() }
        assertEquals("second", reloaded.textFor(0..99)?.text)
    }

    // ---- Speech activity --------------------------------------------------------------------------

    /**
     * Null regions mean "the VAD ran and found no useful restriction -- transcribe everything",
     * and that must survive the round trip as null. An empty list coming back instead would read as
     * "skip the entire recording" and silently save an empty note.
     */
    @Test
    fun `no-restriction speech activity round-trips as null, not empty`() {
        checkpointFor().recordSpeechActivity(null)

        val reloaded = checkpointFor().apply { load() }.speechActivity()
        assertNotNull(reloaded)
        assertTrue(reloaded!!.ran)
        assertNull(reloaded.regions)
    }

    @Test
    fun `speech regions round-trip with their exact bounds`() {
        checkpointFor().recordSpeechActivity(listOf(0..15999, 32000..47999))

        val reloaded = checkpointFor().apply { load() }.speechActivity()
        assertEquals(listOf(0..15999, 32000..47999), reloaded?.regions)
    }

    /**
     * The retry path: a wrong VAD verdict produced "Nothing was recognised", and clearing it is what
     * lets "Try again" listen afresh. Only the verdict may go -- the slices already transcribed and
     * the request are precisely what makes the retry cheap and correctly targeted.
     */
    @Test
    fun `clearing speech activity forgets only the verdict`() {
        val checkpoint = checkpointFor()
        checkpoint.recordRequest(
            TranscriptionCheckpoint.Request(
                markers = emptyList(),
                excludedRanges = emptyList(),
                sttBackend = SttBackend.GEMMA,
            ),
        )
        checkpoint.record(0..999, "kept", "de")
        checkpoint.recordSpeechActivity(listOf(0..999))

        checkpointFor().clearSpeechActivity()

        val reloaded = checkpointFor().apply { load() }
        assertNull(reloaded.speechActivity())
        assertEquals("kept", reloaded.textFor(0..999)?.text)
        assertEquals(SttBackend.GEMMA, reloaded.requestOrNull()?.sttBackend)
    }

    // ---- Corruption -------------------------------------------------------------------------------

    /** A half-written checkpoint is worth nothing; loading one must mean "start over", not crash. */
    @Test
    fun `a corrupt file reads as empty and can be written over`() {
        val audio = File(temp.root, "note.wav")
        File(temp.root, "note.wav.progress").writeText("{ not json")

        val checkpoint = TranscriptionCheckpoint.forAudio(audio).apply { load() }
        assertNull(checkpoint.textFor(0..99))
        assertNull(checkpoint.speechActivity())

        checkpoint.record(0..99, "fresh start", null)
        assertEquals("fresh start", checkpointFor().apply { load() }.textFor(0..99)?.text)
    }

    /** A language the recogniser did not report stays null through the round trip. */
    @Test
    fun `a null language stays null`() {
        checkpointFor().record(0..99, "text", null)
        assertNull(checkpointFor().apply { load() }.textFor(0..99)?.language)
    }
}
