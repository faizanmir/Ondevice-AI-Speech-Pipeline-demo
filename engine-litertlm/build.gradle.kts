plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.example.aiagent.engine.litertlm"
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

    // LiteRT-LM. Successor to MediaPipe's LlmInference, which is @Deprecated in source and in
    // maintenance-only mode. Google Maven only -- not on Maven Central.
    implementation(libs.litertlm.android)

    testImplementation(libs.junit)
}
