#!/usr/bin/env bash
# ✨ PURR-ENGINE ZONOS SOURCE FETCHER (v2.8.0) ✨
# Vendors the official Zyphra native runtime into app/src/main/cpp/third_party:
#   zonos3 ← github.com/Zyphra/ZONOS3-Mini (sparse: servers/ggml + model registries,
#            with its pinned ggml submodule). This is the engine behind ROM-18.
# NOTE: a ZONOS1 native runtime does NOT exist upstream — github.com/Zyphra/zonos1.cpp
# 404s and the Zyphra org has no such repo (verified 2026-11-27 via the GitHub API).
# ROM-19 ZONOS1 therefore ships as a staged cartridge with a fail-soft stub adapter.
# After this runs, CMake links the REAL zonos3 engine (PURR_ZONOS3_VENDORED).
# No Rust/cargo and no espeak are needed for the CLI synthesis path (build_prompt →
# generate → zac_decode); the Rust textnorm lives only in the HTTP server path.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TP="$ROOT/app/src/main/cpp/third_party"
mkdir -p "$TP"

if [ ! -d "$TP/zonos3" ]; then
  echo "> Fetching Zyphra/ZONOS3-Mini (shallow, sparse: servers/ggml + registries)…"
  git clone --depth 1 --filter=blob:none --sparse \
    https://github.com/Zyphra/ZONOS3-Mini.git "$TP/zonos3"
  git -C "$TP/zonos3" sparse-checkout set servers/ggml model/src/zonos3/registries
  git -C "$TP/zonos3" submodule update --init --depth 1 servers/ggml/ggml
else
  echo "> zonos3 already vendored at $TP/zonos3 (skipping)"
fi

echo "> Done. Rebuild with: ./gradlew assembleRelease"

