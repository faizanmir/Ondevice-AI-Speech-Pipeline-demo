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
 * semantics. These bundles have none of that: the keyword spotter either has its four models or it
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
    private const val GH_PUNCT =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/punctuation-models"
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
    const val PUNCT_MODEL = "punct-model.onnx"
    const val PUNCT_VOCAB = "punct-bpe.vocab"

    const val SPEAKER_BUNDLE_ID = "speaker"
    const val KEYWORD_BUNDLE_ID = "keywords"
    const val PUNCTUATION_BUNDLE_ID = "punctuation"

    /**
     * The identifier written onto every enrolled voiceprint, so a future change of embedding model is
     * detectable instead of silently matching nothing. See [SpeakerRecord.embeddingModelId].
     */
    const val EMBEDDING_MODEL_ID = "3dspeaker-eres2net-base-16k"

    /**
     * Speaker identification: pyannote for "who is talking when", WeSpeaker CAM++ for "and which of
     * my enrolled people is that".
     *
     * The float pyannote build rather than its 1.5 MB int8 sibling: segmentation decides every
     * boundary downstream, and 6 MB is nothing beside the ASR model already on disk.
     *
     * 3D-Speaker ERes2Net-base for the embeddings, replacing WeSpeaker CAM++, because the clustering
     * that consumes them is calibrated against this model and not against CAM++. sherpa's stock
     * `threshold = 0.5` -- the value the diariser cuts the dendrogram at -- was set with the
     * 3D-Speaker family, and it is what both sherpa's Android sample and its Python example ship.
     * Running CAM++ against a threshold tuned for something else is how a two-speaker recording came
     * back as eleven clusters. Trained on Mandarin, which matters less than it sounds: a voiceprint
     * encodes the voice rather than the words, and speaker embeddings transfer across languages far
     * better than recognition does.
     *
     * Changing this invalidates every enrolled voiceprint, which is exactly what [EMBEDDING_MODEL_ID]
     * exists to make visible -- vectors from a different model do not look wrong, they simply match
     * nobody, so the app offers re-enrolment instead of silently recognising no one.
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
                    url = "$GH_SPEAKER/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx",
                    sizeBytes = 39_593_761L,
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
                name = "kws-gigaspeech.tar.bz2",
                url = "$GH_KWS/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2",
                sizeBytes = 17_626_723L,
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


    /**
     * Restores capitalisation and sentence punctuation on streaming transcripts.
     *
     * Needed only by the streaming transducer, and needed *badly*: measured on device, that model
     * returns `GOOD MORNING THIS IS THE OPENING NARRATION FOR THE SURVEILLANCE AUDIT` -- one
     * unbroken uppercase stream. The offline recognisers do not have this problem. SenseVoice is
     * loaded with inverse text normalisation on, and Whisper punctuates natively; a transducer emits
     * raw tokens and has no equivalent setting.
     *
     * That is more than an aesthetic complaint here. This app feeds the transcript to a summarising
     * language model and matches spoken marker phrases against its text, and both do measurably
     * worse on an uncased run-on stream. So this bundle is what makes streaming output usable by the
     * rest of the pipeline rather than merely readable.
     *
     * int8 preferred: 7 MB against 28 MB, roughly twice as fast, and the task is inserting commas
     * rather than deciding words.
     */
    val PUNCTUATION = AudioModelBundle(
        id = PUNCTUATION_BUNDLE_ID,
        label = "Punctuation",
        blurb = "Adds capitals and full stops to live transcripts. Only used by the streaming " +
            "speech model, which produces none of its own. English.",
        payload = BundlePayload.Archive(
            archive = AudioModelFile(
                name = "online-punct-en.tar.bz2",
                url = "$GH_PUNCT/sherpa-onnx-online-punct-en-2024-08-06.tar.bz2",
                sizeBytes = 30_667_839L,
            ),
            entries = listOf(
                ArchiveEntry(
                    localName = PUNCT_MODEL,
                    patterns = listOf(
                        Regex("^model\\.int8\\.onnx$"),
                        Regex("^model\\.onnx$"),
                    ),
                ),
                ArchiveEntry(
                    localName = PUNCT_VOCAB,
                    patterns = listOf(Regex("^bpe\\.vocab$")),
                ),
            ),
        ),
    )

    val all: List<AudioModelBundle> = listOf(SPEAKER, KEYWORDS, PUNCTUATION)
}
