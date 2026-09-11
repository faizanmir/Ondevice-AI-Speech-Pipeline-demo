# Splitting `:app`

> **Status, 3 Sep 2026 — Phases 0 to 4 are applied.** `:llm` and `:stt` exist, build standalone, and
> carry no Hilt annotation and no dependency on the host's settings store. 821 unit tests pass across
> five modules. What is *not* done is listed under "Remaining" at the end of this file, and
> `docs/integration.md` is the consumer-facing half. Everything below is kept as written so the
> reasoning behind each decision survives; where the implementation diverged, a note says so.

Read from `pipeline-followups` at `282bc6d`, 3 Sep 2026. File and line references were read from the
working tree and will drift as the branch moves.

**Target:** `:stt` and `:llm` ship as hand-over AARs — built with `assembleRelease`, handed to a
consumer, no Maven POM. Layered so a consumer takes only what it needs. Two engines have already
been dropped from this repo; llama.cpp makes three, and once it goes the boundary between speech
and language is only three files wide.

| | |
|---|---|
| Kotlin in `:app` | 51,316 lines, 210 files |
| Gradle modules today | 4 |
| Imports coupling `:app` to llama.cpp | **2** |
| Files coupling STT to the LLM | **3** |
| Hilt annotations in the whole `:stt` candidate | **17**, none in the pure pipeline |
| Unit test files to re-home | 79 |

---

## 1. Dropping llama.cpp is smaller than it looks — and one consequence is bigger

There is precedent to copy exactly. Commit `0fe20f3` ("Drop the MNN and AICore engines", 31 Jul)
removed two backends and everything that only existed to serve them. This is the same move, third
time.

The engine abstraction did its job: `:app` reaches into `engine-llamacpp` through precisely two
imports.

| Where | What | Change |
|---|---|---|
| `di/AppModule.kt:8,75` | `LlamaCppEngine()` in the `EngineRegistry` list | delete |
| `functions/ToolCallingStrategy.kt:6` | `ToolCallingProtocol` backs `PromptToolCalling` | delete |
| `settings.gradle.kts:44` | `include(":engine-llamacpp")` | delete |
| `app/build.gradle.kts:134` | `implementation(project(":engine-llamacpp"))` | delete |
| `gradle.properties` | `enableLlamaCpp`, `llamaCppAbiFilters` + their comment block | delete |
| `tools/fetch_llama_cpp.sh` | the vendoring script | delete |
| `engine-llamacpp/` | 3 Kotlin files, 1 test, `llama_jni.cpp` (671 lines), CMake, vendored tree | delete |
| `app/build.gradle.kts` | `pickFirsts += "**/libc++_shared.so"` — still needed for LiteRT-LM + sherpa, but the comment says "both engines" | reword |
| `ui/settings/SettingsScreen.kt:576` | "Decode threads for the CPU engine (llama.cpp)" — *the setting stays*, see below | reword |
| `CLAUDE.md`, `README.md` | build commands, engine table, the two-mechanism tool-calling section | rewrite |

### The prompt-driven tool loop dies with it

`ToolCallingStrategy.PromptDriven` exists because llama.cpp has no tool API. LiteRT-LM is
`RuntimeDriven` and runs the loop itself. With no `PromptDriven` implementation left, this all
becomes unreachable:

- `ui/chat/ChatToolLoop.kt` **in full** — its constructor takes a `PromptDriven`
- the `as? PromptDriven` branch at `ChatViewModel.kt:513–520`
- the `maxToolHops` setting behind it — `SettingsStore.kt:91,233,269` and the slider at
  `SettingsScreen.kt:419–425`

Keep the `ToolCallingStrategy` sealed interface and `forEngine` — that seam is what makes a fourth
engine cheap. Delete the `PromptDriven` sub-interface and its one implementation.

### Do not delete `threadCount`

Its label claims it is llama.cpp's, but it is read by `ModelLoadPlan.kt:132` on the LiteRT-LM path
and by four STT callers — `RecordViewModel`, `DiarizeViewModel`, `DiarizeWorker`,
`LiveDiarizeWorker`. The removal should fix the label, not the setting.

---

## 2. GGUF goes too, because nothing is left that can read it

Five of the eleven catalogue entries are GGUF, and the Hub's Hugging Face browser *defaults* to it.

