package com.projectmika.purrtts.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.projectmika.purrtts.core.purr.PurrThemeConfig
import com.projectmika.purrtts.core.purr.PurrThemeEngine
import com.projectmika.purrtts.core.purr.PurrThemeMode

/**
 * ✨ MIKA VISUAL LANGUAGE — REACTIVE OBSIDIAN RETRO & NEON GLOW ENGINE (v1.8.6) ✨
 * All theme tokens are backed by Compose snapshot state so toggling between Classic Retro
 * Cyberdeck and Neon Glow (with any background/glow pair) re-themes the entire app live.
 */
object MikaColors {
    var VoidBg by mutableStateOf(Color(0xFF050308))
        private set
    var DeepWellBg by mutableStateOf(Color(0xFF030206))
        private set
    var InputBg by mutableStateOf(Color(0xFF08060F))
        private set
    var PanelBg by mutableStateOf(Color(0xFF0B0914))
        private set
    var RaisedCardBg by mutableStateOf(Color(0xFF0F0B1A))
        private set
    var CardViolet by mutableStateOf(Color(0xFF171226))
        private set
    var HousingTop by mutableStateOf(Color(0xFF1C172E))
        private set

    // The Neon Trio + Tier Accents
    var NeonCyan by mutableStateOf(Color(0xFF00E5FF))       // SYSTEM
        private set
    var NeonPink by mutableStateOf(Color(0xFFFF107A))       // LOVE / PRIMARY ACCENT
        private set
    var NeonPurple by mutableStateOf(Color(0xFFB533FF))     // RARE / CHAOS / CLONE
        private set
    var TerminalGreen by mutableStateOf(Color(0xFF00FF41))  // ROM-04 PURR / VU GREEN
        private set
    var SsrGold by mutableStateOf(Color(0xFFFFD700))        // FAVORITE / VU YELLOW
        private set
    var MikaAmber by mutableStateOf(Color(0xFFFF9100))      // MIKA VOICE / ALERT
        private set
    var DangerRed by mutableStateOf(Color(0xFFFF3333))      // BLACKLIST / ERROR
        private set

    // Tinted Text & Contrast-Guarded Neutral Tokens
    var CyanWhite by mutableStateOf(Color(0xFFC8E8F0))
        private set
    var PinkWhite by mutableStateOf(Color(0xFFFFB8D5))
        private set
    var VioletWhite by mutableStateOf(Color(0xFFE0E7FF))
        private set
    var WarmCream by mutableStateOf(Color(0xFFEBE3D6))
        private set
    var DimText by mutableStateOf(Color(0xFF888888))
        private set
    var PrimaryText by mutableStateOf(Color.White)
        private set
    var SubtleBorder by mutableStateOf(Color(0x24FFFFFF))
        private set

    var IsNeonGlowMode by mutableStateOf(false)
        private set
    var IsLightBackground by mutableStateOf(false)
        private set
    var GlowIntensity by mutableFloatStateOf(0.65f)
        private set

    private var appliedConfig: PurrThemeConfig? = null

    fun applyTheme(config: PurrThemeConfig) {
        if (appliedConfig == config) return
        appliedConfig = config
        val p = PurrThemeEngine.resolvePalette(config)
        VoidBg = Color(p.voidBg)
        DeepWellBg = Color(p.deepWellBg)
        InputBg = Color(p.inputBg)
        PanelBg = Color(p.panelBg)
        RaisedCardBg = Color(p.raisedCardBg)
        CardViolet = Color(p.cardViolet)
        HousingTop = Color(p.housingTop)
        NeonCyan = Color(p.neonCyan)
        NeonPink = Color(p.neonPink)
        NeonPurple = Color(p.neonPurple)
        TerminalGreen = Color(p.terminalGreen)
        SsrGold = Color(p.ssrGold)
        MikaAmber = Color(p.mikaAmber)
        DangerRed = Color(p.dangerRed)
        CyanWhite = Color(p.cyanWhite)
        PinkWhite = Color(p.pinkWhite)
        VioletWhite = Color(p.violetWhite)
        WarmCream = Color(p.warmCream)
        DimText = Color(p.dimText)
        PrimaryText = Color(p.warmCream)
        SubtleBorder = Color(p.subtleBorder)
        IsNeonGlowMode = p.mode == PurrThemeMode.NEON_GLOW
        IsLightBackground = p.isLightBackground
        GlowIntensity = p.glowIntensity
    }

    /**
     * In RETRO_CYBERDECK, preserves cartridge-specific accents (`Color(spec.accentHex)`).
     * In NEON_GLOW, unifies custom decorative accents under the chosen primary glowing accent
     * while preserving destructive semantic red (`DangerRed`).
     */
    fun resolveAccent(rawAccent: Color): Color {
        if (!IsNeonGlowMode) return rawAccent
        if (rawAccent == DangerRed) return DangerRed
        return NeonCyan
    }
}

@Composable
fun MikaPurrTheme(
    themeConfig: PurrThemeConfig = PurrThemeConfig(),
    content: @Composable () -> Unit
) {
    MikaColors.applyTheme(themeConfig)

    val colorScheme = if (MikaColors.IsLightBackground) {
        lightColorScheme(
            primary = MikaColors.TerminalGreen,
            onPrimary = MikaColors.VoidBg,
            secondary = MikaColors.NeonCyan,
            onSecondary = MikaColors.VoidBg,
            tertiary = MikaColors.NeonPink,
            background = MikaColors.VoidBg,
            onBackground = MikaColors.WarmCream,
            surface = MikaColors.PanelBg,
            onSurface = MikaColors.CyanWhite,
            surfaceVariant = MikaColors.CardViolet,
            onSurfaceVariant = MikaColors.DimText,
            error = MikaColors.DangerRed,
            outline = MikaColors.NeonCyan.copy(alpha = 0.35f)
        )
    } else {
        darkColorScheme(
            primary = MikaColors.TerminalGreen,
            onPrimary = MikaColors.VoidBg,
            secondary = MikaColors.NeonCyan,
            onSecondary = MikaColors.VoidBg,
            tertiary = MikaColors.NeonPink,
            background = MikaColors.VoidBg,
            onBackground = MikaColors.WarmCream,
            surface = MikaColors.PanelBg,
            onSurface = MikaColors.CyanWhite,
            surfaceVariant = MikaColors.CardViolet,
            onSurfaceVariant = MikaColors.DimText,
            error = MikaColors.DangerRed,
            outline = MikaColors.NeonCyan.copy(alpha = 0.30f)
        )
    }

    val typography = Typography(
        headlineSmall = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 18.sp,
            letterSpacing = 1.6.sp,
            color = MikaColors.TerminalGreen
        ),
        titleMedium = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            letterSpacing = 0.9.sp,
            color = MikaColors.WarmCream
        ),
        bodyMedium = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Normal,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = MikaColors.CyanWhite
        ),
        labelMedium = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            letterSpacing = 0.8.sp,
            color = MikaColors.NeonCyan
        ),
        labelSmall = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 9.sp,
            letterSpacing = 1.1.sp,
            color = MikaColors.DimText
        )
    )

    MaterialTheme(
        colorScheme = colorScheme,
        typography = typography,
        content = content
    )
}
