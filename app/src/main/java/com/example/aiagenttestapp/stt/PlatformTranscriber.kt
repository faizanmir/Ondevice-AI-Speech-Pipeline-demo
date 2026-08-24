package com.example.aiagenttestapp.stt

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.ModelDownloadListener
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.InterruptedIOException
import java.util.Locale
import java.util.concurrent.CancellationException
import kotlin.coroutines.resume
import android.speech.SpeechRecognizer as PlatformSpeechRecognizer

/**
 * Whether the platform's own on-device recogniser can be offered as a backend.
 *
 * Two separate gates, and they fail for different reasons, so they are reported separately rather
 * than as one boolean:
 *
 *  - **API level.** `EXTRA_AUDIO_SOURCE` -- the only way to hand this API a recording that already
 *    exists rather than opening the microphone -- is API 33. `createOnDeviceSpeechRecognizer` is 31,
 *    so a device can have on-device recognition and still be useless to this app, which transcribes
 *    a WAV captured earlier.
 *  - **A service that offers it.** The platform API is a facade over whichever `RecognitionService`
 *    the device ships. Nothing guarantees one exists, and nothing guarantees the one that does
 *    honours a file as its audio source.
 */
object PlatformSpeech {

    /** `EXTRA_AUDIO_SOURCE` and `EXTRA_SEGMENTED_SESSION` both arrived in API 33. */
    const val MIN_API = 33

    fun availability(context: Context): Availability = when {
        Build.VERSION.SDK_INT < MIN_API ->
            Availability.Unsupported(
                "Android ${Build.VERSION.RELEASE} cannot feed a recording to the system " +
                    "recogniser; that needs Android 13 or newer.",
            )

        !PlatformSpeechRecognizer.isOnDeviceRecognitionAvailable(context) ->
            Availability.Unsupported(
                "This device has no on-device speech recogniser installed.",
            )

        else -> Availability.Ready
    }

    /**
     * What the recogniser can hear offline now, and what it could hear after a download.
     *
     * [installed] is offered as a choice rather than inferred, because inferring it is a measured
     * mistake: a second party's protocol for this same engine requires naming the exact pack, and
     * reports 4.0% against 11.6% on identical audio for a run that let a generic "en" resolve to
     * whichever regional pack happened to be preinstalled. This app was doing exactly that --
     * `Locale.getDefault()`, which on the test device is `en-IN`.
     *
     * [downloadable] is the rest of what the service says it supports on device. It is separate
     * from [installed] because asking for a supported-but-absent pack is not refused: the service
     * accepts the request and then answers with nothing, which reads on this side as a wedged run
     * rather than a missing model.
     */
    data class Packs(
        val installed: List<String> = emptyList(),
        val downloadable: List<String> = emptyList(),
        /** Downloading now. Neither usable yet nor worth offering as a download again. */
        val pending: List<String> = emptyList(),
    )

