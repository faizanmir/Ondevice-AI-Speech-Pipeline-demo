package com.example.aiagenttestapp.ui.mvi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update

/**
 * A screen's whole state, in one immutable value.
 *
 * One object, not a handful of flows: a screen rendered from several independent sources can show
 * combinations that never actually happened -- a spinner beside the results it was waiting for --
 * because each flow lands on its own frame. A single state cannot.
 */
interface UiState

/**
 * Something the user (or the system) asked for. The *only* way into a ViewModel.
 *
 * Intents are the reason this is MVI rather than a bag of public methods: every way a screen can
 * change is one branch of one sealed type, so the whole surface of a screen is readable in one
 * place, and any of it can be replayed in a test by feeding the same values back in.
 */
interface UiIntent

/**
 * Something that happens once and is then gone -- navigation, a toast, a "saved" signal.
 *
 * Deliberately *not* state. State is re-read on every recomposition and survives configuration
 * change, so a navigation modelled as state fires again on rotation and traps the user on the
 * screen it sent them to. Effects are delivered exactly once, to whoever is collecting.
 */
interface UiEffect

/**
 * Base for every ViewModel in the app: state in, intents out, effects on the side.
 *
 * The contract is deliberately small. Subclasses get [setState] to move the state forward and
 * [emitEffect] to fire a one-shot, and they implement [reduce] to say what each intent does. What
 * they do *not* get is a way to publish state from anywhere else -- [state] is read-only from
 * outside, so the only writer is the class that owns it.
 *
 * @param S this screen's state, published as [state].
 * @param I this screen's intents. [Nothing] for a screen that takes none.
 * @param E this screen's one-shot effects. [Nothing] for a screen that has none, which is most of
 *   them -- and saying so in the type is why an effect-free screen needs no collector at all.
 */
abstract class MviViewModel<S : UiState, I : UiIntent, E : UiEffect>(
    initialState: S,
) : ViewModel() {

    private val _state = MutableStateFlow(initialState)
    val state: StateFlow<S> = _state.asStateFlow()

    private val _effects = Channel<E>(Channel.BUFFERED)

    /**
     * One-shot effects.
     *
     * A [Channel] rather than a `SharedFlow`: a shared flow with no replay drops anything emitted
     * while the screen is not collecting -- which is exactly what happens across a configuration
     * change -- whereas the channel buffers it and hands it over when the screen comes back. Each
     * effect still goes to exactly one collector, which is what makes "navigate once" mean once.
     */
    val effects: Flow<E> = _effects.receiveAsFlow()

    /** The state as it is right now, for the intent handlers that need to read before they write. */
    protected val currentState: S get() = _state.value

    /** The single entry point. Everything a screen can ask for arrives here. */
    fun onIntent(intent: I) = reduce(intent)

    /**
     * Handles one intent: updates state, starts work, emits effects.
     *
     * Called straight from [onIntent] rather than through a queue, so an intent is handled on the
     * caller's frame and two intents dispatched in order are handled in that order.
     */
    protected abstract fun reduce(intent: I)

    /** Moves the state forward. The only way to write it. */
    protected fun setState(reducer: S.() -> S) {
        _state.update { it.reducer() }
    }

    /**
     * Fires a one-shot effect. Never suspends -- the channel is buffered, so this is safe to call
     * from a non-suspending intent handler.
     */
    protected fun emitEffect(effect: E) {
        _effects.trySend(effect)
    }

    /**
     * Folds a stream of external data (a database, a repository, a download tracker) into the state.
     *
     * This is the other half of the contract: intents cover what the *user* changes, this covers
     * what changes underneath them. Both land in the same single state, so the screen still has
     * only one thing to render.
     */
    protected fun <T> Flow<T>.collectIntoState(reducer: S.(T) -> S) {
        onEach { value -> setState { reducer(value) } }.launchIn(viewModelScope)
    }
}
