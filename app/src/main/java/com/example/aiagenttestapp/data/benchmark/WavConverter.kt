package com.example.aiagenttestapp.data.benchmark

import com.example.aiagenttestapp.data.notes.WavFile
import com.example.aiagenttestapp.stt.AudioRecorder
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Normalises an imported WAV to what the transcription pipeline eats: 16 kHz, mono, 16-bit PCM.
 *
 * WAV is handled here, in pure Kotlin a JVM test can pin. Compressed sources (m4a, mp3, ogg) go
 * through [CompressedAudioDecoder] instead, which needs MediaCodec and therefore a device --
 * [BenchmarkImporter] sniffs the header and picks. The split is not tidiness: everything downstream
 * of the decode is shared, so the untestable part is kept as small as a codec loop and no smaller,
 * and the part that decides what the samples end up being stays testable.
 *
 * Two paths, and the difference is the point:
 *
 *  - Already 16 kHz mono PCM16: the sample bytes are **copied raw**. Running them through the
 *    float write path would not be identity -- decode divides by 32768, encode multiplies by
 *    32767, so a copied file would differ from its source by one code here and there. Benchmark
 *    runs on the reference corpus must be bit-identical to the file that produced the published
 *    numbers.
 *  - Anything else (PCM16 at another rate, or multi-channel): downmix by averaging, then
 *    linear-resample. Linear is audibly imperfect but speech models are trained on far worse; the
 *    corpus that matters never takes this path.
 *
 * Streaming throughout -- the source is read a block at a time and written through
 * [WavFile.Writer], so a long recording never lives in memory whole. Same rule as everywhere else
 * in the pipeline: audio length must not be a memory question.
 */
object WavConverter {

    sealed interface Result {
        data class Converted(
            val sampleCount: Int,
            /** True when the source was already pipeline-format and was copied byte-for-byte. */
            val bitExactCopy: Boolean,
        ) : Result

        data class Unsupported(val reason: String) : Result
    }

    /**
     * Reads a WAV from [input] and writes the pipeline-format copy to [target]. [input] is
     * consumed but not closed; [target] is replaced. IO failures throw; format problems return
     * [Result.Unsupported] with a reason the user can act on.
     */
    @Throws(IOException::class)
    fun toPipelineWav(
        input: InputStream,
        target: File,
        /**
         * Completion in 0..1 as the audio is consumed. Called from whatever thread is doing the
         * work -- an import of a 56 MB WAV is seconds of silence otherwise, and silence during a
         * long operation reads as a hang.
         */
        onProgress: (Float) -> Unit = {},
    ): Result {
        // RIFF header: "RIFF" <size> "WAVE".
        val riff = input.readExactly(12)
            ?: return Result.Unsupported("The file is too short to be a WAV.")
        if (!riff.ascii(0, 4).equals("RIFF", ignoreCase = true) ||
            !riff.ascii(8, 12).equals("WAVE", ignoreCase = true)
        ) {
            // Reached only when the header sniff said WAV and the body disagrees -- a truncated
            // or mislabelled file, not an unsupported format. Compressed files never arrive here.
            return Result.Unsupported("The file starts like a WAV but its header is not readable.")
        }

        // Walk chunks until "data", remembering what "fmt " said. Chunk sizes are little-endian
        // and odd-sized chunks are padded to even -- skipping without the pad byte desyncs the walk.
        var format = -1
        var channels = 0
        var sampleRate = 0
        var bitsPerSample = 0

        while (true) {
            val header = input.readExactly(8)
                ?: return Result.Unsupported("The WAV ended before its audio data.")
            val id = header.ascii(0, 4)
            val size = header.intLe(4)
            if (size < 0) return Result.Unsupported("The WAV header is corrupt.")

            when {
                id == "fmt " -> {
                    val fmt = input.readExactly(size + (size and 1))
                        ?: return Result.Unsupported("The WAV ended inside its format chunk.")
                    format = fmt.shortLe(0)
                    channels = fmt.shortLe(2)
                    sampleRate = fmt.intLe(4)
                    bitsPerSample = fmt.shortLe(14)
                }

                id == "data" -> {
                    if (format != 1 || bitsPerSample != 16) {
                        return Result.Unsupported(
                            "Only 16-bit PCM WAVs are supported (this one is " +
                                "format $format, $bitsPerSample-bit).",
                        )
                    }
                    if (channels < 1 || sampleRate <= 0) {
                        return Result.Unsupported("The WAV header is corrupt.")
                    }
                    return convertData(input, target, size, channels, sampleRate, onProgress)
                }

                else -> {
                    // LIST, fact, cue… skip, pad byte included.
                    if (!input.skipExactly(size.toLong() + (size and 1))) {
                        return Result.Unsupported("The WAV ended inside a '$id' chunk.")
                    }
                }
            }
        }
    }

