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
import androidx.compose.foundation.layout.width
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
import com.projectmika.purrtts.core.purr.ActionHandlingMode
import com.projectmika.purrtts.core.purr.ArchetypeTone
import com.projectmika.purrtts.core.purr.PhoneticRule
import com.projectmika.purrtts.core.purr.PurrChunk
import com.projectmika.purrtts.core.purr.PurrProfile
import com.projectmika.purrtts.core.purr.RuleCategory
import com.projectmika.purrtts.core.purr.VoiceDescriptor
import com.projectmika.purrtts.ui.theme.CyberDropdown
import com.projectmika.purrtts.ui.theme.MikaColors
import com.projectmika.purrtts.ui.theme.MikaGlowPanel
import com.projectmika.purrtts.ui.theme.MikaTagPill
import kotlin.math.roundToInt

/**
 * ✨ PURR RULEBOOK & PHONETIC EDITOR TAB ✨
 * Controls *action* vs "dialogue" splitting, deterministic MIKASTOP pause durations (ms),
 * sentiment prosody curves, 24kHz Post-DSP EQ/Reverb, and live phonetic dictionary overrides.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PurrRulebookTab(
    profile: PurrProfile,
    customRules: List<PhoneticRule>,
    allVoices: List<VoiceDescriptor>,
    previewChunks: List<PurrChunk>,
    viewModel: TtsForgeViewModel
) {
    var rulePattern by remember { mutableStateOf("") }
    var ruleReplacement by remember { mutableStateOf("") }
    var ruleIsRegex by remember { mutableStateOf(false) }
    var ruleCategory by remember { mutableStateOf(RuleCategory.CUSTOM) }
    var ruleSearch by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {

        // 1. Action Blocks vs Dialogue Routing Matrix
        MikaGlowPanel(accent = MikaColors.NeonPurple) {
            Text(
                text = "> ACTION_VS_DIALOGUE_SPLITTER",
                color = MikaColors.NeonPurple,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Black,
                fontSize = 11.sp,
                letterSpacing = 1.0.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ActionHandlingMode.entries.forEach { mode ->
                    MikaTagPill(
                        label = mode.label,
                        selected = profile.actionHandlingMode == mode,
                        activeColor = MikaColors.NeonPurple,
                        onClick = { viewModel.updateActiveProfile { it.copy(actionHandlingMode = mode) } }
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "// ${profile.actionHandlingMode.description}",
                color = MikaColors.DimText,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp
            )

            if (profile.actionHandlingMode == ActionHandlingMode.DUAL_VOICE_ROUTING) {
                Spacer(modifier = Modifier.height(10.dp))
                val voiceOptions = remember(allVoices) { allVoices.map { it.id } }
                CyberDropdown(
                    label = "> SECONDARY_DIALOGUE_VOICE_ID",
                    options = voiceOptions,
                    selectedOption = profile.dialogueVoiceId.ifBlank { voiceOptions.firstOrNull().orEmpty() },
                    optionLabel = { id ->
                        allVoices.firstOrNull { it.id == id }?.displayName ?: id
                    },
                    onSelect = { selected -> viewModel.assignDialogueVoice(selected) },
                    accent = MikaColors.NeonPink
                )
            }

            if (profile.actionHandlingMode == ActionHandlingMode.NARRATE_SOFT) {
                Spacer(modifier = Modifier.height(8.dp))
                TerminalSlider(
                    label = "ACTION_NARRATION_VOL",
                    valueText = "${(profile.narrationVolumeMultiplier * 100).roundToInt()}%",
                    value = profile.narrationVolumeMultiplier,
                    valueRange = 0.35f..1.0f,
                    accent = MikaColors.NeonPurple,
                    onValueChange = { v -> viewModel.updateActiveProfile { it.copy(narrationVolumeMultiplier = v) } }
                )
                TerminalSlider(
                    label = "ACTION_NARRATION_RATE",
                    valueText = "${"%.2f".format(profile.narrationRateMultiplier)}x",
                    value = profile.narrationRateMultiplier,
                    valueRange = 0.75f..1.15f,
                    accent = MikaColors.NeonPurple,
                    onValueChange = { v -> viewModel.updateActiveProfile { it.copy(narrationRateMultiplier = v) } }
                )
            }
        }

        // 2. MIKASTOP Pause Duration Sliders + Live Token Preview
        MikaGlowPanel(accent = MikaColors.MikaAmber) {
            Text(
                text = "> MIKASTOP_PAUSE_BOUNDARIES (24KHZ SILENCE)",
                color = MikaColors.MikaAmber,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Black,
                fontSize = 11.sp,
                letterSpacing = 0.9.sp
            )
            Spacer(modifier = Modifier.height(8.dp))

            TerminalSlider(
                label = "ELLIPSIS (...)",
                valueText = "${profile.mikaStopConfig.ellipsisPauseMs} ms",
                value = profile.mikaStopConfig.ellipsisPauseMs.toFloat(),
                valueRange = 100f..1200f,
                accent = MikaColors.MikaAmber,
                onValueChange = { v -> viewModel.updateMikaStopConfig { it.copy(ellipsisPauseMs = v.roundToInt()) } }
            )
            TerminalSlider(
                label = "EM_DASH (— / ~)",
                valueText = "${profile.mikaStopConfig.emDashPauseMs} ms",
                value = profile.mikaStopConfig.emDashPauseMs.toFloat(),
                valueRange = 80f..900f,
                accent = MikaColors.MikaAmber,
                onValueChange = { v -> viewModel.updateMikaStopConfig { it.copy(emDashPauseMs = v.roundToInt()) } }
            )
            TerminalSlider(
                label = "SENTENCE (. ! ?)",
                valueText = "${profile.mikaStopConfig.sentencePauseMs} ms",
                value = profile.mikaStopConfig.sentencePauseMs.toFloat(),
                valueRange = 80f..900f,
                accent = MikaColors.MikaAmber,
                onValueChange = { v -> viewModel.updateMikaStopConfig { it.copy(sentencePauseMs = v.roundToInt()) } }
            )
            TerminalSlider(
                label = "COMMA_BREATH (, ; :)",
                valueText = "${profile.mikaStopConfig.commaPauseMs} ms",
                value = profile.mikaStopConfig.commaPauseMs.toFloat(),
                valueRange = 40f..450f,
                accent = MikaColors.MikaAmber,
                onValueChange = { v -> viewModel.updateMikaStopConfig { it.copy(commaPauseMs = v.roundToInt()) } }
            )
            TerminalSlider(
                label = "ACTION_STRIP_PAUSE (*...*)",
                valueText = "${profile.mikaStopConfig.actionStripPauseMs} ms",
                value = profile.mikaStopConfig.actionStripPauseMs.toFloat(),
                valueRange = 60f..650f,
                accent = MikaColors.MikaAmber,
                onValueChange = { v -> viewModel.updateMikaStopConfig { it.copy(actionStripPauseMs = v.roundToInt()) } }
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "SLICE_AT_COMMAS (NATURAL BREATH)",
                    color = MikaColors.CyanWhite,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp
                )
                Switch(
                    checked = profile.mikaStopConfig.sliceAtCommas,
                    onCheckedChange = { c -> viewModel.updateMikaStopConfig { it.copy(sliceAtCommas = c) } },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MikaColors.VoidBg,
                        checkedTrackColor = MikaColors.MikaAmber
                    )
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "> LIVE_MIKASTOP_TOKENIZER_PREVIEW (${previewChunks.size} CHUNKS)",
                color = MikaColors.NeonCyan,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 9.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
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
                            text = "#$idx [MIKASTOP] +${chunk.durationMs}ms ZERO_PCM (${chunk.reason})",
                            color = MikaColors.MikaAmber,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp
                        )
                        is PurrChunk.Speech -> Text(
                            text = "#$idx [${chunk.role.name}|${chunk.targetVoiceId}|r=${"%.2f".format(chunk.speakingRate)}] ${chunk.text}",
                            color = if (chunk.role.name == "DIALOGUE") MikaColors.PinkWhite else MikaColors.CyanWhite,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }

        // 3. Sentiment Prosody Curves & Post-DSP Rack
        MikaGlowPanel(accent = MikaColors.NeonPink) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "> SENTIMENT_PROSODY & POST_DSP",
                    color = MikaColors.NeonPink,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    fontSize = 11.sp
                )
                Switch(
                    checked = profile.sentimentConfig.enabled,
                    onCheckedChange = { e ->
                        viewModel.updateActiveProfile { p -> p.copy(sentimentConfig = p.sentimentConfig.copy(enabled = e)) }
                    },
                    colors = SwitchDefaults.colors(checkedThumbColor = MikaColors.VoidBg, checkedTrackColor = MikaColors.NeonPink)
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
            CyberDropdown(
                label = "> ARCHETYPE_TONE_OVERRIDE",
                options = ArchetypeTone.entries,
                selectedOption = profile.sentimentConfig.toneOverride,
                optionLabel = { it.label },
                onSelect = { tone ->
                    viewModel.updateActiveProfile { p -> p.copy(sentimentConfig = p.sentimentConfig.copy(toneOverride = tone)) }
                },
                accent = MikaColors.NeonPink
            )

            Spacer(modifier = Modifier.height(8.dp))
            CyberDropdown(
                label = "> ACOUSTIC_DSP_PRESET",
                options = AcousticEffectPreset.entries,
                selectedOption = profile.dspConfig.effectPreset,
                optionLabel = { it.label },
                onSelect = { preset ->
                    viewModel.updateActiveProfile { p -> p.copy(dspConfig = p.dspConfig.copy(effectPreset = preset)) }
                },
                accent = MikaColors.NeonCyan
            )

            Spacer(modifier = Modifier.height(8.dp))
            TerminalSlider(
                label = "BASE_SPEAKING_RATE",
                valueText = "${"%.2f".format(profile.baseSpeed)}x",
                value = profile.baseSpeed,
                valueRange = 0.70f..1.45f,
                accent = MikaColors.NeonCyan,
                onValueChange = { v -> viewModel.updateActiveProfile { it.copy(baseSpeed = v) } }
            )
            TerminalSlider(
                label = "WARMTH_LOW_SHELF_EQ",
                valueText = "+${"%.1f".format(profile.dspConfig.warmthEqDb)} dB",
                value = profile.dspConfig.warmthEqDb,
                valueRange = -4f..8f,
                accent = MikaColors.NeonPink,
                onValueChange = { v -> viewModel.updateActiveProfile { it.copy(dspConfig = it.dspConfig.copy(warmthEqDb = v)) } }
            )
            TerminalSlider(
                label = "ROOM_REVERB_WET",
                valueText = "${(profile.dspConfig.reverbWetMix * 100).roundToInt()}%",
                value = profile.dspConfig.reverbWetMix,
                valueRange = 0f..0.45f,
                accent = MikaColors.NeonPink,
                onValueChange = { v -> viewModel.updateActiveProfile { it.copy(dspConfig = it.dspConfig.copy(reverbWetMix = v)) } }
            )
            TerminalSlider(
                label = "STUTTER_DRAMA_INTENSITY",
                valueText = "${(profile.sentimentConfig.stutterIntensity * 100).roundToInt()}%",
                value = profile.sentimentConfig.stutterIntensity,
                valueRange = 0f..0.45f,
                accent = MikaColors.NeonPink,
                onValueChange = { v -> viewModel.updateActiveProfile { it.copy(sentimentConfig = it.sentimentConfig.copy(stutterIntensity = v)) } }
            )
        }

        // 4. Interactive Phonetic & Slang Dictionary Editor
        MikaGlowPanel(accent = MikaColors.TerminalGreen) {
            Text(
                text = "> PHONETIC_OVERRIDE_TERMINAL",
                color = MikaColors.TerminalGreen,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Black,
                fontSize = 11.sp
            )
            Text(
                text = "// Built-in: ~100 slang rules (omg, lol, fr), anime honorifics, & organic stutters (w-what -> wuh-what).",
                color = MikaColors.DimText,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = rulePattern,
                    onValueChange = { rulePattern = it },
                    label = { Text("PATTERN", fontSize = 9.sp, fontFamily = FontFamily.Monospace) },
                    singleLine = true,
                    textStyle = TextStyle(color = MikaColors.TerminalGreen, fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MikaColors.TerminalGreen,
                        unfocusedBorderColor = MikaColors.TerminalGreen.copy(alpha = 0.35f),
                        focusedContainerColor = MikaColors.PanelBg,
                        unfocusedContainerColor = MikaColors.PanelBg
                    ),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = ruleReplacement,
                    onValueChange = { ruleReplacement = it },
                    label = { Text("SPEAK_AS", fontSize = 9.sp, fontFamily = FontFamily.Monospace) },
                    singleLine = true,
                    textStyle = TextStyle(color = MikaColors.CyanWhite, fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MikaColors.NeonCyan,
                        unfocusedBorderColor = MikaColors.NeonCyan.copy(alpha = 0.35f),
                        focusedContainerColor = MikaColors.PanelBg,
                        unfocusedContainerColor = MikaColors.PanelBg
                    ),
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
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
                        .padding(horizontal = 12.dp, vertical = 7.dp)
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

            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = ruleSearch,
                onValueChange = { ruleSearch = it },
                placeholder = { Text("Filter custom rules...", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MikaColors.DimText) },
                singleLine = true,
                textStyle = TextStyle(color = MikaColors.CyanWhite, fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MikaColors.TerminalGreen,
                    unfocusedBorderColor = MikaColors.TerminalGreen.copy(alpha = 0.2f),
                    focusedContainerColor = MikaColors.DeepWellBg,
                    unfocusedContainerColor = MikaColors.DeepWellBg
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))
            val visibleRules = customRules.filter {
                ruleSearch.isBlank() ||
                    it.pattern.contains(ruleSearch, ignoreCase = true) ||
                    it.replacement.contains(ruleSearch, ignoreCase = true)
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                visibleRules.forEach { rule ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .background(MikaColors.DeepWellBg)
                            .border(
                                1.dp,
                                if (rule.enabled) MikaColors.TerminalGreen.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.1f),
                                RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${rule.pattern}  ->  ${rule.replacement}",
                                color = if (rule.enabled) MikaColors.CyanWhite else MikaColors.DimText,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                            Text(
                                text = "[${rule.category.label}] ${if (rule.isRegex) "REGEX" else "TOKEN"}",
                                color = MikaColors.TerminalGreen.copy(alpha = 0.6f),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.sp
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
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
            Text(
                text = label,
                color = MikaColors.CyanWhite,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp
            )
            Text(
                text = valueText,
                color = accent,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Black,
                fontSize = 10.sp
            )
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
