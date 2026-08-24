import org.gradle.internal.os.OperatingSystem
import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
}

/**
 * llama.cpp has no official Maven artifact for Android, so we compile it from source with the NDK.
 * That is slow the first time, so it is behind a flag: with `enableLlamaCpp=false` the module still
 * compiles and [LlamaCppEngine] simply reports itself unavailable, which the UI renders as a
 * greyed-out engine rather than a crash.
 */
val llamaCppEnabled = providers.gradleProperty("enableLlamaCpp").orNull?.toBoolean() ?: true
val llamaCppAbis = (providers.gradleProperty("llamaCppAbiFilters").orNull ?: "arm64-v8a")
    .split(',')
    .map { it.trim() }
    .filter { it.isNotEmpty() }

// Default OFF. llama.cpp's Vulkan compute produced incorrect results on the Adreno GPU we tested --
// uniform token-salad garbage -- and, worse, its mere presence made even CPU runs offload the graph
// to that broken backend. CPU is correct and fast enough for the small GGUF models here, so
// llama.cpp ships CPU-only. The toolchain below stays behind this flag for anyone who wants to try
// Vulkan on a GPU with a trustworthy compute driver: build with -PenableLlamaCppVulkan=true.
val vulkanRequested = providers.gradleProperty("enableLlamaCppVulkan").orNull?.toBoolean() ?: false

/**
 * Everything CMake needs to build llama.cpp's Vulkan backend, or null when the machine is not
 * equipped to.
 *
 * Two prerequisites live on the *host*, not in the NDK:
 *
 *  - **glslc**, to compile ggml's GLSL compute shaders to SPIR-V. The NDK bundles one, but it is
 *    shaderc 2022.3 -- old enough that it rejects extensions llama.cpp's newer shaders use -- so a
 *    modern glslc is preferred and the NDK's is only a last resort.
 *  - **SPIRV-Headers**, which ggml-vulkan's CMake requires unconditionally.
 *
 * If either is missing we fall back to a CPU-only build rather than failing. Someone who clones
 * this repo without a Vulkan toolchain should still get a working app; they just do not get GPU
 * offload, and the build says so out loud.
 */
data class VulkanToolchain(
    val glslc: File,
    val spirvHeadersDir: File,
    val includeDir: File,
    val library: File,
)

/**
 * Pinned so Gradle and CMake cannot disagree.
 *
 * The Vulkan paths below are derived from the NDK's sysroot, and they have to come from the *same*
 * NDK that AGP hands to CMake -- otherwise we would be linking against one NDK's libvulkan stub
 * while compiling with another's headers. AGP 9 removed `android.ndkDirectory`, so rather than
 * guess at whichever NDK happens to be installed, the version is stated once, here, and used for
 * both.
 */
val pinnedNdkVersion = "28.2.13676358"

fun androidSdkDir(): File? {
    val fromProperties = rootProject.file("local.properties")
        .takeIf { it.exists() }
        ?.let { file ->
            val props = Properties()
            file.inputStream().use { props.load(it) }
            props.getProperty("sdk.dir")
        }
    val path = fromProperties
        ?: System.getenv("ANDROID_HOME")
        ?: System.getenv("ANDROID_SDK_ROOT")
    return path?.let(::File)?.takeIf { it.exists() }
}

