# Build, Artifacts & Release (always-on context)

## Commands
| Command | What it does |
|---|---|
| `python3 scripts/verify_project.py` | Static guard: required files, XML validity, manifest TTS contract, Kotlin package headers + balanced delimiters, 54 Kokoro voices, ≥6 SHA-256 bundles. |
| `gradle testDebugUnitTest` | JVM unit tests (`app/src/test/...`, JUnit4) — CI runs this before every release build. |
| `gradle assembleRelease` | Signed release APK (`release.keystore` if present, else debug signing). |
| `./gradlew --version` | Wrapper is Gradle 8.10.2. **No JDK/Gradle/Android SDK in the automation shell** — CI (GitHub Actions) is the builder; hand Master commands or defer to CI. |

## CI flow (`.github/workflows/build-android-apk.yml`)
1. Triggers: push to `main`, tags `v*`, PRs to `main`, manual `workflow_dispatch`.
2. Fetches `sherpa-onnx-1.13.8.aar` into `app/libs/` (gitignored — never commit AARs).
3. Runs `scripts/verify_project.py` (fails the build on drift).
4. Generates `release.keystore` (keytool, env-overridable passwords).
5. `gradle testDebugUnitTest assembleRelease` — tests gate the release.
6. Packages EXACTLY TWO artifacts into `builds/`:
   - `PURR-Engine_V{version}_Android.apk` (main release)
   - `PURR-TTS_V{version}.apk` (sideload mirror)
7. Uploads artifacts; on tag pushes attaches both APKs to the GitHub Release (`gh release upload --clobber` if the release exists, else skips).

## Release checklist (mirrors GachaSwipe's `release` skill)
1. Bump `versionName` + `versionCode` in `app/build.gradle.kts` (single source of truth).
2. Prepend `RELEASE_NOTES.md`: `# 🌌 PURR-TTS vX.Y.Z // <Codename>` + `### ✨ What's New since v(X.Y.Z-1)` bullets, then `---`.
3. Add the matching changelog section to `README.md` (newest on top).
4. Run `python3 scripts/verify_project.py` locally (works without Android SDK).
5. Commit `vX.Y.Z: <summary>` → **tag `vX.Y.Z` (REQUIRED, every release)** → push main + tags together.
6. After CI green: `gh release create vX.Y.Z builds/PURR-Engine_VX.Y.Z_Android.apk builds/PURR-TTS_VX.Y.Z.apk --title "PURR-TTS vX.Y.Z // <Codename>" --notes-file <file>` (authed as ProjectMIKA).

## Versioning
- Pattern `1.#.##`. versionCode increments by 1 every release. RELEASE_NOTES + README must match
  `versionName` before any build. Release titles: `# 🌌 PURR-TTS vX.Y.Z // <Codename>`.

## Git conventions
- Release commits: `vX.Y.Z: <summary>`. Regular commits: short imperative one-liners.
- Never commit: `builds/`, `archive/`, `*.apk`, `*.keystore` (except `debug.keystore`),
  `app/libs/*.aar|jar`, `app/build/`, `.gradle/` — all already in `.gitignore`.
- Zips/APKs in git history = the 287MB GachaSwipe scar. Do not repeat it here.

## Lessons already learned (do not re-learn them)
- AOSP calls `onLoadLanguage` inside `TextToSpeechService.super.onCreate()` → init deps first.
- AGP 8.7.2 Lint UAST crash with older Kotlin → toolchain aligned at Kotlin 2.4.0; keep aligned.
- sherpa-onnx `v2.0.0` dropped espeak-ng G2P → KittenTTS broke + OOV words skipped → pinned
  `v1.13.8`. Verify pronunciation before bumping.
- Sentiment-driven pitch resampling + glue compressor on CLEAN_STUDIO + blocking `delay()` in
  the AudioTrack loop = the v1.3.2 "buzz" crash class. Keep the transparent default DSP path.
