package com.example.aiagenttestapp.ui.benchmark

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import com.example.aiagenttestapp.data.SliceWindow
import com.example.aiagenttestapp.data.notes.SttBackend
import com.example.aiagenttestapp.stt.SpeechEngineKind
import com.example.aiagenttestapp.stt.SpeechModel
import com.example.aiagenttestapp.stt.SpeechModelState
import androidx.compose.foundation.layout.Box
import com.example.aiagenttestapp.ui.components.ListDetailPanes
import androidx.compose.foundation.layout.height
import com.example.aiagenttestapp.ui.components.ControlsContentPanes
import com.example.aiagenttestapp.ui.components.formatBytes
import com.example.aiagenttestapp.ui.components.rememberListDetailState
import com.example.aiagenttestapp.ui.settings.FeedChunkRow
import com.example.aiagenttestapp.ui.settings.FeedPaceRow
import com.example.aiagenttestapp.data.benchmark.ReferenceText
import com.example.aiagenttestapp.data.benchmark.Wer
import com.example.aiagenttestapp.data.benchmark.BenchmarkClip
import com.example.aiagenttestapp.data.benchmark.BenchmarkRun
import com.example.aiagenttestapp.data.benchmark.BenchmarkRunStatus
import com.example.aiagenttestapp.data.benchmark.MatchedPairs
import java.text.DateFormat
import java.util.Date

/**
 * The on-device WER rig: import an audio + reference pair, transcribe it under the current
 * settings, compare the runs.
 *
 * The screen exists to close a loop that used to run through a laptop -- pull the transcript off
 * the device, score it with `docs/wer.py`, compare by hand. Each run row carries the settings it
 * ran under, so "16× against No delay" is two Run taps and one glance.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BenchmarkScreen(
    viewModel: BenchmarkViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // The id rather than the run: a row keeps updating while its job runs, and holding the object
    // would freeze the sheet on the snapshot it was opened with.
    var openRunId by remember { mutableStateOf<Long?>(null) }
    val openRun = state.runsByClip.values.flatten().firstOrNull { it.id == openRunId }

    var importOpen by rememberSaveable { mutableStateOf(false) }

    // The import's three answers, held here rather than driven by the pickers, so each can be given
    // and changed on its own. The previous version launched the reference picker from the audio
    // picker's result: two system dialogs back to back, no way to see what had been chosen, and
    // cancelling the second silently discarded the first.
    var audio by rememberSaveable { mutableStateOf<Uri?>(null) }
    var referenceFile by rememberSaveable { mutableStateOf<Uri?>(null) }
    var referenceText by rememberSaveable { mutableStateOf("") }
    var language by rememberSaveable { mutableStateOf("en") }

    val audioPicker = rememberLauncherForActivityResult(OpenFromArchive()) { uri ->
        if (uri != null) audio = uri
    }
    val transcriptPicker = rememberLauncherForActivityResult(OpenFromArchive()) { uri ->
        if (uri != null) {
            referenceFile = uri
            // The two reference sources are exclusive: whichever was given last is the one meant.
            referenceText = ""
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("STT benchmark", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { importOpen = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Import clip") },
            )
        },
    ) { padding ->
        ControlsContentPanes(
            modifier = Modifier.padding(padding),
            controls = {
                RunSettingsCard(
                    state = state,
                    onIntent = viewModel::onIntent,
                )
            },
        ) {
            state.error?.let { error ->
                item {
                    Text(
                        text = "$error (tap to dismiss)",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.onIntent(BenchmarkIntent.ClearError) },
                    )
                }
            }

            state.importing?.let { importing ->
                item { ImportingCard(importing) }
            }

            if (state.clips.isEmpty()) {
                item {
                    Text(
                        "No clips yet. Import a WAV and the transcript that was read aloud \u2014 " +
                            "the picker opens in Download/Archive/audio.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            items(state.clips, key = { it.id }) { clip ->
                ClipCard(
                    clip = clip,
                    runs = state.runsByClip[clip.id].orEmpty(),
                    comparison = state.comparisons[clip.id],
                    isRunning = state.isRunning(clip.id),
                    onRun = { viewModel.onIntent(BenchmarkIntent.Run(clip.id)) },
                    onDelete = { viewModel.onIntent(BenchmarkIntent.DeleteClip(clip.id)) },
                    onOpenRun = { openRunId = it.id },
                )
            }

            // Clears the floating "Import clip" button. Without it the last card sits under the
            // FAB, which is the overlap this layout is supposed to be free of.
            item { Spacer(Modifier.height(72.dp)) }
        }
    }

    openRun?.let { run ->
        RunDetailSheet(
            run = run,
            clip = state.clips.firstOrNull { it.id == run.clipId },
            onDismiss = { openRunId = null },
        )
    }

    if (importOpen) {
        ImportSheet(
            audio = audio,
            referenceFile = referenceFile,
            referenceText = referenceText,
            language = language,
            onPickAudio = { audioPicker.launch(arrayOf("audio/*", "*/*")) },
            onPickReference = { transcriptPicker.launch(ReferenceText.MIME_TYPES) },
            onReferenceTextChange = {
                referenceText = it
                if (it.isNotBlank()) referenceFile = null
            },
            onLanguage = { language = it },
            onDismiss = { importOpen = false },
            onImport = {
                val picked = audio
                if (picked != null) {
                    val file = referenceFile
                    if (file != null) {
                        viewModel.onIntent(BenchmarkIntent.ImportFromFile(picked, file, language))
                    } else {
                        viewModel.onIntent(
                            BenchmarkIntent.ImportText(picked, referenceText, language),
                        )
                    }
                    // Cleared on success only in the sense that the sheet closes; a failure surfaces
                    // on the list behind it, and re-opening starts from empty rather than from a
                    // half-filled form the user has to work out the state of.
                    audio = null
                    referenceFile = null
                    referenceText = ""
                    importOpen = false
                }
            },
        )
    }

}

