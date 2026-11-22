---
name: mika-compose-ui
description: Compose port of GachaSwipe's terminal/neon skin — color tokens, component recipes (GlowPanel, StatusBadge slots, BottomHudBar, CyberIcons), motion doctrine, and the fixed-size slot law. Use when touching ui/theme or ui/forge in PURR-Engine.
---

# ✨ MIKA'S COMPOSE TERMINAL SKIN (PURR-Engine edition) ✨

The GachaSwipe obsidian terminal, rebuilt natively in Jetpack Compose. The React bible is the
global `mika-visual-language` skill; this is the Compose mapping + the scars of porting it.

## Color tokens (`ui/theme/MikaTheme.kt` + `MikaColors`)
- `VoidBg` (#050308 void), `PanelBg` (raised panel), `RaisedCardBg` (card gradient top),
  `NeonCyan` (primary accent), `TerminalGreen` (action/success), `MikaAmber` (warning/GPU),
  `NeonPurple` (proxy/clone), `DangerRed` (destructive/recording), `DimText` (secondary),
  `CyanWhite` (primary text). Never hardcode hex in composables — use `MikaColors`.

## Component recipes (`ui/theme/MikaComponents.kt`)
- **`MikaTerminalStage`** — full-screen CRT stage: void bg, hairline cyan side borders, 3px
  scanlines (alpha 0.016), slow vertical scan band (12s loop, 60dp gradient band). Wrap screens.
- **`MikaGlowPanel`** — terminal panel: vertical gradient card, `shadow(8dp, spotColor accent
  0.25)` + hairline accent border (alpha 0.30) + `contentPadding` (default 12dp).
- **`MikaStatusBadge`** — animated hardware badge (pulsing dot + label, 900ms reverse pulse).
  ✨ **FIXED-SIZE SLOT LAW (v1.3.3 scar)**: callers MUST pass a fixed width (`Modifier.width(112.dp)`
  in the header). The badge never self-sizes; label + VRAM countdown render `maxLines = 1` and
  clip inside the slot. Violating this re-measures the parent row, re-wraps `weight(1f)` titles,
  and shifts the entire page on every synthesis state change. Same law applies to ANY dynamic
  text in pinned regions (headers/HUD bars): fixed slot + `maxLines(1)` + `overflow` + pinned
  container height (`heightIn(min = 56.dp)`).
- **`MikaBottomHudBar`** — glassmorphic 54dp icon-only capsule (v1.3.1): frosted background,
  anchored above the gesture pill, custom Canvas icons only (no labels/numbers).
- **`MikaCyberIcon`** + `CyberIconType` — ALL icons are Canvas-drawn vectors (PLAY, STOP,
  DOWNLOAD, SLAM_IN, EJECT, MIC_WAVE, DNA_CLONE, BOLT_ACCEL…). System emojis are banned from
  the UI; never add a `Text("🔧")`.
- **`MikaVoiceDropdownSelector`** — searchable voice dropdown tray with instant ▶ TEST audition.
- **`LiveWaveformMonitor`** — 24kHz oscilloscope panel wired to router telemetry (32 bins).
- **`CartridgeSlamOverlay`** — full-screen slam-in/eject animation for cartridge lifecycle.
- **`TerminalToastBanner`** + `ExpandableTerminalSection` — toast banners + collapsible drawers.

## Motion doctrine
- Page phasing (`AnimatedContent` in `TtsForgeScreen`): in = `fadeIn(240ms) + scaleIn(0.965,
  240ms) + slideInHorizontally(dir * width/16)`; out = `fadeOut(150ms) + scaleOut(1.015) +
  slideOutHorizontally(-dir * width/24)`. Direction follows tab index delta.
- Pulse animations: `rememberInfiniteTransition` + `tween(900, LinearEasing)` +
  `RepeatMode.Reverse`. Scan band: 12s linear restart, alpha ≤ 0.05.
- Badges/pulses animate COLOR and ALPHA only — never size. Size animation in pinned layouts
  reintroduces the v1.3.3 shift bug.

## Per-screen anatomy
- `MainActivity` → `MikaTerminalStage` → `TtsForgeScreen(activeTab, onOpenSystemTtsSettings)`.
- Pinned top HUD header (status bar padding → fixed-height Row → title column `weight(1f)` +
  right control row) → `AnimatedContent` viewport (LazyColumn, contentPadding bottom 92dp for
  the HUD bar) → `MikaBottomHudBar`.
- Tabs: `VOICE` (oscilloscope, test bench, engine picker, voice selector, pacing/pitch, PURR
  rulebook), `MODULES` (Cyberdeck OS tool cartridges), `CARTRIDGE BAY` (TTS/TOOLS rack).
- Settings sheet (header cog): action rows + `> HARDWARE_OFFLOAD // [EXPERIMENTAL]` (v1.8.3) —
  GLOBAL device-level accelerator toggles (currently Qwen3 Adreno OpenCL) via `SettingsToggleRow`
  (action-row skin + Switch). Per-cartridge NPU/GPU chips stay in the Cartridge Bay; future
  GPU/NPU offloads slot here.
- PURR Rulebook: vibe presets are ONE `CyberDropdown` (label `> ACTIVE_PRESET`, `name [BADGE_TAG]`
  labels, active preset summary rendered beneath) — never a selectable card wall.

## Porting checklist (React → Compose)
1. Map CSS tokens → `MikaColors` entries; keep hex values identical.
2. Monospace law: every terminal string uses `FontFamily.Monospace`; sizes 9–15sp.
3. `> SNAKE_CASE` prefixes + `//` comment lines preserved verbatim in Text composables.
4. Dynamic text → fixed slot (see StatusBadge law) BEFORE first render.
5. Glow = shadow(spotColor) + hairline border, never blur-heavy effects (WebView scars don't
   apply here, but GPU cost still matters on low-end hosts).
6. Verify with `python3 scripts/verify_project.py` + CI build; visual check via sideloaded APK.
