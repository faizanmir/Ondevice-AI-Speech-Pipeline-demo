package com.example.aiagenttestapp.ui.speakers

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.material3.FilledTonalButton
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.draw.rotate
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import com.example.aiagenttestapp.ui.components.ListDetailPanes
import com.example.aiagenttestapp.ui.components.rememberListDetailState
import com.example.aiagenttestapp.ui.components.ControlsContentPanes
import com.example.aiagenttestapp.ui.components.formatDuration
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import com.example.aiagenttestapp.data.audiomodels.AudioModelBundle
import com.example.aiagenttestapp.data.audiomodels.AudioModelState
import com.example.aiagenttestapp.stt.SpeechModelState
import com.example.aiagenttestapp.data.speakers.DialogTurn
import com.example.aiagenttestapp.data.speakers.DialogTurns
import com.example.aiagenttestapp.data.speakers.DiarizedRecording
import com.example.aiagenttestapp.data.benchmark.ReferenceText
import com.example.aiagenttestapp.data.benchmark.Wer
import com.example.aiagenttestapp.data.speakers.DiarizedStatus
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonOutline
import com.example.aiagenttestapp.data.speakers.SpeakerStat
import com.example.aiagenttestapp.data.speakers.SpeakerRepository
import com.example.aiagenttestapp.data.speakers.SpeakerStats
import com.example.aiagenttestapp.data.speakers.TranscriptBundle
import com.example.aiagenttestapp.data.speakers.TranscriptExport
import com.example.aiagenttestapp.ui.theme.speakerAccent
import com.example.aiagenttestapp.stt.AudioRecorder

