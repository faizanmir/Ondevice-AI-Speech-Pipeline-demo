package com.example.aiagenttestapp.data.notes

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
import com.example.aiagenttestapp.data.speakers.SpeakerRepository
import com.example.aiagenttestapp.stt.AudioRecorder
import com.example.aiagenttestapp.stt.SpeakerDiarizer
import com.example.aiagenttestapp.stt.SpeechModelRepository
import com.example.aiagenttestapp.stt.SpeechRecognizer
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.absoluteValue

/**
 * Transcribes one recording in a foreground job: diarise, label the speakers, slice, transcribe.
 *
 * Why a worker rather than the ViewModel that used to do this. Diarising and then transcribing a
 * meeting-length recording per speaker turn is minutes of work, and in a ViewModel all of it died the
 * moment the user left the screen -- on a long walkthrough, quite possibly every time. It also had to
 * hold the whole recording on the heap, ~115 MB for half an hour, doubled while copying it. Here the
 * audio is read back from a file and each stage is checkpointed, so a process death costs the current
 * slice rather than the entire run.
 *
 * The note row exists before this starts and is updated as it goes, which is what lets the user leave,
 * come back, and find either a finished transcript or an honest error.
 */
@HiltWorker
class NoteTranscribeWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val noteDao: NoteDao,
    private val speechModels: SpeechModelRepository,
    private val speechRecognizer: SpeechRecognizer,
    private val audioModels: AudioModelRepository,
    private val speakers: SpeakerRepository,
    private val settings: SettingsStore,
) : CoroutineWorker(context, params) {

    /** WorkManager asks for this before `doWork` in some paths, so it must not depend on any state. */
    override suspend fun getForegroundInfo(): ForegroundInfo =
        foregroundInfo(inputData.getLong(KEY_NOTE_ID, 0L), 0f)

    override suspend fun doWork(): Result {
        val noteId = inputData.getLong(KEY_NOTE_ID, -1L)
        if (noteId < 0) return Result.failure()

        val note = noteDao.byId(noteId) ?: return Result.success() // deleted while queued
        val audio = note.audioPath?.let(::File)

        if (audio == null || !audio.exists()) {
            noteDao.failTranscription(noteId, "The recording is no longer available.")
            return Result.success()
        }

        val checkpoint = TranscriptionCheckpoint.forAudio(audio).apply { load() }

        // From the checkpoint beside the audio, not from this job's input data: a re-enqueued job (see
        // [reconcileOrphans]) has no input data of its own, and losing the markers would silently
        // produce an untagged transcript.
        val request = checkpoint.requestOrNull()
        val markers = request?.markers.orEmpty()
        val excluded = request?.excludedRanges.orEmpty()
        val expectedSpeakers = request?.expectedSpeakers ?: 0

        // Android 12+ can refuse a foreground start when the app is in the background. Refusing to
        // transcribe over it would be the wrong call -- the work is still worth doing, it just loses the
        // notification and the process-priority boost.
        runCatching { setForeground(foregroundInfo(noteId, 0f)) }

        return try {
            transcribe(noteId, audio, markers, excluded, expectedSpeakers, checkpoint)
            checkpoint.delete()
            audio.delete()
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Cancelled, not failed. The checkpoint and the audio stay so a retry resumes.
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "transcription failed for note $noteId", e)
            noteDao.failTranscription(
                noteId,
                e.message ?: "Could not transcribe the recording.",
            )
            // Success: the *work* is finished and must not be retried, and the note carries the error.
            Result.success()
        }
    }

    private suspend fun transcribe(
        noteId: Long,
        audio: File,
        markers: List<SpokenMarker>,
        excluded: List<IntRange>,
        expectedSpeakers: Int,
        checkpoint: TranscriptionCheckpoint,
    ) = withContext(Dispatchers.Default) {
        val samples = WavFile.read(audio)
        if (samples.isEmpty()) {
            noteDao.failTranscription(noteId, "The recording was empty.")
            return@withContext
        }

        // Marker edges must survive turn merging: inside a tagged span, who is speaking is the point.
        val protectedBoundaries = markers
            .flatMap { listOf(it.startSample, it.endSample) }
            .toSet()

        val speakerWork = resolveSpeakers(samples, expectedSpeakers, protectedBoundaries, checkpoint)

        val slices = SpokenMarkers.slice(
            totalSamples = samples.size,
            markers = markers,
            turns = speakerWork.turns,
            excludedRanges = excluded,
            maxSliceSamples = SpeechRecognizer.MAX_SEGMENT_SAMPLES,
            cutLongSlice = { from, until ->
                speechRecognizer.quietestCutBetween(samples, from, until)
            },
        )

        val spoken = slices.filter { !it.isTriggerPhrase }
        if (spoken.isEmpty()) {
            noteDao.failTranscription(noteId, "Nothing was recognised. Try again.")
            return@withContext
        }

        val paths = speechModels.selectedPaths()
        if (speechRecognizer.loadedModelId != paths.id) speechRecognizer.load(paths)

        val texts = mutableListOf<String?>()
        var language: String? = null

        spoken.forEachIndexed { index, slice ->
            // Resume rather than redo: on a long recording this is the difference between a process
            // death costing one slice and costing the whole run.
            val cached = checkpoint.textFor(slice.range)
            if (cached != null) {
                texts += cached.text
                if (language == null) language = cached.language
            } else {
                val piece = speechRecognizer
                    .transcribeSegments(samples, listOf(slice.range))
                    .firstOrNull()

                texts += piece?.text
                if (language == null) language = piece?.language
                checkpoint.record(slice.range, piece?.text.orEmpty(), piece?.language)
            }

            val progress = (index + 1).toFloat() / spoken.size
            noteDao.updateProgress(noteId, progress)
            runCatching { setForeground(foregroundInfo(noteId, progress)) }
        }

        val blocks = spoken.zip(texts) { slice, text ->
            TranscriptBlock(
                speaker = slice.cluster?.let { speakerWork.labels[it] },
                text = text.orEmpty(),
                tags = slice.tags,
            )
        }.filter { it.text.isNotBlank() }

        val rendered = TranscriptMarkup.render(blocks)

        // No marker was heard acoustically -- either the keyword spotter is off, or the user was
        // dictating in a language it has no model for. The marker words are in the recognised text
        // instead, so look for them there.
        val text = if (markers.isEmpty()) {
            TranscriptMarkup.wrapSpokenMarkers(rendered)
        } else {
            rendered
        }

        noteDao.finishTranscription(
            id = noteId,
            transcript = text,
            durationMillis = samples.size * 1000L / AudioRecorder.SAMPLE_RATE,
            language = language,
        )
    }

    private data class SpeakerWork(
        val turns: List<SpeakerTurn>,
        val labels: Map<Int, String>,
    )

    /**
     * Diarises and names the speakers, or returns nothing when the feature is off or unavailable.
     *
     * Checkpointed as a unit because it is the slow, all-or-nothing half: repeating a two-minute
     * diarisation after a process death would often mean never getting past it.
     *
     * The diarizer is released before the naming step. It carries its own copy of the 29 MB embedding
     * model internally, and holding both open at once would double that for no reason on a device that
     * is about to load an ASR model too.
     */
    private suspend fun resolveSpeakers(
        samples: FloatArray,
        expectedSpeakers: Int,
        protectedBoundaries: Set<Int>,
        checkpoint: TranscriptionCheckpoint,
    ): SpeakerWork {
        if (!settings.settings.value.speakerIdEnabled) return SpeakerWork(emptyList(), emptyMap())
        if (!audioModels.isReady(audioModels.speaker)) return SpeakerWork(emptyList(), emptyMap())

        checkpoint.diarisationResult()?.let { done ->
            return SpeakerWork(done.turns, done.labels)
        }

        val diarizer = SpeakerDiarizer()
        val segments = try {
            diarizer.load(
                segmentationModel = audioModels.fileFor(
                    audioModels.speaker,
                    AudioModelCatalog.SEGMENTATION,
                ),
                embeddingModel = audioModels.fileFor(
                    audioModels.speaker,
                    AudioModelCatalog.EMBEDDING,
                ),
                expectedSpeakers = expectedSpeakers,
            )
            diarizer.diarize(samples)
        } catch (e: Exception) {
            Log.w(TAG, "diarisation unavailable; transcribing without speaker labels", e)
            emptyList()
        } finally {
            diarizer.release()
        }

        if (segments.isEmpty()) return SpeakerWork(emptyList(), emptyMap())

        val turns = SpeakerTurns.build(
            segments = segments.map { DiarizedRange(it.startSample, it.endSample, it.cluster) },
            totalSamples = samples.size,
            minTurnSamples = MIN_TURN_SAMPLES,
            protectedBoundaries = protectedBoundaries,
        )

        val labels = speakers.labelClusters(samples, turns)
        checkpoint.recordDiarisation(turns, labels)

        return SpeakerWork(turns, labels)
    }

    private fun foregroundInfo(noteId: Long, progress: Float): ForegroundInfo {
        val channelId = ensureChannel()
        val percent = (progress * 100).toInt().coerceIn(0, 100)
        val notifId = NOTIF_BASE + (noteId.hashCode().absoluteValue % 1_000)

        val notification = NotificationCompat.Builder(context, channelId)
            .setContentTitle("Transcribing voice note")
            .setContentText(if (progress > 0f) "$percent%" else "Starting…")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setProgress(100, percent, /* indeterminate = */ progress <= 0f)
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
        if (Build.VERSION.SDK_INT >= 26) {
            val mgr = context.getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "Voice note transcription",
                        NotificationManager.IMPORTANCE_LOW,
                    ),
                )
            }
        }
        return CHANNEL_ID
    }

    companion object {
        private const val TAG = "NoteTranscribeWorker"
        private const val CHANNEL_ID = "note_transcription"

        /** Distinct from the download workers' bases (4200, 5300, 6400). */
        private const val NOTIF_BASE = 7500

        internal const val KEY_NOTE_ID = "noteId"

        const val WORK_TAG = "note-transcription"

        /**
         * The absorption floor: speaker turns shorter than four seconds are folded into a neighbour.
         *
         * Whisper's encoder pads every input to 30 s no matter how short it is, so cost scales with the
         * *number* of turns rather than their length. Without a floor, a lively conversation becomes a
         * hundred-odd full encoder runs. Four seconds keeps that in hand at the cost of attributing
         * brief interjections to whoever was speaking around them.
         */
        val MIN_TURN_SAMPLES = AudioRecorder.SAMPLE_RATE * 4

        fun uniqueName(noteId: Long) = "note-transcription:$noteId"

        /**
         * Enqueues transcription for a note whose audio -- and checkpoint request -- are already on disk.
         *
         * Only the note id travels in the input data. Everything else is read from the checkpoint file,
         * which is what makes re-enqueueing after WorkManager has lost the job harmless.
         */
        fun enqueue(context: Context, noteId: Long) {
            val request = OneTimeWorkRequestBuilder<NoteTranscribeWorker>()
                .setInputData(workDataOf(KEY_NOTE_ID to noteId))
                .addTag(WORK_TAG)
                .addTag(uniqueName(noteId))
                .build()

            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(uniqueName(noteId), ExistingWorkPolicy.KEEP, request)
        }

        /**
         * Deals with notes left mid-transcription by a process that died without a job to resume them.
         *
         * A row stuck on "Transcribing…" with nothing running is worse than an error, because the user
         * cannot tell the difference or act on it. Where the audio survives, the job is simply enqueued
         * again -- the checkpoint means it picks up where it stopped rather than starting over. Where it
         * does not, the note is marked failed so at least it says so.
         */
        suspend fun reconcileOrphans(context: Context, noteDao: NoteDao) {
            val stuck = noteDao.withStatus(NoteStatus.Transcribing)
            if (stuck.isEmpty()) return

            val workManager = WorkManager.getInstance(context.applicationContext)

            for (note in stuck) {
                val live = runCatching {
                    workManager.getWorkInfosForUniqueWorkFlow(uniqueName(note.id))
                        .first()
                        .any { info -> !info.state.isFinished }
                }.getOrDefault(false)

                if (live) continue

                val audio = note.audioPath?.let(::File)
                if (audio != null && audio.exists()) {
                    Log.i(TAG, "re-enqueueing orphaned transcription for note ${note.id}")
                    enqueue(context, note.id)
                } else {
                    noteDao.failTranscription(
                        note.id,
                        "Transcription was interrupted and the recording is gone.",
                    )
                }
            }
        }
    }
}
