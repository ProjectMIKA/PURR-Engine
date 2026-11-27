#!/usr/bin/env bash
# ✨ PURR-ENGINE ZONOS SOURCE FETCHER (v2.7.0) ✨
# Vendors the official Zyphra native runtimes into app/src/main/cpp/third_party:
#   zonos3 ← github.com/Zyphra/ZONOS3-Mini  (servers/ggml = zonos3.cpp, ggml submodule)
#   zonos1 ← github.com/Zyphra/zonos1.cpp   (standalone Zonos-v0.1 ggml port)
# After this runs, the CMake build links the REAL adapters instead of stubs
# (PURR_ZONOS3_VENDORED / PURR_ZONOS1_VENDORED). Rust textnorm (zyphra-textnorm)
# is built by the zonos3 CMake via cargo — install a Rust toolchain + cargo-ndk
# for Android targets before building.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TP="$ROOT/app/src/main/cpp/third_party"
mkdir -p "$TP"

if [ ! -d "$TP/zonos3" ]; then
  echo "> Fetching Zyphra/ZONOS3-Mini (shallow, sparse: servers/ggml)…"
  git clone --depth 1 --filter=blob:none --sparse \
    https://github.com/Zyphra/ZONOS3-Mini.git "$TP/zonos3"
  git -C "$TP/zonos3" sparse-checkout set servers/ggml third_party
  git -C "$TP/zonos3" submodule update --init --depth 1 servers/ggml/ggml third_party/zyphra-textnorm
else
  echo "> zonos3 already vendored at $TP/zonos3 (skipping)"
fi

if [ ! -d "$TP/zonos1" ]; then
  echo "> Fetching Zyphra/zonos1.cpp (shallow)…"
  git clone --depth 1 https://github.com/Zyphra/zonos1.cpp.git "$TP/zonos1"
  git -C "$TP/zonos1" submodule update --init --depth 1 --recursive || true
else
  echo "> zonos1 already vendored at $TP/zonos1 (skipping)"
fi

echo "> Done. Rebuild with: ./gradlew assembleRelease"
echo "> NOTE: adapters in app/src/main/cpp/zonos/*_adapter.cpp must be wired to the"
echo ">       vendored engine entry points (see the #error markers) before enabling"
echo ">       PURR_ZONOS*_VENDORED in CMakeLists.txt."
