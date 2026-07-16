cmake_minimum_required(VERSION 3.22.1)
project("mnnjni" CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# MNN is pinned to a release tag, not a branch. The LLM C++ API (transformers/llm/engine) moves
# fast enough that an unpinned build would silently break mnn_jni.cpp on some future sync; bumping
# this is a deliberate act that should come with re-reading include/llm/llm.hpp.
set(MNN_TAG 3.6.0)

# One static libMNN with the LLM engine folded in, linked into our single libmnnjni.so. Static
# (rather than MNN's default shared + separate backends) keeps packaging to one .so and lets the
# linker drop the large parts of MNN this app never calls.
set(MNN_BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(MNN_SEP_BUILD         OFF CACHE BOOL "" FORCE)

# What MNN's own Android build scripts set. Without it, MNN's CMake attaches a POST_BUILD
# header-copy step to the `llm` target -- which is an OBJECT library in this static layout, and
# CMake rejects POST_BUILD on OBJECT libraries outright.
set(MNN_BUILD_FOR_ANDROID_COMMAND TRUE CACHE BOOL "" FORCE)

# The LLM engine itself, and the flags Alibaba's own MnnLlmChat app builds it with:
#  - MNN_LOW_MEMORY / MNN_CPU_WEIGHT_DEQUANT_GEMM: run 4-bit weights without dequantising the whole
#    model into RAM -- without these a "500 MB" model costs gigabytes.
#  - MNN_SUPPORT_TRANSFORMER_FUSE: fused attention kernels; the difference between usable and
#    painful decode speed.
#  - MNN_ARM82: armv8.2 fp16 arithmetic, present on effectively every 64-bit phone this app's
#    minSdk can reach. MNN gates the fp16 path at runtime, so older cores still work.
set(MNN_BUILD_LLM                 ON  CACHE BOOL "" FORCE)
set(MNN_LOW_MEMORY                ON  CACHE BOOL "" FORCE)
set(MNN_CPU_WEIGHT_DEQUANT_GEMM   ON  CACHE BOOL "" FORCE)
set(MNN_SUPPORT_TRANSFORMER_FUSE  ON  CACHE BOOL "" FORCE)
set(MNN_ARM82                     ON  CACHE BOOL "" FORCE)
set(MNN_USE_LOGCAT                ON  CACHE BOOL "" FORCE)

# Trim the build to the library: no demo executables, no converter, no test suite, and no
# GPU backends. GPU is deliberately out for now -- this repo already turned llama.cpp's Vulkan off
# after it produced garbage on the Adreno driver we tested, and MNN's OpenCL LLM path has the same
# per-device trust problem. CPU with ARM82 fp16 is correct everywhere and fast enough for the
# small models in the catalogue.
set(MNN_LLM_BUILD_DEMO      OFF CACHE BOOL "" FORCE)
set(LLM_SUPPORT_HTTP_RESOURCE OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_TOOLS         OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_TEST          OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_BENCHMARK     OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_CONVERTER     OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_DEMO          OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_QUANTOOLS     OFF CACHE BOOL "" FORCE)
set(MNN_OPENCL              OFF CACHE BOOL "" FORCE)
set(MNN_VULKAN              OFF CACHE BOOL "" FORCE)
set(MNN_OPENGL              OFF CACHE BOOL "" FORCE)

# Prefer a vendored checkout, for the same reason as llama.cpp: Gradle configures a separate CMake
# build directory per ABI and per build type, and FetchContent clones into each one -- so a
# from-scratch fetch would re-download MNN several times over. tools/fetch_mnn.sh vendors it once;
# the network fetch stays only as a fallback for a fresh checkout.
set(MNN_VENDORED "${CMAKE_CURRENT_SOURCE_DIR}/../../../third_party/MNN")

if(EXISTS "${MNN_VENDORED}/CMakeLists.txt")
    message(STATUS "MNN: using vendored checkout at ${MNN_VENDORED}")
    add_subdirectory("${MNN_VENDORED}" "${CMAKE_CURRENT_BINARY_DIR}/MNN" EXCLUDE_FROM_ALL)
    set(MNN_SOURCE_DIR "${MNN_VENDORED}")
else()
    message(STATUS "MNN: no vendored checkout, fetching ${MNN_TAG} over the network")
    include(FetchContent)
    FetchContent_Declare(
            mnn
            GIT_REPOSITORY https://github.com/alibaba/MNN.git
            GIT_TAG ${MNN_TAG}
            GIT_SHALLOW TRUE
            GIT_PROGRESS TRUE
    )
    FetchContent_MakeAvailable(mnn)
    set(MNN_SOURCE_DIR "${mnn_SOURCE_DIR}")
endif()

add_library(mnnjni SHARED mnn_jni.cpp)

target_include_directories(mnnjni PRIVATE
        "${MNN_SOURCE_DIR}/include"
        "${MNN_SOURCE_DIR}/transformers/llm/engine/include"
)

target_link_libraries(mnnjni
        PRIVATE
        MNN
        android
        log
)
