# PURR-Engine // Project Overview (always-on context)

**PURR-TTS** (`/home/projectmika/Documents/ProjectMIKA/PURR-Engine/`) — standalone native Android
**Jetpack Compose** app + system-wide `android.speech.tts.TextToSpeechService` that carries
GachaSwipe's **P.U.R.R.** (Per-Utterance Reaction Rendering) speech middleware to every app on
the device. ROM-04 Portable Speech Engine. Current version lives in `app/build.gradle.kts`
(`versionName` + `versionCode`) — that file is the single source of truth.

## Stack
- **Kotlin 2.4.0, Jetpack Compose (BOM 2024.11), AGP 8.7.2, Gradle 8.10.2 wrapper, JDK 17.**
- minSdk 26, targetSdk/compileSdk 35, ABIs `arm64-v8a` + `x86_64`.
- Neural runtimes: **LiteRT / LiteRT-LM + MediaPipe Tasks Audio** (Kokoro-82M) and
  **sherpa-onnx JNI AAR pinned `v1.13.8`** (espeak-ng + piper-phonemize G2P — do NOT bump past
  the pinned version without re-verifying KittenTTS + OOV word pronunciation; v1.3.2 scar).
- kotlinx-serialization JSON, OkHttp, commons-compress (`.tar.bz2` unpacking). Zero deps added
  without Master's approval.

## Architecture map
```
app/src/main/java/com/projectmika/purrtts/
├── PurrApplication.kt              # Shared singleton container (repository + backendRouter)
├── core/purr/                      # PURE Kotlin middleware — Android-free, unit-testable
│   ├── PurrModels.kt               # All @Serializable contracts + PurrDefaultPresets catalogs
│   ├── PurrSentimentScorer.kt      # 5-axis lexicon scoring → prosody (rate/pitch/volume)
│   ├── PurrTextProcessor.kt        # Slang/honorifics/stutters, action-dialogue split, MIKASTOP
│   ├── PurrDspProcessor.kt         # 24kHz rack: taper, biquads, reverb, compressor, resampler
│   ├── PurrVoiceMatcher.kt         # Whitelist/blacklist + tag scoring + deterministic fallback
│   └── PurrProfileRepository.kt    # JSON persistence → filesDir/purr_forge_state.json
├── engine/
│   ├── PurrSynthesizer.kt          # Unified backend interface → SynthesisOutcome (24k float32)
│   ├── TtsBackendRouter.kt         # Single-lane Mutex serializer + telemetry + fail-soft chain
│   ├── ModelCatalogManager.kt      # Resumable HTTP Range downloads, SHA-256, tar.bz2 unpack
│   ├── litert/LiteRtKokoroEngine.kt    # Kokoro-82M GPU FP16 + G2P, 5-min idle VRAM eviction
│   ├── sherpa/SherpaOnnxEngine.kt      # Kokoro/KittenTTS/Supertonic3/Piper/Pocket/ZipVoice
│   └── proxy/ExternalProxyEngine.kt    # LAN OpenAI /v1/audio/speech + OS TTS passthrough
├── service/
│   ├── PurrTtsService.kt           # System TextToSpeechService streaming 24kHz PCM chunks
│   └── TtsSystemActivities.kt      # CHECK_VOICE_DATA / GET_SAMPLE_TEXT / INSTALL_TTS_DATA
└── ui/
    ├── theme/                      # MikaTheme tokens + MikaComponents (terminal skin, Canvas icons)
    └── forge/                      # TtsForgeScreen/ViewModel, PurrRulebookTab, ModelCatalogTab,
                                   # VoiceCloneStudioSheet, glassmorphic MikaBottomHudBar
```

## Architectural doctrine (violating these = shipping bugs)
1. **Fail-soft to real speech, never fake tones** — if a neural cartridge is ejected/not
   downloaded, route falls back to `CORE_OS` device speech. Output is ALWAYS coherent spoken
   words; empty PCM flows through so the router can fail soft (v1.1.0 removed sine placeholders).
2. **Single-lane synthesis** — all neural synthesis serializes through `TtsBackendRouter`'s
   `synthMutex` (plus per-engine mutexes). Never run backends concurrently.
3. **Service init order law** — `PurrTtsService` initializes `repository`/`backendRouter` BEFORE
   `super.onCreate()` because AOSP invokes `onLoadLanguage` synchronously inside it (v1.1.0 crash).
4. **lmkd protection** — LiteRT + Sherpa engines run a 5-minute idle VRAM eviction timer
   (`evictResources()`); background services must never hold GPU/NPU buffers while idle.
5. **Pitch-lock law** — default `basePitch = 1.0` never incurs pitch resampling (sentiment acting
   modulates pacing only) unless the user moves the Pitch slider or enables a pitch effect
   (v1.3.2 intermodulation-buzz scar).
6. **MIKASTOP determinism** — pauses are exact zero-filled 24kHz PCM buffers injected at chunk
   boundaries (silences merged, capped 3000ms); neural models never hallucinate on punctuation.
7. **Proxy never self-binds** — `ExternalProxyEngine` OS-TTS fallback must never resolve to
   `com.projectmika.purrtts` (infinite loop).
8. **Model downloads fail closed** — SHA-256 must verify before a cartridge is `INSTALLED`.

## Where things live
- Docs: `README.md` (architecture + changelog, newest entry on top), `RELEASE_NOTES.md`
  (prepend per release), `scripts/verify_project.py` (CI structure guard), `SDK`-equivalent
  contracts live in code (no vendored SDK here — it's a native host app).
- `builds/` = latest release's 2 APKs; `archive/` = previous APKs. Both gitignored.
- Workspace skills: `.agents/skills/` (purr-native-engine, purr-release, mika-compose-ui).
- Global skill: `mika-licensing` (`~/.cline/skills/`) — governs LICENSE / CLA.md / NOTICE /
  README license sections / in-app license strings for this repo. The project relicensed from
  MIT to **AGPL-3.0-or-later** + the broad ProjectMIKA ICLA right after v1.8.3 (sole-copyright
  window; releases up to v1.8.3 remain MIT for their copies).
- The JS twin of this engine lives in GachaSwipe (`src/` + global `purr-tts-engine` skill);
  keep behavioral parity when porting features in either direction.
