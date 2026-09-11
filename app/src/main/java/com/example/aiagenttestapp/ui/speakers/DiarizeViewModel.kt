package com.example.aiagenttestapp.ui.speakers

import android.content.Context
import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.example.aiagenttestapp.data.SettingsStore
import com.example.aiagenttestapp.data.audiomodels.AudioModelBundle
import com.example.aiagenttestapp.data.audiomodels.AudioModelRepository
import com.example.aiagenttestapp.data.audiomodels.AudioModelState
import com.example.aiagenttestapp.data.notes.WavFile
import com.example.aiagenttestapp.data.benchmark.ReferenceText
import com.example.aiagenttestapp.data.speakers.DiarizationScore
import com.example.aiagenttestapp.data.speakers.DiarizeWorker
import com.example.aiagenttestapp.data.speakers.LiveDiarizeWorker
import com.example.aiagenttestapp.data.speakers.DiarizedAudioStore
import com.example.aiagenttestapp.data.speakers.DiarizedBlock
import com.example.aiagenttestapp.data.speakers.DiarizedDao
import com.example.aiagenttestapp.data.speakers.DiarizedRecording
import com.example.aiagenttestapp.data.speakers.DiarizedStatus
import com.example.aiagenttestapp.data.speakers.SpeakerRepository
import com.example.aiagenttestapp.data.speakers.TranscriptBundle
import com.example.aiagenttestapp.data.speakers.needsNewRowFor
import com.example.aiagenttestapp.stt.AudioRecorder
import com.example.aiagenttestapp.stt.SpeechEngineKind
import com.example.aiagenttestapp.stt.SpeechModel
import com.example.aiagenttestapp.stt.SpeechModelRepository
import com.example.aiagenttestapp.stt.SpeechModelState
import com.example.aiagenttestapp.stt.SpeechRecognizer
import com.example.aiagenttestapp.stt.ThreadBudget
import com.example.aiagenttestapp.stt.TranscribeLanes
import com.example.aiagenttestapp.ui.mvi.MviViewModel
import com.example.aiagenttestapp.ui.mvi.UiEffect
import com.example.aiagenttestapp.ui.mvi.UiIntent
import com.example.aiagenttestapp.ui.mvi.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
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
    /** Diarisation chunk length in minutes, 0 for the whole recording; mirrors Settings. */
    val chunkMinutes: Int = 5,
    /**
     * The recognisers this screen may offer: only those that report word timings, because
     * attribution aligns words to speaker turns by time and a model without timings cannot run
     * here at all. The full catalogue stays on the models screen; offering a model this feature
     * would immediately refuse is a trap, not a choice.
     */
    val speechChoices: List<SpeechModel> = emptyList(),
    /** The id [speechChoices] should show as selected -- the Settings choice, default resolved. */
    val speechModelId: String = "",
    /** Download state per offered recogniser, for the chip to show progress or a retry. */
    val speechStates: Map<String, SpeechModelState> = emptyMap(),
    /** The speaker segmentation+embedding bundles on offer. */
    val speakerChoices: List<AudioModelBundle> = emptyList(),
    val speakerBundleId: String = "",
    val bundleStates: Map<String, AudioModelState> = emptyMap(),
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
    /**
     * Rows picked for a bundled export, by id. Non-empty *is* selection mode: the list's taps toggle
     * rows instead of opening them and the top bar becomes the selection bar. A set of ids rather
     * than a flag plus a set, so there is no state in which the bar is up with nothing under it.
     */
    val selected: Set<Long> = emptySet(),
    /** True from Export ZIP until the archive is handed over, so the bar can show it is working. */
    val exporting: Boolean = false,
) : UiState

sealed interface DiarizeIntent : UiIntent {
    data class Import(val audio: Uri) : DiarizeIntent
    data object StartRecording : DiarizeIntent
    data object StopRecording : DiarizeIntent
    data class Run(val id: Long) : DiarizeIntent
    /** Cancel whatever is running on this row -- a batch run or a live session -- and leave it stopped. */
    data class Stop(val id: Long) : DiarizeIntent
    /** Play a finished recording back at the speed it was spoken, transcribing it as it goes. */
    data class PlayLive(val id: Long) : DiarizeIntent
    data class SetLiveCapture(val enabled: Boolean) : DiarizeIntent
    data class Delete(val id: Long) : DiarizeIntent
    data class SetExpectedSpeakers(val count: Int) : DiarizeIntent
    data class SetChunkMinutes(val minutes: Int) : DiarizeIntent
    /** Choose a recogniser for the next run; downloads it first if it is not on disk. */
    data class SetSpeechModel(val id: String) : DiarizeIntent
    /** Choose a segmentation+embedding bundle; downloads it first if it is not on disk. */
    data class SetSpeakerBundle(val id: String) : DiarizeIntent

