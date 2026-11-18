package com.projectmika.purrtts.ui.forge

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
import com.projectmika.purrtts.core.purr.BackendType
import com.projectmika.purrtts.core.purr.PurrDefaultPresets
import com.projectmika.purrtts.core.purr.PurrProfile
import com.projectmika.purrtts.core.purr.VoiceDescriptor
import com.projectmika.purrtts.core.purr.VoiceGender
import com.projectmika.purrtts.ui.theme.CyberDropdown
import com.projectmika.purrtts.ui.theme.LiveWaveformMonitor
import com.projectmika.purrtts.ui.theme.MikaColors
import com.projectmika.purrtts.ui.theme.MikaGlowPanel
import com.projectmika.purrtts.ui.theme.MikaStatusBadge
import com.projectmika.purrtts.ui.theme.MikaTagPill
import com.projectmika.purrtts.ui.theme.MikaTerminalStage
import com.projectmika.purrtts.ui.theme.TerminalToastBanner

private enum class ForgeTab(val title: String) {
    FORGE("> TTS_FORGE"),
    RULEBOOK("> PURR_RULEBOOK"),
    CATALOG("> MODEL_CATALOG")
}

private enum class EngineFilter(val label: String) {
    ALL("ALL"),
    LITERT("LITERT NPU/GPU"),
    SHERPA("SHERPA-ONNX"),
    CLONED("CLONED"),
    PROXY("PROXY")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TtsForgeScreen(
    viewModel: TtsForgeViewModel,
    initialTabIndex: Int = 0,
    onOpenSystemTtsSettings: () -> Unit
) {
    val forgeState by viewModel.forgeState.collectAsState()
    val telemetry by viewModel.telemetry.collectAsState()
    val vramSec by viewModel.vramRemainingSeconds.collectAsState()
    val toastMsg by viewModel.toastMessage.collectAsState()
    val testText by viewModel.testInputText.collectAsState()
    val isPlaying by viewModel.isPlayingPreview.collectAsState()
    val isRecordingClone by viewModel.isRecordingClone.collectAsState()
    val recordingProgress by viewModel.recordingProgress.collectAsState()
    val lastRecordedWav by viewModel.lastRecordedWavPath.collectAsState()

    val activeProfile = forgeState.profiles.firstOrNull { it.id == forgeState.activeProfileId }
        ?: forgeState.profiles.first()
    val allVoices = remember(forgeState.clonedVoices) { viewModel.allAvailableVoices() }

    var activeTab by remember {
        mutableIntStateOf(initialTabIndex.coerceIn(0, ForgeTab.entries.lastIndex))
    }
    var showSaveProfileDialog by remember { mutableStateOf(false) }
    var showCloneStudio by remember { mutableStateOf(false) }

    // Voice Forge Filters
    var searchQuery by remember { mutableStateOf("") }
    var engineFilter by remember { mutableStateOf(EngineFilter.ALL) }
    var genderFilter by remember { mutableStateOf<VoiceGender?>(null) }
    var langFilter by remember { mutableStateOf<String?>(null) }
    var statusFilter by remember { mutableStateOf<String?>(null) } // "WHITELISTED" | "BLACKLISTED" | null
    var vibeFilter by remember { mutableStateOf<String?>(null) }

    val filteredVoices = remember(
        allVoices, searchQuery, engineFilter, genderFilter, langFilter, statusFilter, vibeFilter, activeProfile
    ) {
        allVoices.filter { v ->
            val customTags = activeProfile.customVoiceTags[v.id].orEmpty()
            val allTags = (v.nativeTags + customTags).map { it.lowercase() }

            if (searchQuery.isNotBlank()) {
                val q = searchQuery.lowercase()
                val matchesName = v.displayName.lowercase().contains(q) || v.id.lowercase().contains(q)
                val matchesTag = allTags.any { it.contains(q) }
                if (!matchesName && !matchesTag) return@filter false
            }

            when (engineFilter) {
                EngineFilter.ALL -> Unit
                EngineFilter.LITERT -> if (v.engine != BackendType.LITERT_NPU_GPU) return@filter false
                EngineFilter.SHERPA -> if (v.engine != BackendType.SHERPA_ONNX || v.isCloned) return@filter false
                EngineFilter.CLONED -> if (!v.isCloned) return@filter false
                EngineFilter.PROXY -> if (v.engine != BackendType.EXTERNAL_PROXY) return@filter false
            }

            if (genderFilter != null && v.gender != genderFilter) return@filter false
            if (langFilter != null && !v.languageCode.equals(langFilter, ignoreCase = true)) return@filter false

            when (statusFilter) {
                "WHITELISTED" -> if (v.id !in activeProfile.whitelist) return@filter false
                "BLACKLISTED" -> if (v.id !in activeProfile.blacklist) return@filter false
                else -> Unit
            }

            if (vibeFilter != null && !allTags.contains(vibeFilter!!.lowercase())) return@filter false
            true
        }
    }

    val previewChunks = remember(testText, activeProfile, forgeState.customPhoneticRules) {
        viewModel.previewChunks(testText, activeProfile, forgeState.customPhoneticRules)
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
                // 1. Header Bar + Single Animated Status Badge + OS TTS Settings Trigger
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "> TTS_FORGE // ROM-04",
                                color = MikaColors.TerminalGreen,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Black,
                                fontSize = 16.sp,
                                letterSpacing = 1.4.sp
                            )
                            Text(
                                text = "// System-Wide Android PURR Neural Engine",
                                color = MikaColors.TerminalGreen.copy(alpha = 0.6f),
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
                                    .background(MikaColors.NeonCyan.copy(alpha = 0.12f))
                                    .border(1.dp, MikaColors.NeonCyan.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                                    .clickable(onClick = onOpenSystemTtsSettings)
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "OS_TTS",
                                    color = MikaColors.NeonCyan,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }
                }

                // 2. Profile Selector + Save New Profile + Live Waveform Monitor & Synth Test Bench
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Bottom
                        ) {
                            CyberDropdown(
                                label = "> ACTIVE_PURR_PROFILE",
                                options = forgeState.profiles,
                                selectedOption = activeProfile,
                                optionLabel = { "${it.name} (${it.preferredBackend.displayLabel})" },
                                onSelect = { p -> viewModel.selectProfile(p.id) },
                                accent = MikaColors.TerminalGreen,
                                modifier = Modifier.weight(1f)
                            )
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(MikaColors.NeonPink.copy(alpha = 0.14f))
                                    .border(1.dp, MikaColors.NeonPink.copy(alpha = 0.7f), RoundedCornerShape(6.dp))
                                    .clickable { showSaveProfileDialog = true }
                                    .padding(horizontal = 10.dp, vertical = 10.dp)
                            ) {
                                Text(
                                    text = "+ SAVE",
                                    color = MikaColors.NeonPink,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Black,
                                    fontSize = 10.sp
                                )
                            }
                        }

                        LiveWaveformMonitor(
                            waveformBins = telemetry.waveformBins,
                            activeChunkLabel = telemetry.activeChunkLabel,
                            engineSummary = telemetry.lastEngineSummary,
                            sentimentLabel = telemetry.activeSentimentLabel
                        )

                        // Interactive Audition Bench
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
                                        "Test MIKASTOP, *actions*, \"dialogue\", slang...",
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
                                    .padding(horizontal = 12.dp, vertical = 14.dp)
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

                // 3. 3-Tab Terminal Switcher (`settings-tab-btn` exact recipe)
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ForgeTab.entries.forEachIndexed { index, tab ->
                            val isSelected = activeTab == index
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(
                                        if (isSelected) MikaColors.NeonCyan.copy(alpha = 0.16f)
                                        else MikaColors.PanelBg
                                    )
                                    .border(
                                        1.dp,
                                        if (isSelected) MikaColors.NeonCyan else Color.White.copy(alpha = 0.20f),
                                        RoundedCornerShape(4.dp)
                                    )
                                    .clickable { activeTab = index }
                                    .padding(vertical = 9.dp, horizontal = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = tab.title,
                                    color = if (isSelected) MikaColors.NeonCyan else Color.White.copy(alpha = 0.55f),
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 9.sp,
                                    letterSpacing = 0.5.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                // 4. Tab Content
                when (ForgeTab.entries[activeTab]) {
                    ForgeTab.FORGE -> {
                        // System Note Banner
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(MikaColors.TerminalGreen.copy(alpha = 0.05f))
                                    .border(1.dp, MikaColors.TerminalGreen.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                                    .padding(10.dp)
                            ) {
                                Text(
                                    text = "> SYSTEM_NOTE",
                                    color = MikaColors.TerminalGreen,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Black,
                                    fontSize = 10.sp
                                )
                                Text(
                                    text = "Smart Assignment ONLY picks whitelisted/non-blacklisted tagged voices. Assign 1ST:MAIN narrator and 2ND:DIALOGUE voices below!",
                                    color = MikaColors.CyanWhite.copy(alpha = 0.8f),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp
                                )
                            }
                        }

                        // Filter HUD
                        item {
                            MikaGlowPanel(accent = MikaColors.TerminalGreen) {
                                OutlinedTextField(
                                    value = searchQuery,
                                    onValueChange = { searchQuery = it },
                                    placeholder = {
                                        Text(
                                            "Search voice name, ID, or tag...",
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            color = MikaColors.DimText
                                        )
                                    },
                                    singleLine = true,
                                    textStyle = TextStyle(
                                        color = MikaColors.TerminalGreen,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp
                                    ),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = MikaColors.TerminalGreen,
                                        unfocusedBorderColor = MikaColors.TerminalGreen.copy(alpha = 0.35f),
                                        focusedContainerColor = MikaColors.PanelBg,
                                        unfocusedContainerColor = MikaColors.PanelBg
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Spacer(modifier = Modifier.height(8.dp))
                                FilterLabel("ENGINE FILTER:")
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    EngineFilter.entries.forEach { ef ->
                                        MikaTagPill(
                                            label = ef.label,
                                            selected = engineFilter == ef,
                                            activeColor = MikaColors.TerminalGreen,
                                            onClick = { engineFilter = ef }
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))
                                FilterLabel("GENDER / LANGUAGE / STATUS:")
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    VoiceGender.entries.forEach { g ->
                                        MikaTagPill(
                                            label = g.tag.uppercase(),
                                            selected = genderFilter == g,
                                            activeColor = MikaColors.NeonCyan,
                                            onClick = { genderFilter = if (genderFilter == g) null else g }
                                        )
                                    }
                                    listOf("en-US", "en-GB", "ja-JP").forEach { lang ->
                                        MikaTagPill(
                                            label = lang,
                                            selected = langFilter == lang,
                                            activeColor = MikaColors.NeonCyan,
                                            onClick = { langFilter = if (langFilter == lang) null else lang }
                                        )
                                    }
                                    MikaTagPill(
                                        label = "WHITELISTED",
                                        selected = statusFilter == "WHITELISTED",
                                        activeColor = MikaColors.TerminalGreen,
                                        onClick = { statusFilter = if (statusFilter == "WHITELISTED") null else "WHITELISTED" }
                                    )
                                    MikaTagPill(
                                        label = "BLACKLISTED",
                                        selected = statusFilter == "BLACKLISTED",
                                        activeColor = MikaColors.DangerRed,
                                        onClick = { statusFilter = if (statusFilter == "BLACKLISTED") null else "BLACKLISTED" }
                                    )
                                }

                                Spacer(modifier = Modifier.height(8.dp))
                                FilterLabel("VIBE TAGS:")
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                                    verticalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    listOf("cute", "shy", "energetic", "calm", "yandere", "tsundere", "ara-ara", "narrator", "deep", "breathy")
                                        .forEach { vibe ->
                                            MikaTagPill(
                                                label = vibe,
                                                selected = vibeFilter == vibe,
                                                activeColor = MikaColors.NeonPink,
                                                onClick = { vibeFilter = if (vibeFilter == vibe) null else vibe }
                                            )
                                        }
                                }

                                Spacer(modifier = Modifier.height(10.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(MikaColors.TerminalGreen.copy(alpha = 0.12f))
                                            .border(1.dp, MikaColors.TerminalGreen, RoundedCornerShape(4.dp))
                                            .clickable {
                                                viewModel.bulkSetWhitelist(filteredVoices.map { it.id }, true)
                                            }
                                            .padding(vertical = 8.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "+ WHITELIST VISIBLE (${filteredVoices.size})",
                                            color = MikaColors.TerminalGreen,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Black,
                                            fontSize = 9.sp
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(MikaColors.DangerRed.copy(alpha = 0.08f))
                                            .border(1.dp, MikaColors.DangerRed, RoundedCornerShape(4.dp))
                                            .clickable {
                                                viewModel.bulkSetWhitelist(filteredVoices.map { it.id }, false)
                                            }
                                            .padding(vertical = 8.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "- BLACKLIST VISIBLE",
                                            color = MikaColors.DangerRed,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Black,
                                            fontSize = 9.sp
                                        )
                                    }
                                }
                            }
                        }

                        // Voice Roster Cards
                        items(filteredVoices, key = { it.id }) { voice ->
                            VoiceRosterCard(
                                voice = voice,
                                profile = activeProfile,
                                onToggleWhitelist = { checked -> viewModel.toggleWhitelist(voice.id, checked) },
                                onToggleBlacklist = { black -> viewModel.toggleBlacklist(voice.id, black) },
                                onSetPrimary = { viewModel.assignPrimaryVoice(voice.id, voice.engine) },
                                onSetDialogue = { viewModel.assignDialogueVoice(voice.id) },
                                onSample = { viewModel.auditionText(overrideVoiceId = voice.id) },
                                onToggleCustomTag = { tag -> viewModel.toggleCustomTag(voice.id, tag) }
                            )
                        }
                    }

                    ForgeTab.RULEBOOK -> {
                        item {
                            PurrRulebookTab(
                                profile = activeProfile,
                                customRules = forgeState.customPhoneticRules,
                                allVoices = allVoices,
                                previewChunks = previewChunks,
                                viewModel = viewModel
                            )
                        }
                    }

                    ForgeTab.CATALOG -> {
                        item {
                            ModelCatalogTab(
                                forgeState = forgeState,
                                modelCatalog = viewModel.modelCatalog,
                                vramRemainingSec = vramSec,
                                viewModel = viewModel,
                                onOpenCloneStudio = { showCloneStudio = true }
                            )
                        }
                    }
                }
            }

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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VoiceRosterCard(
    voice: VoiceDescriptor,
    profile: PurrProfile,
    onToggleWhitelist: (Boolean) -> Unit,
    onToggleBlacklist: (Boolean) -> Unit,
    onSetPrimary: () -> Unit,
    onSetDialogue: () -> Unit,
    onSample: () -> Unit,
    onToggleCustomTag: (String) -> Unit
) {
    val isWhitelisted = voice.id in profile.whitelist
    val isBlacklisted = voice.id in profile.blacklist
    val isPrimary = profile.primaryVoiceId == voice.id
    val isDialogue = profile.dialogueVoiceId == voice.id
    val customTags = profile.customVoiceTags[voice.id].orEmpty()

    val borderAccent = when {
        isPrimary -> MikaColors.TerminalGreen
        isDialogue -> MikaColors.NeonPink
        isBlacklisted -> MikaColors.DangerRed
        isWhitelisted -> MikaColors.NeonCyan
        else -> MikaColors.TerminalGreen.copy(alpha = 0.25f)
    }

    MikaGlowPanel(accent = borderAccent) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = voice.displayName,
                    color = if (isBlacklisted) MikaColors.DimText else MikaColors.WarmCream,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "ID:${voice.id} • ${voice.engine.displayLabel} • ${voice.languageCode}",
                    color = borderAccent.copy(alpha = 0.85f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (isWhitelisted) "WL" else "--",
                    color = if (isWhitelisted) MikaColors.TerminalGreen else MikaColors.DimText,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.sp
                )
                Spacer(modifier = Modifier.width(4.dp))
                Switch(
                    checked = isWhitelisted,
                    onCheckedChange = onToggleWhitelist,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MikaColors.VoidBg,
                        checkedTrackColor = MikaColors.TerminalGreen
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MikaTagPill(
                label = if (isPrimary) "✓ 1ST:MAIN" else "SET_MAIN",
                selected = isPrimary,
                activeColor = MikaColors.TerminalGreen,
                onClick = onSetPrimary
            )
            MikaTagPill(
                label = if (isDialogue) "✓ 2ND:DIALOGUE" else "SET_DIALOGUE",
                selected = isDialogue,
                activeColor = MikaColors.NeonPink,
                onClick = onSetDialogue
            )
            MikaTagPill(
                label = "▶ SAMPLE",
                selected = true,
                activeColor = MikaColors.NeonCyan,
                onClick = onSample
            )
            MikaTagPill(
                label = if (isBlacklisted) "BLOCKED" else "BLK",
                selected = isBlacklisted,
                activeColor = MikaColors.DangerRed,
                onClick = { onToggleBlacklist(!isBlacklisted) }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "ASSIGNED TAGS:",
            color = Color.White.copy(alpha = 0.45f),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 9.sp
        )
        Spacer(modifier = Modifier.height(4.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            voice.nativeTags.forEach { nt ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.White.copy(alpha = 0.05f))
                        .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "🔒 $nt",
                        color = Color.White.copy(alpha = 0.65f),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp
                    )
                }
            }

            PurrDefaultPresets.TTS_TAG_POOL.take(10).forEach { tag ->
                if (tag !in voice.nativeTags) {
                    MikaTagPill(
                        label = tag,
                        selected = tag in customTags,
                        activeColor = MikaColors.TerminalGreen,
                        onClick = { onToggleCustomTag(tag) }
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterLabel(text: String) {
    Text(
        text = text,
        color = MikaColors.TerminalGreen.copy(alpha = 0.65f),
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 9.sp,
        letterSpacing = 0.9.sp,
        modifier = Modifier.padding(bottom = 5.dp)
    )
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
                text = "> SAVE_PURR_PROFILE",
                color = MikaColors.NeonPink,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Black,
                fontSize = 12.sp
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text("e.g. Cozy Audiobooks", fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
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
                MikaTagPill(label = "SAVE_PRESET", selected = true, activeColor = MikaColors.NeonPink, onClick = { onSave(name) })
            }
        }
    }
}
