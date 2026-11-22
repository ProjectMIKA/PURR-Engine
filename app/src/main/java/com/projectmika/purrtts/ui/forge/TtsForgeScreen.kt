package com.projectmika.purrtts.ui.forge

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.projectmika.purrtts.core.purr.ActionHandlingMode
import com.projectmika.purrtts.core.purr.CartridgeCategory
import com.projectmika.purrtts.core.purr.DownloadState
import com.projectmika.purrtts.core.purr.AcademyEntry
import com.projectmika.purrtts.core.purr.ModelBundleSpec
import com.projectmika.purrtts.core.purr.PurrProfile
import com.projectmika.purrtts.core.purr.PurrUpdateGate
import com.projectmika.purrtts.core.purr.PurrForgeState
import com.projectmika.purrtts.core.purr.PurrProxyPresets
import com.projectmika.purrtts.core.purr.TUTORIAL_VERSION
import androidx.compose.material3.HorizontalDivider
import com.projectmika.purrtts.ui.theme.CartridgeSlamOverlay
import com.projectmika.purrtts.ui.theme.CyberIconType
import com.projectmika.purrtts.ui.theme.ExpandableTerminalSection
import com.projectmika.purrtts.ui.theme.LiveWaveformMonitor
import com.projectmika.purrtts.ui.theme.MikaAcademyOverlay
import com.projectmika.purrtts.ui.theme.MikaSettingsSheet
import com.projectmika.purrtts.ui.theme.MikaBottomHudBar
import com.projectmika.purrtts.ui.theme.MikaColors
import com.projectmika.purrtts.ui.theme.MikaCyberIcon
import com.projectmika.purrtts.ui.theme.MikaGlowPanel
import com.projectmika.purrtts.ui.theme.MikaStatusBadge
import com.projectmika.purrtts.ui.theme.MikaTagPill
import com.projectmika.purrtts.ui.theme.MikaTerminalStage
import com.projectmika.purrtts.ui.theme.MikaVoiceDropdownSelector
import com.projectmika.purrtts.ui.theme.TerminalToastBanner
import kotlin.math.roundToInt

