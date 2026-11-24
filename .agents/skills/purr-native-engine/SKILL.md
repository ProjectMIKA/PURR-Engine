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
- ✨ **THREE-WAY BENDER SPLIT (v1.8.3)**: profile flags `enableSlang` / `enableHonorifics` /
  `enableStutters` gate the slang dictionary, honorific/vocalism phonetics, and the organic
  stutter translator independently (each preset carries its own defaults; migrated from the old
  combined `enableSlangAndHonorifics` in `PurrProfileRepository.migrateLegacyEnhancerFlags`).
  **SLANG-ONLY CARVE-OUT LAW**: on Neural Pure flagship voices ONLY slang survives (it is a
  pronunciation aid), governed solely by `enableSlang` — honorifics & stutters are forced OFF
  there. The v1.8.2 `slangDuringNeuralPure` toggle and `shouldBypassSlang` are retired.
  Migrated users who had explicitly DISABLED the old carve-out (full v1.8.0 Neural Pure) start
  with `enableSlang = false` everywhere — their expressed clean-speech preference wins
  (Master's call); everyone else seeds all three toggles from the old combined flag.
  Covered by `PurrProfileRepositoryMigrationTest`.
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
- **Qwen3-TTS Native Stack (`v1.7.0`, `ROM-04`)**: Implemented genuinely via native C++ JNI (`app/src/main/cpp/purr_qwen3_jni.cpp` + `Lux-Luna/LunaVox` MIT + `ggml-org/llama.cpp b8470` + `onnxruntime-android:1.31.1`) and `engine/qwen3/Qwen3TtsEngine.kt`. Supports 0.6B CustomVoice (9 multilingual speakers), `SUB-04A` 0.6B Base (3s zero-shot clone), and `SUB-04B` 1.7B VoiceDesign (`profile.voiceDesignPrompt`).
- **Pure-Kotlin ONNX Runtime Stack (`v1.7.0`, `ROM-05`/`ROM-06`/`SUB-03E`)**: `engine/onnx/OnnxRuntimeEngine.kt` + `core/purr/PurrBpeTokenizer.kt` execute Soprano-80M 32kHz (resampled to 24kHz), NeuTTS-2E Emotional (`assets/neutts_2e_refs.json` + PURR Sentiment emotion control tags + NeuCodec INT8), and Chatterbox-Turbo 350M Q4 (zero-shot cloning + `[laugh]`/`[sigh]`/`[gasp]` paralinguistic tags preserved across Symbol Shield).
- **Clean 8-Bay Hub Rack (`v1.7.0`)**: Top-level TTS ROMs are unified into `ROM-01..ROM-08`: Kokoro variants (`SUB-02A..D`) nest under `ROM-02`, all 5 cloning engines (`SUB-03A..E`) nest under `ROM-03` container hub (`isHubContainer = true`), Qwen3 expansions nest under `ROM-04`, Kitten Mini (`SUB-07A`) nests under `ROM-07`, and Piper voices + `SUB-08G` MeloTTS English + `SUB-08H` Supertonic 3 + `SUB-08I` Matcha-TTS nest under `ROM-08`. One unified `libonnxruntime.so` (v1.31.1) is enforced in CI by stripping duplicate `jni/*/libonnxruntime.so` from `sherpa-onnx-1.13.8.aar`.
- **v1.6.1 scars**: (1) `requiredSubCartridgeId` gates ENGINE MOUNTING only — voice AVAILABILITY
  must also accept `modelStates[id] == INSTALLED` for top-level cartridges (they never enter
  `enabledSubCartridges`). (2) **Vocoder sample-rate law**: ALWAYS read the model's README for
  its vocoder + rate (matcha-icefall-zh-en = 16kHz + vocos-16khz-univ, NOT the 22kHz example
  vocoder!) — a rate-mismatched vocoder outputs robotic garbage. Companion installs now scrub
  stale vocoder variants, and a manager-init migration self-heals already-installed cartridges.
- **v1.6.2 law — ONE VOICE, ONE NAME**: voice IDs must be globally unique across cartridges
  (`fp32_*`, `v11_*` prefixes). ROM-08 originally reused ROM-02's bare ids; `builtInVoices()`
  lists ROM-02 first, so every synthesis lookup resolved ROM-02's descriptor (null
  requiredSubCartridgeId → int8 weights dir → empty if not installed → CORE_OS fail-soft).
  `loadInitialState` migrates stale bare-id selections via the premium prefix map. Guarded by
  the global-uniqueness test in PurrPremiumCatalogTest.

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
- ✨ **TRUST RENEWAL (v1.8.1)** — upstream hosts are MUTABLE, so expected hashes resolve
  `live manifest → baked pin`: `model_manifest.json` (repo root, raw.githubusercontent) carries
  the current verified digest per asset URL (GitHub release digests + HF LFS oids; regenerate
  with `scripts/refresh_model_manifest.py`, drift-audit with `--check`). The manager preloads it
  at init/download start, force-refreshes ONCE on any SHA mismatch before failing closed, and
  caches the last-good copy at `filesDir/models/manifest_cache.json` for offline. The fail-closed
  SHA-256 law itself is UNCHANGED — a download only ever commits after matching a trusted digest.
