package com.example.aiagent.engine.core

import kotlinx.coroutines.flow.Flow

/**
 * One clip of audio handed to a model that can listen.
 *
 * The bytes are a whole *encoded audio file*, not raw PCM. That is what the runtimes want:
 * LiteRT-LM decodes what it is given with miniaudio, so it needs the container header to know the
 * sample rate, channel count and bit depth. It also happens to be free here -- the recorder already
 * parks recordings as 16 kHz mono WAV (see `WavFile`), so a clip is a slice of a file the app was
 * writing anyway.
 *
 * [mimeType] is carried rather than assumed because the decoder accepts several formats and a future
 * caller handing over an m4a from the file picker should not have to lie about it.
 */
class AudioClip(
    val bytes: ByteArray,
    val mimeType: String = MIME_WAV,
) {
    val sizeBytes: Int get() = bytes.size

    companion object {
        const val MIME_WAV = "audio/wav"
    }
}

/**
 * An engine that can be given audio alongside its prompt.
 *
 * A separate interface rather than another overload on [InferenceEngine], for exactly the reason
 * [NativeToolEngine] is separate: a default implementation that quietly ignored the audio would let
 * an engine declare [EngineDescriptor.supportsAudioInput] and then transcribe silence, with nothing
 * at compile time or start-up to say why. [EngineRegistry] rejects that pairing when the app builds
 * its registry.
 *
 * Same contract as [InferenceEngine.generate]: cold flow, [GenerationEvent.Token] repeatedly, then
 * exactly one [GenerationEvent.Complete], and callers serialise turns themselves.
 *
 * The audio counts against the model's context window like any other input -- Gemma's encoder spends
 * roughly six tokens a second -- and every runtime has a hard ceiling on how long a single clip may
 * be. Callers are responsible for splitting long recordings before they get here.
 */
interface AudioInputEngine {

    /**
     * Streams a response to [prompt] with [audio] attached, the audio placed before the text so the
     * instruction is read as being *about* the clip.
     */
    fun generate(prompt: String, audio: AudioClip): Flow<GenerationEvent>
}
