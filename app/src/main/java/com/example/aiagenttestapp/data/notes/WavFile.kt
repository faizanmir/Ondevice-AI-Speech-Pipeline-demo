package com.example.aiagenttestapp.data.notes

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Reads and writes the 16 kHz mono PCM files that recordings are parked in between capture and
 * transcription.
 *
 * The reason recordings go to disk at all: half an hour of audio is about 115 MB of `FloatArray`, and
 * the old flow copied the whole capture buffer to hand it to the transcriber -- doubling that at the
 * exact moment the app was also about to load an ASR model. Spilling to a file frees the buffer
 * immediately and lets a background worker read the audio back after the process has been restarted,
 * which is the other half of making transcription survivable.
 *
 * A plain WAV rather than anything compressed: encoding costs CPU and quality on audio a speech model
 * is about to consume, and the file is temporary.
 */
object WavFile {

    private const val HEADER_BYTES = 44
    private const val BITS_PER_SAMPLE = 16
    private const val CHANNELS = 1

    /**
     * Writes [samples] (-1..1) as 16-bit PCM.
     *
     * Streamed in blocks rather than materialised as one big `ByteArray`: a second full-size copy is
     * the very thing this file exists to avoid.
     */
    @Throws(IOException::class)
    fun write(target: File, samples: FloatArray, sampleRate: Int) {
        target.parentFile?.mkdirs()

        BufferedOutputStream(target.outputStream(), 64 * 1024).use { out ->
            out.write(header(samples.size, sampleRate))

            val block = ByteArray(8 * 1024)
            var index = 0
            while (index < samples.size) {
                var offset = 0
                while (offset < block.size && index < samples.size) {
                    offset = writeSample(block, offset, samples[index])
                    index++
                }
                out.write(block, 0, offset)
            }
            out.flush()
        }
    }

    /**
     * Appends samples to a WAV file as they are captured, so a recording is never held whole in RAM.
     *
     * The reason this exists rather than one [write] at the end: the capture buffer used to grow for
     * the entire recording and only reach disk when the user pressed stop, which made the length of a
     * note a memory question. On a 7.6 GB tablet it died at **38 min 55 s** with a 224 MB
     * `OutOfMemoryError` inside the buffer's own grow, and because nothing had been written yet the
     * whole recording went with it -- the one outcome the rest of this pipeline is built to prevent.
     *
     * The header is written up front with a zero length and patched by [finish]. That ordering is
     * deliberate: [read] and [sampleCount] both size themselves from the *file length* rather than
     * the header, so audio from a recording that was killed before [finish] is still readable in
     * full. A crash now costs the header, not the note.
     */
    class Writer(private val target: File, private val sampleRate: Int) : Closeable {

        private val out: BufferedOutputStream
        private val block = ByteArray(8 * 1024)

        /** Samples appended so far -- the recording's length, without asking the filesystem. */
        var sampleCount: Int = 0
            private set

        private var finished = false

        init {
            target.parentFile?.mkdirs()
            out = BufferedOutputStream(target.outputStream(), 64 * 1024)
            out.write(header(sampleCount = 0, sampleRate = sampleRate))
        }

        /** Appends one captured chunk. Blocks on IO, so call it off the main thread. */
        @Throws(IOException::class)
        fun append(samples: FloatArray) {
            check(!finished) { "cannot append to a finished WAV" }

            var index = 0
            while (index < samples.size) {
                var offset = 0
                while (offset < block.size && index < samples.size) {
                    offset = writeSample(block, offset, samples[index])
                    index++
                }
                out.write(block, 0, offset)
            }
            sampleCount += samples.size
        }

        /**
         * Flushes and patches the header with the real length. Idempotent, so a `finally` that runs
         * after an explicit call is harmless.
         */
        @Throws(IOException::class)
        fun finish() {
            if (finished) return
            finished = true

            out.flush()
            out.close()

            val dataSize = sampleCount * (BITS_PER_SAMPLE / 8) * CHANNELS
            RandomAccessFile(target, "rw").use { raf ->
                val patch = ByteArray(4)
                writeIntLe(patch, 0, 36 + dataSize)
                raf.seek(4L)
                raf.write(patch)

                writeIntLe(patch, 0, dataSize)
                raf.seek(40L)
                raf.write(patch)
            }
        }

        override fun close() {
            // Same reasoning as the header: a close that cannot patch is still better than one that
            // leaves the stream open, and the audio remains readable either way.
            runCatching { finish() }
        }
    }

    /**
     * A whole WAV file, header and all, as bytes -- for the slice of [samples] in `[from, until)`.
     *
     * The in-memory twin of [write], and it exists because a model that listens wants a *file*, not
     * a path: LiteRT-LM takes the encoded bytes and decodes them itself, so a transcriber handing it
     * one slice at a time would otherwise have to write a temp file per slice and delete it after.
     * The slices are bounded (see [com.example.aiagenttestapp.stt.AudioSegmenter]) at well under a
     * megabyte apiece, so materialising one is cheaper than the file it replaces.
     */
    fun bytes(
        samples: FloatArray,
        sampleRate: Int,
        from: Int = 0,
        until: Int = samples.size,
    ): ByteArray {
        val start = from.coerceIn(0, samples.size)
        val end = until.coerceIn(start, samples.size)
        val count = end - start

        val out = ByteArray(HEADER_BYTES + count * (BITS_PER_SAMPLE / 8))
        header(count, sampleRate).copyInto(out)

        var offset = HEADER_BYTES
        for (i in start until end) {
            offset = writeSample(out, offset, samples[i])
        }
        return out
    }

