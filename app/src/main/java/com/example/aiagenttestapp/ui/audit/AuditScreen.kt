@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.aiagenttestapp.ui.audit

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.aiagent.engine.core.ModelSpec
import com.example.aiagenttestapp.data.audit.AuditDocument
import com.example.aiagenttestapp.data.audit.AuditMode
import com.example.aiagenttestapp.data.audit.AuditStatus
import com.example.aiagenttestapp.ui.components.formatDuration

@Composable
fun AuditScreen(
    viewModel: AuditViewModel,
    onBack: () -> Unit,
    onOpenReport: (Long) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // The mode chosen when the picker was launched, held across the trip to the system file picker
    // -- the intent needs it when the URI comes back, and the launcher callback cannot be given it
    // any other way. rememberSaveable because the picker can outlive this Activity on a low-memory
    // device, and a restored screen must queue the mode the user actually picked, not the default.
    var pendingMode by rememberSaveable { mutableStateOf(AuditMode.DETAILED) }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { viewModel.onIntent(AuditIntent.AttachFile(it, pendingMode)) } }

    fun pick(mode: AuditMode) {
        pendingMode = mode
        filePicker.launch(arrayOf("*/*"))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Audit", fontWeight = FontWeight.SemiBold)
                        state.model?.let {
                            Text(
                                "${it.name} · ${state.engineName}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                when (val loadState = state.loadState) {
                    is AuditLoadState.Failed -> AuditSectionCard(title = "Model") {
                        Text(loadState.message, color = MaterialTheme.colorScheme.error)
                        // The switcher stays offered on failure on purpose: picking a model that
                        // does load IS the recovery path from a model that did not.
                        Spacer(Modifier.height(8.dp))
                        ModelSwitcher(
                            current = state.model,
                            available = state.availableModels,
                            enabled = !state.anySummarising,
                            onSwitch = { viewModel.onIntent(AuditIntent.SwitchModel(it)) },
                        )
                    }
                    else -> AddDocumentCard(
                        state = state,
                        onPickFile = ::pick,
                        onSwitchModel = { viewModel.onIntent(AuditIntent.SwitchModel(it)) },
                    )
                }
            }

            if (state.documents.isEmpty()) {
                item {
                    Text(
                        "No documents yet. Attach a file to queue it for reading.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                    )
                }
            } else {
                item {
                    Text(
                        "Queue (${state.documents.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                    )
                }
                items(state.documents, key = { it.id }) { doc ->
                    DocumentRow(
                        doc = doc,
                        // The raw id when the directory no longer knows the model: that model still
                        // wrote this report, and the reader deserves to know which one.
                        modelName = state.modelNames[doc.modelId] ?: doc.modelId,
                        onOpen = { if (doc.status == AuditStatus.DONE) onOpenReport(doc.id) },
                        onCancel = { viewModel.onIntent(AuditIntent.Cancel(doc.id)) },
                        onRetry = { viewModel.onIntent(AuditIntent.Retry(doc.id)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AddDocumentCard(
    state: AuditUiState,
    onPickFile: (AuditMode) -> Unit,
    onSwitchModel: (String) -> Unit,
) {
    AuditSectionCard(title = "Add a document") {
        Text(
            "Attach a transcript or document file to queue it for reading.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        AttachFileButton(
            mode = state.mode,
            enabled = state.canAttach,
            isBusy = state.isExtractingFile,
            onPick = onPickFile,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Read with",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        // Disabled while a load is in flight so a second switch cannot race the first (the attach
        // button above is already gated on Ready, so a mid-switch enqueue cannot happen either),
        // and while any document is summarising -- see AuditUiState.anySummarising.
        ModelSwitcher(
            current = state.model,
            available = state.availableModels,
            enabled = state.loadState !is AuditLoadState.Loading && !state.anySummarising,
            onSwitch = onSwitchModel,
        )
        state.lastAdded?.let { name ->
            Spacer(Modifier.height(6.dp))
            Text(
                buildString {
                    // Names the mode: with two reads available and the picker's default not
                    // necessarily what was tapped, "Queued report.pdf" alone leaves the one thing
                    // the user just chose unconfirmed.
                    append("Queued $name — ${state.lastAddedMode.label.lowercase()}")
                    if (state.addTruncated) append(" (very large -- trimmed to a supported size)")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.attachmentError?.let { error ->
            Spacer(Modifier.height(6.dp))
            Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

/**
 * Attach-a-file, with the read to perform chosen at the same moment.
 *
 * A split button rather than a mode toggle somewhere else on the card: the mode is pinned onto the
 * document being queued, so the choice belongs on the action that queues it. Tapping the body uses
 * [mode] -- whatever the screen was opened with -- and the chevron opens both options, each of which
 * launches the picker directly. Either way the mode is settled before the file is chosen, so nothing
 * has to be re-confirmed afterwards.
 */
@Composable
private fun AttachFileButton(
    mode: AuditMode,
    enabled: Boolean,
    isBusy: Boolean,
    onPick: (AuditMode) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(
            onClick = { onPick(mode) },
            enabled = enabled,
            modifier = Modifier.weight(1f),
        ) {
            if (isBusy) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Default.AttachFile, contentDescription = null, Modifier.size(18.dp))
            }
            Spacer(Modifier.width(6.dp))
            // The mode is on the button face, so the default action is never a mystery -- a plain
            // "Attach file" beside a chevron gives no clue which of the two it would run.
            Text("Attach file · ${mode.label.lowercase()}")
        }
        Box {
            OutlinedButton(
                onClick = { menuOpen = true },
                enabled = enabled,
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) {
                Icon(
                    Icons.Default.ArrowDropDown,
                    contentDescription = "Choose how to read the document",
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                AuditMode.entries.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(option.label)
                                Text(
                                    option.blurb,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        leadingIcon = {
                            // A tick on the current default, not a disabled row: both options stay
                            // pickable, since choosing the one already shown is a perfectly ordinary
                            // thing to do from a menu.
                            if (option == mode) {
                                Icon(Icons.Default.Check, contentDescription = null)
                            } else {
                                Spacer(Modifier.size(24.dp))
                            }
                        },
                        onClick = {
                            menuOpen = false
                            onPick(option)
                        },
                    )
                }
            }
        }
    }
}

/**
 * Which model new documents will be audited with: one [FilterChip] per usable model with the active
 * one selected, the same presentation Settings uses for the chat model, so choosing a model looks
 * identical everywhere. Only models audit can actually run right now are offered (downloaded,
 * engine available, fits this device) -- the same resolution the drain worker applies, so nothing
 * offered here can be refused later. Switching never touches documents already in the queue; each
 * keeps the model pinned at its enqueue.
 */
@Composable
private fun ModelSwitcher(
    current: ModelSpec?,
    available: List<ModelSpec>,
    enabled: Boolean,
    onSwitch: (String) -> Unit,
) {
    if (available.isEmpty()) {
        Text(
            "No usable models downloaded yet.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    // One scrollable line rather than a wrapping grid: the chips keep their natural width, and the
    // selected model is findable by scanning one row.
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        available.forEach { spec ->
            FilterChip(
                selected = spec.id == current?.id,
                enabled = enabled,
                // Re-selecting the active chip is a no-op rather than a reload; the model is
                // already warm, and FilterChip has no disabled-because-selected state.
                onClick = { if (spec.id != current?.id) onSwitch(spec.id) },
                label = { Text("${spec.name} · ${spec.paramsLabel}") },
            )
        }
    }
}

@Composable
private fun DocumentRow(
    doc: AuditDocument,
    modelName: String,
    onOpen: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
) {
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = doc.status == AuditStatus.DONE, onClick = onOpen),
        colors = CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    doc.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                if (doc.status == AuditStatus.DONE) {
                    Icon(Icons.Default.ChevronRight, contentDescription = "Open report")
                }
            }
            // The model and mode pinned at enqueue, which are what analysed this document -- shown
            // per row because either can differ from what the card above currently offers, and a
            // queue holding both kinds of read is otherwise impossible to tell apart.
            Text(
                "$modelName · ${doc.mode.label.lowercase()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))

            when (doc.status) {
                AuditStatus.QUEUED -> StatusRow("Queued", onCancel = onCancel)

                AuditStatus.ANALYSING -> {
                    val total = doc.chunkCount.coerceAtLeast(1)
                    val reading = if (doc.mode == AuditMode.QUICK) "Reading" else "Analysing"
                    StatusRow(
                        when {
                            // Quick mode runs no grading pass at all, so naming one here would
                            // describe a phase that does not exist.
                            doc.summarising && doc.mode == AuditMode.QUICK -> "Summarising…"
                            doc.summarising -> "Grading and summarising…"
                            doc.chunkCount > 1 ->
                                "$reading — section ${doc.currentSection} of ${doc.chunkCount}"
                            else -> "$reading…"
                        },
                        onCancel = onCancel,
                    )
                    Spacer(Modifier.height(6.dp))
                    // Counts the in-flight section, exactly like the notification's bar -- a bar
                    // sitting at 0% for the minutes the first chunk takes reads as stuck.
                    LinearProgressIndicator(
                        progress = {
                            if (doc.summarising) 1f
                            else doc.currentSection.toFloat() / total.toFloat()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                AuditStatus.DONE -> Text(
                    buildString {
                        append(
                            doc.result?.let {
                                val headline = if (doc.mode == AuditMode.QUICK) {
                                    "${it.keyPoints.size} key points"
                                } else {
                                    "${it.nonConformities.size} non-conformities"
                                }
                                "$headline · ${it.actions.size} actions"
                            } ?: "Done",
                        )
                        // Only for documents timed since this shipped; older ones simply omit it.
                        formatDuration(doc.analysisMillis).takeIf { it.isNotEmpty() }
                            ?.let { append(" · generated in $it") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                AuditStatus.FAILED -> {
                    Text(
                        doc.error ?: "Analysis failed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row {
                        TextButton(onClick = onRetry) {
                            Icon(Icons.Default.Refresh, contentDescription = null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Retry")
                        }
                        TextButton(onClick = onCancel) { Text("Remove") }
                    }
                }

                AuditStatus.CANCELLED -> Text(
                    "Cancelled",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatusRow(text: String, onCancel: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onCancel) {
            Icon(Icons.Default.Close, contentDescription = null, Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("Cancel")
        }
    }
}
