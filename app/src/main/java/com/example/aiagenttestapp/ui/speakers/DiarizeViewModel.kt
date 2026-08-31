package com.example.aiagenttestapp.ui.speakers

import android.content.Context
import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.example.aiagenttestapp.data.audiomodels.AudioModelRepository
import com.example.aiagenttestapp.data.notes.WavFile
import com.example.aiagenttestapp.data.benchmark.ReferenceText
import com.example.aiagenttestapp.data.speakers.DiarizationScore
import com.example.aiagenttestapp.data.speakers.DiarizeWorker
import com.example.aiagenttestapp.data.speakers.LiveDiarizeWorker
import com.example.aiagenttestapp.data.speakers.DiarizedAudioStore
import com.example.aiagenttestapp.data.speakers.DiarizedBlock
import com.example.aiagenttestapp.data.speakers.DiarizedDao
import com.example.aiagenttestapp.data.speakers.DiarizedRecording
import com.example.aiagenttestapp.data.speakers.SpeakerRepository
import com.example.aiagenttestapp.stt.AudioRecorder
import com.example.aiagenttestapp.stt.SpeechEngineKind
import com.example.aiagenttestapp.stt.SpeechModelRepository
import com.example.aiagenttestapp.stt.SpeechRecognizer
import com.example.aiagenttestapp.stt.ThreadBudget
import com.example.aiagenttestapp.stt.TranscribeLanes
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

/**
 * What a reference is scored as when nobody has said otherwise.
 *
 * English, because [com.example.aiagenttestapp.data.benchmark.Wer] treats every language it does
 * not recognise as German -- so a default of "" would quietly score English recordings under German
 * numeral rules and report the difference as errors.
 */
const val DEFAULT_LANGUAGE = "en"

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
    /**
     * The reference transcript waiting to be attached to the *next* recording, if any.
     *
     * Held here rather than only on the row because the reference is usually in hand before the
     * audio is: a corpus clip and the script that was read from arrive together, and the import
     * starts its run immediately. Anything already in the list is scored by attaching a reference
     * to it directly -- which needs no re-run, since scoring reads the stored blocks.
     */
    val pendingReference: String = "",
    /** Numeral grammar for the next recording, and what the language chips show. "en" or "de". */
    val pendingLanguage: String = DEFAULT_LANGUAGE,
    /**
     * Whether the next recording is labelled **while it is being made** -- a live session writing
     * provisional speakers and words as the microphone runs -- instead of after Stop. Off by default:
     * the live view costs a second model set for the whole recording and its labels can change.
     */
    val liveCapture: Boolean = false,
    val error: String? = null,
    /** What is missing before a run can start, or null when everything is ready. */
    val blocker: String? = null,
) : UiState

sealed interface DiarizeIntent : UiIntent {
    data class Import(val audio: Uri) : DiarizeIntent
    data object StartRecording : DiarizeIntent
    data object StopRecording : DiarizeIntent
    data class Run(val id: Long) : DiarizeIntent
    /** Play a finished recording back at the speed it was spoken, transcribing it as it goes. */
    data class PlayLive(val id: Long) : DiarizeIntent
    data class SetLiveCapture(val enabled: Boolean) : DiarizeIntent
    data class Delete(val id: Long) : DiarizeIntent
    data class SetExpectedSpeakers(val count: Int) : DiarizeIntent

    /**
     * Attaches a reference transcript, and scores against it straight away.
     *
     * A null [id] means the next recording rather than an existing one -- the two are the same
     * action to the user and differ only in whether there is a row to put it on yet. Blank text
     * clears the reference and the numbers with it, which is the only way to take one back off.
     */
    data class AttachReference(val id: Long?, val text: String) : DiarizeIntent

    /** The same, with the text still in a picked file. Fails differently, so it is its own intent. */
    data class AttachReferenceFile(val id: Long?, val file: Uri) : DiarizeIntent

