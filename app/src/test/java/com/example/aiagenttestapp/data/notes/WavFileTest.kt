package com.example.aiagenttestapp.data.notes

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.RandomAccessFile

/**
 * Covers [WavFile.Writer], the streaming path recordings are captured through.
 *
 * Worth its own tests because the thing it replaced failed in the worst possible way: the capture
 * buffer held the whole recording in RAM and only wrote the file when the user pressed stop, so a
 * long note died with an `OutOfMemoryError` and left nothing on disk at all. The properties asserted
 * here are the ones that make that unrepeatable -- audio is readable while recording, and readable
 * even if the writer never got to finish.
 */
class WavFileTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val rate = 16_000

    /** 16-bit PCM quantises, so a round-tripped sample is only ever close. */
    private fun assertSamplesEqual(expected: FloatArray, actual: FloatArray) {
        assertEquals("sample count", expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals("sample $i", expected[i], actual[i], 1e-4f)
        }
    }

    private fun ramp(n: Int, from: Int = 0) =
        FloatArray(n) { i -> (((from + i) % 200) - 100) / 100f }

    @Test
    fun `appended chunks read back as one recording`() {
        val file = temp.newFile("a.wav")
        val chunks = listOf(ramp(1000), ramp(1600, 1000), ramp(37, 2600))

        WavFile.Writer(file, rate).use { writer ->
            chunks.forEach { writer.append(it) }
            writer.finish()
            assertEquals(2637, writer.sampleCount)
        }

        assertSamplesEqual(ramp(2637), WavFile.read(file))
        assertEquals(2637, WavFile.sampleCount(file))
    }

    @Test
    fun `a streamed file is byte-identical to a whole-buffer write`() {
        val samples = ramp(5000)

        val streamed = temp.newFile("streamed.wav")
        WavFile.Writer(streamed, rate).use { writer ->
            // Deliberately ragged chunk sizes: the block buffer inside the writer must not care
            // where a chunk boundary falls relative to its own 8 KB flushes.
            var i = 0
            for (size in listOf(1, 7, 512, 3000, 1480)) {
                writer.append(samples.copyOfRange(i, i + size))
                i += size
            }
            writer.finish()
        }

        val whole = temp.newFile("whole.wav")
        WavFile.write(whole, samples, rate)

        assertArrayEquals(whole.readBytes(), streamed.readBytes())
    }

    /**
     * The durability property: a process killed mid-recording still leaves every captured sample
     * readable. [WavFile.read] sizes itself from the file length rather than the header, so the
     * un-patched zero length in the header costs nothing.
     */
    @Test
    fun `audio survives a writer that never finished`() {
        val file = temp.newFile("killed.wav")
        val writer = WavFile.Writer(file, rate)
        writer.append(ramp(4096))
        // No finish() and no close() -- as if the process had been killed here. Flush the stream the
        // way the OS would when the handle is reclaimed.
        writer.close()

        assertSamplesEqual(ramp(4096), WavFile.read(file))
    }

    @Test
    fun `finish is idempotent so a finally after an explicit call is harmless`() {
        val file = temp.newFile("twice.wav")
        WavFile.Writer(file, rate).use { writer ->
            writer.append(ramp(64))
            writer.finish()
            writer.finish()
        }
        assertEquals(64, WavFile.sampleCount(file))
        assertSamplesEqual(ramp(64), WavFile.read(file))
    }

    @Test
    fun `a recording with no audio is a valid empty wav`() {
        val file = temp.newFile("empty.wav")
        WavFile.Writer(file, rate).use { it.finish() }

        assertEquals(44L, file.length())
        assertEquals(0, WavFile.sampleCount(file))
        assertTrue(WavFile.read(file).isEmpty())
    }

    @Test
    fun `samples outside the valid range are clamped rather than wrapped`() {
        val file = temp.newFile("clamp.wav")
        WavFile.Writer(file, rate).use { writer ->
            writer.append(floatArrayOf(2f, -2f))
            writer.finish()
        }

        val read = WavFile.read(file)
        assertTrue("+2 must clamp high, not wrap negative", read[0] > 0.99f)
        assertTrue("-2 must clamp low, not wrap positive", read[1] < -0.99f)
    }

    // -------- Reader: the windowed path transcription runs on --------

    @Test
    fun `windows read back exactly what a whole-file read would give`() {
        val file = temp.newFile("windows.wav")
        WavFile.Writer(file, rate).use { writer ->
            writer.append(ramp(10_000))
            writer.finish()
        }

        WavFile.Reader(file).use { reader ->
            assertEquals(10_000, reader.sampleCount)

            // Reassembled from ragged windows. This is the property the transcription worker depends
            // on: reading a slice at a time must be indistinguishable from having held the recording.
            val rebuilt = FloatArray(10_000)
            var at = 0
            for (size in listOf(1, 511, 512, 513, 4000, 4463)) {
                reader.read(at, at + size).copyInto(rebuilt, at)
                at += size
            }
            assertEquals(10_000, at)
            assertSamplesEqual(ramp(10_000), rebuilt)
        }
    }

    @Test
    fun `a window reads the same samples wherever it starts`() {
        val file = temp.newFile("offset.wav")
        WavFile.Writer(file, rate).use { writer ->
            writer.append(ramp(4096))
            writer.finish()
        }

        WavFile.Reader(file).use { reader ->
            // An off-by-one in the seek would be invisible on a window starting at zero and wrong
            // everywhere else, so the interesting case is a window that starts mid-recording.
            assertSamplesEqual(ramp(300, from = 1000), reader.read(1000, 1300))
        }
    }

    /**
     * Out-of-range windows are clamped, not thrown.
     *
     * The splitter asks for a window one cap wide without knowing where the recording ends, so the
     * last window it asks about routinely runs past it.
     */
    @Test
    fun `windows outside the recording clamp to what is there`() {
        val file = temp.newFile("clamped.wav")
        WavFile.Writer(file, rate).use { writer ->
            writer.append(ramp(100))
            writer.finish()
        }

        WavFile.Reader(file).use { reader ->
            assertEquals("past the end is truncated", 50, reader.read(50, 5000).size)
            assertTrue("wholly past the end is empty", reader.read(500, 900).isEmpty())
            assertTrue("a reversed range is empty", reader.read(80, 20).isEmpty())
            assertTrue("a negative start is clamped", reader.read(-100, 0).isEmpty())
            assertEquals("a negative start still reads forward", 100, reader.read(-100, 100).size)
        }
    }

    /**
     * The durability property again, from the reading side.
     *
     * A recording whose writer never got to patch the header still says zero samples in it. Sizing
     * from the file length is what makes that recording readable in full instead of empty -- and it
     * is the difference between a crash costing the header and costing the note.
     */
    @Test
    fun `a reader sizes itself from the file, not the header`() {
        val file = temp.newFile("unpatched.wav")
        WavFile.Writer(file, rate).use { writer ->
            writer.append(ramp(2048))
            writer.finish()
        }

        // Put the header back the way an interrupted writer leaves it: both length fields at zero.
        RandomAccessFile(file, "rw").use { raf ->
            val zero = ByteArray(4)
            raf.seek(4L)
            raf.write(zero)
            raf.seek(40L)
            raf.write(zero)
        }

        WavFile.Reader(file).use { reader ->
            assertEquals(2048, reader.sampleCount)
            assertSamplesEqual(ramp(2048), reader.read(0, reader.sampleCount))
        }
    }

    @Test
    fun `a reader on an empty recording reports nothing rather than failing`() {
        val file = temp.newFile("nothing.wav")
        WavFile.Writer(file, rate).use { it.finish() }

        WavFile.Reader(file).use { reader ->
            assertEquals(0, reader.sampleCount)
            assertTrue(reader.read(0, 1000).isEmpty())
        }
    }
}
