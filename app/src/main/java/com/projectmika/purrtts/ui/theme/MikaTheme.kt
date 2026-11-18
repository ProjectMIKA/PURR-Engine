package com.projectmika.purrtts.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * ✨ MIKA VISUAL LANGUAGE — OBSIDIAN TERMINAL & NEON HOLOGRAPHY TOKENS ✨
 * Exact color tokens & monospace typography system ported from GachaSwipe
 * (src/index.css + .agents/skills/mika-visual-language/SKILL.md).
 */
object MikaColors {
    val VoidBg = Color(0xFF050308)
    val DeepWellBg = Color(0xFF030206)
    val InputBg = Color(0xFF08060F)
    val PanelBg = Color(0xFF0B0914)
    val RaisedCardBg = Color(0xFF0F0B1A)
    val CardViolet = Color(0xFF171226)
    val HousingTop = Color(0xFF1C172E)

    // The Neon Trio + Tier Accents
    val NeonCyan = Color(0xFF00E5FF)       // SYSTEM
    val NeonPink = Color(0xFFFF107A)       // LOVE / PRIMARY ACCENT
    val NeonPurple = Color(0xFFB533FF)     // RARE / CHAOS / CLONE
    val TerminalGreen = Color(0xFF00FF41)  // ROM-04 PURR / VU GREEN
    val SsrGold = Color(0xFFFFD700)        // FAVORITE / VU YELLOW
    val MikaAmber = Color(0xFFFF9100)      // MIKA VOICE / ALERT
    val DangerRed = Color(0xFFFF3333)      // BLACKLIST / ERROR

    // Tinted White Families
    val CyanWhite = Color(0xFFC8E8F0)
    val PinkWhite = Color(0xFFFFB8D5)
    val VioletWhite = Color(0xFFE0E7FF)
    val WarmCream = Color(0xFFEBE3D6)
    val DimText = Color(0xFF888888)
}

private val MikaDarkScheme = darkColorScheme(
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
    outline = MikaColors.NeonCyan.copy(alpha = 0.25f)
)

private val MikaMonoTypography = Typography(
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

@Composable
fun MikaPurrTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MikaDarkScheme,
        typography = MikaMonoTypography,
        content = content
    )
}
