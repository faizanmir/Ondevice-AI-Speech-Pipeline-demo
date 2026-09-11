package com.example.aiagenttestapp.data.speakers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunTargetTest {

    private val row = DiarizedRecording(
        id = 1, name = "bbg.mp3", audioPath = "/x/bbg.wav", durationMillis = 1_000, createdAtMillis = 0,
        status = DiarizedStatus.Done, speechModelId = "fastconformer", speakerBundleId = "speaker-campplus",
    )

    @Test
    fun `same models re-run in place`() {
        assertFalse(row.needsNewRowFor("fastconformer", "speaker-campplus"))
    }

    @Test
    fun `a different recogniser or a different speaker bundle makes a new row`() {
        assertTrue(row.needsNewRowFor("parakeet-tdt-v3", "speaker-campplus"))
        assertTrue(row.needsNewRowFor("fastconformer", "speaker-reverb-campplus"))
    }

    @Test
    fun `a row that never recorded its models runs in place whatever is selected`() {
        val unrun = row.copy(status = DiarizedStatus.Idle, speechModelId = null, speakerBundleId = null)
        assertFalse(unrun.needsNewRowFor("parakeet-tdt-v3", "speaker"))
        val halfRecorded = row.copy(speakerBundleId = null)
        assertFalse(halfRecorded.needsNewRowFor("parakeet-tdt-v3", "speaker"))
    }
}
