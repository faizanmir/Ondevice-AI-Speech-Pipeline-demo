package com.example.aiagenttestapp.ui.benchmark

import android.content.Context
import android.net.Uri
import androidx.annotation.RequiresApi
import androidx.lifecycle.viewModelScope
import com.example.aiagenttestapp.data.PlatformFeedChunk
import com.example.aiagenttestapp.data.PlatformFeedPace
import com.example.aiagenttestapp.data.SettingsStore
import com.example.aiagenttestapp.data.SliceWindow
import com.example.aiagenttestapp.data.benchmark.BenchmarkClip
import com.example.aiagenttestapp.data.benchmark.BenchmarkClipDao
import com.example.aiagenttestapp.data.benchmark.BenchmarkImporter
import com.example.aiagenttestapp.data.benchmark.BenchmarkRun
import com.example.aiagenttestapp.data.benchmark.BenchmarkRunDao
import com.example.aiagenttestapp.data.benchmark.BenchmarkRunStatus
import com.example.aiagenttestapp.data.benchmark.BenchmarkWorker
import com.example.aiagenttestapp.data.benchmark.MatchedPairs
import com.example.aiagenttestapp.data.notes.SttBackend
import com.example.aiagenttestapp.data.notes.TranscriptionCheckpoint
import android.os.Build
import com.example.aiagenttestapp.stt.PlatformSpeech
import com.example.aiagenttestapp.stt.SpeechModel
import com.example.aiagenttestapp.stt.SpeechModelRepository
import com.example.aiagenttestapp.stt.SpeechModelState
import com.example.aiagenttestapp.stt.SttLoadPlanner
import com.example.aiagenttestapp.stt.SttModelPlan
import com.example.aiagenttestapp.ui.mvi.MviViewModel
import com.example.aiagenttestapp.ui.mvi.UiIntent
import com.example.aiagenttestapp.ui.mvi.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * An import in flight: which file, and how far in.
 *
 * Exists because an import of a long recording is not instant and used to show nothing at all --
 * the sheet closed and the screen sat unchanged until the clip appeared, which for a 29-minute m4a
 * is tens of seconds of a UI that looks broken. A decode is also the one step here with no row of
 * its own to put a bar on: there is no clip yet.
 */
data class Importing(val name: String, val progress: Float)

/**
 * A language pack being fetched from the recogniser service.
 *
 * [percent] is null until the service reports any -- it reports none at all for a download it
 * merely queued, so the screen shows an indeterminate bar rather than one frozen at zero.
 */
data class PackDownload(val tag: String, val percent: Int? = null)

data class BenchmarkUiState(
    val clips: List<BenchmarkClip> = emptyList(),
    /** Runs per clip, newest first (the DAO orders them). */
    val runsByClip: Map<Long, List<BenchmarkRun>> = emptyMap(),
    val error: String? = null,
    /** Non-null while a clip is being decoded and copied. */
    val importing: Importing? = null,
    /**
     * Per clip, whether its two most recent finished runs actually differ. See [MatchedPairs].
     *
     * Held in state rather than computed in the composable because it is two full alignments over a
     * 3,000-word reference, and a recomposition is the wrong place to pay for that.
     */
    val comparisons: Map<Long, MatchedPairs.Result> = emptyMap(),
    /*
     * The settings a run will be started under, mirrored from SettingsStore.
     *
     * Mirrored rather than owned: these are the real app-wide settings, not a benchmark-local copy.
     * A separate copy would let the screen offer 16x while the worker read whatever Settings said,
     * and the run row -- which snapshots the settings at enqueue -- would then describe a third
     * thing. One value, editable from here or from Settings.
     */
    val backend: SttBackend = SttBackend.DEFAULT,
    val vadEnabled: Boolean = true,
    val feedPace: PlatformFeedPace = PlatformFeedPace.DEFAULT,
    val feedChunk: PlatformFeedChunk = PlatformFeedChunk.DEFAULT,
    /** The pack the platform recogniser will be asked for; null means the device locale. */
    val language: String? = null,
    /** Packs installed on this device, queried once. Empty until the service answers. */
    val installedPacks: List<String> = emptyList(),
    /** Packs the service says it supports but has not fetched yet. See [PlatformSpeech.Packs]. */
    val downloadablePacks: List<String> = emptyList(),
    /** Non-null while a pack is being fetched. */
    val packDownload: PackDownload? = null,
    /**
     * What the last pack request came to, in words.
     *
     * Kept because the two ways a request ends well are not the same thing and the difference
     * matters to whoever is about to start a run: "installed" means the pack can be chosen now,
     * "queued" means the system took the job and may sit on it until Wi-Fi. Without this the row
     * simply vanished and both looked identical to a request that silently failed.
     */
    val packNote: String? = null,
    /*
     * The sherpa side of the same idea as the platform block above: which model answers, and how
     * much audio it is handed at once.
     *
     * Here rather than only in Settings for the reason the language-pack rows are here -- a run is
     * started by changing one thing and going again, and a comparison that needs a different model
     * is otherwise four taps away on another screen, with nothing on this one to say which model the
     * last run used. The model list carries its own download state because the interesting
     * comparison is usually against a model the device does not have yet.
     */
    val speechModelOptions: List<SpeechModel> = emptyList(),
    val speechModelId: String? = null,
    val speechModelStates: Map<String, SpeechModelState> = emptyMap(),
    val sliceWindow: SliceWindow = SliceWindow.DEFAULT,
) : UiState {

    /** One run at a time per clip: two transcriptions would fight over the same recogniser. */
    fun isRunning(clipId: Long): Boolean =
        runsByClip[clipId].orEmpty().any { it.status == BenchmarkRunStatus.Running }
}

