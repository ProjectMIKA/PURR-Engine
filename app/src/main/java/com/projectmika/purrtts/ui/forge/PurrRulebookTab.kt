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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.projectmika.purrtts.core.purr.AcousticEffectPreset
import com.projectmika.purrtts.core.purr.ArchetypeTone
import com.projectmika.purrtts.core.purr.PurrChunk
import com.projectmika.purrtts.core.purr.PurrForgeState
import com.projectmika.purrtts.core.purr.PurrProfile
import com.projectmika.purrtts.core.purr.PurrTextProcessor
import com.projectmika.purrtts.core.purr.RuleCategory
import com.projectmika.purrtts.ui.theme.CyberDropdown
import com.projectmika.purrtts.ui.theme.ExpandableTerminalSection
import com.projectmika.purrtts.ui.theme.MikaColors
import com.projectmika.purrtts.ui.theme.MikaGlowPanel
import com.projectmika.purrtts.ui.theme.MikaTagPill
import kotlin.math.roundToInt

/**
 * ✨ TAB 2: STREAMLINED P.U.R.R. ENGINE ✨
 * Clean, easy-to-use surface layer:
 * 1) 6 One-Tap Vibe Presets (GachaSwipe Default, Audiobook, Tsundere, Yandere, Shy, Clean & Fast)
 * 2) 4 Simple Feature Toggles (Slang & Honorifics, MIKASTOP Breath Pauses, Emotion Acting, Audio DSP)
 * 3) Expandable Advanced Sections (MIKASTOP Timings, Emotion/DSP Sliders, Built-In & Custom Rulebook)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PurrRulebookTab(
    forgeState: PurrForgeState,
    activeProfile: PurrProfile,
    previewChunks: List<PurrChunk>,
    viewModel: TtsForgeViewModel,
    onOpenSaveDialog: () -> Unit
) {
    var expandPauses by remember { mutableStateOf(false) }
    var expandActingDsp by remember { mutableStateOf(false) }
    var expandRulebook by remember { mutableStateOf(false) }

    var rulePattern by remember { mutableStateOf("") }
    var ruleReplacement by remember { mutableStateOf("") }
    var ruleIsRegex by remember { mutableStateOf(false) }
    var ruleCategory by remember { mutableStateOf(RuleCategory.CUSTOM) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {

        // 1. Vibe Presets Picker
        MikaGlowPanel(accent = MikaColors.NeonPink) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "> PICK_YOUR_VIBE_PRESET",
                    color = MikaColors.NeonPink,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    fontSize = 11.sp
                )
                MikaTagPill(
                    label = "+ SAVE CUSTOM",
                    selected = true,
                    activeColor = MikaColors.NeonCyan,
                    onClick = onOpenSaveDialog
                )
            }
            Text(
                text = "// One tap configures slang, pauses, acting & audio polish for your style.",
                color = MikaColors.DimText,
                fontFamily = FontFamily.Monospace,
                fontSize = 9.5.sp,
                modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                forgeState.profiles.forEach { preset ->
                    val isActive = preset.id == activeProfile.id
                    val accent = if (preset.id == "gachaswipe_default") MikaColors.NeonCyan else MikaColors.NeonPink
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isActive) accent.copy(alpha = 0.14f) else MikaColors.DeepWellBg)
                            .border(
                                width = if (isActive) 1.5.dp else 1.dp,
                                color = if (isActive) accent else Color.White.copy(alpha = 0.14f),
                                shape = RoundedCornerShape(6.dp)
                            )
                            .clickable { viewModel.selectProfile(preset.id) }
                            .padding(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${if (isActive) "✓ " else ""}${preset.name}",
                                color = if (isActive) Color.White else MikaColors.WarmCream,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                            Text(
                                text = preset.badgeTag,
                                color = accent,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Black,
                                fontSize = 9.sp,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(accent.copy(alpha = 0.14f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = preset.summary,
                            color = MikaColors.CyanWhite.copy(alpha = 0.72f),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }

        // 2. Four Simple Surface Toggles
        MikaGlowPanel(accent = MikaColors.TerminalGreen) {
            Text(
                text = "> CORE_PURR_FEATURES",
                color = MikaColors.TerminalGreen,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Black,
                fontSize = 11.sp
            )
            Spacer(modifier = Modifier.height(8.dp))

            SimplePurrToggle(
                title = "💬 Slang, Honorifics & Stutters",
                subtitle = "Expands 'omg', 'fr', '-senpai', 'nya~' & 'w-what' cleanly.",
                checked = activeProfile.enableSlangAndHonorifics,
                accent = MikaColors.TerminalGreen,
                onToggle = { v -> viewModel.updateActiveProfile { it.copy(enableSlangAndHonorifics = v) } }
            )
            SimplePurrToggle(
                title = "⏸️ MIKASTOP Natural Breath Pauses",
                subtitle = "Splits '...', '—', and commas into clean silence gaps.",
                checked = activeProfile.enableMikaStopPauses,
                accent = MikaColors.MikaAmber,
                onToggle = { v -> viewModel.updateActiveProfile { it.copy(enableMikaStopPauses = v) } }
            )
            SimplePurrToggle(
                title = "🎭 Emotion & Archetype Acting",
                subtitle = "Adapts speech speed & pitch to sentence emotion.",
                checked = activeProfile.sentimentConfig.enabled,
                accent = MikaColors.NeonPink,
                onToggle = { v ->
                    viewModel.updateActiveProfile { it.copy(sentimentConfig = it.sentimentConfig.copy(enabled = v)) }
                }
            )
            SimplePurrToggle(
                title = "🎧 Studio Audio Polish (24kHz DSP)",
                subtitle = "Applies subtle warmth EQ, reverb & volume normalization.",
                checked = activeProfile.enableDsp,
                accent = MikaColors.NeonCyan,
                onToggle = { v -> viewModel.updateActiveProfile { it.copy(enableDsp = v) } }
            )
        }

        // 3. Expandable Advanced Section 1: MIKASTOP Pause Timings + Live Chunk Preview
        ExpandableTerminalSection(
            title = "ADVANCED: MIKASTOP PAUSE TIMING (MS)",
            subtitle = "Fine-tune pause durations & inspect live chunk output",
            expanded = expandPauses,
            onToggle = { expandPauses = !expandPauses },
            accent = MikaColors.MikaAmber
        ) {
            TerminalSlider(
                label = "ELLIPSIS (...)",
                valueText = "${activeProfile.mikaStopConfig.ellipsisPauseMs} ms",
                value = activeProfile.mikaStopConfig.ellipsisPauseMs.toFloat(),
                valueRange = 100f..1200f,
                accent = MikaColors.MikaAmber,
                onValueChange = { v -> viewModel.updateMikaStopConfig { it.copy(ellipsisPauseMs = v.roundToInt()) } }
            )
            TerminalSlider(
                label = "EM_DASH (— / ~)",
                valueText = "${activeProfile.mikaStopConfig.emDashPauseMs} ms",
                value = activeProfile.mikaStopConfig.emDashPauseMs.toFloat(),
                valueRange = 80f..900f,
                accent = MikaColors.MikaAmber,
                onValueChange = { v -> viewModel.updateMikaStopConfig { it.copy(emDashPauseMs = v.roundToInt()) } }
            )
            TerminalSlider(
                label = "SENTENCE (. ! ?)",
                valueText = "${activeProfile.mikaStopConfig.sentencePauseMs} ms",
                value = activeProfile.mikaStopConfig.sentencePauseMs.toFloat(),
                valueRange = 80f..900f,
                accent = MikaColors.MikaAmber,
                onValueChange = { v -> viewModel.updateMikaStopConfig { it.copy(sentencePauseMs = v.roundToInt()) } }
            )
            TerminalSlider(
                label = "COMMA_BREATH (, ; :)",
                valueText = "${activeProfile.mikaStopConfig.commaPauseMs} ms",
                value = activeProfile.mikaStopConfig.commaPauseMs.toFloat(),
                valueRange = 40f..450f,
                accent = MikaColors.MikaAmber,
                onValueChange = { v -> viewModel.updateMikaStopConfig { it.copy(commaPauseMs = v.roundToInt()) } }
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("SLICE_AT_COMMAS", color = MikaColors.CyanWhite, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                Switch(
                    checked = activeProfile.mikaStopConfig.sliceAtCommas,
                    onCheckedChange = { c -> viewModel.updateMikaStopConfig { it.copy(sliceAtCommas = c) } },
                    colors = SwitchDefaults.colors(checkedThumbColor = MikaColors.VoidBg, checkedTrackColor = MikaColors.MikaAmber)
                )
            }

            Text(
                text = "> LIVE_CHUNK_PREVIEW (${previewChunks.size} CHUNKS)",
                color = MikaColors.NeonCyan,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 9.sp
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(MikaColors.DeepWellBg)
                    .border(1.dp, MikaColors.NeonCyan.copy(alpha = 0.25f), RoundedCornerShape(6.dp))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                previewChunks.take(8).forEachIndexed { idx, chunk ->
                    when (chunk) {
                        is PurrChunk.Silence -> Text(
                            text = "#$idx [MIKASTOP] +${chunk.durationMs}ms (${chunk.reason})",
                            color = MikaColors.MikaAmber,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp
                        )
                        is PurrChunk.Speech -> Text(
                            text = "#$idx [${chunk.role.name}|r=${"%.2f".format(chunk.speakingRate)}] ${chunk.text}",
                            color = MikaColors.CyanWhite,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }

        // 4. Expandable Advanced Section 2: Emotion, Archetype Tone & 24kHz DSP Sliders
        ExpandableTerminalSection(
            title = "ADVANCED: ARCHETYPE ACTING & DSP RACK",
            subtitle = "Tone override, stutter/vowel intensity, warmth EQ & reverb",
            expanded = expandActingDsp,
            onToggle = { expandActingDsp = !expandActingDsp },
            accent = MikaColors.NeonPink
        ) {
            CyberDropdown(
                label = "> ARCHETYPE_TONE_OVERRIDE",
                options = ArchetypeTone.entries,
                selectedOption = activeProfile.sentimentConfig.toneOverride,
                optionLabel = { it.label },
                onSelect = { tone ->
                    viewModel.updateActiveProfile { p -> p.copy(sentimentConfig = p.sentimentConfig.copy(toneOverride = tone)) }
                },
                accent = MikaColors.NeonPink
            )
            CyberDropdown(
                label = "> ACOUSTIC_DSP_PRESET",
                options = AcousticEffectPreset.entries,
                selectedOption = activeProfile.dspConfig.effectPreset,
                optionLabel = { it.label },
                onSelect = { preset ->
                    viewModel.updateActiveProfile { p -> p.copy(dspConfig = p.dspConfig.copy(effectPreset = preset)) }
                },
                accent = MikaColors.NeonCyan
            )
            TerminalSlider(
                label = "STUTTER_DRAMA_INTENSITY",
                valueText = "${(activeProfile.sentimentConfig.stutterIntensity * 100).roundToInt()}%",
                value = activeProfile.sentimentConfig.stutterIntensity,
                valueRange = 0f..0.45f,
                accent = MikaColors.NeonPink,
                onValueChange = { v -> viewModel.updateActiveProfile { it.copy(sentimentConfig = it.sentimentConfig.copy(stutterIntensity = v)) } }
            )
            TerminalSlider(
                label = "VOWEL_STRETCH_INTENSITY",
                valueText = "${(activeProfile.sentimentConfig.vowelStretchIntensity * 100).roundToInt()}%",
                value = activeProfile.sentimentConfig.vowelStretchIntensity,
                valueRange = 0f..0.45f,
                accent = MikaColors.NeonPink,
                onValueChange = { v -> viewModel.updateActiveProfile { it.copy(sentimentConfig = it.sentimentConfig.copy(vowelStretchIntensity = v)) } }
            )
            TerminalSlider(
                label = "WARMTH_LOW_SHELF_EQ",
                valueText = "+${"%.1f".format(activeProfile.dspConfig.warmthEqDb)} dB",
                value = activeProfile.dspConfig.warmthEqDb,
                valueRange = -4f..8f,
                accent = MikaColors.NeonCyan,
                onValueChange = { v -> viewModel.updateActiveProfile { it.copy(dspConfig = it.dspConfig.copy(warmthEqDb = v)) } }
            )
            TerminalSlider(
                label = "ROOM_REVERB_WET",
                valueText = "${(activeProfile.dspConfig.reverbWetMix * 100).roundToInt()}%",
                value = activeProfile.dspConfig.reverbWetMix,
                valueRange = 0f..0.45f,
                accent = MikaColors.NeonCyan,
                onValueChange = { v -> viewModel.updateActiveProfile { it.copy(dspConfig = it.dspConfig.copy(reverbWetMix = v)) } }
            )
        }

        // 5. Expandable Advanced Section 3: GachaSwipe Tried & True Rulebook + Custom Overrides
        ExpandableTerminalSection(
            title = "ADVANCED: PHONETIC & SLANG RULEBOOK",
            subtitle = "${PurrTextProcessor.SLANG_DICTIONARY.size} built-in GachaSwipe rules + ${forgeState.customPhoneticRules.size} custom rules",
            expanded = expandRulebook,
            onToggle = { expandRulebook = !expandRulebook },
            accent = MikaColors.TerminalGreen
        ) {
            Text(
                text = "> BUILT-IN GACHA-SWIPE RULES (ALWAYS ACTIVE WHEN SLANG IS ON)",
                color = MikaColors.TerminalGreen,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 9.sp
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(MikaColors.DeepWellBg)
                    .border(1.dp, MikaColors.TerminalGreen.copy(alpha = 0.25f), RoundedCornerShape(6.dp))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                PurrTextProcessor.HONORIFIC_RULES_PREVIEW.forEach { (k, v) ->
                    Text("$k  ->  $v", color = MikaColors.CyanWhite, fontFamily = FontFamily.Monospace, fontSize = 9.5.sp)
                }
                Text(
                    text = "Slang sample: " + PurrTextProcessor.SLANG_DICTIONARY.entries.take(8).joinToString(", ") { "${it.key}->${it.value}" } + " (+${PurrTextProcessor.SLANG_DICTIONARY.size - 8} more)",
                    color = MikaColors.DimText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp
                )
            }

            Text(
                text = "> ADD CUSTOM PHONETIC OVERRIDE",
                color = MikaColors.NeonCyan,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 9.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = rulePattern,
                    onValueChange = { rulePattern = it },
                    label = { Text("WORD / REGEX", fontSize = 9.sp, fontFamily = FontFamily.Monospace) },
                    singleLine = true,
                    textStyle = TextStyle(color = MikaColors.TerminalGreen, fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MikaColors.TerminalGreen,
                        unfocusedBorderColor = MikaColors.TerminalGreen.copy(alpha = 0.35f),
                        focusedContainerColor = MikaColors.DeepWellBg,
                        unfocusedContainerColor = MikaColors.DeepWellBg
                    ),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = ruleReplacement,
                    onValueChange = { ruleReplacement = it },
                    label = { Text("SPEAK AS", fontSize = 9.sp, fontFamily = FontFamily.Monospace) },
                    singleLine = true,
                    textStyle = TextStyle(color = MikaColors.CyanWhite, fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MikaColors.NeonCyan,
                        unfocusedBorderColor = MikaColors.NeonCyan.copy(alpha = 0.35f),
                        focusedContainerColor = MikaColors.DeepWellBg,
                        unfocusedContainerColor = MikaColors.DeepWellBg
                    ),
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MikaTagPill(
                        label = if (ruleIsRegex) "REGEX:ON" else "REGEX:OFF",
                        selected = ruleIsRegex,
                        activeColor = MikaColors.NeonCyan,
                        onClick = { ruleIsRegex = !ruleIsRegex }
                    )
                    MikaTagPill(
                        label = ruleCategory.label,
                        selected = true,
                        activeColor = MikaColors.TerminalGreen,
                        onClick = {
                            val entries = RuleCategory.entries
                            ruleCategory = entries[(entries.indexOf(ruleCategory) + 1) % entries.size]
                        }
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(MikaColors.TerminalGreen.copy(alpha = 0.16f))
                        .border(1.dp, MikaColors.TerminalGreen, RoundedCornerShape(4.dp))
                        .clickable {
                            viewModel.addPhoneticRule(rulePattern, ruleReplacement, ruleIsRegex, ruleCategory)
                            rulePattern = ""
                            ruleReplacement = ""
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "+ ADD_RULE",
                        color = MikaColors.TerminalGreen,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Black,
                        fontSize = 10.sp
                    )
                }
            }

            forgeState.customPhoneticRules.forEach { rule ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .background(MikaColors.DeepWellBg)
                        .border(1.dp, MikaColors.TerminalGreen.copy(alpha = 0.25f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${rule.pattern} -> ${rule.replacement}",
                        color = if (rule.enabled) MikaColors.CyanWhite else MikaColors.DimText,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        MikaTagPill(
                            label = if (rule.enabled) "ON" else "OFF",
                            selected = rule.enabled,
                            activeColor = MikaColors.TerminalGreen,
                            onClick = { viewModel.togglePhoneticRule(rule.id) }
                        )
                        MikaTagPill(
                            label = "DEL",
                            selected = true,
                            activeColor = MikaColors.DangerRed,
                            onClick = { viewModel.deletePhoneticRule(rule.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SimplePurrToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    accent: Color,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
            Text(
                text = title,
                color = if (checked) Color.White else MikaColors.DimText,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp
            )
            Text(
                text = subtitle,
                color = MikaColors.DimText,
                fontFamily = FontFamily.Monospace,
                fontSize = 9.5.sp
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MikaColors.VoidBg,
                checkedTrackColor = accent
            )
        )
    }
}

@Composable
private fun TerminalSlider(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    accent: Color,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, color = MikaColors.CyanWhite, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 10.sp)
            Text(valueText, color = accent, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black, fontSize = 10.sp)
        }
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = accent,
                activeTrackColor = accent,
                inactiveTrackColor = accent.copy(alpha = 0.22f)
            )
        )
    }
}
