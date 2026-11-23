package com.projectmika.purrtts.ui.forge

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.projectmika.purrtts.core.purr.DownloadState
import com.projectmika.purrtts.core.purr.PurrForgeState
import com.projectmika.purrtts.core.purr.PurrProfile
import com.projectmika.purrtts.core.purr.QwenVoiceDesignBuilder
import com.projectmika.purrtts.core.purr.VoiceDesignAccent
import com.projectmika.purrtts.core.purr.VoiceDesignAcoustics
import com.projectmika.purrtts.core.purr.VoiceDesignAge
import com.projectmika.purrtts.core.purr.VoiceDesignDelivery
import com.projectmika.purrtts.core.purr.VoiceDesignGender
import com.projectmika.purrtts.core.purr.VoiceDesignPace
import com.projectmika.purrtts.core.purr.VoiceDesignPitch
import com.projectmika.purrtts.core.purr.VoiceDesignSpec
import com.projectmika.purrtts.core.purr.VoiceDesignTimbre
import com.projectmika.purrtts.engine.qwen3.Qwen3TtsEngine
import com.projectmika.purrtts.ui.theme.CyberDropdown
import com.projectmika.purrtts.ui.theme.CyberIconType
import com.projectmika.purrtts.ui.theme.MikaColors
import com.projectmika.purrtts.ui.theme.MikaCyberIcon
import com.projectmika.purrtts.ui.theme.MikaGlowPanel
import com.projectmika.purrtts.ui.theme.MikaTagPill

