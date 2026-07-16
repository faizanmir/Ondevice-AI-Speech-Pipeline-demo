plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.example.aiagent.engine.aicore"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 31
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

    // ML Kit GenAI Prompt API -- Gemini Nano via the AICore system service. Pure Kotlin/AIDL
    // client, no native code of its own; the model runs in AICore's process, not ours.
    implementation(libs.mlkit.genai.prompt)
}
