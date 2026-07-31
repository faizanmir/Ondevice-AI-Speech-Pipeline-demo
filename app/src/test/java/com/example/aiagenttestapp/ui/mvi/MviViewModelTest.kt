package com.example.aiagenttestapp.ui.mvi

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests the MVI contract itself, on a stand-in ViewModel.
 *
 * The point of testing the base class rather than each screen is that every screen inherits these
 * guarantees: intents are the only entry, state is replaced not mutated, and effects fire once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MviViewModelTest {

    private data class CountState(val count: Int = 0, val label: String = "") : UiState

    private sealed interface CountIntent : UiIntent {
        data object Increment : CountIntent
        data class SetLabel(val label: String) : CountIntent
        data object Announce : CountIntent
    }

    private data class Announced(val count: Int) : UiEffect

    private class CountViewModel(
        source: MutableStateFlow<String> = MutableStateFlow(""),
    ) : MviViewModel<CountState, CountIntent, Announced>(CountState()) {

        init {
            source.collectIntoState { value -> copy(label = value) }
        }

        override fun reduce(intent: CountIntent) = when (intent) {
            CountIntent.Increment -> setState { copy(count = count + 1) }
            is CountIntent.SetLabel -> setState { copy(label = intent.label) }
            CountIntent.Announce -> emitEffect(Announced(currentState.count))
        }
    }

    @Before
    fun setUp() {
        // viewModelScope is hard-wired to Dispatchers.Main; point it at the test scheduler.
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `intents move the state forward`() = runTest {
        val vm = CountViewModel()

        vm.onIntent(CountIntent.Increment)
        vm.onIntent(CountIntent.Increment)
        vm.onIntent(CountIntent.SetLabel("hits"))

        assertEquals(CountState(count = 2, label = "hits"), vm.state.value)
    }

    @Test
    fun `state is replaced, never mutated in place`() = runTest {
        val vm = CountViewModel()
        val before = vm.state.value

        vm.onIntent(CountIntent.Increment)

        // The old value a composable already read must not have changed underneath it -- that is
        // what makes recomposition correct.
        assertEquals(0, before.count)
        assertEquals(1, vm.state.value.count)
    }

    @Test
    fun `an effect is delivered once, not replayed`() = runTest {
        val vm = CountViewModel()
        val received = mutableListOf<Announced>()
        // Unconfined so the collector is actually running before the effects are emitted, the way a
        // screen's LaunchedEffect already is.
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.effects.toList(received)
        }

        vm.onIntent(CountIntent.Increment)
        vm.onIntent(CountIntent.Announce)
        vm.onIntent(CountIntent.Announce)

        collector.cancel()
        assertEquals(listOf(Announced(1), Announced(1)), received)
    }

    @Test
    fun `an effect emitted before anyone collects is buffered, not dropped`() = runTest {
        val vm = CountViewModel()

        // Emitted while the screen is away -- a configuration change, say. A SharedFlow with no
        // replay would drop this; the channel hands it over once someone subscribes.
        vm.onIntent(CountIntent.Announce)

        val received = mutableListOf<Announced>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.effects.toList(received)
        }
        collector.cancel()

        assertEquals(listOf(Announced(0)), received)
    }

    @Test
    fun `external data folds into the same single state`() = runTest {
        val source = MutableStateFlow("initial")
        val vm = CountViewModel(source)

        assertEquals("initial", vm.state.value.label)

        source.value = "updated"
        assertEquals("updated", vm.state.value.label)

        // And a user intent still lands on the same object, not a competing one.
        vm.onIntent(CountIntent.Increment)
        assertTrue(vm.state.value.count == 1 && vm.state.value.label == "updated")
    }
}
