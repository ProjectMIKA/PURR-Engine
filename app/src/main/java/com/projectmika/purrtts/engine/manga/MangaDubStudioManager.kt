package com.projectmika.purrtts.engine.manga

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.provider.OpenableColumns
import com.projectmika.purrtts.core.manga.MangaAtmospherePreset
import com.projectmika.purrtts.core.manga.MangaBfsPanelSlicer
import com.projectmika.purrtts.core.manga.MangaChapterEntry
import com.projectmika.purrtts.core.manga.MangaChapterStatus
import com.projectmika.purrtts.core.manga.MangaCharacterProfile
import com.projectmika.purrtts.core.manga.MangaEmotionalPacer
import com.projectmika.purrtts.core.manga.MangaNormalizedRect
import com.projectmika.purrtts.core.manga.MangaPageEntry
import com.projectmika.purrtts.core.manga.MangaPanelNode
import com.projectmika.purrtts.core.manga.MangaReadingOrder
import com.projectmika.purrtts.core.manga.MangaSeriesProject
import com.projectmika.purrtts.core.manga.MangaSpeechLine
import com.projectmika.purrtts.core.manga.MangaTextBender
import com.projectmika.purrtts.core.manga.MangaVideoAspect
import com.projectmika.purrtts.core.purr.BackendType
import com.projectmika.purrtts.core.purr.DownloadState
import com.projectmika.purrtts.core.purr.PurrChunk
import com.projectmika.purrtts.core.purr.PurrDspProcessor
import com.projectmika.purrtts.core.purr.PurrProfile
import com.projectmika.purrtts.core.purr.PurrProfileRepository
import com.projectmika.purrtts.core.purr.PurrVoiceMatcher
import com.projectmika.purrtts.core.purr.SegmentRole
import com.projectmika.purrtts.core.purr.SentimentResult
import com.projectmika.purrtts.core.purr.VoiceDescriptor
import com.projectmika.purrtts.core.purr.VoiceGender
import com.projectmika.purrtts.engine.TtsBackendRouter
import com.projectmika.purrtts.engine.qwen3.Qwen3TtsEngine
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * ✨ MIKA'S MANGA DUB STUDIO MANAGER (TOOL-11) ✨
 *
 * Orchestrates persistent multi-chapter `.purrmanga` series projects, `.cbz` image extraction,
 * GachaSwipe BFS panel slicing, Gemma 4 E2B LiteRT Vision + Thinking attribution (with strict
 * sequential VRAM eviction before TTS starts), Pre-Production decision resolution + Clone Studio
 * voice binding, Qwen VoiceDesign / installed voice synthesis, and hardware `.MP4` export.
 */
