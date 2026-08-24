package com.example.aiagenttestapp.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Tablet adaptation, done with constraints rather than configuration.
 *
 * The app deliberately has no window-size-class branching: every layout here is written so the
 * *same* code produces a sensible phone layout and a sensible tablet layout. Two tools do all the
 * work:
 *
 *  - [readableWidth] caps single-column content (chat transcript, settings, row lists) at
 *    [ContentMaxWidth] and lets the parent centre it. On a phone the cap is above the screen
 *    width, so it is a no-op; on a tablet it stops a chat bubble or a settings card stretching
 *    into an unreadable 1200dp ribbon.
 *
 *  - [androidx.compose.foundation.lazy.grid.GridCells.Adaptive] with [GridCardMinWidth] turns the
 *    card lists (catalogue, notes, model markets) into grids that resolve to one column on a
 *    phone -- pixel-identical to the old list -- and two or three columns on a tablet.
 *
 * Settings is the one screen that goes further, and it is worth saying why the rule bent there
 * rather than broke. A list-detail layout is not a sizing question -- it needs to know whether both
 * panes *fit at once*, which no constraint on a single column can express -- so it uses
 * material3-adaptive's `ListDetailPaneScaffold`, exactly as this note once said the first real
 * two-pane layout should. Everything else still sizes itself with the two tools above; reach for the
 * scaffold only when a screen genuinely has two levels to show side by side, not to make one wider.
 */

/** Wider than this, a line of body text or a full-width card stops being comfortable to scan. */
val ContentMaxWidth: Dp = 840.dp

/**
 * Minimum width of one card in an adaptive grid. Chosen so a 360-411dp phone gets exactly one
 * column (the layout phones already had), a portrait tablet two, a landscape tablet three.
 */
val GridCardMinWidth: Dp = 340.dp

/**
 * Fills the available width up to [ContentMaxWidth]. The parent decides the centring -- a
 * `LazyColumn` via `horizontalAlignment`, a `Box` via `contentAlignment`.
 */
fun Modifier.readableWidth(): Modifier = widthIn(max = ContentMaxWidth).fillMaxWidth()
