package com.example.aiagenttestapp.stt

/**
 * Which implementation answers "who spoke when".
 *
 * Both run the same two models -- pyannote segmentation-3.0 and the 3D-Speaker embedder -- and they
 * differ only in who owns the stage between them.
 *
 * [SHERPA] hands the audio to sherpa's `OfflineSpeakerDiarization`, which segments, embeds and cuts
 * a dendrogram behind one native call and returns segments. It is the path every number in
 * `docs/diarization-benchmark.md` was measured on.
 *
 * [FRAME] runs the segmentation model itself and keeps what sherpa discards. pyannote emits a
 * *powerset posterior per frame* -- a distribution over {}, {A}, {B}, {C}, {A,B}, {A,C}, {B,C} every
 * ~17 ms -- and sherpa reduces that to argmax segments and drops the rest. Three things downstream
 * are only possible with the full track:
 *
 *  - **Overlapping speech is detectable.** Two speakers active in one frame is a value in the
 *    posterior, not an inference from segments that no longer exist.
 *  - **A word can be attributed by speaker mass** across the frames it spans, weighted by the
 *    model's own confidence margin, instead of by which turn its start timestamp lands in.
 *  - **Clustering can be ours.** Owning the segments means owning their embeddings, which is what
 *    lets automatic speaker count, crumb absorption, the phantom fold, the same-voice merge, Viterbi
 *    smoothing and frame-level resegmentation run at all -- see [com.example.aiagenttestapp.stt.SpeakerClustering].
 *
 * The cost is a second ONNX Runtime in the process and the ~15 MB it adds, since sherpa's
 * static-link build keeps its own ORT sealed inside its `.so`.
 *
 * [FRAME] is the default. It is ported from an unrelated codebase that measured ~99.2% word-level
 * speaker attribution on a 23-minute German audit recording -- against 98.3-98.6% here on a
 * different corpus, so the two figures do not compare directly and this default is not yet backed by
 * a measurement on *this* app's benchmark. Re-running `docs/diarscore.py` on both settings is the
 * open follow-up; [SHERPA] stays selectable so that comparison is one setting away rather than a
 * revert. See `docs/wp30-comparison.html` for where the two pipelines diverge.
 */
enum class DiarizationEngine(
    val slug: String,
    val label: String,
    val hint: String,
) {
    FRAME(
        slug = "frame",
        label = "Frame posteriors",
        hint = "Runs segmentation here and keeps its per-frame output. Detects overlapping " +
            "speech, attributes words by speaker mass, and clusters with automatic speaker count.",
    ),

    SHERPA(
        slug = "sherpa",
        label = "sherpa",
        hint = "One native call for segmentation, embedding and clustering. The path every " +
            "published benchmark figure was measured on.",
    ),

    ;

    companion object {

        val DEFAULT = FRAME

        /**
         * Resolves a stored slug, falling back to [DEFAULT].
         *
         * Unrecognised covers a setting written by a newer build, an option since withdrawn, and the
         * fresh-install case where nothing is stored at all. Both engines drive the same two model
         * files and are loaded the same way, so neither answer can leave a device unable to diarise.
         */
        fun fromSlug(slug: String?): DiarizationEngine =
            entries.firstOrNull { it.slug == slug } ?: DEFAULT
    }
}
