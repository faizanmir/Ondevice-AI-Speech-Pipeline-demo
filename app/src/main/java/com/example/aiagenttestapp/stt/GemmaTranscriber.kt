package com.example.aiagenttestapp.stt

import android.util.Log
import com.example.aiagent.engine.core.AudioClip
import com.example.aiagent.engine.core.AudioInputEngine
import com.example.aiagent.engine.core.GenerationEvent
import com.example.aiagent.engine.core.InferenceEngine
import com.example.aiagenttestapp.data.ModelResidency
import com.example.aiagenttestapp.data.notes.WavFile
import com.example.aiagenttestapp.prompts.SttPrompts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Transcribes by asking a multimodal language model what it hears.
 *
 * The trade against [OnnxTranscriber], stated plainly: this needs no speech model on disk at all --
 * it uses a model the device already has for chat -- and Gemma 4 is a stronger recogniser than
 * Whisper Small. What it gives up is everything a purpose-built recogniser gets for free. There is
 * no detected-language field, no speaker attribution, and no guarantee that the reply is a
 * transcript at all rather than a summary, a translation or a polite refusal. The prompt argues
 * against the last of those and [GemmaTranscriptGuard] catches what gets through.
 *
 * The audio is handed over one slice at a time as a complete WAV, and the conversation is reset
 * between slices. Both matter. A model's audio encoder has a hard ceiling on clip length -- exceeded,
 * LiteRT-LM aborts the process rather than throwing -- so slices are bounded by
 * [AudioSegmenter.GEMMA]; and without the reset each slice's audio tokens would stay in the context,
 * so a long recording would fill the window and start losing its own earlier audio.
 *
 * The engine is borrowed, not owned: it comes from [ModelResidency] and is very often the same model
 * a chat screen is using. That sharing is the reason for the [residency] parameter as well as the
 * engine -- transcription has to hold the model against a memory-pressure release and serialise its
 * conversation resets against a chat opening on the same engine, exactly as the audit pipeline does.
 * [release] hands the model back rather than unloading it.
 */