/**
 * `OpenDocument`, opening in the reference-corpus folder. An initial-location hint only: pickers
 * that do not honour `EXTRA_INITIAL_URI` simply open where they normally would.
 */
private class OpenFromArchive : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).putExtra(
            DocumentsContract.EXTRA_INITIAL_URI,
            DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents",
                "primary:Download/Archive/audio",
            ),
        )
}

/**
 * What the next run will be started under, changeable here.
 *
 * On this screen rather than only in Settings because this is the screen where the answer matters:
 * a benchmark is a comparison, and a comparison means changing one parameter and running again.
 * Sending the user to Settings and back between every run made the rig tedious enough that runs
 * were being compared from memory instead of from rows.
 *
 * Everything here writes through to the app-wide settings -- see [BenchmarkUiState.backend]. The
 * feed rows are the same composables Settings shows, not copies of them, and they appear only for
 * the platform backend because that is the only path that reads them.
 */
/**
 * Chooses a clip's three pieces -- audio, reference, language -- independently.
 *
 * Replaces a chained flow that opened the audio picker and, on its result, immediately opened a
 * second picker for the reference. That read as one unexplained sequence of system dialogs: nothing
 * said a second file was coming, nothing showed what the first pick had been, and cancelling the
 * second threw the first away with no message. Here each piece is a row that states what it holds,
 * can be changed in any order, and Import stays disabled until enough is present to succeed.
 *
 * The reference has two sources because the two are genuinely different situations: a corpus file
 * that lives beside the recording, and a script that exists only as text the user has on hand.
 * Giving one clears the other -- they are alternatives, and remembering both would leave the sheet
 * unable to say which one it would use.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportSheet(
    audio: Uri?,
    referenceFile: Uri?,
    referenceText: String,
    language: String,
    onPickAudio: () -> Unit,
    onPickReference: () -> Unit,
    onReferenceTextChange: (String) -> Unit,
    onLanguage: (String) -> Unit,
    onDismiss: () -> Unit,
    onImport: () -> Unit,
) {
    val context = LocalContext.current
    val hasReference = referenceFile != null || referenceText.isNotBlank()

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Import clip", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

            ImportStep(
                index = "1",
                title = "Audio",
                detail = audio?.let { displayName(context, it) }
                    ?: "WAV, m4a, mp3 — anything the device can decode. Converted to 16 kHz mono on import.",
                chosen = audio != null,
            ) {
                TextButton(onClick = onPickAudio) {
                    Text(if (audio == null) "Choose file" else "Change")
                }
            }

            ImportStep(
                index = "2",
                title = "Reference transcript",
                detail = when {
                    referenceFile != null -> displayName(context, referenceFile)
                    referenceText.isNotBlank() -> "${referenceText.trim().split(Regex("\\s+")).size} words pasted"
                    else -> "What was actually said — the text the score is measured against."
                },
                chosen = hasReference,
            ) {
                TextButton(onClick = onPickReference) {
                    Text(if (referenceFile == null) "Choose file" else "Change")
                }
            }

            OutlinedTextField(
                value = referenceText,
                onValueChange = onReferenceTextChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("…or paste the reference here") },
                placeholder = { Text("Paste the script that was read aloud") },
                minLines = 3,
                maxLines = 6,
                textStyle = MaterialTheme.typography.bodySmall,
            )

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Reference language",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    // Not the recognition language: this one only decides how numbers are compared.
                    "Which numeral grammar the score normalises with, so \"14001\" and " +
                        "\"fourteen thousand and one\" are not counted as an error.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("en" to "English", "de" to "German").forEach { (code, label) ->
                        FilterChip(
                            selected = language == code,
                            onClick = { onLanguage(code) },
                            label = { Text(label) },
                        )
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(onClick = onImport, enabled = audio != null && hasReference) {
                    Text("Import")
                }
            }
        }
    }
}

/** One numbered row of [ImportSheet]: what it wants, what it has, and how to change it. */
@Composable
private fun ImportStep(
    index: String,
    title: String,
    detail: String,
    chosen: Boolean,
    action: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (chosen) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (chosen) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Column(Modifier.weight(1f)) {
            Text(
                "$index. $title",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        action()
    }
}

/** The file's own name, or the Uri when the provider will not give one. */
private fun displayName(context: Context, uri: Uri): String = runCatching {
    context.contentResolver
        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
}.getOrNull() ?: uri.lastPathSegment ?: uri.toString()

/**
 * The one step with no row of its own to report on: there is no clip until the decode finishes.
 *
 * Determinate wherever the source will say how long it is, indeterminate otherwise -- a bar that
 * moves at a rate unrelated to the work is worse than one that admits it does not know.
 */
@Composable
private fun ImportingCard(importing: Importing) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "Importing ${importing.name}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                if (importing.progress > 0f) {
                    Text(
                        "${(importing.progress * 100).toInt()}%",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Text(
                "Decoding and converting to 16 kHz mono.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (importing.progress > 0f) {
                LinearProgressIndicator(
                    progress = { importing.progress },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun RunSettingsCard(
    state: BenchmarkUiState,
    onIntent: (BenchmarkIntent) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Run settings",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Applied to the next run you start, and recorded on its row.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Recogniser",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SttBackend.entries.forEach { backend ->
                        FilterChip(
                            selected = backend == state.backend,
                            onClick = { onIntent(BenchmarkIntent.SetBackend(backend)) },
                            label = { Text(backend.label) },
                        )
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Skip silence (VAD)",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        // Worth stating on a benchmark screen specifically: turning it off changes
                        // where slices are cut, so the two runs are not decoding the same clips.
                        "Off transcribes every second of the clip, and moves every slice boundary.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.vadEnabled,
                    onCheckedChange = { onIntent(BenchmarkIntent.SetVad(it)) },
                )
            }

            if (state.backend == SttBackend.ONNX) {
                SpeechModelRow(
                    models = state.speechModelOptions,
                    selectedId = state.speechModelId,
                    states = state.speechModelStates,
                    onSelect = { onIntent(BenchmarkIntent.SetSpeechModel(it)) },
                    onDownload = { onIntent(BenchmarkIntent.DownloadSpeechModel(it)) },
                )

                // Offered only for the model that can act on it. Whisper and SenseVoice are held at
                // 28 s by a wall in the model, so a window control on their runs would be a knob
                // that changes nothing -- worse than absent, because a run row would then claim a
                // window the run did not use.
                val selected = state.speechModelOptions.firstOrNull { it.id == state.speechModelId }
                if (selected?.kind == SpeechEngineKind.NEMO_TRANSDUCER) {
                    SliceWindowRow(
                        selected = state.sliceWindow,
                        onSelect = { onIntent(BenchmarkIntent.SetSliceWindow(it)) },
                    )
                }
            }

            if (state.backend == SttBackend.PLATFORM) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Language pack",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        // The reason this is a choice and not a default: a shared protocol for this
                        // engine measured 4.0% against 11.6% on identical audio purely on which
                        // regional pack answered.
                        "Which pack the recogniser is asked for. \"Device default\" sends nothing " +
                            "and lets the service pick — which is how a run ends up on a regional " +
                            "pack nobody chose.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = state.language == null,
                            onClick = { onIntent(BenchmarkIntent.SetLanguage(null)) },
                            label = { Text("Device default") },
                        )
                        state.installedPacks.forEach { tag ->
                            FilterChip(
                                selected = state.language == tag,
                                onClick = { onIntent(BenchmarkIntent.SetLanguage(tag)) },
                                label = { Text(tag) },
                            )
                        }
                    }
                    if (state.installedPacks.isEmpty()) {
                        Text(
                            "No packs reported — the recogniser service did not answer.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    PackDownloadRow(
                        downloadable = state.downloadablePacks,
                        download = state.packDownload,
                        note = state.packNote,
                        onDownload = { onIntent(BenchmarkIntent.DownloadPack(it)) },
                    )
                }

                FeedPaceRow(
                    selected = state.feedPace,
                    onSelect = { onIntent(BenchmarkIntent.SetFeedPace(it)) },
                    chunk = state.feedChunk,
                )
                FeedChunkRow(
                    selected = state.feedChunk,
                    onSelect = { onIntent(BenchmarkIntent.SetFeedChunk(it)) },
                )
            }
        }
    }
}