    /**
     * Queries the recogniser service for [Packs].
     *
     * Everything empty when the service does not answer within
     * [PlatformTranscriber.SUPPORT_QUERY_MILLIS]; callers should fall back to the device locale
     * rather than blocking the run.
     */
    @RequiresApi(MIN_API)
    suspend fun packs(context: Context): Packs {
        val recognizer = withContext(Dispatchers.Main) {
            PlatformSpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        }
        return try {
            withTimeoutOrNull(PlatformTranscriber.SUPPORT_QUERY_MILLIS) {
                suspendCancellableCoroutine { cont ->
                    var done = false
                    recognizer.checkRecognitionSupport(
                        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(
                                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                            )
                            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                        },
                        context.mainExecutor,
                        object : RecognitionSupportCallback {
                            override fun onSupportResult(support: RecognitionSupport) {
                                if (done) return
                                done = true
                                val installed = support.installedOnDeviceLanguages
                                val pending = support.pendingOnDeviceLanguages
                                cont.resume(
                                    Packs(
                                        installed = installed.sorted(),
                                        // Supported *minus* what is already here: the service lists
                                        // installed packs in both, and offering a download for a
                                        // pack that is already usable is how a user waits for
                                        // nothing.
                                        downloadable = support.supportedOnDeviceLanguages
                                            .filterNot { it in installed || it in pending }
                                            .sorted(),
                                        pending = pending.sorted(),
                                    ),
                                )
                            }

                            override fun onError(error: Int) {
                                if (done) return
                                done = true
                                Log.w("PlatformSpeech", "pack query failed: ${describe(error)}")
                                cont.resume(Packs())
                            }
                        },
                    )
                }
            } ?: Packs()
        } finally {
            withContext(Dispatchers.Main) { runCatching { recognizer.destroy() } }
        }
    }

    /** Where a [download] has got to. Terminal states are [Complete], [Scheduled] and [Failed]. */
    sealed interface Download {
        data class Progress(val percent: Int) : Download

        /**
         * The service took the request but will do it later -- typically waiting for Wi-Fi or
         * charge. Terminal as far as this app is concerned: no further callback arrives, and the
         * pack appears in [Packs.installed] whenever the service gets round to it.
         */
        data object Scheduled : Download
        data object Complete : Download
        data class Failed(val reason: String) : Download
    }

    /**
     * Asks the service to fetch the pack for [tag], reporting progress until it is usable.
     *
     * Here because the alternative is not available: the download UI belongs to the speech service
     * and its activity is not exported, so neither the user nor `adb` can reach it except by
     * hunting through Settings by hand -- and a benchmark whose protocol turns on naming the exact
     * pack cannot depend on that. `triggerModelDownload` is the same request that screen makes.
     *
     * The listener overload is API 34; on 33 the fire-and-forget call is all there is, so the flow
     * reports [Download.Scheduled] and stops -- the pack shows up in a later [packs] query or it
     * does not.
     */
    @RequiresApi(MIN_API)
    fun download(context: Context, tag: String): Flow<Download> = callbackFlow {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }

        // Main thread, like every other call on this class -- and kept alive for the whole download,
        // because destroying the recognizer is what cancels it.
        val recognizer = withContext(Dispatchers.Main) {
            PlatformSpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            withContext(Dispatchers.Main) {
                recognizer.triggerModelDownload(
                    intent,
                    context.mainExecutor,
                    object : ModelDownloadListener {
                        override fun onProgress(completedPercent: Int) {
                            trySend(Download.Progress(completedPercent))
                        }

                        override fun onSuccess() {
                            trySend(Download.Complete)
                            close()
                        }

                        override fun onScheduled() {
                            trySend(Download.Scheduled)
                            close()
                        }

                        override fun onError(error: Int) {
                            trySend(Download.Failed(describe(error)))
                            close()
                        }
                    },
                )
            }
        } else {
            withContext(Dispatchers.Main) { recognizer.triggerModelDownload(intent) }
            trySend(Download.Scheduled)
            close()
        }

        awaitClose {
            // Not `withContext`: awaitClose runs during cancellation, where a suspending hop may
            // never resume. The recognizer must still be released, so it is posted.
            context.mainExecutor.execute { runCatching { recognizer.destroy() } }
        }
    }

    sealed interface Availability {
        data object Ready : Availability
        data class Unsupported(val reason: String) : Availability
    }
}

/**
 * Transcribes with Android's own on-device recogniser (`android.speech.SpeechRecognizer`).
 *
 * The third backend, and the only one whose model this app neither ships nor downloads: the speech
 * models are language packs owned by the system, managed from Settings, and shared with every other
 * app on the device. That is the whole appeal -- no 240 MB download, no 2.5 GB model resident -- and
 * also the whole risk, because everything about the recognition is decided by a service this app
 * does not control.
 *
 * ## Why this is shaped so differently from the other two
 *
 * [OnnxTranscriber] and [GemmaTranscriber] are libraries: hand them samples, get text back. This one
 * is an IPC conversation with a system service, and three of its constraints leak into the design:
 *
 *  - **Main thread only.** The framework documents that "this class's methods must be invoked only
 *    from the main application thread", while [Transcriber.transcribe] is called from a
 *    `CoroutineWorker` on a background dispatcher. Every call into the recogniser is therefore
 *    wrapped in `withContext(Dispatchers.Main)`, and the callbacks are bridged back to a coroutine.
 *  - **Callbacks, not returns.** A recognition ends at `onResults`, `onError`, or
 *    `onEndOfSegmentedSession`, whichever comes first -- and on a bad day none of them arrive, which
 *    is why [recognise] bounds the wait with [recogniseTimeoutMillis] instead of awaiting forever.
 *  - **The audio has to be streamed in.** `EXTRA_AUDIO_SOURCE` takes a file descriptor, so each
 *    slice is converted to 16-bit PCM and pushed through a pipe. The format is declared by the
 *    companion extras because the descriptor carries no header; sending a WAV would put 44 bytes of
 *    "RIFF...." through the recogniser as if it were audio.
 *
 * ## Two things that are settled, and cost a day to settle
 *
 * Both were measured on device (Xiaomi SM8735P, Android 16) rather than reasoned about, and both
 * looked like something else first:
 *
 *  - **`EXTRA_SEGMENTED_SESSION` is mandatory, not a tuning choice.** Without it the same request
 *    returns `onResults` with `text=null` -- no error, no text. With it the service reports each
 *    utterance through `onSegmentResults` and finishes at `onEndOfSegmentedSession`.
 *  - **A language pack is per-service.** The same intent that works through
 *    `createOnDeviceSpeechRecognizer` returns `ERROR_LANGUAGE_UNAVAILABLE` through the default
 *    `createSpeechRecognizer`, because the default service on this device has no en-US pack. An
 *    early attempt also returned a bare `ERROR_CLIENT` 12 ms after `startListening` while the pack
 *    was still downloading -- so a refusal here can mean "not ready yet" rather than "not supported",
 *    and is worth retrying once the pack is in place before concluding the backend cannot work.
 *
 * A plain file descriptor and a pipe both work as the audio source; the choice of a pipe here is
 * what makes [feedPaced] possible, and pacing is the thing that actually matters.
 *
 * ## Slices are decoded one at a time, and that is not a choice this code gets to make
 *
 * Overlapping them looked worth trying: at [FEED_PACE] a 20-second slice takes about a second,
 * nearly all of it spent waiting on another process, so the app is idle for most of every slice.
 * Measured on the same device, three concurrent sessions fail within seconds:
 *
 *     ERROR_RECOGNIZER_BUSY -- "The system recogniser is busy with another app."
 *
 * The `RecognitionService` behind `createOnDeviceSpeechRecognizer` serves **one session at a time**,
 * and a pool of recognisers does not help because they all bind to that same service. Concurrency
 * here needs a different platform, not a different number, which is why there is no knob for it.
 * What *does* help is pre-decoding during capture -- the same sessions, spread across the recording
 * instead of run against each other.
 *
 * ## The failure this is built to report clearly
 *
 * The error handling distinguishes two cases rather than treating every failure alike, because
 * they need opposite responses:
 *
 *  - `NO_MATCH` / `SPEECH_TIMEOUT` mean *this slice* had nothing to hear. Normal on a quiet stretch;
 *    the slice yields empty text and the run continues.
 *  - Anything else means the request itself was refused, which will refuse identically for every
 *    remaining slice. Failing the run immediately reports the real reason once, instead of grinding
 *    through fifty slices to produce an empty transcript and no explanation.
 */
