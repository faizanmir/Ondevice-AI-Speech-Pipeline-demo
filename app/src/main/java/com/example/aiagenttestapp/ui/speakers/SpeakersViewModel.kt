package com.example.aiagenttestapp.ui.speakers

import androidx.lifecycle.viewModelScope
import com.example.aiagenttestapp.data.notes.SpeakerRecord
import com.example.aiagenttestapp.data.speakers.EnrollResult
import com.example.aiagenttestapp.data.speakers.SpeakerRepository
import com.example.aiagenttestapp.data.speakers.TakeAnalysis
import com.example.aiagenttestapp.data.speakers.TakeProblem
import com.example.aiagenttestapp.stt.AudioRecorder
import com.example.aiagenttestapp.ui.mvi.MviViewModel
import com.example.aiagenttestapp.ui.mvi.UiIntent
import com.example.aiagenttestapp.ui.mvi.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SpeakersUiState(
    val speakers: List<SpeakerRecord> = emptyList(),

    /**
     * Speakers enrolled with a different embedding model.
     *
     * Surfaced rather than hidden because their voiceprints silently match nobody -- without saying so,
     * the app would simply appear to have forgotten them.
     */
    val stale: List<SpeakerRecord> = emptyList(),

    /** Whether the speaker models are downloaded. Nothing here works without them. */
    val available: Boolean = false,

    val isEnrolling: Boolean = false,
    val name: String = "",

    /** One slot per required take; null until that take has been recorded and analysed. */
    val takes: List<TakeAnalysis?> = List(SpeakerRepository.REQUIRED_TAKES) { null },

    val isRecording: Boolean = false,
    val recordingTake: Int? = null,
    val level: Float = 0f,
    val recordingMillis: Long = 0,

    val isAnalysing: Boolean = false,
    val isSaving: Boolean = false,

    val error: String? = null,

    /** Set when the new voice matches somebody already enrolled; the user may override. */
    val soundsLike: String? = null,
) : UiState {

    val usableTakes: Int get() = takes.count { it?.isUsable == true }

    val canFinish: Boolean
        get() = name.isNotBlank() && usableTakes >= 2 && !isRecording && !isAnalysing && !isSaving
}

sealed interface SpeakersIntent : UiIntent {
    data object BeginEnroll : SpeakersIntent
    data object CancelEnroll : SpeakersIntent
    data class NameChanged(val name: String) : SpeakersIntent

    /** The caller must already hold RECORD_AUDIO. */
    data class StartTake(val index: Int) : SpeakersIntent
    data object StopTake : SpeakersIntent

    data object Finish : SpeakersIntent

    /** "Yes, enrol them anyway" after a voice-collision warning. */
    data object ConfirmSoundsLike : SpeakersIntent
    data object DismissSoundsLike : SpeakersIntent

    data object DismissError : SpeakersIntent
    data class Delete(val id: Long) : SpeakersIntent
}

/**
 * Enrolling and managing the voices the app can recognise.
 *
 * Three takes, each checked before it counts. The checks matter more than they look: a bad voiceprint is
 * worse than no voiceprint, because it does not fail visibly -- it produces confident wrong attributions
 * in transcripts, which is far harder to notice and to undo than an unrecognised "Speaker 2".
 */
