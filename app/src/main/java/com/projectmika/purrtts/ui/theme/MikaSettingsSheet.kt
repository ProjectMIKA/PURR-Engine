package com.projectmika.purrtts.ui.theme

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.projectmika.purrtts.core.purr.PURR_KOFI_URL
import com.projectmika.purrtts.core.purr.PURR_REPO_URL

/**
 * ✨ SYSTEM SETTINGS SHEET (v1.5.0) — opens from the header cog beside SET_OS_TTS ✨
 * Links out to the source repository and the Ko-fi support page (both shared with
 * GachaSwipe), replays the M.I.K.A. Academy, and stamps the exact running build.
 *
 * ✨ HARDWARE OFFLOAD (v1.8.3): device-level accelerator toggles live here — GLOBAL forge
 * state that survives vibe-preset switches, under an [EXPERIMENTAL] badge, with room for
 * future GPU/NPU offloads. Per-cartridge switches that ride on separately downloaded
 * sub-cartridge packs (Kokoro Hexagon-NPU / Adreno-GPU chips) stay in the Cartridge Bay —
 * this section is for IN-ENGINE offloads only (currently the Qwen3 Adreno OpenCL one).
 *
 * ✨ APP UPDATE CHECK (v1.8.4): MANUAL ONLY — one tap hits GitHub's `releases/latest`,
 * compares against the running versionName, and (newer?) opens the release page in the
 * user's browser for downloading. No background polling, ever (Master's standing law).
 */
@Composable
fun MikaSettingsSheet(
    versionName: String,
    versionCode: Int,
    updateCheckInFlight: Boolean,
    onCheckForUpdates: () -> Unit,
    qwen3GpuOffload: Boolean,
    onQwen3GpuOffloadToggle: (Boolean) -> Unit,
    onReplayTutorial: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MikaColors.PanelBg)
                .border(1.5.dp, MikaColors.NeonCyan, RoundedCornerShape(14.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MikaCyberIcon(type = CyberIconType.SETTINGS_COG, tint = MikaColors.NeonCyan, size = 15.dp)
                    Text(
                        text = "> SYSTEM_SETTINGS",
                        color = MikaColors.NeonCyan,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Black,
                        fontSize = 13.sp,
                        letterSpacing = 1.0.sp
                    )
                }
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
                text = "// Forge preferences, links & the M.I.K.A. Academy.",
                color = MikaColors.DimText,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp
            )

            SettingsActionRow(
                icon = CyberIconType.BOOK_NARRATOR,
                label = "REPLAY_ACADEMY",
                sub = "Walk through the Forge again — Milk & Wire included.",
                accent = MikaColors.TerminalGreen
            ) {
                onDismiss()
                onReplayTutorial()
            }

            SettingsActionRow(
                icon = CyberIconType.CHIP_EXPANSION,
                label = "SOURCE_REPOSITORY",
                sub = PURR_REPO_URL,
                accent = MikaColors.NeonCyan
            ) {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PURR_REPO_URL)))
                }
            }

            SettingsActionRow(
                icon = CyberIconType.STAR_BADGE,
                label = "SUPPORT_ON_KO_FI",
                sub = PURR_KOFI_URL,
                accent = MikaColors.NeonPurple
            ) {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PURR_KOFI_URL)))
                }
            }

            // ✨ APP UPDATE CHECK (v1.8.4) — manual one-tap check against GitHub's
            // `releases/latest`; a newer version opens the release page in the browser.
            // Deliberately NO background polling (zero silent traffic, zero wake-locks).
            SettingsActionRow(
                icon = CyberIconType.REFRESH,
                label = "CHECK_FOR_UPDATES",
                sub = if (updateCheckInFlight) {
                    "Checking the GitHub Releases feed…"
                } else {
                    "Manual check only — no background polling, ever."
                },
                accent = MikaColors.TerminalGreen
            ) {
                onCheckForUpdates()
            }

            // ✨ HARDWARE OFFLOAD // EXPERIMENTAL (v1.8.3) — the future home for engine-level
            // GPU/NPU accelerator toggles. Per-cartridge accelerator chips (the Kokoro NPU/GPU
            // G2P sub-cartridges) are NOT here: they need separately downloaded model packs
            // and stay configured in the Cartridge Bay rack.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MikaCyberIcon(type = CyberIconType.BOLT_ACCEL, tint = MikaColors.MikaAmber, size = 15.dp)
                    Text(
                        text = "> HARDWARE_OFFLOAD",
                        color = MikaColors.MikaAmber,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Black,
                        fontSize = 12.sp,
                        letterSpacing = 0.8.sp
                    )
                }
                // [EXPERIMENTAL] badge — static chip, never in a pinned slot law region here
                // (the settings sheet scrolls; no fixed-width slot needed).
                Text(
                    text = "[EXPERIMENTAL]",
                    color = MikaColors.MikaAmber,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    fontSize = 9.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(MikaColors.MikaAmber.copy(alpha = 0.14f))
                        .border(1.dp, MikaColors.MikaAmber.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
            Text(
                text = "// Engine accelerators. Per-cartridge NPU/GPU chips stay in the Cartridge Bay.",
                color = MikaColors.DimText,
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp
            )
            SettingsToggleRow(
                title = "Qwen3 GPU Offload (Adreno OpenCL)",
                subtitle = "Runs the Qwen3 talker on your Adreno GPU instead of CPU — big synthesis speedup on Snapdragon flagships. Per-tensor CPU fallback on unsupported devices or quant types.",
                checked = qwen3GpuOffload,
                accent = MikaColors.MikaAmber,
                onToggle = onQwen3GpuOffloadToggle
            )

            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "PURR-TTS v$versionName // BUILD $versionCode",
                color = MikaColors.DimText,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 9.sp
            )
            Text(
                text = "© 2026 ProjectMIKA (Project M.I.K.A.) — AGPL-3.0-or-later. The PURR Engine name lives on in every fork.",
                color = MikaColors.DimText.copy(alpha = 0.7f),
                fontFamily = FontFamily.Monospace,
                fontSize = 8.5.sp,
                lineHeight = 12.sp
            )
        }
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    accent: Color,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(accent.copy(alpha = 0.08f))
            .border(1.dp, accent.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        MikaCyberIcon(type = CyberIconType.BOLT_ACCEL, tint = accent, size = 16.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = if (checked) Color.White else MikaColors.DimText,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Black,
                fontSize = 11.sp,
                letterSpacing = 0.8.sp
            )
            Text(
                text = subtitle,
                color = MikaColors.DimText,
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MikaColors.VoidBg,
                checkedTrackColor = accent,
                uncheckedThumbColor = MikaColors.DimText,
                uncheckedTrackColor = MikaColors.PanelBg,
                uncheckedBorderColor = MikaColors.DimText.copy(alpha = 0.4f)
            )
        )
    }
}

@Composable
private fun SettingsActionRow(
    icon: CyberIconType,
    label: String,
    sub: String,
    accent: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(accent.copy(alpha = 0.08f))
            .border(1.dp, accent.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        MikaCyberIcon(type = icon, tint = accent, size = 16.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = accent,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Black,
                fontSize = 11.sp,
                letterSpacing = 0.8.sp
            )
            Text(
                text = sub,
                color = MikaColors.DimText,
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp
            )
        }
        MikaCyberIcon(type = CyberIconType.CHEVRON_RIGHT, tint = accent.copy(alpha = 0.7f), size = 12.dp)
    }
}