/**
 * Who said what: a recording split by speaker, with names where the app recognises the voice.
 *
 * Its own screen rather than a mode of the voice-note recorder, because the two want opposite
 * things from the same audio. A note is one person thinking aloud and is finished when its
 * transcript is -- the recorder deletes the audio at that point. This is a conversation, its audio
 * is kept, and the useful action after a run is usually to *re-run it*: with a different expected
 * speaker count, or after enrolling whoever came back as "Speaker 2".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiarizeScreen(
    viewModel: DiarizeViewModel,
    onOpenSpeakers: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenReport: (Long) -> Unit,
    onOpenTranscriptFile: (Long) -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Which transcript is open, by id rather than by the recording: a row updates all the way
    // through a run, and holding the object would freeze the text on the snapshot it was opened at.
    var expandedId by rememberSaveable { mutableStateOf<Long?>(null) }

    // The list is newest-first, so a new recording arrives at the top -- which is off screen for
    // anyone who has scrolled down, and a recording that appears where you cannot see it reads as
    // one that did not start. Keyed on the newest id rather than the count: deleting a row changes
    // the count too, and being thrown to the top after a delete is a jump nobody asked for.
    val listState = rememberLazyListState()
    val newestId = state.recordings.firstOrNull()?.id
    LaunchedEffect(newestId) {
        if (newestId != null) listState.animateScrollToItem(0)
    }

    val selecting = state.selected.isNotEmpty()
    // Back leaves selection mode before it leaves the screen: the selection bar has replaced the
    // top bar and its close button, and a back press that navigated away instead would throw the
    // picks away with no way to notice it had.
    BackHandler(enabled = selecting) { viewModel.onIntent(DiarizeIntent.ClearSelection) }
    // Effects are collected here and not in the nav host, unlike the recorder's: the archive share
    // needs this screen's Activity context and nothing else ever wants to know about it.
    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is DiarizeEffect.ShareZip -> shareTranscriptZip(context, effect.file, effect.count)
            }
        }
    }
    // A selection is a gesture in progress, not a fact about the recordings. Coming back to the
    // screen with rows still ticked from a previous visit would read as the app having picked them.
    DisposableEffect(Unit) {
        onDispose { viewModel.onIntent(DiarizeIntent.ClearSelection) }
    }
    val exportableIds = state.recordings
        .filter { TranscriptBundle.exportable(it, state.blocks[it.id].orEmpty()) }
        .map { it.id }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { viewModel.onIntent(DiarizeIntent.Import(it)) } }

    // Two launchers rather than one with a remembered target. Which recording a picked reference
    // belongs to is decided at the moment the picker opens, and a single launcher would have to
    // carry that decision across a system dialog in state that outlives the screen's recomposition.
    val pendingReferencePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { viewModel.onIntent(DiarizeIntent.AttachReferenceFile(null, it)) } }

    val openReferencePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val target = expandedId
        if (uri != null && target != null) {
            viewModel.onIntent(DiarizeIntent.AttachReferenceFile(target, uri))
        }
    }

    Scaffold(
        topBar = {
            if (selecting) {
                SelectionTopBar(
                    count = state.selected.size,
                    allSelected = exportableIds.isNotEmpty() && state.selected.containsAll(exportableIds),
                    exporting = state.exporting,
                    onClear = { viewModel.onIntent(DiarizeIntent.ClearSelection) },
                    onSelectAll = { viewModel.onIntent(DiarizeIntent.SelectAll) },
                    onExport = { viewModel.onIntent(DiarizeIntent.ExportSelected) },
                )
            } else {
                TopAppBar(
                    title = { Text("Speaker transcript") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        TextButton(onClick = onOpenSpeakers) { Text("Enrolled voices") }
                    },
                )
            }
        },
    ) { padding ->
        ControlsContentPanes(
            modifier = Modifier.padding(padding),
            listState = listState,
            controls = {
                SourceCard(
                    state = state,
                    onImport = { picker.launch(arrayOf("audio/*")) },
                    onStart = { viewModel.onIntent(DiarizeIntent.StartRecording) },
                    onStop = { viewModel.onIntent(DiarizeIntent.StopRecording) },
                    onLiveCapture = { viewModel.onIntent(DiarizeIntent.SetLiveCapture(it)) },
                    onExpected = { viewModel.onIntent(DiarizeIntent.SetExpectedSpeakers(it)) },
                    onChunkMinutes = { viewModel.onIntent(DiarizeIntent.SetChunkMinutes(it)) },
                    onSpeechModel = { viewModel.onIntent(DiarizeIntent.SetSpeechModel(it)) },
                    onSpeakerBundle = { viewModel.onIntent(DiarizeIntent.SetSpeakerBundle(it)) },
                    onPickReference = { pendingReferencePicker.launch(ReferenceText.MIME_TYPES) },
                    onReference = { viewModel.onIntent(DiarizeIntent.AttachReference(null, it)) },
                    onLanguage = { viewModel.onIntent(DiarizeIntent.SetLanguage(null, it)) },
                )

                state.blocker?.let { blocker ->
                    // Stated, not hidden behind a disabled button. Needing one *particular* speech
                    // model is the kind of requirement nobody guesses from a greyed-out control --
                    // and it comes with the way to fix it, because naming a screen and leaving the
                    // user to find it is most of the way to not saying anything.
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            blocker,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(onClick = onOpenModels, contentPadding = PaddingValues(0.dp)) {
                            Text("Manage models")
                        }
                    }
                }
            },
        ) {
            state.error?.let { error ->
                item {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            error,
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(onClick = { viewModel.onIntent(DiarizeIntent.ClearError) }) {
                            Text("Dismiss")
                        }
                    }
                }
            }

            if (state.recordings.isEmpty() && state.importing == null) {
                item {
                    Text(
                        "Import a recording or record one \u2014 it goes through the models straight " +
                            "away, and comes back split by speaker.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            state.recordings.forEach { recording ->
                val blocks = state.blocks[recording.id].orEmpty()

                item(key = recording.id) {
                    RecordingRow(
                        recording = recording,
                        models = transcriptModels(recording, state.speechChoices, state.speakerChoices),
                        speakerCount = blocks.map { it.speakerName }.distinct().count { !isUnattributed(it) },
                        selected = recording.id == expandedId,
                        selecting = selecting,
                        checked = recording.id in state.selected,
                        exportable = recording.id in exportableIds,
                        onOpen = {
                            if (selecting) {
                                viewModel.onIntent(DiarizeIntent.ToggleSelected(recording.id))
                            } else {
                                expandedId = if (expandedId == recording.id) null else recording.id
                            }
                        },
                        onLongPress = { viewModel.onIntent(DiarizeIntent.ToggleSelected(recording.id)) },
                        onStop = { viewModel.onIntent(DiarizeIntent.Stop(recording.id)) },
                        onDelete = { viewModel.onIntent(DiarizeIntent.Delete(recording.id)) },
                    )
                }

                // The transcript belongs to the content side, under the recording it came from, and
                // as its own items rather than a nested scroller -- a twenty-minute conversation is
                // dozens of turns, and a list inside a list is the overlap this layout removes.
                if (recording.id == expandedId) {
                    // Words diarisation never covered are shown, but they are not a speaker: they
                    // get no row in the roster and no place in the count. See [UnattributedRow].
                    val stats = SpeakerStats.from(blocks, AudioRecorder.SAMPLE_RATE)
                        .filterNot { isUnattributed(it.name) }
                    val turns = DialogTurns.from(blocks)
                    // The summary is already ordered by first speech, so a speaker's position in it
                    // *is* their colour index. One ordering serves both halves of the screen, which
                    // is what makes the swatch beside a name mean the turns tinted the same below.
                    val order = stats.map { it.name }

                    item {
                        TranscriptHeader(
                            recording = recording,
                            stats = stats,
                            turnCount = turns.size,
                            onOpenReport = { onOpenReport(recording.id) },
                            onExport = {
                                val models = transcriptModels(recording, state.speechChoices, state.speakerChoices)
                                scope.launch { shareTranscript(context, recording, blocks, models) }
                            },
                            onViewFile = { onOpenTranscriptFile(recording.id) },
                            onRun = { viewModel.onIntent(DiarizeIntent.Run(recording.id)) },
                            onStop = { viewModel.onIntent(DiarizeIntent.Stop(recording.id)) },
                            onPlayLive = { viewModel.onIntent(DiarizeIntent.PlayLive(recording.id)) },
                            onPickReference = { openReferencePicker.launch(ReferenceText.MIME_TYPES) },
                            onReference = {
                                viewModel.onIntent(DiarizeIntent.AttachReference(recording.id, it))
                            },
                            onLanguage = {
                                viewModel.onIntent(DiarizeIntent.SetLanguage(recording.id, it))
                            },
                        )
                    }
                    items(turns, key = { "turn-" + it.id }) { turn ->
                        if (isUnattributed(turn.speakerName)) {
                            UnattributedRow(turn)
                        } else {
                            DialogTurnRow(
                                turn = turn,
                                accent = speakerAccent(order.indexOf(turn.speakerName)),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceCard(
    state: DiarizeUiState,
    onImport: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onLiveCapture: (Boolean) -> Unit,
    onExpected: (Int) -> Unit,
    onChunkMinutes: (Int) -> Unit,
    onSpeechModel: (String) -> Unit,
    onSpeakerBundle: (String) -> Unit,
    onPickReference: () -> Unit,
    onReference: (String) -> Unit,
    onLanguage: (String) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "New recording",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )

            // Only offered while nothing is recording: flipping it mid-take could not retroactively
            // give the running capture a row to write into.
            if (state.recordingMillis == null) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Label speakers while recording", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Words and speakers appear as you talk; the final transcript follows at Stop.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = state.liveCapture, onCheckedChange = onLiveCapture)
                }
            }

            state.importing?.let { importing ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Converting ${importing.name} — ${(importing.progress * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    LinearProgressIndicator(
                        progress = { importing.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            if (state.recordingMillis != null) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Recording ${formatClock(state.recordingMillis)}",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = onStop) {
                        Icon(Icons.Default.Stop, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Stop")
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onImport, enabled = state.importing == null) {
                        Icon(Icons.Default.UploadFile, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Import")
                    }
                    OutlinedButton(onClick = onStart, enabled = state.importing == null) {
                        Icon(Icons.Default.Mic, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Record")
                    }
                }
            }

            HorizontalDivider()
            ReferenceEditor(
                reference = state.pendingReference,
                language = state.pendingLanguage,
                // Optional here and mandatory on the benchmark screen, which is the difference
                // between the two features: a benchmark clip exists only to be scored, while a
                // conversation is worth transcribing whether or not anyone knows what was said.
                title = "Reference transcript (optional)",
                onPickReference = onPickReference,
                onReference = onReference,
                onLanguage = onLanguage,
            )

            HorizontalDivider()
            SectionTitle("Models")
            Text(
                "Tap to use a model; one that is not on the device downloads first, and the run " +
                    "unblocks when it lands.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ModelChipRow(
                caption = "Words",
                chips = state.speechChoices.map { model ->
                    ModelChip(
                        id = model.id,
                        label = model.label,
                        selected = model.id == state.speechModelId,
                        note = downloadNote(state.speechStates[model.id]),
                    )
                },
                onPick = onSpeechModel,
            )
            ModelChipRow(
                caption = "Speakers",
                chips = state.speakerChoices.map { bundle ->
                    ModelChip(
                        id = bundle.id,
                        label = bundleChipName(bundle),
                        selected = bundle.id == state.speakerBundleId,
                        note = downloadNote(state.bundleStates[bundle.id]),
                    )
                },
                onPick = onSpeakerBundle,
            )
            // Worth a warning where the choice is made, not only in the enrolment screen's small
            // print: voiceprints carry the id of the embedder that made them, and under any other
            // embedder they match nobody -- not wrongly, just never.
            Text(
                "Enrolled voices only match under the Speakers model that enrolled them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()
            SectionTitle("Run")
            Text(
                "How many people?",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                // This used to say telling it the number was better than letting it guess. It is
                // not, once voices are enrolled -- forcing the count made a two-person recording
                // come back as one person talking for 99% of it, because the count is a hard limit
                // on how many groups the clustering may form rather than a hint. Enrolled voices are
                // matched per group, so extra groups cost nothing and missing ones cannot be
                // recovered. The setting still applies when nobody is enrolled.
                if (state.enrolledCount > 0) {
                    "Only used when nobody is enrolled — with enrolled voices it works the count " +
                        "out and matches each one by voice, which is more reliable."
                } else {
                    "Telling it the number helps when nobody is enrolled — left to work it out on " +
                        "a short or noisy recording, it tends to split one person into two."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilterChip(
                    selected = state.expectedSpeakers == 0,
                    onClick = { onExpected(0) },
                    label = { Text("Work it out") },
                )
                (2..8).forEach { count ->
                    FilterChip(
                        selected = state.expectedSpeakers == count,
                        onClick = { onExpected(count) },
                        label = { Text("$count") },
                    )
                }
            }

            Text(
                "Chunking",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                "How much of a recording is diarised at once. Shorter chunks finish sooner and use " +
                    "more cores; the whole recording at once keeps each person as one voice most " +
                    "reliably. A setting for every run, not this recording alone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilterChip(
                    selected = state.chunkMinutes == 0,
                    onClick = { onChunkMinutes(0) },
                    label = { Text("Whole recording") },
                )
                listOf(2, 5, 8, 10).forEach { minutes ->
                    FilterChip(
                        selected = state.chunkMinutes == minutes,
                        onClick = { onChunkMinutes(minutes) },
                        label = { Text("$minutes min") },
                    )
                }
            }

        }
    }
}

internal data class ModelChip(
    val id: String,
    val label: String,
    val selected: Boolean,
    /** Appended to the label -- a download percentage, "get", or "retry" -- null when ready. */
    val note: String?,
)

