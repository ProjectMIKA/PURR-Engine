---
name: purr-native-engine
description: PURR-TTS native Kotlin engine bible — middleware pipeline, MIKASTOP, sentiment prosody, DSP rack, dual-runtime router, service crash laws, model catalog, and the scar log. Use when working on anything under core/purr, engine/, or service/ in the PURR-Engine repo.
---

# ✨ MIKA'S NATIVE P.U.R.R. ENGINE (PURR-Engine Kotlin twin) ✨

This is the native Android reference implementation of GachaSwipe's P.U.R.R. speech middleware.
The JS bible lives in the global `purr-tts-engine` skill; THIS doc covers the Kotlin specifics,
contracts, and scars. GachaSwipe = behavioral origin; keep parity when porting.

## Middleware pipeline order (`PurrTextProcessor.process`)
1. Sentiment scoring + Tone Override Drama Matrix (`PurrSentimentScorer.score`).
2. `*action blocks*` vs `"dialogue"` segmentation (`ActionHandlingMode`: NARRATE_SOFT /
   STRIP_ACTIONS / DUAL_VOICE_ROUTING; dual split routes dialogue → `dialogueVoiceId`).
3. Slang (~100 entries), honorifics (`senpai→sen-pie`, `chan→chahn`, `nya~→nyaah`), organic
   stutters (`w-what→wuh-what`), **strict `"mika"` immunity** (never rewrite MIKA).
4. MIKASTOP tokenizer: `...`, `—`, `[pause:Nms]`, sentences, breath commas → `PurrChunk.Speech`
   / `PurrChunk.Silence` alternating stream. Silences MERGE with adjacent silences, capped 3000ms.
5. Sanitize per chunk: Hallucination Shredder, Acronym Assassin (whitelist-aware), Symbol &
   Punctuation Shield (`CommaHandlingMode`: MIKA_PAUSE_AND_STRIP / STRIP_NO_PAUSE / KEEP_RAW),
   mixed-bang collapse, multi-dot collapse, terminal punctuation guarantee.
- Boundary regexes are full Unicode `\p{L}` (v1.3.2: `café`/`naïve` never stripped at edges).
- Compound hyphen words use positive lookahead (v1.3.2: `co-worker`, `state-of-the-art` intact).

## Sentiment & prosody (`PurrSentimentScorer`)
- Five axes: joy / love (1.2 weight) / anger (1.3) / sadness (1.2) / flustered (1.25 incl.
  `^[a-z]{1,2}-[a-z]+$` stutter regex). Intensifiers (`!!`, `?!`, `so/very/really`, `~`) boost.
- Emoji boosts: ❤️💖💕🥰✨🐾 → love+2/joy+1; 💢😡🤬 → anger+2; 😢😭🥺💔 → sadness+2; 😳🙈💦?! → flustered+1.5.
- Dominant label requires ≥ 0.25, else NEUTRAL. Denominator = min(6, wordCount).
- `computeProsodyModulation` coerces rate [0.65,1.5], pitch [0.88,1.12], volume [0.30,1.25].
- **PITCH-LOCK LAW**: when `basePitch ≈ 1.0`, archetype pace overrides may slow rate but must NOT
  touch pitch (guarded by `pitchOffsetAllowed = basePitch !in 0.995..1.005`). ROBOT pins
  rate 0.98/pitch 1.0; KUUDERE 0.97; YANDERE ≤ 0.94; DOMINANT_LOVE ≤ 0.95.
- ACTION chunks multiply rate by `narrationRateMultiplier` (0.92) and volume by
  `narrationVolumeMultiplier` (0.78).

## DSP rack (`PurrDspProcessor`, all 24kHz mono float32)
- Order: 25ms anti-pop fade-in → 120ms exponential sentence tail taper → (only if EQ/effect
  active) 80Hz HP → 250Hz warmth peak (Q1.5) → 8.5kHz de-esser high-shelf → glue compressor
  (-20dBFS, 2.5:1, 25ms/250ms) → Schroeder 6-comb + allpass reverb → effect preset chain
  (10 presets) → soft limiter (-1dB), peak ceiling 0.92.
