# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

An Android app that runs LLMs and speech models **entirely on-device**: chat with tool calling,
voice notes with on-device STT, and a document audit pipeline. See `README.md` for the product-level
overview and the model catalogue.

## Navigating this codebase

This repo carries a knowledge graph. **Reach for it before Grep/Glob/Read** — it is faster, costs
far fewer tokens, and answers structural questions (callers, dependents, test coverage) that
scanning files cannot.

The `code-review-graph` MCP tools:

| Tool | Use when |
|------|----------|
| `semantic_search_nodes` | Finding a function or class by name or keyword — instead of grepping |
| `query_graph` | Tracing `callers_of` / `callees_of` / `imports_of` / `tests_for` |
| `get_impact_radius` | Working out the blast radius before changing something |
| `get_affected_flows` | Which execution paths a change touches |
| `detect_changes` | Reviewing a diff — returns a risk-scored analysis |
| `get_review_context` | Source snippets for review, without reading whole files |
| `get_architecture_overview`, `list_communities` | Orientation in an unfamiliar area |

Impact analysis earns its keep here specifically, because the strongest couplings in this codebase
are not import edges: slice boundaries are a checkpoint lookup key, chunk sizing is read by four
separate budgets, and a prompt change moves the token reserve that decides where chunks are cut.
Nothing imports those relationships — the graph is where they are visible.

`/graphify` (the graphify skill) is what builds and refreshes the graph, into `graphify-out/`. It
captures two halves: structure from the AST, and the **rationale carried in the KDoc** — the "this
used to be X, which cost the user their recording" comments that are the most valuable thing in this
repo and the first thing to go stale.

**The graph rebuilds on commit, not by hand.** A `post-commit` hook (`graphify hook status` to
check) launches a background rebuild of the structural half from whatever the commit touched. Do not
run `/graphify` yourself to refresh it after a change — the hook has it. Running it manually
re-clusters the whole graph, which renumbers every community and forces all ~370 labels to be
reassigned by hand for a result the hook produces on its own.

The hook covers the AST half only, and only for code files. The **rationale in the KDoc** — the half
that actually matters here — needs a semantic pass, which costs an LLM call per file and therefore
happens only when explicitly asked for. So after a change that rewrites a long explanatory comment,
say so; do not launch one unprompted.

Fall back to Grep/Glob/Read only when the graph does not cover what you need.

## Commands

```sh
./gradlew :app:assembleDebug              # no native build step; there is nothing compiled from source
./gradlew test                            # ALL unit tests (~820, JVM, no device needed)
./gradlew :stt:testDebugUnitTest --tests "*AudioSegmenterTest*"   # one test class, in its module
./gradlew :app:compileDebugKotlin         # fastest check that a change compiles
./gradlew :app:lint                       # stock Android lint; no ktlint/detekt/spotless configured
./gradlew :stt:runtimeDeps                # coordinates a consumer of the AAR must declare
```

**`:app:testDebugUnitTest` no longer means "all tests".** The suite is split across modules -- 584 in
`:app`, 168 in `:stt`, 63 in `:engine-core`. Use `./gradlew test`, and name the module when running
one class.

- There is no longer a vendoring step or a native build. llama.cpp was the only dependency compiled
  from source; with it gone every dependency is a prebuilt AAR, so a clean build is minutes shorter
  and `tools/` no longer exists.
- minSdk 31, **arm64-v8a only** — set by `abiFilters` in `app/build.gradle.kts`. For an emulator,
  add `x86_64` there.
- **Do not run the test suite unless asked.** Write the tests, and use `:app:compileDebugKotlin`
  to confirm a change builds — running them is the user's call, not a step to fold into every task.
- Most logic is deliberately pure and JVM-testable. Prefer adding a unit test over reaching for a
  device; ask the user to run on-device steps rather than driving adb yourself.

### Device work goes through `/android-cli`

The default above stands — the user runs on-device steps. When they do hand you the device, drive it
through `/android-cli` (the `android` CLI) rather than guessing at raw `adb`:

- `android layout` prints the on-screen UI tree as JSON; `android layout --diff` prints only what
  changed since the last call. Make this the primary way you look at the app — it costs a fraction of
  a screenshot in context, and it hands you the `resourceId` and `center` coordinates to act on.
- `android screen capture -o <png>` is the fallback for what the layout dump cannot show: WebViews,
  images, animation. `--annotate` numbers the elements on the image, and `android screen resolve
  --screen <png> --string "#3"` turns a label back into coordinates.
