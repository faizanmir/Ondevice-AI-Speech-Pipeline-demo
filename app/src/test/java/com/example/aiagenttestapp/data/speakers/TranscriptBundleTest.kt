package com.example.aiagenttestapp.data.speakers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.util.zip.ZipInputStream

class TranscriptBundleTest {

    private val rate = 16_000

    private fun block(id: Long, recordingId: Long, from: Int, to: Int, cluster: Int, name: String, text: String) =
        DiarizedBlock(
            id = id, recordingId = recordingId, startSample = from * rate, endSample = to * rate,
            cluster = cluster, speakerName = name, text = text,
        )

    private fun recording(id: Long, name: String, status: DiarizedStatus = DiarizedStatus.Done) = DiarizedRecording(
        id = id,
        name = name,
        audioPath = "/x/$id.wav",
        durationMillis = 60_000,
        createdAtMillis = 1_756_636_800_000,
        status = status,
        language = "de",
        speechModelId = "fastconformer-en-de-es-fr",
        speakerBundleId = "speaker-campplus",
    )

    private val models = TranscriptExport.Models("FastConformer", "pyannote 3.0", "CAM++")

    private fun source(id: Long, name: String, vararg blocks: DiarizedBlock) =
        TranscriptBundle.Source(recording(id, name), blocks.toList(), models)

    @Test
    fun `an empty selection produces no entries`() {
        assertEquals(emptyList<TranscriptBundle.Entry>(), TranscriptBundle.entries(emptyList(), rate))
    }

    @Test
    fun `every entry is the single export's bytes, byte-order mark first`() {
        val entries = TranscriptBundle.entries(
            listOf(
                source(1, "a.wav", block(1, 1, 0, 5, 0, "SP1", "Guten Tag.")),
                source(2, "b.wav", block(2, 2, 0, 5, 0, "SP2", "Hallo.")),
            ),
            rate, ZoneOffset.UTC,
        )
        assertEquals(2, entries.size)
        entries.forEach { assertEquals('\uFEFF', it.text[0]) }
        // Apart from the mark, exactly what shareTranscript writes for the same row.
        val single = TranscriptExport.render(recording(1, "a.wav"), listOf(block(1, 1, 0, 5, 0, "SP1", "Guten Tag.")), models, rate, ZoneOffset.UTC)
        assertEquals(single, entries[0].text.drop(1))
        assertEquals("a-transcript-fastconformer-pyannote-3-0-campp.txt", entries[0].name)
    }

    @Test
    fun `duplicate names are numbered before the extension, and never collide with a real name`() {
        assertEquals(
            listOf("x.txt", "x-2.txt", "x-3.txt", "y.txt"),
            TranscriptBundle.uniqueNames(listOf("x.txt", "x.txt", "x.txt", "y.txt")),
        )
        // A name that already looks like a suffixed duplicate is a real name and keeps its place;
        // the duplicate that would have taken "x-2.txt" is pushed on to "x-3.txt".
        assertEquals(
            listOf("x.txt", "x-2.txt", "x-3.txt"),
            TranscriptBundle.uniqueNames(listOf("x.txt", "x-2.txt", "x.txt")),
        )
        assertEquals(listOf("noext", "noext-2"), TranscriptBundle.uniqueNames(listOf("noext", "noext")))
    }

    @Test
    fun `two rows of one recording under the same models get distinct entries`() {
        val entries = TranscriptBundle.entries(
            listOf(
                source(1, "same.wav", block(1, 1, 0, 5, 0, "SP1", "Erster Lauf.")),
                source(2, "same.wav", block(2, 2, 0, 5, 0, "SP1", "Zweiter Lauf.")),
            ),
            rate, ZoneOffset.UTC,
        )
        assertEquals(
            listOf("same-transcript-fastconformer-pyannote-3-0-campp.txt", "same-transcript-fastconformer-pyannote-3-0-campp-2.txt"),
            entries.map { it.name },
        )
    }

    @Test
    fun `unattributed words are written bare inside the archive too`() {
        val entries = TranscriptBundle.entries(
            listOf(
                source(
                    1, "a.wav",
                    block(1, 1, 0, 5, 0, "SP1", "Guten Tag."),
                    block(2, 1, 5, 6, SpeakerAlignment.UNATTRIBUTED, SpeakerRepository.UNATTRIBUTED_NAME, "Hm."),
                    block(3, 1, 6, 9, 1, "SP2", "Ja."),
                ),
            ),
            rate, ZoneOffset.UTC,
        )
        val turns = entries.single().text.lines().dropWhile { it.isNotEmpty() }.drop(1).filter { it.isNotEmpty() }
        assertEquals(listOf("[0:00] SP1: Guten Tag.", "[0:05] Hm.", "[0:06] SP2: Ja."), turns)
    }

    @Test
    fun `a row without words is left out rather than written as a header`() {
        val entries = TranscriptBundle.entries(
            listOf(source(1, "empty.wav"), source(2, "full.wav", block(1, 2, 0, 5, 0, "SP1", "Hallo."))),
            rate, ZoneOffset.UTC,
        )
        assertEquals(listOf("full-transcript-fastconformer-pyannote-3-0-campp.txt"), entries.map { it.name })
    }

    @Test
    fun `only finished or stopped rows with words are exportable`() {
        val words = listOf(block(1, 1, 0, 5, 0, "SP1", "Hallo."))
        assertTrue(TranscriptBundle.exportable(recording(1, "a", DiarizedStatus.Done), words))
        assertTrue(TranscriptBundle.exportable(recording(1, "a", DiarizedStatus.Stopped), words))
        assertFalse(TranscriptBundle.exportable(recording(1, "a", DiarizedStatus.Done), emptyList()))
        // A live session has blocks, and they are provisional: not a record yet.
        assertFalse(TranscriptBundle.exportable(recording(1, "a", DiarizedStatus.Live), words))
        assertFalse(TranscriptBundle.exportable(recording(1, "a", DiarizedStatus.Running), words))
        assertFalse(TranscriptBundle.exportable(recording(1, "a", DiarizedStatus.Failed), words))
    }

    @Test
    fun `the archive round-trips names and UTF-8 contents`() {
        val entries = listOf(
            TranscriptBundle.Entry("ä.txt", "\uFEFFGrüße, Straße."),
            TranscriptBundle.Entry("b.txt", "\uFEFFplain"),
        )
        val bytes = ByteArrayOutputStream().also { TranscriptBundle.write(entries, it) }.toByteArray()
        val back = mutableListOf<Pair<String, String>>()
        ZipInputStream(ByteArrayInputStream(bytes), Charsets.UTF_8).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entry ->
                back += entry.name to zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        assertEquals(entries.map { it.name to it.text }, back)
    }

    @Test
    fun `the archive is named by the moment of export`() {
        assertEquals(
            "transcripts-20250831-1040.zip",
            TranscriptBundle.fileName(Instant.ofEpochMilli(1_756_636_800_000), ZoneOffset.UTC),
        )
    }
}