fun resolveVulkanToolchain(minSdk: Int): VulkanToolchain? {
    if (!vulkanRequested || !llamaCppEnabled) return null

    val sdkDir = androidSdkDir() ?: return null
    val ndkDir = sdkDir.resolve("ndk/$pinnedNdkVersion")
    if (!ndkDir.exists()) {
        logger.warn("llama.cpp Vulkan: NDK $pinnedNdkVersion not installed -- building CPU-only")
        return null
    }

    val hostTag = when {
        OperatingSystem.current().isMacOsX -> "darwin-x86_64"
        OperatingSystem.current().isLinux -> "linux-x86_64"
        else -> "windows-x86_64"
    }
    val sysroot = ndkDir.resolve("toolchains/llvm/prebuilt/$hostTag/sysroot")

    // We link against the NDK's libvulkan.so -- Android's Vulkan loader ships on every device from
    // API 24, so nothing extra goes into the APK.
    val library = sysroot.resolve("usr/lib/aarch64-linux-android/$minSdk/libvulkan.so")
    if (!library.exists()) {
        logger.warn("llama.cpp Vulkan: NDK has no libvulkan.so for API $minSdk -- building CPU-only")
        return null
    }

    // The *headers*, though, cannot come from the NDK: ggml-vulkan.cpp is written against
    // `vulkan/vulkan.hpp`, the C++ bindings, and the NDK ships only the C header. So we take the
    // Khronos headers from the host.
    //
    // Mixing a newer header set with the NDK's older loader stub is safe here specifically because
    // ggml-vulkan compiles with VULKAN_HPP_DISPATCH_LOADER_DYNAMIC: every Vulkan entry point is
    // resolved at run time through vkGetInstanceProcAddr, so anything the device's driver does not
    // implement is simply never called, rather than being a missing link-time symbol.
    val includeDir = listOfNotNull(
        providers.gradleProperty("vulkanIncludeDir").orNull?.let(::File),
        System.getenv("VULKAN_SDK")?.let { File(it).resolve("include") },
        File("/opt/homebrew/include"),
        File("/usr/local/include"),
        File("/usr/include"),
    ).firstOrNull {
        it.resolve("vulkan/vulkan.hpp").exists() && it.resolve("vulkan/vulkan.h").exists()
    }

    if (includeDir == null) {
        logger.warn(
            "llama.cpp Vulkan: vulkan.hpp not found (the NDK ships only the C header) -- " +
                "building CPU-only. Install with `brew install vulkan-headers`, or set " +
                "-PvulkanIncludeDir=<dir containing vulkan/vulkan.hpp>.",
        )
        return null
    }

    val glslc = listOfNotNull(
        providers.gradleProperty("glslcPath").orNull?.let(::File),
        System.getenv("VULKAN_SDK")?.let { File(it).resolve("bin/glslc") },
        File("/opt/homebrew/bin/glslc"),
        File("/usr/local/bin/glslc"),
        File("/usr/bin/glslc"),
        // Last resort. Works, but it is an old shaderc and will silently disable some shader
        // extensions, costing throughput.
        ndkDir.resolve("shader-tools/$hostTag/glslc"),
    ).firstOrNull { it.exists() }

    if (glslc == null) {
        logger.warn(
            "llama.cpp Vulkan: no glslc found -- building CPU-only. " +
                "Install one with `brew install shaderc`, or set -PglslcPath=/path/to/glslc.",
        )
        return null
    }

    val spirvHeadersDir = listOfNotNull(
        providers.gradleProperty("spirvHeadersDir").orNull?.let(::File),
        System.getenv("VULKAN_SDK")?.let { File(it).resolve("share/cmake/SPIRV-Headers") },
        File("/opt/homebrew/share/cmake/SPIRV-Headers"),
        File("/usr/local/share/cmake/SPIRV-Headers"),
        File("/usr/share/cmake/SPIRV-Headers"),
    ).firstOrNull { it.resolve("SPIRV-HeadersConfig.cmake").exists() }

    if (spirvHeadersDir == null) {
        logger.warn(
            "llama.cpp Vulkan: SPIRV-Headers not found -- building CPU-only. " +
                "Install with `brew install spirv-headers`, or set -PspirvHeadersDir=<dir with " +
                "SPIRV-HeadersConfig.cmake>.",
        )
        return null
    }

    return VulkanToolchain(glslc, spirvHeadersDir, includeDir, library)
}

val minSdkVersion = 31
val vulkan = resolveVulkanToolchain(minSdkVersion)

if (llamaCppEnabled) {
    if (vulkan != null) {
        logger.lifecycle("llama.cpp: Vulkan GPU backend ON (glslc=${vulkan.glslc})")
    } else if (vulkanRequested) {
        logger.lifecycle("llama.cpp: Vulkan GPU backend OFF -- CPU only")
    }
}

android {
    namespace = "com.example.aiagent.engine.llamacpp"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = minSdkVersion

        // Lets the Kotlin side tell "llama.cpp was compiled out of this build" apart from
        // "the .so failed to load", and report the difference honestly instead of as one error.
        buildConfigField("boolean", "LLAMA_CPP_ENABLED", llamaCppEnabled.toString())
        // Whether the *binary* has a GPU backend at all. Whether a usable GPU is actually present
        // is a separate, runtime question -- see LlamaNative.gpuDeviceName().
        buildConfigField("boolean", "VULKAN_COMPILED_IN", (vulkan != null).toString())

        if (llamaCppEnabled) {
            externalNativeBuild {
                cmake {
                    // -O3 for ggml's hot loops; without it token throughput drops several-fold.
                    cppFlags += listOf("-O3", "-fexceptions", "-frtti")
                    arguments += listOf(
                        "-DANDROID_STL=c++_shared",
                        "-DCMAKE_BUILD_TYPE=Release",
                    )

                    if (vulkan != null) {
                        arguments += listOf(
                            "-DLLAMAJNI_VULKAN=ON",
                            // find_package(Vulkan) would otherwise hunt for a *host* glslc inside
                            // the Android sysroot and fail, so every path is pinned explicitly.
                            "-DVulkan_GLSLC_EXECUTABLE=${vulkan.glslc.absolutePath}",
                            "-DVulkan_INCLUDE_DIR=${vulkan.includeDir.absolutePath}",
                            "-DVulkan_LIBRARY=${vulkan.library.absolutePath}",
                            "-DSPIRV-Headers_DIR=${vulkan.spirvHeadersDir.absolutePath}",
                        )
                    }
                }
            }
            ndk {
                abiFilters += llamaCppAbis
            }
        }
    }

    if (llamaCppEnabled) {
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

    testImplementation(libs.junit)
}
