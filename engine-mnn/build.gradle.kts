plugins {
    alias(libs.plugins.android.library)
}

/**
 * MNN, like llama.cpp, has no official Maven artifact for its Android LLM runtime, so it is
 * compiled from source with the NDK (see src/main/cpp/CMakeLists.txt). The first build is slow --
 * MNN is a large codebase -- so it sits behind the same kind of flag: with `enableMnn=false` the
 * module still compiles and [MnnEngine] reports itself unavailable, which the UI renders as a
 * greyed-out engine rather than a crash.
 */
val mnnEnabled = providers.gradleProperty("enableMnn").orNull?.toBoolean() ?: true
val mnnAbis = (providers.gradleProperty("mnnAbiFilters").orNull ?: "arm64-v8a")
    .split(',')
    .map { it.trim() }
    .filter { it.isNotEmpty() }

android {
    namespace = "com.example.aiagent.engine.mnn"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 31

        // Lets the Kotlin side tell "MNN was compiled out of this build" apart from "the .so
        // failed to load", and report the difference honestly instead of as one error.
        buildConfigField("boolean", "MNN_ENABLED", mnnEnabled.toString())

        if (mnnEnabled) {
            externalNativeBuild {
                cmake {
                    // -O2/-O3 matter for MNN's CPU kernels; MNN sets most of its own flags, these
                    // apply to the JNI translation unit.
                    cppFlags += listOf("-O3")
                    arguments += listOf(
                        "-DANDROID_STL=c++_shared",
                        "-DCMAKE_BUILD_TYPE=Release",
                    )
                }
            }
            ndk {
                abiFilters += mnnAbis
            }
        }
    }

    if (mnnEnabled) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    api(project(":engine-core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
}
