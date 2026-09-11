# Consuming `:stt` and `:llm` as AARs

These two modules are built to be handed over as `.aar` files. **There is no POM**, so nothing
resolves transitively — a consumer declares every dependency by hand, and a missing one is not a
build failure in their project but a `NoClassDefFoundError` in their users' hands.

```sh
./gradlew :stt:assembleRelease :llm:assembleRelease
# -> stt/build/outputs/aar/stt-release.aar
# -> llm/build/outputs/aar/llm-release.aar
```

Both modules also produce a sources jar (`withSourcesJar()`), which is worth shipping: without a
POM there is no javadoc either, and the KDoc in this codebase carries most of what a caller needs.

---

## 1. sherpa-onnx is not on Maven at all

This is the sharpest edge and the one that will waste someone's afternoon. k2-fsa publishes no Maven
artifact — only prebuilt AARs attached to GitHub releases. This project teaches Gradle to fetch them
as a normal dependency, and **that block has to be copied into the consumer's own
`settings.gradle.kts`**. A coordinate alone will not resolve.

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()

        // The release tag is `v1.13.4` while the file inside is `...-1.13.4.aar`, hence the
        // literal "v" in the pattern.
        ivy("https://github.com/k2-fsa/sherpa-onnx/releases/download") {
            patternLayout { artifact("v[revision]/[artifact]-[revision].[ext]") }
            metadataSources { artifact() }
            content { includeGroup("com.k2-fsa") }
        }
    }
}
```

The dependency then needs the `@aar` suffix, because the file carries no Gradle metadata and Gradle
would otherwise look for a `.jar`:

```kotlin
implementation("com.k2-fsa:sherpa-onnx-static-link-onnxruntime:1.13.4@aar")
```

## 2. Application-level settings the AAR cannot carry

Packaging options are an *application* setting. An AAR cannot contribute them, so these must be
repeated in the consuming app's `build.gradle.kts` or the build fails at packaging time:

```kotlin
android {
    androidResources {
        // The bundled Silero VAD model is read out of the APK by sherpa's native loader. Without
        // this it is deflated and the native side cannot open it.
        noCompress += "onnx"
    }
    packaging {
        jniLibs {
            // LiteRT-LM and sherpa each ship their own libc++_shared.so.
            pickFirsts += "**/libc++_shared.so"
            // sherpa's static-link AAR is static for every ABI except x86, where it ships a
            // standalone libonnxruntime.so alongside onnxruntime-android's.
            pickFirsts += "lib/x86/libonnxruntime.so"
        }
    }
    defaultConfig {
        // sherpa's AAR carries several ABIs. Narrow, or the APK inflates with native code that can
        // never load on the target device.
        ndk { abiFilters += listOf("arm64-v8a") }
    }
}
```

## 3. Permissions the manifest merges in

`:stt`'s manifest contributes `RECORD_AUDIO`, `POST_NOTIFICATIONS` and the foreground-service
permissions to the host through manifest merging.

`AudioRecorder` documents `RECORD_AUDIO` as **the caller's to hold and request** — it is not asked
for on the library's behalf. A consumer that only transcribes files therefore never prompts anyone
for a microphone.

## 4. WorkManager: one factory, composed

Both libraries own `CoroutineWorker`s with constructor dependencies, so WorkManager needs a factory
to build them. Neither uses `@HiltWorker`: that would make Hilt a requirement of *using* the
library. Each ships a plain `WorkerFactory` instead.

An app installs exactly one factory, so compose them:

```kotlin
override val workManagerConfiguration: Configuration
    get() = Configuration.Builder()
        .setWorkerFactory(
            DelegatingWorkerFactory().apply {
                addFactory(LlmWorkerFactory(modelDirectory, modelRepository))
                addFactory(SttWorkerFactory(speechModelRepository))
                // …and whatever the host already had, Hilt's included.
            },
        )
        .build()
