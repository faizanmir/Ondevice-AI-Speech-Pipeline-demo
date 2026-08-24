package com.example.aiagenttestapp.ui.chat

import com.example.aiagenttestapp.stt.AudioRecorder
import com.example.aiagenttestapp.stt.SpeechModelRepository
import com.example.aiagenttestapp.stt.SpeechRecognizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Dictation for the chat's message box: record, transcribe, hand back the words.
 *
 * Pulled out of the view model because none of it is about chat. It owns a microphone, a rolling
 * sample buffer and a ~240 MB speech recogniser whose lifetime has a rule of its own (see
 * [releaseRecogniserIfOwned]) -- three concerns that had nothing to do with conversations, models
 * or tool calls, and that made the view model's teardown read as though it did.
 *
 * It reports through [ChatDictation.Listener] rather than touching UI state itself, so what appears
 * on screen stays the view model's decision.
 */
class ChatDictation(
    private val audioRecorder: AudioRecorder,
    private val speechRecognizer: SpeechRecognizer,
    private val speechModels: SpeechModelRepository,
    private val scope: CoroutineScope,
    private val listener: Listener,
) {

    /** What dictation tells the screen. */
    interface Listener {
        /** Mic amplitude while recording, for the level meter. */
        fun onLevel(level: Float)

        /** Recording stopped without producing anything -- the mic failed or was denied. */
        fun onRecordingFailed()

        /** Transcription finished. [text] is blank when nothing intelligible was said. */
        fun onTranscribed(text: String)

        /** Transcription failed; the screen should stop showing a spinner. */
        fun onTranscriptionFailed()
    }

    private var recordingJob: Job? = null
    private val samples = mutableListOf<Float>()
    private val samplesLock = Any()

    /**
     * Whether *this* screen was what loaded the recogniser.
     *
     * The recogniser is shared with the Voice Notes screen and the background transcription
     * worker. Releasing one this screen merely borrowed would tear it out from under them.
     */
    private var loadedRecogniser = false

    val isRecording: Boolean get() = recordingJob != null

    /** Starts the mic. Levels arrive on [Listener.onLevel] until [stopAndTranscribe]. */
    fun start() {
        synchronized(samplesLock) { samples.clear() }

        recordingJob = scope.launch {
            try {
                audioRecorder.record().collect { chunk ->
                    synchronized(samplesLock) { chunk.samples.forEach(samples::add) }
                    listener.onLevel(chunk.level)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                listener.onRecordingFailed()
            }
        }
    }

    /** Stops the mic and transcribes what was captured, reporting the text to the listener. */
    fun stopAndTranscribe() {
        recordingJob?.cancel()
        recordingJob = null

        scope.launch {
            try {
                val paths = speechModels.selectedPaths()
                if (speechRecognizer.loadedModelId != paths.id) {
                    speechRecognizer.load(paths)
                    loadedRecogniser = true
                }

                val captured = synchronized(samplesLock) { samples.toFloatArray() }
                // Dictation only wants the words; the reply's language is the conversation's
                // business. transcribeLong so a dictation past ~30 s is not clipped to its first
                // half-minute.
                listener.onTranscribed(speechRecognizer.transcribeLong(captured).text)
            } catch (e: Exception) {
                listener.onTranscriptionFailed()
            }
        }
    }

    /**
     * Frees the recogniser's native memory, but only if dictation here is what loaded it.
     *
     * Runs on [teardownScope] rather than the caller's, because releasing *waits* for any decode
     * still in flight: the recogniser is shared with the background transcription worker, and
     * freeing native memory under a running decode takes the process down. The view model's scope
     * is already cancelled by the time this is called.
     */
    fun releaseRecogniserIfOwned(teardownScope: CoroutineScope) {
        recordingJob?.cancel()
        recordingJob = null
        if (!loadedRecogniser) return

        loadedRecogniser = false
        val recogniser = speechRecognizer
        teardownScope.launch { runCatching { recogniser.release() } }
    }
}
