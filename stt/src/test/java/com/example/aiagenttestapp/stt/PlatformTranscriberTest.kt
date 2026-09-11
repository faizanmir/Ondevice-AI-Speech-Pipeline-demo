package com.example.aiagenttestapp.stt

import android.speech.SpeechRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the two judgement calls in the platform backend that a device cannot conveniently check.
 *
 * Everything else about [PlatformTranscriber] is an IPC conversation with a system service and only
 * a real device can answer it. These two are pure, and both fail *silently* if they are wrong --
 * backwards PCM decodes to noise rather than throwing, and a mis-sorted error code either kills a
 * whole recording over one quiet slice or hides a refusal behind an empty transcript.
 *
 * The `SpeechRecognizer.ERROR_*` values are Java `static final int`s, so Kotlin inlines them at
 * compile time and this runs on the JVM without Robolectric.
 */
class PlatformTranscriberTest {

    // ---- Error classification -------------------------------------------------------------------

    /**
     * A recording sliced on silence contains quiet slices by construction; they are what the VAD and
     * the segmenter leave behind. Treating one as a failure would make the backend unusable on
     * exactly the long recordings it exists for.
     */
    @Test
    fun `a slice with nothing to hear is not a failure`() {
        assertTrue(heardNothing(SpeechRecognizer.ERROR_NO_MATCH))
        assertTrue(heardNothing(SpeechRecognizer.ERROR_SPEECH_TIMEOUT))
    }

    /**
     * These describe the request rather than the audio, so they will repeat identically for every
     * remaining slice. Carrying on would spend the whole recording to arrive at an empty transcript.
     */
    @Test
    fun `a refused request is a failure, not an empty slice`() {
        listOf(
            SpeechRecognizer.ERROR_CLIENT,
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
            SpeechRecognizer.ERROR_SERVER,
            SpeechRecognizer.ERROR_AUDIO,
            SpeechRecognizer.ERROR_NETWORK,
        ).forEach { assertFalse("error $it should fail the run", heardNothing(it)) }
    }

    /** The two failures actually seen on device should name themselves, not say "failed". */
    @Test
    fun `the errors we have hit explain themselves`() {
        assertTrue(describe(SpeechRecognizer.ERROR_CLIENT).contains("audio source"))
        assertTrue(describe(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE).contains("not downloaded"))
        // Even an unmapped code has to carry its number, or a bug report says only "it failed".
        assertTrue(describe(9999).contains("9999"))
    }

    // ---- PCM conversion -------------------------------------------------------------------------

    /**
     * `AudioFormat.ENCODING_PCM_16BIT` is little-endian. Getting this backwards produces audio that
     * decodes to nothing recognisable, which on this backend is indistinguishable from the service
     * refusing the request -- so it is worth an explicit byte-order assertion.
     */
    @Test
    fun `samples are little-endian 16-bit`() {
        val bytes = pcm16(floatArrayOf(1f), 0..0)
        assertEquals(2, bytes.size)
        // 32767 = 0x7FFF -> low byte first.
        assertEquals(0xFF.toByte(), bytes[0])
        assertEquals(0x7F.toByte(), bytes[1])
    }

    @Test
    fun `silence converts to zeroes and full negative scale is preserved`() {
        assertTrue(pcm16(floatArrayOf(0f, 0f), 0..1).all { it == 0.toByte() })

        val negative = pcm16(floatArrayOf(-1f), 0..0)
        assertEquals(0x01.toByte(), negative[0])
        assertEquals(0x80.toByte(), negative[1])
    }

    /**
     * Clipped rather than wrapped. An overshoot that wrapped would turn the loudest moment of a
     * recording into full-scale noise of the opposite sign -- audible damage, from a sample that was
     * merely slightly out of range.
     */
    @Test
    fun `out-of-range samples clip instead of wrapping`() {
        assertArrayEqualsBytes(pcm16(floatArrayOf(1f), 0..0), pcm16(floatArrayOf(9f), 0..0))
        assertArrayEqualsBytes(pcm16(floatArrayOf(-1f), 0..0), pcm16(floatArrayOf(-9f), 0..0))
    }

    /** Only the requested range is converted: slices are decoded one at a time, not the whole file. */
    @Test
    fun `only the requested range is converted`() {
        val samples = floatArrayOf(0f, 1f, -1f, 0f)
        val middle = pcm16(samples, 1..2)

        assertEquals(4, middle.size)
        assertArrayEqualsBytes(pcm16(floatArrayOf(1f), 0..0), middle.copyOfRange(0, 2))
        assertArrayEqualsBytes(pcm16(floatArrayOf(-1f), 0..0), middle.copyOfRange(2, 4))
    }

    @Test
    fun `every sample becomes exactly two bytes`() {
        assertEquals(200, pcm16(FloatArray(100), 0..99).size)
    }

    // ---- Recognition timeout ----------------------------------------------------------------------

