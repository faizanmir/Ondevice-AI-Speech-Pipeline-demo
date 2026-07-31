package com.example.aiagenttestapp.ui.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.aiagent.engine.core.EngineId
import com.example.aiagent.engine.core.ModelSpec
import com.example.aiagenttestapp.data.DownloadState
import com.example.aiagenttestapp.ui.components.GridCardMinWidth
import com.example.aiagenttestapp.ui.hub.HubContent
import com.example.aiagenttestapp.ui.hub.HubViewModel

private const val TAB_DOWNLOADED = 0
private const val TAB_HUGGINGFACE = 1
private const val TAB_MNN = 2
private const val TAB_CATALOG = 3

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogScreen(
    viewModel: CatalogViewModel,
    hubViewModel: HubViewModel,
    /** Alibaba's own model market, downloading from ModelScope -- the MNN tab. */
    mnnMarketViewModel: MnnMarketViewModel,
    onOpenChat: (ModelSpec) -> Unit,
    onOpenDetail: (ModelSpec) -> Unit,
    onSignIn: () -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(TAB_DOWNLOADED) }
    var filterMenuOpen by remember { mutableStateOf(false) }
    var sortMenuOpen by remember { mutableStateOf(false) }

    val downloaded = state.entries.filter { it.downloadState is DownloadState.Downloaded }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Models", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Sort and engine filter drive the two model-list tabs; the HuggingFace and MNN
                    // tabs have their own search, so both are hidden there.
                    if (tab == TAB_DOWNLOADED || tab == TAB_CATALOG) {
                        Box {
                            IconButton(onClick = { sortMenuOpen = true }) {
                                Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort")
                            }
                            SortMenu(
                                expanded = sortMenuOpen,
                                selected = state.sort,
                                onSelect = {
                                    viewModel.onIntent(CatalogIntent.SortChanged(it))
                                    sortMenuOpen = false
                                },
                                onDismiss = { sortMenuOpen = false },
                            )
                        }
                        Box {
                            IconButton(onClick = { filterMenuOpen = true }) {
                                Icon(Icons.Default.FilterList, contentDescription = "Filter")
                            }
                            EngineFilterMenu(
                                expanded = filterMenuOpen,
                                options = state.engineOptions,
                                selected = state.engineFilter,
                                onSelect = {
                                    viewModel.onIntent(CatalogIntent.EngineFilterChanged(it))
                                    filterMenuOpen = false
                                },
                                onDismiss = { filterMenuOpen = false },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            TabRow(selectedTabIndex = tab) {
                Tab(
                    selected = tab == TAB_DOWNLOADED,
                    onClick = { tab = TAB_DOWNLOADED },
                    text = { Text("Downloaded") },
                )
                Tab(
                    selected = tab == TAB_HUGGINGFACE,
                    onClick = { tab = TAB_HUGGINGFACE },
                    text = { Text("HuggingFace") },
                )
                Tab(
                    selected = tab == TAB_MNN,
                    onClick = { tab = TAB_MNN },
                    text = { Text("MNN") },
                )
                Tab(
                    selected = tab == TAB_CATALOG,
                    onClick = { tab = TAB_CATALOG },
                    text = { Text("Catalog") },
                )
            }

            when (tab) {
                TAB_DOWNLOADED -> ModelList(
                    entries = downloaded,
                    header = null,
                    emptyText = "No models downloaded yet. Add one from the Catalog or HuggingFace.",
                    viewModel = viewModel,
                    onOpenChat = onOpenChat,
                    onOpenDetail = onOpenDetail,
                    onSignIn = onSignIn,
                )

                TAB_HUGGINGFACE -> HubContent(
                    viewModel = hubViewModel,
                    onSignIn = onSignIn,
                    modifier = Modifier.fillMaxSize(),
                )

                // Alibaba's own model market -- the same catalogue MNN's app ships, served from
                // meta.alicdn.com with downloads from ModelScope. No HuggingFace involved.
                TAB_MNN -> MnnMarketContent(
                    viewModel = mnnMarketViewModel,
                    modifier = Modifier.fillMaxSize(),
                )

                TAB_CATALOG -> ModelList(
                    entries = state.entries,
                    header = {
                        DeviceCapabilityCard(
                            device = state.device,
                            quantization = state.budgetQuantization,
                            maxParamsBillions = state.maxParamsBillions,
                            onQuantizationChange = {
                                viewModel.onIntent(CatalogIntent.BudgetQuantizationChanged(it))
                            },
                        )
                    },
                    emptyText = "No models match this filter.",
                    viewModel = viewModel,
                    onOpenChat = onOpenChat,
                    onOpenDetail = onOpenDetail,
                    onSignIn = onSignIn,
                )
            }
        }
    }
}

