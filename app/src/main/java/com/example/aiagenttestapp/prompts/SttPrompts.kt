package com.example.aiagenttestapp.prompts

/**
 * What a language model is asked when it is being used as a speech recogniser.
 *
 * This is the whole difference between the two transcription backends. sherpa-onnx cannot do
 * anything but transcribe -- there is no instruction, because a recogniser has no other behaviour to
 * suppress. A language model has plenty, and every one of them is wrong here: it will happily
 * summarise the recording, translate it into English, describe the acoustics ("a man speaking in a
 * noisy room"), answer a question it heard someone ask, or apologise that it cannot help. Each of
 * those produces a confident, well-formed, completely false transcript.
 *
 * So the prompt's job is subtractive: name the one behaviour wanted and rule out the near neighbours
 * by hand. [com.example.aiagenttestapp.stt.GemmaTranscriptGuard] is the backstop for when it does
 * not take.
 */
object SttPrompts {

    /**
     * Loaded once for a transcription run, so the framing is not re-paid on every chunk.
     *
     * "Transcription engine" rather than "assistant" deliberately: the assistant framing is what
     * invites a model to be helpful about the audio instead of literal about it.
     */
    const val SYSTEM_PROMPT: String =
        "You are a speech transcription engine. You convert spoken audio into text exactly as it " +
            "was said, in the language it was said in. You never translate, never summarise, " +
            "never explain, and never add words that were not spoken."

    /**
     * The per-chunk instruction, sent with each slice of audio.
     *
     * Repeated on every chunk rather than stated once at the start of a conversation, because each
     * chunk *is* its own conversation -- the transcriber resets between them so audio tokens cannot
     * accumulate across a long recording.
     *
     * The empty-output rule matters more than it looks. A recording is sliced at its quietest points,
     * so some slices are close to silence, and a model given silence and no permission to say nothing
     * will invent something plausible to fill the turn.
     */
    const val TRANSCRIBE_INSTRUCTION: String =
        "Transcribe this audio word for word. Write only the transcript itself -- no preamble, no " +
            "quotation marks, no commentary, no description of the speaker or the recording. Use " +
            "normal punctuation and capitalisation. Keep the original language; do not translate. " +
            "If the audio contains no speech, reply with nothing at all."

    /**
     * A hard ceiling on one chunk's reply, in tokens.
     *
     * A model that loses the thread on a hard slice tends to fail by repeating a phrase until
     * something stops it, and without a cap that something is the context window -- minutes of
     * decoding for a chunk that was 20 seconds of audio. Speech runs at roughly three words a second,
     * so 24 s of dense speech is on the order of 100 words; 512 tokens is several times what any
     * honest transcript of one chunk needs, and still bounds the damage.
     */
    const val MAX_CHUNK_TOKENS: Int = 512

    /**
     * Near-zero, but not zero: transcription is a faithful read, and the creativity that makes a
     * summary readable is exactly what invents words here. Left just above zero because some
     * samplers divide by the temperature.
     */
    const val TEMPERATURE: Float = 0.1f
}
