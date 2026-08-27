package com.example.aiagenttestapp.ui.speakers

import android.content.Context
import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.example.aiagenttestapp.data.audiomodels.AudioModelRepository
import com.example.aiagenttestapp.data.notes.WavFile
import com.example.aiagenttestapp.data.speakers.DiarizeWorker
import com.example.aiagenttestapp.data.speakers.DiarizedAudioStore
import com.example.aiagenttestapp.data.speakers.DiarizedBlock
import com.example.aiagenttestapp.data.speakers.DiarizedDao
import com.example.aiagenttestapp.data.speakers.DiarizedRecording
import com.example.aiagenttestapp.data.speakers.DiarizedStatus
import com.example.aiagenttestapp.data.speakers.SpeakerRepository
import com.example.aiagenttestapp.stt.AudioRecorder
import com.example.aiagenttestapp.stt.SpeechEngineKind
import com.example.aiagenttestapp.stt.SpeechModelRepository
import com.example.aiagenttestapp.ui.mvi.MviViewModel
import com.example.aiagenttestapp.ui.mvi.UiIntent
import com.example.aiagenttestapp.ui.mvi.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/** An import in flight: a long file takes tens of seconds to decode and there is no row for it yet. */
data class DiarizeImport(val name: String, val progress: Float)

data class DiarizeUiState(
    val recordings: List<DiarizedRecording> = emptyList(),
    /** Every block, grouped by the recording it belongs to. */
    val blocks: Map<Long, List<DiarizedBlock>> = emptyMap(),
    val importing: DiarizeImport? = null,
    /** Null unless a live take is in progress; the value is how long it has been running. */
    val recordingMillis: Long? = null,
    /**
     * How many people the *next* run is told to expect. 0 means "work it out".
     *
     * Only reaches the clustering when nobody is enrolled -- see the note in [DiarizeWorker] for the
     * measurement that put it behind that condition. Kept in state regardless so the choice survives
     * enrolling and un-enrolling someone.
     */
    val expectedSpeakers: Int = 0,
    /** How many voices are enrolled, which decides whether [expectedSpeakers] is consulted at all. */
    val enrolledCount: Int = 0,
    val error: String? = null,
    /** What is missing before a run can start, or null when everything is ready. */
    val blocker: String? = null,
) : UiState

sealed interface DiarizeIntent : UiIntent {
    data class Import(val audio: Uri) : DiarizeIntent
    data object StartRecording : DiarizeIntent
    data object StopRecording : DiarizeIntent
    data class Run(val id: Long) : DiarizeIntent
    data class Delete(val id: Long) : DiarizeIntent
    data class SetExpectedSpeakers(val count: Int) : DiarizeIntent
    data object ClearError : DiarizeIntent
}

/**
 * Drives the speaker-transcript screen.
 *
 * Audio arrives two ways and the difference stops at [DiarizedAudioStore]: an import is decoded to a
 * pipeline WAV, a live take is written as one while it is captured, and from the row onward nothing
 * downstream can tell which happened.
 */
