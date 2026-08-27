package com.example.aiagenttestapp.ui.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.aiagenttestapp.data.SettingsStore
import com.example.aiagenttestapp.data.audiomodels.AudioModelBundle
import com.example.aiagenttestapp.data.audiomodels.AudioModelRepository
import com.example.aiagenttestapp.data.audiomodels.AudioModelState
import com.example.aiagenttestapp.stt.SpeechModel
import com.example.aiagenttestapp.stt.SpeechModelRepository
import com.example.aiagenttestapp.stt.SpeechModelState
import com.example.aiagenttestapp.ui.components.GridCardMinWidth
import com.example.aiagenttestapp.ui.components.FeatureHero
import com.example.aiagenttestapp.ui.components.ModelDownloadControl
import com.example.aiagenttestapp.ui.components.ModelDownloadUiState

/**
 * The single inventory for every non-chat model the app downloads.
 *
 * Feature screens still recover in context when a model is missing, but this is where users compare,
 * pre-download and remove speech or feature models without first discovering which setting owns
 * them. Selection belongs here too because choosing and acquiring a recogniser are one decision.
 */
@Composable
fun SpeechModelsContent(
    settingsStore: SettingsStore,
    speechModels: SpeechModelRepository,
    audioModels: AudioModelRepository,
    modifier: Modifier = Modifier,
) {
    val settings by settingsStore.settings.collectAsStateWithLifecycle()

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = GridCardMinWidth),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            FeatureHero(
                eyebrow = "On-device audio",
                title = "Private speech, ready offline",
                body = "Download once, then dictate, transcribe and identify speakers without " +
                    "sending recordings away from this phone.",
                icon = Icons.Default.Mic,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            ModelGroupHeader(
                title = "Speech recognition",
                description = "Used by dictation, voice notes and benchmarks. Choose the default " +
                    "recogniser here; individual recordings can still override it.",
            )
        }

        items(speechModels.available, key = { "speech-${it.id}" }) { model ->
            SpeechModelCard(
                model = model,
                repository = speechModels,
                selected = settings.speechModelId == model.id ||
                    (settings.speechModelId == null && model == speechModels.available.first()),
                onSelect = {
                    settingsStore.update { it.copy(speechModelId = model.id) }
                },
            )
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            ModelGroupHeader(
                title = "Feature models",
                description = "Small, task-specific models that add speaker labels, spoken markers " +
                    "or punctuation. Their feature switches remain in Settings.",
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        items(audioModels.bundles, key = { "audio-${it.id}" }) { bundle ->
            AudioModelCard(bundle = bundle, repository = audioModels)
        }
    }
}

@Composable
private fun ModelGroupHeader(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(
            description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SpeechModelCard(
    model: SpeechModel,
    repository: SpeechModelRepository,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val state by repository.stateOf(model.id).collectAsStateWithLifecycle()

    ModelInventoryCard(
        title = model.label,
        description = model.blurb,
        supporting = {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "Voice notes · Dictation",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FilterChip(
                    selected = selected,
                    onClick = onSelect,
                    label = { Text(if (selected) "Default" else "Use by default") },
                )
            }
        },
        accented = selected,
    ) {
        ModelDownloadControl(
            state = state.toDownloadUiState(),
            downloadBytes = model.totalBytes,
            onDownload = { repository.enqueueDownload(model) },
            onCancel = { repository.cancelDownload(model) },
            onDelete = { repository.delete(model) },
            deleteTitle = "Delete ${model.label}?",
        )
    }
}

@Composable
private fun AudioModelCard(
    bundle: AudioModelBundle,
    repository: AudioModelRepository,
) {
    val state by repository.state(bundle).collectAsStateWithLifecycle()
    val usedBy = when (bundle.id) {
        repository.speaker.id -> "Speaker transcripts"
        repository.keywords.id -> "Voice-note markers"
        repository.punctuation.id -> "Live and Android speech"
        else -> "Optional feature"
    }

    ModelInventoryCard(
        title = bundle.label,
        description = bundle.blurb,
        supporting = {
            Text(
                usedBy,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        accented = false,
    ) {
        ModelDownloadControl(
            state = state.toDownloadUiState(),
            downloadBytes = bundle.downloadBytes,
            onDownload = { repository.enqueueDownload(bundle) },
            onCancel = { repository.cancelDownload(bundle) },
            onDelete = { repository.delete(bundle) },
            deleteTitle = "Delete ${bundle.label}?",
        )
    }
}

@Composable
private fun ModelInventoryCard(
    title: String,
    description: String,
    supporting: @Composable () -> Unit,
    accented: Boolean,
    action: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (accented) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
        border = BorderStroke(
            1.dp,
            if (accented) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
            else MaterialTheme.colorScheme.outlineVariant,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (accented) 2.dp else 0.dp),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            supporting()
            action()
        }
    }
}

private fun SpeechModelState.toDownloadUiState(): ModelDownloadUiState = when (this) {
    SpeechModelState.NotDownloaded -> ModelDownloadUiState.NotDownloaded
    is SpeechModelState.Downloading -> ModelDownloadUiState.Downloading(progress)
    SpeechModelState.Ready -> ModelDownloadUiState.Ready
    is SpeechModelState.Failed -> ModelDownloadUiState.Failed(message)
}

private fun AudioModelState.toDownloadUiState(): ModelDownloadUiState = when (this) {
    AudioModelState.NotDownloaded -> ModelDownloadUiState.NotDownloaded
    is AudioModelState.Downloading -> ModelDownloadUiState.Downloading(progress)
    AudioModelState.Ready -> ModelDownloadUiState.Ready
    is AudioModelState.Failed -> ModelDownloadUiState.Failed(message)
}