    /** "en" or "de". Re-scores in place when [id] names a recording that already has a reference. */
    data class SetLanguage(val id: Long?, val code: String) : DiarizeIntent

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
    private val recognizer: SpeechRecognizer,
) : MviViewModel<DiarizeUiState, DiarizeIntent, Nothing>(DiarizeUiState()) {

    private var captureJob: Job? = null

    /** The row a live capture is writing into, from Start until Stop; null for an ordinary recording. */
    private var liveRowId: Long? = null
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

        // Opening this screen is the announcement that a run is coming, so the models start
        // loading now, behind the user's own think-time -- picking a file, pasting a reference.
        // By the time the worker asks, they are the resident instances it would have built.
        viewModelScope.launch(Dispatchers.Default) { prewarm() }
    }

    /**
     * Loads what the next run will need, before it is asked for.
     *
     * The recogniser is loaded with exactly the thread share [DiarizeWorker] will compute --
     * [TranscribeLanes] is shared between them for that reason -- because a warm model at the
     * wrong thread count gets reloaded, which pays the load twice and calls it an optimisation.
     * The naming embedder warms through [SpeakerRepository.prepare]. Diarizer lanes are *not*
     * warmed here: how many a run builds depends on the recording's length, unknown until one is
     * picked, so they stay warm between runs via the pool instead of being guessed at.
     *
     * Every failure is swallowed on purpose: pre-warming is an optimisation, and the worker loads
     * whatever is missing exactly as it would have without this.
     */
    private suspend fun prewarm() {
        runCatching {
            speakers.prepare()

            val model = speechModels.selected
            if (!model.kind.reportsWordTimings || !speechModels.isDownloaded(model)) return
            val bundle = audioModels.speaker
            if (!audioModels.isReady(bundle)) return

            val threads = ThreadBudget.concurrent(
                weights = ThreadBudget.Weights(
                    diarise = bundle.diariseWeight,
                    transcribe = model.transcribeWeight,
                ),
                fastCores = ThreadBudget.detectFastCores(),
            )
            val laneThreads = TranscribeLanes.laneThreads(appContext, threads.transcribe) {
                recognizer.hasWarmLane(model.id, it)
            }
            if (recognizer.loadedModelId != model.id ||
                recognizer.loadedThreadCount != laneThreads[0]
            ) {
                recognizer.load(speechModels.selectedPaths(), threadCount = laneThreads[0])
            }
        }
    }

    override fun reduce(intent: DiarizeIntent) = when (intent) {
        is DiarizeIntent.Import -> importAudio(intent.audio)
        DiarizeIntent.StartRecording -> startRecording()
        DiarizeIntent.StopRecording -> stopRecording()
        is DiarizeIntent.Run -> run(intent.id)
        is DiarizeIntent.PlayLive -> playLive(intent.id)
        is DiarizeIntent.SetLiveCapture -> setState { copy(liveCapture = intent.enabled) }
        is DiarizeIntent.Delete -> viewModelScope.launch {
            // Cancelled before the row goes, not after: the worker holds the whole recording in
            // memory and would carry on for minutes over audio the user has already deleted.
            DiarizeWorker.cancel(appContext, intent.id)
            LiveDiarizeWorker.cancel(appContext, intent.id)
            store.delete(intent.id)
        }.let { }
        is DiarizeIntent.SetExpectedSpeakers ->
            setState { copy(expectedSpeakers = intent.count.coerceIn(0, MAX_SPEAKERS)) }
        is DiarizeIntent.AttachReference -> attachReference(intent.id, intent.text)
        is DiarizeIntent.AttachReferenceFile -> attachReferenceFile(intent.id, intent.file)
        is DiarizeIntent.SetLanguage -> setLanguage(intent.id, intent.code)
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
            // lands on the screen now rather than as a run that fails minutes later -- which is
            // [autoStart]'s own rule, so it is the only thing that needs saying here.
            //
            // This used to also call `run()` first, under the same blocker check, which started the
            // recording twice. WorkManager's REPLACE policy hid it: the second enqueue cancelled
            // the first and the result looked right. It stopped being harmless once the reference
            // was attached here, because two coroutines then read and wrote the same row -- and the
            // loser's copy was stale, so whichever finished second silently undid the other.
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

        // A live capture gets its row now, not at Stop, and a session that follows the file as it
        // grows. If something blocks a run the recording still happens -- it is just labelled after
        // Stop like any other, and the blocker is already showing at the top of the screen.
        if (currentState.liveCapture && blockerFor() == null) {
            viewModelScope.launch {
                val id = store.adoptLive(file, "Recording ${stamp()}", currentState.expectedSpeakers)
                liveRowId = id
                LiveDiarizeWorker.enqueue(appContext, id, LiveDiarizeWorker.Mode.Follow)
            }
        }

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

            // A live capture already has its row; telling it the duration is what ends the session,
            // and the session hands the finished file to the batch worker itself.
            liveRowId?.let { id ->
                liveRowId = null
                dao.setDuration(id, capturedSamples * 1000L / AudioRecorder.SAMPLE_RATE)
                return@launch
            }

            when (val result = store.adopt(file, "Recording ${stamp()}", currentState.expectedSpeakers)) {
                is DiarizedAudioStore.Result.Failed -> setState { copy(error = result.message) }
                is DiarizedAudioStore.Result.Imported -> autoStart(result.id)
            }
        }
    }

    /**
     * Attaches a reference and re-scores, or clears both when [text] is blank.
     *
     * No re-run. The numbers come from the blocks already in the database, so a recording that
     * finished days ago is scored the moment its reference arrives -- spending minutes of two
     * models again to produce the same transcript would be work that changes nothing.
     */
    private fun attachReference(id: Long?, text: String) {
        if (id == null) {
            setState { copy(pendingReference = text, error = null) }
            return
        }
        viewModelScope.launch {
            rescore(id) { row -> ReferenceEdit(text.ifBlank { null }, row.language) }
        }
    }

    private fun attachReferenceFile(id: Long?, file: Uri) {
        viewModelScope.launch {
            val text = ReferenceText.read(appContext, file)
            if (text == null) {
                setState { copy(error = ReferenceText.UNREADABLE) }
                return@launch
            }
            attachReference(id, text)
        }
    }

    private fun setLanguage(id: Long?, code: String) {
        if (id == null) {
            setState { copy(pendingLanguage = code) }
            return
        }
        viewModelScope.launch { rescore(id) { row -> ReferenceEdit(row.referenceText, code) } }
    }

    /** The two fields a score is computed from, and the only two this path is allowed to write. */
    private data class ReferenceEdit(val text: String?, val language: String)

    /**
     * Applies [edit] to a row and recomputes its three numbers from the stored blocks.
     *
     * One path for every reason a score can go stale -- a reference arriving, being replaced, being
     * cleared, the language changing -- because the alternative is four call sites that each have to
     * remember to null the old numbers out. A row whose reference has gone must not keep the
     * percentages it was scored with.
     *
     * The write is [DiarizedDao.updateScore] and touches five columns, never the whole row. Scoring
     * is an edit distance over every word of both transcripts and takes real time on a long
     * reference, so a run can easily finish inside it -- and a whole-row write built before that
     * would put `status` back to Running afterwards, stranding the row on a progress bar with no
     * worker behind it. The row read here is only read.
     */
    private suspend fun rescore(id: Long, edit: (DiarizedRecording) -> ReferenceEdit) {
        val (raw, language) = edit(dao.byId(id) ?: return)
        // Normalised once. Blank and absent mean the same thing here, and deciding that separately
        // for the score and for the write is how a row ends up storing a reference it was not
        // scored against.
        val reference = raw?.takeIf { it.isNotBlank() }

        // Off the main thread: see above -- this is called straight from a text field.
        val score = if (reference == null) {
            null
        } else {
            val blocks = dao.blocksFor(id)
            withContext(Dispatchers.Default) { DiarizationScore.of(reference, language, blocks) }
        }

        dao.updateScore(
            id = id,
            referenceText = reference,
            language = language,
            coveragePercent = score?.coveragePercent,
            werPercent = score?.werPercent,
            speakerAccuracyPercent = score?.speakerAccuracyPercent,
        )
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
     * The same recording, transcribed as if it were being spoken now.
     *
     * A live session and a batch run on one row would write over each other, so whichever is running
     * is cancelled first. The session ends by enqueueing the batch run itself, so the row's final
     * transcript is the same one Run would have produced.
     */
    private fun playLive(id: Long) {
        val blocker = blockerFor()
        if (blocker != null) {
            setState { copy(error = blocker) }
            return
        }
        DiarizeWorker.cancel(appContext, id)
        LiveDiarizeWorker.enqueue(appContext, id, LiveDiarizeWorker.Mode.FilePaced)
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
        // Before the blocker check, not after. A run that cannot start yet still keeps the
        // reference the user lined up for it, so dealing with the missing model and pressing Run
        // scores the result -- rather than silently discarding the reference along with the run.
        val pending = currentState.pendingReference
        // Only when there is something to attach. A fresh row already holds nulls in all five
        // columns, so an import with no reference lined up was spending a read and a write to
        // write them again.
        if (pending.isNotBlank() || currentState.pendingLanguage != DEFAULT_LANGUAGE) {
            rescore(id) { ReferenceEdit(pending, currentState.pendingLanguage) }
        }
        // Spent, not sticky. A reference describes one recording; leaving it in the field would
        // silently score the next import against the wrong script and report the mismatch as a
        // catastrophic error rate. The language stays -- that is a property of the user, not of a
        // particular recording.
        if (pending.isNotBlank()) setState { copy(pendingReference = "") }

        if (blockerFor() != null) return
        start(id)
    }

    private suspend fun start(id: Long) {
        // The count is pinned onto the row at the moment the run starts, so a re-run at a
        // different setting is a different row's worth of history rather than a silent rewrite.
        //
        // [DiarizedDao.beginRun] also clears the last run's time and score, rather than leaving
        // them to be overwritten at the end -- see the note there. The reference itself survives:
        // a re-run is scored against the same text, which is the only way its number is comparable
        // to the one before it.
        LiveDiarizeWorker.cancel(appContext, id)
        dao.beginRun(id, currentState.expectedSpeakers)
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
