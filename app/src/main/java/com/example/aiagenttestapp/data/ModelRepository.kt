package com.example.aiagenttestapp.data

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.aiagent.engine.core.ModelFile
import com.example.aiagent.engine.core.ModelSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Where a model is in its lifecycle, from the UI's point of view. */
sealed interface DownloadState {
    data object NotDownloaded : DownloadState

    data class Downloading(
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val bytesPerSecond: Long,
    ) : DownloadState {
        val progress: Float
            get() = if (totalBytes <= 0) 0f
            else (bytesDownloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)

        /** Null when we cannot estimate yet -- better than showing a made-up number. */
        val secondsRemaining: Long?
            get() = if (bytesPerSecond <= 0) null
            else (totalBytes - bytesDownloaded) / bytesPerSecond
    }

    data object Downloaded : DownloadState

    data class Failed(val message: String) : DownloadState
}

/**
 * Owns model files on disk and the downloads that produce them.
 *
 * Downloads run in a WorkManager foreground job ([ModelDownloadWorker]) rather than in a screen's
 * coroutine, so they keep going when the user leaves the catalogue, backgrounds the app, or the
 * process is killed -- and they can be cancelled. They are resumable: a 3.6 GB model over a phone
 * connection will be interrupted, and making the user start again from zero is not a real product,
 * so a `.part` file plus an HTTP Range request picks up where it stopped.
 */