/**
 * One captioned row of model chips.
 *
 * The chip is the whole download surface here: its note says what a tap will do ("get"), what a
 * tap did (a percentage), or what went wrong ("retry" -- the same tap enqueues again). Cancel and
 * delete deliberately stay on the models screen; this panel exists to get a run going, not to
 * manage disk.
 */
@Composable
internal fun ModelChipRow(
    caption: String,
    chips: List<ModelChip>,
    onPick: (String) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            caption,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(64.dp),
        )
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            chips.forEach { chip ->
                FilterChip(
                    selected = chip.selected,
                    onClick = { onPick(chip.id) },
                    label = { Text(if (chip.note == null) chip.label else "${chip.label} · ${chip.note}") },
                )
            }
        }
    }
}

internal fun downloadNote(state: SpeechModelState?): String? = when (state) {
    is SpeechModelState.Downloading -> "${(state.progress * 100).toInt()}%"
    is SpeechModelState.Failed -> "retry"
    SpeechModelState.NotDownloaded -> "get"
    else -> null
}

internal fun downloadNote(state: AudioModelState?): String? = when (state) {
    is AudioModelState.Downloading -> "${(state.progress * 100).toInt()}%"
    is AudioModelState.Failed -> "retry"
    AudioModelState.NotDownloaded -> "get"
    else -> null
}

/**
 * Chip-sized names for the speaker bundles. The catalogue labels all begin "Speaker
 * identification", which says nothing once three of them sit side by side; what tells them apart
 * is the model family, so that is what the chip says.
 */
internal fun bundleChipName(bundle: AudioModelBundle): String = when (bundle.id) {
    "speaker" -> "ERes2Net"
    "speaker-campplus" -> "CAM++"
    "speaker-reverb-campplus" -> "Reverb v1"
    else -> bundle.label
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
}

/**
 * Attaching a reference, in the two ways one arrives: a file beside the recording, or text on hand.
 *
 * The same pair the benchmark's import sheet offers, and for the same reason -- a corpus clip comes
 * with a script file, an ad-hoc check comes as something pasted. Neither clears the other here,
 * because both end up as the same string on the same row: the last one given simply wins.
 *
 * The paste field applies on a press rather than on every keystroke. Scoring is an edit distance
 * over every word of both transcripts, and running it per character typed would re-score a
 * thousand-word reference a thousand times.
 */
