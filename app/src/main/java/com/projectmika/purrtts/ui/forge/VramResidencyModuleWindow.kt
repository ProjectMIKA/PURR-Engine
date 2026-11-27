package com.projectmika.purrtts.ui.forge

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.projectmika.purrtts.core.purr.KeepWarmTimeoutPreset
import com.projectmika.purrtts.core.purr.PurrForgeState
import com.projectmika.purrtts.core.purr.PurrProfile
import com.projectmika.purrtts.core.purr.VramResidencyPolicy
import com.projectmika.purrtts.engine.TtsBackendRouter
import com.projectmika.purrtts.ui.theme.CyberIconType
import com.projectmika.purrtts.ui.theme.MikaColors
import com.projectmika.purrtts.ui.theme.MikaCyberIcon
import com.projectmika.purrtts.ui.theme.MikaGlowPanel
import com.projectmika.purrtts.ui.theme.MikaTagPill

/**
 * ✨ TOOL-06: VRAM & KEEP-WARM GOVERNOR WINDOW ✨
 * Optional OS module (ejected by default) giving power users manual pre-warming, custom
 * Keep-Warm idle timers (0s..∞ Pinned), auto-warm on voice switch, and an optional persistent
 * Android Foreground Service notification that shields large models (Qwen3 1.7B, Chatterbox,
 * Kokoro FP32) from `lmkd`.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VramResidencyModuleWindowBody(
    forgeState: PurrForgeState,
    activeProfile: PurrProfile,
    residency: TtsBackendRouter.ResidencySnapshot,
    viewModel: TtsForgeViewModel
) {
    val context = LocalContext.current
    val config = forgeState.vramResidency
    val (targetLabel, canWarmTarget) = remember(activeProfile, forgeState.modelStates, forgeState.clonedVoices) {
        viewModel.backendRouter.selectedEngineTargetLabel(activeProfile, forgeState)
    }

    val memInfo = remember(residency.residentLabel, residency.isWarming) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val info = ActivityManager.MemoryInfo()
        if (am != null) {
            am.getMemoryInfo(info)
            info
        } else null
    }

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Foreground Service still functions even if the user declines visible notification display;
        // Android simply suppresses the shade card until permission is granted.
        viewModel.setForegroundKeepWarmService(true)
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "> VRAM_&_KEEP_WARM_GOVERNOR // MEMORY LOCK",
            color = MikaColors.SsrGold,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Black,
            fontSize = 11.sp
        )
        Text(
            text = "// Keep heavy weights (Qwen3 1.7B VoiceDesign, Chatterbox, Kokoro FP32) hot in RAM/GPU between replies, or immediately flush after every utterance.",
            color = MikaColors.DimText,
            fontFamily = FontFamily.Monospace,
            fontSize = 9.5.sp,
            lineHeight = 13.sp
        )

        // 1. Live Residency Monitor + Manual Warm / Flush Actions
        MikaGlowPanel(accent = if (residency.residentLabel != null) MikaColors.TerminalGreen else MikaColors.NeonCyan) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    val dotColor = when {
                        residency.isWarming -> MikaColors.MikaAmber
                        residency.residentLabel != null && residency.remainingSeconds < 0 -> MikaColors.SsrGold
                        residency.residentLabel != null -> MikaColors.TerminalGreen
                        else -> MikaColors.DimText
                    }
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(dotColor)
                    )
                    Text(
                        text = when {
                            residency.isWarming -> "MOUNTING: ${residency.warmingLabel ?: targetLabel}…"
                            residency.residentLabel != null -> "HOT: ${residency.residentLabel}"
                            else -> "COLD STANDBY ($targetLabel)"
                        },
                        color = dotColor,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Black,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                val timerBadgeText = when {
                    residency.isWarming -> "LOADING"
                    residency.residentLabel == null -> "UNLOADED"
                    residency.remainingSeconds < 0 -> "PINNED ∞"
                    residency.remainingSeconds > 0 -> "%d:%02d".format(
                        residency.remainingSeconds / 60,
                        residency.remainingSeconds % 60
                    )
                    else -> "EVICT_ON_END"
                }
                Text(
                    text = timerBadgeText,
                    color = MikaColors.SsrGold,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    fontSize = 9.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(MikaColors.SsrGold.copy(alpha = 0.14f))
                        .border(1.dp, MikaColors.SsrGold.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                )
            }

            if (memInfo != null) {
                val availGb = memInfo.availMem / (1024f * 1024f * 1024f)
                val totalGb = memInfo.totalMem / (1024f * 1024f * 1024f)
                val lowMemWarn = if (memInfo.lowMemory) " • LOW_RAM_PRESSURE" else ""
                Text(
                    text = "// System RAM: ${"%.1f".format(availGb)} GB free / ${"%.1f".format(totalGb)} GB total$lowMemWarn",
                    color = if (memInfo.lowMemory) MikaColors.DangerRed else MikaColors.DimText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (canWarmTarget && !residency.isWarming) MikaColors.TerminalGreen.copy(alpha = 0.18f)
                            else MikaColors.DimText.copy(alpha = 0.10f)
                        )
                        .border(
                            1.dp,
                            if (canWarmTarget && !residency.isWarming) MikaColors.TerminalGreen else MikaColors.DimText.copy(alpha = 0.4f),
                            RoundedCornerShape(6.dp)
                        )
                        .clickable(enabled = canWarmTarget && !residency.isWarming) {
                            viewModel.warmSelectedEngine()
                        }
                        .padding(vertical = 9.dp, horizontal = 8.dp)
                ) {
                    Text(
                        text = if (canWarmTarget) "▶ WARM $targetLabel" else "NOT WARMABLE",
                        color = if (canWarmTarget && !residency.isWarming) MikaColors.TerminalGreen else MikaColors.DimText,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Black,
                        fontSize = 9.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (residency.residentLabel != null) MikaColors.DangerRed.copy(alpha = 0.18f)
                            else MikaColors.DimText.copy(alpha = 0.08f)
                        )
                        .border(
                            1.dp,
                            if (residency.residentLabel != null) MikaColors.DangerRed else MikaColors.DimText.copy(alpha = 0.35f),
                            RoundedCornerShape(6.dp)
                        )
                        .clickable(enabled = residency.residentLabel != null && !residency.isWarming) {
                            viewModel.evictAllVramNow()
                        }
                        .padding(vertical = 9.dp, horizontal = 8.dp)
                ) {
                    Text(
                        text = "🧹 FLUSH RAM/VRAM",
                        color = if (residency.residentLabel != null) MikaColors.DangerRed else MikaColors.DimText,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Black,
                        fontSize = 9.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        HorizontalDivider(color = MikaColors.SsrGold.copy(alpha = 0.25f))

        // 2. Keep-Warm Timeout Presets
        Text(
            text = "> IDLE_EVICTION_TIMER",
            color = MikaColors.NeonCyan,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Black,
            fontSize = 10.sp
        )
        Text(
            text = "// Effective Policy: ${VramResidencyPolicy.effectivePolicySummary(config, moduleSlotted = true)}",
            color = MikaColors.CyanWhite.copy(alpha = 0.78f),
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            KeepWarmTimeoutPreset.entries.forEach { preset ->
                val isSelected = if (!config.basicKeepWarmEnabled) {
                    preset == KeepWarmTimeoutPreset.IMMEDIATE_EVICT
                } else {
                    config.moduleTimeoutPreset == preset
                }
                val accent = when (preset) {
                    KeepWarmTimeoutPreset.IMMEDIATE_EVICT -> MikaColors.DangerRed
                    KeepWarmTimeoutPreset.ALWAYS_HOT -> MikaColors.SsrGold
                    else -> MikaColors.NeonCyan
                }
                MikaTagPill(
                    label = preset.displayLabel,
                    selected = isSelected,
                    activeColor = accent,
                    onClick = { viewModel.setKeepWarmTimeoutPreset(preset) }
                )
            }
        }

        HorizontalDivider(color = MikaColors.SsrGold.copy(alpha = 0.25f))

        // 3. Persistent Foreground Notification Switch
        ResidencyToggleCard(
            title = "PERSISTENT_KEEP_WARM_NOTIFICATION",
            subtitle = "Runs a low-priority Foreground Service notification so Android lmkd won't kill resident Qwen3 / Chatterbox weights while you switch back to GachaSwipe or Layla.",
            checked = config.foregroundKeepWarmService,
            accent = MikaColors.SsrGold,
            onCheckedChange = { enable ->
                if (enable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val granted = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                    if (!granted) {
                        notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        return@ResidencyToggleCard
                    }
                }
                viewModel.setForegroundKeepWarmService(enable)
            }
        )

        // 4. Auto-Warm on Voice Switch
        ResidencyToggleCard(
            title = "AUTO_WARM_ON_VOICE_SWITCH",
            subtitle = "Automatically pre-loads the selected neural engine into RAM/GPU the moment you switch engines or voices (great while typing a Qwen 1.7B VoiceDesign prompt).",
            checked = config.autoWarmOnVoiceSelect,
            accent = MikaColors.TerminalGreen,
            onCheckedChange = { viewModel.setAutoWarmOnVoiceSelect(it) }
        )

        Text(
            text = "// Safety Law: Manga Dub Studio & Audiobook Studio always release TTS weights before mounting Vision LLMs or muxing video.",
            color = MikaColors.DimText,
            fontFamily = FontFamily.Monospace,
            fontSize = 8.5.sp,
            lineHeight = 12.sp
        )
    }
}

@Composable
private fun ResidencyToggleCard(
    title: String,
    subtitle: String,
    checked: Boolean,
    accent: Color,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(accent.copy(alpha = if (checked) 0.12f else 0.05f))
            .border(1.dp, accent.copy(alpha = if (checked) 0.72f else 0.35f), RoundedCornerShape(8.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        MikaCyberIcon(type = CyberIconType.BOLT_ACCEL, tint = accent, size = 15.dp)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                color = if (checked) accent else MikaColors.CyanWhite,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Black,
                fontSize = 10.sp
            )
            Text(
                text = subtitle,
                color = MikaColors.DimText,
                fontFamily = FontFamily.Monospace,
                fontSize = 8.5.sp,
                lineHeight = 12.sp
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = accent,
                checkedTrackColor = accent.copy(alpha = 0.28f),
                uncheckedThumbColor = MikaColors.DimText,
                uncheckedTrackColor = MikaColors.DeepWellBg
            )
        )
    }
}
