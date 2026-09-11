package com.example.aiagenttestapp.data.speakers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class TranscriptExportTest {

    private val rate = 16_000

    private fun block(id: Long, from: Int, to: Int, cluster: Int, name: String, text: String) =
        DiarizedBlock(
            id = id, recordingId = 1, startSample = from * rate, endSample = to * rate,
            cluster = cluster, speakerName = name, text = text,
        )

    private val recording = DiarizedRecording(
        id = 1,
        name = "german audit bbg.mp3",
        audioPath = "/x/bbg.wav",
        durationMillis = 3_723_000, // 1:02:03, so the hours branch of the clock is exercised
        createdAtMillis = 1_756_636_800_000, // 2025-08-31 10:40 UTC
        status = DiarizedStatus.Done,
        language = "de",
        runMillis = 48_700,
        diariseMillis = 41_200,
        transcribeMillis = 40_900,
        werPercent = 6.99,
        speakerAccuracyPercent = 97.91,
        speechModelId = "fastconformer-en-de-es-fr",
        speakerBundleId = "speaker-campplus",
    )

    private val models = TranscriptExport.Models("FastConformer", "pyannote 3.0", "CAM++")

    @Test
    fun `header names the three models the run recorded and the run's cost and score`() {
        val text = TranscriptExport.render(
            recording, listOf(block(1, 0, 5, 0, "SP1", "Guten Tag.")),
            models = models, sampleRate = rate, zone = ZoneOffset.UTC,
        )
        val lines = text.lines()
        assertEquals("german audit bbg.mp3", lines[0])
        assertEquals("2025-08-31 10:40 · 1:02:03 long · language de", lines[1])
        assertEquals("STT: FastConformer · Segmentation: pyannote 3.0 · Speaker embedding: CAM++", lines[2])
        assertEquals("Run took 48.7 s (diarise 41.2 s, transcribe 40.9 s)", lines[3])
        assertEquals("Against the reference: WER 6.99% · speakers 97.9%", lines[4])
        assertEquals("", lines[5])
    }

    @Test
    fun `turns are grouped by speaker and stamped with their start`() {
        val blocks = listOf(
            block(1, 0, 5, 0, "SP1", "Guten Tag."),
            block(2, 5, 9, 1, "SP1", "Wir beginnen."), // a second cluster, same person: one turn
            block(3, 70, 75, 2, "SP2", "Ja."),
        )
        val text = TranscriptExport.render(recording, blocks, models, rate, ZoneOffset.UTC)
        val turns = text.lines().dropWhile { it.isNotEmpty() }.drop(1).filter { it.isNotEmpty() }
        assertEquals(listOf("[0:00] SP1: Guten Tag. Wir beginnen.", "[1:10] SP2: Ja."), turns)
    }

    @Test
    fun `a row from before the model columns says so rather than guessing`() {
        val old = recording.copy(speechModelId = null, speakerBundleId = null, runMillis = null, werPercent = null)
        val none = TranscriptExport.Models(null, null, null)
        val text = TranscriptExport.render(old, listOf(block(1, 0, 5, 0, "SP1", "Hallo.")), none, rate, ZoneOffset.UTC)
        assertTrue(text.lines()[2] == "STT: not recorded · Segmentation: not recorded · Speaker embedding: not recorded")
        // No run line and no score line: the blank separator follows the models line directly.
        assertEquals("", text.lines()[3])
    }

    @Test
    fun `file name carries the recording and the three models, and is safe for documents providers`() {
        assertEquals(
            "german-audit-bbg-transcript-fastconformer-pyannote-3-0-campp.txt",
            TranscriptExport.fileName("german audit bbg.mp3", models),
        )
        // "Parakeet v3" and "ERes2Net" slug without losing anything a reader needs to tell them apart.
        assertEquals(
            "long-de-transcript-parakeet-v3-reverb-v1-eres2net.txt",
            TranscriptExport.fileName("long_de.wav", TranscriptExport.Models("Parakeet v3", "Reverb v1", "ERes2Net")),
        )
        // A row from before the model columns names only the recording; a name of only symbols is not blank.
        val none = TranscriptExport.Models(null, null, null)
        assertEquals("german-audit-bbg-transcript.txt", TranscriptExport.fileName("german audit bbg.mp3", none))
        assertEquals("transcript.txt", TranscriptExport.fileName("???.wav", none))
    }

    @Test
    fun `unattributed words are written with their time and no name`() {
        val blocks = listOf(
            block(1, 0, 5, 0, "SP1", "Guten Tag."),
            block(2, 5, 6, SpeakerAlignment.UNATTRIBUTED, SpeakerRepository.UNATTRIBUTED_NAME, "Hm."),
            block(3, 6, 9, 1, "SP2", "Ja."),
        )
        val text = TranscriptExport.render(recording, blocks, models, rate, ZoneOffset.UTC)
        val turns = text.lines().dropWhile { it.isNotEmpty() }.drop(1).filter { it.isNotEmpty() }
        assertEquals(listOf("[0:00] SP1: Guten Tag.", "[0:05] Hm.", "[0:06] SP2: Ja."), turns)
    }
}
