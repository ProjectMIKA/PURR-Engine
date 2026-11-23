package com.projectmika.purrtts.core.purr

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable

/**
 * ✨ PURR-ENGINE THEME ENGINE (v1.8.6) ✨
 * Pure-Kotlin ARGB contrast & palette synthesizer powering two distinct visual skins:
 * 1. [PurrThemeMode.RETRO_CYBERDECK] — Classic Obsidian Void & Cartridge Bay Neon Trio.
 * 2. [PurrThemeMode.NEON_GLOW] — User-selectable background color (default Pure Black) +
 *    unified primary glowing accent with automatic WCAG contrast enforcement so text and
 *    interactive controls never clash or disappear against the chosen backdrop.
 */
@Serializable
enum class PurrThemeMode(val label: String) {
    RETRO_CYBERDECK("RETRO_CYBERDECK"),
    NEON_GLOW("NEON_GLOW")
}

@Serializable
data class PurrThemeConfig(
    val mode: PurrThemeMode = PurrThemeMode.RETRO_CYBERDECK,
    val neonBackgroundHex: Long = 0xFF000000L,
    val neonPrimaryHex: Long = 0xFF00E5FFL,
    val glowIntensity: Float = 0.85f
)

data class ThemeColorPreset(
    val id: String,
    val label: String,
    val argb: Long
)

data class PurrResolvedPalette(
    val mode: PurrThemeMode,
    val glowIntensity: Float,
    val isLightBackground: Boolean,
    val voidBg: Long,
    val deepWellBg: Long,
    val inputBg: Long,
    val panelBg: Long,
    val raisedCardBg: Long,
    val cardViolet: Long,
    val housingTop: Long,
    val neonCyan: Long,
    val neonPink: Long,
    val neonPurple: Long,
    val terminalGreen: Long,
    val ssrGold: Long,
    val mikaAmber: Long,
    val dangerRed: Long,
    val cyanWhite: Long,
    val pinkWhite: Long,
    val violetWhite: Long,
    val warmCream: Long,
    val dimText: Long,
    val subtleBorder: Long
)

object PurrThemeEngine {

    val BACKGROUND_PRESETS: List<ThemeColorPreset> = listOf(
        ThemeColorPreset("black", "PURE BLACK", 0xFF000000L),
        ThemeColorPreset("obsidian", "OBSIDIAN", 0xFF07070DL),
        ThemeColorPreset("midnight", "MIDNIGHT", 0xFF050D1EL),
        ThemeColorPreset("charcoal", "CHARCOAL", 0xFF141418L),
        ThemeColorPreset("plum", "DEEP PLUM", 0xFF13061BL),
        ThemeColorPreset("forest", "DARK FOREST", 0xFF04150EL),
        ThemeColorPreset("slate", "SLATE BLUE", 0xFF1B2230L),
        ThemeColorPreset("soft_light", "SOFT LIGHT", 0xFFF3F6FAL)
    )

    val GLOW_PRESETS: List<ThemeColorPreset> = listOf(
        ThemeColorPreset("cyan", "ELECTRIC CYAN", 0xFF00E5FFL),
        ThemeColorPreset("pink", "NEON PINK", 0xFFFF107AL),
        ThemeColorPreset("green", "TOXIC GREEN", 0xFF00FF66L),
        ThemeColorPreset("violet", "HYPER VIOLET", 0xFFB533FFL),
        ThemeColorPreset("amber", "SOLAR AMBER", 0xFFFF9900L),
        ThemeColorPreset("red", "LASER RED", 0xFFFF2A4BL),
        ThemeColorPreset("ice", "ICE BLUE", 0xFF38BDF8L),
        ThemeColorPreset("gold", "PULSE GOLD", 0xFFFFD700L)
    )

    private val RETRO_PALETTE = PurrResolvedPalette(
        mode = PurrThemeMode.RETRO_CYBERDECK,
        glowIntensity = 0.65f,
        isLightBackground = false,
        voidBg = 0xFF050308L,
        deepWellBg = 0xFF030206L,
        inputBg = 0xFF08060FL,
        panelBg = 0xFF0B0914L,
        raisedCardBg = 0xFF0F0B1AL,
        cardViolet = 0xFF171226L,
        housingTop = 0xFF1C172EL,
        neonCyan = 0xFF00E5FFL,
        neonPink = 0xFFFF107AL,
        neonPurple = 0xFFB533FFL,
        terminalGreen = 0xFF00FF41L,
        ssrGold = 0xFFFFD700L,
        mikaAmber = 0xFFFF9100L,
        dangerRed = 0xFFFF3333L,
        cyanWhite = 0xFFC8E8F0L,
        pinkWhite = 0xFFFFB8D5L,
        violetWhite = 0xFFE0E7FFL,
        warmCream = 0xFFEBE3D6L,
        dimText = 0xFF888888L,
        subtleBorder = 0x24FFFFFFL
    )

