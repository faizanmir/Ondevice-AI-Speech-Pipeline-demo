package com.example.aiagenttestapp.data.benchmark

import com.example.aiagenttestapp.data.notes.WavFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Pins the import normalisation. The case that must never regress is the bit-exact one: the
 * reference corpus is already 16 kHz mono PCM16, and a benchmark on it is only comparable to the
 * published numbers if the copied samples are byte-for-byte the file that produced them — a float
 * round-trip is off by one code here and there, which is invisible to the ear and visible to the
 * principle.
 */
class WavConverterTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** Builds a complete little-endian PCM16 WAV from interleaved short samples. */
    private fun wavBytes(
        sampleRate: Int,
        channels: Int,
        samples: ShortArray,
        extraChunk: Pair<String, ByteArray>? = null,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        fun int(v: Int) {
            out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
            out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF)
        }
        fun short(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }

        val dataSize = samples.size * 2
        out.write("RIFF".toByteArray()); int(0) // RIFF size unread by the converter
        out.write("WAVE".toByteArray())
        extraChunk?.let { (id, body) ->
            out.write(id.toByteArray()); int(body.size); out.write(body)
            if (body.size % 2 == 1) out.write(0) // pad byte, per spec
        }
        out.write("fmt ".toByteArray()); int(16)
        short(1); short(channels); int(sampleRate); int(sampleRate * channels * 2)
        short(channels * 2); short(16)
        out.write("data".toByteArray()); int(dataSize)
        samples.forEach { short(it.toInt()) }
        return out.toByteArray()
    }

    private fun convert(bytes: ByteArray): Pair<WavConverter.Result, File> {
        val target = File(temp.root, "out.wav")
        val result = bytes.inputStream().use { WavConverter.toPipelineWav(it, target) }
        return result to target
    }

    @Test
    fun `a pipeline-format wav is copied byte-for-byte`() {
        val samples = ShortArray(2000) { ((it * 31) % 65536 - 32768).toShort() }
        val (result, target) = convert(wavBytes(16_000, 1, samples))

        result as WavConverter.Result.Converted
        assertTrue(result.bitExactCopy)
        assertEquals(2000, result.sampleCount)

        // The sample bytes of the output must equal the source's, not merely decode close to them.
        val written = target.readBytes()
        val data = written.copyOfRange(44, written.size)
        val expected = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, s ->
            expected[i * 2] = (s.toInt() and 0xFF).toByte()
            expected[i * 2 + 1] = ((s.toInt() shr 8) and 0xFF).toByte()
        }
        assertArrayEquals(expected, data)
    }

    @Test
    fun `a chunk before the data section is skipped, odd size and pad included`() {
        val samples = ShortArray(100) { 1000 }
        val bytes = wavBytes(16_000, 1, samples, extraChunk = "LIST" to ByteArray(7) { 9 })
        val (result, _) = convert(bytes)

        result as WavConverter.Result.Converted
        assertTrue(result.bitExactCopy)
        assertEquals(100, result.sampleCount)
    }

    @Test
    fun `stereo is downmixed by averaging`() {
        // L/R pairs: (0.5, -0.5) cancels; (8000, 8000) survives as itself.
        val samples = ShortArray(400) { i ->
            when {
                i < 200 -> if (i % 2 == 0) 16384 else -16384
                else -> 8000
            }
        }
        val (result, target) = convert(wavBytes(16_000, 2, samples))

        result as WavConverter.Result.Converted
        assertEquals(200, result.sampleCount)
        val mono = WavFile.read(target)
        assertEquals(0f, mono[50], 1e-3f)
        assertEquals(8000 / 32768f, mono[150], 1e-3f)
    }

    @Test
    fun `a higher sample rate halves down to 16k with the length to match`() {
        val samples = ShortArray(32_000) { 12_000 } // one second at 32 kHz, constant signal
        val (result, target) = convert(wavBytes(32_000, 1, samples))

        result as WavConverter.Result.Converted
        assertTrue(!result.bitExactCopy)
        // One second of audio is one second of audio: 16k output samples, give or take an edge.
        assertTrue("got ${result.sampleCount}", result.sampleCount in 15_999..16_001)
        // A constant signal must stay constant through interpolation.
        val mono = WavFile.read(target)
        assertEquals(12_000 / 32768f, mono[8_000], 2e-3f)
    }

    @Test
    fun `not a wav and not pcm16 are refused with a reason`() {
        val (junk, _) = convert(ByteArray(64) { 42 })
        assertTrue(junk is WavConverter.Result.Unsupported)

        // 8-bit PCM: same walk, refused at the data chunk.
        val eightBit = wavBytes(16_000, 1, ShortArray(10)).also { it[34 + 0] = 8 }
        val (result, _) = convert(eightBit)
        assertTrue(result is WavConverter.Result.Unsupported)
    }

    /** The whole reason the resampler is stateful: block boundaries must be invisible. */
    @Test
    fun `chunked resampling equals one-shot resampling`() {
        val signal = FloatArray(10_000) { kotlin.math.sin(it / 40.0).toFloat() }

        fun resample(chunkSizes: List<Int>): List<Float> {
            val resampler = LinearResampler(fromRate = 44_100, toRate = 16_000)
            val out = mutableListOf<Float>()
            var at = 0
            for (size in chunkSizes) {
                resampler.accept(signal.copyOfRange(at, at + size)) { block -> block.forEach(out::add) }
                at += size
            }
            resampler.accept(signal.copyOfRange(at, signal.size)) { block -> block.forEach(out::add) }
            resampler.flush()?.forEach(out::add)
            return out
        }

        val oneShot = resample(emptyList())
        val chunked = resample(listOf(1, 7, 512, 3, 2048, 999))

        assertEquals(oneShot.size, chunked.size)
        oneShot.zip(chunked).forEachIndexed { i, (a, b) ->
            assertEquals("sample $i", a, b, 0f)
        }
    }

    // ---- Format dispatch ---------------------------------------------------------------------

    /**
     * The sniff that routes a file to the pure-Kotlin path or to MediaCodec. It reads content, not
     * names, because a `.wav` holding an m4a is ordinary once a file has been through a messaging
     * app -- and routing that one by extension answers "corrupt header" to a file that decodes fine.
     */
    @Test
    fun `a wav is recognised by its header, and an m4a is not`() {
        assertTrue(WavConverter.looksLikeWav(wavBytes(16_000, 1, ShortArray(4))))

        // ....ftypM4A  -- an MPEG-4 box header, the thing that must NOT be taken for a WAV.
        val m4a = byteArrayOf(0, 0, 0, 32) + "ftypM4A ".toByteArray()
        assertTrue(!WavConverter.looksLikeWav(m4a))

        // RIFF, but not WAVE: an AVI, and not ours either.
        val avi = "RIFF".toByteArray() + ByteArray(4) + "AVI ".toByteArray()
        assertTrue(!WavConverter.looksLikeWav(avi))

        assertTrue(!WavConverter.looksLikeWav("RIFF".toByteArray()))
        assertTrue(!WavConverter.looksLikeWav(ByteArray(0)))
    }

    // ---- The sink both import paths share ------------------------------------------------------

    /**
     * The decode path can only run on a device, so what it shares with the WAV path is what a JVM
     * test can still protect: block-boundary invariance. A codec hands over buffers of whatever size
     * it likes, and a resampler that restarted its interpolation at each one would put a click at
     * every boundary -- audible as nothing in particular, and visible only as a worse WER.
     */
    @Test
    fun `the normaliser produces the same samples however the blocks are cut`() {
        val frames = ShortArray(8_000) { i -> (Math.sin(i / 12.0) * 12_000).toInt().toShort() }
        val pcm = ByteArray(frames.size * 2)
        frames.forEachIndexed { i, s ->
            pcm[i * 2] = (s.toInt() and 0xFF).toByte()
            pcm[i * 2 + 1] = ((s.toInt() shr 8) and 0xFF).toByte()
        }

        fun run(name: String, blockBytes: Int): FloatArray {
            val target = File(temp.root, name)
            val sink = PcmNormaliser(target, channels = 1, sourceRate = 44_100)
            try {
                var at = 0
                while (at < pcm.size) {
                    val take = minOf(blockBytes, pcm.size - at)
                    sink.acceptPcm16(pcm, at, take)
                    at += take
                }
                sink.finish()
            } finally {
                sink.close()
            }
            return WavFile.read(target)
        }

        val whole = run("whole.wav", pcm.size)
        val ragged = run("ragged.wav", 626) // deliberately not a round number of frames-per-block
        assertArrayEquals(whole, ragged, 1e-6f)
    }

    /** Float output is a real codec behaviour (`KEY_PCM_ENCODING`), and must land in the same place. */
    @Test
    fun `float input downmixes and resamples like pcm16`() {
        val target = File(temp.root, "float.wav")
        val sink = PcmNormaliser(target, channels = 2, sourceRate = 16_000)
        try {
            // L/R pairs that cancel, then pairs that agree.
            val interleaved = FloatArray(400) { i ->
                when {
                    i < 200 -> if (i % 2 == 0) 0.5f else -0.5f
                    else -> 0.25f
                }
            }
            sink.acceptFloat(interleaved, interleaved.size)
            sink.finish()
        } finally {
            sink.close()
        }

        val mono = WavFile.read(target)
        assertEquals(200, mono.size)
        assertEquals(0f, mono[50], 1e-3f)
        assertEquals(0.25f, mono[150], 1e-3f)
    }

}
