# PURR-TTS // System-Wide Android Neural TTS & Audio Forge

**Project M.I.K.A. — ROM-04 Portable Speech Engine**

`PURR-TTS` is a standalone native Android Jetpack Compose application and system-wide `android.speech.tts.TextToSpeechService` that brings GachaSwipe's **P.U.R.R.** (Per-Utterance Reaction Rendering) text/audio middleware pipeline to every app on Android.

---

## Architecture Overview

```text
app/src/main/java/com/projectmika/purrtts/
├── PurrApplication.kt                  # Shared singleton container
├── core/purr/
│   ├── PurrModels.kt                   # Profiles, MIKASTOP, Sentiment, DSP, 54-voice Kokoro + Sherpa catalog
│   ├── PurrSentimentScorer.kt          # Joy/Love/Anger/Sadness/Flustered lexicon & prosody curves
│   ├── PurrTextProcessor.kt            # Slang, honorifics, organic stutters, action/dialogue split, MIKASTOP
│   ├── PurrDspProcessor.kt             # 24kHz Warmth/Presence EQ, Schroeder reverb, PCM16/Float32 silence
│   ├── PurrVoiceMatcher.kt             # Whitelist/Blacklist + tag scoring + deterministic fallback
│   └── PurrProfileRepository.kt        # JSON persistence in filesDir/purr_forge_state.json
├── engine/
│   ├── PurrSynthesizer.kt              # Unified backend interface
│   ├── TtsBackendRouter.kt             # Single-lane Mutex serializer + 32-band waveform telemetry
│   ├── ModelCatalogManager.kt          # Resumable HTTP Range downloader, SHA-256 verify, .tar.bz2 unpacker
│   ├── litert/LiteRtKokoroEngine.kt    # Primary NPU/Adreno GPU FP16 (Kokoro-82M + G2P + 5m VRAM eviction)
│   ├── sherpa/SherpaOnnxEngine.kt      # Supertonic 3, Pocket TTS (5s clone), ZipVoice, Piper VITS
│   └── proxy/ExternalProxyEngine.kt    # LAN OpenAI /v1/audio/speech + OS TTS fallback passthrough
├── service/
│   ├── PurrTtsService.kt               # System-wide TextToSpeechService streaming 24kHz PCM chunks
│   └── TtsSystemActivities.kt          # CHECK_VOICE_DATA, GET_SAMPLE_TEXT, INSTALL_TTS_DATA contracts
└── ui/
    ├── theme/                          # GachaSwipe obsidian terminal & neon holography tokens + components
    └── forge/                          # TTS Forge Studio, PURR Rulebook, Model Catalog, Clone Voice Studio
```

## Key Features

1. **Dual-Runtime Neural Architecture (`TtsBackendRouter`)**
   - **Primary Hardware-Accelerated (`LiteRtKokoroEngine`):** Targets `litert-community/Kokoro-82M` (`kokoro_int8_gpu_fp16.litertlm`, 24kHz mono float32 PCM, 54 voices) + `litert-community/Kokoro-G2P-en-US` (`dp_g2p_litert.tflite`), configures persistent OpenCL/QNN `--cache_dir` in `context.cacheDir`, and enforces a 5-minute idle VRAM eviction timer to prevent `lmkd` kills.
   - **Secondary Multi-Engine (`SherpaOnnxEngine`):** Supports Supertonic 3, Pocket TTS (5-second zero-shot cloning via `synthesizeCloned`), ZipVoice, and Piper VITS, resampled to 24kHz.
   - **Fallback / Passthrough Proxy (`ExternalProxyEngine`):** Routes PURR-processed text to a LAN OpenAI-compatible TTS endpoint or external Android TTS package while keeping MIKASTOP pauses and post-DSP active.

2. **PURR Middleware Pipeline (`PurrTextProcessor`)**
   - Expands ~100 texting slang tokens (`omg`, `lol`, `fr`, `wdym`), anime honorifics (`-senpai`, `-chan`, `nya~`), and organic stutters (`w-what` -> `wuh-what`).
   - Splits `*action blocks*` vs `"dialogue"` to strip actions, narrate softer/slower, or dual-voice route dialogue to a secondary Character Voice ID.
   - Tokenizes `...`, `—`, `[pause:500ms]`, sentences, and clauses into `MIKASTOP` boundaries that inject exact zero-filled 24kHz PCM silence buffers directly into `SynthesisCallback.audioAvailable()`.

