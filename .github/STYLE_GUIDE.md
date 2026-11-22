# 🎨 PURR-TTS Visual Language — Contributor Style Guide

PURR-TTS looks the way it does **on purpose**. The obsidian-terminal / neon-holography aesthetic is the product's identity — ported 1:1 from GachaSwipe and rebuilt natively in Jetpack Compose, hand-tuned across thirteen releases. The fastest way to get a PR rejected here is to ship styling that looks like a generic AI-generated Android settings app — no matter how clean the Kotlin is. This guide tells you exactly what the language is, so your UI changes speak it fluently.

**The one-line test:** PURR-TTS is a *retro-future terminal that happens to be a speech engine* — not a Material You settings page. If your composable would look at home in a stock Android settings screen or a generic admin dashboard, it is wrong here, no matter how polished it is.

## The one dialect (+ where warmth lives)

GachaSwipe speaks two dialects; PURR speaks **one — the terminal**. Every screen is machine chrome: the Forge, the Rulebook, the Cartridge Bay, the Clone Studio. Warmth lives in the **copy** (M.I.K.A.'s voice in toasts, tutorials, and empty states) and in the **romance accent lanes** (pink/purple), never in Material-default rounded chrome. There is no light theme, no dynamic color, no Material You pastels. It is a dark terminal, forever.

## Hard laws (PR-ending violations)

1. **No new UI styling systems or dependencies.** The skin = `ui/theme/MikaTheme.kt` (tokens + typography) + `ui/theme/MikaComponents.kt` (recipes) + inline `Modifier` chains in composables. That is the whole system. No stock Material3 components in new work (no default `Button` / `Card` / `TopAppBar` / `FloatingActionButton`), no icon packs, no image-loading libraries for chrome.
2. **Never invent colors.** `MikaColors` is a closed set with semantic lanes (below). A hardcoded `Color(0xFF...)` inside a composable is a PR-ending violation; a brand-new token needs an accepted issue first.
3. **Depth = border + glow, never gray-on-gray.** Surfaces are separated by the surface ladder, hairline neon borders, and glow shadows — never lighter-gray-on-darker-gray.
4. **"Disabled" = alpha, not a new color.** Use `.copy(alpha = 0.35f..0.5f)` on the existing lanes.
5. **No emoji as icons.** ALL icons are Canvas-drawn `MikaCyberIcon(type = CyberIconType...)` vectors. `Text("🔧")` is a rejection. No icon fonts, no `ImageVector` packs in new work.
6. **Monospace law.** Every terminal string renders `FontFamily.Monospace`, 9–15sp, and keeps `> SNAKE_CASE` prefixes + `//` comment lines verbatim.
7. **Fixed-size slot law (v1.3.3 crash-class scar).** Dynamic text in pinned regions (header HUD, bottom bar) lives in a fixed-width `Modifier` slot with `maxLines = 1` + `overflow` + pinned container heights (`heightIn(min = ...)`). A label that re-measures its parent shifts the whole page — that bug shipped once; it never ships again.
8. **Don't redesign canonical screens.** The Forge header HUD, the glassmorphic BottomHudBar, the cartridge rack + slam overlay, the waveform monitor, the Rulebook sections, and the Settings sheet are canonical. Fix bugs in them; propose redesigns in an issue first.
9. **Motion is performance law, not decoration** — see Motion. This one has real layout-shift scars behind it.

## The palette (the closed set — `MikaColors` in `ui/theme/MikaTheme.kt`)

### Surface ladder (depth without gray)

| Token | Hex | Role |
|---|---|---|
| `VoidBg` | `#050308` | page void — the app background |
| `DeepWellBg` | `#030206` | recessed wells (deepest inset) |
| `InputBg` | `#08060F` | text fields |
| `PanelBg` | `#0B0914` | standard panels, dropdowns, sheets |
| `RaisedCardBg` | `#0F0B1A` | raised cards |
| `CardViolet` | `#171226` | elevated violet panel (the GachaSwipe card) |
| `HousingTop` | `#1C172E` | hardware-housing top gradient stop |

### The neon lanes

| Token | Hex | Lane | Use it for |
|---|---|---|---|
| `NeonCyan` | `#00E5FF` | **SYSTEM** | the workhorse: meters, tags, borders, glows, dropdowns, interactive chrome |
| `NeonPink` | `#FF107A` | **LOVE / PRIMARY** | romance accents, personality panes, primary branding |
| `NeonPurple` | `#B533FF` | **RARE / CHAOS / CLONE** | Clone Studio, rare features, dual-voice routing |
| `TerminalGreen` | `#00FF41` | **ROM-04 PURR** | success states, the engine itself, VU/positive readouts |
| `SsrGold` | `#FFD700` | **FAVORITE / VU YELLOW** | SSR-tier moments, favorites |
| `MikaAmber` | `#FF9100` | **MIKA VOICE / ALERT** | M.I.K.A.'s voice surfaces, warnings, hardware offload |
| `DangerRed` | `#FF3333` | **BLACKLIST / ERROR** | destructive actions, errors, recording |

### Tinted white families (text — never pure `#FFFFFF`)

| Token | Hex | Use |
|---|---|---|
| `CyanWhite` | `#C8E8F0` | primary terminal body text |
| `PinkWhite` | `#FFB8D5` | pink-lane body text |
| `VioletWhite` | `#E0E7FF` | violet-lane body text |
| `WarmCream` | `#EBE3D6` | warm headings/body (the GachaSwipe cream) |
| `DimText` | `#888888` | secondary text |

**Brightness comes from glow, not fill.** Build surfaces from low-alpha variants of the lanes — `.copy(alpha = 0.05f..0.16f)` fills, `0.2f..0.6f` borders, `0.25f..0.5f` glows. A full-opacity lane fill is almost always wrong.

## Recipes (taken from the real components — keep new work consistent)

### Terminal surface (GlowPanel / settings sections / sheets)

`MikaGlowPanel`: vertical-gradient card on `PanelBg`/`RaisedCardBg`, `shadow(8.dp, spotColor = accent.copy(alpha = 0.25f))`, hairline border `accent.copy(alpha = 0.30f)`, `contentPadding = 12.dp`. Inside: a `Column(spacedBy(10.dp))` of toggle rows / dropdowns / sliders. Section headers are `> SNAKE_CASE` at 11sp monospace black + a `// subtitle` line at 9.5sp dim.

### Chip / badge (the `SET_OS_TTS` pattern)

`clip(RoundedCornerShape(4.dp))` + `background(accent.copy(alpha = 0.14f))` + `border(1.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(4.dp))` + `padding(horizontal = 8.dp, vertical = 4.dp)` + monospace bold 9sp label. Larger section chips (like `[EXPERIMENTAL]`) use the same recipe at 9sp.

### Toggle row (the `SimplePurrToggle` pattern)

`Row`: Canvas icon (16dp, accent) + `weight(1f)` Column (title: monospace black 11sp, accent when checked / dim when not; subtitle: 9sp dim) + `Switch` (`checkedTrackColor = accent`, `checkedThumbColor = VoidBg`, unchecked track `PanelBg`). The row container is the chip recipe at 8dp radius.

### Dropdown (the `CyberDropdown` — the only select control)

Collapsed row: `PanelBg` fill + `1.dp` border `accent.copy(alpha = 0.40f)` (full `accent` when expanded), label 9sp accent above, value monospace semibold 11sp `VioletWhite` with a chevron `MikaCyberIcon`. Menu: `PanelBg` + `accent.copy(alpha = 0.6f)` border; the selected row gets `accent.copy(alpha = 0.14f)` fill, bold text, and the `CHECK` icon. Never use a plain Material `ExposedDropdownMenuBox` in new work.

### Microcopy voice

- System / section labels: `> SNAKE_CASE`, optionally with a `// SUBTITLE` — e.g. `> HARDWARE_OFFLOAD // EXPERIMENTAL`, `> PURR_TTS // ENGINE`
- Buttons and actions: bracket labels — `[ CONFIRM_UPLINK ]`, `[+ SAVE CUSTOM]`, `[CLOSE]`, `[ START RELAY ]`
- ALL-CAPS, monospace, letter-spaced. M.I.K.A.'s purrs and sass live in toasts, tutorials, and release notes — **never in code**. The chaos is the commentary; the code is the craft.
- Don't rewrite terminal copy into friendly SaaS sentences — the machine speaks like a machine.

### Iconography

ALL icons are **Canvas-drawn vectors**: `MikaCyberIcon(type = CyberIconType.X, tint = accent, size = 14.dp)` — `PLAY`, `STOP`, `DOWNLOAD`, `SLAM_IN`, `EJECT`, `MIC_WAVE`, `DNA_CLONE`, `BOLT_ACCEL`, `CHAT_SLANG`, `SHIELD_GUARD`, `BOOK_NARRATOR`, `SETTINGS_COG`… They take their color from the surrounding lane via `tint` and never hardcode hex. A new glyph = a new `when` entry in `MikaComponents.kt` (stroke width ≈ `minOf(w, h) * 0.13f`, round caps/joins). No emoji, no icon fonts, no `ImageVector` packs.

### Motion (performance law)

- **Page phasing** (`AnimatedContent` between tabs): in = `fadeIn(240ms) + scaleIn(0.965f, 240ms) + slideInHorizontally(dir * width/16)`; out = `fadeOut(150ms) + scaleOut(1.015f) + slideOutHorizontally(-dir * width/24)`. Direction follows the tab-index delta.
- **Pulses**: `rememberInfiniteTransition` + `tween(900, LinearEasing)` + `RepeatMode.Reverse` — animate **color and alpha only**.
- **Scan band**: 12s linear restart, alpha ≤ 0.05.
- Badges/pulses never animate **size** in pinned layouts — that reintroduces the v1.3.3 page-shift bug.

## Example: generic vs. PURR-TTS

```kotlin
// ❌ REJECTED — stock Material button
Button(onClick = onClick) { Text("Continue") }

// ❌ REJECTED — invented colors, SaaS pill card
Card(
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = Color(0xFF3A2E55))
) { Text("Continue", style = MaterialTheme.typography.bodyLarge) }

// ✅ PURR-TTS — terminal dialect action chip
Box(
    modifier = Modifier
        .clip(RoundedCornerShape(4.dp))
        .background(MikaColors.NeonCyan.copy(alpha = 0.14f))
        .border(1.dp, MikaColors.NeonCyan.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
        .clickable(onClick = onClick)
        .padding(horizontal = 8.dp, vertical = 4.dp)
) {
    Text(
        text = "[ CONFIRM_UPLINK ]",
        color = MikaColors.NeonCyan,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 9.sp
    )
}
```

## Pre-PR checklist

- [ ] `python3 scripts/verify_project.py` passes · `./gradlew testDebugUnitTest` green
- [ ] Every color is a `MikaColors` token or an alpha variant of one — zero hardcoded hex
- [ ] Terminal chrome: 4dp radii · hairline border · glow · monospace uppercase label
- [ ] No emoji icons — `MikaCyberIcon` only (new glyphs get a Canvas entry)
- [ ] Dynamic text in pinned regions has a fixed-width slot (`maxLines = 1` + overflow)
- [ ] Depth via surface ladder + border + glow — never gray-on-gray · disabled = alpha
- [ ] Motion follows the phasing/pulse recipes — no size animation in pinned layouts
- [ ] Microcopy keeps the terminal voice (`> LABELS`, `[ BRACKETS ]`)
- [ ] Canonical screens untouched — or an issue discussed the redesign first
- [ ] New systems carry a `// ✨ MIKA'S <NAME> ✨` banner explaining the why

If a reviewer asks "would this look at home in a stock Android settings app?" and the honest answer is yes — expect the PR back with comments. This engine looks the way it does because someone loves it; keep it that way. 🐾