private enum class PurrTab(val title: String) {
    VOICE("VOICE"),
    MODULES("MODULES"),
    CARTRIDGE_BAY("CARTRIDGE BAY")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TtsForgeScreen(
    viewModel: TtsForgeViewModel,
    initialTabIndex: Int = 0,
    onOpenSystemTtsSettings: () -> Unit
) {
    val forgeState by viewModel.forgeState.collectAsState()
    val discoveredSystemVoices by viewModel.discoveredSystemVoices.collectAsState()
    val telemetry by viewModel.telemetry.collectAsState()
    val vramSec by viewModel.vramRemainingSeconds.collectAsState()
    val toastMsg by viewModel.toastMessage.collectAsState()
    val cartridgeAnim by viewModel.cartridgeAnim.collectAsState()
    val testText by viewModel.testInputText.collectAsState()
    val isPlaying by viewModel.isPlayingPreview.collectAsState()
    val auditionProgress by viewModel.auditionProgress.collectAsState()
    val isRecordingClone by viewModel.isRecordingClone.collectAsState()
    val recordingProgress by viewModel.recordingProgress.collectAsState()
    val lastRecordedWav by viewModel.lastRecordedWavPath.collectAsState()

    val activeProfile = forgeState.profiles.firstOrNull { it.id == forgeState.activeProfileId }
        ?: forgeState.profiles.first()
    val allVoices = remember(
        forgeState.clonedVoices,
        discoveredSystemVoices,
        forgeState.enabledSubCartridges,
        forgeState.modelStates
    ) {
        viewModel.allAvailableVoices()
    }

    var activeTab by remember {
        mutableIntStateOf(initialTabIndex.coerceIn(0, PurrTab.entries.lastIndex))
    }
    var cartridgeBayCategory by remember { mutableStateOf(CartridgeCategory.TTS) }
    var showSaveProfileDialog by remember { mutableStateOf(false) }
    var showCloneStudio by remember { mutableStateOf(false) }
    var showAdvancedProxy by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showAcademy by remember { mutableStateOf(false) }
    var academyPatchnotesOnly by remember { mutableStateOf(false) }
    var requestedOsToolId by remember { mutableStateOf<String?>(null) }

    // ✨ MIKA'S ACADEMY GATE (v1.5.0): first launch => full walkthrough; returning user on
    // older tutorial content => patchnotes popup once per TUTORIAL_VERSION bump.
    LaunchedEffect(forgeState.seenTutorialVersion) {
        when (PurrUpdateGate.resolveEntry(forgeState.seenTutorialVersion)) {
            AcademyEntry.FULL_TUTORIAL -> {
                academyPatchnotesOnly = false
                showAcademy = true
            }
            AcademyEntry.PATCHNOTES_ONLY -> {
                academyPatchnotesOnly = true
                showAcademy = true
            }
            AcademyEntry.NONE -> Unit
        }
    }

    val activeCartridgeId = activeProfile.selectedCartridgeId
    val voicesForSelectedEngine = remember(allVoices, activeCartridgeId) {
        allVoices.filter { v -> v.cartridgeId == activeCartridgeId }
    }

    val previewChunks = remember(
        testText,
        activeProfile,
        forgeState.customPhoneticRules,
        forgeState.slottedCartridges
    ) {
        viewModel.previewChunks(testText, activeProfile, forgeState.customPhoneticRules)
    }

    val slottedSpecs = remember(forgeState.slottedCartridges, viewModel.mainCartridgeCatalog) {
        viewModel.mainCartridgeCatalog.filter { it.id in forgeState.slottedCartridges }
    }
    val activeToolCount = remember(forgeState.slottedCartridges, viewModel.toolCartridgeCatalog) {
        viewModel.toolCartridgeCatalog.count { it.id in forgeState.slottedCartridges }
    }

    MikaTerminalStage {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Pinned Top HUD Header across the entire Cyberdeck OS.
                // ✨ FIXED-SIZE SLOT LAW (v1.3.3 scar): every dynamic element in this header
                // (hardware badge labels `[IDLE]..[NPU ACTIVE]` + VRAM countdown) must live in a
                // fixed-width slot and single-line text, or the title column re-wraps and the
                // whole page below shifts on every synthesis state change.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .heightIn(min = 56.dp)
                        .background(MikaColors.PanelBg.copy(alpha = 0.92f))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "> PURR_TTS // ENGINE",
                            color = MikaColors.TerminalGreen,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Black,
                            fontSize = 14.sp,
                            letterSpacing = 1.0.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val purrActive = "tool_purr_engine" in forgeState.slottedCartridges
                        Text(
                            text = if (purrActive) "// Preset: ${activeProfile.name}" else "// PURR Engine: OFF (Bypassed)",
                            color = if (purrActive) MikaColors.TerminalGreen.copy(alpha = 0.65f) else MikaColors.DimText,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        MikaStatusBadge(
                            badge = telemetry.badge,
                            vramCountdownSec = vramSec,
                            modifier = Modifier
                                .width(70.dp)
                                .height(26.dp)
                        )
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .height(26.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(MikaColors.NeonCyan.copy(alpha = 0.14f))
                                .border(1.dp, MikaColors.NeonCyan.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                                .clickable(onClick = onOpenSystemTtsSettings)
                                .padding(horizontal = 8.dp)
                        ) {
                            Text(
                                text = "SET_OS_TTS",
                                color = MikaColors.NeonCyan,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 9.sp
                            )
                        }
                        // ✨ SETTINGS COG (v1.5.0) — System Settings, Academy replay & links.
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .width(26.dp)
                                .height(26.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(MikaColors.DimText.copy(alpha = 0.12f))
                                .border(1.dp, MikaColors.DimText.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                                .clickable { showSettingsSheet = true }
                        ) {
                            MikaCyberIcon(type = CyberIconType.SETTINGS_COG, tint = MikaColors.CyanWhite, size = 13.dp)
                        }
                    }
                }

                // Phasing Page Viewport: VOICE | MODULES | CARTRIDGE BAY
                AnimatedContent(
                    targetState = activeTab,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    transitionSpec = {
                        val dir = if (targetState > initialState) 1 else -1
                        (fadeIn(tween(240)) +
                            scaleIn(initialScale = 0.965f, animationSpec = tween(240)) +
                            slideInHorizontally(tween(240)) { width -> dir * (width / 16) })
                            .togetherWith(
                                fadeOut(tween(150)) +
                                    scaleOut(targetScale = 1.015f, animationSpec = tween(150)) +
                                    slideOutHorizontally(tween(150)) { width -> -dir * (width / 24) }
                            )
                    },
                    label = "purrTabPhasing"
                ) { targetTabIndex ->
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 92.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        when (PurrTab.entries[targetTabIndex]) {
                            PurrTab.VOICE -> {
                                // Live Waveform + Voice Test Bench (dedicated to Voice tab)
                                item {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        LiveWaveformMonitor(
                                            waveformBins = telemetry.waveformBins,
                                            activeChunkLabel = telemetry.activeChunkLabel,
                                            engineSummary = telemetry.lastEngineSummary,
                                            sentimentLabel = telemetry.activeSentimentLabel
                                        )

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            OutlinedTextField(
                                                value = testText,
                                                onValueChange = { viewModel.updateTestInputText(it) },
                                                placeholder = {
                                                    Text(
                                                        "Type anything to test the selected voice...",
                                                        fontFamily = FontFamily.Monospace,
                                                        fontSize = 10.sp,
                                                        color = MikaColors.DimText
                                                    )
                                                },
                                                maxLines = 2,
                                                textStyle = TextStyle(
                                                    color = MikaColors.CyanWhite,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontSize = 11.sp
                                                ),
                                                colors = OutlinedTextFieldDefaults.colors(
                                                    focusedBorderColor = MikaColors.NeonCyan,
                                                    unfocusedBorderColor = MikaColors.NeonCyan.copy(alpha = 0.35f),
                                                    focusedContainerColor = MikaColors.PanelBg,
                                                    unfocusedContainerColor = MikaColors.PanelBg
                                                ),
                                                modifier = Modifier.weight(1f)
                                            )
                                            Row(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(
                                                        if (isPlaying) MikaColors.DangerRed.copy(alpha = 0.22f)
                                                        else MikaColors.TerminalGreen.copy(alpha = 0.18f)
                                                    )
                                                    .border(
                                                        1.dp,
                                                        if (isPlaying) MikaColors.DangerRed else MikaColors.TerminalGreen,
                                                        RoundedCornerShape(6.dp)
                                                    )
                                                    .clickable {
                                                        if (isPlaying) viewModel.stopAudition() else viewModel.auditionText()
                                                    }
                                                    .padding(horizontal = 12.dp, vertical = 14.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                MikaCyberIcon(
                                                    type = if (isPlaying) CyberIconType.STOP else CyberIconType.PLAY,
                                                    tint = if (isPlaying) MikaColors.DangerRed else MikaColors.TerminalGreen,
                                                    size = 11.dp
                                                )
                                                Text(
                                                    text = if (isPlaying) "STOP" else "TEST",
                                                    color = if (isPlaying) MikaColors.DangerRed else MikaColors.TerminalGreen,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontWeight = FontWeight.Black,
                                                    fontSize = 11.sp
                                                )
                                            }
                                        }
                                        if (isPlaying && auditionProgress != null) {
                                            // ✨ TRUST RENEWAL UX (v1.8.1): live per-chunk audition progress —
                                            // slow neural engines (Qwen3 ≈ 30s/clip) never look frozen mid-test.
                                            Text(
                                                text = auditionProgress ?: "",
                                                color = MikaColors.NeonCyan,
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 9.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }

                                // Step 1: Engine Picker (Slotted TTS Cartridges + LAN Proxy)
                                item {
                                    MikaGlowPanel(accent = MikaColors.NeonCyan) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "> 1. CHOOSE_TTS_ENGINE",
                                                color = MikaColors.NeonCyan,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Black,
                                                fontSize = 11.sp
                                            )
                                            Text(
                                                text = "+ CARTRIDGE BAY (${slottedSpecs.size}/${viewModel.mainCartridgeCatalog.size})",
                                                color = MikaColors.TerminalGreen,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 9.sp,
                                                modifier = Modifier.clickable {
                                                    cartridgeBayCategory = CartridgeCategory.TTS
                                                    activeTab = PurrTab.CARTRIDGE_BAY.ordinal
                                                }
                                            )
                                        }
                                        Text(
                                            text = "// Slam or download TTS engines (Kokoro, KittenTTS, Piper packs, Supertonic, Pocket, ZipVoice) in Cartridge Bay.",
                                            color = MikaColors.DimText,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 9.5.sp,
                                            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
                                        )

                                        // ✨ SLEEK ENGINE DROPDOWN (v1.5.0) — replaces the pill wall.
                                        EngineDropdownSelector(
                                            slottedSpecs = slottedSpecs,
                                            activeCartridgeId = activeCartridgeId,
                                            forgeState = forgeState,
                                            onSelect = { viewModel.selectEngineCartridge(it) }
                                        )

                                        if (slottedSpecs.isEmpty()) {
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(MikaColors.MikaAmber.copy(alpha = 0.14f))
                                                    .border(1.dp, MikaColors.MikaAmber, RoundedCornerShape(6.dp))
                                                    .clickable {
                                                        cartridgeBayCategory = CartridgeCategory.TTS
                                                        activeTab = PurrTab.CARTRIDGE_BAY.ordinal
                                                    }
                                                    .padding(10.dp),
                                                horizontalArrangement = Arrangement.Center,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                MikaCyberIcon(type = CyberIconType.ALERT_TRIANGLE, tint = MikaColors.MikaAmber, size = 13.dp)
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = "ALL TTS CARTRIDGES EJECTED - OPEN CARTRIDGE BAY",
                                                    color = MikaColors.MikaAmber,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontWeight = FontWeight.Black,
                                                    fontSize = 10.sp
                                                )
                                            }
                                        }
                                    }
                                }

                                // Step 2: Stylized Voice Dropdown (Single Preferred Voice when Dual-Voice Split is off)
                                if (!activeProfile.enableDualVoiceSplit) {
                                    item {
                                        val engineLabel = slottedSpecs.firstOrNull { it.id == activeCartridgeId }?.name
                                            ?: if (activeCartridgeId == "proxy_lan") "LAN PROXY" else "NO ENGINE"
                                        val selectedPrimary = voicesForSelectedEngine.firstOrNull { it.id == activeProfile.primaryVoiceId }
                                            ?: voicesForSelectedEngine.firstOrNull()

                                        MikaGlowPanel(accent = MikaColors.TerminalGreen) {
                                            MikaVoiceDropdownSelector(
                                                label = "> 2. PREFERRED_VOICE // $engineLabel (${voicesForSelectedEngine.size})",
                                                voices = voicesForSelectedEngine,
                                                selectedVoice = selectedPrimary,
                                                onSelectVoice = { v -> viewModel.selectPrimaryVoice(v) },
                                                onSampleVoice = { v -> viewModel.auditionText(overrideVoice = v) },
                                                accent = MikaColors.TerminalGreen,
                                                onRefreshAction = if (activeCartridgeId == "rom_core_os") {
                                                    { viewModel.refreshSystemVoices() }
                                                } else {
                                                    null
                                                },
                                                    emptyHint = if (slottedSpecs.isEmpty()) {
                                                        "No TTS cartridge slotted - open Cartridge Bay to slam ROM-01 or a neural cartridge."
                                                    } else {
                                                        "No voices found for $engineLabel."
                                                    }
                                            )

                                            AnimatedVisibility(
                                                visible = selectedPrimary?.id == "qwen3_design_custom" ||
                                                    activeProfile.primaryVoiceId == "qwen3_design_custom"
                                            ) {
                                                Column(
                                                    modifier = Modifier.padding(top = 10.dp),
                                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    Text(
                                                        text = "> QWEN3_1.7B_VOICEDESIGN_PROMPT",
                                                        color = MikaColors.NeonPink,
                                                        fontFamily = FontFamily.Monospace,
                                                        fontWeight = FontWeight.Black,
                                                        fontSize = 10.sp
                                                    )
                                                    OutlinedTextField(
                                                        value = activeProfile.voiceDesignPrompt,
                                                        onValueChange = { viewModel.updateVoiceDesignPrompt(it) },
                                                        minLines = 2,
                                                        placeholder = {
                                                            Text(
                                                                "Describe pitch, warmth, age, cadence...",
                                                                fontSize = 10.sp,
                                                                fontFamily = FontFamily.Monospace
                                                            )
                                                        },
                                                        textStyle = TextStyle(
                                                            color = MikaColors.WarmCream,
                                                            fontFamily = FontFamily.Monospace,
                                                            fontSize = 11.sp
                                                        ),
                                                        colors = OutlinedTextFieldDefaults.colors(
                                                            focusedBorderColor = MikaColors.NeonPink,
                                                            unfocusedBorderColor = MikaColors.NeonPink.copy(alpha = 0.35f),
                                                            focusedContainerColor = MikaColors.DeepWellBg,
                                                            unfocusedContainerColor = MikaColors.DeepWellBg
                                                        ),
                                                        modifier = Modifier.fillMaxWidth()
                                                    )
                                                    FlowRow(
                                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                                    ) {
                                                        listOf(
                                                            "Warm Anime" to "Warm, expressive young woman with crisp close-mic clarity and playful cadence",
                                                            "Cinema Narrator" to "Deep, resonant baritone narrator with calm studio warmth and slow deliberate pacing",
                                                            "Soft ASMR" to "Soft breathy female whisper near microphone, gentle soothing intonation"
                                                        ).forEach { (chipLabel, chipPrompt) ->
                                                            MikaTagPill(
                                                                label = chipLabel,
                                                                selected = activeProfile.voiceDesignPrompt == chipPrompt,
                                                                activeColor = MikaColors.NeonPink,
                                                                onClick = { viewModel.updateVoiceDesignPrompt(chipPrompt) }
                                                            )
                                                        }
                                                        MikaTagPill(
                                                            label = "OPEN VOICEDESIGN STUDIO",
                                                            selected = true,
                                                            activeColor = MikaColors.NeonCyan,
                                                            leadingIcon = CyberIconType.WRENCH_TOOL,
                                                            onClick = {
                                                                viewModel.repository.setCartridgeSlotted("tool_voice_design", true)
                                                                requestedOsToolId = "tool_voice_design"
                                                                activeTab = PurrTab.MODULES.ordinal
                                                            }
                                                        )
                                                    }
                                                }
                                            }

                                            HorizontalDivider(
                                                modifier = Modifier.padding(vertical = 10.dp),
                                                color = MikaColors.TerminalGreen.copy(alpha = 0.22f)
                                            )

                                            VoicePacingPitchSection(
                                                activeProfile = activeProfile,
                                                viewModel = viewModel,
                                                includeNarratorMultipliers = false
                                            )
                                        }
                                    }
                                }

                                // Optional Feature: Dual Narrator & "Dialogue" Voices
                                item {
                                    MikaGlowPanel(accent = MikaColors.NeonPurple) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    MikaCyberIcon(type = CyberIconType.DUAL_MASKS, tint = MikaColors.NeonPurple, size = 14.dp)
                                                    Text(
                                                        text = "SPLIT NARRATOR & \"DIALOGUE\" VOICES",
                                                        color = MikaColors.NeonPurple,
                                                        fontFamily = FontFamily.Monospace,
                                                        fontWeight = FontWeight.Black,
                                                        fontSize = 11.sp
                                                    )
                                                }
                                                Text(
                                                    text = "Optional: use one voice for narration and a second character voice inside \"quotes\".",
                                                    color = MikaColors.DimText,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontSize = 9.5.sp
                                                )
                                            }
                                            Switch(
                                                checked = activeProfile.enableDualVoiceSplit,
                                                onCheckedChange = { enabled ->
                                                    viewModel.updateActiveProfile { p -> p.copy(enableDualVoiceSplit = enabled) }
                                                },
                                                colors = SwitchDefaults.colors(
                                                    checkedThumbColor = MikaColors.VoidBg,
                                                    checkedTrackColor = MikaColors.NeonPurple
                                                )
                                            )
                                        }

                                        AnimatedVisibility(visible = activeProfile.enableDualVoiceSplit) {
                                            Column(
                                                modifier = Modifier.padding(top = 10.dp),
                                                verticalArrangement = Arrangement.spacedBy(10.dp)
                                            ) {
                                                val selectableVoices = allVoices.filter {
                                                    viewModel.repository.isCartridgeSlotted(it.cartridgeId)
                                                }
                                                val primaryVoiceObj = selectableVoices.firstOrNull { it.id == activeProfile.primaryVoiceId }
                                                    ?: selectableVoices.firstOrNull()
                                                val dialogueVoiceObj = selectableVoices.firstOrNull { it.id == activeProfile.dialogueVoiceId }
                                                    ?: selectableVoices.firstOrNull()

                                                MikaVoiceDropdownSelector(
                                                    label = "> 1. NARRATOR_VOICE (PROSE & *ACTIONS*)",
                                                    voices = selectableVoices,
                                                    selectedVoice = primaryVoiceObj,
                                                    onSelectVoice = { v -> viewModel.selectPrimaryVoice(v) },
                                                    onSampleVoice = { v -> viewModel.auditionText(overrideVoice = v) },
                                                    accent = MikaColors.NeonCyan,
                                                    onRefreshAction = if (viewModel.repository.isCartridgeSlotted("rom_core_os")) {
                                                        { viewModel.refreshSystemVoices() }
                                                    } else {
                                                        null
                                                    }
                                                )

                                                MikaVoiceDropdownSelector(
                                                    label = "> 2. DIALOGUE_VOICE (INSIDE \"QUOTES\")",
                                                    voices = selectableVoices,
                                                    selectedVoice = dialogueVoiceObj,
                                                    onSelectVoice = { v -> viewModel.selectDialogueVoice(v) },
                                                    onSampleVoice = { v -> viewModel.auditionText(overrideVoice = v) },
                                                    accent = MikaColors.NeonPink
                                                )

                                                Text(
                                                    text = "*ACTION* BLOCKS:",
                                                    color = MikaColors.NeonPurple,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 9.sp
                                                )
                                                FlowRow(
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    ActionHandlingMode.entries.forEach { mode ->
                                                        MikaTagPill(
                                                            label = mode.label,
                                                            selected = activeProfile.actionHandlingMode == mode,
                                                            activeColor = MikaColors.NeonPurple,
                                                            onClick = {
                                                                viewModel.updateActiveProfile { p -> p.copy(actionHandlingMode = mode) }
                                                            }
                                                        )
                                                    }
                                                }

                                                HorizontalDivider(
                                                    modifier = Modifier.padding(vertical = 4.dp),
                                                    color = MikaColors.NeonPurple.copy(alpha = 0.25f)
                                                )

                                                VoicePacingPitchSection(
                                                    activeProfile = activeProfile,
                                                    viewModel = viewModel,
                                                    includeNarratorMultipliers = true
                                                )
                                            }
                                        }
                                    }
                                }

                                // Expandable LAN Proxy Endpoint (Advanced Voice Network)
                                item {
                                    ExpandableTerminalSection(
                                        title = "ADVANCED: LAN OPENAI / HEAVY-MODEL PROXY",
                                        subtitle = "Optional remote network endpoint URL",
                                        expanded = showAdvancedProxy,
                                        onToggle = { showAdvancedProxy = !showAdvancedProxy },
                                        accent = MikaColors.NeonCyan
                                    ) {
                                        var proxyUrl by remember(activeProfile.id) { mutableStateOf(activeProfile.proxyConfig.openAiBaseUrl) }

                                        // ✨ HEAVY-MODEL PRESETS (v1.6.0): one tap fills endpoint/model/voice.
                                        Text(
                                            text = "> HEAVY_MODEL_PRESETS // TAP_TO_APPLY",
                                            color = MikaColors.NeonPurple,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 9.sp
                                        )
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            PurrProxyPresets.PRESETS.forEach { preset ->
                                                MikaTagPill(
                                                    label = preset.label,
                                                    selected = proxyUrl == preset.baseUrl,
                                                    activeColor = MikaColors.NeonPurple,
                                                    onClick = {
                                                        proxyUrl = preset.baseUrl
                                                        viewModel.updateActiveProfile { p ->
                                                            p.copy(
                                                                proxyConfig = p.proxyConfig.copy(
                                                                    openAiBaseUrl = preset.baseUrl,
                                                                    openAiModel = preset.model,
                                                                    openAiVoice = preset.voice,
                                                                    useOpenAiEndpoint = true
                                                                )
                                                            )
                                                        }
                                                    }
                                                )
                                            }
                                        }
                                        Text(
                                            text = "// ${PurrProxyPresets.PRESETS.first().note} — edit URL/voice below to fit your server.",
                                            color = MikaColors.DimText,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 8.5.sp
                                        )

                                        OutlinedTextField(
                                            value = proxyUrl,
                                            onValueChange = {
                                                proxyUrl = it
                                                viewModel.updateActiveProfile { p -> p.copy(proxyConfig = p.proxyConfig.copy(openAiBaseUrl = it, useOpenAiEndpoint = true)) }
                                            },
                                            label = { Text("LAN OPENAI / KOKORO-FASTAPI URL", fontSize = 9.sp, fontFamily = FontFamily.Monospace) },
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
                                    }
                                }
                            }

                            PurrTab.MODULES -> {
                                item {
                                    PurrRulebookTab(
                                        forgeState = forgeState,
                                        activeProfile = activeProfile,
                                        previewChunks = previewChunks,
                                        isPlayingPreview = isPlaying,
                                        viewModel = viewModel,
                                        onOpenSaveDialog = { showSaveProfileDialog = true },
                                        onOpenCloneStudio = { showCloneStudio = true },
                                        onOpenCartridgeBayTools = {
                                            cartridgeBayCategory = CartridgeCategory.TOOLS
                                            activeTab = PurrTab.CARTRIDGE_BAY.ordinal
                                        },
                                        onOpenCartridgeBayTts = {
                                            cartridgeBayCategory = CartridgeCategory.TTS
                                            activeTab = PurrTab.CARTRIDGE_BAY.ordinal
                                        },
                                        requestedToolId = requestedOsToolId,
                                        onConsumeRequestedTool = { requestedOsToolId = null }
                                    )
                                }
                            }

                            PurrTab.CARTRIDGE_BAY -> {
                                item {
                                    ModelCatalogTab(
                                        forgeState = forgeState,
                                        modelCatalog = viewModel.modelCatalog,
                                        vramRemainingSec = vramSec,
                                        selectedCategory = cartridgeBayCategory,
                                        onSelectCategory = { cartridgeBayCategory = it },
                                        viewModel = viewModel,
                                        onJumpToVoiceSelector = { cartridgeId ->
                                            viewModel.selectEngineCartridge(cartridgeId)
                                            activeTab = PurrTab.VOICE.ordinal
                                        },
                                        onOpenCloneStudio = { showCloneStudio = true },
                                        onOpenModulesTab = { activeTab = PurrTab.MODULES.ordinal }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Floating Glassmorphic Icon-Only Bottom HUD Capsule (overlaid over scroll area)
            MikaBottomHudBar(
                activeTab = activeTab,
                activeToolCount = activeToolCount,
                slottedTtsCount = slottedSpecs.size,
                onSelectTab = { activeTab = it },
                modifier = Modifier.align(Alignment.BottomCenter)
            )

            // Dramatic ROM Cartridge Slot / Eject HUD Overlay
            CartridgeSlamOverlay(
                spec = cartridgeAnim?.spec,
                isInsert = cartridgeAnim?.isInsert ?: true,
                onDismiss = { viewModel.dismissCartridgeAnim() }
            )

            // Floating Top TerminalToastBanner
            TerminalToastBanner(
                message = toastMsg,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 8.dp)
            )
        }

        if (showSaveProfileDialog) {
            SaveProfileDialog(
                onSave = { name ->
                    viewModel.saveAsNewProfile(name)
                    showSaveProfileDialog = false
                },
                onDismiss = { showSaveProfileDialog = false }
            )
        }

        if (showCloneStudio) {
            val cloneImportStatus by viewModel.cloneImportStatus.collectAsState()
            val isImportingClone by viewModel.isImportingClone.collectAsState()
            val importedCloneWav by viewModel.importedCloneWavPath.collectAsState()
            val cleanImportedAudio by viewModel.cleanImportedAudio.collectAsState()
            VoiceCloneStudioSheet(
                isRecording = isRecordingClone,
                recordingProgress = recordingProgress,
                lastRecordedWavPath = lastRecordedWav,
                isImporting = isImportingClone,
                importStatus = cloneImportStatus,
                importedWavPath = importedCloneWav,
                cleanAudio = cleanImportedAudio,
                onToggleCleanAudio = { viewModel.toggleCleanImportedAudio(it) },
                onImportClip = { uri -> viewModel.importCloneClip(uri) },
                onRecordFiveSeconds = { viewModel.recordFiveSecondReferenceSample() },
                onRegisterClone = { name, family, gender, transcript, tags, cloneEngineId ->
                    viewModel.registerClonedVoice(
                        displayName = name,
                        family = family,
                        gender = gender,
                        transcript = transcript,
                        tagsRaw = tags,
                        cloneEngineId = cloneEngineId
                    )
                },
                onDismiss = { showCloneStudio = false }
            )
        }

        // ✨ SYSTEM SETTINGS SHEET (v1.5.0) — opened from the header cog.
        if (showSettingsSheet) {
            MikaSettingsSheet(
                versionName = com.projectmika.purrtts.BuildConfig.VERSION_NAME,
                versionCode = com.projectmika.purrtts.BuildConfig.VERSION_CODE,
                qwen3GpuOffload = forgeState.qwen3GpuOffload,
                onQwen3GpuOffloadToggle = { viewModel.setQwen3GpuOffload(it) },
                onReplayTutorial = {
                    academyPatchnotesOnly = false
                    showAcademy = true
                },
                onDismiss = { showSettingsSheet = false }
            )
        }

        // ✨ MIKA'S ACADEMY OVERLAY (v1.5.0) — full-screen walkthrough / patchnotes popup.
        if (showAcademy) {
            MikaAcademyOverlay(
                versionName = com.projectmika.purrtts.BuildConfig.VERSION_NAME,
                startAtPatchnotes = academyPatchnotesOnly,
                onFinish = {
                    showAcademy = false
                    viewModel.markTutorialSeen()
                }
            )
        }
    }
}

