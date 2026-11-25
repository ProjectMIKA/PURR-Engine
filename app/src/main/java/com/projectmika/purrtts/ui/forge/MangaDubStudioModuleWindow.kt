package com.projectmika.purrtts.ui.forge

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.projectmika.purrtts.core.manga.MangaAtmospherePreset
import com.projectmika.purrtts.core.manga.MangaChapterEntry
import com.projectmika.purrtts.core.manga.MangaChapterStatus
import com.projectmika.purrtts.core.manga.MangaCharacterProfile
import com.projectmika.purrtts.core.manga.MangaCloneHandoffTarget
import com.projectmika.purrtts.core.manga.MangaDeterministicEffectsEngine
import com.projectmika.purrtts.core.manga.MangaJobTelemetry
import com.projectmika.purrtts.core.manga.MangaPanelCameraEffect
import com.projectmika.purrtts.core.manga.MangaPipelineStage
import com.projectmika.purrtts.core.manga.MangaReadingOrder
import com.projectmika.purrtts.core.manga.MangaSeriesProject
import com.projectmika.purrtts.core.manga.MangaTelemetryUtils
import com.projectmika.purrtts.core.manga.MangaVideoAspect
import com.projectmika.purrtts.core.manga.MangaVideoQuality
import com.projectmika.purrtts.core.manga.MangaVisionModelTier
import com.projectmika.purrtts.core.purr.DownloadState
import com.projectmika.purrtts.core.purr.ModelBundleSpec
import com.projectmika.purrtts.core.purr.ModelBundleState
import com.projectmika.purrtts.core.purr.PurrForgeState
import com.projectmika.purrtts.core.purr.PurrProfile
import com.projectmika.purrtts.core.purr.SavedOpenAiVisionEngine
import com.projectmika.purrtts.core.purr.VoiceDescriptor
import com.projectmika.purrtts.core.purr.VoiceGender
import com.projectmika.purrtts.engine.manga.LiteRtGemmaVisionEngine
import com.projectmika.purrtts.engine.qwen3.Qwen3TtsEngine
import com.projectmika.purrtts.ui.theme.CyberDropdown
import com.projectmika.purrtts.ui.theme.CyberIconType
import com.projectmika.purrtts.ui.theme.ExpandableTerminalSection
import com.projectmika.purrtts.ui.theme.MikaColors
import com.projectmika.purrtts.ui.theme.MikaCyberIcon
import com.projectmika.purrtts.ui.theme.MikaGlowPanel
import com.projectmika.purrtts.ui.theme.MikaTagPill
import com.projectmika.purrtts.ui.theme.MikaVoiceDropdownSelector
import com.projectmika.purrtts.ui.theme.bringIntoViewWithIme
import java.io.File
import kotlin.math.roundToInt

private enum class MangaPreProdTab(val label: String) {
    DECISIONS("DECISIONS"),
    CAST("CAST ROSTER"),
    STORYBOARD("STORYBOARD")
}

private sealed interface MangaVisionLlmChoice {
    data class LocalTier(val tier: MangaVisionModelTier) : MangaVisionLlmChoice
    data class SavedApiEngine(val engine: SavedOpenAiVisionEngine) : MangaVisionLlmChoice
}

