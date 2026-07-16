package com.example.aiagenttestapp.ui.hub

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.aiagent.engine.core.ModelFormat
import com.example.aiagenttestapp.ui.components.FitBadge
import com.example.aiagenttestapp.ui.components.GridCardMinWidth
import com.example.aiagenttestapp.ui.components.formatBytes
import com.example.aiagenttestapp.ui.components.palette

/**
 * Browse HuggingFace and add any model the app can run.
 *
 * The point of doing this in-app rather than shipping a fixed list: the built-in catalogue is eight
 * models I picked, and the Hub has thousands. Every file listed here is judged against the device's
 * memory before the user commits to a multi-gigabyte download.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HubScreen(
    viewModel: HubViewModel,
    onSignIn: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("HuggingFace", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        HubContent(
            viewModel = viewModel,
            onSignIn = onSignIn,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        )
    }
}

/**
 * The search box and results, without a scaffold of its own, so it can be the whole [HubScreen] or a
 * tab inside the models page.
 */
@Composable
fun HubContent(
    viewModel: HubViewModel,
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier) {
        OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Search models") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
            )

            // Format, not engine: the file format is what decides which engine can load the result,
            // so this is the honest way to frame the choice.
            Row(
                Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = state.format == ModelFormat.GGUF,
                    onClick = { viewModel.onFormatChange(ModelFormat.GGUF) },
                    label = { Text("GGUF · llama.cpp") },
                )
                FilterChip(
                    selected = state.format == ModelFormat.LITERTLM,
                    onClick = { viewModel.onFormatChange(ModelFormat.LITERTLM) },
                    label = { Text("LiteRT-LM") },
                )
                FilterChip(
                    selected = state.format == ModelFormat.MNN,
                    onClick = { viewModel.onFormatChange(ModelFormat.MNN) },
                    label = { Text("MNN") },
                )
            }

            state.error?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp),
                )
            }

            if (state.isSearching && state.results.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (state.hasSearched && state.results.isEmpty() && state.error == null) {
                EmptyResults(state.format)
            } else {
                // One column on a phone, two or three on a tablet -- see GridCardMinWidth.
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = GridCardMinWidth),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.results, key = { it.id }) { repo ->
                        RepoCard(
                            repo = repo,
                            isOpen = state.openRepo?.id == repo.id,
                            isLoading = state.isLoadingRepo && state.openRepo?.id != repo.id,
                            files = if (state.openRepo?.id == repo.id) state.openRepoFiles
                            else emptyList(),
                            onClick = { viewModel.openRepo(repo) },
                            onAdd = viewModel::add,
                            onRemove = viewModel::remove,
                            isSignedIn = state.isSignedIn,
                            onSignIn = onSignIn,
                        )
                    }
                }
            }
        }
}

@Composable
private fun RepoCard(
    repo: com.example.aiagenttestapp.data.HfRepo,
    isOpen: Boolean,
    isLoading: Boolean,
    files: List<HubFile>,
    onClick: () -> Unit,
    onAdd: (HubFile) -> Unit,
    onRemove: (HubFile) -> Unit,
    isSignedIn: Boolean,
    onSignIn: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = repo.id.substringAfter('/'),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${repo.author} · ${formatCount(repo.downloads)} downloads",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Gated repos are shown rather than hidden. This app has no HuggingFace sign-in, so
                // they cannot be downloaded -- but a user searching "gemma" needs to be told why
                // the results look wrong, not handed a silently filtered list.
                if (repo.gated && !isSignedIn) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = null,
                            Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "Needs sign-in",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (isLoading) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }

            AnimatedVisibility(visible = isOpen && files.isNotEmpty()) {
                Column {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    files.forEach { hubFile ->
                        FileRow(
                            hubFile = hubFile,
                            // Signed in, so a gated repo is just a repo. The licence still has to be
                            // accepted on huggingface.co, and the download surfaces that if not.
                            locked = repo.gated && !isSignedIn,
                            onAdd = { onAdd(hubFile) },
                            onRemove = { onRemove(hubFile) },
                            onSignIn = onSignIn,
                        )
                    }
                }
            }

            if (isOpen && files.isEmpty() && !isLoading) {
                Text(
                    "No runnable model files in this repo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun FileRow(
    hubFile: HubFile,
    locked: Boolean,
    onAdd: () -> Unit,
    onRemove: () -> Unit,
    onSignIn: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = hubFile.file.path.substringAfterLast('/'),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${hubFile.file.quantization.label} · " +
                        formatBytes(hubFile.file.sizeBytes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // The same verdict the built-in catalogue shows, computed the same way.
                FitBadge(hubFile.fit.verdict)
            }
        }

        Spacer(Modifier.width(8.dp))

        when {
            locked -> TextButton(onClick = onSignIn) {
                Icon(Icons.Default.Lock, contentDescription = null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Sign in")
            }

            hubFile.isAdded -> TextButton(onClick = onRemove) {
                Icon(Icons.Default.Check, contentDescription = null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Added")
            }

            else -> OutlinedButton(onClick = onAdd) {
                Icon(Icons.Default.Add, contentDescription = null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Add")
            }
        }
    }
}

@Composable
private fun EmptyResults(format: ModelFormat) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = when (format) {
                ModelFormat.GGUF -> "No GGUF models matched. Try a family name like \"qwen\", " +
                    "\"phi\" or \"smol\"."

                ModelFormat.LITERTLM -> "No LiteRT-LM models matched. Google publishes these under " +
                    "the litert-community organisation, so the selection is much smaller than GGUF."

                ModelFormat.MNN -> "No MNN models matched. Alibaba publishes these under the " +
                    "taobao-mnn organisation -- try a Qwen family name."

                // Unreachable -- the format tabs above never offer AICore -- but a composable
                // should degrade to an explanation rather than crash if that ever changes.
                ModelFormat.AICORE -> "Gemini Nano is built into Android and managed by the OS. " +
                    "There is nothing to search for on HuggingFace."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatCount(value: Long): String = when {
    value >= 1_000_000 -> "%.1fM".format(value / 1_000_000.0)
    value >= 1_000 -> "%.0fk".format(value / 1_000.0)
    else -> value.toString()
}