@RequiresApi(PlatformSpeech.MIN_API)
class PlatformTranscriber(
    private val context: Context,
    private val language: String? = null,
    /**
     * Restores the capitals and full stops this recogniser does not emit.
     *
     * Needed here for the same reason [StreamingTranscriber] needs it, and discovered the same way:
     * measured on device, this backend returns one unbroken lowercase stream -- *"good morning this
     * is the opening narration for the resertification audit of the ash com food still ready
     * meals facility"*. Whisper and SenseVoice punctuate natively; this one does not, and the
     * transcript feeds a summariser and a spoken-marker scan that both do measurably worse on an
     * uncased run-on stream. Optional, as it is there: without the bundle the note is merely
     * unpunctuated rather than absent.
     */
    private val punctuator: Punctuator? = null,
    /**
     * How fast the audio is handed over, as a multiple of real time. Defaults to [FEED_PACE], the
     * fastest rate verified complete on device; callers pass the Settings choice so pacing rates
     * can be compared without a rebuild per rate. [Double.POSITIVE_INFINITY] means chunked writes
     * with no delay -- the untested variant the confound note under [feedPaced] asks about. A
     * constructor parameter rather than a per-call read on purpose: one run is never fed at two
     * different rates.
     */
    private val feedPace: Double = FEED_PACE,
    /**
     * How much audio goes into each write, in milliseconds. [Int.MAX_VALUE] means one write per
     * slice.
     *
     * A separate knob from [feedPace] because the measurement that established pacing changed both
     * at once -- the failing case was one big write *and* unpaced, every passing case was chunked
     * *and* paced. See [com.example.aiagenttestapp.data.PlatformFeedChunk] for the four corners that
     * leaves, and [feedPaced] for what each does to the wire.
     */
    private val chunkMillis: Int = CHUNK_MILLIS,
) : Transcriber {

    /**
     * Deliberately shorter than the ONNX cap, and deliberately unverified.
     *
     * There is no documented limit on a segmented session's length, and no measurement behind a
     * larger number either. Short slices cost extra round trips; an over-long one risks a service
     * that silently truncates, which would lose speech without reporting anything. Until a real
     * device says otherwise the cheaper mistake is the one that wastes time rather than words.
     */
    override val maxSliceSamples: Int get() = AudioSegmenter.PLATFORM.max

    override fun quietestCutBetween(samples: FloatArray, from: Int, until: Int): Int =
        AudioSegmenter.quietestCutBetween(samples, from, until, AudioSegmenter.PLATFORM)

    /** One per run. Binding a system service per slice would pay the bind cost fifty times over. */
    private var recognizer: PlatformSpeechRecognizer? = null

    /** Feeds the pipes. Cancelled wholesale in [release] so a blocked write cannot outlive the run. */
    private val feeders = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun transcribe(
        samples: FloatArray,
        ranges: List<IntRange>,
    ): List<SegmentTranscription> = ranges.map { range ->
        val result = recognise(pcm16(samples, range))
        SegmentTranscription(
            range = range,
            // Punctuated per slice rather than over the joined transcript, matching
            // [StreamingTranscriber]: the slices are handed on separately, each with its own tags
            // and speaker, so there is no joined string to punctuate at this point.
            text = punctuator?.punctuate(result.text) ?: result.text,
            language = shortLanguageCode(result.language),
        )
    }

    private data class Recognised(
        val text: String,
        val language: String?,
        /**
         * How many segments the service reported. Logged per slice because the feed-pacing failure
         * mode is *silent*: an over-fast feed does not error, it returns fewer segments -- so this
         * count against a known recording is the instrument that verifies a pacing change, and the
         * first thing to compare when a transcript comes back short in the field.
         */
        val segments: Int,
    )

    /**
     * Runs one slice through the recogniser and waits for whichever callback ends it.
     *
     * Segmented-session mode is what makes a slice longer than a single utterance work at all: left
     * to itself the recogniser ends the session at the first pause it hears, so a 20-second slice
     * would return only its opening sentence. In segmented mode the service reports each segment
     * through `onSegmentResults` and finishes at `onEndOfSegmentedSession`, and the segments are
     * joined back into the one string this slice is supposed to produce.
     */
    private suspend fun recognise(pcm: ByteArray): Recognised =
        PLATFORM_SESSION_MUTEX.withLock {
            withContext(Dispatchers.Main) {
                ensureLanguageInstalled()
                val timeoutMillis = recogniseTimeoutMillis(pcm.size, feedPace)
                try {
                    recogniseOnce(pcm, timeoutMillis)
                } catch (e: Exception) {
                    // If the first attempt fails (timeout or transient error), the recognizer has
                    // been nulled out and destroyed. Retrying once with a fresh instance often
                    // clears a wedged or busy system service.
                    if (shouldRetry(e)) {
                        Log.w(
                            TAG,
                            "recogniser failed (${e.message}); retrying once with a fresh instance",
                        )
                        // destroy() has no completion callback. Keep the process-wide lock during
                        // its grace period so another benchmark or recording pipeline cannot race
                        // the service teardown and claim its single session.
                        delay(2_000)
                        try {
                            recogniseOnce(pcm, timeoutMillis)
                        } catch (retry: Exception) {
                            if (isTimeout(retry)) {
                                // Both attempts timed out. Unlike a language-pack refusal or a
                                // permissions error — which repeat identically for every remaining
                                // slice — a timeout is transient: the recogniser was destroyed and
                                // nulled on both exits, so the next slice gets a fresh instance and
                                // a fresh bind to the service. Throwing here would abort the entire
                                // run for a single wedged slice; returning empty loses one slice of
                                // text but lets the run finish, and the gap shows up as a WER hit
                                // rather than a missing benchmark row.
                                Log.e(TAG, "retry also timed out; yielding empty text for this slice")
                                Recognised(text = "", language = null, segments = 0)
                            } else {
                                throw retry
                            }
                        }
                    } else {
                        throw e
                    }
                }
            }
        }

    private fun isTimeout(e: Exception): Boolean =
        e is IllegalStateException && e.message?.contains("did not answer") == true

    private fun shouldRetry(e: Exception): Boolean {
        if (isTimeout(e)) return true

        // Other retryable errors are wrapped in RecognitionException.
        if (e is RecognitionException) {
            return when (e.error) {
                PlatformSpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                PlatformSpeechRecognizer.ERROR_SERVER,
                PlatformSpeechRecognizer.ERROR_SERVER_DISCONNECTED -> true
                else -> false
            }
        }
        return false
    }

    private suspend fun recogniseOnce(pcm: ByteArray, timeoutMillis: Long): Recognised {
        val active = activeRecognizer()

        val pipe = ParcelFileDescriptor.createPipe()
        val readSide = pipe[0]
        val writeSide = pipe[1]

        // The write blocks until the service drains it, so it cannot run on the main thread -- and if
        // the service never reads, this job is what release() cancels to unblock the process.
        var feedMillis = -1L
        val feeder: Job = feeders.launch {
            val fedFrom = System.currentTimeMillis()
            runCatching {
                ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { out ->
                    feedPaced(pcm, out)
                }
            }.onFailure { 
                if (it !is InterruptedIOException && it !is CancellationException) {
                    Log.w(TAG, "feeding audio to the recogniser failed", it)
                }
            }
            feedMillis = System.currentTimeMillis() - fedFrom
        }

        val startedAt = System.currentTimeMillis()
        try {
            return withTimeoutOrNull(timeoutMillis) {
                suspendCancellableCoroutine { cont ->
                    active.setRecognitionListener(Bridge(cont))
                    active.startListening(intentFor(readSide))
                    cont.invokeOnCancellation {
                        // Main-thread-only applies to cancel() as well, and cancellation can arrive
                        // from anywhere -- so it is posted rather than called here.
                        feeders.launch(Dispatchers.Main) { runCatching { active.cancel() } }
                    }
                }
            }?.also { result ->
                // The measurement behind every pacing and cap decision, one greppable line per
                // slice. The segment count is the load-bearing number -- see [Recognised.segments].
                val clipSeconds = pcm.size / (2.0 * AudioRecorder.SAMPLE_RATE)
                Log.i(
                    TAG,
                    "slice %.1fs: total=%dms feed=%dms segments=%d chars=%d".format(
                        clipSeconds,
                        System.currentTimeMillis() - startedAt,
                        feedMillis,
                        result.segments,
                        result.text.length,
                    ),
                )
            } ?: run {
                // The bad day the class docs warn about: no terminal callback ever arrived. Left
                // unguarded, this suspended forever -- in the worker that is a foreground job that
                // never finishes, and in the record screen's pre-decode it silently switched the
                // pipeline off for the rest of the recording. The session is treated as wedged: it
                // is destroyed rather than reused, so the next slice re-binds fresh, and the
                // failure is an ordinary exception rather than the TimeoutCancellationException
                // withTimeout would throw -- both callers rethrow CancellationException as "the
                // user stopped this", which this decidedly is not.
                runCatching { active.destroy() }
                recognizer = null
                error("The system recogniser did not answer for this slice within ${timeoutMillis / 1000}s.")
            }
        } finally {
            feeder.cancel()
            // Ensure both ends are closed. AutoCloseOutputStream handled writeSide if it started,
            // but if the feeder was cancelled before it opened the stream, writeSide might stay
            // open. Closing the side already closed is a no-op.
            runCatching { readSide.close() }
            runCatching { writeSide.close() }
        }
    }

    /**
     * Writes the clip to the recogniser in timed pieces instead of all at once.
     *
     * **This is what makes the backend usable at all, and it is not an optimisation.** Handed the
     * audio as fast as the pipe would accept it, the service silently discarded whole utterances: on
     * a 25 s clip it reported `onBeginningOfSpeech` four times and returned only two segments, and
     * the text it dropped was a complete sentence from the *middle* -- "the lead auditor is ... who
     * holds the technical manager role" -- not a truncated tail. Over a 22-minute recording that
     * came out as 69 evenly-spread gaps, half the words missing, and a 57.5% word error rate against
     * 7.8% for Whisper on the same audio. Nothing anywhere reported a problem.
     *
     * Measured on device (Xiaomi SM8735P, Android 16), same clip, segments returned:
     *
     *     unpaced   2 of 3 segments   0.8 s   <- loses a sentence
     *     16x       3 of 3 segments   1.7 s
     *     8x        3 of 3 segments   3.3 s
     *     4x        3 of 3 segments   6.5 s
     *     1x        3 of 3 segments  25.2 s
     *
     * So the cliff is not at some particular multiple -- every paced rate tested was complete, and
     * only the unpaced write failed.
     *
     * [FEED_PACE] is the fastest of those verified-complete rates, adopted after a full 22-minute
     * recording at 8x confirmed the fix at length (25.7% word error rate against 57.5% unpaced, and
     * deletions down from 658 to 115). Two caveats worth carrying:
     *
     *  - The clip that established these rates held three utterances; a real note is ~65 sessions.
     *    16x is therefore at the edge of what has been measured rather than comfortably inside it.
     *  - The failure is **silent**. An over-fast feed does not error -- it returns a shorter
     *    transcript, and nothing in the log says so. If a run ever comes back short, drop this back
     *    to 8x before looking anywhere else.
     *
     * The failing case was a single `write()` of the whole clip while every passing case chunked
     * *and* delayed, so those two measurements cannot say which of the two did the work. Both are
     * settings now ([com.example.aiagenttestapp.data.PlatformFeedPace],
     * [com.example.aiagenttestapp.data.PlatformFeedChunk]) precisely so the missing corners can be
     * run on device instead of argued about.
     */
    private suspend fun feedPaced(pcm: ByteArray, out: java.io.OutputStream) {
        val bytesPerChunk = feedChunkBytes(chunkMillis, pcm.size)
        // An infinite pace divides to zero delay: chunked writes, no pacing. A whole-slice chunk
        // never reaches the delay at all -- the loop ends after one write.
        val waitMillis = (chunkMillis / feedPace).toLong()

        var offset = 0
        while (offset < pcm.size) {
            val end = minOf(offset + bytesPerChunk, pcm.size)
            out.write(pcm, offset, end - offset)
            out.flush()
            offset = end
            // Suspends rather than sleeps: this runs on the shared feeder scope, and release()
            // cancels it to unblock a write the service has stopped draining.
            if (offset < pcm.size) delay(waitMillis)
        }
    }

    private fun activeRecognizer(): PlatformSpeechRecognizer =
        recognizer ?: PlatformSpeechRecognizer
            .createOnDeviceSpeechRecognizer(context)
            .also { recognizer = it }

    /**
     * What to put in `EXTRA_LANGUAGE`, resolved once per run against the packs actually installed.
     * Null until [ensureLanguageInstalled] has run.
     */
    private var languageTag: String? = null
    private var languageChecked = false

    /**
     * Establishes, before the first slice, that the service has a pack for the language it is about
     * to be asked for -- and asks for it *explicitly*.
     *
     * Written after two minutes of failure that produced no diagnosis at all: every slice hit the
     * [recogniseTimeoutMillis] ceiling, the retry hit it again, and the run failed saying only "did
     * not answer" while `com.google.android.as` sat at 99% CPU. Nothing in the app could say why.
     *
     * The cause turned out to be a *wedged service*, not a language at all -- an abandoned session
     * from an earlier run, still grinding, which our `cancel()` was answered for with
     *
     *     #cancel received for a listener which has not started a session - ignoring this call.
     *
     * so every later session starved behind it. Only force-stopping the service cleared it. A
     * measurement afterwards showed the device had `installed=[en-IN, en-US]` all along, so the
     * locale this originally suspected was never the problem.
     *
     * It stays because the check is cheap and the class of failure it rules out is the expensive
     * one. Nothing about `EXTRA_LANGUAGE` is verified by the framework: leave it unset and the
     * service silently uses `Locale.getDefault()`, and a locale whose pack is absent is not refused
     * with `ERROR_LANGUAGE_UNAVAILABLE` on this vendor's service -- it is accepted, and then answered
     * with nothing. One line of log now says which packs exist before any audio is sent, which is
     * the difference between a one-second answer and a two-minute silence.
     *
     * [RecognitionSupport] separates installed (usable offline now) from pending (downloading) from
     * supported (downloadable), which is exactly the distinction the failure needed and did not have.
     * A near miss is corrected rather than refused -- `en-IN` requested with only `en-US` installed
     * is a device-locale accident, not a request to transcribe a language we cannot hear -- and the
     * substitution is logged, because it changes which model produced the transcript.
     */
    private suspend fun ensureLanguageInstalled() {
        if (languageChecked) return
        languageChecked = true

        val requested = language ?: Locale.getDefault().toLanguageTag()

        val support = supportedLanguages()
        if (support == null) {
            // The query is itself a call into the same service, so it can hang exactly as the
            // recognition does. Proceeding is still better than failing: this is a check, and a
            // check that cannot run should not be the thing that stops the run.
            Log.w(TAG, "checkRecognitionSupport did not answer; asking for $requested unverified")
            languageTag = requested
            return
        }

        val installed = support.installedOnDeviceLanguages
        Log.i(
            TAG,
            "language packs: installed=$installed pending=${support.pendingOnDeviceLanguages} " +
                "downloadable=${support.supportedOnDeviceLanguages.size}",
        )

        val match = installed.firstOrNull { it.equals(requested, ignoreCase = true) }
        if (match != null) {
            languageTag = match
            return
        }

        // Same language, different region. Correct it silently in the intent but say so in the log:
        // en-IN and en-US are not the same model and the transcript will differ.
        val subtag = requested.substringBefore('-')
        val nearby = installed.firstOrNull { it.substringBefore('-').equals(subtag, ignoreCase = true) }
        if (nearby != null) {
            Log.w(TAG, "$requested has no pack installed; using $nearby instead")
            languageTag = nearby
            return
        }

        // Nothing usable. Failing here is the whole point of the check: the alternative is the
        // silent 99%-CPU hang described above, which also poisons the service for everything else.
        if (support.pendingOnDeviceLanguages.any { it.substringBefore('-').equals(subtag, true) }) {
            error(
                "The system recogniser is still downloading its $subtag language pack. " +
                    "Wait for it to finish and try again.",
            )
        }
        error(
            if (installed.isEmpty()) {
                "The system recogniser has no language pack installed. Download one in Android " +
                    "Settings before using this backend."
            } else {
                "The system recogniser has no $requested language pack installed; it has " +
                    "${installed.joinToString()}. Download $requested in Android Settings, or " +
                    "record in a language it has."
            },
        )
    }

    /**
     * Asks the service which languages it can hear, or null if it does not answer.
     *
     * Bounded for the same reason [recognise] is: this is the same service, reached the same way,
     * and it is just as free to say nothing. The intent passed deliberately carries no audio source
     * -- the query is about the service's models, not about this clip.
     */
    private suspend fun supportedLanguages(): RecognitionSupport? =
        withTimeoutOrNull(SUPPORT_QUERY_MILLIS) {
            suspendCancellableCoroutine { cont ->
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                    )
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                }
                var done = false
                activeRecognizer().checkRecognitionSupport(
                    intent,
                    context.mainExecutor,
                    object : RecognitionSupportCallback {
                        override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                            if (done) return
                            done = true
                            cont.resume(recognitionSupport)
                        }

                        override fun onError(error: Int) {
                            if (done) return
                            done = true
                            Log.w(TAG, "checkRecognitionSupport failed: ${describe(error)}")
                            cont.resume(null)
                        }
                    },
                )
            }
        }

    private fun intentFor(audio: ParcelFileDescriptor) =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            // Explicit, always. Left unset the service falls back to Locale.getDefault(), and a
            // locale whose pack is absent is not refused -- see [ensureLanguageInstalled].
            (languageTag ?: language)?.let { putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) }
            // Belt and braces: createOnDeviceSpeechRecognizer already refuses to reach the network,
            // but a note recorded on site must not depend on that being true of every vendor.
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)

            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, audio)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, AudioRecorder.SAMPLE_RATE)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            // End the session when the audio runs out rather than at the first pause.
            putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)

            // Asked for explicitly, because the detection is not free and is not on by default:
            // a first run without this returned DETECTED_LANGUAGE empty and the note was stored
            // with no language at all, which is what decides the summary's language downstream.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
            }
        }

    /**
     * Turns the framework's callbacks into one resumption of [cont].
     *
     * Guarded against resuming twice because more than one terminal callback can arrive -- a service
     * that reports `onResults` and then `onEndOfSegmentedSession` is within its rights, and resuming
     * a continuation twice is a crash rather than a warning.
     */
    private inner class Bridge(
        private val cont: CancellableContinuation<Recognised>,
    ) : RecognitionListener {

        private val segments = mutableListOf<String>()
        private var detected: String? = null
        private var done = false

        override fun onSegmentResults(segmentResults: Bundle) = collect(segmentResults)

        /**
         * Where the detected language actually arrives.
         *
         * Not in the results bundle, which is where reading it first put this backend: with
         * `EXTRA_ENABLE_LANGUAGE_DETECTION` set and `DETECTED_LANGUAGE` read from `onResults`, every
         * note was still stored with no language at all. The framework reports detection through
         * this separate callback (API 34) instead, and it fires before the results do.
         *
         * The results bundle is still checked in [collect] as a fallback: a service is free to put
         * it there too, and the first non-null wins either way.
         */
        override fun onLanguageDetection(results: Bundle) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
            if (detected == null) {
                detected = results.getString(PlatformSpeechRecognizer.DETECTED_LANGUAGE)
            }
        }

        override fun onResults(results: Bundle?) {
            results?.let(::collect)
            finish()
        }

        override fun onEndOfSegmentedSession() = finish()

        override fun onError(error: Int) {
            if (done) return
            done = true

            if (heardNothing(error)) {
                cont.resume(Recognised(segments.joinToString(" ").trim(), detected, segments.size))
                return
            }

            // Destroy the recognizer before cancelling the continuation so the retry starts fresh.
            runCatching { recognizer?.destroy() }
            recognizer = null

            cont.cancel(RecognitionException(error, describe(error)))
        }

        private fun collect(bundle: Bundle) {
            bundle.getStringArrayList(PlatformSpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { segments += it }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && detected == null) {
                detected = bundle.getString(PlatformSpeechRecognizer.DETECTED_LANGUAGE)
            }
        }

        private fun finish() {
            if (done) return
            done = true
            cont.resume(Recognised(segments.joinToString(" ").trim(), detected, segments.size))
        }

        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onPartialResults(partialResults: Bundle?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    override suspend fun release() {
        feeders.cancel()
        withContext(Dispatchers.Main) {
            runCatching { recognizer?.destroy() }
            recognizer = null
        }
    }

    /** Carries a framework error code so [recognise] can decide whether to retry. */
    private class RecognitionException(val error: Int, message: String) : IllegalStateException(message)

    internal companion object {
        const val TAG = "PlatformTranscriber"

        /**
         * The on-device RecognitionService accepts one session, but this process can create a
         * transcriber from a benchmark worker and another from live-recording pre-decode. Serialize
         * all instances through terminal callback, destroy and retry; an instance-local guard would
         * still let those two owners repeatedly steal the service from each other.
         *
         * This cannot exclude another app or repair a session already stuck inside the service.
         */
        val PLATFORM_SESSION_MUTEX = Mutex()

        /**
         * How much faster than real time the audio may be handed over by default. See [feedPaced].
         * Settings can override per run (`AppSettings.platformFeedPace`) so rates can be compared
         * on device without a rebuild; this constant is the verified choice that ships.
         */
        const val FEED_PACE = 16.0

        /** Long enough for a cold service to answer a metadata query, short enough not to be the run. */
        const val SUPPORT_QUERY_MILLIS = 15_000L

        /** ~200 ms per write, close to what a live capture emits. Settings can override it. */
        const val CHUNK_MILLIS = 200
    }
}

