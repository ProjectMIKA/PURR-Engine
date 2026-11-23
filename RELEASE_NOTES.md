# 🌌 PURR-TTS v1.9.5 // XY-Cut Panel Slicer (Max 5), Context Framing & Strict RTL Bubble Order

### ✨ What's New since v1.9.4

Fixes over-segmentation into tiny speech-bubble crops, backwards Left-to-Right panel/bubble order on Japanese manga pages, and overly tight camera zooming! 🧠📖🎬

* **✂️ Border-Verified Recursive XY-Cut Panel Slicer (`MangaBfsPanelSlicer`, Max 5 Panels/Page)**:
  * Replaced pixel-island BFS + low-threshold internal splitting with a **Dual-Half Border-Verified Recursive XY-Cut Gutter Tree** capped at **`MAX_PANELS_PER_PAGE = 5`**.
  * Open-border panels and bright internal artwork around speech bubbles are no longer shattered into 15–20 tiny bubble boxes; every page slices cleanly into 4–5 true comic panels.
* **📖 True Right-to-Left Manga Ordering (`partitionByGuillotine` + Intra-Panel Coordinate Sort)**:
  * Upgraded `orderPanelsForReadingDirection` with **Recursive Guillotine XY-Partitioning** so staggered columns (e.g. a full-height right panel alongside 3 stacked left panels) strictly follow **Right -> Left** before advancing downward.
  * Added normalized `"x_pos"` / `"y_pos"` bubble coordinates in VLM prompts and transitive row-band sorting inside `MangaVisionResponseParser` so speech bubbles inside every panel also sort strictly **RTL (rightmost first)**, **LTR**, or **Webtoon Vertical**.
* **🎥 Wider Surrounding-Page Context Framing (`MangaDeterministicEffectsEngine.expandViewportForPageContext`)**:
  * Expanded playback viewports with generous outer padding (`minWidth = 0.68`, `minHeight = 0.56` in Portrait **9:16**; `0.62 x 0.50` in Landscape **16:9**) and softened `READING_AXIS_PAN` zoom so viewers see full panels and surrounding page art instead of tight speech-bubble closeups.

---

### 📦 Android APK Packages (Attached Below)

1. **`PURR-Engine_V1.9.5_Android.apk`** — Signed release Android APK (`com.projectmika.purrtts`, versionCode `31`).
2. **`PURR-TTS_V1.9.5.apk`** — Mirror signed release APK for direct Android sideloading.

---

# 🌌 PURR-TTS v1.9.4 // Storyboard Context Pass, Toned-Down Motion & Speech-Bubble-Only Filter

### ✨ What's New since v1.9.3

Eliminates dizzying over-zooming/panning (especially in **Portrait 9:16**), skips noisy SFX/scanlation credits by default while preserving an experimental toggle, and adds a whole-chapter **Storyboard Context Pass** so characters and plot stay consistent across every page! 🧠📖🎬

* **🎬 Toned-Down Camera Zoom & Vertical-Aware Panning (`MangaDeterministicEffectsEngine` / `MangaMotionVideoMuxer`)**:
  * Reduced subtle push-in / pull-back zoom margins to ~1–2% and gentle panning (Portrait **9:16** uses **0.55×** motion scale vs **0.85×** in Landscape **16:9**), raising the portrait wide-panel pan threshold (`>= 2.10` aspect ratio) and softening midpoint transition arcs (`0.018` Portrait / `0.032` Landscape) with snappier `750ms` silent panel holds.
  * Increased recursive composite gutter splitting (`MangaBfsPanelSlicer`) up to 4 levels with bright (`luminance >= 235`) white-margin awareness so dense multi-subpanel pages break cleanly into calm individual framing.
* **💬 Speech-Bubble-Only Default & Experimental SFX Toggle (`MangaDubModels` / `LiteRtGemmaVisionEngine` / UI)**:
  * Skips sound effects (`Shaaa`, `GULP`, `BWO DO`), floating background laughs, scanlation credit bars (`Scanlation by:`), and handwritten signatures by default during cook sessions (`includeNonBubbleText = false`) via both VLM schema gating (`is_speech_bubble`) and deterministic pattern filtering (`isNonBubbleOrCreditNoise`).
  * Power users can still opt back in via the **`EXPERIMENTAL: PROCESS NON-BUBBLE TEXT & SFX`** toggle in the Customize drawer.
* **🗺️ Fast Chapter Storyboard Context Pass (`runChapterStoryboardPass`)**:
  * Renders up to 6 chapter pages onto a single labeled `1024×1024` contact sheet right before per-panel OCR so the Vision LLM grasps the full chapter arc, canonical character names, outfits, and per-page synopses upfront — ending mid-chapter speaker identity drift across scene cuts.

---

### 📦 Android APK Packages (Attached Below)

1. **`PURR-Engine_V1.9.4_Android.apk`** — Signed release Android APK (`com.projectmika.purrtts`, versionCode `30`).
2. **`PURR-TTS_V1.9.4.apk`** — Mirror signed release APK for direct Android sideloading.

---

# 🌌 PURR-TTS v1.9.3 // Sticky Vision Model Picker, 6 VLMs, Per-Panel Crop OCR & Reading Direction UI

### ✨ What's New since v1.9.2

Makes your selected Vision LLM and Reading Direction stick reliably, upgrades OCR accuracy & panel dubbing alignment via **Zoomed Per-Panel Crop Scanning**, expands the Vision catalog to **6 official LiteRT-LM models**, and refines panel camera transitions! 🧠📖🎬

* **🎛️ Sticky Vision Model Selection (Removed `[RECOMMENDED]` & `AUTO`)**:
  * Removed the `AUTO` selector and `[RECOMMENDED]` badge so **Gemma 4 E2B**, **Gemma 4 E4B**, and 4 new Vision LLMs are explicit, persistent choices saved in app preferences across chapters, imports, and screen switches.
* **🤖 4 New Official LiteRT-LM Vision LLMs Added (6 Total)**:
  * Added **`SUB-11Q` Qwen 3.5 VL 2B** (24-layer high-res ViT OCR specialist), **`SUB-11L` LiquidAI LFM 2.5 VL 3B** (SigLIP2 + fast hybrid decoder), **`SUB-11F` Qwen 3.5 VL 0.8B** (lightweight fast OCR), and **`SUB-11I` InternVL3 1B** (compact 705 MB InternViT) alongside Gemma 4 E2B & E4B.
* **🔍 Zoomed Per-Panel Crop OCR & Topological Slicing (`LiteRtGemmaVisionEngine` / `MangaBfsPanelSlicer`)**:
  * Instead of shrinking an entire page to 1024px where bubbles become tiny, each BFS-detected panel is cropped individually (`<=960px` zoomed) and transcribed in its own focused pass, locking every speech bubble deterministically to its exact panel index.
  * Added internal composite gutter splitting (`splitCompositePanelBoxes`) and 2D topological column/row sorting so staggered panels stay in true reading order.
* **📖 Front-Surface Reading Direction Control & Explicit VLM Instructions**:
  * Surfaced **READING DIRECTION** right on the front panel (**`MANGA (R -> L)`** default, **`COMIC (L -> R)`**, **`WEBTOON (TOP -> DOWN)`**) with an explicit visual guidance caption and matching geometric ordering instructions injected directly into Vision prompts.
* **🎥 Smoother Panel Transitions (`MangaMotionVideoMuxer`)**:
  * Added a 10% midpoint zoom-bridge arc (`lerpRectWithZoomArc`) across 380ms panel pans and tightened silent panel holds (`950ms`) for snappier pacing.

---

### 📦 Android APK Packages (Attached Below)

1. **`PURR-Engine_V1.9.3_Android.apk`** — Signed release Android APK (`com.projectmika.purrtts`, versionCode `29`).
2. **`PURR-TTS_V1.9.3.apk`** — Mirror signed release APK for direct Android sideloading.

---

# 🌌 PURR-TTS v1.9.2 // Synchronized Panel Pacing, Crisp Quality & Deterministic FX Engine

### ✨ What's New since v1.9.1

Fixes the `.MP4` panel pacing & compression quality issues in Manga Dub Studio, makes speech bubbles crystal clear with zero subtitle overlap, adds a zero-hallucination **Deterministic Panel Camera Engine**, and puts **Aspect Ratio**, **Subtitles**, and **Output Quality** right on the front surface! 🎬📖🔥

