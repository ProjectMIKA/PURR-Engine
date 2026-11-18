# 🌌 PURR-TTS v1.2.0 // Stylized Voice Dropdown, Ejectable System Cartridge & NPU Models

### ✨ What's New since v1.1.0

Master wanted cleaner voice selection via a stylized dropdown, the ability to eject built-in Android system voices just like any other cartridge, and the full **NPU / Adreno GPU** cartridges restored to the bay! 🐾

* **🎛️ STYLIZED CYBERDECK VOICE DROPDOWN (`MikaVoiceDropdownSelector` in `> VOICE & ENGINE`)**:
  * Replaced the long vertical voice card list in Tab 1 with a compact stylized dropdown selector displaying `[GENDER • LANG]`, native vibe tags, and inline **`▶ TEST`** + **`▼ CHANGE`** triggers.
  * Expanding the tray opens an instant live search box, `ALL / FEMALE / MALE / ANDROGYNOUS` filter pills, match counter, and a smooth scrollable voice list with mini **`▶`** audition buttons — both for single-voice mode and for the Narrator / `"Dialogue"` selectors!
* **🔌 EJECTABLE SYSTEM VOICES CARTRIDGE (`ROM-01: ANDROID_OS_SPEECH`)**:
  * Built-in Android OS speech is now **ROM-01** in the **8-Bay Cartridge Rack** (slotted by default with zero download required, automatically migrating existing saves cleanly).
  * Hit **`▲ EJECT CARTRIDGE (DISABLE OS)`** anytime to completely unmount system voices from Tab 1, `PurrTtsService.onGetVoices()`, and failover synthesis paths — or **`▼ SLAM CARTRIDGE IN (ENABLE OS VOICES)`** to bring them back!
* **⚡ RESTORED HARDWARE NPU & GPU FP16 CARTRIDGES (`ROM-02` & `ROM-03`)**:
  * **ROM-02 (`KOKORO_82M_NPU_GPU`)**: `litert-community/Kokoro-82M` (`kokoro_int8_gpu_fp16.litertlm`, 54 voices, Adreno GPU FP16 + Hexagon NPU ready with persistent shader cache & 5-minute VRAM eviction).
  * **ROM-03 (`KOKORO_G2P_HEXAGON_NPU`)**: `litert-community/Kokoro-G2P-en-US` (`dp_g2p_litert.tflite`, Snapdragon Hexagon NPU / QNN HTP accelerated grapheme-to-phoneme model) — plus `[NPU_ACTIVE]` hardware telemetry acceleration across `TtsBackendRouter` when paired with `Kokoro-82M`!

---

### 📦 Android APK Packages (Attached Below)

1. **`PURR-Engine_V1.2.0_Android.apk`** — Signed release Android APK (`com.projectmika.purrtts`, versionCode `3`) with stylized voice dropdown, 8-bay Cartridge Rack (`ROM-01`..`ROM-08`), ejectable System OS cartridge, and Hexagon NPU / Adreno GPU cartridges.
2. **`PURR-TTS_V1.2.0.apk`** — Mirror signed release APK for direct Android sideloading.

---

# 🌌 PURR-TTS v1.1.0 // The Cartridge Bay Overhaul

### ✨ What's New since v1.0.0

Master wanted PURR-TTS to stop sounding like a sine-wave synth, stop crashing when Android binds it as system TTS, and stop hiding voice selection behind confusing Forge toggles. Done, done, and *slammed* into a real Cyberdeck Cartridge Bay! 🐾

* **🛠️ FIXED SYSTEM TTS BINDER CRASH (`PurrTtsService` + `CheckVoiceDataActivity`)**:
  * **Root-Cause Init Fix**: Android's `TextToSpeechService.onCreate()` calls `onLoadLanguage()` synchronously inside `super.onCreate()`. Dependencies (`repository` & `backendRouter`) now initialize **before `super.onCreate()`**, eliminating the `UninitializedPropertyAccessException` crash when selecting PURR-TTS in Android Settings.
  * **Added `onLoadVoice()` & Safe ISO-3 Locales**: Implemented `onLoadVoice(voiceName)` so custom voice IDs never fail AOSP locale parsing, guarded ISO3 metadata calls, and returned clean `eng-USA` / `eng-GBR` / `jpn-JPN` codes in `CHECK_VOICE_DATA`.
* **🗣️ 100% COHERENT REAL SPEECH (GOODBYE MUSICAL NOTES!)**:
  * **Deleted Synthetic Sine-Wave Preview Tones**: Completely removed the placeholder tone generators so the app never plays musical notes when testing voices.
  * **Bundled `sherpa-onnx-2.0.1.aar` JNI + Instant `CORE_OS` Device Speech Bridge**: Works immediately out of the box with zero downloads via your phone's built-in Android speech engine (with automatic device voice discovery + RIFF WAV parsing + direct audition fallback), AND runs **Kokoro-82M INT8 (54 voices)**, **Supertonic 3**, **Piper VITS**, **Pocket TTS**, and **ZipVoice** 100% offline via real bundled `sherpa-onnx` JNI!
