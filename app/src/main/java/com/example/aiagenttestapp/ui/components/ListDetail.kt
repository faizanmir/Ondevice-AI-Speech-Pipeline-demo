package com.example.aiagenttestapp.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import androidx.compose.material3.adaptive.navigation.ThreePaneScaffoldNavigator
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * A list beside its detail on a wide screen, one at a time on a narrow one.
 *
 * Extracted after the second screen wanted it rather than the first, because the first could not
 * have shown which parts were general. Two of the three rules below only became visible when
 * Settings and the benchmark rig were put side by side, and both had already been got wrong once:
 *
 *  - **Back means two different things.** With one pane showing it leaves the detail; with both it
 *    leaves the screen. Reading that off the navigator rather than off a width check is what stops
 *    the two disagreeing, which they will the moment a foldable is half-open.
 *  - **[highlighted] is not [chosen].** A detail pane always renders something, so on a wide screen
 *    it shows a default before the user picks anything -- and a list marking nothing beside it reads
 *    as "nothing selected" next to a pane full of content. On a narrow screen the list stands alone
 *    and marking a row nobody tapped would misstate where they are.
 *  - **The key is `Any`.** `ThreePaneScaffoldNavigator` is not generic, so every caller was casting
 *    at its own call site. Here it happens once.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
class ListDetailState<T : Any> internal constructor(
    @PublishedApi internal val navigator: ThreePaneScaffoldNavigator<Any>,
    private val scope: CoroutineScope,
    private val fallback: T?,
) {

    /** What the user actually picked, or null if they have not yet. */
    @Suppress("UNCHECKED_CAST")
    val chosen: T?
        get() = navigator.currentDestination?.contentKey as? T

    /**
     * What the detail pane should render.
     *
     * Null only for a screen that supplied no fallback, and that difference is real rather than a
     * convenience: Settings always has a category to show, while the benchmark rig legitimately has
     * no run selected and wants an empty pane saying so. A helper that forced a default on both
     * would make the second invent one.
     */
    val shown: T? get() = chosen ?: fallback

    /** Whether the list is beside the detail rather than instead of it. */
    val bothPanesVisible: Boolean
        get() = navigator.scaffoldValue[ListDetailPaneScaffoldRole.List] == PaneAdaptedValue.Expanded &&
            navigator.scaffoldValue[ListDetailPaneScaffoldRole.Detail] == PaneAdaptedValue.Expanded

    /** What the list should mark as selected -- see the note on this class. */
    val highlighted: T? get() = if (bothPanesVisible) shown else chosen

    /** True when back should close the detail rather than leave the screen. */
    val canGoBack: Boolean get() = navigator.canNavigateBack()

    fun select(item: T) {
        scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, item) }
    }

    fun back() {
        scope.launch { navigator.navigateBack() }
    }

    /**
     * The handler for a screen's own back affordance: closes the detail if one is open on its own,
     * otherwise does whatever the screen does.
     */
    fun onBackPressed(orElse: () -> Unit) {
        if (canGoBack) back() else orElse()
    }
}

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun <T : Any> rememberListDetailState(fallback: T? = null): ListDetailState<T> {
    val navigator = rememberListDetailPaneScaffoldNavigator()
    val scope = rememberCoroutineScope()
    return remember(navigator, scope) { ListDetailState(navigator, scope, fallback) }
}

/**
 * Renders the two panes and wires system back to [ListDetailState.back].
 *
 * The system handler is registered here rather than left to callers: it is enabled exactly when the
 * detail is on its own, which is the same condition the screen's own back arrow uses, and having one
 * of the two forget is a screen that traps the user in a detail or exits from underneath them.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun <T : Any> ListDetailPanes(
    state: ListDetailState<T>,
    modifier: Modifier = Modifier,
    listPane: @Composable () -> Unit,
    detailPane: @Composable () -> Unit,
) {
    BackHandler(enabled = state.canGoBack) { state.back() }

    ListDetailPaneScaffold(
        directive = state.navigator.scaffoldDirective,
        value = state.navigator.scaffoldValue,
        modifier = modifier,
        listPane = { AnimatedPane { listPane() } },
        detailPane = { AnimatedPane { detailPane() } },
    )
}