/**
 * Whether an error means "this slice had nothing to hear" rather than "the request was refused".
 *
 * The distinction decides whether one bad slice costs a sentence or the whole note, so it is a
 * judgement call rather than plumbing, and it lives out here where a JVM test can pin it.
 *
 * A recording sliced on silence boundaries *will* contain quiet slices -- that is what the VAD and
 * the segmenter leave behind -- and failing the run on the first one would make the backend unusable
 * on exactly the recordings it is meant for. Everything else is a property of the request, not of
 * the audio: a refused intent, a missing language pack, a busy service. Those repeat identically for
 * every remaining slice, so continuing would grind through the whole recording to produce an empty
 * transcript and no reason for it.
 */
internal fun heardNothing(error: Int): Boolean =
    error == PlatformSpeechRecognizer.ERROR_NO_MATCH ||
        error == PlatformSpeechRecognizer.ERROR_SPEECH_TIMEOUT

/**
 * Reduces a BCP-47 tag to the bare language code the rest of the app stores.
 *
 * This backend reports `en-US`; the ONNX recognisers report `en`. The difference is not cosmetic,
 * because the stored value is handed to `Locale(code).getDisplayLanguage()` when the summariser is
 * told which language to answer in -- and `Locale("en-US")` is a language *called* "en-US", not
 * English, so the directive silently degrades to the "same language as the transcript" fallback.
 * Normalising here keeps that difference from leaking out of this file.
 *
 * Region and script subtags are dropped rather than preserved: nothing downstream distinguishes
 * en-US from en-GB, and a value only one backend could produce would be a trap for the next reader.
 */
