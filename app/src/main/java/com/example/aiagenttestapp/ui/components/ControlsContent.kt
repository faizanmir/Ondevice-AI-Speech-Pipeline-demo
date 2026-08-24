package com.example.aiagenttestapp.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowWidthSizeClass

/**
 * Settings on the left, whatever they act on on the right.
 *
 * A different shape from [ListDetailPanes] and worth keeping separate, because these screens are not
 * list-detail. Nothing here is *selected*: the left pane is the same controls whichever row you are
 * looking at, and the right pane is the one thing the screen is about. Modelling that with a
 * navigator would invent a selection the screen does not have, and give it a back gesture that
 * nothing meaningful is behind.
 *
 * On a narrow screen the two become one scroll -- controls first, then the content -- which is
 * exactly what these screens already were. That is the point: a phone keeps the layout it had, and
 * the wide case stops the controls eating the top of the screen before you can see any results.
 *
 * The content is a [LazyListScope] rather than a composable so both arrangements can put it in a
 * *single* lazy list. Nesting one scrolling list inside another is the overlap this layout is meant
 * to remove, not a detail to be careful about.
 */
@Composable
fun ControlsContentPanes(
    modifier: Modifier = Modifier,
    controlsWidth: Dp = ControlsPaneWidth,
    controls: @Composable () -> Unit,
    content: LazyListScope.() -> Unit,
) {
    val wide = currentWindowAdaptiveInfo().windowSizeClass.windowWidthSizeClass !=
        WindowWidthSizeClass.COMPACT

    if (wide) {
        Row(modifier.fillMaxSize()) {
            Column(
                Modifier
                    .width(controlsWidth)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    // Roomier than the content side on purpose. The controls are chips, switches and
                    // their explanations packed into a fixed column, and at this width the first
                    // version wrapped a two-word chip onto two lines.
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                controls()
            }

            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
        }
    } else {
        LazyColumn(
            modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { controls() }
            }
            content()
        }
    }
}

/**
 * Width of the controls column.
 *
 * Wide enough that a row of filter chips and a sentence of explanation sit without wrapping mid-word,
 * narrow enough to leave the content side above [ContentMaxWidth] on a landscape tablet.
 */
val ControlsPaneWidth: Dp = 380.dp
