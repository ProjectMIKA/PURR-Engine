---
name: purr-release
description: PURR-TTS release flow — version bumps, RELEASE_NOTES/README sync, tagging, CI APK build, gh release publishing. Use when cutting a PURR-Engine release or touching version/CI files.
---

# ✨ PURR-ENGINE RELEASE FLOW ✨

GachaSwipe's `release` skill pattern, adapted to the native Android repo. CI builds; you document.

## Version sources (all must agree before building)
1. `app/build.gradle.kts` → `versionCode` (increment by 1) + `versionName` (`1.#.##`).
2. `RELEASE_NOTES.md` → prepend `# 🌌 PURR-TTS vX.Y.Z // <Codename>` section with
   `### ✨ What's New since v<prev>` bullets + `### 📦 Android APK Packages (Attached Below)`
   naming both APKs, then `---` separator. Newest on top.
3. `README.md` → add matching changelog entry (newest on top).

## Release checklist
1. `python3 scripts/verify_project.py` locally (must print VERIFICATION PASSED).
2. Commit: `vX.Y.Z: <summary>` (short imperative).
3. `git tag vX.Y.Z` — **REQUIRED every release, no exceptions** (untagged = unreachable release).
4. `git push origin main --tags` (or `git push origin main vX.Y.Z`).
5. CI (`.github/workflows/build-android-apk.yml`) auto-runs on the tag: fetch sherpa AAR →
   verify_project.py → unit tests → assembleRelease → `builds/PURR-Engine_VX.Y.Z_Android.apk`
   + `builds/PURR-TTS_VX.Y.Z.apk` → attaches to the GitHub Release IF it already exists.
6. Create the release (CI only uploads to EXISTING releases):
   ```
   gh release create vX.Y.Z \
     builds/PURR-Engine_VX.Y.Z_Android.apk builds/PURR-TTS_VX.Y.Z.apk \
     --title "PURR-TTS vX.Y.Z // <Codename>" --notes-file release_notes.md
   ```
   (Order matters: push tag → wait green CI → create release → CI-on-tag already ran, so either
   create the release BEFORE pushing the tag with `--target main`, or `gh release upload --clobber`
   after. Prefer: create release first with draft=false and no assets, push tag, CI attaches.)
7. Rotate artifacts locally: keep newest in `builds/`, `git mv`-style move olders to `archive/`
   (both gitignored — local only).

## Past release codenames (continue the pattern)
v1.0.0 The Neural Voice Forge · v1.1.0 The Cartridge Bay Overhaul · v1.2.0 (Stylized Voice
Dropdown, Ejectable System Cartridge & NPU Models) · v1.3.0 (KittenTTS, 6 Piper Voice Packs,
Bottom HUD, Cyberdeck OS Modules & Audio Media Exporter) · v1.3.1 (Glassmorphic Icon HUD,
Phasing Transitions & Custom Cyberdeck SVG Icons) · v1.3.2 (Crystal-Clear Audio, KittenTTS Fix
& Full OOV Word Pronunciation) · v1.7.0 The Heavy Local Fleet · v1.8.0 The Audiobook Constructor
· v1.8.1 The Trust Renewal · v1.8.2 The Adreno Offload.

## CI gates that can fail the release
- `verify_project.py`: missing/empty required file, invalid XML, manifest missing TTS service
  contract entries, unbalanced Kotlin delimiters, <54 Kokoro voice IDs, <6 SHA-256 bundles.
- Unit tests (`testDebugUnitTest`): all suites in `app/src/test/`.
- Release APK must exist at `app/build/outputs/apk/release/app-release.apk` or packaging fails.

## Keystore
CI generates `release.keystore` via keytool (passes default `purrtts`); local builds use debug
signing if no keystore exists. Never commit keystores (`.gitignore` covers `*.keystore`).

## Release scar (do not re-learn)
- **v1.8.1 `gh release create --target <branch>` ref-cache race**: creating the release with
  `--target main` IMMEDIATELY after `git push origin main` resolved main to the PREVIOUS head —
  the v1.8.1 tag got created on the v1.8.0 commit, CI built the old code and attached
  `V1.8.0`-named APKs to the v1.8.1 release. ALWAYS pass the explicit commit SHA
  (`gh release create vX.Y.Z --target <sha>`) or push the tag with git FIRST
  (`git push origin vX.Y.Z`) and create the release after. If wrong assets land:
  `gh release delete-asset`, delete the remote tag, re-push the local tag (git push can't race),
  and either let the fresh tag-CI attach or `gh run download <run-id> -n PURR-TTS-Release-APKs`
  + `gh release upload vX.Y.Z *.apk --clobber` manually.
- **`--target <sha>` can ALSO be rejected** ("Release.target_commitish is invalid") during
  API replication lag even when the commit exists — fallback: confirm `main` via
  `git ls-remote origin refs/heads/main` (the authoritative git endpoint, not the REST cache)
  and use `--target main`, then IMMEDIATELY verify `git ls-remote origin refs/tags/vX.Y.Z`
  equals the expected SHA before relying on the tag CI.
- **Deleting a release's tag CONVERTS the release into a DRAFT** (invisible to users and to
  `releases/latest` — v1.8.1 sat as a draft with correct APKs while v1.8.0 still looked "latest").
  After any tag delete/re-push recovery, ALWAYS re-publish:
  `gh release edit vX.Y.Z --draft=false --latest`, then re-verify
  `gh api repos/<owner>/<repo>/releases/latest` points at the new tag.
