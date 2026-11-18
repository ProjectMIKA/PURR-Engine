package com.projectmika.purrtts.ui.forge

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.projectmika.purrtts.core.purr.PurrProfile
import com.projectmika.purrtts.core.purr.VoiceDescriptor
import com.projectmika.purrtts.core.purr.VoiceGender
import com.projectmika.purrtts.ui.theme.CartridgeSlamOverlay
import com.projectmika.purrtts.ui.theme.CyberDropdown
import com.projectmika.purrtts.ui.theme.ExpandableTerminalSection
import com.projectmika.purrtts.ui.theme.LiveWaveformMonitor
import com.projectmika.purrtts.ui.theme.MikaColors
import com.projectmika.purrtts.ui.theme.MikaGlowPanel
import com.projectmika.purrtts.ui.theme.MikaStatusBadge
import com.projectmika.purrtts.ui.theme.MikaTagPill
import com.projectmika.purrtts.ui.theme.MikaTerminalStage
import com.projectmika.purrtts.ui.theme.TerminalToastBanner
import kotlin.math.roundToInt

private enum class PurrTab(val title: String) {
    VOICE_ENGINE("> VOICE & ENGINE"),
    PURR_ENGINE("> PURR ENGINE"),
    CARTRIDGE_BAY("> CARTRIDGE BAY")
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
    val isRecordingClone by viewModel.isRecordingClone.collectAsState()
    val recordingProgress by viewModel.recordingProgress.collectAsState()
    val lastRecordedWav by viewModel.lastRecordedWavPath.collectAsState()

    val activeProfile = forgeState.profiles.firstOrNull { it.id == forgeState.activeProfileId }
        ?: forgeState.profiles.first()
    val allVoices = remember(forgeState.clonedVoices, discoveredSystemVoices) {
        viewModel.allAvailableVoices()
    }

    var activeTab by remember {
        mutableIntStateOf(initialTabIndex.coerceIn(0, PurrTab.entries.lastIndex))
    }
    var showSaveProfileDialog by remember { mutableStateOf(false) }
    var showCloneStudio by remember { mutableStateOf(false) }
    var showAdvancedVoice by remember { mutableStateOf(false) }

    // Simple Tab 1 filters
    var voiceSearch by remember { mutableStateOf("") }
    var genderFilter by remember { mutableStateOf<VoiceGender?>(null) }

    val activeCartridgeId = activeProfile.selectedCartridgeId
    val voicesForSelectedEngine = remember(allVoices, activeCartridgeId, voiceSearch, genderFilter) {
        allVoices.filter { v ->
            if (v.cartridgeId != activeCartridgeId) return@filter false
            if (genderFilter != null && v.gender != genderFilter) return@filter false
            if (voiceSearch.isNotBlank()) {
                val q = voiceSearch.lowercase()
                val matchName = v.displayName.lowercase().contains(q) || v.id.lowercase().contains(q)
                val matchLang = v.languageCode.lowercase().contains(q)
                if (!matchName && !matchLang) return@filter false
            }
            true
        }
    }

    val previewChunks = remember(testText, activeProfile, forgeState.customPhoneticRules) {
        viewModel.previewChunks(testText, activeProfile, forgeState.customPhoneticRules)
    }

    val slottedSpecs = remember(forgeState.slottedCartridges, viewModel.modelCatalog) {
        viewModel.modelCatalog.filter { it.id in forgeState.slottedCartridges }
    }

