# Contributing to PURR-TTS

🐾 Welcome! PURR-TTS is Project M.I.K.A.'s **ROM-04 Portable Speech Engine** — a
standalone Jetpack Compose app *and* a system-wide Android `TextToSpeechService`
that carries the P.U.R.R. pipeline to every app on the device. We love
contributors — please read this before opening a pull request.

## ✍️ Step 1: The CLA (required)

To keep the project's licensing clean and sustainable, every contributor
signs our [ProjectMIKA Individual Contributor License Agreement](https://github.com/ProjectMIKA/PURR-Engine/blob/main/CLA.md) —
once, one click. **CLA Assistant** will automatically comment on your first
pull request with a signing link; merges stay blocked until it is signed. You
keep full ownership and credit for your code.

**Why a CLA?** The system-wide TTS app is and stays free — forever. The CLA
simply keeps the project's rights unified so that if this code ever powers
other surfaces (an app-store release, OEM / embedded speech licensing),
ProjectMIKA can license those cleanly. Your contribution keeps working in the
free app either way — see
[Why this CLA exists](https://github.com/ProjectMIKA/PURR-Engine/blob/main/CLA.md#why-this-cla-exists-the-short-version)
in the agreement itself.

## 🛠️ Getting started

```bash
python3 scripts/verify_project.py   # stdlib-only structure guard — no Android SDK needed
./gradlew testDebugUnitTest         # pure JVM JUnit4 suite under app/src/test/
./gradlew assembleRelease           # signed release APK (CI runs this on release tags)
```

There is no Android SDK in automation shells — CI
(`.github/workflows/build-android-apk.yml`) is the build authority. Local
work leans on `verify_project.py` plus the JVM test suite; visual checks
happen via sideloaded APK.

## 🗺️ Where things live

| Path | What it is |
|---|---|
| `app/src/main/java/.../core/purr/` | **PURE Kotlin middleware** — Android-free, unit-testable (text processor, sentiment, DSP, voice matcher, models, repository) |
| `app/src/main/java/.../engine/` | Backends: LiteRT Kokoro, sherpa-onnx, ONNX Runtime, Qwen3 native, LAN proxy + the single-lane `TtsBackendRouter` |
| `app/src/main/java/.../service/` | The system-wide `TextToSpeechService` + system contract activities |
| `app/src/main/java/.../ui/` | Compose terminal skin (`theme/`) + Forge screens (`forge/`) |
| `app/src/test/` | Pure JVM JUnit4 suites |

## 📏 Ground rules

1. **`core/purr` stays PURE.** No Android imports — that is what keeps it
   JVM-testable. If you add or extend an engine there, add tests under
   `app/src/test/` in the same change.
2. **Fixed-size slot law** for dynamic text in pinned regions (headers, HUD
   bars) — fixed-width slots, `maxLines = 1`, pinned container heights.
3. **`sherpa-onnx` is pinned at `v1.13.8`** — never bump without re-verifying
   KittenTTS + OOV word pronunciation (the v1.3.2 scar).
4. **No new dependencies without an accepted issue first.**
5. **Never commit keystores or build artifacts** (`builds/`, `archive/`,
   `*.keystore` — both output dirs are gitignored).
6. **Small, focused PRs** — one feature or fix per pull request, with a short
   description of what changed and why.

## 🎨 Code style

Standard Kotlin, 4-space indent. New systems get a
`// ✨ MIKA'S <SYSTEM NAME> ✨` banner comment explaining the **why** (crash
class, cache behavior, scar), not just the what.

## 📮 Pull request checklist

- [ ] `python3 scripts/verify_project.py` passes
- [ ] `./gradlew testDebugUnitTest` green
- [ ] New/changed `core/purr` engines have JVM test coverage
- [ ] No new deps (or an accepted issue justifies them)
- [ ] No keystores / build artifacts committed
- [ ] CLA signed (first PR only)

## 📜 License

By contributing, you agree that your contributions are distributed under
[AGPL-3.0-or-later](https://github.com/ProjectMIKA/PURR-Engine/blob/main/LICENSE) and covered by the
[ProjectMIKA Individual Contributor License Agreement](https://github.com/ProjectMIKA/PURR-Engine/blob/main/CLA.md).
