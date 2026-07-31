package com.example.aiagenttestapp.data

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
 * Exercises [ArchiveExtractor] against archives shaped like the real sherpa-onnx keyword-spotting
 * release: everything nested under a top-level directory, an encoder shipped as both int8 and float,
 * a decoder shipped only as float, and a pile of members we do not want.
 */
class ArchiveExtractorTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val kwsEntries = listOf(
        ArchiveEntry(
            localName = "kws-encoder.onnx",
            patterns = listOf(
                Regex("^encoder-.*\\.int8\\.onnx$"),
                Regex("^encoder-.*\\.onnx$"),
            ),
        ),
        ArchiveEntry(
            localName = "kws-decoder.onnx",
            patterns = listOf(
                Regex("^decoder-.*\\.int8\\.onnx$"),
                Regex("^decoder-.*\\.onnx$"),
            ),
        ),
        ArchiveEntry(
            localName = "kws-tokens.txt",
            patterns = listOf(Regex("^tokens\\.txt$")),
        ),
    )

    @Test
    fun `extracts wanted members, flattens the top-level directory, and skips the rest`() {
        val archive = writeArchive(
            "sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01-mobile/encoder-epoch-12-avg-2.onnx" to "float-encoder",
            "sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01-mobile/decoder-epoch-12-avg-2.onnx" to "decoder",
            "sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01-mobile/tokens.txt" to "<blk> 0",
            "sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01-mobile/README.md" to "docs",
            "sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01-mobile/bpe.model" to "tokeniser",
            "sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01-mobile/test_wavs/0.wav" to "audio",
        )
        val into = temp.newFolder("out")

        val produced = ArchiveExtractor.extractTarBz2(archive, into, kwsEntries)

        assertEquals(
            setOf("kws-encoder.onnx", "kws-decoder.onnx", "kws-tokens.txt"),
            produced.keys,
        )
        assertEquals("float-encoder", File(into, "kws-encoder.onnx").readText())
        assertEquals("decoder", File(into, "kws-decoder.onnx").readText())
        assertEquals("<blk> 0", File(into, "kws-tokens.txt").readText())

        // Nothing we did not ask for, and no staging left behind.
        assertEquals(
            setOf("kws-encoder.onnx", "kws-decoder.onnx", "kws-tokens.txt"),
            into.listFiles()!!.map { it.name }.toSet(),
        )
    }

    @Test
    fun `prefers the int8 build when the archive ships both`() {
        val archive = writeArchive(
            "m/encoder-epoch-12-avg-2.onnx" to "float-encoder",
            "m/encoder-epoch-12-avg-2.int8.onnx" to "int8-encoder",
            "m/decoder-epoch-12-avg-2.onnx" to "decoder",
            "m/tokens.txt" to "tokens",
        )
        val into = temp.newFolder("out")

        ArchiveExtractor.extractTarBz2(archive, into, kwsEntries)

        // The float encoder appears *first* in the stream, so this only passes if preference is
        // applied after staging rather than while reading.
        assertEquals("int8-encoder", File(into, "kws-encoder.onnx").readText())
        // The decoder ships float-only; the int8 pattern must fall through rather than fail.
        assertEquals("decoder", File(into, "kws-decoder.onnx").readText())
    }

    @Test
    fun `reports a missing member as an absent key rather than throwing`() {
        val archive = writeArchive(
            "m/encoder-epoch-12-avg-2.int8.onnx" to "int8-encoder",
            "m/tokens.txt" to "tokens",
        )
        val into = temp.newFolder("out")

        val produced = ArchiveExtractor.extractTarBz2(archive, into, kwsEntries)

        assertEquals(setOf("kws-encoder.onnx", "kws-tokens.txt"), produced.keys)
        assertNull(produced["kws-decoder.onnx"])
    }

    @Test
    fun `ignores path traversal in member names`() {
        val archive = writeArchive("../../../../etc/tokens.txt" to "evil")
        val into = temp.newFolder("out")

        val produced = ArchiveExtractor.extractTarBz2(archive, into, kwsEntries)

        // Only the basename is ever used, so the member lands inside `into` or not at all.
        val extracted = produced["kws-tokens.txt"]
        assertTrue(extracted != null && extracted.canonicalPath.startsWith(into.canonicalPath))
    }

    /** Writes a `.tar.bz2` with the given `path to contents` members, in the order supplied. */
    private fun writeArchive(vararg members: Pair<String, String>): File {
        val archive = temp.newFile("bundle-${members.size}-${members.hashCode()}.tar.bz2")

        BZip2CompressorOutputStream(archive.outputStream().buffered()).use { bz ->
            TarArchiveOutputStream(bz).use { tar ->
                for ((path, contents) in members) {
                    val bytes = contents.toByteArray()
                    tar.putArchiveEntry(
                        TarArchiveEntry(path).apply { size = bytes.size.toLong() },
                    )
                    tar.write(bytes)
                    tar.closeArchiveEntry()
                }
            }
        }
        return archive
    }
}