* **⏱️ Fixed Panel Desync / End-Frame Freeze (`MangaMotionVideoMuxer`)**:
  * Replaced unthrottled wall-clock `Surface.unlockCanvasAndPost()` timestamps with an OpenGL ES 2.0 `EglBitmapSurfaceRenderer` stamping exact synthetic 24 FPS `EGLExt.eglPresentationTimeANDROID` timestamps plus deterministic muxer `presentationTimeUs` enforcement.
  * Every panel now stays on screen cleanly for the full duration of its spoken voiceover + emotional pauses (zero fast-forwarding through panels or sitting frozen on the final panel).
* **💎 High-Res Bubble Readability & Output Quality (`480p` / `720p` [Default] / `1080p`)**:
  * Unlocked full native 24 FPS hardware bitrate headroom (**3.5 Mbps** `480p` / **8 Mbps** `720p` / **14 Mbps** `1080p`), raised page decode resolution up to `3200px` (`720p`) and `4096px` (`1080p`), trimmed edge crops to `1.5%`, and reserved a dedicated bottom band when subtitles are enabled so subtitle pills never cover panel speech bubbles.
* **🎥 Deterministic Panel Camera & Motion FX Engine (`MangaDeterministicEffectsEngine`)**:
  * Zero-LLM, zero-hallucination engine that chooses repeatable panel choreography from panel geometry (`area`, `aspect`), output aspect ratio, reading direction (`RTL_MANGA` / `LTR_COMIC` / `WEBTOON_VERTICAL`), and dialogue/mood triggers (`READABILITY_HOLD`, `READING_AXIS_PAN` with entry/exit hold plateaus, `DRAMATIC_PUSH_IN`, `REVEAL_PULL_BACK`, `IMPACT_SHAKE_ZOOM` with anime speed lines, and `DIAGONAL_DRIFT`).
* **🎛️ Front-Surface Output Controls (`MangaDubStudioModuleWindow`)**:
  * **Aspect Ratio**: **Portrait `9:16`** (Default) or **Landscape `16:9`**.
  * **Subtitles**: **On** (Default) or **Off**.
  * **Output Quality**: **`480P`**, **`720P`** (Default), or **`1080P`**.

---

### 📦 Android APK Packages (Attached Below)

1. **`PURR-Engine_V1.9.2_Android.apk`** — Signed release Android APK (`com.projectmika.purrtts`, versionCode `28`).
2. **`PURR-TTS_V1.9.2.apk`** — Mirror signed release APK for direct Android sideloading.

---

# 🌌 PURR-TTS v1.9.1 // Direct Gemma 4 Vision Binding, 2-Phase VoiceDesign & Auto-Cook UI

### ✨ What's New since v1.9.0

Fixes the silent Gemma 4 bypass in Manga Dub Studio and brings **1-Tap Auto-Cook** (`Import Manga -> Series Title -> Watch It Cook`) with a live multi-phase cooking console! 🧠🎭🎬

* **🧠 Fixed Gemma 4 E2B / E4B Vision Not Being Used When Installed (`LiteRtGemmaVisionEngine`)**:
  * Replaced fragile Java reflection calls with direct compile-time bindings to Google AI Edge `com.google.ai.edge.litertlm` (`EngineConfig` with explicit `visionBackend` + `maxNumImages = 1`, `ConversationConfig` with `ThinkingConfig(enableThinking = true)`, `Contents.of(Content.ImageFile/ImageBytes, Content.Text)` + `Message.contents`/`channels["thought"]`, and automatic `Session.generateContent` fallback).
  * Fixed the misleading offline fallback message so when a local `.litertlm` file is installed, diagnostics surface transparently instead of claiming Gemma 4 isn't installed.
* **🎭 2-Phase Gemma 4 -> Qwen3 1.7B VoiceDesign Casting Director (`MangaDubStudioManager`)**:
  * **Phase 2A (Vision OCR + Thinking)**: Scans cyan-boxed `[P1..PN]` reading-order panels, traces bubble tails via `<thought>`, and populates confident speaker attributions automatically (lowering unnecessary manual flags to `< 55%` confidence or `UNKNOWN`).
  * **Phase 2B (VoiceDesign Casting Prompt Generation)**: While Gemma 4 is still loaded, automatically feeds the discovered cast roster + sample lines back through a dedicated casting pass to generate tailored **Qwen3 1.7B VoiceDesign** prompts, speaking cadence, and pitch scales for every character before evicting Vision VRAM for TTS.
* **🚀 1-Tap Auto-Cook Workflow & Live 5-Stage Cooking Console (`MangaDubStudioModuleWindow`)**:
  * **Surface-Simple UX**: Enter a **Series Title**, pick your **`.CBZ`**, and watch the 5-Stage Console (**`1 SLICE` -> `2 VISION` -> `3 CAST` -> `4 VOICE` -> `5 .MP4`**) with an auto-scrolling color-coded live log (Gemma `<thought>` traces, crafted Qwen3 VoiceDesign prompts, voiced lines, frame progress, elapsed time & ETA).
  * Pauses only when Gemma explicitly flags an uncertain speech bubble (offering both 1-tap speaker assignment and **`AUTO-RESOLVE GUESSES & FINISH .MP4`**), while tucking Cast Customization, Vegeta Clone Studio round-trip, Storyboards, Aspect Ratio, and LAN Vision overrides into an optional collapsible **`CUSTOMIZE`** drawer.

---

### 📦 Android APK Packages (Attached Below)

1. **`PURR-Engine_V1.9.1_Android.apk`** — Signed release Android APK (`com.projectmika.purrtts`, versionCode `27`).
2. **`PURR-TTS_V1.9.1.apk`** — Mirror signed release APK for direct Android sideloading.

---

# 🌌 PURR-TTS v1.9.0 // Hardened Manga Dub Studio, Keep-Awake & Gemma 4 E4B

### ✨ What's New since v1.8.9

Major reliability, UX telemetry, and AI vision upgrade for **`TOOL-11` Manga Dub Studio** & long-running audio/video jobs! 🎬📖🔥

* **📦 Hardened 4-Strategy `.CBZ` Extractor (`MangaCbzExtractorUtils`)**:
  * Replaces Android's `ZipInputStream`-only reader with a disk-backed 4-strategy extractor (**Apache Commons Compress `ZipFile` -> streaming `ZipArchiveInputStream` with `STORED` + Data Descriptor support -> JDK `ZipFile` -> `TarArchiveInputStream`**).
  * Handles `Shift_JIS` / `CP437` / `ISO-8859-1` comic entry names, extensionless image files via magic-byte header detection (`JPEG/PNG/WEBP/BMP/GIF`), and surfaces explicit error guidance if a `.cbr` (RAR), `.cb7` (7-Zip), or `.pdf` file is opened as `.cbz`.
* **🎬 Fixed `.MP4` Export Freeze on `ENCODING AAC VOICEOVER TRACK...` (`MangaMotionVideoMuxer`)**:
  * Fixed the `MediaCodec` EOS input-buffer starvation deadlock in `encodeAacTrack` (now actively drains AAC output buffers while waiting for free EOS input slots and enforces bounded EOS timeouts across both video & audio codecs), plus live frame-by-frame and AAC progress reporting.
* **💡 Global Screen Wake-Lock (`FLAG_KEEP_SCREEN_ON` + `view.keepScreenOn`)**:
  * Keeps the screen awake automatically across heavy Manga Dub Studio renders, Audiobook builds/combines, Script/Benchmark/SRT batches, audio playback/cloning, and large model downloads/extracts.
* **⏱️ Live 4-Stage Pipeline Telemetry, Elapsed Timer, Dynamic ETA & Rolling Logs**:
  * Displays active pipeline stage (**`1 SLICE` -> `2 VISION` -> `3 VOICE` -> `4 MP4 MUX`**), live progress bar & item counts, per-second **Elapsed Time** & **ETA**, `"SCREEN AWAKE LOCK ACTIVE"` status, and timestamped rolling logs.
* **🗑️ Safe Project & Chapter Management**:
  * Added **Delete Project**, **Delete Chapter**, and **Clear All Projects** controls with confirmation dialogs so accidental taps never wipe work.