```

Returning null for an unrecognised class name is the contract, not a failure: it is how a delegating
factory knows to try the next one.

### Resolve the factories lazily, not in a field

This will bite you if you inject them eagerly. `SpeechModelRepository` and `ModelRepository` both call
`WorkManager.getInstance()` from a property initialiser. On a cold start that is the call which
*first* initialises WorkManager, so it reads your `workManagerConfiguration` back -- while you are
still constructing the very factory it is asking for. With a DI framework doing field injection, the
later fields are not assigned yet:

```
java.lang.RuntimeException: Unable to create application …
Caused by: kotlin.UninitializedPropertyAccessException:
    lateinit property llmWorkerFactory has not been initialized
```

The app never reaches its first screen. Build the factory without touching your object graph, and
resolve the delegates on first `createWorker` -- which only happens when WorkManager actually
instantiates a worker, long after startup:

```kotlin
private class DeferredWorkerFactory(private val app: Application) : WorkerFactory() {
    private val delegate by lazy { /* resolve LlmWorkerFactory / SttWorkerFactory here */ }

    override fun createWorker(c: Context, name: String, params: WorkerParameters) =
        delegate.createWorker(c, name, params)
}
```

`AIAgentApplication` in this repo is a working example.

## 5. Configuration and storage are yours to supply

Neither library reads a settings store, and neither decides where files go.

```kotlin
// Speech. Every field has a default; SttConfig.DEFAULT is this app's shipped behaviour.
val config = MutableStateFlow(SttConfig(onnxProvider = OnnxProvider.XNNPACK, vadEnabled = true))

// Storage roots. The library never assumes it owns filesDir.
val storage = SttStorage(context.filesDir)

// Model hosting.
val llm = LlmConfigSource { LlmConfig(preferredAccelerator = Accelerator.GPU) }
val paths = LlmPaths(cacheDir = context.cacheDir.absolutePath, nativeLibraryDir = nativeLibDir)
```

`ModelDirectory` takes the curated model list as a parameter rather than baking one in — the
catalogue in this app is editorial, and a consumer should not have to delete someone else's models.
An empty list is a fine answer.

## 6. The LLM-backed transcriber is optional

Voice notes can transcribe with a resident multimodal LLM. That is the only place speech meets
language, and it is behind one interface:

```kotlin
fun interface LlmTranscriberFactory {
    suspend fun open(preferredModelId: String?): Transcriber?
    suspend fun releaseIfIdle() = Unit
}
```

**A consumer that never binds it simply has no LLM backend.** The sherpa, streaming and platform
backends work untouched, and `:stt` needs nothing from `:llm`. That is the test of whether the split
is real, and it is the reason `:stt` can be handed over on its own.

## 7. The dependency lists

Generated from the real resolved classpath, because a hand-maintained list goes stale on the first
version bump and fails as a runtime crash:

```sh
./gradlew :stt:runtimeDeps
./gradlew :llm:runtimeDeps
```

Ship the output beside each `.aar`. As of this writing `:stt` resolves 60-odd coordinates and `:llm`
around 50; the overlap (androidx core, lifecycle, WorkManager, coroutines) is large, so a consumer
taking both declares rather fewer than the sum.

---

## Known gaps

- **The namespace is still `com.example.*`.** Legal for a hand-over AAR, unlike Maven Central, but a
  collision risk in a consumer's classpath and it reads as unfinished. Renaming touches every import
  in both modules, so it is worth doing in the same pass as any other mass move — see
  `docs/modularization-plan.md` §5.5.
- **`explicitApi()` is not on yet.** The module boundary has already forced one API review (nine
  `internal` members had to be promoted for the host to compile), but the public surface is still
  whatever happened to be top-level rather than a deliberate list.
- **The voice-note pipeline is still in `:app`.** `data/notes`, `data/speakers`, `data/benchmark`
  and `data/audiomodels` — the durable layer with the workers, the Room checkpoints and the orphan
  reconciliation — have not moved yet. `:stt` today is the pipeline itself, which is the reusable
  half; see the plan's Phase 4 notes for what the rest needs.