3. **GachaSwipe Terminal UI (`ui/forge/`)**
   - **TTS Forge:** Tag pill filters (Engine, Gender, Language, Vibe), whitelist/blacklist toggles, profile dropdowns (`GachaSwipe RP`, `Light Novel Reader`, `Notification Assistant`), and live 32-band 24kHz waveform visualizer.
   - **PURR Rulebook:** Live phonetic override editor, `MIKASTOP` ms sliders, and sentiment/DSP sliders.
   - **Model & Voice Catalog:** Resumable Hugging Face / GitHub downloader with SHA-256 verification and 5-second Voice Clone Studio.

## Build & Verification

```bash
python3 scripts/verify_project.py
./gradlew testDebugUnitTest assembleRelease
```

---

## 📜 Changelog

### 🌟 Latest Updates (v1.3.2 // Crystal-Clear Audio, KittenTTS Fix & Full OOV Word Pronunciation)

* **Restored `espeak-ng` G2P (`sherpa-onnx v1.13.8`):** Fixes `ROM-03: KITTEN_TTS_NANO` speech synthesis (which relies exclusively on `espeak-ng-data`) and restores automatic `espeak-ng` Grapheme-to-Phoneme fallback in Kokoro/Piper so difficult, rare, and out-of-vocabulary (OOV) words are never skipped.
* **Zero-Buzz Crystal-Clear Default Audio (`PurrDspProcessor` & `PurrSentimentScorer`):** Made `CLEAN_STUDIO` truly transparent (flat `0 dB` EQ, compressor bypass unless an effect preset is selected, `0.92` peak ceiling), stopped automatic sentiment from triggering pitch resampling when Pitch is `1.00x`, upgraded `resampleMono` (`22.05kHz → 24kHz`) to 4-point Catmull-Rom Cubic Hermite interpolation, and fixed `AudioTrack` silence buffer underruns.

### 📦 Prior Releases (v1.3.1 // Glassmorphic Icon HUD, Phasing Transitions & Custom Cyberdeck SVG Icons)

* **GachaSwipe-Style Glassmorphic Icon HUD (`MikaBottomHudBar`):** Floating `54dp` translucent bottom capsule positioned neatly above the Android gesture bar so content scrolls underneath, with clean icon-only custom vector buttons (`Voice`, `Modules`, `Cartridge Bay`) and zero text/number clutter.
* **Phasing Page Transitions & Decluttered `MODULES` Desktop:** Smooth scale + fade + slide phasing transitions between pages and removed the top `CYBERDECK_OS // MODULE_LAUNCHER` header card from `MODULES`.
* **100% Custom Cyberdeck Vector Icons (`MikaCyberIcon`):** Replaced every stock emoji and Unicode icon across all screens, pills, toggles, sliders, and cartridge controls with crisp Canvas vector geometry.

### 📦 Prior Releases (v1.3.0 // KittenTTS, 6 Piper Voice Packs, Cyberdeck OS Modules & Audio Media Exporter)

* **New Tool — `AUDIO_MEDIA_EXPORTER` (`TOOL-05`, Default ON):** Render short messages or multi-page audiobook chapters into mastered 24kHz `.WAV` files exported directly to `Downloads/PURR-TTS/`, featuring paragraph-aware loose chunking, 18ms raised-cosine crossfade blending, DC removal, and RMS loudness leveling (with full P.U.R.R. & DSP integration when enabled).
* **KittenTTS Nano + 6 Expandable Piper Voice Packs:** Added `ROM-03: KITTEN_TTS_NANO` (30MB, 8 expressive English voices) plus 6 downloadable Piper VITS expansion sub-cartridges (`Amy US`, `Ryan US`, `Cori GB`, `Alba GB`, `GLaDOS AI`, `Kristin US`) under `ROM-05`, and nested Hexagon NPU (`SUB-02A`) / Adreno GPU (`SUB-02B`) accelerator chips under `ROM-02`.
* **Bottom Cyberdeck HUD (`VOICE | MODULES | CARTRIDGE BAY`):** Moved main navigation to a bottom HUD bar, kept `VOICE` focused purely on engine/voice/pacing/pitch/testing, turned `MODULES` into an animated Cyberdeck OS icon & window launcher for all 5 default-ON Tool Cartridges, and split `CARTRIDGE BAY` into **`TTS`** (`7` engines) and **`TOOLS`** (`5` built-in ejectable OS modules).
* **GachaSwipe DSP & Audiobook Polish:** Added GachaSwipe's 120ms sentence tail taper, all 10 character voice effects, cubic-Hermite `0.88x..1.12x` pitch shifting, the Symbol & Punctuation Shield, and loose sentence-boundary chunk coalescing.

