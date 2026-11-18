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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.projectmika.purrtts.core.purr.DownloadState
import com.projectmika.purrtts.core.purr.ModelBundleSpec
import com.projectmika.purrtts.core.purr.ModelBundleState
import com.projectmika.purrtts.core.purr.PurrForgeState
import com.projectmika.purrtts.ui.theme.MikaColors
import com.projectmika.purrtts.ui.theme.MikaGlowPanel
import com.projectmika.purrtts.ui.theme.MikaTagPill

/**
 * ✨ TAB 3: RETRO CYBERDECK ROM CARTRIDGE BAY (MODEL CATALOG) ✨
 * Mirrors GachaSwipe's `> EXPANSION_BAY // ROM_CARTRIDGES` design:
 * - 6-Bay Cyberdeck Motherboard Pin Rack Visualizer
 * - Physical Molded ROM Cartridge Cards (`ROM-01`..`ROM-06`)
 * - Download bundle -> Slam Cartridge In (mounts voices) or Eject Cartridge (evicts & hides)
 */
@Composable
fun ModelCatalogTab(
    forgeState: PurrForgeState,
    modelCatalog: List<ModelBundleSpec>,
    vramRemainingSec: Int,
    viewModel: TtsForgeViewModel,
    onJumpToVoiceSelector: (String) -> Unit,
    onOpenCloneStudio: () -> Unit
) {
    val slottedCount = modelCatalog.count { it.id in forgeState.slottedCartridges }
    val busLoadPct = if (modelCatalog.isNotEmpty()) (slottedCount * 100) / modelCatalog.size else 0

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {

        // 1. Cyberdeck 6-Bay Motherboard Pin Rack Visualizer
        MikaGlowPanel(accent = MikaColors.NeonCyan) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "🔌 MOTHERBOARD_BUS // 6-BAY RACK",
                    color = MikaColors.NeonCyan,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    fontSize = 10.sp,
                    letterSpacing = 0.8.sp
                )
                Text(
                    text = "[ $slottedCount / ${modelCatalog.size} SLOTTED • $busLoadPct% ]",
                    color = if (slottedCount > 0) MikaColors.TerminalGreen else MikaColors.DimText,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.sp
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 6 Interactive Mini Bay Slots (01..06)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF040207))
                    .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                modelCatalog.forEachIndexed { idx, spec ->
                    val isInstalled = forgeState.modelStates[spec.id]?.state == DownloadState.INSTALLED
                    val isSlotted = spec.id in forgeState.slottedCartridges
                    val accent = Color(spec.accentHex)

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                if (isSlotted) accent.copy(alpha = 0.22f)
                                else Color.White.copy(alpha = 0.03f)
                            )
                            .border(
                                width = 1.dp,
                                color = if (isSlotted) accent else if (isInstalled) accent.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.14f),
                                shape = RoundedCornerShape(4.dp)
                            )
                            .clickable {
                                if (isInstalled) {
                                    viewModel.toggleCartridgeSlotted(spec, !isSlotted)
                                } else {
                                    viewModel.showToast("${spec.romCode}: Tap DOWNLOAD below first!")
                                }
                            }
                            .padding(vertical = 4.dp, horizontal = 2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Box(
                            modifier = Modifier
                                .width(10.dp)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(if (isSlotted) accent else Color.White.copy(alpha = 0.15f))
                        )
                        Text(
                            text = "0${idx + 1}",
                            color = if (isSlotted) Color.White else Color.White.copy(alpha = 0.35f),
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Black,
                            fontSize = 8.sp
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.7f)
                                .height(3.dp)
                                .background(if (isSlotted) MikaColors.SsrGold else Color.White.copy(alpha = 0.08f))
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "// CORE_OS (Built-in Device Speech) is soldered to the motherboard. Download & slam offline ROM Cartridges below to unlock Kokoro-82M, Supertonic 3, Piper, & Voice Cloning!",
                color = MikaColors.DimText,
                fontFamily = FontFamily.Monospace,
                fontSize = 9.5.sp,
                lineHeight = 14.sp
            )

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (vramRemainingSec > 0) "VRAM Guard: ${vramRemainingSec}s" else "VRAM: 0 MB resident",
                    color = MikaColors.DimText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MikaTagPill(
                        label = "🧬 CLONE STUDIO",
                        selected = true,
                        activeColor = MikaColors.NeonPurple,
                        onClick = onOpenCloneStudio
                    )
                    MikaTagPill(
                        label = "EVICT_VRAM",
                        selected = true,
                        activeColor = MikaColors.DangerRed,
                        onClick = { viewModel.evictAllVramNow() }
                    )
                }
            }
        }

        // 2. Physical Molded ROM Cartridge Cards
        modelCatalog.forEach { spec ->
            val bundleState = forgeState.modelStates[spec.id] ?: ModelBundleState(specId = spec.id)
            val isInstalled = bundleState.state == DownloadState.INSTALLED
            val isSlotted = isInstalled && spec.id in forgeState.slottedCartridges
            val accent = Color(spec.accentHex)

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Molded Cartridge Chassis
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp, bottomStart = 3.dp, bottomEnd = 3.dp))
                        .background(
                            Brush.verticalGradient(
                                if (isSlotted) listOf(Color(0xFF27223A), Color(0xFF171326), Color(0xFF100D1C))
                                else listOf(Color(0xFF171422), Color(0xFF0E0C16))
                            )
                        )
                        .border(
                            width = if (isSlotted) 2.dp else 1.5.dp,
                            color = if (isSlotted) accent else Color.White.copy(alpha = 0.16f),
                            shape = RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp, bottomStart = 3.dp, bottomEnd = 3.dp)
                        )
                        .padding(start = 12.dp, end = 12.dp, top = 10.dp)
                ) {
                    // Grip Ridges + Screw Boss
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF0A0812))
                                .border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("⊕", color = Color.White.copy(alpha = 0.3f), fontSize = 8.sp)
                        }
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Box(modifier = Modifier.fillMaxWidth().height(2.dp).background(if (isSlotted) accent.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.12f)))
                            Box(modifier = Modifier.fillMaxWidth().height(2.dp).background(if (isSlotted) accent.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.08f)))
                            Box(modifier = Modifier.fillMaxWidth().height(2.dp).background(if (isSlotted) accent.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.12f)))
                        }
                        Text(
                            text = "M.I.K.A.-ROM",
                            color = if (isSlotted) accent else Color.White.copy(alpha = 0.3f),
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Black,
                            fontSize = 8.sp,
                            letterSpacing = 1.0.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Recessed Glossy Cartridge Sticker Label
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isSlotted) accent.copy(alpha = 0.12f) else Color(0xFF08060E))
                            .border(
                                1.dp,
                                if (isSlotted) accent else Color.White.copy(alpha = 0.14f),
                                RoundedCornerShape(6.dp)
                            )
                    ) {
                        // Metallic Top Header Band
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(if (isSlotted) accent.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.07f))
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = spec.romCode,
                                    color = if (isSlotted) MikaColors.VoidBg else Color.White.copy(alpha = 0.7f),
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Black,
                                    fontSize = 10.sp,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(if (isSlotted) Color.White.copy(alpha = 0.9f) else Color.Black.copy(alpha = 0.4f))
                                        .padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                                Text(
                                    text = "// ${spec.badge} • ${spec.approximateSizeMb}MB",
                                    color = if (isSlotted) MikaColors.VoidBg else Color.White.copy(alpha = 0.55f),
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 9.sp
                                )
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(Color(0xFF07050C))
                                    .border(1.dp, if (isSlotted) MikaColors.TerminalGreen else Color.White.copy(alpha = 0.2f), RoundedCornerShape(3.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(if (isSlotted) MikaColors.TerminalGreen else if (isInstalled) MikaColors.MikaAmber else Color.White.copy(alpha = 0.25f))
                                )
                                Text(
                                    text = when {
                                        isSlotted -> "SLOTTED"
                                        isInstalled -> "EJECTED"
                                        else -> "UNINSTALLED"
                                    },
                                    color = when {
                                        isSlotted -> MikaColors.TerminalGreen
                                        isInstalled -> MikaColors.MikaAmber
                                        else -> Color.White.copy(alpha = 0.5f)
                                    },
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Black,
                                    fontSize = 8.sp
                                )
                            }
                        }

                        // Cartridge Title & Specs Body
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = spec.name,
                                color = Color.White,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Black,
                                fontSize = 13.sp
                            )
                            Text(
                                text = spec.subtitle,
                                color = accent,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp
                            )
                            Text(
                                text = spec.description,
                                color = MikaColors.CyanWhite.copy(alpha = 0.72f),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.5.sp,
                                lineHeight = 13.sp
                            )

                            if (bundleState.state == DownloadState.DOWNLOADING ||
                                bundleState.state == DownloadState.VERIFYING ||
                                bundleState.state == DownloadState.EXTRACTING
                            ) {
                                Spacer(modifier = Modifier.height(4.dp))
                                LinearProgressIndicator(
                                    progress = { bundleState.progressFraction },
                                    color = accent,
                                    trackColor = accent.copy(alpha = 0.18f),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text(
                                    text = when (bundleState.state) {
                                        DownloadState.VERIFYING -> "VERIFYING SHA-256 CHECKSUM..."
                                        DownloadState.EXTRACTING -> "UNPACKING .TAR.BZ2 ARCHIVE..."
                                        else -> "DOWNLOADING ${(bundleState.progressFraction * 100).toInt()}% (${bundleState.downloadedBytes / (1024 * 1024)} / ${spec.approximateSizeMb} MB)"
                                    },
                                    color = accent,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 9.sp
                                )
                            }

                            if (!bundleState.errorMessage.isNullOrBlank()) {
                                Text(
                                    text = "! ${bundleState.errorMessage}",
                                    color = MikaColors.DangerRed,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 9.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Cartridge Action Strip
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        when {
                            bundleState.state == DownloadState.DOWNLOADING -> {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(MikaColors.MikaAmber.copy(alpha = 0.18f))
                                        .border(1.dp, MikaColors.MikaAmber, RoundedCornerShape(5.dp))
                                        .clickable { viewModel.pauseModelDownload(spec.id) }
                                        .padding(vertical = 9.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("⏸ PAUSE DOWNLOAD", color = MikaColors.MikaAmber, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black, fontSize = 10.sp)
                                }
                            }
                            !isInstalled -> {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(accent.copy(alpha = 0.18f))
                                        .border(1.dp, accent, RoundedCornerShape(5.dp))
                                        .clickable { viewModel.startModelDownload(spec) }
                                        .padding(vertical = 9.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = if (bundleState.downloadedBytes > 0L) "⬇ RESUME ROM DOWNLOAD" else "⬇ DOWNLOAD ROM CARTRIDGE",
                                        color = accent,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Black,
                                        fontSize = 10.sp
                                    )
                                }
                            }
                            isSlotted -> {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(MikaColors.DangerRed.copy(alpha = 0.14f))
                                        .border(1.dp, MikaColors.DangerRed.copy(alpha = 0.7f), RoundedCornerShape(5.dp))
                                        .clickable { viewModel.toggleCartridgeSlotted(spec, false) }
                                        .padding(vertical = 9.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("▲ EJECT CARTRIDGE", color = MikaColors.DangerRed, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black, fontSize = 10.sp)
                                }
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(accent.copy(alpha = 0.18f))
                                        .border(1.dp, accent, RoundedCornerShape(5.dp))
                                        .clickable { onJumpToVoiceSelector(spec.id) }
                                        .padding(horizontal = 12.dp, vertical = 9.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("⚙ VOICES ▸", color = accent, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black, fontSize = 10.sp)
                                }
                            }
                            else -> {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(accent.copy(alpha = 0.22f))
                                        .border(1.5.dp, accent, RoundedCornerShape(5.dp))
                                        .clickable { viewModel.toggleCartridgeSlotted(spec, true) }
                                        .padding(vertical = 9.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("▼ SLAM CARTRIDGE IN", color = accent, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black, fontSize = 10.sp)
                                }
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(MikaColors.DangerRed.copy(alpha = 0.10f))
                                        .border(1.dp, MikaColors.DangerRed.copy(alpha = 0.5f), RoundedCornerShape(5.dp))
                                        .clickable { viewModel.deleteModelBundle(spec) }
                                        .padding(horizontal = 10.dp, vertical = 9.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("DEL", color = MikaColors.DangerRed, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Exposed 16-Pin Gold PCB Edge Connector
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF06040A))
                            .border(1.dp, MikaColors.SsrGold.copy(alpha = 0.3f))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        repeat(16) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(if (isSlotted) 6.dp else 10.dp)
                                    .background(if (isSlotted) accent else MikaColors.SsrGold.copy(alpha = 0.78f))
                            )
                        }
                    }
                }

                // Motherboard Socket Mouth
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(bottomStart = 6.dp, bottomEnd = 6.dp))
                        .background(Color(0xFF090710))
                        .border(1.dp, if (isSlotted) accent.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.14f), RoundedCornerShape(bottomStart = 6.dp, bottomEnd = 6.dp))
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (isSlotted) "● BUS_LOCKED // VOICES ONLINE" else if (isInstalled) "○ READY TO SLAM" else "○ BAY EMPTY",
                        color = if (isSlotted) MikaColors.TerminalGreen else if (isInstalled) MikaColors.MikaAmber else MikaColors.DimText,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Black,
                        fontSize = 8.5.sp
                    )
                    Text(
                        text = "SHA-256: ${spec.expectedSha256.take(10)}...",
                        color = MikaColors.DimText,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 8.5.sp
                    )
                }
            }
        }
    }
}
