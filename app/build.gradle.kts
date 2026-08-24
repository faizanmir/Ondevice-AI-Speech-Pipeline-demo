plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

/**
 * AppFunctions' compiler generates one aggregated service from every @AppFunction in the build.
 * Without this it produces a per-module service and the system only ever discovers one of them.
 */
ksp {
    arg("appfunctions:aggregateAppFunctions", "true")

    // Export Room's schema JSON. Hand-written migrations have to reproduce Room's generated DDL
    // *exactly* -- it compares an identity hash on first open and throws if they differ -- so having
    // the real schema checked in is the difference between verifying a migration and guessing at it.
    arg("room.schemaLocation", "$projectDir/schemas")
}

/**
 * Ship the AppFunctions metadata that KSP generates.
 *
 * The AppFunctions compiler writes `app_functions.xml` -- the manifest of what this app exposes --
 * into KSP's *resources* output directory, under an `assets/` path, and the merged manifest points
 * the system at it as an asset. The failure mode if it does not ship is silent and total: the app
 * installs, the service is enabled, nothing errors, and
 * `adb shell cmd app_function list-app-functions` simply never lists this package.
 *
 * It used to be copied in by hand, by registering KSP's output as an extra `assets.srcDir`. That is
 * no longer needed *and* actively breaks packaging: with the Hilt plugin's ASM transform in the
 * pipeline, KSP's resources output also reaches `processDebugJavaRes`, which packages it at
 * `assets/app_functions.xml` on its own. Both routes then write the same entry and `packageDebug`
 * fails with "already contains entry 'assets/app_functions.xml'".
 *
 * So the copy is left to the resources path, which delivers it unaided -- verified by unzipping the
 * APK and confirming `assets/app_functions.xml` is present.
 */
tasks.configureEach {
    // dependsOn(String), not tasks.named(...): KSP registers its tasks after this block is
    // configured, so resolving them eagerly throws UnknownTaskException. A name is resolved when
    // the task graph is built, by which point KSP has registered.
    //
    // Kept even though the asset now travels the resources path: the merged *manifest* still names
    // the file, and ordering the asset merge after KSP costs nothing.
    when (name) {
        "mergeDebugAssets" -> dependsOn("kspDebugKotlin")
        "mergeReleaseAssets" -> dependsOn("kspReleaseKotlin")
    }
}

android {
    namespace = "com.example.aiagenttestapp"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.aiagenttestapp"
        // 31, not 24. Below API 31 there is no Build.SOC_MODEL to identify the chipset, LiteRT-LM's
        // accelerated backends are not dependable, and no phone of that vintage has the RAM to run
        // anything in the catalogue anyway -- so supporting it would only promise what the hardware
        // cannot deliver.
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // arm64 only. Every phone that can run an LLM is 64-bit ARM; other ABIs would just inflate
        // the APK with native code that can never be loaded.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }

    // No `assets.srcDir` for KSP's output here -- see the comment on tasks.configureEach above.
    // Registering it duplicates an entry that the Java-resources path already packages, and the
    // duplicate is a hard packaging failure rather than a warning.

    androidResources {
        // The bundled Silero VAD model is read straight out of the APK by sherpa-onnx's native
        // loader. Quantised ONNX weights barely compress anyway, so storing it uncompressed costs
        // almost nothing and removes any question of how the native side handles a deflated asset.
        noCompress += "onnx"
    }

    packaging {
        // Both engines ship their own libc++_shared.so. Without this, merging them into one APK
        // fails with a duplicate-file error.
        jniLibs {
            pickFirsts += "**/libc++_shared.so"
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    // Constructor injection into WorkManager workers, and hiltViewModel() from Compose.
    implementation(libs.hilt.work)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.androidx.hilt.compiler)

    implementation(project(":engine-core"))
    implementation(project(":engine-litertlm"))
    implementation(project(":engine-llamacpp"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    // Two-pane Settings. Brought in exactly where ui/components/Adaptive.kt said it should be: the
    // constraint-based helpers there cover sizing, and a real list-detail layout is the one thing
    // they cannot express -- it needs to know whether both panes fit, not just how wide one is.
    implementation(libs.androidx.compose.material3.adaptive)
    implementation(libs.androidx.compose.material3.adaptive.layout)
    implementation(libs.androidx.compose.material3.adaptive.navigation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)
    implementation(libs.pdfbox.android)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)

    // Persistent, resumable model downloads: they survive the app being backgrounded or killed,
    // run in a foreground service, and can be cancelled.
    implementation(libs.androidx.work.runtime.ktx)

    // App Startup: warms the default model into memory at launch (see ModelPreloadInitializer).
    implementation(libs.androidx.startup)

    // Renders the model's Markdown output (links, tables, code, lists) as native Compose.
    implementation(libs.markdown.renderer)
    implementation(libs.markdown.renderer.m3)

    // Exports this app's capabilities to the *system* assistant (Gemini). This is the opposite
    // direction from the in-app tool calling: here the app is the provider and a privileged agent
    // is the caller. Our own model cannot call these -- EXECUTE_APP_FUNCTIONS is protectionLevel
    // internal|role, granted only to the app holding the system ASSISTANT role.
    implementation(libs.androidx.appfunctions)
    implementation(libs.androidx.appfunctions.service)
    ksp(libs.androidx.appfunctions.compiler)

    // Speech-to-text, running entirely on-device like everything else here.
    //
    // Declared inline rather than through the version catalogue because it needs the `@aar`
    // suffix: the file on the GitHub release page carries no Gradle metadata, so the extension
    // has to be stated or Gradle looks for a .jar and fails. The static-link build bundles
    // onnxruntime into a single .so instead of shipping it alongside.
    implementation(
        "com.k2-fsa:sherpa-onnx-static-link-onnxruntime:" +
            "${libs.versions.sherpaOnnx.get()}@aar",
    )

    // bzip2 + tar. Needed for exactly one thing: sherpa-onnx publishes its keyword-spotting models
    // only as .tar.bz2 attachments on GitHub releases -- no per-file mirror exists -- and the Android
    // runtime ships neither codec. Every other model this app downloads is a plain file.
    implementation(libs.commons.compress)

    // Saved notes.
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    // Drives the MVI base class's coroutines deterministically -- see MviViewModelTest.
    testImplementation(libs.kotlinx.coroutines.test)
    // The android.jar used by unit tests stubs org.json to throw; the real library shadows it so
    // TranscriptionCheckpoint's sidecar JSON is testable on the JVM -- see TranscriptionCheckpointTest.
    testImplementation(libs.json)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
