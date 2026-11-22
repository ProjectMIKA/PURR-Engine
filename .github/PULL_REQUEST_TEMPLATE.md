<!-- 🐾 PURR-TTS pull request -->

## What does this PR change?

<!-- One or two sentences: what + why. Reference issues with #n. -->

## How was it tested?

- [ ] `python3 scripts/verify_project.py` passes
- [ ] `./gradlew testDebugUnitTest` passes (CI runs it too)
- [ ] New/changed `core/purr` engines have JVM test coverage

## Checklist

- [ ] I signed the ProjectMIKA CLA (CLA Assistant prompts on your first PR)
- [ ] UI changes follow the [Visual Language Style Guide](https://github.com/ProjectMIKA/PURR-Engine/blob/main/.github/STYLE_GUIDE.md) — `MikaColors` tokens only, `MikaCyberIcon` only, no generic dashboard styling
- [ ] No new dependencies (or an accepted issue justifies them)
- [ ] No keystores / build artifacts (`builds/`, `archive/`, `*.keystore`) committed
- [ ] `core/purr` stays Android-free (pure Kotlin, JVM-testable)
