package com.example.aiagenttestapp.stt

/**
 * Supplies a [Transcriber] backed by a language model, when the host has one.
 *
 * This is the seam that separates speech from language. Voice notes can transcribe with the resident
 * multimodal LLM (`SttBackend.GEMMA`), and that one feature was the *entire* reason the speech
 * pipeline reached into model hosting: three files -- `SttLoadPlanner`, `GemmaTranscriber` and the
 * `GEMMA` branch of `TranscriptionRun` -- imported the catalogue, the load planner, the repository
 * and residency, and dragged all of it across what should have been a module boundary.
 *
 * Inverted, the pipeline states only that *something* may hand it a transcriber, and the host binds
 * whatever it has. The two classes that know both worlds live on the host's side, where both are
 * already visible.
 *
 * **The test of whether the seam is real:** a host that never binds this simply has no LLM backend.
 * The sherpa, streaming and platform backends work untouched, and nothing in the speech pipeline
 * refers to a model, an engine or a catalogue.
 */
fun interface LlmTranscriberFactory {

    /**
     * A transcriber over the resident model, or null when there is none to be had.
     *
     * [preferredModelId] is the model a resumed transcription started on. Honouring it is what stops
     * a job finishing on a different model from the one it began with -- the two produce visibly
     * different transcripts, and half a recording in each is a result nobody can account for.
     *
     * Throws, with a user-facing message, when the host *has* an LLM backend but cannot open it
     * here: no audio-capable model downloaded, or none that fits this device. That is deliberately
     * different from returning null -- a caller that asked for the LLM backend explicitly needs to
     * be told why it did not happen, not quietly given a different transcriber.
     */
    suspend fun open(preferredModelId: String?): Transcriber?

    /**
     * Hands the model back once a run has finished with it.
     *
     * Separate from [Transcriber.release] because the model is *borrowed*, not owned: it is the
     * process-wide resident engine that chat and the audit pipeline also use, so releasing the
     * transcriber says nothing about whether the model should be unloaded. Only the host knows
     * whether anything else still wants it.
     *
     * A no-op by default, which is the right answer for a host with nothing shared to release.
     */
    suspend fun releaseIfIdle() = Unit
}
