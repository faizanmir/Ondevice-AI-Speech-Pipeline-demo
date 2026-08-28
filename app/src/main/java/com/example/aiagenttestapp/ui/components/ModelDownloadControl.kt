package com.example.aiagenttestapp.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The four states every downloadable model presents, regardless of which runtime consumes it.
 *
 * Repositories deliberately keep their own domain state: a chat model is one file, a speech model
 * is several files, and an audio bundle may need unpacking. This small UI type centralises only the
 * interaction users see, without pretending those storage models are interchangeable.
 */
sealed interface ModelDownloadUiState {
    data object NotDownloaded : ModelDownloadUiState
    data class Downloading(val progress: Float) : ModelDownloadUiState
    data object Ready : ModelDownloadUiState
    data class Failed(val message: String) : ModelDownloadUiState
}

/**
 * One download interaction used everywhere a model is managed.
 *
 * Context screens may still offer a download at the point of need, but it should behave and read
 * like the Models screen: size before the transfer, visible progress and cancellation during it,
 * an actionable failure, and confirmation before expensive bytes are deleted.
 */
@Composable
fun ModelDownloadControl(
    state: ModelDownloadUiState,
    downloadBytes: Long,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: (() -> Unit)? = null,
    deleteTitle: String = "Delete downloaded model?",
    modifier: Modifier = Modifier,
) {
    var confirmDelete by remember { mutableStateOf(false) }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(deleteTitle) },
            text = {
                Text(
                    "This frees ${formatBytes(downloadBytes)} from this device. " +
                        "You can download it again later.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete?.invoke()
                    },
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (state) {
            ModelDownloadUiState.NotDownloaded -> Button(
                onClick = onDownload,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Download, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Download ${formatBytes(downloadBytes)}")
            }

            is ModelDownloadUiState.Downloading -> {
                LinearProgressIndicator(
                    progress = { state.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Downloading · ${(state.progress.coerceIn(0f, 1f) * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onCancel) {
                        Icon(Icons.Default.Close, contentDescription = null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Cancel")
                    }
                }
            }

            ModelDownloadUiState.Ready -> Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Text("On device", style = MaterialTheme.typography.bodyMedium)
                }
                if (onDelete != null) {
                    TextButton(onClick = { confirmDelete = true }) {
                        Icon(
                            Icons.Default.DeleteOutline,
                            contentDescription = null,
                            Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Delete")
                    }
                }
            }

            is ModelDownloadUiState.Failed -> {
                Text(
                    state.message,
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
