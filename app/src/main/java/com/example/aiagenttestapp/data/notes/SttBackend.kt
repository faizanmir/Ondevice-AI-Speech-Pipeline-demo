package com.example.aiagenttestapp.data.notes

/**
 * Which speech-to-text engine transcribes a recording.
 *
 * Chosen by the user before they start recording, and then fixed for that recording: it is written
 * into the [TranscriptionCheckpoint] beside the audio, so a job re-enqueued after the app was killed
 * resumes on the backend the recording was made for rather than on whatever Settings says now. Half
 * a transcript from one recogniser and half from another would be a strange artefact to hand someone.
 *
 * Lives here rather than in the `stt` package because this is the durable, per-recording fact -- the
 * thing the checkpoint and the settings store persist -- while `stt` holds the machinery that acts on
 * it. It also keeps [com.example.aiagenttestapp.data.SettingsStore] from having to import the speech
 * layer, which it has deliberately avoided doing.
 */
enum class SttBackend(
    /** Stable string form for SharedPreferences and the checkpoint JSON. Never change these. */
    val slug: String,
    /** Shown on the record screen's picker. */
    val label: String,
) {
    /** sherpa-onnx running SenseVoice or Whisper: a purpose-built recogniser, downloaded separately. */
    ONNX("onnx", "Speech model"),

    /** A multimodal language model listening to the audio directly. No separate download. */
    GEMMA("gemma", "Gemma"),

    /**
     * Android's own on-device recogniser, using the system's shared language packs.
     *
     * The only backend whose model this app neither ships nor downloads -- the packs belong to the
     * system, are managed from Settings, and are shared with every other app. Which also means its
     * availability is a property of the device rather than of this app: see
     * [com.example.aiagenttestapp.stt.PlatformSpeech].
     */
    PLATFORM("platform", "Android"),
    ;

    companion object {
        /** The safe answer for anything unrecognised: the backend that predates the choice. */
        val DEFAULT = ONNX

        fun fromSlug(slug: String?): SttBackend =
            entries.firstOrNull { it.slug == slug } ?: DEFAULT
    }
}
