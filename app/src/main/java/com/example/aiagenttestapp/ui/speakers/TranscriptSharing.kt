package com.example.aiagenttestapp.ui.speakers

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import com.example.aiagenttestapp.data.audiomodels.AudioModelBundle
import com.example.aiagenttestapp.data.speakers.DiarizedBlock
import com.example.aiagenttestapp.data.speakers.DiarizedRecording
import com.example.aiagenttestapp.data.speakers.TranscriptExport
import com.example.aiagenttestapp.stt.AudioRecorder
import com.example.aiagenttestapp.stt.SpeechModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Renders a transcript to a text file in the cache and offers it through the share sheet.
 *
 * The same route the audit report takes ([com.example.aiagenttestapp.ui.audit.shareAuditReport]),
 * and the same `reports/` cache directory, because that is the one path the FileProvider exposes:
 * a shared file is a copy handed to another app, not something this app keeps, and the system may
 * reclaim it whenever it likes.
 */
suspend fun shareTranscript(
    context: Context,
    recording: DiarizedRecording,
    blocks: List<DiarizedBlock>,
    models: TranscriptExport.Models,
) {
    if (blocks.isEmpty()) {
        Toast.makeText(context, "There is no transcript to export yet.", Toast.LENGTH_SHORT).show()
        return
    }

    val uri = withContext(Dispatchers.IO) {
        runCatching {
            val directory = File(context.cacheDir, "reports").apply { mkdirs() }
            val file = File(directory, TranscriptExport.fileName(recording.name, models))
            // UTF-8 with a byte-order mark. The text was always UTF-8, but a bare .txt carries no
            // charset, and readers that guess from the bytes -- file previews, older desktop
            // editors, mail clients -- guessed wrong on German: every ä, ö, ü and ß arrived as two
            // characters of noise. The BOM is the one in-band signal every such reader honours.
            file.writeText(
                "\uFEFF" + TranscriptExport.render(recording, blocks, models, AudioRecorder.SAMPLE_RATE),
            )
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull()
    }

    if (uri == null) {
        Toast.makeText(context, "Could not prepare the transcript.", Toast.LENGTH_SHORT).show()
        return
    }

    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        // Both, deliberately: EXTRA_SUBJECT is what mail apps use as the subject line, and
        // EXTRA_TITLE is what the sheet itself shows above the preview.
        putExtra(Intent.EXTRA_SUBJECT, recording.name)
        putExtra(Intent.EXTRA_TITLE, recording.name)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    // The flag goes on the chooser too. The receiving app is granted access through whichever
    // intent actually starts it, and which of the two that is has varied across versions.
    val chooser = Intent.createChooser(send, "Export transcript")
        .apply { addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    context.startActivity(chooser)
}

/**
 * The display names for a row's recorded models, from the catalogues the screen already holds.
 *
 * An id the catalogue no longer knows is kept as the id: still true, just terse. A row from before
 * the model columns yields nulls, which the renderer prints as "not recorded" and the file name
 * leaves out. One resolver for the transcript pane and the file screen, so the two never disagree
 * about what a file is called.
 */
fun transcriptModels(
    recording: DiarizedRecording,
    speechChoices: List<SpeechModel>,
    speakerChoices: List<AudioModelBundle>,
): TranscriptExport.Models {
    val stt = speechChoices.firstOrNull { it.id == recording.speechModelId }?.label ?: recording.speechModelId
    val bundle = speakerChoices.firstOrNull { it.id == recording.speakerBundleId }
    return TranscriptExport.Models(
        stt = stt,
        segmentation = bundle?.segmentationLabel ?: recording.speakerBundleId,
        embedding = bundle?.embeddingLabel ?: recording.speakerBundleId,
    )
}

/**
 * Offers an archive of transcripts, already written by [com.example.aiagenttestapp.data.speakers.TranscriptBundle], through the share sheet.
 *
 * Writing and sharing are split, unlike [shareTranscript], because the archive is built in the
 * ViewModel -- it reads blocks for several rows, which is not the screen's business -- while the
 * share sheet needs an Activity to start from. The file is the hand-off between the two. Same cache
 * directory, same provider, for the same reason: a shared file is a copy the system may reclaim.
 */
fun shareTranscriptZip(context: Context, file: File, count: Int) {
    val uri = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }.getOrNull()
    if (uri == null) {
        Toast.makeText(context, "Could not prepare the archive.", Toast.LENGTH_SHORT).show()
        return
    }
    val title = if (count == 1) "1 transcript" else "$count transcripts"
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/zip"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, title)
        putExtra(Intent.EXTRA_TITLE, title)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(send, "Export transcripts")
        .apply { addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    context.startActivity(chooser)
}