    private fun convertData(
        input: InputStream,
        target: File,
        dataBytes: Int,
        channels: Int,
        sampleRate: Int,
        onProgress: (Float) -> Unit,
    ): Result {
        val pipelineFormat = channels == 1 && sampleRate == AudioRecorder.SAMPLE_RATE

        if (pipelineFormat) {
            // Raw byte copy of the sample data under a fresh header -- see the class doc for why
            // this must not go through the float path.
            val sampleCount = dataBytes / 2
            target.parentFile?.mkdirs()
            target.outputStream().buffered().use { out ->
                out.write(monoPcm16Header(sampleCount))
                val block = ByteArray(64 * 1024)
                var remaining = dataBytes
                while (remaining > 0) {
                    val read = input.read(block, 0, minOf(block.size, remaining))
                    if (read <= 0) break
                    out.write(block, 0, read)
                    remaining -= read
                    onProgress((dataBytes - remaining).toFloat() / dataBytes)
                }
            }
            return Result.Converted(WavFile.sampleCount(target), bitExactCopy = true)
        }

        val normaliser = PcmNormaliser(target, channels = channels, sourceRate = sampleRate)
        try {
            val frameBytes = 2 * channels
            // ~64k frames a block, kept frame-aligned so a frame never straddles two reads.
            val block = ByteArray(frameBytes * 65_536)
            var remaining = dataBytes
            while (remaining > 0) {
                var filled = 0
                val want = minOf(block.size, remaining - remaining % frameBytes)
                if (want == 0) break
                while (filled < want) {
                    val read = input.read(block, filled, want - filled)
                    if (read <= 0) break
                    filled += read
                }
                if (filled == 0) break
                remaining -= filled
                normaliser.acceptPcm16(block, 0, filled)
                onProgress((dataBytes - remaining).toFloat() / dataBytes)
            }
            normaliser.finish()
        } finally {
            normaliser.close()
        }
        return Result.Converted(normaliser.sampleCount, bitExactCopy = false)
    }

    /**
     * Whether [head] -- the first bytes of a file -- looks like a RIFF/WAVE container.
     *
     * The dispatch between the pure-Kotlin path and the codec path, by content rather than by file
     * extension or the MIME the picker claims: a `.wav` named file that is really an m4a is common
     * enough (anything re-encoded by a messaging app), and trusting the name means answering
     * "corrupt header" to a file that would have decoded perfectly well.
     */
    fun looksLikeWav(head: ByteArray): Boolean =
        head.size >= 12 &&
            head.ascii(0, 4).equals("RIFF", ignoreCase = true) &&
            head.ascii(8, 12).equals("WAVE", ignoreCase = true)

    /** The 44-byte header for a finished mono 16 kHz PCM16 file of known length. */
    private fun monoPcm16Header(sampleCount: Int): ByteArray {
        val dataSize = sampleCount * 2
        val header = ByteArray(44)
        "RIFF".toByteArray().copyInto(header, 0)
        header.putIntLe(4, 36 + dataSize)
        "WAVE".toByteArray().copyInto(header, 8)
        "fmt ".toByteArray().copyInto(header, 12)
        header.putIntLe(16, 16)
        header.putShortLe(20, 1)
        header.putShortLe(22, 1)
        header.putIntLe(24, AudioRecorder.SAMPLE_RATE)
        header.putIntLe(28, AudioRecorder.SAMPLE_RATE * 2)
        header.putShortLe(32, 2)
        header.putShortLe(34, 16)
        "data".toByteArray().copyInto(header, 36)
        header.putIntLe(40, dataSize)
        return header
    }

    // -- byte helpers ----------------------------------------------------------------------------

    private fun InputStream.readExactly(count: Int): ByteArray? {
        val out = ByteArray(count)
        var filled = 0
        while (filled < count) {
            val read = read(out, filled, count - filled)
            if (read <= 0) return null
            filled += read
        }
        return out
    }

    private fun InputStream.skipExactly(count: Long): Boolean {
        var remaining = count
        val bin = ByteArray(8 * 1024)
        while (remaining > 0) {
            val read = read(bin, 0, minOf(bin.size.toLong(), remaining).toInt())
            if (read <= 0) return false
            remaining -= read
        }
        return true
    }

    private fun ByteArray.ascii(from: Int, until: Int) = String(this, from, until - from, Charsets.US_ASCII)

    private fun ByteArray.intLe(at: Int): Int =
        (this[at].toInt() and 0xFF) or
            ((this[at + 1].toInt() and 0xFF) shl 8) or
            ((this[at + 2].toInt() and 0xFF) shl 16) or
            ((this[at + 3].toInt() and 0xFF) shl 24)

    private fun ByteArray.shortLe(at: Int): Int =
        (this[at].toInt() and 0xFF) or ((this[at + 1].toInt() and 0xFF) shl 8)