@Composable
private fun ReferenceEditor(
    reference: String,
    language: String,
    title: String,
    onPickReference: () -> Unit,
    onReference: (String) -> Unit,
    onLanguage: (String) -> Unit,
) {
    // Keyed on the stored reference so that a file picked while this is on screen replaces what the
    // field shows -- without it the editor would keep displaying text the row no longer holds.
    var draft by remember(reference) { mutableStateOf(reference) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Text(
            "What was actually said, one bracketed speaker per turn — “[S1] ... [S2] ...”. " +
                "The tags say who, and are not scored as words.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Paste the reference, or choose a file") },
            placeholder = { Text("[S1] so where did we land on the migration") },
            minLines = 3,
            maxLines = 6,
            textStyle = MaterialTheme.typography.bodySmall,
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onPickReference) {
                Icon(Icons.Default.UploadFile, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Choose file")
            }
            // Enabled only when the field differs from what is stored, so the control says whether
            // there is anything to apply. Clearing a stored reference is a real change and stays
            // available: an empty field against a stored one is a difference like any other.
            TextButton(onClick = { onReference(draft) }, enabled = draft != reference) {
                Text(if (draft.isBlank()) "Clear" else "Use this")
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                // Not the recognition language: this one only decides how numbers are compared.
                "Numerals:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            listOf("en" to "English", "de" to "German").forEach { (code, label) ->
                FilterChip(
                    selected = language == code,
                    onClick = { onLanguage(code) },
                    label = { Text(label) },
                )
            }
        }
    }
}

/**
 * One recording in the list.
 *
 * Two kinds of "selected" meet here and are coloured apart on purpose. [selected] is the row whose
 * transcript is open below it; [checked] is a row picked for the bundled export. A long-press
 * starts picking, and while [selecting] a tap picks too, instead of opening -- the list cannot do
 * both on one gesture, and picking is the mode the user just entered on purpose. A row that is not
 * [exportable] shows its box greyed and unticked rather than hiding it: hiding would make the list
 * look like it had fewer rows, and a disabled box says why the tap did nothing.
 */
/**
 * One recording in the list.
 *
 * The row carries a lot -- what the recording is, how a run went, what it scored, what it cost and
 * which models produced it -- and it used to carry all of it as four consecutive lines in the same
 * size and the same grey. Nothing was wrong with any single line; the problem was that a reader
 * scanning the list had nothing to land on, and the two numbers they actually came for (did it
 * work, how good was it) sat *below* two lines of pipeline diagnostics.
 *
 * So the content is stratified rather than trimmed. Four tiers, each visually distinct:
 *
 *  1. **What it is** -- name, and a status the eye can find in the same place on every row.
 *  2. **What it contains** -- length and speakers, the facts a person recognises it by.
 *  3. **How it scored** -- [MetricStrip], the results, given the weight results deserve.
 *  4. **What it cost and what ran** -- one demoted line below a divider, for whoever is tuning the
 *     pipeline rather than reading a transcript.
 *
 * Nothing was dropped in the move. Every value the four grey lines carried is still here.
 */
