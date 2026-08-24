# AI Agent Test App

An Android app that runs large language models and speech models **entirely on-device** — chat with
tool calling, voice notes with on-device transcription, speaker identification, and a document audit
pipeline. Nothing is uploaded: the only network traffic is downloading models, plus one optional web
search tool you have to supply a key for.

## What it does

- **Chat** with any model you have downloaded — dictation, document attachments, tool calling, and
  conversations that keep going after they outgrow the model's context window.
- **Voice notes** — record, transcribe on device, correct the transcript, and have the model
  summarise it. A 20-minute recording survives the app being killed and resumes where it stopped.
- **Speakers** — enrol a voice once, then have a recording split into speaker turns and attributed
  by name.
- **Audit** — queue documents to be read against a standard, section by section, and get a report
  with quoted evidence that exports as a PDF.
- **Benchmark** — score the transcription pipeline's word error rate on the device that will run it,
  using the same machinery a real note goes through.

## Highlights

- **Two inference engines behind one interface.** The chat layer never learns which runtime it is
  talking to; capability differences are declared on the engine descriptor rather than discovered by
  type checks. Adding a backend means implementing one interface and registering it.
- **A curated catalogue plus open search.** Built-in models from Google, Alibaba, Meta, DeepSeek and
  HuggingFace, or add any compatible model from the HuggingFace Hub. Downloads are resumable and
  survive the app being killed.
- **Memory-fit verdicts.** Every model is judged against the device's RAM budget before download —
  *comfortable*, *tight* or *unsupported* — from measured file sizes and per-engine weight-residency
  modelling, not guesses.
- **Tool calling with two mechanisms and one catalogue.** LiteRT-LM declares tools as schemas and
  calls them itself; llama.cpp is told about them in the system prompt and the app drives the
  call/result loop. Both read the same registry, and the calls render as function chips in the
  transcript either way.
- **Three speech-to-text backends.** A downloaded sherpa-onnx recogniser, the resident multimodal LLM
  listening to the audio directly, or Android's own on-device recogniser. The choice is pinned onto
  each recording, so a job resumed after a restart finishes on the backend it started on.
- **Long work is durable, not just backgrounded.** Every long job is a WorkManager worker with a
  checkpoint keyed by exact identity, the request stored where the job can find it, and orphan
  reconciliation at startup. Killing the app costs you the current section, not the recording.
- **System assistant integration.** App capabilities are exported via AndroidX AppFunctions, so the
  system assistant can drive the app too.
- **Phone and tablet layouts.** Adaptive grids and readable-width columns from the same code — no
  window-size branching.

## Inference engines

| Engine | Formats | Compute | Native tools | Audio in | Source |
|---|---|---|---|---|---|
| **LiteRT-LM** | `.litertlm` | CPU / GPU / NPU | yes | yes | Google Maven AAR |
| **llama.cpp** | `.gguf` | CPU | no (prompt protocol) | no | built from source with the NDK |

LiteRT-LM is the primary engine: it is Google's supported successor to the deprecated MediaPipe LLM
Inference API, runs on GPU and NPU, and is the only one of the two with a native tool-calling API and
audio input. llama.cpp is kept for the breadth of the GGUF catalogue.

One model stays resident for the whole session, so opening a chat is instant. Background consumers —
note transcription, the audit drainer — borrow it rather than loading their own.

## Speech

Three backends sit behind one `Transcriber` interface, chosen per recording:

| Backend | Model | Notes |
|---|---|---|
| **Speech model** | sherpa-onnx, downloaded | Four recognisers to choose from, below |
| **Gemma** | the resident LLM | No separate download; the model listens to the audio itself |
| **Android** | the system's language packs | Shared with every other app, managed in system Settings |

The downloadable recognisers:

| Model | Size | Languages |
|---|---|---|
| **SenseVoice** | ~240 MB | English, Chinese, Japanese, Korean, Cantonese. Fast |
| **Streaming Zipformer** | ~73 MB | English only, transcribed live as you speak |
| **Whisper Small** | ~375 MB | ~100 languages, detected automatically. Slower |
| **Parakeet v3** | ~670 MB | English, German and 23 more European languages. Most accurate |

Three optional bundles download separately:

- **Speaker identification** (~46 MB) — pyannote segmentation for *who is talking when*, 3D-Speaker
  ERes2Net for *which enrolled person that is*.
- **Spoken keywords** (~18 MB archive) — a 3.3 M-parameter keyword spotter that hears fixed phrases
  without transcribing anything, so you can mark a non-conformity or an action out loud, or say
  "stop recording", without stopping to type. The spotter is English-only; German markers are found
  by exact phrase match on the finished transcript instead. Either way the phrase itself is excised
  rather than transcribed.
- **Punctuation** (~31 MB archive) — capitals and full stops for the streaming recogniser, which
  produces none of its own. Only that one model needs it.

### How a recording becomes a transcript

