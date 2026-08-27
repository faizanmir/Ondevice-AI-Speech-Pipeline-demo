package com.example.aiagenttestapp.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.aiagenttestapp.data.audiomodels.AudioModelBundle
import com.example.aiagenttestapp.data.audiomodels.AudioModelRepository
import com.example.aiagenttestapp.data.audiomodels.AudioModelState
import com.example.aiagenttestapp.ui.components.ModelDownloadControl
import com.example.aiagenttestapp.ui.components.ModelDownloadUiState

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

        ModelDownloadControl(
            state = when (val current = state) {
                AudioModelState.NotDownloaded -> ModelDownloadUiState.NotDownloaded
                is AudioModelState.Downloading ->
                    ModelDownloadUiState.Downloading(current.progress)
                AudioModelState.Ready -> ModelDownloadUiState.Ready
                is AudioModelState.Failed -> ModelDownloadUiState.Failed(current.message)
            },
            downloadBytes = bundle.downloadBytes,
            onDownload = { repository.enqueueDownload(bundle) },
            onCancel = { repository.cancelDownload(bundle) },
            onDelete = { repository.delete(bundle) },
            deleteTitle = "Delete ${bundle.label}?",
        )
    }
}

/**
 * The two speaker-embedding models, as cards the user picks between.
 *
 * A choice rather than a setting with a right answer, because the two genuinely trade against each
 * other. CAM++ compares voices about 2.8x faster, measured head to head at four threads on five
 * seconds of audio, and embedding is most of what identifying speakers costs. ERes2Net-base is what
 * sherpa's clustering threshold was calibrated against, and an earlier attempt to run a CAM++ model
 * against that threshold turned a two-speaker recording into eleven clusters.
 *
 * Selection is allowed before the files are there. The model is a statement of intent and the
 * download is a separate act, the same separation [AudioBundleRow] already draws between a feature
 * switch and its bytes; the speaker screen refuses to run without the files and says which are
 * missing.
 */
@Composable
fun SpeakerModelSection(
    repository: AudioModelRepository,
    bundles: List<AudioModelBundle>,
    selectedId: String?,
    onSelect: (AudioModelBundle) -> Unit,
) {
    val active = bundles.firstOrNull { it.id == selectedId } ?: bundles.first()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Voice matching", style = MaterialTheme.typography.bodyMedium)
        Text(
            "Which model compares voices. Switching does not convert anyone already enrolled: the " +
                "two store different kinds of voiceprint, so people need enrolling again.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        bundles.forEach { bundle ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = bundle.id == active.id,
                            onClick = { onSelect(bundle) },
                        )
                        Text(bundle.label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                AudioBundleRow(repository = repository, bundle = bundle)
            }
        }
    }
}
