@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.example.aiagenttestapp.ui.speakers

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.aiagenttestapp.data.speakers.TranscriptComparison
import com.example.aiagenttestapp.stt.AudioRecorder

/**
 * The comparison report for one speaker transcript, on a screen of its own.
 *
 * It began as a card in the transcript pane, between the reference editor and the turns. On a real
 * transcript that card is long -- four tiles, a speaker table, the misattributed stretches and
 * hundreds of word errors -- so the turns it was meant to explain sat below the fold, and every
 * look at the transcript meant scrolling past the report first. Here the report has the whole
 * screen and the transcript pane has its turns back; one tap separates them.
 *
 * Read-only. The reference is edited where it is attached, on the transcript pane; this screen
 * follows the row, so a reference changed there is re-scored here without reopening.
 */
@Composable
fun SpeakerReportScreen(
    viewModel: SpeakerReportViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val recording = state.recording
    val comparison = state.comparison

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(recording?.name ?: "Comparison", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        when {
            !state.loaded -> Centred(padding) { ScoringInProgress("Opening…") }

            recording == null -> Centred(padding) {
                Text("This recording has been deleted.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            recording.referenceText == null -> Centred(padding) {
                Text(
                    "No reference transcript is attached. Attach one on the transcript screen and " +
                        "the comparison appears here.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            comparison == null -> Centred(padding) {
                ScoringInProgress("Scoring against the reference…")
            }

            else -> Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ComparisonReport(comparison)
            }
        }
    }
}

/**
 * The wait animation: a short waveform whose bars pulse in sequence, with a line saying what the
 * wait is for.
 *
 * Drawn rather than shipped as a GIF because playing an image file needs a decoder library the app
 * does not carry (no Coil, no Lottie), while the same infinite-transition idiom already animates
 * the chat's typing dots and the recorder's level meter. Bars, not a spinner: a spinner is the
 * screen saying "something, eventually", and this screen knows exactly what it is doing --
 * reading a transcript back and scoring it word by word -- so it can look like that.
 */
@Composable
private fun ScoringInProgress(label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        val transition = rememberInfiniteTransition(label = "scoring")
        // Uneven resting heights so the row reads as a waveform even mid-pulse.
        val resting = listOf(10.dp, 20.dp, 26.dp, 16.dp, 22.dp, 12.dp)
        Row(
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.height(32.dp),
        ) {
            resting.forEachIndexed { index, tall ->
                val grow by transition.animateFloat(
                    initialValue = 0.35f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 450, delayMillis = index * 110, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "bar$index",
                )
                Box(
                    Modifier
                        .size(width = 5.dp, height = tall * grow)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
                )
            }
        }
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Centred(
    padding: PaddingValues,
    content: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
        content()
    }
}

/**
 * What went wrong, itemised.
 *
 * Three numbers on the row say how much; this says what -- and the first version said it as a wall
 * of identical small lines, which nobody could scan. Now: a strip of the four numbers to read first;
 * one row per reference speaker with a bar, so a weak match is visible before it is read; the
 * stretches given to the wrong person as time-stamped entries; and the word errors as diff tokens --
 * the reference word struck through, the transcript's word beside it, the surrounding reference
 * words dimmed on the right -- filterable by kind, because "is it names, numbers or noise" is the
 * question this list exists to answer. Twenty rows to start; a long transcript has hundreds and the
 * first twenty usually settle it.
 */
@Composable
private fun ComparisonReport(c: TranscriptComparison) {
    var kind by remember(c) { mutableStateOf<TranscriptComparison.ErrorKind?>(null) }
    var shown by remember(c) { mutableStateOf(20) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val removed = MaterialTheme.colorScheme.error
    val added = MaterialTheme.colorScheme.tertiary

    // The numbers, big enough to read first.
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        StatTile("WER", "%.1f%%".format(c.werPercent), "%,d ref words".format(c.referenceWords))
        StatTile(
            "Speakers",
            c.speakerAccuracyPercent?.let { "%.1f%%".format(it) } ?: "—",
            if (c.speakerAccuracyPercent == null) "no tags in reference" else "of matched words",
        )
        StatTile("Coverage", "%.0f%%".format(c.coveragePercent), "%,d transcribed".format(c.hypothesisWords))
        StatTile(
            "Errors",
            "%,d".format(c.substitutions + c.deletions + c.insertions),
            "${c.substitutions} sub · ${c.deletions} drop · ${c.insertions} add",
        )
    }

    if (c.speakers.isNotEmpty()) {
        HorizontalDivider()
        SectionLabel("Speakers")
        c.speakers.forEach { row ->
            val weak = row.transcriptLabel == null || row.percent < 90
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "[${row.referenceLabel.uppercase()}]",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = muted,
                    modifier = Modifier.width(44.dp),
                )
                Text(
                    row.transcriptLabel ?: "nobody",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (weak) removed else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.width(120.dp),
                )
                LinearProgressIndicator(
                    progress = { (row.percent / 100.0).toFloat() },
                    modifier = Modifier.weight(1f).height(6.dp),
                    color = if (weak) removed else MaterialTheme.colorScheme.primary,
                )
                Text(
                    "%.0f%% · %,d words".format(row.percent, row.comparedWords),
                    style = MaterialTheme.typography.labelMedium,
                    color = muted,
                    modifier = Modifier.width(120.dp),
                )
            }
        }

        val totalWrong = c.misattributed.sumOf { it.words }
        SectionLabel(
            if (c.misattributed.isEmpty()) "Wrong speaker · none"
            else "Wrong speaker · ${c.misattributed.size} stretches · $totalWrong words",
        )
        if (c.misattributed.isEmpty()) {
            Text("Every matched word is with the right person.", style = MaterialTheme.typography.bodySmall, color = muted)
        }
        c.misattributed.sortedByDescending { it.words }.take(8).sortedBy { it.startSample }.forEach { m ->
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    formatClock(m.startSample * 1000L / AudioRecorder.SAMPLE_RATE),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = muted,
                    modifier = Modifier.width(44.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = muted)) { append("[${m.referenceLabel.uppercase()}] ") }
                            withStyle(SpanStyle(color = removed, fontWeight = FontWeight.Medium)) { append(m.actualLabel) }
                            withStyle(SpanStyle(color = muted)) { append("  ${m.words} words") }
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text("“${m.excerpt}”", style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, color = muted)
                }
            }
        }
    }

    HorizontalDivider()
    val errors = c.wordErrors
    SectionLabel(if (errors.isEmpty()) "Word errors · none" else "Word errors · ${errors.size}")
    if (errors.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FilterChip(selected = kind == null, onClick = { kind = null; shown = 20 }, label = { Text("All ${errors.size}") })
            listOf(
                TranscriptComparison.ErrorKind.Substitution to "Substituted ${c.substitutions}",
                TranscriptComparison.ErrorKind.Deletion to "Dropped ${c.deletions}",
                TranscriptComparison.ErrorKind.Insertion to "Added ${c.insertions}",
            ).forEach { (k, label) ->
                FilterChip(selected = kind == k, onClick = { kind = k; shown = 20 }, label = { Text(label) })
            }
        }
        val filtered = if (kind == null) errors else errors.filter { it.kind == kind }
        filtered.take(shown).forEach { e ->
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    buildAnnotatedString {
                        when (e.kind) {
                            TranscriptComparison.ErrorKind.Substitution -> {
                                withStyle(SpanStyle(color = removed, textDecoration = TextDecoration.LineThrough)) { append(e.reference) }
                                withStyle(SpanStyle(color = muted)) { append("  →  ") }
                                withStyle(SpanStyle(color = added, fontWeight = FontWeight.Medium)) { append(e.hypothesis) }
                            }
                            TranscriptComparison.ErrorKind.Deletion -> {
                                withStyle(SpanStyle(color = removed, textDecoration = TextDecoration.LineThrough)) { append(e.reference) }
                                withStyle(SpanStyle(color = muted)) { append("  dropped") }
                            }
                            TranscriptComparison.ErrorKind.Insertion -> {
                                withStyle(SpanStyle(color = added, fontWeight = FontWeight.Medium)) { append("+ ${e.hypothesis}") }
                                withStyle(SpanStyle(color = muted)) { append("  added") }
                            }
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1.2f),
                )
                Text(
                    if (e.context.isBlank()) "" else "…${e.context}",
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                    fontStyle = FontStyle.Italic,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (filtered.size > shown) {
            TextButton(onClick = { shown = minOf(filtered.size, shown + 50) }) {
                Text("Show ${minOf(50, filtered.size - shown)} more of ${filtered.size - shown}")
            }
        }
        val repeated = c.topPairs.filter { it.third > 1 }.take(6)
        if (repeated.isNotEmpty()) {
            SectionLabel("Most repeated")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                repeated.forEach { (a, b, n) ->
                    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Text(
                            buildAnnotatedString {
                                withStyle(SpanStyle(color = removed, textDecoration = TextDecoration.LineThrough)) { append(a) }
                                withStyle(SpanStyle(color = muted)) { append(" → ") }
                                withStyle(SpanStyle(color = added)) { append(b) }
                                withStyle(SpanStyle(color = muted)) { append("  ×$n") }
                            },
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.StatTile(label: String, value: String, note: String) {
    Column(Modifier.weight(1f)) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
}
