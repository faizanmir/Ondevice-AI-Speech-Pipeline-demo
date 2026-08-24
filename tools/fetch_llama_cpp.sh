#!/usr/bin/env bash
#
# Vendors llama.cpp into engine-llamacpp/third_party/ at the tag the CMake build expects.
#
# Run this once on a fresh checkout. The build works without it -- CMake falls back to fetching
# over the network -- but Gradle configures a separate CMake directory per ABI and per build type,
# and FetchContent clones into each one, so the fallback re-downloads llama.cpp several times.
# Vendoring it once makes builds fast, reproducible, and offline-capable.
#
# The tag must match LLAMA_CPP_TAG in engine-llamacpp/src/main/cpp/CMakeLists.txt. Bumping it means
# re-reading include/llama.h: llama.cpp's C API is not stable across releases, and llama_jni.cpp is
# written against this exact one.

set -euo pipefail

TAG="b9999"
REPO="https://github.com/ggml-org/llama.cpp.git"

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
dest="${script_dir}/../engine-llamacpp/third_party/llama.cpp"

if [ -e "${dest}" ]; then
  current=""
  if [ -d "${dest}/.git" ]; then
    current="$(git -C "${dest}" describe --tags --always 2>/dev/null || echo unknown)"
    if [ "${current}" = "${TAG}" ]; then
      echo "llama.cpp already vendored at ${TAG}"
      exit 0
    fi
  fi
  # dest exists but is not a clean checkout at ${TAG}: wrong tag, a trimmed
  # (non-git) source tree, or a partial/broken clone. git clone refuses to
  # write into a non-empty directory, so clear it out for a clean slate.
  if [ -n "${current}" ]; then
    echo "llama.cpp is at ${current}, want ${TAG} -- re-cloning"
  else
    echo "llama.cpp at ${dest} is not a git checkout -- re-cloning"
  fi
  rm -rf "${dest}"
fi

mkdir -p "$(dirname "${dest}")"
echo "Cloning llama.cpp ${TAG} (shallow) into ${dest}"
git clone --depth 1 --branch "${TAG}" --single-branch "${REPO}" "${dest}"

echo "Done. llama.cpp ${TAG} vendored."