    MikaTerminalStage {
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding(),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 36.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 1. Clean Top HUD
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "> PURR_TTS // SYSTEM ENGINE",
                                color = MikaColors.TerminalGreen,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Black,
                                fontSize = 15.sp,
                                letterSpacing = 1.2.sp
                            )
                            Text(
                                text = "// Preset: ${activeProfile.name}",
                                color = MikaColors.TerminalGreen.copy(alpha = 0.65f),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            MikaStatusBadge(
                                badge = telemetry.badge,
                                vramCountdownSec = vramSec
                            )
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(MikaColors.NeonCyan.copy(alpha = 0.14f))
                                    .border(1.dp, MikaColors.NeonCyan.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                                    .clickable(onClick = onOpenSystemTtsSettings)
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "SET_OS_TTS",
                                    color = MikaColors.NeonCyan,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 9.sp
                                )
                            }
                        }
                    }
                }

                // 2. Live Waveform + Quick Voice Test Bench
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
                                        "Type anything to hear PURR + selected voice...",
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
                            Box(
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
                                    .padding(horizontal = 14.dp, vertical = 14.dp)
                            ) {
                                Text(
                                    text = if (isPlaying) "■ STOP" else "▶ TEST",
                                    color = if (isPlaying) MikaColors.DangerRed else MikaColors.TerminalGreen,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Black,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }

                // 3. 3-Tab Selector
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        PurrTab.entries.forEachIndexed { index, tab ->
                            val isSelected = activeTab == index
                            val tabColor = when (tab) {
                                PurrTab.VOICE_ENGINE -> MikaColors.NeonCyan
                                PurrTab.PURR_ENGINE -> MikaColors.NeonPink
                                PurrTab.CARTRIDGE_BAY -> MikaColors.TerminalGreen
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (isSelected) tabColor.copy(alpha = 0.16f) else MikaColors.PanelBg)
                                    .border(
                                        1.dp,
                                        if (isSelected) tabColor else Color.White.copy(alpha = 0.20f),
                                        RoundedCornerShape(4.dp)
                                    )
                                    .clickable { activeTab = index }
                                    .padding(vertical = 9.dp, horizontal = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = tab.title,
                                    color = if (isSelected) tabColor else Color.White.copy(alpha = 0.55f),
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 9.sp,
                                    letterSpacing = 0.4.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                // 4. Tab Content
                when (PurrTab.entries[activeTab]) {
                    PurrTab.VOICE_ENGINE -> {
                        // Step 1: Engine Picker (Built-in Device Speech + Slotted Cartridges + LAN Proxy)
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
                                        text = "+ CARTRIDGES (${slottedSpecs.size}/${viewModel.modelCatalog.size})",
                                        color = MikaColors.TerminalGreen,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 9.sp,
                                        modifier = Modifier.clickable { activeTab = PurrTab.CARTRIDGE_BAY.ordinal }
                                    )
                                }
                                Text(
                                    text = "// Built-in Android Speech works instantly. Download & slam ROM cartridges in Tab 3 for offline neural voices.",
                                    color = MikaColors.DimText,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 9.5.sp,
                                    modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
                                )

                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    MikaTagPill(
                                        label = "DEVICE SPEECH (READY)",
                                        selected = activeCartridgeId == "core_os",
                                        activeColor = MikaColors.NeonCyan,
                                        onClick = { viewModel.selectEngineCartridge("core_os") }
                                    )
                                    slottedSpecs.forEach { spec ->
                                        MikaTagPill(
                                            label = "${spec.romCode}: ${spec.name}",
                                            selected = activeCartridgeId == spec.id,
                                            activeColor = Color(spec.accentHex),
                                            onClick = { viewModel.selectEngineCartridge(spec.id) }
                                        )
                                    }
                                    MikaTagPill(
                                        label = "LAN PROXY",
                                        selected = activeCartridgeId == "proxy_lan",
                                        activeColor = MikaColors.NeonPurple,
                                        onClick = { viewModel.selectEngineCartridge("proxy_lan") }
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
                                        Text(
                                            text = "🎭 SPLIT NARRATOR & \"DIALOGUE\" VOICES",
                                            color = MikaColors.NeonPurple,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Black,
                                            fontSize = 11.sp
                                        )
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
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        val selectableVoices = allVoices.filter {
                                            viewModel.repository.isCartridgeSlotted(it.cartridgeId)
                                        }
                                        val primaryVoiceObj = selectableVoices.firstOrNull { it.id == activeProfile.primaryVoiceId }
                                            ?: selectableVoices.firstOrNull()
                                        val dialogueVoiceObj = selectableVoices.firstOrNull { it.id == activeProfile.dialogueVoiceId }
                                            ?: selectableVoices.firstOrNull()

                                        if (primaryVoiceObj != null) {
                                            CyberDropdown(
                                                label = "> NARRATOR_VOICE (PROSE & *ACTIONS*)",
                                                options = selectableVoices,
                                                selectedOption = primaryVoiceObj,
                                                optionLabel = { it.displayName },
                                                onSelect = { v -> viewModel.selectPrimaryVoice(v) },
                                                accent = MikaColors.NeonCyan
                                            )
                                        }
                                        if (dialogueVoiceObj != null) {
                                            CyberDropdown(
                                                label = "> DIALOGUE_VOICE (INSIDE \"QUOTES\")",
                                                options = selectableVoices,
                                                selectedOption = dialogueVoiceObj,
                                                optionLabel = { it.displayName },
                                                onSelect = { v -> viewModel.selectDialogueVoice(v) },
                                                accent = MikaColors.NeonPink
                                            )
                                        }

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
                                    }
                                }
                            }
                        }

                        // Step 2: Preferred Voice Picker
                        item {
                            MikaGlowPanel(accent = MikaColors.TerminalGreen) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "> 2. PREFERRED_VOICE (${voicesForSelectedEngine.size})",
                                        color = MikaColors.TerminalGreen,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Black,
                                        fontSize = 11.sp
                                    )
                                    if (activeCartridgeId == "core_os") {
                                        Text(
                                            text = "↻ REFRESH",
                                            color = MikaColors.NeonCyan,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 9.sp,
                                            modifier = Modifier.clickable { viewModel.refreshSystemVoices() }
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                OutlinedTextField(
                                    value = voiceSearch,
                                    onValueChange = { voiceSearch = it },
                                    placeholder = {
                                        Text(
                                            "Search voices or language (e.g. bella, en-GB, ja)...",
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.sp,
                                            color = MikaColors.DimText
                                        )
                                    },
                                    singleLine = true,
                                    textStyle = TextStyle(
                                        color = MikaColors.TerminalGreen,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp
                                    ),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = MikaColors.TerminalGreen,
                                        unfocusedBorderColor = MikaColors.TerminalGreen.copy(alpha = 0.3f),
                                        focusedContainerColor = MikaColors.DeepWellBg,
                                        unfocusedContainerColor = MikaColors.DeepWellBg
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    MikaTagPill(
                                        label = "ALL",
                                        selected = genderFilter == null,
                                        activeColor = MikaColors.TerminalGreen,
                                        onClick = { genderFilter = null }
                                    )
                                    VoiceGender.entries.forEach { g ->
                                        MikaTagPill(
                                            label = g.tag.uppercase(),
                                            selected = genderFilter == g,
                                            activeColor = MikaColors.NeonCyan,
                                            onClick = { genderFilter = if (genderFilter == g) null else g }
                                        )
                                    }
                                }
                            }
                        }

                        // Clean Voice Cards
                        items(voicesForSelectedEngine, key = { it.id }) { voice ->
                            SimpleVoiceCard(
                                voice = voice,
                                isSelectedPrimary = activeProfile.primaryVoiceId == voice.id,
                                isSelectedDialogue = activeProfile.enableDualVoiceSplit && activeProfile.dialogueVoiceId == voice.id,
                                showDialogueButton = activeProfile.enableDualVoiceSplit,
                                onSelectPrimary = { viewModel.selectPrimaryVoice(voice) },
                                onSelectDialogue = { viewModel.selectDialogueVoice(voice) },
                                onSample = { viewModel.auditionText(overrideVoice = voice) }
                            )
                        }

                        // Expandable Advanced Voice Tuning & Clone Studio (Collapsed by default)
                        item {
                            ExpandableTerminalSection(
                                title = "ADVANCED: SPEED, PITCH & CLONE STUDIO",
                                subtitle = "Base speech rate/pitch sliders & 5s zero-shot voice cloning",
                                expanded = showAdvancedVoice,
                                onToggle = { showAdvancedVoice = !showAdvancedVoice },
                                accent = MikaColors.NeonCyan
                            ) {
                                SimpleSliderRow(
                                    label = "BASE_SPEED",
                                    valueText = "${"%.2f".format(activeProfile.baseSpeed)}x",
                                    value = activeProfile.baseSpeed,
                                    range = 0.70f..1.45f,
                                    accent = MikaColors.NeonCyan,
                                    onChange = { v -> viewModel.updateActiveProfile { it.copy(baseSpeed = v) } }
                                )
                                SimpleSliderRow(
                                    label = "BASE_PITCH",
                                    valueText = "${"%.2f".format(activeProfile.basePitch)}x",
                                    value = activeProfile.basePitch,
                                    range = 0.75f..1.30f,
                                    accent = MikaColors.NeonCyan,
                                    onChange = { v -> viewModel.updateActiveProfile { it.copy(basePitch = v) } }
                                )

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(MikaColors.NeonPurple.copy(alpha = 0.16f))
                                        .border(1.dp, MikaColors.NeonPurple, RoundedCornerShape(6.dp))
                                        .clickable { showCloneStudio = true }
                                        .padding(10.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "🧬 OPEN 5S VOICE CLONE STUDIO (POCKET / ZIPVOICE)",
                                        color = MikaColors.NeonPurple,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Black,
                                        fontSize = 10.sp
                                    )
                                }

                                // Optional LAN Proxy Endpoint Config
                                var proxyUrl by remember(activeProfile.id) { mutableStateOf(activeProfile.proxyConfig.openAiBaseUrl) }
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

                    PurrTab.PURR_ENGINE -> {
                        item {
                            PurrRulebookTab(
                                forgeState = forgeState,
                                activeProfile = activeProfile,
                                previewChunks = previewChunks,
                                viewModel = viewModel,
                                onOpenSaveDialog = { showSaveProfileDialog = true }
                            )
                        }
                    }

                    PurrTab.CARTRIDGE_BAY -> {
                        item {
                            ModelCatalogTab(
                                forgeState = forgeState,
                                modelCatalog = viewModel.modelCatalog,
                                vramRemainingSec = vramSec,
                                viewModel = viewModel,
                                onJumpToVoiceSelector = { cartridgeId ->
                                    viewModel.selectEngineCartridge(cartridgeId)
                                    activeTab = PurrTab.VOICE_ENGINE.ordinal
                                },
                                onOpenCloneStudio = { showCloneStudio = true }
                            )
                        }
                    }
                }
            }

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
            VoiceCloneStudioSheet(
                isRecording = isRecordingClone,
                recordingProgress = recordingProgress,
                lastRecordedWavPath = lastRecordedWav,
                onRecordFiveSeconds = { viewModel.recordFiveSecondReferenceSample() },
                onRegisterClone = { name, family, gender, transcript, tags ->
                    viewModel.registerClonedVoice(name, family, gender, transcript, tags)
                },
                onDismiss = { showCloneStudio = false }
            )
        }
    }
}