* **🧠 Optional Gemma 4 E4B LiteRT Vision Model (`SUB-11X`, 3.1 GB)**:
  * Added **`SUB-11X` Gemma 4 E4B LiteRT** alongside **`SUB-11V` Gemma 4 E2B LiteRT** (marked **`[RECOMMENDED]`**) inside Manga Dub Studio (`AUTO / E2B / E4B` selector + per-model install cards).

---

### 📦 Android APK Packages (Attached Below)

1. **`PURR-Engine_V1.9.0_Android.apk`** — Signed release Android APK (`com.projectmika.purrtts`, versionCode `26`).
2. **`PURR-TTS_V1.9.0.apk`** — Mirror signed release APK for direct Android sideloading.

---

# 🌌 PURR-TTS v1.8.9 // Manga Dub Studio & Gemma 4 E2B LiteRT Vision

### ✨ What's New since v1.8.8

Turn full `.CBZ` manga chapters into dubbed motion-comic `.MP4` episodes right on Android with **`TOOL-11` Manga Dub Studio**, **Gemma 4 E2B LiteRT Vision + Thinking** (`SUB-11V`), a guided **Pre-Production Desk**, and 1-tap **Clone Studio Round-Trip** casting! 🎬📖🎙️

* **📖 `TOOL-11` MANGA DUB & MOTION-COMIC STUDIO (`tool_manga_dub_studio`)**:
  * **Auto-Pilot `.CBZ` Importer & GachaSwipe BFS Panel Slicer**: Unpacks `.cbz` archives in natural page order and slices every manga page into exact RTL Manga, LTR Comic, or Webtoon reading-order panels using GachaSwipe's edge-connected BFS gutter flood-fill algorithm (`MangaBfsPanelSlicer`), plus an instant 1-tap **Load Demo Chapter** starter project.
  * **🧠 `SUB-11V` Gemma 4 E2B LiteRT Vision + Thinking Cartridge (`gemma-4-E2B-it-gpu.litertlm`)**: Annotated `[P1..PN]` Set-of-Mark page scanning on Google AI Edge `litertlm-android` (plus optional LAN OpenAI Vision endpoint for power users), extracting OCR dialogue, speaker attribution with chain-of-thought reasoning traces, character gender/appearance notes, and tailored **Qwen VoiceDesign** prompts — then strictly evicting Vision VRAM before TTS synthesis begins.
  * **🎭 Pre-Production Desk (`Decisions` | `Cast Roster` | `Storyboard`)**:
    * Surfaces only low-confidence or tail-less speech bubbles in a fast **`⚠️ DECISIONS`** inbox with 1-tap character speaker assignment pills.
    * **Persistent Series Character Roster**: Crops character portraits and freezes voice assignments across chapters — tap **`CLONE VOICE`** on any character card (e.g., Vegeta) to jump straight into PURR's **Clone Studio**, forge a custom cloned voice, and automatically return with that clone bound directly to the character.
  * **🎬 Hardware `MediaCodec` `.MP4` Motion-Comic Exporter**: Renders 24fps H.264/AAC `.mp4` episodes with smooth cubic-bezier pan/zoom transitions, Ken Burns camera drift, Holodeck dimmed page backdrops, GachaSwipe emotional pause pacing + `AtmosphereEngine` particle effects (`Embers`, `Rain`, `Sakura`, `Sparkle`), MIKAReader all-caps OCR sanitization, and burned-in speaker subtitle HUD saved to `Downloads/PURR-TTS/MangaDubs/`.

---

### 📦 Android APK Packages (Attached Below)

1. **`PURR-Engine_V1.8.9_Android.apk`** — Signed release Android APK (`com.projectmika.purrtts`, versionCode `25`).
2. **`PURR-TTS_V1.8.9.apk`** — Mirror signed release APK for direct Android sideloading.

---

# 🌌 PURR-TTS v1.8.8 // Bulletproof Mount Repairs & All-In-One Studio Suite

### ✨ What's New since v1.8.7

Eliminated `"failed to mount"` failures across all 16 TTS hubs and cloning packs, added startup self-healing for incomplete bundle extracts, and shipped 3 major All-In-One TTS Studio modules (`TOOL-08..TOOL-10`)! 🔧🎙️🎬

* **🔧 BULLETPROOF ENGINE MOUNTING & `"failed to mount"` FIXES**:
  * **Smart Hub / Sub-Cartridge / Cloned Fallback Resolver**: Fixed mount misses when a hub (`ROM-02`, `ROM-06`, `ROM-07`, `ROM-08`, `ROM-09`, `ROM-10`, `ROM-11`, `ROM-14`) or cloning bay (`CLN-01`, `CLN-02`) only has standalone sub-cartridges installed (e.g., Kokoro FP32 / v1.1 without base INT8, or Pocket FP32 / ZipVoice Full clones without base), eliminating stale empty-directory mount errors.
  * **Rule FST Ordering & 180MB FAR OOM Guard**: Fixed `ROM-16` (`AISHELL-3`), `SUB-07I` (`Matcha ZH+EN`), and `Kokoro` rule normalizers — enabled `phone -> date -> number` FST ordering cleanly for Matcha and omitted redundant 180MB `rule.far` loads when fast `.fst` rules exist so Android doesn't hit native memory mount failures.
  * **Single-Lexicon VITS / MeloTTS Contract & `espeak-ng-data` Validation**: Fixed comma-joined lexicon passing on VITS / MeloTTS (`std::ifstream` requires a single path), preferred fast `.int8.onnx` graphs (`ROM-12` VCTK), excluded `espeak-ng-data/` internals from asset scans, verified complete `phontab/phonindex/phondata/intonations` sets, and added sibling Matcha vocoder lookup + detailed missing-asset diagnostics.
  * **Startup Bundle Self-Healing**: `ModelCatalogManager` now audits every `INSTALLED` bundle on boot, automatically re-extracts intact `.tar.bz2` archives if weights or `espeak-ng-data` were left incomplete, downloads missing companion vocoders, and clears phantom `INSTALLED` badges cleanly.
* **🎛️ THREE NEW ALL-IN-ONE TTS STUDIO MODULES (`TOOL-08..TOOL-10`)**:
  * **🎙️ `TOOL-08` Multi-Voice Script & Podcast Studio**: Cast up to multiple named speakers (`HOST_A`, `HOST_B`, `NARRATOR`, `GUEST`, etc.) to any installed voices across any engines with per-speaker rate & pitch trim, run live table-reads with line highlighting, or export mastered multi-voice `.WAV` scenes.
  * **⚡ `TOOL-09` Voice A/B Benchmark & Phonetic Lab**: Head-to-head Voice A vs Voice B comparison on identical prompts with exact wall-clock latency (`ms`), audio duration (`s`), Real-Time Factor (`RTF`), hardware offload badge, instant cached A/B playback, and built-in phonetic stress-test prompts.
  * **🎬 `TOOL-10` SRT Subtitle & Batch Queue Generator**: Synthesizes multi-line voiceover queues cue-by-cue with exact 24kHz sample timestamps, exports synchronized `.SRT` & `.VTT` subtitle files alongside mastered `.WAV` audio straight to `Downloads/PURR-TTS/`, and includes one-tap clipboard copy + caption inspector.

---

### 📦 Android APK Packages (Attached Below)

1. **`PURR-Engine_V1.8.8_Android.apk`** — Signed release Android APK (`com.projectmika.purrtts`, versionCode `24`).
2. **`PURR-TTS_V1.8.8.apk`** — Mirror signed release APK for direct Android sideloading.

---

# 🌌 PURR-TTS v1.8.7 // The Full 16-Bay Fleet & Neon HUD Polish

### ✨ What's New since v1.8.6

Completed all 16 TTS Cartridge Bays (`ROM-01..ROM-16`), sleek single-row HUD dropdowns, clean Neon Theme swatch hitboxes, and smart keyboard avoidance across the terminal! 🎛️⌨️🚀