internal fun shortLanguageCode(tag: String?): String? = tag
    ?.substringBefore('-')
    ?.substringBefore('_')
    ?.trim()
    ?.lowercase()
    ?.takeIf { it.isNotEmpty() }

/**
 * How long [PlatformTranscriber] waits for a slice before declaring the service wedged.
 *
 * Generous by design: three times the paced feed time plus a flat half-minute. The feed is the only
 * part of a recognition whose duration this app controls, and the measured decode rides along with
 * it -- 1.7 s total for a 25 s clip at 16x -- so a session that has taken three feeds' worth of time
 * and then another thirty seconds is not slow, it is gone. The asymmetry of being wrong sets the
 * margin: a too-long timeout wastes minutes exactly once per wedged session, while a too-short one
 * aborts healthy recognitions on a busy device -- silently, and on every slice.
 *
 * [feedPace] is a parameter rather than the companion constant so this stays a pure function a JVM
 * test can pin without reaching into the class.
 */
/**
 * Bytes per write for a given chunk size, clamped to what there is to write.
 *
 * Pure and separate because the two ends of the range are both easy to get wrong in ways that do
 * not throw: a whole-slice chunk in milliseconds would overflow `Int` if it were multiplied out
 * (`Int.MAX_VALUE * 16000` is not a number), and a chunk of zero bytes would spin the feed loop
 * forever without ever writing anything. Neither would look like a bug from the outside -- one is a
 * negative write size, the other is a hang -- so both are pinned by a test.
 */
