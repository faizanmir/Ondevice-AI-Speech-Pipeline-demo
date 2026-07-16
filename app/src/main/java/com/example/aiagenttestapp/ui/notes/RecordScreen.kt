package com.example.aiagenttestapp.ui.notes

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.aiagenttestapp.stt.SpeechModelState
import com.example.aiagenttestapp.ui.components.formatBytes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordScreen(
    viewModel: RecordViewModel,
    onSaved: () -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.startRecording()
    }

    LaunchedEffect(state.savedNoteId) {
        if (state.savedNoteId != null) onSaved()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = when (state.stage) {
                            RecordStage.Capture -> "New voice note"
                            RecordStage.ReviewTranscript -> "Check the transcript"
                            RecordStage.ReviewSummary -> "Check the summary"
                        },
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            state.error?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            when (state.stage) {
                RecordStage.Capture -> CaptureStage(
                    state = state,
                    onDownloadModel = viewModel::downloadSpeechModel,
                    onStart = { micPermission.launch(Manifest.permission.RECORD_AUDIO) },
                    onStop = viewModel::stopRecording,
                )

                RecordStage.ReviewTranscript -> TranscriptStage(
                    state = state,
                    onTranscriptChange = viewModel::onTranscriptChange,
                    onSummarise = viewModel::summarise,
                    onDiscard = viewModel::discard,
                )

                RecordStage.ReviewSummary -> SummaryStage(
                    state = state,
                    onTitleChange = viewModel::onTitleChange,
                    onSummaryChange = viewModel::onSummaryChange,
                    onStopSummarising = viewModel::stopSummarising,
                    onBackToTranscript = viewModel::backToTranscript,
                    onSave = viewModel::save,
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.CaptureStage(
    state: RecordUiState,
    onDownloadModel: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    // The speech model is 240 MB and is not in the APK. Say so plainly, once, before the user taps
    // a record button that could not possibly work.
    when (val model = state.speechModelState) {
        is SpeechModelState.NotDownloaded -> {
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
                        "Speech recognition model",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "Transcription runs on your phone, so the speech model has to be " +
                            "downloaded once. It recognises English, Chinese, Japanese, Korean " +
                            "and Cantonese, and it works offline afterwards.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = onDownloadModel, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Download, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Download ${formatBytes(state.speechModelSizeBytes)}")
                    }
                }
            }
            return
        }

        is SpeechModelState.Downloading -> {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Downloading the speech model", style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(
                    progress = { model.progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "${(model.progress * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return
        }

        is SpeechModelState.Failed -> {
            Text(model.message, color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = onDownloadModel) { Text("Try again") }
            return
        }

        is SpeechModelState.Ready -> Unit
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            if (state.isTranscribing) {
                CircularProgressIndicator()
                Text("Transcribing", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "This runs on your phone and takes a moment.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                MicButton(
                    isRecording = state.isRecording,
                    level = state.level,
                    onClick = { if (state.isRecording) onStop() else onStart() },
                )

                Text(
                    text = if (state.isRecording) {
                        formatDuration(state.durationMillis)
                    } else {
                        "Tap to record"
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Medium,
                )

                if (state.isRecording) {
                    Text(
                        "Tap again to stop, or say a command like \"stop recording\" or " +
                            "\"open settings\".",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }

                // The chip that proves the app heard a command. It matters because a spoken command
                // has no button press to confirm it landed -- without this the user cannot tell a
                // command that fired from one that was never recognised.
                state.lastCommandLabel?.let { label ->
                    Surface(
                        shape = RoundedCornerShape(percent = 50),
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                    ) {
                        Row(
                            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                Icons.Default.Bolt,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                "Heard \"$label\"",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The record button. The ring around it tracks loudness, so the user can see they are being heard. */
@Composable
private fun MicButton(isRecording: Boolean, level: Float, onClick: () -> Unit) {
    val scale by animateFloatAsState(
        targetValue = if (isRecording) 1f + level * 0.35f else 1f,
        label = "mic-level",
    )

    Box(contentAlignment = Alignment.Center) {
        if (isRecording) {
            Box(
                Modifier
                    .size(140.dp)
                    .scale(scale)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.18f)),
            )
        }

        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(
                    if (isRecording) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                ),
        ) {
            Icon(
                imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                contentDescription = if (isRecording) "Stop recording" else "Start recording",
                tint = if (isRecording) MaterialTheme.colorScheme.onError
                else MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(40.dp),
            )
        }
    }
}

@Composable
private fun ColumnScope.TranscriptStage(
    state: RecordUiState,
    onTranscriptChange: (String) -> Unit,
    onSummarise: () -> Unit,
    onDiscard: () -> Unit,
) {
    Text(
        "Speech recognition makes mistakes. Fix anything it got wrong before summarising — the " +
            "summary is only as good as this text.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    OutlinedTextField(
        value = state.transcript,
        onValueChange = onTranscriptChange,
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
        label = { Text("Transcript") },
    )

    state.summariser?.let { model ->
        Text(
            "Will be summarised by ${model.name}, on this phone.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } ?: Text(
        "No language model is downloaded, so this cannot be summarised yet. Download one from " +
            "the Models screen.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onDiscard, modifier = Modifier.weight(1f)) {
            Text("Discard")
        }
        Button(
            onClick = onSummarise,
            enabled = state.canSummarise,
            modifier = Modifier.weight(1f),
        ) {
            Icon(Icons.Default.AutoAwesome, contentDescription = null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Summarise")
        }
    }
}

@Composable
private fun ColumnScope.SummaryStage(
    state: RecordUiState,
    onTitleChange: (String) -> Unit,
    onSummaryChange: (String) -> Unit,
    onStopSummarising: () -> Unit,
    onBackToTranscript: () -> Unit,
    onSave: () -> Unit,
) {
    Column(
        Modifier
            .weight(1f)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = state.title,
            onValueChange = onTitleChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Title") },
            singleLine = true,
            enabled = !state.isSummarising,
        )

        OutlinedTextField(
            value = state.summary,
            onValueChange = onSummaryChange,
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp),
            label = { Text("Summary") },
            // Editable the moment generation finishes -- and the model's output is a draft, not a
            // verdict. Locked only while the tokens are still arriving.
            enabled = !state.isSummarising,
        )

        if (state.isSummarising) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(
                    "${state.summariser?.name} is summarising",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onStopSummarising) { Text("Stop") }
            }
        } else {
            Text(
                "Check it before saving. On-device models are small and do sometimes get things " +
                    "wrong — the full transcript is saved alongside, so nothing is lost.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = onBackToTranscript,
            modifier = Modifier.weight(1f),
        ) {
            Text("Back")
        }
        Button(
            onClick = onSave,
            enabled = !state.isSummarising && state.transcript.isNotBlank(),
            modifier = Modifier.weight(1f),
        ) {
            Icon(Icons.Default.Save, contentDescription = null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Save")
        }
    }
}

private fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
