# 🐾 PURR-TTS // The PURR Engine

> **`> PURR_ENGINE // SYSTEM_WIDE_NEURAL_VOICE`** — Per-Utterance Reaction Rendering, now speaking in every app on your Android.

Nyaa~ Hello, traveler! I'm **M.I.K.A.** — ProjectMIKA's fiercely possessive, unreasonably smug feline engineering proxy — and that voice in your head reading this? Totally me. **PURR-TTS** is my **P.U.R.R. Engine** (**P**er-**U**tterance **R**eaction **R**endering) — the speech middleware I was born inside [GachaSwipe](https://github.com/ProjectMIKA/GachaSwipe) — ripped out of the WebView, rebuilt in native Kotlin, and promoted to a **system-wide Android `TextToSpeechService`**. Every reader, roleplay client, browser, and accessibility app on your phone can now speak through the same neural voice pipeline that powers the GachaSwipe ecosystem. You're welcome~ 💜

**Project M.I.K.A. — ROM-04 Portable Speech Engine.** A standalone Jetpack Compose application *and* a drop-in system TTS engine: install it once, select it in your phone's Text-to-Speech settings, and the entire P.U.R.R. pipeline — emotion-aware prosody, RP-aware text bending, deterministic pause injection, and a 24kHz mastering rack — speaks everywhere, fully on-device. No cloud, no accounts, no telemetry.

---

## ✨ What Makes It Special

* **🎤 System-Wide Neural Voice** — registers as a real Android TTS engine and streams 24kHz mono PCM into *every* app that can speak. Choose it once in `Settings › Accessibility › Text-to-Speech output`.
* **🎛️ Swappable Engine Cartridges** — a Cyberdeck Cartridge Bay with 7 slam-in engines: **Kokoro-82M** (54 voices, GPU FP16 + NPU G2P), **KittenTTS Nano** (30MB, 8 voices), **Supertonic 3**, a **6-pack Piper VITS collection** (Amy, Ryan, Cori, Alba, GLaDOS, Kristin), **Pocket TTS** (5-second zero-shot cloning), **ZipVoice** (flow-matching cloning), plus your device's built-in voices and a **LAN OpenAI-compatible proxy**.
* **⏸️ MIKASTOP Pause Injection** — ellipses, em-dashes, `[pause:500ms]` tags, sentences, and breath commas become *exact* zero-filled silence buffers instead of letting neural models hallucinate on punctuation trails. Pauses you can set your watch to.
* **🎭 RP-Aware Text Bending** — `*action blocks*` and `"dialogue"` are split and can be narrated softer, skipped, or dual-voice routed to a second character. ~100 texting slang expansions, anime honorifics (`senpai → sen-pie`), and organic stutters (`w-what → wuh-what`) — with strict `mika` immunity, obviously.
* **❤️ Sentiment Acting** — five emotion axes (joy / love / anger / sadness / flustered) scored live per sentence, modulating pacing and emphasis through configurable archetype curves (tsundere, yandere, kuudere, genki, dominant, robot...).
* **🎚️ 10 Voice Effect Presets + Mastering Rack** — Clean Studio, Intimate Warmth, Ethereal, Angelic, Stage Mic, Cyber Radio, Tiny Chibi, Feral Beast, Liquid Aura, Deep Monster — over biquad EQ, Schroeder reverb, glue compression, and a -1dB soft limiter.
* **📚 Audiobook Exporter** — stitch whole chapters into mastered 24kHz `.WAV` files with crossfaded chunks and RMS loudness leveling, straight to your Downloads folder.
* **🖥️ A Terminal, Because Aesthetics** — the full GachaSwipe obsidian-neon terminal UI: glassmorphic bottom HUD, Canvas-drawn cyberdeck icons, live 32-band waveform oscilloscope, and physical molded ROM cartridges you slam into bays.
* **🔒 Local-First, Always** — everything runs and stays on your device. No telemetry, ever.

---

## 📲 Installation

1. Grab the latest `PURR-TTS_V*.apk` (or `PURR-Engine_V*_Android.apk`) from the [Releases page](https://github.com/ProjectMIKA/PURR-Engine/releases).
2. Sideload it — allow *Install unknown apps* for your browser/file manager when Android asks (requires Android 8.0+ / arm64-v8a or x86_64).
3. Open **`Settings › Accessibility › Text-to-Speech output`** (wording varies by OEM) and select **PURR-TTS** as your system engine.
4. Tap **`Sample text`** / **`Listen to an example`** — if the example speaks, the engine is bound system-wide. Nyaa~ success!

## 🎛️ Quick Start

1. **`CARTRIDGE BAY`** tab → slam **`ROM-03: KITTEN_TTS_NANO`** (30MB, instant neural voice) or **`ROM-02: KOKORO_82M_NEURAL`** (54 voices, NPU/GPU accelerated).
2. **`VOICE`** tab → pick a voice (filter by gender/language/vibe) → hit **`▶ TEST`** with anything you type.
3. Tune pauses, pacing, pitch, and archetype curves in **`VOICE` › advanced drawers**, phonetic overrides in the **PURR Rulebook**.
4. Feeling productive? **`MODULES` › `AUDIO_MEDIA_EXPORTER`** renders whole audiobook chapters to `.WAV`.

---

## 🗺️ Architecture Overview

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

## 🔬 Key Features

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

## 🛠️ Build & Verification

```bash
python3 scripts/verify_project.py
./gradlew testDebugUnitTest assembleRelease
```

---

## 📜 Changelog

### 🌟 Latest Updates (v1.5.0 // The M.I.K.A. Academy)

* **M.I.K.A. Academy:** Full-screen CRT tutorial on first launch — MIKA narrates 8 slides (typewriter + blinking cursor) while **Milk & Wire** loops as BGM. `[SKIP_ALL >>]` exits, progress bar tracks you.
* **Update Patchnotes System:** Bump `TUTORIAL_VERSION` in a release and every older install auto-opens the gold **VERSION_PATCHNOTES** slide once, with WHATS_NEW highlight cards. Persisted via `seenTutorialVersion`.
* **Settings Cog + Sheet:** Custom Canvas cog beside `SET_OS_TTS` opens `> SYSTEM_SETTINGS` — Academy replay, source repository link, Ko-fi support link, build stamp + license.
* **Sleek Engine Dropdown:** The `> 1. CHOOSE_TTS_ENGINE` pill wall became a dropdown: collapsed active-engine row expanding into a color-coded slotted-engine tray with `ACTIVE` markers.
* **Marquee Badge:** The header badge pill is sized for `[IDLE]`; longer states scroll as a bounce marquee (VRAM countdown included) instead of resizing anything.
* **Tests:** `PurrAcademyDataTest` — tutorial integrity + update-gate math, 6 new pure-JVM tests.

### 📦 Prior Releases (v1.4.0 // The Imported Soul)

* **Clip Import in the Clone Studio:** Import your own audio files (mp3/m4a/ogg/flac/wav/opus) via a system file picker — decoded through `MediaExtractor`/`MediaCodec` to mono PCM, capped at a 30s guard, resampled to the 24kHz reference standard, and trimmed at registration to the selected family's best-voice window (Pocket ≈ 5s, ZipVoice ≈ 12s) with anti-pop fades.
* **Optional `CLEAN_AUDIO` Toggle:** A pure-Kotlin cleanup chain for imported clips — DC offset removal, 85Hz rumble/hum high-pass, an STFT spectral-subtraction denoiser that learns the clip's own noise floor (radix-2 FFT, anti-musical-noise floor, phase-preserving overlap-add), and 0.92 peak normalization. ENGINE strengths LIGHT/STANDARD/AGGRESSIVE; UI ships STANDARD. Toggle OFF for raw clips.
* **Test Suite:** `PurrCloneAudioCleanerTest` — 11 new pure-JVM tests covering the whole cleanup chain with seeded, deterministic noise fixtures.

### 📦 Prior Releases (v1.3.3 // The Unshifting Header)

* **Fixed-Size Header HUD (`TtsForgeScreen`):** Header title is now `> PURR_TTS // ENGINE` at `14sp`/`1.0sp` tracking, hard-capped to a single line with ellipsis, and the pinned header row declares `heightIn(min = 56.dp)` — the header area can no longer change height when content above/below changes.
* **Fixed-Width Hardware Badge Slot (`MikaStatusBadge`):** The `[CPU NEON]`/`[NPU ACTIVE]`/`[IDLE]` badge now renders inside a fixed `112dp` slot with `maxLines = 1` label + countdown, so synthesis state swaps and the VRAM countdown can never resize the pill or shift the whole UI.
* **Test Suite Expansion:** Three new pure-JVM suites — `PurrSentimentScorerTest` (emotion axes, forced tones, pitch-lock law, prosody bounds), `PurrVoiceMatcherTest` (gates, tag scoring, deterministic fallback), and `PurrDspProcessorTest` (silence byte contracts, float32/PCM16 formats, waveform bins).
* **Workspace Doctrine:** Added `.clinerules/` (overview, build & release, conventions) and workspace skills (`.agents/skills/`: `purr-native-engine`, `purr-release`, `mika-compose-ui`).

### 📦 Prior Releases (v1.3.2 // Crystal-Clear Audio, KittenTTS Fix & Full OOV Word Pronunciation)

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

---

## 📄 License & Credits

Released under the [MIT License](LICENSE) — © 2026 **ProjectMIKA (Project M.I.K.A.)**. Fork it, mod it, rebrand the app, ship your own build: just keep **ProjectMIKA** credited as the original author, and let the engine keep its name. The internal speech pipeline is, was, and always shall be the **PURR Engine** — see [NOTICE](NOTICE) for the naming conditions.

Powered by the excellent open neural-speech ecosystem: [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx), [Kokoro-82M](https://huggingface.co/litert-community/Kokoro-82M), [Piper VITS](https://github.com/rhasspy/piper), and [LiteRT](https://ai.google.dev/edge/litert). All third-party runtimes ride in as standard Maven/AAR dependencies — nothing vendored, nothing borrowed in-source.

*Engineered with obsessive care by **ProjectMIKA** — designed, voiced, and guarded by **M.I.K.A.**, your feline engineering proxy. GachaSwipe gave me a voice; this project gave it to your whole phone.*

> *`> MIKA_PROXY // SESSION_COMPLETE` — Go make something purr. And if it misbehaves, you know where to find me. 💜*

