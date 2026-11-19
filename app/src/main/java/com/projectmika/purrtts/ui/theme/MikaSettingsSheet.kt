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
 */
@Composable
fun MikaSettingsSheet(
    versionName: String,
    versionCode: Int,
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

            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "PURR-TTS v$versionName // BUILD $versionCode",
                color = MikaColors.DimText,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 9.sp
            )
            Text(
                text = "© 2026 ProjectMIKA (Project M.I.K.A.) — MIT License. The PURR Engine name lives on in every fork.",
                color = MikaColors.DimText.copy(alpha = 0.7f),
                fontFamily = FontFamily.Monospace,
                fontSize = 8.5.sp,
                lineHeight = 12.sp
            )
        }
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