@Composable
private fun RecordingRow(
    recording: DiarizedRecording,
    models: TranscriptExport.Models,
    speakerCount: Int,
    selected: Boolean,
    selecting: Boolean,
    checked: Boolean,
    exportable: Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onStop: () -> Unit,
    onDelete: () -> Unit,
) {
    val busy = recording.status == DiarizedStatus.Running || recording.status == DiarizedStatus.Live
    val chevronTurn by animateFloatAsState(
        targetValue = if (selected) 180f else 0f,
        label = "chevron",
    )

    Card(
        colors = CardDefaults.cardColors(
            containerColor = when {
                checked -> MaterialTheme.colorScheme.primaryContainer
                selected -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
    ) {
        // The gesture is on the content, not the card: Material's clickable Card has no long-press,
        // and the card clips its content to its own shape, so the ripple still stops at the corners.
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
                .height(IntrinsicSize.Min),
        ) {
            StatusStripe(recording.status)

            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // ---- Tier 1: what it is -------------------------------------------------------
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Title and pill share one weighted cell, and the actions sit outside it. The
                    // obvious arrangement -- a weighted title, then a weighted spacer before the
                    // icons -- gives the two weights *half the free space each*, so the trash icon
                    // lands at a different x on every row depending on how long the name is. The
                    // icons have to be the fixed end of the row for a column of them to line up.
                    Row(
                        Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            recording.name,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )

                        StatusPill(recording.status)
                    }

                    if (selecting) {
                        // The row's own controls step aside while picking. Delete beside a tick box
                        // is how a row gets deleted by someone aiming for the box.
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { onOpen() },
                            enabled = exportable,
                        )
                    } else {
                        // Stop lives on the row, where the progress bar is, so a run can be halted
                        // without opening the transcript -- and so the trash icon is no longer the
                        // only control in reach while a row is busy, which is how a live session was
                        // deleted mid-run once. Delete is tinted down beside it: two icons of equal
                        // weight is what made that mistap easy, and only one of them is reversible.
                        if (busy) {
                            IconButton(onClick = onStop) {
                                Icon(Icons.Default.Stop, contentDescription = "Stop")
                            }
                        }
                        IconButton(onClick = onDelete) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Delete",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        // The row opens its transcript when tapped, and nothing said so. A chevron
                        // is the affordance people already know, and rotating it rather than
                        // swapping the glyph shows *which way* the row is about to move.
                        //
                        // Deliberately not a button. The whole row is the target, and a chevron
                        // that were separately tappable would offer two hit areas for one action --
                        // with a 48dp gap between them where a tap does something else entirely.
                        Icon(
                            Icons.Default.ExpandMore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(start = 4.dp)
                                .rotate(chevronTurn),
                        )
                    }
                }

                // ---- Tier 2: what it contains -------------------------------------------------
                Text(
                    buildString {
                        append(formatClock(recording.durationMillis))
                        if (speakerCount > 0) {
                            append(" · $speakerCount ")
                            append(if (speakerCount == 1) "speaker" else "speakers")
                        }
                        append(
                            if (recording.expectedSpeakers > 0) {
                                " · ${recording.expectedSpeakers} expected"
                            } else {
                                " · count worked out"
                            },
                        )
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // ---- Status: progress, failure, and the notes that go with them ---------------
                //
                // On the row rather than only in the detail: a run takes minutes, and the whole
                // point of the list is to see at a glance which ones are still going.
                when (recording.status) {
                    // Imported and waiting on whatever the blocker at the top of the list says. A
                    // run starts itself otherwise, so this is only seen when something is missing.
                    DiarizedStatus.Idle -> Unit

                    DiarizedStatus.Running -> RunProgress(recording.progress)

                    DiarizedStatus.Failed -> Text(
                        recording.error ?: "Failed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )

                    DiarizedStatus.Live -> {
                        RunProgress(recording.progress)
                        Text(
                            "Labels are provisional until the recording ends.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    DiarizedStatus.Stopped -> Text(
                        "Run or Play as live starts it over.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    DiarizedStatus.Done -> Unit
                }

                // ---- Tier 3: how it scored ----------------------------------------------------
                MetricStrip(recording)

                // ---- Tier 4: what it cost, and what ran ---------------------------------------
                ProvenanceLine(recording, models)
            }
        }
    }
}

/**
 * The status, as a colour the eye can find in the same place on every row.
 *
 * A four-pixel rule down the left edge rather than a badge in the flow, so scanning a list for
 * "which of these is still running" is one vertical sweep instead of reading six rows of text.
 * Colour alone is never the whole answer -- [StatusPill] carries the word, and this carries the
 * description for a screen reader -- but it is what makes the list scannable at a glance.
 */
@Composable
private fun StatusStripe(status: DiarizedStatus) {
    val colour = when (status) {
        DiarizedStatus.Done -> MaterialTheme.colorScheme.primary
        DiarizedStatus.Running -> MaterialTheme.colorScheme.primary
        DiarizedStatus.Live -> MaterialTheme.colorScheme.tertiary
        DiarizedStatus.Failed -> MaterialTheme.colorScheme.error
        DiarizedStatus.Idle, DiarizedStatus.Stopped -> MaterialTheme.colorScheme.outlineVariant
    }
    Box(
        Modifier
            .fillMaxHeight()
            .width(4.dp)
            .background(colour)
            .semantics { contentDescription = statusWord(status) },
    )
}

/**
 * The status as a word, for every state that is not the quiet majority.
 *
 * [DiarizedStatus.Done] gets none on purpose. Most rows in a healthy list are done, and a badge on
 * every one of them would be a badge that says nothing -- the rows worth noticing are the ones
 * still running, stopped or broken, and they are exactly the ones that carry a pill.
 */
@Composable
private fun StatusPill(status: DiarizedStatus) {
    if (status == DiarizedStatus.Done) return

    val container = when (status) {
        DiarizedStatus.Live -> MaterialTheme.colorScheme.tertiaryContainer
        DiarizedStatus.Failed -> MaterialTheme.colorScheme.errorContainer
        DiarizedStatus.Running -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val content = when (status) {
        DiarizedStatus.Live -> MaterialTheme.colorScheme.onTertiaryContainer
        DiarizedStatus.Failed -> MaterialTheme.colorScheme.onErrorContainer
        DiarizedStatus.Running -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        color = container,
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.padding(start = 8.dp),
    ) {
        Text(
            statusWord(status).uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = content,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

private fun statusWord(status: DiarizedStatus): String = when (status) {
    DiarizedStatus.Idle -> "Not run yet"
    DiarizedStatus.Running -> "Running"
    DiarizedStatus.Live -> "Live"
    DiarizedStatus.Done -> "Done"
    DiarizedStatus.Failed -> "Failed"
    DiarizedStatus.Stopped -> "Stopped"
}

/** Determinate once there is something to be determinate about; indeterminate before that. */
@Composable
private fun RunProgress(progress: Float) {
    if (progress > 0f) {
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

/**
 * How the run scored against its reference, as three labelled figures rather than a sentence.
 *
 * Tiles because this is the one thing on the row a reader compares *between* rows -- "is this run
 * better than the last one" -- and a number buried mid-sentence sits at a different horizontal
 * position on every row, so the eye has to re-find it each time. Labelled and boxed, they line up
 * well enough to scan down. Figures are tabular so the digits do too.
 *
 * Coverage leads, and that is the same deliberate order the old sentence had: a truncated transcript
 * produces a plausible-looking error rate that is really a measure of how much is missing, and that
 * has already been read here as an accuracy result once. Under the threshold the coverage tile turns
 * error-coloured *and the WER tile is muted*, so the discredited number looks discredited rather
 * than merely sitting next to a warning.
 */
@Composable
private fun MetricStrip(recording: DiarizedRecording) {
    val wer = recording.werPercent ?: return
    val truncated = recording.isTruncated

    Row(
        Modifier.padding(top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        recording.coveragePercent?.let {
            MetricTile("Coverage", "%.0f%%".format(it), alert = truncated)
        }
        MetricTile("WER", "%.1f%%".format(wer), muted = truncated)
        recording.speakerAccuracyPercent?.let {
            MetricTile("Speakers", "%.0f%%".format(it), muted = truncated)
        }
    }
}

/**
 * One figure with its name above it.
 *
 * [alert] is the only colour spent here. A good score is left unmarked on purpose -- painting every
 * healthy number green would make the one number that needs attention harder to find, not easier.
 */
@Composable
private fun MetricTile(
    label: String,
    value: String,
    alert: Boolean = false,
    muted: Boolean = false,
) {
    val content = when {
        alert -> MaterialTheme.colorScheme.error
        muted -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
        else -> MaterialTheme.colorScheme.onSurface
    }
    Surface(
        color = if (alert) {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f)
        },
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(
                label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                value,
                style = MaterialTheme.typography.titleSmall.copy(
                    // Digits of equal width, so a column of these lines up down the list.
                    fontFeatureSettings = "tnum",
                ),
                color = content,
            )
        }
    }
}

/**
 * What the run cost and which models produced it -- everything a reader tuning the pipeline wants
 * and a reader looking for a transcript does not.
 *
 * One wrapped line below a divider, deliberately the quietest thing on the row. It carries exactly
 * what the old `PhaseLine` and `ModelsLine` did, in the order a question gets asked: how long in
 * total, where those minutes went, and what ran.
 *
 * "run together" is not decoration. The two branches are concurrent, so they do not sum to the
 * total, and a reader who tries to add them up should be told why the sum overshoots before they
 * conclude the numbers are wrong.
 *
 * A live session reports the wait *after* its audio ended rather than the final pass's own
 * duration. Those are different questions and only one of them is the user's: they watched the
 * recording finish, and what they sat through afterwards is the cost live mode actually has. Saying
 * "took 12s" for a 22-minute recording played live would be true of the pass and useless as an
 * answer.
 */
@Composable
private fun ProvenanceLine(
    recording: DiarizedRecording,
    models: TranscriptExport.Models,
) {
    val parts = buildList {
        val post = recording.postCaptureMillis
        if (post != null) {
            add("${formatDuration(post)} after recording")
        } else {
            recording.runMillis?.let { add("${formatDuration(it)} total") }
        }

        val diarise = recording.diariseMillis
        val transcribe = recording.transcribeMillis
        when {
            diarise != null && transcribe != null -> add(
                "speakers ${formatDuration(diarise)} · words ${formatDuration(transcribe)}, " +
                    "run together",
            )
            diarise != null -> add("speakers ${formatDuration(diarise)}")
            transcribe != null -> add("words ${formatDuration(transcribe)}")
        }

        addAll(listOfNotNull(models.stt, models.segmentation, models.embedding))
    }
    if (parts.isEmpty()) return

    HorizontalDivider(
        Modifier.padding(top = 4.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
    Text(
        parts.joinToString(" · "),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * How the run scored against its reference, or nothing at all when it has none.
 *
 * Coverage is printed first and the whole line turns red below 90%, which is the shared scoring
 * protocol this app already follows on the benchmark screen: a truncated transcript produces a
 * plausible-looking error rate that is really a measure of how much is missing, and that has
 * already been read here as an accuracy result once.
 *
 * The two rates are kept apart rather than blended into one number. Mishearing a word and giving
 * the right word to the wrong person are different failures with different fixes -- a worse
 * recogniser against a worse embedding model -- and a single figure would hide which one a run has.
 */
/**
 * Where a run's minutes went: working out who spoke, against writing down what they said.
 *
 * Shown apart from the total because the two are the pipeline's two independent costs and they
 * respond to different fixes -- one to the diarisation window shift and chunking, the other to the
 * choice of recogniser. The total alone cannot say which one to reach for.
 *
 * "run together" is not decoration. The branches are concurrent, so these two do not sum to the
 * total, and a reader who tries to add them up should be told why the sum overshoots before they
 * conclude the numbers are wrong.
 */
/**
 * The top bar while rows are being picked for a bundled export.
 *
 * It replaces the screen's own bar rather than sitting above the list, because the list scrolls
 * and a bar that scrolled away with it would leave the user ticking rows with no count and no
 * Export in sight. "Select all" means every row that *can* be exported; when all of those are
 * ticked the same button offers "None", which is also what the close button does -- two ways out
 * because the button is where the eye already is after tapping it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(
    count: Int,
    allSelected: Boolean,
    exporting: Boolean,
    onClear: () -> Unit,
    onSelectAll: () -> Unit,
    onExport: () -> Unit,
) {
    TopAppBar(
        title = { Text(if (count == 1) "1 selected" else "$count selected") },
        navigationIcon = {
            IconButton(onClick = onClear) {
                Icon(Icons.Default.Close, contentDescription = "Clear selection")
            }
        },
        actions = {
            TextButton(onClick = if (allSelected) onClear else onSelectAll) {
                Text(if (allSelected) "None" else "Select all")
            }
            if (exporting) {
                CircularProgressIndicator(Modifier.padding(horizontal = 12.dp).size(20.dp), strokeWidth = 2.dp)
            } else {
                TextButton(onClick = onExport, enabled = count > 0) {
                    Icon(Icons.Default.Archive, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Export ZIP")
                }
            }
        },
    )
}

/**
 * Which models produced this row's transcript -- the recogniser, the segmentation model and the
 * speaker embedder -- from the ids the run recorded, never from the chips above the list.
 *
 * On the row because the list is where runs are compared: six rows of the same recording under
 * different models are indistinguishable by name and duration, and the chips say only what the
 * *next* run will use. A row from before the models were recorded shows nothing here rather than
 * a guess; a run that has not happened yet has nothing to show either.
 */
@Composable
private fun ModelsLine(models: TranscriptExport.Models) {
    val parts = listOfNotNull(models.stt, models.segmentation, models.embedding)
    if (parts.isEmpty()) return
    Text(
        parts.joinToString(" · "),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun PhaseLine(recording: DiarizedRecording) {
    val diarise = recording.diariseMillis
    val transcribe = recording.transcribeMillis
    if (diarise == null && transcribe == null) return

    Text(
        buildString {
            diarise?.let { append("speakers ${formatDuration(it)}") }
            if (diarise != null && transcribe != null) append(" · ")
            transcribe?.let { append("words ${formatDuration(it)}") }
            if (diarise != null && transcribe != null) append(", run together")
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ScoreLine(recording: DiarizedRecording) {
    val wer = recording.werPercent ?: return
    val coverage = recording.coveragePercent

    Text(
        buildString {
            coverage?.let { append("coverage %.0f%% · ".format(it)) }
            append("WER %.1f%%".format(wer))
            recording.speakerAccuracyPercent?.let { append(" · speakers %.0f%%".format(it)) }
        },
        style = MaterialTheme.typography.bodySmall,
        color = if (recording.isTruncated) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}

/**
 * The heading above an opened transcript: what it is, how to run it again, and who is in it.
 *
 * Separate from the turns rather than wrapping them, because the turns are emitted as their own
 * items in the content list. Wrapping them in a scroller of their own would put a list inside a
 * list, which is exactly the overlap this screen's layout was changed to remove.
 *
 * Laid out for the person who opened the row to *read*. The first version put the reference editor
 * -- a title, a paragraph, a six-line field, two buttons and a chip row -- between the speaker
 * summary and the first turn, so every look at a transcript meant scrolling past a form. It is now
 * one line that says what is attached and opens the editor on demand; the turns start where the
 * eye lands after the speakers. Nothing was removed: every control and every note is still here,
 * in the order it is needed rather than the order it was written.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TranscriptHeader(
    recording: DiarizedRecording,
    stats: List<SpeakerStat>,
    turnCount: Int,
    onOpenReport: () -> Unit,
    onExport: () -> Unit,
    onViewFile: () -> Unit,
    onRun: () -> Unit,
    onStop: () -> Unit,
    onPlayLive: () -> Unit,
    onPickReference: () -> Unit,
    onReference: (String) -> Unit,
    onLanguage: (String) -> Unit,
) {
    val hasBlocks = stats.isNotEmpty()
    val busy = recording.status == DiarizedStatus.Running || recording.status == DiarizedStatus.Live

    // Per recording, and survives the row being scrolled out of composition: a reference someone
    // was halfway through pasting must not fold shut because the list recycled the item.
    var editingReference by rememberSaveable(recording.id) { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // ---- Actions: one that is filled, the rest quiet ------------------------------------
        //
        // Five identical outlined buttons gave the eye nothing to land on. The one a reader
        // reaches for -- run it again at a different count, or after enrolling "Speaker 2" -- is
        // the filled one; while a run is going, Stop takes that place instead, because it is then
        // the only urgent thing on the row. Run stays offered even while the row says Running,
        // which looks wrong and is the safety valve: a run whose worker died leaves the row
        // claiming progress forever, and hiding the only button that restarts it makes the state
        // unrecoverable without deleting the recording.
        //
        // Wraps: five controls no longer fit one line of a phone-width pane, and a row that clips
        // the last of them hides exactly the newest one.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (busy) {
                FilledTonalButton(onClick = onStop) {
                    Icon(Icons.Default.Stop, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Stop")
                }
                TextButton(onClick = onRun) { Text(if (hasBlocks) "Run again" else "Run") }
            } else {
                FilledTonalButton(onClick = onRun) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (hasBlocks) "Run again" else "Run")
                }
            }
            // The same recording fed at the speed it was spoken, with speakers and words appearing
            // as it goes and the ordinary run replacing them at the end. A demonstration of the live
            // path on audio whose right answer is already known, and the way to measure its latency.
            TextButton(onClick = onPlayLive, enabled = recording.status != DiarizedStatus.Live) {
                Text(if (recording.status == DiarizedStatus.Live) "Playing live…" else "Play as live")
            }
            // Only once there are words to export. Offered during a live session too: what is on
            // screen is provisional, but a provisional transcript someone wants now is still worth
            // more than one they cannot have until the audio ends.
            if (hasBlocks) {
                TextButton(onClick = onExport) {
                    Icon(Icons.Default.Share, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Export")
                }
                // The file itself, on screen: what Export will send, byte for byte, so the header
                // and the special characters can be checked here before they go anywhere.
                TextButton(onClick = onViewFile) {
                    Icon(Icons.Default.Description, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("View file")
                }
            }
        }

        if (recording.status == DiarizedStatus.Live) {
            Note(
                "Live — these speakers and words are provisional. The final transcript replaces " +
                    "them when the audio ends.",
                tone = NoteTone.Live,
            )
        }
        if (!hasBlocks && recording.status == DiarizedStatus.Done) {
            Note("No speech was attributed in this recording.", tone = NoteTone.Plain)
        }

        if (hasBlocks) {
            // ---- Who is in it --------------------------------------------------------------
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle(if (stats.size == 1) "1 speaker" else "${stats.size} speakers")
                stats.forEachIndexed { index, stat ->
                    SpeakerStatRow(stat, accent = speakerAccent(index))
                }
            }

            // ---- The reference, folded --------------------------------------------------------
            //
            // Offered on a finished transcript, not only before a run, because scoring reads the
            // blocks already stored: a reference attached now scores this recording immediately,
            // without spending the minutes of two models to produce the same transcript again.
            ReferenceSummary(
                recording = recording,
                expanded = editingReference,
                onToggle = { editingReference = !editingReference },
                onOpenReport = onOpenReport,
            )
            AnimatedVisibility(visible = editingReference) {
                ReferenceEditor(
                    reference = recording.referenceText.orEmpty(),
                    language = recording.language,
                    title = if (recording.referenceText == null) {
                        "Score this against a reference"
                    } else {
                        "Reference transcript"
                    },
                    onPickReference = onPickReference,
                    onReference = onReference,
                    onLanguage = onLanguage,
                )
            }

            // ---- What the score is worth ---------------------------------------------------
            //
            // Both notes qualify the numbers on the row above, so they sit with the reference
            // that produced them rather than wherever there was room.
            if (recording.werPercent != null && recording.isTruncated) {
                Note(TRUNCATION_NOTE, tone = NoteTone.Alert)
            }
            if (recording.referenceText != null && recording.speakerAccuracyPercent == null) {
                Note(
                    "The reference names no speakers, so only the words are scored. Mark each turn " +
                        "with a bracketed label — “[S1] …” — to score attribution too.",
                    tone = NoteTone.Plain,
                )
            }

            // ---- What follows ----------------------------------------------------------------
            SectionTitle(if (turnCount == 1) "Transcript · 1 turn" else "Transcript · $turnCount turns")
        }
    }
}

/**
 * The attached reference in one line -- what it is, how big, which numeral grammar -- with the
 * editor behind a tap.
 *
 * A clickable surface rather than a button with a label, so the whole line is the target and it
 * reads as a thing with a state rather than a command. The report link lives here because the
 * report is *about* the reference: a comparison with nothing to compare against is not a screen
 * worth a button.
 */
@Composable
private fun ReferenceSummary(
    recording: DiarizedRecording,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpenReport: () -> Unit,
) {
    val reference = recording.referenceText
    // Counted once per reference, not per recomposition: a 3,000-word reference split on every
    // frame of the chevron's rotation is exactly the kind of work an animation exposes.
    val words = remember(reference) {
        reference?.split(Regex("\\s+"))?.count { it.isNotBlank() } ?: 0
    }
    val turn by animateFloatAsState(targetValue = if (expanded) 180f else 0f, label = "reference")

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
    ) {
        Row(
            Modifier.padding(start = 12.dp, top = 8.dp, end = 4.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Description,
                contentDescription = null,
                Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    if (reference == null) "Score against a reference" else "Reference transcript",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    if (reference == null) {
                        "Attach what was actually said to get a word error rate and speaker accuracy."
                    } else {
                        "%,d words · %s numerals".format(
                            words,
                            if (recording.language == "de") "German" else "English",
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (reference != null) {
                // The itemised comparison opens on its own screen. It used to be a card right
                // here, between the reference editor and the turns, and on a real transcript that
                // card is long -- four tiles, a speaker table, the misattributed stretches and
                // hundreds of word errors -- so the turns it was meant to explain sat below the
                // fold. See [SpeakerReportScreen].
                TextButton(onClick = onOpenReport) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Report")
                }
            }
            Icon(
                Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Hide the reference editor" else "Edit the reference",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(end = 8.dp)
                    .rotate(turn),
            )
        }
    }
}

/** How a note should read: a fact, a caution, or a live-session caveat. */
private enum class NoteTone { Plain, Alert, Live }

/**
 * A one-line qualifier. Four of these used to be scattered through the header as bare `Text`s in
 * three different colours; one component means they at least look like the same kind of thing.
 */
@Composable
private fun Note(text: String, tone: NoteTone) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = when (tone) {
            NoteTone.Plain -> MaterialTheme.colorScheme.onSurfaceVariant
            NoteTone.Alert -> MaterialTheme.colorScheme.error
            NoteTone.Live -> MaterialTheme.colorScheme.tertiary
        },
    )
}

/**
 * One line of the summary: who, how much, and whether the app actually knows them.
 *
 * The enrolled/unenrolled difference is carried by more than the name, because the name alone is
 * easy to skim past -- and the two mean very different things to someone acting on the transcript.
 * A named speaker is a claim about a person; "Unknown Speaker 2" is a claim only that this was a
 * distinct voice.
 *
 * The bar is the share of talk time, drawn in the speaker's accent so it reads as the same person
 * the tinted turns below belong to. A percentage alone has to be read; two bars of different
 * lengths are compared before they are read, and "who did most of the talking" is usually the
 * first question a summary like this is asked.
 */
@Composable
private fun SpeakerStatRow(stat: SpeakerStat, accent: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The swatch, not a tint on the icon below it: the icon's colour already carries
            // whether the app knows this person, and overloading it with identity would cost that
            // distinction the one channel that makes it visible at a glance.
            Box(
                Modifier
                    .size(10.dp)
                    .background(accent, CircleShape),
            )
            Icon(
                if (stat.enrolled) Icons.Default.Person else Icons.Default.PersonOutline,
                contentDescription = if (stat.enrolled) "Enrolled voice" else "Unenrolled voice",
                Modifier.size(18.dp),
                tint = if (stat.enrolled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                stat.name,
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (stat.enrolled) FontWeight.Medium else FontWeight.Normal,
                color = if (stat.enrolled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                "${(stat.share * 100).toInt()}% · ${formatClock(stat.speakingMillis)} · " +
                    if (stat.turns == 1) "1 turn" else "${stat.turns} turns",
                style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LinearProgressIndicator(
            progress = { stat.share.coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp)
                .height(4.dp),
            color = accent,
            trackColor = accent.copy(alpha = 0.15f),
        )
    }
}

/** The label alignment gives words that fell in no turn -- not a person, and never shown as one. */
private fun isUnattributed(name: String): Boolean = name == SpeakerRepository.UNATTRIBUTED_NAME

/**
 * Words nobody was attributed: shown, but not as a speaker.
 *
 * These used to be rendered as a full turn under the header "Unknown Speaker ?", the same way as a
 * real person's turn, and a long recording had over a hundred of them -- one for every hand-over
 * where the recogniser heard a word between two turns. Most of those are now given to the nearer
 * speaker by alignment; the few that remain are genuinely unplaced, and the honest presentation is
 * text without a name: quiet, indented, unaccented, so the eye reads past it to the next real turn
 * instead of stopping at a header for nobody. The words are still there -- hiding them would make
 * the transcript claim less was said than was heard.
 */
@Composable
private fun UnattributedRow(turn: DialogTurn) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 15.dp, end = 12.dp, top = 2.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            formatClock(turn.startSample * 1000L / AudioRecorder.SAMPLE_RATE),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
        Text(
            turn.text,
            style = MaterialTheme.typography.bodySmall,
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One turn of the conversation, as a block the eye can take in whole.
 *
 * This was a small coloured name over a paragraph, every turn at the same indent in the same column,
 * and a long conversation read as a wall: nothing marked where one person stopped and the next
 * started, so following a single speaker meant reading every line to find out whether it was theirs.
 * The tint and the rail give a turn edges, and the accent repeats down the page, so a speaker can be
 * followed -- or skipped past -- without reading a word of them.
 *
 * The tint is faint deliberately. It is there to bound the turn, and eight saturated bands down a
 * twenty-minute transcript would be worse to read than the wall they replaced.
 */
@Composable
private fun DialogTurnRow(turn: DialogTurn, accent: Color) {
    Surface(
        color = accent.copy(alpha = 0.08f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            // Full height rather than a bullet beside the name: the rail is what says where a turn
            // ends, which the tint alone cannot when the next speaker's tint is a neighbouring hue.
            Box(
                Modifier
                    .fillMaxHeight()
                    .width(3.dp)
                    .background(accent),
            )
            Column(
                Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        turn.speakerName,
                        style = MaterialTheme.typography.labelLarge,
                        color = accent,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        formatClock(turn.startSample * 1000L / AudioRecorder.SAMPLE_RATE),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    turn.text,
                    style = MaterialTheme.typography.bodyMedium,
                    // Looser than the default, which is set for labels and captions. A turn is a
                    // paragraph of speech and is read as one.
                    lineHeight = MaterialTheme.typography.bodyMedium.fontSize * 1.45,
                )
            }
        }
    }
}

/** One predicate, so the row and the detail pane cannot disagree about what "truncated" means. */
private val DiarizedRecording.isTruncated: Boolean
    get() = (coveragePercent ?: 100.0) < Wer.TRUNCATED_COVERAGE

private val TRUNCATION_NOTE =
    "Under %.0f%% coverage the transcript is truncated — the error rate below that is measuring "
        .format(Wer.TRUNCATED_COVERAGE) + "what is missing rather than what was heard."

internal fun formatClock(millis: Long): String {
    val seconds = (millis / 1000).coerceAtLeast(0)
    val hours = seconds / 3600
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, (seconds % 3600) / 60, seconds % 60)
    } else {
        "%d:%02d".format(seconds / 60, seconds % 60)
    }
}
