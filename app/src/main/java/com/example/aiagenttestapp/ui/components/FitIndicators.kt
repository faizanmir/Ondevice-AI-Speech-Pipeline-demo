package com.example.aiagenttestapp.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.aiagent.engine.core.FitVerdict
import com.example.aiagent.engine.core.ModelFit

/**
 * The colour language for "will this run on my phone".
 *
 * Deliberately not Material's semantic error/primary roles: this is a traffic light, and users read
 * it as one. It has to mean the same thing at a glance whether or not they read the label.
 */
data class FitPalette(
    val container: Color,
    val content: Color,
    val accent: Color,
)

@Composable
fun FitVerdict.palette(): FitPalette {
    val dark = MaterialTheme.colorScheme.surface.luminanceIsDark()
    return when (this) {
        FitVerdict.COMFORTABLE -> FitPalette(
            container = if (dark) Color(0xFF12351F) else Color(0xFFD7F2DF),
            content = if (dark) Color(0xFF7EE2A0) else Color(0xFF0B5C2B),
            accent = Color(0xFF25A55B),
        )

        FitVerdict.TIGHT -> FitPalette(
            container = if (dark) Color(0xFF3A2E10) else Color(0xFFFCEFD0),
            content = if (dark) Color(0xFFF2C866) else Color(0xFF6B4B00),
            accent = Color(0xFFD79A17),
        )

        FitVerdict.EXCEEDS_MEMORY, FitVerdict.INSUFFICIENT_STORAGE -> FitPalette(
            container = if (dark) Color(0xFF3D1A1A) else Color(0xFFFBDDDD),
            content = if (dark) Color(0xFFF29B9B) else Color(0xFF7A1D1D),
            accent = Color(0xFFD14343),
        )

        FitVerdict.UNSUPPORTED -> FitPalette(
            container = MaterialTheme.colorScheme.surfaceVariant,
            content = MaterialTheme.colorScheme.onSurfaceVariant,
            accent = MaterialTheme.colorScheme.outline,
        )
    }
}

private fun Color.luminanceIsDark(): Boolean =
    (0.299 * red + 0.587 * green + 0.114 * blue) < 0.5

/** Short label. Written from the user's point of view, not the system's. */
val FitVerdict.label: String
    get() = when (this) {
        FitVerdict.COMFORTABLE -> "Runs well"
        FitVerdict.TIGHT -> "Tight fit"
        FitVerdict.EXCEEDS_MEMORY -> "Too large"
        FitVerdict.INSUFFICIENT_STORAGE -> "No space"
        FitVerdict.UNSUPPORTED -> "Unsupported"
    }

@Composable
fun FitBadge(
    verdict: FitVerdict,
    modifier: Modifier = Modifier,
) {
    val palette = verdict.palette()
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(palette.container)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(palette.accent),
        )
        Text(
            text = verdict.label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = palette.content,
        )
    }
}

/**
 * How much of the device's RAM budget this model would take.
 *
 * The bar is the argument the badge only asserts: a model that reads "Tight fit" looks tight,
 * nearly filling the track, and one that reads "Runs well" visibly leaves room.
 */
@Composable
fun MemoryMeter(
    fit: ModelFit,
    modifier: Modifier = Modifier,
) {
    val palette = fit.verdict.palette()
    val target = if (fit.verdict == FitVerdict.EXCEEDS_MEMORY) 1f else fit.budgetUsedFraction

    val fraction by animateFloatAsState(targetValue = target, label = "memory-meter")
    val color by animateColorAsState(targetValue = palette.accent, label = "memory-meter-color")

    Box(
        modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(color),
        )
    }
}
