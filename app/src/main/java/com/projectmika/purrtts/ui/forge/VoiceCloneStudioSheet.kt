package com.projectmika.purrtts.ui.forge

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.projectmika.purrtts.core.purr.SherpaModelFamily
import com.projectmika.purrtts.core.purr.VoiceGender
import com.projectmika.purrtts.ui.theme.CyberIconType
import com.projectmika.purrtts.ui.theme.MikaColors
import com.projectmika.purrtts.ui.theme.MikaCyberIcon
import com.projectmika.purrtts.ui.theme.MikaTagPill

/**
 * ✨ ZERO-SHOT VOICE CLONING STUDIO DIALOG (POCKET TTS / ZIPVOICE) ✨
 * Captures a 5-second 24kHz mono WAV reference via `AudioRecord` (or uses an imported WAV)
 * and registers the zero-shot cloned speaker immediately into the Forge roster.
 */
@Composable
fun VoiceCloneStudioSheet(
    isRecording: Boolean,
    recordingProgress: Float,
    lastRecordedWavPath: String?,
    onRecordFiveSeconds: () -> Unit,
    onRegisterClone: (String, SherpaModelFamily, VoiceGender, String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var displayName by remember { mutableStateOf("MIKA_CLONE_01") }
    var selectedFamily by remember { mutableStateOf(SherpaModelFamily.POCKET_TTS) }
    var selectedGender by remember { mutableStateOf(VoiceGender.FEMALE) }
    var transcript by remember {
        mutableStateOf("Hello Master, this five-second sample captures my natural pitch and cadence.")
    }
    var tagsInput by remember { mutableStateOf("cute, teasing, cloned, american") }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MikaColors.PanelBg)
                .border(1.5.dp, MikaColors.NeonPurple, RoundedCornerShape(14.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "> CLONE_VOICE_STUDIO",
                    color = MikaColors.NeonPurple,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    fontSize = 13.sp,
                    letterSpacing = 1.0.sp
                )
                Text(
                    text = "[CLOSE]",
                    color = MikaColors.DangerRed,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    modifier = Modifier.clickable(onClick = onDismiss)
                )
            }

            Text(
                text = "// Zero-shot 5s voice cloning via sherpa-onnx Pocket TTS or ZipVoice.",
                color = MikaColors.DimText,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(SherpaModelFamily.POCKET_TTS, SherpaModelFamily.ZIPVOICE).forEach { fam ->
                    MikaTagPill(
                        label = fam.label,
                        selected = selectedFamily == fam,
                        activeColor = MikaColors.NeonPurple,
                        onClick = { selectedFamily = fam }
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                VoiceGender.entries.forEach { g ->
                    MikaTagPill(
                        label = g.tag.uppercase(),
                        selected = selectedGender == g,
                        activeColor = MikaColors.NeonCyan,
                        onClick = { selectedGender = g }
                    )
                }
            }

            OutlinedTextField(
                value = displayName,
                onValueChange = { displayName = it },
                label = { Text("VOICE_NAME", fontSize = 9.sp, fontFamily = FontFamily.Monospace) },
                singleLine = true,
                textStyle = TextStyle(color = MikaColors.WarmCream, fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MikaColors.NeonPurple,
                    unfocusedBorderColor = MikaColors.NeonPurple.copy(alpha = 0.35f),
                    focusedContainerColor = MikaColors.DeepWellBg,
                    unfocusedContainerColor = MikaColors.DeepWellBg
                ),
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = transcript,
                onValueChange = { transcript = it },
                label = { Text("REFERENCE_TRANSCRIPT", fontSize = 9.sp, fontFamily = FontFamily.Monospace) },
                minLines = 2,
                textStyle = TextStyle(color = MikaColors.CyanWhite, fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MikaColors.NeonCyan,
                    unfocusedBorderColor = MikaColors.NeonCyan.copy(alpha = 0.35f),
                    focusedContainerColor = MikaColors.DeepWellBg,
                    unfocusedContainerColor = MikaColors.DeepWellBg
                ),
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = tagsInput,
                onValueChange = { tagsInput = it },
                label = { Text("MATCH_TAGS (COMMA_SEP)", fontSize = 9.sp, fontFamily = FontFamily.Monospace) },
                singleLine = true,
                textStyle = TextStyle(color = MikaColors.TerminalGreen, fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MikaColors.TerminalGreen,
                    unfocusedBorderColor = MikaColors.TerminalGreen.copy(alpha = 0.35f),
                    focusedContainerColor = MikaColors.DeepWellBg,
                    unfocusedContainerColor = MikaColors.DeepWellBg
                ),
                modifier = Modifier.fillMaxWidth()
            )

            // 5-Second Reference Capture Control
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(MikaColors.DeepWellBg)
                    .border(1.dp, MikaColors.NeonPink.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isRecording) "RECORDING 24KHZ MONO..." else "5S REFERENCE AUDIO",
                        color = MikaColors.NeonPink,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp
                    )
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MikaColors.NeonPink.copy(alpha = 0.18f))
                            .border(1.dp, MikaColors.NeonPink, RoundedCornerShape(4.dp))
                            .clickable(enabled = !isRecording, onClick = onRecordFiveSeconds)
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        MikaCyberIcon(type = CyberIconType.RECORD_DOT, tint = MikaColors.NeonPink, size = 10.dp)
                        Text(
                            text = if (isRecording) "CAPTURING..." else "REC_5S_WAV",
                            color = MikaColors.NeonPink,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Black,
                            fontSize = 10.sp
                        )
                    }
                }
                if (isRecording || recordingProgress > 0f) {
                    LinearProgressIndicator(
                        progress = { recordingProgress },
                        color = MikaColors.NeonPink,
                        trackColor = MikaColors.NeonPink.copy(alpha = 0.15f),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (!lastRecordedWavPath.isNullOrBlank()) {
                    Text(
                        text = "READY: ${lastRecordedWavPath.substringAfterLast('/')}",
                        color = MikaColors.TerminalGreen,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(2.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(MikaColors.NeonPurple.copy(alpha = 0.22f))
                    .border(1.dp, MikaColors.NeonPurple, RoundedCornerShape(6.dp))
                    .clickable {
                        onRegisterClone(displayName, selectedFamily, selectedGender, transcript, tagsInput)
                        onDismiss()
                    }
                    .padding(vertical = 11.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "[ REGISTER_CLONED_VOICE_TO_FORGE ]",
                    color = MikaColors.WarmCream,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    fontSize = 11.sp,
                    letterSpacing = 0.8.sp
                )
            }
        }
    }
}