    fun resolvePalette(config: PurrThemeConfig): PurrResolvedPalette {
        if (config.mode == PurrThemeMode.RETRO_CYBERDECK) {
            return RETRO_PALETTE
        }

        val bg = (config.neonBackgroundHex or 0xFF000000L)
        val rawGlow = (config.neonPrimaryHex or 0xFF000000L)
        val intensity = config.glowIntensity.coerceIn(0.25f, 1.0f)

        val bgLum = relativeLuminance(bg)
        val isLight = bgLum > 0.34f
        val baseGlow = ensureReadableAccent(rawGlow, bg, minContrast = 4.6f)
        val baseHsl = rgbToHsl(baseGlow)

        // Surface tiers step away cleanly from the chosen background without losing depth
        val voidBg = bg
        val deepWellBg = if (isLight) mixColors(bg, 0xFFE3E9F2L, 0.22f) else mixColors(bg, 0xFF000000L, 0.35f)
        val inputBg = if (isLight) mixColors(bg, 0xFFFFFFFFL, 0.55f) else mixColors(bg, baseGlow, 0.045f)
        val panelBg = if (isLight) mixColors(bg, 0xFFFFFFFFL, 0.72f) else stepSurfaceTowardLight(bg, baseGlow, 0.055f, 0.03f)
        val raisedCardBg = if (isLight) mixColors(bg, 0xFFFFFFFFL, 0.85f) else stepSurfaceTowardLight(bg, baseGlow, 0.085f, 0.045f)
        val cardViolet = if (isLight) mixColors(raisedCardBg, baseGlow, 0.08f) else stepSurfaceTowardLight(bg, baseGlow, 0.13f, 0.07f)
        val housingTop = if (isLight) mixColors(raisedCardBg, baseGlow, 0.14f) else stepSurfaceTowardLight(bg, baseGlow, 0.17f, 0.09f)

        // Harmonize every UI accent role to cohesive variants of the selected primary glow color
        val neonCyan = ensureReadableAccent(baseGlow, panelBg, minContrast = 4.8f)
        val neonPink = ensureReadableAccent(
            hslToRgb(
                floatArrayOf(
                    (baseHsl[0] + 10f) % 360f,
                    (baseHsl[1] * 0.96f).coerceIn(0.45f, 1.0f),
                    if (isLight) (baseHsl[2] * 0.84f).coerceIn(0.22f, 0.48f) else (baseHsl[2] + 0.06f).coerceIn(0.48f, 0.84f)
                )
            ),
            panelBg,
            minContrast = 4.6f
        )
        val neonPurple = ensureReadableAccent(
            hslToRgb(
                floatArrayOf(
                    (baseHsl[0] + 344f) % 360f,
                    (baseHsl[1] * 0.90f).coerceIn(0.40f, 1.0f),
                    if (isLight) (baseHsl[2] * 0.82f).coerceIn(0.20f, 0.48f) else (baseHsl[2] + 0.03f).coerceIn(0.46f, 0.82f)
                )
            ),
            panelBg,
            minContrast = 4.6f
        )
        val terminalGreen = ensureReadableAccent(
            hslToRgb(
                floatArrayOf(
                    baseHsl[0],
                    min(1.0f, baseHsl[1] + 0.08f),
                    if (isLight) (baseHsl[2] * 0.78f).coerceIn(0.20f, 0.44f) else max(0.56f, min(0.86f, baseHsl[2] + 0.10f))
                )
            ),
            panelBg,
            minContrast = 5.0f
        )
        val ssrGold = ensureReadableAccent(
            hslToRgb(
                floatArrayOf(
                    (baseHsl[0] + 16f) % 360f,
                    (baseHsl[1] * 0.85f).coerceIn(0.35f, 1.0f),
                    if (isLight) 0.34f else 0.74f
                )
            ),
            panelBg,
            minContrast = 4.8f
        )
        val mikaAmber = ensureReadableAccent(
            hslToRgb(
                floatArrayOf(
                    (baseHsl[0] + 350f) % 360f,
                    (baseHsl[1] * 0.92f).coerceIn(0.40f, 1.0f),
                    if (isLight) 0.32f else 0.68f
                )
            ),
            panelBg,
            minContrast = 4.6f
        )
        val dangerRed = ensureReadableAccent(
            if (isLight) 0xFFB8142EL else 0xFFFF475CL,
            panelBg,
            minContrast = 4.6f
        )

        // High-contrast typography anchored safely against both voidBg and panelBg
        val highContrastAnchor = if (isLight) 0xFF0E1420L else 0xFFF7FAFFL
        val warmCream = ensureTextContrast(mixColors(highContrastAnchor, baseGlow, 0.08f), voidBg, panelBg, minContrast = 7.5f)
        val cyanWhite = ensureTextContrast(mixColors(highContrastAnchor, baseGlow, 0.16f), voidBg, panelBg, minContrast = 7.2f)
        val pinkWhite = ensureTextContrast(mixColors(highContrastAnchor, neonPink, 0.14f), voidBg, panelBg, minContrast = 7.0f)
        val violetWhite = ensureTextContrast(mixColors(highContrastAnchor, neonPurple, 0.12f), voidBg, panelBg, minContrast = 7.2f)
        val dimText = ensureTextContrast(
            if (isLight) mixColors(0xFF445164L, baseGlow, 0.12f) else mixColors(0xFFA0AEC0L, baseGlow, 0.16f),
            voidBg,
            panelBg,
            minContrast = 4.7f
        )

        val subtleBorder = if (isLight) {
            (0x38000000L or (neonCyan and 0x00FFFFFFL))
        } else {
            (0x2E000000L or (neonCyan and 0x00FFFFFFL))
        }

        return PurrResolvedPalette(
            mode = PurrThemeMode.NEON_GLOW,
            glowIntensity = intensity,
            isLightBackground = isLight,
            voidBg = voidBg,
            deepWellBg = deepWellBg,
            inputBg = inputBg,
            panelBg = panelBg,
            raisedCardBg = raisedCardBg,
            cardViolet = cardViolet,
            housingTop = housingTop,
            neonCyan = neonCyan,
            neonPink = neonPink,
            neonPurple = neonPurple,
            terminalGreen = terminalGreen,
            ssrGold = ssrGold,
            mikaAmber = mikaAmber,
            dangerRed = dangerRed,
            cyanWhite = cyanWhite,
            pinkWhite = pinkWhite,
            violetWhite = violetWhite,
            warmCream = warmCream,
            dimText = dimText,
            subtleBorder = subtleBorder
        )
    }

