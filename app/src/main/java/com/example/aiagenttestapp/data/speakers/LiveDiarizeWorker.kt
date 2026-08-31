package com.example.aiagenttestapp.data.speakers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.aiagenttestapp.data.SettingsStore
import com.example.aiagenttestapp.data.audiomodels.AudioModelCatalog
import com.example.aiagenttestapp.data.audiomodels.AudioModelRepository
import com.example.aiagenttestapp.data.notes.WavFile
import com.example.aiagenttestapp.data.speakers.live.ChunkBuffer
import com.example.aiagenttestapp.data.speakers.live.ClusterObservation
import com.example.aiagenttestapp.data.speakers.live.LiveChunk
import com.example.aiagenttestapp.data.speakers.live.LiveChunker
import com.example.aiagenttestapp.data.speakers.live.LiveSessionRosters
import com.example.aiagenttestapp.data.speakers.live.LiveTranscript
import com.example.aiagenttestapp.data.speakers.live.RosterVoice
import com.example.aiagenttestapp.data.speakers.live.SessionSpeakerTracker
import com.example.aiagenttestapp.stt.AudioRecorder
import com.example.aiagenttestapp.stt.AudioSegmenter
import com.example.aiagenttestapp.stt.DiarizedSegment
import com.example.aiagenttestapp.stt.SpeakerDiarizer
import com.example.aiagenttestapp.stt.SpeechActivityDetector
import com.example.aiagenttestapp.stt.SpeechModelRepository
import com.example.aiagenttestapp.stt.SpeechRecognizer
import com.example.aiagenttestapp.stt.ThreadBudget
import com.example.aiagenttestapp.stt.TimedWords
import com.example.aiagenttestapp.stt.WarmPool
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.absoluteValue

/**
 * Diarises and transcribes a recording **while its audio is still arriving**, and shows the result
 * as it goes.
 *
 * [DiarizeWorker] assumes the recording is finished: it reads the whole WAV, clusters every voice
 * against every other, and answers once. This worker takes the same audio as a stream -- a file fed
 * at the speed it would be spoken, or a capture still being written -- cuts it into chunks at pauses,
 * runs each chunk through the **same stage functions** the batch worker uses (the diariser, the
 * fold, the recogniser, [SpeakerAlignment]), and rewrites the recording's blocks after every chunk.
 * What it cannot borrow from the batch path is speaker identity across chunks, which there comes from
 * naming and therefore from enrolment; here [SessionSpeakerTracker] carries it by voiceprint, so an
 * unenrolled voice keeps one letter for the whole session.
 *
 * **The labels are provisional, and the batch worker is the last word.** When the audio ends this
 * worker hands the finished file to [DiarizeWorker], whose whole-recording pass produces the same
 * transcript it always has, replacing the provisional blocks. That is deliberate: the live view exists
 * so people see something while a meeting runs, not to change what the recording's transcript is. It
 * also makes recovery free -- a session that dies with the process leaves its WAV on disk, and
 * [DiarizeWorker.reconcile] runs the batch pass on it.
 *
 * **Why chunks of thirty seconds.** The tracker and the diariser both want as much of a voice as they
 * can get, and words appear on screen only when a chunk closes, so the chunk length is the latency
 * the user sees. Thirty seconds, cut at the next pause, was chosen on 2026-08-31 as the balance; a
 * monologue with no pause is cut at forty-five regardless, at its quietest frame. See [LiveChunker].
 *
 * **Why one chunk at a time.** Chunks queue into a single consumer rather than fanning out onto lanes.
 * Real time gives the models thirty seconds to process thirty seconds, and on both tablets they need
 * two to four; a backlog only forms when the device is far slower than the audio, and then a second
 * lane would fight the first for the same cores. The queue depth is logged so that is visible.
 */
