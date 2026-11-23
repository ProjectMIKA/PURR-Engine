package com.projectmika.purrtts.ui.forge

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.projectmika.purrtts.core.purr.PurrForgeState
import com.projectmika.purrtts.core.purr.PurrThemeConfig
import com.projectmika.purrtts.core.purr.PurrThemeEngine
import com.projectmika.purrtts.core.purr.PurrThemeMode
import com.projectmika.purrtts.core.purr.ThemeColorPreset
import com.projectmika.purrtts.ui.theme.CyberIconType
import com.projectmika.purrtts.ui.theme.MikaColors
import com.projectmika.purrtts.ui.theme.MikaGlowPanel
import com.projectmika.purrtts.ui.theme.MikaTagPill
import kotlin.math.roundToInt

/**
 * TOOL-07: NEON THEME ENGINE OS Window.
 * Lets the user switch between classic RETRO CYBERDECK styling and monochrome-variant NEON GLOW,
 * customize the background & primary glowing color (presets or #RRGGBB hex), and inspect live
 * WCAG 2.1 text & accent readability guarantees.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ThemeEngineModuleWindowBody(
    forgeState: PurrForgeState,
    viewModel: TtsForgeViewModel
) {
    val cfg = forgeState.themeConfig
    val palette = remember(cfg) { PurrThemeEngine.resolvePalette(cfg) }
    val textContrast = remember(palette) {
        PurrThemeEngine.contrastRatio(palette.warmCream, palette.voidBg)
    }
    val accentContrast = remember(palette) {
        PurrThemeEngine.contrastRatio(palette.neonCyan, palette.panelBg)
    }
    val isNeon = cfg.mode == PurrThemeMode.NEON_GLOW

    var bgHexInput by remember(cfg.neonBackgroundHex) {
        mutableStateOf(PurrThemeEngine.formatHexRgb(cfg.neonBackgroundHex))
    }
    var glowHexInput by remember(cfg.neonPrimaryHex) {
        mutableStateOf(PurrThemeEngine.formatHexRgb(cfg.neonPrimaryHex))
    }
    var bgHexError by remember { mutableStateOf<String?>(null) }
    var glowHexError by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // 1. Mode Switcher
        MikaGlowPanel(accent = MikaColors.NeonCyan) {
            Text(
                text = "> DISPLAY_MODE_SELECTOR",
                color = MikaColors.NeonCyan,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Black,
                fontSize = 10.sp,
                letterSpacing = 0.8.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "// Classic RETRO preserves per-cartridge Cyberdeck colors. NEON GLOW unifies borders & accents around one glowing hue with auto WCAG text contrast.",
                color = MikaColors.DimText,
                fontFamily = FontFamily.Monospace,
                fontSize = 9.5.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ThemeModeTile(
                    title = "RETRO CYBERDECK",
                    subtitle = "Multi-accent ROM deck",
                    selected = !isNeon,
                    accent = MikaColors.NeonPink,
                    onClick = {
                        viewModel.updateThemeConfig { it.copy(mode = PurrThemeMode.RETRO_CYBERDECK) }
                    },
                    modifier = Modifier.weight(1f)
                )
                ThemeModeTile(
                    title = "NEON GLOW",
                    subtitle = "Custom unified neon",
                    selected = isNeon,
                    accent = MikaColors.NeonCyan,
                    onClick = {
                        viewModel.updateThemeConfig { it.copy(mode = PurrThemeMode.NEON_GLOW) }
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // 2. Live Contrast & Skin Preview
        val previewVoid = Color(palette.voidBg)
        val previewPanel = Color(palette.panelBg)
        val previewAccent = Color(palette.neonCyan)
        val previewText = Color(palette.warmCream)
        val previewDim = Color(palette.dimText)
        val txtRatioLabel = ((textContrast * 10f).roundToInt() / 10f).toString()
        val accRatioLabel = ((accentContrast * 10f).roundToInt() / 10f).toString()
        val wcagPass = textContrast >= 7.0f && accentContrast >= 4.5f

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = if (isNeon) (14f * cfg.glowIntensity).dp else 4.dp,
                    shape = RoundedCornerShape(10.dp),
                    spotColor = previewAccent,
                    ambientColor = previewAccent.copy(alpha = 0.45f)
                )
                .clip(RoundedCornerShape(10.dp))
                .background(previewVoid)
                .border(1.5.dp, previewAccent, RoundedCornerShape(10.dp))
                .padding(12.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isNeon) "LIVE_NEON_PREVIEW" else "RETRO_DEFAULT_PREVIEW",
                        color = previewAccent,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Black,
                        fontSize = 10.sp,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = if (wcagPass) "WCAG PASS ($txtRatioLabel:1)" else "CONTRAST $txtRatioLabel:1",
                        color = if (wcagPass) Color(palette.terminalGreen) else Color(palette.mikaAmber),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 8.5.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(previewPanel)
                            .border(1.dp, previewAccent.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(previewPanel)
                        .border(1.dp, previewAccent.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "PURR // ACTIVE_NEURAL_BUS",
                        color = previewText,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Black,
                        fontSize = 12.sp
                    )
                    Text(
                        text = "Readable foreground text & glowing accent borders auto-calibrated against your background.",
                        color = previewDim,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.5.sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "[ PRIMARY ACTION ]",
                            color = previewAccent,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 9.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(previewAccent.copy(alpha = 0.16f))
                                .border(1.dp, previewAccent, RoundedCornerShape(4.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                        Text(
                            text = "TXT $txtRatioLabel:1 • ACC $accRatioLabel:1",
                            color = previewDim,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 8.5.sp,
                            modifier = Modifier.align(Alignment.CenterVertically)
                        )
                    }
                }
            }
        }

        // 3. Background Color Picker (Default Pure Black)
        MikaGlowPanel(accent = MikaColors.NeonPurple) {
            Text(
                text = "> BACKGROUND_SURFACE (DEFAULT: PURE BLACK)",
                color = MikaColors.NeonPurple,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Black,
                fontSize = 10.sp,
                letterSpacing = 0.8.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            ColorPresetSwatchRow(
                presets = PurrThemeEngine.BACKGROUND_PRESETS,
                selectedArgb = cfg.neonBackgroundHex,
                borderAccent = MikaColors.NeonCyan,
                onSelect = { preset ->
                    bgHexError = null
                    bgHexInput = PurrThemeEngine.formatHexRgb(preset.argb)
                    viewModel.updateThemeConfig {
                        it.copy(
                            mode = PurrThemeMode.NEON_GLOW,
                            neonBackgroundHex = preset.argb
                        )
                    }
                }
            )
            Spacer(modifier = Modifier.height(10.dp))
            HexColorApplyRow(
                label = "CUSTOM_BG_HEX",
                value = bgHexInput,
                errorText = bgHexError,
                accent = MikaColors.NeonPurple,
                onValueChange = {
                    bgHexInput = it
                    bgHexError = null
                },
                onApply = {
                    val parsed = PurrThemeEngine.parseHexColorOrNull(bgHexInput)
                    if (parsed == null) {
                        bgHexError = "ERR: Use 6-digit hex like #000000"
                    } else {
                        bgHexError = null
                        bgHexInput = PurrThemeEngine.formatHexRgb(parsed)
                        viewModel.updateThemeConfig {
                            it.copy(
                                mode = PurrThemeMode.NEON_GLOW,
                                neonBackgroundHex = parsed
                            )
                        }
                    }
                }
            )
        }

        // 4. Primary Glow Color Picker (e.g. Cyan)
        MikaGlowPanel(accent = MikaColors.NeonCyan) {
            Text(
                text = "> PRIMARY_GLOW_COLOR (BORDERS & ACCENTS)",
                color = MikaColors.NeonCyan,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Black,
                fontSize = 10.sp,
                letterSpacing = 0.8.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            ColorPresetSwatchRow(
                presets = PurrThemeEngine.GLOW_PRESETS,
                selectedArgb = cfg.neonPrimaryHex,
                borderAccent = MikaColors.NeonCyan,
                onSelect = { preset ->
                    glowHexError = null
                    glowHexInput = PurrThemeEngine.formatHexRgb(preset.argb)
                    viewModel.updateThemeConfig {
                        it.copy(
                            mode = PurrThemeMode.NEON_GLOW,
                            neonPrimaryHex = preset.argb
                        )
                    }
                }
            )
            Spacer(modifier = Modifier.height(10.dp))
            HexColorApplyRow(
                label = "CUSTOM_GLOW_HEX",
                value = glowHexInput,
                errorText = glowHexError,
                accent = MikaColors.NeonCyan,
                onValueChange = {
                    glowHexInput = it
                    glowHexError = null
                },
                onApply = {
                    val parsed = PurrThemeEngine.parseHexColorOrNull(glowHexInput)
                    if (parsed == null) {
                        glowHexError = "ERR: Use 6-digit hex like #00E5FF"
                    } else {
                        glowHexError = null
                        glowHexInput = PurrThemeEngine.formatHexRgb(parsed)
                        viewModel.updateThemeConfig {
                            it.copy(
                                mode = PurrThemeMode.NEON_GLOW,
                                neonPrimaryHex = parsed
                            )
                        }
                    }
                }
            )
        }

        // 5. Glow Intensity & Reset
        MikaGlowPanel(accent = MikaColors.NeonPink) {
            Text(
                text = "> NEON_BLOOM_INTENSITY // ${(cfg.glowIntensity * 100f).roundToInt()}%",
                color = MikaColors.NeonPink,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Black,
                fontSize = 10.sp,
                letterSpacing = 0.8.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Slider(
                value = cfg.glowIntensity,
                onValueChange = { next ->
                    viewModel.updateThemeConfig {
                        it.copy(
                            mode = PurrThemeMode.NEON_GLOW,
                            glowIntensity = next.coerceIn(0.2f, 1.0f)
                        )
                    }
                },
                valueRange = 0.2f..1.0f,
                colors = SliderDefaults.colors(
                    thumbColor = MikaColors.NeonCyan,
                    activeTrackColor = MikaColors.NeonCyan,
                    inactiveTrackColor = MikaColors.SubtleBorder
                )
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isNeon) {
                        "ACTIVE: ${PurrThemeEngine.formatHexRgb(cfg.neonBackgroundHex)} + ${PurrThemeEngine.formatHexRgb(cfg.neonPrimaryHex)}"
                    } else {
                        "ACTIVE: CLASSIC RETRO CYBERDECK"
                    },
                    color = MikaColors.DimText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 8.5.sp
                )
                MikaTagPill(
                    label = "RESET RETRO",
                    selected = true,
                    activeColor = MikaColors.NeonPink,
                    leadingIcon = CyberIconType.REFRESH,
                    onClick = {
                        bgHexError = null
                        glowHexError = null
                        viewModel.updateThemeConfig { PurrThemeConfig() }
                    }
                )
            }
        }
    }
}

@Composable
private fun ThemeModeTile(
    title: String,
    subtitle: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) accent.copy(alpha = 0.16f) else MikaColors.DeepWellBg)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) accent else MikaColors.SubtleBorder,
                shape = RoundedCornerShape(6.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = title,
            color = if (selected) accent else MikaColors.PrimaryText,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Black,
            fontSize = 10.sp
        )
        Text(
            text = subtitle,
            color = MikaColors.DimText,
            fontFamily = FontFamily.Monospace,
            fontSize = 8.5.sp
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorPresetSwatchRow(
    presets: List<ThemeColorPreset>,
    selectedArgb: Long,
    borderAccent: Color,
    onSelect: (ThemeColorPreset) -> Unit
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        presets.forEach { preset ->
            val selected = (selectedArgb and 0xFFFFFFL) == (preset.argb and 0xFFFFFFL)
            val swatch = Color(preset.argb)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (selected) borderAccent.copy(alpha = 0.16f) else MikaColors.DeepWellBg)
                    .border(
                        width = if (selected) 1.5.dp else 1.dp,
                        color = if (selected) borderAccent else MikaColors.SubtleBorder,
                        shape = RoundedCornerShape(6.dp)
                    )
                    .clickable { onSelect(preset) }
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(swatch)
                        .border(1.dp, MikaColors.PrimaryText.copy(alpha = 0.55f), CircleShape)
                )
                Text(
                    text = preset.label.uppercase(),
                    color = if (selected) borderAccent else MikaColors.PrimaryText,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 8.5.sp
                )
            }
        }
    }
}

@Composable
private fun HexColorApplyRow(
    label: String,
    value: String,
    errorText: String?,
    accent: Color,
    onValueChange: (String) -> Unit,
    onApply: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text(label, fontFamily = FontFamily.Monospace, fontSize = 8.5.sp) },
                singleLine = true,
                textStyle = TextStyle(
                    color = MikaColors.PrimaryText,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = accent,
                    unfocusedBorderColor = accent.copy(alpha = 0.35f),
                    focusedContainerColor = MikaColors.DeepWellBg,
                    unfocusedContainerColor = MikaColors.DeepWellBg
                ),
                modifier = Modifier.weight(1f)
            )
            MikaTagPill(
                label = "APPLY",
                selected = true,
                activeColor = accent,
                leadingIcon = CyberIconType.CHECK,
                onClick = onApply
            )
        }
        if (errorText != null) {
            Text(
                text = errorText,
                color = MikaColors.DangerRed,
                fontFamily = FontFamily.Monospace,
                fontSize = 8.5.sp
            )
        }
    }
}
