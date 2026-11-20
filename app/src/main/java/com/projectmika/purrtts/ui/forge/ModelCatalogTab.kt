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
import androidx.compose.runtime.remember
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
import com.projectmika.purrtts.core.purr.CartridgeCategory
import com.projectmika.purrtts.core.purr.DownloadState
import com.projectmika.purrtts.core.purr.ModelBundleSpec
import com.projectmika.purrtts.core.purr.ModelBundleState
import com.projectmika.purrtts.core.purr.PurrForgeState
import com.projectmika.purrtts.ui.theme.CyberIconType
import com.projectmika.purrtts.ui.theme.MikaColors
import com.projectmika.purrtts.ui.theme.MikaCyberIcon
import com.projectmika.purrtts.ui.theme.MikaGlowPanel
import com.projectmika.purrtts.ui.theme.MikaTagPill

/**
 * ✨ TAB 3: RETRO CYBERDECK CARTRIDGE BAY (TTS MODULES & TOOL MODULES) ✨
 * Mirrors GachaSwipe's `> EXPANSION_BAY // ROM_CARTRIDGES` design:
 * - Dual Category Switcher (`TTS MODULES` vs `TOOLS`)
 * - Interactive Cyberdeck Motherboard Pin Rack Visualizer
 * - Physical Molded Cartridges with nested Expansion Sub-Cartridges (`SUB-02A/B` & `SUB-05A..F`)
 */