    private fun ByteArray.putIntLe(at: Int, value: Int) {
        this[at] = (value and 0xFF).toByte()
        this[at + 1] = ((value shr 8) and 0xFF).toByte()
        this[at + 2] = ((value shr 16) and 0xFF).toByte()
        this[at + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun ByteArray.putShortLe(at: Int, value: Int) {
        this[at] = (value and 0xFF).toByte()
        this[at + 1] = ((value shr 8) and 0xFF).toByte()
    }
}

/**
 * Turns interleaved source audio into the pipeline's format -- mono, 16 kHz -- one block at a time.
 *
 * Shared by both import paths, which is the point: a WAV at 44.1 kHz stereo and an m4a at 44.1 kHz
 * stereo must produce the same samples, or a benchmark comparing the two would be measuring the
 * importer. The codec path can only be exercised on a device, so keeping the arithmetic here means
 * the part that decides what the samples *are* is pinned by JVM tests either way.
 *
 * Callers hand over whole frames. A frame split across two calls would be silently mixed with its
 * neighbour -- both sources are frame-aligned by construction (a WAV read is aligned deliberately,
 * a codec buffer holds whole frames), so this is a precondition rather than a case to handle.
 */
internal class PcmNormaliser(
    target: File,
    private val channels: Int,
    sourceRate: Int,
) {
    private val resampler = LinearResampler(fromRate = sourceRate, toRate = AudioRecorder.SAMPLE_RATE)
    private val writer = WavFile.Writer(target, AudioRecorder.SAMPLE_RATE)

    val sampleCount: Int get() = writer.sampleCount

    /**
     * Interleaved little-endian PCM16.
     *
     * Little-endian without a check because every ABI Android supports is, and the two producers
     * are a WAV file (little-endian by specification) and MediaCodec (native order).
     */
    fun acceptPcm16(bytes: ByteArray, offset: Int, length: Int) {
        val frameBytes = 2 * channels
        val frames = length / frameBytes
        if (frames == 0) return

        val mono = FloatArray(frames)
        for (f in 0 until frames) {
            var sum = 0f
            for (c in 0 until channels) {
                val at = offset + f * frameBytes + c * 2
                val low = bytes[at].toInt() and 0xFF
                val high = bytes[at + 1].toInt()
                sum += ((high shl 8) or low).toShort() / 32768f
            }
            mono[f] = sum / channels
        }
        resampler.accept(mono) { out -> writer.append(out) }
    }

    /** Interleaved float, which some codecs emit instead of PCM16 (`KEY_PCM_ENCODING`). */
    fun acceptFloat(samples: FloatArray, length: Int) {
        val frames = length / channels
        if (frames == 0) return

        val mono = FloatArray(frames)
        for (f in 0 until frames) {
            var sum = 0f
            for (c in 0 until channels) sum += samples[f * channels + c]
            mono[f] = sum / channels
        }
        resampler.accept(mono) { out -> writer.append(out) }
    }

    /** Emits the resampler's tail. Separate from [close] so a failed decode writes no tail. */
    fun finish() {
        resampler.flush()?.let { writer.append(it) }
    }

    fun close() = writer.close()
}

/**
 * Streaming linear resampler: arbitrary-size input blocks in, converted samples out, with the
 * inter-block state carried so block boundaries are invisible in the output.
 *
 * Pure and stateful-by-design, like [com.example.aiagenttestapp.stt.VadFramer] and for the same
 * reason: the conversion has to be streamable (a long WAV must never be materialised whole) and a
 * JVM test has to be able to prove that chunked and unchunked conversions produce identical
 * samples.
 */
internal class LinearResampler(fromRate: Int, toRate: Int) {

    private val step = fromRate.toDouble() / toRate

    /** Absolute index of the next input sample [accept] will receive. */
    private var inputPosition = 0L

    /** Absolute index of the next output sample to produce. */
    private var outputIndex = 0L

    /** The last input sample of the previous block, for interpolation across the boundary. */
    private var carried = 0f

    fun accept(samples: FloatArray, emit: (FloatArray) -> Unit) {
        if (samples.isEmpty()) return

        val out = ArrayList<Float>(samples.size)
        while (true) {
            val position = outputIndex * step
            val i0 = position.toLong()
            val i1 = i0 + 1
            // The interpolation needs i1; once it points past this block, wait for the next.
            if (i1 >= inputPosition + samples.size) break

            val fraction = (position - i0).toFloat()
            val s0 = if (i0 < inputPosition) carried else samples[(i0 - inputPosition).toInt()]
            val s1 = samples[(i1 - inputPosition).toInt()]
            out.add(s0 + (s1 - s0) * fraction)
            outputIndex++
        }

        carried = samples.last()
        inputPosition += samples.size
        if (out.isNotEmpty()) emit(FloatArray(out.size) { out[it] })
    }

    /** Output samples that land on or past the final input sample, clamped to it. Resets nothing. */
    fun flush(): FloatArray? {
        val out = ArrayList<Float>()
        while (outputIndex * step < inputPosition) {
            out.add(carried)
            outputIndex++
        }
        return if (out.isEmpty()) null else FloatArray(out.size) { out[it] }
    }
}
