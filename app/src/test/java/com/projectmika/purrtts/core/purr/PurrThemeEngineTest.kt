package com.projectmika.purrtts.core.purr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * ✨ PURE-JVM TESTS FOR TOOL-07 NEON THEME ENGINE & WCAG CONTRAST ENFORCER ✨
 */
class PurrThemeEngineTest {

    @Test
    fun `retro cyberdeck mode preserves classic palette tokens`() {
        val palette = PurrThemeEngine.resolvePalette(PurrThemeConfig(mode = PurrThemeMode.RETRO_CYBERDECK))
        assertEquals(PurrThemeMode.RETRO_CYBERDECK, palette.mode)
        assertFalse(palette.isLightBackground)
        assertEquals(0xFF050308L, palette.voidBg)
        assertEquals(0xFF00E5FFL, palette.neonCyan)
        assertEquals(0xFFFF107AL, palette.neonPink)
        assertEquals(0xFF00FF41L, palette.terminalGreen)
        assertEquals(0xFFFFD700L, palette.ssrGold)
    }

    @Test
    fun `all background and primary glow preset combinations meet WCAG contrast targets`() {
        for (bg in PurrThemeEngine.BACKGROUND_PRESETS) {
            for (glow in PurrThemeEngine.GLOW_PRESETS) {
                val palette = PurrThemeEngine.resolvePalette(
                    PurrThemeConfig(
                        mode = PurrThemeMode.NEON_GLOW,
                        neonBackgroundHex = bg.argb,
                        neonPrimaryHex = glow.argb,
                        glowIntensity = 0.85f
                    )
                )
                assertEquals("${bg.id}/${glow.id} light flag", PurrThemeEngine.relativeLuminance(bg.argb) > 0.34f, palette.isLightBackground)

                val warmOnVoid = PurrThemeEngine.contrastRatio(palette.warmCream, palette.voidBg)
                val warmOnPanel = PurrThemeEngine.contrastRatio(palette.warmCream, palette.panelBg)
                val cyanTextOnVoid = PurrThemeEngine.contrastRatio(palette.cyanWhite, palette.voidBg)
                val dimOnPanel = PurrThemeEngine.contrastRatio(palette.dimText, palette.panelBg)
                val primaryOnPanel = PurrThemeEngine.contrastRatio(palette.neonCyan, palette.panelBg)
                val primaryOnVoid = PurrThemeEngine.contrastRatio(palette.neonCyan, palette.voidBg)
                val dangerOnPanel = PurrThemeEngine.contrastRatio(palette.dangerRed, palette.panelBg)

                assertTrue("${bg.id}/${glow.id} warmOnVoid=$warmOnVoid", warmOnVoid >= 7.0f)
                assertTrue("${bg.id}/${glow.id} warmOnPanel=$warmOnPanel", warmOnPanel >= 7.0f)
                assertTrue("${bg.id}/${glow.id} cyanTextOnVoid=$cyanTextOnVoid", cyanTextOnVoid >= 7.0f)
                assertTrue("${bg.id}/${glow.id} dimOnPanel=$dimOnPanel", dimOnPanel >= 4.5f)
                assertTrue("${bg.id}/${glow.id} primaryOnPanel=$primaryOnPanel", primaryOnPanel >= 4.5f)
                assertTrue("${bg.id}/${glow.id} primaryOnVoid=$primaryOnVoid", primaryOnVoid >= 4.5f)
                assertTrue("${bg.id}/${glow.id} dangerOnPanel=$dangerOnPanel", dangerOnPanel >= 4.5f)
            }
        }
    }

    @Test
    fun `selecting cyan neon glow harmonizes secondary accent tokens around cyan hue`() {
        val cyan = 0xFF00E5FFL
        val cyanHue = PurrThemeEngine.rgbToHsl(cyan)[0]
        val palette = PurrThemeEngine.resolvePalette(
            PurrThemeConfig(
                mode = PurrThemeMode.NEON_GLOW,
                neonBackgroundHex = 0xFF000000L,
                neonPrimaryHex = cyan,
                glowIntensity = 0.90f
            )
        )
        fun angularDist(a: Float, b: Float): Float {
            val diff = abs(a - b) % 360f
            return minOf(diff, 360f - diff)
        }
        assertTrue(angularDist(PurrThemeEngine.rgbToHsl(palette.neonCyan)[0], cyanHue) < 8f)
        assertTrue(angularDist(PurrThemeEngine.rgbToHsl(palette.neonPink)[0], cyanHue) <= 22f)
        assertTrue(angularDist(PurrThemeEngine.rgbToHsl(palette.neonPurple)[0], cyanHue) <= 24f)
        assertTrue(angularDist(PurrThemeEngine.rgbToHsl(palette.terminalGreen)[0], cyanHue) < 8f)
        // Destructive alerts stay clearly distinct in the red warning band
        val dangerHue = PurrThemeEngine.rgbToHsl(palette.dangerRed)[0]
        assertTrue("Expected dangerRed near red hue, got $dangerHue", dangerHue >= 330f || dangerHue <= 20f)
    }

    @Test
    fun `parseHexColorOrNull and formatHexRgb handle valid and malformed inputs`() {
        assertEquals(0xFF00E5FFL, PurrThemeEngine.parseHexColorOrNull("#00e5ff"))
        assertEquals(0xFF112233L, PurrThemeEngine.parseHexColorOrNull(" 0x112233 "))
        assertEquals(0xFFABCDEFL, PurrThemeEngine.parseHexColorOrNull("abcdef"))
        assertNull(PurrThemeEngine.parseHexColorOrNull("#12345"))
        assertNull(PurrThemeEngine.parseHexColorOrNull("nothex"))
        assertEquals("#00E5FF", PurrThemeEngine.formatHexRgb(0xFF00E5FFL))
        assertEquals("#000000", PurrThemeEngine.formatHexRgb(0xFF000000L))
    }
}