| File | What comes out |
|---|---|
| `engine-core/…/ModelSpec.kt:15` | `GGUF(".gguf", "GGUF")` — leaves `LITERTLM` as the only `ModelFormat` |
| `data/ModelCatalog.kt:160–265` | functiongemma-270m, qwen2.5-0.5b, smollm2-360m, llama-3.2-1b, qwen2.5-3b |
| `data/HuggingFaceClient.kt` | the `&filter=gguf` search (109), `gguf` metadata parsing (159–174), extension→format (184), multi-part shard guard (189), `GGUF → CPU` accelerator map (349) |
| `ui/hub/HubViewModel.kt:51,87` | default format `GGUF` → `LITERTLM`; the format picker in `HubScreen` now has one option |
| `data/ModelLoadPlan.kt:31`, `data/ChatLoadPlan.kt:28` | the `NoEngine` states — their doc comment says they exist for "a GGUF model with llama.cpp excluded" |
| `test/…/AuditAnalysisParserTest.kt:220,226` | the `engineName = "llama.cpp"` fixture; plus `ToolCallingStrategyTest`, `AppFunctionBodyTest` |

**A user's saved custom models are already protected.** `CustomModelStore.kt:50` decodes saved specs
one at a time inside a `runCatching`, precisely so that removing a `ModelFormat` drops the dead
entries and keeps the rest. That guard was added by `0fe20f3` for this exact situation and is still
intact — but pin it with a test before relying on it a second time.

---

## 3. The export contract: an AAR with no POM

This is the constraint that shapes everything below. `assembleRelease` produces an `.aar` and
nothing else — **no POM, so no transitive dependency resolution.** Whoever receives it must declare
every dependency by hand, at a compatible version, or they get a `NoClassDefFoundError` at runtime
rather than a build failure.

Three consequences.

### The sherpa-onnx dependency is not resolvable from Maven at all

`settings.gradle.kts` teaches Gradle to fetch it from an **ivy repository pointing at GitHub
release attachments**, because k2-fsa publishes no Maven artifact. A consumer cannot simply add a
coordinate — they must replicate that whole repository block in their own `settings.gradle.kts`,
including the `v[revision]` pattern quirk. Ship it verbatim in the integration doc:

```kotlin
ivy("https://github.com/k2-fsa/sherpa-onnx/releases/download") {
    patternLayout { artifact("v[revision]/[artifact]-[revision].[ext]") }
    metadataSources { artifact() }
    content { includeGroup("com.k2-fsa") }
}
```

Then the dependency itself, which needs the `@aar` suffix for the same reason:
`com.k2-fsa:sherpa-onnx-static-link-onnxruntime:1.13.4@aar`.

### The dependency list must be generated, not written by hand

A hand-maintained list in a README goes stale on the first version bump and the failure is a
runtime crash in someone else's app. Add a task to each publishable module and ship its output
beside the AAR:

```kotlin
tasks.register("printRuntimeDeps") {
    val coords = configurations.named("releaseRuntimeClasspath").map { conf ->
        conf.incoming.resolutionResult.allDependencies
            .mapNotNull { (it as? ResolvedDependencyResult)?.selected?.id }
            .filterIsInstance<ModuleComponentIdentifier>()
            .map { "${it.group}:${it.module}:${it.version}" }
            .distinct().sorted()
    }
    doLast { coords.get().forEach { println(it) } }
}
```

### Fewer artifacts is worth real money here

Every extra AAR is another file to hand over and another block for the consumer to declare. That
argues against a shared `:common` module — and happily, none is needed:

- `downloadToFile` and `ArchiveExtractor` are used **only** by `SpeechModelRepository` and
  `AudioModelRepository`. Both are STT. They go into `:stt-runtime`.
- `NetworkMonitor` is used only by `di/AppModule` and `functions/AppFunctionDeps`. It stays in `:app`.
- `ModelRepository` has its own OkHttp download path (`ModelRepository.kt:73,212`) and shares
  nothing with the STT downloader.

So the two halves are genuinely independent. Six artifacts total, no shared base.

---

## 4. Module shape

