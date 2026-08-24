package com.example.aiagenttestapp.stt

import com.example.aiagenttestapp.data.SliceWindow
import com.example.aiagenttestapp.data.notes.SttBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The segmenter decides where a recording is cut, and both of its failure modes are invisible in the
 * output: a cut through the middle of a word loses that word silently, and a segment over the
 * backend's limit is a truncated transcript on one path and an aborted process on the other. Neither
 * shows up as an error, so they are pinned down here.
 *
 * Most cases use small custom [AudioSegmenter.Limits] rather than the real ones -- the arithmetic is
 * identical and a 0.6-second buffer can be reasoned about exactly, where a 28-second one cannot.
 */
class AudioSegmenterTest {

    /** ~30 ms at 16 kHz: the frame size the quiet search steps in. */
    private val frame = AudioRecorder.SAMPLE_RATE * 30 / 1000

    private val small = AudioSegmenter.Limits(target = frame * 10, max = frame * 20)

    /** Full-scale everywhere, so any deliberate quiet window is unambiguously the quietest. */
    private fun loud(size: Int) = FloatArray(size) { 1f }

    // -------- where a cut lands --------

    @Test
    fun `cuts at the quietest frame`() {
        val samples = loud(small.max)
        // A frame boundary measured from the start of the search, so the quiet window lines up with
        // exactly one of the frames the search steps through.
        val quietFrom = small.target + frame * 5
        for (i in quietFrom until quietFrom + frame) samples[i] = 0f

        val cut = AudioSegmenter.quietestCutBetween(samples, 0, samples.size, small)

        assertEquals(quietFrom + frame / 2, cut)
    }

    @Test
    fun `ignores a quiet stretch before the target length`() {
        val samples = loud(small.max)
        // Silence early on is not a reason to cut early -- doing so would make every segment short
        // and multiply the number of decodes a long recording costs.
        for (i in frame until frame * 2) samples[i] = 0f

        val cut = AudioSegmenter.quietestCutBetween(samples, 0, samples.size, small)

        assertTrue("cut at $cut, expected at or after ${small.target}", cut >= small.target)
    }

    @Test
    fun `always moves forward`() {
        // A window too short to hold even one frame: the search finds nothing and must still return
        // a point past `from`, or the splitter that calls it would loop forever.
        val samples = loud(100)

        val cut = AudioSegmenter.quietestCutBetween(samples, 0, 10, small)

        assertTrue("cut must advance past the start, got $cut", cut > 0)
    }

    // -------- how a recording is divided --------

    @Test
    fun `leaves short audio in one piece`() {
        val samples = loud(small.max - 1)

        val bounds = AudioSegmenter.segmentBounds(samples, small)

        assertEquals(listOf(0..samples.lastIndex), bounds)
    }

    @Test
    fun `covers a long recording with contiguous segments under the cap`() {
        val samples = loud(small.max * 3 + 137)

        val bounds = AudioSegmenter.segmentBounds(samples, small)

        assertEquals("must start at the first sample", 0, bounds.first().first)
        assertEquals("must end at the last sample", samples.lastIndex, bounds.last().last)

        bounds.zipWithNext { a, b ->
            assertEquals("segments must not overlap or leave a gap", a.last + 1, b.first)
        }
        bounds.forEach {
            assertTrue("segment of ${it.count()} exceeds the cap of ${small.max}", it.count() <= small.max)
        }
    }

    @Test
    fun `handles an empty recording`() {
        assertEquals(emptyList<IntRange>(), AudioSegmenter.segmentBounds(FloatArray(0), small))
    }

    // -------- the real limits --------

    @Test
    fun `the onnx cut points have not moved`() {
        // The pin that matters most on this class, and it is not about audio quality. A slice range
        // is the transcription checkpoint's lookup key and the identity of every slice pre-decoded
        // during recording, so moving an ONNX boundary by one sample silently invalidates every
        // resume in the field: a killed process restarts from zero instead of continuing, and the
        // work done during the recording is thrown away.
        //
        // 320240 is not a derived number, it is the measured one -- it is the slice length the
        // benchmark runs in docs/ produced on both devices over a flat signal, where no frame is
        // quieter than any other so every cut lands on the target. Adding Parakeet must not have
        // touched it.
        val samples = loud(AudioSegmenter.ONNX.max * 3)

        val bounds = AudioSegmenter.segmentBounds(samples, AudioSegmenter.ONNX)

        assertEquals(320_240, bounds.first().count())
        assertEquals(AudioRecorder.SAMPLE_RATE * 20, AudioSegmenter.ONNX.target)
        assertEquals(AudioRecorder.SAMPLE_RATE * 28, AudioSegmenter.ONNX.max)
    }

