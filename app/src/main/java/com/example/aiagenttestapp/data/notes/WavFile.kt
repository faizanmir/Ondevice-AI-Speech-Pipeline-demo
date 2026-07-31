package com.example.aiagenttestapp.data.notes

import java.io.BufferedOutputStream
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
                    // Clamped before scaling: a sample slightly outside -1..1 would otherwise wrap
                    // round to the opposite extreme and click.
                    val clamped = samples[index].coerceIn(-1f, 1f)
                    val value = (clamped * 32767f).toInt()
                    block[offset++] = (value and 0xFF).toByte()
                    block[offset++] = ((value shr 8) and 0xFF).toByte()
                    index++
                }
                out.write(block, 0, offset)
            }
            out.flush()
        }
    }

    /** Total samples in [file], from its header, without reading the audio. */
    fun sampleCount(file: File): Int {
        val dataBytes = (file.length() - HEADER_BYTES).coerceAtLeast(0)
        return (dataBytes / (BITS_PER_SAMPLE / 8)).toInt()
    }

    /**
     * Reads the whole file back as -1..1 floats.
     *
     * Whole-file rather than windowed because both diarisation and the recogniser want random access
     * across the recording, and the alternative -- re-reading a range per slice -- would mean dozens of
     * passes over the same file for no memory saving that matters once the buffer has been freed.
     */
    @Throws(IOException::class)
    fun read(file: File): FloatArray {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() <= HEADER_BYTES) return FloatArray(0)

            raf.seek(HEADER_BYTES.toLong())
            val dataBytes = (raf.length() - HEADER_BYTES).toInt()
            val bytes = ByteArray(dataBytes)
            raf.readFully(bytes)

            val samples = FloatArray(dataBytes / 2)
            for (i in samples.indices) {
                val low = bytes[i * 2].toInt() and 0xFF
                val high = bytes[i * 2 + 1].toInt()
                samples[i] = ((high shl 8) or low).toShort() / 32768f
            }
            return samples
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