sealed interface BenchmarkIntent : UiIntent {
    /**
     * A clip and its reference, however the reference was supplied. [language] is "en" or "de" --
     * which numeral grammar the WER normalisation uses.
     */
    data class ImportFromFile(
        val audio: Uri,
        val transcript: Uri,
        val language: String,
    ) : BenchmarkIntent

    data class ImportText(
        val audio: Uri,
        val reference: String,
        val language: String,
    ) : BenchmarkIntent

    /** Transcribes the clip under whatever the Settings currently say, and scores it. */
    data class Run(val clipId: Long) : BenchmarkIntent

    data class DeleteClip(val clipId: Long) : BenchmarkIntent
    data object ClearError : BenchmarkIntent

    /*
     * The run parameters, changed from the screen that uses them.
     *
     * They write straight through to Settings rather than into this screen's state -- see the note
     * on [BenchmarkUiState.backend]. The next run reads them the same way every other caller does.
     */
    data class SetBackend(val backend: SttBackend) : BenchmarkIntent
    data class SetVad(val enabled: Boolean) : BenchmarkIntent
    data class SetFeedPace(val pace: PlatformFeedPace) : BenchmarkIntent
    data class SetFeedChunk(val chunk: PlatformFeedChunk) : BenchmarkIntent
    data class SetLanguage(val tag: String?) : BenchmarkIntent

    /**
     * Fetches a pack the device does not have.
     *
     * On the screen because the speech service's own download UI is not reachable from here: its
     * activity is not exported, so short of hunting through Settings by hand there is no way to add
     * the pack a comparison needs -- and running German audio against an English pack produces a
     * plausible-looking transcript of the wrong thing.
     */
    data class DownloadPack(val tag: String) : BenchmarkIntent

    /** Which sherpa model the next ONNX run uses. Writes through to Settings, like the rest. */
    data class SetSpeechModel(val modelId: String) : BenchmarkIntent

    /**
     * Fetches a speech model the device does not have.
     *
     * Same reasoning as [DownloadPack], and the same deliberate omission: it does not select what it
     * downloads. Parakeet is 670 MB, so a download started here finishes long after the tap, and
     * quietly moving the run onto it would change what the next run measures without anyone
     * choosing that.
     */
    data class DownloadSpeechModel(val modelId: String) : BenchmarkIntent

    /**
     * How long a slice the recogniser is handed.
     *
     * The one run parameter on this screen with no measurement behind its default at all -- see
     * [SliceWindow]. Offering it here is the point: it is the reason Parakeet is in the catalogue,
     * and this is the only screen that can score the answer.
     */
    data class SetSliceWindow(val window: SliceWindow) : BenchmarkIntent
}