@Composable
fun ModelCatalogTab(
    forgeState: PurrForgeState,
    modelCatalog: List<ModelBundleSpec>,
    vramRemainingSec: Int,
    selectedCategory: CartridgeCategory,
    onSelectCategory: (CartridgeCategory) -> Unit,
    viewModel: TtsForgeViewModel,
    onJumpToVoiceSelector: (String) -> Unit,
    onOpenCloneStudio: () -> Unit,
    onOpenModulesTab: () -> Unit
) {
    val mainCatalog = remember(modelCatalog) { modelCatalog.filter { !it.isSubCartridge } }
    val ttsCatalog = remember(mainCatalog) { mainCatalog.filter { it.category == CartridgeCategory.TTS } }
    val toolsCatalog = remember(mainCatalog) { mainCatalog.filter { it.category == CartridgeCategory.TOOLS } }
    val visibleCatalog = remember(ttsCatalog, toolsCatalog, selectedCategory) {
        if (selectedCategory == CartridgeCategory.TTS) ttsCatalog else toolsCatalog
    }
    val subCartridgesByParent = remember(modelCatalog) {
        modelCatalog.filter { it.isSubCartridge && !it.parentCartridgeId.isNullOrBlank() }
            .groupBy { it.parentCartridgeId!! }
    }
    val slottedCount = visibleCatalog.count { it.id in forgeState.slottedCartridges }
    val busLoadPct = if (visibleCatalog.isNotEmpty()) (slottedCount * 100) / visibleCatalog.size else 0

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {

        // 0. Dual Category Switcher: TTS vs TOOLS
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val isTts = selectedCategory == CartridgeCategory.TTS
            val isTools = selectedCategory == CartridgeCategory.TOOLS
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isTts) MikaColors.TerminalGreen.copy(alpha = 0.16f) else MikaColors.PanelBg)
                    .border(1.5.dp, if (isTts) MikaColors.TerminalGreen else Color.White.copy(alpha = 0.16f), RoundedCornerShape(6.dp))
                    .clickable { onSelectCategory(CartridgeCategory.TTS) }
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                MikaCyberIcon(type = CyberIconType.MIC_WAVE, tint = if (isTts) MikaColors.TerminalGreen else MikaColors.DimText, size = 12.dp)
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "TTS (${ttsCatalog.size})",
                    color = if (isTts) MikaColors.TerminalGreen else MikaColors.DimText,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    fontSize = 10.sp,
                    letterSpacing = 0.6.sp
                )
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isTools) MikaColors.NeonPink.copy(alpha = 0.16f) else MikaColors.PanelBg)
                    .border(1.5.dp, if (isTools) MikaColors.NeonPink else Color.White.copy(alpha = 0.16f), RoundedCornerShape(6.dp))
                    .clickable { onSelectCategory(CartridgeCategory.TOOLS) }
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                MikaCyberIcon(type = CyberIconType.WRENCH_TOOL, tint = if (isTools) MikaColors.NeonPink else MikaColors.DimText, size = 12.dp)
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "TOOLS (${toolsCatalog.size})",
                    color = if (isTools) MikaColors.NeonPink else MikaColors.DimText,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    fontSize = 10.sp,
                    letterSpacing = 0.6.sp
                )
            }
        }

        // 1. Cyberdeck Motherboard Pin Rack Visualizer
        MikaGlowPanel(accent = if (selectedCategory == CartridgeCategory.TTS) MikaColors.NeonCyan else MikaColors.NeonPink) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val rackAccent = if (selectedCategory == CartridgeCategory.TTS) MikaColors.NeonCyan else MikaColors.NeonPink
                    MikaCyberIcon(
                        type = if (selectedCategory == CartridgeCategory.TTS) CyberIconType.BUS_PLUG else CyberIconType.WRENCH_TOOL,
                        tint = rackAccent,
                        size = 12.dp
                    )
                    Text(
                        text = if (selectedCategory == CartridgeCategory.TTS) {
                            "TTS_BUS // ${visibleCatalog.size}-BAY RACK"
                        } else {
                            "TOOL_BUS // ${visibleCatalog.size}-MODULE RACK"
                        },
                        color = rackAccent,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Black,
                        fontSize = 10.sp,
                        letterSpacing = 0.8.sp
                    )
                }
                Text(
                    text = "[ $slottedCount / ${visibleCatalog.size} SLOTTED | $busLoadPct% ]",
                    color = if (slottedCount > 0) MikaColors.TerminalGreen else MikaColors.DimText,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.sp
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Interactive Mini Bay Slots
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF040207))
                    .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                visibleCatalog.forEachIndexed { idx, spec ->
                    val childSubs = subCartridgesByParent[spec.id].orEmpty()
                    val standaloneSubInstalled = childSubs.any { sub ->
                        sub.id !in setOf("rom_kokoro_npu_g2p", "rom_kokoro_litert") &&
                            forgeState.modelStates[sub.id]?.state == DownloadState.INSTALLED
                    }
                    val isInstalled = spec.isBuiltInOsCartridge ||
                        forgeState.modelStates[spec.id]?.state == DownloadState.INSTALLED ||
                        standaloneSubInstalled
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
                            text = if (selectedCategory == CartridgeCategory.TTS) "0${idx + 1}" else "T${idx + 1}",
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
                text = if (selectedCategory == CartridgeCategory.TTS) {
                    "// Download & slam TTS engines (Kokoro 54v, KittenTTS 30MB, Piper Collection, Supertonic, Pocket, ZipVoice). ROM-02 & ROM-05 accept expansion chips!"
                } else {
                    "// Slam or eject built-in OS Tool Modules. Ejecting P.U.R.R. ENGINE or DSP RACK removes its icon from the Modules tab and turns off its pipeline!"
                },
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
                        label = "CLONE STUDIO",
                        selected = true,
                        activeColor = MikaColors.NeonPurple,
                        leadingIcon = CyberIconType.DNA_CLONE,
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

        // 2. Physical Molded Cartridge Cards for Active Category
        visibleCatalog.forEach { spec ->
            val bundleState = forgeState.modelStates[spec.id] ?: ModelBundleState(specId = spec.id)
            val childSubs = subCartridgesByParent[spec.id].orEmpty()
            val standaloneSubInstalled = childSubs.any { sub ->
                sub.id !in setOf("rom_kokoro_npu_g2p", "rom_kokoro_litert") &&
                    forgeState.modelStates[sub.id]?.state == DownloadState.INSTALLED
            }
            val isInstalled = spec.isBuiltInOsCartridge ||
                bundleState.state == DownloadState.INSTALLED ||
                standaloneSubInstalled
            val isSlotted = isInstalled && spec.id in forgeState.slottedCartridges
            val isToolCartridge = spec.category == CartridgeCategory.TOOLS
            val accent = Color(spec.accentHex)
            val sizeLabel = when {
                isToolCartridge -> "OS_MODULE"
                spec.isBuiltInOsCartridge -> "BUILT-IN"
                spec.isHubContainer -> "MULTI-ENGINE HUB"
                else -> "${spec.approximateSizeMb}MB"
            }

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
                            MikaCyberIcon(type = CyberIconType.SCREW_BOSS, tint = Color.White.copy(alpha = 0.35f), size = 9.dp)
                        }
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Box(modifier = Modifier.fillMaxWidth().height(2.dp).background(if (isSlotted) accent.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.12f)))
                            Box(modifier = Modifier.fillMaxWidth().height(2.dp).background(if (isSlotted) accent.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.08f)))
                            Box(modifier = Modifier.fillMaxWidth().height(2.dp).background(if (isSlotted) accent.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.12f)))
                        }
                        Text(
                            text = if (isToolCartridge) "M.I.K.A.-OS" else "M.I.K.A.-ROM",
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
                                    text = "// ${spec.badge} • $sizeLabel",
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

                            if (spec.unlocksSummary.isNotBlank()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 4.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(accent.copy(alpha = 0.10f))
                                        .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(4.dp))
                                        .padding(horizontal = 8.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    MikaCyberIcon(type = CyberIconType.CHECK, tint = accent, size = 11.dp)
                                    Text(
                                        text = spec.unlocksSummary,
                                        color = if (isSlotted) Color.White else accent,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 9.5.sp,
                                        lineHeight = 13.sp,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }

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
                                        DownloadState.VERIFYING -> bundleState.errorMessage?.takeIf { it.isNotBlank() }
                                            ?: "VERIFYING SHA-256 CHECKSUM..."
                                        DownloadState.EXTRACTING -> bundleState.errorMessage?.takeIf { it.isNotBlank() }
                                            ?: "UNPACKING .TAR.BZ2 ARCHIVE..."
                                        else -> {
                                            val prefix = bundleState.errorMessage?.takeIf { it.startsWith("[") }?.let { "$it • " }.orEmpty()
                                            "${prefix}DOWNLOADING ${(bundleState.progressFraction * 100).toInt()}% (${bundleState.downloadedBytes / (1024 * 1024)} / ${spec.approximateSizeMb} MB)"
                                        }
                                    },
                                    color = accent,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 9.sp
                                )
                            }

                            if (bundleState.state == DownloadState.FAILED && !bundleState.errorMessage.isNullOrBlank()) {
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
                                Row(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(MikaColors.MikaAmber.copy(alpha = 0.18f))
                                        .border(1.dp, MikaColors.MikaAmber, RoundedCornerShape(5.dp))
                                        .clickable { viewModel.pauseModelDownload(spec.id) }
                                        .padding(vertical = 9.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    MikaCyberIcon(type = CyberIconType.PAUSE, tint = MikaColors.MikaAmber, size = 11.dp)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("PAUSE DOWNLOAD", color = MikaColors.MikaAmber, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black, fontSize = 10.sp)
                                }
                            }
                            !isInstalled && spec.isHubContainer -> {
                                Row(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(accent.copy(alpha = 0.10f))
                                        .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(5.dp))
                                        .padding(vertical = 9.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "SELECT A CLONING ENGINE BELOW (SUB-03A..E)",
                                        color = accent,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Black,
                                        fontSize = 9.5.sp
                                    )
                                }
                            }
                            !isInstalled -> {
                                Row(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(accent.copy(alpha = 0.18f))
                                        .border(1.dp, accent, RoundedCornerShape(5.dp))
                                        .clickable { viewModel.startModelDownload(spec) }
                                        .padding(vertical = 9.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    MikaCyberIcon(type = CyberIconType.DOWNLOAD, tint = accent, size = 11.dp)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (bundleState.downloadedBytes > 0L) "RESUME ROM DOWNLOAD" else "DOWNLOAD ROM CARTRIDGE",
                                        color = accent,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Black,
                                        fontSize = 10.sp
                                    )
                                }
                            }
                            isSlotted -> {
                                Row(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(MikaColors.DangerRed.copy(alpha = 0.14f))
                                        .border(1.dp, MikaColors.DangerRed.copy(alpha = 0.7f), RoundedCornerShape(5.dp))
                                        .clickable { viewModel.toggleCartridgeSlotted(spec, false) }
                                        .padding(vertical = 9.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    MikaCyberIcon(type = CyberIconType.EJECT, tint = MikaColors.DangerRed, size = 11.dp)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = when {
                                            isToolCartridge -> "EJECT MODULE (TURN OFF)"
                                            spec.isBuiltInOsCartridge -> "EJECT CARTRIDGE (DISABLE OS)"
                                            else -> "EJECT CARTRIDGE"
                                        },
                                        color = MikaColors.DangerRed,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Black,
                                        fontSize = 10.sp
                                    )
                                }
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(accent.copy(alpha = 0.18f))
                                        .border(1.dp, accent, RoundedCornerShape(5.dp))
                                        .clickable {
                                            if (isToolCartridge) onOpenModulesTab() else onJumpToVoiceSelector(spec.id)
                                        }
                                        .padding(horizontal = 10.dp, vertical = 9.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    MikaCyberIcon(type = CyberIconType.WRENCH_TOOL, tint = accent, size = 11.dp)
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Text(
                                        text = if (isToolCartridge) "MODULE" else "VOICES",
                                        color = accent,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Black,
                                        fontSize = 10.sp
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    MikaCyberIcon(type = CyberIconType.CHEVRON_RIGHT, tint = accent, size = 10.dp)
                                }
                            }
                            else -> {
                                Row(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(accent.copy(alpha = 0.22f))
                                        .border(1.5.dp, accent, RoundedCornerShape(5.dp))
                                        .clickable { viewModel.toggleCartridgeSlotted(spec, true) }
                                        .padding(vertical = 9.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    MikaCyberIcon(type = CyberIconType.SLAM_IN, tint = accent, size = 11.dp)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = when {
                                            isToolCartridge -> "SLAM MODULE IN (ENABLE)"
                                            spec.isBuiltInOsCartridge -> "SLAM CARTRIDGE IN (ENABLE OS)"
                                            else -> "SLAM CARTRIDGE IN"
                                        },
                                        color = accent,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Black,
                                        fontSize = 10.sp
                                    )
                                }
                                if (!spec.isBuiltInOsCartridge && !isToolCartridge && !spec.isHubContainer) {
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
                    }

                    if (childSubs.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF07050E))
                                .border(1.dp, accent.copy(alpha = 0.40f), RoundedCornerShape(6.dp))
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val subHeaderText = when (spec.id) {
                                "rom_kokoro_sherpa" -> "KOKORO EXPANSIONS & ACCELERATORS (${childSubs.size} CHIPS)"
                                "rom_clone_hub" -> "ZERO-SHOT CLONING ENGINES (${childSubs.size} ENGINES)"
                                "rom_qwen3_custom_06b" -> "QWEN3-TTS EXPANSION MODELS (CLONE & VOICEDESIGN)"
                                "rom_kitten_tts" -> "KITTENTTS FULL-WEIGHT UPGRADE (${childSubs.size} CHIP)"
                                "rom_piper" -> "PIPER VOICES, MELOTTS & FLOW ENGINES (${childSubs.size} PACKS)"
                                else -> "EXPANSION SUB-CARTRIDGES (${childSubs.size} PACKS FOR ${spec.romCode})"
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                MikaCyberIcon(type = CyberIconType.CHIP_EXPANSION, tint = accent, size = 12.dp)
                                Text(
                                    text = subHeaderText,
                                    color = accent,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Black,
                                    fontSize = 9.5.sp,
                                    letterSpacing = 0.5.sp
                                )
                            }
                            val hubGuideText = when (spec.id) {
                                "rom_kokoro_sherpa" -> if (bundleState.state == DownloadState.INSTALLED) {
                                    "// Kokoro Base INT8 is ready! SUB-02A/B accelerate Kokoro Base; SUB-02C (FP32) & SUB-02D (v1.1 103v) also work standalone."
                                } else {
                                    "// Install ROM-02 Base INT8 above (required for NPU/GPU SUB-02A/B), OR install SUB-02C FP32 / SUB-02D v1.1 standalone!"
                                }
                                "rom_clone_hub" -> "// Pick ANY cloning engine below (Pocket INT8/FP32, ZipVoice INT8/Full, or Chatterbox-Turbo 350M) — ROM-03 auto-slots!"
                                "rom_qwen3_custom_06b" -> "// Install 0.6B CustomVoice above OR slam 0.6B Base Cloner (SUB-04A) / 1.7B VoiceDesign (SUB-04B) directly — standalone!"
                                "rom_kitten_tts" -> "// Install 30MB Nano Base above OR 64MB KittenTTS Mini 80M below — either works standalone!"
                                "rom_piper" -> "// Pick Piper Lessac US above OR any standalone narrator pack (Amy, Ryan, Cori, Alba, GLaDOS, Kristin, MeloTTS, Supertonic 3, Matcha)."
                                else -> "// Expansion sub-cartridges for ${spec.romCode}:"
                            }
                            Text(
                                text = hubGuideText,
                                color = if (isInstalled) MikaColors.DimText else MikaColors.NeonCyan,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.sp,
                                lineHeight = 13.sp
                            )

                            childSubs.forEach { subSpec ->
                                val subState = forgeState.modelStates[subSpec.id] ?: ModelBundleState(specId = subSpec.id)
                                val subInstalled = subState.state == DownloadState.INSTALLED
                                val subEnabled = subInstalled && subSpec.id in forgeState.enabledSubCartridges
                                SubCartridgeCard(
                                    subSpec = subSpec,
                                    subState = subState,
                                    isInstalled = subInstalled,
                                    isEnabled = subEnabled,
                                    viewModel = viewModel
                                )
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(if (isSlotted) MikaColors.TerminalGreen else Color.Transparent)
                                .border(1.dp, if (isSlotted) MikaColors.TerminalGreen else if (isInstalled) MikaColors.MikaAmber else MikaColors.DimText, CircleShape)
                        )
                        Text(
                            text = when {
                                isToolCartridge && isSlotted -> "MODULE ONLINE // ACTIVE"
                                isToolCartridge -> "MODULE EJECTED // BYPASSED"
                                isSlotted -> "BUS_LOCKED // VOICES ONLINE"
                                isInstalled -> "READY TO SLAM"
                                else -> "BAY EMPTY"
                            },
                            color = if (isSlotted) MikaColors.TerminalGreen else if (isInstalled) MikaColors.MikaAmber else MikaColors.DimText,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Black,
                            fontSize = 8.5.sp
                        )
                    }
                    Text(
                        text = when {
                            isToolCartridge -> "${spec.romCode} // TOOL MODULE"
                            spec.isBuiltInOsCartridge -> "${spec.romCode} // OS BINDER"
                            else -> "SHA-256: ${spec.expectedSha256.take(10)}..."
                        },
                        color = MikaColors.DimText,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 8.5.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun SubCartridgeCard(
    subSpec: ModelBundleSpec,
    subState: ModelBundleState,
    isInstalled: Boolean,
    isEnabled: Boolean,
    viewModel: TtsForgeViewModel
) {
    val subAccent = Color(subSpec.accentHex)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (isEnabled) subAccent.copy(alpha = 0.10f) else Color(0xFF0C0916))
            .border(
                width = if (isEnabled) 1.5.dp else 1.dp,
                color = if (isEnabled) subAccent else Color.White.copy(alpha = 0.16f),
                shape = RoundedCornerShape(6.dp)
            )
            .padding(9.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${subSpec.romCode} // ${subSpec.badge} | ${subSpec.approximateSizeMb}MB",
                color = subAccent,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Black,
                fontSize = 9.sp
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (isEnabled) {
                    MikaCyberIcon(type = CyberIconType.BOLT_ACCEL, tint = MikaColors.TerminalGreen, size = 10.dp)
                }
                Text(
                    text = when {
                        isEnabled -> "[ACTIVE]"
                        isInstalled -> "[INSTALLED | OFF]"
                        else -> "[OPTIONAL ADD-ON]"
                    },
                    color = when {
                        isEnabled -> MikaColors.TerminalGreen
                        isInstalled -> MikaColors.MikaAmber
                        else -> MikaColors.DimText
                    },
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    fontSize = 8.5.sp
                )
            }
        }

        Text(
            text = subSpec.name,
            color = Color.White,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Black,
            fontSize = 11.sp
        )
        Text(
            text = subSpec.subtitle,
            color = MikaColors.CyanWhite.copy(alpha = 0.72f),
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp
        )
        if (subSpec.unlocksSummary.isNotBlank()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                MikaCyberIcon(type = CyberIconType.BOLT_ACCEL, tint = subAccent, size = 11.dp)
                Text(
                    text = subSpec.unlocksSummary,
                    color = subAccent,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.sp,
                    lineHeight = 13.sp,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        if (subState.state == DownloadState.DOWNLOADING ||
            subState.state == DownloadState.VERIFYING ||
            subState.state == DownloadState.EXTRACTING
        ) {
            LinearProgressIndicator(
                progress = { subState.progressFraction },
                color = subAccent,
                trackColor = subAccent.copy(alpha = 0.18f),
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = when (subState.state) {
                    DownloadState.VERIFYING -> subState.errorMessage?.takeIf { it.isNotBlank() }
                        ?: "VERIFYING SHA-256 CHECKSUM..."
                    DownloadState.EXTRACTING -> subState.errorMessage?.takeIf { it.isNotBlank() }
                        ?: "UNPACKING .TAR.BZ2 ARCHIVE..."
                    else -> {
                        val prefix = subState.errorMessage?.takeIf { it.startsWith("[") }?.let { "$it • " }.orEmpty()
                        "${prefix}DOWNLOADING ${(subState.progressFraction * 100).toInt()}% (${subState.downloadedBytes / (1024 * 1024)} / ${subSpec.approximateSizeMb} MB)"
                    }
                },
                color = subAccent,
                fontFamily = FontFamily.Monospace,
                fontSize = 8.5.sp
            )
        }

        if (subState.state == DownloadState.FAILED && !subState.errorMessage.isNullOrBlank()) {
            Text(
                text = "! ${subState.errorMessage}",
                color = MikaColors.DangerRed,
                fontFamily = FontFamily.Monospace,
                fontSize = 8.5.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val packNoun = if (subSpec.parentCartridgeId == "rom_piper") "VOICE PACK" else "SUB-CHIP"
            when {
                subState.state == DownloadState.DOWNLOADING -> {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(4.dp))
                            .background(MikaColors.MikaAmber.copy(alpha = 0.16f))
                            .border(1.dp, MikaColors.MikaAmber, RoundedCornerShape(4.dp))
                            .clickable { viewModel.pauseModelDownload(subSpec.id) }
                            .padding(vertical = 7.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MikaCyberIcon(type = CyberIconType.PAUSE, tint = MikaColors.MikaAmber, size = 10.dp)
                        Spacer(modifier = Modifier.width(5.dp))
                        Text("PAUSE", color = MikaColors.MikaAmber, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black, fontSize = 9.sp)
                    }
                }
                !isInstalled -> {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(4.dp))
                            .background(subAccent.copy(alpha = 0.16f))
                            .border(1.dp, subAccent, RoundedCornerShape(4.dp))
                            .clickable { viewModel.startModelDownload(subSpec) }
                            .padding(vertical = 7.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MikaCyberIcon(type = CyberIconType.DOWNLOAD, tint = subAccent, size = 10.dp)
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = if (subState.downloadedBytes > 0L) "RESUME $packNoun (${subSpec.approximateSizeMb}MB)" else "DOWNLOAD $packNoun (${subSpec.approximateSizeMb}MB)",
                            color = subAccent,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Black,
                            fontSize = 9.sp
                        )
                    }
                }
                else -> {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (isEnabled) MikaColors.TerminalGreen.copy(alpha = 0.18f) else subAccent.copy(alpha = 0.16f))
                            .border(1.dp, if (isEnabled) MikaColors.TerminalGreen else subAccent, RoundedCornerShape(4.dp))
                            .clickable { viewModel.toggleSubCartridgeEnabled(subSpec, !isEnabled) }
                            .padding(vertical = 7.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MikaCyberIcon(
                            type = if (isEnabled) CyberIconType.BOLT_ACCEL else CyberIconType.SLAM_IN,
                            tint = if (isEnabled) MikaColors.TerminalGreen else subAccent,
                            size = 10.dp
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = if (isEnabled) "${packNoun} ACTIVE (TAP TO DISABLE)" else "ENABLE $packNoun",
                            color = if (isEnabled) MikaColors.TerminalGreen else subAccent,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Black,
                            fontSize = 9.sp
                        )
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MikaColors.DangerRed.copy(alpha = 0.10f))
                            .border(1.dp, MikaColors.DangerRed.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                            .clickable { viewModel.deleteModelBundle(subSpec) }
                            .padding(horizontal = 9.dp, vertical = 7.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("DEL", color = MikaColors.DangerRed, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 9.sp)
                    }
                }
            }
        }
    }
}
