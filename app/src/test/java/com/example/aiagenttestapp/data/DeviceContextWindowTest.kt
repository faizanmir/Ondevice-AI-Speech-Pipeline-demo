package com.example.aiagenttestapp.data

import com.example.aiagent.engine.core.DeviceMemoryProfile
import com.example.aiagent.engine.core.EngineId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceContextWindowTest {

    private fun device(ramGb: Int) = DeviceMemoryProfile(
        advertisedRamBytes = ramGb * GB,
        totalRamBytes = ramGb * GB,
        availableRamBytes = ramGb * GB / 2,
        lowMemoryThresholdBytes = 512L * 1024 * 1024,
        isLowRamDevice = false,
        freeStorageBytes = 32 * GB,
        socModel = "test",
        supportedAbis = listOf("arm64-v8a"),
    )

    @Test
    fun `an advertised window the device cannot hold is cut down to what it can`() {
        // Gemma 4 E2B's card says 32k. At ~24 KiB of KV per token per billion parameters that is
        // over a gigabyte of cache for a 2B model, before weights -- no phone here affords it.
        val capped = DeviceContextWindow.cap(
            advertised = 32_768,
            weightsBytes = 3 * GB,
            paramsBillions = 2.0,
            engine = EngineId.LITE_RT_LM,
            device = device(ramGb = 8),
        )

        assertTrue("32k must not survive on an 8 GB phone", capped < 32_768)
        assertTrue("but something usable must", capped >= DeviceContextWindow.MIN_CONTEXT)
    }

    @Test
    fun `a modest window the device can afford is left alone`() {
        val capped = DeviceContextWindow.cap(
            advertised = 4096,
            weightsBytes = GB,
            paramsBillions = 1.0,
            engine = EngineId.LITE_RT_LM,
            device = device(ramGb = 12),
        )

        // Capping is a ceiling, never a target: a model that fits keeps what it asked for.
        assertEquals(4096, capped)
    }

    @Test
    fun `a bigger phone affords more of the same model`() {
        fun on(ramGb: Int) = DeviceContextWindow.cap(
            advertised = 32_768,
            weightsBytes = 2 * GB,
            paramsBillions = 2.0,
            engine = EngineId.LITE_RT_LM,
            device = device(ramGb),
        )

        assertTrue(on(ramGb = 16) > on(ramGb = 8))
    }

    @Test
    fun `the result is a whole block, rounded down`() {
        val capped = DeviceContextWindow.cap(
            advertised = 32_768,
            weightsBytes = 3 * GB,
            paramsBillions = 2.0,
            engine = EngineId.LITE_RT_LM,
            device = device(ramGb = 8),
        )

        // The estimate runs high, so under-fill rather than over.
        assertEquals(0, capped % 256)
    }

    @Test
    fun `a tight device still gets a usable window rather than nothing`() {
        val capped = DeviceContextWindow.cap(
            advertised = 8192,
            weightsBytes = 6 * GB,
            paramsBillions = 7.0,
            engine = EngineId.LLAMA_CPP,
            device = device(ramGb = 4),
        )

        // A model that genuinely cannot run is ModelFitEvaluator's to reject; this must not hand
        // back a window so small the chat is unusable before that check ever runs.
        assertTrue(capped >= DeviceContextWindow.MIN_CONTEXT - 256)
    }

    private companion object {
        const val GB = 1024L * 1024L * 1024L
    }
}