- **CLEAN_STUDIO transparency**: `AcousticEffectPreset.NONE` + 0dB warmth/presence + 0 reverb
  bypasses ALL filtering/compression — bit-exact pass-through (tested!). Never add unconditional
  processing to the default path (v1.3.2 buzz scar).
- Resampler: 4-point Catmull-Rom Cubic Hermite (`resampleMono`, e.g. 22050→24000;
  `shiftPitchCubic` clamped 0.88..1.12). NEVER replace with linear interpolation.
- `computeWaveformBins` = per-lane max amplitude, coerced [0,1] — feeds router telemetry.

## Router (`TtsBackendRouter`)
- `synthMutex` serializes EVERY chunk; effective rate coerced [0.6,1.8], pitch [0.88,1.12].
- Voice-ID routing: `core_os_sys:` prefix or `core_os_auto` → CORE_OS; cloned voice IDs →
  SHERPA_ONNX (Pocket/ZipVoice); Kokoro regex `^[abjefhipz][fm]_[a-z]+$` → Kokoro engines with
  NPU sub-cartridge (`rom_kokoro_npu_g2p` + `isNpuG2pReady`) / LiteRT GPU (`rom_kokoro_litert`)
  gating; otherwise slotted-cartridge descriptor engine; else preferred backend; fails soft to
  CORE_OS when `rom_core_os` slotted. Empty PCM outcome → router falls back, never fake tones.
- Telemetry StateFlow: badge, 32 waveform bins, chunk label `[ROLE] text…`, engine summary,
  sentiment label, VRAM countdown seconds.

## Engines
- **LiteRtKokoroEngine** — `litert-community/Kokoro-82M` (`kokoro_int8_gpu_fp16.litertlm`, 54
  voices) + `Kokoro-G2P-en-US` (`dp_g2p_litert.tflite`). Persistent OpenCL/QNN `--cache_dir` in
  `context.cacheDir`. 5-min idle eviction (`IDLE_VRAM_EVICTION_MS`) resets on activity; emits
  `vramRemainingSeconds` for the UI countdown.
- **SherpaOnnxEngine** — 6 families: KOKORO (INT8 54 voices), KITTEN_TTS (Nano v0.8 INT8,
  `model.int8.onnx` + `voices.bin` + `tokens.txt` + espeak-ng-data — needs sherpa **v1.13.8**),
  SUPERTONIC_3, PIPER (multi-voice collections, 22050Hz → resampled), POCKET_TTS
  (`synthesizeCloned`, 5s zero-shot), ZIPVOICE (flow-matching clone). Idle eviction mirror.
- **ExternalProxyEngine** — LAN OpenAI `/v1/audio/speech` (PCM response) OR OS TTS
  `synthesizeToFile` + RIFF parse / direct `speak()` audition. MIKASTOP + DSP stay active.
  **Never self-bind to `com.projectmika.purrtts`.**

## Service & OS contract (`PurrTtsService`)
- Init `repository`/`backendRouter` BEFORE `super.onCreate()` (AOSP calls `onLoadLanguage`
  synchronously inside `super.onCreate()` — v1.1.0 crash scar).
- Override `onLoadVoice(voiceName)`; guard ISO3 locale conversions via `normalizeLangTag`
  (`eng`→`en`, `USA`→`US`, default fallbacks). Never leak exceptions on binder threads.
- Synthesis: chunks → `float32ToPcm16Le`/`float32ToByteArrayLe` → `streamToCallback` in
  `callback.maxBufferSize` packets; check `isStopped` between packets; NO blocking `delay()`
  inside the AudioTrack loop (v1.3.2 underrun scar). `callback.done()` on success AND
  cancellation; `callback.error()` on failure; always `router.markIdle()` in `finally`.
