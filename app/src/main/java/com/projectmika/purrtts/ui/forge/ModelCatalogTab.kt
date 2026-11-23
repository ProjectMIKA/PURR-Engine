package com.projectmika.purrtts.ui.forge

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
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
private enum class CartridgeSortMode(val label: String) {
    BAY_ORDER("BAY"),
    NAME("NAME"),
    SIZE("SIZE"),
    NEWEST("NEWEST")
}

private enum class CartridgeFilterMode(val label: String) {
    ALL("ALL"),
    DOWNLOADED("INSTALLED"),
    NOT_DOWNLOADED("UNINSTALLED"),
    SLOTTED("SLOTTED")
}

@OptIn(ExperimentalLayoutApi::class)
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
    val cloningCatalog = remember(mainCatalog) { mainCatalog.filter { it.category == CartridgeCategory.CLONING } }
    val toolsCatalog = remember(mainCatalog) { mainCatalog.filter { it.category == CartridgeCategory.TOOLS } }
    val categoryCatalog = remember(ttsCatalog, cloningCatalog, toolsCatalog, selectedCategory) {
        when (selectedCategory) {
            CartridgeCategory.TTS -> ttsCatalog
            CartridgeCategory.CLONING -> cloningCatalog
            CartridgeCategory.TOOLS -> toolsCatalog
        }
    }
    val subCartridgesByParent = remember(modelCatalog) {
        modelCatalog.filter { it.isSubCartridge && !it.parentCartridgeId.isNullOrBlank() }
            .groupBy { it.parentCartridgeId!! }
    }
    fun isSpecOrStandaloneSubInstalled(spec: ModelBundleSpec): Boolean {
        if (spec.isBuiltInOsCartridge || forgeState.modelStates[spec.id]?.state == DownloadState.INSTALLED) {
            return true
        }
        val childSubs = subCartridgesByParent[spec.id].orEmpty()
        return childSubs.any { sub ->
            sub.id !in setOf("rom_kokoro_npu_g2p", "rom_kokoro_litert") &&
                forgeState.modelStates[sub.id]?.state == DownloadState.INSTALLED
        }
    }
    var sortMode by remember { mutableStateOf(CartridgeSortMode.BAY_ORDER) }
    var filterMode by remember { mutableStateOf(CartridgeFilterMode.ALL) }
    val visibleCatalog = remember(categoryCatalog, sortMode, filterMode, forgeState.modelStates, forgeState.slottedCartridges) {
        val filtered = categoryCatalog.filter { spec ->
            val installed = isSpecOrStandaloneSubInstalled(spec)
            val slotted = installed && spec.id in forgeState.slottedCartridges
            when (filterMode) {
                CartridgeFilterMode.ALL -> true
                CartridgeFilterMode.DOWNLOADED -> installed
                CartridgeFilterMode.NOT_DOWNLOADED -> !installed
                CartridgeFilterMode.SLOTTED -> slotted
            }
        }
        when (sortMode) {
            CartridgeSortMode.BAY_ORDER -> filtered
            CartridgeSortMode.NAME -> filtered.sortedBy { it.name.lowercase() }
            CartridgeSortMode.SIZE -> filtered.sortedBy { it.approximateSizeMb }
            CartridgeSortMode.NEWEST -> filtered.sortedByDescending { it.releaseOrder }
        }
    }
    // Collapsible sub-modules: only main cartridges show initially; users tap a parent's obvious
    // expansion drawer to reveal and manage its nested sub-modules.
    var expandedSubMenus by remember { mutableStateOf(setOf<String>()) }
    val slottedCount = categoryCatalog.count { it.id in forgeState.slottedCartridges }
    val busLoadPct = if (categoryCatalog.isNotEmpty()) (slottedCount * 100) / categoryCatalog.size else 0
    val categoryAccent = when (selectedCategory) {
        CartridgeCategory.TTS -> MikaColors.NeonCyan
        CartridgeCategory.CLONING -> MikaColors.NeonPurple
        CartridgeCategory.TOOLS -> MikaColors.NeonPink
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {

        // 0. 3-Category Switcher: TTS | CLONING | TOOLS
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val isTts = selectedCategory == CartridgeCategory.TTS
            val isCloning = selectedCategory == CartridgeCategory.CLONING
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
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = "TTS (${ttsCatalog.size})",
                    color = if (isTts) MikaColors.TerminalGreen else MikaColors.DimText,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    fontSize = 9.5.sp,
                    letterSpacing = 0.5.sp,
                    maxLines = 1
                )
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isCloning) MikaColors.NeonPurple.copy(alpha = 0.18f) else MikaColors.PanelBg)
                    .border(1.5.dp, if (isCloning) MikaColors.NeonPurple else Color.White.copy(alpha = 0.16f), RoundedCornerShape(6.dp))
                    .clickable { onSelectCategory(CartridgeCategory.CLONING) }
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                MikaCyberIcon(type = CyberIconType.DNA_CLONE, tint = if (isCloning) MikaColors.NeonPurple else MikaColors.DimText, size = 12.dp)
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = "CLONE (${cloningCatalog.size})",
                    color = if (isCloning) MikaColors.NeonPurple else MikaColors.DimText,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    fontSize = 9.5.sp,
                    letterSpacing = 0.5.sp,
                    maxLines = 1
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
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = "TOOLS (${toolsCatalog.size})",
                    color = if (isTools) MikaColors.NeonPink else MikaColors.DimText,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    fontSize = 9.5.sp,
                    letterSpacing = 0.5.sp,
                    maxLines = 1
                )
            }
        }

        // 1. Cyberdeck Motherboard Pin Rack Visualizer
        MikaGlowPanel(accent = categoryAccent) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    MikaCyberIcon(
                        type = when (selectedCategory) {
                            CartridgeCategory.TTS -> CyberIconType.BUS_PLUG
                            CartridgeCategory.CLONING -> CyberIconType.DNA_CLONE
                            CartridgeCategory.TOOLS -> CyberIconType.WRENCH_TOOL
                        },
                        tint = categoryAccent,
                        size = 12.dp
                    )
                    Text(
                        text = when (selectedCategory) {
                            CartridgeCategory.TTS -> "TTS_BUS // ${categoryCatalog.size}-BAY RACK"
                            CartridgeCategory.CLONING -> "CLONE_BUS // ${categoryCatalog.size}-BAY RACK"
                            CartridgeCategory.TOOLS -> "TOOL_BUS // ${categoryCatalog.size}-MODULE RACK"
                        },
                        color = categoryAccent,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Black,
                        fontSize = 10.sp,
                        letterSpacing = 0.8.sp
                    )
                }
                Text(
                    text = "[ $slottedCount / ${categoryCatalog.size} SLOTTED | $busLoadPct% ]",
                    color = if (slottedCount > 0) MikaColors.TerminalGreen else MikaColors.DimText,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.sp
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Interactive Multi-Row Motherboard Bay Rack (max 8 bays per row)
            val rackRows = remember(categoryCatalog) { categoryCatalog.chunked(8) }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF040207))
                    .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                rackRows.forEachIndexed { rowIdx, rowSpecs ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        rowSpecs.forEachIndexed { colIdx, spec ->
                            val globalIdx = rowIdx * 8 + colIdx
                            val isInstalled = isSpecOrStandaloneSubInstalled(spec)
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
                                    text = when (selectedCategory) {
                                        CartridgeCategory.TTS -> "%02d".format(globalIdx + 1)
                                        CartridgeCategory.CLONING -> "C${globalIdx + 1}"
                                        CartridgeCategory.TOOLS -> "T${globalIdx + 1}"
                                    },
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
                        if (rackRows.size > 1 && rowSpecs.size < 8) {
                            repeat(8 - rowSpecs.size) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Color.White.copy(alpha = 0.015f))
                                        .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(4.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "--",
                                        color = Color.White.copy(alpha = 0.14f),
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 8.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = when (selectedCategory) {
                    CartridgeCategory.TTS -> "// Download & slam core TTS engines. Tap EXPAND on any hub cartridge to reveal its nested sub-modules and accelerator chips."
                    CartridgeCategory.CLONING -> "// Dedicated zero-shot cloning bay (Pocket, ZipVoice, Chatterbox-Turbo, Qwen3 Base) + Clone Studio workshop. Tap EXPAND on hubs for studio weights."
                    CartridgeCategory.TOOLS -> "// Slam or eject built-in OS Tool Modules. Ejecting P.U.R.R. ENGINE or DSP RACK removes its icon from the Modules tab and turns off its pipeline!"
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

        // 1b. Compact Filter & Sort Controls
        MikaGlowPanel(accent = categoryAccent, contentPadding = 10.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "> FILTER // ${visibleCatalog.size} OF ${categoryCatalog.size}",
                        color = categoryAccent,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Black,
                        fontSize = 9.5.sp
                    )
                    if (filterMode != CartridgeFilterMode.ALL || sortMode != CartridgeSortMode.BAY_ORDER) {
                        Text(
                            text = "[RESET]",
                            color = MikaColors.MikaAmber,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 9.sp,
                            modifier = Modifier.clickable {
                                filterMode = CartridgeFilterMode.ALL
                                sortMode = CartridgeSortMode.BAY_ORDER
                            }
                        )
                    }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    CartridgeFilterMode.entries.forEach { mode ->
                        MikaTagPill(
                            label = mode.label,
                            selected = filterMode == mode,
                            activeColor = categoryAccent,
                            onClick = { filterMode = mode }
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "SORT:",
                        color = MikaColors.DimText,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 9.sp
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        CartridgeSortMode.entries.forEach { mode ->
                            MikaTagPill(
                                label = mode.label,
                                selected = sortMode == mode,
                                activeColor = MikaColors.SsrGold,
                                onClick = { sortMode = mode }
                            )
                        }
                    }
                }
            }
        }

        if (visibleCatalog.isEmpty()) {
            MikaGlowPanel(accent = MikaColors.MikaAmber) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "// No cartridges match filter [${filterMode.label}].",
                        color = MikaColors.DimText,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.5.sp,
                        modifier = Modifier.weight(1f)
                    )
                    MikaTagPill(
                        label = "SHOW ALL",
                        selected = true,
                        activeColor = MikaColors.MikaAmber,
                        onClick = { filterMode = CartridgeFilterMode.ALL }
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
            val isToolCartridge = spec.isBuiltInOsCartridge && (spec.category == CartridgeCategory.TOOLS || spec.id == "tool_clone_studio")
            val isCloningCartridge = spec.category == CartridgeCategory.CLONING
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
                            text = when {
                                isToolCartridge -> "M.I.K.A.-OS"
                                isCloningCartridge -> "M.I.K.A.-CLN"
                                else -> "M.I.K.A.-ROM"
                            },
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
                                        text = "EXPAND SUB-MODULES BELOW TO INSTALL CHIPS",
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
                                            when {
                                                spec.id == "tool_clone_studio" -> onOpenCloneStudio()
                                                isToolCartridge -> onOpenModulesTab()
                                                else -> onJumpToVoiceSelector(spec.id)
                                            }
                                        }
                                        .padding(horizontal = 10.dp, vertical = 9.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    MikaCyberIcon(
                                        type = if (spec.id == "tool_clone_studio") CyberIconType.DNA_CLONE else CyberIconType.WRENCH_TOOL,
                                        tint = accent,
                                        size = 11.dp
                                    )
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Text(
                                        text = when {
                                            spec.id == "tool_clone_studio" -> "STUDIO"
                                            isToolCartridge -> "MODULE"
                                            else -> "VOICES"
                                        },
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
                        val isExpanded = spec.id in expandedSubMenus
                        val activeSubCount = childSubs.count { sub ->
                            forgeState.modelStates[sub.id]?.state == DownloadState.INSTALLED &&
                                sub.id in forgeState.enabledSubCartridges
                        }
                        val installedSubCount = childSubs.count { sub ->
                            forgeState.modelStates[sub.id]?.state == DownloadState.INSTALLED
                        }
                        val anySubDownloading = childSubs.any { sub ->
                            val st = forgeState.modelStates[sub.id]?.state
                            st == DownloadState.DOWNLOADING || st == DownloadState.VERIFYING || st == DownloadState.EXTRACTING
                        }
                        val chevronRotation by animateFloatAsState(
                            targetValue = if (isExpanded) 90f else 0f,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessMediumLow
                            ),
                            label = "subMenuChevron_${spec.id}"
                        )
                        val drawerBorderColor by animateColorAsState(
                            targetValue = if (isExpanded) accent.copy(alpha = 0.72f) else accent.copy(alpha = 0.42f),
                            animationSpec = tween(220),
                            label = "subMenuBorder_${spec.id}"
                        )
                        val drawerBgColor by animateColorAsState(
                            targetValue = if (isExpanded) Color(0xFF0A0716) else Color(0xFF07050E),
                            animationSpec = tween(220),
                            label = "subMenuBg_${spec.id}"
                        )

                        Spacer(modifier = Modifier.height(10.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(drawerBgColor)
                                .border(1.dp, drawerBorderColor, RoundedCornerShape(6.dp))
                        ) {
                            val subHeaderText = when (spec.id) {
                                "rom_kokoro_sherpa" -> "KOKORO SUB-MODULES & CHIPS (${childSubs.size})"
                                "rom_pocket_tts" -> "POCKET CLONE UPGRADES (${childSubs.size})"
                                "rom_zipvoice" -> "ZIPVOICE CLONE UPGRADES (${childSubs.size})"
                                "rom_qwen3_custom_06b" -> "QWEN3 SUB-MODULES (${childSubs.size})"
                                "rom_kitten_tts" -> "KITTENTTS FULL-WEIGHT UPGRADE (${childSubs.size})"
                                "rom_piper" -> "PIPER, MELOTTS & FLOW SUB-MODULES (${childSubs.size})"
                                else -> "SUB-MODULES FOR ${spec.romCode} (${childSubs.size})"
                            }

                            // Unmistakable Collapsible Header Trigger
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        expandedSubMenus = if (isExpanded) {
                                            expandedSubMenus - spec.id
                                        } else {
                                            expandedSubMenus + spec.id
                                        }
                                    }
                                    .padding(horizontal = 10.dp, vertical = 9.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
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
                                        MikaCyberIcon(type = CyberIconType.CHIP_EXPANSION, tint = accent, size = 12.dp)
                                        Text(
                                            text = subHeaderText,
                                            color = accent,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Black,
                                            fontSize = 9.5.sp,
                                            letterSpacing = 0.5.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                                    ) {
                                        val statusBadgeText = when {
                                            anySubDownloading -> "BUSY"
                                            activeSubCount > 0 -> "$activeSubCount ON"
                                            installedSubCount > 0 -> "$installedSubCount READY"
                                            else -> "${childSubs.size} CHIPS"
                                        }
                                        val statusBadgeColor = when {
                                            anySubDownloading -> MikaColors.MikaAmber
                                            activeSubCount > 0 -> MikaColors.TerminalGreen
                                            installedSubCount > 0 -> MikaColors.NeonCyan
                                            else -> MikaColors.DimText
                                        }
                                        Text(
                                            text = statusBadgeText,
                                            color = statusBadgeColor,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Black,
                                            fontSize = 8.sp,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(3.dp))
                                                .background(statusBadgeColor.copy(alpha = 0.14f))
                                                .border(1.dp, statusBadgeColor.copy(alpha = 0.5f), RoundedCornerShape(3.dp))
                                                .padding(horizontal = 5.dp, vertical = 2.dp)
                                        )
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(accent.copy(alpha = if (isExpanded) 0.22f else 0.14f))
                                                .border(1.dp, accent.copy(alpha = 0.75f), RoundedCornerShape(4.dp))
                                                .padding(horizontal = 7.dp, vertical = 3.dp)
                                        ) {
                                            Text(
                                                text = if (isExpanded) "HIDE" else "EXPAND",
                                                color = accent,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Black,
                                                fontSize = 8.sp,
                                                letterSpacing = 0.5.sp
                                            )
                                            Box(modifier = Modifier.rotate(chevronRotation)) {
                                                MikaCyberIcon(type = CyberIconType.CHEVRON_RIGHT, tint = accent, size = 9.dp)
                                            }
                                        }
                                    }
                                }

                                if (!isExpanded) {
                                    val previewNames = childSubs.take(3).joinToString(" • ") { it.badge } +
                                        if (childSubs.size > 3) " +${childSubs.size - 3} more" else ""
                                    Text(
                                        text = "// Tap to expand: $previewNames",
                                        color = MikaColors.CyanWhite.copy(alpha = 0.62f),
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 8.5.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            AnimatedVisibility(
                                visible = isExpanded,
                                enter = expandVertically(
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioNoBouncy,
                                        stiffness = Spring.StiffnessMediumLow
                                    ),
                                    expandFrom = Alignment.Top
                                ) + fadeIn(
                                    animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing)
                                ),
                                exit = shrinkVertically(
                                    animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                                    shrinkTowards = Alignment.Top
                                ) + fadeOut(
                                    animationSpec = tween(durationMillis = 160)
                                )
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    val hubGuideText = when (spec.id) {
                                        "rom_kokoro_sherpa" -> if (bundleState.state == DownloadState.INSTALLED) {
                                            "// Kokoro Base INT8 is ready! SUB-02A/B accelerate Kokoro Base; SUB-02C (FP32) & SUB-02D (v1.1 103v) also work standalone."
                                        } else {
                                            "// Install ROM-02 Base INT8 above (required for NPU/GPU SUB-02A/B), OR install SUB-02C FP32 / SUB-02D v1.1 standalone!"
                                        }
                                        "rom_pocket_tts" -> "// Upgrade CLN-01 Pocket INT8 with unquantized FP32 Studio cloning weights below!"
                                        "rom_zipvoice" -> "// Upgrade CLN-02 ZipVoice Distill with full non-distilled flow-matching studio weights below!"
                                        "rom_qwen3_custom_06b" -> "// Install 0.6B CustomVoice above OR slam 1.7B VoiceDesign (SUB-03A) directly — standalone!"
                                        "rom_kitten_tts" -> "// Install 30MB Nano Base above OR Micro (42MB) / Mini 80M (64MB) below — all work standalone!"
                                        "rom_inflect_v2" -> "// Install Inflect v2 Nano (21MB) above OR Micro Studio (41MB) below!"
                                        "rom_icefall_vits" -> "// Install Icefall Medium (70MB) above OR Lite (30MB) below!"
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
