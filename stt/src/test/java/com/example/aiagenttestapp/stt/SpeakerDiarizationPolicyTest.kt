package com.example.aiagenttestapp.stt

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeakerDiarizationPolicyTest {

    @Test
    fun `an explicit speaker count remains a hard clustering instruction`() {
        val policy = speakerDiarizationPolicy(expectedSpeakers = 2)

        assertEquals(2, policy.numClusters)
    }

    @Test
    fun `an unknown speaker count uses threshold clustering`() {
        val policy = speakerDiarizationPolicy(expectedSpeakers = 0)

        assertEquals(-1, policy.numClusters)
        assertEquals(0.5f, policy.threshold, 1e-6f)
    }

    @Test
    fun `unstable fragments below sherpa default duration are discarded`() {
        val policy = speakerDiarizationPolicy(expectedSpeakers = 0)

        assertEquals(0.2f, policy.minDurationOn, 1e-6f)
    }

    /**
     * Pinned because it is the pipeline's accuracy-for-time dial and the only evidence for the
     * value is a measured table in the KDoc beside it. A change here moves published benchmark
     * numbers, so it should have to be deliberate rather than incidental.
     */
    @Test
    fun `the segmentation window advances half its length`() {
        val policy = speakerDiarizationPolicy(expectedSpeakers = 0)

        assertEquals(0.5f, policy.windowShiftRatio, 1e-6f)
    }
}