* **🎛️ COMPLETE 16-BAY TTS RACK (`ROM-15` & `ROM-16`)**: Filled the final 2 bays in the 2×8 TTS Motherboard Rack with **VITS LJSpeech Direct Lexicon** (`ROM-15`: 104MB English US studio voice backed by a direct 120k-word pronunciation lexicon) and **Icefall AISHELL-3 Multi-Speaker** (`ROM-16`: ultra-compact 30MB Mandarin VITS engine with 10 curated speakers and full `rule.far` OpenFst text normalization wired cleanly into `OfflineTtsConfig`).
* **🎨 NEON THEME ENGINE SWATCH HITBOXES & COMPACT HEX BAR**: Replaced oversized `OutlinedTextField` hex inputs with sleek `36.dp` Cyberdeck `BasicTextField` HUD bars, increased swatch heights (`>= 38.dp`) and row spacing so every preset button taps cleanly in the middle without adjacent text-field touch overlap.
* **🔽 SLEEK SINGLE-ROW FILTER & SORT HUD BAR**: Redesigned the Cartridge Bay `FLT` and `SRT` dropdown controls into a compact single-row beveled Cyberdeck HUD bar with live count badge and inline `[×]` reset.
* **⌨️ SMART KEYBOARD AVOIDANCE (`imePadding` + `bringIntoViewWithIme`)**: Fixed soft keyboards covering bottom text boxes — the viewport shrinks above the IME keyboard, the floating bottom HUD auto-hides while typing, and bottom inputs automatically scroll into view with clear padding above the keyboard.

---

### 📦 Android APK Packages (Attached Below)

1. **`PURR-Engine_V1.8.7_Android.apk`** — Signed release Android APK (`com.projectmika.purrtts`, versionCode `23`).
2. **`PURR-TTS_V1.8.7.apk`** — Mirror signed release APK for direct Android sideloading.

---

# 🌌 PURR-TTS v1.8.6 // The Neon Synthesis Fleet

### ✨ What's New since v1.8.5

Inflect v2 & Icefall VITS wired up cleanly, 5 new on-device neural TTS hubs (14 total), persisted Sort/Filter dropdowns, and the full-app Neon Theme Engine! 🌈⚡🎙️

* **🔧 INFLECT v2, ICEFALL VITS & MATCHA WIRING FIX**: Eliminated silent fallbacks to Android System TTS when running Inflect v2 (`ROM-08`), Icefall VITS (`ROM-09`), and Matcha Flow engines. Stopped Smart Hardware Offload (`NNAPI`) from engaging on dynamic-shape VITS/MeloTTS/Matcha graphs (with automatic CPU fail-soft recovery if a runtime offload fails), isolated Matcha Flow extract directories (`matcha_<id>`), extended voice-family inference (`inflect_`, `icefall_`, `kmicro_`, `coqui_`, `mms_`, `vctk_`, `mimic3_`), and blocked silent `CORE_OS` fallback whenever an installed neural cartridge is slotted.
* **🌐 THE DEFACTO ON-DEVICE TTS FLEET (`ROM-01..ROM-14` + EXPANSIONS)**: Added 5 brand-new open-source neural TTS hubs and 9 expansion sub-cartridges (all SHA-256 verified):
  * **`ROM-10` Coqui VITS Hub**: 10 curated VCTK multi-accent speakers + `SUB-10A` LJSpeech Studio & `SUB-10B` Neon AI LJSpeech.
  * **`ROM-11` Meta MMS Multilingual**: Pure character-frontend English ONNX + `SUB-11A` Spanish, `SUB-11B` French, and `SUB-11C` German packs.
  * **`ROM-12` VCTK 109 Studio**: Fast built-in CMU-lexicon 10-speaker VCTK pack with zero espeak overhead.
  * **`ROM-13` Matcha Flow EN + 22kHz Vocos**: English conditional flow-matching Matcha narrator paired with universal 22kHz Vocos vocoder.
  * **`ROM-14` Mycroft Mimic3 MultiLang**: Spanish (`es-ES`) base + `SUB-14A` Korean KSS (`ko-KR`).
  * **`ROM-07` Piper / MeloTTS Additions**: `SUB-07J` Piper Jenny Dioco (UK Velvet), `SUB-07K` Piper Alan Medium (UK Male), and `SUB-07L` MeloTTS Bilingual ZH+EN.
* **🌈 `TOOL-07` NEON THEME ENGINE (RETRO <-> NEON GLOW + CONTRAST GUARD)**: Keep the classic multi-accent **Retro Cyberdeck** cartridge skin or transform the entire app into a glowing **Neon Glow** UI! Pick your backdrop color (default Pure Black `#000000`, presets including Soft Light, or custom `#RRGGBB` hex), choose your primary glowing accent (e.g. Electric Cyan, Neon Pink, Toxic Green, Pulse Gold), and tune bloom intensity — while automatic WCAG 2.1 contrast enforcement guarantees crisp, readable text and borders on every surface.
* **🗂️ PERSISTED SORT & FILTER DROPDOWNS**: Converted Cartridge Bay Filter (`ALL` / `INSTALLED` / `UNINSTALLED` / `SLOTTED`) and Sort (`BAY` / `NAME` / `SIZE` / `NEWEST`) controls into clean side-by-side dropdown menus whose selections persist across screen switches and app restarts.

---

### 📦 Android APK Packages (Attached Below)

1. **`PURR-Engine_V1.8.6_Android.apk`** — Signed release Android APK (`com.projectmika.purrtts`, versionCode `22`).
2. **`PURR-TTS_V1.8.6.apk`** — Mirror signed release APK for direct Android sideloading.

---

# 🌌 PURR-TTS v1.8.5 // The Cloning Bay & Smart Offload

### ✨ What's New since v1.8.4

Clean collapsible racks, a dedicated Voice Cloning bay, smart hardware acceleration, and a wider neural voice lineup! 🎛️🧬🐾

* **🧬 DEDICATED `CLONING` CATEGORY (`CLN-01..CLN-05`)**: Voice cloning cartridges now live in their own dedicated bay alongside `TTS` and `TOOLS` — hosting **Pocket TTS** (`CLN-01` + `SUB-C1A` FP32 Studio), **ZipVoice** (`CLN-02` + `SUB-C2A` Full Studio), **Chatterbox-Turbo 350M** (`CLN-03`), **Qwen3 0.6B Base Cloner** (`CLN-04`), and the **Voice Clone Studio** OS module (`CLN-05`). Existing v1.8.4 installations migrate cleanly without losing downloaded weights or saved clones.
* **📂 COLLAPSIBLE SUB-MODULES WITH ANIMATED EXPANDERS**: Hub cartridges now hide their sub-modules inside clearly labeled expandable drawers (`[EXPAND]` / `[HIDE]`) with live chip counts (`N ON` / `N READY` / `N CHIPS`), collapsed content previews, bouncy rotating chevrons, and smooth `AnimatedVisibility` expand/shrink transitions.
* **🔧 QWEN3 VOICE CLONE STUDIO FIX**: Eliminated silent fallback to default Android system TTS when targeting Qwen3 Base or other cloning engines — missing/incomplete models surface actionable cartridge status messages, Qwen3 Base verifies its speaker & codec encoders cleanly, retries x-vector-only cloning automatically if ICL transcript alignment returns empty, and Clone Studio blocks silent placeholder registrations while showing `[READY]` / `[INSTALL IN BAY]` tags per engine.
* **⚡ SMART HARDWARE OFFLOAD `[EXPERIMENTAL]` + COMPACT SETTINGS**: Cleaned up the Settings sheet into a tighter scrollable terminal dialog with compact `[ON]`/`[OFF]` toggle pills instead of oversized switches, and added an experimental **Smart Hardware Offload** toggle powered by `PurrHardwareProbe` — automatically selecting Adreno OpenCL, Hexagon/NNAPI FP16, or XNNPACK per SoC/runtime with automatic CPU fallback if any graph rejects delegation.
* **🎙️ NEW TTS ENGINES, 8-PER-ROW RACK & BAY SORT/FILTER**: Added **Inflect v2 Neural** (`ROM-08`: 21MB Nano + `SUB-08A` 41MB Micro), **Icefall VITS Studio** (`ROM-09`: 70MB LJSpeech Medium + `SUB-09A` 30MB Lite), **KittenTTS Micro v0.8** (`SUB-06B`), plus LAN Heavy-Model Proxy presets for **OuteTTS GGUF**, **Orpheus TTS**, **VibeVoice-Realtime-0.5B**, **Spark-TTS-0.5B**, and **CosyVoice 2**. The Motherboard Pin Rack now wraps cleanly at **8 bays per row**, and new **Filter** (`ALL` / `INSTALLED` / `UNINSTALLED` / `SLOTTED`) + **Sort** (`BAY` / `NAME` / `SIZE` / `NEWEST`) controls keep growing racks easy to navigate.

---

### 📦 Android APK Packages (Attached Below)