/**
 * ✨ TOOL-06: QWEN3 1.7B VOICEDESIGN PROMPT STUDIO MODULE ✨
 * Dedicated structured prompt builder mapping Qwen3-TTS VoiceDesign dimensions
 * (Gender, Age, Pitch, Multi-Timbre Texture, Pace, Emotional Delivery, Accent & Mic Acoustics)
 * into clean natural-language `instruct` strings, saved voice presets, and Design->Clone locking.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VoiceDesignModuleWindowBody(
    forgeState: PurrForgeState,
    activeProfile: PurrProfile,
    isPlayingPreview: Boolean,
    viewModel: TtsForgeViewModel,
    onOpenCartridgeBayTts: () -> Unit
) {
    val designModelInstalled = forgeState.modelStates[Qwen3TtsEngine.SPEC_DESIGN_17B]?.state == DownloadState.INSTALLED
    var spec by remember { mutableStateOf(QwenVoiceDesignBuilder.CURATED_PRESETS.first().second) }
    var presetName by remember { mutableStateOf("MIKA_DESIGN_01") }
    var sampleLine by remember {
        mutableStateOf("Hey there! Listen carefully to how crisp and natural this custom designed voice sounds.")
    }

    fun applySpec(next: VoiceDesignSpec) {
        spec = next
        viewModel.updateVoiceDesignPrompt(QwenVoiceDesignBuilder.composePrompt(next))
    }

    // 1. Model availability & Overview Header
    MikaGlowPanel(accent = if (designModelInstalled) MikaColors.NeonPink else MikaColors.MikaAmber) {
        Text(
            text = "> QWEN3_1.7B_VOICEDESIGN_STUDIO",
            color = MikaColors.NeonPink,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Black,
            fontSize = 11.sp
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Sculpt any original voice from scratch using natural-language attributes for Alibaba Qwen3-TTS 1.7B VoiceDesign (SUB-03A). Combine up to 3 textures, audition live, or lock into a reusable Clone voice.",
            color = MikaColors.CyanWhite.copy(alpha = 0.78f),
            fontFamily = FontFamily.Monospace,
            fontSize = 9.5.sp,
            lineHeight = 13.5.sp
        )
        AnimatedVisibility(visible = !designModelInstalled) {
            Column {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(MikaColors.MikaAmber.copy(alpha = 0.16f))
                        .border(1.2.dp, MikaColors.MikaAmber, RoundedCornerShape(6.dp))
                        .clickable(onClick = onOpenCartridgeBayTts)
                        .padding(horizontal = 10.dp, vertical = 9.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MikaCyberIcon(type = CyberIconType.DOWNLOAD, tint = MikaColors.MikaAmber, size = 13.dp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "DOWNLOAD SUB-03A (QWEN3 1.7B VOICEDESIGN) IN BAY",
                        color = MikaColors.MikaAmber,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Black,
                        fontSize = 9.5.sp
                    )
                }
            }
        }
    }

    // 2. Quick Curated Archetype Presets
    MikaGlowPanel(accent = MikaColors.NeonCyan) {
        Text(
            text = "> ARCHETYPE_TEMPLATES",
            color = MikaColors.NeonCyan,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Black,
            fontSize = 10.5.sp
        )
        Spacer(modifier = Modifier.height(6.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            QwenVoiceDesignBuilder.CURATED_PRESETS.forEach { (label, template) ->
                MikaTagPill(
                    label = label,
                    selected = spec == template,
                    activeColor = MikaColors.NeonCyan,
                    onClick = { applySpec(template) }
                )
            }
        }
    }

    // 3. Structured Attribute Controls
    MikaGlowPanel(accent = MikaColors.NeonPink) {
        Text(
            text = "> VOICE_SCULPTING_PARAMETERS",
            color = MikaColors.NeonPink,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Black,
            fontSize = 10.5.sp
        )
        Spacer(modifier = Modifier.height(8.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CyberDropdown(
                label = "GENDER",
                options = VoiceDesignGender.entries,
                selectedOption = spec.gender,
                optionLabel = { it.label },
                onSelect = { applySpec(spec.copy(gender = it)) },
                accent = MikaColors.NeonCyan,
                modifier = Modifier.weight(1f)
            )
            CyberDropdown(
                label = "AGE_BAND",
                options = VoiceDesignAge.entries,
                selectedOption = spec.age,
                optionLabel = { it.label },
                onSelect = { applySpec(spec.copy(age = it)) },
                accent = MikaColors.NeonCyan,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CyberDropdown(
                label = "PITCH_REGISTER",
                options = VoiceDesignPitch.entries,
                selectedOption = spec.pitch,
                optionLabel = { it.label },
                onSelect = { applySpec(spec.copy(pitch = it)) },
                accent = MikaColors.NeonPink,
                modifier = Modifier.weight(1f)
            )
            CyberDropdown(
                label = "PACE_CADENCE",
                options = VoiceDesignPace.entries,
                selectedOption = spec.pace,
                optionLabel = { it.label },
                onSelect = { applySpec(spec.copy(pace = it)) },
                accent = MikaColors.NeonPink,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        CyberDropdown(
            label = "EMOTION_&_DELIVERY_STYLE",
            options = VoiceDesignDelivery.entries,
            selectedOption = spec.delivery,
            optionLabel = { it.label },
            onSelect = { applySpec(spec.copy(delivery = it)) },
            accent = MikaColors.SsrGold
        )

        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CyberDropdown(
                label = "ACCENT_/_DICTION",
                options = VoiceDesignAccent.entries,
                selectedOption = spec.accent,
                optionLabel = { it.label },
                onSelect = { applySpec(spec.copy(accent = it)) },
                accent = MikaColors.TerminalGreen,
                modifier = Modifier.weight(1f)
            )
            CyberDropdown(
                label = "MIC_ACOUSTICS",
                options = VoiceDesignAcoustics.entries,
                selectedOption = spec.acoustics,
                optionLabel = { it.label },
                onSelect = { applySpec(spec.copy(acoustics = it)) },
                accent = MikaColors.TerminalGreen,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "TIMBRE_&_TEXTURE (SELECT 1-3):",
            color = MikaColors.NeonPurple,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 9.sp
        )
        Spacer(modifier = Modifier.height(5.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            VoiceDesignTimbre.entries.forEach { timbre ->
                val isSelected = timbre in spec.timbres
                MikaTagPill(
                    label = timbre.label,
                    selected = isSelected,
                    activeColor = MikaColors.NeonPurple,
                    onClick = {
                        val nextTimbres = if (isSelected) {
                            if (spec.timbres.size > 1) spec.timbres - timbre else spec.timbres
                        } else {
                            (spec.timbres + timbre).takeLast(3)
                        }
                        applySpec(spec.copy(timbres = nextTimbres))
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        OutlinedTextField(
            value = spec.personaRole,
            onValueChange = { applySpec(spec.copy(personaRole = it)) },
            label = { Text("CHARACTER_ROLE (OPTIONAL)", fontSize = 9.sp, fontFamily = FontFamily.Monospace) },
            placeholder = { Text("e.g. calm late-night radio host, veteran captain...", fontSize = 9.5.sp, fontFamily = FontFamily.Monospace) },
            singleLine = true,
            textStyle = TextStyle(color = MikaColors.CyanWhite, fontFamily = FontFamily.Monospace, fontSize = 11.sp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MikaColors.NeonCyan,
                unfocusedBorderColor = MikaColors.NeonCyan.copy(alpha = 0.35f),
                focusedContainerColor = MikaColors.DeepWellBg,
                unfocusedContainerColor = MikaColors.DeepWellBg
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(6.dp))
        OutlinedTextField(
            value = spec.customNuance,
            onValueChange = { applySpec(spec.copy(customNuance = it)) },
            label = { Text("EXTRA_VOCAL_NUANCE (OPTIONAL)", fontSize = 9.sp, fontFamily = FontFamily.Monospace) },
            placeholder = { Text("e.g. slight smirk on playful words, soft sighs...", fontSize = 9.5.sp, fontFamily = FontFamily.Monospace) },
            singleLine = true,
            textStyle = TextStyle(color = MikaColors.WarmCream, fontFamily = FontFamily.Monospace, fontSize = 11.sp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MikaColors.NeonPink,
                unfocusedBorderColor = MikaColors.NeonPink.copy(alpha = 0.35f),
                focusedContainerColor = MikaColors.DeepWellBg,
                unfocusedContainerColor = MikaColors.DeepWellBg
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }

    // 4. Live Assembled Prompt + Audition & Lock-as-Clone
    MikaGlowPanel(accent = MikaColors.TerminalGreen) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "> LIVE_QWEN3_INSTRUCT_PROMPT",
                color = MikaColors.TerminalGreen,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Black,
                fontSize = 10.sp
            )
            MikaTagPill(
                label = "REBUILD FROM KNOBS",
                selected = false,
                activeColor = MikaColors.NeonCyan,
                leadingIcon = CyberIconType.REFRESH,
                onClick = { applySpec(spec) }
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        OutlinedTextField(
            value = activeProfile.voiceDesignPrompt,
            onValueChange = { viewModel.updateVoiceDesignPrompt(it) },
            minLines = 3,
            maxLines = 5,
            textStyle = TextStyle(color = MikaColors.WarmCream, fontFamily = FontFamily.Monospace, fontSize = 11.sp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MikaColors.NeonPink,
                unfocusedBorderColor = MikaColors.NeonPink.copy(alpha = 0.38f),
                focusedContainerColor = MikaColors.DeepWellBg,
                unfocusedContainerColor = MikaColors.DeepWellBg
            ),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = sampleLine,
            onValueChange = { sampleLine = it },
            label = { Text("AUDITION_TEST_SENTENCE", fontSize = 9.sp, fontFamily = FontFamily.Monospace) },
            minLines = 2,
            maxLines = 3,
            textStyle = TextStyle(color = MikaColors.CyanWhite, fontFamily = FontFamily.Monospace, fontSize = 11.sp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MikaColors.TerminalGreen,
                unfocusedBorderColor = MikaColors.TerminalGreen.copy(alpha = 0.35f),
                focusedContainerColor = MikaColors.DeepWellBg,
                unfocusedContainerColor = MikaColors.DeepWellBg
            ),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(
                    if (isPlayingPreview) MikaColors.DangerRed.copy(alpha = 0.22f)
                    else MikaColors.TerminalGreen.copy(alpha = 0.20f)
                )
                .border(
                    1.5.dp,
                    if (isPlayingPreview) MikaColors.DangerRed else MikaColors.TerminalGreen,
                    RoundedCornerShape(6.dp)
                )
                .clickable {
                    if (isPlayingPreview) {
                        viewModel.stopAudition()
                    } else {
                        viewModel.activateVoiceDesignAndAudition(
                            prompt = activeProfile.voiceDesignPrompt,
                            sampleLine = sampleLine
                        )
                    }
                }
                .padding(vertical = 11.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            MikaCyberIcon(
                type = if (isPlayingPreview) CyberIconType.STOP else CyberIconType.PLAY,
                tint = if (isPlayingPreview) MikaColors.DangerRed else MikaColors.TerminalGreen,
                size = 13.dp
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (isPlayingPreview) "STOP AUDITION" else "ACTIVATE & AUDITION IN QWEN3 1.7B",
                color = if (isPlayingPreview) MikaColors.DangerRed else MikaColors.TerminalGreen,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Black,
                fontSize = 10.5.sp
            )
        }

        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = presetName,
                onValueChange = { presetName = it },
                label = { Text("DESIGN_NAME", fontSize = 9.sp, fontFamily = FontFamily.Monospace) },
                singleLine = true,
                textStyle = TextStyle(color = MikaColors.SsrGold, fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MikaColors.SsrGold,
                    unfocusedBorderColor = MikaColors.SsrGold.copy(alpha = 0.35f),
                    focusedContainerColor = MikaColors.DeepWellBg,
                    unfocusedContainerColor = MikaColors.DeepWellBg
                ),
                modifier = Modifier.weight(1f)
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(MikaColors.SsrGold.copy(alpha = 0.20f))
                    .border(1.dp, MikaColors.SsrGold, RoundedCornerShape(6.dp))
                    .clickable {
                        viewModel.saveVoiceDesignPreset(
                            name = presetName,
                            prompt = activeProfile.voiceDesignPrompt,
                            spec = spec
                        )
                    }
                    .padding(horizontal = 12.dp, vertical = 14.dp)
            ) {
                Text(
                    text = "SAVE DESIGN",
                    color = MikaColors.SsrGold,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    fontSize = 10.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(MikaColors.NeonPurple.copy(alpha = 0.16f))
                .border(1.dp, MikaColors.NeonPurple, RoundedCornerShape(6.dp))
                .clickable {
                    val mappedGender = when (spec.gender) {
                        VoiceDesignGender.FEMALE -> com.projectmika.purrtts.core.purr.VoiceGender.FEMALE
                        VoiceDesignGender.MALE -> com.projectmika.purrtts.core.purr.VoiceGender.MALE
                        VoiceDesignGender.ANDROGYNOUS -> com.projectmika.purrtts.core.purr.VoiceGender.ANDROGYNOUS
                    }
                    viewModel.freezeVoiceDesignAsClone(
                        name = presetName,
                        prompt = activeProfile.voiceDesignPrompt,
                        gender = mappedGender,
                        sampleLine = sampleLine
                    )
                }
                .padding(horizontal = 10.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            MikaCyberIcon(type = CyberIconType.DNA_CLONE, tint = MikaColors.NeonPurple, size = 13.dp)
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "LOCK AS CLONE VOICE (DESIGN -> CLONE)",
                color = MikaColors.NeonPurple,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Black,
                fontSize = 10.sp
            )
        }
        Text(
            text = "// Official Design->Clone tip: renders one reference clip with 1.7B VoiceDesign so long scripts stay 100% consistent in Qwen3 Base / Pocket Clone.",
            color = MikaColors.DimText,
            fontFamily = FontFamily.Monospace,
            fontSize = 8.5.sp,
            lineHeight = 12.sp,
            modifier = Modifier.padding(top = 4.dp)
        )
    }

    // 5. Saved Custom VoiceDesigns
    MikaGlowPanel(accent = MikaColors.SsrGold) {
        Text(
            text = "> SAVED_VOICE_DESIGNS (${forgeState.savedVoiceDesigns.size})",
            color = MikaColors.SsrGold,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Black,
            fontSize = 10.sp
        )
        Spacer(modifier = Modifier.height(6.dp))
        if (forgeState.savedVoiceDesigns.isEmpty()) {
            Text(
                text = "// No saved VoiceDesign presets yet. Tune sliders above and tap SAVE DESIGN.",
                color = MikaColors.DimText,
                fontFamily = FontFamily.Monospace,
                fontSize = 9.5.sp
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                forgeState.savedVoiceDesigns.forEach { saved ->
                    val isCurrentPrompt = activeProfile.voiceDesignPrompt == saved.prompt
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(MikaColors.DeepWellBg)
                            .border(
                                1.dp,
                                if (isCurrentPrompt) MikaColors.NeonPink else MikaColors.SsrGold.copy(alpha = 0.35f),
                                RoundedCornerShape(6.dp)
                            )
                            .clickable {
                                spec = saved.spec
                                presetName = saved.name
                                viewModel.updateVoiceDesignPrompt(saved.prompt)
                            }
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = saved.name,
                                color = Color.White,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.5.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                MikaTagPill(
                                    label = "USE",
                                    selected = isCurrentPrompt,
                                    activeColor = MikaColors.TerminalGreen,
                                    leadingIcon = CyberIconType.PLAY,
                                    onClick = {
                                        spec = saved.spec
                                        presetName = saved.name
                                        viewModel.activateVoiceDesignAndAudition(saved.prompt, sampleLine)
                                    }
                                )
                                MikaTagPill(
                                    label = "DEL",
                                    selected = true,
                                    activeColor = MikaColors.DangerRed,
                                    onClick = { viewModel.deleteVoiceDesignPreset(saved.id) }
                                )
                            }
                        }
                        Text(
                            text = saved.prompt,
                            color = MikaColors.CyanWhite.copy(alpha = 0.72f),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