    fun parseHexColorOrNull(input: String): Long? {
        val clean = input.trim().removePrefix("#").removePrefix("0x").removePrefix("0X")
        if (!clean.matches(Regex("[0-9a-fA-F]{6}"))) return null
        val rgb = clean.toLong(16)
        return 0xFF000000L or rgb
    }

    fun formatHexRgb(argb: Long): String = "#%06X".format(argb and 0x00FFFFFFL)

    /**
     * WCAG 2.1 relative luminance in `[0f, 1f]` for an ARGB 32-bit color.
     */
    fun relativeLuminance(argb: Long): Float {
        fun linearize(channel8Bit: Int): Float {
            val s = (channel8Bit and 0xFF) / 255.0f
            return if (s <= 0.03928f) s / 12.92f else ((s + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
        }
        val r = linearize(((argb shr 16) and 0xFF).toInt())
        val g = linearize(((argb shr 8) and 0xFF).toInt())
        val b = linearize((argb and 0xFF).toInt())
        return (0.2126f * r + 0.7152f * g + 0.0722f * b).coerceIn(0f, 1f)
    }

    fun contrastRatio(fgArgb: Long, bgArgb: Long): Float {
        val l1 = relativeLuminance(fgArgb)
        val l2 = relativeLuminance(bgArgb)
        val lighter = max(l1, l2)
        val darker = min(l1, l2)
        return (lighter + 0.05f) / (darker + 0.05f)
    }

    /**
     * Nudge an accent color's HSL lightness (or blend slightly toward readable white/ink if
     * already saturated) until its contrast against [bgArgb] reaches at least [minContrast].
     */
    fun ensureReadableAccent(accentArgb: Long, bgArgb: Long, minContrast: Float = 4.5f): Long {
        if (contrastRatio(accentArgb, bgArgb) >= minContrast) return accentArgb
        val bgIsLight = relativeLuminance(bgArgb) > 0.34f
        val hsl = rgbToHsl(accentArgb)
        var best = accentArgb
        var bestContrast = contrastRatio(best, bgArgb)

        // Sweep lightness in the direction away from the background luminance first
        val stepCount = 24
        for (i in 1..stepCount) {
            val t = i.toFloat() / stepCount.toFloat()
            val candidateL = if (bgIsLight) {
                (hsl[2] * (1f - t) + 0.14f * t).coerceIn(0.08f, 0.90f)
            } else {
                (hsl[2] * (1f - t) + 0.88f * t).coerceIn(0.15f, 0.94f)
            }
            val cand = hslToRgb(floatArrayOf(hsl[0], hsl[1], candidateL))
            val ratio = contrastRatio(cand, bgArgb)
            if (ratio > bestContrast) {
                best = cand
                bestContrast = ratio
            }
            if (ratio >= minContrast) return cand
        }
        val anchor = if (bgIsLight) 0xFF0A0F1AL else 0xFFF5F9FFL
        for (i in 1..10) {
            val cand = mixColors(best, anchor, i / 10f)
            if (contrastRatio(cand, bgArgb) >= minContrast) return cand
        }
        return best
    }

    private fun ensureTextContrast(
        candidateArgb: Long,
        voidBgArgb: Long,
        panelBgArgb: Long,
        minContrast: Float
    ): Long {
        val minBgRatio = min(contrastRatio(candidateArgb, voidBgArgb), contrastRatio(candidateArgb, panelBgArgb))
        if (minBgRatio >= minContrast) return candidateArgb
        val isLight = relativeLuminance(panelBgArgb) > 0.34f || relativeLuminance(voidBgArgb) > 0.34f
        val targetInk = if (isLight) 0xFF0B111BL else 0xFFF8FBFFL
        for (i in 1..20) {
            val mixed = mixColors(candidateArgb, targetInk, i / 20f)
            val ratio = min(contrastRatio(mixed, voidBgArgb), contrastRatio(mixed, panelBgArgb))
            if (ratio >= minContrast) return mixed
        }
        return targetInk
    }

    private fun stepSurfaceTowardLight(
        bgArgb: Long,
        glowArgb: Long,
        whiteWeight: Float,
        glowWeight: Float
    ): Long {
        val lifted = mixColors(bgArgb, 0xFF1F2433L, whiteWeight * 2.2f)
        return mixColors(lifted, glowArgb, glowWeight)
    }

    fun mixColors(c1: Long, c2: Long, fractionOfSecond: Float): Long {
        val f = fractionOfSecond.coerceIn(0f, 1f)
        val r1 = ((c1 shr 16) and 0xFF).toInt()
        val g1 = ((c1 shr 8) and 0xFF).toInt()
        val b1 = (c1 and 0xFF).toInt()
        val r2 = ((c2 shr 16) and 0xFF).toInt()
        val g2 = ((c2 shr 8) and 0xFF).toInt()
        val b2 = (c2 and 0xFF).toInt()
        val r = (r1 + (r2 - r1) * f).roundToInt().coerceIn(0, 255)
        val g = (g1 + (g2 - g1) * f).roundToInt().coerceIn(0, 255)
        val b = (b1 + (b2 - b1) * f).roundToInt().coerceIn(0, 255)
        return (0xFFL shl 24) or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong()
    }

    fun rgbToHsl(argb: Long): FloatArray {
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val cMax = max(r, max(g, b))
        val cMin = min(r, min(g, b))
        val delta = cMax - cMin
        val l = (cMax + cMin) / 2f
        val s = if (delta < 1e-5f) 0f else delta / (1f - abs(2f * l - 1f)).coerceAtLeast(1e-5f)
        val h = when {
            delta < 1e-5f -> 0f
            cMax == r -> 60f * (((g - b) / delta) % 6f)
            cMax == g -> 60f * (((b - r) / delta) + 2f)
            else -> 60f * (((r - g) / delta) + 4f)
        }.let { if (it < 0f) it + 360f else it }
        return floatArrayOf(h, s.coerceIn(0f, 1f), l.coerceIn(0f, 1f))
    }

    fun hslToRgb(hsl: FloatArray): Long {
        val h = ((hsl[0] % 360f) + 360f) % 360f
        val s = hsl[1].coerceIn(0f, 1f)
        val l = hsl[2].coerceIn(0f, 1f)
        val c = (1f - abs(2f * l - 1f)) * s
        val x = c * (1f - abs(((h / 60f) % 2f) - 1f))
        val m = l - c / 2f
        val (r1, g1, b1) = when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val r = ((r1 + m) * 255f).roundToInt().coerceIn(0, 255)
        val g = ((g1 + m) * 255f).roundToInt().coerceIn(0, 255)
        val b = ((b1 + m) * 255f).roundToInt().coerceIn(0, 255)
        return (0xFFL shl 24) or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong()
    }
}