1. **`PURR-Engine_V1.8.5_Android.apk`** — Signed release Android APK (`com.projectmika.purrtts`, versionCode `21`).
2. **`PURR-TTS_V1.8.5.apk`** — Mirror signed release APK for direct Android sideloading.

---

# 🌌 PURR-TTS v1.8.4 // The Open Covenant

### ✨ What's New since v1.8.3

The engine gets its true colors — in-app *and* in law — and learns to check its own releases. ⚖️🐾

* **⚖️ AGPL-3.0-OR-LATER (THE MIT ERA ENDS)**: PURR-TTS's code is now free software under the **GNU Affero General Public License v3.0 or later**. Nothing changes for you: the app stays 100% free, fully on-device, no accounts, no telemetry. What changes is the covenant — every derivative must ship its full source under the same license (no take-it-close-and-sell forks), while the **PURR Engine / PURR-TTS / M.I.K.A. brand stays exclusive to ProjectMIKA**. Releases up to v1.8.3 remain MIT for their copies; the in-app Settings sheet now states the real license. Full scope lives in the repo's ⚖️ License & Notice section.
* **🤝 PROJECTMIKA INDIVIDUAL CLA**: the contribution machinery is live — a broad ProjectMIKA ICLA (contributors keep full credit; ProjectMIKA gains the unified right to relicense and distribute, keeping every commercial door open), signed one-click via CLA Assistant on the first PR — plus `.github/CONTRIBUTING.md`, a PR template, and `CODEOWNERS`.
* **🔄 MANUAL UPDATE CHECK**: a new **`> CHECK_FOR_UPDATES`** row in the Settings sheet — one tap hits GitHub's `releases/latest`, a strict numeric compare runs against the running version (pre-release suffixes never promote), and a newer release opens straight in your browser for downloading. **Zero background polling** — no WorkManager, no alarms, no silent traffic; you tap, it checks. Fail-soft: no network is a toast, not a crash.
* **🎨 VISUAL LANGUAGE STYLE GUIDE**: `.github/STYLE_GUIDE.md` codifies the obsidian-terminal skin for contributors — the `MikaColors` palette lanes, the surface ladder, the monospace law, the fixed-size slot law, the Canvas-icon law, the motion doctrine, and a pre-PR checklist. Generic dashboard styling now has a documented rejection reason.
* **🔗 COMMUNITY-PAGE LINK FIXES**: the `CONTRIBUTING.md` CLA/LICENSE links are now absolute (GitHub's Contributing tab resolves relative links from the repo root — the old `../CLA.md` 404'd there), and the README changelog moved to the Releases page, which is now the single canonical changelog home.

---

### 📦 Android APK Packages (Attached Below)

1. **`PURR-Engine_V1.8.4_Android.apk`** — Signed release Android APK (`com.projectmika.purrtts`, versionCode `20`).
2. **`PURR-TTS_V1.8.4.apk`** — Mirror signed release APK for direct Android sideloading.

---

# 🌌 PURR-TTS v1.8.3 // The Decoupled Dialects

### ✨ What's New since v1.8.2

The Rulebook finally reads like a sentence: one dropdown, three honest toggles, and a proper home for hardware experiments. 🎛️🐾

* **🎛️ ONE SLICK PRESET DROPDOWN**: the PURR Rulebook's "pick your vibe preset" card wall collapsed into a single terminal **CyberDropdown** (`> ACTIVE_PRESET`) — every vibe preset (customs included) in one expandable tray, with the active preset's full description still rendered underneath. Swapping personas is one tap, zero scroll.
* **🗣️ THREE-WAY TEXT BENDER SPLIT**: the confusing combined toggles are gone. **Slang**, **Honorifics**, and **Stutters** are now three independent toggles, each with its own per-preset default — slang expansions (`omg` → "oh my god"), anime honorifics & vocalisms (`-senpai` → "sen-pie", `nya~` → "nyaah"), and organic stutters (`w-what` → "wuh-what") can each be dialed on their own.
* **🛡️ SLANG-ONLY FLAGSHIP LAW**: the "Slang & Honorifics on Flagship Voices" carve-out toggle is retired. New law: on Neural Pure flagship voices (Qwen3, NeuTTS-2E, Chatterbox, Soprano, Studio FP32) **only Slang survives** the auto-bypass — it's a pronunciation aid, not a prosody enhancer — while honorifics & stutters are always bypassed there. The Slang toggle is the single source of truth on every engine class.
* **⚙️ `> HARDWARE_OFFLOAD // [EXPERIMENTAL]` IN SETTINGS**: the **Qwen3 GPU Offload (Adreno OpenCL)** toggle moved out of the Rulebook into the System Settings sheet under an amber `[EXPERIMENTAL]` badge — and it is now a GLOBAL device setting instead of a per-vibe-preset one, so your accelerator choice survives persona switches. This section is the future home for engine-level GPU/NPU offloads. Per-cartridge accelerator chips (the Kokoro Hexagon-NPU / Adreno-GPU sub-cartridges that need their own downloads) stay in the Cartridge Bay where they belong.
* **🧬 STATE-FILE MIGRATION**: existing installs migrate cleanly — the old combined flag seeds the three new toggles (clean-prose presets stay clean), and anyone who explicitly disabled the v1.8.2 flagship carve-out (full v1.8.0 Neural Pure) starts with Slang OFF everywhere, honoring their expressed preference. One tap re-enables it.
* **🧪 Test coverage expanded**: new `PurrProfileRepositoryMigrationTest` (five JVM tests: on/off seeding, the full-Neural-Pure slang-off rule, GPU-offload hoisting, and already-migrated passthrough) plus rewritten Neural-Pure and independent-toggle processor tests.

---

### 📦 Android APK Packages (Attached Below)

1. **`PURR-Engine_V1.8.3_Android.apk`** — Signed release Android APK (`com.projectmika.purrtts`, versionCode `19`).
2. **`PURR-TTS_V1.8.3.apk`** — Mirror signed release APK for direct Android sideloading.

---

# 🌌 PURR-TTS v1.8.2 // The Adreno Offload

### ✨ What's New since v1.8.1

Qwen3 gets a GPU lane and large models get their slang back! 🎮🐾

