package com.example.aiagenttestapp.data.speakers

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the state a recording is born in.
 *
 * One line, because the line it pins cost the feature everything: the default used to be
 * [DiarizedStatus.Running], which put an indeterminate progress bar against a run that had never
 * started, and the detail pane hides its Run button while a row is running -- so an imported file
 * showed a bar that never finished and offered no way to start the work it was pretending to do.
 */
class DiarizedRecordingTest {

    @Test
    fun `a new recording has not run`() {
        val fresh = DiarizedRecording(
            name = "clip.wav",
            audioPath = "/tmp/clip.wav",
            durationMillis = 60_000,
            createdAtMillis = 0,
        )

        assertEquals(DiarizedStatus.Idle, fresh.status)
        // And has no run time to show. The row prints one whenever it is present, so a non-null
        // default would put "took 0s" against a recording that has never been through the models.
        assertEquals(null, fresh.runMillis)
    }
}