class MangaDubStudioManager(
    private val context: Context,
    private val repository: PurrProfileRepository,
    private val backendRouter: TtsBackendRouter
) {
    private val projectsRoot = File(context.filesDir, "manga_projects").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val visionEngine = LiteRtGemmaVisionEngine(context)

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _projects = MutableStateFlow<List<MangaSeriesProject>>(emptyList())
    val projects: StateFlow<List<MangaSeriesProject>> = _projects.asStateFlow()

    private val _activeProjectId = MutableStateFlow<String?>(null)
    val activeProjectId: StateFlow<String?> = _activeProjectId.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _statusLabel = MutableStateFlow("IDLE // IMPORT A .CBZ OR LOAD DEMO CHAPTER")
    val statusLabel: StateFlow<String> = _statusLabel.asStateFlow()

    private val _overallProgress = MutableStateFlow(0f)
    val overallProgress: StateFlow<Float> = _overallProgress.asStateFlow()

    private var activeJob: Job? = null

    init {
        reloadAllProjects()
    }

    fun reloadAllProjects() {
        val loaded = projectsRoot.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { dir ->
                val f = File(dir, "project.json")
                if (!f.exists()) return@mapNotNull null
                runCatching {
                    val decoded = json.decodeFromString<MangaSeriesProject>(f.readText())
                    val healedChapters = decoded.chapters.map { ch ->
                        if (ch.status in setOf(
                                MangaChapterStatus.ANALYZING_VISION,
                                MangaChapterStatus.SYNTHESIZING_AUDIO,
                                MangaChapterStatus.RENDERING_VIDEO
                            )
                        ) {
                            ch.copy(status = MangaChapterStatus.PRE_PRODUCTION_READY)
                        } else {
                            ch
                        }
                    }
                    if (healedChapters != decoded.chapters) {
                        val updated = decoded.copy(chapters = healedChapters)
                        persistProject(updated)
                        updated
                    } else {
                        decoded
                    }
                }.getOrNull()
            }
            ?.sortedByDescending { it.updatedAtEpochMs }
            .orEmpty()

        _projects.value = loaded
        if (_activeProjectId.value == null || loaded.none { it.id == _activeProjectId.value }) {
            _activeProjectId.value = loaded.firstOrNull()?.id
        }
    }

    fun selectProject(projectId: String) {
        _activeProjectId.value = projectId
    }

    fun deleteProject(projectId: String) {
        if (_isBusy.value && _activeProjectId.value == projectId) {
            cancelActiveWork()
        }
        runCatching { projectDir(projectId).deleteRecursively() }
        val remaining = _projects.value.filterNot { it.id == projectId }
        _projects.value = remaining
        if (_activeProjectId.value == projectId) {
            _activeProjectId.value = remaining.firstOrNull()?.id
        }
    }

    fun cancelActiveWork() {
        activeJob?.cancel()
        activeJob = null
        scope.launch {
            runCatching { visionEngine.unload() }
            runCatching { backendRouter.evictAllVram() }
        }
        _isBusy.value = false
        _overallProgress.value = 0f
        _statusLabel.value = "CANCELLED // VRAM CLEARED"
    }

    fun importCbzChapter(
        uri: Uri,
        targetProjectId: String?,
        seriesTitleInput: String,
        readingOrder: MangaReadingOrder,
        onComplete: (String) -> Unit
    ) {
        if (_isBusy.value) {
            onComplete("Manga Studio task already running!")
            return
        }
        activeJob = scope.launch {
            _isBusy.value = true
            _overallProgress.value = 0.04f
            try {
                val rawName = resolveFileName(uri)
                val chapterTitle = rawName.substringBeforeLast('.').ifBlank { "Chapter 1" }
                val existingProject = targetProjectId?.let { id -> _projects.value.firstOrNull { it.id == id } }
                val now = System.currentTimeMillis()
                val projectId = existingProject?.id ?: "manga_${sanitizeSlug(seriesTitleInput.ifBlank { chapterTitle })}_$now"
                val chapterId = "ch_${sanitizeSlug(chapterTitle)}_$now"
                val chapterPagesDir = File(projectDir(projectId), "chapters/$chapterId/pages").apply { mkdirs() }

                _statusLabel.value = "UNPACKING .CBZ ARCHIVE ($rawName)..."
                val extractedFiles = extractCbzImages(uri, chapterPagesDir)
                if (extractedFiles.isEmpty()) {
                    throw IllegalStateException("No readable manga image pages (.jpg/.png/.webp) found in $rawName")
                }

                val initialProj = existingProject?.copy(updatedAtEpochMs = now, readingOrder = readingOrder)
                    ?: MangaSeriesProject(
                        id = projectId,
                        seriesTitle = seriesTitleInput.trim().ifEmpty { chapterTitle },
                        createdAtEpochMs = now,
                        updatedAtEpochMs = now,
                        readingOrder = readingOrder
                    )

                _statusLabel.value = "SLICING PANELS (${extractedFiles.size} PAGES)..."
                val rawPages = extractedFiles.mapIndexedNotNull { pageIdx, imgFile ->
                    _overallProgress.value = 0.08f + 0.18f * ((pageIdx + 1).toFloat() / extractedFiles.size.toFloat())
                    sliceSinglePageFromDisk(pageIdx, imgFile, readingOrder)
                }
                if (rawPages.isEmpty()) {
                    throw IllegalStateException("Failed to decode extracted manga images")
                }

                val importedChapter = MangaChapterEntry(
                    id = chapterId,
                    title = chapterTitle,
                    sourceCbzName = rawName,
                    readingOrder = readingOrder,
                    status = MangaChapterStatus.ANALYZING_VISION,
                    pages = rawPages
                )
                val projWithChapter = initialProj.copy(
                    chapters = initialProj.chapters + importedChapter
                )
                persistAndPublish(projWithChapter)
                _activeProjectId.value = projWithChapter.id

                val finalProj = runVisionPhaseOnChapter(projWithChapter.id, chapterId)
                val ch = finalProj.chapters.firstOrNull { it.id == chapterId }
                val pending = ch?.pendingDecisionCount ?: 0
                val summaryMsg = "Scanned ${rawPages.size} pages, ${ch?.totalPanelCount ?: 0} panels ($pending decision(s) flagged)"
                _statusLabel.value = "PRE-PRODUCTION READY // $summaryMsg"
                onComplete(summaryMsg)
            } catch (ce: CancellationException) {
                throw ce
            } catch (err: Exception) {
                _statusLabel.value = "IMPORT ERROR: ${err.message}"
                onComplete("CBZ import failed: ${err.message}")
            } finally {
                runCatching { visionEngine.unload() }
                _isBusy.value = false
            }
        }
    }

    /**
     * Procedurally draws a 2-page, 4-panel illustrated cyber-manga chapter onto real PNG files
     * so users can test BFS panel slicing, Pre-Production decisions, Vegeta Clone Studio round-trip,
     * and full hardware .MP4 export immediately without needing an external CBZ handy.
     */
    fun createSampleMangaProject(onComplete: (String) -> Unit) {
        if (_isBusy.value) return
        activeJob = scope.launch {
            _isBusy.value = true
            _overallProgress.value = 0.1f
            try {
                _statusLabel.value = "GENERATING SAMPLE CYBER-MANGA CHAPTER..."
                val now = System.currentTimeMillis()
                val projectId = "manga_dragon_cyber_demo_$now"
                val chapterId = "ch_01_saiyan_signal"
                val pagesDir = File(projectDir(projectId), "chapters/$chapterId/pages").apply { mkdirs() }
                val charsDir = File(projectDir(projectId), "characters").apply { mkdirs() }

                val page1File = File(pagesDir, "page_001.png")
                val page2File = File(pagesDir, "page_002.png")
                renderSyntheticMangaPage(page1File, pageNum = 1)
                renderSyntheticMangaPage(page2File, pageNum = 2)

                val p1 = sliceSinglePageFromDisk(0, page1File, MangaReadingOrder.RTL_MANGA)!!
                val p2 = sliceSinglePageFromDisk(1, page2File, MangaReadingOrder.RTL_MANGA)!!

                // Crop real character portraits directly from the generated pages
                val vegetaCrop = File(charsDir, "char_vegeta_x.jpg")
                val kaelCrop = File(charsDir, "char_kael.jpg")
                cropPortraitFromPage(page1File, MangaNormalizedRect(0.55f, 0.08f, 0.90f, 0.44f), vegetaCrop)
                cropPortraitFromPage(page1File, MangaNormalizedRect(0.10f, 0.54f, 0.45f, 0.90f), kaelCrop)

                val characters = listOf(
                    MangaCharacterProfile(
                        id = "char_narrator",
                        name = "NARRATOR",
                        gender = VoiceGender.ANDROGYNOUS,
                        visualDescription = "Cinematic anime episode narrator",
                        voiceDesignPrompt = "Commanding theatrical narrator voice, crisp articulation, dramatic buildup",
                        assignedVoiceLabel = "AUTO (Smart Match / VoiceDesign)",
                        lineCount = 1
                    ),
                    MangaCharacterProfile(
                        id = "char_vegeta_x",
                        name = "VEGETA_X",
                        gender = VoiceGender.MALE,
                        visualDescription = "Spiky-haired Saiyan warrior in cyber battle armor",
                        avatarCropPath = vegetaCrop.absolutePath,
                        voiceDesignPrompt = "Fierce raspy proud warrior prince, medium-low gritty baritone, intense snarl",
                        assignedVoiceLabel = "AUTO (Tap Clone Voice for Vegeta!)",
                        lineCount = 2
                    ),
                    MangaCharacterProfile(
                        id = "char_kael",
                        name = "KAEL",
                        gender = VoiceGender.MALE,
                        visualDescription = "Cyber-monk rival holding glowing scanner visor",
                        avatarCropPath = kaelCrop.absolutePath,
                        voiceDesignPrompt = "Sharp calm tactician, youthful tenor, composed yet urgent",
                        assignedVoiceLabel = "AUTO (Smart Match / VoiceDesign)",
                        lineCount = 1
                    )
                )

                val page1Panels = p1.panels.mapIndexed { idx, panel ->
                    val line = if (idx == 0) {
                        MangaSpeechLine(
                            id = "l_demo_1",
                            bubbleIndex = 1,
                            speakerId = "char_narrator",
                            speakerName = "NARRATOR",
                            rawText = "SECTOR SEVEN. ORBITAL RING COLLAPSE IMMINENT!",
                            sanitizedText = MangaTextBender.sanitizeMangaDialogue("SECTOR SEVEN. ORBITAL RING COLLAPSE IMMINENT!"),
                            emotionTag = "dramatic",
                            confidence = 0.98f,
                            postPauseMs = 450
                        )
                    } else {
                        MangaSpeechLine(
                            id = "l_demo_2",
                            bubbleIndex = 1,
                            speakerId = "char_vegeta_x",
                            speakerName = "VEGETA_X",
                            rawText = "IMPOSSIBLE! MY SCANNER SAYS HIS POWER LEVEL BROKE NINE THOUSAND!!",
                            sanitizedText = MangaTextBender.sanitizeMangaDialogue("IMPOSSIBLE! MY SCANNER SAYS HIS POWER LEVEL BROKE NINE THOUSAND!!"),
                            emotionTag = "anger shout battle",
                            confidence = 0.96f,
                            postPauseMs = 400
                        )
                    }
                    panel.copy(
                        sceneMood = if (idx == 1) "intense battle aura flame" else "dramatic skyline",
                        gemmaThoughtTrace = if (idx == 0) {
                            "Narration box at top-right sets Sector 7 scene."
                        } else {
                            "Jagged scream bubble points directly to spiky-haired warrior Vegeta X smashing scouter."
                        },
                        lines = listOf(line)
                    )
                }

                val page2Panels = p2.panels.mapIndexed { idx, panel ->
                    val line = if (idx == 0) {
                        MangaSpeechLine(
                            id = "l_demo_3",
                            bubbleIndex = 1,
                            speakerId = "char_kael",
                            speakerName = "KAEL",
                            rawText = "Wait... you actually dropped your guard for a second, prince.",
                            sanitizedText = MangaTextBender.sanitizeMangaDialogue("Wait... you actually dropped your guard for a second, prince."),
                            emotionTag = "calm",
                            confidence = 0.93f,
                            postPauseMs = 500
                        )
                    } else {
                        // One realistic ambiguous floating bubble to showcase Pre-Production "Decisions Needed"
                        MangaSpeechLine(
                            id = "l_demo_4",
                            bubbleIndex = 1,
                            speakerId = "UNKNOWN",
                            speakerName = "UNKNOWN",
                            rawText = "PREPARE FOR FINAL FLASH PROTOCOL!!",
                            sanitizedText = MangaTextBender.sanitizeMangaDialogue("PREPARE FOR FINAL FLASH PROTOCOL!!"),
                            emotionTag = "anger shout battle",
                            confidence = 0.48f,
                            needsUserDecision = true,
                            decisionReason = "Tail-less action bubble across energy beam — confirm whether VEGETA_X or KAEL speaks.",
                            candidateSpeakers = listOf("VEGETA_X", "KAEL", "NARRATOR"),
                            postPauseMs = 400
                        )
                    }
                    panel.copy(
                        sceneMood = if (idx == 1) "battle explosion cyber sparkle" else "tense standoff",
                        gemmaThoughtTrace = if (idx == 0) {
                            "Clean tail points to Kael adjusting cyber visor."
                        } else {
                            "Floating shout bubble sits over beam clash with no visible mouth -> flagged for user cast check."
                        },
                        lines = listOf(line)
                    )
                }

                val chapter = MangaChapterEntry(
                    id = chapterId,
                    title = "Ch.01: Saiyan Cyber Signal",
                    sourceCbzName = "demo_cyber_manga_ch01.cbz",
                    readingOrder = MangaReadingOrder.RTL_MANGA,
                    status = MangaChapterStatus.PRE_PRODUCTION_READY,
                    pages = listOf(
                        p1.copy(panels = page1Panels),
                        p2.copy(panels = page2Panels)
                    )
                )

                val project = MangaSeriesProject(
                    id = projectId,
                    seriesTitle = "DRAGON CYBER (DEMO)",
                    createdAtEpochMs = now,
                    updatedAtEpochMs = now,
                    readingOrder = MangaReadingOrder.RTL_MANGA,
                    storyContextSummary = "Vegeta_X and Kael confront each other above collapsing Sector 7.",
                    characters = characters,
                    chapters = listOf(chapter)
                )
                persistAndPublish(project)
                _activeProjectId.value = project.id
                _overallProgress.value = 1f
                _statusLabel.value = "DEMO READY // TRY VEGETA CLONE OR RESOLVE 1 DECISION"
                onComplete("Loaded demo manga chapter with Vegeta_X & Kael! Check Pre-Production Decisions & Cast Roster.")
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun reanalyzeChapterWithVision(projectId: String, chapterId: String, onComplete: (String) -> Unit) {
        if (_isBusy.value) return
        activeJob = scope.launch {
            _isBusy.value = true
            try {
                val updated = runVisionPhaseOnChapter(projectId, chapterId)
                val ch = updated.chapters.firstOrNull { it.id == chapterId }
                val pending = ch?.pendingDecisionCount ?: 0
                _statusLabel.value = "VISION RESCAN COMPLETE ($pending DECISION(S))"
                onComplete("Re-scanned chapter (${ch?.totalLineCount ?: 0} lines, $pending decision(s))")
            } catch (ce: CancellationException) {
                throw ce
            } catch (err: Exception) {
                onComplete("Vision rescan failed: ${err.message}")
            } finally {
                runCatching { visionEngine.unload() }
                _isBusy.value = false
            }
        }
    }

    private suspend fun runVisionPhaseOnChapter(projectId: String, chapterId: String): MangaSeriesProject {
        var proj = _projects.value.firstOrNull { it.id == projectId }
            ?: throw IllegalStateException("Project not found")
        val ch = proj.chapters.firstOrNull { it.id == chapterId }
            ?: throw IllegalStateException("Chapter not found")

        val charsDir = File(projectDir(projectId), "characters").apply { mkdirs() }
        val roster = proj.characters.toMutableList()
        val recentLines = mutableListOf<String>()
        val updatedPages = ArrayList<MangaPageEntry>(ch.pages.size)

        try {
            for ((pageIdx, page) in ch.pages.withIndex()) {
                _overallProgress.value = 0.25f + 0.70f * ((pageIdx + 1).toFloat() / ch.pages.size.coerceAtLeast(1).toFloat())
                _statusLabel.value = "GEMMA 4 VISION SCAN PAGE ${pageIdx + 1}/${ch.pages.size}..."
                val bmp = BitmapFactory.decodeFile(page.localImagePath) ?: continue
                try {
                    val panelRects = page.panels.map { it.panelRect }
                    val (analysis, engineBadge) = visionEngine.analyzeMangaPage(
                        pageIndex = pageIdx,
                        pageBitmap = bmp,
                        panels = panelRects,
                        readingOrder = ch.readingOrder,
                        existingCharacters = roster,
                        recentDialogueContext = recentLines,
                        powerUserVisionUrl = proj.powerUserVisionUrl
                    )
                    _statusLabel.value = "PAGE ${pageIdx + 1}/${ch.pages.size} // $engineBadge"

                    val panelByIdx = analysis.panels.associateBy { it.panelIndex }
                    val nextPanels = page.panels.mapIndexed { pIdx, existingPanel ->
                        val spec = panelByIdx[pIdx]
                        if (spec == null) {
                            existingPanel
                        } else {
                            val builtLines = spec.lines.mapIndexed { lIdx, ls ->
                                val speakerKey = ls.speakerName.trim().ifEmpty { "UNKNOWN" }
                                var matchedChar = roster.firstOrNull {
                                    it.name.equals(speakerKey, ignoreCase = true)
                                }
                                if (matchedChar == null && !speakerKey.equals("UNKNOWN", ignoreCase = true)) {
                                    val newCharId = "char_${sanitizeSlug(speakerKey)}_${roster.size + 1}"
                                    val avatarFile = File(charsDir, "$newCharId.jpg")
                                    cropPortraitFromPage(File(page.localImagePath), existingPanel.panelRect, avatarFile)
                                    val newChar = MangaCharacterProfile(
                                        id = newCharId,
                                        name = speakerKey.uppercase(),
                                        gender = ls.gender,
                                        visualDescription = ls.visualDescription.ifBlank { "Discovered on page ${pageIdx + 1}" },
                                        avatarCropPath = avatarFile.absolutePath,
                                        voiceDesignPrompt = ls.voiceDesignPrompt.ifBlank {
                                            "Expressive ${ls.gender.tag} anime character voice, clear dialogue pacing"
                                        }
                                    )
                                    roster += newChar
                                    matchedChar = newChar
                                }
                                val cleanText = MangaTextBender.sanitizeMangaDialogue(ls.rawText)
                                if (cleanText.isNotEmpty() && !ls.needsUserDecision) {
                                    recentLines += "$speakerKey: $cleanText"
                                }
                                MangaSpeechLine(
                                    id = "ln_${pageIdx}_${pIdx}_$lIdx",
                                    bubbleIndex = ls.bubbleIndex,
                                    speakerId = matchedChar?.id ?: "UNKNOWN",
                                    speakerName = matchedChar?.name ?: "UNKNOWN",
                                    rawText = ls.rawText,
                                    sanitizedText = cleanText,
                                    emotionTag = ls.emotion,
                                    confidence = ls.confidence,
                                    needsUserDecision = ls.needsUserDecision,
                                    decisionReason = ls.decisionReason,
                                    candidateSpeakers = ls.candidateSpeakers,
                                    postPauseMs = MangaEmotionalPacer.computePostLinePauseMs(cleanText, ls.emotion)
                                )
                            }
                            existingPanel.copy(
                                sceneMood = spec.sceneMood.ifBlank { existingPanel.sceneMood },
                                gemmaThoughtTrace = spec.thoughtTrace.ifBlank { analysis.thoughtTrace },
                                lines = builtLines
                            )
                        }
                    }
                    updatedPages += page.copy(panels = nextPanels)
                    if (analysis.storySummary.isNotBlank()) {
                        proj = proj.copy(storyContextSummary = analysis.storySummary)
                    }
                } finally {
                    bmp.recycle()
                }
            }
        } finally {
            // Strict LMKD protection: release Gemma 4 LiteRT weights immediately after Vision pass
            visionEngine.unload()
        }

        val recomputedRoster = recomputeCharacterLineCounts(roster, ch.copy(pages = updatedPages), proj.chapters.filter { it.id != chapterId })
        val nextChapter = ch.copy(
            status = MangaChapterStatus.PRE_PRODUCTION_READY,
            pages = updatedPages,
            errorMessage = null
        )
        val finalProj = proj.copy(
            updatedAtEpochMs = System.currentTimeMillis(),
            characters = recomputedRoster,
            chapters = proj.chapters.map { if (it.id == chapterId) nextChapter else it }
        )
        persistAndPublish(finalProj)
        return finalProj
    }

    // =========================================================================
    // ✨ PRE-PRODUCTION INTERACTIVE MUTATIONS ✨
    // =========================================================================

    fun resolvePanelDecision(
        projectId: String,
        chapterId: String,
        pageIndex: Int,
        panelId: String,
        lineId: String,
        characterId: String
    ) {
        mutateProject(projectId) { proj ->
            val charProfile = proj.characters.firstOrNull { it.id == characterId } ?: return@mutateProject proj
            val nextChapters = proj.chapters.map { ch ->
                if (ch.id != chapterId) ch
                else ch.copy(
                    pages = ch.pages.map { pg ->
                        if (pg.pageIndex != pageIndex) pg
                        else pg.copy(
                            panels = pg.panels.map { pan ->
                                if (pan.id != panelId) pan
                                else pan.copy(
                                    lines = pan.lines.map { ln ->
                                        if (ln.id != lineId) ln
                                        else ln.copy(
                                            speakerId = charProfile.id,
                                            speakerName = charProfile.name,
                                            confidence = 1.0f,
                                            needsUserDecision = false,
                                            decisionReason = ""
                                        )
                                    }
                                )
                            }
                        )
                    }
                )
            }
            val nextCh = nextChapters.firstOrNull { it.id == chapterId }
            val syncedRoster = recomputeCharacterLineCounts(proj.characters, nextCh, nextChapters.filter { it.id != chapterId })
            proj.copy(updatedAtEpochMs = System.currentTimeMillis(), characters = syncedRoster, chapters = nextChapters)
        }
    }

    fun assignVoiceToCharacter(
        projectId: String,
        characterId: String,
        voiceId: String,
        voiceLabel: String,
        voiceDesignPrompt: String? = null,
        speedScale: Float? = null,
        pitchScale: Float? = null
    ) {
        mutateProject(projectId) { proj ->
            proj.copy(
                updatedAtEpochMs = System.currentTimeMillis(),
                characters = proj.characters.map { ch ->
                    if (ch.id != characterId) ch
                    else ch.copy(
                        assignedVoiceId = voiceId,
                        assignedVoiceLabel = voiceLabel,
                        voiceDesignPrompt = voiceDesignPrompt ?: ch.voiceDesignPrompt,
                        speedScale = speedScale ?: ch.speedScale,
                        pitchScale = pitchScale ?: ch.pitchScale
                    )
                }
            )
        }
    }

    fun addCustomCharacter(
        projectId: String,
        name: String,
        gender: VoiceGender,
        voiceDesignPrompt: String
    ) {
        val cleanName = name.trim().uppercase().ifEmpty { return }
        mutateProject(projectId) { proj ->
            if (proj.characters.any { it.name.equals(cleanName, ignoreCase = true) }) return@mutateProject proj
            val next = MangaCharacterProfile(
                id = "char_${sanitizeSlug(cleanName)}_${System.currentTimeMillis() % 10000}",
                name = cleanName,
                gender = gender,
                visualDescription = "Custom series cast member",
                voiceDesignPrompt = voiceDesignPrompt.trim().ifBlank {
                    "Expressive ${gender.tag} character voice with natural manga delivery"
                }
            )
            proj.copy(
                updatedAtEpochMs = System.currentTimeMillis(),
                characters = proj.characters + next
            )
        }
    }

    fun updateLineText(
        projectId: String,
        chapterId: String,
        pageIndex: Int,
        panelId: String,
        lineId: String,
        newText: String,
        newEmotion: String? = null
    ) {
        mutateProject(projectId) { proj ->
            proj.copy(
                updatedAtEpochMs = System.currentTimeMillis(),
                chapters = proj.chapters.map { ch ->
                    if (ch.id != chapterId) ch
                    else ch.copy(
                        pages = ch.pages.map { pg ->
                            if (pg.pageIndex != pageIndex) pg
                            else pg.copy(
                                panels = pg.panels.map { pan ->
                                    if (pan.id != panelId) pan
                                    else pan.copy(
                                        lines = pan.lines.map { ln ->
                                            if (ln.id != lineId) ln
                                            else {
                                                val em = newEmotion ?: ln.emotionTag
                                                val clean = MangaTextBender.sanitizeMangaDialogue(newText)
                                                ln.copy(
                                                    rawText = newText,
                                                    sanitizedText = clean,
                                                    emotionTag = em,
                                                    postPauseMs = MangaEmotionalPacer.computePostLinePauseMs(clean, em)
                                                )
                                            }
                                        }
                                    )
                                }
                            )
                        }
                    )
                }
            )
        }
    }

    fun addLineToPanel(
        projectId: String,
        chapterId: String,
        pageIndex: Int,
        panelId: String,
        speakerId: String,
        text: String
    ) {
        mutateProject(projectId) { proj ->
            val sp = proj.characters.firstOrNull { it.id == speakerId } ?: proj.characters.firstOrNull()
            val clean = MangaTextBender.sanitizeMangaDialogue(text.ifBlank { "Dialogue line." })
            proj.copy(
                updatedAtEpochMs = System.currentTimeMillis(),
                chapters = proj.chapters.map { ch ->
                    if (ch.id != chapterId) ch
                    else ch.copy(
                        pages = ch.pages.map { pg ->
                            if (pg.pageIndex != pageIndex) pg
                            else pg.copy(
                                panels = pg.panels.map { pan ->
                                    if (pan.id != panelId) pan
                                    else {
                                        val nextLine = MangaSpeechLine(
                                            id = "ln_${pageIndex}_${pan.panelIndex}_${System.currentTimeMillis() % 10000}",
                                            bubbleIndex = pan.lines.size + 1,
                                            speakerId = sp?.id ?: "UNKNOWN",
                                            speakerName = sp?.name ?: "NARRATOR",
                                            rawText = text,
                                            sanitizedText = clean,
                                            confidence = 1.0f,
                                            needsUserDecision = false
                                        )
                                        pan.copy(lines = pan.lines + nextLine)
                                    }
                                }
                            )
                        }
                    )
                }
            )
        }
    }

    fun updatePanelAtmosphere(
        projectId: String,
        chapterId: String,
        pageIndex: Int,
        panelId: String,
        atmosphere: MangaAtmospherePreset
    ) {
        mutateProject(projectId) { proj ->
            proj.copy(
                updatedAtEpochMs = System.currentTimeMillis(),
                chapters = proj.chapters.map { ch ->
                    if (ch.id != chapterId) ch
                    else ch.copy(
                        pages = ch.pages.map { pg ->
                            if (pg.pageIndex != pageIndex) pg
                            else pg.copy(
                                panels = pg.panels.map { pan ->
                                    if (pan.id != panelId) pan else pan.copy(atmosphere = atmosphere)
                                }
                            )
                        }
                    )
                }
            )
        }
    }

    fun updateProjectConfig(
        projectId: String,
        readingOrder: MangaReadingOrder? = null,
        videoAspect: MangaVideoAspect? = null,
        burnInSubtitles: Boolean? = null,
        enableAtmosphereFx: Boolean? = null,
        powerUserVisionUrl: String? = null
    ) {
        mutateProject(projectId) { proj ->
            proj.copy(
                updatedAtEpochMs = System.currentTimeMillis(),
                readingOrder = readingOrder ?: proj.readingOrder,
                videoAspect = videoAspect ?: proj.videoAspect,
                burnInSubtitles = burnInSubtitles ?: proj.burnInSubtitles,
                enableAtmosphereFx = enableAtmosphereFx ?: proj.enableAtmosphereFx,
                powerUserVisionUrl = powerUserVisionUrl ?: proj.powerUserVisionUrl
            )
        }
    }

    // =========================================================================
    // ✨ PHASE 2 (VOICE FORGE) -> VRAM EVICT -> PHASE 3 (HARDWARE .MP4 MUX) ✨
    // =========================================================================

    fun produceDubbedChapterMp4(
        projectId: String,
        chapterId: String,
        onComplete: (String) -> Unit
    ) {
        if (_isBusy.value) {
            onComplete("Manga Studio task already running!")
            return
        }
        activeJob = scope.launch {
            _isBusy.value = true
            _overallProgress.value = 0.02f
            try {
                // Phase 1 safety: guarantee Gemma 4 Vision is completely out of RAM
                visionEngine.unload()

                val proj = _projects.value.firstOrNull { it.id == projectId }
                    ?: throw IllegalStateException("Project not found")
                val chapter = proj.chapters.firstOrNull { it.id == chapterId }
                    ?: throw IllegalStateException("Chapter not found")

                val allLinesCount = chapter.totalLineCount.coerceAtLeast(1)
                val audioDir = File(projectDir(projectId), "chapters/$chapterId/audio").apply { mkdirs() }
                val voices = repository.allAvailableVoices()
                val baseProfile = repository.currentProfile
                val sampleRate = PurrDspProcessor.SAMPLE_RATE_HZ

                mutateChapterStatus(projectId, chapterId, MangaChapterStatus.SYNTHESIZING_AUDIO, null)

                var linesDone = 0
                val renderedPages = ArrayList<MangaPageEntry>(chapter.pages.size)

                for (page in chapter.pages) {
                    val renderedPanels = ArrayList<MangaPanelNode>(page.panels.size)
                    for (panel in page.panels) {
                        val renderedLines = ArrayList<MangaSpeechLine>(panel.lines.size)
                        for (line in panel.lines) {
                            if (!isActive) throw CancellationException("Cancelled")
                            linesDone++
                            val textToSpeak = line.sanitizedText.ifBlank { line.rawText }.trim()
                            if (textToSpeak.isEmpty()) {
                                renderedLines += line
                                continue
                            }

                            _overallProgress.value = 0.05f + 0.55f * (linesDone.toFloat() / allLinesCount.toFloat())
                            _statusLabel.value = "FORGING VOICE [${line.speakerName}] ($linesDone/$allLinesCount)..."

                            val charProfile = proj.characters.firstOrNull {
                                it.id == line.speakerId || it.name.equals(line.speakerName, ignoreCase = true)
                            }
                            val synthProfile = resolveSynthesisProfileForCharacter(
                                baseProfile = baseProfile,
                                character = charProfile,
                                availableVoices = voices
                            )
                            val outcome = backendRouter.synthesizeSpeechChunk(
                                chunk = PurrChunk.Speech(
                                    text = textToSpeak,
                                    role = if (line.speakerName.equals("NARRATOR", ignoreCase = true)) SegmentRole.NARRATION else SegmentRole.DIALOGUE,
                                    targetVoiceId = synthProfile.primaryVoiceId,
                                    speakingRate = synthProfile.baseSpeed,
                                    pitch = synthProfile.basePitch,
                                    volumeGain = 1.0f,
                                    sentiment = SentimentResult()
                                ),
                                profile = synthProfile
                            )

                            val wavFile = File(audioDir, "${line.id}.wav")
                            val pcmFloat = outcome.pcmFloat24k
                            val durMs = if (pcmFloat.isNotEmpty()) {
                                val pcm16 = PurrDspProcessor.float32ToPcm16Le(pcmFloat)
                                writePcm16MonoWav(wavFile, pcm16, pcm16.size, sampleRate)
                                ((pcmFloat.size.toLong() * 1000L) / sampleRate.toLong()).coerceAtLeast(350L)
                            } else {
                                maxOf(1400L, textToSpeak.length * 60L)
                            }
                            renderedLines += line.copy(
                                renderedWavPath = if (pcmFloat.isNotEmpty()) wavFile.absolutePath else "",
                                durationMs = durMs
                            )
                        }
                        renderedPanels += panel.copy(lines = renderedLines)
                    }
                    renderedPages += page.copy(panels = renderedPanels)
                }

                // Release all TTS VRAM before hardware H.264/AAC encoding
                _statusLabel.value = "EVICTING TTS VRAM FOR VIDEO ENCODE..."
                backendRouter.evictAllVram()

                val chapterReadyForMux = chapter.copy(
                    status = MangaChapterStatus.RENDERING_VIDEO,
                    pages = renderedPages
                )
                mutateChapter(projectId, chapterId) { chapterReadyForMux }

                val safeBase = sanitizeSlug("${proj.seriesTitle}_${chapter.title}")
                val outMp4File = File(projectDir(projectId), "exports/${safeBase}.mp4").apply {
                    parentFile?.mkdirs()
                }
                val renderRes = MangaMotionVideoMuxer.renderChapterMp4(
                    context = context,
                    chapter = chapterReadyForMux,
                    aspect = proj.videoAspect,
                    burnInSubtitles = proj.burnInSubtitles,
                    enableAtmosphereFx = proj.enableAtmosphereFx,
                    destMp4File = outMp4File
                ) { p, label ->
                    _overallProgress.value = 0.62f + p * 0.38f
                    _statusLabel.value = label
                }

                val finishedChapter = chapterReadyForMux.copy(
                    status = MangaChapterStatus.COMPLETED,
                    exportedMp4Path = renderRes.internalMp4File.absolutePath,
                    publicDownloadPath = renderRes.publicDownloadPath,
                    durationSec = renderRes.durationSec,
                    errorMessage = null
                )
                mutateChapter(projectId, chapterId) { finishedChapter }

                val destNote = renderRes.publicDownloadPath.ifBlank { renderRes.internalMp4File.name }
                _overallProgress.value = 1f
                _statusLabel.value = "EXPORTED ${"%.1f".format(renderRes.durationSec)}s -> $destNote"
                onComplete("Dubbed Manga .MP4 ready: $destNote")
            } catch (ce: CancellationException) {
                throw ce
            } catch (err: Exception) {
                mutateChapterStatus(projectId, chapterId, MangaChapterStatus.FAILED, err.message)
                _statusLabel.value = "PRODUCTION ERROR: ${err.message}"
                onComplete("Production failed: ${err.message}")
            } finally {
                backendRouter.markIdle()
                _isBusy.value = false
            }
        }
    }

    fun resolveSynthesisProfileForCharacter(
        baseProfile: PurrProfile,
        character: MangaCharacterProfile?,
        availableVoices: List<VoiceDescriptor>
    ): PurrProfile {
        if (character == null) return baseProfile

        // 1. Explicit assigned voice (including custom Clone Studio voices like Vegeta!)
        if (character.assignedVoiceId.isNotBlank()) {
            val matched = availableVoices.firstOrNull { it.id == character.assignedVoiceId }
            if (matched != null) {
                return baseProfile.copy(
                    primaryVoiceId = matched.id,
                    preferredBackend = matched.engine,
                    selectedCartridgeId = matched.cartridgeId,
                    sherpaFamily = matched.sherpaFamily ?: baseProfile.sherpaFamily,
                    baseSpeed = (baseProfile.baseSpeed * character.speedScale).coerceIn(0.6f, 1.8f),
                    basePitch = (baseProfile.basePitch * character.pitchScale).coerceIn(0.88f, 1.12f)
                )
            }
        }

        // 2. Qwen3 1.7B VoiceDesign prompt-driven voice synthesis when installed
        val designInstalled = repository.state.value.modelStates[Qwen3TtsEngine.SPEC_DESIGN_17B]?.state == DownloadState.INSTALLED
        if (designInstalled && character.voiceDesignPrompt.isNotBlank()) {
            return baseProfile.copy(
                primaryVoiceId = "qwen3_design_custom",
                preferredBackend = BackendType.QWEN3_NATIVE,
                selectedCartridgeId = "rom_qwen3_custom_06b",
                voiceDesignPrompt = character.voiceDesignPrompt,
                baseSpeed = (baseProfile.baseSpeed * character.speedScale).coerceIn(0.6f, 1.8f),
                basePitch = (baseProfile.basePitch * character.pitchScale).coerceIn(0.88f, 1.12f)
            )
        }

        // 3. Smart Voice Matcher fallback across all installed local/OS voices
        val queryTags = character.voiceDesignPrompt
            .lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 3 }
        val smartVoice = PurrVoiceMatcher.assignVoice(
            voices = availableVoices,
            profile = baseProfile,
            queryName = character.name,
            queryTags = queryTags,
            preferredGender = character.gender
        )
        return if (smartVoice != null) {
            baseProfile.copy(
                primaryVoiceId = smartVoice.id,
                preferredBackend = smartVoice.engine,
                selectedCartridgeId = smartVoice.cartridgeId,
                sherpaFamily = smartVoice.sherpaFamily ?: baseProfile.sherpaFamily,
                baseSpeed = (baseProfile.baseSpeed * character.speedScale).coerceIn(0.6f, 1.8f),
                basePitch = (baseProfile.basePitch * character.pitchScale).coerceIn(0.88f, 1.12f)
            )
        } else {
            baseProfile
        }
    }

    // =========================================================================
    // ✨ INTERNAL FILE & BITMAP HELPERS ✨
    // =========================================================================

    private fun extractCbzImages(uri: Uri, destDir: File): List<File> {
        val allowedExt = setOf("jpg", "jpeg", "png", "webp", "bmp")
        val extracted = ArrayList<Pair<String, File>>()
        context.contentResolver.openInputStream(uri)?.use { raw ->
            ZipInputStream(BufferedInputStream(raw)).use { zis ->
                val destCanonical = destDir.canonicalPath + File.separator
                var idx = 0
                while (true) {
                    val entry = zis.nextEntry ?: break
                    if (!entry.isDirectory) {
                        val name = entry.name.replace('\\', '/')
                        val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
                        if (ext in allowedExt && !name.contains("__MACOSX")) {
                            val outFile = File(destDir, "raw_%04d_%s".format(idx++, name.substringAfterLast('/')))
                            if (outFile.canonicalPath.startsWith(destCanonical)) {
                                FileOutputStream(outFile).use { fos -> zis.copyTo(fos) }
                                extracted += name to outFile
                            }
                        }
                    }
                    zis.closeEntry()
                }
            }
        }
        return extracted.sortedWith(compareBy(NaturalOrderComparator) { it.first }).map { it.second }
    }

    private fun sliceSinglePageFromDisk(
        pageIdx: Int,
        imgFile: File,
        readingOrder: MangaReadingOrder
    ): MangaPageEntry? {
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(imgFile.absolutePath, boundsOpts)
        val origW = boundsOpts.outWidth
        val origH = boundsOpts.outHeight
        if (origW <= 0 || origH <= 0) return null

        val gridW = 220
        val gridH = ((gridW.toFloat() * origH.toFloat()) / origW.toFloat()).toInt().coerceIn(80, 440)
        var sample = 1
        while (origW / sample > gridW * 2 && origH / sample > gridH * 2) sample *= 2
        val sampled = BitmapFactory.decodeFile(imgFile.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val small = Bitmap.createScaledBitmap(sampled, gridW, gridH, true)
        if (sampled !== small) sampled.recycle()

        val pixels = IntArray(gridW * gridH)
        small.getPixels(pixels, 0, gridW, 0, 0, gridW, gridH)
        small.recycle()

        val luminance = IntArray(pixels.size) { idx ->
            val p = pixels[idx]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            (r * 299 + g * 587 + b * 114) / 1000
        }

        val rects = MangaBfsPanelSlicer.slicePanelsFromLuminanceGrid(luminance, gridW, gridH, readingOrder)
        val panelNodes = rects.mapIndexed { panelIdx, rect ->
            val (camStart, camEnd) = MangaBfsPanelSlicer.computeKenBurnsPair(rect, readingOrder)
            MangaPanelNode(
                id = "pan_${pageIdx}_$panelIdx",
                pageIndex = pageIdx,
                panelIndex = panelIdx,
                panelRect = rect,
                cameraStartRect = camStart,
                cameraEndRect = camEnd
            )
        }
        return MangaPageEntry(
            pageIndex = pageIdx,
            fileName = imgFile.name,
            localImagePath = imgFile.absolutePath,
            widthPx = origW,
            heightPx = origH,
            panels = panelNodes
        )
    }

    private fun cropPortraitFromPage(pageFile: File, normRect: MangaNormalizedRect, destFile: File) {
        runCatching {
            val bmp = BitmapFactory.decodeFile(pageFile.absolutePath) ?: return
            try {
                val cx = (normRect.centerX * bmp.width).toInt()
                val cy = ((normRect.top + normRect.height * 0.35f) * bmp.height).toInt()
                val half = (minOf(normRect.width * bmp.width, normRect.height * bmp.height) * 0.38f)
                    .toInt().coerceAtLeast(32)
                val l = (cx - half).coerceIn(0, bmp.width - 16)
                val t = (cy - half).coerceIn(0, bmp.height - 16)
                val r = (cx + half).coerceIn(l + 16, bmp.width)
                val b = (cy + half).coerceIn(t + 16, bmp.height)
                val crop = Bitmap.createBitmap(bmp, l, t, r - l, b - t)
                val scaled = Bitmap.createScaledBitmap(crop, 160, 160, true)
                destFile.parentFile?.mkdirs()
                FileOutputStream(destFile).use { fos ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, 88, fos)
                }
                if (crop !== scaled) crop.recycle()
                scaled.recycle()
            } finally {
                bmp.recycle()
            }
        }
    }

    private fun renderSyntheticMangaPage(targetFile: File, pageNum: Int) {
        val w = 900
        val h = 1280
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)

        val panelStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 6f
            color = Color.BLACK
        }
        val fillDark = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.rgb(24, 24, 34) }
        val fillMid = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.rgb(65, 72, 95) }
        val fillLight = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.WHITE }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 24f
            isFakeBoldText = true
        }

        val pTop = RectF(45f, 45f, 855f, 600f)
        val pBot = RectF(45f, 650f, 855f, 1235f)
        c.drawRect(pTop, fillMid)
        c.drawRect(pTop, panelStroke)
        c.drawRect(pBot, fillDark)
        c.drawRect(pBot, panelStroke)

        // Character silhouettes + speedlines
        c.drawCircle(680f, 320f, 95f, fillDark)
        c.drawCircle(250f, 940f, 105f, fillMid)

        // Speech bubbles
        val b1 = RectF(90f, 90f, 520f, 230f)
        val b2 = RectF(400f, 700f, 815f, 860f)
        c.drawRoundRect(b1, 24f, 24f, fillLight)
        c.drawRoundRect(b1, 24f, 24f, panelStroke)
        c.drawRoundRect(b2, 36f, 36f, fillLight)
        c.drawRoundRect(b2, 36f, 36f, panelStroke)

        if (pageNum == 1) {
            c.drawText("SECTOR 7 COLLAPSE!", 125f, 170f, textPaint)
            c.drawText("OVER 9000!!", 460f, 790f, textPaint)
        } else {
            c.drawText("WAIT... PRINCE.", 135f, 170f, textPaint)
            c.drawText("FINAL FLASH!!", 460f, 790f, textPaint)
        }

        FileOutputStream(targetFile).use { fos -> bmp.compress(Bitmap.CompressFormat.PNG, 95, fos) }
        bmp.recycle()
    }

    private fun recomputeCharacterLineCounts(
        roster: List<MangaCharacterProfile>,
        updatedChapter: MangaChapterEntry?,
        otherChapters: List<MangaChapterEntry>
    ): List<MangaCharacterProfile> {
        val allChapters = if (updatedChapter != null) otherChapters + updatedChapter else otherChapters
        val counts = HashMap<String, Int>()
        for (ch in allChapters) {
            for (pg in ch.pages) {
                for (pan in pg.panels) {
                    for (ln in pan.lines) {
                        counts[ln.speakerId] = (counts[ln.speakerId] ?: 0) + 1
                    }
                }
            }
        }
        return roster.map { it.copy(lineCount = counts[it.id] ?: 0) }
    }

    private fun mutateChapter(projectId: String, chapterId: String, transform: (MangaChapterEntry) -> MangaChapterEntry) {
        mutateProject(projectId) { proj ->
            proj.copy(
                updatedAtEpochMs = System.currentTimeMillis(),
                chapters = proj.chapters.map { if (it.id == chapterId) transform(it) else it }
            )
        }
    }

    private fun mutateChapterStatus(projectId: String, chapterId: String, status: MangaChapterStatus, error: String?) {
        mutateChapter(projectId, chapterId) { it.copy(status = status, errorMessage = error) }
    }

    private inline fun mutateProject(projectId: String, transform: (MangaSeriesProject) -> MangaSeriesProject) {
        val current = _projects.value
        val target = current.firstOrNull { it.id == projectId } ?: return
        val updated = transform(target)
        persistProject(updated)
        _projects.value = current.map { if (it.id == projectId) updated else it }
    }

    private fun persistAndPublish(project: MangaSeriesProject) {
        persistProject(project)
        val without = _projects.value.filterNot { it.id == project.id }
        _projects.value = (listOf(project) + without).sortedByDescending { it.updatedAtEpochMs }
    }

    private fun persistProject(project: MangaSeriesProject) {
        runCatching {
            val file = File(projectDir(project.id), "project.json")
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(project))
        }
    }

    private fun projectDir(projectId: String): File = File(projectsRoot, projectId)

    private fun sanitizeSlug(raw: String): String = raw.lowercase(Locale.US)
        .replace(Regex("[^a-z0-9]+"), "_")
        .trim('_')
        .take(24)
        .ifEmpty { "manga" }

    private fun resolveFileName(uri: Uri): String {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) return c.getString(idx)
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "chapter.cbz"
    }

    private fun writePcm16MonoWav(file: File, pcmData: ByteArray, dataLength: Int, sampleRate: Int) {
        FileOutputStream(file).use { fos ->
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray(Charsets.US_ASCII))
            header.putInt(36 + dataLength)
            header.put("WAVE".toByteArray(Charsets.US_ASCII))
            header.put("fmt ".toByteArray(Charsets.US_ASCII))
            header.putInt(16)
            header.putShort(1.toShort())
            header.putShort(1.toShort())
            header.putInt(sampleRate)
            header.putInt(sampleRate * 2)
            header.putShort(2.toShort())
            header.putShort(16.toShort())
            header.put("data".toByteArray(Charsets.US_ASCII))
            header.putInt(dataLength)
            fos.write(header.array())
            fos.write(pcmData, 0, dataLength)
        }
    }

    private object NaturalOrderComparator : Comparator<String> {
        private val chunkRe = Regex("(\\d+|\\D+)")
        override fun compare(a: String, b: String): Int {
            val partsA = chunkRe.findAll(a.lowercase(Locale.US)).map { it.value }.toList()
            val partsB = chunkRe.findAll(b.lowercase(Locale.US)).map { it.value }.toList()
            val count = minOf(partsA.size, partsB.size)
            for (i in 0 until count) {
                val sa = partsA[i]
                val sb = partsB[i]
                val na = sa.toLongOrNull()
                val nb = sb.toLongOrNull()
                val cmp = if (na != null && nb != null) na.compareTo(nb) else sa.compareTo(sb)
                if (cmp != 0) return cmp
            }
            return partsA.size.compareTo(partsB.size)
        }
    }
}