/**
 * ✨ SLEEK ENGINE DROPDOWN SELECTOR (v1.5.0) ✨
 * Collapsed row = the active engine (ROM code + name + NPU/GPU/Piper suffix), expanding into a
 * color-coded tray of every slotted cartridge + LAN PROXY. Replaces the old selectable pill wall.
 */
@Composable
private fun EngineDropdownSelector(
    slottedSpecs: List<ModelBundleSpec>,
    activeCartridgeId: String?,
    forgeState: PurrForgeState,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    fun chipSuffixFor(spec: ModelBundleSpec): String = when (spec.id) {
        "rom_kokoro_sherpa" -> {
            val npuActive = "rom_kokoro_npu_g2p" in forgeState.enabledSubCartridges &&
                forgeState.modelStates["rom_kokoro_npu_g2p"]?.state == DownloadState.INSTALLED
            val gpuActive = "rom_kokoro_litert" in forgeState.enabledSubCartridges &&
                forgeState.modelStates["rom_kokoro_litert"]?.state == DownloadState.INSTALLED
            when {
                npuActive && gpuActive -> " [NPU+GPU]"
                npuActive -> " [NPU]"
                gpuActive -> " [GPU]"
                else -> ""
            }
        }
        "rom_piper" -> {
            val extraPiperCount = forgeState.enabledSubCartridges.count {
                it.startsWith("sub_piper_") && forgeState.modelStates[it]?.state == DownloadState.INSTALLED
            }
            if (extraPiperCount > 0) " [+$extraPiperCount]" else ""
        }
        else -> ""
    }

    val selectedSpec = slottedSpecs.firstOrNull { it.id == activeCartridgeId }
    val selectedIsProxy = activeCartridgeId == "proxy_lan"
    val selectedAccent = when {
        selectedIsProxy -> MikaColors.NeonPurple
        selectedSpec != null -> Color(selectedSpec.accentHex)
        else -> MikaColors.DimText
    }
    val selectedLabel = when {
        selectedIsProxy -> "LAN PROXY"
        selectedSpec != null -> "${selectedSpec.romCode}: ${selectedSpec.name}${chipSuffixFor(selectedSpec)}"
        else -> "NO_ENGINE_SELECTED"
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Collapsed row: the active engine.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(selectedAccent.copy(alpha = 0.14f))
                .border(1.dp, selectedAccent, RoundedCornerShape(6.dp))
                .clickable { expanded = !expanded }
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MikaCyberIcon(type = CyberIconType.BUS_PLUG, tint = selectedAccent, size = 14.dp)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = selectedLabel,
                    color = selectedAccent,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = when {
                        selectedIsProxy -> "// LAN OpenAI-compatible endpoint"
                        selectedSpec != null -> "// ${selectedSpec.subtitle}"
                        else -> "// Slot an engine in Cartridge Bay"
                    },
                    color = MikaColors.DimText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            MikaCyberIcon(
                type = if (expanded) CyberIconType.CHEVRON_DOWN else CyberIconType.CHEVRON_RIGHT,
                tint = selectedAccent,
                size = 12.dp
            )
        }

        // Expanded tray: every slotted engine + LAN PROXY.
        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(MikaColors.DeepWellBg)
                    .border(1.dp, MikaColors.NeonCyan.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                    .padding(vertical = 4.dp)
            ) {
                slottedSpecs.forEach { spec ->
                    val specAccent = Color(spec.accentHex)
                    val isSelected = activeCartridgeId == spec.id
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (isSelected) specAccent.copy(alpha = 0.16f) else Color.Transparent)
                            .clickable {
                                onSelect(spec.id)
                                expanded = false
                            }
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MikaCyberIcon(
                            type = if (isSelected) CyberIconType.CHECK else CyberIconType.BUS_PLUG,
                            tint = specAccent,
                            size = 12.dp
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${spec.romCode}: ${spec.name}${chipSuffixFor(spec)}",
                                color = if (isSelected) specAccent else MikaColors.CyanWhite,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.5.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "// ${spec.subtitle}",
                                color = MikaColors.DimText,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 8.5.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (isSelected) {
                            Text(
                                text = "ACTIVE",
                                color = specAccent,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Black,
                                fontSize = 8.5.sp,
                                letterSpacing = 1.0.sp
                            )
                        }
                    }
                }
                // LAN PROXY tray row.
                val proxySelected = activeCartridgeId == "proxy_lan"
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (proxySelected) MikaColors.NeonPurple.copy(alpha = 0.16f) else Color.Transparent)
                        .clickable {
                            onSelect("proxy_lan")
                            expanded = false
                        }
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MikaCyberIcon(
                        type = if (proxySelected) CyberIconType.CHECK else CyberIconType.BUS_PLUG,
                        tint = MikaColors.NeonPurple,
                        size = 12.dp
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "LAN PROXY",
                            color = if (proxySelected) MikaColors.NeonPurple else MikaColors.CyanWhite,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.5.sp
                        )
                        Text(
                            text = "// LAN OpenAI-compatible endpoint",
                            color = MikaColors.DimText,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 8.5.sp
                        )
                    }
                    if (proxySelected) {
                        Text(
                            text = "ACTIVE",
                            color = MikaColors.NeonPurple,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Black,
                            fontSize = 8.5.sp,
                            letterSpacing = 1.0.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VoicePacingPitchSection(
    activeProfile: PurrProfile,
    viewModel: TtsForgeViewModel,
    includeNarratorMultipliers: Boolean
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "> VOICE_PACING_&_PITCH",
                color = MikaColors.NeonCyan,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Black,
                fontSize = 10.sp
            )
            MikaTagPill(
                label = "RESET (1.00x)",
                selected = true,
                activeColor = MikaColors.MikaAmber,
                leadingIcon = CyberIconType.REFRESH,
                onClick = {
                    viewModel.updateActiveProfile {
                        it.copy(
                            baseSpeed = 1.0f,
                            basePitch = 1.0f,
                            narrationRateMultiplier = 0.94f,
                            narrationVolumeMultiplier = 0.80f
                        )
                    }
                }
            )
        }

        SteppedSliderRow(
            label = "PACING (SPEED)",
            icon = CyberIconType.SPEED_GAUGE,
            valueText = "${"%.2f".format(activeProfile.baseSpeed)}x",
            value = activeProfile.baseSpeed,
            range = 0.70f..1.45f,
            step = 0.05f,
            accent = MikaColors.NeonCyan,
            onChange = { v -> viewModel.updateActiveProfile { it.copy(baseSpeed = v) } }
        )

        SteppedSliderRow(
            label = "PITCH",
            icon = CyberIconType.PITCH_TUNER,
            valueText = "${"%.2f".format(activeProfile.basePitch)}x",
            value = activeProfile.basePitch,
            range = 0.88f..1.12f,
            step = 0.02f,
            accent = MikaColors.NeonPink,
            onChange = { v -> viewModel.updateActiveProfile { it.copy(basePitch = v) } }
        )

        if (includeNarratorMultipliers) {
            SimpleSliderRow(
                label = "NARRATOR_SPEED_MULTIPLIER",
                icon = CyberIconType.BOOK_NARRATOR,
                valueText = "${"%.2f".format(activeProfile.narrationRateMultiplier)}x",
                value = activeProfile.narrationRateMultiplier,
                range = 0.75f..1.15f,
                accent = MikaColors.NeonPurple,
                onChange = { v -> viewModel.updateActiveProfile { it.copy(narrationRateMultiplier = v) } }
            )
            SimpleSliderRow(
                label = "NARRATOR_VOLUME",
                icon = CyberIconType.BOOK_NARRATOR,
                valueText = "${(activeProfile.narrationVolumeMultiplier * 100).roundToInt()}%",
                value = activeProfile.narrationVolumeMultiplier,
                range = 0.50f..1.00f,
                accent = MikaColors.NeonPurple,
                onChange = { v -> viewModel.updateActiveProfile { it.copy(narrationVolumeMultiplier = v) } }
            )
        }
    }
}

