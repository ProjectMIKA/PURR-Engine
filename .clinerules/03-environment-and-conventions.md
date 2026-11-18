# Environment & Code Conventions (always-on context)

## Environment realities
- **No JDK / Gradle / Android SDK in the automation shell** (verified Nov 2026). You cannot run
  Gradle builds or unit tests locally. Write the change → run `python3 scripts/verify_project.py`
  (stdlib-only, runs fine) → hand Master the exact Gradle commands, or let CI verify on push.
- Tooling is zero-new-deps by policy: `scripts/verify_project.py` uses only the Python stdlib.
  Do not add test frameworks/linters without asking Master.
- `gh` CLI is installed and authed (ProjectMIKA). `git` push works over https.

## Code style
- Standard Kotlin style, 4-space indent. New systems get a
  `// ✨ MIKA'S <SYSTEM NAME> ✨` banner comment block explaining the WHY (crash class,
  cache behavior, scar), like the existing engines.
- Companion objects hold named constants (`TAG`, `SAMPLE_RATE_HZ`, eviction windows).
- Coroutines everywhere: `StateFlow` for observable state, `Mutex` for single-lane sections,
  `Dispatchers.IO` for blocking work, `SupervisorJob` scopes per engine/service.
- Never block the binder/main thread; `PurrTtsService` synthesis runs on `Dispatchers.IO`
  with cancellation checks (`isStopped`) between chunks.

## UI: the FIXED-SIZE SLOT LAW (v1.3.3 scar)
Dynamic text (hardware badges `[IDLE]..[NPU ACTIVE]`, VRAM countdowns, telemetry labels) must
NEVER size its own container in pinned layout regions (headers, HUD bars). Give every dynamic
element a fixed-width `Modifier` slot, cap text with `maxLines = 1` + `overflow`, and pin
container heights (`heightIn(min = …)`). Otherwise the label swap re-measures the parent row,
a `weight(1f)` neighbor re-wraps, and the whole page shifts on every synthesis state change.
Details: `.agents/skills/mika-compose-ui/SKILL.md`.

## Testing
- Pure JVM JUnit4 under `app/src/test/java/com/projectmika/purrtts/core/purr/` — CI runs
  `testDebugUnitTest` before every release build.
- `core/purr/` engines stay PURE (no Android imports) so they stay unit-testable. When adding a
  new validator/engine/scorer behavior, ADD TESTS IN THE SAME CHANGE.
- Existing suites: `PurrTextProcessorTest` (pipeline + DSP taper/resampler/stitching),
  `PurrSentimentScorerTest` (axes, forced tones, pitch-lock law, prosody bounds),
  `PurrVoiceMatcherTest` (gates, scoring, hash fallback), `PurrDspProcessorTest`
  (silence byte contracts, float formats, waveform bins).
- Compose UI needs instrumentation tests — don't fake them as JVM tests; verify visually via APK.

## Console/diagnostic conventions
- `Log.i/.e` with module tags: `PurrTtsService`, `PurrLiteRT`, `PurrSherpaONNX`, `PurrCoreOsSpeech`,
  `PurrCatalog`. Messages use `[BOOT]`/`[SYNTH_ERR]`-style prefixes like GachaSwipe's `[OOB]`.

## Shell discipline (automation)
- Terminal commands MUST produce output and never fail silently — append `|| true` to greps/ls.
- This shell's integration occasionally drops command completion output; prefer `read_files` /
  single-purpose commands over chained greps when capturing evidence.

## Skill Maintenance Doctrine (the library must not rot)
1. Touching code a skill documents → update that SKILL.md in the SAME commit.
   Mapping: `core/purr`+`engine`+`service`→purr-native-engine · release flow/CI→purr-release ·
   `ui/theme`+`ui/forge`+index-style tokens→mika-compose-ui.
2. Shipped something non-obvious or that cost a debugging session → it earns a skill (or extends
   one) in the same change, registered here.
3. Reference by section banner / ✨ banner name, never line number.
4. The JS twin lives in GachaSwipe + the global `purr-tts-engine` skill — when behavior parity
   changes on either side, note it in the other repo's docs or the global skill.
