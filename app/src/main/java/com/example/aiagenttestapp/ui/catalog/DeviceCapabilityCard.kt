package com.example.aiagenttestapp.ui.catalog

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.aiagent.engine.core.DeviceMemoryProfile
import com.example.aiagent.engine.core.Quantization
import com.example.aiagenttestapp.ui.components.formatBytes
import com.example.aiagenttestapp.ui.components.formatParams

/**
 * The answer to "what can my phone actually run", stated before the user has to work it out from a
 * list of file sizes.
 *
 * The quantization chips are not decoration. Precision is the single biggest lever a user has over
 * what fits -- the same phone that tops out near 2B at 8-bit will run nearly 4B at 4-bit -- and
 * making the headline recompute as they tap between them teaches that in a way no help text does.
 */
@Composable
fun DeviceCapabilityCard(
    device: DeviceMemoryProfile,
    quantization: Quantization,
    maxParamsBillions: Double,
    onQuantizationChange: (Quantization) -> Unit,
    modifier: Modifier = Modifier,
) {
    val animatedParams by animateFloatAsState(
        targetValue = maxParamsBillions.toFloat(),
        label = "max-params",
    )

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(
            Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "THIS DEVICE",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.65f),
            )

            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = formatParams(animatedParams.toDouble()),
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = "  parameters",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            Text(
                // Every number here is qualified. "8 GB" on its own is meaningless -- total,
                // available, and usable are three different numbers, and conflating them is how
                // other apps end up promising a model that then gets killed on load.
                text = "${device.advertisedRamGb} GB RAM · " +
                    "${formatBytes(device.modelRamBudgetBytes)} usable for a model · " +
                    "${formatBytes(device.freeStorageBytes)} free",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
            )

            Text(
                text = "Largest model that fits, at ${quantization.label} precision and a " +
                    "${CatalogUiState.HEADLINE_CONTEXT_TOKENS / 1024}K context:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
            )

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(PRECISION_OPTIONS) { option ->
                    FilterChip(
                        selected = option == quantization,
                        onClick = { onQuantizationChange(option) },
                        label = { Text(option.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    )
                }
            }
        }
    }
}

/** MIXED is omitted: it is a property of specific Gemma builds, not a precision a user chooses. */
private val PRECISION_OPTIONS = listOf(Quantization.Q4, Quantization.Q8, Quantization.F16)