@HiltViewModel
class DiarizeViewModel @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val dao: DiarizedDao,
    private val store: DiarizedAudioStore,
    private val audioRecorder: AudioRecorder,
    private val audioModels: AudioModelRepository,
    private val speechModels: SpeechModelRepository,
    private val speakers: SpeakerRepository,
) : MviViewModel<DiarizeUiState, DiarizeIntent, Nothing>(DiarizeUiState()) {

    private var captureJob: Job? = null
    private var writer: WavFile.Writer? = null
    private var liveFile: File? = null
    private var capturedSamples = 0

    init {
        dao.observeAll().collectIntoState { rows -> copy(recordings = rows, blocker = blockerFor()) }
        dao.observeAllBlocks().collectIntoState { all ->
            copy(blocks = all.groupBy { it.recordingId })
        }
        speakers.observeSpeakers().collectIntoState { copy(enrolledCount = it.size) }

        // A Running row whose job WorkManager has lost would show a progress bar forever.
        viewModelScope.launch { DiarizeWorker.reconcile(appContext, dao) }
    }

    override fun reduce(intent: DiarizeIntent) = when (intent) {
        is DiarizeIntent.Import -> importAudio(intent.audio)
        DiarizeIntent.StartRecording -> startRecording()
        DiarizeIntent.StopRecording -> stopRecording()
        is DiarizeIntent.Run -> run(intent.id)
        is DiarizeIntent.Delete -> viewModelScope.launch {
            // Cancelled before the row goes, not after: the worker holds the whole recording in
            // memory and would carry on for minutes over audio the user has already deleted.
            DiarizeWorker.cancel(appContext, intent.id)
            store.delete(intent.id)
        }.let { }
        is DiarizeIntent.SetExpectedSpeakers ->
            setState { copy(expectedSpeakers = intent.count.coerceIn(0, MAX_SPEAKERS)) }
        DiarizeIntent.ClearError -> setState { copy(error = null, blocker = blockerFor()) }
    }

    /**
     * What stops a run from starting, as a sentence rather than a disabled button.
     *
     * Both conditions are things the user can fix and neither is guessable from a greyed-out
     * control -- and the second one is genuinely surprising: the feature needs a *specific* speech
     * model, because it is the only one that reports word times.
     */
    private fun blockerFor(): String? {
        val model = speechModels.selected
        return when {
            !audioModels.isReady(audioModels.speaker) ->
                "Download the speaker identification models in Settings to attribute speakers."

            !model.kind.reportsWordTimings ->
                "${model.label} reports no word timings. Choose ${speechModels.wordTimingChoices}."

            !speechModels.isDownloaded(model) -> "${model.label} is not downloaded yet."

            else -> null
        }
    }

    private fun importAudio(audio: Uri) {
        viewModelScope.launch {
            setState { copy(importing = DiarizeImport("the recording", 0f), error = null) }

            // Whole percents only: a long file reports per block, which is hundreds of calls a
            // second, and past a percent the bar cannot move visibly anyway.
            var lastPercent = -1
            val result = store.import(audio, currentState.expectedSpeakers) { fraction ->
                val percent = (fraction * 100).toInt()
                if (percent != lastPercent) {
                    lastPercent = percent
                    setState { copy(importing = importing?.copy(progress = fraction)) }
                }
            }

            setState {
                copy(
                    importing = null,
                    error = (result as? DiarizedAudioStore.Result.Failed)?.message,
                    blocker = blockerFor(),
                )
            }

            // Straight into the run. Importing a file here has exactly one purpose, and an imported
            // recording that sits waiting for a second tap is a step that exists only because the
            // code was written in two pieces. Held back when something is missing, so the reason
            // lands on the screen now rather than as a run that fails minutes later.
            if (result is DiarizedAudioStore.Result.Imported && blockerFor() == null) {
                run(result.id)
            }

            (result as? DiarizedAudioStore.Result.Imported)?.let { autoStart(it.id) }
        }
    }

    private fun startRecording() {
        if (captureJob != null) return

        val file = store.newLiveFile()
        liveFile = file
        capturedSamples = 0
        writer = WavFile.Writer(file, AudioRecorder.SAMPLE_RATE)

        setState { copy(recordingMillis = 0L, error = null) }

        captureJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                audioRecorder.record().collect { chunk ->
                    // Straight to disk as it arrives, the same rule the voice recorder follows: a
                    // recording held in memory is one process death away from never having existed,
                    // and this screen's recordings are the long ones.
                    withContext(Dispatchers.IO) { writer?.append(chunk.samples) }
                    capturedSamples += chunk.samples.size
                    setState {
                        copy(recordingMillis = capturedSamples * 1000L / AudioRecorder.SAMPLE_RATE)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                setState { copy(error = e.message ?: "Could not record audio", recordingMillis = null) }
            }
        }
    }

    private fun stopRecording() {
        val job = captureJob ?: return
        captureJob = null

        viewModelScope.launch {
            job.cancelAndJoin()

            val file = liveFile
            val finished = withContext(Dispatchers.IO) {
                runCatching { writer?.finish() }.isSuccess
            }
            writer = null
            liveFile = null
            setState { copy(recordingMillis = null) }

            if (file == null || !finished) {
                setState { copy(error = "The recording could not be saved.") }
                return@launch
            }

            when (val result = store.adopt(file, "Recording ${stamp()}", currentState.expectedSpeakers)) {
                is DiarizedAudioStore.Result.Failed -> setState { copy(error = result.message) }
                is DiarizedAudioStore.Result.Imported -> autoStart(result.id)
            }
        }
    }

    private fun run(id: Long) {
        val blocker = blockerFor()
        if (blocker != null) {
            setState { copy(error = blocker) }
            return
        }
        viewModelScope.launch { start(id) }
    }

    /**
     * Starts the run a new recording exists to have.
     *
     * Automatic rather than waiting for the Run button, because there is nothing else a row on this
     * screen is for: the expected-speaker count is chosen before the audio arrives and pinned onto
     * the row by the import itself, so at this point the user has already said everything the run
     * needs. Leaving it to a tap meant importing a file and being shown a recording that sat there
     * doing nothing, with the only control that would start it one pane away.
     *
     * Silent when something blocks the run -- the missing model is already stated at the top of the
     * list, and the row keeps its Run button for once that is dealt with. Raising it as an error
     * here would say the same thing twice about an action the user did not take.
     */
    private suspend fun autoStart(id: Long) {
        if (blockerFor() != null) return
        start(id)
    }

    private suspend fun start(id: Long) {
        val row = dao.byId(id) ?: return
        // The count is pinned onto the row at the moment the run starts, so a re-run at a
        // different setting is a different row's worth of history rather than a silent rewrite.
        dao.update(
            row.copy(
                status = DiarizedStatus.Running,
                progress = 0f,
                error = null,
                expectedSpeakers = currentState.expectedSpeakers,
            ),
        )
        DiarizeWorker.enqueue(appContext, id)
    }

    private fun stamp(): String {
        val now = java.util.Date()
        return java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(now)
    }

    private companion object {
        /** Beyond this the control is noise; a recording with more voices should be left to guess. */
        const val MAX_SPEAKERS = 8
    }
}
