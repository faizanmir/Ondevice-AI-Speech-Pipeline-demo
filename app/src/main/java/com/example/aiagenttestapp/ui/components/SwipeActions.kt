package com.example.aiagenttestapp.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * How an action should read, rather than what colour it should be.
 *
 * Callers name the intent and the tray picks the colours from the theme, so "delete" looks the same
 * everywhere it appears. Passing raw colours instead would let two screens disagree about what
 * destructive looks like, which is exactly the thing a shared component exists to prevent.
 */
enum class SwipeActionTone {
    /** Ordinary actions -- share, export, rename. */
    Neutral,

    /** Actions that destroy something. Carries the theme's error colours. */
    Destructive,
}

/** One button in the tray behind a row. */
@Immutable
data class SwipeAction(
    val label: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
    val tone: SwipeActionTone = SwipeActionTone.Neutral,
)

/**
 * A list row that slides aside to reveal a tray of [actions] behind it.
 *
 * Reveal-and-hold rather than swipe-to-dismiss, which is why this is hand-rolled instead of
 * material3's `SwipeToDismissBox`: that component commits an action when the swipe passes a
 * threshold and lets go, so it can carry exactly one action per direction and has no way to ask
 * "which of these two?". An audit report needs share *and* delete, and a delete that fires on a
 * gesture with no second tap is one flick away from losing a report the user spent minutes
 * generating.
 *
 * The row stays interactive while closed. Once open, a tap anywhere on the row closes the tray
 * instead of activating the row -- the same first-tap-dismisses rule a drawer follows, and the
 * reason a scrim is laid over the content rather than the row's own onClick being disabled (which
 * would leave the tap doing nothing at all).
 *
 * The actions are also published as accessibility custom actions, so the tray is reachable without
 * performing the gesture at all. A swipe-only affordance is invisible to TalkBack.
 *
 * @param actions revealed end-to-start; an empty list makes this a plain [Box]
 * @param enabled false pins the row shut, for rows that must not be acted on yet
 * @param shape each tray button's shape, so they match the card the caller draws on top
 */
