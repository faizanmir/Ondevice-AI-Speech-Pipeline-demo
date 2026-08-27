package com.example.aiagenttestapp.ui.speakers

import android.Manifest
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.aiagenttestapp.data.speakers.SpeakerRecord
import com.example.aiagenttestapp.data.speakers.SpeakerRepository
import com.example.aiagenttestapp.data.speakers.TakeAnalysis

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeakersScreen(
    viewModel: SpeakersViewModel,
    onOpenModels: () -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Which take the permission prompt was launched for, so the recording starts on the right one.
    var pendingTake by remember { mutableStateOf<Int?>(null) }
    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val index = pendingTake
        pendingTake = null
        if (granted && index != null) viewModel.onIntent(SpeakersIntent.StartTake(index))
    }

    // Which take the file picker was opened for. Same pattern as the microphone above, and for the
    // same reason: the result arrives long after the button that asked for it.
    var importingTake by remember { mutableStateOf<Int?>(null) }
    val pickTake = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        val index = importingTake
        importingTake = null
        if (uri != null && index != null) {
            viewModel.onIntent(SpeakersIntent.ImportTake(index, uri))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Speakers", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            if (state.available && !state.isEnrolling) {
                ExtendedFloatingActionButton(
                    onClick = { viewModel.onIntent(SpeakersIntent.BeginEnroll) },
                    icon = { Icon(Icons.Default.PersonAdd, contentDescription = null) },
                    text = { Text("Add person") },
                )
            }
        },
    ) { padding ->
        if (!state.available) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        "Speaker identification needs its models downloaded first.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = onOpenModels) { Text("Manage models") }
                }
            }
            return@Scaffold
        }

        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.isEnrolling) {
                item {
                    EnrollCard(
                        state = state,
                        onNameChange = { viewModel.onIntent(SpeakersIntent.NameChanged(it)) },
                        onStartTake = { index ->
                            pendingTake = index
                            micPermission.launch(Manifest.permission.RECORD_AUDIO)
                        },
                        onStopTake = { viewModel.onIntent(SpeakersIntent.StopTake) },
                        onImportTake = { index ->
                            importingTake = index
                            // Anything the two decoders can read; a wrong pick is rejected on
                            // inspection rather than filtered out of the picker.
                            pickTake.launch(arrayOf("audio/*"))
                        },
                        onFinish = { viewModel.onIntent(SpeakersIntent.Finish) },
                        onCancel = { viewModel.onIntent(SpeakersIntent.CancelEnroll) },
                    )
                }
            }

            if (state.stale.isNotEmpty()) {
                item { StaleWarning(state.stale) }
            }

            if (state.speakers.isEmpty() && !state.isEnrolling) {
                item {
                    Text(
                        "Nobody enrolled yet. Add the people who appear in your recordings and the " +
                            "app will label them in transcripts. Only a voiceprint is stored — never " +
                            "the recording it came from.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            items(state.speakers, key = { it.id }) { speaker ->
                SpeakerRow(
                    speaker = speaker,
                    isStale = state.stale.any { it.id == speaker.id },
                    onDelete = { viewModel.onIntent(SpeakersIntent.Delete(speaker.id)) },
                )
            }
        }
    }

    state.soundsLike?.let { name ->
        AlertDialog(
            onDismissRequest = { viewModel.onIntent(SpeakersIntent.DismissSoundsLike) },
            title = { Text("This sounds like $name") },
            text = {
                Text(
                    "The app already has a voice this close to it. Enrolling the same person twice " +
                        "makes both entries less reliable — but if these really are two different " +
                        "people, go ahead.",
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.onIntent(SpeakersIntent.ConfirmSoundsLike) }) {
                    Text("Enrol anyway")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.onIntent(SpeakersIntent.DismissSoundsLike) }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun EnrollCard(
    state: SpeakersUiState,
    onNameChange: (String) -> Unit,
    onStartTake: (Int) -> Unit,
    onStopTake: () -> Unit,
    onImportTake: (Int) -> Unit,
    onFinish: () -> Unit,
    onCancel: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Add a person",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            OutlinedTextField(
                value = state.name,
                onValueChange = onNameChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Name") },
                placeholder = { Text("As it should appear in transcripts") },
                singleLine = true,
                enabled = !state.isRecording && !state.isSaving,
            )

            Text(
                "Read each sentence out loud. Three separate takes let the app tell a real voice " +
                    "from a noisy recording.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SpeakersViewModel.PROMPTS.forEachIndexed { index, prompt ->
                TakeRow(
                    index = index,
                    prompt = prompt,
                    analysis = state.takes.getOrNull(index),
                    isRecording = state.isRecording && state.recordingTake == index,
                    isBusy = state.isRecording || state.isAnalysing || state.isSaving,
                    recordingMillis = state.recordingMillis,
                    level = state.level,
                    onStart = { onStartTake(index) },
                    onStop = onStopTake,
                    onImport = { onImportTake(index) },
                )
            }

            if (state.isAnalysing) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(18.dp))
                    Text("Checking the recording…", style = MaterialTheme.typography.bodySmall)
                }
            }

            state.error?.let { error ->
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Text(
                "${state.usableTakes} of ${SpeakerRepository.REQUIRED_TAKES} takes usable",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f),
                    enabled = !state.isSaving,
                ) {
                    Text("Cancel")
                }
                Button(
                    onClick = onFinish,
                    modifier = Modifier.weight(1f),
                    enabled = state.canFinish,
                ) {
                    Text(if (state.isSaving) "Saving…" else "Enrol")
                }
            }
        }
    }
}

@Composable
private fun TakeRow(
    index: Int,
    prompt: String,
    analysis: TakeAnalysis?,
    isRecording: Boolean,
    isBusy: Boolean,
    recordingMillis: Long,
    level: Float,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onImport: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Take ${index + 1}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    "\"$prompt\"",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.width(8.dp))

            when {
                isRecording -> IconButton(onClick = onStop) {
                    Icon(
                        Icons.Default.Stop,
                        contentDescription = "Stop",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }

                else -> {
                    // Import sits beside Record rather than replacing it: reading aloud is still the
                    // normal way to enrol, and a file is the exception for voices that already exist
                    // as audio.
                    TextButton(onClick = onImport, enabled = !isBusy) { Text("Import") }
                    IconButton(onClick = onStart, enabled = !isBusy) {
                        Icon(
                            Icons.Default.Mic,
                            contentDescription = if (analysis == null) "Record" else "Record again",
                        )
                    }
                }
            }
        }

        if (isRecording) {
            LinearProgressIndicator(
                progress = { level.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "%d:%02d".format(recordingMillis / 60000, (recordingMillis / 1000) % 60),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        analysis?.let { take ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = if (take.isUsable) {
                        Icons.Default.CheckCircle
                    } else {
                        Icons.Default.ErrorOutline
                    },
                    contentDescription = null,
                    tint = if (take.isUsable) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = if (take.isUsable) {
                        "%.1f s of speech".format(take.speechSeconds)
                    } else {
                        "Not usable — record again"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StaleWarning(stale: List<SpeakerRecord>) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "Re-enrolment needed",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                // Said plainly, because the symptom on its own looks like the feature is broken.
                "${stale.joinToString { it.name }} were enrolled with an older voice model, so they " +
                    "will not be recognised. Delete and add them again.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun SpeakerRow(speaker: SpeakerRecord, isStale: Boolean, onDelete: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    speaker.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (isStale) "Enrolled with an older model" else "Voiceprint stored",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isStale) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete ${speaker.name}")
            }
        }
    }
}