@Composable
private fun SteppedSliderRow(
    label: String,
    icon: CyberIconType? = null,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    accent: Color,
    onChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                if (icon != null) {
                    MikaCyberIcon(type = icon, tint = accent, size = 12.dp)
                }
                Text(label, color = MikaColors.CyanWhite, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 10.sp)
            }
            val stepLabel = "%.2f".format(step)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                MikaTagPill(
                    label = "-$stepLabel",
                    selected = false,
                    activeColor = accent,
                    inactiveColor = accent,
                    onClick = { onChange((value - step).coerceIn(range.start, range.endInclusive)) }
                )
                Text(valueText, color = accent, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black, fontSize = 10.sp)
                MikaTagPill(
                    label = "+$stepLabel",
                    selected = false,
                    activeColor = accent,
                    inactiveColor = accent,
                    onClick = { onChange((value + step).coerceIn(range.start, range.endInclusive)) }
                )
            }
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = accent,
                activeTrackColor = accent,
                inactiveTrackColor = accent.copy(alpha = 0.22f)
            )
        )
    }
}

@Composable
private fun SimpleSliderRow(
    label: String,
    icon: CyberIconType? = null,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    accent: Color,
    onChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                if (icon != null) {
                    MikaCyberIcon(type = icon, tint = accent, size = 12.dp)
                }
                Text(label, color = MikaColors.CyanWhite, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 10.sp)
            }
            Text(valueText, color = accent, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black, fontSize = 10.sp)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = accent,
                activeTrackColor = accent,
                inactiveTrackColor = accent.copy(alpha = 0.22f)
            )
        )
    }
}

@Composable
private fun SaveProfileDialog(
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MikaColors.PanelBg)
                .border(1.dp, MikaColors.NeonPink, RoundedCornerShape(12.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "> SAVE_VIBE_PRESET",
                color = MikaColors.NeonPink,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Black,
                fontSize = 12.sp
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text("e.g. Late Night Storytime", fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
                singleLine = true,
                textStyle = TextStyle(color = MikaColors.CyanWhite, fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MikaColors.NeonPink,
                    unfocusedBorderColor = MikaColors.NeonPink.copy(alpha = 0.4f),
                    focusedContainerColor = MikaColors.DeepWellBg,
                    unfocusedContainerColor = MikaColors.DeepWellBg
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                MikaTagPill(label = "CANCEL", selected = true, activeColor = MikaColors.DimText, onClick = onDismiss)
                Spacer(modifier = Modifier.width(8.dp))
                MikaTagPill(label = "SAVE", selected = true, activeColor = MikaColors.NeonPink, onClick = { onSave(name) })
            }
        }
    }
}