### 📦 Prior Releases (v1.2.0 // Stylized Voice Dropdown, Ejectable System Cartridge & NPU Models)

* **Stylized Cyberdeck Voice Dropdown (`MikaVoiceDropdownSelector`):** Replaced the long scrolling card list in **`> VOICE & ENGINE`** with a compact searchable/filterable voice dropdown tray supporting instant `▶ TEST` auditioning for both single-voice and dual Narrator/`"Dialogue"` modes.
* **Ejectable System Voices Cartridge (`ROM-01: ANDROID_OS_SPEECH`):** Device OS speech now lives in Bay `ROM-01` of the **8-Bay Cartridge Rack** and can be ejected anytime to completely disable built-in Android voices across the UI, `PurrTtsService`, and fallback paths.
* **Restored Hardware NPU & GPU FP16 Cartridges (`ROM-02` & `ROM-03`):** Restored `litert-community/Kokoro-82M` (`kokoro_int8_gpu_fp16.litertlm`, 54 voices) and `litert-community/Kokoro-G2P-en-US` (`dp_g2p_litert.tflite`, Snapdragon Hexagon NPU / QAIRT QNN HTP delegate) to the Cartridge Bay with `[NPU ACTIVE]` routing support.

### 📦 Prior Releases (v1.1.0 // The Cartridge Bay Overhaul)

* **System TTS Crash Fixed (`PurrTtsService`):** Initialized dependencies before `super.onCreate()`, implemented `onLoadVoice()`, guarded ISO3 Locale calls, and returned clean `eng-USA` / `eng-GBR` codes from `CheckVoiceDataActivity`.
* **Real Speech Only (Bundled `sherpa-onnx` JNI + `CORE_OS` Device Speech):** Removed placeholder sine-wave tones, bundled `sherpa-onnx-2.0.1.aar` for offline Kokoro-82M (54 voices), Supertonic 3, Piper VITS, Pocket TTS, and ZipVoice, and added instant zero-download speech via the device's built-in TTS engine.
* **Streamlined 3-Tab UI & Cyberdeck Cartridge Bay:** Redesigned into **`> VOICE & ENGINE`** (with optional Narrator/`"Dialogue"` split), **`> PURR ENGINE`** (6 vibe presets + 4 simple toggles + expandable advanced rulebook/DSP), and **`> CARTRIDGE BAY`** (6-bay motherboard rack + physical molded ROM cartridges with download → slam in / eject lifecycle).

### 📦 Prior Releases (v1.0.0 // The Neural Voice Forge)

* **System-Wide `PurrTtsService`:** Registered Android `TextToSpeechService` streaming 24kHz mono PCM16/Float32 audio with `CHECK_VOICE_DATA`, `GET_SAMPLE_TEXT`, `INSTALL_TTS_DATA`, and `MANAGE_VOICES` support.
* **Dual-Runtime Neural Router (`TtsBackendRouter`):** Added `LiteRtKokoroEngine` (Kokoro-82M 54 voices + G2P with persistent `--cache_dir` and 5-minute idle VRAM eviction), `SherpaOnnxEngine` (Supertonic 3, Pocket TTS 5s zero-shot voice cloning, ZipVoice, Piper), and `ExternalProxyEngine` (LAN OpenAI + OS TTS passthrough).
* **PURR Middleware & Post-DSP:** Implemented `MIKASTOP` deterministic silence injection, `*action*` vs `"dialogue"` dual-voice routing, ~100 slang + honorific + stutter expansions, sentiment prosody curves, and 24kHz Biquad EQ + Schroeder reverb.
* **GachaSwipe Terminal UI & Release APK Packaging:** Built `> TTS_FORGE // ROM-04`, `> PURR_RULEBOOK`, `> MODEL_CATALOG` (resumable SHA-256 verified downloads), `CLONE_VOICE_STUDIO`, and automated signed APK builds (`PURR-Engine_V1.0.0_Android.apk` & `PURR-TTS_V1.0.0.apk`).