    // -------- which model gets which limits --------

    @Test
    fun `each model family gets the limits its own constraint sets`() {
        // Whisper and SenseVoice share a wall in the model; Parakeet has none and is held by device
        // memory instead; a streaming transducer has neither. Three different reasons, so three
        // answers -- the mapping is asserted here so a fourth model cannot be added by silently
        // inheriting whichever branch it fell into.
        assertEquals(AudioSegmenter.ONNX, AudioSegmenter.limitsFor(SpeechEngineKind.WHISPER))
        assertEquals(AudioSegmenter.ONNX, AudioSegmenter.limitsFor(SpeechEngineKind.SENSE_VOICE))
        assertEquals(AudioSegmenter.PARAKEET, AudioSegmenter.limitsFor(SpeechEngineKind.NEMO_TRANSDUCER))
        assertNull(AudioSegmenter.limitsFor(SpeechEngineKind.STREAMING_ZIPFORMER))
    }

    @Test
    fun `the cap the vad is given matches the cap the transcriber will use`() {
        // These are computed in two different places on purpose -- the VAD needs the number before
        // the transcriber exists -- and the whole reason that is safe is that both read limitsFor.
        SpeechEngineKind.entries.forEach { kind ->
            assertEquals(
                "capFor disagrees with limitsFor for $kind",
                AudioSegmenter.limitsFor(kind)?.max ?: Int.MAX_VALUE,
                AudioSegmenter.capFor(SttBackend.ONNX, kind),
            )
        }
    }

    @Test
    fun `the other backends ignore which sherpa model is selected`() {
        // Gemma and the platform recogniser resolve their own model later; passing a sherpa model
        // kind must not leak into their caps.
        SpeechEngineKind.entries.forEach { kind ->
            assertEquals(AudioSegmenter.GEMMA.max, AudioSegmenter.capFor(SttBackend.GEMMA, kind))
            assertEquals(AudioSegmenter.PLATFORM.max, AudioSegmenter.capFor(SttBackend.PLATFORM, kind))
        }
    }

    @Test
    fun `parakeet slices longer than whisper and still stops well short of the model's ceiling`() {
        assertTrue(
            "Parakeet is offered for its length: its cap (${AudioSegmenter.PARAKEET.max}) must " +
                "exceed the 30-second wall the other two live under (${AudioSegmenter.ONNX.max})",
            AudioSegmenter.PARAKEET.max > AudioSegmenter.ONNX.max,
        )
        assertTrue(
            "a cut must be reachable before the cap",
            AudioSegmenter.PARAKEET.target < AudioSegmenter.PARAKEET.max,
        )

        // The export's positional embedding is pos_emb_max_len 5000, and one encoder frame is 80 ms,
        // so the ONNX graph cannot attend past 400 s however much memory the device has. Full
        // attention makes memory grow with the square of the clip, so the working cap belongs far
        // below that ceiling rather than just under it -- half is already generous.
        val ceilingSamples = AudioRecorder.SAMPLE_RATE * 400
        assertTrue(
            "Parakeet's cap (${AudioSegmenter.PARAKEET.max}) is past half the graph's 400 s " +
                "positional-embedding ceiling; raise it only against a device measurement",
            AudioSegmenter.PARAKEET.max <= ceilingSamples / 2,
        )
    }

    @Test
    fun `a long recording costs far fewer decodes on parakeet than on whisper`() {
        // The reason the model is here at all, stated as a number: same audio, same pipeline, fewer
        // passes through the encoder and more context in each one.
        val tenMinutes = loud(AudioRecorder.SAMPLE_RATE * 600)

        val whisper = AudioSegmenter.segmentBounds(tenMinutes, AudioSegmenter.ONNX)
        val parakeet = AudioSegmenter.segmentBounds(tenMinutes, AudioSegmenter.PARAKEET)

        assertTrue(
            "expected far fewer slices, got ${parakeet.size} against ${whisper.size}",
            parakeet.size * 3 < whisper.size,
        )
        parakeet.forEach {
            assertTrue(
                "slice of ${it.count()} exceeds Parakeet's cap of ${AudioSegmenter.PARAKEET.max}",
                it.count() <= AudioSegmenter.PARAKEET.max,
            )
        }
    }

    // -------- the window the benchmark screen sets --------