    /** A long-press, or a tap while selecting: puts a row into the bundled export or takes it out. */
    data class ToggleSelected(val id: Long) : DiarizeIntent
    /** Every row that has a transcript to export. Rows still running are left out -- see [TranscriptBundle.exportable]. */
    data object SelectAll : DiarizeIntent
    data object ClearSelection : DiarizeIntent
    /** Renders every selected transcript into one ZIP and hands it to the share sheet. */
    data object ExportSelected : DiarizeIntent

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

sealed interface DiarizeEffect : UiEffect {
    /**
     * The archive is written and waiting in the cache. The screen starts the share sheet, not the
     * ViewModel: a chooser needs an Activity to launch from, and the ViewModel only has the
     * application. [count] is for the sheet's title -- the file name is a time stamp, which says
     * nothing about what is inside.
     */
    data class ShareZip(val file: File, val count: Int) : DiarizeEffect
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
    private val settingsStore: SettingsStore,
) : MviViewModel<DiarizeUiState, DiarizeIntent, DiarizeEffect>(DiarizeUiState()) {

    private var captureJob: Job? = null

    /** The row a live capture is writing into, from Start until Stop; null for an ordinary recording. */
    private var liveRowId: Long? = null
    private var writer: WavFile.Writer? = null
    private var liveFile: File? = null
    private var capturedSamples = 0

    init {
        dao.observeAll().collectIntoState { rows ->
            // A selected row that was deleted, or that a re-run has put back to Running, drops out
            // of the selection here rather than at export time, so the count on the bar is never
            // higher than what Export ZIP will actually produce.
            val stillThere = rows.filter { it.status == DiarizedStatus.Done || it.status == DiarizedStatus.Stopped }
                .map { it.id }.toSet()
            copy(recordings = rows, blocker = blockerFor(), selected = selected intersect stillThere)
        }
        dao.observeAllBlocks().collectIntoState { all ->
            copy(blocks = all.groupBy { it.recordingId })
        }
        speakers.observeSpeakers().collectIntoState { copy(enrolledCount = it.size) }
        settingsStore.settings.collectIntoState {
            copy(
                chunkMinutes = it.diarizeChunkMinutes,
                speechModelId = speechModels.byIdOrDefault(it.speechModelId).id,
                speakerBundleId = audioModels.speaker.id,
            )
        }

        // The model chips. Choices are fixed at construction; what moves is each one's download
        // state, combined into one map per family so a chip can show "downloading 40%" or offer a
        // retry. The blocker is recomputed on every change because it is what tells the user a
        // model is still missing -- without this it would keep saying so after the download lands.
        val timed = speechModels.available.filter { it.kind.reportsWordTimings }
        setState { copy(speechChoices = timed, speakerChoices = audioModels.speakerBundles) }
        combine(timed.map { m -> speechModels.stateOf(m.id) }) { states ->
            timed.map { it.id }.zip(states.toList()).toMap()
        }.collectIntoState { copy(speechStates = it, blocker = blockerFor()) }
        combine(audioModels.speakerBundles.map { b -> audioModels.state(b) }) { states ->
            audioModels.speakerBundles.map { it.id }.zip(states.toList()).toMap()
        }.collectIntoState { copy(bundleStates = it, blocker = blockerFor()) }

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
        is DiarizeIntent.Stop -> stop(intent.id)
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
        is DiarizeIntent.SetChunkMinutes ->
            settingsStore.update { it.copy(diarizeChunkMinutes = intent.minutes.coerceIn(0, 60)) }
        is DiarizeIntent.SetSpeechModel -> setSpeechModel(intent.id)
        is DiarizeIntent.SetSpeakerBundle -> setSpeakerBundle(intent.id)
        is DiarizeIntent.ToggleSelected -> toggleSelected(intent.id)
        DiarizeIntent.SelectAll -> setState {
            copy(selected = recordings.filter { TranscriptBundle.exportable(it, blocks[it.id].orEmpty()) }.map { it.id }.toSet())
        }
        DiarizeIntent.ClearSelection -> setState { copy(selected = emptySet()) }
        DiarizeIntent.ExportSelected -> exportSelected()
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
        viewModelScope.launch {
            // Under different models the run is a comparison, not a retry, and gets its own row on
            // the same audio; the transcript already on this row stays. See [needsNewRowFor].
            val row = dao.byId(id) ?: return@launch
            val target = if (row.needsNewRowFor(speechModels.selected.id, audioModels.speaker.id)) {
                store.sibling(row)
            } else {
                id
            }
            start(target)
        }
    }

    private fun toggleSelected(id: Long) {
        setState {
            val row = recordings.firstOrNull { it.id == id } ?: return@setState this
            // Only a row with a transcript can be selected -- the same rule the archive applies, so
            // a row that cannot be exported never shows a tick that Export ZIP then ignores.
            if (id !in selected && !TranscriptBundle.exportable(row, blocks[id].orEmpty())) return@setState this
            copy(selected = if (id in selected) selected - id else selected + id)
        }
    }

    /**
     * Renders the selected transcripts into one archive in the cache and hands the file to the screen.
     *
     * The blocks are read from the database rather than taken from [DiarizeUiState.blocks], which
     * is one flow behind the rows: a row that finished a moment ago can be Done in `recordings`
     * while `blocks` still holds the previous run's words. An export is a record and reads the
     * record. Each row's models come from the row itself, never from the chips -- a sibling row was
     * produced under other models and its file has to say which.
     *
     * Off the main thread throughout: rendering a twenty-minute transcript is string work over
     * thousands of words, and the ZIP is deflated on the way out. The selection is cleared only
     * once the archive exists, so a failure leaves the user's picks in place to try again.
     */
    private fun exportSelected() {
        if (currentState.exporting) return
        viewModelScope.launch {
            setState { copy(exporting = true) }
            val snapshot = currentState
            val written = withContext(Dispatchers.IO) {
                runCatching {
                    val sources = snapshot.recordings
                        .filter { it.id in snapshot.selected }
                        .mapNotNull { row ->
                            val blocks = dao.blocksFor(row.id)
                            if (!TranscriptBundle.exportable(row, blocks)) return@mapNotNull null
                            TranscriptBundle.Source(
                                row, blocks, transcriptModels(row, snapshot.speechChoices, snapshot.speakerChoices),
                            )
                        }
                    val entries = TranscriptBundle.entries(sources, AudioRecorder.SAMPLE_RATE)
                    if (entries.isEmpty()) return@runCatching null
                    val directory = File(appContext.cacheDir, "reports").apply { mkdirs() }
                    val file = File(directory, TranscriptBundle.fileName(Instant.now()))
                    TranscriptBundle.write(entries, file)
                    file to entries.size
                }.getOrNull()
            }
            if (written == null) {
                setState { copy(exporting = false, error = "There is nothing to export in the selection.") }
                return@launch
            }
            setState { copy(exporting = false, selected = emptySet()) }
            emitEffect(DiarizeEffect.ShareZip(written.first, written.second))
        }
    }

    /**
     * Selecting a model *is* asking for it: a chip tapped while the files are missing enqueues the
     * download rather than leaving a selection that cannot run and a blocker naming another screen.
     * Selection is written first so the chip shows as chosen with its progress, and tapping an
     * already-selected chip retries a failed or missing download -- the tap always means "I want
     * this one working".
     */
    private fun setSpeechModel(id: String) {
        val model = currentState.speechChoices.firstOrNull { it.id == id } ?: return
        settingsStore.update { it.copy(speechModelId = id) }
        if (!speechModels.isDownloaded(model)) speechModels.enqueueDownload(model)
    }

    /** Same contract as [setSpeechModel], for the segmentation+embedding bundle. */
    private fun setSpeakerBundle(id: String) {
        val bundle = currentState.speakerChoices.firstOrNull { it.id == id } ?: return
        settingsStore.update { it.copy(speakerBundleId = id) }
        if (!audioModels.isReady(bundle)) audioModels.enqueueDownload(bundle)
    }

    /**
     * Stops whatever is running on a row.
     *
     * Both workers are cancelled because the row does not say which one is behind it, and cancelling
     * an idle unique work name is free. The status is written here rather than by the worker, since
     * a cancelled coroutine does not get to write anything -- and the diariser's native call, which
     * cannot be interrupted, may keep a core busy for up to a minute after this returns.
     */
    private fun stop(id: Long) {
        DiarizeWorker.cancel(appContext, id)
        LiveDiarizeWorker.cancel(appContext, id)
        viewModelScope.launch { dao.markStopped(id) }
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
