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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.projectmika.purrtts.core.manga.MangaAtmospherePreset
import com.projectmika.purrtts.core.manga.MangaChapterStatus
import com.projectmika.purrtts.core.manga.MangaCharacterProfile
import com.projectmika.purrtts.core.manga.MangaCloneHandoffTarget
import com.projectmika.purrtts.core.manga.MangaReadingOrder
import com.projectmika.purrtts.core.manga.MangaSeriesProject
import com.projectmika.purrtts.core.manga.MangaVideoAspect
import com.projectmika.purrtts.core.purr.DownloadState
import com.projectmika.purrtts.core.purr.PurrForgeState
import com.projectmika.purrtts.core.purr.PurrProfile
import com.projectmika.purrtts.core.purr.VoiceDescriptor
import com.projectmika.purrtts.core.purr.VoiceGender
import com.projectmika.purrtts.engine.manga.LiteRtGemmaVisionEngine
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

/**
 * ✨ TOOL-11: MANGA DUB & MOTION-COMIC STUDIO MODULE WINDOW ✨
 *
 * Auto-Pilot CBZ Importer -> BFS Panel Slicer -> Gemma 4 E2B LiteRT Vision Pre-Production ->
 * 1-Tap Clone Studio Round-Trip (e.g. craft Vegeta voice and return straight to Roster) ->
 * Hardware MediaCodec .MP4 Motion-Comic Renderer with GachaSwipe Emotion Pacing & Atmosphere FX.
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

    val activeProject = remember(projects, activeProjectId) {
        projects.firstOrNull { it.id == activeProjectId } ?: projects.firstOrNull()
    }
    val activeChapter = activeProject?.chapters?.lastOrNull()

    var seriesTitleInput by remember { mutableStateOf("") }
    var importIntoCurrentSeries by remember { mutableStateOf(true) }
    var selectedReadingOrder by remember(activeProject?.id) {
        mutableStateOf(activeProject?.readingOrder ?: MangaReadingOrder.RTL_MANGA)
    }
    var preProdTab by remember { mutableStateOf(MangaPreProdTab.DECISIONS) }
    var selectedPageIdx by remember(activeChapter?.id) { mutableIntStateOf(0) }
    var expandPowerUser by remember { mutableStateOf(false) }

    val gemmaSpec = remember(viewModel.modelCatalog) {
        viewModel.modelCatalog.firstOrNull { it.id == LiteRtGemmaVisionEngine.GEMMA_VISION_SPEC_ID }
    }
    val gemmaState = forgeState.modelStates[LiteRtGemmaVisionEngine.GEMMA_VISION_SPEC_ID]
    val gemmaInstalled = gemmaState?.state == DownloadState.INSTALLED

    val cbzPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            val targetId = if (importIntoCurrentSeries) activeProject?.id else null
            viewModel.importMangaCbz(
                uri = it,
                targetProjectId = targetId,
                seriesTitleInput = seriesTitleInput,
                readingOrder = selectedReadingOrder
            )
        }
    }

    // 1. Header, Gemma 4 E2B Vision Cartridge Bar & CBZ Project Importer
    MikaGlowPanel(accent = MikaColors.NeonPink) {
        Text(
            text = "> MANGA_DUB_STUDIO // CBZ -> PRE-PROD -> .MP4",
            color = MikaColors.NeonPink,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Black,
            fontSize = 11.sp
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Drop in a manga .CBZ archive. PURR slices panels automatically via BFS, scans dialogue & characters with Gemma 4 E2B LiteRT Vision + Thinking, lets you resolve flagged bubbles or forge custom clones in Pre-Production, and exports a dubbed motion-comic .MP4.",
            color = MikaColors.CyanWhite.copy(alpha = 0.78f),
            fontFamily = FontFamily.Monospace,
            fontSize = 9.5.sp,
            lineHeight = 13.5.sp
        )
        Spacer(modifier = Modifier.height(8.dp))

        // Gemma 4 E2B LiteRT Vision Engine Cartridge Strip
        if (gemmaSpec != null) {
            val visionAccent = if (gemmaInstalled) MikaColors.TerminalGreen else MikaColors.NeonCyan
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(MikaColors.DeepWellBg)
                    .border(1.dp, visionAccent.copy(alpha = 0.65f), RoundedCornerShape(6.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (gemmaInstalled) {
                                "[READY] GEMMA 4 E2B LITERT VISION + THINKING"
                            } else {
                                "SUB-11V // GEMMA 4 E2B LITERT VISION (+2.2 GB)"
                            },
                            color = visionAccent,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Black,
                            fontSize = 9.5.sp
                        )
                        Text(
                            text = if (gemmaInstalled) {
                                "Offline OCR, character discovery & Qwen VoiceDesign direction active (unloads cleanly before TTS)."
                            } else {
                                "Optional offline AI Vision engine. BFS panel slicing & Pre-Production still work without it!"
                            },
                            color = MikaColors.DimText,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 8.5.sp
                        )
                    }
                    if (!gemmaInstalled) {
                        val isDownloading = gemmaState?.state == DownloadState.DOWNLOADING ||
                            gemmaState?.state == DownloadState.VERIFYING
                        MikaTagPill(
                            label = if (isDownloading) "PAUSE" else "INSTALL GEMMA4",
                            selected = true,
                            activeColor = if (isDownloading) MikaColors.MikaAmber else MikaColors.NeonCyan,
                            leadingIcon = if (isDownloading) CyberIconType.PAUSE else CyberIconType.DOWNLOAD,
                            onClick = {
                                if (isDownloading) viewModel.pauseModelDownload(gemmaSpec.id)
                                else viewModel.startModelDownload(gemmaSpec)
                            }
                        )
                    }
                }
                if (gemmaState?.state == DownloadState.DOWNLOADING || gemmaState?.state == DownloadState.VERIFYING) {
                    LinearProgressIndicator(
                        progress = { gemmaState.progressFraction },
                        color = MikaColors.NeonCyan,
                        trackColor = MikaColors.NeonCyan.copy(alpha = 0.16f),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "${(gemmaState.progressFraction * 100).toInt()}% (${gemmaState.downloadedBytes / (1024 * 1024)} / ${gemmaSpec.approximateSizeMb} MB)",
                        color = MikaColors.NeonCyan,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 8.5.sp
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (projects.isNotEmpty() && activeProject != null) {
            CyberDropdown(
                label = "> ACTIVE_SERIES_PROJECT (${projects.size})",
                options = projects,
                selectedOption = activeProject,
                optionLabel = { p ->
                    val chCount = p.chapters.size
                    "${p.seriesTitle} ($chCount ch | ${p.characters.size} cast)"
                },
                onSelect = { viewModel.selectMangaProject(it.id) },
                accent = MikaColors.NeonPink
            )
            Spacer(modifier = Modifier.height(6.dp))
        }

        OutlinedTextField(
            value = seriesTitleInput,
            onValueChange = { seriesTitleInput = it },
            label = { Text("SERIES_TITLE (OPTIONAL WHEN STARTING NEW)", fontSize = 8.5.sp, fontFamily = FontFamily.Monospace) },
            placeholder = { Text("e.g. Dragon Cyber", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MikaColors.DimText) },
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

        if (activeProject != null) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                MikaTagPill(
                    label = if (importIntoCurrentSeries) "ADD CHAPTER TO: ${activeProject.seriesTitle.take(16)}" else "CREATE NEW SERIES PROJECT",
                    selected = importIntoCurrentSeries,
                    activeColor = MikaColors.TerminalGreen,
                    onClick = { importIntoCurrentSeries = !importIntoCurrentSeries }
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            MangaReadingOrder.entries.forEach { order ->
                MikaTagPill(
                    label = order.label,
                    selected = selectedReadingOrder == order,
                    activeColor = MikaColors.NeonCyan,
                    onClick = {
                        selectedReadingOrder = order
                        activeProject?.let { viewModel.updateMangaProjectConfig(it.id, readingOrder = order) }
                    }
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MikaColors.NeonPink.copy(alpha = 0.20f))
                    .border(1.5.dp, MikaColors.NeonPink, RoundedCornerShape(6.dp))
                    .clickable(enabled = !isBusy) {
                        cbzPicker.launch(
                            arrayOf("application/x-cbz", "application/zip", "application/octet-stream", "*/*")
                        )
                    }
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                MikaCyberIcon(type = CyberIconType.DOWNLOAD, tint = MikaColors.NeonPink, size = 12.dp)
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "+ IMPORT .CBZ",
                    color = MikaColors.NeonPink,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    fontSize = 10.sp
                )
            }

            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MikaColors.SsrGold.copy(alpha = 0.16f))
                    .border(1.5.dp, MikaColors.SsrGold, RoundedCornerShape(6.dp))
                    .clickable(enabled = !isBusy) { viewModel.createSampleMangaProject() }
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                MikaCyberIcon(type = CyberIconType.BOLT_ACCEL, tint = MikaColors.SsrGold, size = 12.dp)
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = "LOAD DEMO CHAPTER",
                    color = MikaColors.SsrGold,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    fontSize = 9.5.sp
                )
            }
        }
    }

    // 2. Active Project Pre-Production Desk
    if (activeProject != null && activeChapter != null) {
        val pendingCount = activeChapter.pendingDecisionCount

        MikaGlowPanel(accent = if (pendingCount > 0) MikaColors.MikaAmber else MikaColors.NeonCyan) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "> PRE_PRODUCTION // ${activeChapter.title.uppercase()}",
                        color = if (pendingCount > 0) MikaColors.MikaAmber else MikaColors.NeonCyan,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Black,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${activeChapter.pages.size} pages | ${activeChapter.totalPanelCount} panels | ${activeChapter.totalLineCount} lines | ${activeProject.characters.size} cast",
                        color = MikaColors.DimText,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 8.5.sp
                    )
                }
                MikaTagPill(
                    label = "DEL",
                    selected = true,
                    activeColor = MikaColors.DangerRed,
                    onClick = { viewModel.deleteMangaProject(activeProject.id) }
                )
            }
            Spacer(modifier = Modifier.height(8.dp))

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
            Spacer(modifier = Modifier.height(10.dp))

            when (preProdTab) {
                MangaPreProdTab.DECISIONS -> {
                    val flaggedEntries = activeChapter.pages.flatMap { pg ->
                        pg.panels.flatMap { pan ->
                            pan.lines.filter { it.needsUserDecision }.map { ln -> Triple(pg.pageIndex, pan, ln) }
                        }
                    }
                    if (flaggedEntries.isEmpty()) {
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
                                text = "ALL PANELS CONFIDENT! Customize character voices or Vegeta-style clones in CAST ROSTER, then hit PRODUCE .MP4 below.",
                                color = MikaColors.TerminalGreen,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 9.5.sp,
                                lineHeight = 13.sp,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    } else {
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
                                        text = "PAGE ${pgIdx + 1} • PANEL ${panel.panelIndex + 1} • NEEDS SPEAKER",
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
                                            text = "// Vision Trace: ${panel.gemmaThoughtTrace}",
                                            color = MikaColors.NeonCyan.copy(alpha = 0.8f),
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 8.5.sp
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
                                        text = "TAP WHO IS SPEAKING:",
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
                }

                MangaPreProdTab.CAST -> {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "// Tap CLONE VOICE on any character (e.g. Vegeta) to jump straight into PURR Clone Studio, craft their clone, and auto-return here!",
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
                                        text = "PANEL ${panel.panelIndex + 1} // ${panel.sceneMood.ifBlank { "SCENE" }.uppercase()}",
                                        color = MikaColors.NeonCyan,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Black,
                                        fontSize = 9.5.sp,
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
        }

        // 3. Video Production & Power-User Export Deck
        MikaGlowPanel(accent = MikaColors.TerminalGreen) {
            Text(
                text = "> MOTION_COMIC_PRODUCTION // EXPORT_.MP4",
                color = MikaColors.TerminalGreen,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Black,
                fontSize = 11.sp
            )
            Spacer(modifier = Modifier.height(6.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                MangaVideoAspect.entries.forEach { asp ->
                    MikaTagPill(
                        label = asp.label,
                        selected = activeProject.videoAspect == asp,
                        activeColor = MikaColors.NeonCyan,
                        onClick = { viewModel.updateMangaProjectConfig(activeProject.id, videoAspect = asp) }
                    )
                }
            }

            SimplePurrToggle(
                title = "BURN-IN SUBTITLE HUD",
                subtitle = "Draws speaker badge & dialogue pill on rendered frames",
                checked = activeProject.burnInSubtitles,
                accent = MikaColors.NeonCyan,
                icon = CyberIconType.BOOK_NARRATOR,
                onToggle = { viewModel.updateMangaProjectConfig(activeProject.id, burnInSubtitles = it) }
            )
            SimplePurrToggle(
                title = "GACHA ATMOSPHERE PARTICLE FX",
                subtitle = "Adds animated battle embers, rain streaks, sakura petals or sparkles",
                checked = activeProject.enableAtmosphereFx,
                accent = MikaColors.SsrGold,
                icon = CyberIconType.BOLT_ACCEL,
                onToggle = { viewModel.updateMangaProjectConfig(activeProject.id, enableAtmosphereFx = it) }
            )

            ExpandableTerminalSection(
                title = "POWER_USER // VISION_ENDPOINT_&_RESCAN",
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
                    label = "RE-RUN VISION SCAN ON CHAPTER",
                    selected = true,
                    activeColor = MikaColors.NeonPurple,
                    onClick = {
                        if (!isBusy) viewModel.reanalyzeMangaChapter(activeProject.id, activeChapter.id)
                    }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { overallProgress.coerceIn(0f, 1f) },
                color = if (isBusy) MikaColors.TerminalGreen else MikaColors.NeonCyan,
                trackColor = MikaColors.DeepWellBg,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = statusLabel,
                color = MikaColors.CyanWhite,
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        if (isBusy) MikaColors.DangerRed.copy(alpha = 0.20f)
                        else MikaColors.TerminalGreen.copy(alpha = 0.22f)
                    )
                    .border(
                        1.5.dp,
                        if (isBusy) MikaColors.DangerRed else MikaColors.TerminalGreen,
                        RoundedCornerShape(6.dp)
                    )
                    .clickable {
                        if (isBusy) viewModel.cancelMangaWork()
                        else viewModel.produceMangaChapterMp4(activeProject.id, activeChapter.id)
                    }
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                MikaCyberIcon(
                    type = if (isBusy) CyberIconType.STOP else CyberIconType.PLAY,
                    tint = if (isBusy) MikaColors.DangerRed else MikaColors.TerminalGreen,
                    size = 14.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isBusy) "CANCEL PRODUCTION" else "PRODUCE DUBBED EPISODE (.MP4)",
                    color = if (isBusy) MikaColors.DangerRed else MikaColors.TerminalGreen,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    fontSize = 11.sp
                )
            }

            if (activeChapter.status == MangaChapterStatus.COMPLETED && activeChapter.exportedMp4Path.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(MikaColors.SsrGold.copy(alpha = 0.14f))
                        .border(1.dp, MikaColors.SsrGold, RoundedCornerShape(6.dp))
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MikaCyberIcon(type = CyberIconType.CHECK, tint = MikaColors.SsrGold, size = 14.dp)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "DUBBED .MP4 READY (${"%.1f".format(activeChapter.durationSec)}s)",
                            color = MikaColors.SsrGold,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Black,
                            fontSize = 10.sp
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
                    text = "${character.lineCount} lines • ${character.assignedVoiceLabel}",
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
            val selectedVoiceDesc = availableVoices.firstOrNull { it.id == character.assignedVoiceId }
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