    @Test
    fun `the window cannot reach the models that have a wall`() {
        // The property that makes the setting safe to leave switched on. Whisper truncates at 29.5 s
        // and says nothing about it, so a 240 s window left behind from a Parakeet run must not
        // follow the user back to Whisper -- it would produce a transcript that covers the first
        // half-minute of every slice and looks complete.
        SliceWindow.entries.forEach { window ->
            assertEquals(
                "$window leaked into Whisper's limits",
                AudioSegmenter.ONNX,
                AudioSegmenter.limitsFor(SpeechEngineKind.WHISPER, window),
            )
            assertEquals(
                "$window leaked into SenseVoice's limits",
                AudioSegmenter.ONNX,
                AudioSegmenter.limitsFor(SpeechEngineKind.SENSE_VOICE, window),
            )
            assertNull(AudioSegmenter.limitsFor(SpeechEngineKind.STREAMING_ZIPFORMER, window))
        }
    }

    @Test
    fun `the default window is the parakeet default, exactly`() {
        // Two names for one number, and they are read by different callers -- the transcriber goes
        // through the window, the docs and the tests above go through the constant. If they ever
        // disagree the pre-decode pass and the worker would cut at different places.
        assertEquals(
            AudioSegmenter.PARAKEET,
            AudioSegmenter.limitsFor(SpeechEngineKind.NEMO_TRANSDUCER, SliceWindow.DEFAULT),
        )
        assertEquals(AudioRecorder.SAMPLE_RATE * 120, AudioSegmenter.PARAKEET.max)
        assertEquals(AudioRecorder.SAMPLE_RATE * 90, AudioSegmenter.PARAKEET.target)
    }

    @Test
    fun `every window keeps a quarter of itself for the quiet search`() {
        // A target too close to the cap leaves the search nowhere to look and every cut lands
        // mid-word. Asserted for all of them so a new entry cannot arrive without one.
        SliceWindow.entries.forEach { window ->
            val limits = AudioSegmenter.limitsFor(SpeechEngineKind.NEMO_TRANSDUCER, window)!!

            assertEquals(
                "$window's cap must be its own length",
                AudioRecorder.SAMPLE_RATE * window.seconds,
                limits.max,
            )
            assertEquals(
                "$window's target must sit at three quarters of the cap",
                limits.max / 4 * 3,
                limits.target,
            )
            assertEquals(
                "capFor must report the same cap the transcriber will use for $window",
                limits.max,
                AudioSegmenter.capFor(
                    SttBackend.ONNX,
                    SpeechEngineKind.NEMO_TRANSDUCER,
                    window,
                ),
            )
        }
    }

    @Test
    fun `no window is offered past what the exported model can attend to`() {
        // pos_emb_max_len is 5000 and one encoder frame is 80 ms, so the exported graph cannot
        // attend past 400 s at any memory budget -- a window past that does not run slowly, it
        // fails.
        //
        // The bound here is looser than the one on the default a few tests up, and deliberately so:
        // they answer different questions. The default has to be a number nobody chose and everybody
        // gets, so it sits at half the ceiling. The offered range is what someone deliberately
        // picks on a benchmark screen to find out where the useful window is, and a range that
        // stopped at the safe default could never answer that. What it must not do is offer a value
        // that cannot complete.
        val ceilingSeconds = 400
        SliceWindow.entries.forEach { window ->
            assertTrue(
                "${window.label} leaves too little margin under the export's ${ceilingSeconds}s wall",
                window.seconds <= ceilingSeconds * 3 / 4,
            )
        }
        assertTrue(
            "the default must stay in the conservative half of the range",
            SliceWindow.DEFAULT.seconds <= ceilingSeconds / 2,
        )
    }

    @Test
    fun `stored window slugs survive a round trip`() {
        // A renamed slug does not fail, it silently resets an installed device to the default --
        // and on this setting that means a benchmark run measuring something other than the row
        // says it did.
        SliceWindow.entries.forEach { window ->
            assertEquals(window, SliceWindow.fromSlug(window.slug))
        }
        assertEquals(SliceWindow.DEFAULT, SliceWindow.fromSlug(null))
        assertEquals(SliceWindow.DEFAULT, SliceWindow.fromSlug("not a window"))
    }

    @Test
    fun `gemma is capped well below the onnx path`() {
        // Not a stylistic preference. Exceeding the ONNX cap truncates a transcript; exceeding
        // LiteRT-LM's aborts the process from a native assertion that cannot be caught. If someone
        // raises this to match, they should have to delete this test to do it.
        assertTrue(
            "Gemma's cap (${AudioSegmenter.GEMMA.max}) must stay under the ONNX cap " +
                "(${AudioSegmenter.ONNX.max})",
            AudioSegmenter.GEMMA.max < AudioSegmenter.ONNX.max,
        )
        assertTrue(
            "a cut must be reachable before the cap",
            AudioSegmenter.GEMMA.target < AudioSegmenter.GEMMA.max,
        )
    }
}
