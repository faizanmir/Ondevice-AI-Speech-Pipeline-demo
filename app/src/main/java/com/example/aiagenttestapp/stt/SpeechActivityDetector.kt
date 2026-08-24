package com.example.aiagenttestapp.stt

import android.content.res.AssetManager
import android.util.Log
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/**
 * Finds the stretches of a recording that actually contain speech.
 *
 * Without this the transcriber is handed the whole recording end to end, silence included, and the
 * silence is the expensive part. Not in decode time -- though on the Gemma path a near-silent chunk
 * costs the same seconds as a full one -- but in what comes back. A language model given silence and
 * told to transcribe will not return nothing; it will fill the turn with something plausible. Whisper
 * has the same habit, which is why its transcripts of quiet recordings are littered with phrases
 * nobody said. [GemmaTranscriptGuard] catches some of that after the fact; this removes the input
 * that provokes it.
 *
 * Silero rather than the frame-energy measure [AudioSegmenter] already computes. Energy is free and
 * would be worse than useless here: this app records site walkthroughs, where the loudest thing in
 * the room is frequently a compressor, and an energy threshold would faithfully mark it as speech and
 * skip the quiet person describing it.
 *
 * The model is 644 KB and ships inside the APK, unlike every other model here. The rule those follow
 * -- download on demand, do not inflate the app -- is a rule about hundreds of megabytes for an
 * opt-in feature. This is under a megabyte and improves the default path, so a download gate would be
 * all cost.
 */
class SpeechActivityDetector(private val assets: AssetManager) {

    private var vad: Vad? = null

    /**
     * State for the streaming API, which exists so the recorder can run detection *while capturing*
     * instead of leaving it as a serial post-stop pass. [streamRegions] is read from the pre-decode
     * pipeline's coroutine while the capture loop appends, hence the lock; the native model itself
     * is only ever touched from one thread at a time (the callers serialise capture against stop).
     */
    private val framer = VadFramer(WINDOW)
    private val streamRegions = mutableListOf<IntRange>()
    private val streamLock = Any()

    /** Samples actually consumed by the model so far -- full frames only, the carry excluded. */
    private var streamConsumed = 0

    val isLoaded: Boolean get() = vad != null

