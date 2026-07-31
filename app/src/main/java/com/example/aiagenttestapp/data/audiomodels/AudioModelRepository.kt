package com.example.aiagenttestapp.data.audiomodels

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.aiagenttestapp.data.ArchiveExtractor
import com.example.aiagenttestapp.data.downloadToFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Downloads and tracks the optional audio-model bundles: speaker identification and spoken keywords.
 *
 * Same discipline as [com.example.aiagenttestapp.stt.SpeechModelRepository] -- a WorkManager
 * foreground job does the transfer so it survives leaving the screen, backgrounding and process
 * death, and download state is *derived* from WorkManager rather than written by the download loop,
 * so it stays correct when the app restarts mid-transfer.
 *
 * One repository with two bundle descriptors rather than a class per feature: "several files that are
 * only useful together, either all present or the feature is off" is one problem, and the only thing
 * that differs between the two is whether the bytes arrive as plain files or inside a tarball -- which
 * is what [BundlePayload] exists to say.
 */
class AudioModelRepository(context: Context) {

    private val root = File(context.applicationContext.filesDir, "audio-models").apply { mkdirs() }

    private val workManager = WorkManager.getInstance(context.applicationContext)

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    /** Only for deriving state; the repository is an app-lifetime singleton, so never cancelled. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val bundles: List<AudioModelBundle> = AudioModelCatalog.all

    private val states: Map<String, MutableStateFlow<AudioModelState>> =
        bundles.associate { it.id to MutableStateFlow(stateOnDisk(it)) }

    init {
        // The single writer of these states: whatever WorkManager says, reconciled with the disk.
        // Work surviving from a previous process shows up here too.
        scope.launch {
            workManager.getWorkInfosByTagFlow(WORK_TAG).collect { infos ->
                bundles.forEach { bundle ->
                    states.getValue(bundle.id).value = deriveState(bundle, infos)
                }
            }
        }
    }

    fun state(bundle: AudioModelBundle): StateFlow<AudioModelState> =
        states.getValue(bundle.id).asStateFlow()

    fun bundleWithId(id: String): AudioModelBundle? = bundles.firstOrNull { it.id == id }

    val speaker: AudioModelBundle get() = AudioModelCatalog.SPEAKER
    val keywords: AudioModelBundle get() = AudioModelCatalog.KEYWORDS

    /**
     * Every file the bundle promises is present and usable.
     *
     * Direct files must match their published size exactly. Archive members are only checked for
     * presence and non-emptiness -- see [BundlePayload.Archive] for why their byte counts are not
     * something this app is entitled to assert.
     */
    fun isReady(bundle: AudioModelBundle): Boolean = when (val payload = bundle.payload) {
        is BundlePayload.DirectFiles ->
            payload.files.all { fileFor(bundle, it.name).length() == it.sizeBytes }

        is BundlePayload.Archive ->
            payload.entries.all { fileFor(bundle, it.localName).length() > 0L }
    }

    /** Absolute path of one of the bundle's files. Only meaningful once [isReady]. */
    fun fileFor(bundle: AudioModelBundle, localName: String): File =
        File(dirFor(bundle), localName)

    fun refresh() {
        bundles.forEach { states.getValue(it.id).value = stateOnDisk(it) }
    }

    /**
     * Enqueues the download as persistent foreground work. Safe to call repeatedly:
     * [ExistingWorkPolicy.KEEP] ignores the request while one is running and starts a fresh one once
     * the last attempt finished, so this doubles as the retry path after a failure or a cancel.
     */
    fun enqueueDownload(bundle: AudioModelBundle) {
        val request = OneTimeWorkRequestBuilder<AudioModelDownloadWorker>()
            .setInputData(workDataOf(KEY_BUNDLE_ID to bundle.id))
            .addTag(WORK_TAG)
            .addTag(bundleTag(bundle.id))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()
        workManager.enqueueUniqueWork(uniqueName(bundle.id), ExistingWorkPolicy.KEEP, request)
    }

    fun cancelDownload(bundle: AudioModelBundle) {
        workManager.cancelUniqueWork(uniqueName(bundle.id))
    }

    fun delete(bundle: AudioModelBundle) {
        dirFor(bundle).deleteRecursively()
        refresh()
    }

    private fun dirFor(bundle: AudioModelBundle): File =
        File(root, bundle.id).apply { mkdirs() }

    private fun stateOnDisk(bundle: AudioModelBundle): AudioModelState =
        if (isReady(bundle)) AudioModelState.Ready else AudioModelState.NotDownloaded

