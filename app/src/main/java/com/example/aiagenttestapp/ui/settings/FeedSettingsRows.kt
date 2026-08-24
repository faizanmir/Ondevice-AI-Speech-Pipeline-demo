package com.example.aiagenttestapp.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.aiagenttestapp.data.PlatformFeedChunk
import com.example.aiagenttestapp.data.PlatformFeedPace

/*
 * The two platform-feed controls, shared by Settings and the benchmark screen.
 *
 * They live here rather than in either screen because they are the same control in both places and
 * must stay the same control: the benchmark exists to compare feed settings, and a picker that
 * drifted from the one in Settings would let a run be configured one way and described another.
 * Both write to the same [com.example.aiagenttestapp.data.SettingsStore] field, so there is one
 * value, changeable from wherever the user happens to be.
 */

/**
 * How fast recordings are handed to the system recogniser.
 *
 * Offered for the same reason [OnnxProviderRow] is: the right rate has to be measured on a real
 * device, and rebuilding the app per rate makes that comparison too expensive to actually run. The
 * copy leads with the risk rather than the reward, because the failure mode is silent -- a feed
 * that is too fast returns a *shorter transcript*, not an error, so the user has to know to check
 * completeness before trusting a faster setting.
 */
@Composable
internal fun FeedPaceRow(
    selected: PlatformFeedPace,
    onSelect: (PlatformFeedPace) -> Unit,
    /**
     * The write size in force, so this row can say when it has no effect. A whole-slice write is a
     * single write, and a rate that changes nothing is worse than absent: it reads as a variable
     * that was set when it was not.
     */
    chunk: PlatformFeedChunk = PlatformFeedChunk.DEFAULT,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "System recogniser feed rate",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        Text(
            "Only used by the \"Android\" recogniser. How fast a recording is handed over: " +
                "faster means less waiting, but too fast makes Android's recogniser drop " +
                "sentences without any error. After changing it, check a transcript of a " +
                "recording you know against what it should say.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PlatformFeedPace.entries.forEach { pace ->
                FilterChip(
                    selected = pace == selected,
                    onClick = { onSelect(pace) },
                    label = { Text(pace.label) },
                )
            }
        }
        Text(
            if (chunk == PlatformFeedChunk.WHOLE_CLIP) {
                "Not in use: “${chunk.label}” is a single write, so there is nothing to pace."
            } else {
                selected.hint
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (chunk == PlatformFeedChunk.WHOLE_CLIP) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/**
 * How large each write to the system recogniser is.
 *
 * Sits directly under [FeedPaceRow] because the two are one experiment: the measurement behind the
 * pacing default changed write size and delay together, so neither setting alone says which mattered.
 * The copy names the whole-slice option as a reproduction of a known failure rather than a fast
 * option, because that is what it is -- it is here to be switched on deliberately and switched back.
 */
@Composable
internal fun FeedChunkRow(
    selected: PlatformFeedChunk,
    onSelect: (PlatformFeedChunk) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "System recogniser write size",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        Text(
            "Only used by the \"Android\" recogniser. How much audio goes into each write. " +
                "Handing over a whole slice at once is what made Android's recogniser drop " +
                "sentences; smaller writes are what fixed it — though the feed rate above changed " +
                "at the same time, so which one did the work is still open.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PlatformFeedChunk.entries.forEach { chunk ->
                FilterChip(
                    selected = chunk == selected,
                    onClick = { onSelect(chunk) },
                    label = { Text(chunk.label) },
                )
            }
        }
        Text(
            selected.hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
