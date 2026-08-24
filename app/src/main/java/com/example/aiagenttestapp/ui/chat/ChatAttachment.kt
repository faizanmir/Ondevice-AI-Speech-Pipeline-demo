package com.example.aiagenttestapp.ui.chat

import android.net.Uri
import com.example.aiagent.engine.core.ContextWindow
import com.example.aiagenttestapp.data.FileTextExtractor

/**
 * The file staged alongside the next message: its extracted text, and how much of it fits.
 *
 * Its own type because the text is the part that must *not* live in UI state -- a PDF's worth of
 * characters re-emitted on every recomposition is a real cost, and holding it here keeps
 * [ChatUiState] to what the screen actually draws. What the user sees (the chip, the truncation
 * warning, an error) stays state; the payload does not.
 */
class ChatAttachment(private val extractor: FileTextExtractor) {

    /** The staged text, or null when nothing is attached. Read once, when a message is sent. */
    var text: String? = null
        private set

    /** What extraction produced, for the screen to render. */
    sealed interface Outcome {
        data class Attached(val name: String, val truncated: Boolean) : Outcome
        data class Failed(val message: String) : Outcome
    }

    /**
     * Extracts [uri]'s text, trimmed to what [contextTotal] can afford, and stages it.
     *
     * A failure clears any previously staged file rather than leaving it: the user picked a new
     * one, so the old attachment is no longer what they meant to send.
     */
    suspend fun stage(uri: Uri, contextTotal: Int): Outcome =
        when (val result = extractor.extract(uri, maxChars = charBudget(contextTotal))) {
            is FileTextExtractor.Result.Success -> {
                text = result.text
                Outcome.Attached(result.name, result.truncated)
            }

            is FileTextExtractor.Result.Failure -> {
                text = null
                Outcome.Failed(result.message)
            }
        }

    fun clear() {
        text = null
    }

    private companion object {

        /**
         * Share of the model's context window an attached file may fill. The remaining ~30% holds
         * the system prompt, the user's question and the model's reply. At a 4K context this lands
         * on the ~10K-char cap the extractor used before; a larger context scales it up.
         */
        const val FILE_CONTEXT_FRACTION = 0.7

        /** Budget before a model is loaded and its real context is known. */
        const val DEFAULT_CONTEXT_TOKENS = 4096

        /**
         * How much of a file to feed the model, in characters.
         *
         * Sized off the *loaded* model's context, which is device-dependent -- so a large-context
         * model swallows a whole document while a 4K model still takes the ~10K chars it always
         * did.
         */
        fun charBudget(contextTotal: Int): Int {
            val contextTokens = contextTotal.takeIf { it > 0 } ?: DEFAULT_CONTEXT_TOKENS
            return ContextWindow.estimateChars((contextTokens * FILE_CONTEXT_FRACTION).toInt())
        }
    }
}
