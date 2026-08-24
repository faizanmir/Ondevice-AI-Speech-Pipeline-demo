package com.example.aiagenttestapp.ui.history

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.automirrored.filled.FactCheck
import com.example.aiagenttestapp.data.audit.AuditDocument
import com.example.aiagenttestapp.data.audit.AuditMode
import com.example.aiagenttestapp.data.audit.AuditStatus
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.aiagent.engine.core.ModelSpec
import com.example.aiagenttestapp.ui.audit.shareAuditReport
import com.example.aiagenttestapp.ui.components.SwipeAction
import com.example.aiagenttestapp.ui.components.SwipeActionTone
import com.example.aiagenttestapp.ui.components.SwipeRevealBox
import com.example.aiagenttestapp.ui.components.formatDuration
import com.example.aiagenttestapp.ui.components.readableWidth
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel,
    onOpenChat: (modelId: String, conversationId: Long) -> Unit,
    onNewChat: (ModelSpec) -> Unit,
    onGetModels: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenNotes: () -> Unit,
    onOpenBenchmark: () -> Unit,
    onOpenSpeakers: () -> Unit,
    onOpenAudit: (AuditMode) -> Unit,
    onOpenAuditReport: (Long) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val items = state.entries
    val chatModels = state.chatModels
    var newChatMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var auditMenuOpen by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Chats", fontWeight = FontWeight.SemiBold) },
                actions = {
                    // Same two-option entry point as the chat top bar, so the choice is offered
                    // wherever a document can be started -- see ChatScreen's AuditMenuButton.
                    Box {
                        IconButton(onClick = { auditMenuOpen = true }) {
                            Icon(
                                Icons.AutoMirrored.Filled.FactCheck,
                                contentDescription = "Read a document",
                            )
                        }
                        DropdownMenu(
                            expanded = auditMenuOpen,
                            onDismissRequest = { auditMenuOpen = false },
                        ) {
                            AuditMode.entries.forEach { mode ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(mode.label)
                                            Text(
                                                mode.blurb,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    },
                                    onClick = {
                                        auditMenuOpen = false
                                        onOpenAudit(mode)
                                    },
                                )
                            }
                        }
                    }
                    IconButton(onClick = onOpenNotes) {
                        Icon(Icons.Default.Mic, contentDescription = "Voice notes")
                    }
                    // Straight to the rig rather than via Voice notes. The benchmark is run in
                    // sittings of a dozen comparisons, and burying it one screen deep was enough
                    // friction to keep sending the work back to a laptop.
                    IconButton(onClick = onOpenBenchmark) {
                        Icon(Icons.Default.Speed, contentDescription = "STT benchmark")
                    }
                    // Enrolment is a thing people do once, ahead of the recording it matters for --
                    // so it needs to be findable *before* anyone has a transcript to fix, which a
                    // link from the transcript screen would not be.
                    IconButton(onClick = onOpenSpeakers) {
                        Icon(Icons.Default.RecordVoiceOver, contentDescription = "Speakers")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        floatingActionButton = {
            NewChatMenu(
                expanded = newChatMenuExpanded,
                onExpandedChange = { newChatMenuExpanded = it },
                models = chatModels,
                onPick = onNewChat,
                onGetModels = onGetModels,
            )
        },
    ) { padding ->
        if (items.isEmpty()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Nothing yet. Tap New chat to begin, or audit a document from the check icon above.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                // Rows are capped at a readable width; on a tablet this centres them.
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                items(
                    items,
                    key = { entry ->
                        when (entry) {
                            is HistoryEntry.Chat -> "c${entry.item.id}"
                            is HistoryEntry.Audit -> "a${entry.doc.id}"
                        }
                    },
                ) { entry ->
                    when (entry) {
                        is HistoryEntry.Chat -> HistoryRow(
                            item = entry.item,
                            onClick = { onOpenChat(entry.item.modelId, entry.item.id) },
                            onDelete = { viewModel.onIntent(HistoryIntent.DeleteChat(entry.item.id)) },
                        )

                        is HistoryEntry.Audit -> AuditHistoryRow(
                            doc = entry.doc,
                            modelName = entry.modelName,
                            onClick = {
                                if (entry.doc.status == AuditStatus.DONE) onOpenAuditReport(entry.doc.id)
                            },
                            onDelete = { viewModel.onIntent(HistoryIntent.DeleteAudit(entry.doc.id)) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * The New-chat button, fanned out speed-dial style: tapping it opens the model choice -- the
 * built-in Gemini Nano versus the models the user has downloaded -- instead of silently starting
 * on whatever Settings last said. The choice IS the menu; there is no separate picker screen.
 *
 * Hand-rolled rather than material3's FloatingActionButtonMenu because that component has not
 * reached the 1.4 stable line this app is pinned to (only its design tokens shipped).
 */
@Composable
private fun NewChatMenu(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    models: List<ModelSpec>,
    onPick: (ModelSpec) -> Unit,
    onGetModels: () -> Unit,
) {
    // The system back gesture should close the fan, not leave the screen.
    BackHandler(enabled = expanded) { onExpandedChange(false) }

    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom),
            exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom),
        ) {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                models.forEach { model ->
                    NewChatOption(
                        label = model.name,
                        icon = Icons.Default.Memory,
                        onClick = {
                            onExpandedChange(false)
                            onPick(model)
                        },
                    )
                }

                // Nothing downloaded is not a dead end -- the fan offers the way to fix it.
                if (models.isEmpty()) {
                    NewChatOption(
                        label = "Download a model",
                        icon = Icons.Default.Download,
                        onClick = {
                            onExpandedChange(false)
                            onGetModels()
                        },
                    )
                }
            }
        }

        // The + rotates into an x rather than swapping icons -- continuous motion reads as the
        // same control changing state, where a swap reads as a different button appearing.
        val rotation by animateFloatAsState(
            targetValue = if (expanded) 45f else 0f,
            label = "newChatFabRotation",
        )
        ExtendedFloatingActionButton(
            onClick = { onExpandedChange(!expanded) },
            shape = CircleShape,
            icon = {
                Icon(
                    Icons.Default.Add,
                    contentDescription = if (expanded) "Close" else null,
                    modifier = Modifier.rotate(rotation),
                )
            },
            text = { Text("New chat") },
        )
    }
}

/**
 * One option in the fan: a full pill, deliberately lighter than the anchor FAB -- smaller type,
 * secondary tone, less elevation -- so the fan reads as choices radiating from the button rather
 * than as a stack of rival buttons.
 */
@Composable
private fun NewChatOption(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shadowElevation = 3.dp,
    ) {
        Row(
            Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(icon, contentDescription = null, Modifier.size(18.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun AuditHistoryRow(
    doc: AuditDocument,
    modelName: String,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    SwipeRevealBox(
        modifier = Modifier.readableWidth(),
        actions = buildList {
            // Share only once there is a report to render. Offering it on a queued or failed
            // document would put a button behind the row that can only ever apologise.
            if (doc.result != null) {
                add(
                    SwipeAction(
                        label = "Share",
                        icon = Icons.Default.Share,
                        onClick = { scope.launch { shareAuditReport(context, doc, modelName) } },
                    ),
                )
            }
            add(
                SwipeAction(
                    label = "Delete",
                    icon = Icons.Default.DeleteOutline,
                    tone = SwipeActionTone.Destructive,
                    onClick = onDelete,
                ),
            )
        },
    ) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = doc.status == AuditStatus.DONE, onClick = onClick),
    ) {
        androidx.compose.foundation.layout.Row(
            Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.FactCheck,
                contentDescription = null,
                modifier = Modifier
                    .padding(end = 12.dp)
                    .size(20.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = doc.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = auditSubtitle(doc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Provenance on its own line rather than appended to the status: an audit row already
                // carries counts, and one line holding all of it ellipsizes away exactly the model
                // name and timing this line exists to show.
                Text(
                    text = auditProvenance(doc, modelName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Kept alongside the swipe tray rather than replaced by it. A swipe is invisible until
            // someone tries it, so the one action that must never be undiscoverable stays on the
            // row; the tray is the shortcut, not the only way in.
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.DeleteOutline,
                    contentDescription = "Delete audit",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    }
}

private fun auditSubtitle(doc: AuditDocument): String {
    // The row's own word for itself. A quick read is not an audit -- it grades nothing and looks for
    // no non-conformities -- so calling it one on the home list would misdescribe what it produced.
    val kind = if (doc.mode == AuditMode.QUICK) "Summary" else "Audit"
    return when (doc.status) {
        AuditStatus.QUEUED -> "$kind · queued"
        AuditStatus.ANALYSING -> when {
            // "Summarising" only when one is actually being written. A run with the summary turned
            // off still passes through the same reduce phase -- merging findings -- and naming it
            // after a stage that was skipped describes work nobody asked for.
            doc.summarising && doc.includeSummary -> "$kind · summarising…"
            doc.summarising -> "$kind · merging findings…"
            doc.chunkCount > 1 -> "$kind · reading ${doc.currentSection}/${doc.chunkCount}"
            else -> "$kind · reading…"
        }
        AuditStatus.DONE -> doc.result?.let { result ->
            buildString {
                // A quick report states its one conclusion; there is nothing to count, and the key
                // points this used to count are no longer produced.
                append(
                    if (doc.mode == AuditMode.QUICK) {
                        val conclusion = result.protocolElements.firstOrNull()?.result?.label
                            ?: "no clear result"
                        "$kind · $conclusion"
                    } else {
                        "$kind · ${result.nonConformities.size} non-conformities"
                    },
                )
                append(" · ${result.actions.size} actions")
                // Incompleteness travels with the summary line: a row that looks like every other
                // finished audit is exactly how a partial result gets mistaken for a clean one.
                if (result.unanalysedSections > 0) {
                    append(" · ${result.unanalysedSections} section(s) unanalysed")
                }
            }
        } ?: "$kind · done"
        AuditStatus.FAILED -> "$kind · failed"
        AuditStatus.CANCELLED -> "$kind · cancelled"
    }
}

/**
 * "Gemma 3 4B · 4m 12s" -- the model that wrote the summary, and how long it took. While a document
 * is still running the time is the elapsed-so-far (the worker banks it at every chunk), which is why
 * it is worded as "in" only once the report is DONE.
 */
private fun auditProvenance(doc: AuditDocument, modelName: String): String = buildString {
    append(modelName)
    formatDuration(doc.analysisMillis).takeIf { it.isNotEmpty() }?.let { duration ->
        append(" · ")
        append(if (doc.status == AuditStatus.DONE) "generated in $duration" else duration)
    }
}

@Composable
private fun HistoryRow(
    item: ChatHistoryItem,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    SwipeRevealBox(
        modifier = Modifier.readableWidth(),
        actions = listOf(
            SwipeAction(
                label = "Delete",
                icon = Icons.Default.DeleteOutline,
                tone = SwipeActionTone.Destructive,
                onClick = onDelete,
            ),
        ),
    ) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        androidx.compose.foundation.layout.Row(
            Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${item.modelName} · ${DateUtils.getRelativeTimeSpanString(item.updatedAtMillis)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.DeleteOutline,
                    contentDescription = "Delete chat",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    }
}
