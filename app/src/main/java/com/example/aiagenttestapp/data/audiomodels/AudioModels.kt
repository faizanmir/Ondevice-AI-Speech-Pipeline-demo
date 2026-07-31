package com.example.aiagenttestapp.data.audiomodels

import com.example.aiagenttestapp.data.ArchiveEntry

/** One remote file that makes up a bundle. */
internal data class AudioModelFile(
    val name: String,
    val url: String,
    /**
     * The exact published size, checked after download. A truncated ONNX looks like a finished one
     * to the filesystem and takes the process down inside the native loader.
     */
    val sizeBytes: Long,
)

/** How a bundle's bytes arrive. */
internal sealed interface BundlePayload {

    /** Plain files, fetched straight to disk and each validated against its exact size. */
    data class DirectFiles(val files: List<AudioModelFile>) : BundlePayload

    /**
     * One archive, fetched then unpacked.
     *
     * The archive itself is size-validated; the extracted members are validated as present and
     * non-empty but *not* by byte count, because their inner sizes are published nowhere. Asserting
     * numbers we cannot source would be inventing a contract, and the first upstream rebuild would
     * turn that invention into a download that can never succeed.
     */
    data class Archive(
        val archive: AudioModelFile,
        val entries: List<ArchiveEntry>,
    ) : BundlePayload
}

/**
 * A group of model files that is either wholly present or wholly absent.
 *
 * Deliberately not a [com.example.aiagenttestapp.stt.SpeechModel]. That type answers "which one of
 * these did the user pick?" -- it has a Settings-backed selection, a picker blurb, one-of-N
 * semantics. These bundles have none of that: speaker identification either has its two models or it
 * is switched off, and there is nothing to choose between. Forcing them through the ASR type would
 * have meant giving it a meaningless "selected" concept and an archive-shaped payload it never wants.
 */
class AudioModelBundle internal constructor(
    val id: String,
    val label: String,
    /** Shown on the Settings card: what the feature does and what it costs. */
    val blurb: String,
    internal val payload: BundlePayload,
) {
    /** Bytes that cross the network. For an archive this is the compressed size, which is what the
     *  progress bar is actually measuring. */
    val downloadBytes: Long
        get() = when (val p = payload) {
            is BundlePayload.DirectFiles -> p.files.sumOf { it.sizeBytes }
            is BundlePayload.Archive -> p.archive.sizeBytes
        }

    /** Names the bundle exposes once ready, for callers resolving paths. */
    internal val localNames: List<String>
        get() = when (val p = payload) {
            is BundlePayload.DirectFiles -> p.files.map { it.name }
            is BundlePayload.Archive -> p.entries.map { it.localName }
        }
}

sealed interface AudioModelState {
    data object NotDownloaded : AudioModelState
    data class Downloading(val progress: Float) : AudioModelState

    /** Downloaded, and for an archive bundle also unpacked. */
    data object Ready : AudioModelState
    data class Failed(val message: String) : AudioModelState
}

/**
 * The bundles this app can fetch, and the file names the rest of the code refers to them by.
 *
 * Sizes are the published ones, verified against the servers rather than estimated -- see
 * [AudioModelFile.sizeBytes] for why they have to be exact.
 */
internal object AudioModelCatalog {

    private const val GH_SPEAKER =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models"
    private const val GH_KWS =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models"
    private const val HF = "https://huggingface.co"

    /** Stable local names, so nothing outside this file knows the upstream naming. */
    const val SEGMENTATION = "segmentation.onnx"
    const val EMBEDDING = "embedding.onnx"
    const val KWS_ENCODER = "kws-encoder.onnx"
    const val KWS_DECODER = "kws-decoder.onnx"
    const val KWS_JOINER = "kws-joiner.onnx"
    const val KWS_TOKENS = "kws-tokens.txt"

    /**
     * The keyword list, written next to the model rather than downloaded with it.
     *
     * Generated from `SpokenKeywords` on every load, so it is deliberately not one of the bundle's
     * entries: nothing about it can be missing before the first load, and `isReady` must not wait for
     * a file that only exists once the detector has run. Living in the bundle directory means the
     * user deleting the model takes it too.
     */
    const val KWS_KEYWORDS = "kws-keywords.txt"

