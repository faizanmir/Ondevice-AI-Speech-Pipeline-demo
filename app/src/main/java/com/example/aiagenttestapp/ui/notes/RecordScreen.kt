package com.example.aiagenttestapp.ui.notes

import android.Manifest
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Stop
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.aiagenttestapp.functions.MarkerKind
import com.example.aiagenttestapp.stt.SpeechModelState
import com.example.aiagenttestapp.ui.components.formatBytes
import java.util.Locale

/** Utterance ids for [TextReader], one per readable block on screen. */
private const val READ_TRANSCRIPT = "transcript"
private const val READ_SUMMARY = "summary"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordScreen(
    viewModel: RecordViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val reader = rememberTextReader()

    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.onIntent(RecordIntent.StartRecording)
    }

    // Note: "saved" and "a spoken command wants to navigate" are both RecordEffects, and both are
    // collected by the nav host -- effects go to exactly one collector, so this screen must not also
    // subscribe or the two would race for them.

    // Whatever was being read aloud belongs to the block that was on screen. When the stage changes
    // that block is gone, so stop rather than let a summary keep reading over the transcript view.
    LaunchedEffect(state.stage) { reader.stop() }

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
                    IconButton(onClick = { reader.stop(); onBack() }) {
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

            // The three stages are one screen changing state, so they slide into each other rather
            // than cutting: forward (record -> transcript -> summary) enters from the right, going
            // back (discard, undo) from the left.
            AnimatedContent(
                targetState = state.stage,
                transitionSpec = {
                    val sign = if (targetState.ordinal >= initialState.ordinal) 1 else -1
                    (slideInHorizontally(tween(300)) { full -> sign * full } + fadeIn(tween(300)))
                        .togetherWith(
                            slideOutHorizontally(tween(300)) { full -> -sign * full } +
                                fadeOut(tween(300)),
                        )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                label = "record-stage",
            ) { stage ->
                when (stage) {
                    RecordStage.Capture -> CaptureStage(
                        state = state,
                        onDownloadModel = { viewModel.onIntent(RecordIntent.DownloadSpeechModel) },
                        onStart = { micPermission.launch(Manifest.permission.RECORD_AUDIO) },
                        onStop = { viewModel.onIntent(RecordIntent.StopRecording) },
                        onExpectedSpeakersChange = {
                            viewModel.onIntent(RecordIntent.ExpectedSpeakersChanged(it))
                        },
                    )

                    RecordStage.ReviewTranscript -> TranscriptStage(
                        state = state,
                        reader = reader,
                        onTranscriptChange = { viewModel.onIntent(RecordIntent.TranscriptChanged(it)) },
                        onSummarise = { viewModel.onIntent(RecordIntent.Summarise) },
                        onDiscard = { viewModel.onIntent(RecordIntent.Discard) },
                    )

                    RecordStage.ReviewSummary -> SummaryStage(
                        state = state,
                        reader = reader,
                        onTitleChange = { viewModel.onIntent(RecordIntent.TitleChanged(it)) },
                        onSummaryChange = { viewModel.onIntent(RecordIntent.SummaryChanged(it)) },
                        onStopSummarising = { viewModel.onIntent(RecordIntent.StopSummarising) },
                        onUndo = { viewModel.onIntent(RecordIntent.BackToTranscript) },
                        onSave = { viewModel.onIntent(RecordIntent.Save) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CaptureStage(
    state: RecordUiState,
    onDownloadModel: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onExpectedSpeakersChange: (Int) -> Unit,
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
                TranscribingIndicator(
                    progress = state.transcriptionProgress,
                    partial = state.partialTranscript,
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

                if (!state.isRecording && state.speakerIdActive) {
                    SpeakerCountPicker(
                        selected = state.expectedSpeakers,
                        onSelect = onExpectedSpeakersChange,
                    )
                }

                if (state.isRecording) {
                    Text(
                        text = if (state.keywordsActive) {
                            "Tap again to stop, or say \"stop recording\". Say \"start non " +
                                "conformity\" or \"start action item\" to tag what comes next, and " +
                                "\"end\" it when you are done."
                        } else {
                            "Tap again to stop, or say a command like \"stop recording\" or " +
                                "\"open settings\"."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )

                    MarkerStatus(
                        openMarkers = state.openMarkers,
                        counts = state.markerCounts,
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

/**
 * How many people are in this recording.
 *
 * Worth asking rather than always inferring. Fixing the number of speakers is a far stronger instruction
 * to the clustering than a similarity threshold, which has to guess how many groups exist and on short or
 * noisy audio habitually splits one person into two. "Auto" stays the default because the user does not
 * always know -- but when they do, they should be able to say so.
 */
@Composable
private fun SpeakerCountPicker(selected: Int, onSelect: (Int) -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "How many people?",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0, 1, 2, 3, 4).forEach { count ->
                FilterChip(
                    selected = selected == count,
                    onClick = { onSelect(count) },
                    label = { Text(if (count == 0) "Auto" else count.toString()) },
                )
            }
        }
    }
}

/**
 * Live state of the spoken tags: what is open right now, and what has been completed.
 *
 * A spoken marker has no button press to confirm it landed. Without this the user cannot tell an open
 * non-conformity from a phrase that was never heard, and would only discover the difference once the
 * recording was over and the tag was missing -- by which point the walkthrough cannot be repeated.
 * The open pill deliberately looks live (error colour, "recording…") and the counts read as settled.
 */
@Composable
private fun MarkerStatus(
    openMarkers: Set<MarkerKind>,
    counts: Map<MarkerKind, Int>,
) {
    if (openMarkers.isEmpty() && counts.isEmpty()) return

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        openMarkers.forEach { kind ->
            Surface(
                shape = RoundedCornerShape(percent = 50),
                color = MaterialTheme.colorScheme.errorContainer,
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        Icons.Default.Label,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        "${markerLabel(kind)} — recording…",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }

        val completed = counts.entries.filter { it.value > 0 }
        if (completed.isNotEmpty()) {
            Text(
                completed.joinToString(" · ") { (kind, count) ->
                    "$count ${markerLabel(kind).lowercase()}${if (count == 1) "" else "s"}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun markerLabel(kind: MarkerKind): String = when (kind) {
    MarkerKind.NonConformity -> "Non-conformity"
    MarkerKind.Action -> "Action"
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

/**
 * Shown while the recording is being transcribed. The pulsing dots and the growing text are the
 * "still receiving data" signal: a long note is transcribed in segments, and a bare spinner over a
 * minute of silence is indistinguishable from a hang.
 */
@Composable
private fun TranscribingIndicator(progress: Float, partial: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        PulsingDots()

        Text("Transcribing", style = MaterialTheme.typography.bodyLarge)

        // Indeterminate until the first segment lands, then a real bar as the segments complete.
        if (progress > 0f) {
            val animatedProgress by animateFloatAsState(
                targetValue = progress,
                label = "transcribe-progress",
            )
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        if (partial.isBlank()) {
            Text(
                "This runs on your phone and takes a moment.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        } else {
            Text(
                text = partial,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Three dots that pulse in sequence -- a lightweight "live activity" cue, reused by both AI stages. */
@Composable
private fun PulsingDots() {
    val transition = rememberInfiniteTransition(label = "pulsing")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(3) { index ->
            val alpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = 600,
                        delayMillis = index * 200,
                        easing = LinearEasing,
                    ),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot-$index",
            )
            Box(
                Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha)),
            )
        }
    }
}

@Composable
private fun TranscriptStage(
    state: RecordUiState,
    reader: TextReader,
    onTranscriptChange: (String) -> Unit,
    onSummarise: () -> Unit,
    onDiscard: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            buildString {
                append(
                    "Speech recognition makes mistakes. Fix anything it got wrong before " +
                        "summarising — the summary is only as good as this text.",
                )
                // Explained here rather than left to be guessed at: the brackets and name prefixes are
                // editable, so the user needs to know they mean something before they delete one.
                if (state.transcript.contains("[NON-CONFORMITY]") ||
                    state.transcript.contains("[ACTION]")
                ) {
                    append(
                        " Text inside [NON-CONFORMITY] or [ACTION] is what you marked out loud, and " +
                            "will be reported as a finding — you can edit or remove the brackets.",
                    )
                }
                if (state.knownSpeakers.any { state.transcript.contains("$it:") } ||
                    state.transcript.contains("Speaker ")
                ) {
                    append(" \"Name:\" at the start of a line is who the app thinks was speaking.")
                }
            },
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

        ReadAloudButton(
            isSpeaking = reader.speakingId == READ_TRANSCRIPT,
            enabled = state.transcript.isNotBlank(),
            onToggle = { reader.toggle(READ_TRANSCRIPT, state.transcript, state.detectedLanguage) },
        )

        state.summariser?.let { model ->
            Text(
                // Naming the detected language here is what makes a German summary of a German note
                // unsurprising -- and a misdetection visible while the user can still fix course.
                text = buildString {
                    append("Will be summarised")
                    languageName(state.detectedLanguage)?.let { append(" in $it") }
                    append(" by ${model.name}, on this phone.")
                },
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
}

@Composable
private fun SummaryStage(
    state: RecordUiState,
    reader: TextReader,
    onTitleChange: (String) -> Unit,
    onSummaryChange: (String) -> Unit,
    onStopSummarising: () -> Unit,
    onUndo: () -> Unit,
    onSave: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
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
                // Editable the moment generation finishes -- and the model's output is a draft, not
                // a verdict. Locked only while the tokens are still arriving.
                enabled = !state.isSummarising,
            )

            if (state.isSummarising) {
                SummarisingIndicator(
                    modelName = state.summariser?.name,
                    onStop = onStopSummarising,
                )
            } else {
                ReadAloudButton(
                    isSpeaking = reader.speakingId == READ_SUMMARY,
                    enabled = state.summary.isNotBlank(),
                    onToggle = {
                        reader.toggle(READ_SUMMARY, state.summary, state.detectedLanguage)
                    },
                )
                Text(
                    "Check it before saving. On-device models are small and do sometimes get " +
                        "things wrong — the full transcript is saved alongside, so nothing is lost.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(
            Modifier.padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // "Undo" rather than "Back": it throws the generated summary away and returns to the
            // transcript, which is what the arrow-back would otherwise ambiguously imply.
            OutlinedButton(
                onClick = onUndo,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Undo")
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
}

/** The "the model is writing the summary" state: the same pulsing dots as transcription, plus a stop. */
@Composable
private fun SummarisingIndicator(modelName: String?, onStop: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PulsingDots()
        Text(
            "${modelName ?: "The model"} is writing a summary",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onStop) { Text("Stop") }
    }
}

/** Reads the transcript or summary back with the system text-to-speech engine. */
@Composable
private fun ReadAloudButton(
    isSpeaking: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    OutlinedButton(onClick = onToggle, enabled = enabled) {
        Icon(
            imageVector = if (isSpeaking) Icons.Default.Stop else Icons.AutoMirrored.Filled.VolumeUp,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(if (isSpeaking) "Stop" else "Read aloud")
    }
}

/**
 * A [TextToSpeech] wrapper scoped to the screen. Kept in the UI layer, not the ViewModel: playback
 * is a view concern, tied to a Context and the composition's lifetime, with no state worth surviving
 * a rotation.
 */
@Stable
private class TextReader(context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var ready = false
    private var tts: TextToSpeech? = null

    /** How many queued utterance chunks are still outstanding for the current read. */
    private var pending = 0

    /** The id of the block currently being read, or null. Drives each button's play/stop state. */
    var speakingId by mutableStateOf<String?>(null)
        private set

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
        }.apply {
            setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    // A long text is spoken as several chunks; only clear once the last one is done.
                    mainHandler.post {
                        pending -= 1
                        if (pending <= 0) speakingId = null
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    mainHandler.post { pending = 0; speakingId = null }
                }
            })
        }
    }

    /** Starts reading [text], or stops if [id] is already being read (so the button toggles). */
    fun toggle(id: String, text: String, language: String?) {
        if (speakingId == id) stop() else start(id, text, language)
    }

    private fun start(id: String, text: String, language: String?) {
        val engine = tts ?: return
        if (!ready || text.isBlank()) return

        language?.let { code ->
            val locale = Locale.forLanguageTag(code)
            if (engine.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) {
                runCatching { engine.language = locale }
            }
        }

        // speak() rejects anything past getMaxSpeechInputLength(), so a long transcript has to be
        // split; the first chunk flushes whatever was playing, the rest queue behind it.
        val chunks = chunkForSpeech(text)
        pending = chunks.size
        speakingId = id
        chunks.forEachIndexed { index, chunk ->
            val mode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            engine.speak(chunk, mode, null, "$id#$index")
        }
    }

    fun stop() {
        runCatching { tts?.stop() }
        pending = 0
        speakingId = null
    }

    fun shutdown() {
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
        pending = 0
        speakingId = null
    }

    private fun chunkForSpeech(text: String): List<String> {
        val max = TextToSpeech.getMaxSpeechInputLength() - 16
        if (text.length <= max) return listOf(text)

        val chunks = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            var end = minOf(start + max, text.length)
            if (end < text.length) {
                // Back up to a space so a word is not cut across two utterances.
                val lastSpace = text.lastIndexOf(' ', end - 1)
                if (lastSpace > start) end = lastSpace
            }
            chunks += text.substring(start, end)
            start = end
            while (start < text.length && text[start] == ' ') start++
        }
        return chunks
    }
}

@Composable
private fun rememberTextReader(): TextReader {
    val context = LocalContext.current
    val reader = remember { TextReader(context) }
    DisposableEffect(Unit) {
        onDispose { reader.shutdown() }
    }
    return reader
}

/** "de" -> the language's name in the device's own language, or null when unknown/undetected. */
private fun languageName(code: String?): String? = code
    ?.let { Locale(it).displayLanguage }
    ?.takeIf { it.isNotBlank() && !it.equals(code, ignoreCase = true) }

private fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