- **416 self-heal (`streamAssetToPart`)**: a leftover COMPLETE `.part` file (stale-pin rejection
  or upstream size change) is hash-verified + promoted instead of dead-ending every retry with
  `HTTP 416`; if the part is genuinely stale it is deleted and restarted clean from byte 0.
- All three download paths (single archive, multi-file native bundle, companion) share the
  helper pipeline: `streamAssetToPart` → `verifyAgainstExpectedSha` (manifest rescue) → commit.
- Tool cartridges (`tool_purr_engine`, `tool_dsp_rack`, `tool_symbol_shield`, `tool_clone_studio`,
  `tool_audio_exporter`, `tool_voice_design`, `tool_audiobook_constructor`) are always INSTALLED/builtin; TTS cartridges slam/eject via UI.
- Failure UX names the ROM bay, not the voice (`cartridgeLabelFor` in `SherpaOnnxEngine`):
  `Af Heart offline — ROM-02 KOKORO_NEURAL_HUB cartridge not installed (download it in Tab 3)`;
  `ensureFamilyLoadedLocked` logs `[MOUNT_MISS]` with the exact directory checked.

## Qwen3 GPU offload (Adreno OpenCL, v1.8.2)
- Native stack ships `libggml-opencl.so` alongside `ggml-cpu` (CMake: `GGML_OPENCL ON`,
  `GGML_OPENCL_EMBED_KERNELS ON`, `GGML_OPENCL_USE_ADRENO_KERNELS ON` — kernels embedded, no
  runtime files; Adreno-optimized matmul kernels for Snapdragon flagships).
- Android NDK has no OpenCL SDK: `app/src/main/cpp/opencl_headers` vendors KhronosGroup headers
  (Apache-2.0, see NOTICE) and CMake generates a **link-only stub `libOpenCL.so`** at configure
  time (never packaged — at runtime the device's vendor `libOpenCL.so` resolves via the vendor
  public-libraries list, and is skipped gracefully when absent).
- LunaVox hardcodes `n_gpu_layers=-1` in `TalkerPredictor::load` → **`patches/lunavox_opencl_gpu_offload.patch`**
  (applied by CI after the clone, fail-closed) routes it through `lunavox::set_default_n_gpu_layers`:
  **0 = CPU-only (default), -1 = offload all layers**. `ggml_backend_load_all_from_path` in
  LunaVox's `ensure_loaded` auto-registers the packaged OpenCL backend — the ONLY gate is the
  n_gpu_layers value, so toggling works mid-session with no lib unloading.
- Kotlin (v1.8.3): GLOBAL `PurrForgeState.qwen3GpuOffload` (default OFF — Settings ▸
  `> HARDWARE_OFFLOAD // [EXPERIMENTAL]`, NOT per-vibe-profile; hardware outlives personas) →
  `TtsBackendRouter` wires it as `Qwen3TtsEngine(gpuOffloadProvider = ...)` →
  `Qwen3NativeBridge.nativeCreate(..., nGpuLayers)`. Badge flips to `GPU_FP16`, engine summary
  appends `• Adreno OpenCL`, and the mount log names the engaged backend. Toggle-off destroys and
  re-mounts the engine on CPU (mid-session, no restart). Per-cartridge accelerator chips (Kokoro
  Hexagon-NPU / Adreno-GPU sub-cartridges) stay per-cartridge in the Cartridge Bay — this global
  section is for in-engine offloads only; future GPU/NPU toggles slot next to it.
- q5_k tensors may lack OpenCL kernels → llama.cpp falls back per-tensor to CPU automatically
  (partial offload is fine); a full-GPU q4_0 talker variant is the future upgrade if needed.

## App update check (`core/purr/PurrUpdateChecker`)

