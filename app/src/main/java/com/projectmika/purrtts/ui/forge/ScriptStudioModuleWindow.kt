package com.projectmika.purrtts.ui.forge

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.projectmika.purrtts.core.purr.PurrForgeState
import com.projectmika.purrtts.core.purr.PurrProfile
import com.projectmika.purrtts.core.purr.PurrScriptCastSlot
import com.projectmika.purrtts.core.purr.PurrScriptParser
import com.projectmika.purrtts.core.purr.VoiceDescriptor
import com.projectmika.purrtts.ui.theme.CyberIconType
import com.projectmika.purrtts.ui.theme.MikaColors
import com.projectmika.purrtts.ui.theme.MikaCyberIcon
import com.projectmika.purrtts.ui.theme.MikaGlowPanel
import com.projectmika.purrtts.ui.theme.MikaTagPill
import com.projectmika.purrtts.ui.theme.MikaVoiceDropdownSelector
import com.projectmika.purrtts.ui.theme.bringIntoViewWithIme
import kotlin.math.roundToInt

/**
 * ✨ TOOL-08: MULTI-VOICE SCRIPT & PODCAST STUDIO OS MODULE ✨
 * Assigns named speakers across any installed TTS/Cloning engines, auditions scenes live line-by-line,
 * and exports mastered multi-speaker `.WAV` scenes to `Downloads/PURR-TTS/`.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScriptStudioModuleWindowBody(
    forgeState: PurrForgeState,
    activeProfile: PurrProfile,
    availableVoices: List<VoiceDescriptor>,
    viewModel: TtsForgeViewModel
) {
    val isRunning by viewModel.isRunningScriptStudio.collectAsState()
    val statusText by viewModel.scriptStudioStatus.collectAsState()
    val activeLineIdx by viewModel.scriptStudioActiveLineIndex.collectAsState()

    val firstTemplate = PurrScriptParser.DEFAULT_TEMPLATES.first()
    var sceneTitle by remember { mutableStateOf(firstTemplate.title) }
    var rawScript by remember { mutableStateOf(firstTemplate.script) }
    var defaultPauseMs by remember { mutableIntStateOf(240) }

    val parsedLines = remember(rawScript, defaultPauseMs) {
        PurrScriptParser.parse(rawScript, defaultPauseMs)
    }
    val speakers = remember(parsedLines) {
        PurrScriptParser.discoverSpeakers(parsedLines)
    }

    val slottedVoices = remember(availableVoices, forgeState.slottedCartridges) {
        val eligible = availableVoices.filter { v -> v.cartridgeId in forgeState.slottedCartridges }
        eligible.ifEmpty { availableVoices }
    }

    val castAssignments = remember { mutableStateMapOf<String, PurrScriptCastSlot>() }

    fun effectiveSlotFor(tag: String, index: Int): PurrScriptCastSlot {
        val existing = castAssignments[tag]
        if (existing != null && slottedVoices.any { it.id == existing.voiceId }) return existing
        val fallbackVoice = if (slottedVoices.isNotEmpty()) {
            slottedVoices[index % slottedVoices.size]
        } else {
            availableVoices.firstOrNull()
        }
        return PurrScriptCastSlot(
            speakerTag = tag,
            voiceId = existing?.voiceId?.takeIf { id -> availableVoices.any { it.id == id } }
                ?: fallbackVoice?.id
                ?: activeProfile.primaryVoiceId,
            speedScale = existing?.speedScale ?: 1.0f,
            pitchScale = existing?.pitchScale ?: 1.0f
        )
    }

    val resolvedCastMap = remember(speakers, castAssignments.toMap(), slottedVoices) {
        speakers.mapIndexed { idx, tag -> tag to effectiveSlotFor(tag, idx) }.toMap()
    }

    // 1. Header & Template Picker
    MikaGlowPanel(accent = MikaColors.NeonPink) {
        Text(
            text = "> MULTI_VOICE_SCRIPT_STUDIO // PODCAST_&_SCENE",
            color = MikaColors.NeonPink,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Black,
            fontSize = 11.sp
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Write dialogue as `SPEAKER: line` (optional trailing `[pause:400ms]`), cast any installed voice across engines per speaker, then audition live or render a mastered multi-voice .WAV!",
            color = MikaColors.CyanWhite.copy(alpha = 0.78f),
            fontFamily = FontFamily.Monospace,
            fontSize = 9.5.sp,
            lineHeight = 13.5.sp
        )
        Spacer(modifier = Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            PurrScriptParser.DEFAULT_TEMPLATES.forEach { tpl ->
                MikaTagPill(
                    label = tpl.label,
                    selected = sceneTitle == tpl.title,
                    activeColor = MikaColors.NeonCyan,
                    onClick = {
                        sceneTitle = tpl.title
                        rawScript = tpl.script
                    }
                )
            }
        }
    }

    // 2. Scene Script Editor
    MikaGlowPanel(accent = MikaColors.NeonCyan) {
        OutlinedTextField(
            value = sceneTitle,
            onValueChange = { sceneTitle = it },
            label = { Text("SCENE_EXPORT_TITLE", fontSize = 9.sp, fontFamily = FontFamily.Monospace) },
            singleLine = true,
            textStyle = TextStyle(color = MikaColors.SsrGold, fontFamily = FontFamily.Monospace, fontSize = 11.sp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MikaColors.SsrGold,
                unfocusedBorderColor = MikaColors.SsrGold.copy(alpha = 0.35f),
                focusedContainerColor = MikaColors.DeepWellBg,
                unfocusedContainerColor = MikaColors.DeepWellBg
            ),
            modifier = Modifier.fillMaxWidth().bringIntoViewWithIme()
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = rawScript,
            onValueChange = { rawScript = it },
            label = { Text("SCRIPT_LINES (SPEAKER: DIALOGUE)", fontSize = 9.sp, fontFamily = FontFamily.Monospace) },
            minLines = 6,
            maxLines = 12,
            textStyle = TextStyle(color = MikaColors.CyanWhite, fontFamily = FontFamily.Monospace, fontSize = 10.5.sp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MikaColors.NeonCyan,
                unfocusedBorderColor = MikaColors.NeonCyan.copy(alpha = 0.35f),
                focusedContainerColor = MikaColors.DeepWellBg,
                unfocusedContainerColor = MikaColors.DeepWellBg
            ),
            modifier = Modifier.fillMaxWidth().bringIntoViewWithIme()
        )
        Spacer(modifier = Modifier.height(6.dp))
        TerminalSlider(
            label = "INTER_TURN_PAUSE_MS",
            valueText = "$defaultPauseMs ms",
            value = defaultPauseMs.toFloat(),
            valueRange = 50f..900f,
            accent = MikaColors.NeonCyan,
            onValueChange = { defaultPauseMs = (it.roundToInt() / 10) * 10 }
        )
        Text(
            text = "DETECTED_SPEAKERS: ${speakers.size} | PARSED_TURNS: ${parsedLines.size}",
            color = MikaColors.DimText,
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp
        )
    }

    // 3. Cast Roster Voice Assignment
    MikaGlowPanel(accent = MikaColors.NeonPurple) {
        Text(
            text = "> CAST_ROSTER_VOICES (${speakers.size})",
            color = MikaColors.NeonPurple,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Black,
            fontSize = 10.5.sp
        )
        Spacer(modifier = Modifier.height(6.dp))
        if (speakers.isEmpty()) {
            Text(
                text = "// Add script lines formatted like `HOST_A: Hello!` to cast voices.",
                color = MikaColors.DimText,
                fontFamily = FontFamily.Monospace,
                fontSize = 9.5.sp
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                speakers.forEachIndexed { idx, tag ->
                    val slot = effectiveSlotFor(tag, idx)
                    val selectedVoice = slottedVoices.firstOrNull { it.id == slot.voiceId }
                        ?: availableVoices.firstOrNull { it.id == slot.voiceId }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(MikaColors.DeepWellBg)
                            .border(1.dp, MikaColors.NeonPurple.copy(alpha = 0.36f), RoundedCornerShape(6.dp))
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        MikaVoiceDropdownSelector(
                            label = "CAST_$tag",
                            voices = slottedVoices,
                            selectedVoice = selectedVoice,
                            accent = MikaColors.NeonPurple,
                            onSelectVoice = { chosen ->
                                castAssignments[tag] = slot.copy(voiceId = chosen.id)
                            },
                            onSampleVoice = { chosen ->
                                viewModel.auditionText(
                                    text = "$tag casting voice check on PURR Engine.",
                                    overrideVoice = chosen
                                )
                            }
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                TerminalSlider(
                                    label = "RATE",
                                    valueText = "${"%.2f".format(slot.speedScale)}x",
                                    value = slot.speedScale,
                                    valueRange = 0.75f..1.35f,
                                    accent = MikaColors.NeonCyan,
                                    onValueChange = { castAssignments[tag] = slot.copy(speedScale = it) }
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                TerminalSlider(
                                    label = "PITCH",
                                    valueText = "${"%.2f".format(slot.pitchScale)}x",
                                    value = slot.pitchScale,
                                    valueRange = 0.88f..1.12f,
                                    accent = MikaColors.NeonPink,
                                    onValueChange = { castAssignments[tag] = slot.copy(pitchScale = it) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 4. Table-Read Transport & Export
    MikaGlowPanel(accent = MikaColors.TerminalGreen) {
        statusText?.let { msg ->
            Text(
                text = msg,
                color = if (activeLineIdx >= 0) MikaColors.TerminalGreen else MikaColors.MikaAmber,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 9.5.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(6.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        if (isRunning) MikaColors.DangerRed.copy(alpha = 0.22f)
                        else MikaColors.TerminalGreen.copy(alpha = 0.20f)
                    )
                    .border(
                        1.4.dp,
                        if (isRunning) MikaColors.DangerRed else MikaColors.TerminalGreen,
                        RoundedCornerShape(6.dp)
                    )
                    .clickable {
                        if (isRunning) {
                            viewModel.stopScriptStudio()
                        } else {
                            viewModel.auditionScriptScene(parsedLines, resolvedCastMap)
                        }
                    }
                    .padding(vertical = 11.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                MikaCyberIcon(
                    type = if (isRunning) CyberIconType.STOP else CyberIconType.PLAY,
                    tint = if (isRunning) MikaColors.DangerRed else MikaColors.TerminalGreen,
                    size = 13.dp
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isRunning) "STOP READ" else "TABLE READ LIVE",
                    color = if (isRunning) MikaColors.DangerRed else MikaColors.TerminalGreen,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    fontSize = 10.sp
                )
            }

            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MikaColors.SsrGold.copy(alpha = if (isRunning) 0.08f else 0.20f))
                    .border(
                        1.4.dp,
                        MikaColors.SsrGold.copy(alpha = if (isRunning) 0.35f else 1f),
                        RoundedCornerShape(6.dp)
                    )
                    .clickable(enabled = !isRunning) {
                        viewModel.exportScriptSceneWav(sceneTitle, parsedLines, resolvedCastMap)
                    }
                    .padding(vertical = 11.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                MikaCyberIcon(type = CyberIconType.DOWNLOAD, tint = MikaColors.SsrGold, size = 13.dp)
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "EXPORT .WAV",
                    color = MikaColors.SsrGold,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    fontSize = 10.sp
                )
            }
        }

        if (parsedLines.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                parsedLines.take(8).forEachIndexed { idx, line ->
                    val isCurrent = idx == activeLineIdx
                    val borderCol = if (isCurrent) MikaColors.TerminalGreen else MikaColors.SubtleBorder
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(5.dp))
                            .background(if (isCurrent) MikaColors.TerminalGreen.copy(alpha = 0.14f) else MikaColors.DeepWellBg)
                            .border(1.dp, borderCol, RoundedCornerShape(5.dp))
                            .clickable(enabled = !isRunning) {
                                viewModel.auditionScriptScene(parsedLines, resolvedCastMap, startIndex = idx)
                            }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "[${line.speakerTag}]",
                            color = if (isCurrent) MikaColors.TerminalGreen else MikaColors.NeonPink,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Black,
                            fontSize = 9.sp
                        )
                        Text(
                            text = line.text,
                            color = MikaColors.PrimaryText,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.5.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}
