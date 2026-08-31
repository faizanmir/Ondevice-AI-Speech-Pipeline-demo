package com.example.aiagenttestapp.ui.speakers

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Which transcript is open, by id rather than by the recording: a row updates all the way
    // through a run, and holding the object would freeze the text on the snapshot it was opened at.
    var expandedId by rememberSaveable { mutableStateOf<Long?>(null) }

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
        },
    ) { padding ->
        ControlsContentPanes(
            modifier = Modifier.padding(padding),
            controls = {
                SourceCard(
                    state = state,
                    onImport = { picker.launch(arrayOf("audio/*")) },
                    onStart = { viewModel.onIntent(DiarizeIntent.StartRecording) },
                    onStop = { viewModel.onIntent(DiarizeIntent.StopRecording) },
                    onLiveCapture = { viewModel.onIntent(DiarizeIntent.SetLiveCapture(it)) },
                    onExpected = { viewModel.onIntent(DiarizeIntent.SetExpectedSpeakers(it)) },
                    onChunkMinutes = { viewModel.onIntent(DiarizeIntent.SetChunkMinutes(it)) },
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
                        speakerCount = blocks.map { it.speakerName }.distinct().count { !isUnattributed(it) },
                        selected = recording.id == expandedId,
                        onOpen = { expandedId = if (expandedId == recording.id) null else recording.id },
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
                            onOpenReport = { onOpenReport(recording.id) },
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
        }
    }
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

@Composable
private fun RecordingRow(
    recording: DiarizedRecording,
    speakerCount: Int,
    selected: Boolean,
    onOpen: () -> Unit,
    onStop: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        onClick = onOpen,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        recording.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        buildString {
                            append(formatClock(recording.durationMillis))
                            append(
                                if (recording.expectedSpeakers > 0) {
                                    " · ${recording.expectedSpeakers} expected"
                                } else {
                                    " · count worked out"
                                },
                            )
                            if (speakerCount > 0) append(" · $speakerCount found")
                            // Phrased and formatted unlike the recording's own length, which sits
                            // three words to its left. Two bare clock times on one line invite the
                            // reader to compare them as if they measured the same thing.
                            recording.runMillis?.let { append(" · took ${formatDuration(it)}") }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Stop lives on the row, where the progress bar is, so a run can be halted without
                // opening the transcript -- and so the trash icon is no longer the only control in
                // reach while a row is busy, which is how a live session was deleted mid-run once.
                if (recording.status == DiarizedStatus.Running || recording.status == DiarizedStatus.Live) {
                    IconButton(onClick = onStop) {
                        Icon(Icons.Default.Stop, contentDescription = "Stop")
                    }
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete")
                }
            }

            PhaseLine(recording)

            ScoreLine(recording)

            // Progress and failure belong on the row, not only in the detail: a run takes minutes
            // and the whole point of the list is to see at a glance which ones are still going.
            when (recording.status) {
                // Imported and waiting on whatever the blocker at the top of the list says. A run
                // starts itself otherwise, so this is only ever seen when something is missing.
                DiarizedStatus.Idle -> Text(
                    "Not run yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                DiarizedStatus.Running ->
                    if (recording.progress > 0f) {
                        LinearProgressIndicator(
                            progress = { recording.progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }

                DiarizedStatus.Failed -> Text(
                    recording.error ?: "Failed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )

                DiarizedStatus.Live -> {
                    if (recording.progress > 0f) {
                        LinearProgressIndicator(
                            progress = { recording.progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Text(
                        "Live · provisional labels",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }

                DiarizedStatus.Stopped -> Text(
                    "Stopped — Run or Play as live starts it over.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                DiarizedStatus.Done -> Unit
            }
        }
    }
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
 * The heading above an opened transcript: what it is, and how to run it again.
 *
 * Separate from the turns rather than wrapping them, because the turns are emitted as their own
 * items in the content list. Wrapping them in a scroller of their own would put a list inside a
 * list, which is exactly the overlap this screen's layout was changed to remove.
 */
@Composable
private fun TranscriptHeader(
    recording: DiarizedRecording,
    stats: List<SpeakerStat>,
    onOpenReport: () -> Unit,
    onRun: () -> Unit,
    onStop: () -> Unit,
    onPlayLive: () -> Unit,
    onPickReference: () -> Unit,
    onReference: (String) -> Unit,
    onLanguage: (String) -> Unit,
) {
    val hasBlocks = stats.isNotEmpty()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Offered even while the row says Running, which looks wrong and is the safety valve: a run
        // whose worker died leaves the row claiming to be in progress forever, and hiding the only
        // button that restarts it makes the state unrecoverable without deleting the recording.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onRun) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                // "Run again" once there is a transcript: re-running at a different speaker count,
                // or after enrolling whoever came back as "Speaker 2", is the normal thing here.
                Text(if (hasBlocks) "Run again" else "Run")
            }
            // The same recording fed at the speed it was spoken, with speakers and words appearing
            // as it goes and the ordinary run replacing them at the end. A demonstration of the live
            // path on audio whose right answer is already known, and the way to measure its latency.
            OutlinedButton(onClick = onPlayLive, enabled = recording.status != DiarizedStatus.Live) {
                Text(if (recording.status == DiarizedStatus.Live) "Playing live…" else "Play as live")
            }
            if (recording.status == DiarizedStatus.Running || recording.status == DiarizedStatus.Live) {
                OutlinedButton(onClick = onStop) {
                    Icon(Icons.Default.Stop, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Stop")
                }
            }
        }
        if (recording.status == DiarizedStatus.Live) {
            Text(
                "Live — these speakers and words are provisional. The final transcript replaces " +
                    "them when the audio ends.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        if (!hasBlocks && recording.status == DiarizedStatus.Done) {
            Text(
                "No speech was attributed in this recording.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (hasBlocks) {
            Text(
                if (stats.size == 1) "1 speaker" else "${stats.size} speakers",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            stats.forEachIndexed { index, stat ->
                SpeakerStatRow(stat, accent = speakerAccent(index))
            }

            // Offered on a finished transcript, not only before a run, because scoring reads the
            // blocks already stored: a reference attached now scores this recording immediately,
            // without spending the minutes of two models to produce the same transcript again.
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
            // The itemised comparison opens on its own screen. It used to be a card right here,
            // between the reference editor and the turns, and on a real transcript that card is
            // long -- four tiles, a speaker table, the misattributed stretches and hundreds of word
            // errors -- so the turns it was meant to explain sat below the fold and every look at
            // the transcript meant scrolling past the report first. See [SpeakerReportScreen].
            if (recording.referenceText != null) {
                OutlinedButton(onClick = onOpenReport) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Open comparison report")
                }
            }

            if (recording.werPercent != null && recording.isTruncated) {
                Text(
                    TRUNCATION_NOTE,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (recording.referenceText != null && recording.speakerAccuracyPercent == null) {
                Text(
                    "The reference names no speakers, so only the words are scored. Mark each turn " +
                        "with a bracketed label — “[S1] …” — to score attribution too.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * One line of the summary: who, how much, and whether the app actually knows them.
 *
 * The enrolled/unenrolled difference is carried by more than the name, because the name alone is
 * easy to skim past -- and the two mean very different things to someone acting on the transcript.
 * A named speaker is a claim about a person; "Unknown Speaker 2" is a claim only that this was a
 * distinct voice.
 */
@Composable
private fun SpeakerStatRow(stat: SpeakerStat, accent: Color) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The swatch, not a tint on the icon below it: the icon's colour already carries whether
        // the app knows this person, and overloading it with identity would cost that distinction
        // the one channel that makes it visible at a glance.
        Box(
            Modifier
                .size(10.dp)
                .background(accent, CircleShape),
        )
        Icon(
            if (stat.enrolled) Icons.Default.Person else Icons.Default.PersonOutline,
            contentDescription = null,
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
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The label alignment gives words that fell in no turn -- not a person, and never shown as one. */
private fun isUnattributed(name: String): Boolean =
    name == "${SpeakerRepository.UNKNOWN_SPEAKER_PREFIX} ?"

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