@HiltViewModel
class BenchmarkViewModel @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val clipDao: BenchmarkClipDao,
    private val runDao: BenchmarkRunDao,
    private val importer: BenchmarkImporter,
    private val settingsStore: SettingsStore,
    private val speechModels: SpeechModelRepository,
    private val sttLoadPlanner: SttLoadPlanner,
) : MviViewModel<BenchmarkUiState, BenchmarkIntent, Nothing>(BenchmarkUiState()) {

    init {
        clipDao.observeAll().collectIntoState { copy(clips = it) }
        runDao.observeAll().collectIntoState { runs ->
            copy(runsByClip = runs.groupBy { it.clipId })
        }

        settingsStore.settings.collectIntoState { settings ->
            copy(
                backend = settings.sttBackend ?: SttBackend.DEFAULT,
                vadEnabled = settings.vadEnabled,
                feedPace = settings.platformFeedPace,
                feedChunk = settings.platformFeedChunk,
                language = settings.platformLanguage,
                // Resolved through the repository rather than taken raw: a stored id for a model
                // that no longer exists must show as the model a run would actually use, not as
                // nothing selected.
                speechModelId = speechModels.byIdOrDefault(settings.speechModelId).id,
                sliceWindow = settings.sliceWindow,
            )
        }

        setState { copy(speechModelOptions = speechModels.available) }

        // One collector per model, matching the record screen: the picker shows every model's
        // download state at once, including the ones not selected -- which is most of the value,
        // since the comparison usually needs a model that is not on the device yet.
        speechModels.available.forEach { model ->
            speechModels.stateOf(model.id).collectIntoState { state ->
                copy(speechModelStates = speechModelStates + (model.id to state))
            }
        }

        // Asked once. The query binds the recogniser service, so it is not something to repeat on
        // every recomposition -- and the answer only changes when the user installs a pack, which
        // happens in Settings, outside this process.
        if (Build.VERSION.SDK_INT >= PlatformSpeech.MIN_API) {
            viewModelScope.launch { refreshPacks() }
        }

        // Recomputed when the runs change rather than when the screen recomposes: a finished run
        // is the only thing that can alter a comparison.
        runDao.observeAll()
            .onEach { runs -> updateComparisons(runs.groupBy { it.clipId }) }
            .launchIn(viewModelScope)

        // A Running row whose job WorkManager no longer knows about would show a progress bar
        // forever; reconcile turns it into an honest failure.
        viewModelScope.launch { BenchmarkWorker.reconcile(appContext, runDao) }
    }

    override fun reduce(intent: BenchmarkIntent): Unit = when (intent) {
        is BenchmarkIntent.ImportFromFile -> import(intent.audio) { onProgress ->
            importer.importFromFile(intent.audio, intent.transcript, intent.language, onProgress)
        }

        is BenchmarkIntent.ImportText -> import(intent.audio) { onProgress ->
            importer.import(intent.audio, intent.reference, intent.language, onProgress)
        }
        is BenchmarkIntent.Run -> run(intent.clipId)
        is BenchmarkIntent.DeleteClip -> deleteClip(intent.clipId)
        BenchmarkIntent.ClearError -> setState { copy(error = null) }
        is BenchmarkIntent.SetBackend ->
            settingsStore.update { it.copy(sttBackend = intent.backend) }
        is BenchmarkIntent.SetVad ->
            settingsStore.update { it.copy(vadEnabled = intent.enabled) }
        is BenchmarkIntent.SetFeedPace ->
            settingsStore.update { it.copy(platformFeedPace = intent.pace) }
        is BenchmarkIntent.SetFeedChunk ->
            settingsStore.update { it.copy(platformFeedChunk = intent.chunk) }
        is BenchmarkIntent.SetLanguage ->
            settingsStore.update { it.copy(platformLanguage = intent.tag) }
        is BenchmarkIntent.DownloadPack -> downloadPack(intent.tag)
        is BenchmarkIntent.SetSpeechModel ->
            settingsStore.update { it.copy(speechModelId = intent.modelId) }
        is BenchmarkIntent.SetSliceWindow ->
            settingsStore.update { it.copy(sliceWindow = intent.window) }
        is BenchmarkIntent.DownloadSpeechModel -> downloadSpeechModel(intent.modelId)
    }

    /**
     * Starts a model download, and reports the one way it can fail before it begins.
     *
     * The transfer itself reports through [SpeechModelRepository.stateOf], which is derived from
     * WorkManager and therefore survives leaving this screen -- so there is nothing to hold here and
     * nothing to clean up, unlike [downloadPack].
     */
    private fun downloadSpeechModel(modelId: String) {
        val model = speechModels.modelWithId(modelId)
        if (model == null) {
            setState { copy(error = "No speech model with id $modelId") }
            return
        }
        speechModels.enqueueDownload(model)
    }

    /**
     * Fetches [tag], then re-queries so the new pack appears as something a run can be given.
     *
     * Deliberately does not select it. The pack a run should use is a decision with a measured cost
     * attached to getting it wrong, and quietly moving it because a download finished would change
     * what the next run measures without anyone choosing that.
     */
    private fun downloadPack(tag: String) {
        if (Build.VERSION.SDK_INT < PlatformSpeech.MIN_API) return
        if (currentState.packDownload != null) return

        viewModelScope.launch {
            setState { copy(packDownload = PackDownload(tag), packNote = null) }
            PlatformSpeech.download(appContext, tag)
                .catch { e ->
                    setState {
                        copy(
                            packDownload = null,
                            error = "Could not ask for $tag: ${e.message ?: e::class.simpleName}",
                        )
                    }
                }
                .collect { state ->
                    when (state) {
                        is PlatformSpeech.Download.Progress ->
                            setState { copy(packDownload = PackDownload(tag, state.percent)) }

                        PlatformSpeech.Download.Scheduled -> setState {
                            copy(packNote = "$tag queued by the system — it may wait for Wi-Fi or charge")
                        }

                        PlatformSpeech.Download.Complete ->
                            setState { copy(packNote = "$tag installed") }

                        is PlatformSpeech.Download.Failed -> setState {
                            copy(error = "$tag did not download: ${state.reason}")
                        }
                    }
                }
            // Cleared here rather than per terminal state: every one of them ends the flow, and a
            // row left saying "Fetching" after it finished is how the first version read.
            setState { copy(packDownload = null) }
            refreshPacks()
        }
    }

    /**
     * Re-asks the service which packs exist.
     *
     * An empty answer is discarded rather than written through. The query times out whenever the
     * service is busy -- which is exactly the moment after a download, when this is called -- and
     * the first version of this replaced a correct list of packs with "No packs reported", making
     * an installed en-GB unselectable until the screen was reopened.
     */
    @RequiresApi(PlatformSpeech.MIN_API)
    private suspend fun refreshPacks() {
        val packs = runCatching { PlatformSpeech.packs(appContext) }
            .getOrDefault(PlatformSpeech.Packs())
        if (packs.installed.isEmpty() && packs.downloadable.isEmpty()) return
        setState {
            copy(
                installedPacks = packs.installed,
                // Pending ones are offered nowhere: not usable, and asking again would start a
                // second download of something already on its way.
                downloadablePacks = packs.downloadable,
            )
        }
    }

    /**
     * Compares each clip's two most recent finished runs.
     *
     * The two most recent rather than the best two on purpose: a benchmark is run by changing one
     * setting and going again, so "the last two" is the comparison the user just asked for. Clips
     * whose reference has not arrived yet are skipped and picked up on the next emission.
     */
    private fun updateComparisons(runsByClip: Map<Long, List<BenchmarkRun>>) {
        viewModelScope.launch(Dispatchers.Default) {
            val clips = currentState.clips.associateBy { it.id }
            val out = mutableMapOf<Long, MatchedPairs.Result>()

            for ((clipId, runs) in runsByClip) {
                val clip = clips[clipId] ?: continue
                val finished = runs
                    .filter { it.status == BenchmarkRunStatus.Done && !it.transcript.isNullOrBlank() }
                    .take(2)
                if (finished.size < 2) continue

                MatchedPairs.compare(
                    reference = clip.referenceText,
                    hypothesisA = finished[0].transcript.orEmpty(),
                    hypothesisB = finished[1].transcript.orEmpty(),
                    lang = clip.language,
                )?.let { out[clipId] = it }
            }

            setState { copy(comparisons = out) }
        }
    }

    /** Both import paths differ only in where the reference came from; the outcome handling is one. */
    private fun import(
        audio: Uri,
        run: suspend (onProgress: (Float) -> Unit) -> BenchmarkImporter.Result,
    ) {
        viewModelScope.launch {
            val name = withContext(Dispatchers.IO) { importer.displayName(audio) } ?: "the recording"
            setState { copy(importing = Importing(name, 0f), error = null) }

            // Reported per block, which on a 56 MB WAV is hundreds of calls a second. Whole percents
            // only: past that the bar cannot move visibly and every extra emission is a recomposition
            // competing with the decode for the same cores.
            var lastPercent = -1
            val result = run { fraction ->
                val percent = (fraction * 100).toInt()
                if (percent != lastPercent) {
                    lastPercent = percent
                    setState { copy(importing = importing?.copy(progress = fraction)) }
                }
            }

            setState {
                copy(
                    importing = null,
                    error = (result as? BenchmarkImporter.Result.Failure)?.message,
                )
            }
        }
    }

    /**
     * Starts a run under the *current* settings, snapshotting them onto the run row -- the row
     * must describe what it actually ran under, not what Settings says when it is read later.
     * The Gemma model is resolved here for the same reason the record screen resolves it at stop:
     * finding out mid-run that no model fits would waste the run, and the resolved id pins a
     * process-death resume to the same model.
     */
    private fun run(clipId: Long) {
        viewModelScope.launch {
            if (currentState.isRunning(clipId)) return@launch

            val settings = settingsStore.settings.value
            val backend = settings.sttBackend ?: SttBackend.DEFAULT

            val (modelId, backendLabel) = when (backend) {
                SttBackend.GEMMA -> when (val plan = sttLoadPlanner.plan()) {
                    is SttModelPlan.Unavailable -> {
                        setState { copy(error = plan.reason) }
                        return@launch
                    }

                    is SttModelPlan.Ready ->
                        plan.resolved.model.id to "Gemma ${plan.modelName}"
                }

                SttBackend.ONNX -> {
                    // Refused up here, next to the Gemma branch that already refuses for its own
                    // reasons, so the user gets a sentence on the screen they are looking at. The
                    // worker checks too, but a run that fails there costs a row that says
                    // "failed" and a trip to the log to find out why.
                    val model = speechModels.selected
                    if (!speechModels.isDownloaded(model)) {
                        setState {
                            copy(
                                error = "${model.label} is not downloaded yet — " +
                                    "fetch it above, then run.",
                            )
                        }
                        return@launch
                    }
                    // The window is on the summary because it moves every slice boundary in the
                    // run: two rows that differ only here are not decoding the same clips, and a
                    // comparison that cannot see that is the language-pack mistake again.
                    null to "${model.label} · ${settings.onnxProvider.label} · " +
                        "${settings.sliceWindow.label} slices"
                }

                SttBackend.PLATFORM ->
                    // Feed settings *and* the language pack. The pack is here because leaving it
                    // out cost a day: 26% and 7.9% on identical audio and the same engine differed
                    // only by which regional pack answered, and neither number carried it.
                    null to "Android · ${settings.platformLanguage ?: "device default"} · " +
                        "${settings.platformFeedPace.label} feed · " +
                        "${settings.platformFeedChunk.label} chunks"
            }

            val runId = runDao.insert(
                BenchmarkRun(
                    clipId = clipId,
                    startedAtMillis = System.currentTimeMillis(),
                    settingsSummary =
                        "$backendLabel · VAD ${if (settings.vadEnabled) "on" else "off"}",
                    backend = backend.slug,
                    sttModelId = modelId,
                ),
            )
            BenchmarkWorker.enqueue(appContext, runId)
        }
    }

    private fun deleteClip(clipId: Long) {
        viewModelScope.launch {
            val clip = clipDao.byId(clipId) ?: return@launch
            clipDao.delete(clipId) // runs go with it via the cascade
            withContext(Dispatchers.IO) {
                val audio = File(clip.audioPath)
                TranscriptionCheckpoint.forAudio(audio).delete()
                audio.delete()
            }
        }
    }
}