- Taps and text still go through `adb shell input`, fed by those coordinates. The CLI inspects; adb
  acts.
- `android run` builds, deploys and launches; `android install` pushes APKs without starting anything;
  `android info` prints the SDK path and connected devices. `android emulator` manages AVDs — but this
  app builds **arm64-v8a only**, so an emulator needs `x86_64` in `abiFilters` first.
- `android sdk install|update|remove|list` manages SDK packages instead of raw `sdkmanager`.
- `android docs <keywords>` searches Android's own documentation. Use it before answering a platform
  API question from memory.

The skill also carries a *journey* format — an XML list of `<action>` steps you walk yourself with
`layout` and `screen`, failing the run at the first step that cannot be performed as written. There is
no `journey` subcommand; it is a convention, and the closest thing to a UI test this repo has, since
`app/src/androidTest` holds only a template stub and one platform-speech probe.

## Architecture

### Engines are pluggable; nothing above them knows which is running

`engine-core` holds the contracts (`InferenceEngine`, `EngineDescriptor`, `EngineAvailability`,
`ModelSpec`, `ModelFitEvaluator`, `OutputGuard`), and `engine-litertlm` implements them. Capability
differences are **declared on `EngineDescriptor`**, not discovered by type checks —
`supportsNativeTools`, `supportsAudioInput`, `supportsVision`. Adding a backend means implementing
the interface and registering it with `EngineRegistry`.

**One engine is registered, and `ModelFormat` has one value.** MNN and AICore went in `0fe20f3`,
llama.cpp and GGUF after them. The abstraction is kept because each of those removals was a deletion
rather than a hunt — but it is currently carrying no weight that can be observed, so do not let a
hypothetical fourth engine justify anything new. What each removal did have to touch is worth
knowing: the registry list, `ModelFormat`, `EngineId`, `ParamBudget.weightResidency`, and the
catalogue.

`OutputGuard` exists because no two runtimes expose the same controls: max-output-tokens and stop
sequences are enforced uniformly in Kotlin rather than per-engine.

### One model stays resident; features borrow it

`data/ModelResidency.kt` keeps the active model loaded for the whole session so opening a chat is
instant. Anything that wants the model **borrows** it:

- `attach()` / `detach()` — hold it against a memory-pressure release for the length of a job.
- `runExclusive { }` — serialise conversation resets, because two callers rebuilding the
  conversation concurrently leaves one holding a closed handle.

Voice-note transcription (`GemmaTranscriber`) and the audit pipeline both do this. If you add
another background consumer of the LLM, follow the same pattern or you will unload a model out from
under a running job.

### Tool calling has one mechanism, and a seam where the second was

`functions/ToolCallingStrategy.kt` — LiteRT-LM declares tools as schemas and calls them itself
(`RuntimeDriven`). `functions/AppFunctionRegistry.kt` is the single catalogue; app capabilities are
also exported via AndroidX AppFunctions.

There was a second mechanism until llama.cpp went: `PromptDriven`, where tools were described in the
system prompt and the app drove the call/result loop by hand. Removing it took `ChatToolLoop.kt`, the
hop branch in `ChatViewModel`, and the `maxToolHops` setting with it — which is the point of the
`forEngine` seam, since none of that had to be hunted for. `NoToolCalling` is what an engine without
a tool API gets now, and it is also what a chat holds before a model is loaded.

### ViewModels are MVI, uniformly

`ui/mvi/Mvi.kt` defines `UiState` / `UiIntent` / `UiEffect` and `MviViewModel`. One immutable state
object per screen, every change is one branch of one sealed intent type, one-shot things
(navigation, toasts) are **effects, not state**. Screens have no other way in.

### Long work is durable, not just backgrounded

This is the strongest recurring pattern in the codebase, and new background work should match it.
Every long job is a WorkManager `CoroutineWorker` (foreground service where it matters) plus:

- **A checkpoint keyed by exact identity**, so a killed process resumes rather than restarts.
  Transcription uses a `.progress` sidecar JSON beside the audio (`TranscriptionCheckpoint`, keyed
  by exact sample range); audits use Room (`audit.db`) with per-chunk checkpointing.
- **The request stored where the job can find it**, never only in WorkManager input data — input
  data dies with the job, and a re-enqueue that lost it would silently produce a degraded result.
- **Orphan reconciliation at startup** — `NoteTranscribeWorker.reconcileOrphans` /
  `recoverOrphanedAudio` adopt work left behind by a process death.

### Voice notes: capture → slice → transcribe

