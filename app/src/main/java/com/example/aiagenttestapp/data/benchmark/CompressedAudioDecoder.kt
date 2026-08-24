package com.example.aiagenttestapp.data.benchmark

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Decodes a compressed recording -- m4a, mp3, ogg, whatever the device's codecs cover -- into the
 * pipeline's format.
 *
 * The counterpart to [WavConverter], and separated from it along the line that decides what can be
 * tested. Everything here needs a real device: `MediaCodec` is hardware-backed, its buffer protocol
 * is stateful, and Robolectric's shadow does not decode. Everything *after* the decode -- the
 * downmix, the resample, the file the pipeline eventually reads -- lives in [PcmNormaliser], where
 * a JVM test can prove a stereo 44.1 kHz m4a and a stereo 44.1 kHz WAV end up as the same samples.
 * That split is the whole reason this file is as short as it is.
 *
 * ## The two things that make a codec loop go wrong
 *
 *  - **The output format is not the input format, and is not known up front.** The track's
 *    `MediaFormat` says what the *encoded* stream is; the decoder announces the PCM it will actually
 *    produce through `INFO_OUTPUT_FORMAT_CHANGED`, which arrives before the first buffer and may
 *    disagree about sample rate, channel count, and even sample type. Reading the rate from the
 *    track format is how an import comes out at the wrong speed. The normaliser is therefore built
 *    on the *first output format seen*, not before the loop.
 *  - **A codec that is never fed stops answering.** Input and output are pumped in the same
 *    iteration rather than in two phases, because a decoder with no queued input has nothing to
 *    emit and a decoder whose output is never drained stops accepting input. Either half alone
 *    deadlocks, and the symptom is an import that hangs rather than fails.
 *
 * The decode is *not* bit-exact and cannot be: the source is lossy. That is a real difference from
 * the WAV path, and it is why the corpus behind the published WER numbers stays WAV -- re-importing
 * an m4a of the same recording is a different measurement, not the same one.
 */
object CompressedAudioDecoder {

    /**
     * Decodes [uri] into [target] as 16 kHz mono PCM16.
     *
     * Failures come back as [WavConverter.Result.Unsupported] with something the user can act on;
     * IO problems throw, matching [WavConverter.toPipelineWav] so the caller has one shape to handle.
     */
    @Throws(IOException::class)
    fun decode(
        context: Context,
        uri: Uri,
        target: File,
        /** Completion in 0..1, from the decoded buffer's presentation time against the duration. */
        onProgress: (Float) -> Unit = {},
    ): WavConverter.Result {
        val extractor = MediaExtractor()
        try {
            try {
                extractor.setDataSource(context, uri, null)
            } catch (e: Exception) {
                // Thrown for anything the platform cannot parse as a container at all, which is the
                // honest answer to "is this an audio file?" for a file picked by hand.
                Log.w(TAG, "no extractor for $uri", e)
                return WavConverter.Result.Unsupported(
                    "This file is not an audio format the device can read.",
                )
            }

            val track = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index)
                    .getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: return WavConverter.Result.Unsupported("This file has no audio track.")

            val trackFormat = extractor.getTrackFormat(track)
            val mime = trackFormat.getString(MediaFormat.KEY_MIME)
                ?: return WavConverter.Result.Unsupported("This file's audio track has no type.")

            extractor.selectTrack(track)

            val codec = try {
                MediaCodec.createDecoderByType(mime)
            } catch (e: Exception) {
                Log.w(TAG, "no decoder for $mime", e)
                return WavConverter.Result.Unsupported("This device has no decoder for $mime.")
            }

            return try {
                codec.configure(trackFormat, null, null, 0)
                codec.start()
                drain(extractor, codec, trackFormat, target, onProgress)
            } catch (e: MediaCodec.CodecException) {
                Log.w(TAG, "decoding $mime failed", e)
                target.delete()
                WavConverter.Result.Unsupported("The audio could not be decoded ($mime).")
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }

    private fun drain(
        extractor: MediaExtractor,
        codec: MediaCodec,
        trackFormat: MediaFormat,
        target: File,
        onProgress: (Float) -> Unit,
    ): WavConverter.Result {
        val info = MediaCodec.BufferInfo()

        // Progress comes from the *output* buffer's presentation time rather than from how much of
        // the file has been read: a decoder buffers ahead, so input position runs away from what is
        // actually decoded and the bar would reach the end well before the work did. Zero when the
        // container declares no duration -- a bar that lies is worse than a spinner.
        val durationMicros = if (trackFormat.containsKey(MediaFormat.KEY_DURATION)) {
            trackFormat.getLong(MediaFormat.KEY_DURATION)
        } else {
            0L
        }

        // Seeded from the track so a codec that never announces a format change still has honest
        // numbers; replaced the moment the decoder says otherwise.
        var channels = trackFormat.optInt(MediaFormat.KEY_CHANNEL_COUNT, 1)
        var sampleRate = trackFormat.optInt(MediaFormat.KEY_SAMPLE_RATE, 0)
        var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT

        var normaliser: PcmNormaliser? = null
        var inputDone = false
        var outputDone = false

        try {
            while (!outputDone) {
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(TIMEOUT_MICROS)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index)
                        val size = if (buffer == null) -1 else extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(
                                index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val index = codec.dequeueOutputBuffer(info, TIMEOUT_MICROS)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val out = codec.outputFormat
                        channels = out.optInt(MediaFormat.KEY_CHANNEL_COUNT, channels)
                        sampleRate = out.optInt(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                        pcmEncoding = out.optInt(MediaFormat.KEY_PCM_ENCODING, pcmEncoding)
                    }

                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                    else -> if (index >= 0) {
                        if (info.size > 0) {
                            if (sampleRate <= 0 || channels <= 0) {
                                return WavConverter.Result.Unsupported(
                                    "The decoder did not report a usable audio format.",
                                )
                            }
                            val sink = normaliser ?: PcmNormaliser(
                                target,
                                channels = channels,
                                sourceRate = sampleRate,
                            ).also { normaliser = it }

                            codec.getOutputBuffer(index)?.let { buffer ->
                                write(sink, buffer, info, pcmEncoding)
                            }
                        }
                        if (durationMicros > 0) {
                            onProgress((info.presentationTimeUs.toFloat() / durationMicros).coerceIn(0f, 1f))
                        }
                        codec.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }
            }

            val sink = normaliser
                ?: return WavConverter.Result.Unsupported("The audio track decoded to nothing.")
            sink.finish()
            return WavConverter.Result.Converted(sink.sampleCount, bitExactCopy = false)
        } finally {
            normaliser?.close()
        }
    }

    /** Copies one output buffer into the normaliser, in whichever sample type the codec chose. */
    private fun write(
        sink: PcmNormaliser,
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
        pcmEncoding: Int,
    ) {
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)

        if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
            val floats = FloatArray(info.size / 4)
            buffer.asFloatBuffer().get(floats)
            sink.acceptFloat(floats, floats.size)
        } else {
            val bytes = ByteArray(info.size)
            buffer.get(bytes)
            sink.acceptPcm16(bytes, 0, bytes.size)
        }
    }

    /** `getInteger` throws on a missing key rather than returning a default. */
    private fun MediaFormat.optInt(key: String, fallback: Int): Int =
        if (containsKey(key)) getInteger(key) else fallback

    private const val TAG = "CompressedAudio"

    /** Long enough not to spin the loop, short enough that neither half of it starves the other. */
    private const val TIMEOUT_MICROS = 10_000L
}
