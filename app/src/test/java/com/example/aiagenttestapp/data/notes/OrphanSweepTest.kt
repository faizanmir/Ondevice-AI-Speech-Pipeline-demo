package com.example.aiagenttestapp.data.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Covers the rules [NoteTranscribeWorker.planOrphanSweep] applies to loose files in the audio cache.
 *
 * The sweep exists because audio now streams to disk from the moment recording starts while the note
 * row is only written at stop, so anything that kills the app in between leaves a complete recording
 * that nothing references. The danger in fixing that is symmetrical: a recording *in progress* looks
 * identical to an abandoned one, and adopting a live recording would hand it to a transcription
 * worker while the recorder was still writing to it. Most of what is asserted here is restraint.
 */
class OrphanSweepTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val now = 1_800_000_000_000L
    private val settled = now - 5 * 60_000L // comfortably past the sweep's quiet period
    private val justNow = now - 2_000L

    private fun wav(name: String, modifiedAt: Long): File =
        temp.newFile(name).apply { setLastModified(modifiedAt) }

    private fun sweep(
        files: List<File>,
        referenced: Set<String> = emptySet(),
        samples: (File) -> Int = { 16_000 },
    ) = NoteTranscribeWorker.planOrphanSweep(files, referenced, now, samples)

    @Test
    fun `an abandoned recording is adopted`() {
        val audio = wav("note-1.wav", settled)

        val plan = sweep(listOf(audio))

        assertEquals(listOf(audio), plan.adopt)
        assertTrue(plan.delete.isEmpty())
    }

    /** The one that matters: never touch a recording that is still being written. */
    @Test
    fun `a recording still in progress is left completely alone`() {
        val live = wav("note-live.wav", justNow)

        val plan = sweep(listOf(live))

        assertTrue("a live recording must not be adopted", plan.adopt.isEmpty())
        assertTrue("a live recording must not be deleted", plan.delete.isEmpty())
    }

    @Test
    fun `a recording a note already owns is not adopted twice`() {
        val owned = wav("note-owned.wav", settled)

        val plan = sweep(listOf(owned), referenced = setOf(owned.absolutePath))

        assertTrue(plan.adopt.isEmpty())
        assertTrue(plan.delete.isEmpty())
    }

    @Test
    fun `a recording with no audio in it is binned rather than adopted`() {
        val empty = wav("note-empty.wav", settled)

        val plan = sweep(listOf(empty), samples = { 0 })

        assertTrue(plan.adopt.isEmpty())
        assertEquals(listOf(empty), plan.delete)
    }

    @Test
    fun `a checkpoint whose recording is gone is binned`() {
        val stray = wav("note-9.wav.progress", settled)

        val plan = sweep(listOf(stray))

        assertTrue(plan.adopt.isEmpty())
        assertEquals(listOf(stray), plan.delete)
    }

    @Test
    fun `a checkpoint beside its recording is kept`() {
        val audio = wav("note-2.wav", settled)
        val checkpoint = wav("note-2.wav.progress", settled)

        val plan = sweep(listOf(audio, checkpoint))

        assertEquals(listOf(audio), plan.adopt)
        assertTrue("the checkpoint carries the backend and markers", plan.delete.isEmpty())
    }

    /**
     * A live recording's checkpoint is written at record time, so it exists while its WAV is still
     * being appended to. It must not be mistaken for a stray.
     */
    @Test
    fun `a live recording's checkpoint is not binned`() {
        val live = wav("note-live.wav", justNow)
        val checkpoint = wav("note-live.wav.progress", justNow)

        val plan = sweep(listOf(live, checkpoint))

        assertTrue(plan.adopt.isEmpty())
        assertTrue(plan.delete.isEmpty())
    }

    @Test
    fun `a mixed directory is sorted out in one pass`() {
        val abandoned = wav("note-a.wav", settled)
        val live = wav("note-b.wav", justNow)
        val owned = wav("note-c.wav", settled)
        val empty = wav("note-d.wav", settled)
        val stray = wav("note-gone.wav.progress", settled)

        val plan = sweep(
            files = listOf(abandoned, live, owned, empty, stray),
            referenced = setOf(owned.absolutePath),
            samples = { if (it == empty) 0 else 16_000 },
        )

        assertEquals(listOf(abandoned), plan.adopt)
        assertEquals(setOf(empty, stray), plan.delete.toSet())
    }

    @Test
    fun `an empty directory is a no-op`() {
        val plan = sweep(emptyList())

        assertTrue(plan.adopt.isEmpty())
        assertTrue(plan.delete.isEmpty())
    }

    @Test
    fun `files that are neither recordings nor checkpoints are ignored`() {
        val junk = wav("README.txt", settled)

        val plan = sweep(listOf(junk))

        assertTrue(plan.adopt.isEmpty())
        assertTrue(plan.delete.isEmpty())
    }
}