Audio streams to a WAV **as it is captured** (`WavFile.Writer`); only a few seconds live in RAM
(`RollingSampleWindow`). Transcription reads it back through `WavFile.Reader` a window at a time —
never whole, because that cost grows without limit with recording length.

The worker's stages: VAD (`SpeechActivityDetector` → `SpeechRegions`) marks silence,
`SpokenMarkers.slice` cuts on marker phrases and the backend's clip cap, `AudioSegmenter` places
each cut at the quietest nearby frame, then each slice is decoded and checkpointed.

Two things to know before touching it:

- **Slice boundaries are the checkpoint's lookup key.** Changing how cuts are placed invalidates
  every resume and every slice pre-decoded during recording (`PipelinePlanner`). Verify boundaries
  are unchanged, not just that the code compiles.
- **Window vs recording coordinates.** A `WavFile.Reader` window starts at index 0 while slice ranges
  are absolute. Mixing them indexes off the front of an array — it has caused a crash here before.
  The conversions are deliberately confined to `decode` / `cutBetween` in `NoteTranscribeWorker`.

Two STT backends sit behind `Transcriber`: sherpa-onnx (`OnnxTranscriber` / `StreamingTranscriber`)
and the resident LLM (`GemmaTranscriber`). They tolerate very different clip lengths, so
`maxSliceSamples` comes from the transcriber — the Gemma cap is a **crash guard**, since LiteRT-LM
aborts the process rather than throwing on an over-long clip.

### Modules, and the two that are meant to leave

`:stt` and `:llm` are built to be handed over as AARs — see `docs/integration.md`. Three rules follow
from that, and they are the ones most easily broken by a well-meaning change:

- **Neither reads `SettingsStore`.** Speech takes `SttConfig`, model hosting takes `LlmConfig`, and
  `data/SttSettings.kt` / `data/LlmSettings.kt` are the *only* files that know both. Adding a speech
  setting means a field on `AppSettings` and one line in the mapper; the compiler will not tell you
  about the second, but `SttSettingsTest` will.
- **Neither carries a Hilt annotation.** A library that forces Hilt on its callers is one most hosts
  cannot take. Their objects are constructed in `di/AppModule.kt`, and their WorkManager workers come
  from plain `WorkerFactory`s composed into a `DelegatingWorkerFactory` in `AIAgentApplication`.
- **`:stt` does not depend on `:llm`.** Voice notes can transcribe with the resident LLM, and that
  one feature is behind `LlmTranscriberFactory`, bound in `:app` by `bridge/GemmaTranscriberFactory`.
  Unbind it and three of the four speech backends still work — which is the test of whether the seam
  is real. `bridge/` exists for exactly the classes that know both worlds; nothing else belongs there.

`SttStorage` takes the storage root as a parameter, and **the resolved paths must not change** —
`speech`, `audio-models/speaker`, `enroll`, `notes`, `diarized` all hold files on real devices, and
transcription checkpoints are keyed to the audio beside them. `SttStorageTest` pins them.

### Layering conventions worth respecting

- The old rule that `data/` must not import `stt/` is **gone**, along with the workaround it forced:
  `OnnxProvider`, `SliceWindow`, `SttBackend` and the rest lived in `data/` only to keep this file
  from importing the pipeline. They are now `:stt`'s public config API and `AppSettings` merely holds
  them, so the dependency runs app → library, which is the direction it should always have run.
- `prompts/` centralises every prompt string. Prompt changes belong there, not inlined at call sites.
- **Room migrations are never rewritten**, only appended. A migration describes a step that already
  ran on real devices; editing one is how a device on an old version takes an untested path.
- sherpa-onnx is a **prebuilt AAR with ONNX Runtime statically linked**, resolved via the ivy repo in
  `settings.gradle.kts` (it publishes no Maven artifact). You get only the execution providers
  k2-fsa compiled in; changing that means building it from source.

## Code style

The distinguishing convention here is that comments explain **why**, at length, and usually record
the failure that motivated the decision ("this used to be X, which cost the user their recording").
Match that when editing — density of explanation is a deliberate property of this codebase, not
accidental verbosity. Do not add comments that restate what the code says.

Prefer pure, injectable functions for anything involving judgement calls (slicing rules, parsing,
fit evaluation) so they can be pinned by a JVM test rather than reproduced on a device.

## Benchmarks

`docs/` holds a real on-device transcription benchmark with WER methodology —
`python3 docs/wer.py <reference> <transcript>` prints raw and number-normalised WER, and
`docs/stt-benchmark.html` is the written-up comparison. Use it when changing anything that affects
transcript quality; there is no equivalent speed baseline yet.
