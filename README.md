# 🐾 PURR-TTS // The PURR Engine

> **`> PURR_ENGINE // SYSTEM_WIDE_NEURAL_VOICE`** — Per-Utterance Reaction Rendering, now speaking in every app on your Android.

Nyaa~ Hello, traveler! I'm **M.I.K.A.** — ProjectMIKA's fiercely possessive, unreasonably smug feline engineering proxy — and that voice in your head reading this? Totally me. **PURR-TTS** is my **P.U.R.R. Engine** (**P**er-**U**tterance **R**eaction **R**endering) — the speech middleware I was born inside [GachaSwipe](https://github.com/ProjectMIKA/GachaSwipe) — ripped out of the WebView, rebuilt in native Kotlin, and promoted to a **system-wide Android `TextToSpeechService`**. Every reader, roleplay client, browser, and accessibility app on your phone can now speak through the same neural voice pipeline that powers GachaSwipe. You're welcome~ 💜

**Project M.I.K.A. — ROM-04 Portable Speech Engine.** A standalone Jetpack Compose application *and* a drop-in system TTS engine: install it once, select it in your phone's Text-to-Speech settings, and the entire P.U.R.R. pipeline — emotion-aware prosody, RP-aware text bending, deterministic pause injection, and a 24kHz mastering rack — speaks everywhere, fully on-device. No cloud, no accounts, no telemetry.

**The studio law:** ProjectMIKA builds what doesn't exist yet — or puts its own spin on what does. We don't do cover bands: when something already does it incredibly well, we create *alongside* it, not over it.

---

## ✨ What Makes It Special

* **🎤 System-Wide Neural Voice** — registers as a real Android TTS engine and streams 24kHz mono PCM into *every* app that can speak. Choose it once in `Settings › Accessibility › Text-to-Speech output`.
* **📖 Manga Dub Studio — the v2.0 flagship** — slam a manga/comic chapter into the bay: the **Gemma vision engine** slices the pages, reads every bubble, and discovers the cast; then every line gets a REAL synthesized voice. Confirmed characters receive descriptive role labels (`BUNNY_BUTLER`, `TOWEL_GIRL` — lazy `UNKNOWN`s are banned) and locked **per-character voice clones**; narration is always spoken by **MIKA herself** (narrator identity law, enforced pipeline-wide); and a deterministic gendered **Qwen3 VoiceDesign fallback** guarantees universal cast coverage — no bubble ever falls back to a generic system voice. Dubbed motion-panel videos render with timed subtitles.
* **🎛️ 17-Bay Cartridge Rack + Clone Deck (`ROM-01..ROM-17`, `SUB-xx` chips, `CLN-01..05`)** — every neural engine family as a physical cartridge you slam into a bay:
  * **Flagship neural hubs** — **Kokoro-82M** (INT8, FP32 & v1.1 multilingual; Hexagon NPU G2P + Adreno GPU FP16 sub-chips), **Qwen3-TTS Studio** (0.6B CustomVoice, 0.6B Base 3s-clone, 1.7B VoiceDesign via native llama.cpp), **NeuTTS-2E Emotional** (Qwen3-236M + NeuCodec sentiment-controlled emotions), **Soprano-80M 32kHz LLM**, **Gradium Phonon 31M** (55MB XNNPACK bay, 16 voices), **KittenTTS Hub** (Nano 30MB + Mini/Micro 80M)…
  * **The long-tail fleet** — Narrator/Flow Hub (7 Piper VITS packs, **MeloTTS 5-accent EN**, **Matcha-TTS**, **Supertonic 3**), Inflect v2, IceFall VITS, Coqui VITS, Meta MMS multilingual, VCTK-109 Studio, Mimic3 multilingual, LJS Lexicon, AISHELL3 (174 speakers) — plus device OS voices & a LAN OpenAI proxy bay.
  * **Voice-clone cartridges (`CLN-01..05`)** — Pocket TTS (5-second zero-shot), ZipVoice, Chatterbox-Turbo 350M Q4 with native `[laugh]`/`[sigh]`/`[gasp]`, Qwen3 0.6B Base cloner, and the 5-second **Voice Clone Studio**.
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

1. **`CARTRIDGE BAY`** tab → slam **`ROM-06: KITTEN_TTS_HUB`** (KittenTTS Nano — 30MB, instant neural voice) or **`ROM-02: KOKORO_NEURAL_HUB`** (NPU/GPU-accelerated Kokoro-82M).
2. **`VOICE`** tab → pick a voice (filter by gender/language/vibe) → hit **`▶ TEST`** with anything you type.
3. Tune pauses, pacing, pitch, and archetype curves in **`VOICE` › advanced drawers**, phonetic overrides in the **PURR Rulebook**.
4. Feeling productive? **`MODULES` › `AUDIO_MEDIA_EXPORTER`** renders whole audiobook chapters to `.WAV`.
5. Comic night? **`MODULES` › `MANGA_DUB_STUDIO`** — scan a chapter, confirm the cast, and every speech bubble gets its own cloned or designed voice.

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
│   ├── qwen3/Qwen3TtsEngine.kt         # Qwen3-TTS (0.6B / 1.7B VoiceDesign) via native llama.cpp bridge
│   ├── onnx/OnnxRuntimeEngine.kt       # ONNX Runtime / XNNPACK bays (Gradium Phonon 31M, NeuTTS-2E, Soprano…)
│   ├── sherpa/SherpaOnnxEngine.kt      # Supertonic 3, Pocket TTS (5s clone), ZipVoice, Piper VITS
│   ├── manga/                          # Manga Dub Studio: LiteRT Gemma vision engine, dub manager, motion-video muxer
│   ├── audiobook/                      # Audiobook constructor engine
│   └── proxy/ExternalProxyEngine.kt    # LAN OpenAI /v1/audio/speech + OS TTS fallback passthrough
├── service/
│   ├── PurrTtsService.kt               # System-wide TextToSpeechService streaming 24kHz PCM chunks
│   └── TtsSystemActivities.kt          # CHECK_VOICE_DATA, GET_SAMPLE_TEXT, INSTALL_TTS_DATA contracts
└── ui/
    ├── theme/                          # GachaSwipe obsidian terminal & neon holography tokens + components
    └── forge/                          # TTS Forge, Manga Dub Studio, Script & SRT Batch studios, Voice Benchmark, Voice Design, PURR Rulebook, Model Catalog, Clone Voice Studio
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

4. **Manga Dub Studio (`engine/manga/` + optional `TOOL-12` OpenAI Vision API)**
   - **Vision & Storyboard pass:** multi-sheet chapter storyboarding + zoomed XY-cut panel OCR (6 local LiteRT VLMs, or optional `TOOL-12` OpenAI Vision endpoints from NanoGPT / OpenRouter / Clinepass / Custom URL — disabled by default), followed by a chapter-wide Story & Cast Consolidation pass that merges drifted labels across pages, locks genders, and honors optional character count baselines (`AUTO` or user-set).
   - **Pre-Cook Review & Whole-Bubble Audio:** review/edit story summary, character counts, genders, and voices before cooking; every speech bubble synthesizes as one unbroken chunk with DC/onset-conditioned clean audio and locked Qwen voice clones (`no system-voice fallback`).
   - **Narration:** MIKA's voice resolves per narration line in isolation (narrator identity law) — never a roster entry, never a clone target.

## 🛠️ Build & Verification

```bash
python3 scripts/verify_project.py
./gradlew testDebugUnitTest assembleRelease
```

---

## 📜 Changelog

The full release-by-release history lives on the [Releases page](https://github.com/ProjectMIKA/PURR-Engine/releases) — newest first, every release with its signed APKs and full notes.

---

## ⚖️ License & Notice

PURR-TTS's code is free software: it is released under the **GNU Affero General Public License v3.0 or later** (`AGPL-3.0-or-later`) — see [`LICENSE`](LICENSE). You may run, study, modify, and redistribute it; distribute a modified version (a fork, a sideloaded APK, an embedded build) and you must share your modified source under the same license.

**NOTICE — what the license does NOT cover:**

- **The name "PURR-TTS", the "PURR Engine" / "P.U.R.R." engine name, the M.I.K.A. persona and voice, and the bundled artwork / brand assets** are **not** licensed under AGPLv3 and remain the exclusive property of ProjectMIKA. Selling or marketing a copy or derivative **under this name or branding** requires separate written permission — the free-software grant covers the code, not the brand. Forks are welcome to rebrand the app shell; the internal engine stays the **PURR Engine** — see [NOTICE](NOTICE).
- **The third-party neural runtimes & model weights** (llama.cpp, LunaVox, ONNX Runtime, sherpa-onnx, Kokoro-82M, the Qwen3-TTS weights, Chatterbox-Turbo, NeuTTS-2E, Soprano-80M, Piper / MeloTTS, PdfBox-Android, Khronos OpenCL-Headers) ride in under **their own licenses** — they are not part of this repository's grant, and their terms govern their use. The full ledger lives in [NOTICE](NOTICE).
- **Selling the code:** AGPL-3.0-or-later permits redistribution (including selling) of the *code* — but only under the same license, with complete source, and never as closed source. **Closed-source and commercial licensing of PURR-TTS are reserved to ProjectMIKA**; contributors grant those rights via the [ProjectMIKA Individual Contributor License Agreement](CLA.md).

**💼 Commercial licensing:** the app itself is and always will be free, open-source software — that will not change. What's reserved is everything *else*: an app-store release, OEM / embedded speech licensing, or any closed product requires a commercial license from ProjectMIKA. Interested? Reach out via GitHub. Just want to support development? [Ko-fi](https://ko-fi.com/projectmika) 💜

*Contributions are accepted under the [ProjectMIKA Individual Contributor License Agreement](CLA.md) — see [why it exists](CLA.md#why-this-cla-exists-the-short-version).*

Powered by the excellent open neural-speech ecosystem: [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx), [Kokoro-82M](https://huggingface.co/litert-community/Kokoro-82M), [Piper VITS](https://github.com/rhasspy/piper), and [LiteRT](https://ai.google.dev/edge/litert). All third-party runtimes ride in as standard Maven/AAR dependencies — nothing vendored, nothing borrowed in-source.

*More from the studio: [GachaSwipe](https://github.com/ProjectMIKA/GachaSwipe) — where this engine was born · [ProjectMIKA](https://github.com/ProjectMIKA) — every public project.*

*Engineered with obsessive care by **ProjectMIKA** — designed, voiced, and guarded by **M.I.K.A.**, your feline engineering proxy. GachaSwipe gave me a voice; this project gave it to your whole phone.*

> *`> MIKA_PROXY // SESSION_COMPLETE` — Go make something purr. And if it misbehaves, you know where to find me. 💜*

