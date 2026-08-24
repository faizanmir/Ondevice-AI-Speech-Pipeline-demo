package com.example.aiagenttestapp.data

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.IOException

/**
 * A file we want out of an archive, and the names it might actually go by.
 *
 * [patterns] is ordered by preference, first match wins. That ordering is the whole reason this type
 * exists: the sherpa keyword-spotting release ships an encoder as both `...int8.onnx` and `...onnx`
 * and we want the int8 one (4 MB against 11.5 MB, on a model whose whole job is to be cheap), while
 * for the decoder only the float build exists. Expressing "prefer this, settle for that" per file
 * keeps that knowledge in one table instead of scattered `if` statements.
 *
 * [localName] is what the file is called once extracted, so nothing downstream has to know that the
 * archive named it `encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx`. A new release that bumps the
 * epoch number then costs nothing.
 */
internal data class ArchiveEntry(
    val localName: String,
    val patterns: List<Regex>,
)

/**
 * Unpacks the `.tar.bz2` archives that sherpa-onnx publishes on its GitHub releases.
 *
 * This exists only because those releases offer no other shape. Every other model this app downloads
 * is a plain file fetched straight to disk; the keyword-spotting models are published exclusively as
 * bzip2'd tarballs, with no per-file mirror anywhere. Android's runtime has neither bzip2 nor tar, so
 * Commons Compress is on the classpath for this one job.
 *
 * Deliberately free of Android imports -- no `Log`, no `Context` -- so the archive-shape rules can be
 * tested on the JVM. `AudioModelRepository` reports which members were missing, which is the
 * diagnostic that actually matters when an upstream release changes shape.
 */
internal object ArchiveExtractor {

    /**
     * Extracts the [wanted] entries from [archive] into [into], named by their `localName`.
     *
     * Returns the files it actually produced, keyed by `localName`. A caller must check that every
     * entry it needs is present -- a truncated or unexpected archive shows up here as a missing key
     * rather than an exception, because "the release changed shape" and "the download broke" deserve
     * the same honest report and neither should crash the app.
     *
     * Every candidate match is staged first and the preference order applied afterwards. A tar is a
     * stream in arbitrary order, so "prefer the int8 encoder" cannot be decided while reading -- the
     * float one may well arrive first. The rejected candidates are deleted before returning.
     */
    fun extractTarBz2(
        archive: File,
        into: File,
        wanted: List<ArchiveEntry>,
    ): Map<String, File> {
        into.mkdirs()

        val staging = File(into, "staging").apply {
            deleteRecursively()
            mkdirs()
        }

        try {
            stageCandidates(archive, staging, wanted)

            val produced = mutableMapOf<String, File>()
            for (entry in wanted) {
                // First pattern with a staged hit wins -- this is where the preference order applies.
                val chosen = entry.patterns.firstNotNullOfOrNull { pattern ->
                    staging.listFiles()
                        ?.filter { it.isFile }
                        ?.firstOrNull { pattern.containsMatchIn(it.name) }
                } ?: continue

                val target = File(into, entry.localName)
                target.delete()
                if (!chosen.renameTo(target)) {
                    // Cross-device rename can fail even inside app storage; copying always works.
                    chosen.copyTo(target, overwrite = true)
                }
                produced[entry.localName] = target
            }

            return produced
        } finally {
            staging.deleteRecursively()
        }
    }

    /** Extracts every archive member whose basename matches any wanted pattern into [staging]. */
    private fun stageCandidates(archive: File, staging: File, wanted: List<ArchiveEntry>) {
        val patterns = wanted.flatMap { it.patterns }

        BZip2CompressorInputStream(archive.inputStream().buffered()).use { decompressed ->
            TarArchiveInputStream(decompressed).use { tar ->
                while (true) {
                    val entry = tar.nextEntry ?: break
                    if (entry.isDirectory) continue

                    // Basename only. The archives nest everything under a top-level directory, and
                    // taking just the name both flattens that and makes `../` traversal impossible.
                    val name = File(entry.name).name
                    if (name.isEmpty()) continue
                    if (patterns.none { it.containsMatchIn(name) }) continue

                    val staged = File(staging, name)
                    staged.outputStream().buffered().use { out -> tar.copyTo(out) }
                }
            }
        }
    }

    /** Wraps anything the archive layer throws so callers see one failure type. */
    fun extractOrThrow(
        archive: File,
        into: File,
        wanted: List<ArchiveEntry>,
    ): Map<String, File> = try {
        extractTarBz2(archive, into, wanted)
    } catch (e: IOException) {
        throw IOException("Could not unpack ${archive.name}: ${e.message}", e)
    }
}