    const val SPEAKER_BUNDLE_ID = "speaker"
    const val KEYWORD_BUNDLE_ID = "keywords"

    /**
     * The identifier written onto every enrolled voiceprint, so a future change of embedding model is
     * detectable instead of silently matching nothing. See `SpeakerRecord.embeddingModelId`.
     */
    const val EMBEDDING_MODEL_ID = "wespeaker-en-voxceleb-campplus"

    /**
     * Speaker identification: pyannote for "who is talking when", WeSpeaker CAM++ for "and which of
     * my enrolled people is that".
     *
     * The float pyannote build rather than its 1.5 MB int8 sibling: segmentation decides every
     * boundary downstream, and 6 MB is nothing beside the ASR model already on disk. CAM++ at 29 MB
     * over the stronger 71 MB 3D-Speaker model because embeddings transfer across languages far
     * better than recognition does -- and [EMBEDDING_MODEL_ID] makes the swap safe if that turns out
     * to be optimistic on this app's German notes.
     */
    val SPEAKER = AudioModelBundle(
        id = SPEAKER_BUNDLE_ID,
        label = "Speaker identification",
        blurb = "Recognises who is speaking in a recording and labels the transcript with their " +
            "names. Runs entirely on your phone.",
        payload = BundlePayload.DirectFiles(
            listOf(
                AudioModelFile(
                    name = SEGMENTATION,
                    url = "$HF/csukuangfj/sherpa-onnx-pyannote-segmentation-3-0/resolve/main/" +
                        "model.onnx?download=true",
                    sizeBytes = 5_992_913L,
                ),
                AudioModelFile(
                    name = EMBEDDING,
                    url = "$GH_SPEAKER/wespeaker_en_voxceleb_CAM++.onnx",
                    sizeBytes = 29_292_684L,
                ),
            ),
        ),
    )

    /**
     * Spoken keyword markers: a 3.3 M-parameter streaming zipformer that spots fixed phrases without
     * transcribing anything.
     *
     * The "-mobile" archive because it carries int8 encoder and joiner builds; the set we keep unpacks
     * to about 5 MB. `bpe.model` and the bundled `test_wavs` are deliberately not extracted -- the
     * keyword token sequences are generated ahead of time (see `KeywordSpec`), so nothing needs a
     * tokeniser at runtime.
     */
    val KEYWORDS = AudioModelBundle(
        id = KEYWORD_BUNDLE_ID,
        label = "Spoken keywords",
        blurb = "Lets you mark non-conformities and actions out loud while recording, without " +
            "stopping to type. English only.",
        payload = BundlePayload.Archive(
            archive = AudioModelFile(
                name = "kws-gigaspeech-mobile.tar.bz2",
                url = "$GH_KWS/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01-mobile.tar.bz2",
                sizeBytes = 15_667_804L,
            ),
            entries = listOf(
                // int8 preferred, float accepted. Only the encoder and joiner ship both.
                ArchiveEntry(
                    localName = KWS_ENCODER,
                    patterns = listOf(
                        Regex("^encoder-.*\\.int8\\.onnx$"),
                        Regex("^encoder-.*\\.onnx$"),
                    ),
                ),
                ArchiveEntry(
                    localName = KWS_DECODER,
                    patterns = listOf(
                        Regex("^decoder-.*\\.int8\\.onnx$"),
                        Regex("^decoder-.*\\.onnx$"),
                    ),
                ),
                ArchiveEntry(
                    localName = KWS_JOINER,
                    patterns = listOf(
                        Regex("^joiner-.*\\.int8\\.onnx$"),
                        Regex("^joiner-.*\\.onnx$"),
                    ),
                ),
                ArchiveEntry(
                    localName = KWS_TOKENS,
                    patterns = listOf(Regex("^tokens\\.txt$")),
                ),
            ),
        ),
    )

    val all: List<AudioModelBundle> = listOf(SPEAKER, KEYWORDS)
}
