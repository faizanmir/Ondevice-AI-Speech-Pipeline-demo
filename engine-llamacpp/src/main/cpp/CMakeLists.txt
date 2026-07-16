cmake_minimum_required(VERSION 3.22.1)
project("llamajni" CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# llama.cpp is pinned to a release tag, not a branch. Its C API moves fast enough that an unpinned
# build would silently break llama_jni.cpp on some future sync; bumping this is a deliberate act
# that should come with re-reading include/llama.h.
set(LLAMA_CPP_TAG b9999)

# Trim the build to the library. Without these, CMake also configures the CLI tools, the server
# (which wants libcurl, absent from the NDK) and the test suite -- many minutes of build time for
# artifacts an app never loads.
set(LLAMA_BUILD_COMMON   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL           OFF CACHE BOOL "" FORCE)

# The NDK ships no libomp, so OpenMP must be off or ggml fails to link.
set(GGML_OPENMP          OFF CACHE BOOL "" FORCE)
# llamafile's sgemm kernels are tuned for desktop and have miscompiled on some NDK toolchains.
# ggml's own ARM kernels are what actually run on phones.
set(GGML_LLAMAFILE       OFF CACHE BOOL "" FORCE)

# Runtime weight repacking (Q4_0/Q4_K -> interleaved layouts) is left off. It was first suspected of
# the garbage-output bug, but the real cause was the Vulkan backend (now built out); repack is kept
# off out of caution, because this NDK cross-build only produces the baseline armv8-a CPU variant
# (no DOTPROD/I8MM -- see the ggml sysinfo line), which is the least-exercised repack path. The plain
# quant kernels it falls back to are correct everywhere. Re-enabling it is a possible future CPU
# speed win, worth verifying on-device before it ships on.
set(GGML_CPU_REPACK      OFF CACHE BOOL "" FORCE)

# ---------------------------------------------------------------------------------------------
# Vulkan: llama.cpp's GPU backend on Android.
#
# Gradle turns this on (and supplies the paths below) only when it can find the two host-side
# prerequisites -- a glslc shader compiler and SPIRV-Headers. If it cannot, LLAMAJNI_VULKAN stays
# off and we build a CPU-only library rather than failing, so a checkout without those tools still
# produces a working app.
#
# Android's Vulkan loader (libvulkan.so) ships with every device from API 24, so nothing extra has
# to be bundled into the APK -- but whether a *usable* GPU driver is behind it is a runtime
# question, which is why llama_jni.cpp probes for a real device rather than assuming one.
# ---------------------------------------------------------------------------------------------
if (LLAMAJNI_VULKAN)
    message(STATUS "llamajni: Vulkan backend ENABLED")

    set(GGML_VULKAN ON CACHE BOOL "" FORCE)

    # vulkan-shaders-gen is a build-time tool that must run on the developer's machine, so it needs
    # a host compiler and -- crucially -- a host build tool. See host-toolchain.cmake.in.
    if (CMAKE_CROSSCOMPILING)
        # Search ONLY the user's PATH.
        #
        # The Android NDK toolchain injects its own bin/ into CMake's system program path, so a
        # plain find_program(clang) hands back the *NDK's* clang -- a cross-compiler for Android,
        # several LLVM releases behind the host's. Building a macOS binary with it fails deep inside
        # the system C++ headers ("use of undeclared identifier '__builtin_ctzg'"), because the
        # macOS SDK expects a newer clang than the NDK ships. NO_CMAKE_SYSTEM_PATH is the flag that
        # actually excludes the NDK; the others keep any other CMake-injected hints out too.
        find_program(HOST_C_COMPILER NAMES clang gcc cc
                NO_CMAKE_PATH NO_CMAKE_ENVIRONMENT_PATH NO_CMAKE_SYSTEM_PATH NO_CMAKE_FIND_ROOT_PATH
                REQUIRED)
        find_program(HOST_CXX_COMPILER NAMES clang++ g++ c++
                NO_CMAKE_PATH NO_CMAKE_ENVIRONMENT_PATH NO_CMAKE_SYSTEM_PATH NO_CMAKE_FIND_ROOT_PATH
                REQUIRED)
        message(STATUS "llamajni: host compiler for vulkan-shaders-gen: ${HOST_CXX_COMPILER}")

        configure_file(
                "${CMAKE_CURRENT_SOURCE_DIR}/cmake/host-toolchain.cmake.in"
                "${CMAKE_BINARY_DIR}/llamajni-host-toolchain.cmake"
                @ONLY)

        set(GGML_VULKAN_SHADERS_GEN_TOOLCHAIN
                "${CMAKE_BINARY_DIR}/llamajni-host-toolchain.cmake"
                CACHE FILEPATH "" FORCE)
    endif()
else()
    message(STATUS "llamajni: Vulkan backend disabled -- building CPU-only")
endif()

# Prefer a vendored checkout. Gradle configures a separate CMake build directory per ABI and per
# build type, and FetchContent clones into each one -- so a from-scratch fetch would re-download
# llama.cpp several times over. Vendoring it once under third_party/ makes the build fast,
# offline-capable, and reproducible; the network fetch stays only as a fallback for a fresh
# checkout that has not run tools/fetch_llama_cpp.sh yet.
set(LLAMA_CPP_VENDORED "${CMAKE_CURRENT_SOURCE_DIR}/../../../third_party/llama.cpp")

if(EXISTS "${LLAMA_CPP_VENDORED}/CMakeLists.txt")
    message(STATUS "llama.cpp: using vendored checkout at ${LLAMA_CPP_VENDORED}")
    add_subdirectory("${LLAMA_CPP_VENDORED}" "${CMAKE_CURRENT_BINARY_DIR}/llama.cpp" EXCLUDE_FROM_ALL)
else()
    message(STATUS "llama.cpp: no vendored checkout, fetching ${LLAMA_CPP_TAG} over the network")
    include(FetchContent)
    FetchContent_Declare(
            llama_cpp
            GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
            GIT_TAG ${LLAMA_CPP_TAG}
            GIT_SHALLOW TRUE
            GIT_PROGRESS TRUE
    )
    FetchContent_MakeAvailable(llama_cpp)
endif()

add_library(llamajni SHARED llama_jni.cpp)

if (LLAMAJNI_VULKAN)
    target_compile_definitions(llamajni PRIVATE LLAMAJNI_VULKAN=1)
endif()

target_link_libraries(llamajni
        PRIVATE
        llama
        android
        log
)
