package com.example.aiagenttestapp.stt

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Pins the resolved paths, because getting these wrong is silent and expensive.
 *
 * [SttStorage] was introduced to make the root injectable -- a library cannot assume it owns
 * `filesDir` -- and the temptation while doing that is to tidy the names up. Doing so would orphan
 * everything already on a device: several hundred megabytes of downloaded recognisers, the speaker
 * models, the user's enrolment takes, and every `.progress` transcription checkpoint, which is keyed
 * to the audio sitting beside it. The app would report nothing downloaded and re-fetch the lot.
 *
 * Nothing in the build catches that. This does.
 */
class SttStorageTest {

    private val storage = SttStorage(File("/data/user/0/com.example.aiagenttestapp/files"))

    @Test
    fun `paths resolve exactly as they did before the root was injectable`() {
        val root = "/data/user/0/com.example.aiagenttestapp/files"

        assertEquals("$root/speech", storage.speechModels.path)
        assertEquals("$root/audio-models/speaker", storage.speakerModels.path)
        assertEquals("$root/enroll", storage.enrolments.path)
        assertEquals("$root/notes", storage.notes.path)
        assertEquals("$root/diarized", storage.diarized.path)
    }

    /**
     * A host that passes a different root gets everything under it, and nothing escapes upward --
     * the point of taking the root at all.
     */
    @Test
    fun `every directory sits under the root it was given`() {
        val other = SttStorage(File("/tmp/somewhere-else"))
        val dirs = listOf(
            other.speechModels,
            other.speakerModels,
            other.enrolments,
            other.notes,
            other.diarized,
        )

        dirs.forEach {
            assertEquals(
                "${it.path} escaped the root",
                true,
                it.path.startsWith("/tmp/somewhere-else/"),
            )
        }
    }

    /** Two roots must not collide, which is the whole reason a library cannot hardcode them. */
    @Test
    fun `different roots produce different paths`() {
        val a = SttStorage(File("/a")).speechModels.path
        val b = SttStorage(File("/b")).speechModels.path

        assertEquals(false, a == b)
    }
}