* **🎮 OPTIONAL ADRENO OPENCL GPU OFFLOAD (QWEN3)**: New **"Qwen3 GPU Offload (Adreno OpenCL)"** toggle in the P.U.R.R. Rulebook (default OFF). ON = the Qwen3 talker & predictor llama models run on your Adreno GPU via a newly-built `libggml-opencl.so` (embedded OpenCL kernels + Qualcomm's Adreno-optimized matmul kernels) instead of CPU — big synthesis speedup on Snapdragon flagships. Engineered to your law: **fail-soft everywhere** — unsupported tensor types and devices without a vendor `libOpenCL.so` fall back per-tensor (or fully) to CPU automatically, never fail-open; toggling mid-session re-mounts the engine (no restart); the status badge flips to `[GPU FP16]`, telemetry appends `• Adreno OpenCL`, and logcat names the engaged backend.
* **🛠️ NATIVE ENGINEERING BEHIND IT**: Vendored Khronos OpenCL-Headers (`Apache-2.0`, see NOTICE) plus a link-only stub `libOpenCL.so` generated at configure time (never packaged — runtime resolves the device's vendor lib); OpenCL symbols stay undefined at link time (`-Wl,-z,undefs`) and resolve from the device; LunaVox's hardcoded `n_gpu_layers=-1` is now a runtime gate via `patches/lunavox_opencl_gpu_offload.patch` (applied fail-closed in CI). NPU (Hexagon) remains impractical for Qwen3 — the experimental `ggml-hexagon` backend lacks q5_k support and needs Qualcomm's proprietary QNN SDK; the Kokoro NPU chip stays the NPU lane.
* **🗣️ SLANG DEFAULT ON FOR FLAGSHIP VOICES**: `slangDuringNeuralPure` now defaults **ON** — large TTS models (Qwen3, NeuTTS-2E, Chatterbox, Soprano, Studio FP32 clones) read texting shorthand meaningfully (`omg` → "oh my god", `senpai` → "sen-pie") instead of verbatim gibberish, while the truly intrusive Neural Pure bypasses (Drama-Matrix stutters, comma-chopping, micro-chunking, EQ coloring, MIKASTOP injection) stay active. The **"Slang & Honorifics on Flagship Voices"** Rulebook toggle (added in v1.8.1) now ships checked; flip it off to restore the full v1.8.0 silence.
* **🧪 Test suite updated** for the new carve-out semantics (three-way coverage: carve-out default, explicit full bypass, Neural-Pure-off override).

---

### 📦 Android APK Packages (Attached Below)

1. **`PURR-Engine_V1.8.2_Android.apk`** — Signed release Android APK (`com.projectmika.purrtts`, versionCode `18`).
2. **`PURR-TTS_V1.8.2.apk`** — Mirror signed release APK for direct Android sideloading.

---

# 🌌 PURR-TTS v1.8.1 // The Trust Renewal

### ✨ What's New since v1.8.0

A reliability hotfix for the cartridge download pipeline, prompted by live user reports (Pocket TTS failing SHA-256 on every fresh install). 🐾

* **🔧 STALE PIN HOTFIX (POCKET TTS DOWNLOADS FIXED)**: k2-fsa silently re-uploaded `sherpa-onnx-pocket-tts-int8-2026-01-26.tar.bz2` and `sherpa-onnx-pocket-tts-2026-01-26.tar.bz2` under the same `tts-models` release tag (2026-11-21), so every fresh Pocket TTS / clone download failed the fail-closed SHA-256 check with `Expected 2f3b8882…, got f4da7c7b…`. Both catalog pins now match the live upstream digests.
* **🛡️ SELF-HEALING HASH MANIFEST**: New `model_manifest.json` (hosted in this repo, regenerated by `scripts/refresh_model_manifest.py`, drift-audited with `--check`) carries the current verified SHA-256 for all 27 network catalog assets (GitHub releases + HuggingFace LFS). The app resolves `live manifest → baked pin` at every download/verify, force-refreshes the manifest ONCE on any mismatch before failing closed, and caches the last-good copy for offline use — future upstream re-uploads self-heal with a one-line manifest commit instead of an APK release. The fail-closed SHA-256 law itself is unchanged.
* **⛑️ HTTP 416 SELF-HEAL (RETRY TRAP FIXED)**: A leftover complete `.part` file (e.g. from a download rejected by a stale pin) used to dead-end every retry with `HTTP 416` forever. Resume now recognizes the complete file, hash-verifies it, and promotes it into the cartridge — or, if the part is genuinely stale, deletes it and restarts cleanly from byte 0. Users who already downloaded Pocket TTS get it installed on first tap without re-downloading.
* **📣 CARTRIDGE-CODED FAILURE MESSAGES**: Missing-voice errors now name the ROM bay to download instead of just the voice — e.g. `Af Heart offline — ROM-02 KOKORO_NEURAL_HUB cartridge not installed (download it in Tab 3)` — plus `[MOUNT_MISS]` logcat diagnostics that state exactly which directory was checked and what was found.
* **🎛️ CALMER AUDITION + LIVE CHUNK PROGRESS**: The default test line no longer packs ellipses, a phonetic stutter, and an action block (MIKASTOP pause injection + Qwen3's ~30s per-chunk latency made the old one look "stuck processing"), and the Voice test bench now shows live `[n/total] NEURAL` chunk progress while auditioning.

---

### 📦 Android APK Packages (Attached Below)

1. **`PURR-Engine_V1.8.1_Android.apk`** — Signed release Android APK (`com.projectmika.purrtts`, versionCode `17`).
2. **`PURR-TTS_V1.8.1.apk`** — Mirror signed release APK for direct Android sideloading.

---

# 🌌 PURR-TTS v1.8.0 // Audiobook Constructor (.M4B), Qwen3 VoiceDesign Studio, Neural Pure Mode & Persistent Signing

### ✨ What's New since v1.7.1

Six major upgrades turning PURR-TTS into a full mobile voice lab and chaptered audiobook studio! 🐾👑

* **🔐 PERMANENT RELEASE SIGNING (IN-PLACE APK UPGRADES)**: Replaced per-build CI ephemeral key generation with a permanent persistent signing key in CI secrets. *(Note: installing `v1.8.0` over `v1.7.1` requires one final clean reinstall because `v1.7.1` used an ephemeral key; starting with `v1.8.0 -> v1.8.1+`, every future release upgrades cleanly in-place keeping all downloaded models and profiles!)*
* **👑 FIX QWEN3 `NhwcFused` SPEAKER ENCODER CRASH**: Set ONNX Runtime CPU EP optimization level in `provider_policy_android.cpp` to `ORT_ENABLE_BASIC` so ONNX Runtime 1.31.1 on ARM64 no longer rewrites FP16 `Conv` nodes into unsupported `NhwcFused` kernels inside `qwen3_tts_speaker_encoder.fp16.onnx`, `codec_encoder.fp16.onnx`, and `decoder.fp16.onnx`.
* **🧠 NEURAL PURE MODE (AUTO-BYPASS ON FLAGSHIP MODELS)**: Heavyweight neural voices (`Qwen3-TTS`, `NeuTTS-2E`, `Chatterbox-Turbo`, `Soprano-80M`, `Kokoro FP32 / v1.1`, `ZipVoice Full`, `Pocket FP32`, `MeloTTS`, `Matcha`) now automatically bypass disruptive slang expansion, anime honorific mangling, stutter injection, comma micro-chopping, and neutral EQ coloring — keeping expressive quotes, commas, capitalization, Chatterbox `[laugh]`/`[sigh]`/`[gasp]` tags, and NeuTTS-2E sentiment controls crisp and natural (toggleable anytime in P.U.R.R. Engine settings).
* **🎛️ SLICK VOICE CLONE STUDIO DROPDOWN**: Replaced the 2×3 engine pill grid in `VoiceCloneStudioSheet` with a sleek `CyberDropdown` engine selector (`> CLONE_ENGINE_TARGET`) and added smooth vertical scrolling.
* **🎨 NEW OS MODULE — `TOOL-06: QWEN3_VOICE_DESIGN` (Structured 1.7B Prompt Builder)**: Build expressive Qwen3-TTS 1.7B VoiceDesign prompts visually using curated presets + Gender, Age, Pitch, up to 3 Vocal Timbres, Pace, Emotional Delivery, Accent/Language, Mic Acoustics, Character Role & Nuance controls — save custom voice designs, audition live, or lock into a reusable Clone Voice via the Design→Clone pipeline!
* **📚 NEW OS MODULE — `TOOL-07: AUDIOBOOK_CONSTRUCTOR` (Multi-Format Ebook -> Chaptered `.M4B`)**:
  * Import **EPUB**, **PDF** (`pdfbox-android` + bookmark outline awareness), **TXT**, **MD**, **HTML/XHTML**, **DOCX**, or **FB2** files.
  * Automatically extracts & splits chapters, lets you curate/check which chapters to include (`ALL` / `STORY ONLY` / `NONE`) or rename/preview chapters.
  * Construct chapters with **Start / Pause & Save / Resume Later** checkpoint persistence (both completed chapter `.wav` files and mid-chapter chunks persist across restarts!).
  * Combine finished chapters into a single standard **chaptered `.m4b` audiobook** (`MediaCodec` AAC + `MediaMuxer` + Nero `chpl` chapters & iTunes `stik = 2` metadata atoms) saved to `Downloads/PURR-TTS/Audiobooks/` — ready for Smart AudioBook Player, Voice, Listen, Audiobookshelf, VLC & more.

---

### 📦 Android APK Packages (Attached Below)

1. **`PURR-Engine_V1.8.0_Android.apk`** — Signed release Android APK (`com.projectmika.purrtts`, versionCode `16`).
2. **`PURR-TTS_V1.8.0.apk`** — Mirror signed release APK for direct Android sideloading.

---

# 🌌 PURR-TTS v1.7.1 // Crystal Clear Qwen3, NeuTTS-2E Fix & Module Dock Polish

### ✨ What's New since v1.7.0

Five focused runtime and UI polish fixes from live on-device testing! 🐾✨

* **👑 QWEN3 `0.6B` INSTRUCT FIX + NO MIXED OS FALLBACK**: `Qwen3TtsEngine` now reads `instruct_support` from `model_profile.json` and stops passing sentiment `instruct` phrases to `0.6B CustomVoice` (`instruct_support: false`), preventing LunaVox rejections on emotional chunks (and preventing mixed OS-speech + neural-speech audition transitions or silent drops when `ROM-01` is ejected).
* **🎙️ ZERO ROBOTIC REVERB ON SPEED ADJUSTMENT (`PurrDspProcessor.timeScaleWsola`)**: Replaced naive unaligned overlap-add scaling in `Qwen3TtsEngine` and `OnnxRuntimeEngine` with phase-coherent **Waveform Similarity Overlap-Add (WSOLA)** and bypassed neutral `0.94x..1.06x` speech rates bit-exact so voices stay crisp and metallic-free everywhere.
* **🎭 NEUTTS-2E OOM CRASH FIXED**: Added `android:largeHeap="true"`, replaced full-DOM `tokenizer.json` loading with a **zero-AST streaming JSON parser** in `PurrBpeTokenizer`, and extracted only the final sequence step in `OnnxRuntimeEngine` (avoiding 387MB heap spikes on NeuTTS-2E's 217k vocab head) — NeuTTS-2E now renders smoothly without silently failing back to OS voices.
* **🧩 MODULES WINDOW ICON DOCK**: Replaced the multi-line text switcher inside open module windows with a fixed-height horizontally scrollable bar of mini `MikaOsModuleIcon` SVGs featuring pulsating left/right scroll chevrons whenever more apps exist off-screen.
* **📡 CRISP `[IDLE]` BADGE & SEAMLESS MARQUEE (`MikaStatusBadge`)**: Fitted the top-right status badge neatly into a `70.dp × 26.dp` capsule aligned with `SET_OS_TTS` & Settings, centered short labels (`[IDLE]`), and upgraded overflowing labels (`[CPU NEON]`, `[NPU ACTIVE]`, VRAM timer) to a smooth continuous marquee loop.

---

### 📦 Android APK Packages (Attached Below)

1. **`PURR-Engine_V1.7.1_Android.apk`** — Signed release Android APK (`com.projectmika.purrtts`, versionCode `15`).
2. **`PURR-TTS_V1.7.1.apk`** — Mirror signed release APK for direct Android sideloading.

---

# 🌌 PURR-TTS v1.7.0 // The Heavy Local Fleet & Clean 8-Bay Hub Rack

### ✨ What's New since v1.6.2

Four heavyweight neural TTS architectures now run **100% locally on Android** — no cloud API, no proxy server required — and the Cartridge Bay is reorganized into a crisp **8-Bay Hub Rack** (`ROM-01..ROM-08`)! 🐾👑

* **👑 `ROM-04: QWEN3_TTS_STUDIO` (Native C++ JNI — `llama.cpp b8470` + `LunaVox` + `ORT 1.31.1`)**:
  * **Base (`0.6B CustomVoice`, ~1.05 GB)**: 9 multilingual studio speakers (`Vivian`, `Serena`, `Ryan`, `Aiden`, `Dylan`, `Eric`, `Ono Anna`, `Sohee`, `Uncle Fu`) across EN/ZH/JA/KO/EU with automatic language detection.
  * **`SUB-04A: QWEN3_06B_BASE_CLONER` (~1.17 GB)**: 3-second multilingual zero-shot voice cloning in Clone Studio.
  * **`SUB-04B: QWEN3_17B_VOICEDESIGN` (~2.1 GB, 12GB+ RAM)**: Type any natural-language voice description right inside the Voice tab (`Warm Anime`, `Cinema Baritone`, `Soft ASMR`, or custom prompt) to synthesize bespoke voices on-device.
* **🎭 `ROM-05: NEUTTS_2E_EMOTIONAL` (`Qwen3-236M INT8 ONNX` + `NeuCodec INT8`, ~654 MB)**:
  * Four expressive English voices (`Emily`, `Paul`, `Sophie`, `Steven`) with bundled NeuCodec speaker codes and automatic **PURR Sentiment → `<|HAPPY|>` / `<|SAD|>` / `<|ANGRY|>` / `<|SURPRISED|>`** emotion control injection.
* **✨ `ROM-06: SOPRANO_32KHZ_LLM` (`ekwek/Soprano-80M ONNX`, ~362 MB)**:
  * Compact 80M Qwen3-backbone neural TTS emitting ultra-crisp 32kHz Vocos audio (master-resampled to 24kHz) with tiny latency.
* **🤣 `SUB-03E: CHATTERBOX_TURBO_350M` (`ResembleAI/chatterbox-turbo-ONNX Q4`, ~691 MB)**:
  * Zero-shot cloning PLUS native paralinguistic reaction tags (`[laugh]`, `[chuckle]`, `[sigh]`, `[gasp]`, `[cough]`, `[clear throat]`, `[whispering]`, and more)! PURR's Symbol Shield protects native tags automatically and maps RP `*laughs*`/`*sighs*` actions directly to Chatterbox tags.
* **🧩 CLEAN 8-BAY HUB RACK & CLONE STUDIO UPGRADE (`ROM-01..ROM-08`)**:
  * **ROM-02 `KOKORO_NEURAL_HUB`** now nests Hexagon NPU (`SUB-02A`), Adreno GPU (`SUB-02B`), Kokoro FP32 (`SUB-02C`), and Kokoro v1.1 103-Speaker (`SUB-02D`).
  * **ROM-03 `VOICE_CLONE_HUB`** unites all 5 dedicated cloning sub-engines: Pocket INT8 (`SUB-03A`), **Pocket FP32 Studio (`SUB-03B`)**, ZipVoice INT8 (`SUB-03C`), **ZipVoice Full Non-Distilled (`SUB-03D`)**, and **Chatterbox-Turbo 350M (`SUB-03E`)**. Clone Studio supports all 6 local cloning engines (including `SUB-04A` Qwen3 0.6B Base).
  * **ROM-07 `KITTEN_TTS_HUB`** adds **KittenTTS Mini 80M (`SUB-07A`)** alongside 30MB Nano.
  * **ROM-08 `NARRATOR_VITS_FLOW_HUB`** unites Piper Lessac Base, 6 Piper expansion voices, **MeloTTS English 5-Accent (`SUB-08G`)**, Supertonic 3 (`SUB-08H`), and Matcha-TTS + 16kHz Vocos (`SUB-08I`).
* **🔁 State Migration & Pure-JVM Tests**: Automatically migrates `v1.6.2` saved slotting/cartridge state into the new 8-Bay Hubs; adds `PurrHeavyEnginesTest` and updates `PurrPremiumCatalogTest`. `TUTORIAL_VERSION` bumped to `3`!

---

### 📦 Android APK Packages (Attached Below)

1. **`PURR-Engine_V1.7.0_Android.apk`** — Signed release Android APK (`com.projectmika.purrtts`, versionCode `14`) with Qwen3-TTS JNI, Chatterbox-Turbo Q4, NeuTTS-2E, Soprano-80M, MeloTTS, Kitten Mini, FP32/Full clones & the 8-Bay Hub Rack.
2. **`PURR-TTS_V1.7.0.apk`** — Mirror signed release APK for direct Android sideloading.

---

# 🌌 PURR-TTS v1.6.2 // One Voice, One Name

### ✨ What's New since v1.6.1

The premium Kokoro cartridges were fail-softing to stock OS voices — root cause found and fixed at the identity level! 🐾

* **🔎 THE VOICE-ID COLLISION FIX**: ROM-08's fp32 roster reused ROM-02's exact voice IDs (`af_heart`, `af_bella`...), and every synthesis-time lookup resolves the FIRST match — ROM-02's descriptor, which mounts the plain `kokoro` weights dir. With ROM-02's int8 not installed, the outcome was empty → the fail-soft router handed the utterance to **CORE_OS stock voices**. Premium rosters now carry **globally unique prefixed IDs** (`fp32_af_heart`, `v11_af_maple`, `v11_zf_001`...) so the router, speaker resolver and weights-dir mount all land on the right cartridge every time.
* **🔁 SELECTION MIGRATION**: Profiles saved while the bug was live (primary/dialogue voice pointing at bare ids) are remapped to the new prefixed IDs on first launch — no re-selection needed.
* **🧪 +1 LAW TEST**: `all built-in voice ids are globally unique across cartridges` — this class of routing bug can never compile-ship again.

---

### 📦 Android APK Packages (Attached Below)

1. **`PURR-Engine_V1.6.2_Android.apk`** — Signed release Android APK (`com.projectmika.purrtts`, versionCode `13`) with the unique voice-ID routing fix and selection migration.
2. **`PURR-TTS_V1.6.2.apk`** — Mirror signed release APK for direct Android sideloading.

---

# 🌌 PURR-TTS v1.6.1 // The Right Vocoder

### ✨ What's New since v1.6.0

Two live-testing fixes for the premium tier — one invisible, one audible! 🐾

* **🎙️ PREMIUM VOICES NOW SELECTABLE (`PurrProfileRepository`)**: The fp32/v1.1 voice rosters were filtered out by the sub-cartridge availability gate (`enabledSubCartridges`), which top-level ROMs never enter. Voice availability now also accepts a top-level cartridge that is **INSTALLED** in `modelStates` — all 54 fp32 voices and all 103 v1.1 speakers appear in the selector the moment their cartridge is slammed.
* **🍵 MATCHA VOCODER FIXED (16kHz!)**: The `matcha-icefall-zh-en` model card requires **`vocos-16khz-univ.onnx`** — the model is 16kHz and v1.6.0 shipped the 22kHz vocoder, producing robotic garbage. The companion spec now points at the 16kHz vocoder (digest-verified), stale vocoder variants are auto-removed on install, and a **self-healing companion migration** re-fetches the right vocoder for already-installed cartridges on first launch of the update. No manual re-download needed.

---

### 📦 Android APK Packages (Attached Below)

1. **`PURR-Engine_V1.6.1_Android.apk`** — Signed release Android APK (`com.projectmika.purrtts`, versionCode `12`) with the premium voice-availability fix and the correct Matcha vocoder.
2. **`PURR-TTS_V1.6.1.apk`** — Mirror signed release APK for direct Android sideloading.

---

# 🌌 PURR-TTS v1.6.0 // The Heavy Weights

### ✨ What's New since v1.5.1

The Forge grows a **premium tier** — bigger, heavier, higher-fidelity engines for strong phones, plus a bridge to the true "not meant for mobile" giants. The rack is now **10 bays**! 🏋️🐾

* **🏋️ ROM-08 `KOKORO_82M_FP32` (334MB)**: The unquantized full-precision Kokoro weights — the same 54-voice roster as ROM-02 but with noticeably richer breath, sibilance and emotional depth. Studio grade; needs 8GB+ RAM (warned on the cartridge, protected by the 5-min eviction timer).
* **🎙️ ROM-09 `KOKORO_V11_MULTILING` (140MB INT8)**: Kokoro v1.1-zh — **103 speakers**: `af_maple`, `af_sol`, `bf_vale` in English plus 100 Chinese voices (55 female `zf_*`, 45 male `zm_*`). Fully bilingual synthesis with dual lexicons (EN + ZH), all offline.
* **🍵 ROM-10 `MATCHA_ZH_EN` (75MB + 51MB vocoder)**: Matcha-TTS flow-matching synthesis — a completely different neural architecture with astonishingly natural prosody and blazing speed (RTF < 1 even on a Raspberry Pi). The **Vocos vocoder ships as a SHA-256-verified companion download** unpacked into the same cartridge dir.
* **🌉 HEAVY_MODEL_PRESETS**: The LAN proxy panel gains one-tap presets — **Kokoro-FastAPI**, **openedai-speech**, **F5-TTS wrapper**, and **OpenAI Cloud** — so the true giants (CosyVoice, F5-TTS, XTTS-v2) run on your PC while the phone streams through the full PURR pipeline. (Investigated Qwen TTS: DashScope WebSocket-only, closed weights — not reachable through an OpenAI-compatible bridge, documented in-source.)
* **🔧 ENGINEERING**: Per-cartridge Kokoro extract dirs (no more `model.onnx` collisions), a new `MATCHA` sherpa family with acoustic+vocoder config, voice routing for the numeric v1.1 roster (`zf_001`), and resume-capable companion-asset downloads.
* **🧪 +7 PURE-JVM TESTS**: `PurrPremiumCatalogTest` — digest format, bay sequence, companion verification fields, the 54-voice fp32 mirror (byte-identical IDs & speaker indexes to ROM-02), the full 103-speaker v1.1 roster with correct language split, and Qwen-free proxy presets. The patchnotes you're reading? The Academy pops this up automatically — `TUTORIAL_VERSION` bumped to 2!

---

### 📦 Android APK Packages (Attached Below)

1. **`PURR-Engine_V1.6.0_Android.apk`** — Signed release Android APK (`com.projectmika.purrtts`, versionCode `11`) with the premium cartridge tier, heavy-model proxy presets, and the 10-bay rack.
2. **`PURR-TTS_V1.6.0.apk`** — Mirror signed release APK for direct Android sideloading.

---

# 🌌 PURR-TTS v1.5.1 // Squeaky-Clean Buttons

### ✨ What's New since v1.5.0

A quick layout patch — the Clone Studio's new IMPORT_CLIP and CLEAN_AUDIO controls were wrapping their labels into word-salad on narrow screens. Fixed! 🐾

* **🧹 FIXED CLONE STUDIO IMPORT LAYOUT (`VoiceCloneStudioSheet`)**: The `IMPORT_AUDIO_CLIP` and `CLEAN_AUDIO` labels now own a weighted column with single-line ellipsis, the `IMPORT_CLIP` / `ON` / `OFF` chips render on one line, and the format list (`MP3 M4A OGG FLAC WAV • ≤30s`) plus the `RECOMMENDED` hint moved to tidy dim sub-lines. No more button word-salad.

---

### 📦 Android APK Packages (Attached Below)

1. **`PURR-Engine_V1.5.1_Android.apk`** — Signed release Android APK (`com.projectmika.purrtts`, versionCode `10`) with the Clone Studio layout patch.
2. **`PURR-TTS_V1.5.1.apk`** — Mirror signed release APK for direct Android sideloading.

---

# 🌌 PURR-TTS v1.5.0 // The M.I.K.A. Academy

### ✨ What's New since v1.4.0

The Forge gets a voice of its own — a full GachaSwipe-style walkthrough, changelog popups on every update, a settings cog, and two layout refinements! 🐾

* **🐾 THE M.I.K.A. ACADEMY**: A full-screen CRT tutorial on first launch — MIKA narrates 8 slides with a typewriter effect and blinking cursor while **Milk & Wire** loops as BGM with a smooth fade-in. Covers the system-wide voice setup, Cartridge Bay, the engine dropdown, the PURR Rulebook, Clone Studio (import + clean), and the tool modules. `[SKIP_ALL >>]` exits anytime; `[NEXT >]` walks you through; progress bar and slide counter included.
* **📜 UPDATE PATCHNOTES SYSTEM**: The same update-type system as GachaSwipe — whenever `TUTORIAL_VERSION` bumps in a release, everyone on an older content version gets the **VERSION_PATCHNOTES** slide auto-opened exactly once, with the gold version pill and `> HIGHLIGHTS` cards (icon + color + body per headline system). Persists via `seenTutorialVersion` in the Forge state.
* **⚙️ THE SILVER COG**: A custom Canvas-drawn settings cog now sits beside `SET_OS_TTS`, opening **`> SYSTEM_SETTINGS`**: replay the Academy anytime, open the **source repository**, open the **Ko-fi support page** (`ko-fi.com/projectmika`), and read your exact build stamp + license line.
* **🎚️ SLEEK ENGINE DROPDOWN**: The `> 1. CHOOSE_TTS_ENGINE` pill wall is now a dropdown — a collapsed row showing the active engine (with NPU/GPU/Piper suffixes), expanding into a color-coded tray with `ACTIVE` markers and per-engine subtitles.
* **✨ MARQUEE BADGE**: The header hardware badge now sits in an `[IDLE]`-sized fixed slot; longer states (`[NPU ACTIVE]`, VRAM countdown) scroll through it as a smooth bounce marquee instead of resizing the header.
* **🧪 +6 PURE-JVM TESTS**: `PurrAcademyDataTest` guards tutorial integrity (unique ids, single patchnotes slide, well-formed bodies/colors) and the update gate math (first-run, one-time patchnotes, already-seen).

---

### 📦 Android APK Packages (Attached Below)

1. **`PURR-Engine_V1.5.0_Android.apk`** — Signed release Android APK (`com.projectmika.purrtts`, versionCode `9`) with the M.I.K.A. Academy, update patchnotes popups, the settings cog + sheet, the sleek engine dropdown, and the marquee badge.
2. **`PURR-TTS_V1.5.0.apk`** — Mirror signed release APK for direct Android sideloading.

---

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