| Module | Published | Contents | Lines |
|---|---|---|---|
| `:stt-core` | ✅ | The pure pipeline over `FloatArray` and model `File`s. `Transcriber`/`OnnxTranscriber`/`StreamingTranscriber`, `FrameDiarizer`, `PyannoteSegmenter`, `SpeakerEmbedder`, `SpeakerClustering`, `SpeechActivityDetector`, `AudioSegmenter`, `SpeechRegions`, `KeywordDetector`, `Punctuator`, `AudioRecorder`, `WavFile`, `SpokenKeywords`, and the config types. **No Hilt, no WorkManager, no Room, no manifest permissions.** | ~7,700 |
| `:stt-runtime` | ✅ | The durable layer: 6 workers, 3 Room databases, checkpoints, orphan reconciliation, model download + archive extraction, `TranscriptionRun`, `SessionSpeakerTracker`, `DiarizedAudioStore`. Needs WorkManager and Room. | ~11,200 |
| `:stt-hilt` | ✅ | `@Module`/`@Binds` only. Exists so `:stt-core` and `:stt-runtime` never force Hilt on a consumer. | ~150 |
| `:engine-core` | ✅ | Contracts. Already library-shaped. Loses `ModelFormat.GGUF`. | ~1,900 |
| `:engine-litertlm` | ✅ | The only remaining backend. | ~700 |
| `:llm` | ✅ | `ModelResidency`, `ModelRepository`, `ModelDirectory`, `ModelLoadPlan`, `ChatLoadPlan`, `ModelDownloadWorker`, `ModelContextDefaults`, `CustomModelStore`, `HuggingFace*`, `functions/`. | ~5,000 |
| `:app` | ❌ | `ui/`, `di/`, `MainActivity`, `data/audit`, `data/chat`, `SettingsStore`, `NetworkMonitor`, `ModelCatalog`, and the two bridge classes. | ~26,000 |

Two placements worth arguing about:

- **`ModelCatalog` stays in `:app`.** The curated list of eleven models is this app's editorial
  judgement, not a library concern. `:llm` provides `ModelDirectory` and `CustomModelStore`; the
  host supplies the catalogue. A consumer with different models should not have to delete yours.
- **`data/audit` and `data/chat` stay in `:app`** as features that *consume* `:llm`, so `:llm`
  stays a capability rather than becoming a feature bag.

---

## 5. Three things must change before `:stt` can leave the building

None of this is a file move. It is the actual work.

### 5.1 `SettingsStore` cannot come along

A library must not read the host app's DataStore. `stt/` currently reads `SettingsStore` from five
files — `Punctuator`, `StreamingRecognizer`, `SpeechRecognizer`, `SpeechModelRepository`,
`SttLoadPlanner`. Replace it with an explicit config object owned by `:stt-core`:

```kotlin
// :stt-core -- public API
data class SttConfig(
    val sliceWindow: SliceWindow = SliceWindow.DEFAULT,
    val onnxProvider: OnnxProvider = OnnxProvider.DEFAULT,
    val diarizationEngine: DiarizationEngine = DiarizationEngine.DEFAULT,
    val platformFeedPace: PlatformFeedPace = PlatformFeedPace.DEFAULT,
    val platformFeedChunk: PlatformFeedChunk = PlatformFeedChunk.DEFAULT,
    val vadEnabled: Boolean = true,
    val keywordMarkersEnabled: Boolean = false,
    val threadCount: Int = 0,
    val diarizeChunkMinutes: Int = 5,
)

// :app -- one mapping function, one test
fun AppSettings.toSttConfig() = SttConfig(sliceWindow = sliceWindow, /* … */)
```

This has a pleasant side effect. `SliceWindow`, `OnnxProvider`, `DiarizationEngine`,
`PlatformFeedPace`, `PlatformFeedChunk` and `SttBackend` currently live under `data/` **only**
because of the layering rule in `CLAUDE.md` ("`data/` does not import `stt/`"). Once they are
`:stt-core`'s public config API and `AppSettings` merely holds them, that workaround has no reason
to exist. Delete the rule and record why it did.

### 5.2 De-Hilting is cheap, because the pipeline never had it

All 17 Hilt annotations sit in what becomes `:stt-runtime`. `:stt-core` is already clean.

| Class | Annotation | Fix |
|---|---|---|
| `NoteTranscribeWorker`, `DiarizeWorker`, `LiveDiarizeWorker`, `SpeechModelDownloadWorker`, `AudioModelDownloadWorker`, `BenchmarkWorker` | `@HiltWorker` | plain `(Context, WorkerParameters)` constructors + a library-supplied `WorkerFactory` |
| `TranscriptionRun`, `DiarizedAudioStore`, `TakeAudioReader`, `LiveSessionRosters`, `BenchmarkImporter` | `@Inject`/`@Singleton` | plain constructors; lifetime becomes the consumer's problem |
| `TakeAudioReader` | `@ApplicationContext` | take `Context` as an ordinary parameter |
| `SttLoadPlanner` | `@Inject`/`@Singleton` | moves to `:app` anyway — it is a bridge (§6) |