@HiltWorker
class LiveDiarizeWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val dao: DiarizedDao,
    private val speakers: SpeakerRepository,
    private val audioModels: AudioModelRepository,
    private val speechModels: SpeechModelRepository,
    private val recognizer: SpeechRecognizer,
    private val settings: SettingsStore,
    private val diarizerPool: WarmPool<SpeakerDiarizer>,
    private val rosters: LiveSessionRosters,
) : CoroutineWorker(context, params) {

    /** How the audio reaches the session. */
    enum class Mode {
        /** A finished file, fed at the pace it would be spoken: the demonstration and the latency measurement. */
        FilePaced,

        /** A finished file, fed as fast as the models take it: the deterministic test of the pipeline. */
        FileFast,

        /** A capture in progress: the WAV grows while this runs, and the row's duration turning non-zero says it stopped. */
        Follow,
    }

    private class ChunkAudio(val chunk: LiveChunk, val samples: FloatArray, val fedAtMillis: Long)

    override suspend fun doWork(): Result {
        val id = inputData.getLong(KEY_RECORDING_ID, -1L)
        if (id < 0) return Result.failure()
        val mode = inputData.getString(KEY_MODE)?.let { runCatching { Mode.valueOf(it) }.getOrNull() }
            ?: Mode.FilePaced

        val recording = dao.byId(id) ?: return Result.success()
        val audio = File(recording.audioPath)
        if (mode != Mode.Follow && !audio.exists()) {
            dao.fail(id, "The audio for this recording is no longer available.")
            return Result.success()
        }

        runCatching { setForeground(foregroundInfo(id, 0f)) }

        var diarizer: SpeakerDiarizer? = null
        var diarizerKey: List<Any>? = null
        val vad = SpeechActivityDetector(context.assets)
        return try {
            val model = speechModels.selected
            check(model.kind.reportsWordTimings) {
                "${model.label} reports no word timings. Choose ${speechModels.wordTimingChoices}."
            }
            check(speechModels.isDownloaded(model)) { "${model.label} is not downloaded yet." }
            val bundle = audioModels.speaker
            check(audioModels.isReady(bundle)) { "The speaker identification models are not downloaded yet." }

            dao.beginLive(id)
            val provider = settings.settings.value.onnxProvider.slug
            val threads = ThreadBudget.concurrent(
                weights = ThreadBudget.Weights(bundle.diariseWeight, model.transcribeWeight),
                fastCores = ThreadBudget.detectFastCores(),
            )
            Log.i(TAG, "live session $id ($mode): diarise ${threads.diarise}, transcribe ${threads.transcribe} threads")

            // The same key the batch worker uses for its whole-recording diariser, so the two paths
            // hand each other warm instances instead of evicting them.
            val segmentation = audioModels.fileFor(bundle, AudioModelCatalog.SEGMENTATION)
            val embedding = audioModels.fileFor(bundle, AudioModelCatalog.EMBEDDING)
            diarizerKey = listOf(segmentation.absolutePath, embedding.absolutePath, recording.expectedSpeakers, threads.diarise, provider)
            diarizer = diarizerPool.acquire(diarizerKey)?.also { Log.i(TAG, "diarizer reused warm") }
                ?: SpeakerDiarizer().apply {
                    load(segmentation, embedding, recording.expectedSpeakers, threads.diarise, provider)
                }
            if (recognizer.loadedModelId != model.id || recognizer.loadedThreadCount != threads.transcribe) {
                recognizer.load(speechModels.selectedPaths(), threadCount = threads.transcribe)
            }
            speakers.prepare()
            vad.load(maxSpeechSamples = MAX_SPEECH_SAMPLES, provider = provider)

            val chunker = LiveChunker(MIN_CHUNK_SAMPLES, MAX_CHUNK_SAMPLES, PAD_SAMPLES)
            val tracker = SessionSpeakerTracker()
            val transcript = LiveTranscript()
            val started = System.currentTimeMillis()
            var chunksDone = 0
            var lagTotal = 0L

            coroutineScope {
                val queue = Channel<ChunkAudio>(Channel.UNLIMITED)
                var queued = 0

                val consumer = launch(Dispatchers.Default) {
                    for (item in queue) {
                        val backlog = queued - chunksDone - 1
                        processChunk(id, item, diarizer!!, tracker, transcript)
                        chunksDone++
                        val lag = System.currentTimeMillis() - item.fedAtMillis
                        lagTotal += lag
                        Log.i(
                            TAG,
                            "live chunk ${item.chunk.index}: %.1f-%.1fs shown %.1fs after its audio, backlog %d, %d voice(s) so far".format(
                                item.chunk.startSample / RATE_F,
                                item.chunk.endSample / RATE_F,
                                lag / 1000f,
                                backlog.coerceAtLeast(0),
                                tracker.speakers.size,
                            ),
                        )
                    }
                }

                // The audio, block by block, into the VAD and a buffer of the current chunk.
                val buffer = ChunkBuffer(capacity = MAX_CHUNK_SAMPLES + BLOCK_SAMPLES)
                var consumed = 0
                suspend fun emit(cutAt: Int) {
                    val chunk = chunker.commit(cutAt)
                    val samples = buffer.take(chunk.startSample, chunk.endSample)
                    queued++
                    queue.send(ChunkAudio(chunk, samples, System.currentTimeMillis()))
                }

                val totalKnown = if (mode == Mode.Follow) null else WavFile.Reader(audio).use { it.sampleCount }
                feed(mode, audio, id, started) { block ->
                    currentCoroutineContext().ensureActive()
                    vad.acceptStream(block)
                    buffer.append(consumed, block)
                    consumed += block.size

                    when (val cut = chunker.cutPoint(vad.regionsSoFar(), vad.classifiedUpTo(), consumed)) {
                        is LiveChunker.Cut.AtSilence -> emit(cut.sample)
                        is LiveChunker.Cut.AtCap -> {
                            // Peek, not take: the chunk itself is taken a moment later, up to the
                            // quiet frame this finds. Taking here dropped the buffer's start past
                            // the chunk's start, and the second read indexed 45 s before the array.
                            val local = buffer.peek(chunker.chunkStart, consumed)
                            val quiet = AudioSegmenter.quietestCutBetween(
                                local,
                                from = cut.searchFrom - chunker.chunkStart,
                                until = cut.searchUntil - chunker.chunkStart,
                                limits = AudioSegmenter.Limits(target = 0, max = local.size),
                            )
                            emit(chunker.chunkStart + quiet)
                        }
                        null -> Unit
                    }
                    totalKnown?.let { total -> dao.updateProgress(id, consumed.toFloat() / total) }
                }

                vad.endStream()
                chunker.finish(consumed)?.let { tail ->
                    queued++
                    queue.send(ChunkAudio(tail, buffer.take(tail.startSample, tail.endSample), System.currentTimeMillis()))
                }
                queue.close()
                consumer.join()
            }

            Log.i(
                TAG,
                "live session $id done: $chunksDone chunk(s) in %.1fs, mean %.1fs from audio to screen, %d voice(s): %s".format(
                    (System.currentTimeMillis() - started) / 1000f,
                    if (chunksDone > 0) lagTotal / 1000f / chunksDone else 0f,
                    tracker.speakers.size,
                    tracker.labels().values.joinToString(),
                ),
            )

            // The last word: the whole-recording pass, on the finished file -- seeded with what this
            // session learned, so its speakers keep the labels the user has been watching. See
            // [LiveSessionRosters].
            rosters.put(
                id,
                tracker.speakers
                    .filter { it.voiceprint.isNotEmpty() }
                    .map { RosterVoice(it.label, it.voiceprint) },
            )
            dao.beginRun(id, recording.expectedSpeakers)
            DiarizeWorker.enqueue(context, id)
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "live session failed", e)
            dao.fail(id, e.message ?: "The live session failed.")
            Result.success()
        } finally {
            vad.release()
            diarizer?.let { d -> diarizerKey?.let { diarizerPool.stash(it, d) } }
        }
    }

    /**
     * One chunk through both models and onto the screen.
     *
     * Diarisation and recognition run concurrently, as in the batch worker, and meet at
     * [SpeakerAlignment]. Before alignment the chunk's clusters -- numbered from zero by sherpa,
     * meaningless beyond this chunk -- are exchanged for session speaker ids by the tracker, so the
     * blocks stored carry an identity that survives the chunk boundary. Every block of the recording
     * is then rendered afresh with the tracker's current labels and written in one transaction.
     */
    private suspend fun processChunk(
        id: Long,
        item: ChunkAudio,
        diarizer: SpeakerDiarizer,
        tracker: SessionSpeakerTracker,
        transcript: LiveTranscript,
    ) = coroutineScope {
        val chunk = item.chunk
        val samples = item.samples

        val turnsDeferred = async(Dispatchers.Default) {
            val t = System.currentTimeMillis()
            val local = diarizer.diarize(samples)
            val profiles = speakers.profileClusters(samples, local)
            Triple(profiles, System.currentTimeMillis() - t, local.size)
        }
        val wordsDeferred = async(Dispatchers.Default) {
            val t = System.currentTimeMillis()
            val pieces = recognizer.transcribeSegments(samples, listOf(0..samples.lastIndex))
            TimedWords.offsetBySamples(pieces.flatMap { it.words }, chunk.startSample, AudioRecorder.SAMPLE_RATE) to
                (System.currentTimeMillis() - t)
        }
        val (profiles, diariseMillis, rawTurns) = turnsDeferred.await()
        val (words, transcribeMillis) = wordsDeferred.await()

        val sessionIds = tracker.assign(
            profiles.profiles.map { p ->
                ClusterObservation(p.cluster, p.samples, p.voiceprint, p.enrolled?.acceptedName)
            },
        )
        val turns = profiles.turns.map { turn ->
            DiarizedSegment(
                startSample = turn.startSample + chunk.startSample,
                endSample = turn.endSample + chunk.startSample,
                cluster = sessionIds[turn.cluster] ?: SpeakerAlignment.UNATTRIBUTED,
            )
        }
        val blocks = SpeakerAlignment.blocks(words, turns, AudioRecorder.SAMPLE_RATE)
        transcript.record(chunk.index, blocks)

        val rows = transcript.render(
            labels = tracker.labels(),
            unknownLabel = "${SpeakerRepository.UNKNOWN_SPEAKER_PREFIX} ?",
            unknownPrefix = SpeakerRepository.UNKNOWN_SPEAKER_PREFIX,
            minSamples = SHORT_BLOCK_SECONDS * AudioRecorder.SAMPLE_RATE,
        ).map { block ->
            DiarizedBlock(
                recordingId = id,
                startSample = block.startSample,
                endSample = block.endSample,
                cluster = block.cluster,
                speakerName = block.name,
                text = block.text,
            )
        }
        dao.replaceBlocks(id, rows)

        Log.i(
            TAG,
            "live chunk ${chunk.index}: %d turns -> %d clusters -> %s; %d words; diarise %.1fs || transcribe %.1fs".format(
                rawTurns,
                profiles.profiles.size,
                sessionIds.values.distinct().map { tracker.labels()[it] }.joinToString(),
                words.size,
                diariseMillis / 1000f,
                transcribeMillis / 1000f,
            ),
        )
    }

    /**
     * Reads the audio the way the mode says it arrives and hands it over in half-second blocks.
     *
     * [Mode.FilePaced] sleeps so that the audio never runs ahead of the wall clock -- what a
     * microphone would deliver. [Mode.Follow] reads whatever the writer has appended since the last
     * look, and stops when the row's duration has been set *and* the file has been drained, or when
     * the file has not grown for [FOLLOW_STALL_MILLIS] (the capture died without saying so).
     */
    private suspend fun feed(
        mode: Mode,
        audio: File,
        id: Long,
        startedMillis: Long,
        onBlock: suspend (FloatArray) -> Unit,
    ) = withContext(Dispatchers.IO) {
        when (mode) {
            Mode.FilePaced, Mode.FileFast -> WavFile.Reader(audio).use { reader ->
                var from = 0
                while (from < reader.sampleCount) {
                    val until = minOf(from + BLOCK_SAMPLES, reader.sampleCount)
                    if (mode == Mode.FilePaced) {
                        val dueAt = startedMillis + until * 1000L / AudioRecorder.SAMPLE_RATE
                        val wait = dueAt - System.currentTimeMillis()
                        if (wait > 0) delay(wait)
                    }
                    onBlock(reader.read(from, until))
                    from = until
                }
            }

            Mode.Follow -> {
                var from = 0
                var lastGrowth = System.currentTimeMillis()
                while (true) {
                    val available = runCatching { WavFile.Reader(audio).use { it.sampleCount } }.getOrDefault(0)
                    if (available > from) {
                        lastGrowth = System.currentTimeMillis()
                        while (from < available) {
                            val until = minOf(from + BLOCK_SAMPLES, available)
                            onBlock(WavFile.read(audio, from, until))
                            from = until
                        }
                        continue
                    }
                    val stopped = (dao.byId(id)?.durationMillis ?: 0L) > 0L
                    if (stopped) break
                    if (System.currentTimeMillis() - lastGrowth > FOLLOW_STALL_MILLIS) {
                        Log.w(TAG, "live capture stopped growing; treating it as ended")
                        break
                    }
                    delay(FOLLOW_POLL_MILLIS)
                }
            }
        }
    }

    private fun foregroundInfo(runId: Long, progress: Float): ForegroundInfo {
        val channelId = ensureChannel()
        val percent = (progress * 100).toInt().coerceIn(0, 100)
        val notifId = NOTIF_BASE + (runId.hashCode().absoluteValue % 1_000)
        val notification = NotificationCompat.Builder(context, channelId)
            .setContentTitle("Listening live")
            .setContentText("Speakers and words appear as the audio plays")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setProgress(100, percent, progress <= 0f)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        return if (Build.VERSION.SDK_INT >= 34) {
            ForegroundInfo(notifId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notifId, notification)
        }
    }

    private fun ensureChannel(): String {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager?.getNotificationChannel(CHANNEL_ID) == null) {
            manager?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Live speaker transcripts", NotificationManager.IMPORTANCE_LOW),
            )
        }
        return CHANNEL_ID
    }

    companion object {
        private const val TAG = "LiveDiarizeWorker"
        private const val CHANNEL_ID = "speaker_diarization_live"
        private const val NOTIF_BASE = 9800
        private const val RATE_F = AudioRecorder.SAMPLE_RATE.toFloat()

        internal const val KEY_RECORDING_ID = "recordingId"
        internal const val KEY_MODE = "mode"

        /** Half a second: the VAD's own frame cadence, and fine enough that pacing is smooth. */
        private const val BLOCK_SAMPLES = AudioRecorder.SAMPLE_RATE / 2

        /** A chunk holds at least this much before it may be cut at a pause. See [LiveChunker]. */
        private const val MIN_CHUNK_SAMPLES = 30 * AudioRecorder.SAMPLE_RATE

        /** ... and is cut at its quietest frame after this much, pause or no pause. */
        private const val MAX_CHUNK_SAMPLES = 45 * AudioRecorder.SAMPLE_RATE

        /** How far into a pause the cut lands, so the last word is never clipped. */
        private const val PAD_SAMPLES = AudioRecorder.SAMPLE_RATE / 4

        /** Same as the batch worker: blocks under this are folded into a neighbour. */
        private const val SHORT_BLOCK_SECONDS = 2

        /** The VAD's forced-close length; irrelevant to chunking, which cuts far sooner. */
        private const val MAX_SPEECH_SAMPLES = 5 * 60 * AudioRecorder.SAMPLE_RATE

        private const val FOLLOW_POLL_MILLIS = 250L
        private const val FOLLOW_STALL_MILLIS = 15_000L

        fun uniqueName(id: Long) = "diarize-live:$id"

        fun enqueue(context: Context, id: Long, mode: Mode) {
            val request = OneTimeWorkRequestBuilder<LiveDiarizeWorker>()
                .setInputData(workDataOf(KEY_RECORDING_ID to id, KEY_MODE to mode.name))
                .addTag(uniqueName(id))
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(uniqueName(id), ExistingWorkPolicy.REPLACE, request)
        }

        fun cancel(context: Context, id: Long) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(uniqueName(id))
        }
    }
}