@Composable
fun SwipeRevealBox(
    actions: List<SwipeAction>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = MaterialTheme.shapes.medium,
    content: @Composable () -> Unit,
) {
    if (actions.isEmpty() || !enabled) {
        Box(modifier) { content() }
        return
    }

    val density = LocalDensity.current
    val ltr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val scope = rememberCoroutineScope()

    // The buttons plus the gaps between them, and then how far the row actually travels: one gap
    // further, which is what opens a matching space between the row's trailing edge and the first
    // button. Without it the row slides flush against the tray and the three read as one block.
    val openDistancePx = with(density) {
        (ACTION_WIDTH * actions.size + ACTION_GAP * actions.size).toPx()
    }

    // How far the row has travelled, in pixels: 0 shut, openDistancePx fully open. Held as a
    // distance rather than a signed offset so none of the drag arithmetic has to know which way the
    // layout runs -- the direction is applied once, where the content is actually offset.
    val revealed = remember { Animatable(0f) }

    // Mirrors `revealed`, but only at rest. `revealed.value` is read inside deferred lambdas so a
    // drag does not recompose; this drives the parts that genuinely are composition (the scrim), and
    // updating it only when the tray settles keeps that to two recompositions per gesture.
    var isOpen by remember { mutableStateOf(false) }

    val close: () -> Unit = {
        scope.launch {
            revealed.animateTo(0f, SETTLE_SPRING)
            isOpen = false
        }
        Unit
    }

    // A tray whose action list changes under the user -- an audit finishing, so Share appears --
    // is a tray whose open width is now wrong. Shut it and let them swipe again.
    LaunchedEffect(actions.size) {
        revealed.snapTo(0f)
        isOpen = false
    }

    Box(modifier) {
        // Behind the content, and deliberately not part of the measurement. `matchParentSize` is
        // what makes that work: it takes no part in sizing the Box and is instead measured against
        // whatever the content settled on, in a second pass. Anything that measured the tray
        // directly would have to invent a height -- inside a LazyColumn the incoming maxHeight is
        // Constraints.Infinity, so there is no parent height to copy at that point.
        Row(
            modifier = Modifier.matchParentSize(),
            // Spaced, and each button clipped to [shape] on its own rather than the whole tray
            // being clipped as one -- that is what makes two actions read as two buttons instead
            // of one bar with a colour change down the middle.
            horizontalArrangement = Arrangement.spacedBy(ACTION_GAP, Alignment.End),
        ) {
            actions.forEach { action ->
                TrayButton(
                    action = action,
                    shape = shape,
                    onClick = {
                        close()
                        action.onClick()
                    },
                )
            }
        }

        Box(
            Modifier
                .offset { revealed.value }
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        // Towards the start opens in LTR, towards the end opens in RTL.
                        val opening = if (ltr) -delta else delta
                        scope.launch {
                            revealed.snapTo((revealed.value + opening).coerceIn(0f, openDistancePx))
                        }
                    },
                    onDragStopped = { velocity ->
                        val opening = if (ltr) -velocity else velocity
                        val target = when {
                            opening > FLING_VELOCITY -> openDistancePx
                            opening < -FLING_VELOCITY -> 0f
                            // A gesture that ran out of momentum settles to whichever end it is
                            // nearer, so a half-hearted swipe never leaves the tray stranded open.
                            revealed.value > openDistancePx / 2f -> openDistancePx
                            else -> 0f
                        }
                        revealed.animateTo(target, SETTLE_SPRING)
                        isOpen = target > 0f
                    },
                )
                .semantics {
                    customActions = actions.map { action ->
                        CustomAccessibilityAction(action.label) {
                            action.onClick()
                            true
                        }
                    }
                },
        ) {
            content()

            if (isOpen) {
                Box(
                    Modifier
                        .matchParentSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = close,
                        ),
                )
            }
        }
    }
}

@Composable
private fun TrayButton(action: SwipeAction, shape: Shape, onClick: () -> Unit) {
    // Destructive takes the full `error` role rather than `errorContainer`. In a warm palette the
    // two *container* roles land close enough together that Share and Delete read as one pink
    // block, and the button you must not hit by accident is the one that has to be unmistakable.
    val container = when (action.tone) {
        SwipeActionTone.Neutral -> MaterialTheme.colorScheme.secondaryContainer
        SwipeActionTone.Destructive -> MaterialTheme.colorScheme.error
    }
    val content = when (action.tone) {
        SwipeActionTone.Neutral -> MaterialTheme.colorScheme.onSecondaryContainer
        SwipeActionTone.Destructive -> MaterialTheme.colorScheme.onError
    }

    Column(
        modifier = Modifier
            .width(ACTION_WIDTH)
            .fillMaxHeight()
            // Clip before the background, so the corners round the fill and not just the ripple.
            .clip(shape)
            .background(container)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(action.icon, contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
        Text(
            text = action.label,
            style = MaterialTheme.typography.labelSmall,
            color = content,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** Deferred offset: reading the animation here keeps a drag out of composition entirely. */
private fun Modifier.offset(revealed: () -> Float): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(placeable.width, placeable.height) {
        placeable.placeRelative(-revealed().roundToInt(), 0)
    }
}

/** Wide enough for a one-word label under a 20dp icon, narrow enough that two fit on a phone. */
private val ACTION_WIDTH: Dp = 76.dp

/**
 * Breathing room, used twice: between the row and the first button, and between the buttons.
 *
 * The same value in both places on purpose -- an uneven gap makes the tray look like it belongs to
 * the row on one side and to nothing on the other. It is charged to the travel distance rather than
 * taken out of the buttons, so a gap never eats into the tap target.
 */
private val ACTION_GAP: Dp = 8.dp

/** Pixels a second past which a flick decides the outcome, rather than where the finger stopped. */
private const val FLING_VELOCITY = 400f

private val SETTLE_SPRING = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow,
)
