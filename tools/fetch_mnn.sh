#!/usr/bin/env bash
#
# Vendors MNN into engine-mnn/third_party/ at the tag the CMake build expects.
#
# Run this once on a fresh checkout. The build works without it -- CMake falls back to fetching
# over the network -- but Gradle configures a separate CMake directory per ABI and per build type,
# and FetchContent clones into each one, so the fallback re-downloads MNN several times.
# Vendoring it once makes builds fast, reproducible, and offline-capable.
#
# The tag must match MNN_TAG in engine-mnn/src/main/cpp/CMakeLists.txt. Bumping it means
# re-reading transformers/llm/engine/include/llm/llm.hpp: MNN's LLM C++ API is not stable across
# releases, and mnn_jni.cpp is written against this exact one.

set -euo pipefail

TAG="3.6.0"
REPO="https://github.com/alibaba/MNN.git"

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
dest="${script_dir}/../engine-mnn/third_party/MNN"

if [ -e "${dest}" ]; then
  current=""
  if [ -d "${dest}/.git" ]; then
    current="$(git -C "${dest}" describe --tags --always 2>/dev/null || echo unknown)"
    if [ "${current}" = "${TAG}" ]; then
      echo "MNN already vendored at ${TAG}"
      exit 0
    fi
  fi
  # dest exists but is not a clean checkout at ${TAG}: wrong tag, a trimmed
  # (non-git) source tree, or a partial/broken clone. git clone refuses to
  # write into a non-empty directory, so clear it out for a clean slate.
  if [ -n "${current}" ]; then
    echo "MNN is at ${current}, want ${TAG} -- re-cloning"
  else
    echo "MNN at ${dest} is not a git checkout -- re-cloning"
  fi
  rm -rf "${dest}"
fi

mkdir -p "$(dirname "${dest}")"
echo "Cloning MNN ${TAG} (shallow) into ${dest}"
git clone --depth 1 --branch "${TAG}" --single-branch "${REPO}" "${dest}"

echo "Done. MNN ${TAG} vendored."