    private fun deriveState(bundle: AudioModelBundle, infos: List<WorkInfo>): AudioModelState {
        // Files complete on disk always win: a stale FAILED record in WorkManager's database must not
        // override a bundle that is actually there.
        if (isReady(bundle)) return AudioModelState.Ready

        // Finished attempts linger next to new ones, so prefer live work: running, then queued, then
        // the most recent failure.
        val group = infos.filter { it.tags.contains(bundleTag(bundle.id)) }
        val live = group.firstOrNull { it.state == WorkInfo.State.RUNNING }
            ?: group.firstOrNull {
                it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED
            }
            ?: group.firstOrNull { it.state == WorkInfo.State.FAILED }

        return when (live?.state) {
            WorkInfo.State.RUNNING ->
                AudioModelState.Downloading(live.progress.getFloat(KEY_PROGRESS, 0f))
            // Queued -- waiting for a network, or for the worker to spin up. Show 0% rather than
            // nothing, so the tap visibly took.
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED ->
                AudioModelState.Downloading(0f)
            WorkInfo.State.FAILED ->
                AudioModelState.Failed(live.outputData.getString(KEY_ERROR) ?: "Download failed")
            else -> AudioModelState.NotDownloaded
        }
    }

    /**
     * The bytes-to-disk work, run inside [AudioModelDownloadWorker]. Reports progress in [0, 1] and
     * throws on failure. Deliberately writes no state: the repository derives it from WorkManager,
     * which stays correct even when this runs in a restarted process.
     */
    internal suspend fun performDownload(
        bundle: AudioModelBundle,
        onProgress: suspend (Float) -> Unit,
    ) = withContext(Dispatchers.IO) {
        if (isReady(bundle)) return@withContext

        when (val payload = bundle.payload) {
            is BundlePayload.DirectFiles -> downloadDirect(bundle, payload, onProgress)
            is BundlePayload.Archive -> downloadArchive(bundle, payload, onProgress)
        }
    }

    private suspend fun downloadDirect(
        bundle: AudioModelBundle,
        payload: BundlePayload.DirectFiles,
        onProgress: suspend (Float) -> Unit,
    ) {
        var completed = 0L
        val total = payload.files.sumOf { it.sizeBytes }

        for (file in payload.files) {
            val target = fileFor(bundle, file.name)
            if (target.length() == file.sizeBytes) {
                completed += file.sizeBytes
                continue
            }

            // Straight to a .part and renamed on success: a half-written ONNX that looks finished
            // would be handed to the native loader and take the process down.
            val part = File(dirFor(bundle), "${file.name}.part")
            downloadToFile(client, file.url, part, file.name) { bytes ->
                onProgress(((completed + bytes).toFloat() / total).coerceIn(0f, 1f))
            }

            if (part.length() != file.sizeBytes) {
                part.delete()
                throw IOException("${file.name} downloaded to the wrong size")
            }
            if (!part.renameTo(target)) throw IOException("Could not save ${file.name}")

            completed += file.sizeBytes
        }
    }

    /**
     * Fetches the tarball, unpacks the members we need, then throws the archive away.
     *
     * Progress is scaled to 90%: unpacking is genuinely slow enough to notice on a phone (bzip2 is
     * not cheap), and a bar that sat at 100% through it would read as a hang.
     */
    private suspend fun downloadArchive(
        bundle: AudioModelBundle,
        payload: BundlePayload.Archive,
        onProgress: suspend (Float) -> Unit,
    ) {
        val dir = dirFor(bundle)
        val archive = File(dir, payload.archive.name)

        if (archive.length() != payload.archive.sizeBytes) {
            val part = File(dir, "${payload.archive.name}.part")
            downloadToFile(client, payload.archive.url, part, payload.archive.name) { bytes ->
                onProgress((bytes.toFloat() / payload.archive.sizeBytes * 0.9f).coerceIn(0f, 0.9f))
            }
            if (part.length() != payload.archive.sizeBytes) {
                part.delete()
                throw IOException("${payload.archive.name} downloaded to the wrong size")
            }
            if (!part.renameTo(archive)) throw IOException("Could not save ${payload.archive.name}")
        }

        onProgress(0.9f)

        val produced = ArchiveExtractor.extractOrThrow(archive, dir, payload.entries)

        val missing = payload.entries.map { it.localName }.filter { name ->
            produced[name]?.let { it.length() > 0L } != true
        }
        if (missing.isNotEmpty()) {
            throw IOException(
                "${payload.archive.name} did not contain ${missing.joinToString()} -- the model " +
                    "release may have changed shape",
            )
        }

        // The archive is 15 MB of no further use once unpacked.
        archive.delete()
        onProgress(1f)
    }

    companion object {
        internal const val KEY_BUNDLE_ID = "audioBundleId"
        internal const val KEY_PROGRESS = "progress"
        internal const val KEY_ERROR = "error"

        internal const val WORK_TAG = "audio-model-download"

        private fun uniqueName(bundleId: String) = "audio-model-download:$bundleId"
        private fun bundleTag(bundleId: String) = "audio-model:$bundleId"
    }
}
