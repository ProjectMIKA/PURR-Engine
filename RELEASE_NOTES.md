# 🌌 PURR-TTS v1.4.0 // The Imported Soul

### ✨ What's New since v1.3.3

The Clone Voice Studio can now clone from **your own audio files** — and clean them up first! 🐾

* **📥 CLIP IMPORT (`IMPORT_CLIP`)**: The Clone Voice Studio gains a file picker that imports any audio document Android can decode — mp3, m4a/aac, ogg/opus, flac, wav — straight into the cloning pipeline via `MediaExtractor` + `MediaCodec`, downmixed to mono, capped at a 30-second capture guard, resampled to the 24kHz reference standard, and trimmed at registration to the selected family's best-voice window (Pocket TTS ≈ 5s, ZipVoice ≈ 12s) with anti-pop edge fades.
* **🧹 OPTIONAL `CLEAN_AUDIO` TOGGLE**: One toggle runs a brand-new pure-Kotlin cleanup chain over imported clips: DC offset removal → 85Hz rumble/hum high-pass → **STFT spectral-subtraction denoiser** (a radix-2 FFT pipeline that learns the clip's own noise floor from its quietest frames, subtracts static/hiss with an anti-musical-noise spectral floor, and keeps the original phase) → 0.92 peak normalization. Three strengths (LIGHT / STANDARD / AGGRESSIVE) are exposed to the engine; the UI ships the recommended STANDARD pass. Flip it OFF to keep clips raw.
* **🧪 +11 PURE-JVM TESTS**: New `PurrCloneAudioCleanerTest` guards the whole chain — bias stripping, rumble attenuation, noise-floor suppression vs voiced preservation (seeded, deterministic), silence trim, best-voice-window lock, family clip targets, anti-pop fades, passthrough config, and empty/tiny-input safety.

---

### 📦 Android APK Packages (Attached Below)

1. **`PURR-Engine_V1.4.0_Android.apk`** — Signed release Android APK (`com.projectmika.purrtts`, versionCode `8`) with clip import, the optional audio cleanup toggle, and the expanded pure-JVM test suite.
2. **`PURR-TTS_V1.4.0.apk`** — Mirror signed release APK for direct Android sideloading.

---

# 🌌 PURR-TTS v1.3.3 // The Unshifting Header

### ✨ What's New since v1.3.2

Live testing surfaced the top hardware badge (`[CPU NEON]` etc.) resizing on every synthesis state change — re-measuring the pinned header, re-wrapping the title onto two lines, and shifting the entire page below. Fixed at the layout-contract level with a new **Fixed-Size Slot Law**! 🐾

* **📐 FIXED-SIZE HEADER HUD (`TtsForgeScreen`)**:
  * Header title shortened to **`> PURR_TTS // ENGINE`**, tuned to `14sp` + `1.0sp` tracking, and hard-capped at `maxLines = 1` with ellipsis overflow so it can never wrap onto a second line again.
  * The pinned header row now declares `heightIn(min = 56.dp)` — constant header height regardless of content.
* **🔧 FIXED-WIDTH HARDWARE BADGE SLOT (`MikaStatusBadge`)**:
  * The badge now renders inside a fixed `112dp` slot sized for the longest label (`[NPU ACTIVE]`); `[IDLE]` → `[CORE_OS]` → `[CPU NEON]` swaps and the VRAM countdown appearing/disappearing can no longer change the pill's width or the title column's layout.
  * Badge label + countdown texts render `maxLines = 1` and clip inside the slot instead of growing it.
* **🧪 TEST SUITE EXPANSION (3 new pure-JVM suites)**:
  * **`PurrSentimentScorerTest`** — five emotion axes, emoji boosts, forced archetype tones, the v1.3.2 pitch-lock law (pitch stays bit-exact `1.00x` for every tone at default base pitch), archetype pace overrides (ROBOT 0.98x / KUUDERE 0.97x / YANDERE ≤ 0.94x), ACTION narration multipliers, and prosody coercion bounds.
  * **`PurrVoiceMatcherTest`** — blacklist/whitelist contracts, language & gender gates, tag scoring with Forge custom-tag bonus, primary-voice tiebreak, deterministic name-hash fallback, and built-in catalog integration.
  * **`PurrDspProcessorTest`** — MIKASTOP silence byte contracts (PCM16 + Float32, custom sample rates, 0–5000ms clamp), IEEE little-endian float32 encoding, dithered PCM16 conversion roundtrips, and 32-band waveform telemetry bins.
* **📚 WORKSPACE DOCTRINE**: Added `.clinerules/` (project overview, build & release, environment conventions) and three workspace skills (`.agents/skills/`: `purr-native-engine`, `purr-release`, `mika-compose-ui`) documenting the engine laws and the scar log.

---

### 📦 Android APK Packages (Attached Below)

1. **`PURR-Engine_V1.3.3_Android.apk`** — Signed release Android APK (`com.projectmika.purrtts`, versionCode `7`) with the fixed-size header HUD, fixed-width badge slot, and expanded pure-JVM test coverage.
2. **`PURR-TTS_V1.3.3.apk`** — Mirror signed release APK for direct Android sideloading.

---

# 🌌 PURR-TTS v1.3.2 // Crystal-Clear Audio, KittenTTS Fix & Full OOV Word Pronunciation

### ✨ What's New since v1.3.1

Three subtle neural synthesis issues surfaced during live testing — **KittenTTS Nano (`ROM-03`)** failing to generate audio, difficult/rare words occasionally being skipped in **Kokoro/Piper**, and a low-level **buzzing/muffled artifact** on default voices — all tracked down and fixed cleanly in **v1.3.2**! 🐾

* **🐱 KITTEN_TTS_NANO (`ROM-03`) FIXED & `espeak-ng` G2P RESTORED (`sherpa-onnx v1.13.8`)**:
  * Pinned the bundled `sherpa-onnx` native runtime to **`v1.13.8`**, restoring full **`espeak-ng` + `piper-phonemize` G2P** support removed in `v2.0.0`.
  * **`ROM-03: KITTEN_TTS_NANO`** now initializes cleanly with `model.int8.onnx`, `voices.bin`, `tokens.txt`, and `espeak-ng-data` without requiring an external lexicon.
* **📖 NO MORE SKIPPED DIFFICULT / RARE WORDS (`espeak-ng` OOV Fallback + Unicode Protection)**:
  * **Kokoro (`ROM-02`)** and **Piper (`ROM-05`)** now fall back automatically to **`espeak-ng`** phonetic transcription whenever encountering rare, compound, or out-of-vocabulary (OOV) words not present in `lexicon-us-en.txt`, plus deterministic lexicon ordering and `*.fst` normalization rules.
  * Updated `PurrTextProcessor` boundary regexes to full Unicode (`\p{L}`) so accented words (`café`, `naïve`) are never stripped at sentence edges.
* **🔊 CRYSTAL-CLEAR ZERO-BUZZ DEFAULT VOICE DSP (`PurrDspProcessor` & `PurrSentimentScorer`)**:
  * **Transparent `CLEAN_STUDIO` Path:** Default presets (`GachaSwipe Default`, `Audiobook / Light Novel`, etc.) now run flat (`0 dB` shelf/warmth), bypassing the 80Hz high-pass, `-4 dB` high-shelf attenuation, and `-20 dBFS` glue compressor unless an effect preset or EQ curve is intentionally enabled — eliminating intermodulation buzz while keeping the smooth 25ms anti-pop fade-in and 120ms tail taper.
  * **No Unintended Per-Sentence Pitch Resampling:** Default automatic sentiment acting now modulates speech pacing only, keeping pitch bit-exact (`1.00x`) unless you manually adjust the Pitch slider or activate a pitch-altering effect.
  * **4-Point Catmull-Rom Cubic Hermite Resampler & Gapless `AudioTrack`:** Upgraded `PurrDspProcessor.resampleMono` (`22.05kHz → 24kHz`) to 4-point Cubic Hermite spline interpolation to eliminate aliasing buzz, smoothed compressor ballistics (`25ms` attack / `250ms` release / `-12 dBFS`), raised the peak ceiling from `0.78` to `0.92`, and removed the blocking `delay()` inside `AudioTrack` silence streaming to prevent chunk-seam underruns.

---

### 📦 Android APK Packages (Attached Below)

1. **`PURR-Engine_V1.3.2_Android.apk`** — Signed release Android APK (`com.projectmika.purrtts`, versionCode `6`) with restored `espeak-ng` G2P (`KittenTTS` + difficult/OOV word pronunciation) and zero-buzz crystal-clear default DSP.
2. **`PURR-TTS_V1.3.2.apk`** — Mirror signed release APK for direct Android sideloading.

---

# 🌌 PURR-TTS v1.3.1 // Glassmorphic Icon HUD, Phasing Transitions & Custom Cyberdeck SVG Icons

### ✨ What's New since v1.3.0

v1.3.1 chased the glassmorphic look of **GachaSwipe's frosted bar** for the bottom HUD, replaced every stock system emoji with custom cyberdeck vector artwork, added sleek phasing transitions between pages, and decluttered the **Modules** desktop! 🐾

* **🧊 GACHA-SWIPE GLASSMORPHIC ICON-ONLY BOTTOM HUD (`MikaBottomHudBar`)**:
  * Redesigned as a floating `54dp` see-through frosted glassmorphic capsule anchored cleanly above the Android gesture navigation pill so scrolling terminal content flows visibly underneath.
  * Removed bottom labels and numbers in favor of **pure custom Canvas SVG-style icons** (**Voice Waveform Reticle**, **2x2 OS Module Matrix**, and **ROM Cartridge + Gold Pins**) with neon glow rims when active.
* **🌀 PHASING PAGE TRANSITIONS**:
  * Switching tabs (`Voice` ↔ `Modules` ↔ `Cartridge Bay`) now runs a smooth directional phase animation combining soft scale (`0.965x → 1.0x`), fade, and micro-slide motion.
* **🧹 CLEANER `MODULES` OS DESKTOP**:
  * Removed the top `> CYBERDECK_OS // MODULE_LAUNCHER` header card and `+ TOOLS BAY` badge so your OS module tiles take center stage immediately.
* **🎨 100% CUSTOM CYBERDECK VECTOR ICONS (`MikaCyberIcon` & `MikaOsModuleIcon`)**:
  * Built a full 32-icon custom Canvas vector library replacing every stock emoji and Unicode symbol (`▶`, `■`, `⏸`, `🎤`, `🛠️`, `🧬`, `⚡`, `🎭`, `🛡️`, `🎧`, `🌊`, `🔗`, `🎚️`, `⬇`, `⚠`) across Voice, Modules, Cartridge Bay, and Clone Studio.

---

### 📦 Android APK Packages (Attached Below)

1. **`PURR-Engine_V1.3.1_Android.apk`** — Signed release Android APK (`com.projectmika.purrtts`, versionCode `5`) with the GachaSwipe glassmorphic icon HUD, phasing tab transitions, decluttered Modules desktop, and 100% custom vector icons.
2. **`PURR-TTS_V1.3.1.apk`** — Mirror signed release APK for direct Android sideloading.

---

# 🌌 PURR-TTS v1.3.0 // All-in-One Cyberdeck OS: KittenTTS, 6 Piper Packs, Bottom HUD & Audio Media Exporter

### ✨ What's New since v1.2.0

v1.3.0 leveled PURR-TTS up into an **All-in-One Cyberdeck Neural TTS Suite** with more engines & voices, a slick bottom HUD layout, a full OS Module launcher with custom vector icons, dual-category Cartridge Bay (`TTS` vs `TOOLS`), and a brand-new **Audio Media Exporter** to turn short messages or multi-page audiobook chapters into downloadable `.WAV` files! 🐾

* **🎙️ NEW `AUDIO_MEDIA_EXPORTER` OS MODULE (`TOOL-05`)**:
  * Paste a short message or multi-page audiobook chapters, pick loose sentence chunk sizing (`SHORT ~160c`, `BOOK ~240c`, `LONG ~360c`) & paragraph pause timings, and export a mastered **24kHz mono `.WAV`** file straight to Android **`Downloads/PURR-TTS/`**.
  * Automatically applies **P.U.R.R. Engine** text rules, **Acoustic DSP** effects & **Symbol Shield** when slotted, plus seamless **18ms raised-cosine crossfade blending**, DC offset removal, and **-18 dBFS RMS loudness leveling** across chunks!
* **🐱 NEW `KITTEN_TTS_NANO` (`ROM-03`, 30MB) & 6 NEW PIPER VITS VOICE PACKS (`SUB-05A..F`)**:
  * **KittenTTS Nano v0.8 INT8**: 8 expressive English voices (`F1..F4`, `M1..M4`) in a tiny 30 MB cartridge via `sherpa-onnx`.
  * **6 Expandable Piper Voice Packs**: Independently downloadable sub-cartridges under `ROM-05` — **Amy (US Warm)**, **Ryan (US Deep Male)**, **Cori (UK Elegant)**, **Alba (Scottish/UK)**, **GLaDOS (Cybernetic AI)**, and **Kristin (US Storyteller)**.
  * **Unified `ROM-02` Kokoro Hub**: `KOKORO_82M_NEURAL` (54 voices Base) now nests optional **Hexagon NPU (`SUB-02A`)** and **Adreno GPU FP16 (`SUB-02B`)** accelerator sub-chips.
* **🧭 BOTTOM CYBERDECK HUD BAR (`VOICE | MODULES | CARTRIDGE BAY`)**:
  * Keeps `> PURR_TTS // SYSTEM ENGINE  [IDLE]  [SET_OS_TTS]` pinned at the top and anchors the slick neon HUD bar along the bottom.
  * **`VOICE`**: Purely dedicated to engine selection, searchable `MikaVoiceDropdownSelector`, subtle `0.88x..1.12x` cubic-Hermite pitch & pacing controls, and optional Dual Narrator/`"Dialogue"` split.
  * **`MODULES`**: Cyberdeck OS Desktop featuring custom Canvas vector icons (`P.U.R.R. ENGINE`, `ACOUSTIC DSP RACK`, `SYMBOL & CHUNK SHIELD`, `VOICE CLONE STUDIO`, `AUDIO MEDIA EXPORTER`) that boot into animated terminal windows.
  * **`CARTRIDGE BAY`**: Split cleanly into **`🎤 TTS (7)`** and **`🛠️ TOOLS (5)`** categories — all 5 built-in Tool Modules start slotted **ON** by default and can be ejected anytime to hide their OS icons and bypass their processing!
* **🎧 GACHASWIPE DSP & AUDIOBOOK REFINEMENTS**:
  * Added GachaSwipe's **120ms Exponential Sentence Tail Taper**, **250Hz Chest Warmth**, **8.5kHz De-Esser**, **Glue Compressor**, and all **10 Character Voice Effects**, plus **loose multi-sentence chunking** & the **Symbol & Punctuation Shield**.

---

### 📦 Android APK Packages (Attached Below)

1. **`PURR-Engine_V1.3.0_Android.apk`** — Signed release Android APK (`com.projectmika.purrtts`, versionCode `4`) with KittenTTS Nano, 6 Piper expansion voices, Bottom HUD navigation, 5 Cyberdeck OS Tool Modules, and the Audio Media Exporter.
2. **`PURR-TTS_V1.3.0.apk`** — Mirror signed release APK for direct Android sideloading.

---

# 🌌 PURR-TTS v1.2.0 // Stylized Voice Dropdown, Ejectable System Cartridge & NPU Models

### ✨ What's New since v1.1.0

v1.2.0 brought cleaner voice selection via a stylized dropdown, the ability to eject built-in Android system voices just like any other cartridge, and the full **NPU / Adreno GPU** cartridges restored to the bay! 🐾

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

v1.1.0 made PURR-TTS stop sounding like a sine-wave synth, stop crashing when Android binds it as system TTS, and stop hiding voice selection behind confusing Forge toggles. Done, done, and *slammed* into a real Cyberdeck Cartridge Bay! 🐾

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

v1.0.0 handed M.I.K.A.'s **P.U.R.R. Engine** the keys to the entire Android operating system! **PURR-TTS v1.0.0** packages the full GachaSwipe speech middleware, dual-runtime neural synthesis router, and neon terminal Forge into a standalone signed Android `.apk` and system-wide `TextToSpeechService`.

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