    /**
     * Loads the model, sized for [maxSpeechSamples].
     *
     * The cap is handed to Silero rather than applied afterwards: it splits a long unbroken run of
     * speech at its own quietest internal point, which is a better boundary than anything a caller
     * can pick from outside. Loading is per-run because that cap differs by transcription backend.
     *
     * [provider] is passed in rather than read here, because this class is constructed per run and
     * has no business knowing about Settings. It is included in the experiment at all because the
     * VAD runs inside the transcription job, so its cost is part of the number being measured.
     */
    fun load(maxSpeechSamples: Int, provider: String) {
        release()

        // A reloaded instance starts a fresh stream: leftover carry or regions from an aborted one
        // would silently shift every position of the next.
        framer.flush()
        synchronized(streamLock) { streamRegions.clear() }
        streamConsumed = 0

        vad = Vad(
            assetManager = assets,
            config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = ASSET_NAME,
                    // sherpa's default. Lowering it finds more speech and more compressor; raising
                    // it starts discarding people who mumble. The padding in [SpeechRegions] is the
                    // safety margin, not this number.
                    threshold = 0.5f,
                    // Half a second of quiet before a stretch of speech is declared over. Shorter
                    // and every clause becomes its own region; longer and genuine gaps are kept.
                    minSilenceDuration = 0.5f,
                    // Under a quarter-second is a cough or a chair, not a word worth a decode.
                    minSpeechDuration = 0.25f,
                    // Fixed by the model architecture at 16 kHz; not a tuning knob.
                    windowSize = 512,
                    maxSpeechDuration = maxSpeechSamples.toFloat() / AudioRecorder.SAMPLE_RATE,
                ),
                sampleRate = AudioRecorder.SAMPLE_RATE,
                numThreads = 1,
                provider = provider,
            ),
        )
    }

    /**
     * The speech regions in a recording, in its own sample timeline.
     *
     * Positions in the original timeline are the whole point. Everything downstream -- spoken
     * markers, the checkpoint's per-slice resume -- is expressed in sample offsets into the
     * recording, so a detector that returned only the speech audio would be handing back something
     * that could never be lined up against any of it again. They stay absolute here for free:
     * [readBlock] is called strictly in order from zero, so Silero's own running position *is* the
     * recording's.
     *
     * The audio arrives through [readBlock] rather than as one array because a recording is
     * arbitrarily long and this is the first thing that touches it. Reading `[from, until)` on demand
     * keeps the cost flat at one block no matter how long the walkthrough was; see
     * [com.example.aiagenttestapp.data.notes.WavFile.Reader].
     *
     * Returns raw regions exactly as Silero found them. Padding, merging and the marker-span
     * protections are [SpeechRegions]' job, kept separate because they are judgement calls about
     * whose failure a unit test should be able to demonstrate.
     */
    fun detect(totalSamples: Int, readBlock: (from: Int, until: Int) -> FloatArray): List<IntRange> {
        val active = vad ?: error("The VAD model is not loaded")

        val regions = mutableListOf<IntRange>()

        var blockStart = 0
        while (blockStart < totalSamples) {
            val block = readBlock(blockStart, minOf(blockStart + BLOCK, totalSamples))
            // A short read means the file ended earlier than its length implied. Stopping is right:
            // flush below still closes whatever speech was open, so the audio that did arrive is
            // fully accounted for.
            if (block.isEmpty()) break

            // Fed in the window size the model expects. Handing it a whole block at once is not an
            // option: this is a streaming API, and it emits segments as it consumes.
            var offset = 0
            while (offset < block.size) {
                val end = minOf(offset + WINDOW, block.size)
                active.acceptWaveform(block.copyOfRange(offset, end))
                drainInto(regions, active)
                offset = end
            }

            blockStart += block.size
        }

        // Closes a stretch of speech still open when the recording ended. Without it the last thing
        // said in a note is dropped, which would be the most noticeable bug this class could have.
        active.flush()
        drainInto(regions, active)

        active.reset()

        Log.i(TAG, "VAD found ${regions.size} speech regions in $totalSamples samples")
        return regions
    }

    /**
     * The live counterpart to [detect]: capture chunks are pushed in as they arrive, and regions
     * accumulate as Silero settles them.
     *
     * Same model, same 512-sample windows, same ordering guarantee -- frames are fed strictly in
     * capture order from the recording's first sample, so Silero's running position is the
     * recording's, exactly as [detect] documents. The only difference is who owns the loop: [detect]
     * pulls blocks off disk after the fact, this is fed during capture so the post-stop pass has
     * nothing left to do.
     */
    fun acceptStream(samples: FloatArray) {
        val active = vad ?: error("The VAD model is not loaded")

        framer.accept(samples) { frame ->
            active.acceptWaveform(frame)
            streamConsumed += frame.size
            synchronized(streamLock) { drainInto(streamRegions, active) }
        }
    }

    /** Regions Silero has settled so far. Safe to call from another thread mid-stream. */
    fun regionsSoFar(): List<IntRange> = synchronized(streamLock) { streamRegions.toList() }

    /**
     * The position below which Silero's verdict is final.
     *
     * A segment is only reported when it *closes*, so audio behind this position that appears in no
     * region really is silence -- while everything past it is still an open question. With no
     * speech currently open, everything consumed has been ruled on; with speech open, the run being
     * spoken right now started somewhere after the last settled region, and nothing from there on
     * can be called silent yet. Callers treat the unruled stretch as speech, because the cost of
     * being wrong that way is one wasted decode, not a skipped word.
     */
    fun classifiedUpTo(): Int {
        val active = vad ?: error("The VAD model is not loaded")
        return if (active.isSpeechDetected()) {
            synchronized(streamLock) { streamRegions.lastOrNull()?.last?.plus(1) ?: 0 }
        } else {
            streamConsumed
        }
    }

    /**
     * Ends the stream: the final partial frame, the flush that closes any open speech, the complete
     * region list. The flush mirrors [detect]'s tail handling -- dropping the last thing said in a
     * note would be the most noticeable bug this class could have.
     */
    fun endStream(): List<IntRange> {
        val active = vad ?: error("The VAD model is not loaded")

        framer.flush()?.let { active.acceptWaveform(it) }
        active.flush()

        val regions = synchronized(streamLock) {
            drainInto(streamRegions, active)
            val out = streamRegions.toList()
            streamRegions.clear()
            out
        }
        streamConsumed = 0
        active.reset()
        return regions
    }

    private fun drainInto(into: MutableList<IntRange>, active: Vad) {
        while (!active.empty()) {
            val segment = active.front()
            if (segment.samples.isNotEmpty()) {
                into += segment.start until (segment.start + segment.samples.size)
            }
            active.pop()
        }
    }

    fun release() {
        runCatching { vad?.release() }
            .onFailure { Log.w(TAG, "releasing the VAD failed", it) }
        vad = null
    }

    private companion object {
        const val TAG = "SpeechActivityDetector"

        /** Name inside `app/src/main/assets`. */
        const val ASSET_NAME = "silero_vad.onnx"

        /** Silero's frame at 16 kHz. Feeding it anything else is a native-side error. */
        const val WINDOW = 512

        /**
         * How much audio to pull off disk at a time: ~4 s, 256 KB as floats.
         *
         * An exact multiple of [WINDOW] on purpose. Anything else would leave a part-filled frame at
         * the end of every block rather than only at the end of the recording, and Silero is fed each
         * block's remainder as a short window -- so a badly chosen block size would quietly change
         * what the model sees hundreds of times over instead of once.
         */
        const val BLOCK = WINDOW * 128
    }
}
