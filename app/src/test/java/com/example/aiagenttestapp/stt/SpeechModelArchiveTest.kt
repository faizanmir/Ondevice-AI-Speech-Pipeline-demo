package com.example.aiagenttestapp.stt

import com.example.aiagenttestapp.data.ArchiveExtractor
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Pins the two judgement calls in the archive-backed speech model: which archive members the
 * catalogue's patterns pick out of a release shaped like the real one, and what counts as the
 * archive having failed to deliver.
 */
class SpeechModelArchiveTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val model = SpeechModelRepository.FASTCONFORMER_EN_DE_ES_FR
    private val archive get() = model.archive!!

    @Test
    fun `catalogue patterns pick exactly the four members out of the real release layout`() {
        // The member paths as `tar -tjvf` lists them, test recordings included.
        val root = "sherpa-onnx-nemo-fast-conformer-transducer-en-de-es-fr-14288-int8"
        val tarball = writeArchive(
            "$root/tokens.txt" to "<blk> 0",
            "$root/joiner.int8.onnx" to "joiner",
            "$root/decoder.int8.onnx" to "decoder",
            "$root/encoder.int8.onnx" to "encoder",
            "$root/test_wavs/en-english.wav" to "audio",
            "$root/test_wavs/de-german.wav" to "audio",
        )
        val into = temp.newFolder("unpack")

        val produced = ArchiveExtractor.extractTarBz2(tarball, into, archive.entries)

        assertEquals(model.files.map { it.name }.toSet(), produced.keys)
        assertEquals("encoder", produced.getValue(model.encoder!!.name).readText())
        assertEquals("decoder", produced.getValue(model.decoder!!.name).readText())
        assertEquals("joiner", produced.getValue(model.joiner!!.name).readText())
        assertEquals("<blk> 0", produced.getValue(model.tokens.name).readText())
    }

    @Test
    fun `a float build in the archive is not mistaken for the int8 one`() {
        // The exact-name patterns exist so a release that starts shipping both cannot hand over
        // the wrong network. `encoder.onnx` must match nothing.
        val tarball = writeArchive("m/encoder.onnx" to "float", "m/tokens.txt" to "tokens")
        val into = temp.newFolder("unpack")

        val produced = ArchiveExtractor.extractTarBz2(tarball, into, archive.entries)

        assertNull(produced[model.encoder!!.name])
        assertEquals(setOf(model.tokens.name), produced.keys)
    }

    @Test
    fun `every file the model lists is an archive entry with the same local name`() {
        // The two lists are written separately; drifting apart would make a download succeed and
        // isDownloaded fail forever.
        assertEquals(model.files.map { it.name }, archive.entries.map { it.localName })
        assertTrue(model.files.all { it.url == null })
    }

    @Test
    fun `shortfall names members that are missing or unpacked to the wrong size`() {
        val expected = listOf(
            SpeechModelFile("a.onnx", url = null, sizeBytes = 3),
            SpeechModelFile("b.onnx", url = null, sizeBytes = 3),
            SpeechModelFile("c.txt", url = null, sizeBytes = 3),
        )
        val produced = mapOf(
            "a.onnx" to temp.newFile("a.onnx").apply { writeText("abc") },
            "b.onnx" to temp.newFile("b.onnx").apply { writeText("ab") },
        )

        assertEquals(listOf("b.onnx", "c.txt"), archiveShortfall(expected, produced))
    }

    @Test
    fun `shortfall is empty when every member is present at its published size`() {
        val expected = listOf(SpeechModelFile("a.onnx", url = null, sizeBytes = 3))
        val produced = mapOf("a.onnx" to temp.newFile("a.onnx").apply { writeText("abc") })

        assertEquals(emptyList<String>(), archiveShortfall(expected, produced))
    }

    @Test
    fun `download size is the archive, not the sum of what it unpacks to`() {
        assertEquals(archive.sizeBytes, model.totalBytes)
        assertTrue(model.files.sumOf { it.sizeBytes } > model.totalBytes)
    }

    /** Writes a `.tar.bz2` with the given `path to contents` members, in the order supplied. */
    private fun writeArchive(vararg members: Pair<String, String>): File {
        val tarball = temp.newFile("release-${members.hashCode()}.tar.bz2")

        BZip2CompressorOutputStream(tarball.outputStream().buffered()).use { bz ->
            TarArchiveOutputStream(bz).use { tar ->
                for ((path, contents) in members) {
                    val bytes = contents.toByteArray()
                    tar.putArchiveEntry(TarArchiveEntry(path).apply { size = bytes.size.toLong() })
                    tar.write(bytes)
                    tar.closeArchiveEntry()
                }
            }
        }
        return tarball
    }
}