The worker factory is the one piece of real API design. An app may install exactly one
`WorkerFactory`, so the library must offer one the consumer can compose:

```kotlin
// :stt-runtime
class SttWorkerFactory(private val deps: SttDependencies) : WorkerFactory() { /* … */ }

// consumer, in Configuration.Provider
DelegatingWorkerFactory().apply { addFactory(SttWorkerFactory(deps)) }
```

`:stt-hilt` then provides the Hilt-flavoured binding so this app keeps working unchanged.

### 5.3 Storage roots must be given, not assumed

`stt/` and `data/` hardcode `filesDir` subdirectories — `speech`, `notes`, `audio-models`,
`models`, `speech-model-download`. A library writing into a consumer's `filesDir` under names it
chose is a collision waiting to happen. Take the root as a parameter:

```kotlin
class SttStorage(private val root: File) {
    val speechModels get() = File(root, "speech")
    val speakerModels get() = File(root, "audio-models/speaker")
    val notes get() = File(root, "notes")
}
```

> **This app must pass `context.filesDir` so every resolved path stays byte-identical.** The
> downloaded speech and speaker models live at those exact paths on real devices, and the
> transcription `.progress` sidecars are keyed to the audio beside them. A "tidier" root silently
> orphans every downloaded model and every resumable job on upgrade. Verify with a diff of resolved
> paths, not by reading the code.

### 5.4 Two smaller ones

- **Logging.** 43 `Log.` calls in `stt/` alone, with per-class tags. A consumer cannot silence them.
  Route through one `SttLog` object with a settable level — cheap now, impossible later.
- **`explicitApi()`.** Turn it on for every published module so the public surface is deliberate
  rather than whatever happened to be top-level. It will produce a long first list; that list *is*
  the API review.

### 5.5 Rename the namespace in the same pass

`com.example.aiagenttestapp.*` and `com.example.aiagent.engine.*` are sample namespaces. A
hand-over AAR will not be rejected for it the way Maven Central would, but it invites a collision
in the consumer's classpath and reads as unfinished.

Do it **during** the module moves, not after. Both operations rewrite every import line in the
files they touch, and the knowledge graph re-clusters on either — doing them together costs one
re-cluster and one review instead of two.

---

## 6. Three files hold speech to language

Voice notes can transcribe with the resident LLM (`SttBackend.GEMMA`), and that is the entire reason
`:stt` would otherwise need `:llm`. It is confined to:

| File | Why it reaches across |
|---|---|
| `stt/SttLoadPlanner.kt` | injects `ModelDirectory`, `ModelLoadPlanner`, `ModelRepository`, `ModelResidency` to decide whether the resident model can transcribe |
| `stt/GemmaTranscriber.kt` | `: Transcriber` — borrows the engine from `ModelResidency` rather than owning it |
| `data/notes/TranscriptionRun.kt:59–60` | the transcriber factory; its `SttBackend.GEMMA` branch calls `sttLoadPlanner.plan()` / `.open()` |

Invert it. `:stt-core` already owns `interface Transcriber`, so it only needs to state that
*something* may supply an LLM-backed one:

```kotlin
// :stt-core -- beside Transcriber
fun interface LlmTranscriberFactory {
    /** The resident model as a transcriber, or null when it cannot serve. */
    suspend fun open(preferredModelId: String?): Transcriber?
}

// :stt-runtime -- TranscriptionRun, unchanged in shape
SttBackend.GEMMA -> llmTranscribers.open(preferredModelId) ?: throw NoUsableModel()

// :app -- SttLoadPlanner and GemmaTranscriber move here and bind it
@Binds abstract fun llmTranscribers(impl: GemmaTranscriberFactory): LlmTranscriberFactory
```

`SttLoadPlanner` and `GemmaTranscriber` move up into `:app`, where both halves are visible.
`prompts/SttPrompts` (64 lines) goes with them. `TranscriptionRun` stays in `:stt-runtime` and loses
both LLM imports.

A consumer who never sets `LlmTranscriberFactory` simply has no Gemma backend — the other three
work untouched. That is the test of whether the seam is real.

---

## 7. Order of work

Each phase compiles and ships on its own.

### Phase 0 — Drop llama.cpp and GGUF

No module churn — sections 1 and 2, in one commit, mirroring `0fe20f3`. Independent of everything
else, so if the split stalls the engine decision has still landed.

