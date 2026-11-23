package com.projectmika.purrtts.ui.forge

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.projectmika.purrtts.core.purr.SherpaModelFamily
import com.projectmika.purrtts.core.purr.VoiceGender
import com.projectmika.purrtts.ui.theme.CyberDropdown
import com.projectmika.purrtts.ui.theme.CyberIconType
import com.projectmika.purrtts.ui.theme.MikaColors
import com.projectmika.purrtts.ui.theme.MikaCyberIcon
import com.projectmika.purrtts.ui.theme.MikaTagPill

/**
 * ✨ ZERO-SHOT VOICE CLONING STUDIO DIALOG (ROM-03 CLONE HUB + ROM-04 QWEN3 BASE) ✨
 * Supports all 6 local zero-shot cloning engines:
 * Pocket INT8 / FP32, ZipVoice INT8 / Full, Chatterbox-Turbo 350M & Qwen3-TTS 0.6B Base.
 */
private data class CloneEngineOption(
    val id: String,
    val label: String,
    val family: SherpaModelFamily,
    val windowSec: Int,
    val badge: String
)

private val CLONE_ENGINE_OPTIONS = listOf(
    CloneEngineOption("rom_pocket_tts", "Pocket INT8 (5s)", SherpaModelFamily.POCKET_TTS, 5, "FAST INT8"),
    CloneEngineOption("sub_pocket_fp32", "Pocket FP32 (5s)", SherpaModelFamily.POCKET_TTS, 5, "STUDIO FP32"),
    CloneEngineOption("rom_zipvoice", "ZipVoice INT8 (12s)", SherpaModelFamily.ZIPVOICE, 12, "FLOW INT8"),
    CloneEngineOption("sub_zipvoice_full", "ZipVoice Full (12s)", SherpaModelFamily.ZIPVOICE, 12, "FLOW FULL"),
    CloneEngineOption("sub_chatterbox_turbo", "Chatterbox 350M (12s)", SherpaModelFamily.POCKET_TTS, 12, "PARALINGUISTIC"),
    CloneEngineOption("sub_qwen3_base_06b", "Qwen3 0.6B Base (12s)", SherpaModelFamily.ZIPVOICE, 12, "10-LANG 3S")
)

