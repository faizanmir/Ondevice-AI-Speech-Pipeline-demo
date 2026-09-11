plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * On-device LLM hosting: the model catalogue's plumbing, downloads, load planning and residency.
 *
 * Published as a hand-over AAR, which is why nothing here carries a Hilt annotation and nothing
 * reads the host's settings store. Configuration arrives as [LlmConfig], paths as [LlmPaths], and
 * the curated model list is passed to `ModelDirectory` rather than baked in -- see `:app`'s
 * `AppModule` for what wiring this costs a host that does use Hilt.
 *
 * There is no POM, so a consumer declares the dependencies below by hand. `./gradlew :llm:runtimeDeps`
 * prints the exact coordinates.
 */
android {
    namespace = "com.example.aiagent.llm"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 31
        consumerProguardFiles("consumer-rules.pro")
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
    api(project(":engine-core"))

    implementation(libs.androidx.core.ktx)
    api(libs.kotlinx.coroutines.android)
    api(libs.kotlinx.serialization.json)

    // Model downloads: resumable, survive process death, run in a foreground service.
    api(libs.androidx.work.runtime.ktx)
    // Paged HuggingFace browsing.
    api(libs.androidx.paging.runtime)
    api(libs.okhttp)

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
