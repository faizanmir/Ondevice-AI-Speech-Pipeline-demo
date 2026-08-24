package com.example.aiagenttestapp.stt

import android.util.Log
import com.example.aiagenttestapp.data.SettingsStore
import com.k2fsa.sherpa.onnx.OnlinePunctuation
import com.k2fsa.sherpa.onnx.OnlinePunctuationConfig
import com.k2fsa.sherpa.onnx.OnlinePunctuationModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Puts capitals and sentence punctuation back into a transcript that has none.
 *
 * Exists for exactly one caller: [StreamingRecognizer]. A streaming transducer emits raw tokens, so
 * a recording comes back as `GOOD MORNING THIS IS THE OPENING NARRATION FOR THE SURVEILLANCE AUDIT`
 * -- measured on device, not hypothesised. The offline recognisers never need this; SenseVoice is
 * loaded with inverse text normalisation on and Whisper punctuates natively.
 *
 * It matters more than legibility. The transcript is fed to a summarising language model and scanned
 * for spoken marker phrases, and an uncased run-on stream is worse input for both. Without this the
 * streaming path produces text the rest of the pipeline handles badly.
 *
 * ### Optional by construction
 *
 * Every method degrades to a no-op when the model is absent, and [isLoaded] says so. A missing
 * punctuation bundle must cost the user their capitals, never their recording -- so nothing here
 * throws on the transcription path, and a streaming note taken before the bundle was downloaded is
 * still a note.
 */
class Punctuator(private val settings: SettingsStore) {

    private val lock = Mutex()
    private var punctuation: OnlinePunctuation? = null

    val isLoaded: Boolean get() = punctuation != null

    /**
     * Loads the model, or leaves the punctuator dormant if its files are not on disk.
     *
     * Returns whether it is usable afterwards rather than throwing, because "the user has not
     * downloaded this optional extra" is an ordinary state, not an error.
     */
    suspend fun load(model: File, vocab: File): Boolean = lock.withLock {
        withContext(Dispatchers.IO) {
            if (punctuation != null) return@withContext true

            if (!model.isFile || model.length() == 0L || !vocab.isFile || vocab.length() == 0L) {
                Log.i(TAG, "punctuation model not present; streaming transcripts stay unpunctuated")
                return@withContext false
            }

            runCatching {
                OnlinePunctuation(
                    assetManager = null,
                    config = OnlinePunctuationConfig(
                        model = OnlinePunctuationModelConfig(
                            cnnBilstm = model.absolutePath,
                            bpeVocab = vocab.absolutePath,
                            numThreads = 1,
                            provider = settings.settings.value.onnxProvider.slug,
                        ),
                    ),
                )
            }.onSuccess {
                punctuation = it
                Log.i(TAG, "punctuation model loaded")
            }.onFailure {
                // Logged and swallowed on purpose: see the class docs. Losing punctuation is a
                // cosmetic regression; failing the transcription over it is not.
                Log.w(TAG, "could not load the punctuation model", it)
            }

            punctuation != null
        }
    }

    /**
     * Returns [text] with capitals and punctuation, or unchanged when no model is loaded.
     *
     * **The input is lower-cased first, deliberately.** The model restores case rather than editing
     * it: it is trained on caseless text and predicts, for each token, whether a capital and a mark
     * belong there. Hand it `GOOD MORNING THIS IS` and every token already carries the capital it
     * would have added, so it returns the string untouched -- which is exactly what happened on
     * device the first time this ran, with the model loaded and the transcript still shouting.
     *
     * Lower-casing here rather than at the call site because it is this class's contract, not the
     * caller's problem: give it text with no case information, get text with case restored. The only
     * caller is a transducer whose output is uppercase-by-construction, so nothing is lost.
     *
     * Blank input short-circuits: a silent slice is the common case on a long recording and there is
     * nothing to punctuate.
     */
    suspend fun punctuate(text: String): String {
        if (text.isBlank()) return text
        val active = lock.withLock { punctuation } ?: return text

        val caseless = text.lowercase()
        return withContext(Dispatchers.Default) {
            runCatching { active.addPunctuation(caseless) }
                .getOrElse {
                    Log.w(TAG, "punctuation failed; returning the raw text", it)
                    text
                }
        }
    }

    suspend fun release() = lock.withLock {
        withContext(Dispatchers.IO) {
            punctuation?.release()
            punctuation = null
        }
    }

    private companion object {
        const val TAG = "Punctuator"
    }
}
