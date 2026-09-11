package com.example.aiagenttestapp.data.speakers

import java.io.File
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Several transcripts as one ZIP, for the reader who wants the whole comparison at once.
 *
 * The single-file export exists so one transcript can be pasted or mailed; this exists because the
 * list is where runs are compared, and the comparison is six rows of the same recording under
 * different models. Sharing six files one at a time through a share sheet is six round trips, and
 * a receiving app that takes one attachment at a time -- most messengers -- turns that into six
 * messages. One archive is one attachment, and its entry names already carry the models
 * ([TranscriptExport.fileName]), so the folder it unpacks into reads as the comparison it is.
 *
 * Every entry is the same bytes the single export would have produced, byte-order mark included.
 * The BOM is per file, not per archive: the archive is opened by an archiver, but each `.txt`
 * inside it is opened by whatever guesses charsets from the bytes, and that guess is what turned
 * German umlauts into noise before the mark was added.
 */
object TranscriptBundle {

    /** One file inside the archive. */
    data class Entry(val name: String, val text: String)

    /** A recording with everything its transcript is rendered from. */
    data class Source(
        val recording: DiarizedRecording,
        val blocks: List<DiarizedBlock>,
        val models: TranscriptExport.Models,
    )

    /**
     * Whether a row has a transcript worth putting in an archive.
     *
     * Done or Stopped, and with words. A Running or Live row is excluded even when it already has
     * blocks -- a live session writes provisional labels that the final pass replaces -- because an
     * archive is a record, and a record of a run that was still changing when it was taken
     * misrepresents the run without any sign that it does. A Stopped row is included: the user
     * ended it and its words are the words it will ever have.
     */
    fun exportable(recording: DiarizedRecording, blocks: List<DiarizedBlock>): Boolean =
        (recording.status == DiarizedStatus.Done || recording.status == DiarizedStatus.Stopped) &&
            blocks.isNotEmpty()

    /**
     * Renders every source to an entry, with names made unique within the archive.
     *
     * A source with no blocks is dropped rather than written as a header-only file: it would tell
     * the reader nothing the file list did not, and a two-line file among the transcripts looks like
     * an export that failed.
     */
    fun entries(
        sources: List<Source>,
        sampleRate: Int,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<Entry> {
        val withWords = sources.filter { it.blocks.isNotEmpty() }
        val names = uniqueNames(withWords.map { TranscriptExport.fileName(it.recording.name, it.models) })
        return withWords.zip(names) { source, name ->
            Entry(
                name = name,
                text = "\uFEFF" + TranscriptExport.render(source.recording, source.blocks, source.models, sampleRate, zone),
            )
        }
    }

    /**
     * Makes duplicate file names distinct: the second "x.txt" becomes "x-2.txt", the third "x-3.txt".
     *
     * Two rows of the same recording under the same models -- a re-run after enrolling someone, a
     * sibling row that turned out identical -- render to the same name, and a ZIP with two entries
     * of one name is not an error to the writer but is to many readers: some unpack the last and
     * lose the first silently, some refuse the archive. The suffix goes before the extension so the
     * file still opens as text. A suffixed name that collides with a name already in the list is
     * suffixed again rather than trusted.
     */
    fun uniqueNames(names: List<String>): List<String> {
        val taken = mutableSetOf<String>()
        return names.map { name ->
            var candidate = name
            var ordinal = 2
            while (!taken.add(candidate)) {
                val stem = name.substringBeforeLast('.')
                val extension = name.substringAfterLast('.', missingDelimiterValue = "")
                candidate = if (extension.isEmpty()) "$stem-$ordinal" else "$stem-$ordinal.$extension"
                ordinal++
            }
            candidate
        }
    }

    /**
     * "transcripts-20260901-1432.zip". Stamped with the moment of export rather than with any
     * recording's name: the archive holds several, and a name that picked one of them would claim
     * the rest were about it. The minute is enough to tell two exports apart in a downloads folder.
     */
    fun fileName(at: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
        "transcripts-" + STAMP.withZone(zone).format(at) + ".zip"

    /**
     * Writes the entries to [out] as a ZIP with UTF-8 names and contents. The stream is closed.
     *
     * Names are UTF-8 explicitly. The ZIP format's original name encoding is code page 437, and a
     * writer that does not set the flag leaves an archiver to guess -- the same guessing game the
     * BOM exists to end for the contents.
     */
    fun write(entries: List<Entry>, out: OutputStream) {
        ZipOutputStream(out, Charsets.UTF_8).use { zip ->
            entries.forEach { entry ->
                zip.putNextEntry(ZipEntry(entry.name))
                zip.write(entry.text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }

    fun write(entries: List<Entry>, target: File) = write(entries, target.outputStream())

    private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm", Locale.ROOT)
}