Audio streams to a WAV as it is captured — only a few seconds ever live in RAM, so recording length
costs disk rather than memory. Transcription then reads it back a window at a time: voice-activity
detection marks the silence, spoken markers and the backend's clip cap decide where to cut, each cut
is nudged to the quietest nearby frame, and every slice is checkpointed as it lands.

## Speakers

Enrol someone with three takes of at least four seconds of speech each; the app rejects a take with
two voices in it, or too little speech to be a voiceprint. After that, a recording — imported or
taken live — is diarised into speaker turns, transcribed, and written out as one block per turn with
the enrolled name attached. Voiceprints are tied to the embedding model that produced them, so a
model change offers re-enrolment instead of quietly recognising nobody.

## Audit

Attach a document (text formats or PDF) and queue it. It is chunked to the loaded model's context
window and read section by section, with per-chunk checkpoints in Room, so an interrupted run picks
up mid-document rather than starting over. Two reads are available and are pinned per document at
enqueue: **in detail**, which enumerates non-conformities with a severity grade, a verified quote and
the clause it cites, and **quick**, which answers one question — result, reason, evidence, actions,
what was left open. Finished reports are viewable in the app and exportable as a PDF.

## Benchmarks

`docs/` holds a real on-device transcription benchmark with its methodology and caveats:
**[docs/stt-benchmark.html](docs/stt-benchmark.html)** is the write-up, `docs/README.md` the summary,
and `python3 docs/wer.py <reference> <transcript>` scores a transcript raw and number-normalised. The
best run to date is 7.8% normalised WER (6.9% excluding marker phrases). `docs/stt-approaches.html`
and `docs/diarization-research.html` record how the two pipelines were chosen.

The app has its own benchmark screen that runs the same measurement on-device, against a clip and
reference you import.

## Project structure

```
app/                 UI (Compose, MVI), catalogue, downloads, chat, voice notes, speakers,
                     audit queue, benchmark, settings
engine-core/         Engine-agnostic contracts: InferenceEngine, ModelSpec, fit evaluation,
                     context-window budgeting, output guard, tool-calling protocol
engine-litertlm/     LiteRT-LM backend
engine-llamacpp/     llama.cpp backend (native build)
docs/                Transcription benchmark, WER scorer, research write-ups
tools/               fetch script for llama.cpp's upstream source
```

## Building

Requirements: Android Studio with the NDK and CMake, and a JDK 11+ toolchain (resolved automatically
via Gradle toolchains).

1. Fetch llama.cpp's source (not vendored in this repository):

   ```sh
   tools/fetch_llama_cpp.sh
   ```

2. Build:

   ```sh
   ./gradlew :app:assembleDebug
   ```

The first build compiles llama.cpp from source and takes several minutes; every build after that is
incremental. It can be excluded with `enableLlamaCpp=false` in `gradle.properties`, or
`-PenableLlamaCpp=false` for one build — the app still builds and reports the engine as unavailable
at runtime. `-PenableLlamaCppVulkan=true` opts into the Vulkan backend, which needs SPIRV-Headers on
the host.

Tests run on the JVM without a device:

```sh
./gradlew :app:testDebugUnitTest        # ~640 unit tests across all modules
./gradlew :app:compileDebugKotlin       # fastest check that a change compiles
```

- **minSdk 31**, arm64-v8a only. Below API 31 there is no reliable way to identify the chipset, the
  accelerated backends are not dependable, and no such device has the RAM to run anything in the
  catalogue anyway.
- Emulator use: add `x86_64` to `llamaCppAbiFilters` in `gradle.properties` (roughly doubles the
  native build time).

## Models

Nothing is bundled. On first use the app offers the catalogue; models download to app-private storage
and are verified against their advertised sizes before an engine ever touches them. Gated
HuggingFace models (Gemma 3, Llama 3.2, official FunctionGemma) need a HuggingFace access token,
entered in Settings and stored encrypted with hardware-backed keys.

Speech models likewise download on demand, the first time you need one.

## Settings worth knowing

- **Chat model** — the model new chats, note summaries and audits use.
- **Speech recognition** — which recogniser the sherpa-onnx backend downloads and uses: SenseVoice
  for speed, Parakeet v3 for accuracy in English and the European languages, Whisper Small for
  everything further afield, streaming for a live transcript. (Which *backend* — speech model,
  Gemma or Android — is picked on the record screen, per recording.)
- **Voice notes** — skip silence, spoken keyword markers, the speaker bundle, and the ONNX execution
  provider (CPU by default; XNNPACK is the faster one).
- **Inference engine / accelerator** — preferences, with graceful fallback when unavailable.
- **Tools** — lets the model drive the app; off if you just want to chat.
- **Web search** — a Tavily API key enables the `web_search` tool. Without it the app is fully
  offline once models are downloaded.
- **Sampling, performance, reasoning** — temperature, top-k/top-p, stop sequences, thread count,
  reproducible output, and whether reasoning models get a thinking step before answering.
