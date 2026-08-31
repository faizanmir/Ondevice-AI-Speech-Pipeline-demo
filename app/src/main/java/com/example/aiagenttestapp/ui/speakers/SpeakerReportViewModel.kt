package com.example.aiagenttestapp.ui.speakers

import androidx.lifecycle.viewModelScope
import com.example.aiagenttestapp.data.speakers.DiarizedDao
import com.example.aiagenttestapp.data.speakers.DiarizedRecording
import com.example.aiagenttestapp.data.speakers.TranscriptComparison
import com.example.aiagenttestapp.ui.mvi.MviViewModel
import com.example.aiagenttestapp.ui.mvi.UiIntent
import com.example.aiagenttestapp.ui.mvi.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class SpeakerReportUiState(
    /** False until the row has been read once, so "deleted" and "not read yet" are told apart. */
    val loaded: Boolean = false,
    val recording: DiarizedRecording? = null,
    /** Null while the recording has no reference, and for the moment the comparison is being computed. */
    val comparison: TranscriptComparison? = null,
) : UiState

sealed interface SpeakerReportIntent : UiIntent {
    data class Load(val id: Long) : SpeakerReportIntent
}

/**
 * Observes one speaker recording and scores it against its reference, for [SpeakerReportScreen].
 *
 * The comparison is a view of the blocks and the reference, derived here rather than stored: an
 * edit distance over a couple of thousand words is milliseconds, and keeping it out of Room means
 * no migration and no stale copy. It is recomputed only when one of its inputs changes -- the row
 * re-emits on every progress tick during a run and the blocks flow on any write to the table,
 * neither of which changes the answer -- and always off the main thread, because the first
 * version computed it inside the transcript screen's composition and a long reference stalled the
 * frame it landed on.
 *
 * Observed rather than read once: a report opened while a run is finishing would otherwise keep
 * the previous run's numbers with no way to refresh but leaving and coming back.
 */
@HiltViewModel
class SpeakerReportViewModel @Inject constructor(
    private val dao: DiarizedDao,
) : MviViewModel<SpeakerReportUiState, SpeakerReportIntent, Nothing>(SpeakerReportUiState()) {

    private var job: Job? = null

    /** Which recording [job] is following, so re-selecting the open one does not restart it. */
    private var loadedId: Long? = null

    override fun reduce(intent: SpeakerReportIntent) = when (intent) {
        is SpeakerReportIntent.Load -> load(intent.id)
    }

    private fun load(id: Long) {
        if (loadedId == id) return
        loadedId = id
        job?.cancel()
        job = viewModelScope.launch {
            launch {
                dao.observeById(id).collect { row -> setState { copy(loaded = true, recording = row) } }
            }
            launch {
                // Only the two columns the score depends on, so a progress tick does not re-score.
                val scored = dao.observeById(id)
                    .map { it?.referenceText to (it?.language ?: DEFAULT_LANGUAGE) }
                    .distinctUntilChanged()
                combine(scored, dao.observeBlocksFor(id).distinctUntilChanged()) { (reference, language), blocks ->
                    Triple(reference, language, blocks)
                }
                    .distinctUntilChanged()
                    // Latest, not each: a reference and its blocks can change a beat apart, and the
                    // score of the intermediate pair is not worth finishing.
                    .collectLatest { (reference, language, blocks) ->
                        val comparison = reference?.let {
                            withContext(Dispatchers.Default) { TranscriptComparison.of(it, language, blocks) }
                        }
                        setState { copy(comparison = comparison) }
                    }
            }
        }
    }
}
