package com.example.aiagenttestapp.data.audit

import com.example.aiagenttestapp.prompts.audit.AuditExtractionPrompts
import com.example.aiagenttestapp.prompts.audit.AuditPromptBudget
import com.example.aiagenttestapp.prompts.audit.AuditQuickPrompts
import com.example.aiagenttestapp.prompts.audit.AuditSummaryPrompts
import android.Manifest
import android.R
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.example.aiagent.engine.core.Accelerator
import com.example.aiagent.engine.core.EngineId
import com.example.aiagent.engine.core.GenerationEvent
import com.example.aiagent.engine.core.GenerationStats
import com.example.aiagent.engine.core.InferenceEngine
import androidx.hilt.work.HiltWorker
import com.example.aiagenttestapp.MainActivity
import com.example.aiagenttestapp.data.ModelResidency
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import com.example.aiagenttestapp.util.Reasoning
import kotlinx.coroutines.CancellationException

/**
 * Drains the audit queue: one document at a time (there is a single model/engine), each chunk
 * analysed and checkpointed to Room so an interrupted, backgrounded, or process-killed run resumes
 * from the last finished chunk. Runs as a foreground service so a long batch completes even in the
 * background. Per-document results are written to Room; the UI observes Room, not this worker.
 */
@HiltWorker
class AuditDrainWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val dao: AuditDao,
    private val loadPlanner: AuditLoadPlanner,
    private val modelResidency: ModelResidency,
) : CoroutineWorker(context, params) {

    /** WorkManager reads this to promote the (expedited) worker to a foreground service. */
    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo("Preparing…", 0, 0)

    override suspend fun doWork(): Result {
        safeSetForeground(foregroundInfo("Preparing…", 0, 0))
        try {
            // Loop until the queue is empty; a final re-check closes the enqueue/finish race.
            while (true) {
                // The platform stops long jobs. This worker asks to be expedited, but once that
                // quota is spent it runs as an ordinary job and JobScheduler ends it after about ten
                // minutes -- which is how a queue came to sit at 3 of 8 sections with nothing
                // running, waiting for someone to open the screen. Handing back a retry lets
                // WorkManager re-run the same unique work, and every finished chunk is already
                // checkpointed, so it resumes rather than restarts.
                if (isStopped) return Result.retry()
                val doc = dao.nextPending() ?: break
                processDocument(doc)
            }
            return Result.success()
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            return Result.failure()
        }
    }

    private suspend fun processDocument(doc: AuditDocumentEntity) {
        val now = { System.currentTimeMillis() }
        // Time is banked slice by slice at the same points the work is checkpointed, so a killed and
        // resumed run adds to the total rather than restarting or double-counting it.
        var mark = now()
        val sliceMillis = {
            val at = now()
            (at - mark).also { mark = at }
        }
        dao.setStatus(doc.id, AuditStatus.ANALYSING.name, error = null, ts = now())

        val plan = when (val resolution = loadPlanner.plan(doc.modelId)) {
            is AuditModelPlan.Unavailable -> {
                dao.setStatus(doc.id, AuditStatus.FAILED.name, resolution.reason, now())
                return
            }

            is AuditModelPlan.Ready -> resolution
        }

        val mode = AuditMode.from(doc.mode)

        modelResidency.attach()
        val extractStats = PhaseStats("extract")
        val summaryStats = PhaseStats("summarise")
        try {
            // llama.cpp reuses a shared prompt prefix, so it can afford the fuller preamble; the
            // engines that re-prefill every chunk get the trimmed one. Decided by the plan.
            val profile = plan.profile
            val engine = loadPlanner.open(plan, mode)
            // The audit's own window, not the model's full one -- the same number the load request
            // used, so chunk sizing, the reply reserve and the context-full check all agree.
            val contextTokens = plan.contextTokens

            // Whether to ask for the plain-text draft, decided by where the model actually landed.
            //
            // The draft doubles the output, and it is written before the records, so on a slow
            // engine the turn's time budget runs out inside it and the section yields nothing --
            // observed directly: two sections cut at the same five-minute mark, one mid-records
            // keeping 8,564 bytes of findings, one still drafting keeping 64. On an accelerator
            // that never happens, and the draft earns its cost by catching findings a single pass
            // misses.
            //
            // activeAccelerator, not the requested one: engines fall back to CPU when a GPU turns
            // out to be unusable, and it is where the model *is* that decides how fast it decodes.
            // Unknown counts as CPU -- losing a whole section is worse than a slightly thinner one.
            //
            // Decided once here, not per section: the preamble has to stay byte-identical across a
            // document or llama.cpp re-decodes it for every chunk instead of reusing the prefix.
            //
            // Never in quick mode: the draft exists to catch findings a single pass misses, and
            // quick has no findings to miss -- it reports what the section says rather than deciding
            // what counts. Paying double the output tokens for that would be the single largest cost
            // in a mode whose entire point is being fast.
            val draft = mode == AuditMode.DETAILED && when (engine.activeAccelerator) {
                Accelerator.GPU, Accelerator.NPU -> true
                else -> false
            }

            // How long one turn may run before it is cut off.
            //
            // Five minutes is a runaway guard, and it was chosen to sit under the platform's job
            // limit so a turn always reaches a checkpoint. On llama.cpp away from an accelerator it
            // stopped being a guard and became the binding constraint: Qwen decodes at ~7.5 tok/s
            // there, and a section died at exactly 300 seconds having spent every one of them
            // reasoning, never reaching the records at all. The work was not runaway, it was slow.
            //
            // A reasoning model gets the same allowance even on faster hardware, because a think
            // block is paid before the answer starts and is not visible in any budget that counts
            // prompt or section tokens. Detected from the reply rather than declared: no model spec
            // carries the fact, and the ceiling is not part of the prompt, so raising it mid-document
            // costs nothing -- unlike the draft decision, which has to stay fixed for prefix reuse.
            val slowEngine = plan.resolved.engineId == EngineId.LLAMA_CPP && !draft
            var turnMaxMillis = if (slowEngine) SLOW_TURN_MAX_MILLIS else TURN_MAX_MILLIS
            Log.i(
                TAG,
                "'${doc.name}' on ${engine.activeAccelerator?.label ?: "unknown"}: " +
                    if (draft) "drafting before records" else "records only, no draft",
            )

            var consecutiveStarved = 0
            for (chunk in dao.pendingChunks(doc.id)) {
                // Cancelled (row deleted) or explicitly cancelled -> abandon this document.
                val status = dao.statusOf(doc.id)
                if (status == null || status == AuditStatus.CANCELLED.name) return
                // Stopped by the platform: leave the document ANALYSING so nextPending picks it up,
                // and let doWork hand back a retry. Checked between chunks, which is where the last
                // checkpoint is, so nothing in flight is lost.
                if (isStopped) return

                safeSetForeground(foregroundInfo(doc.name, chunk.chunkIndex + 1, doc.chunkCount))
                modelResidency.runExclusive { engine.resetKeepingPrefixCache() }
                val turn = generateFull(
                    engine,
                    when (mode) {
                        AuditMode.DETAILED -> AuditExtractionPrompts.extraction(
                            chunk.text,
                            chunk.chunkIndex + 1,
                            doc.chunkCount,
                            profile,
                            draft = draft,
                        )

                        AuditMode.QUICK -> AuditQuickPrompts.quickExtraction(
                            chunk.text,
                            chunk.chunkIndex + 1,
                            doc.chunkCount,
                        )
                    },
                    extractStats,
                    maxMillis = turnMaxMillis,
                    // The room actually left for this reply -- the window, less this run's real
                    // preamble and this chunk's real text. Not the sizing formula: chunks are sized
                    // reserving for the larger RICH preamble because the profile is not known at
                    // enqueue, so a LEAN run has a few hundred tokens spare that the formula would
                    // never hand back, and the cap would clip answers that fit perfectly well.
                    maxTokens = extractionMaxTokens(contextTokens, mode, profile, chunk.text),
                    label = "chunk ${chunk.chunkIndex}",
                )
                val raw = turn.text
                // A think block means this model spends part of every turn reasoning before it
                // answers, which no token budget here accounts for. Raise the ceiling for the rest
                // of the document rather than cutting the next section off in the same place.
                if (turnMaxMillis == TURN_MAX_MILLIS &&
                    plan.resolved.engineId == EngineId.LLAMA_CPP &&
                    ("<think>" in raw || "</think>" in raw)
                ) {
                    turnMaxMillis = SLOW_TURN_MAX_MILLIS
                    Log.i(TAG, "model reasons before answering; turn ceiling raised to ${SLOW_TURN_MAX_MILLIS / 60_000} minutes")
                }

                // Did the reply end because the model finished, or because the window did? The
                // runtime cannot tell us: llama.cpp's decode loop returns "no more tokens" for a
                // filled KV cache in exactly the same way it does for an end-of-turn token, so a
                // guillotined answer is indistinguishable from a complete one at this level. The
                // cache occupancy is the tell, and without it a budgeting failure gets reported to
                // the user as the model writing bad JSON -- which is where the last one hid.
                val filledContext =
                    engine.contextTokensUsed() >= contextTokens - CONTEXT_FULL_MARGIN_TOKENS

                // An unparseable reply is RECORDED, not swallowed -- along with WHY it was
                // unreadable, diagnosed from the reply's shape while it is still in hand. Marking
                // the chunk done with an empty analysis would present the document as fully
                // audited with a section never read -- the worst outcome for a compliance artefact.
                // The reply is read by whichever parser matches the format it was asked for. The
                // record parser never returns null -- an unreadable reply simply yields nothing --
                // so "unusable" below is what decides a lost section either way.
                // Quick mode always answers in records -- it has no JSON variant to fall back on --
                // so its reply goes straight to the record parser.
                val rawParsed = when {
                    mode == AuditMode.QUICK -> AuditRecordParser.parse(raw)
                    AuditExtractionPrompts.OUTPUT_FORMAT == AuditOutputFormat.RECORDS -> AuditRecordParser.parse(raw)
                    else -> AuditAnalysisParser.parse(raw)
                }

                // Two questions, and they are not the same one.
                //
                // First: is this reply usable? An empty analysis from a reply that was cut off -- by
                // the window, or by one of our own ceilings -- is not a section with no findings, it
                // is a section the model never finished. The parser is lenient by design and will
                // return an empty object from a stray `{}` in a truncated draft, and recording that
                // as a clean section is the single most dangerous thing this pipeline can do.
                val cutOff = filledContext || turn.stoppedBy != null
                val unusable = rawParsed == null || (rawParsed.isEmpty && cutOff)
                val parsed = if (unusable) null else rawParsed

                // Second: was the *window* too small? Only if nothing of ours stopped the turn. On an
                // engine that cannot be interrupted mid-turn the model keeps generating after a cap
                // fires and fills the window every time, so "the window filled" says nothing about
                // whether the window was ever the problem. Reading it as starvation failed a document
                // that would otherwise have finished with seventeen findings.
                val starved = unusable && filledContext && turn.stoppedBy == null
                // Say which limit ended the turn, not just that the reply was unreadable. A section
                // stopped by a ceiling and a model writing bad JSON look identical in the output and
                // call for completely different responses from whoever reads the report.
                val parseError = when {
                    parsed != null -> ""
                    turn.stoppedBy == StopReason.TIME_CAP ->
                        "this section took longer than ${turnMaxMillis / 60_000} minutes and was stopped"
                    turn.stoppedBy == StopReason.TOKEN_CAP ->
                        "the model's reply outgrew the room reserved for it"
                    filledContext -> "the model ran out of context part-way through this section"
                    mode == AuditMode.QUICK || AuditExtractionPrompts.OUTPUT_FORMAT == AuditOutputFormat.RECORDS ->
                        "the model's reply contained no records to read"
                    else -> AuditAnalysisParser.diagnose(raw)
                }
                if (parsed == null) {
                    Log.w(TAG, "chunk ${chunk.chunkIndex}: $parseError")
                    // The parser's precise complaint, when it had one: which offset, and what it
                    // expected to find there. A reply that ends in a closed object and still will
                    // not parse is malformed somewhere the tail cannot show.
                    if (mode == AuditMode.DETAILED && AuditExtractionPrompts.OUTPUT_FORMAT == AuditOutputFormat.JSON) {
                        AuditAnalysisParser.parseFailureDetail(raw)?.let {
                            Log.w(TAG, "chunk ${chunk.chunkIndex}: $it")
                        }
                    }
                    Log.w(TAG, "chunk ${chunk.chunkIndex}: ${replyTail(raw)}")
                } else if (filledContext) {
                    // Parsed with real content, but against a full window -- so these may be the
                    // findings the model got to before it ran out, not all of them.
                    Log.w(TAG, "chunk ${chunk.chunkIndex}: filled the context window; may be partial")
                }
                // The one place the character estimate meets ground truth. Chunk sizes are derived
                // from an estimated chars-per-token ratio, and if that estimate is wrong every
                // budget downstream is wrong with it -- silently, until a window overflows.
                logEstimateDrift(chunk.text, extractStats.lastPromptTokens, mode, profile)
                // Verify quotes and cited standards here, the one point where both the findings and
                // their source text are in hand. An unverifiable claim is cleared, not the finding.
                // Actions go through too: they carry no quotes, but a standard echoed onto an action
                // from a worked example dies in the same check.
                val checked = AuditEvidence.verify(parsed?.nonConformities.orEmpty(), chunk.text)
                val checkedActions = AuditEvidence.verify(parsed?.actions.orEmpty(), chunk.text)
                if (checked.rejected > 0) {
                    Log.w(TAG, "chunk ${chunk.chunkIndex}: ${checked.rejected} unverifiable quote(s)")
                }
                val rejectedStandards = checked.rejectedStandards + checkedActions.rejectedStandards
                if (rejectedStandards > 0) {
                    Log.w(TAG, "chunk ${chunk.chunkIndex}: $rejectedStandards uncited standard(s) dropped")
                }
                val findings = (parsed ?: AuditAnalysis(parseFailed = true, parseError = parseError))
                    .copy(nonConformities = checked.findings, actions = checkedActions.findings)
                // Keyed by chunk row -> reprocessing a chunk overwrites, never double-counts.
                dao.completeChunk(chunk.id, AuditResultCodec.encode(findings))
                dao.addElapsed(doc.id, sliceMillis())
                dao.refreshProgress(doc.id, now())

                // Starvation in a row, not starvation once. A model whose replies do not fit the
                // window starves on every section, and stopping then costs minutes instead of hours
                // and says something the user can act on. But an intermittent failure -- a model that
                // loops on one section and answers the next -- starves once, and failing the document
                // for it threw away a run that went on to produce seventeen findings.
                if (starved) {
                    consecutiveStarved++
                    if (consecutiveStarved >= STARVED_SECTIONS_BEFORE_FAILING) {
                        val message = contextStarvedMessage(
                            modelName = plan.modelName,
                            contextTokens = contextTokens,
                            promptTokens = extractStats.lastPromptTokens,
                        )
                        Log.w(TAG, "audit '${doc.name}': $message")
                        dao.setStatus(doc.id, AuditStatus.FAILED.name, message, now())
                        return
                    }
                } else {
                    consecutiveStarved = 0
                }
            }

            // Reduce: merge the checkpointed per-chunk findings, grade them, then fuse the summaries.
            val partials = dao.chunkFindings(doc.id).mapNotNull { it?.let(AuditResultCodec::decode) }
            // chunkFindings is ordered by chunkIndex, so this is the document's facts in order.
            val factsByPart = partials.map { it.facts }
            val nonConformities = if (mode == AuditMode.DETAILED) {
                AuditChunker.mergeFindings(partials.map { it.nonConformities })
            } else {
                emptyList()
            }
            // The last stated verdict wins: chunks are in document order, and the closing statement
            // -- where an auditor states the result -- is the last place one appears.
            val verdict = partials.lastOrNull { it.verdict.isNotBlank() }?.verdict.orEmpty()

            // One flag covers the whole reduce phase, and the label says so. Setting it here and
            // calling it "Summarising" sent a grading pass that ran long to the wrong place
            // entirely: the screen said summarising, the summary had not started, and the phase
            // that was actually running had no name anywhere the user could see.
            dao.setSummarising(doc.id, summarising = true, ts = now())

            // Extraction states each finding's conclusion now, so there is no grading pass. It
            // was a second opinion by construction -- it re-read a finding with no memory of what
            // had been concluded, so it could only ever soften one, which is what "never
            // downgrade" forbids. Deciding once, where the evidence is, also takes up to three
            // sweeps of the merged list out of every detailed run.
            val graded = nonConformities

            safeSetForeground(
                foregroundInfo(doc.name, doc.chunkCount, doc.chunkCount, phase = "Writing the summary…"),
            )
            val summaryResult = when (mode) {
                AuditMode.DETAILED ->
                    finalSummary(engine, factsByPart, verdict, contextTokens, summaryStats, turnMaxMillis)

                AuditMode.QUICK ->
                    quickSummary(engine, factsByPart, contextTokens, summaryStats, turnMaxMillis)
            }
            val summary = summaryResult.text
            val keyPoints = summaryResult.points
            val notesTrimmed = summaryResult.notesTrimmed
            val summaryTruncated = summaryResult.summaryTruncated

            Log.i(
                TAG,
                "audit '${doc.name}' on ${engine.activeAccelerator?.label ?: "unknown"} -- " +
                    "$extractStats | $summaryStats",
            )

            val unanalysed = partials.count { it.parseFailed }
            if (unanalysed > 0) {
                Log.w(TAG, "audit '${doc.name}': $unanalysed of ${partials.size} sections unanalysed")
            }
            // One line per failed section, by number: the banner's "why", not just its "how many".
            // partials is in chunkIndex order, so index+1 is the same 1-based section number the
            // progress UI and the notification already use.
            val unanalysedReasons = partials.mapIndexedNotNull { index, partial ->
                if (!partial.parseFailed) null
                else "Section ${index + 1}: ${partial.parseError.ifBlank { "the reply could not be read" }}."
            }
            val result = AuditAnalysis(
                summary = summary,
                keyPoints = keyPoints,
                mode = mode.name,
                verdict = verdict,
                nonConformities = graded,
                // Merged in code in both modes: handing a small model the whole action list and
                // asking it to consolidate is where recall silently dies, and quick mode has no more
                // reason to take that risk than detailed does.
                actions = AuditChunker.mergeFindings(partials.map { it.actions }),
                unanalysedSections = unanalysed,
                // Section failures first, then the document-level gaps, so the banner reads in the
                // order a reader needs: what was unreadable, then what was never read at all.
                unanalysedReasons = unanalysedReasons + documentGaps(doc, notesTrimmed, summaryTruncated),
                truncatedChars = doc.truncatedChars,
                notesTrimmed = notesTrimmed,
                // Provenance on the artefact itself: which engine and prompt profile produced it.
                engineName = plan.engineName,
                promptProfile = profile.label,
            )
            dao.setResult(doc.id, AuditResultCodec.encode(result), sliceMillis(), now())
            notifyCompleted(doc, result)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            // Bank the in-flight slice too: a retry resumes, so this time is part of the eventual total.
            dao.addElapsed(doc.id, sliceMillis())
            dao.setStatus(doc.id, AuditStatus.FAILED.name, t.message ?: "Analysis failed.", now())
        } finally {
            modelResidency.detach()
        }
    }

    /**
     * Runs one turn to completion, stopping it at [maxTokens] if it gets there first.
     *
     * The cap is the only thing standing between this pipeline and an unbounded generation. Nothing
     * else bounds a reply: the sampler's own limit is off by default, and the reservations this code
     * computes size *chunks*, they do not restrain the model. Until the context window grew that was
     * survivable -- a 4K window guillotined a runaway within a thousand tokens or so. On a 22K window
     * the same runaway is half an hour of decode, which is how a grading pass came to look like a
     * hung summary. Greedy decoding, which "Reproducible output" turns on, is the mode most prone to
     * the repetition loops this catches.
     *
     * Counted in streamed pieces rather than true tokens -- close enough for a runaway guard, and it
     * needs no tokenizer. Hitting the cap is always logged: a limit that trims an answer silently is
     * the same failure as the one it was added to prevent.
     */
    /** How a turn ended, when it did not end because the model finished. */
    private enum class StopReason { TOKEN_CAP, TIME_CAP }

    /** A finished turn: what the model said, and why it stopped saying it. */
    private data class Turn(val text: String, val stoppedBy: StopReason? = null)

    private suspend fun generateFull(
        engine: InferenceEngine,
        prompt: String,
        phase: PhaseStats? = null,
        maxTokens: Int = 0,
        label: String = "",
        maxMillis: Long = TURN_MAX_MILLIS,
    ): Turn {
        val buffer = StringBuilder()
        val startedAt = System.currentTimeMillis()
        var pieces = 0
        var stoppedBy: StopReason? = null
        engine.generate(prompt).collect { event ->
            when (event) {
                is GenerationEvent.Token -> {
                    // Past a limit the rest of the stream is dropped rather than kept: on a runtime
                    // that cannot be interrupted it keeps arriving, and appending it would undo the
                    // cap entirely.
                    if (stoppedBy != null) return@collect
                    buffer.append(event.text)
                    pieces++
                    // Two ceilings, because they catch different failures. The token cap bounds a
                    // reply against the room reserved for it. The clock bounds the *turn*, which is
                    // what a repetition loop actually costs -- a loop stays inside its token budget
                    // for as long as the budget is large, and a 22K window bought twenty-one minutes
                    // of one. The clock also has to sit below the platform's job limit: a turn that
                    // outlives the job can never checkpoint, so it would be retried forever.
                    if (stoppedBy == null && maxTokens > 0 && pieces >= maxTokens) {
                        stoppedBy = StopReason.TOKEN_CAP
                        Log.w(TAG, "$label: stopped at the $maxTokens-token cap; reply is truncated")
                        stopTurn(engine)
                    } else if (stoppedBy == null &&
                        System.currentTimeMillis() - startedAt > maxMillis
                    ) {
                        stoppedBy = StopReason.TIME_CAP
                        Log.w(
                            TAG,
                            "$label: stopped after ${maxMillis / 60_000} minutes " +
                                "($pieces tokens); reply is truncated",
                        )
                        stopTurn(engine)
                    }
                }
                // The runtime already measures prefill and decode for us; the only reason this was
                // ever slow to diagnose is that we dropped the event on the floor.
                is GenerationEvent.Complete -> phase?.add(event.stats)
            }
        }
        return Turn(buffer.toString(), stoppedBy)
    }

    /**
     * Ends a turn early, by the only means the engine allows.
     *
     * Asking first is not defensive coding, it is the difference between stopping a turn and losing
     * the job: LiteRT-LM's cancel raises CANCELLED through the collector, which cancelled this
     * worker mid-document and left a queue sitting at one section of eight. Where an engine cannot be
     * interrupted the turn is left to finish on its own and its remaining output is discarded --
     * wasted compute, but a document that completes.
     */
    private fun stopTurn(engine: InferenceEngine) {
        if (engine.supportsMidTurnCancel) {
            engine.cancel()
        } else {
            Log.i(TAG, "engine cannot be interrupted mid-turn; ignoring the rest of this reply")
        }
    }

    /**
     * Per-phase roll-up of the runtime's own counters, so a slow run can be attributed to extraction,
     * grading, or the summary rather than guessed at. Prefill vs decode matters because they scale
     * with different things -- prefill with prompt size and chunk count, decode with how much the
     * model chooses to say.
     */
    private class PhaseStats(private val name: String) {
        private var calls = 0
        private var promptTokens = 0
        private var generatedTokens = 0
        private var prefillMs = 0L
        private var totalMs = 0L

        // First and last decode rate, kept separately from the totals. A sustained run on a tablet
        // thermal-throttles, and an average over the whole document hides that entirely -- the shape
        // only shows up as a gap between the opening call and the closing one.
        private var firstTokensPerSecond = 0.0
        private var lastTokensPerSecond = 0.0

        // Likewise for prefill: on an engine that reuses a shared prompt prefix the first call pays
        // for the preamble and later calls should not, so first-vs-rest is the tell that it is live.
        private var firstPrefillMs = 0L

        /** The runtime's own token count for the most recent prompt; 0 when it reported none. */
        var lastPromptTokens = 0
            private set

        fun add(stats: GenerationStats) {
            calls++
            lastPromptTokens = stats.promptTokens
            promptTokens += stats.promptTokens
            generatedTokens += stats.generatedTokens
            prefillMs += stats.timeToFirstTokenMs
            totalMs += stats.totalMs
            if (calls == 1) {
                firstTokensPerSecond = stats.tokensPerSecond
                firstPrefillMs = stats.timeToFirstTokenMs
            }
            lastTokensPerSecond = stats.tokensPerSecond
        }

        override fun toString(): String = "$name x$calls: ${totalMs}ms " +
            "(prefill ${prefillMs}ms, decode ${totalMs - prefillMs}ms), " +
            "$promptTokens prompt / $generatedTokens generated tok, " +
            "decode %.1f->%.1f tok/s, first prefill ${firstPrefillMs}ms, ".format(
                firstTokensPerSecond,
                lastTokensPerSecond,
            ) +
            "rest avg ${if (calls > 1) (prefillMs - firstPrefillMs) / (calls - 1) else 0}ms"
    }

    /**
     * The reduce step: one prose summary written from the per-chunk facts.
     *
     * There is no single-chunk short cut here, unlike the summary-of-summaries this replaced. A
     * chunk now yields a list of facts, not prose, so a one-chunk document still needs this pass --
     * returning its facts verbatim would put a bullet list where the summary belongs. Only a document
     * with no facts at all skips it.
     */
    /**
     * The summary, plus whether the notes had to be trimmed to fit the window to produce it.
     *
     * [text] is detailed mode's prose and [points] is quick mode's bullet list; each mode fills one
     * and leaves the other empty. Two fields rather than a joined string because the report renders
     * a list as a list, and re-splitting prose on newlines to get it back would be guesswork.
     */
    private data class SummaryResult(
        val text: String = "",
        val points: List<String> = emptyList(),
        val notesTrimmed: Boolean = false,
        /** The reply ran into its own output cap, so the summary stops mid-thought. */
        val summaryTruncated: Boolean = false,
    )

    /**
     * Quick mode's reduce: every section's points condensed into at most [QuickAudit.MAX_POINTS].
     *
     * One turn over notes gathered from the whole document -- so the result reflects a full read
     * even though no single turn ever saw the whole text. The cap is applied in code by
     * [QuickPointsParser.parseQuickPoints], not merely asked for.
     *
     * Falls back to the leading section notes when the model returns nothing parseable. A quick
     * report whose points are the raw notes is thin; one with no points at all is a blank report,
     * and the notes are already the document's own content in its own words.
     */
    private suspend fun quickSummary(
        engine: InferenceEngine,
        pointsByPart: List<List<String>>,
        contextTokens: Int,
        phase: PhaseStats,
        maxMillis: Long,
    ): SummaryResult {
        if (pointsByPart.all { it.isEmpty() }) return SummaryResult()
        modelResidency.runExclusive { engine.resetKeepingPrefixCache() }

        val budget = AuditQuickPrompts.quickSummaryNoteBudget(contextTokens)
        val noteChars = AuditSummaryPrompts.noteChars(pointsByPart)
        val trimmed = noteChars > budget
        if (trimmed) {
            Log.w(TAG, "quick summary notes trimmed to fit context: $noteChars chars into $budget")
        }

        val turn = generateFull(
            engine,
            AuditQuickPrompts.quickSummary(pointsByPart, QuickAudit.MAX_POINTS, budget),
            phase,
            maxTokens = AuditQuickPrompts.QUICK_SUMMARY_OUTPUT_RESERVE_TOKENS,
            label = "quick summary",
            maxMillis = maxMillis,
        )
        val raw = turn.text

        val points = QuickPointsParser.parseQuickPoints(Reasoning.stripThinking(raw))
            .ifEmpty {
                Log.w(TAG, "quick summary produced no readable points; falling back to section notes")
                pointsByPart.flatten().take(QuickAudit.MAX_POINTS)
            }
        // A truncated quick summary loses whole points rather than half a sentence: the parser
        // reads bullets, so a reply cut mid-list simply yields fewer of them, with nothing to show
        // that more were coming.
        return SummaryResult(
            points = points,
            notesTrimmed = trimmed,
            summaryTruncated = turn.stoppedBy != null,
        )
    }

    private suspend fun finalSummary(
        engine: InferenceEngine,
        factsByPart: List<List<String>>,
        verdict: String,
        contextTokens: Int,
        phase: PhaseStats,
        maxMillis: Long,
    ): SummaryResult {
        if (factsByPart.all { it.isEmpty() }) return SummaryResult()
        modelResidency.runExclusive { engine.resetKeepingPrefixCache() }

        // Unlike a chunk, the notes are not sized by anything upstream -- a document can reach here
        // with up to MAX_CHUNKS sections' worth. Cap them to what this model's window can actually
        // hold, and report it when the cap bites: the trimming is invisible in the finished prose,
        // so a summary written from a fraction of the document would otherwise read exactly like one
        // written from all of it.
        val budget = AuditSummaryPrompts.summaryNoteBudget(contextTokens, verdict)
        val noteChars = AuditSummaryPrompts.noteChars(factsByPart)
        val trimmed = noteChars > budget
        if (trimmed) {
            Log.w(TAG, "summary notes trimmed to fit context: $noteChars chars into $budget")
        }

        val turn = generateFull(
            engine,
            AuditSummaryPrompts.finalSummary(factsByPart, verdict, budget),
            phase,
            maxTokens = AuditSummaryPrompts.SUMMARY_OUTPUT_RESERVE_TOKENS,
            label = "summary",
            maxMillis = maxMillis,
        )
        val raw = turn.text
        // Plain prose, so the reply is taken as-is. Falling back to the raw facts beats showing
        // nothing if the model returns empty.
        val text = Reasoning.stripThinking(raw).trim()
            .ifBlank { factsByPart.flatten().joinToString(" ") }
        // Trimming and truncation are different failures and both have to be reported: the notes
        // not fitting *in* is caught above by measuring them, but a summary that fills its own
        // output budget and stops mid-finding leaves no trace in the text at all.
        return SummaryResult(
            text = text,
            notesTrimmed = trimmed,
            summaryTruncated = turn.stoppedBy != null,
        )
    }

    /**
     * The end of a reply, flattened onto one log line, for when parsing it failed.
     *
     * Recording only *that* a section could not be read leaves the one question that matters
     * unanswerable. A trailing comma, an unescaped quote inside a string, a reply cut off mid-object
     * and prose that never became JSON all arrive at the same place and read identically in the
     * report -- and they call for four different fixes, to the parser, the prompt, the cap and the
     * profile respectively. Without the text, choosing between them is guesswork.
     *
     * The tail rather than the head, because the prompt puts the JSON last and writes nothing after
     * it, so whatever went wrong is at the end. Short and one-line on purpose: logcat splits on
     * newlines and truncates long messages, and this is model output from a document that may be
     * confidential -- enough to diagnose, not a copy of the section.
     */
    private fun replyTail(raw: String): String {
        val tail = raw.takeLast(REPLY_TAIL_CHARS).replace("\r", "").replace("\n", "\\n")
        return "reply was ${raw.length} chars, ending: $tail"
    }

    /**
     * Why a model and a context window cannot audit together, in words that name the remedy.
     *
     * The failing quantity is the model's own verbosity: a reasoning model spends its `<think>` block
     * on the task, not on the length of the passage, so shrinking the section does not shrink the
     * reply much -- there is simply no arrangement of a 4K window that holds this prompt, a usable
     * slice of transcript, and two thousand tokens of answer. The lever that works is the model file:
     * a smaller quantisation of the same model frees the RAM that the context window is sized from.
     */
    private fun contextStarvedMessage(modelName: String, contextTokens: Int, promptTokens: Int): String =
        buildString {
            append("$modelName filled its $contextTokens-token context before finishing a section, ")
            append("$STARVED_SECTIONS_BEFORE_FAILING sections in a row. ")
            // Only when the runtime actually told us. LiteRT-LM reports no prompt token count, and
            // the arithmetic then produced "about 4096 tokens for the reply" out of a window of
            // 4096 -- a fabricated number sitting one line below the warning that says we do not
            // have it. A message that invents its evidence is worse than one that omits it.
            if (promptTokens > 0) {
                append("The audit prompt and that section left about ")
                append("${(contextTokens - promptTokens).coerceAtLeast(0)} tokens for the reply, ")
                append("and this model needs more than that to answer. ")
            }
            append("Try a smaller quantisation of it (a smaller file frees memory, which buys a ")
            append("larger context window), or a model that reasons less.")
        }

    /**
     * The document-level gaps, in the same voice as the per-section ones, for the report banner.
     * These are the losses that happen outside any single section -- so nothing in the chunk loop
     * can report them, and without this they would be visible only in logcat.
     */
    private fun documentGaps(
        doc: AuditDocumentEntity,
        notesTrimmed: Boolean,
        summaryTruncated: Boolean = false,
    ): List<String> =
        buildList {
            if (doc.truncatedChars > 0) {
                add(
                    "The last ${doc.truncatedChars} characters of this document were not analysed: " +
                        "it needs more than the ${AuditQueue.MAX_CHUNKS}-section limit allows.",
                )
            }
            if (summaryTruncated) {
                add(
                    "The overall summary stopped before it finished: it reached the limit on how " +
                        "long a summary may be. The findings below are complete.",
                )
            }
            if (notesTrimmed) {
                add(
                    "The overall summary was written from a subset of the section notes, which did " +
                        "not all fit this model's context. The findings below are unaffected.",
                )
            }
        }

    /**
     * Compares the chars-per-token estimate that sized this chunk against the count the runtime
     * actually tokenised, and warns when they disagree enough to matter.
     *
     * Every budget in this pipeline is built on that estimate, and until now nothing ever checked
     * it: a ratio that is wrong for the document's script sizes every chunk wrong, and the only
     * symptom is a window overflowing at some later point that looks like a model failure. Silent
     * when the runtime reports no count -- which is itself worth knowing, so it says that once.
     */
    private fun logEstimateDrift(
        chunkText: String,
        reportedPromptTokens: Int,
        mode: AuditMode,
        profile: AuditPromptProfile,
    ) {
        if (reportedPromptTokens <= 0) {
            if (!warnedNoPromptTokens) {
                warnedNoPromptTokens = true
                Log.w(TAG, "engine reports no prompt token count; chunk sizing cannot be verified")
            }
            return
        }
        val estimated = AuditPromptBudget.fixedPromptTokens(mode, profile) +
            com.example.aiagent.engine.core.ContextWindow.estimateTokens(chunkText)
        val drift = (reportedPromptTokens - estimated).toDouble() / estimated
        if (kotlin.math.abs(drift) >= ESTIMATE_DRIFT_WARN) {
            Log.w(
                TAG,
                "prompt estimate off by ${(drift * 100).toInt()}%: estimated $estimated, " +
                    "runtime tokenised $reportedPromptTokens",
            )
        }
    }

    private var warnedNoPromptTokens = false

    /**
     * setForeground that never crashes the drain. Android 12+ forbids starting a foreground service
     * from the background (and the expedited quota can run out), which throws
     * ForegroundServiceStartNotAllowedException. When that happens we keep going as an ordinary
     * background worker rather than failing the whole job -- WorkManager still runs it, just without
     * the ongoing notification until it can promote again.
     */
    private suspend fun safeSetForeground(info: ForegroundInfo) {
        try {
            setForeground(info)
            if (!foregroundActive) {
                foregroundActive = true
                Log.i(TAG, "running as a foreground service")
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            // Not allowed to start/update the foreground service right now; carry on in the
            // background. Worth saying out loud rather than swallowing: without the foreground
            // service the platform stops this job at around ten minutes, which is exactly the
            // ceiling a slow turn is now allowed to reach. A run that quietly lost its promotion
            // and a run that is merely slow look identical without this line.
            if (foregroundActive || !warnedNoForeground) {
                foregroundActive = false
                warnedNoForeground = true
                Log.w(
                    TAG,
                    "not running as a foreground service (${t.javaClass.simpleName}); " +
                        "the platform may stop this job at ~10 minutes",
                )
            }
        }
    }

    private var foregroundActive = false
    private var warnedNoForeground = false

    private fun foregroundInfo(
        name: String,
        chunk: Int,
        chunks: Int,
        phase: String? = null,
    ): ForegroundInfo {
        val channelId = ensureChannel()
        val text = when {
            phase != null -> phase
            chunks > 1 -> "$name — section $chunk of $chunks"
            chunks == 1 -> "$name — analysing…"
            else -> "Preparing…"
        }
        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle("Auditing documents")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setProgress(chunks.coerceAtLeast(1), chunk, /* indeterminate = */ chunk <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

        return if (Build.VERSION.SDK_INT >= 34) {
            ForegroundInfo(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIF_ID, notification)
        }
    }

    private fun ensureChannel(): String {
        if (Build.VERSION.SDK_INT >= 26) {
            val mgr = applicationContext.getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Audit analysis", NotificationManager.IMPORTANCE_LOW),
                )
            }
        }
        return CHANNEL_ID
    }

    /**
     * Posts a "analysis complete" notification for a finished document. Separate channel and id from
     * the ongoing progress notification, and not ongoing -- so it survives the worker ending (which
     * tears down the foreground-service notification) and the user can dismiss it. Tapping opens the
     * app.
     */
    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    private fun notifyCompleted(doc: AuditDocumentEntity, result: AuditAnalysis) {
        if (!canNotify()) return

        // Tapping opens the app straight to this document's report; MainActivity reads the extra and
        // navigates once it is past the splash. FLAG_ACTIVITY_SINGLE_TOP reuses the running instance.
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_AUDIT_DOC_ID, doc.id)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            applicationContext,
            doc.id.toInt(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(applicationContext, ensureCompleteChannel())
            .setContentTitle(
                if (result.auditMode == AuditMode.QUICK) "Summary ready" else "Audit complete",
            )
            .setContentText(
                buildString {
                    append(doc.name)
                    append(" — ")
                    append(
                        if (result.auditMode == AuditMode.QUICK) {
                            "${result.keyPoints.size} key points"
                        } else {
                            "${result.nonConformities.size} non-conformities"
                        },
                    )
                    append(", ${result.actions.size} actions")
                },
            )
            .setSmallIcon(R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()

        NotificationManagerCompat.from(applicationContext)
            .notify(COMPLETE_NOTIF_BASE + (doc.id % 1000).toInt(), notification)
    }

    private fun ensureCompleteChannel(): String {
        if (Build.VERSION.SDK_INT >= 26) {
            val mgr = applicationContext.getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(COMPLETE_CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(COMPLETE_CHANNEL_ID, "Audit results", NotificationManager.IMPORTANCE_DEFAULT),
                )
            }
        }
        return COMPLETE_CHANNEL_ID
    }

    private fun canNotify(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(
                applicationContext,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "AuditDrainWorker"

        /**
         * Findings graded per call. Small enough that a 4-bit model keeps a numbered list aligned,
         * large enough that the per-call conversation rebuild is amortised. Misalignment is caught
         * by the per-finding fallback rather than by trusting this bound.
         */
        /**
         * Findings graded per call. Raised from 10 once the reply became one word per finding rather
         * than a paragraph: what limits a batch now is the *prompt* -- the titles and quotes -- not
         * the answer, and 25 of those with their one-word replies still sit inside a 4K window.
         */
        const val GRADE_BATCH_SIZE = 25

        /**
         * How close to the context limit counts as "the window ran out". A couple of tokens of slack
         * because the estimate of what the template added is not exact, and the distinction being
         * drawn -- finished vs guillotined -- does not turn on the last token or two.
         */
        const val CONTEXT_FULL_MARGIN_TOKENS = 8

        /** Room left for one chunk's reply: the window, less the real preamble and the real chunk. */
        fun extractionMaxTokens(
            contextTokens: Int,
            mode: AuditMode,
            profile: AuditPromptProfile,
            chunkText: String,
        ): Int =
            (
                contextTokens - AuditPromptBudget.fixedPromptTokens(mode, profile) -
                    com.example.aiagent.engine.core.ContextWindow.estimateTokens(chunkText)
                ).coerceAtLeast(AuditChunker.MIN_OUTPUT_RESERVE_TOKENS)

        /**
         * Starved sections in a row before a document is given up on. Two, not one: one starved
         * section is as likely to be a model looping on that particular passage as a window that is
         * too small, and only the second in a row distinguishes them.
         */
        const val STARVED_SECTIONS_BEFORE_FAILING = 2

        /** How much of an unreadable reply to log. Enough to see the shape of the failure. */
        const val REPLY_TAIL_CHARS = 400

        /** Estimate-vs-actual disagreement worth a warning. Below this, rounding explains it. */
        const val ESTIMATE_DRIFT_WARN = 0.15

        /**
         * Token cap for grading [count] findings at once. A grade is one word, but a reasoning model
         * spends a think block reaching it and the prompt asks for a sentence of reasoning too, so
         * the allowance is per finding with a fixed head start -- generous enough not to cut a real
         * answer, tight enough that a loop cannot run for half an hour.
         */
        fun gradeMaxTokens(count: Int): Int = 512 + 64 * count

        /**
         * Token cap for a one-word grading pass. A reply is "1: minor" per finding and nothing else,
         * so this is a runaway guard, not a budget -- it should never be reached in a healthy run.
         */
        fun fastGradeMaxTokens(count: Int): Int = 32 + 8 * count


        /**
         * Wall-clock ceiling for one turn.
         *
         * Sized to sit under the platform's job limit (about ten minutes) with prefill and the
         * surrounding bookkeeping to spare: a turn that outlives its job never reaches a checkpoint,
         * so it would be retried from the start forever. Above the longest legitimate extraction on
         * an 8K window, which is a full-length reply at a few tokens a second.
         */
        const val TURN_MAX_MILLIS = 5 * 60 * 1000L

        /**
         * The ceiling for a turn that is expected to be slow: llama.cpp off an accelerator, or a
         * model that reasons before answering.
         *
         * Ten minutes sits *at* the platform's job limit rather than under it, which is only safe
         * while the foreground service holds. If promotion fails -- and safeSetForeground is written
         * to carry on when it does -- the job is stopped before a ten-minute turn can checkpoint,
         * and that section is retried from the start every time. safeSetForeground now says which
         * state it is in, so that shows up in the log instead of as a document that never finishes.
         */
        const val SLOW_TURN_MAX_MILLIS = 10 * 60 * 1000L
        const val CHANNEL_ID = "audit_analysis"
        const val NOTIF_ID = 4300
        const val COMPLETE_CHANNEL_ID = "audit_complete"
        const val COMPLETE_NOTIF_BASE = 4400
    }
}
