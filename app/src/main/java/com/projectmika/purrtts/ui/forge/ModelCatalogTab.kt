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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.projectmika.purrtts.core.purr.BackendType
import com.projectmika.purrtts.core.purr.DownloadState
import com.projectmika.purrtts.core.purr.ModelBundleSpec
import com.projectmika.purrtts.core.purr.ModelBundleState
import com.projectmika.purrtts.core.purr.PurrForgeState
import com.projectmika.purrtts.ui.theme.MikaColors
import com.projectmika.purrtts.ui.theme.MikaGlowPanel
import com.projectmika.purrtts.ui.theme.MikaTagPill

/**
 * ✨ MODEL & VOICE CATALOG MANAGER TAB ✨
 * Manages Hugging Face (.litertlm / .tflite) & GitHub sherpa-onnx (.tar.bz2) resumable
 * downloads with SHA-256 verification, 5-minute VRAM eviction manual trigger, Voice Clone
 * studio modal launcher, and External LAN OpenAI / OS TTS Proxy routing.
 */
@Composable
fun ModelCatalogTab(
    forgeState: PurrForgeState,
    modelCatalog: List<ModelBundleSpec>,
    vramRemainingSec: Int,
    viewModel: TtsForgeViewModel,
    onOpenCloneStudio: () -> Unit
) {
    val activeProfile = forgeState.profiles.firstOrNull { it.id == forgeState.activeProfileId }
        ?: forgeState.profiles.first()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {

        // 1. Hardware Cache & VRAM Guard + Zero-Shot Clone Studio Launcher
        MikaGlowPanel(accent = MikaColors.NeonCyan) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "> HARDWARE_RUNTIME_GOVERNOR",
                        color = MikaColors.NeonCyan,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Black,
                        fontSize = 11.sp
                    )
                    Text(
                        text = if (vramRemainingSec > 0) {
                            "Idle VRAM eviction timer: ${vramRemainingSec}s remaining (lmkd protection)"
                        } else {
                            "GPU FP16 & NPU buffers currently evicted (0 MB resident)"
                        },
                        color = MikaColors.DimText,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(MikaColors.DangerRed.copy(alpha = 0.14f))
                        .border(1.dp, MikaColors.DangerRed, RoundedCornerShape(4.dp))
                        .clickable { viewModel.evictAllVramNow() }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "EVICT_VRAM",
                        color = MikaColors.DangerRed,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Black,
                        fontSize = 10.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Clone Voice Studio Card Trigger
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(MikaColors.NeonPurple.copy(alpha = 0.12f))
                    .border(1.dp, MikaColors.NeonPurple, RoundedCornerShape(6.dp))
                    .clickable(onClick = onOpenCloneStudio)
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "🧬 CLONE_VOICE_STUDIO (POCKET_TTS / ZIPVOICE)",
                        color = MikaColors.NeonPurple,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Black,
                        fontSize = 11.sp
                    )
                    Text(
                        text = "Record 5s reference audio -> zero-shot cloned voice in ${forgeState.clonedVoices.size} active slots",
                        color = MikaColors.CyanWhite,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp
                    )
                }
                Text(
                    text = "[ OPEN ]",
                    color = MikaColors.NeonPurple,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    fontSize = 10.sp
                )
            }
        }

        // 2. Neural Model Catalog (Hugging Face LiteRT-LM + GitHub sherpa-onnx releases)
        modelCatalog.forEach { spec ->
            val bundleState = forgeState.modelStates[spec.id] ?: ModelBundleState(specId = spec.id)
            val cardAccent = when (spec.backend) {
                BackendType.LITERT_NPU_GPU -> MikaColors.TerminalGreen
                BackendType.SHERPA_ONNX -> MikaColors.NeonCyan
                BackendType.EXTERNAL_PROXY -> MikaColors.NeonPurple
            }

            MikaGlowPanel(accent = cardAccent) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = spec.name,
                            color = MikaColors.WarmCream,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                        Text(
                            text = spec.subtitle,
                            color = MikaColors.CyanWhite.copy(alpha = 0.8f),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp
                        )
                    }
                    MikaTagPill(
                        label = "${spec.approximateSizeMb}MB",
                        selected = bundleState.state == DownloadState.INSTALLED,
                        activeColor = cardAccent,
                        onClick = {}
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "ACCEL: ${spec.hardwareAccelNote}",
                    color = cardAccent.copy(alpha = 0.85f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp
                )
                Text(
                    text = "SHA256: ${spec.expectedSha256.take(24)}...",
                    color = MikaColors.DimText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp
                )

                if (bundleState.state == DownloadState.DOWNLOADING ||
                    bundleState.state == DownloadState.VERIFYING ||
                    bundleState.state == DownloadState.EXTRACTING
                ) {
                    Spacer(modifier = Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { bundleState.progressFraction },
                        color = cardAccent,
                        trackColor = cardAccent.copy(alpha = 0.18f),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = when (bundleState.state) {
                            DownloadState.VERIFYING -> "VERIFYING SHA-256 CHECKSUM..."
                            DownloadState.EXTRACTING -> "EXTRACTING .TAR.BZ2 MODEL BUNDLE..."
                            else -> "DOWNLOADING ${(bundleState.progressFraction * 100).toInt()}% (${bundleState.downloadedBytes / (1024 * 1024)} / ${spec.approximateSizeMb} MB)"
                        },
                        color = cardAccent,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                if (!bundleState.errorMessage.isNullOrBlank()) {
                    Text(
                        text = "! ${bundleState.errorMessage}",
                        color = MikaColors.DangerRed,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    when (bundleState.state) {
                        DownloadState.DOWNLOADING -> {
                            MikaTagPill(
                                label = "PAUSE",
                                selected = true,
                                activeColor = MikaColors.MikaAmber,
                                onClick = { viewModel.pauseModelDownload(spec.id) }
                            )
                        }
                        DownloadState.INSTALLED -> {
                            MikaTagPill(
                                label = if (bundleState.sha256Verified) "✓ SHA256_OK" else "VERIFY_SHA256",
                                selected = true,
                                activeColor = MikaColors.TerminalGreen,
                                onClick = { viewModel.verifyModelChecksum(spec) }
                            )
                            MikaTagPill(
                                label = "DELETE",
                                selected = true,
                                activeColor = MikaColors.DangerRed,
                                onClick = { viewModel.deleteModelBundle(spec) }
                            )
                        }
                        else -> {
                            MikaTagPill(
                                label = if (bundleState.downloadedBytes > 0L) "RESUME_DOWNLOAD" else "DOWNLOAD_BUNDLE",
                                selected = true,
                                activeColor = cardAccent,
                                onClick = { viewModel.startModelDownload(spec) }
                            )
                        }
                    }
                }
            }
        }

        // 3. Fallback / Passthrough Proxy Configuration Card
        var proxyUrl by remember(activeProfile.id) { mutableStateOf(activeProfile.proxyConfig.openAiBaseUrl) }
        var proxyModel by remember(activeProfile.id) { mutableStateOf(activeProfile.proxyConfig.openAiModel) }
        var fallbackPkg by remember(activeProfile.id) { mutableStateOf(activeProfile.proxyConfig.fallbackSystemEnginePackage) }

        MikaGlowPanel(accent = MikaColors.NeonPurple) {
            Text(
                text = "> PASSTHROUGH_PROXY_CONFIG (LAN / OS_TTS)",
                color = MikaColors.NeonPurple,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Black,
                fontSize = 11.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "USE_LAN_OPENAI_ENDPOINT",
                    color = MikaColors.CyanWhite,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp
                )
                Switch(
                    checked = activeProfile.proxyConfig.useOpenAiEndpoint,
                    onCheckedChange = { useOpenAi ->
                        viewModel.updateActiveProfile {
                            it.copy(proxyConfig = it.proxyConfig.copy(useOpenAiEndpoint = useOpenAi))
                        }
                    },
                    colors = SwitchDefaults.colors(checkedThumbColor = MikaColors.VoidBg, checkedTrackColor = MikaColors.NeonPurple)
                )
            }
            OutlinedTextField(
                value = proxyUrl,
                onValueChange = {
                    proxyUrl = it
                    viewModel.updateActiveProfile { p -> p.copy(proxyConfig = p.proxyConfig.copy(openAiBaseUrl = it)) }
                },
                label = { Text("OPENAI_TTS_URL", fontSize = 9.sp, fontFamily = FontFamily.Monospace) },
                singleLine = true,
                textStyle = TextStyle(color = MikaColors.CyanWhite, fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MikaColors.NeonPurple,
                    unfocusedBorderColor = MikaColors.NeonPurple.copy(alpha = 0.3f),
                    focusedContainerColor = MikaColors.DeepWellBg,
                    unfocusedContainerColor = MikaColors.DeepWellBg
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = proxyModel,
                    onValueChange = {
                        proxyModel = it
                        viewModel.updateActiveProfile { p -> p.copy(proxyConfig = p.proxyConfig.copy(openAiModel = it)) }
                    },
                    label = { Text("MODEL", fontSize = 9.sp, fontFamily = FontFamily.Monospace) },
                    singleLine = true,
                    textStyle = TextStyle(color = MikaColors.CyanWhite, fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MikaColors.NeonPurple,
                        unfocusedBorderColor = MikaColors.NeonPurple.copy(alpha = 0.3f),
                        focusedContainerColor = MikaColors.DeepWellBg,
                        unfocusedContainerColor = MikaColors.DeepWellBg
                    ),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = fallbackPkg,
                    onValueChange = {
                        fallbackPkg = it
                        viewModel.updateActiveProfile { p -> p.copy(proxyConfig = p.proxyConfig.copy(fallbackSystemEnginePackage = it)) }
                    },
                    label = { Text("OS_FALLBACK_PKG", fontSize = 9.sp, fontFamily = FontFamily.Monospace) },
                    singleLine = true,
                    textStyle = TextStyle(color = MikaColors.CyanWhite, fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MikaColors.NeonPurple,
                        unfocusedBorderColor = MikaColors.NeonPurple.copy(alpha = 0.3f),
                        focusedContainerColor = MikaColors.DeepWellBg,
                        unfocusedContainerColor = MikaColors.DeepWellBg
                    ),
                    modifier = Modifier.weight(1.4f)
                )
            }
        }
    }
}