internal fun feedChunkBytes(chunkMillis: Int, clipBytes: Int): Int {
    if (chunkMillis >= WHOLE_CLIP_MILLIS) return clipBytes
    val bytes = chunkMillis.toLong() * AudioRecorder.SAMPLE_RATE / 1000 * BYTES_PER_SAMPLE
    return bytes.coerceIn(BYTES_PER_SAMPLE.toLong(), clipBytes.toLong().coerceAtLeast(1)).toInt()
}

/** Any chunk this long or longer is the whole slice; see `PlatformFeedChunk.WHOLE_CLIP`. */
internal const val WHOLE_CLIP_MILLIS = Int.MAX_VALUE

/** `ENCODING_PCM_16BIT`, the encoding declared on the intent. */
internal const val BYTES_PER_SAMPLE = 2

internal fun recogniseTimeoutMillis(pcmBytes: Int, feedPace: Double): Long {
    val clipMillis = pcmBytes.toLong() * 1000 / (2L * AudioRecorder.SAMPLE_RATE)
    val feedMillis = (clipMillis / feedPace).toLong()
    // 60s margin rather than 30s: a cold-start on a busy device can take a while, and the
    // asymmetry of being wrong (wasting time once vs failing every run) favors a long wait.
    return feedMillis * 3 + 60_000L
}

