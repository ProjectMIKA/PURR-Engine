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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.projectmika.purrtts.core.purr.PurrProfile
import com.projectmika.purrtts.core.purr.PurrSrtFormatter
import com.projectmika.purrtts.ui.theme.CyberIconType
import com.projectmika.purrtts.ui.theme.MikaColors
import com.projectmika.purrtts.ui.theme.MikaCyberIcon
import com.projectmika.purrtts.ui.theme.MikaGlowPanel
import com.projectmika.purrtts.ui.theme.MikaTagPill
import com.projectmika.purrtts.ui.theme.bringIntoViewWithIme
import kotlin.math.roundToInt

/**
 * ✨ TOOL-10: SRT SUBTITLE & BATCH QUEUE GENERATOR OS MODULE ✨
 * Synthesizes multi-line voiceover scripts cue-by-cue while tracking exact sample offsets,
 * generating synced `.SRT` & `.VTT` caption tracks alongside a mastered `.WAV` audio track.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SrtBatchStudioModuleWindowBody(
    activeProfile: PurrProfile,
    isPlayingPreview: Boolean,
    viewModel: TtsForgeViewModel
) {
    val isBatchRunning by viewModel.isRunningSrtBatch.collectAsState()
    val batchProgress by viewModel.srtBatchProgress.collectAsState()
    val batchStatus by viewModel.srtBatchStatus.collectAsState()
    val batchOutput by viewModel.srtBatchOutput.collectAsState()
    val clipboard = LocalClipboardManager.current

    var captionTitle by remember { mutableStateOf("PURR_Voiceover_Cut01") }
    var batchInput by remember {
        mutableStateOf(
            """
            Welcome to the PURR Engine studio showcase.
            Every single neural voiceover line is rendered locally offline.
            Start and end timestamps align cleanly to 24kHz audio samples.
            Ready to drop straight into DaVinci Resolve, CapCut, or OBS!
            """.trimIndent()
        )
    }
    var splitSentences by remember { mutableStateOf(false) }
    var interCueGapMs by remember { mutableIntStateOf(300) }
    var normalizeLoudness by remember { mutableStateOf(true) }
    var showVtt by remember { mutableStateOf(false) }

    val parsedCues = remember(batchInput, splitSentences) {
        PurrSrtFormatter.parseCueLines(batchInput, splitSentences = splitSentences)
    }

    MikaGlowPanel(accent = MikaColors.SsrGold) {
        Text(
            text = "> SRT_SUBTITLE_&_BATCH_QUEUE_STUDIO",
            color = MikaColors.SsrGold,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Black,
            fontSize = 11.sp
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Synthesize queued voiceover cues in batch and auto-generate sample-accurate .SRT & .VTT subtitles + a mastered .WAV file in Downloads/PURR-TTS/.",
            color = MikaColors.CyanWhite.copy(alpha = 0.78f),
            fontFamily = FontFamily.Monospace,
            fontSize = 9.5.sp,
            lineHeight = 13.5.sp
        )
        Spacer(modifier = Modifier.height(6.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            MikaTagPill(
                label = "CUES: ${parsedCues.size}",
                selected = parsedCues.isNotEmpty(),
                activeColor = MikaColors.SsrGold,
                onClick = {}
            )
            MikaTagPill(
                label = "VOICE: ${activeProfile.primaryVoiceId.substringAfter(':').take(16)}",
                selected = true,
                activeColor = MikaColors.NeonCyan,
                onClick = {}
            )
        }
    }

    MikaGlowPanel(accent = MikaColors.NeonCyan) {
        OutlinedTextField(
            value = captionTitle,
            onValueChange = { captionTitle = it },
            label = { Text("OUTPUT_BASENAME (.WAV / .SRT / .VTT)", fontSize = 9.sp, fontFamily = FontFamily.Monospace) },
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
            value = batchInput,
            onValueChange = { batchInput = it },
            label = { Text("BATCH_CUES (1 LINE PER SUBTITLE CUE)", fontSize = 9.sp, fontFamily = FontFamily.Monospace) },
            minLines = 5,
            maxLines = 11,
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
        SimplePurrToggle(
            title = "Split Long Lines by Sentence",
            subtitle = "Splits paragraphs automatically at sentence punctuation before queueing captions.",
            checked = splitSentences,
            accent = MikaColors.NeonCyan,
            icon = CyberIconType.SHIELD_GUARD,
            onToggle = { splitSentences = it }
        )
        SimplePurrToggle(
            title = "Level Cue Loudness (-18 dBFS RMS)",
            subtitle = "Matches loudness cleanly across every subtitle cue + soft master limiter.",
            checked = normalizeLoudness,
            accent = MikaColors.TerminalGreen,
            icon = CyberIconType.SLIDER_FADER,
            onToggle = { normalizeLoudness = it }
        )
        TerminalSlider(
            label = "INTER_CUE_SILENCE_MS",
            valueText = "$interCueGapMs ms",
            value = interCueGapMs.toFloat(),
            valueRange = 0f..1000f,
            accent = MikaColors.SsrGold,
            onValueChange = { interCueGapMs = (it.roundToInt() / 25) * 25 }
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (isBatchRunning) {
            LinearProgressIndicator(
                progress = { batchProgress },
                color = MikaColors.SsrGold,
                trackColor = MikaColors.SsrGold.copy(alpha = 0.18f),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = batchStatus,
                    color = MikaColors.SsrGold,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.5.sp,
                    modifier = Modifier.weight(1f)
                )
                MikaTagPill(
                    label = "CANCEL",
                    selected = true,
                    activeColor = MikaColors.DangerRed,
                    leadingIcon = CyberIconType.CLOSE,
                    onClick = { viewModel.cancelSrtBatch() }
                )
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(MikaColors.SsrGold.copy(alpha = 0.22f))
                    .border(1.5.dp, MikaColors.SsrGold, RoundedCornerShape(6.dp))
                    .clickable {
                        viewModel.renderSrtBatchQueue(
                            title = captionTitle,
                            cues = parsedCues,
                            interCueGapMs = interCueGapMs,
                            normalizeLoudness = normalizeLoudness
                        )
                    }
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                MikaCyberIcon(type = CyberIconType.DOWNLOAD, tint = MikaColors.SsrGold, size = 14.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "BATCH SYNTH + EXPORT .WAV & .SRT/.VTT",
                    color = MikaColors.SsrGold,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    fontSize = 10.sp
                )
            }
        }
    }

    batchOutput?.let { out ->
        MikaGlowPanel(accent = MikaColors.TerminalGreen) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "> GENERATED_TIMECODES (${out.cueCount} CUES • ${"%.1f".format(out.totalDurationMs / 1000f)}s)",
                    color = MikaColors.TerminalGreen,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    fontSize = 10.sp,
                    modifier = Modifier.weight(1f)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    MikaTagPill(
                        label = ".SRT",
                        selected = !showVtt,
                        activeColor = MikaColors.SsrGold,
                        onClick = { showVtt = false }
                    )
                    MikaTagPill(
                        label = ".VTT",
                        selected = showVtt,
                        activeColor = MikaColors.NeonCyan,
                        onClick = { showVtt = true }
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            val activeCaptionText = if (showVtt) out.vttText else out.srtText
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                out.wavFile?.let { wav ->
                    MikaTagPill(
                        label = if (isPlayingPreview) "STOP WAV" else "PLAY WAV",
                        selected = true,
                        activeColor = MikaColors.TerminalGreen,
                        leadingIcon = if (isPlayingPreview) CyberIconType.STOP else CyberIconType.PLAY,
                        onClick = {
                            if (isPlayingPreview) viewModel.stopAudition()
                            else viewModel.playExportedAudioFile(wav)
                        }
                    )
                }
                MikaTagPill(
                    label = if (showVtt) "COPY .VTT" else "COPY .SRT",
                    selected = true,
                    activeColor = MikaColors.NeonCyan,
                    onClick = {
                        clipboard.setText(AnnotatedString(activeCaptionText))
                        viewModel.showToast("Copied ${if (showVtt) ".VTT" else ".SRT"} captions to clipboard!")
                    }
                )
            }
            if (out.srtDownloadPath.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "SAVED: ${out.srtDownloadPath} & ${out.vttDownloadPath}",
                    color = MikaColors.TerminalGreen,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 8.5.sp
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(MikaColors.DeepWellBg)
                    .border(1.dp, MikaColors.TerminalGreen.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                    .padding(8.dp)
            ) {
                Text(
                    text = activeCaptionText.trim().take(1200),
                    color = MikaColors.CyanWhite,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    lineHeight = 13.sp
                )
            }
        }
    }
}