> `./gradlew :app:compileDebugKotlin` · `:app:testDebugUnitTest`

### Phase 1 — Config objects, in place

`SttConfig` + `AppSettings.toSttConfig()` and `SttStorage`, with everything still inside `:app`.
Nothing moves; the five `SettingsStore` readers and the hardcoded paths change. Doing this before
any file move means the risky part (§5.3 path identity) is verifiable against an unchanged build.

> `./gradlew :app:testDebugUnitTest` · then diff resolved paths on device against a pre-change run

### Phase 2 — Extract `:llm`

`stt/` is still inside `:app` and may import `:llm` freely, so nothing breaks yet. This is the phase
that surfaces the AppFunctions KSP problem — risk 1.

> `adb shell cmd app_function list-app-functions`

### Phase 3 — Invert the bridge

`LlmTranscriberFactory`; `SttLoadPlanner`, `GemmaTranscriber` and `SttPrompts` move to `:app`.
Afterwards nothing under `stt/`, `data/notes` or `data/speakers` imports `:llm`.

> `grep -r "aiagenttestapp.llm" app/…/stt app/…/data/notes` — must be empty

### Phase 4 — Extract `:stt-core`, then `:stt-runtime`

Core first: it has no Hilt, no Room, no workers, so it moves almost mechanically. Then the runtime
layer, which is where de-Hilting (§5.2) and the worker factory actually happen. Rename the namespace
in these same commits (§5.5). Keep it to as few commits as possible — risk 6.

> `./gradlew test` · a real on-device recording · resume a killed transcription

### Phase 5 — Make the handover real

`:stt-hilt`, `printRuntimeDeps` on each published module, and `docs/integration.md` with the ivy
block and the generated coordinate list. Prove it by consuming the AARs from a scratch project that
has never seen this repo — that is the only honest test of a POM-less handover.

> build a throwaway app against the AARs; it must compile and transcribe a file

---

## 8. What will bite

**1. AppFunctions KSP aggregation fails silently** — *high*
`appfunctions:aggregateAppFunctions=true` builds one service from every `@AppFunction` in the build;
the comment in `app/build.gradle.kts` records that without it you get a per-module service and the
system discovers only one. Moving `functions/` into `:llm` puts that arg in a second module. The
failure is total and quiet — the app installs, the service is enabled, nothing errors, and
`list-app-functions` never names the package. Check on device at the end of phase 2.

**2. Storage paths must not move** — *high*
See §5.3. Changing a resolved path orphans downloaded models and every `.progress` checkpoint keyed
beside the audio. Nothing in the build will tell you; the symptom is a user re-downloading 2 GB and
losing a resumable job.

**3. Room schemas must travel with their databases** — *high*
`room.schemaLocation` is `$projectDir/schemas` in `:app`. Speaker, notes and benchmark go to
`:stt-runtime`; chat to `:llm`; audit stays. Move the exported JSON with each — do not let Room
regenerate it. Room compares an identity hash on first open, and `CLAUDE.md` is explicit that
migrations are appended, never rewritten.

**4. The consumer needs a manifest, and `:stt-core` must not give them one** — *medium*
`:stt-runtime`'s manifest contributes `RECORD_AUDIO`, `POST_NOTIFICATIONS`, the FGS permissions and
the `<service android:foregroundServiceType>` merge that `app/src/main/AndroidManifest.xml:57`
already documents. `:stt-core` must contribute **nothing** — `AudioRecorder` already documents
`RECORD_AUDIO` as the caller's job, so keep it that way and a consumer doing offline file
transcription declares no permissions at all.

**5. Native libraries and ABI** — *medium*
The sherpa AAR carries `.so`s for several ABIs; this app narrows to `arm64-v8a` with `abiFilters`,
which is an *application*-level setting. A consumer who does not set it gets a much larger APK.
`noCompress += "onnx"` and both `pickFirsts` entries also stay application-level and must be in the
integration doc, not silently assumed.

**6. `:app:testDebugUnitTest` stops meaning "all tests"** — *medium*
79 test files follow their subjects — 48 under `data/` split three ways, 15 under `stt/`, 10 across
`functions/` and `prompts/`. The command in `CLAUDE.md` becomes `./gradlew test`, and the one-class
form needs a module prefix. Update the docs in the same commit that splits the tests, or the next
session runs a suite that silently covers a third of the code.