class ModelRepository(
    context: Context,
    private val auth: HuggingFaceAuth,
) {

    private val appContext = context.applicationContext

    private val modelsDir: File = File(appContext.filesDir, "models").apply { mkdirs() }

    private val workManager = WorkManager.getInstance(appContext)

    private val client = OkHttpClient.Builder()
        // Model files are huge, and phones are slow. A read timeout here would kill a healthy
        // download; only the connect phase gets a deadline.
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    /** Bumped when a file is deleted, so the derived state flow re-checks the disk. */
    private val diskRevision = MutableStateFlow(0)

    /** The file the engine loads: the model itself, or the entry point of a multi-file model. */
    fun fileFor(model: ModelSpec): File = File(modelsDir, model.fileName)

    private fun fileFor(file: ModelFile): File = File(modelsDir, file.relativePath)

    private fun partFileFor(file: ModelFile): File = File(modelsDir, "${file.relativePath}.part")

    /**
     * A model counts as downloaded only when every one of its files is exactly the size
     * HuggingFace advertises. A truncated file left behind by a kill would otherwise be handed to
     * the engine, which surfaces as an unexplained native crash rather than a failed download.
     *
     * A system-managed model (AICore's Gemini Nano) has no files of ours to check and is always
     * "downloaded" from the app's point of view -- whether the *OS* has it is AICore's business,
     * asked and answered by the engine at load time.
     */
    fun isDownloaded(model: ModelSpec): Boolean = model.systemManaged || model.allFiles.all { spec ->
        val file = fileFor(spec)
        file.exists() && file.length() == spec.sizeBytes
    }

    // ---- Download control ------------------------------------------------------------------------

    /**
     * Enqueues a persistent, resumable download. Safe to call repeatedly: [ExistingWorkPolicy.KEEP]
     * ignores the request while one is already running, and starts a fresh one once the last attempt
     * has finished (so this doubles as the retry path after a failure or a cancel).
     */
    fun enqueueDownload(model: ModelSpec) {
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(workDataOf(KEY_MODEL_ID to model.id))
            .addTag(WORK_TAG)
            .addTag(modelTag(model.id))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()
        workManager.enqueueUniqueWork(uniqueName(model.id), ExistingWorkPolicy.KEEP, request)
    }

    fun cancelDownload(model: ModelSpec) {
        workManager.cancelUniqueWork(uniqueName(model.id))
    }

    /**
     * Live download states, keyed by model id, for every model that has a running, queued or failed
     * download. Absent means "nothing in flight" -- the caller decides Downloaded vs NotDownloaded
     * from the disk. Re-emits on both WorkManager changes and file deletions.
     */
    val downloadStates: Flow<Map<String, DownloadState>> =
        combine(workManager.getWorkInfosByTagFlow(WORK_TAG), diskRevision) { infos, _ ->
            infos
                .groupBy { modelIdOf(it) }
                .mapNotNull { (id, group) ->
                    if (id == null) return@mapNotNull null
                    // Finished work lingers in WorkManager's DB next to a new attempt, so prefer the
                    // live one: running, then queued, then the most recent failure.
                    val relevant = group.firstOrNull { it.state == WorkInfo.State.RUNNING }
                        ?: group.firstOrNull {
                            it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED
                        }
                        ?: group.firstOrNull { it.state == WorkInfo.State.FAILED }
                        ?: return@mapNotNull null
                    stateFor(relevant)?.let { id to it }
                }
                .toMap()
        }

    private fun stateFor(info: WorkInfo): DownloadState? = when (info.state) {
        WorkInfo.State.RUNNING -> DownloadState.Downloading(
            bytesDownloaded = info.progress.getLong(KEY_DOWNLOADED, 0),
            totalBytes = info.progress.getLong(KEY_TOTAL, 0),
            bytesPerSecond = info.progress.getLong(KEY_RATE, 0),
        )
        // Queued but not yet started -- show a download in progress at 0% rather than nothing.
        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> DownloadState.Downloading(0, 0, 0)
        WorkInfo.State.FAILED -> DownloadState.Failed(
            info.outputData.getString(KEY_ERROR) ?: "Download failed",
        )
        // Success and cancel are left to the disk check: a cancelled download keeps its .part, and a
        // finished one is Downloaded only if the file is really there (e.g. not since deleted).
        WorkInfo.State.SUCCEEDED, WorkInfo.State.CANCELLED -> null
    }

    suspend fun delete(model: ModelSpec) = withContext(Dispatchers.IO) {
        // Nothing of a system-managed model is on our disk; there is nothing to delete.
        if (model.systemManaged) return@withContext

        cancelDownload(model) // in case one is mid-flight
        model.allFiles.forEach { file ->
            fileFor(file).delete()
            partFileFor(file).delete()
        }
        // A multi-file model lives in its own subdirectory; remove it once it is empty so deleted
        // models do not leave a trail of empty folders under models/.
        fileFor(model).parentFile
            ?.takeIf { it != modelsDir && it.list()?.isEmpty() == true }
            ?.delete()
        diskRevision.value++
    }

    // ---- The actual transfer, driven by ModelDownloadWorker --------------------------------------

    /**
     * Downloads [model] to disk, reporting progress through [onProgress]. Resumes from `.part`
     * files when present. Throws [IOException] on failure and propagates cancellation (leaving the
     * `.part` in place for a later resume). Called only from [ModelDownloadWorker].
     *
     * Multi-file models (MNN) download file by file; progress is reported against the model's
     * total size so the UI shows one bar, not one per file. Files already complete on disk are
     * skipped, which makes a failed multi-file download resume from the file it died in.
     */
    suspend fun performDownload(
        model: ModelSpec,
        onProgress: suspend (downloaded: Long, total: Long, bytesPerSecond: Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        var completedBytes = 0L
        for (file in model.allFiles) {
            val target = fileFor(file)
            if (target.exists() && target.length() == file.sizeBytes) {
                completedBytes += file.sizeBytes
                continue
            }
            val base = completedBytes
            downloadFile(file) { downloaded, rate ->
                onProgress(base + downloaded, model.sizeBytes, rate)
            }
            completedBytes += file.sizeBytes
        }
        Log.i(TAG, "downloaded ${model.id}")
    }

    /** Transfers one file, with `.part` + HTTP Range resume. */
    private suspend fun downloadFile(
        spec: ModelFile,
        onProgress: suspend (downloaded: Long, bytesPerSecond: Long) -> Unit,
    ) {
        val target = fileFor(spec)
        val part = partFileFor(spec)

        // A stale .part bigger than the finished file would ever be means the previous attempt was
        // writing garbage (wrong URL, an HTML error page). Start it over.
        if (part.exists() && part.length() > spec.sizeBytes) part.delete()

        val alreadyHave = if (part.exists()) part.length() else 0L

        val request = Request.Builder()
            .url(spec.url)
            .apply {
                if (alreadyHave > 0) header("Range", "bytes=$alreadyHave-")
                // Attached to every HuggingFace download, not only gated ones: HF gives
                // authenticated traffic higher rate limits, so signing in speeds up public models
                // too. But ONLY HuggingFace -- MNN-market models download from ModelScope, and
                // sending the HF bearer token to another host would leak the user's credential.
                if (spec.url.startsWith("https://huggingface.co/")) {
                    auth.authHeader()?.let { (name, value) -> header(name, value) }
                }
            }
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException(describeHttpFailure(response.code))

            // If we asked to resume and the server ignored it (200 rather than 206), the body starts
            // at byte zero -- appending would corrupt the file, so restart cleanly.
            val resuming = alreadyHave > 0 && response.code == 206
            if (alreadyHave > 0 && !resuming) part.delete()

            val startingFrom = if (resuming) alreadyHave else 0L
            val body = response.body ?: throw IOException("Empty response from server")
            part.parentFile?.mkdirs()

            body.byteStream().use { input ->
                FileOutputStream(part, /* append = */ resuming).use { output ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                    var downloaded = startingFrom
                    var lastEmitMs = 0L
                    var lastEmitBytes = startingFrom

                    while (true) {
                        // Between buffers, honour cancellation -- WorkManager cancelling the work
                        // cancels this coroutine, and we want to stop promptly, keeping the .part.
                        currentCoroutineContext().ensureActive()

                        val read = input.read(buffer)
                        if (read == -1) break

                        output.write(buffer, 0, read)
                        downloaded += read

                        val now = System.currentTimeMillis()
                        if (now - lastEmitMs >= PROGRESS_INTERVAL_MS) {
                            val elapsed = (now - lastEmitMs).coerceAtLeast(1)
                            val rate = if (lastEmitMs == 0L) 0L
                            else (downloaded - lastEmitBytes) * 1000 / elapsed
                            onProgress(downloaded, rate)
                            lastEmitMs = now
                            lastEmitBytes = downloaded
                        }
                    }
                    output.flush()
                }
            }
        }

        if (part.length() != spec.sizeBytes) {
            part.delete()
            throw IOException(
                "Download finished but the file is the wrong size. It may have been corrupted in " +
                    "transit -- please try again.",
            )
        }

        // Rename only after the size checks out, so a `models/` file is never half-written.
        if (!part.renameTo(target)) throw IOException("Could not finalise the downloaded file")
    }

    private fun describeHttpFailure(code: Int): String = when (code) {
        // Two very different problems behind one status code, and the fix differs, so they must not
        // share a message: signed out means "sign in"; signed in means "you have not accepted this
        // model's licence yet", which no amount of retrying will fix.
        401, 403 -> if (auth.isSignedIn) {
            "Your HuggingFace account has not been granted access to this model. Open its page on " +
                "huggingface.co, accept the licence, then try again."
        } else {
            "This model is gated. Sign in to HuggingFace in Settings to download it."
        }
        404 -> "This model is no longer available at the address the app has for it."
        416 -> "The partly-downloaded file is out of step with the server. Delete and retry."
        in 500..599 -> "HuggingFace is having trouble right now (error $code). Try again shortly."
        else -> "Download failed with HTTP $code"
    }

    /** Total bytes the app is holding in downloaded models -- shown in Settings. */
    fun bytesOnDisk(): Long =
        modelsDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun modelIdOf(info: WorkInfo): String? =
        info.tags.firstOrNull { it.startsWith(MODEL_TAG_PREFIX) }?.removePrefix(MODEL_TAG_PREFIX)

    companion object {
        // Shared with ModelDownloadWorker.
        const val KEY_MODEL_ID = "modelId"
        const val KEY_DOWNLOADED = "downloaded"
        const val KEY_TOTAL = "total"
        const val KEY_RATE = "rate"
        const val KEY_ERROR = "error"

        private const val TAG = "ModelRepository"
        private const val WORK_TAG = "model-download"
        private const val MODEL_TAG_PREFIX = "model:"
        private const val DOWNLOAD_BUFFER_BYTES = 128 * 1024
        private const val PROGRESS_INTERVAL_MS = 250L

        private fun uniqueName(modelId: String) = "download:$modelId"
        private fun modelTag(modelId: String) = "$MODEL_TAG_PREFIX$modelId"
    }
}
