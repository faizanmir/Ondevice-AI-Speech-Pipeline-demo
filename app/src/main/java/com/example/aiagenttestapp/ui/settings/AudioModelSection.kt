package com.example.aiagenttestapp.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.aiagenttestapp.data.audiomodels.AudioModelBundle
import com.example.aiagenttestapp.data.audiomodels.AudioModelRepository
import com.example.aiagenttestapp.data.audiomodels.AudioModelState
import com.example.aiagenttestapp.ui.components.formatBytes

/**
 * One optional audio-model bundle: a switch, and whatever the bundle needs from the user next.
 *
 * The switch and the download are deliberately separate. Turning the feature on is a statement of
 * intent that should survive a failed or cancelled download, so the toggle is not wired to disk state
 * -- otherwise cancelling a transfer would silently switch the feature back off and the user would be
 * left wondering why their setting did not stick.
 *
 * [onEnabledChange] is null for a bundle with no feature toggle behind it, and then no switch is
 * drawn. Speaker identification is the case: there is no setting to turn it on, because having the
 * models *is* the enablement -- the speaker screen requires them and says so. Rendering a switch
 * there would invent a preference the app does not store, and one that could sit "off" over a
 * downloaded model for no reason the user could act on.
 */
@Composable
fun AudioBundleRow(
    repository: AudioModelRepository,
    bundle: AudioModelBundle,
    enabled: Boolean = true,
    onEnabledChange: ((Boolean) -> Unit)? = null,
) {
    val state by repository.state(bundle).collectAsStateWithLifecycle()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(bundle.label, style = MaterialTheme.typography.bodyMedium)
            if (onEnabledChange != null) {
                Switch(checked = enabled, onCheckedChange = onEnabledChange)
            }
        }

        Text(
            bundle.blurb,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Nothing below matters until the feature is actually wanted.
        if (!enabled) return@Column

        when (val current = state) {
            is AudioModelState.NotDownloaded -> OutlinedButton(
                onClick = { repository.enqueueDownload(bundle) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Download, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Download ${formatBytes(bundle.downloadBytes)}")
            }

            is AudioModelState.Downloading -> Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                LinearProgressIndicator(
                    progress = { current.progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${(current.progress * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { repository.cancelDownload(bundle) }) { Text("Cancel") }
                }
            }

            is AudioModelState.Ready -> Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Downloaded and ready",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { repository.delete(bundle) }) { Text("Delete") }
            }

            is AudioModelState.Failed -> Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    current.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                OutlinedButton(onClick = { repository.enqueueDownload(bundle) }) {
                    Text("Try again")
                }
            }
        }
    }
}
