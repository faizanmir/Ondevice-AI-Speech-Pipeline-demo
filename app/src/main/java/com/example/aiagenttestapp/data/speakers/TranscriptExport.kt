package com.example.aiagenttestapp.data.speakers

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A speaker transcript as a plain-text file for someone who does not have the app.
 *
 * Text, not PDF or JSON: the people a transcript is sent to paste it into notes, search it, and read
 * it in whatever opens first, and a `.txt` does all three anywhere. One turn per line, in the
 * conversational grouping [DialogTurns] makes for the screen -- not one line per diarisation block,
 * which would show the reader every seam the clustering left and read as the speaker interrupting
 * themselves.
 *
 * The header states the provenance a reader cannot see: which models produced the words and the
 * speakers, how long the run took, and what it scored if a reference was attached. The model names
 * come from the row, written when the run finished ([DiarizedRecording.speechModelId]), never from
 * the current Settings -- see the column's note for why. Rows from before that column say so
 * rather than guess.
 */
object TranscriptExport {

    const val NOT_RECORDED = "not recorded"

    /**
     * The three models behind a transcript, by display name; null where the row did not record one.
     *
     * Three, not two, although a run records only a speech model and a bundle: the bundle is a pair
     * -- a segmentation model that finds the turns and an embedder that tells the voices apart --
     * and they are swapped independently (CAM++ under pyannote or under Reverb), so a file that
     * named only the bundle would leave the reader to look the pair up.
     */
    data class Models(val stt: String?, val segmentation: String?, val embedding: String?) {
        val allRecorded: Boolean get() = stt != null && segmentation != null && embedding != null
    }

    fun render(
        recording: DiarizedRecording,
        blocks: List<DiarizedBlock>,
        models: Models,
        sampleRate: Int,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String = buildString {
        appendLine(recording.name)
        appendLine(
            listOf(
                DATE.withZone(zone).format(Instant.ofEpochMilli(recording.createdAtMillis)),
                clock(recording.durationMillis) + " long",
                "language " + recording.language,
            ).joinToString(" · "),
        )
        appendLine(
            "STT: ${models.stt ?: NOT_RECORDED} · Segmentation: ${models.segmentation ?: NOT_RECORDED} · " +
                "Speaker embedding: ${models.embedding ?: NOT_RECORDED}",
        )
        recording.runMillis?.let { run ->
            val phases = listOfNotNull(
                recording.diariseMillis?.let { "diarise ${seconds(it)}" },
                recording.transcribeMillis?.let { "transcribe ${seconds(it)}" },
            )
            appendLine("Run took ${seconds(run)}" + if (phases.isEmpty()) "" else " (${phases.joinToString(", ")})")
        }
        recording.werPercent?.let { wer ->
            val speakers = recording.speakerAccuracyPercent?.let { " · speakers %.1f%%".format(Locale.ROOT, it) } ?: ""
            appendLine("Against the reference: WER %.2f%%".format(Locale.ROOT, wer) + speakers)
        }
        appendLine()
        DialogTurns.from(blocks).forEach { turn ->
            val at = clock(turn.startSample * 1000L / sampleRate)
            // Words nobody was attributed keep their place and their time but get no name: a name
            // here would be a guess the pipeline itself declined to make.
            if (turn.speakerName == SpeakerRepository.UNATTRIBUTED_NAME) {
                appendLine("[$at] ${turn.text}")
            } else {
                appendLine("[$at] ${turn.speakerName}: ${turn.text}")
            }
        }
    }

    /**
     * "german audit bbg.mp3" + FastConformer/pyannote 3.0/CAM++ ->
     * "german-audit-bbg-transcript-fastconformer-pyannote-3-0-campp.txt".
     *
     * The models are in the name because the files are compared side by side: six exports of the
     * same recording under different models in one folder are indistinguishable by recording name,
     * and the header is not visible in a file list. Safe for every documents provider, never blank;
     * the model part is left off when a row from before the columns has nothing to say.
     */
    fun fileName(recordingName: String, models: Models): String {
        val base = slug(recordingName.substringBeforeLast('.'))
        val stem = if (base.isBlank()) "transcript" else "$base-transcript"
        val suffix = if (models.allRecorded) {
            "-" + listOf(models.stt, models.segmentation, models.embedding).joinToString("-") { slug(it!!) }
        } else {
            ""
        }
        return "$stem$suffix.txt"
    }

    /** Lower-case, "+" kept as a letter so CAM++ survives as "campp", everything else a hyphen. */
    private fun slug(text: String): String = text
        .lowercase(Locale.ROOT)
        .replace("+", "p")
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')

    private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)

    private fun seconds(millis: Long) = "%.1f s".format(Locale.ROOT, millis / 1000.0)

    private fun clock(millis: Long): String {
        val total = (millis / 1000).coerceAtLeast(0)
        val hours = total / 3600
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, (total % 3600) / 60, total % 60)
        } else {
            "%d:%02d".format(total / 60, total % 60)
        }
    }
}