    /**
     * The timeout guards against a session that never answers, so its own failure modes are the
     * ones worth pinning: too short and it aborts healthy recognitions silently, per slice;
     * mis-scaled and a long slice gets a short slice's deadline.
     */
    @Test
    fun `the timeout scales with the clip and keeps a flat margin`() {
        // A 20 s clip at 16x: 1250 ms of paced feed, tripled, plus the 60 s margin.
        val twentySeconds = 20 * AudioRecorder.SAMPLE_RATE * 2
        assertEquals(63_750L, recogniseTimeoutMillis(twentySeconds, 16.0))

        val forty = recogniseTimeoutMillis(twentySeconds * 2, 16.0)
        assertTrue(forty > recogniseTimeoutMillis(twentySeconds, 16.0))
    }

    /** Even a zero-length clip gets the margin: the service still has a session to open and close. */
    @Test
    fun `an empty clip still waits the flat margin`() {
        assertEquals(60_000L, recogniseTimeoutMillis(0, 16.0))
    }

    /** A pathological clip length must degrade to a long wait, never to a negative or instant one. */
    @Test
    fun `a huge clip does not overflow the arithmetic`() {
        val timeout = recogniseTimeoutMillis(Int.MAX_VALUE, 16.0)
        assertTrue(timeout > 60_000L)
    }

    /** A slower pace means a longer feed, and the deadline has to move with it. */
    @Test
    fun `a slower feed pace lengthens the timeout`() {
        val clip = 20 * AudioRecorder.SAMPLE_RATE * 2
        assertTrue(recogniseTimeoutMillis(clip, 8.0) > recogniseTimeoutMillis(clip, 16.0))
    }

    /**
     * The "No delay" pacing option arrives here as infinity. The feed then takes no budgeted time
     * at all, and the deadline must degrade to exactly the flat margin -- never NaN, never zero.
     */
    @Test
    fun `an infinite pace leaves only the flat margin`() {
        val clip = 20 * AudioRecorder.SAMPLE_RATE * 2
        assertEquals(60_000L, recogniseTimeoutMillis(clip, Double.POSITIVE_INFINITY))
    }

    // ---- Feed chunk sizing -------------------------------------------------------------------

    /** 200 ms at 16 kHz, 16-bit mono: the size every complete transcript so far was fed at. */
    @Test
    fun `a chunk in milliseconds becomes that many bytes of PCM`() {
        val clip = 20 * AudioRecorder.SAMPLE_RATE * 2
        assertEquals(6_400, feedChunkBytes(200, clip))
        assertEquals(1_600, feedChunkBytes(50, clip))
        assertEquals(16_000, feedChunkBytes(500, clip))
    }

    /**
     * The whole-slice option is a sentinel, not a duration. Multiplied out it would overflow `Int`
     * and produce a negative write size, which is the kind of failure that reaches a device.
     */
    @Test
    fun `a whole-slice chunk is the clip, not an overflow`() {
        val clip = 20 * AudioRecorder.SAMPLE_RATE * 2
        assertEquals(clip, feedChunkBytes(WHOLE_CLIP_MILLIS, clip))
        assertTrue(feedChunkBytes(WHOLE_CLIP_MILLIS, clip) > 0)
    }

    /** A chunk longer than the clip is the clip: one write, not a write past the end of the array. */
    @Test
    fun `a chunk larger than the clip is clamped to it`() {
        assertEquals(1_000, feedChunkBytes(500, 1_000))
    }

    /**
     * Zero would make the feed loop write nothing and never advance -- a hang rather than an error,
     * and the loop's only exit is `offset` reaching the end.
     */
    @Test
    fun `a chunk can never be zero bytes`() {
        assertTrue(feedChunkBytes(0, 64_000) > 0)
        assertTrue(feedChunkBytes(-5, 64_000) > 0)
    }

    // ---- Language tag normalisation ---------------------------------------------------------------

    /**
     * The platform reports BCP-47 (`en-US`); every other backend reports a bare code (`en`), and the
     * bare code is what the summariser's language directive resolves through `Locale`. A stored
     * `en-US` is not English to `Locale`, it is a language whose name is "en-US", so the directive
     * quietly falls back instead of naming the language.
     */
    @Test
    fun `a BCP-47 tag reduces to the bare language code`() {
        assertEquals("en", shortLanguageCode("en-US"))
        assertEquals("en", shortLanguageCode("en-GB"))
        assertEquals("de", shortLanguageCode("de-DE"))
        assertEquals("cmn", shortLanguageCode("cmn-Hans-CN"))
    }

    @Test
    fun `an already-bare code is left alone`() {
        assertEquals("en", shortLanguageCode("en"))
        assertEquals("de", shortLanguageCode("DE"))
    }

    /** Underscores appear in Locale-style strings; both separators have to reduce the same way. */
    @Test
    fun `underscore-separated tags reduce too`() {
        assertEquals("en", shortLanguageCode("en_US"))
    }

    /**
     * No language is a real answer and must stay null rather than become "". Empty string would be
     * stored as a language, and downstream would try to resolve it to a display name.
     */
    @Test
    fun `absent or blank stays null`() {
        assertEquals(null, shortLanguageCode(null))
        assertEquals(null, shortLanguageCode(""))
        assertEquals(null, shortLanguageCode("   "))
        assertEquals(null, shortLanguageCode("-US"))
    }

    private fun assertArrayEqualsBytes(expected: ByteArray, actual: ByteArray) =
        assertEquals(expected.toList(), actual.toList())
}
