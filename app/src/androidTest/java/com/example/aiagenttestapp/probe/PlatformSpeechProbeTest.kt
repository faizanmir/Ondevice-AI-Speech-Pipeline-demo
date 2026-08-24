package com.example.aiagenttestapp.probe

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.ParcelFileDescriptor
import android.speech.ModelDownloadListener
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Probes whether the platform's on-device [SpeechRecognizer] could serve as a transcription backend.
 *
 * Deliberately a probe rather than a backend. The platform API is a facade over whichever
 * `RecognitionService` the device ships -- on this tablet, `com.google.android.as` and
 * `com.google.android.tts` both register one -- and nothing in the documentation says which language
 * packs are installed, or whether a given vendor's service honours a *file* as the audio source
 * rather than the microphone. Those are the two facts that decide whether a backend is worth
 * writing, and only a device can answer them. Writing the backend first and discovering the answer
 * afterwards is the expensive order.
 *
 * Lives in its own package on purpose: this app already has a `com.example.aiagenttestapp.stt.
 * SpeechRecognizer`, and a probe sitting in that package would silently bind to it instead of the
 * framework class.
 *
 * Run with:
 * ```
 * ./gradlew :app:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.example.aiagenttestapp.probe.PlatformSpeechProbeTest
 * ```
 * Everything it learns goes to logcat under [TAG] as well as to assertions, because the interesting
 * output here is the list of languages, not pass/fail.
 */
class PlatformSpeechProbeTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** [SpeechRecognizer] "methods must be invoked only from the main application thread". */
    private fun onMain(block: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    /** Returns Unit deliberately: `Log.i` returns an Int, which would not satisfy a Unit override. */
    private fun log(line: String) {
        Log.i(TAG, line)
    }

    // ---- 1. Is there an on-device recogniser at all? ---------------------------------------------

    @Test
    fun a_availability() {
        log("=== availability ===")
        log("Build.VERSION.SDK_INT              = ${Build.VERSION.SDK_INT}")
        log("isRecognitionAvailable             = ${SpeechRecognizer.isRecognitionAvailable(context)}")
        val onDevice = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        log("isOnDeviceRecognitionAvailable     = $onDevice")
        log("(availability APIs are API 31; this device is API ${Build.VERSION.SDK_INT})")
    }

    // ---- 2. Which language packs are installed, supported, or pending? ---------------------------

    /**
     * The question the whole idea rests on: "offline" is only true once a pack is on the device.
     * [RecognitionSupport] separates *supported* (could be downloaded) from *installed* (usable with
     * no network) from *pending* (download in flight), which is exactly the distinction a settings
     * screen would have to show the user.
     */
    @Test
    fun b_languagePacks() {
        assumeTrue("checkRecognitionSupport is API 33", Build.VERSION.SDK_INT >= 33)
        log("=== language packs ===")

        val latch = CountDownLatch(1)
        var recognizer: SpeechRecognizer? = null

        onMain {
            recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            recognizer!!.checkRecognitionSupport(
                freeFormIntent(),
                context.mainExecutor,
                object : RecognitionSupportCallback {
                    override fun onSupportResult(support: RecognitionSupport) {
                        log("installed on-device : ${support.installedOnDeviceLanguages}")
                        log("pending on-device   : ${support.pendingOnDeviceLanguages}")
                        log("supported on-device : ${support.supportedOnDeviceLanguages}")
                        log("online              : ${support.onlineLanguages}")
                        latch.countDown()
                    }

                    override fun onError(error: Int) {
                        log("checkRecognitionSupport error = $error (${errorName(error)})")
                        latch.countDown()
                    }
                },
            )
        }

        val answered = latch.await(30, TimeUnit.SECONDS)
        onMain { recognizer?.destroy() }
        log("checkRecognitionSupport answered within 30 s = $answered")
    }

    /** Asks for en-US to be downloaded, so a later offline run has something to work with. */
    @Test
    fun c_triggerDownload() {
        assumeTrue("ModelDownloadListener is API 34", Build.VERSION.SDK_INT >= 34)
        log("=== triggerModelDownload(en-US) ===")

        val latch = CountDownLatch(1)
        var recognizer: SpeechRecognizer? = null

        onMain {
            recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            recognizer!!.triggerModelDownload(
                freeFormIntent(),
                context.mainExecutor,
                object : ModelDownloadListener {
                    override fun onProgress(completedPercent: Int) = log("download $completedPercent%")
                    override fun onSuccess() { log("download: already present or completed"); latch.countDown() }
                    override fun onScheduled() { log("download: scheduled for later"); latch.countDown() }
                    override fun onError(error: Int) {
                        log("download error = $error (${errorName(error)})"); latch.countDown()
                    }
                },
            )
        }

        log("download callback within 60 s = ${latch.await(60, TimeUnit.SECONDS)}")
        onMain { recognizer?.destroy() }
    }

    // ---- 3. The decisive one: can it read a file instead of the microphone? ----------------------

    /**
     * Feeds 25 s of pre-recorded PCM through `EXTRA_AUDIO_SOURCE` and reports what comes back.
     *
     * This is the test that decides whether a backend is possible at all. This app transcribes a WAV
     * that was captured earlier; if the vendor's service ignores the descriptor and opens the
     * microphone, every result here will be silence or nonsense, and no amount of work downstream
     * fixes that.
     *
     * `EXTRA_SEGMENTED_SESSION` is what keeps a 25 s clip from ending at the first pause: in
     * segmented mode the service reports each segment through `onSegmentResults` and only finishes
     * at `onEndOfSegmentedSession`, rather than stopping at the first silence the way a single
     * utterance would.
     *
     * Push the audio first (raw PCM, no header -- the format is declared by the extras):
     * ```
     * adb push probe.pcm /sdcard/Android/data/com.example.aiagenttestapp/files/probe.pcm
     * ```
     */
    @Test
    fun d_recogniseFromFile() {
        assumeTrue("EXTRA_AUDIO_SOURCE is API 33", Build.VERSION.SDK_INT >= 33)
        log("=== file-source recognition: four variants ===")

        // ERROR_CLIENT came back in 12 ms on the first attempt -- far too fast for any audio to have
        // been read, so the request was rejected outright rather than failing during recognition.
        // These four isolate why: whether segmented-session mode is the problem, whether the
        // on-device service specifically refuses a file, and whether a streamed pipe is accepted
        // where a seekable file is not.
        val results = LinkedHashMap<String, String>()
        results["on-device + segmented"] = attempt(onDevice = true, segmented = true, pipe = false)
        results["on-device, no segmented"] = attempt(onDevice = true, segmented = false, pipe = false)
        results["default service + segmented"] = attempt(onDevice = false, segmented = true, pipe = false)
        results["on-device + pipe"] = attempt(onDevice = true, segmented = true, pipe = true)

        log("--- file-source verdict ---")
        results.forEach { (variant, outcome) -> log("  ${variant.padEnd(28)} -> $outcome") }
    }

    /**
     * Does the recogniser drop utterances when the audio arrives faster than it was spoken?
     *
     * The question that decides whether this backend is usable. On a 25 s clip fed as fast as the
     * pipe accepts, the service reported `onBeginningOfSpeech` four times and delivered only two
     * `onSegmentResults` -- and the missing text was a whole sentence from the middle, not a tail.
     * Across a 22-minute recording that came out as 69 evenly-spread gaps and a 57.5% word error
     * rate against 7.8% for Whisper on the same audio.
     *
     * SODA is built for a live microphone, so the suspicion is that it discards what it cannot keep
     * up with. If that is right, pacing the feed fixes the transcript and costs wall-clock: the
     * whole speed advantage of this backend comes from finishing 22 minutes of audio in 85 seconds.
     * These attempts measure both halves of that trade -- how many segments come back, and how long
     * it takes -- at full speed, 4x, 2x and 1x.
     */
    @Test
    fun e_feedPace() {
        assumeTrue("EXTRA_AUDIO_SOURCE is API 33", Build.VERSION.SDK_INT >= 33)
        log("=== feed pace: does faster-than-real-time lose utterances? ===")

        val results = LinkedHashMap<String, String>()
        for (pace in listOf(null, 16.0, 12.0, 8.0, 6.0, 4.0)) {
            val label = pace?.let { "${it.toInt()}x real time" } ?: "as fast as the pipe accepts"
            results[label] = attempt(onDevice = true, segmented = true, pipe = true, pace = pace)
        }

        log("--- feed-pace verdict ---")
        results.forEach { (variant, outcome) -> log("  ${variant.padEnd(30)} -> $outcome") }
    }

    /**
     * One file-source recognition attempt, described by what it varies.
     *
     * Returns a one-line outcome rather than asserting, because a probe that stops at the first
     * failure tells you less than one that reports all four.
     *
     * [pace] throttles the pipe to a multiple of real time; null writes as fast as the pipe accepts.
     * Only meaningful with `pipe = true`, since a plain file descriptor is read by the service at
     * whatever rate it chooses.
     */
    private fun attempt(
        onDevice: Boolean,
        segmented: Boolean,
        pipe: Boolean,
        pace: Double? = null,
    ): String {
        // /data/local/tmp survives the reinstall that a connectedAndroidTest run performs; MIUI
        // wipes /sdcard/Android/data/<pkg>/ on reinstall, which silently emptied this on run two.
        val pcm = listOf(
            File("/data/local/tmp/probe.pcm"),
            File(context.getExternalFilesDir(null), "probe.pcm"),
        ).firstOrNull { it.canRead() && it.length() > 0 }
            ?: return "SKIPPED: no readable probe.pcm"

        log("--- attempt onDevice=$onDevice segmented=$segmented pipe=$pipe (${pcm.path}, ${pcm.length()} B)")

        val latch = CountDownLatch(1)
        val segments = mutableListOf<String>()
        var recognizer: SpeechRecognizer? = null
        var errorCode: Int? = null
        var feeder: Thread? = null

        onMain {
            val fd = if (pipe) {
                // Some recognition services expect a stream they can read to EOF rather than a
                // seekable file. A pipe is the shape a live capture would have had.
                val (read, write) = ParcelFileDescriptor.createPipe()
                feeder = Thread {
                    runCatching {
                        ParcelFileDescriptor.AutoCloseOutputStream(write).use { out ->
                            if (pace == null) {
                                pcm.inputStream().use { it.copyTo(out) }
                            } else {
                                // 200 ms of audio per write, close to what a live capture emits, with
                                // the wait between writes scaled by the pace. Writing the whole clip
                                // and sleeping afterwards would prove nothing: the point is the rate
                                // the service *receives* at.
                                val chunk = ByteArray(CHUNK_MILLIS * SAMPLE_RATE / 1000 * 2)
                                val waitMillis = (CHUNK_MILLIS / pace).toLong()
                                pcm.inputStream().use { input ->
                                    while (true) {
                                        val read2 = input.read(chunk)
                                        if (read2 <= 0) break
                                        out.write(chunk, 0, read2)
                                        out.flush()
                                        Thread.sleep(waitMillis)
                                    }
                                }
                            }
                        }
                    }.onFailure { log("pipe feeder failed: $it") }
                }.also { it.start() }
                read
            } else {
                ParcelFileDescriptor.open(pcm, ParcelFileDescriptor.MODE_READ_ONLY)
            }

            val intent = freeFormIntent().apply {
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, fd)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16_000)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                if (segmented) {
                    // End the session when the audio source runs out, not at the first pause.
                    putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
                }
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }

            recognizer = if (onDevice) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }
            recognizer!!.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: android.os.Bundle?) = log("onReadyForSpeech")
                override fun onBeginningOfSpeech() = log("onBeginningOfSpeech")
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = log("onEndOfSpeech")
                override fun onPartialResults(partialResults: android.os.Bundle?) = Unit
                override fun onEvent(eventType: Int, params: android.os.Bundle?) = Unit

                override fun onError(error: Int) {
                    errorCode = error
                    log("onError = $error (${errorName(error)})")
                    latch.countDown()
                }

                override fun onResults(results: android.os.Bundle?) {
                    record("onResults", results)
                    latch.countDown()
                }

                override fun onSegmentResults(segmentResults: android.os.Bundle) {
                    record("onSegmentResults", segmentResults)
                }

                override fun onEndOfSegmentedSession() {
                    log("onEndOfSegmentedSession")
                    latch.countDown()
                }

                private fun record(from: String, bundle: android.os.Bundle?) {
                    val texts = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val lang = if (Build.VERSION.SDK_INT >= 34) {
                        bundle?.getString(SpeechRecognizer.DETECTED_LANGUAGE)
                    } else {
                        null
                    }
                    log("$from: lang=$lang text=${texts?.firstOrNull()}")
                    texts?.firstOrNull()?.let { segments += it }
                }
            })
            recognizer!!.startListening(intent)
        }

        val started = System.currentTimeMillis()
        val finished = latch.await(90, TimeUnit.SECONDS)
        val took = System.currentTimeMillis() - started
        onMain { recognizer?.destroy() }
        feeder?.join(2_000)

        segments.forEachIndexed { i, s -> log("    [$i] $s") }
        return when {
            errorCode != null -> "ERROR ${errorCode} (${errorName(errorCode!!)}) after ${took} ms"
            !finished -> "TIMED OUT after ${took} ms, ${segments.size} segment(s)"
            segments.isEmpty() -> "no error but zero text after ${took} ms"
            else -> "OK ${segments.size} segment(s), ${segments.sumOf { it.length }} chars in ${took} ms"
        }
    }

    private fun freeFormIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
        // Belt and braces: createOnDeviceSpeechRecognizer already refuses to go to the network.
        .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)

    private fun errorName(code: Int) = when (code) {
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NETWORK_TIMEOUT"
        SpeechRecognizer.ERROR_NETWORK -> "NETWORK"
        SpeechRecognizer.ERROR_AUDIO -> "AUDIO"
        SpeechRecognizer.ERROR_SERVER -> "SERVER"
        SpeechRecognizer.ERROR_CLIENT -> "CLIENT"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "SPEECH_TIMEOUT"
        SpeechRecognizer.ERROR_NO_MATCH -> "NO_MATCH"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "RECOGNIZER_BUSY"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "INSUFFICIENT_PERMISSIONS"
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "TOO_MANY_REQUESTS"
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "SERVER_DISCONNECTED"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "LANGUAGE_NOT_SUPPORTED"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "LANGUAGE_UNAVAILABLE (pack not downloaded)"
        SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> "CANNOT_CHECK_SUPPORT"
        else -> "unknown"
    }

    private companion object {
        const val TAG = "SpeechProbe"

        /** The probe clip's format, and the write size a paced feed uses. */
        const val SAMPLE_RATE = 16_000
        const val CHUNK_MILLIS = 200

    }
}