- **MANUAL ONLY** (Settings ▸ `CHECK_FOR_UPDATES`) — one GET to
  `api.github.com/repos/ProjectMIKA/PURR-Engine/releases/latest` (UA header set, 10s
  timeouts, fail-soft null on ANY error), strict numeric version compare
  (`isNewerVersion`: `v` prefix tolerated, missing parts = 0, pre-release suffixes
  stripped and never promote), and (newer?) the release `html_url` opens in the user's
  browser. **ZERO background polling** — no WorkManager, no alarms, no silent traffic
  (Master's standing law). Compare/parse halves are pure JVM — covered by
  `PurrUpdateCheckerTest`.

## Scar log (do not re-learn)
- AGP 8.7.2 Lint UAST crash → toolchain aligned at Kotlin 2.4.0; keep AGP/Kotlin aligned.
- sherpa-onnx v2.0.0 dropped espeak-ng G2P → KittenTTS + OOV words broke → pinned v1.13.8.
- v1.3.2 buzz chain: compressor on defaults + sentiment pitch resampling + linear resampler +
  blocking delay in stream loop. Fixed via transparent CLEAN path + pitch lock + Cubic Hermite
  + gapless streaming. Don't regress any of the four.
- v1.3.3 header slot law: dynamic badge/countdown text in pinned headers needs fixed-width
  slots (see mika-compose-ui skill).
- **v1.7.1 Qwen3 & NeuTTS-2E scars**: (1) `custom_small` (`0.6B CustomVoice`) sets `"instruct_support": false` in `model_profile.json`, so passing non-empty sentiment `instruct` makes LunaVox reject `synthesize_custom`; ALWAYS read `instruct_support` before sending `instruct`. (2) NeuTTS-2E has 151k vocab + 65k `<|speech_N|>` added_tokens (`23MB tokenizer.json`) and a `217,226`-logit prefill matrix (`387MB` on long prompts); load tokenizers via streaming `StreamingTokenizerJsonReader`, read ONLY the final sequence step vector in `OnnxRuntimeEngine`, and keep `android:largeHeap="true"`. (3) Never use unaligned overlap-add time-scaling (causes metallic phase-comb reverb); use pitch-synchronous `PurrDspProcessor.timeScaleWsola` and keep `0.94x..1.06x` bypassed bit-exact.
- **v1.8.0 scars**: (1) NEVER set `GraphOptimizationLevel::ORT_ENABLE_ALL` on Android ARM64 CPU EP for Qwen3 FP16 ONNX graphs — `ORT_ENABLE_ALL` triggers `NhwcTransformer`, rewriting FP16 `Conv` nodes into `NhwcFused` and crashing `speaker_encoder.fp16.onnx` (`Non-zero status code returned while running NhwcFused`); ALWAYS keep `ORT_ENABLE_BASIC` in `provider_policy_android.cpp`. (2) Never generate release signing keystores inside CI runs — always decode the persistent keystore from `PURR_RELEASE_KEYSTORE_B64` secret so upgrades stay compatible in-place. (3) Heavyweight neural TTS voices (`PurrNeuralBypass`) sound far cleaner when slang expansion, stutters, comma-stripping, and micro-chopping are auto-bypassed (post-v1.8.3: slang expansion is the ONLY survivor of Neural Pure on flagship voices, governed by the profile `enableSlang` toggle — the v1.8.2 `slangDuringNeuralPure` toggle is retired; the intrusive enhancers stay bypassed). (4) `TOOL-06` (`QwenVoiceDesignBuilder`) & `TOOL-07` (`EbookParser` + `EbookPdfExtractor` + `AudiobookConstructorManager` + `M4bAudiobookMuxer` + `Mp4ChapterAtomWriter` writing Nero `chpl` + iTunes `stik=2`) provide structured VoiceDesign prompt editing and resumable `.m4b` audiobook creation.
- **v1.8.3 migration init-order scar**: Kotlin initializes class properties in DECLARATION ORDER —
  `PurrProfileRepository.legacyProfileKeys` was declared AFTER `_state = MutableStateFlow(loadInitialState())`,
  so the constructor-time migration read it as NULL: NPE inside `runCatching` → silently swallowed →
  migration no-oped and every new flag fell back to its default (CI `PurrProfileRepositoryMigrationTest`
  caught it; local reasoning couldn't — no Gradle in the automation shell). LAW: any property read by a
  constructor-time function MUST be declared before the property that triggers the call. Migration
  failures now log loudly (`[PURR_REPO][MIGRATE_ERR]`) instead of silently skipping.
- **v1.8.1 trust-renewal scar**: upstream model hosts are MUTABLE — k2-fsa re-uploads assets
  under the SAME `tts-models` tag (Pocket INT8/FP32 on 2026-11-21, Kokoro packs on 2026-10-26)
  and HuggingFace `resolve/main` moves with the branch tip, so baked catalog pins rot and the
  fail-closed law then bricks every fresh download ("SHA-256 mismatch! Expected 2f3b8882…").
  NEVER trust a baked pin alone: `model_manifest.json` wins over pins (refresh via
  `scripts/refresh_model_manifest.py`, audit via `--check`), a mismatch force-refreshes the
  manifest ONCE before failing closed, and a leftover complete `.part` must be 416-verified and
  promoted — never resumed into an eternal "HTTP 416" retry trap.
- **v1.9.6 manga-dub regression**: KV panel-turn token ceiling (768 output − 384 thinking)
  truncated busy-panel JSON → 0-line specs wiped panels; the expanded SFX lexicon + a NEW
  synthesis-time re-filter dropped 1-2 word real dialogue; the clone lock's single neutral seed
  flattened every dubbed voice. All three fixed in v1.9.7 — see the laws below.

## Manga Dub Studio — vision & voice-lock laws (`engine/manga/`)

The `TOOL-11` Manga Dub Studio pipeline (vision OCR → casting → voice forge → mux) has its own
failure classes; these laws came out of the v1.9.6 regression where bubbles went silent and
voices went flat:

- **Storyboard pass is context-only** (`runChapterStoryboardPass`, v1.9.4): contact-sheet cast
  bible + page synopses. It fails soft (`null` → empty synopses) and can NEVER drop bubbles —
  when bubbles vanish, suspect the per-panel path, not the storyboard.
- **KV-cache panel turns have a token ceiling** (`analyzeMangaPage` `usePageKvCache`):
  `maxOutputToken` must exceed the JSON needed by the busiest panel (~80 tokens/bubble).
  v1.9.6 shipped 768 output − 384 thinking = truncation mid-JSON → parse → 0 lines → panel wiped.
  v1.9.7 law: ≥1024 output / ≤256 thinking, AND `MangaVisionResponseParser.repairTruncatedJson`
  deterministically balances truncated strings/brackets (shortened value-tails survive, dangling
  partial keys drop), AND the candidate builder accepts opener-without-closer payloads. The
  `{"lines"` repair prompt only fires for brace-free prose — it cannot save truncation; the
  balancer does.
- **No-wipe law**: a vision spec with zero lines NEVER replaces a panel's existing lines
  (`runVisionPhaseOnChapter`). An empty response = "no data", not "panel is silent" (silence is
  the VLM explicitly returning `{"mood":"silent_panel","lines":[]}` on a first pass).
- **Trusted-speaker rescue** (`MangaTextBender.isNonBubbleOrCreditNoise(speaker, rawText, trustedSpeaker)`):
  the SFX lexicon (`PURE_SFX_TOKENS`) grew so large in v1.9.6 that 1-2 word REAL dialogue
  ("Sob...", "Hiss!") from known cast members got dropped — twice (parse-time AND the
  synthesis-time re-check in `executeProduceDubbedChapterMp4`). v1.9.7: roster speakers bypass
  the ≤2-word token rule (elongated onomatopoeia "Shaaa", credits, SFX prefixes still drop), a
  KNOWN speaker + SFX-labelled container is rescued only when the text passes the UNTRUSTED
  check, and the synthesis re-check mirrors the parse semantics. Keep both layers consistent.
- **Expressive seed law** (`ensureCharacterVoiceClonesLocked` + `composeVoiceSeedTranscript`):
  the Qwen3 0.6B Base Cloner anchors timbre AND prosody to the reference clip — a single neutral
  anchor sentence (v1.9.6) flattened every line. Seeds are now composed from the character's OWN
  transcribed lines (≤3 lines / ≤180 chars, verbatim — the ICL `referenceTranscript` must match
  the spoken audio exactly), fallback `FALLBACK_VOICE_SEED` (3 emotional beats). Seeds are
  synthesized DRY (`enableDsp = false` + neutral `DspConfig`) — profile EQ/reverb must never
  bake into a clone reference.
- **No pitch re-shift on clones** (`resolveSynthesisProfileForCharacter` clone branch): the seed
  already embodies the designed register from its VoiceDesign prompt; multiplying `pitchScale`
  in again double-applies the design and adds cubic-interpolation artifacts. Speed (WSOLA) may
  stay.
- **Cartridge guard**: clone descriptors persist even when their cartridge is uninstalled, and
  the router never hijacks clones with CORE_OS → uninstalled clone = EMPTY audio (silent
  subtitle ghosts). `cloneDescriptorUnavailable()` checks
  `modelStates[requiredSubCartridgeId ?: cartridgeId]` before returning a clone profile; fall
  through to the VoiceDesign / matcher fallbacks instead.
- The `family` (`SherpaModelFamily`) stamped on Qwen3 Base auto-clones is inert bookkeeping —
  routing goes through `resolvedCloneEngineId` → `QWEN3_NATIVE`. Don't "fix" it into Sherpa.

## v1.9.8 field-feedback laws (E2B parrots, narrator MIKA, subtitle labels)

- **KV-cache panel scanning is OPT-IN** (`manga_kv_cache_enabled` pref, default OFF, advanced
  toggle in the CUSTOMIZE drawer): the field confirmed small VLMs (Gemma **E2B**) fail the
  compact KV turn prompts and parrot the schema example — users heard `"Dialogue text"` in a
  male voice (the repair-prompt example object). E4B handled it fine. Default OFF restores the
  proven full single-panel prompt for every tier; the toggle warns speed↑/hallucinations↑.
- **Placeholder-parrot guard** (`MangaTextBender.PLACEHOLDER_DIALOGUE_TEXTS` +
  `parseLineSpecArray`): literal schema-example strings ("Dialogue text", "Exact transcribed
  dialogue inside bubble 1", ...) are dropped at parse time so a parroting model falls to
  review/heuristic instead of dubbing its own prompt. Extend the set when schemas change.
- **Prompt persona-contamination law**: the VLM prompts mention "MIKA" (the studio persona) and
  small models WILL name unnamed characters "MIKA"/"THE_MALE"/"THE_FEMALE". Every prompt now
  carries an explicit NEVER-invent-placeholder-names rule (use "UNKNOWN") + a page-boundary
  rule (transcribe only text visible in THIS crop — fixes cross-page speaker bleed).
- **MIKA is the Manga Dub narrator** (`MangaTextBender.MIKA_NARRATOR_VOICE_PROMPT` +
  `resolveSynthesisProfileForCharacter(isNarrator = true)` branch 0): NARRATOR lines resolve to
  M.I.K.A.'s hardcoded condensed VoiceDesign voice directly — no roster entry, no casting
  override, no clone lock; falls through to the matcher only if the 1.7B isn't installed.
- **Subtitle label law** (`MangaTextBender.subtitleLabelFor` + `drawSubtitleHud`): UNKNOWN and
  invented placeholder speakers (incl. a contaminated "MIKA") get NO subtitle name prefix —
  spoken text only; NARRATOR lines render "MIKA (Narrator)". The muxer collapses the speaker
  row when the label is null.
- **Narrator identity law (v1.9.9)** (`MangaTextBender.isPlaceholderOrNarratorName`): narration
  belongs to MIKA alone — the storyboard cast-seeding loop, `autoResolveDecisionsAndProduce`,
  and `ensureCharacterVoiceClonesLocked` all filter NARRATOR/UNKNOWN/placeholder names, and
  narration synthesis resolves MIKA's voice with `character = null` in isolation. Field scar:
  E4B's storyboard listed NARRATOR as a character → an androgynous NARRATOR cast member with
  invented traits appeared in the CAST tab. The placeholder family also grew (the VLM emitted
  "THE_WOMAN" — variants THE_MAN/THE_GIRL/THE_BOY/THE_GUY/THE_LADY/NARRATION are covered in
  both `GENERIC_UNNAMED_SPEAKERS` and every prompt ban list).
- **Cast cards show the EFFECTIVE dub voice** (v1.9.9): the `ASSIGNED VOICE (OR CLONE)`
  dropdown fell back to `activeProfile.primaryVoiceId` — the user's system-TTS pick — which
  mislabeled an active Qwen clone as e.g. "Phonon // Snow Queen". The fallback chain is now
  assignedVoiceId → autoCloneVoiceId → activeProfile → first; the header shows
  "AUTO (Qwen VoiceClone Lock)" when the clone lock is active.
- **Dynamic panel ceiling + chained-cut depth law** (`MangaBfsPanelSlicer.slicePanelsFromLuminanceGrid(…, maxPanels)`,
  manager pref `manga_max_panels_per_page`, 0 = AUTO): the XY-cut recursion is evidence-gated
  (≥90% gutter across both halves + border/peak validity) — a max cap never prevents
  over-cutting, it only MERGES verified panels away. The REAL limiter is recursion depth:
  chained cuts land at depth R+C−2 for an RxC grid (depth 3 only reaches 2×3 = 6 panels;
  2×4/4×2 need depth 4). Depth tiers derive from the cap: ≤6→3, ≤8→4, ≤12→5, else 6.
  UI: `PANELS:` pill row (AUTO(8)/4/6/8/12) in the CUSTOMIZE drawer. Validated with a
  faithful Python port against the 2×4 grid (8 panels AUTO / 6 merged), the tall-right
  4-panel layout, and webtoon bands before shipping.
