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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import com.projectmika.purrtts.core.purr.PurrForgeState
import com.projectmika.purrtts.core.purr.PurrProfile
import com.projectmika.purrtts.core.purr.PurrSrtFormatter
import com.projectmika.purrtts.core.purr.VoiceDescriptor
import com.projectmika.purrtts.ui.theme.CyberIconType
import com.projectmika.purrtts.ui.theme.MikaColors
import com.projectmika.purrtts.ui.theme.MikaCyberIcon
import com.projectmika.purrtts.ui.theme.MikaGlowPanel
import com.projectmika.purrtts.ui.theme.MikaTagPill
import com.projectmika.purrtts.ui.theme.MikaVoiceDropdownSelector
import com.projectmika.purrtts.ui.theme.bringIntoViewWithIme

/**
 * ✨ TOOL-09: VOICE A/B BENCHMARK & PHONETIC LAB OS MODULE ✨
 * Head-to-head comparison across any two voices/engines on identical test prompts,
 * measuring synthesis latency (`ms`), audio duration (`s`), Real-Time Factor (`RTF`),
 * and hardware backend badge with instant cached A/B playback.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VoiceBenchmarkModuleWindowBody(
    forgeState: PurrForgeState,
    activeProfile: PurrProfile,
    availableVoices: List<VoiceDescriptor>,
    viewModel: TtsForgeViewModel
) {
    val isRunning by viewModel.isRunningBenchmark.collectAsState()
    val resultA by viewModel.benchmarkResultA.collectAsState()
    val resultB by viewModel.benchmarkResultB.collectAsState()

    val slottedVoices = remember(availableVoices, forgeState.slottedCartridges) {
        val eligible = availableVoices.filter { it.cartridgeId in forgeState.slottedCartridges }
        eligible.ifEmpty { availableVoices }
    }

    var voiceAId by remember {
        mutableStateOf(activeProfile.primaryVoiceId)
    }
    var voiceBId by remember {
        mutableStateOf(
            slottedVoices.firstOrNull { it.id != activeProfile.primaryVoiceId }?.id
                ?: activeProfile.primaryVoiceId
        )
    }
    var promptText by remember {
        mutableStateOf(PurrSrtFormatter.PHONETIC_CORPUS.first().prompt)
    }

    val voiceA = slottedVoices.firstOrNull { it.id == voiceAId } ?: slottedVoices.firstOrNull()
    val voiceB = slottedVoices.firstOrNull { it.id == voiceBId } ?: slottedVoices.lastOrNull() ?: voiceA

    // 1. Header & Phonetic Stress Test Selector
    MikaGlowPanel(accent = MikaColors.TerminalGreen) {
        Text(
            text = "> VOICE_A/B_BENCHMARK_&_PHONETIC_LAB",
            color = MikaColors.TerminalGreen,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Black,
            fontSize = 11.sp
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Compare any two voices across engines side-by-side on latency, Real-Time Factor (RTF < 1.0 = faster than real-time), hardware offload badge & pronunciation clarity.",
            color = MikaColors.CyanWhite.copy(alpha = 0.78f),
            fontFamily = FontFamily.Monospace,
            fontSize = 9.5.sp,
            lineHeight = 13.5.sp
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "PHONETIC_STRESS_TEST_CORPUS:",
            color = MikaColors.NeonCyan,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 9.sp
        )
        Spacer(modifier = Modifier.height(5.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            PurrSrtFormatter.PHONETIC_CORPUS.forEach { sample ->
                MikaTagPill(
                    label = sample.label,
                    selected = promptText == sample.prompt,
                    activeColor = MikaColors.NeonCyan,
                    onClick = { promptText = sample.prompt }
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = promptText,
            onValueChange = { promptText = it },
            label = { Text("BENCHMARK_TEST_PROMPT", fontSize = 9.sp, fontFamily = FontFamily.Monospace) },
            minLines = 2,
            maxLines = 4,
            textStyle = TextStyle(color = MikaColors.CyanWhite, fontFamily = FontFamily.Monospace, fontSize = 10.5.sp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MikaColors.TerminalGreen,
                unfocusedBorderColor = MikaColors.TerminalGreen.copy(alpha = 0.35f),
                focusedContainerColor = MikaColors.DeepWellBg,
                unfocusedContainerColor = MikaColors.DeepWellBg
            ),
            modifier = Modifier.fillMaxWidth().bringIntoViewWithIme()
        )
    }

    // 2. Slot A / Slot B Selectors
    MikaGlowPanel(accent = MikaColors.NeonCyan) {
        MikaVoiceDropdownSelector(
            label = "SLOT_A // REFERENCE_VOICE",
            voices = slottedVoices,
            selectedVoice = voiceA,
            accent = MikaColors.NeonCyan,
            onSelectVoice = { voiceAId = it.id },
            onSampleVoice = { v -> viewModel.auditionText(promptText, overrideVoice = v) }
        )
        Spacer(modifier = Modifier.height(8.dp))
        MikaVoiceDropdownSelector(
            label = "SLOT_B // CHALLENGER_VOICE",
            voices = slottedVoices,
            selectedVoice = voiceB,
            accent = MikaColors.NeonPink,
            onSelectVoice = { voiceBId = it.id },
            onSampleVoice = { v -> viewModel.auditionText(promptText, overrideVoice = v) }
        )
        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(MikaColors.TerminalGreen.copy(alpha = if (isRunning) 0.10f else 0.22f))
                .border(1.5.dp, MikaColors.TerminalGreen, RoundedCornerShape(6.dp))
                .clickable(enabled = !isRunning && voiceA != null && voiceB != null) {
                    if (voiceA != null && voiceB != null) {
                        viewModel.runVoiceAbBenchmark(promptText, voiceA, voiceB)
                    }
                }
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            MikaCyberIcon(type = CyberIconType.BOLT_ACCEL, tint = MikaColors.TerminalGreen, size = 14.dp)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (isRunning) "BENCHMARKING A vs B..." else "RUN HEAD-TO-HEAD BENCHMARK",
                color = MikaColors.TerminalGreen,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Black,
                fontSize = 10.sp,
                letterSpacing = 0.6.sp
            )
        }
    }

    // 3. Telemetry Scorecard
    if (resultA != null || resultB != null) {
        MikaGlowPanel(accent = MikaColors.NeonPink) {
            Text(
                text = "> HARDWARE_RTF_&_LATENCY_SCORECARD",
                color = MikaColors.NeonPink,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Black,
                fontSize = 10.5.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            val maxRtf = maxOf(resultA?.rtf ?: 0.5f, resultB?.rtf ?: 0.5f, 0.25f)
            resultA?.let { res ->
                BenchmarkScoreCard(
                    result = res,
                    maxRtfScale = maxRtf,
                    accent = MikaColors.NeonCyan,
                    onPlay = { viewModel.playBenchmarkResult(res) }
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            resultB?.let { res ->
                BenchmarkScoreCard(
                    result = res,
                    maxRtfScale = maxRtf,
                    accent = MikaColors.NeonPink,
                    onPlay = { viewModel.playBenchmarkResult(res) }
                )
            }
        }
    }
}

@Composable
private fun BenchmarkScoreCard(
    result: TtsForgeViewModel.BenchmarkResult,
    maxRtfScale: Float,
    accent: Color,
    onPlay: () -> Unit
) {
    val speedX = if (result.rtf > 0.001f) 1.0f / result.rtf else 0f
    val barFraction = if (maxRtfScale > 0f) (result.rtf / maxRtfScale).coerceIn(0.06f, 1f) else 0.1f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(MikaColors.DeepWellBg)
            .border(1.dp, accent.copy(alpha = 0.50f), RoundedCornerShape(6.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${result.slotLabel} // ${result.voiceName}",
                    color = accent,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${result.backendBadge} • ${result.synthTimeMs}ms synth / ${"%.2f".format(result.audioDurationMs / 1000f)}s audio",
                    color = MikaColors.PrimaryText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp
                )
            }
            if (result.wavPath.isNotBlank()) {
                MikaTagPill(
                    label = "PLAY",
                    selected = true,
                    activeColor = accent,
                    leadingIcon = CyberIconType.PLAY,
                    onClick = onPlay
                )
            }
        }

        // RTF visual gauge
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(accent.copy(alpha = 0.15f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(barFraction)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(accent)
                )
            }
            Text(
                text = "RTF ${"%.2f".format(result.rtf)} (${"%.1f".format(speedX)}x)",
                color = if (result.rtf <= 1.0f) MikaColors.TerminalGreen else MikaColors.MikaAmber,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Black,
                fontSize = 9.sp
            )
        }
        Text(
            text = result.summary,
            color = MikaColors.DimText,
            fontFamily = FontFamily.Monospace,
            fontSize = 8.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
