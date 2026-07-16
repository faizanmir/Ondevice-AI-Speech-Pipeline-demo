package com.example.aiagenttestapp.ui.catalog

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.aiagent.engine.core.ModelFit
import com.example.aiagent.engine.core.ModelFitEvaluator
import com.example.aiagent.engine.core.ModelSpec
import com.example.aiagenttestapp.AppContainer
import com.example.aiagenttestapp.data.MnnMarketModel
import com.example.aiagenttestapp.ui.components.FitBadge
import com.example.aiagenttestapp.ui.components.GridCardMinWidth
import com.example.aiagenttestapp.ui.components.formatBytes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A market entry opened for a closer look: its real file list fetched, its fit judged. */
data class MnnMarketDetail(
    val spec: ModelSpec,
    val fit: ModelFit,
)

data class MnnMarketUiState(
    val query: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val models: List<MnnMarketModel> = emptyList(),
    /** Ids ([MnnMarketModel.specId]) already added to the user's models. */
    val addedIds: Set<String> = emptySet(),

    /** The entry expanded for detail, if any. */
    val openModel: MnnMarketModel? = null,
    val openDetail: MnnMarketDetail? = null,
    val isLoadingDetail: Boolean = false,
) {
    /** Client-side filter: the market is one small JSON, so no per-keystroke requests. */
    val visibleModels: List<MnnMarketModel>
        get() = if (query.isBlank()) models else models.filter {
            it.name.contains(query, ignoreCase = true) ||
                it.vendor.contains(query, ignoreCase = true)
        }
}

/**
 * The MNN tab: Alibaba's own model market (the list MnnLlmChat ships), downloading from
 * ModelScope -- no HuggingFace involved.
 */
class MnnMarketViewModel(private val container: AppContainer) : ViewModel() {

    private val _uiState = MutableStateFlow(MnnMarketUiState())
    val uiState: StateFlow<MnnMarketUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val models = container.mnnMarket.fetchMarket()
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        models = models,
                        addedIds = models
                            .map(MnnMarketModel::specId)
                            .filter(container.customModelStore::contains)
                            .toSet(),
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, error = e.message ?: "Could not reach the MNN market")
                }
            }
        }
    }

    fun onQueryChange(query: String) {
        _uiState.update { it.copy(query = query) }
    }

    /** Expands an entry: fetches its file list from ModelScope and judges it against this device. */
    fun open(model: MnnMarketModel) {
        if (_uiState.value.openModel?.specId == model.specId) {
            _uiState.update { it.copy(openModel = null, openDetail = null) }
            return
        }

        _uiState.update { it.copy(openModel = model, openDetail = null, isLoadingDetail = true) }
        viewModelScope.launch {
            try {
                val spec = container.mnnMarket.modelSpec(model)
                val engine = container.engines.defaultFor(spec)?.descriptor
                    ?: container.engines.all.map { it.descriptor }
                        .firstOrNull { it.canLoad(spec.format) }
                    ?: error("MNN engine is not part of this build")
                val fit = ModelFitEvaluator.evaluateBest(spec, engine, container.deviceMemory)
                _uiState.update {
                    // Only publish if this entry is still the open one; a slow response must not
                    // attach itself to whatever the user opened next.
                    if (it.openModel?.specId == model.specId) {
                        it.copy(openDetail = MnnMarketDetail(spec, fit), isLoadingDetail = false)
                    } else it
                }
            } catch (e: Exception) {
                _uiState.update {
                    if (it.openModel?.specId == model.specId) {
                        it.copy(
                            isLoadingDetail = false,
                            openModel = null,
                            error = e.message ?: "Could not open ${model.name}",
                        )
                    } else it
                }
            }
        }
    }

    fun add(detail: MnnMarketDetail) {
        container.customModelStore.add(detail.spec)
        _uiState.update { it.copy(addedIds = it.addedIds + detail.spec.id) }
    }

    fun remove(detail: MnnMarketDetail) {
        container.customModelStore.remove(detail.spec.id)
        _uiState.update { it.copy(addedIds = it.addedIds - detail.spec.id) }
    }
}

@Composable
fun MnnMarketContent(
    viewModel: MnnMarketViewModel,
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
            placeholder = { Text("Filter the MNN market") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
        )

        state.error?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(16.dp),
            )
        }

        if (state.isLoading && state.models.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (state.models.isNotEmpty() && state.visibleModels.isEmpty()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Nothing in the MNN market matched. Try a family name like " +
                        "\"qwen\" or \"smol\".",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            // One column on a phone, two or three on a tablet -- see GridCardMinWidth.
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = GridCardMinWidth),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.visibleModels, key = { it.specId }) { model ->
                    MarketCard(
                        model = model,
                        isOpen = state.openModel?.specId == model.specId,
                        isLoading = state.isLoadingDetail &&
                            state.openModel?.specId == model.specId,
                        detail = if (state.openModel?.specId == model.specId) {
                            state.openDetail
                        } else null,
                        isAdded = model.specId in state.addedIds,
                        onClick = { viewModel.open(model) },
                        onAdd = viewModel::add,
                        onRemove = viewModel::remove,
                    )
                }
            }
        }
    }
}

@Composable
private fun MarketCard(
    model: MnnMarketModel,
    isOpen: Boolean,
    isLoading: Boolean,
    detail: MnnMarketDetail?,
    isAdded: Boolean,
    onClick: () -> Unit,
    onAdd: (MnnMarketDetail) -> Unit,
    onRemove: (MnnMarketDetail) -> Unit,
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
                        text = model.name.removeSuffix("-MNN"),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = buildString {
                            append(model.vendor)
                            if (model.sizeBytes > 0) append(" · ${formatBytes(model.sizeBytes)}")
                            if (model.tags.isNotEmpty()) append(" · ${model.tags.joinToString()}")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (isAdded) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = "Added",
                        Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            if (isLoading) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }

            AnimatedVisibility(visible = isOpen && detail != null) {
                detail?.let { d ->
                    Column {
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = "${d.spec.quantization.label} · " +
                                        "${formatBytes(d.spec.sizeBytes)} · " +
                                        "${d.spec.files.size} files",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.width(4.dp))
                                // The same verdict the built-in catalogue shows, computed the
                                // same way.
                                FitBadge(d.fit.verdict)
                            }

                            Spacer(Modifier.width(8.dp))

                            if (isAdded) {
                                TextButton(onClick = { onRemove(d) }) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        Modifier.size(16.dp),
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text("Added")
                                }
                            } else {
                                OutlinedButton(onClick = { onAdd(d) }) {
                                    Icon(
                                        Icons.Default.Add,
                                        contentDescription = null,
                                        Modifier.size(16.dp),
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text("Add")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
