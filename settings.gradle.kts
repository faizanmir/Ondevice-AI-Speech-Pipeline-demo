pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()

        // sherpa-onnx publishes no Maven artifact -- only prebuilt AARs attached to its GitHub
        // releases. Rather than commit a 37 MB binary into the repo, we teach Gradle to resolve it
        // as a normal dependency straight from the release page. It gets cached, versioned and
        // verified like anything else, and `git clone` stays small.
        //
        // The release tag is `v1.13.4` while the file inside is `...-1.13.4.aar`, hence the
        // literal "v" in the pattern.
        ivy("https://github.com/k2-fsa/sherpa-onnx/releases/download") {
            patternLayout {
                artifact("v[revision]/[artifact]-[revision].[ext]")
            }
            metadataSources { artifact() }
            content { includeGroup("com.k2-fsa") }
        }
    }
}

rootProject.name = "AI Agent Test App"
include(":app")
include(":engine-core")
include(":engine-aicore")
include(":engine-litertlm")
include(":engine-llamacpp")
include(":engine-mnn")