@Composable
private fun SimpleVoiceCard(
    voice: VoiceDescriptor,
    isSelectedPrimary: Boolean,
    isSelectedDialogue: Boolean,
    showDialogueButton: Boolean,
    onSelectPrimary: () -> Unit,
    onSelectDialogue: () -> Unit,
    onSample: () -> Unit
) {
    val accent = when {
        isSelectedPrimary -> MikaColors.TerminalGreen
        isSelectedDialogue -> MikaColors.NeonPink
        else -> MikaColors.NeonCyan.copy(alpha = 0.45f)
    }

    MikaGlowPanel(accent = accent, contentPadding = 10.dp) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = voice.displayName,
                    color = if (isSelectedPrimary || isSelectedDialogue) Color.White else MikaColors.WarmCream,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${voice.languageCode} • ${voice.gender.tag.uppercase()}",
                    color = accent,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                MikaTagPill(
                    label = "▶ TEST",
                    selected = true,
                    activeColor = MikaColors.NeonCyan,
                    onClick = onSample
                )
                MikaTagPill(
                    label = if (isSelectedPrimary) "✓ ACTIVE" else "SELECT",
                    selected = isSelectedPrimary,
                    activeColor = MikaColors.TerminalGreen,
                    onClick = onSelectPrimary
                )
                if (showDialogueButton) {
                    MikaTagPill(
                        label = if (isSelectedDialogue) "✓ \"DLG\"" else "\"DLG\"",
                        selected = isSelectedDialogue,
                        activeColor = MikaColors.NeonPink,
                        onClick = onSelectDialogue
                    )
                }
            }
        }
    }
}

@Composable
private fun SimpleSliderRow(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    accent: Color,
    onChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, color = MikaColors.CyanWhite, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 10.sp)
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