class GemmaTranscriber(
    private val engine: InferenceEngine,
    private val residency: ModelResidency,
    /** For log lines and error messages; the user knows the model by name, not by path. */
    private val modelName: String,
) : Transcriber {

    /**
     * Checked here as well as by the planner and by
     * [EngineRegistry][com.example.aiagent.engine.core.EngineRegistry]. Cheap, and the failure it
     * prevents is a transcript of nothing that looks exactly like a transcript of something.
     */
    private val listener: AudioInputEngine = engine as? AudioInputEngine
        ?: error("$modelName is loaded in an engine that cannot be given audio")

    init {
        // Claims the resident model for the length of this run. Without it, memory pressure while
        // the user is elsewhere in the app would free the model out from under a transcription that
        // has minutes of work left -- and unlike a chat, there is nobody watching to retry it.
        // Released again in [release], which the worker calls from a `finally`.
        residency.attach()
    }

    override val maxSliceSamples: Int get() = AudioSegmenter.GEMMA.max

    override suspend fun transcribe(
        samples: FloatArray,
        ranges: List<IntRange>,
    ): List<SegmentTranscription> = withContext(Dispatchers.Default) {
        val results = mutableListOf<SegmentTranscription>()

        for (range in ranges) {
            currentCoroutineContext().ensureActive()

            // Defensive clamp, matching SpeechRecognizer: a caller computing boundaries in seconds
            // can round a range one sample past the buffer.
            val from = range.first.coerceIn(0, samples.size)
            val to = (range.last + 1).coerceIn(from, samples.size)

            val text = if (to > from) decode(samples, from, to) else ""

            results += SegmentTranscription(
                range = from until to,
                text = text,
                // No language. See the class docs: the model was asked for a transcript, not for a
                // report about one, so there is nowhere for a language code to come from that would
                // not also put it in the user's text.
                language = null,
            )
        }

        results
    }

    /** One slice: encode, ask, clean. */
    private suspend fun decode(samples: FloatArray, from: Int, until: Int): String {
        // A fresh conversation per slice. Also clears anything a previous run -- or a chat sharing
        // this resident model -- left behind, which would otherwise be prefixed to the audio.
        //
        // Under the residency lock, because a chat opening on this same engine rebuilds the
        // conversation too, and two of those interleaving leaves one of them holding a handle to a
        // conversation the other has already closed.
        //
        // A failure here is survivable -- the worst case is that the slice carries some stale
        // context -- but a cancellation is not something to swallow: waiting on the lock is a
        // suspension point, and catching that would let a stopped worker carry on into a decode.
        try {
            residency.runExclusive { engine.resetConversation() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "could not reset the conversation before a slice", e)
        }

        val clip = AudioClip(
            bytes = WavFile.bytes(samples, AudioRecorder.SAMPLE_RATE, from, until),
        )

        val builder = StringBuilder()
        listener.generate(SttPrompts.TRANSCRIBE_INSTRUCTION, clip).collect { event ->
            when (event) {
                is GenerationEvent.Token -> builder.append(event.text)
                is GenerationEvent.Complete -> Unit
            }
        }

        val raw = builder.toString()
        val cleaned = GemmaTranscriptGuard.clean(raw)

        if (cleaned.isEmpty() && raw.isNotBlank()) {
            // Worth a log line rather than silence: a slice dropped wholesale is either genuine
            // silence handled correctly or the guard being too eager, and only the raw text tells
            // them apart.
            Log.i(TAG, "dropped a non-transcript reply from $modelName: ${raw.take(120)}")
        }

        return cleaned
    }

    override fun quietestCutBetween(samples: FloatArray, from: Int, until: Int): Int =
        AudioSegmenter.quietestCutBetween(samples, from, until, AudioSegmenter.GEMMA)

    /**
     * Hands the model back. Nothing is unloaded -- it belongs to residency and other screens may
     * still be using it -- but this run stops holding it against a memory-pressure release.
     */
    override suspend fun release() = residency.detach()

    private companion object {
        const val TAG = "GemmaTranscriber"
    }
}

/**
 * Strips the things a language model adds to a transcript, and drops a reply that is not one.
 *
 * Pure and separately tested, because it is a pile of judgement calls about text and those are
 * miserable to check on a device and trivial to pin down here.
 *
 * The governing bias is **under-filtering**. A stray "Here is the transcript:" left in a note is
 * mildly annoying and the user can delete it; a rule that eats a real sentence because it happened to
 * begin "I'm sorry" has destroyed something the recording cannot give back. So every rule below is
 * anchored -- it fires only at the very start of the reply, or only when it describes the reply in
 * its entirety.
 */
object GemmaTranscriptGuard {

    /**
     * Labels a model puts in front of the thing it was asked for. Matched only at the very start,
     * and only when followed by a colon, so a note that opens with the word "transcript" survives.
     */
    private val LEADING_LABELS = listOf(
        "transcript", "transcription", "transcript of the audio", "audio transcript",
    )

    /**
     * Phrases that mean "there was nothing to transcribe" or "I will not transcribe this".
     *
     * Every entry has to be something a *model* says about a recording rather than something a
     * person says *into* one, and the difference is finer than it first looks. "Silent",
     * "inaudible", "no speech" and a bare "I'm sorry" were all in an earlier version of this list
     * and all had to come out: "the alarm is silent", "that bit was inaudible", "I'm sorry about
     * that" are ordinary things to say on a walkthrough, and a short slice containing one of them
     * would have been deleted outright. What survives here either names the audio as an object
     * ("the recording contains") or is meta about the answer itself ("unable to transcribe").
     *
     * Only ever consulted against a *short whole reply* -- see [isNonTranscript].
     */
    private val NON_TRANSCRIPT_MARKERS = listOf(
        // Reporting on the audio as an object.
        "no speech", "no audible speech", "no discernible speech", "no spoken words",
        "contain any speech", "contains no speech", "the audio is silent",
        "the recording is silent", "the audio contains", "the recording contains",
        "the audio appears", "empty audio",
        // Declining, or talking about the task rather than doing it.
        "cannot transcribe", "can't transcribe", "unable to transcribe", "nothing to transcribe",
        "no transcript", "cannot provide a transcript", "as an ai",
        "sorry, i cannot", "sorry, i can't", "sorry, but i",
        // Annotations standing alone. Inside a longer transcript these are legitimate and this
        // never sees them; as the entire reply they mean the model found nothing to write down.
        "[silence]", "(silence)", "[inaudible]", "(inaudible)", "[no speech]",
    )