- `resolveEffectiveProfile`: requested `voiceName` overrides profile only if the voice exists
  AND its cartridge is slotted.

## PREMIUM TIER + HEAVY-MODEL BRIDGE (v1.6.0)
- Catalog: ROM-08 `rom_kokoro_fp32` (kokoro-multi-lang-v1_0 fp32, 54 voices — same speaker IDs
  as ROM-02), ROM-09 `rom_kokoro_v11` (kokoro-int8-multi-lang-v1_1, 103 speakers), ROM-10
  `rom_matcha_zh_en` (matcha-icefall-zh-en + vocos-22khz-univ vocoder). Real SHA-256 digests
  pulled from the k2-fsa release API (`gh api .../releases/tags/tts-models --jq '.assets[].digest'`).
- **Dir collision law**: Kokoro variants extract to `kokoro_${spec.id}` (ROM-02 keeps plain
  `kokoro`); `SherpaOnnxEngine.familyDir(KOKORO, subCartridgeId)` mirrors it via voice
  `requiredSubCartridgeId` — three model.onnx files must NEVER share a dir.
- `ModelBundleSpec.companionUrl/companionFileName/companionExpectedSha256` — resume-capable
  companion downloads (Matcha's vocoder) verified + copied into the extract dir.
- `SherpaModelFamily.MATCHA` + engine branch: acoustic = non-vocoder onnx, vocoder =
  vocos/hifigan onnx, `OfflineTtsMatchaModelConfig(noiseScale = 0.667f)`. AAR 1.13.8 verified
  to expose it (`android/SherpaOnnxAar/sherpa_onnx/.../Tts.kt` at tag v1.13.8).
- Router Kokoro regex widened to `[a-z0-9]` for the numeric v1.1 roster (zf_001, zm_100).
- `PurrProxyPresets` (PurrModels) + preset chips in the advanced proxy panel: Kokoro-FastAPI,
  openedai-speech, F5-TTS, OpenAI Cloud — the heavy-model bridge for CosyVoice/F5/XTTS servers.
- **Qwen TTS verdict**: API-only via DashScope WebSocket (qwen-audio-3.1-tts-*, qwen3-tts-flash-
  realtime), closed weights, no sherpa/ONNX support — NOT bridgeable via OpenAI-compatible REST.
  Documented in PurrModels; do not add a Qwen preset without a WebSocket client project.

## MIKA'S ACADEMY + HEADER SYSTEMS (v1.5.0)
- `core/purr/PurrTutorialSteps.kt` — pure academy data: `TUTORIAL_VERSION` (bump alongside
  versionName on content changes), `PURR_TUTORIAL_STEPS` (8 slides; the boot slide is
  `firstLaunchOnly`), `PURR_WHATS_NEW` highlight cards, `PurrUpdateGate` (seen == 0 => full
  walkthrough; 0 < seen < TUTORIAL_VERSION => patchnotes popup once; else NONE).
- `ui/theme/MikaAcademyOverlay.kt` — full-screen CRT walkthrough: typewriter narration
  (13ms/char, blinking `▊`), gold patchnotes pill + WHATS_NEW cards on the patchnotes slide,
  `[SKIP_ALL >>]`, progress bar + `n/N` counter, `[NEXT >]` / `[ LET'S PURR ~ 💜 ]`.
  **BGM = `res/raw/milk_and_wire.mp3` (Milk & Wire, Master's track — shared with
  GachaSwipe): MediaPlayer loop, 2.5s 0→0.32 volume ramp, released on dispose.**
- `ui/theme/MikaSettingsSheet.kt` — header-cog dialog: Academy replay, repository +
  Ko-fi links (ACTION_VIEW), build stamp, license line.
- Header: `CyberIconType.SETTINGS_COG` (Canvas gear — 8 teeth, ring, axle) beside
  SET_OS_TTS. `MikaStatusBadge` slot is `width(84.dp)` sized for `[IDLE]`; longer labels +
  VRAM countdown render as a TextMeasurer-measured **bounce marquee** inside the slot
  (clipToBounds + keyframes hold/scroll/hold/return, 4.6s cycle). Never resize pinned headers.
- Engine picker: `EngineDropdownSelector` (TtsForgeScreen) — collapsed active-engine row
  (accent-colored, subtitle, NPU/GPU/Piper suffixes) expanding into a slotted-cartridge tray
  with CHECK + ACTIVE markers, plus a LAN PROXY row. Replaces the pill wall.

## Clone clip import & cleanup (v1.4.0)
- `engine/CloneClipImporter.kt` — `MediaExtractor` + `MediaCodec` synchronous decode of any
  platform-supported audio document (mp3/m4a/ogg/opus/flac/wav) → mono float PCM at source
  rate. Hard 30s capture cap; stereo downmix by channel averaging; decoder errors fail loudly.
- `core/purr/PurrCloneAudioCleaner.kt` — PURE import-path DSP: DC removal → 85Hz high-pass
  biquad → **STFT spectral-subtraction denoiser** (1024/256 Hann STFT, radix-2 FFT, noise
  profile from the quietest 12% of frames, `beta` over-subtraction with per-bin spectral
  floor, phase-preserving overlap-add; strengths LIGHT/STANDARD/AGGRESSIVE) → 0.92 peak
  normalize. `selectBestClip` trims silence then locks the highest-energy window to the
  family target (`targetClipSeconds`: Pocket 5s, ZipVoice 12s) with 12ms anti-pop fades.
- ViewModel flow: `importCloneClip(uri)` (SAF `OpenDocument`, mime `audio/*`) → cleaned
  samples held in memory at 24kHz → `registerClonedVoice` trims per selected family, writes
  `cloned_refs/import_*.wav`, and takes the existing `wavPathOverride` path. Recording path
  unchanged. The `CLEAN_AUDIO` toggle defaults ON; OFF = raw passthrough config.
- Scar law: noise-profile learning needs real noise frames — never build fixtures where
  quiet regions are perfect zeros (the profile collapses to zero and subtraction no-ops).
- Scar law: WOLA normalization must be indexed by GLOBAL output position (`norm[pos]`),
  not frame-local `i` — frame-local indexing overruns `FloatArray(FFT_SIZE)` on any clip
  longer than 1024 samples (AIOOBE) and even when clamped, divides by the wrong gain.
  Verified against a pure-Python port of the algorithm before shipping (evidence-based DSP).

## Model catalog (`ModelCatalogManager` + `PurrDefaultPresets.modelCatalog()`)
- Resumable HTTP Range downloads into `filesDir/models/downloads`, streaming SHA-256 verify
  against `expectedSha256`, `.tar.bz2` unpack via commons-compress into `filesDir/models/…`.
- Tool cartridges (`tool_purr_engine`, `tool_dsp_rack`, `tool_symbol_shield`, `tool_clone_studio`,
  `tool_audio_exporter`) are always INSTALLED/builtin; TTS cartridges slam/eject via UI.

## Scar log (do not re-learn)
- AGP 8.7.2 Lint UAST crash → toolchain aligned at Kotlin 2.4.0; keep AGP/Kotlin aligned.
- sherpa-onnx v2.0.0 dropped espeak-ng G2P → KittenTTS + OOV words broke → pinned v1.13.8.
- v1.3.2 buzz chain: compressor on defaults + sentiment pitch resampling + linear resampler +
  blocking delay in stream loop. Fixed via transparent CLEAN path + pitch lock + Cubic Hermite
  + gapless streaming. Don't regress any of the four.
- v1.3.3 header slot law: dynamic badge/countdown text in pinned headers needs fixed-width
  slots (see mika-compose-ui skill).
