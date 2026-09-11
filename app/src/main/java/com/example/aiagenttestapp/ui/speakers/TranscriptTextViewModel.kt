package com.example.aiagenttestapp.ui.speakers

import androidx.lifecycle.viewModelScope
import com.example.aiagenttestapp.data.audiomodels.AudioModelRepository
import com.example.aiagenttestapp.data.speakers.DiarizedBlock
import com.example.aiagenttestapp.data.speakers.DiarizedDao
import com.example.aiagenttestapp.data.speakers.DiarizedRecording
import com.example.aiagenttestapp.data.speakers.TranscriptExport
import com.example.aiagenttestapp.stt.AudioRecorder
import com.example.aiagenttestapp.stt.SpeechModelRepository
import com.example.aiagenttestapp.ui.mvi.MviViewModel
import com.example.aiagenttestapp.ui.mvi.UiIntent
import com.example.aiagenttestapp.ui.mvi.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class TranscriptTextUiState(
    /** False until the row has been read once, so "deleted" and "not read yet" are told apart. */
    val loaded: Boolean = false,
    val recording: DiarizedRecording? = null,
    val blocks: List<DiarizedBlock> = emptyList(),
    /** Display names for the file header and name, resolved from the ids the run recorded. */
    val models: TranscriptExport.Models = TranscriptExport.Models(null, null, null),
    /** The file's text exactly as Export writes it (minus the byte-order mark); null with no transcript. */
    val text: String? = null,
) : UiState

sealed interface TranscriptTextIntent : UiIntent {
    data class Load(val id: Long) : TranscriptTextIntent
}

/**
 * Renders one recording's transcript the way [TranscriptExport] writes it, for [TranscriptTextScreen].
 *
 * The same renderer, on purpose: the screen exists so the file can be checked before it is sent --
 * the header, the model names, the German characters -- and a preview drawn by different code would
 * be a promise about a file it had never seen. Observed rather than read once, so a run finishing
 * underneath, or a reference attached on the transcript pane, updates the text in place.
 */
@HiltViewModel
class TranscriptTextViewModel @Inject constructor(
    private val dao: DiarizedDao,
    private val speechModels: SpeechModelRepository,
    private val audioModels: AudioModelRepository,
) : MviViewModel<TranscriptTextUiState, TranscriptTextIntent, Nothing>(TranscriptTextUiState()) {

    private var job: Job? = null
    private var loadedId: Long? = null

    override fun reduce(intent: TranscriptTextIntent) = when (intent) {
        is TranscriptTextIntent.Load -> load(intent.id)
    }

    private fun load(id: Long) {
        if (loadedId == id) return
        loadedId = id
        job?.cancel()
        job = viewModelScope.launch {
            combine(dao.observeById(id), dao.observeBlocksFor(id)) { row, blocks -> row to blocks }
                .distinctUntilChanged()
                .collectLatest { (row, blocks) ->
                    if (row == null) {
                        setState { copy(loaded = true, recording = null, blocks = emptyList(), text = null) }
                        return@collectLatest
                    }
                    val models = transcriptModels(row, speechModels.available, audioModels.speakerBundles)
                    val text = if (blocks.isEmpty()) {
                        null
                    } else {
                        withContext(Dispatchers.Default) {
                            TranscriptExport.render(row, blocks, models, AudioRecorder.SAMPLE_RATE)
                        }
                    }
                    setState { copy(loaded = true, recording = row, blocks = blocks, models = models, text = text) }
                }
        }
    }
}