@Composable
fun VoiceCloneStudioSheet(
    installedEngineIds: Set<String>,
    isRecording: Boolean,
    recordingProgress: Float,
    lastRecordedWavPath: String?,
    isImporting: Boolean,
    importStatus: String?,
    importedWavPath: String?,
    cleanAudio: Boolean,
    onToggleCleanAudio: (Boolean) -> Unit,
    onImportClip: (Uri) -> Unit,
    onRecordFiveSeconds: () -> Unit,
    onRegisterClone: (String, SherpaModelFamily, VoiceGender, String, String, String) -> Boolean,
    onDismiss: () -> Unit
) {
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let(onImportClip) }
    var displayName by remember { mutableStateOf("MIKA_CLONE_01") }
    var selectedEngine by remember {
        mutableStateOf(
            CLONE_ENGINE_OPTIONS.firstOrNull { it.id in installedEngineIds }
                ?: CLONE_ENGINE_OPTIONS.first()
        )
    }
    var selectedGender by remember { mutableStateOf(VoiceGender.FEMALE) }
    var transcript by remember {
        mutableStateOf("Hello Master, this reference sample captures my natural pitch and cadence.")
    }
    var tagsInput by remember { mutableStateOf("cute, teasing, cloned, american") }
    val selectedEngineReady = selectedEngine.id in installedEngineIds
    val hasReferenceAudio = !lastRecordedWavPath.isNullOrBlank() ||
        !importedWavPath.isNullOrBlank() ||
        (importStatus?.startsWith("READY:") == true)

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MikaColors.PanelBg)
                .border(1.5.dp, MikaColors.NeonPurple, RoundedCornerShape(14.dp))
                .imePadding()
                .verticalScroll(rememberScrollState())
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
                text = "// Zero-shot voice cloning across Pocket, ZipVoice, Chatterbox-Turbo & Qwen3 Base.",
                color = MikaColors.DimText,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp
            )

            CyberDropdown(
                label = "> CLONE_ENGINE_TARGET",
                options = CLONE_ENGINE_OPTIONS,
                selectedOption = selectedEngine,
                optionLabel = { opt ->
                    val statusTag = if (opt.id in installedEngineIds) "[READY]" else "[INSTALL IN BAY]"
                    "${opt.label} • ${opt.badge} $statusTag"
                },
                onSelect = { selectedEngine = it },
                accent = MikaColors.NeonPurple
            )

            if (!selectedEngineReady) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(MikaColors.MikaAmber.copy(alpha = 0.14f))
                        .border(1.dp, MikaColors.MikaAmber.copy(alpha = 0.7f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MikaCyberIcon(type = CyberIconType.ALERT_TRIANGLE, tint = MikaColors.MikaAmber, size = 13.dp)
                    Text(
                        text = "${selectedEngine.label} isn't downloaded yet! Grab it in CARTRIDGE BAY > CLONING so cloned speech uses the real engine instead of failing.",
                        color = MikaColors.MikaAmber,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 8.5.sp,
                        lineHeight = 12.sp,
                        modifier = Modifier.weight(1f)
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

            // ✨ CLIP IMPORT + CLEAN AUDIO (v1.4.0)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(MikaColors.DeepWellBg)
                    .border(1.dp, MikaColors.NeonCyan.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "IMPORT_AUDIO_CLIP",
                            color = MikaColors.NeonCyan,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "// MP3 M4A OGG FLAC WAV • ≤30s",
                            color = MikaColors.DimText,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 8.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MikaColors.NeonCyan.copy(alpha = 0.18f))
                            .border(1.dp, MikaColors.NeonCyan, RoundedCornerShape(4.dp))
                            .clickable(enabled = !isImporting) {
                                importLauncher.launch(arrayOf("audio/*"))
                            }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        MikaCyberIcon(type = CyberIconType.DOWNLOAD, tint = MikaColors.NeonCyan, size = 10.dp)
                        Text(
                            text = if (isImporting) "DECODING" else "IMPORT_CLIP",
                            color = MikaColors.NeonCyan,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Black,
                            fontSize = 10.sp,
                            maxLines = 1
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "CLEAN_AUDIO // DE_NOISE + DE_STATIC",
                            color = MikaColors.TerminalGreen,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (cleanAudio) {
                                "// RECOMMENDED — strips static & hum"
                            } else {
                                "// OFF — keeps the clip raw"
                            },
                            color = MikaColors.DimText,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 8.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MikaColors.TerminalGreen.copy(alpha = if (cleanAudio) 0.22f else 0.08f))
                            .border(
                                1.dp,
                                if (cleanAudio) MikaColors.TerminalGreen else MikaColors.DimText.copy(alpha = 0.5f),
                                RoundedCornerShape(4.dp)
                            )
                            .clickable { onToggleCleanAudio(!cleanAudio) }
                            .padding(horizontal = 12.dp, vertical = 7.dp)
                    ) {
                        Text(
                            text = if (cleanAudio) "ON" else "OFF",
                            color = if (cleanAudio) MikaColors.TerminalGreen else MikaColors.DimText,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Black,
                            fontSize = 10.sp,
                            maxLines = 1
                        )
                    }
                }

                if (importStatus != null) {
                    Text(
                        text = importStatus,
                        color = if (importStatus.startsWith("READY")) MikaColors.TerminalGreen else MikaColors.NeonCyan,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp
                    )
                }
                if (!importedWavPath.isNullOrBlank()) {
                    Text(
                        text = "READY: ${importedWavPath.substringAfterLast('/')}",
                        color = MikaColors.TerminalGreen,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp
                    )
                }
            }

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
                    .background(
                        if (hasReferenceAudio) MikaColors.NeonPurple.copy(alpha = 0.22f)
                        else MikaColors.MikaAmber.copy(alpha = 0.12f)
                    )
                    .border(
                        1.dp,
                        if (hasReferenceAudio) MikaColors.NeonPurple else MikaColors.MikaAmber.copy(alpha = 0.6f),
                        RoundedCornerShape(6.dp)
                    )
                    .clickable {
                        val registered = onRegisterClone(
                            displayName,
                            selectedEngine.family,
                            selectedGender,
                            transcript,
                            tagsInput,
                            selectedEngine.id
                        )
                        if (registered) onDismiss()
                    }
                    .padding(vertical = 11.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (hasReferenceAudio) "[ REGISTER_CLONED_VOICE_TO_FORGE ]" else "[ RECORD OR IMPORT AUDIO FIRST ]",
                    color = if (hasReferenceAudio) MikaColors.WarmCream else MikaColors.MikaAmber,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    fontSize = 11.sp,
                    letterSpacing = 0.8.sp
                )
            }
        }
    }
}
