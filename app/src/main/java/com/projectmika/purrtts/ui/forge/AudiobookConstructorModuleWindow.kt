package com.projectmika.purrtts.ui.forge

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
import com.projectmika.purrtts.core.audiobook.AudiobookChapter
import com.projectmika.purrtts.core.audiobook.AudiobookProject
import com.projectmika.purrtts.core.audiobook.ChapterRenderStatus
import com.projectmika.purrtts.core.purr.PurrNeuralBypass
import com.projectmika.purrtts.core.purr.PurrProfile
import com.projectmika.purrtts.engine.audiobook.AudiobookConstructorManager
import com.projectmika.purrtts.ui.theme.CyberDropdown
import com.projectmika.purrtts.ui.theme.CyberIconType
import com.projectmika.purrtts.ui.theme.ExpandableTerminalSection
import com.projectmika.purrtts.ui.theme.MikaColors
import com.projectmika.purrtts.ui.theme.MikaCyberIcon
import com.projectmika.purrtts.ui.theme.MikaGlowPanel
import com.projectmika.purrtts.ui.theme.MikaTagPill
import kotlin.math.roundToInt

/**
 * ✨ TOOL-07: AUDIOBOOK CONSTRUCTOR OS MODULE ✨
 * Imports EPUB / PDF / TXT / MD / HTML / DOCX / FB2 ebooks, splits chapters intelligently,
 * lets the user curate chapters, synthesizes each chapter with Pause / Halt & Resume-Later
 * disk checkpoints, and combines completed chapters into a standard chaptered `.m4b` audiobook.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AudiobookConstructorModuleWindowBody(
    activeProfile: PurrProfile,
    isPlayingPreview: Boolean,
    viewModel: TtsForgeViewModel
) {
    val projects by viewModel.audiobookProjects.collectAsState()
    val forgeState by viewModel.forgeState.collectAsState()
    val activeProjectId by viewModel.activeAudiobookId.collectAsState()
    val isBuilding by viewModel.isBuildingAudiobook.collectAsState()
    val isCombining by viewModel.isCombiningAudiobook.collectAsState()
    val statusLabel by viewModel.audiobookStatusLabel.collectAsState()
    val overallProgress by viewModel.audiobookProgress.collectAsState()

    val activeProject = remember(projects, activeProjectId) {
        projects.firstOrNull { it.id == activeProjectId } ?: projects.firstOrNull()
    }

    val ebookPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.importAudiobookDocument(it) }
    }

    var expandTuning by remember { mutableStateOf(false) }

    // 1. Header & Ebook Importer
    MikaGlowPanel(accent = MikaColors.NeonCyan) {
        Text(
            text = "> AUDIOBOOK_CONSTRUCTOR // EBOOK_TO_.M4B",
            color = MikaColors.NeonCyan,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Black,
            fontSize = 11.sp
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Import an EPUB, PDF, TXT, MD, HTML, DOCX, or FB2 ebook. Chapters split automatically so you can pick which sections to keep, pause & resume anytime across sessions, then combine finished chapters into a universal .M4B audiobook.",
            color = MikaColors.CyanWhite.copy(alpha = 0.78f),
            fontFamily = FontFamily.Monospace,
            fontSize = 9.5.sp,
            lineHeight = 13.5.sp
        )
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(MikaColors.NeonCyan.copy(alpha = 0.18f))
                .border(1.5.dp, MikaColors.NeonCyan, RoundedCornerShape(6.dp))
                .clickable(enabled = !isBuilding && !isCombining) {
                    ebookPicker.launch(
                        arrayOf(
                            "application/epub+zip",
                            "application/pdf",
                            "text/plain",
                            "text/markdown",
                            "text/html",
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                            "application/octet-stream",
                            "*/*"
                        )
                    )
                }
                .padding(vertical = 11.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            MikaCyberIcon(type = CyberIconType.BOOK_NARRATOR, tint = MikaColors.NeonCyan, size = 14.dp)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "+ IMPORT EBOOK (EPUB / PDF / TXT / MD / DOCX / FB2)",
                color = MikaColors.NeonCyan,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Black,
                fontSize = 9.5.sp
            )
        }

        Spacer(modifier = Modifier.height(6.dp))
        val neuralPure = PurrNeuralBypass.shouldBypassEnhancers(activeProfile, forgeState.clonedVoices)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            MikaTagPill(
                label = "VOICE: ${activeProfile.primaryVoiceId.substringAfter(':').take(18)}",
                selected = true,
                activeColor = MikaColors.TerminalGreen,
                onClick = {}
            )
            MikaTagPill(
                label = if (neuralPure) "NEURAL PURE: ON" else "PURR PIPELINE: ACTIVE",
                selected = true,
                activeColor = if (neuralPure) MikaColors.NeonCyan else MikaColors.NeonPink,
                onClick = {}
            )
            MikaTagPill(
                label = "OUTPUT: AAC .M4B CHAPTERS",
                selected = true,
                activeColor = MikaColors.SsrGold,
                onClick = {}
            )
        }
    }

    if (activeProject == null) {
        MikaGlowPanel(accent = MikaColors.DimText) {
            Text(
                text = "> NO_EBOOK_PROJECT_LOADED",
                color = MikaColors.WarmCream,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 10.5.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "// Tap + IMPORT EBOOK above to extract chapters cleanly on-device.",
                color = MikaColors.DimText,
                fontFamily = FontFamily.Monospace,
                fontSize = 9.5.sp
            )
        }
        return
    }

    // 2. Book Project Switcher & Metadata Summary
    MikaGlowPanel(accent = MikaColors.SsrGold) {
        if (projects.size > 1) {
            CyberDropdown(
                label = "> ACTIVE_BOOK_PROJECT (${projects.size})",
                options = projects,
                selectedOption = activeProject,
                optionLabel = { "${it.title} [${it.sourceFormat}]" },
                onSelect = { viewModel.selectAudiobookProject(it.id) },
                accent = MikaColors.SsrGold
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = activeProject.title,
                    color = MikaColors.PrimaryText,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${activeProject.author} • ${activeProject.sourceFormat} • ${activeProject.sourceFileName}",
                    color = MikaColors.SsrGold,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (!isBuilding && !isCombining) {
                MikaTagPill(
                    label = "DEL BOOK",
                    selected = true,
                    activeColor = MikaColors.DangerRed,
                    onClick = { viewModel.deleteAudiobookProject(activeProject.id) }
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        val includedChapters = activeProject.includedChapters
        val completedCount = includedChapters.count { it.status == ChapterRenderStatus.COMPLETED }
        val totalWords = includedChapters.sumOf { it.wordCount }
        val estMinutes = ((includedChapters.sumOf { it.estimatedDurationMs }) / 60_000L).coerceAtLeast(1L)

        Text(
            text = "SELECTED: ${includedChapters.size}/${activeProject.chapters.size} CH | DONE: $completedCount/${includedChapters.size} | WORDS: $totalWords (~${estMinutes}m est.)",
            color = MikaColors.CyanWhite.copy(alpha = 0.85f),
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp
        )

        Spacer(modifier = Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { overallProgress },
            color = if (activeProject.allIncludedCompleted) MikaColors.SsrGold else MikaColors.TerminalGreen,
            trackColor = MikaColors.TerminalGreen.copy(alpha = 0.16f),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = statusLabel,
            color = if (activeProject.allIncludedCompleted) MikaColors.SsrGold else MikaColors.NeonCyan,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 9.5.sp
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Start / Pause & Resume Control Button
        val hasProgress = completedCount > 0 || includedChapters.any { it.completedChunks > 0 }
        when {
            isBuilding -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(MikaColors.MikaAmber.copy(alpha = 0.22f))
                        .border(1.5.dp, MikaColors.MikaAmber, RoundedCornerShape(6.dp))
                        .clickable { viewModel.pauseAudiobookBuild() }
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MikaCyberIcon(type = CyberIconType.PAUSE, tint = MikaColors.MikaAmber, size = 14.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "PAUSE & SAVE PROGRESS (RESUME LATER)",
                        color = MikaColors.MikaAmber,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Black,
                        fontSize = 10.5.sp
                    )
                }
            }
            !activeProject.allIncludedCompleted -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(MikaColors.TerminalGreen.copy(alpha = 0.20f))
                        .border(1.5.dp, MikaColors.TerminalGreen, RoundedCornerShape(6.dp))
                        .clickable(enabled = !isCombining && includedChapters.isNotEmpty()) {
                            viewModel.startOrResumeAudiobookBuild(activeProject.id)
                        }
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MikaCyberIcon(type = CyberIconType.PLAY, tint = MikaColors.TerminalGreen, size = 14.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (hasProgress) "RESUME AUDIOBOOK CONSTRUCTION" else "START CONSTRUCTING CHAPTERS",
                        color = MikaColors.TerminalGreen,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Black,
                        fontSize = 10.5.sp
                    )
                }
            }
        }

        // Combine to Full .M4B Audiobook Button once chapters are complete
        if (activeProject.allIncludedCompleted) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(MikaColors.SsrGold.copy(alpha = if (isCombining) 0.12f else 0.24f))
                    .border(1.5.dp, MikaColors.SsrGold, RoundedCornerShape(6.dp))
                    .clickable(enabled = !isCombining && !isBuilding) {
                        viewModel.combineAudiobookToM4b(activeProject.id)
                    }
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                MikaCyberIcon(type = CyberIconType.DOWNLOAD, tint = MikaColors.SsrGold, size = 14.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isCombining) "COMBINING .M4B AUDIOBOOK..." else "COMBINE INTO FULL .M4B AUDIOBOOK",
                    color = MikaColors.SsrGold,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    fontSize = 10.5.sp
                )
            }
        } else if (completedCount > 0 && !isBuilding) {
            Text(
                text = "// ${includedChapters.size - completedCount} selected chapters remaining before full .M4B combine unlocks.",
                color = MikaColors.DimText,
                fontFamily = FontFamily.Monospace,
                fontSize = 8.5.sp,
                modifier = Modifier.padding(top = 6.dp)
            )
        }

        if (activeProject.combinedPublicPath.isNotBlank() || activeProject.combinedM4bPath.isNotBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "FULL CHAPTERED .M4B SAVED",
                        color = MikaColors.TerminalGreen,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Black,
                        fontSize = 10.sp
                    )
                    Text(
                        text = activeProject.combinedPublicPath.ifBlank { activeProject.combinedM4bPath },
                        color = MikaColors.CyanWhite,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 8.5.sp
                    )
                }
            }
        }
    }

    // 3. Optional Book Metadata & Chunking Tuning
    ExpandableTerminalSection(
        title = "ADVANCED: BOOK METADATA, CHUNK SIZE & GAPS",
        subtitle = "Chunk ${activeProject.targetChunkChars}c | Para ${activeProject.paragraphPauseMs}ms | Chapter gap ${activeProject.chapterGapMs}ms",
        expanded = expandTuning,
        onToggle = { expandTuning = !expandTuning },
        accent = MikaColors.NeonPurple
    ) {
        var editTitle by remember(activeProject.id) { mutableStateOf(activeProject.title) }
        var editAuthor by remember(activeProject.id) { mutableStateOf(activeProject.author) }

        OutlinedTextField(
            value = editTitle,
            onValueChange = { editTitle = it },
            label = { Text("AUDIOBOOK_TITLE_TAG", fontSize = 9.sp, fontFamily = FontFamily.Monospace) },
            singleLine = true,
            textStyle = TextStyle(color = MikaColors.PrimaryText, fontFamily = FontFamily.Monospace, fontSize = 11.sp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MikaColors.SsrGold,
                unfocusedBorderColor = MikaColors.SsrGold.copy(alpha = 0.35f),
                focusedContainerColor = MikaColors.DeepWellBg,
                unfocusedContainerColor = MikaColors.DeepWellBg
            ),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = editAuthor,
            onValueChange = { editAuthor = it },
            label = { Text("AUTHOR_TAG", fontSize = 9.sp, fontFamily = FontFamily.Monospace) },
            singleLine = true,
            textStyle = TextStyle(color = MikaColors.CyanWhite, fontFamily = FontFamily.Monospace, fontSize = 11.sp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MikaColors.NeonCyan,
                unfocusedBorderColor = MikaColors.NeonCyan.copy(alpha = 0.35f),
                focusedContainerColor = MikaColors.DeepWellBg,
                unfocusedContainerColor = MikaColors.DeepWellBg
            ),
            modifier = Modifier.fillMaxWidth()
        )
        MikaTagPill(
            label = "APPLY BOOK TAGS",
            selected = true,
            activeColor = MikaColors.NeonCyan,
            onClick = {
                viewModel.updateAudiobookProjectOptions(
                    projectId = activeProject.id,
                    title = editTitle.trim().ifEmpty { activeProject.title },
                    author = editAuthor.trim().ifEmpty { "Unknown Author" }
                )
                viewModel.showToast("Updated M4B book metadata")
            }
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(180 to "SHORT (~180c)", 260 to "BOOK (~260c)", 360 to "LONG (~360c)").forEach { (chars, label) ->
                MikaTagPill(
                    label = label,
                    selected = activeProject.targetChunkChars == chars,
                    activeColor = MikaColors.NeonCyan,
                    onClick = { viewModel.updateAudiobookProjectOptions(activeProject.id, targetChunkChars = chars) }
                )
            }
        }
        TerminalSlider(
            label = "PARAGRAPH_PAUSE_MS",
            valueText = "${activeProject.paragraphPauseMs} ms",
            value = activeProject.paragraphPauseMs.toFloat(),
            valueRange = 200f..1200f,
            accent = MikaColors.MikaAmber,
            onValueChange = { v ->
                viewModel.updateAudiobookProjectOptions(activeProject.id, paragraphPauseMs = (v.roundToInt() / 50) * 50)
            }
        )
        TerminalSlider(
            label = "CHAPTER_GAP_MS",
            valueText = "${activeProject.chapterGapMs} ms",
            value = activeProject.chapterGapMs.toFloat(),
            valueRange = 400f..3000f,
            accent = MikaColors.SsrGold,
            onValueChange = { v ->
                viewModel.updateAudiobookProjectOptions(activeProject.id, chapterGapMs = (v.roundToInt() / 100) * 100)
            }
        )
        SimplePurrToggle(
            title = "Cosine Crossfade Across Chunks",
            subtitle = "18ms raised-cosine boundary blending per chapter.",
            checked = activeProject.smoothTransitions,
            accent = MikaColors.TerminalGreen,
            icon = CyberIconType.CROSSFADE_LINK,
            onToggle = { viewModel.updateAudiobookProjectOptions(activeProject.id, smoothTransitions = it) }
        )
        SimplePurrToggle(
            title = "Auto-Level Sentence Loudness",
            subtitle = "Consistent -18 dBFS RMS loudness across chapters.",
            checked = activeProject.normalizeLoudness,
            accent = MikaColors.NeonCyan,
            icon = CyberIconType.SLIDER_FADER,
            onToggle = { viewModel.updateAudiobookProjectOptions(activeProject.id, normalizeLoudness = it) }
        )
    }

    // 4. Interactive Chapter Selector & Individual Chapter Status Deck
    MikaGlowPanel(accent = MikaColors.TerminalGreen) {
        Text(
            text = "> BOOK_CHAPTERS (${activeProject.chapters.size})",
            color = MikaColors.TerminalGreen,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Black,
            fontSize = 10.5.sp
        )
        Spacer(modifier = Modifier.height(6.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            MikaTagPill(
                label = "SELECT ALL",
                selected = false,
                activeColor = MikaColors.TerminalGreen,
                onClick = {
                    if (!isBuilding) viewModel.applyAudiobookChapterFilter(activeProject.id, AudiobookConstructorManager.ChapterFilterMode.ALL)
                }
            )
            MikaTagPill(
                label = "STORY ONLY",
                selected = false,
                activeColor = MikaColors.NeonCyan,
                onClick = {
                    if (!isBuilding) viewModel.applyAudiobookChapterFilter(activeProject.id, AudiobookConstructorManager.ChapterFilterMode.STORY_ONLY)
                }
            )
            MikaTagPill(
                label = "NONE",
                selected = false,
                activeColor = MikaColors.DangerRed,
                onClick = {
                    if (!isBuilding) viewModel.applyAudiobookChapterFilter(activeProject.id, AudiobookConstructorManager.ChapterFilterMode.NONE)
                }
            )
        }
        Spacer(modifier = Modifier.height(8.dp))

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            activeProject.chapters.forEach { chapter ->
                ChapterItemCard(
                    project = activeProject,
                    chapter = chapter,
                    isBuilding = isBuilding,
                    isPlayingPreview = isPlayingPreview,
                    viewModel = viewModel
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChapterItemCard(
    project: AudiobookProject,
    chapter: AudiobookChapter,
    isBuilding: Boolean,
    isPlayingPreview: Boolean,
    viewModel: TtsForgeViewModel
) {
    var expandedPreview by remember(project.id, chapter.index) { mutableStateOf(false) }
    var editingTitle by remember(chapter.title) { mutableStateOf(chapter.title) }

    val statusColor = when {
        !chapter.included -> MikaColors.DimText
        chapter.status == ChapterRenderStatus.COMPLETED -> MikaColors.TerminalGreen
        chapter.status == ChapterRenderStatus.IN_PROGRESS -> MikaColors.NeonCyan
        chapter.status == ChapterRenderStatus.FAILED -> MikaColors.DangerRed
        chapter.completedChunks > 0 -> MikaColors.MikaAmber
        else -> MikaColors.WarmCream
    }

    val statusTag = when {
        !chapter.included -> "SKIP"
        chapter.status == ChapterRenderStatus.COMPLETED -> "DONE"
        chapter.status == ChapterRenderStatus.IN_PROGRESS -> "${chapter.completedChunks}/${chapter.totalChunks.coerceAtLeast(1)}"
        chapter.status == ChapterRenderStatus.FAILED -> "FAILED"
        chapter.completedChunks > 0 -> "PAUSED ${chapter.completedChunks}/${chapter.totalChunks.coerceAtLeast(1)}"
        else -> "QUEUED"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(MikaColors.DeepWellBg)
            .border(1.dp, statusColor.copy(alpha = if (chapter.included) 0.45f else 0.18f), RoundedCornerShape(6.dp))
            .padding(9.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Checkbox toggle
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(20.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (chapter.included) statusColor.copy(alpha = 0.22f) else Color.Transparent)
                    .border(1.2.dp, statusColor, RoundedCornerShape(4.dp))
                    .clickable(enabled = !isBuilding) {
                        viewModel.toggleAudiobookChapter(project.id, chapter.index, !chapter.included)
                    }
            ) {
                if (chapter.included) {
                    MikaCyberIcon(type = CyberIconType.CHECK, tint = statusColor, size = 12.dp)
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable { expandedPreview = !expandedPreview }
            ) {
                Text(
                    text = "${chapter.index + 1}. ${chapter.title}",
                    color = if (chapter.included) MikaColors.PrimaryText else MikaColors.DimText,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val durStr = if (chapter.renderedDurationMs > 0L) {
                    "${chapter.renderedDurationMs / 1000L}s audio"
                } else {
                    "~${(chapter.estimatedDurationMs / 1000L).coerceAtLeast(1L)}s est."
                }
                Text(
                    text = "${chapter.wordCount} words • $durStr",
                    color = MikaColors.DimText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 8.5.sp
                )
            }

            Text(
                text = statusTag,
                color = statusColor,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Black,
                fontSize = 8.5.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(3.dp))
                    .background(statusColor.copy(alpha = 0.14f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }

        if (!chapter.errorMessage.isNullOrBlank()) {
            Text(
                text = "! ${chapter.errorMessage}",
                color = MikaColors.DangerRed,
                fontFamily = FontFamily.Monospace,
                fontSize = 8.5.sp
            )
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            MikaTagPill(
                label = if (expandedPreview) "HIDE PREVIEW" else "PREVIEW",
                selected = expandedPreview,
                activeColor = MikaColors.NeonCyan,
                onClick = { expandedPreview = !expandedPreview }
            )
            if (chapter.status == ChapterRenderStatus.COMPLETED) {
                MikaTagPill(
                    label = if (isPlayingPreview) "STOP" else "PLAY",
                    selected = true,
                    activeColor = MikaColors.TerminalGreen,
                    leadingIcon = if (isPlayingPreview) CyberIconType.STOP else CyberIconType.PLAY,
                    onClick = {
                        if (isPlayingPreview) viewModel.stopAudition()
                        else viewModel.playCompletedChapterWav(chapter)
                    }
                )
                MikaTagPill(
                    label = "SAVE WAV",
                    selected = true,
                    activeColor = MikaColors.SsrGold,
                    leadingIcon = CyberIconType.DOWNLOAD,
                    onClick = { viewModel.exportAudiobookChapterWav(project.id, chapter.index) }
                )
            }
            if ((chapter.status == ChapterRenderStatus.FAILED || chapter.status == ChapterRenderStatus.COMPLETED) && !isBuilding) {
                MikaTagPill(
                    label = if (chapter.status == ChapterRenderStatus.FAILED) "RETRY" else "RE-RENDER",
                    selected = true,
                    activeColor = MikaColors.MikaAmber,
                    leadingIcon = CyberIconType.REFRESH,
                    onClick = { viewModel.retryAudiobookChapter(project.id, chapter.index) }
                )
            }
        }

        AnimatedVisibility(visible = expandedPreview) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = editingTitle,
                        onValueChange = { editingTitle = it },
                        label = { Text("RENAME_CHAPTER", fontSize = 8.5.sp, fontFamily = FontFamily.Monospace) },
                        singleLine = true,
                        textStyle = TextStyle(color = MikaColors.PrimaryText, fontFamily = FontFamily.Monospace, fontSize = 10.sp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MikaColors.NeonCyan,
                            unfocusedBorderColor = MikaColors.NeonCyan.copy(alpha = 0.35f),
                            focusedContainerColor = MikaColors.PanelBg,
                            unfocusedContainerColor = MikaColors.PanelBg
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    MikaTagPill(
                        label = "SET",
                        selected = true,
                        activeColor = MikaColors.TerminalGreen,
                        onClick = { viewModel.renameAudiobookChapter(project.id, chapter.index, editingTitle) }
                    )
                }
                Text(
                    text = chapter.text.take(480) + if (chapter.text.length > 480) "..." else "",
                    color = MikaColors.CyanWhite.copy(alpha = 0.78f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    lineHeight = 13.sp
                )
            }
        }
    }
}
