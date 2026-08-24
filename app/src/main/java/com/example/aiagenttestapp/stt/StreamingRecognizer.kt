package com.example.aiagenttestapp.stt

import android.util.Log
import com.example.aiagenttestapp.data.SettingsStore
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A recogniser that is fed audio as it arrives and revises its answer continuously.
 *
 * The counterpart to [SpeechRecognizer], and deliberately a separate class rather than a third
 * branch inside it, because the two have incompatible shapes. An offline recogniser is a function:
 * hand it a clip, get a transcript. A streaming one is a *conversation with state* -- a stream that
 * accumulates, a partial result that changes as more audio arrives, and an endpoint that decides
 * when one utterance has ended and the next begins. Folding that into a class whose whole contract
 * is "clip in, text out" would have meant a `transcribe(samples)` that lies about what it does.
 *
 * ### Why this exists at all
 *
 * Two reasons, and the second is the one that motivated it:
 *
 *  - **No length wall.** sherpa's offline Whisper truncates at 2950 mel frames -- 29.5 s -- and says
 *    so only in a log line, which is why every other ONNX recording has to be cut into pieces first.
 *    A transducer has no such limit; audio of any length is one continuous decode.
 *  - **It replaces a much worse imitation of itself.** The record screen used to detect spoken
 *    commands by re-transcribing a rolling four-second window with the *full* offline model every
 *    1.8 s. That is a streaming recogniser built out of the wrong parts, and it cost so much that
 *    it had to be disabled entirely on the Gemma path.
 *
 * ### What it costs
 *
 * A transducer commits to each word having seen only a little audio after it, where Whisper sees the
 * whole clip before deciding anything. On the measured audit recordings the weakest category was
 * already identifiers -- `B74-229` came back as `74-2299` from a model with full context -- and this
 * is the class that suffers most from having less. Streaming is the right default for *watching*
 * words appear; it is not the right default for a transcript that has to be evidence.
 *
 * Not thread-safe by construction; [lock] serialises every native call, because the stream carries
 * decoder state and two coroutines feeding it at once would interleave audio.
 */
class StreamingRecognizer(private val settings: SettingsStore) {

    private val lock = Mutex()

    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null

    /** Text from utterances the endpoint detector has already closed. */
    private val settled = StringBuilder()

    /** Which model is loaded, so a caller can tell whether the Settings choice still matches. */
    @Volatile
    var loadedModelId: String? = null
        private set

    val isLoaded: Boolean get() = loadedModelId != null

    /**
     * Loads the transducer. Allocation-heavy; never call from the main thread.
     *
     * Every file is checked before the native constructor sees it, for the reason
     * [KeywordDetector.load] spells out: sherpa's Kotlin bindings store whatever handle the native
     * side returns without inspecting it, so a rejected config becomes a null pointer that is
     * dereferenced on the *next* call. That crash is a SIGSEGV inside `createStream`, which no
     * `runCatching` can turn back into a working recording.
     */
    suspend fun load(paths: SpeechModelPaths) = lock.withLock {
        withContext(Dispatchers.IO) {
            check(paths.kind.isStreaming) {
                "${paths.id} is not a streaming model"
            }

            releaseLocked()

            val encoder = requireFile(paths.encoder, "encoder")
            val decoder = requireFile(paths.decoder, "decoder")
            val joiner = requireFile(paths.joiner, "joiner")
            val tokens = requireFile(paths.tokens, "tokens")

            val config = OnlineRecognizerConfig(
                featConfig = FeatureConfig(
                    sampleRate = AudioRecorder.SAMPLE_RATE,
                    featureDim = 80,
                ),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = encoder,
                        decoder = decoder,
                        joiner = joiner,
                    ),
                    tokens = tokens,
                    numThreads = recommendedThreadCount(),
                    provider = settings.settings.value.onnxProvider.slug,
                ),
                // Endpointing on. Without it the recogniser returns one ever-growing hypothesis for
                // the whole recording and never commits, so nothing can be treated as final and the
                // decode cost climbs with length -- which would give back the very property that
                // makes streaming worth having.
                enableEndpoint = true,
                decodingMethod = "greedy_search",
            )

            recognizer = OnlineRecognizer(assetManager = null, config = config)
            stream = recognizer?.createStream()
            settled.setLength(0)
            loadedModelId = paths.id
            Log.i(TAG, "streaming ASR model loaded: ${paths.id}")
        }
    }

    /**
     * Feeds one chunk of captured audio and returns the transcript so far.
     *
     * The returned text is *settled utterances plus the current partial*, so it grows and the tail
     * of it can change between calls. Callers show it; they do not append it.
     */
    suspend fun accept(samples: FloatArray): String = lock.withLock {
        val active = recognizer ?: return@withLock settled.toString()
        val current = stream ?: return@withLock settled.toString()
        if (samples.isEmpty()) return@withLock textLocked(active, current)

        withContext(Dispatchers.Default) {
            current.acceptWaveform(samples, AudioRecorder.SAMPLE_RATE)
            while (active.isReady(current)) active.decode(current)

            // An endpoint means the recogniser considers this utterance finished. Settle its text
            // and reset, so the next utterance decodes from a clean state rather than dragging the
            // whole recording's history behind it.
            if (active.isEndpoint(current)) {
                val text = active.getResult(current).text.trim()
                if (text.isNotEmpty()) {
                    if (settled.isNotEmpty()) settled.append(' ')
                    settled.append(text)
                }
                active.reset(current)
            }
        }

        textLocked(active, current)
    }

    /**
     * Tells the recogniser no more audio is coming, drains it, and returns the final transcript.
     *
     * Separate from [accept] because a transducer holds back the tail of what it has heard until it
     * knows nothing more is coming -- stopping without this loses the last utterance.
     */
    suspend fun finish(): String = lock.withLock {
        val active = recognizer ?: return@withLock settled.toString()
        val current = stream ?: return@withLock settled.toString()

        withContext(Dispatchers.Default) {
            current.inputFinished()
            while (active.isReady(current)) active.decode(current)

            val text = active.getResult(current).text.trim()
            if (text.isNotEmpty()) {
                if (settled.isNotEmpty()) settled.append(' ')
                settled.append(text)
            }
        }

        settled.toString()
    }

    /** Drops everything heard so far and starts a fresh stream, keeping the model loaded. */
    suspend fun reset() = lock.withLock {
        val active = recognizer ?: return@withLock
        withContext(Dispatchers.IO) {
            stream?.release()
            stream = active.createStream()
            settled.setLength(0)
        }
    }

    suspend fun release() = lock.withLock {
        withContext(Dispatchers.IO) { releaseLocked() }
    }

    private fun releaseLocked() {
        stream?.release()
        stream = null
        recognizer?.release()
        recognizer = null
        settled.setLength(0)
        loadedModelId = null
    }

    /** Settled utterances plus whatever the current one has decoded so far. */
    private fun textLocked(active: OnlineRecognizer, current: OnlineStream): String {
        val partial = active.getResult(current).text.trim()
        return when {
            partial.isEmpty() -> settled.toString()
            settled.isEmpty() -> partial
            else -> "$settled $partial"
        }
    }

    private fun requireFile(file: java.io.File?, part: String): String {
        check(file != null && file.isFile && file.length() > 0L) {
            "streaming model $part is missing or empty at ${file?.absolutePath}"
        }
        return file.absolutePath
    }

    private fun recommendedThreadCount(): Int =
        (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 4)

    private companion object {
        const val TAG = "StreamingRecognizer"
    }
}