* **🎛️ STREAMLINED 3-TAB UI (SIMPLE FIRST, ADVANCED EXPANDABLE)**:
  * **Tab 1 — `> VOICE & ENGINE`**: Replaced Whitelist/Blacklist clutter with a clean 2-step engine & preferred voice selector, `▶ TEST` button on every voice, an optional **`🎭 Split Narrator & "Dialogue" Voices`** toggle, and an expandable **`▸ ADVANCED: SPEED, PITCH & CLONE STUDIO`** drawer.
  * **Tab 2 — `> PURR ENGINE`**: Six one-tap vibe presets (`★ GachaSwipe Default`, `Audiobook / Light Novel`, `Tsundere / Bratty`, `Yandere / Obsessive`, `Shy / Dandere`, `Clean & Fast`) + 4 simple feature switches (`Slang & Honorifics`, `MIKASTOP Pauses`, `Emotion Acting`, `Studio Audio Polish`) + collapsed advanced drawers for `MIKASTOP` timings, Archetype/DSP sliders, and GachaSwipe's ~100-entry Phonetic Rulebook.
  * **Tab 3 — `> CARTRIDGE BAY`**: Styled straight from GachaSwipe's `MODULES` tab! Features a **6-Bay Cyberdeck Motherboard Pin Rack Visualizer**, physical molded ROM Cartridges (`ROM-01`..`ROM-06`) with 16 gold PCB pins, full-screen **`▼ SLAM CARTRIDGE IN`** / **`▲ EJECT CARTRIDGE`** animations, and SHA-256 verified downloads.

---

### 📦 Android APK Packages (Attached Below)

1. **`PURR-Engine_V1.1.0_Android.apk`** — Signed release Android APK (`com.projectmika.purrtts`, versionCode `2`) with bundled `sherpa-onnx` JNI, fixed system `PurrTtsService`, and the Cartridge Bay UI.
2. **`PURR-TTS_V1.1.0.apk`** — Mirror signed release APK for direct Android sideloading.

---

# 🌌 PURR-TTS v1.0.0 // The Neural Voice Forge

### ✨ What's New in v1.0.0 (Initial Android System-Wide Release)

Master finally gave M.I.K.A.'s **P.U.R.R. Engine** the keys to the entire Android operating system! **PURR-TTS v1.0.0** packages the full GachaSwipe speech middleware, dual-runtime neural synthesis router, and neon terminal Forge into a standalone signed Android `.apk` and system-wide `TextToSpeechService`.

* **🎤 SYSTEM-WIDE ANDROID `TextToSpeechService` (`PurrTtsService`)**:
  * Registers natively under `Settings > Accessibility > Text-to-speech output` (`android.intent.action.TTS_SERVICE`), streaming **24kHz mono PCM16 / Float32** chunks into Android's `SynthesisCallback` across every reader, RP client, browser, and accessibility app on your phone.
  * Implements the full Android OS voice contract (`CHECK_VOICE_DATA`, `GET_SAMPLE_TEXT`, `INSTALL_TTS_DATA`, `CONFIGURE_ENGINE`, and `MANAGE_VOICES`).
* **⚡ DUAL-RUNTIME NEURAL BACKEND ROUTER (`TtsBackendRouter`)**:
  * **Primary Hardware-Accelerated (`LiteRtKokoroEngine`)**: Targets `litert-community/Kokoro-82M` (`kokoro_int8_gpu_fp16.litertlm`, 54 voices, 24kHz mono) + `litert-community/Kokoro-G2P-en-US` (`dp_g2p_litert.tflite`) via Google AI Edge LiteRT-LM / MediaPipe Tasks, configuring persistent OpenCL/QNN shader caching (`--cache_dir`) and a **5-minute idle VRAM eviction timer** so Android `lmkd` never terminates the background service.
  * **Secondary Multi-Engine & Zero-Shot Cloning (`SherpaOnnxEngine`)**: Supports **Supertonic 3**, **Pocket TTS** (5-second zero-shot cloning via `synthesizeCloned`), **ZipVoice**, and **Piper VITS**, automatically resampled to 24kHz.
  * **Fallback / Passthrough Proxy (`ExternalProxyEngine`)**: Routes PURR-processed text to a LAN OpenAI `/v1/audio/speech` endpoint or a secondary Android TTS engine while keeping `MIKASTOP` silences and 24kHz post-DSP active.
* **🧬 THE P.U.R.R. MIDDLEWARE PIPELINE (`PurrTextProcessor` + `PurrSentimentScorer` + `PurrDspProcessor`)**:
  * **MIKASTOP Deterministic Silence Injection**: Splits ellipses (`...`), em-dashes (`—`), `[pause:500ms]`, sentences, and breath commas into `MIKASTOP` chunk boundaries and injects exact zero-filled 24kHz PCM silence buffers instead of letting neural models hallucinate on punctuation trails.
  * **Action vs. Dialogue Splitter**: Separates `*action blocks*` from `"dialogue"` to strip stage directions, narrate actions softer/slower, or dual-voice route dialogue to a secondary Character Voice ID.
  * **Slang, Honorifics & Organic Stutters**: Expands ~100 texting slang tokens (`omg`, `lol`, `fr`, `wdym`), anime honorifics (`-san`, `-chan`, `-senpai`, `nya~`), custom regex overrides, and stutters (`w-what` → `wuh-what`) with strict `"mika"` immunity.
  * **Sentiment Prosody & 24kHz Post-DSP**: Live `joy`/`love`/`anger`/`sadness`/`flustered` scoring modulates chunk `speakingRate` and pitch, followed by Biquad Warmth/Presence EQ, Schroeder room reverb, and peak normalization.
