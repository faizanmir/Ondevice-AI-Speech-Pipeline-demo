package com.example.aiagenttestapp.stt

import java.io.File

/**
 * Where the speech pipeline keeps its files, given a root the host chooses.
 *
 * The directory names were hardcoded against `context.filesDir` in five places. That is fine for an
 * app that owns its own storage and wrong for a library: names like `speech` and `models` are
 * generic enough to collide with whatever the host already keeps there, and a library has no
 * business deciding.
 *
 * **The resolved paths must not change.** Every one of these directories holds files already on real
 * devices -- downloaded recognisers of a few hundred megabytes, speaker models, enrolment takes --
 * and the transcription checkpoints (`.progress` sidecars) are keyed to the audio sitting beside
 * them. A tidier layout would silently orphan all of it: the app would report nothing downloaded,
 * re-fetch gigabytes, and drop every resumable job. So this class exists to make the root
 * *injectable*, not to reorganise anything under it, and the app passes `filesDir` so that every
 * path below resolves exactly as it did before.
 */
class SttStorage(private val root: File) {

    /** Downloaded sherpa-onnx recognisers. */
    val speechModels: File get() = File(root, "speech")

    /** Segmentation and speaker-embedding models, under the shared audio-model root. */
    val speakerModels: File get() = File(root, "audio-models/speaker")

    /** Enrolment takes -- the recordings a voice is identified from. */
    val enrolments: File get() = File(root, "enroll")

    /** Recorded voice notes and their `.progress` checkpoint sidecars. */
    val notes: File get() = File(root, "notes")

    /** Audio kept for a finished diarisation, so a transcript can be replayed. */
    val diarized: File get() = File(root, "diarized")
}