@HiltViewModel
class SpeakersViewModel @Inject constructor(
    private val speakers: SpeakerRepository,
    private val audioRecorder: AudioRecorder,
) : MviViewModel<SpeakersUiState, SpeakersIntent, Nothing>(SpeakersUiState()) {

    private var recordingJob: Job? = null

    /** Audio for the take being recorded. Discarded as soon as it becomes an embedding. */
    private val captured = mutableListOf<FloatArray>()

    init {
        speakers.observeSpeakers().collectIntoState { list -> copy(speakers = list) }

        viewModelScope.launch {
            // Resolved before setState: inside that lambda the receiver is the state, whose own
            // `speakers` list would shadow the repository.
            val ready = speakers.prepare()
            val stale = speakers.staleSpeakers()
            setState { copy(available = ready, stale = stale) }
        }
    }

    override fun reduce(intent: SpeakersIntent): Unit = when (intent) {
        SpeakersIntent.BeginEnroll -> setState {
            copy(
                isEnrolling = true,
                name = "",
                takes = List(SpeakerRepository.REQUIRED_TAKES) { null },
                error = null,
                soundsLike = null,
            )
        }

        SpeakersIntent.CancelEnroll -> cancelEnroll()
        is SpeakersIntent.NameChanged -> setState { copy(name = intent.name, error = null) }
        is SpeakersIntent.StartTake -> startTake(intent.index)
        SpeakersIntent.StopTake -> stopTake()
        SpeakersIntent.Finish -> finish(allowCollision = false)
        SpeakersIntent.ConfirmSoundsLike -> finish(allowCollision = true)
        SpeakersIntent.DismissSoundsLike -> setState { copy(soundsLike = null) }
        SpeakersIntent.DismissError -> setState { copy(error = null) }
        is SpeakersIntent.Delete -> delete(intent.id)
    }

    private fun cancelEnroll() {
        recordingJob?.cancel()
        recordingJob = null
        captured.clear()
        setState {
            copy(
                isEnrolling = false,
                isRecording = false,
                recordingTake = null,
                isAnalysing = false,
                error = null,
                soundsLike = null,
            )
        }
    }

    private fun startTake(index: Int) {
        if (currentState.isRecording || currentState.isAnalysing) return

        captured.clear()
        setState {
            copy(
                isRecording = true,
                recordingTake = index,
                recordingMillis = 0,
                level = 0f,
                error = null,
            )
        }

        recordingJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                audioRecorder.record().collect { chunk ->
                    captured += chunk.samples
                    val total = captured.sumOf { it.size }
                    setState {
                        copy(
                            level = chunk.level,
                            recordingMillis = total * 1000L / AudioRecorder.SAMPLE_RATE,
                        )
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                setState {
                    copy(
                        isRecording = false,
                        recordingTake = null,
                        error = e.message ?: "Could not record audio",
                    )
                }
            }
        }
    }

    private fun stopTake() {
        val index = currentState.recordingTake ?: return
        val job = recordingJob
        recordingJob = null

        setState { copy(isRecording = false, level = 0f, isAnalysing = true) }

        viewModelScope.launch {
            try {
                job?.cancelAndJoin()

                val samples = flatten(captured)
                captured.clear()

                val analysis = speakers.analyseTake(samples)

                setState {
                    copy(
                        isAnalysing = false,
                        recordingTake = null,
                        takes = takes.toMutableList().also { it[index] = analysis },
                        error = analysis.problem?.let(::describe),
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                setState {
                    copy(
                        isAnalysing = false,
                        recordingTake = null,
                        error = e.message ?: "Could not check the recording",
                    )
                }
            }
        }
    }

    private fun finish(allowCollision: Boolean) {
        val state = currentState
        if (!state.canFinish && !allowCollision) return

        setState { copy(isSaving = true, soundsLike = null, error = null) }

        viewModelScope.launch {
            val result = speakers.enroll(
                name = state.name,
                takes = state.takes.filterNotNull(),
                allowCollision = allowCollision,
            )

            setState {
                when (result) {
                    is EnrollResult.Success -> copy(
                        isSaving = false,
                        isEnrolling = false,
                        name = "",
                        takes = List(SpeakerRepository.REQUIRED_TAKES) { null },
                    )

                    is EnrollResult.SoundsLike -> copy(
                        isSaving = false,
                        soundsLike = result.name,
                    )

                    is EnrollResult.TakesDisagree -> copy(
                        isSaving = false,
                        error = "Those recordings do not sound like the same person. Record them " +
                            "again somewhere quieter, and make sure nobody else is talking.",
                    )

                    is EnrollResult.NameTaken -> copy(
                        isSaving = false,
                        error = "${result.name} is already enrolled.",
                    )

                    is EnrollResult.Failed -> copy(isSaving = false, error = result.message)
                }
            }
        }
    }

    private fun delete(id: Long) {
        viewModelScope.launch {
            speakers.delete(id)
            val stale = speakers.staleSpeakers()
            setState { copy(stale = stale) }
        }
    }

    private fun describe(problem: TakeProblem): String = when (problem) {
        TakeProblem.TooLittleSpeech ->
            "That take has less than ${TakeAnalysis.MIN_SPEECH_SECONDS.toInt()} seconds of speech " +
                "in it. Read the whole sentence out loud."

        TakeProblem.MultipleSpeakers ->
            "More than one voice was heard in that take. Record it again with nobody else talking."

        TakeProblem.NoVoiceprint ->
            "That recording was too quiet or too noisy to use. Try again closer to the microphone."
    }

    private fun flatten(chunks: List<FloatArray>): FloatArray {
        val total = chunks.sumOf { it.size }
        val out = FloatArray(total)
        var at = 0
        chunks.forEach { chunk ->
            System.arraycopy(chunk, 0, out, at, chunk.size)
            at += chunk.size
        }
        return out
    }

    override fun onCleared() {
        super.onCleared()
        recordingJob?.cancel()
        captured.clear()
        // The embedder is a ~29 MB native allocation; the repository is a singleton, but nothing else
        // needs it loaded once this screen is gone.
        speakers.release()
    }

    companion object {
        /**
         * Sentences to read for each take.
         *
         * Fixed sentences rather than "say something": three readings of varied, phoneme-rich text give a
         * voiceprint far more to work with than three repetitions of "hello", and having something to read
         * is also what reliably gets people past the four-second speech floor.
         */
        val PROMPTS = listOf(
            "The quick brown fox jumps over the lazy dog near the riverbank.",
            "Please check the third loading bay before the morning inspection.",
            "Six heavy wooden crates were moved to the storage area yesterday.",
        )
    }
}
