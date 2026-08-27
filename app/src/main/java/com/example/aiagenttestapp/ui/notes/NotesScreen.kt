package com.example.aiagenttestapp.ui.notes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.aiagenttestapp.data.notes.FindingSource
import com.example.aiagenttestapp.data.notes.Note
import com.example.aiagenttestapp.data.notes.NoteFinding
import com.example.aiagenttestapp.data.notes.NoteStatus
import com.example.aiagenttestapp.functions.MarkerKind
import com.example.aiagenttestapp.ui.components.EmptyState
import com.example.aiagenttestapp.ui.components.FeatureHero
import com.example.aiagenttestapp.ui.components.GridCardMinWidth
import com.example.aiagenttestapp.ui.components.SwipeAction
import com.example.aiagenttestapp.ui.components.SwipeActionTone
import com.example.aiagenttestapp.ui.components.SwipeRevealBox
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(
    viewModel: NotesViewModel,
    onRecord: () -> Unit,
    /** Opens a note whose transcript is ready but which the user has not reviewed yet. */
    onOpenDraft: (Long) -> Unit,
    /** Opens the STT benchmark rig -- conceptually downstream of voice notes, so it lives here. */
    onOpenBenchmark: () -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val notes = state.notes
    var openNote by remember { mutableStateOf<Note?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Voice notes", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onOpenBenchmark) {
                        Icon(Icons.Default.Speed, contentDescription = "STT benchmark")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onRecord,
                icon = { Icon(Icons.Default.Mic, contentDescription = null) },
                text = { Text("Record") },
            )
        },
    ) { padding ->
        if (notes.isEmpty()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                EmptyState(
                    icon = Icons.Default.Mic,
                    title = "Capture your first voice note",
                    body = "Record naturally, review the private on-device transcript, then create " +
                        "a summary and findings.",
                    actionLabel = "Record a note",
                    onAction = onRecord,
                )
            }
            return@Scaffold
        }

        // One column on a phone, two or three on a tablet -- see GridCardMinWidth.
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = GridCardMinWidth),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                FeatureHero(
                    eyebrow = "Voice notes",
                    title = "Think out loud",
                    body = "Capture the conversation while it is fresh. Transcription and " +
                        "summaries stay on this device.",
                    icon = Icons.Default.Mic,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            items(notes, key = { it.id }) { note ->
                NoteCard(
                    note = note,
                    findings = state.findings[note.id].orEmpty(),
                    onClick = {
                        // A note still being transcribed has nothing to show; one awaiting review goes
                        // back to the recorder, where the review flow already lives.
                        when (note.status) {
                            NoteStatus.Transcribing -> Unit
                            NoteStatus.Draft -> onOpenDraft(note.id)
                            NoteStatus.Ready -> openNote = note
                        }
                    },
                    onDelete = { viewModel.onIntent(NotesIntent.Delete(note.id)) },
                )
            }
        }
    }

    openNote?.let { note ->
        NoteDetailSheet(
            note = note,
            findings = state.findings[note.id].orEmpty(),
            onDismiss = { openNote = null },
        )
    }
}

@Composable
private fun NoteCard(
    note: Note,
    findings: List<NoteFinding>,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    SwipeRevealBox(
        actions = listOf(
            SwipeAction(
                label = "Delete",
                icon = Icons.Default.Delete,
                tone = SwipeActionTone.Destructive,
                onClick = onDelete,
            ),
        ),
    ) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = note.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        // Attribution matters: a summary is only as trustworthy as the model that
                        // wrote it, and this app lets you swap that model.
                        text = "${formatDate(note.createdAtMillis)} · " +
                            "${formatDuration(note.durationMillis)} · ${note.summarisedBy}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete note",
                        Modifier.size(18.dp),
                    )
                }
            }

            when (note.status) {
                NoteStatus.Transcribing -> TranscribingRow(note)

                NoteStatus.Draft -> Text(
                    text = note.error
                        ?: "Transcript ready — tap to check it and summarise.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (note.error != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )

                NoteStatus.Ready -> Unit
            }

            if (findings.isNotEmpty()) {
                FindingBadges(findings)
            }

            if (note.summary.isNotBlank()) {
                Text(
                    text = note.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (note.transcript.isNotBlank()) {
                Text(
                    text = note.transcript,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    }
}

/**
 * Progress for a note still being transcribed.
 *
 * Read from the note row, not from WorkManager: the whole reason transcription is a durable worker is
 * that it survives the process dying, and in-memory progress does not.
 */
@Composable
private fun TranscribingRow(note: Note) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "Transcribing…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        if (note.transcribeProgress > 0f) {
            LinearProgressIndicator(
                progress = { note.transcribeProgress },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Counts of what was found, so a report's weight is legible without opening it. */
@Composable
private fun FindingBadges(findings: List<NoteFinding>) {
    val nonConformities = findings.count { it.kind == MarkerKind.NonConformity }
    val actions = findings.count { it.kind == MarkerKind.Action }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (nonConformities > 0) {
            Badge(
                icon = Icons.Default.Warning,
                text = "$nonConformities non-conformit${if (nonConformities == 1) "y" else "ies"}",
                container = MaterialTheme.colorScheme.errorContainer,
                content = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
        if (actions > 0) {
            Badge(
                icon = Icons.Default.ErrorOutline,
                text = "$actions action${if (actions == 1) "" else "s"}",
                container = MaterialTheme.colorScheme.tertiaryContainer,
                content = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}

@Composable
private fun Badge(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    container: androidx.compose.ui.graphics.Color,
    content: androidx.compose.ui.graphics.Color,
) {
    Surface(shape = RoundedCornerShape(percent = 50), color = container) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(14.dp))
            Text(text, style = MaterialTheme.typography.labelMedium, color = content)
        }
    }
}

/** Opens a note in full: findings, the whole summary and the whole transcript, scrollable. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteDetailSheet(
    note: Note,
    findings: List<NoteFinding>,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = note.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "${formatDate(note.createdAtMillis)} · " +
                    "${formatDuration(note.durationMillis)} · ${note.summarisedBy}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            FindingSection(
                label = "Non-conformities",
                findings = findings.filter { it.kind == MarkerKind.NonConformity },
            )
            FindingSection(
                label = "Actions",
                findings = findings.filter { it.kind == MarkerKind.Action },
            )

            if (note.summary.isNotBlank()) {
                SectionLabel("Summary")
                Text(note.summary, style = MaterialTheme.typography.bodyMedium)
            }

            SectionLabel("Transcript")
            Text(
                text = note.transcript,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * One kind of finding, listed.
 *
 * Items the user marked out loud are labelled as such. That distinction is the point of storing
 * [FindingSource]: in a record someone may act on, "I said this into the recording" and "a small
 * language model inferred it from my words" do not deserve equal confidence.
 */
@Composable
private fun FindingSection(label: String, findings: List<NoteFinding>) {
    if (findings.isEmpty()) return

    SectionLabel(label)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        findings.forEach { finding ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("•", style = MaterialTheme.typography.bodyMedium)
                Column {
                    Text(finding.text, style = MaterialTheme.typography.bodyMedium)
                    val notes = buildList {
                        finding.owner?.let { add(it) }
                        if (finding.source == FindingSource.Tagged) add("marked while recording")
                    }
                    if (notes.isNotEmpty()) {
                        Text(
                            notes.joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 4.dp),
    )
}

private fun formatDate(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))

private fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
