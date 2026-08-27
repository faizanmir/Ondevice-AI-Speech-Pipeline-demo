package com.example.aiagenttestapp.ui.catalog

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.aiagent.engine.core.FitVerdict
import com.example.aiagenttestapp.data.DownloadState
import com.example.aiagenttestapp.ui.components.FitBadge
import com.example.aiagenttestapp.ui.components.MemoryMeter
import com.example.aiagenttestapp.ui.components.formatBytes
import com.example.aiagenttestapp.ui.components.formatBytesPerSecond
import com.example.aiagenttestapp.ui.components.formatEta
import com.example.aiagenttestapp.ui.components.palette

/**
 * Which destructive action the card is asking the user to confirm. Hoisted state: the caller owns
 * the current value and the card only requests changes through `onPendingConfirmationChange`.
 */
enum class ModelCardConfirmation { DeleteDownload, RemoveCustom }

@Composable
fun ModelCard(
    entry: CatalogEntry,
    pendingConfirmation: ModelCardConfirmation?,
    onPendingConfirmationChange: (ModelCardConfirmation?) -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onOpenChat: () -> Unit,
    onDeleteDownload: () -> Unit,
    onRemoveCustom: () -> Unit,
    onSignIn: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val model = entry.model
    val fit = entry.fit

    // Deleting a multi-gigabyte download that took twenty minutes over mobile data is not something
    // to do on a mis-tap, so it is confirmed. Re-downloading is possible but not cheap.
    when (pendingConfirmation) {
        ModelCardConfirmation.DeleteDownload -> ConfirmDialog(
            title = "Delete ${model.name}?",
            body = "This frees ${formatBytes(model.sizeBytes)}. The model stays in the catalogue " +
                "and you can download it again.",
            confirmLabel = "Delete",
            onConfirm = {
                onPendingConfirmationChange(null)
                onDeleteDownload()
            },
            onDismiss = { onPendingConfirmationChange(null) },
        )

        ModelCardConfirmation.RemoveCustom -> ConfirmDialog(
            title = "Remove ${model.name}?",
            body = "This removes it from your catalogue and deletes any downloaded file. You can " +
                "add it again from HuggingFace.",
            confirmLabel = "Remove",
            onConfirm = {
                onPendingConfirmationChange(null)
                onRemoveCustom()
            },
            onDismiss = { onPendingConfirmationChange(null) },
        )

        null -> {}
    }

    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (entry.isReadyToChat) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.48f)
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
        border = BorderStroke(
            1.dp,
            if (entry.isReadyToChat) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
            else MaterialTheme.colorScheme.outlineVariant,
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (entry.isReadyToChat) 2.dp else 0.dp,
        ),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = model.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = buildString {
                            append(model.vendor)
                            entry.engine?.let { append(" · ${it.displayName}") }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(8.dp))
                FitBadge(fit.verdict)
            }

            // The tag row. Params first, because that is the number the header card just taught
            // them to reason with.
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Tag(model.paramsLabel, emphasised = true)
                Tag(model.quantization.label)
                Tag(formatBytes(model.sizeBytes))
                if (model.multimodal) Tag("Vision")
                // Its own tag rather than folded into "Vision": this is what decides whether the
                // model can be picked to transcribe a voice note, so a user comparing models needs
                // to see which ones offer it.
                if (model.hearsAudio) Tag("Audio")
                // Says the model can drive the app -- open screens, change settings. Worth its own
                // badge because it is the difference between a chatbot and something that can act.
                if (model.canCallTools) Tag("Tools")
                if (model.isCustom) Tag("Added")
                if (entry.isLocked) Tag("Sign-in")
            }

            Text(
                text = model.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )

            MemoryMeter(fit)

            Text(
                text = fit.reason,
                style = MaterialTheme.typography.bodySmall,
                color = if (fit.canRun) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    fit.verdict.palette().accent
                },
            )

            if (entry.isLocked) {
                // A gated model is not a broken one. Offering the key beats a dead Download button
                // and a 401 the user has no way to interpret.
                Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Lock, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Sign in to HuggingFace to download")
                }
            } else {
                ModelCardAction(
                    entry = entry,
                    onDownload = onDownload,
                    onCancelDownload = onCancelDownload,
                    onOpenChat = onOpenChat,
                )
            }

            // Two different destructive actions, and conflating them would be a mistake:
            //  - "Delete download" reclaims the disk but keeps the model listed, which is what
            //    someone clearing space for a bigger model actually wants.
            //  - "Remove from catalogue" un-adds a model entirely, and only makes sense for models
            //    the user added themselves. The built-ins are the app's identity; letting someone
            //    delete "Gemma 4" out of the list with no way back would be a trap.
            val isDownloaded = entry.downloadState is DownloadState.Downloaded
            if (isDownloaded || model.isCustom) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (isDownloaded) {
                        TextButton(
                            onClick = {
                                onPendingConfirmationChange(ModelCardConfirmation.DeleteDownload)
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                Icons.Default.DeleteOutline,
                                contentDescription = null,
                                Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            // Naming the size makes this the obvious lever when the catalogue is
                            // telling you elsewhere that you have run out of space.
                            Text("Free ${formatBytes(model.sizeBytes)}")
                        }
                    }

                    if (model.isCustom) {
                        TextButton(
                            onClick = {
                                onPendingConfirmationChange(ModelCardConfirmation.RemoveCustom)
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Remove")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun ModelCardAction(
    entry: CatalogEntry,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onOpenChat: () -> Unit,
) {
    when (val state = entry.downloadState) {
        is DownloadState.NotDownloaded -> {
            Button(
                onClick = onDownload,
                enabled = entry.fit.canDownload,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Download, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                // Naming the size on the button is the last chance to stop someone kicking off a
                // 3.6 GB download on mobile data by accident.
                Text("Download ${formatBytes(entry.model.sizeBytes)}")
            }
        }

        is DownloadState.Downloading -> {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier
                            .weight(1f)
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                    )
                    IconButton(onClick = onCancelDownload, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Cancel download",
                            Modifier.size(18.dp),
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "${formatBytes(state.bytesDownloaded)} of " +
                            formatBytes(state.totalBytes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = listOf(
                            formatBytesPerSecond(state.bytesPerSecond),
                            formatEta(state.secondsRemaining),
                        ).filter { it.isNotEmpty() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        is DownloadState.Downloaded -> {
            Button(
                onClick = onOpenChat,
                // Downloaded but does not fit: the button stays visible and disabled rather than
                // disappearing, so the state is explainable rather than just absent.
                enabled = entry.isReadyToChat,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Chat, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (entry.fit.canRun) "Chat" else "Not enough memory to run")
            }
        }

        is DownloadState.Failed -> {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                OutlinedButton(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                    Text("Try again")
                }
            }
        }
    }
}

@Composable
private fun Tag(text: String, emphasised: Boolean = false) {
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (emphasised) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (emphasised) FontWeight.SemiBold else FontWeight.Normal,
            color = if (emphasised) MaterialTheme.colorScheme.onSecondaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
