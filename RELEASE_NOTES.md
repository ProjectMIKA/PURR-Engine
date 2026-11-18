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
* **🖥️ GACHASWIPE TERMINAL FORGE UI (`> TTS_FORGE // ROM-04`)**:
  * Three dedicated terminal tabs (**`> TTS_FORGE`**, **`> PURR_RULEBOOK`**, **`> MODEL_CATALOG`**) with `#050308` scanline CRT stage, single animated hardware status badge (`[NPU ACTIVE]`, `[GPU FP16]`, `[IDLE]`), 32-band 24kHz live oscilloscope, whitelist/blacklist voice toggles, tag pill filters, resumable SHA-256 model downloads, and the **`🧬 CLONE_VOICE_STUDIO`** 5-second microphone recorder.

---

### 📦 Android APK Packages (Attached Below)

1. **`PURR-Engine_V1.0.0_Android.apk`** — Signed release Android APK (`com.projectmika.purrtts`) with system-wide `PurrTtsService` and full Compose Terminal Forge UI.
2. **`PURR-TTS_V1.0.0.apk`** — Mirror signed release APK named for direct sideloading / Android TTS Forge distribution.