/**
 * Converts one range to little-endian 16-bit PCM, the encoding declared on the intent.
 *
 * Copied rather than streamed straight off the FloatArray because the pipe is written from another
 * thread, and the samples array belongs to the caller for the length of the run.
 *
 * Endianness is the part worth a test. `AudioFormat.ENCODING_PCM_16BIT` is little-endian, and
 * getting it backwards does not fail -- it produces audio that decodes to nothing recognisable,
 * which looks exactly like a recogniser that refused the request.
 */
internal fun pcm16(samples: FloatArray, range: IntRange): ByteArray {
    val out = ByteArray(range.count() * 2)
    var at = 0
    for (index in range) {
        val value = (samples[index].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt()
        out[at++] = (value and 0xFF).toByte()
        out[at++] = ((value shr 8) and 0xFF).toByte()
    }
    return out
}

/** The error text the note carries, so a refusal names itself instead of saying "failed". */
internal fun describe(error: Int): String = when (error) {
    PlatformSpeechRecognizer.ERROR_CLIENT ->
        "The system recogniser refused the request (ERROR_CLIENT). This device's speech " +
            "service may not accept a recording as its audio source."
    PlatformSpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
        "That language pack is not downloaded. Add it under Settings > System > " +
            "Languages & input > On-device speech recognition."
    PlatformSpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ->
        "The system recogniser does not support that language."
    PlatformSpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
        "The system recogniser was denied permission to run."
    PlatformSpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
        "The system recogniser is busy with another app."
    PlatformSpeechRecognizer.ERROR_SERVER, PlatformSpeechRecognizer.ERROR_SERVER_DISCONNECTED ->
        "The system speech service stopped responding."
    PlatformSpeechRecognizer.ERROR_AUDIO ->
        "The system recogniser could not read the audio."
    PlatformSpeechRecognizer.ERROR_NETWORK, PlatformSpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
        "The system recogniser tried to use the network, which this backend does not allow."
    else -> "The system recogniser failed (error $error)."
}