    /**
     * Longest a reply can be and still be dismissed wholesale.
     *
     * A refusal or a one-line description is terse. Real speech is not: 20 seconds of it runs to
     * several hundred characters, so the bound alone excludes almost every genuine transcript before
     * a marker is even consulted.
     */
    private const val NON_TRANSCRIPT_MAX_LENGTH = 120

    /** The cleaned transcript, or an empty string when the reply was not a transcript at all. */
    fun clean(raw: String): String {
        var text = raw.trim()
        if (text.isEmpty()) return ""

        text = stripCodeFence(text)
        text = stripLeadingLabel(text)
        text = stripEnclosingQuotes(text)
        text = text.trim()

        return if (isNonTranscript(text)) "" else text
    }

    /**
     * Unwraps a fenced block. Models reach for one whenever they think of the output as data, and it
     * is only ever a wrapper here -- there is no such thing as a transcript that is genuinely
     * Markdown.
     */
    private fun stripCodeFence(text: String): String {
        if (!text.startsWith("```")) return text
        val afterOpen = text.substringAfter('\n', missingDelimiterValue = "")
        if (afterOpen.isEmpty()) return text
        val closing = afterOpen.lastIndexOf("```")
        return if (closing >= 0) afterOpen.substring(0, closing).trim() else afterOpen.trim()
    }

    /** Drops a leading "Transcript:" style label, including one introduced by "Here is the ...". */
    private fun stripLeadingLabel(text: String): String {
        val firstLine = text.lineSequence().firstOrNull()?.trim().orEmpty()
        val colon = firstLine.indexOf(':')
        if (colon <= 0) return text

        val head = firstLine.substring(0, colon).lowercase().trim()
        val isLabel = LEADING_LABELS.any { it == head } ||
            // "Here is the transcript", "Sure, here's the transcription of the audio" -- an opener
            // rather than a bare label, but the same thing. Bounded in length so a real sentence
            // that happens to contain a colon is never mistaken for one.
            (head.length <= 60 && LEADING_LABELS.any { it in head } &&
                (head.startsWith("here") || head.startsWith("sure") || head.startsWith("the")))

        if (!isLabel) return text

        val rest = firstLine.substring(colon + 1).trim()
        val remainingLines = text.lineSequence().drop(1).joinToString("\n")
        return listOf(rest, remainingLines)
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .trim()
    }

    /** Unwraps a reply the model quoted in its entirety. Only when both ends match. */
    private fun stripEnclosingQuotes(text: String): String {
        if (text.length < 2) return text
        val pairs = listOf('"' to '"', '“' to '”', '\'' to '\'')
        val match = pairs.firstOrNull { (open, close) ->
            text.first() == open && text.last() == close
        } ?: return text

        val inner = text.substring(1, text.length - 1)
        // A quote mark in the middle means these two were quoting something *within* the reply
        // rather than wrapping it, and unwrapping would leave unbalanced text.
        return if (match.first in inner || match.second in inner) text else inner.trim()
    }

    /**
     * Whether the whole reply is the model talking about the audio rather than transcribing it.
     *
     * Deliberately requires both a length bound and a marker, and neither is sufficient alone: a
     * long reply is a transcript however it opens, and a short one is only discarded when it names
     * the recording or the task rather than describing the world.
     */
    private fun isNonTranscript(text: String): Boolean {
        if (text.isEmpty()) return true
        if (text.length > NON_TRANSCRIPT_MAX_LENGTH) return false

        val lowered = text.lowercase()
        return NON_TRANSCRIPT_MARKERS.any { it in lowered }
    }
}
