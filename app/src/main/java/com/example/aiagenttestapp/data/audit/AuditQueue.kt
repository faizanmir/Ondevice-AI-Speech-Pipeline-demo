package com.example.aiagenttestapp.data.audit

import android.content.Context
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.example.aiagent.engine.core.ContextWindow
import com.example.aiagenttestapp.prompts.AuditPrompts
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** A queued document as the UI sees it: status, progress, and the finished report once available. */
data class AuditDocument(
    val id: Long,
    val name: String,
    /** The model pinned at enqueue -- the one that produced [result], not whichever is active now. */
    val modelId: String,
    /** The read pinned at enqueue, which decided how this document was chunked. */
    val mode: AuditMode,
    val status: AuditStatus,
    val chunkCount: Int,
    val chunksDone: Int,
    /** Characters the [AuditQueue.MAX_CHUNKS] cap left unchunked; 0 for a document that fits whole. */
    val truncatedChars: Int,
    val summarising: Boolean,
    /** Time the model actually spent on this document; 0 while it is still queued or unrecorded. */
    val analysisMillis: Long,
    val result: AuditAnalysis?,
    val error: String?,
    val createdAtMillis: Long,
) {
    /**
     * The 1-based section being analysed right now. [chunksDone] counts *finished* sections and only
     * moves when a chunk checkpoints, so showing it raw reads "section 0 of 5" for the minutes the
     * first chunk takes -- while the worker's notification, which shows the in-flight section from
     * its loop, says "section 1 of 5". This is that same number derived from the checkpoint count,
     * so every screen agrees with the notification instead of trailing it by one section.
     */
    val currentSection: Int
        get() = (chunksDone + 1).coerceAtMost(chunkCount.coerceAtLeast(1))
}

/**
 * The durable, per-document audit queue.
 *
 * Adding a document chunks it, persists the document and its chunks to Room, and ensures the single
 * drainer worker is running -- appending, never replacing, so enqueuing a second file no longer
 * kills the first. There is one model/engine, so the drainer processes documents sequentially; Room
 * is the source of truth the UI observes, and the checkpoints that make an interrupted run resumable.
 */
class AuditQueue(context: Context, private val dao: AuditDao) {

    private val workManager = WorkManager.getInstance(context.applicationContext)

    val documents: Flow<List<AuditDocument>> =
        dao.observeDocuments().map { list -> list.map { it.toDomain() } }

    fun document(id: Long): Flow<AuditDocument?> =
        dao.observeDocument(id).map { it?.toDomain() }

    /**
     * Chunks [text] for [modelId]'s context, persists the document + chunks, and starts the drainer.
     * The chunk sizes are pinned to [contextTokens] here, at enqueue, so a later model change cannot
     * shift boundaries under an in-flight checkpoint.
     */
    suspend fun enqueue(
        name: String,
        text: String,
        modelId: String,
        contextTokens: Int,
        mode: AuditMode = AuditMode.DETAILED,
    ) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        // Chunk to the model's context window: everything a turn also has to hold is reserved -- the
        // loaded system prompt and the extraction prompt's scaffolding (both measured from the
        // prompts themselves, so they stay correct if the prompts change), plus room for the chunk's
        // reply, which AuditChunker scales to the chunk rather than pinning flat.
        //
        // Measured against the RICH profile (extraction's default) on purpose, even when the run
        // will use LEAN: chunk sizes are pinned here but the engine -- and so the profile -- is
        // resolved later, so reserving for the largest preamble is the only sizing that cannot
        // overflow if that resolution changes. Costs LEAN runs smaller chunks than they strictly
        // need; pin the profile on the document row to reclaim that.
        //
        // The character budget is converted from tokens using THIS document's own script mix, not a
        // fixed ratio: an ideographic or Devanagari transcript is two to three times denser per
        // character than English, and a Latin ratio would size every chunk to overflow the window.
        //
        // Sized for the pinned mode: quick's preamble and reply reserve are both far smaller, so its
        // sections are correspondingly larger and a document needs fewer of them.
        val chunkChars = AuditChunker.chunkCharBudget(
            contextTokens.coerceAtLeast(1),
            AuditPrompts.fixedPromptTokens(mode, AuditPromptProfile.RICH),
            charsPerToken = ContextWindow.charsPerToken(trimmed),
            mode = mode,
        )
        val plan = AuditChunker.plan(trimmed, maxChars = chunkChars, maxChunks = MAX_CHUNKS)
        val chunks = plan.chunks
        if (chunks.isEmpty()) return
        if (plan.isTruncated) {
            Log.w(
                TAG,
                "'$name' needs more than $MAX_CHUNKS sections: ${plan.droppedChars} of " +
                    "${trimmed.length} chars will not be analysed",
            )
        }

        val now = System.currentTimeMillis()
        dao.enqueue(
            AuditDocumentEntity(
                name = name,
                modelId = modelId,
                mode = mode.name,
                status = AuditStatus.QUEUED.name,
                chunkCount = chunks.size,
                truncatedChars = plan.droppedChars,
                createdAtMillis = now,
                updatedAtMillis = now,
            ),
        ) { docId ->
            chunks.mapIndexed { index, text -> AuditChunkEntity(docId = docId, chunkIndex = index, text = text) }
        }
        ensureDrainerRunning()
    }

    /**
     * Starts the drainer if one is not already pending. KEEP means a running drainer is left to pick
     * up the new work via its DB loop, and a fresh one starts only when none is active -- so calling
     * this on every enqueue (and when the screen opens) closes the small window where a drainer could
     * finish just as a document is added.
     */
    fun ensureDrainerRunning() {
        workManager.enqueueUniqueWork(
            DRAINER_WORK,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<AuditDrainWorker>()
                // Expedited so it is allowed to promote to a foreground service even when the app is
                // in the background (Android 12+); falls back to a normal request if the quota is
                // spent, and safeSetForeground in the worker keeps that fallback from crashing.
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .addTag(DRAINER_WORK)
                .build(),
        )
    }

    /** Removes a document (and its chunks, via cascade). The drainer abandons it at the next chunk. */
    suspend fun cancel(id: Long) {
        dao.deleteDocument(id)
    }

    /** Re-queues a failed document; finished chunks are kept, so it resumes rather than restarts. */
    suspend fun retry(id: Long) {
        dao.setStatus(id, AuditStatus.QUEUED.name, error = null, ts = System.currentTimeMillis())
        ensureDrainerRunning()
    }

    private fun AuditDocumentEntity.toDomain() = AuditDocument(
        id = id,
        name = name,
        modelId = modelId,
        mode = AuditMode.from(mode),
        status = runCatching { AuditStatus.valueOf(status) }.getOrDefault(AuditStatus.FAILED),
        chunkCount = chunkCount,
        chunksDone = chunksDone,
        truncatedChars = truncatedChars,
        summarising = summarising,
        analysisMillis = analysisMillis,
        result = resultJson?.let { AuditResultCodec.decode(it) },
        error = error,
        createdAtMillis = createdAtMillis,
    )

    companion object {
        const val DRAINER_WORK = "audit_drainer"

        /**
         * Safety cap on chunks per document. As much a time budget as a memory one: on a mid-range
         * phone a section takes ~100s, so 80 of them is over two hours of foreground service before
         * grading and the summary even begin.
         *
         * Whatever the cap leaves unread is recorded on the document and reported; see
         * [AuditChunker.plan].
         */
        const val MAX_CHUNKS = 80

        private const val TAG = "AuditQueue"
    }
}