/**
 * ✨ TOOL-11: MANGA AUTO-DUB STUDIO (1-TAP CBZ -> GEMMA 4 VISION & QWEN VOICEDESIGN -> .MP4) ✨
 *
 * Designed for effortless front-surface workflow:
 * 1. Enter Series Title (or attach chapter to existing series)
 * 2. Pick `.CBZ` manga file
 * 3. Watch the live 5-Stage Cooking Console as Gemma 4 E2B/E4B scans panels, casts custom voices
 *    via Qwen3 1.7B VoiceDesign, and muxes the hardware `.MP4` (pausing only when Gemma flags an
 *    ambiguous speaker bubble, or auto-cooking straight to completion).
 * Full customization (Clone Studio round-trip, panel storyboards, aspect ratio, LAN Vision URL)
 * stays neatly tucked inside the collapsible Customize Studio drawer below.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MangaDubStudioModuleWindowBody(
    forgeState: PurrForgeState,
    activeProfile: PurrProfile,
    availableVoices: List<VoiceDescriptor>,
    viewModel: TtsForgeViewModel,
    onLaunchCloneForCharacter: (MangaCloneHandoffTarget) -> Unit
) {
    val projects by viewModel.mangaProjects.collectAsState()
    val activeProjectId by viewModel.activeMangaProjectId.collectAsState()
    val isBusy by viewModel.isMangaBusy.collectAsState()
    val statusLabel by viewModel.mangaStatusLabel.collectAsState()
    val overallProgress by viewModel.mangaProgress.collectAsState()
    val mangaTelemetry by viewModel.mangaTelemetry.collectAsState()

    val activeProject = remember(projects, activeProjectId) {
        projects.firstOrNull { it.id == activeProjectId } ?: projects.firstOrNull()
    }
    var selectedChapterId by remember(activeProject?.id) {
        mutableStateOf(activeProject?.chapters?.lastOrNull()?.id)
    }
    val activeChapter = activeProject?.chapters?.firstOrNull { it.id == selectedChapterId }
        ?: activeProject?.chapters?.lastOrNull()

    var seriesTitleInput by remember { mutableStateOf("") }
    var importIntoCurrentSeries by remember(activeProject?.id) { mutableStateOf(false) }
    val globalReadingOrder by viewModel.selectedMangaReadingOrder.collectAsState()
    val selectedReadingOrder = activeProject?.readingOrder ?: globalReadingOrder
    var selectedVideoAspect by remember(activeProject?.id, activeProject?.videoAspect) {
        mutableStateOf(activeProject?.videoAspect ?: MangaVideoAspect.VERTICAL_9_16)
    }
    var selectedBurnSubtitles by remember(activeProject?.id, activeProject?.burnInSubtitles) {
        mutableStateOf(activeProject?.burnInSubtitles ?: true)
    }
    var selectedVideoQuality by remember(activeProject?.id, activeProject?.videoQuality) {
        mutableStateOf(activeProject?.videoQuality ?: MangaVideoQuality.HD_720P)
    }
    val selectedVisionTier by viewModel.selectedMangaVisionModel.collectAsState()
    val selectedOpenAiEngineId by viewModel.selectedMangaOpenAiEngineId.collectAsState()
    val globalExpectedCharCount by viewModel.mangaExpectedCharacterCount.collectAsState()
    var expectedCharCount by remember(activeProject?.id, activeProject?.expectedCharacterCount, globalExpectedCharCount) {
        mutableIntStateOf(activeProject?.expectedCharacterCount ?: globalExpectedCharCount)
    }
    var charCountFieldText by remember(expectedCharCount) {
        mutableStateOf(if (expectedCharCount > 0) expectedCharCount.toString() else "")
    }
    val globalIncludeNonBubbleText by viewModel.includeMangaNonBubbleText.collectAsState()
    val includeNonBubbleText = activeProject?.includeNonBubbleText ?: globalIncludeNonBubbleText
    val globalLockVoicesViaCloning by viewModel.lockMangaVoicesViaCloning.collectAsState()
    val globalMangaKvCacheEnabled by viewModel.mangaKvCacheEnabled.collectAsState()
    val globalMangaMaxPanelsPerPage by viewModel.mangaMaxPanelsPerPage.collectAsState()
    val lockVoicesViaCloning = activeProject?.lockVoicesViaCloning ?: globalLockVoicesViaCloning
    var autoCookFullMp4 by remember(activeProject?.id) {
        mutableStateOf(activeProject?.autoCookFullMp4 ?: false)
    }
    var pauseOnFlags by remember(activeProject?.id) {
        mutableStateOf(activeProject?.pauseOnFlaggedDecisions ?: true)
    }
    var preProdTab by remember { mutableStateOf(MangaPreProdTab.CAST) }
    var selectedPageIdx by remember(activeChapter?.id) { mutableIntStateOf(0) }
    var expandAdvancedSettings by remember { mutableStateOf(false) }
    var expandCustomizeDrawer by remember { mutableStateOf(false) }
    var expandPowerUser by remember { mutableStateOf(false) }

    var pendingDeleteProject by remember { mutableStateOf<MangaSeriesProject?>(null) }
    var pendingDeleteChapter by remember { mutableStateOf<Pair<String, MangaChapterEntry>?>(null) }
    var pendingClearAllProjects by remember { mutableStateOf(false) }

    val visionSpecsByTier = remember(viewModel.modelCatalog) {
        MangaVisionModelTier.entries.associateWith { tier ->
            viewModel.modelCatalog.firstOrNull { it.id == tier.specId }
        }
    }
    val qwenDesignSpec = remember(viewModel.modelCatalog) {
        viewModel.modelCatalog.firstOrNull { it.id == Qwen3TtsEngine.SPEC_DESIGN_17B }
    }

    val visionStatesByTier = MangaVisionModelTier.entries.associateWith { tier ->
        forgeState.modelStates[tier.specId]
    }
    val qwenDesignState = forgeState.modelStates[Qwen3TtsEngine.SPEC_DESIGN_17B]

    val isOpenAiToolActive = "tool_openai_vision_api" in forgeState.slottedCartridges
    val enabledOpenAiEngines = remember(isOpenAiToolActive, forgeState.savedOpenAiVisionEngines) {
        if (isOpenAiToolActive) forgeState.savedOpenAiVisionEngines else emptyList()
    }
    val visionLlmOptions = remember(enabledOpenAiEngines) {
        MangaVisionModelTier.entries.map { MangaVisionLlmChoice.LocalTier(it) } +
            enabledOpenAiEngines.map { MangaVisionLlmChoice.SavedApiEngine(it) }
    }
    val activeOpenAiId = (activeProject?.selectedOpenAiVisionEngineId?.takeIf { it.isNotBlank() } ?: selectedOpenAiEngineId)
    val selectedOpenAiEngine = enabledOpenAiEngines.firstOrNull { it.id == activeOpenAiId }
    val selectedVisionChoice: MangaVisionLlmChoice = remember(selectedVisionTier, selectedOpenAiEngine, visionLlmOptions) {
        if (selectedOpenAiEngine != null) {
            MangaVisionLlmChoice.SavedApiEngine(selectedOpenAiEngine)
        } else {
            MangaVisionLlmChoice.LocalTier(selectedVisionTier)
        }
    }

    val hasAnyVisionModel = visionStatesByTier.values.any { it?.state == DownloadState.INSTALLED } || selectedOpenAiEngine != null
    val selectedVisionInstalled = selectedOpenAiEngine != null || visionStatesByTier[selectedVisionTier]?.state == DownloadState.INSTALLED
    val hasQwenDesign = qwenDesignState?.state == DownloadState.INSTALLED
    var expandEngineDeck by remember(hasAnyVisionModel) {
        mutableStateOf(!hasAnyVisionModel)
    }

    val cbzPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            val targetId = if (importIntoCurrentSeries && seriesTitleInput.isBlank()) activeProject?.id else null
            viewModel.importMangaCbz(
                uri = it,
                targetProjectId = targetId,
                seriesTitleInput = seriesTitleInput,
                readingOrder = selectedReadingOrder,
                autoProduceMp4 = autoCookFullMp4,
                pauseOnFlags = pauseOnFlags,
                videoAspect = selectedVideoAspect,
                burnInSubtitles = selectedBurnSubtitles,
                videoQuality = selectedVideoQuality,
                includeNonBubbleText = includeNonBubbleText,
                lockVoicesViaCloning = lockVoicesViaCloning,
                preferredVisionModel = selectedVisionTier,
                expectedCharacterCount = expectedCharCount,
                selectedOpenAiVisionEngineId = selectedOpenAiEngine?.id ?: ""
            )
        }
    }

    // Confirmation dialogs for Project / Chapter deletion & Clear All
    pendingDeleteProject?.let { targetProj ->
        AlertDialog(
            onDismissRequest = { pendingDeleteProject = null },
            containerColor = MikaColors.PanelBg,
            titleContentColor = MikaColors.DangerRed,
            textContentColor = MikaColors.PrimaryText,
            title = {
                Text("DELETE MANGA PROJECT?", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black, fontSize = 13.sp)
            },
            text = {
                Text(
                    text = "Permanently remove \"${targetProj.seriesTitle}\" (${targetProj.chapters.size} chapter(s), voice caches & exported videos)?",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.5.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteMangaProject(targetProj.id)
                    pendingDeleteProject = null
                }) {
                    Text("DELETE", color = MikaColors.DangerRed, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteProject = null }) {
                    Text("CANCEL", color = MikaColors.NeonCyan, fontFamily = FontFamily.Monospace)
                }
            }
        )
    }

    pendingDeleteChapter?.let { (projId, targetCh) ->
        AlertDialog(
            onDismissRequest = { pendingDeleteChapter = null },
            containerColor = MikaColors.PanelBg,
            titleContentColor = MikaColors.DangerRed,
            textContentColor = MikaColors.PrimaryText,
            title = {
                Text("DELETE CHAPTER?", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black, fontSize = 13.sp)
            },
            text = {
                Text(
                    text = "Remove chapter \"${targetCh.title}\" and its extracted pages/audio?",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.5.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteMangaChapter(projId, targetCh.id)
                    pendingDeleteChapter = null
                }) {
                    Text("DELETE", color = MikaColors.DangerRed, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteChapter = null }) {
                    Text("CANCEL", color = MikaColors.NeonCyan, fontFamily = FontFamily.Monospace)
                }
            }
        )
    }

    if (pendingClearAllProjects) {
        AlertDialog(
            onDismissRequest = { pendingClearAllProjects = false },
            containerColor = MikaColors.PanelBg,
            titleContentColor = MikaColors.DangerRed,
            textContentColor = MikaColors.PrimaryText,
            title = {
                Text("CLEAR ALL MANGA PROJECTS?", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black, fontSize = 13.sp)
            },
            text = {
                Text(
                    text = "This permanently deletes all ${projects.size} saved manga projects and local files.",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.5.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteAllMangaProjects()
                    pendingClearAllProjects = false
                }) {
                    Text("CLEAR ALL", color = MikaColors.DangerRed, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingClearAllProjects = false }) {
                    Text("CANCEL", color = MikaColors.NeonCyan, fontFamily = FontFamily.Monospace)
                }
            }
        )
    }

    // 1. STREAMLINED SURFACE: IMPORT MANGA -> SERIES TITLE -> WATCH IT COOK
    MikaGlowPanel(accent = MikaColors.NeonPink) {
        Text(
            text = "> MANGA_AUTO_DUB // IMPORT -> TITLE -> COOK .MP4",
            color = MikaColors.NeonPink,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Black,
            fontSize = 11.5.sp
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Pick your reading direction and Vision LLM, enter a series title, and import your .CBZ chapter. Your selected VLM scans zoomed panels individually for sharp speech bubbles, casts Qwen3 VoiceDesign voices, and renders a synchronized .MP4 automatically.",
            color = MikaColors.CyanWhite.copy(alpha = 0.80f),
            fontFamily = FontFamily.Monospace,
            fontSize = 9.5.sp,
            lineHeight = 13.5.sp
        )
        Spacer(modifier = Modifier.height(8.dp))

        // Vision LLM Selection Dropdown (Local VLMs + saved TOOL-12 OpenAI Vision engines when active)
        CyberDropdown(
            label = "> VISION_LLM_ENGINE",
            options = visionLlmOptions,
            selectedOption = selectedVisionChoice,
            optionLabel = { choice ->
                when (choice) {
                    is MangaVisionLlmChoice.LocalTier -> {
                        val readyTag = if (visionStatesByTier[choice.tier]?.state == DownloadState.INSTALLED) "READY" else "INSTALL BELOW"
                        "LOCAL // ${choice.tier.shortLabel} [$readyTag]"
                    }
                    is MangaVisionLlmChoice.SavedApiEngine -> {
                        "API (${choice.engine.provider.label}) // ${choice.engine.name} [${choice.engine.modelId}]"
                    }
                }
            },
            onSelect = { chosen ->
                when (chosen) {
                    is MangaVisionLlmChoice.LocalTier -> viewModel.selectMangaVisionModel(chosen.tier)
                    is MangaVisionLlmChoice.SavedApiEngine -> viewModel.selectMangaOpenAiVisionEngine(chosen.engine.id)
                }
            },
            accent = if (selectedVisionInstalled) MikaColors.TerminalGreen else MikaColors.NeonCyan
        )
        Spacer(modifier = Modifier.height(6.dp))

        // Compact AI Readiness & Local Model Installer Strip
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(MikaColors.DeepWellBg)
                .border(
                    1.dp,
                    if (selectedVisionInstalled) MikaColors.TerminalGreen.copy(alpha = 0.55f) else MikaColors.NeonCyan.copy(alpha = 0.65f),
                    RoundedCornerShape(6.dp)
                )
                .padding(9.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expandEngineDeck = !expandEngineDeck },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val activeBrainText = when {
                    selectedOpenAiEngine != null -> "API: ${selectedOpenAiEngine.name} [READY]"
                    selectedVisionInstalled -> "${selectedVisionTier.shortLabel} [READY]"
                    else -> "${selectedVisionTier.shortLabel} [INSTALL BELOW]"
                }
                val voiceDeckText = if (hasQwenDesign) "QWEN3 1.7B DESIGN [READY]" else "VOICEDESIGN OPTIONAL"
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "$activeBrainText • $voiceDeckText",
                        color = if (selectedVisionInstalled) MikaColors.TerminalGreen else MikaColors.SsrGold,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Black,
                        fontSize = 8.8.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = when {
                            expandEngineDeck -> "Tap to collapse local VLM weights & Qwen3 1.7B installer"
                            isOpenAiToolActive -> "TOOL-12 OpenAI Vision API active (${enabledOpenAiEngines.size} saved) • Tap to install local VLMs"
                            else -> "Tap to install local VLMs • Slam TOOL-12 in Cartridge Bay -> TOOLS for OpenAI Vision APIs"
                        },
                        color = MikaColors.DimText,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 7.8.sp
                    )
                }
                MikaTagPill(
                    label = if (expandEngineDeck) "HIDE" else "WEIGHTS",
                    selected = expandEngineDeck,
                    activeColor = MikaColors.NeonCyan,
                    onClick = { expandEngineDeck = !expandEngineDeck }
                )
            }

            AnimatedVisibility(visible = expandEngineDeck) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        MangaVisionModelTier.entries.forEach { tier ->
                            val isInst = visionStatesByTier[tier]?.state == DownloadState.INSTALLED
                            val pillLabel = if (isInst) "${tier.shortLabel} ✓" else tier.shortLabel
                            MikaTagPill(
                                label = pillLabel,
                                selected = selectedVisionTier == tier,
                                activeColor = if (isInst) MikaColors.NeonCyan else MikaColors.NeonPurple,
                                onClick = { viewModel.selectMangaVisionModel(tier) }
                            )
                        }
                    }
                    MangaVisionModelTier.entries.forEach { tier ->
                        val spec = visionSpecsByTier[tier]
                        val bundleState = visionStatesByTier[tier]
                        if (spec != null) {
                            MangaVisionModelInstallRow(
                                spec = spec,
                                bundleState = bundleState,
                                badgeLabel = if (selectedVisionTier == tier) "[SELECTED • ${spec.romCode}]" else "[${spec.romCode}]",
                                badgeColor = if (selectedVisionTier == tier) MikaColors.TerminalGreen else MikaColors.NeonPurple,
                                accent = if (selectedVisionTier == tier) MikaColors.NeonCyan else MikaColors.NeonPurple,
                                onDownload = { viewModel.startModelDownload(spec) },
                                onPause = { viewModel.pauseModelDownload(spec.id) }
                            )
                        }
                    }
                    if (qwenDesignSpec != null) {
                        MangaVisionModelInstallRow(
                            spec = qwenDesignSpec,
                            bundleState = qwenDesignState,
                            badgeLabel = "[CUSTOM CHARACTER VOICES]",
                            badgeColor = MikaColors.NeonPink,
                            accent = MikaColors.NeonPink,
                            onDownload = { viewModel.startModelDownload(qwenDesignSpec) },
                            onPause = { viewModel.pauseModelDownload(qwenDesignSpec.id) }
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        if (projects.isNotEmpty() && activeProject != null) {
            CyberDropdown(
                label = "> PROJECT (${projects.size})",
                options = projects,
                selectedOption = activeProject,
                optionLabel = { p -> "${p.seriesTitle} (${p.chapters.size} ch | ${p.characters.size} cast)" },
                onSelect = { viewModel.selectMangaProject(it.id) },
                accent = MikaColors.NeonPink
            )
            Spacer(modifier = Modifier.height(6.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                MikaTagPill(
                    label = if (importIntoCurrentSeries) "MODE: ADD CH TO ${activeProject.seriesTitle.take(12)}" else "MODE: NEW SERIES",
                    selected = importIntoCurrentSeries,
                    activeColor = MikaColors.TerminalGreen,
                    onClick = { importIntoCurrentSeries = !importIntoCurrentSeries }
                )
                MikaTagPill(
                    label = "DEL PROJECT",
                    selected = false,
                    activeColor = MikaColors.DangerRed,
                    onClick = { pendingDeleteProject = activeProject }
                )
                if (projects.size > 1) {
                    MikaTagPill(
                        label = "CLEAR ALL",
                        selected = false,
                        activeColor = MikaColors.DangerRed,
                        onClick = { pendingClearAllProjects = true }
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
        }

        OutlinedTextField(
            value = seriesTitleInput,
            onValueChange = { seriesTitleInput = it },
            label = { Text("SERIES_TITLE (FOR NEW MANGA PROJECT)", fontSize = 8.5.sp, fontFamily = FontFamily.Monospace) },
            placeholder = { Text("e.g. Solo Cyber Leveling", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MikaColors.DimText) },
            singleLine = true,
            textStyle = TextStyle(color = MikaColors.CyanWhite, fontFamily = FontFamily.Monospace, fontSize = 11.sp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MikaColors.NeonCyan,
                unfocusedBorderColor = MikaColors.NeonCyan.copy(alpha = 0.35f),
                focusedContainerColor = MikaColors.DeepWellBg,
                unfocusedContainerColor = MikaColors.DeepWellBg
            ),
            modifier = Modifier.fillMaxWidth().bringIntoViewWithIme()
        )
        Spacer(modifier = Modifier.height(6.dp))

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            MikaTagPill(
                label = if (autoCookFullMp4) "AUTO-COOK FULL .MP4: ON" else "AUTO-COOK FULL .MP4: OFF",
                selected = autoCookFullMp4,
                activeColor = MikaColors.TerminalGreen,
                onClick = {
                    autoCookFullMp4 = !autoCookFullMp4
                    activeProject?.let { viewModel.updateMangaProjectConfig(it.id, autoCookFullMp4 = autoCookFullMp4) }
                }
            )
            MikaTagPill(
                label = if (pauseOnFlags) "PAUSE IF FLAGGED: ON" else "PAUSE IF FLAGGED: OFF",
                selected = pauseOnFlags,
                activeColor = MikaColors.MikaAmber,
                onClick = {
                    pauseOnFlags = !pauseOnFlags
                    activeProject?.let { viewModel.updateMangaProjectConfig(it.id, pauseOnFlaggedDecisions = pauseOnFlags) }
                }
            )
        }
        Spacer(modifier = Modifier.height(6.dp))

        // Front-Surface Reading Direction & Output Controls: Reading Direction (Manga R->L default), Aspect Ratio, Subtitles, Quality
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(MikaColors.DeepWellBg)
                .border(1.dp, MikaColors.NeonCyan.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "READING:",
                    color = MikaColors.DimText,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    fontSize = 8.sp,
                    modifier = Modifier.width(62.dp)
                )
                FlowRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    MangaReadingOrder.entries.forEach { order ->
                        MikaTagPill(
                            label = order.shortLabel,
                            selected = selectedReadingOrder == order,
                            activeColor = MikaColors.NeonPink,
                            onClick = { viewModel.selectMangaReadingOrder(order) }
                        )
                    }
                }
            }
            Text(
                text = "ℹ️ ${selectedReadingOrder.instructionHint}",
                color = MikaColors.CyanWhite.copy(alpha = 0.85f),
                fontFamily = FontFamily.Monospace,
                fontSize = 7.8.sp,
                lineHeight = 11.sp
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "ASPECT:",
                    color = MikaColors.DimText,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    fontSize = 8.sp,
                    modifier = Modifier.width(62.dp)
                )
                FlowRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    MangaVideoAspect.entries.forEach { asp ->
                        MikaTagPill(
                            label = asp.shortLabel,
                            selected = selectedVideoAspect == asp,
                            activeColor = MikaColors.NeonCyan,
                            onClick = {
                                selectedVideoAspect = asp
                                activeProject?.let { viewModel.updateMangaProjectConfig(it.id, videoAspect = asp) }
                            }
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "SUBTITLES:",
                    color = MikaColors.DimText,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    fontSize = 8.sp,
                    modifier = Modifier.width(62.dp)
                )
                FlowRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    MikaTagPill(
                        label = "ON",
                        selected = selectedBurnSubtitles,
                        activeColor = MikaColors.TerminalGreen,
                        onClick = {
                            selectedBurnSubtitles = true
                            activeProject?.let { viewModel.updateMangaProjectConfig(it.id, burnInSubtitles = true) }
                        }
                    )
                    MikaTagPill(
                        label = "OFF",
                        selected = !selectedBurnSubtitles,
                        activeColor = MikaColors.MikaAmber,
                        onClick = {
                            selectedBurnSubtitles = false
                            activeProject?.let { viewModel.updateMangaProjectConfig(it.id, burnInSubtitles = false) }
                        }
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "QUALITY:",
                    color = MikaColors.DimText,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    fontSize = 8.sp,
                    modifier = Modifier.width(62.dp)
                )
                FlowRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    MangaVideoQuality.entries.forEach { qual ->
                        val pillText = if (qual == MangaVideoQuality.HD_720P) "720P [DEF]" else qual.shortLabel
                        MikaTagPill(
                            label = pillText,
                            selected = selectedVideoQuality == qual,
                            activeColor = MikaColors.SsrGold,
                            onClick = {
                                selectedVideoQuality = qual
                                activeProject?.let { viewModel.updateMangaProjectConfig(it.id, videoQuality = qual) }
                            }
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "CAST QTY:",
                    color = MikaColors.DimText,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    fontSize = 8.sp,
                    modifier = Modifier.width(62.dp)
                )
                MikaTagPill(
                    label = "AUTO",
                    selected = expectedCharCount == 0,
                    activeColor = MikaColors.NeonCyan,
                    onClick = {
                        expectedCharCount = 0
                        charCountFieldText = ""
                        viewModel.setMangaExpectedCharacterCount(0)
                    }
                )
                MikaTagPill(
                    label = "-",
                    selected = false,
                    activeColor = MikaColors.NeonPurple,
                    onClick = {
                        val next = (expectedCharCount - 1).coerceIn(0, 24)
                        expectedCharCount = next
                        charCountFieldText = if (next > 0) next.toString() else ""
                        viewModel.setMangaExpectedCharacterCount(next)
                    }
                )
                CompactCyberQtyBox(
                    value = charCountFieldText,
                    onValueChange = { raw ->
                        val digits = raw.filter { it.isDigit() }.take(2)
                        charCountFieldText = digits
                        val parsed = digits.toIntOrNull()?.coerceIn(0, 24) ?: 0
                        expectedCharCount = parsed
                        viewModel.setMangaExpectedCharacterCount(parsed)
                    },
                    accent = MikaColors.NeonCyan,
                    modifier = Modifier.bringIntoViewWithIme()
                )
                MikaTagPill(
                    label = "+",
                    selected = false,
                    activeColor = MikaColors.TerminalGreen,
                    onClick = {
                        val next = (expectedCharCount + 1).coerceIn(1, 24)
                        expectedCharCount = next
                        charCountFieldText = next.toString()
                        viewModel.setMangaExpectedCharacterCount(next)
                    }
                )
            }
            Text(
                text = if (expectedCharCount == 0) {
                    "CHARACTER COUNT: AUTO (VLM discovers recurring speaking characters across pages; MIKA narrates separately)"
                } else {
                    "CHARACTER COUNT BASELINE: $expectedCharCount speaker(s) (excl. MIKA Narrator) guided during storyboarding & cast consolidation"
                },
                color = MikaColors.CyanWhite.copy(alpha = 0.80f),
                fontFamily = FontFamily.Monospace,
                fontSize = 7.6.sp,
                lineHeight = 10.5.sp
            )
        }
        Spacer(modifier = Modifier.height(6.dp))

        ExpandableTerminalSection(
            title = "ADVANCED_SCAN_SETTINGS",
            expanded = expandAdvancedSettings,
            onToggle = { expandAdvancedSettings = !expandAdvancedSettings },
            accent = MikaColors.NeonPurple
        ) {
            SimplePurrToggle(
                title = "LOCK CHARACTER VOICES VIA QWEN CLONE (ANTI-DRIFT)",
                subtitle = "ON by default: renders 1 VoiceDesign studio seed per character & locks every line through Qwen/Pocket clone so pitch stays consistent.",
                checked = lockVoicesViaCloning,
                accent = MikaColors.TerminalGreen,
                icon = CyberIconType.DNA_CLONE,
                onToggle = { checked ->
                    viewModel.setMangaLockVoicesViaCloning(checked)
                    activeProject?.let { viewModel.updateMangaProjectConfig(it.id, lockVoicesViaCloning = checked) }
                }
            )
            SimplePurrToggle(
                title = "EXPERIMENTAL: PROCESS NON-BUBBLE TEXT & SFX",
                subtitle = "OFF by default (skips floating SFX like Shaaa/GULP, scanlation credits & signatures). Turn ON to dub everything.",
                checked = includeNonBubbleText,
                accent = MikaColors.NeonPurple,
                icon = CyberIconType.BOLT_ACCEL,
                onToggle = { checked ->
                    viewModel.setMangaIncludeNonBubbleText(checked)
                    activeProject?.let { viewModel.updateMangaProjectConfig(it.id, includeNonBubbleText = checked) }
                }
            )
        }
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier
                    .weight(1.35f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MikaColors.NeonPink.copy(alpha = 0.22f))
                    .border(1.5.dp, MikaColors.NeonPink, RoundedCornerShape(6.dp))
                    .clickable(enabled = !isBusy) {
                        cbzPicker.launch(
                            arrayOf("application/x-cbz", "application/zip", "application/octet-stream", "*/*")
                        )
                    }
                    .padding(vertical = 11.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                MikaCyberIcon(type = CyberIconType.DOWNLOAD, tint = MikaColors.NeonPink, size = 13.dp)
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (autoCookFullMp4) "+ IMPORT .CBZ & COOK .MP4" else "+ IMPORT .CBZ & STORYBOARD",
                    color = MikaColors.NeonPink,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    fontSize = 9.8.sp
                )
            }

            Row(
                modifier = Modifier
                    .weight(0.75f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MikaColors.SsrGold.copy(alpha = 0.16f))
                    .border(1.5.dp, MikaColors.SsrGold, RoundedCornerShape(6.dp))
                    .clickable(enabled = !isBusy) { viewModel.createSampleMangaProject() }
                    .padding(vertical = 11.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                MikaCyberIcon(type = CyberIconType.BOLT_ACCEL, tint = MikaColors.SsrGold, size = 12.dp)
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "DEMO",
                    color = MikaColors.SsrGold,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    fontSize = 9.5.sp
                )
            }
        }

        // Detailed Cooking Console (always displays when busy OR logs/output exist)
        if (isBusy || mangaTelemetry.logTail.isNotEmpty() || mangaTelemetry.stage != MangaPipelineStage.IDLE) {
            Spacer(modifier = Modifier.height(10.dp))
            MangaTelemetryBanner(
                telemetry = mangaTelemetry,
                statusFallback = statusLabel,
                progress = overallProgress,
                isBusy = isBusy,
                onCancel = { viewModel.cancelMangaWork() }
            )
        }
    }

    // 2. OUTPUT & LLM CLARIFICATION SURFACE
    if (activeProject != null && activeChapter != null) {
        val pendingCount = activeChapter.pendingDecisionCount

        // Completed Episode Banner
        if (activeChapter.status == MangaChapterStatus.COMPLETED && activeChapter.exportedMp4Path.isNotBlank()) {
            MikaGlowPanel(accent = MikaColors.SsrGold) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MikaCyberIcon(type = CyberIconType.CHECK, tint = MikaColors.SsrGold, size = 16.dp)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "DUBBED .MP4 READY (${"%.1f".format(activeChapter.durationSec)}s) // ${activeChapter.title}",
                            color = MikaColors.SsrGold,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Black,
                            fontSize = 10.5.sp
                        )
                        Text(
                            text = activeChapter.publicDownloadPath.ifBlank { activeChapter.exportedMp4Path },
                            color = MikaColors.WarmCream,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 8.5.sp
                        )
                    }
                }
            }
        }

        // Focused Clarification Card when Gemma flagged ambiguous speech bubbles
        if (pendingCount > 0) {
            MikaGlowPanel(accent = MikaColors.MikaAmber) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "⚠️ LLM NEEDS CLARIFICATION ($pendingCount BUBBLE${if (pendingCount == 1) "" else "S"})",
                            color = MikaColors.MikaAmber,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Black,
                            fontSize = 10.5.sp
                        )
                        Text(
                            text = "Gemma paused on uncertain speaker(s). Tap a speaker below or auto-resolve to finish cooking!",
                            color = MikaColors.CyanWhite.copy(alpha = 0.78f),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 8.5.sp
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(MikaColors.SsrGold.copy(alpha = 0.18f))
                        .border(1.2.dp, MikaColors.SsrGold, RoundedCornerShape(6.dp))
                        .clickable(enabled = !isBusy) {
                            viewModel.autoResolveAndProduceMangaMp4(activeProject.id, activeChapter.id)
                        }
                        .padding(vertical = 9.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MikaCyberIcon(type = CyberIconType.BOLT_ACCEL, tint = MikaColors.SsrGold, size = 12.dp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "AUTO-RESOLVE GUESSES & FINISH .MP4",
                        color = MikaColors.SsrGold,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Black,
                        fontSize = 9.5.sp
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))

                MangaDecisionQueueList(
                    activeProject = activeProject,
                    activeChapter = activeChapter,
                    viewModel = viewModel
                )
            }
        }

        // PRE-COOK SELECTIONS & CAST REVIEW EDITOR (Before rendering .MP4)
        if (!isBusy && activeChapter.status != MangaChapterStatus.COMPLETED) {
            MangaPreCookReviewPanel(
                project = activeProject,
                chapter = activeChapter,
                isBusy = isBusy,
                viewModel = viewModel,
                onOpenFullCastDrawer = {
                    expandCustomizeDrawer = true
                    preProdTab = MangaPreProdTab.CAST
                },
                onOpenStoryboardDrawer = {
                    expandCustomizeDrawer = true
                    preProdTab = MangaPreProdTab.STORYBOARD
                }
            )
        }

        // Manual / Resume Produce .MP4 CTA whenever not currently running
        if (!isBusy && pendingCount == 0 && activeChapter.status != MangaChapterStatus.COMPLETED) {
            MikaGlowPanel(accent = MikaColors.TerminalGreen) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(MikaColors.TerminalGreen.copy(alpha = 0.22f))
                        .border(1.5.dp, MikaColors.TerminalGreen, RoundedCornerShape(6.dp))
                        .clickable { viewModel.produceMangaChapterMp4(activeProject.id, activeChapter.id) }
                        .padding(vertical = 11.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MikaCyberIcon(type = CyberIconType.PLAY, tint = MikaColors.TerminalGreen, size = 14.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "COOK DUBBED EPISODE (.MP4)",
                        color = MikaColors.TerminalGreen,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Black,
                        fontSize = 11.sp
                    )
                }
            }
        }

        // 3. COLLAPSIBLE POWER-USER & CUSTOMIZATION STUDIO (Optional)
        ExpandableTerminalSection(
            title = "CUSTOMIZE CAST (${activeProject.characters.size}), CLONES, ASPECT & STORYBOARD (${activeChapter.totalPanelCount})",
            expanded = expandCustomizeDrawer,
            onToggle = { expandCustomizeDrawer = !expandCustomizeDrawer },
            accent = MikaColors.NeonCyan
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (activeProject.chapters.size > 1) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        activeProject.chapters.forEachIndexed { chIdx, chEntry ->
                            MikaTagPill(
                                label = "CH ${chIdx + 1}: ${chEntry.title.take(14)}",
                                selected = chEntry.id == activeChapter.id,
                                activeColor = MikaColors.NeonCyan,
                                onClick = { selectedChapterId = chEntry.id }
                            )
                        }
                    }
                }

                // Chapter action bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${activeChapter.pages.size} pg | ${activeChapter.totalPanelCount} pan | ${activeChapter.totalLineCount} lines",
                        color = MikaColors.DimText,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 8.5.sp
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        MikaTagPill(
                            label = "RE-RENDER .MP4",
                            selected = false,
                            activeColor = MikaColors.TerminalGreen,
                            onClick = { if (!isBusy) viewModel.produceMangaChapterMp4(activeProject.id, activeChapter.id) }
                        )
                        MikaTagPill(
                            label = "DEL CH",
                            selected = false,
                            activeColor = MikaColors.DangerRed,
                            onClick = { pendingDeleteChapter = activeProject.id to activeChapter }
                        )
                    }
                }

                // Reading Order + Video Aspect + Output Quality + Toggles
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    MangaReadingOrder.entries.forEach { order ->
                        MikaTagPill(
                            label = order.label,
                            selected = selectedReadingOrder == order,
                            activeColor = MikaColors.NeonCyan,
                            onClick = { viewModel.selectMangaReadingOrder(order) }
                        )
                    }
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    MangaVideoAspect.entries.forEach { asp ->
                        MikaTagPill(
                            label = asp.label,
                            selected = activeProject.videoAspect == asp,
                            activeColor = MikaColors.NeonCyan,
                            onClick = {
                                selectedVideoAspect = asp
                                viewModel.updateMangaProjectConfig(activeProject.id, videoAspect = asp)
                            }
                        )
                    }
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    MangaVideoQuality.entries.forEach { qual ->
                        MikaTagPill(
                            label = qual.label,
                            selected = activeProject.videoQuality == qual,
                            activeColor = MikaColors.SsrGold,
                            onClick = {
                                selectedVideoQuality = qual
                                viewModel.updateMangaProjectConfig(activeProject.id, videoQuality = qual)
                            }
                        )
                    }
                }

                SimplePurrToggle(
                    title = "BURN-IN SUBTITLE HUD",
                    subtitle = "Draws speaker badge & dialogue pill cleanly below each panel",
                    checked = activeProject.burnInSubtitles,
                    accent = MikaColors.NeonCyan,
                    icon = CyberIconType.BOOK_NARRATOR,
                    onToggle = {
                        selectedBurnSubtitles = it
                        viewModel.updateMangaProjectConfig(activeProject.id, burnInSubtitles = it)
                    }
                )
                SimplePurrToggle(
                    title = "GACHA ATMOSPHERE PARTICLE FX",
                    subtitle = "Adds animated battle embers, rain streaks, sakura petals or sparkles",
                    checked = activeProject.enableAtmosphereFx,
                    accent = MikaColors.SsrGold,
                    icon = CyberIconType.BOLT_ACCEL,
                    onToggle = { viewModel.updateMangaProjectConfig(activeProject.id, enableAtmosphereFx = it) }
                )
                SimplePurrToggle(
                    title = "LOCK CHARACTER VOICES VIA QWEN CLONE (ANTI-DRIFT)",
                    subtitle = "ON by default: uses 1 VoiceDesign seed clip -> Qwen Base / Pocket clone to eliminate vocal drift.",
                    checked = activeProject.lockVoicesViaCloning,
                    accent = MikaColors.TerminalGreen,
                    icon = CyberIconType.DNA_CLONE,
                    onToggle = { checked ->
                        viewModel.setMangaLockVoicesViaCloning(checked)
                        viewModel.updateMangaProjectConfig(activeProject.id, lockVoicesViaCloning = checked)
                    }
                )
                SimplePurrToggle(
                    title = "EXPERIMENTAL: PROCESS NON-BUBBLE TEXT & SFX",
                    subtitle = "OFF by default (skips SFX, scanlation credits & signatures). Re-scan chapter after changing.",
                    checked = activeProject.includeNonBubbleText,
                    accent = MikaColors.NeonPurple,
                    icon = CyberIconType.BOLT_ACCEL,
                    onToggle = { checked ->
                        viewModel.setMangaIncludeNonBubbleText(checked)
                        viewModel.updateMangaProjectConfig(activeProject.id, includeNonBubbleText = checked)
                    }
                )
                SimplePurrToggle(
                    title = "EXPERIMENTAL: KV-CACHE PANEL SCANNING",
                    subtitle = "OFF by default — v1.9.7 showed small VLMs (Gemma E2B) hallucinating with it. May speed up multi-panel pages but can cause more hallucinations.",
                    checked = globalMangaKvCacheEnabled,
                    accent = MikaColors.MikaAmber,
                    icon = CyberIconType.BOLT_ACCEL,
                    onToggle = { checked ->
                        viewModel.setMangaKvCacheEnabled(checked)
                    }
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "PANELS:",
                        color = MikaColors.DimText,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Black,
                        fontSize = 8.sp,
                        modifier = Modifier.width(62.dp)
                    )
                    FlowRow(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        listOf(0 to "AUTO (8)", 4 to "4", 6 to "6", 8 to "8", 12 to "12").forEach { (value, label) ->
                            MikaTagPill(
                                label = label,
                                selected = globalMangaMaxPanelsPerPage == value,
                                activeColor = MikaColors.NeonCyan,
                                onClick = { viewModel.setMangaMaxPanelsPerPage(value) }
                            )
                        }
                    }
                }
                Text(
                    text = "ℹ️ AUTO trusts verified gutters up to 8 panels/page (no merge-away). Higher caps keep more panels but scan slower; re-scan chapter to apply.",
                    color = MikaColors.CyanWhite.copy(alpha = 0.85f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 7.8.sp,
                    lineHeight = 11.sp
                )

                // 3 Pre-Production Tabs
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    MangaPreProdTab.entries.forEach { tab ->
                        val selected = preProdTab == tab
                        val badgeLabel = when (tab) {
                            MangaPreProdTab.DECISIONS -> if (pendingCount > 0) "DECISIONS ($pendingCount)" else "DECISIONS (OK)"
                            MangaPreProdTab.CAST -> "CAST (${activeProject.characters.size})"
                            MangaPreProdTab.STORYBOARD -> "STORYBOARD (${activeChapter.totalPanelCount})"
                        }
                        val tabColor = when (tab) {
                            MangaPreProdTab.DECISIONS -> if (pendingCount > 0) MikaColors.MikaAmber else MikaColors.TerminalGreen
                            MangaPreProdTab.CAST -> MikaColors.NeonPurple
                            MangaPreProdTab.STORYBOARD -> MikaColors.NeonCyan
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(5.dp))
                                .background(if (selected) tabColor.copy(alpha = 0.22f) else MikaColors.DeepWellBg)
                                .border(1.2.dp, if (selected) tabColor else MikaColors.SubtleBorder, RoundedCornerShape(5.dp))
                                .clickable { preProdTab = tab }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = badgeLabel,
                                color = if (selected) tabColor else MikaColors.DimText,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Black,
                                fontSize = 8.5.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                when (preProdTab) {
                    MangaPreProdTab.DECISIONS -> {
                        if (pendingCount == 0) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(MikaColors.TerminalGreen.copy(alpha = 0.14f))
                                    .border(1.dp, MikaColors.TerminalGreen, RoundedCornerShape(6.dp))
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                MikaCyberIcon(type = CyberIconType.CHECK, tint = MikaColors.TerminalGreen, size = 14.dp)
                                Text(
                                    text = "ALL PANELS CONFIDENT! Customize cast voices in CAST ROSTER or hit RE-RENDER .MP4.",
                                    color = MikaColors.TerminalGreen,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 9.sp,
                                    lineHeight = 13.sp,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        } else {
                            MangaDecisionQueueList(activeProject, activeChapter, viewModel)
                        }
                    }

                    MangaPreProdTab.CAST -> {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                text = "// Tap CLONE VOICE on any character (e.g. Vegeta) to forge a custom clone in Clone Studio and auto-return here!",
                                color = MikaColors.DimText,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 8.5.sp,
                                lineHeight = 12.sp
                            )

                            activeProject.characters.forEach { character ->
                                MangaCharacterCastCard(
                                    project = activeProject,
                                    character = character,
                                    availableVoices = availableVoices,
                                    activeProfile = activeProfile,
                                    viewModel = viewModel,
                                    onLaunchCloneForCharacter = onLaunchCloneForCharacter
                                )
                            }

                            var newCharName by remember { mutableStateOf("") }
                            var newCharPrompt by remember { mutableStateOf("") }
                            var newCharGender by remember { mutableStateOf(VoiceGender.MALE) }
                            var showAddChar by remember { mutableStateOf(false) }

                            MikaTagPill(
                                label = if (showAddChar) "- HIDE ADD CHARACTER" else "+ ADD CHARACTER TO ROSTER",
                                selected = showAddChar,
                                activeColor = MikaColors.NeonPurple,
                                onClick = { showAddChar = !showAddChar }
                            )
                            AnimatedVisibility(visible = showAddChar) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(MikaColors.DeepWellBg)
                                        .border(1.dp, MikaColors.NeonPurple.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                                        .padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        VoiceGender.entries.forEach { g ->
                                            MikaTagPill(
                                                label = g.tag.uppercase(),
                                                selected = newCharGender == g,
                                                activeColor = MikaColors.NeonCyan,
                                                onClick = { newCharGender = g }
                                            )
                                        }
                                    }
                                    OutlinedTextField(
                                        value = newCharName,
                                        onValueChange = { newCharName = it },
                                        label = { Text("CHARACTER_NAME", fontSize = 8.sp, fontFamily = FontFamily.Monospace) },
                                        singleLine = true,
                                        textStyle = TextStyle(color = MikaColors.PrimaryText, fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                                        modifier = Modifier.fillMaxWidth().bringIntoViewWithIme()
                                    )
                                    OutlinedTextField(
                                        value = newCharPrompt,
                                        onValueChange = { newCharPrompt = it },
                                        label = { Text("QWEN_VOICEDESIGN_PROMPT", fontSize = 8.sp, fontFamily = FontFamily.Monospace) },
                                        minLines = 2,
                                        textStyle = TextStyle(color = MikaColors.CyanWhite, fontFamily = FontFamily.Monospace, fontSize = 10.sp),
                                        modifier = Modifier.fillMaxWidth().bringIntoViewWithIme()
                                    )
                                    MikaTagPill(
                                        label = "SAVE CHARACTER",
                                        selected = true,
                                        activeColor = MikaColors.TerminalGreen,
                                        onClick = {
                                            viewModel.addCustomMangaCharacter(
                                                projectId = activeProject.id,
                                                name = newCharName,
                                                gender = newCharGender,
                                                voiceDesignPrompt = newCharPrompt
                                            )
                                            newCharName = ""
                                            newCharPrompt = ""
                                            showAddChar = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    MangaPreProdTab.STORYBOARD -> {
                        val safePageIdx = selectedPageIdx.coerceIn(0, (activeChapter.pages.size - 1).coerceAtLeast(0))
                        val page = activeChapter.pages.getOrNull(safePageIdx)

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                activeChapter.pages.forEachIndexed { idx, pg ->
                                    MikaTagPill(
                                        label = "PAGE ${idx + 1} (${pg.panels.size})",
                                        selected = safePageIdx == idx,
                                        activeColor = MikaColors.NeonCyan,
                                        onClick = { selectedPageIdx = idx }
                                    )
                                }
                            }

                            page?.panels?.forEach { panel ->
                                var showThought by remember(panel.id) { mutableStateOf(false) }
                                val resolvedCam = remember(panel, page.panels.size, activeProject.videoAspect) {
                                    MangaDeterministicEffectsEngine.resolveCameraEffect(
                                        panel = panel,
                                        totalPanelsOnPage = page.panels.size,
                                        videoAspect = activeProject.videoAspect
                                    )
                                }
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(MikaColors.DeepWellBg)
                                        .border(1.dp, MikaColors.NeonCyan.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                                        .padding(9.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "PANEL ${panel.panelIndex + 1} // ${panel.sceneMood.ifBlank { "SCENE" }.uppercase()} • FX: ${resolvedCam.label}",
                                            color = MikaColors.NeonCyan,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Black,
                                            fontSize = 9.sp,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (panel.gemmaThoughtTrace.isNotBlank()) {
                                            MikaTagPill(
                                                label = if (showThought) "HIDE THOUGHT" else "THINK TRACE",
                                                selected = showThought,
                                                activeColor = MikaColors.NeonPurple,
                                                onClick = { showThought = !showThought }
                                            )
                                        }
                                    }
                                    if (showThought && panel.gemmaThoughtTrace.isNotBlank()) {
                                        Text(
                                            text = panel.gemmaThoughtTrace,
                                            color = MikaColors.VioletWhite,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 8.5.sp
                                        )
                                    }

                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                                        verticalArrangement = Arrangement.spacedBy(5.dp)
                                    ) {
                                        MangaPanelCameraEffect.entries.forEach { camFx ->
                                            MikaTagPill(
                                                label = camFx.label,
                                                selected = panel.cameraEffect == camFx,
                                                activeColor = MikaColors.NeonPurple,
                                                onClick = {
                                                    viewModel.updateMangaPanelCameraEffect(
                                                        projectId = activeProject.id,
                                                        chapterId = activeChapter.id,
                                                        pageIndex = safePageIdx,
                                                        panelId = panel.id,
                                                        cameraEffect = camFx
                                                    )
                                                }
                                            )
                                        }
                                    }

                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                                        verticalArrangement = Arrangement.spacedBy(5.dp)
                                    ) {
                                        MangaAtmospherePreset.entries.forEach { atmo ->
                                            MikaTagPill(
                                                label = atmo.label,
                                                selected = panel.atmosphere == atmo,
                                                activeColor = MikaColors.SsrGold,
                                                onClick = {
                                                    viewModel.updateMangaPanelAtmosphere(
                                                        projectId = activeProject.id,
                                                        chapterId = activeChapter.id,
                                                        pageIndex = safePageIdx,
                                                        panelId = panel.id,
                                                        atmosphere = atmo
                                                    )
                                                }
                                            )
                                        }
                                    }

                                    panel.lines.forEach { line ->
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(5.dp))
                                                .background(MikaColors.PanelBg)
                                                .border(0.8.dp, MikaColors.SubtleBorder, RoundedCornerShape(5.dp))
                                                .padding(8.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = "[${line.speakerName}] (${line.emotionTag})",
                                                    color = if (line.needsUserDecision) MikaColors.MikaAmber else MikaColors.TerminalGreen,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontWeight = FontWeight.Black,
                                                    fontSize = 9.sp
                                                )
                                                Text(
                                                    text = "pause=${line.postPauseMs}ms",
                                                    color = MikaColors.DimText,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontSize = 8.sp
                                                )
                                            }
                                            OutlinedTextField(
                                                value = line.rawText,
                                                onValueChange = { txt ->
                                                    viewModel.updateMangaLineText(
                                                        projectId = activeProject.id,
                                                        chapterId = activeChapter.id,
                                                        pageIndex = safePageIdx,
                                                        panelId = panel.id,
                                                        lineId = line.id,
                                                        newText = txt
                                                    )
                                                },
                                                textStyle = TextStyle(color = MikaColors.PrimaryText, fontFamily = FontFamily.Monospace, fontSize = 10.sp),
                                                minLines = 1,
                                                maxLines = 3,
                                                modifier = Modifier.fillMaxWidth().bringIntoViewWithIme()
                                            )
                                        }
                                    }

                                    MikaTagPill(
                                        label = "+ ADD DIALOGUE LINE TO PANEL",
                                        selected = false,
                                        activeColor = MikaColors.NeonCyan,
                                        onClick = {
                                            val firstChar = activeProject.characters.firstOrNull()?.id ?: ""
                                            viewModel.addMangaLineToPanel(
                                                projectId = activeProject.id,
                                                chapterId = activeChapter.id,
                                                pageIndex = safePageIdx,
                                                panelId = panel.id,
                                                speakerId = firstChar,
                                                text = "New dialogue line."
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // Power-User LAN Vision URL + Re-Scan Trigger
                ExpandableTerminalSection(
                    title = "POWER_USER // LAN_VISION_ENDPOINT_&_RESCAN",
                    expanded = expandPowerUser,
                    onToggle = { expandPowerUser = !expandPowerUser },
                    accent = MikaColors.NeonPurple
                ) {
                    var visionUrlInput by remember(activeProject.id) { mutableStateOf(activeProject.powerUserVisionUrl) }
                    OutlinedTextField(
                        value = visionUrlInput,
                        onValueChange = {
                            visionUrlInput = it
                            viewModel.updateMangaProjectConfig(activeProject.id, powerUserVisionUrl = it)
                        },
                        label = { Text("OPTIONAL_OPENAI_VISION_URL (LAN)", fontSize = 8.sp, fontFamily = FontFamily.Monospace) },
                        placeholder = { Text("http://192.168.1.50:1234/v1/chat/completions", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = MikaColors.DimText) },
                        singleLine = true,
                        textStyle = TextStyle(color = MikaColors.CyanWhite, fontFamily = FontFamily.Monospace, fontSize = 10.sp),
                        modifier = Modifier.fillMaxWidth().bringIntoViewWithIme()
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    MikaTagPill(
                        label = "RE-RUN VISION & VOICEDESIGN SCAN",
                        selected = true,
                        activeColor = MikaColors.NeonPurple,
                        onClick = {
                            if (!isBusy) viewModel.reanalyzeMangaChapter(activeProject.id, activeChapter.id)
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MangaDecisionQueueList(
    activeProject: MangaSeriesProject,
    activeChapter: MangaChapterEntry,
    viewModel: TtsForgeViewModel
) {
    val flaggedEntries = activeChapter.pages.flatMap { pg ->
        pg.panels.flatMap { pan ->
            pan.lines.filter { it.needsUserDecision }.map { ln -> Triple(pg.pageIndex, pan, ln) }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        flaggedEntries.forEach { (pgIdx, panel, line) ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(MikaColors.DeepWellBg)
                    .border(1.dp, MikaColors.MikaAmber.copy(alpha = 0.65f), RoundedCornerShape(6.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "PAGE ${pgIdx + 1} • PANEL ${panel.panelIndex + 1} • CONFIRM SPEAKER",
                    color = MikaColors.MikaAmber,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    fontSize = 9.5.sp
                )
                if (line.decisionReason.isNotBlank()) {
                    Text(
                        text = line.decisionReason,
                        color = MikaColors.DimText,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 8.5.sp
                    )
                }
                if (panel.gemmaThoughtTrace.isNotBlank()) {
                    Text(
                        text = "// Gemma Trace: ${panel.gemmaThoughtTrace.take(180)}",
                        color = MikaColors.NeonCyan.copy(alpha = 0.85f),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 8.2.sp
                    )
                }
                OutlinedTextField(
                    value = line.rawText,
                    onValueChange = { updatedText ->
                        viewModel.updateMangaLineText(
                            projectId = activeProject.id,
                            chapterId = activeChapter.id,
                            pageIndex = pgIdx,
                            panelId = panel.id,
                            lineId = line.id,
                            newText = updatedText
                        )
                    },
                    label = { Text("DIALOGUE_LINE", fontSize = 8.sp, fontFamily = FontFamily.Monospace) },
                    minLines = 1,
                    maxLines = 3,
                    textStyle = TextStyle(color = MikaColors.PrimaryText, fontFamily = FontFamily.Monospace, fontSize = 10.5.sp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MikaColors.MikaAmber,
                        unfocusedBorderColor = MikaColors.MikaAmber.copy(alpha = 0.35f),
                        focusedContainerColor = MikaColors.PanelBg,
                        unfocusedContainerColor = MikaColors.PanelBg
                    ),
                    modifier = Modifier.fillMaxWidth().bringIntoViewWithIme()
                )
                Text(
                    text = "TAP SPEAKER TO ASSIGN:",
                    color = MikaColors.CyanWhite,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 8.5.sp
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    activeProject.characters.forEach { ch ->
                        MikaTagPill(
                            label = ch.name,
                            selected = line.speakerId == ch.id,
                            activeColor = MikaColors.TerminalGreen,
                            onClick = {
                                viewModel.resolveMangaDecision(
                                    projectId = activeProject.id,
                                    chapterId = activeChapter.id,
                                    pageIndex = pgIdx,
                                    panelId = panel.id,
                                    lineId = line.id,
                                    characterId = ch.id
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MangaVisionModelInstallRow(
    spec: ModelBundleSpec,
    bundleState: ModelBundleState?,
    badgeLabel: String,
    badgeColor: Color,
    accent: Color,
    onDownload: () -> Unit,
    onPause: () -> Unit
) {
    val installed = bundleState?.state == DownloadState.INSTALLED
    val isDownloading = bundleState?.state == DownloadState.DOWNLOADING ||
        bundleState?.state == DownloadState.VERIFYING
    val rowAccent = if (installed) MikaColors.TerminalGreen else accent

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(5.dp))
            .background(MikaColors.PanelBg.copy(alpha = 0.72f))
            .border(1.dp, rowAccent.copy(alpha = 0.55f), RoundedCornerShape(5.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        text = "${spec.romCode} // ${spec.name.replace("_LITERT", "")}",
                        color = rowAccent,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Black,
                        fontSize = 8.8.sp
                    )
                    Text(
                        text = badgeLabel,
                        color = badgeColor,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Black,
                        fontSize = 8.2.sp
                    )
                    if (installed) {
                        Text(
                            text = "[READY]",
                            color = MikaColors.TerminalGreen,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Black,
                            fontSize = 8.2.sp
                        )
                    }
                }
                Text(
                    text = "${spec.hardwareAccelNote} (+${spec.approximateSizeMb} MB)",
                    color = MikaColors.DimText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 7.8.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (!installed) {
                MikaTagPill(
                    label = if (isDownloading) "PAUSE" else "INSTALL",
                    selected = true,
                    activeColor = if (isDownloading) MikaColors.MikaAmber else rowAccent,
                    leadingIcon = if (isDownloading) CyberIconType.PAUSE else CyberIconType.DOWNLOAD,
                    onClick = { if (isDownloading) onPause() else onDownload() }
                )
            }
        }
        if (isDownloading && bundleState != null) {
            LinearProgressIndicator(
                progress = { bundleState.progressFraction.coerceIn(0f, 1f) },
                color = rowAccent,
                trackColor = rowAccent.copy(alpha = 0.16f),
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "${(bundleState.progressFraction * 100).toInt()}% (${bundleState.downloadedBytes / (1024 * 1024)} / ${spec.approximateSizeMb} MB)",
                color = rowAccent,
                fontFamily = FontFamily.Monospace,
                fontSize = 8.sp
            )
        }
    }
}

@Composable
private fun MangaTelemetryBanner(
    telemetry: MangaJobTelemetry,
    statusFallback: String,
    progress: Float,
    isBusy: Boolean,
    onCancel: () -> Unit
) {
    val pct = (progress.coerceIn(0f, 1f) * 100f).toInt()
    val elapsedSec = (telemetry.elapsedMs / 1000L).toInt().coerceAtLeast(0)
    val elapsedText = MangaTelemetryUtils.formatDurationShort(elapsedSec)
    val etaText = telemetry.estimatedRemainingSec?.let { "~${MangaTelemetryUtils.formatDurationShort(it)}" }
        ?: if (isBusy) "ESTIMATING..." else "DONE"
    val borderAccent = when {
        statusFallback.contains("ERROR", ignoreCase = true) || statusFallback.contains("❌") -> MikaColors.DangerRed
        statusFallback.contains("🎉") || (!isBusy && progress >= 0.99f) -> MikaColors.SsrGold
        isBusy -> MikaColors.TerminalGreen
        else -> MikaColors.NeonCyan
    }

    val logScrollState = rememberScrollState()
    LaunchedEffect(telemetry.logTail.size) {
        if (logScrollState.maxValue > 0) {
            logScrollState.animateScrollTo(logScrollState.maxValue)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MikaColors.DeepWellBg)
            .border(1.2.dp, borderAccent.copy(alpha = 0.72f), RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        // 5-Stage Pipeline Stepper
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            listOf(
                MangaPipelineStage.EXTRACT_AND_SLICE to "1 SLICE",
                MangaPipelineStage.VISION_SCAN to "2 VISION",
                MangaPipelineStage.VOICE_CASTING to "3 CAST",
                MangaPipelineStage.VOICE_FORGE to "4 VOICE",
                MangaPipelineStage.VIDEO_MUX to "5 .MP4"
            ).forEach { (st, title) ->
                val isCurrent = telemetry.stage == st
                val isPast = telemetry.stage.stepNum > st.stepNum || (!isBusy && progress >= 0.99f)
                val c = when {
                    isCurrent -> MikaColors.TerminalGreen
                    isPast -> MikaColors.NeonCyan
                    else -> MikaColors.DimText.copy(alpha = 0.48f)
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (isCurrent) c.copy(alpha = 0.24f) else MikaColors.PanelBg.copy(alpha = 0.6f))
                        .border(1.dp, c.copy(alpha = if (isCurrent) 0.95f else 0.35f), RoundedCornerShape(4.dp))
                        .padding(vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = title,
                        color = c,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = if (isCurrent) FontWeight.Black else FontWeight.Bold,
                        fontSize = 7.2.sp,
                        maxLines = 1
                    )
                }
            }
        }

        // Percent, Item Counter, Elapsed & Dynamic ETA
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val counterSuffix = if (telemetry.itemTotal > 0) " (${telemetry.itemCurrent}/${telemetry.itemTotal})" else ""
            Text(
                text = "COOK PROGRESS: $pct%$counterSuffix",
                color = borderAccent,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Black,
                fontSize = 9.5.sp
            )
            Text(
                text = "ELAPSED $elapsedText • ETA $etaText",
                color = MikaColors.SsrGold,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 8.5.sp
            )
        }

        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            color = borderAccent,
            trackColor = MikaColors.PanelBg,
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = telemetry.detailText.ifBlank { statusFallback },
                color = MikaColors.CyanWhite,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 9.sp,
                modifier = Modifier.weight(1f)
            )
            if (isBusy) {
                MikaTagPill(
                    label = "STOP",
                    selected = true,
                    activeColor = MikaColors.DangerRed,
                    onClick = onCancel
                )
            }
        }

        if (isBusy) {
            Text(
                text = "💡 SCREEN AWAKE LOCK ACTIVE • STAY IN APP FOR BEST GPU/CODEC SPEED",
                color = MikaColors.TerminalGreen.copy(alpha = 0.88f),
                fontFamily = FontFamily.Monospace,
                fontSize = 7.6.sp
            )
        }

        // Rich Scrollable Cooking Log Console (up to 40 timestamped lines)
        if (telemetry.logTail.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 76.dp, max = 185.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color(0xFF06050B))
                    .border(1.dp, borderAccent.copy(alpha = 0.35f), RoundedCornerShape(5.dp))
                    .padding(8.dp)
                    .verticalScroll(logScrollState),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = "> LIVE_COOK_LOG (${telemetry.logTail.size} EVENTS)",
                    color = MikaColors.NeonCyan.copy(alpha = 0.72f),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    fontSize = 7.5.sp
                )
                telemetry.logTail.forEach { entry ->
                    val logColor = when {
                        entry.contains("❌") || entry.contains("ERROR", ignoreCase = true) -> MikaColors.DangerRed
                        entry.contains("⚠️") || entry.contains("⏸️") -> MikaColors.MikaAmber
                        entry.contains("🎭") -> MikaColors.NeonPurple
                        entry.contains("🧠") || entry.contains("💭") -> MikaColors.NeonCyan
                        entry.contains("🎙️") -> MikaColors.NeonPink
                        entry.contains("🎉") || entry.contains("✅") -> MikaColors.TerminalGreen
                        entry.contains("🎬") -> MikaColors.SsrGold
                        else -> MikaColors.CyanWhite.copy(alpha = 0.78f)
                    }
                    Text(
                        text = entry,
                        color = logColor,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 8.sp,
                        lineHeight = 11.5.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun MangaCharacterCastCard(
    project: MangaSeriesProject,
    character: MangaCharacterProfile,
    availableVoices: List<VoiceDescriptor>,
    activeProfile: PurrProfile,
    viewModel: TtsForgeViewModel,
    onLaunchCloneForCharacter: (MangaCloneHandoffTarget) -> Unit
) {
    var expandDesign by remember(character.id) { mutableStateOf(false) }
    var promptDraft by remember(character.id, character.voiceDesignPrompt) { mutableStateOf(character.voiceDesignPrompt) }
    val avatarBitmap = remember(character.avatarCropPath) {
        if (character.avatarCropPath.isNotBlank() && File(character.avatarCropPath).isFile) {
            runCatching { BitmapFactory.decodeFile(character.avatarCropPath)?.asImageBitmap() }.getOrNull()
        } else {
            null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MikaColors.DeepWellBg)
            .border(1.dp, MikaColors.NeonPurple.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MikaColors.PanelBg)
                    .border(1.dp, MikaColors.NeonCyan.copy(alpha = 0.6f), RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (avatarBitmap != null) {
                    Image(
                        bitmap = avatarBitmap,
                        contentDescription = character.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(46.dp)
                    )
                } else {
                    Text(
                        text = character.name.take(2),
                        color = MikaColors.NeonCyan,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Black,
                        fontSize = 14.sp
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${character.name} [${character.gender.tag.uppercase()}]",
                    color = MikaColors.PrimaryText,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    // ✨ v1.9.9: show the character's EFFECTIVE dub voice label — the auto clone
                    // lock when active — instead of a static "AUTO (Qwen VoiceDesign)" that
                    // hides the locked clone.
                    text = "${character.lineCount} lines • " + when {
                        character.assignedVoiceId.isNotBlank() -> character.assignedVoiceLabel
                        character.autoCloneVoiceId.isNotBlank() -> "AUTO (Qwen VoiceClone Lock)"
                        else -> character.assignedVoiceLabel
                    },
                    color = MikaColors.NeonCyan,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 8.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            MikaTagPill(
                label = "TEST",
                selected = true,
                activeColor = MikaColors.TerminalGreen,
                leadingIcon = CyberIconType.PLAY,
                onClick = { viewModel.auditionMangaCharacter(character) }
            )
        }

        if (availableVoices.isNotEmpty()) {
            // ✨ v1.9.9: the card shows the character's EFFECTIVE dub voice — the auto clone lock
            // when active — never the user's global system-TTS selection (which silently
            // mislabeled an active Qwen clone as e.g. "Phonon // Snow Queen").
            val selectedVoiceDesc = availableVoices.firstOrNull { it.id == character.assignedVoiceId }
                ?: availableVoices.firstOrNull { it.id == character.autoCloneVoiceId }
                ?: availableVoices.firstOrNull { it.id == activeProfile.primaryVoiceId }
                ?: availableVoices.first()
            MikaVoiceDropdownSelector(
                label = "${character.name} // ASSIGNED VOICE (OR CLONE)",
                voices = availableVoices,
                selectedVoice = selectedVoiceDesc,
                onSelectVoice = { chosen ->
                    viewModel.assignMangaCharacterVoice(
                        projectId = project.id,
                        characterId = character.id,
                        voiceId = chosen.id,
                        voiceLabel = chosen.displayName
                    )
                },
                onSampleVoice = { chosen -> viewModel.auditionVoiceDescriptor(chosen) },
                accent = MikaColors.NeonPurple
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(5.dp))
                    .background(MikaColors.NeonPink.copy(alpha = 0.18f))
                    .border(1.dp, MikaColors.NeonPink, RoundedCornerShape(5.dp))
                    .clickable {
                        onLaunchCloneForCharacter(
                            MangaCloneHandoffTarget(
                                projectId = project.id,
                                characterId = character.id,
                                characterName = character.name,
                                suggestedGender = character.gender,
                                suggestedTags = "manga, anime, ${character.name.lowercase()}"
                            )
                        )
                    }
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                MikaCyberIcon(type = CyberIconType.DNA_CLONE, tint = MikaColors.NeonPink, size = 11.dp)
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = "CLONE VOICE FOR ${character.name.take(10)}",
                    color = MikaColors.NeonPink,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    fontSize = 8.5.sp
                )
            }

            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(5.dp))
                    .background(MikaColors.NeonCyan.copy(alpha = 0.14f))
                    .border(1.dp, MikaColors.NeonCyan, RoundedCornerShape(5.dp))
                    .clickable { expandDesign = !expandDesign }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (expandDesign) "HIDE LAB" else "VOICEDESIGN",
                    color = MikaColors.NeonCyan,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    fontSize = 8.5.sp
                )
            }
        }

        AnimatedVisibility(visible = expandDesign) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = promptDraft,
                    onValueChange = {
                        promptDraft = it
                        viewModel.assignMangaCharacterVoice(
                            projectId = project.id,
                            characterId = character.id,
                            voiceId = "",
                            voiceLabel = "AUTO (Qwen VoiceDesign)",
                            voiceDesignPrompt = it
                        )
                    },
                    label = { Text("QWEN3_VOICEDESIGN_PROMPT", fontSize = 8.sp, fontFamily = FontFamily.Monospace) },
                    minLines = 2,
                    textStyle = TextStyle(color = MikaColors.CyanWhite, fontFamily = FontFamily.Monospace, fontSize = 10.sp),
                    modifier = Modifier.fillMaxWidth().bringIntoViewWithIme()
                )
                Text(
                    text = "SPEED ${"%.2f".format(character.speedScale)}x | PITCH ${"%.2f".format(character.pitchScale)}x",
                    color = MikaColors.SsrGold,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 8.5.sp
                )
                Slider(
                    value = character.speedScale,
                    onValueChange = { s ->
                        val snapped = (s * 20f).roundToInt() / 20f
                        viewModel.assignMangaCharacterVoice(
                            projectId = project.id,
                            characterId = character.id,
                            voiceId = character.assignedVoiceId,
                            voiceLabel = character.assignedVoiceLabel,
                            speedScale = snapped
                        )
                    },
                    valueRange = 0.7f..1.4f,
                    colors = SliderDefaults.colors(thumbColor = MikaColors.SsrGold, activeTrackColor = MikaColors.SsrGold)
                )
                Slider(
                    value = character.pitchScale,
                    onValueChange = { p ->
                        val snapped = (p * 50f).roundToInt() / 50f
                        viewModel.assignMangaCharacterVoice(
                            projectId = project.id,
                            characterId = character.id,
                            voiceId = character.assignedVoiceId,
                            voiceLabel = character.assignedVoiceLabel,
                            pitchScale = snapped
                        )
                    },
                    valueRange = 0.88f..1.12f,
                    colors = SliderDefaults.colors(thumbColor = MikaColors.NeonCyan, activeTrackColor = MikaColors.NeonCyan)
                )
            }
        }
    }
}

@Composable
private fun CompactCyberQtyBox(
    value: String,
    onValueChange: (String) -> Unit,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .width(42.dp)
            .height(22.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MikaColors.DeepWellBg)
            .border(
                width = 1.dp,
                color = if (value.isNotEmpty()) accent else accent.copy(alpha = 0.42f),
                shape = RoundedCornerShape(4.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            cursorBrush = SolidColor(accent),
            textStyle = TextStyle(
                color = MikaColors.PrimaryText,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Black,
                fontSize = 9.5.sp,
                textAlign = TextAlign.Center
            ),
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    if (value.isEmpty()) {
                        Text(
                            text = "AUTO",
                            color = accent.copy(alpha = 0.65f),
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 7.8.sp
                        )
                    }
                    innerTextField()
                }
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 3.dp)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MangaPreCookReviewPanel(
    project: MangaSeriesProject,
    chapter: MangaChapterEntry,
    isBusy: Boolean,
    viewModel: TtsForgeViewModel,
    onOpenFullCastDrawer: () -> Unit,
    onOpenStoryboardDrawer: () -> Unit
) {
    var editingStory by remember(project.id) { mutableStateOf(false) }
    var storyDraft by remember(project.id, project.storyContextSummary) {
        mutableStateOf(project.storyContextSummary)
    }
    var baselineInput by remember(project.id, project.expectedCharacterCount) {
        mutableStateOf(if (project.expectedCharacterCount > 0) project.expectedCharacterCount.toString() else "")
    }

    MikaGlowPanel(accent = MikaColors.NeonCyan) {
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
                MikaCyberIcon(type = CyberIconType.DUAL_MASKS, tint = MikaColors.NeonCyan, size = 14.dp)
                Text(
                    text = "> PRE-COOK STORY & CAST (${project.characters.size} CAST | ${chapter.totalLineCount} BUBBLES)",
                    color = MikaColors.NeonCyan,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            MikaTagPill(
                label = if (editingStory) "DONE" else "EDIT STORY",
                selected = editingStory,
                activeColor = MikaColors.NeonPurple,
                onClick = { editingStory = !editingStory }
            )
        }
        Spacer(modifier = Modifier.height(6.dp))

        // 1. Story Context Bible (View or Edit before cooking)
        if (editingStory) {
            OutlinedTextField(
                value = storyDraft,
                onValueChange = {
                    storyDraft = it
                    viewModel.updateMangaStorySummary(project.id, it)
                },
                label = { Text("STORY_SUMMARY_CONTEXT", fontSize = 8.sp, fontFamily = FontFamily.Monospace) },
                minLines = 2,
                maxLines = 4,
                textStyle = TextStyle(color = MikaColors.CyanWhite, fontFamily = FontFamily.Monospace, fontSize = 9.5.sp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MikaColors.NeonCyan,
                    unfocusedBorderColor = MikaColors.NeonCyan.copy(alpha = 0.35f),
                    focusedContainerColor = MikaColors.DeepWellBg,
                    unfocusedContainerColor = MikaColors.DeepWellBg
                ),
                modifier = Modifier.fillMaxWidth().bringIntoViewWithIme()
            )
        } else {
            Text(
                text = project.storyContextSummary.ifBlank {
                    "No storyboard summary yet — tap RESCAN CHAPTER or EDIT STORY to describe the plot."
                },
                color = MikaColors.CyanWhite.copy(alpha = 0.85f),
                fontFamily = FontFamily.Monospace,
                fontSize = 8.8.sp,
                lineHeight = 12.5.sp
            )
        }
        Spacer(modifier = Modifier.height(8.dp))

        // 2. Character Count Baseline Editor + Consolidation / Rescan Controls
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(MikaColors.DeepWellBg)
                .border(1.dp, MikaColors.NeonPurple.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text(
                    text = "CHAR_CAP:",
                    color = MikaColors.NeonPurple,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    fontSize = 8.2.sp
                )
                MikaTagPill(
                    label = "AUTO",
                    selected = project.expectedCharacterCount == 0,
                    activeColor = MikaColors.NeonCyan,
                    onClick = {
                        baselineInput = ""
                        viewModel.applyMangaCharacterCountBaseline(project.id, chapter.id, 0)
                    }
                )
                MikaTagPill(
                    label = "-",
                    selected = false,
                    activeColor = MikaColors.NeonPurple,
                    onClick = {
                        val cur = baselineInput.toIntOrNull()?.coerceIn(0, 24) ?: project.expectedCharacterCount
                        val next = (cur - 1).coerceIn(0, 24)
                        baselineInput = if (next > 0) next.toString() else ""
                        viewModel.applyMangaCharacterCountBaseline(project.id, chapter.id, next)
                    }
                )
                CompactCyberQtyBox(
                    value = baselineInput,
                    onValueChange = { raw ->
                        baselineInput = raw.filter { it.isDigit() }.take(2)
                    },
                    accent = MikaColors.NeonPurple,
                    modifier = Modifier.bringIntoViewWithIme()
                )
                MikaTagPill(
                    label = "+",
                    selected = false,
                    activeColor = MikaColors.TerminalGreen,
                    onClick = {
                        val cur = baselineInput.toIntOrNull()?.coerceIn(0, 24) ?: project.expectedCharacterCount
                        val next = (cur + 1).coerceIn(1, 24)
                        baselineInput = next.toString()
                        viewModel.applyMangaCharacterCountBaseline(project.id, chapter.id, next)
                    }
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                MikaTagPill(
                    label = "APPLY CAP",
                    selected = true,
                    activeColor = MikaColors.TerminalGreen,
                    onClick = {
                        val targetCount = baselineInput.toIntOrNull()?.coerceIn(0, 24) ?: 0
                        viewModel.applyMangaCharacterCountBaseline(project.id, chapter.id, targetCount)
                    }
                )
                MikaTagPill(
                    label = "RESCAN",
                    selected = false,
                    activeColor = MikaColors.SsrGold,
                    onClick = {
                        if (!isBusy) {
                            val targetCount = baselineInput.toIntOrNull()?.coerceIn(0, 24) ?: 0
                            viewModel.setMangaExpectedCharacterCount(targetCount)
                            viewModel.reanalyzeMangaChapter(project.id, chapter.id)
                        }
                    }
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        // 3. Quick Pre-Cook Cast, Gender & Effective Voice List
        if (project.characters.isEmpty()) {
            Text(
                text = "No cast discovered yet — tap RESCAN above or add characters in CAST ROSTER.",
                color = MikaColors.DimText,
                fontFamily = FontFamily.Monospace,
                fontSize = 8.5.sp
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                project.characters.forEach { ch ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(5.dp))
                            .background(MikaColors.DeepWellBg)
                            .border(1.dp, MikaColors.SubtleBorder, RoundedCornerShape(5.dp))
                            .padding(horizontal = 8.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${ch.name} (${ch.lineCount}L)",
                                color = MikaColors.PrimaryText,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Black,
                                fontSize = 9.5.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = when {
                                    ch.assignedVoiceId.isNotBlank() -> ch.assignedVoiceLabel
                                    ch.autoCloneVoiceId.isNotBlank() -> "Qwen Clone Lock"
                                    else -> ch.assignedVoiceLabel
                                },
                                color = MikaColors.NeonCyan,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 7.8.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            listOf(
                                VoiceGender.FEMALE to "F",
                                VoiceGender.MALE to "M",
                                VoiceGender.ANDROGYNOUS to "N"
                            ).forEach { (g, shortTag) ->
                                val gColor = when (g) {
                                    VoiceGender.FEMALE -> MikaColors.NeonPink
                                    VoiceGender.MALE -> MikaColors.NeonCyan
                                    VoiceGender.ANDROGYNOUS -> MikaColors.SsrGold
                                }
                                MikaTagPill(
                                    label = shortTag,
                                    selected = ch.gender == g,
                                    activeColor = gColor,
                                    onClick = { viewModel.updateMangaCharacterGender(project.id, ch.id, g) }
                                )
                            }
                            MikaTagPill(
                                label = "",
                                selected = true,
                                activeColor = MikaColors.TerminalGreen,
                                leadingIcon = CyberIconType.PLAY,
                                onClick = { viewModel.auditionMangaCharacter(ch) }
                            )
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            MikaTagPill(
                label = "EDIT VOICES & CLONES (${project.characters.size})",
                selected = false,
                activeColor = MikaColors.NeonPurple,
                onClick = onOpenFullCastDrawer
            )
            MikaTagPill(
                label = "EDIT PANELS & BUBBLES (${chapter.totalPanelCount})",
                selected = false,
                activeColor = MikaColors.NeonCyan,
                onClick = onOpenStoryboardDrawer
            )
        }
    }
}
