plugins {
    alias(libs.plugins.android.library)
}

/**
 * On-device speech: recognition, voice-activity detection, slicing, diarisation and speaker
 * identification.
 *
 * Published as a hand-over AAR. Nothing here carries a Hilt annotation and nothing reads the host's
 * settings store -- configuration arrives as [SttConfig], storage roots as [SttStorage], and the
 * one place speech meets a language model is the [LlmTranscriberFactory] interface, which a host
 * may simply not bind.
 *
 * Two things a consumer must know, because there is no POM to tell them:
 *
 *  - **sherpa-onnx is not on Maven.** It is resolved from an ivy repository pointing at GitHub
 *    release attachments, declared in this project's `settings.gradle.kts`. That block has to be
 *    copied into the consumer's own settings file; a coordinate alone will not resolve.
 *  - **The manifest contributes RECORD_AUDIO and the foreground-service permissions** through
 *    manifest merging. `AudioRecorder` documents RECORD_AUDIO as the caller's to hold, so a
 *    consumer that only transcribes files never asks the user for a microphone.
 *
 * `./gradlew :stt:runtimeDeps` prints the exact coordinates to declare.
 */
android {
    namespace = "com.example.aiagenttestapp.stt"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 31
    }

    androidResources {
        // The bundled Silero VAD model is read straight out of the APK by sherpa-onnx's native
        // loader. Quantised ONNX weights barely compress anyway, so storing it uncompressed costs
        // almost nothing and removes any question of how the native side handles a deflated asset.
        //
        // Declared here as well as in the app: an AAR's assets merge into the host, but packaging
        // options are an *application*-level setting, so a consumer must repeat the noCompress line
        // in their own build file. It is in the integration notes for that reason.
        noCompress += "onnx"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    publishing {
        singleVariant("release") { withSourcesJar() }
    }
}

dependencies {
    // For `normalizeSpokenText` alone. Spoken markers and the voice-command matcher both match
    // against normalised text, and the two must agree exactly -- a marker phrase that normalises
    // one way here and another way there simply stops being recognised, silently. One definition
    // is worth the dependency; a copied six-line function is not.
    api(project(":engine-core"))

    implementation(libs.androidx.core.ktx)
    api(libs.kotlinx.coroutines.android)

    // Durable, resumable speech-model downloads.
    api(libs.androidx.work.runtime.ktx)
    implementation(libs.okhttp)

    // Speech-to-text, running entirely on-device. Declared inline rather than through the version
    // catalogue because it needs the `@aar` suffix: the file on the GitHub release page carries no
    // Gradle metadata, so the extension has to be stated or Gradle looks for a .jar and fails.
    api(
        "com.k2-fsa:sherpa-onnx-static-link-onnxruntime:" +
            "${libs.versions.sherpaOnnx.get()}@aar",
    )

    // The ONNX Runtime Java API. sherpa's static-link build keeps its ORT inside its own .so and
    // exposes no Java API for it, so reaching a model directly means shipping ORT a second time.
    // PyannoteSegmenter is the only caller: it needs the segmentation model's per-frame posteriors,
    // which OfflineSpeakerDiarization computes internally and throws away.
    api(libs.onnxruntime.android)

    // bzip2 + tar. sherpa-onnx publishes its keyword-spotting models only as .tar.bz2 attachments
    // on GitHub releases, and the Android runtime ships neither codec.
    implementation(libs.commons.compress)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

/**
 * Prints the exact coordinates a consumer must declare by hand.
 *
 * There is no POM in a hand-over AAR, so nothing resolves transitively: a missing dependency is not
 * a build failure in the consumer's project, it is a NoClassDefFoundError in their users' hands. A
 * list typed into a README goes stale on the first version bump and fails in exactly that way, so it
 * is generated from the real resolved classpath instead. Ship its output beside the .aar.
 */
tasks.register("runtimeDeps") {
    group = "publishing"
    description = "Prints the runtime dependencies a consumer of this AAR must declare by hand."
    val coords = configurations.named("releaseRuntimeClasspath").map { conf ->
        conf.incoming.resolutionResult.allDependencies
            .mapNotNull { (it as? ResolvedDependencyResult)?.selected?.id }
            .filterIsInstance<ModuleComponentIdentifier>()
            .map { "${it.group}:${it.module}:${it.version}" }
            .distinct()
            .sorted()
    }
    doLast {
        println("// Declare these in the consuming project:")
        coords.get().forEach { println("implementation(\"" + it + "\")") }
    }
}