@Composable
private fun ModelList(
    entries: List<CatalogEntry>,
    header: (@Composable () -> Unit)?,
    emptyText: String,
    viewModel: CatalogViewModel,
    onOpenChat: (ModelSpec) -> Unit,
    onOpenDetail: (ModelSpec) -> Unit,
    onSignIn: () -> Unit,
) {
    // Nothing to show and no header to anchor -- a centred message reads better than an empty list.
    if (entries.isEmpty() && header == null) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                emptyText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    // Adaptive grid, not a column: phones resolve to a single column (the exact layout the list
    // had), tablets to two or three cards across, with no window-size branching anywhere.
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = GridCardMinWidth),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // The device-capability header speaks about the whole list, so it spans every column.
        header?.let { item(span = { GridItemSpan(maxLineSpan) }) { it() } }

        if (entries.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    emptyText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        items(entries, key = { it.model.id }) { entry ->
            // The card's confirm-dialog state, hoisted out of ModelCard. Keyed item scope keeps
            // it with this model across list changes.
            var pendingConfirmation by remember {
                mutableStateOf<ModelCardConfirmation?>(null)
            }
            ModelCard(
                entry = entry,
                pendingConfirmation = pendingConfirmation,
                onPendingConfirmationChange = { pendingConfirmation = it },
                onDownload = { viewModel.onIntent(CatalogIntent.Download(entry.model)) },
                onCancelDownload = { viewModel.onIntent(CatalogIntent.CancelDownload(entry.model)) },
                onOpenChat = { onOpenChat(entry.model) },
                onDeleteDownload = { viewModel.onIntent(CatalogIntent.DeleteDownload(entry.model)) },
                onRemoveCustom = { viewModel.onIntent(CatalogIntent.RemoveCustom(entry.model)) },
                onSignIn = onSignIn,
                // Tapping the card body is a shortcut into chat, so it respects the same gate as the
                // Chat button -- otherwise it opens a chat for a model that is not downloaded or does
                // not fit, which just shows an error.
                onClick = { if (entry.isReadyToChat) onOpenDetail(entry.model) },
            )
        }
    }
}

/** Sort order as a dropdown, mirroring the engine filter. */
@Composable
private fun SortMenu(
    expanded: Boolean,
    selected: CatalogSort,
    onSelect: (CatalogSort) -> Unit,
    onDismiss: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        CatalogSort.entries.forEach { option ->
            DropdownMenuItem(
                text = { Text(option.label) },
                onClick = { onSelect(option) },
                trailingIcon = {
                    if (selected == option) Icon(Icons.Default.Check, contentDescription = null)
                },
            )
        }
    }
}

/**
 * Engine filter as a dropdown. Unavailable engines stay visible but disabled -- a llama.cpp build
 * that was compiled out should say so by being greyed, not by silently narrowing the list.
 */
@Composable
private fun EngineFilterMenu(
    expanded: Boolean,
    options: List<EngineOption>,
    selected: EngineId?,
    onSelect: (EngineId?) -> Unit,
    onDismiss: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text("All engines") },
            onClick = { onSelect(null) },
            trailingIcon = {
                if (selected == null) Icon(Icons.Default.Check, contentDescription = null)
            },
        )
        options.forEach { option ->
            DropdownMenuItem(
                text = { Text(option.descriptor.displayName) },
                onClick = { onSelect(option.descriptor.id) },
                enabled = option.isAvailable,
                trailingIcon = {
                    if (selected == option.descriptor.id) {
                        Icon(Icons.Default.Check, contentDescription = null)
                    }
                },
            )
        }
    }
}