**7. The knowledge graph re-clusters on a mass move** — *medium*
The post-commit hook rebuilds the structural half from what the commit touched. A move of this size
renumbers communities and forces ~370 labels to be reassigned by hand — `CLAUDE.md` warns about
exactly this. Batch the moves, combine them with the namespace rename (§5.5), and expect one
deliberate `/graphify` pass after phase 4 rather than several during it.

**8. Slice boundaries and checkpoint keys** — *low*
Nothing here changes how cuts are placed, and nothing should. If any phase touches
`AudioSegmenter`, `SpokenMarkers.slice` or `PipelinePlanner`, verify boundaries are byte-identical
before and after — they are the checkpoint's lookup key, and a change invalidates every resume.


---

## Applied: what actually happened, and where it diverged

Five modules now: `:app`, `:stt`, `:llm`, `:engine-core`, `:engine-litertlm`.

| Module | Files | Lines | Notes |
|---|---|---|---|
| `:app` | 169 | 42,203 | was 210 / 51,316 |
| `:stt` | 38 | 7,683 | no Hilt, no settings store, owns the sherpa + ORT deps and the VAD asset |
| `:llm` | 12 | 1,706 | no Hilt, no settings store, no catalogue |
| `:engine-core` | 13 | 1,746 | + `:stt` now depends on it, for `normalizeSpokenText` alone |
| `:engine-litertlm` | 3 | 795 | unchanged |

**821 tests pass** — 584 `:app`, 168 `:stt`, 63 `:engine-core`, 6 `:engine-litertlm`. The APK still
carries `assets/app_functions.xml` (risk 1) and `silero_vad.onnx` still packages `Stored`, 0%
compression, after moving into `:stt`'s assets (risk 4).

### Divergences worth knowing about

**`functions/` stayed in `:app`.** The plan put it in `:llm`, which would have walked straight into
risk 1 — `appfunctions:aggregateAppFunctions` in a second module, failing silently. It is also the
wrong home on merit: `@AppFunction`s are *this app's* capabilities exported to the system assistant,
not a reusable model-hosting concern. Risk 1 was avoided rather than managed.

**`NoEngine` was kept, not deleted.** §2 said to remove it with GGUF. It is still reachable:
`EngineRegistry.defaultFor` filters on `availability()`, so it fires when LiteRT-LM cannot run on the
device. The doc comments that blamed GGUF were rewritten instead, and the user-facing strings now say
"on this device" rather than "in this build".

**`AuditPromptProfile.RICH` was kept.** Nothing selects it now — `forEngine` returns `LEAN` always —
but `AuditQueue` sizes chunks against the *larger* preamble at enqueue, before the engine is known.
Deleting it would shrink that reservation, change chunk sizes, and chunk boundaries are what every
per-chunk checkpoint in `audit.db` is keyed to.

**`:stt` depends on `:engine-core`.** Not in the plan. `SpokenKeywords` and the voice-command matcher
must normalise spoken text identically or markers silently stop matching; one shared definition beat
a copied six-line function.

**The namespace was not renamed.** §5.5 argued for doing it in the same pass. It was not: keeping
`com.example.aiagenttestapp.stt` as the library's package meant the file moves needed **zero** import
rewrites across 169 app files, which is a large risk reduction for a cosmetic gain. It remains worth
doing — see `docs/integration.md`, "Known gaps".

**`:stt` is one module, not the three the layered plan called for.** `:stt-core` / `:stt-runtime` /
`:stt-hilt` needs `SpeechModelRepository` inverted first: the `Transcriber` implementations take the
repository, so splitting WorkManager out means giving them resolved `SpeechModelPaths` instead. The
Hilt split is already effectively free — there are no Hilt annotations left in `:stt` to isolate.

## Remaining

1. **The voice-note durable layer is still in `:app`** — `data/notes`, `data/speakers`,
   `data/benchmark`, `data/audiomodels`. Two things block it: five files still read `SettingsStore`
   (same fix as Phase 1), and `NoteChunking` imports `data/audit`'s `QuickRead` and `AuditChunker`,
   so notes cannot move without inverting that or moving audit with it.
2. **Room databases have not moved** (risk 3) — speaker, notes and benchmark would go to
   `:stt-runtime` with their schema JSON, which must travel rather than be regenerated.
3. **`explicitApi()`** on both published modules. The boundary already forced nine `internal` members
   public for the host to compile; that list should be a deliberate API, not an accident.
4. **The namespace rename**, per §5.5.
5. **`:llm` has no tests of its own** — `ModelLoadPlan` and `CustomModelStore` coverage still sits in
   `:app`'s suite and should follow its subject.
