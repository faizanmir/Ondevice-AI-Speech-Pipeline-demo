# AI Agent Test App

An Android app for running large language models **entirely on-device** — chat, tool calling,
and voice notes, with no server round-trips. Models run locally through swappable inference
engines, and the app is honest about what your hardware can and cannot run before you download
anything.

## Highlights

- **Two inference engines behind one interface.** The chat layer never knows which runtime it
  is talking to; adding a backend means implementing one interface and registering it.
- **A curated catalog plus open search.** Built-in models from Google, Alibaba, Meta, DeepSeek
  and HuggingFace, or add any compatible model from the HuggingFace Hub. Downloads are
  resumable and survive the app being killed.
- **Memory-fit verdicts.** Every model is judged against the device's RAM budget before
  download — "runs comfortably", "tight fit", or "too large" — using measured file sizes and
  per-engine weight-residency modelling, not guesses.
- **Tool calling (app functions).** Capable models can drive the app: open screens, change
  settings, search the web (with a Tavily key). LiteRT-LM uses its native tool API; llama.cpp
  uses a prompt protocol. Either way the calls are rendered as function chips in the
  transcript.
- **Voice notes with on-device speech-to-text.** Record, correct the transcript, and have your
  chosen model summarise it. Two speech models to pick from in Settings: SenseVoice (fast;
  English, Chinese, Japanese, Korean, Cantonese) or Whisper Small (slower; ~100 languages,
  auto-detected).
- **System assistant integration.** App capabilities are exported via AndroidX AppFunctions, so
  the system assistant can drive the app too.
- **Phone and tablet layouts.** Adaptive grids and readable-width columns from the same code —
  no window-size branching.

## Inference engines

| Engine | Formats | Compute | Source |
|---|---|---|---|
| **LiteRT-LM** | `.litertlm` | CPU / GPU / NPU | Google Maven AAR |
| **llama.cpp** | `.gguf` | CPU | built from source with the NDK |

LiteRT-LM is the primary engine: it is Google's supported successor to the deprecated MediaPipe
LLM Inference API, runs on GPU and NPU, and is the only one of the two with a native tool-calling
API. llama.cpp is kept for the breadth of the GGUF catalogue.

## Project structure

```
app/                 UI (Compose), catalog, downloads, chat persistence, settings, voice notes
engine-core/         Engine-agnostic contracts: InferenceEngine, ModelSpec, fit evaluation,
                     context-window budgeting, tool-calling protocol
engine-litertlm/     LiteRT-LM backend
engine-llamacpp/     llama.cpp backend (native build)
tools/               fetch script for llama.cpp's upstream source
```

## Building

Requirements: Android Studio with the NDK and CMake, and a JDK 11+ toolchain (resolved
automatically via Gradle toolchains).

1. Fetch llama.cpp's source (not vendored in this repository):

   ```sh
   tools/fetch_llama_cpp.sh
   ```

2. Build:

   ```sh
   ./gradlew :app:assembleDebug
   ```

The first build compiles llama.cpp from source and takes several minutes; every build after
that is incremental. It can be excluded in `gradle.properties` (`enableLlamaCpp=false`) — the
app still builds and reports the engine as unavailable at runtime.

- **minSdk 31**, arm64-v8a only. Below API 31 there is no reliable way to identify the chipset,
  the accelerated backends are not dependable, and no such device has the RAM to run anything in
  the catalog anyway.
- Emulator use: add `x86_64` to the ABI filters in `gradle.properties` (roughly doubles the
  native build time).

## Models

Nothing is bundled. On first use the app offers the catalog; models download to app-private
storage and are verified against their advertised sizes before an engine ever touches them.
Gated HuggingFace models (Gemma 3, Llama 3.2, official FunctionGemma) need a HuggingFace access
token, entered in Settings and stored encrypted with hardware-backed keys.

Speech models likewise download on demand, the first time you record.

## Settings worth knowing

- **Chat model** — the model new chats (and note summaries) use.
- **Inference engine / accelerator** — preferences, with graceful fallback when unavailable.
- **App functions** — lets the model drive the app; off if you just want to chat.
- **Speech recognition** — SenseVoice for speed, Whisper Small for languages beyond its five.
- **Tavily API key** — enables the `web_search` tool; without it the app is fully offline once
  models are downloaded.