    /**
     * Writes one -1..1 sample as little-endian 16-bit PCM and returns the next offset.
     *
     * Clamped before scaling: a sample slightly outside -1..1 would otherwise wrap round to the
     * opposite extreme and click.
     */
    private fun writeSample(target: ByteArray, at: Int, sample: Float): Int {
        val value = (sample.coerceIn(-1f, 1f) * 32767f).toInt()
        target[at] = (value and 0xFF).toByte()
        target[at + 1] = ((value shr 8) and 0xFF).toByte()
        return at + 2
    }

    /** Total samples in [file], from its header, without reading the audio. */
    fun sampleCount(file: File): Int {
        val dataBytes = (file.length() - HEADER_BYTES).coerceAtLeast(0)
        return (dataBytes / (BITS_PER_SAMPLE / 8)).toInt()
    }

    /**
     * Reads the whole file back as -1..1 floats.
     *
     * Costs **6 bytes per sample at its peak** -- a `ByteArray` of the file's audio and the
     * `FloatArray` it decodes into are alive at the same time -- so half an hour is about 173 MB and
     * it grows without limit alongside the recording. Nothing in the transcription path uses this any
     * more; [Reader] exists precisely so a long recording is never materialised whole. Kept for tests
     * and for callers that genuinely hold a short clip.
     */
    @Throws(IOException::class)
    fun read(file: File): FloatArray = Reader(file).use { it.read(0, it.sampleCount) }

    /**
     * Reads samples `[from, until)` without touching the rest of the file.
     *
     * Opens the file per call. Fine for an occasional window -- the pipelined pass during recording
     * takes one every few seconds -- but a caller making many reads in a row should hold a [Reader]
     * instead and pay for the handle once.
     */
    @Throws(IOException::class)
    fun read(file: File, from: Int, until: Int): FloatArray =
        Reader(file).use { it.read(from, until) }

    /**
     * An open recording, read a window at a time.
     *
     * The counterpart to [Writer], and it exists for the same reason: the length of a recording must
     * not be a memory question. The transcription worker used to begin by decoding the entire WAV into
     * one `FloatArray`, which put the whole-recording allocation back that streaming capture had just
     * removed -- the peak simply moved from the moment the user pressed stop to the moment the worker
     * started. Every stage downstream turns out to want a bounded window anyway: the VAD consumes the
     * audio in 512-sample frames, the quiet-point search never looks further than one slice cap, and a
     * decode is handed exactly one slice. So none of them need more than a few megabytes at a time.
     *
     * Holds one file handle for its lifetime, so the many small reads those stages make do not each
     * pay to open the file.
     */
    class Reader(file: File) : Closeable {

        private val raf = RandomAccessFile(file, "r")

        /**
         * Total samples on disk.
         *
         * From the file's length rather than its header, matching [sampleCount]: a recording killed
         * before [Writer.finish] still says zero in its header, and trusting that would read back an
         * empty recording that is in fact entirely intact.
         */
        val sampleCount: Int =
            ((raf.length() - HEADER_BYTES).coerceAtLeast(0L) / (BITS_PER_SAMPLE / 8)).toInt()

        /** Samples `[from, until)` as -1..1 floats. Clamped to what is on disk; never throws on range. */
        @Throws(IOException::class)
        fun read(from: Int, until: Int): FloatArray {
            val available = sampleCount.toLong()
            val start = from.toLong().coerceIn(0L, available)
            val end = until.toLong().coerceIn(start, available)
            val count = (end - start).toInt()
            if (count <= 0) return FloatArray(0)

            raf.seek(HEADER_BYTES + start * 2)
            val bytes = ByteArray(count * 2)
            raf.readFully(bytes)

            val samples = FloatArray(count)
            for (i in samples.indices) {
                val low = bytes[i * 2].toInt() and 0xFF
                val high = bytes[i * 2 + 1].toInt()
                samples[i] = ((high shl 8) or low).toShort() / 32768f
            }
            return samples
        }

        override fun close() {
            raf.close()
        }
    }

    private fun header(sampleCount: Int, sampleRate: Int): ByteArray {
        val bytesPerSample = BITS_PER_SAMPLE / 8
        val dataSize = sampleCount * bytesPerSample * CHANNELS
        val byteRate = sampleRate * CHANNELS * bytesPerSample

        val header = ByteArray(HEADER_BYTES)
        "RIFF".toByteArray().copyInto(header, 0)
        writeIntLe(header, 4, 36 + dataSize)
        "WAVE".toByteArray().copyInto(header, 8)
        "fmt ".toByteArray().copyInto(header, 12)
        writeIntLe(header, 16, 16) // PCM fmt chunk size
        writeShortLe(header, 20, 1) // PCM
        writeShortLe(header, 22, CHANNELS)
        writeIntLe(header, 24, sampleRate)
        writeIntLe(header, 28, byteRate)
        writeShortLe(header, 32, CHANNELS * bytesPerSample) // block align
        writeShortLe(header, 34, BITS_PER_SAMPLE)
        "data".toByteArray().copyInto(header, 36)
        writeIntLe(header, 40, dataSize)
        return header
    }

    private fun writeIntLe(target: ByteArray, at: Int, value: Int) {
        target[at] = (value and 0xFF).toByte()
        target[at + 1] = ((value shr 8) and 0xFF).toByte()
        target[at + 2] = ((value shr 16) and 0xFF).toByte()
        target[at + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun writeShortLe(target: ByteArray, at: Int, value: Int) {
        target[at] = (value and 0xFF).toByte()
        target[at + 1] = ((value shr 8) and 0xFF).toByte()
    }
}