/**
 * Which sherpa model answers, and how to get one the device has not got.
 *
 * A row per model rather than one chip naming the current choice, for the same reason the record
 * screen grew the same shape: the models differ in which languages they can hear and how long a
 * clip they will take, so a control that does not name them cannot be used to choose between them.
 * Each row carries its own download, because the model worth comparing against is usually the one
 * not on the device yet -- and on this screen that is the whole point of the visit.
 */
@Composable
private fun SpeechModelRow(
    models: List<SpeechModel>,
    selectedId: String?,
    states: Map<String, SpeechModelState>,
    onSelect: (String) -> Unit,
    onDownload: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "Speech model",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        Text(
            "Which recogniser answers. Selecting one does not fetch it — a run against a model " +
                "that is not on the device is refused rather than started.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        models.forEach { model ->
            val modelState = states[model.id] ?: SpeechModelState.NotDownloaded
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = model.id == selectedId,
                    onClick = { onSelect(model.id) },
                    label = { Text(model.label) },
                )

                when (modelState) {
                    is SpeechModelState.Ready -> Unit

                    is SpeechModelState.Downloading -> Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        LinearProgressIndicator(
                            progress = { modelState.progress },
                            modifier = Modifier.width(72.dp),
                        )
                        Text(
                            "${(modelState.progress * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // A failure and a fresh start offer the same button; the download worker's KEEP
                    // policy makes a repeat tap the retry path.
                    is SpeechModelState.NotDownloaded, is SpeechModelState.Failed ->
                        TextButton(onClick = { onDownload(model.id) }) {
                            Icon(
                                Icons.Default.Download,
                                contentDescription = null,
                                Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(formatBytes(model.totalBytes))
                        }
                }
            }

            if (modelState is SpeechModelState.Failed) {
                Text(
                    modelState.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * How much audio goes into one decode.
 *
 * The control this screen was asked for, and the one whose default is least defensible -- see
 * [SliceWindow]. The hint under the chips is the selected option's own, because the numbers that
 * separate them (attention cost, what has actually been run) are the whole basis for choosing and
 * do not fit on a chip.
 */
@Composable
private fun SliceWindowRow(
    selected: SliceWindow,
    onSelect: (SliceWindow) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "Slice window",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        Text(
            // Said plainly because it is the trap: two runs at different windows are not the same
            // measurement repeated, and the checkpoint from one cannot be resumed by the other.
            "The longest piece of audio handed over in one decode. It moves every slice boundary, " +
                "so a run at a different window is a different run — not a repeat.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SliceWindow.entries.forEach { window ->
                FilterChip(
                    selected = window == selected,
                    onClick = { onSelect(window) },
                    label = { Text(window.label) },
                )
            }
        }
        Text(
            selected.hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Packs the device could have, and the one it is fetching.
 *
 * Here rather than in Settings because there is nowhere else: the speech service owns the download
 * UI and does not export it, so a pack the protocol calls for -- de-DE, for the shared German test
 * pair -- can otherwise only be added by hand through the system Settings tree. Scrolls sideways
 * because the list is however many languages the service supports, which is dozens.
 */
@Composable
private fun PackDownloadRow(
    downloadable: List<String>,
    download: PackDownload?,
    note: String?,
    onDownload: (String) -> Unit,
) {
    if (download != null) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Fetching ${download.tag}" + (download.percent?.let { " — $it%" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
            )
            // Indeterminate until the service says otherwise: it reports progress only for a
            // download it is actually running, and a bar sitting at zero looks like a failure.
            if (download.percent != null) {
                LinearProgressIndicator(
                    progress = { download.percent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        return
    }

    if (note != null) {
        Text(note, style = MaterialTheme.typography.bodySmall)
    }

    if (downloadable.isEmpty()) return

    Text(
        "Not installed — tap to fetch:",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        downloadable.forEach { tag ->
            AssistChip(
                onClick = { onDownload(tag) },
                label = { Text(tag) },
                leadingIcon = {
                    Icon(Icons.Default.Download, contentDescription = null)
                },
            )
        }
    }
}

@Composable
private fun ClipCard(
    clip: BenchmarkClip,
    runs: List<BenchmarkRun>,
    /** Whether the two most recent finished runs differ for real; null until there are two. */
    comparison: MatchedPairs.Result?,
    isRunning: Boolean,
    onRun: () -> Unit,
    onDelete: () -> Unit,
    onOpenRun: (BenchmarkRun) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        clip.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "${formatDuration(clip.durationMillis)} · ${clip.language.uppercase()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onRun, enabled = !isRunning) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Text(if (isRunning) "Running…" else "Run")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete clip")
                }
            }

            runs.forEach { run ->
                RunRow(run = run, clip = clip, onOpen = { onOpenRun(run) })
            }

            comparison?.let { ComparisonRow(it) }
        }
    }
}

/**
 * Whether the last two runs on this clip actually differ.
 *
 * Sits under them because that is where the mistake happens: two WERs printed one above the other
 * read as a comparison, and on this rig a comparison of point estimates is often meaningless -- the
 * same configuration twice has come out 3 points apart. The sentence is deliberately plainer than
 * the statistics behind it; the p-value is there for anyone who wants to argue with it.
 */
@Composable
private fun ComparisonRow(result: MatchedPairs.Result) {
    val better = if (result.meanDifference < 0) "the newer run" else "the older run"
    val verdict = if (result.significant) {
        "$better is genuinely better"
    } else {
        "no measurable difference between them"
    }
    val caveat = if (result.reliable) "" else " · only ${result.segments} segments, treat as a hint"

    Text(
        "%.1f%% vs %.1f%% — %s (p = %.3f, %d segments)%s".format(
            result.werA,
            result.werB,
            verdict,
            result.pValue,
            result.segments,
            caveat,
        ),
        style = MaterialTheme.typography.bodySmall,
        color = if (result.significant) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun RunRow(run: BenchmarkRun, clip: BenchmarkClip, onOpen: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = run.status == BenchmarkRunStatus.Done, onClick = onOpen)
            .padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            "${formatTime(run.startedAtMillis)} · ${run.settingsSummary}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when (run.status) {
            BenchmarkRunStatus.Running -> {
                if (run.progress > 0f) {
                    LinearProgressIndicator(
                        progress = { run.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                // A ticking count, not a static one, and it earns its recomposition: these runs
                // take tens of minutes -- the Lenovo took 34 for a 22-minute clip -- and a bar
                // that has not visibly moved in five minutes is indistinguishable from a wedged
                // one. This is the number that says it is still alive.
                //
                // Measured from the enqueue stamp because it is the only one the row has, so it
                // includes any time WorkManager queued the job. That is honest for "how long have
                // I been waiting" and is why it is *not* what gets stored as the run's duration.
                val elapsed by produceState(
                    System.currentTimeMillis() - run.startedAtMillis,
                    run.id,
                ) {
                    while (true) {
                        value = System.currentTimeMillis() - run.startedAtMillis
                        delay(1_000)
                    }
                }
                Text(
                    "running ${formatDuration(elapsed)}" +
                        // Only once there is enough progress for the arithmetic to mean anything.
                        // At 2% a rounding error in the denominator swings the estimate by tens of
                        // minutes, and a wrong ETA is worse than none.
                        if (run.progress >= 0.05f) {
                            val remaining = (elapsed / run.progress - elapsed).toLong()
                            " · about ${formatDuration(remaining)} left"
                        } else {
                            ""
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            BenchmarkRunStatus.Failed -> Text(
                buildString {
                    append(run.error ?: "Failed.")
                    // How long it lasted separates a model that would not load from one that died
                    // part way through a long clip -- the same sentence otherwise describes both.
                    run.wallMillis?.takeIf { it > 0 }?.let {
                        append(" (after ${formatDuration(it)})")
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )

            BenchmarkRunStatus.Done -> {
                // Coverage before WER, and loudly when it is low: a truncated run reports a
                // plausible-looking 30-60% error rate that is really a measure of missing audio.
                val truncated = (run.coveragePercent ?: 100.0) < Wer.TRUNCATED_COVERAGE
                Text(
                    buildString {
                        run.coveragePercent?.let { append("coverage %.0f%% · ".format(it)) }
                        append("WER %.1f%%".format(run.normalisedWerPercent ?: 0.0))
                        append(" · raw %.1f%%".format(run.rawWerPercent ?: 0.0))
                        run.cerPercent?.let { append(" · CER %.1f%%".format(it)) }
                        // The duration first and the ratio in brackets, matching the shared
                        // markdown. The ratio alone answers "is this device fast"; the duration
                        // answers "how long will the next one take", which is what someone
                        // standing over a device queueing runs actually wants.
                        run.wallMillis?.takeIf { it > 0 }?.let { wall ->
                            append(" · took ${formatDuration(wall)}")
                            append(" (%.1f× real time)".format(clip.durationMillis.toDouble() / wall))
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (truncated) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
                if (truncated) {
                    Text(
                        "Coverage under 90% — the transcript is truncated. This is a failed run, " +
                            "not an accuracy number; fix the run before reading the WER.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RunDetailSheet(run: BenchmarkRun, clip: BenchmarkClip?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    run.settingsSummary,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (clip != null && run.status == BenchmarkRunStatus.Done) {
                    TextButton(onClick = { scope.launch { shareBenchmarkRun(context, run, clip) } }) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 6.dp),
                        )
                        Text("Share")
                    }
                }
            }
            run.coveragePercent?.let { coverage ->
                Text(
                    "Coverage %.1f%%".format(coverage) +
                        if (coverage < Wer.TRUNCATED_COVERAGE) " — truncated run, the rate below is missing audio" else "",
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (coverage < Wer.TRUNCATED_COVERAGE) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
            Text(
                "WER %.1f%% normalised · %.1f%% raw".format(
                    run.normalisedWerPercent ?: 0.0,
                    run.rawWerPercent ?: 0.0,
                ) + (run.cerPercent?.let { " · CER %.1f%%".format(it) } ?: ""),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "${run.substitutions ?: 0} substitutions · ${run.deletions ?: 0} deletions · " +
                    "${run.insertions ?: 0} insertions · ${run.referenceWords ?: 0} reference words",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (run.topPairs.isNotBlank()) {
                Text("Most frequent errors", style = MaterialTheme.typography.titleSmall)
                Text(
                    run.topPairs,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text("Transcript", style = MaterialTheme.typography.titleSmall)
            Text(run.transcript, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * `m:ss`, or `h:mm:ss` once it runs past an hour.
 *
 * The hour case is not hypothetical any more: a long clip decoded at the larger slice windows can
 * take over an hour on a slower device, and "94:07" reads as a plausible minute count rather than
 * as an hour and a half.
 */
internal fun formatDuration(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, (totalSeconds % 3600) / 60, totalSeconds % 60)
    } else {
        "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    }
}

private fun formatTime(millis: Long): String =
    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis))
