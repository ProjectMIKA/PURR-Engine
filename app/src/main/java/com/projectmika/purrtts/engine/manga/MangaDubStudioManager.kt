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
import com.projectmika.purrtts.core.manga.MangaCbzExtractorUtils
import com.projectmika.purrtts.core.manga.MangaChapterEntry
import com.projectmika.purrtts.core.manga.MangaChapterStatus
import com.projectmika.purrtts.core.manga.MangaCharacterProfile
import com.projectmika.purrtts.core.manga.MangaEmotionalPacer
import com.projectmika.purrtts.core.manga.MangaJobTelemetry
import com.projectmika.purrtts.core.manga.MangaNormalizedRect
import com.projectmika.purrtts.core.manga.MangaPageEntry
import com.projectmika.purrtts.core.manga.MangaPanelCameraEffect
import com.projectmika.purrtts.core.manga.MangaPanelNode
import com.projectmika.purrtts.core.manga.MangaPipelineStage
import com.projectmika.purrtts.core.manga.MangaReadingOrder
import com.projectmika.purrtts.core.manga.MangaSeriesProject
import com.projectmika.purrtts.core.manga.MangaSpeechLine
import com.projectmika.purrtts.core.manga.MangaTelemetryUtils
import com.projectmika.purrtts.core.manga.MangaTextBender
import com.projectmika.purrtts.core.manga.MangaVideoAspect
import com.projectmika.purrtts.core.manga.MangaVideoQuality
import com.projectmika.purrtts.core.manga.MangaVisionModelTier
import com.projectmika.purrtts.core.purr.AcousticEffectPreset
import com.projectmika.purrtts.core.purr.BackendType
import com.projectmika.purrtts.core.purr.ClonedVoiceProfile
import com.projectmika.purrtts.core.purr.DownloadState
import com.projectmika.purrtts.core.purr.DspConfig
import com.projectmika.purrtts.core.purr.PurrChunk
import com.projectmika.purrtts.core.purr.PurrDspProcessor
import com.projectmika.purrtts.core.purr.PurrProfile
import com.projectmika.purrtts.core.purr.PurrProfileRepository
import com.projectmika.purrtts.core.purr.PurrVoiceMatcher
import com.projectmika.purrtts.core.purr.SegmentRole
import com.projectmika.purrtts.core.purr.SentimentResult
import com.projectmika.purrtts.core.purr.SherpaModelFamily
import com.projectmika.purrtts.core.purr.VoiceDescriptor
import com.projectmika.purrtts.core.purr.VoiceGender
import com.projectmika.purrtts.engine.TtsBackendRouter
import com.projectmika.purrtts.engine.qwen3.Qwen3TtsEngine
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
 * Orchestrates persistent multi-chapter `.purrmanga` series projects, hardened 4-strategy `.cbz`
 * image extraction, GachaSwipe BFS panel slicing, Gemma 4 E2B/E4B LiteRT Vision + Thinking attribution
 * (with strict sequential VRAM eviction before TTS starts), Pre-Production decision resolution +
 * Clone Studio voice binding, Qwen VoiceDesign / installed voice synthesis, live ETA telemetry,
 * and hardware `.MP4` export.
 */
class MangaDubStudioManager(
    private val context: Context,
    private val repository: PurrProfileRepository,
    private val backendRouter: TtsBackendRouter
) {
    private val prefs = context.getSharedPreferences("purr_manga_studio_prefs", Context.MODE_PRIVATE)
    private val projectsRoot = File(context.filesDir, "manga_projects").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val visionEngine = LiteRtGemmaVisionEngine(context)

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _selectedVisionModel = MutableStateFlow(
        MangaVisionModelTier.fromNameOrDefault(prefs.getString("selected_vision_model", null))
    )
    val selectedVisionModel: StateFlow<MangaVisionModelTier> = _selectedVisionModel.asStateFlow()

    private val _selectedReadingOrder = MutableStateFlow(
        MangaReadingOrder.entries.firstOrNull {
            it.name.equals(prefs.getString("selected_reading_order", null), ignoreCase = true)
        } ?: MangaReadingOrder.RTL_MANGA
    )
    val selectedReadingOrder: StateFlow<MangaReadingOrder> = _selectedReadingOrder.asStateFlow()

    private val _includeNonBubbleText = MutableStateFlow(
        prefs.getBoolean("include_non_bubble_text", false)
    )
    val includeNonBubbleText: StateFlow<Boolean> = _includeNonBubbleText.asStateFlow()

    private val _lockVoicesViaCloning = MutableStateFlow(
        prefs.getBoolean("lock_voices_via_cloning", true)
    )
    val lockVoicesViaCloning: StateFlow<Boolean> = _lockVoicesViaCloning.asStateFlow()

    // ✨ v1.9.8: KV-cache panel scanning is OPT-IN — default OFF because the compact KV turn
    // prompts made small VLMs (Gemma E2B) fail the panel OCR task and parrot the schema example
    // ("Dialogue text"). Advanced-settings toggle warns about the speed/hallucination tradeoff.
    private val _mangaKvCacheEnabled = MutableStateFlow(
        prefs.getBoolean("manga_kv_cache_enabled", false)
    )
    val mangaKvCacheEnabled: StateFlow<Boolean> = _mangaKvCacheEnabled.asStateFlow()

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

    private val _telemetry = MutableStateFlow(MangaJobTelemetry())
    val telemetry: StateFlow<MangaJobTelemetry> = _telemetry.asStateFlow()

    private val logLines = ArrayDeque<String>(10)
    private var activeJobStartMs: Long = 0L
    private var telemetryTickerJob: Job? = null
    private var activeJob: Job? = null

    init {
        reloadAllProjects()
    }

    fun setSelectedVisionModel(tier: MangaVisionModelTier) {
        prefs.edit().putString("selected_vision_model", tier.name).apply()
        _selectedVisionModel.value = tier
        _activeProjectId.value?.let { pid ->
            updateProjectConfig(pid, preferredVisionModel = tier)
        }
    }

    fun setSelectedReadingOrder(order: MangaReadingOrder) {
        prefs.edit().putString("selected_reading_order", order.name).apply()
        _selectedReadingOrder.value = order
        _activeProjectId.value?.let { pid ->
            updateProjectConfig(pid, readingOrder = order)
        }
    }

    fun setIncludeNonBubbleText(include: Boolean) {
        prefs.edit().putBoolean("include_non_bubble_text", include).apply()
        _includeNonBubbleText.value = include
        _activeProjectId.value?.let { pid ->
            updateProjectConfig(pid, includeNonBubbleText = include)
        }
    }

    fun setLockVoicesViaCloning(lock: Boolean) {
        prefs.edit().putBoolean("lock_voices_via_cloning", lock).apply()
        _lockVoicesViaCloning.value = lock
        _activeProjectId.value?.let { pid ->
            updateProjectConfig(pid, lockVoicesViaCloning = lock)
        }
    }

    fun setMangaKvCacheEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("manga_kv_cache_enabled", enabled).apply()
        _mangaKvCacheEnabled.value = enabled
        appendLog(
            if (enabled) "⚡ KV-cache panel scanning ON (experimental — may hallucinate on small VLMs)"
            else "🛡️ KV-cache panel scanning OFF (default — full per-panel prompts)"
        )
    }

    // ✨ v1.9.8: max panels per page — 0 = AUTO (trust the border-verified gutter evidence up to
    // the recursion's natural 8-leaf ceiling, no merge-away); 4/6/8/12 are explicit overrides
    // for art styles that fool the gutter detector or to trade scan time for fewer, larger panels.
    private val _mangaMaxPanelsPerPage = MutableStateFlow(
        prefs.getInt("manga_max_panels_per_page", 0)
    )
    val mangaMaxPanelsPerPage: StateFlow<Int> = _mangaMaxPanelsPerPage.asStateFlow()

    fun setMangaMaxPanelsPerPage(maxPanels: Int) {
        prefs.edit().putInt("manga_max_panels_per_page", maxPanels).apply()
        _mangaMaxPanelsPerPage.value = maxPanels
        appendLog(
            if (maxPanels == 0) "✂️ Panel slicer: AUTO (evidence-gated, ceiling ${MangaBfsPanelSlicer.AUTO_MAX_PANELS_PER_PAGE})"
            else "✂️ Panel slicer: max $maxPanels panels/page (re-scan chapter to apply)"
        )
    }

    fun resolveMangaMaxPanelsPerPage(): Int =
        _mangaMaxPanelsPerPage.value.takeIf { it in 4..MangaBfsPanelSlicer.HARD_MAX_PANELS_PER_PAGE }
            ?: MangaBfsPanelSlicer.AUTO_MAX_PANELS_PER_PAGE

    private fun appendLog(entry: String) {
        val elapsedSec = if (activeJobStartMs > 0L) {
            ((System.currentTimeMillis() - activeJobStartMs) / 1000L).toInt().coerceAtLeast(0)
        } else 0
        val stamp = MangaTelemetryUtils.formatDurationShort(elapsedSec)
        val line = "[$stamp] $entry"
        synchronized(logLines) {
            if (logLines.lastOrNull() != line) {
                while (logLines.size >= 40) logLines.removeFirst()
                logLines.addLast(line)
            }
        }
    }

    private fun customVisionPaths(): List<String> = MangaVisionModelTier.entries.mapNotNull { tier ->
        repository.state.value.modelStates[tier.specId]?.localPath
    }

    private fun startTelemetryTicker(stage: MangaPipelineStage, detail: String, itemTotal: Int = 0) {
        telemetryTickerJob?.cancel()
        activeJobStartMs = System.currentTimeMillis()
        synchronized(logLines) { logLines.clear() }
        appendLog(detail)
        _statusLabel.value = detail
        _telemetry.value = MangaJobTelemetry(
            stage = stage,
            detailText = detail,
            itemCurrent = 0,
            itemTotal = itemTotal,
            elapsedMs = 0L,
            estimatedRemainingSec = null,
            logTail = synchronized(logLines) { logLines.toList() }
        )
        telemetryTickerJob = scope.launch {
            while (isActive && _isBusy.value) {
                val nowElapsed = (System.currentTimeMillis() - activeJobStartMs).coerceAtLeast(0L)
                val cur = _telemetry.value
                _telemetry.value = cur.copy(
                    elapsedMs = nowElapsed,
                    estimatedRemainingSec = MangaTelemetryUtils.estimateRemainingSec(
                        elapsedMs = nowElapsed,
                        fractionDone = _overallProgress.value
                    )
                )
                delay(1000L)
            }
        }
    }

    private fun reportTelemetry(
        stage: MangaPipelineStage,
        detail: String,
        progress: Float? = null,
        itemCurrent: Int = _telemetry.value.itemCurrent,
        itemTotal: Int = _telemetry.value.itemTotal,
        addLog: Boolean = false
    ) {
        if (progress != null) {
            _overallProgress.value = progress.coerceIn(0f, 1f)
        }
        _statusLabel.value = detail
        if (addLog) appendLog(detail)
        val nowElapsed = if (activeJobStartMs > 0L) {
            (System.currentTimeMillis() - activeJobStartMs).coerceAtLeast(0L)
        } else 0L
        _telemetry.value = MangaJobTelemetry(
            stage = stage,
            detailText = detail,
            itemCurrent = itemCurrent,
            itemTotal = itemTotal,
            elapsedMs = nowElapsed,
            estimatedRemainingSec = MangaTelemetryUtils.estimateRemainingSec(
                elapsedMs = nowElapsed,
                fractionDone = _overallProgress.value
            ),
            logTail = synchronized(logLines) { logLines.toList() }
        )
    }

    private fun finishTelemetry(detail: String) {
        telemetryTickerJob?.cancel()
        telemetryTickerJob = null
        val nowElapsed = if (activeJobStartMs > 0L) {
            (System.currentTimeMillis() - activeJobStartMs).coerceAtLeast(0L)
        } else 0L
        _statusLabel.value = detail
        appendLog(detail)
        _telemetry.value = _telemetry.value.copy(
            stage = MangaPipelineStage.IDLE,
            detailText = detail,
            elapsedMs = nowElapsed,
            estimatedRemainingSec = null,
            logTail = synchronized(logLines) { logLines.toList() }
        )
    }

    fun reloadAllProjects() {
        val loaded = projectsRoot.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { dir ->
                val f = File(dir, "project.json")
                if (!f.exists()) return@mapNotNull null
                runCatching {
                    val origRaw = f.readText()
                    val sanitizedRaw = origRaw.replace(
                        Regex("\"preferredVisionModel\"\\s*:\\s*\"AUTO\""),
                        "\"preferredVisionModel\":\"GEMMA4_E2B\""
                    )
                    val decoded = json.decodeFromString<MangaSeriesProject>(sanitizedRaw)
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
                    if (healedChapters != decoded.chapters || sanitizedRaw != f.readText()) {
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

    fun deleteChapter(projectId: String, chapterId: String) {
        if (_isBusy.value && _activeProjectId.value == projectId) {
            cancelActiveWork()
        }
        val proj = _projects.value.firstOrNull { it.id == projectId } ?: return
        runCatching { File(projectDir(projectId), "chapters/$chapterId").deleteRecursively() }
        val remainingChapters = proj.chapters.filterNot { it.id == chapterId }
        val updatedRoster = recomputeCharacterLineCounts(
            proj.characters,
            remainingChapters.firstOrNull(),
            remainingChapters.drop(1)
        )
        val updated = proj.copy(
            updatedAtEpochMs = System.currentTimeMillis(),
            characters = updatedRoster,
            chapters = remainingChapters
        )
        persistAndPublish(updated)
    }

    fun deleteAllProjects() {
        if (_isBusy.value) {
            cancelActiveWork()
        }
        projectsRoot.listFiles()?.forEach { dir ->
            runCatching { dir.deleteRecursively() }
        }
        _projects.value = emptyList()
        _activeProjectId.value = null
    }

    fun cancelActiveWork() {
        telemetryTickerJob?.cancel()
        telemetryTickerJob = null
        activeJob?.cancel()
        activeJob = null
        scope.launch {
            runCatching { visionEngine.unload() }
            runCatching { backendRouter.evictAllVram() }
        }
        _isBusy.value = false
        _overallProgress.value = 0f
        finishTelemetry("CANCELLED // VRAM CLEARED")
    }

    fun importCbzChapter(
        uri: Uri,
        targetProjectId: String?,
        seriesTitleInput: String,
        readingOrder: MangaReadingOrder,
        autoProduceMp4: Boolean = true,
        pauseOnFlags: Boolean = true,
        videoAspect: MangaVideoAspect? = null,
        burnInSubtitles: Boolean? = null,
        videoQuality: MangaVideoQuality? = null,
        includeNonBubbleText: Boolean? = null,
        lockVoicesViaCloning: Boolean? = null,
        preferredVisionModel: MangaVisionModelTier? = null,
        onComplete: (String) -> Unit
    ) {
        if (_isBusy.value) {
            onComplete("Manga Studio task already running!")
            return
        }
        val effectiveVisionTier = preferredVisionModel ?: _selectedVisionModel.value
        val effectiveIncludeNonBubble = includeNonBubbleText ?: _includeNonBubbleText.value
        val effectiveLockVoices = lockVoicesViaCloning ?: _lockVoicesViaCloning.value
        prefs.edit()
            .putString("selected_vision_model", effectiveVisionTier.name)
            .putString("selected_reading_order", readingOrder.name)
            .putBoolean("include_non_bubble_text", effectiveIncludeNonBubble)
            .putBoolean("lock_voices_via_cloning", effectiveLockVoices)
            .apply()
        _selectedVisionModel.value = effectiveVisionTier
        _selectedReadingOrder.value = readingOrder
        _includeNonBubbleText.value = effectiveIncludeNonBubble
        _lockVoicesViaCloning.value = effectiveLockVoices

        activeJob = scope.launch {
            _isBusy.value = true
            _overallProgress.value = 0.02f
            startTelemetryTicker(
                stage = MangaPipelineStage.EXTRACT_AND_SLICE,
                detail = "📦 OPENING .CBZ ARCHIVE STREAM..."
            )
            try {
                val rawName = resolveFileName(uri)
                val chapterTitle = rawName.substringBeforeLast('.').ifBlank { "Chapter 1" }
                val existingProject = targetProjectId?.let { id -> _projects.value.firstOrNull { it.id == id } }
                val now = System.currentTimeMillis()
                val projectId = existingProject?.id ?: "manga_${sanitizeSlug(seriesTitleInput.ifBlank { chapterTitle })}_$now"
                val chapterId = "ch_${sanitizeSlug(chapterTitle)}_$now"
                val chapterPagesDir = File(projectDir(projectId), "chapters/$chapterId/pages").apply { mkdirs() }

                reportTelemetry(
                    stage = MangaPipelineStage.EXTRACT_AND_SLICE,
                    detail = "📦 UNPACKING .CBZ ($rawName)...",
                    progress = 0.04f,
                    addLog = true
                )
                val extractedFiles = extractCbzImages(uri, chapterPagesDir, rawName)
                if (extractedFiles.isEmpty()) {
                    throw IllegalStateException("No readable manga image pages (.jpg/.png/.webp) found in $rawName")
                }

                val initialProj = existingProject?.copy(
                    updatedAtEpochMs = now,
                    readingOrder = readingOrder,
                    videoAspect = videoAspect ?: existingProject.videoAspect,
                    burnInSubtitles = burnInSubtitles ?: existingProject.burnInSubtitles,
                    videoQuality = videoQuality ?: existingProject.videoQuality,
                    includeNonBubbleText = effectiveIncludeNonBubble,
                    lockVoicesViaCloning = lockVoicesViaCloning ?: existingProject.lockVoicesViaCloning,
                    preferredVisionModel = effectiveVisionTier,
                    autoCookFullMp4 = autoProduceMp4,
                    pauseOnFlaggedDecisions = pauseOnFlags
                ) ?: MangaSeriesProject(
                    id = projectId,
                    seriesTitle = seriesTitleInput.trim().ifEmpty { chapterTitle },
                    createdAtEpochMs = now,
                    updatedAtEpochMs = now,
                    readingOrder = readingOrder,
                    videoAspect = videoAspect ?: MangaVideoAspect.VERTICAL_9_16,
                    burnInSubtitles = burnInSubtitles ?: true,
                    videoQuality = videoQuality ?: MangaVideoQuality.HD_720P,
                    includeNonBubbleText = effectiveIncludeNonBubble,
                    lockVoicesViaCloning = effectiveLockVoices,
                    preferredVisionModel = effectiveVisionTier,
                    autoCookFullMp4 = autoProduceMp4,
                    pauseOnFlaggedDecisions = pauseOnFlags
                )

                reportTelemetry(
                    stage = MangaPipelineStage.EXTRACT_AND_SLICE,
                    detail = "✂️ BFS SLICING ${extractedFiles.size} PAGES...",
                    progress = 0.06f,
                    itemCurrent = 0,
                    itemTotal = extractedFiles.size,
                    addLog = true
                )
                val rawPages = extractedFiles.mapIndexedNotNull { pageIdx, imgFile ->
                    reportTelemetry(
                        stage = MangaPipelineStage.EXTRACT_AND_SLICE,
                        detail = "✂️ SLICING PAGE ${pageIdx + 1}/${extractedFiles.size} (${imgFile.name})...",
                        progress = 0.06f + 0.08f * ((pageIdx + 1).toFloat() / extractedFiles.size.toFloat()),
                        itemCurrent = pageIdx + 1,
                        itemTotal = extractedFiles.size
                    )
                    sliceSinglePageFromDisk(pageIdx, imgFile, readingOrder, resolveMangaMaxPanelsPerPage())
                }
                if (rawPages.isEmpty()) {
                    throw IllegalStateException("Failed to decode extracted manga images")
                }
                val totalPanelCount = rawPages.sumOf { it.panels.size }
                appendLog("✂️ Sliced ${rawPages.size} page(s) into $totalPanelCount reading-order panels")

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

                val visionSpan = if (autoProduceMp4) 0.36f else 0.86f
                val finalProj = runVisionPhaseOnChapter(
                    projectId = projWithChapter.id,
                    chapterId = chapterId,
                    progressOffset = 0.14f,
                    progressSpan = visionSpan
                )
                val ch = finalProj.chapters.firstOrNull { it.id == chapterId }
                val pending = ch?.pendingDecisionCount ?: 0

                if (!autoProduceMp4) {
                    val summaryMsg = "Scanned ${rawPages.size} pages, ${ch?.totalPanelCount ?: 0} panels ($pending decision(s))"
                    _overallProgress.value = 1f
                    finishTelemetry("✅ PRE-PRODUCTION READY // $summaryMsg")
                    onComplete(summaryMsg)
                    return@launch
                }

                if (pauseOnFlags && pending > 0) {
                    val pauseMsg = "Paused: $pending bubble(s) flagged for quick speaker confirmation (${ch?.totalLineCount ?: 0} lines)"
                    _overallProgress.value = 0.50f
                    finishTelemetry("⏸️ $pauseMsg")
                    onComplete(pauseMsg)
                    return@launch
                }

                reportTelemetry(
                    stage = MangaPipelineStage.VOICE_FORGE,
                    detail = "🚀 AUTO-COOK ENGAGED (${ch?.totalLineCount ?: 0} LINES, ${finalProj.characters.size} CAST) -> FORGING VOICES...",
                    progress = 0.50f,
                    addLog = true
                )
                val destNote = executeProduceDubbedChapterMp4(
                    projectId = projWithChapter.id,
                    chapterId = chapterId,
                    progressOffset = 0.50f,
                    progressSpan = 0.50f
                )
                _overallProgress.value = 1f
                finishTelemetry("🎉 DUBBED EPISODE READY -> $destNote")
                onComplete("Dubbed Manga .MP4 ready: $destNote")
            } catch (ce: CancellationException) {
                throw ce
            } catch (err: Exception) {
                val msg = err.message ?: "Unknown extract error"
                finishTelemetry("❌ COOK ERROR: $msg")
                onComplete("Manga dub failed: $msg")
            } finally {
                runCatching { visionEngine.unload() }
                backendRouter.markIdle()
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
            startTelemetryTicker(
                stage = MangaPipelineStage.EXTRACT_AND_SLICE,
                detail = "GENERATING SAMPLE CYBER-MANGA CHAPTER..."
            )
            try {
                reportTelemetry(
                    stage = MangaPipelineStage.EXTRACT_AND_SLICE,
                    detail = "GENERATING SAMPLE CYBER-MANGA CHAPTER...",
                    progress = 0.15f,
                    itemCurrent = 1,
                    itemTotal = 2,
                    addLog = true
                )
                val now = System.currentTimeMillis()
                val projectId = "manga_dragon_cyber_demo_$now"
                val chapterId = "ch_01_saiyan_signal"
                val pagesDir = File(projectDir(projectId), "chapters/$chapterId/pages").apply { mkdirs() }
                val charsDir = File(projectDir(projectId), "characters").apply { mkdirs() }

                val page1File = File(pagesDir, "page_001.png")
                val page2File = File(pagesDir, "page_002.png")
                renderSyntheticMangaPage(page1File, pageNum = 1)
                renderSyntheticMangaPage(page2File, pageNum = 2)

                val p1 = sliceSinglePageFromDisk(0, page1File, MangaReadingOrder.RTL_MANGA, resolveMangaMaxPanelsPerPage())!!
                val p2 = sliceSinglePageFromDisk(1, page2File, MangaReadingOrder.RTL_MANGA, resolveMangaMaxPanelsPerPage())!!

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
                finishTelemetry("DEMO READY // TRY VEGETA CLONE OR RESOLVE 1 DECISION")
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
            _overallProgress.value = 0.05f
            startTelemetryTicker(
                stage = MangaPipelineStage.VISION_SCAN,
                detail = "🧠 MOUNTING GEMMA 4 VISION FOR CHAPTER RESCAN..."
            )
            try {
                val updated = runVisionPhaseOnChapter(
                    projectId = projectId,
                    chapterId = chapterId,
                    progressOffset = 0.05f,
                    progressSpan = 0.95f
                )
                val ch = updated.chapters.firstOrNull { it.id == chapterId }
                val pending = ch?.pendingDecisionCount ?: 0
                _overallProgress.value = 1f
                finishTelemetry("✅ VISION & VOICEDESIGN RESCAN COMPLETE ($pending DECISION(S))")
                onComplete("Re-scanned chapter (${ch?.totalLineCount ?: 0} lines, $pending decision(s))")
            } catch (ce: CancellationException) {
                throw ce
            } catch (err: Exception) {
                finishTelemetry("❌ VISION RESCAN FAILED: ${err.message}")
                onComplete("Vision rescan failed: ${err.message}")
            } finally {
                runCatching { visionEngine.unload() }
                _isBusy.value = false
            }
        }
    }

    private suspend fun runVisionPhaseOnChapter(
        projectId: String,
        chapterId: String,
        progressOffset: Float = 0.14f,
        progressSpan: Float = 0.80f
    ): MangaSeriesProject {
        var proj = _projects.value.firstOrNull { it.id == projectId }
            ?: throw IllegalStateException("Project not found")
        val ch = proj.chapters.firstOrNull { it.id == chapterId }
            ?: throw IllegalStateException("Chapter not found")

        val charsDir = File(projectDir(projectId), "characters").apply { mkdirs() }
        val roster = proj.characters.toMutableList()
        val recentLines = mutableListOf<String>()
        val sampleLinesBySpeaker = LinkedHashMap<String, MutableList<String>>()
        val updatedPages = ArrayList<MangaPageEntry>(ch.pages.size)
        val modelPaths = customVisionPaths()

        val storyboardSpan = progressSpan * 0.08f
        val pageScanOffset = progressOffset + storyboardSpan
        val pageScanSpan = progressSpan * 0.70f
        val castingOffset = pageScanOffset + pageScanSpan
        val castingSpan = progressSpan * 0.22f

        try {
            val activeVisionTier = _selectedVisionModel.value
            if (proj.id == _activeProjectId.value && proj.preferredVisionModel != activeVisionTier) {
                proj = proj.copy(preferredVisionModel = activeVisionTier)
            }
            val effectiveIncludeNonBubble = proj.includeNonBubbleText

            // Step 2A: Fast Storyboard Context Pass across chapter pages before zoomed panel OCR
            val pageFiles = ch.pages.mapNotNull { pg ->
                File(pg.localImagePath).takeIf { it.isFile }
            }
            var pageSynopses = emptyMap<Int, String>()
            if (pageFiles.isNotEmpty()) {
                reportTelemetry(
                    stage = MangaPipelineStage.VISION_SCAN,
                    detail = "🗺️ ${proj.preferredVisionModel.shortLabel} BUILDING CHAPTER STORYBOARD CONTEXT (${pageFiles.size} pg)...",
                    progress = progressOffset + storyboardSpan * 0.35f,
                    itemCurrent = 0,
                    itemTotal = ch.pages.size,
                    addLog = true
                )
                val bible = visionEngine.runChapterStoryboardPass(
                    seriesTitle = proj.seriesTitle,
                    pageFiles = pageFiles,
                    readingOrder = ch.readingOrder,
                    existingCharacters = roster,
                    powerUserVisionUrl = proj.powerUserVisionUrl,
                    preferredVisionModel = proj.preferredVisionModel,
                    customModelPaths = modelPaths,
                    onLog = { msg -> appendLog(msg) }
                )
                if (bible != null) {
                    if (bible.storySummary.isNotBlank()) {
                        proj = proj.copy(storyContextSummary = bible.storySummary)
                        appendLog("🗺️ Storyboard: ${bible.storySummary.take(100)}")
                    }
                    pageSynopses = bible.pageSynopses
                    val fallbackPageFile = pageFiles.firstOrNull()
                    bible.characters.forEach { sbChar ->
                        val canonicalName = sbChar.name.trim().uppercase()
                        // ✨ NARRATOR IDENTITY LAW (v1.9.9): narration belongs to MIKA (the
                        // hardcoded narrator) — a storyboard "NARRATOR"/UNKNOWN/placeholder entry
                        // must NEVER become a cast member (E4B once seeded an androgynous
                        // NARRATOR with invented bunny-butler traits).
                        if (canonicalName.isNotEmpty() &&
                            !MangaTextBender.isPlaceholderOrNarratorName(canonicalName) &&
                            roster.none { it.name.equals(canonicalName, ignoreCase = true) }
                        ) {
                            val newCharId = "char_${sanitizeSlug(canonicalName)}_${roster.size + 1}"
                            val avatarFile = File(charsDir, "$newCharId.jpg")
                            if (fallbackPageFile != null) {
                                cropPortraitFromPage(
                                    fallbackPageFile,
                                    MangaNormalizedRect(0.15f, 0.12f, 0.85f, 0.78f),
                                    avatarFile
                                )
                            }
                            val seeded = MangaCharacterProfile(
                                id = newCharId,
                                name = canonicalName,
                                gender = sbChar.gender,
                                visualDescription = sbChar.visualDescription.ifBlank { "Discovered in chapter storyboard" },
                                avatarCropPath = if (avatarFile.isFile) avatarFile.absolutePath else "",
                                voiceDesignPrompt = sbChar.voiceDesignPrompt.ifBlank {
                                    "Expressive ${sbChar.gender.tag} anime character voice, clear dialogue pacing"
                                }
                            )
                            roster += seeded
                            appendLog("🗺️ Storyboard cast seeded: ${seeded.name} (${seeded.gender.tag})")
                        }
                    }
                }
            }

            appendLog(
                if (effectiveIncludeNonBubble) {
                    "🧪 Experimental bubble filter OFF — processing SFX & non-bubble text too"
                } else {
                    "💬 Speech-bubble-only filter ON — skipping SFX, credits & signatures"
                }
            )

            for ((pageIdx, page) in ch.pages.withIndex()) {
                if (!kotlinx.coroutines.currentCoroutineContext().isActive) throw CancellationException("Cancelled")
                val tierNote = proj.preferredVisionModel.shortLabel
                reportTelemetry(
                    stage = MangaPipelineStage.VISION_SCAN,
                    detail = "🧠 $tierNote SCANNING PAGE ${pageIdx + 1}/${ch.pages.size} (${ch.readingOrder.shortLabel})...",
                    progress = pageScanOffset + pageScanSpan * (pageIdx.toFloat() / ch.pages.size.coerceAtLeast(1).toFloat()),
                    itemCurrent = pageIdx + 1,
                    itemTotal = ch.pages.size,
                    addLog = true
                )
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
                        powerUserVisionUrl = proj.powerUserVisionUrl,
                        preferredVisionModel = proj.preferredVisionModel,
                        customModelPaths = modelPaths,
                        storySummary = proj.storyContextSummary,
                        pageSynopsis = pageSynopses[pageIdx].orEmpty(),
                        includeNonBubbleText = effectiveIncludeNonBubble,
                        kvCacheEnabled = _mangaKvCacheEnabled.value,
                        onLog = { msg -> appendLog(msg) },
                        onPanelProgress = { panDone, panTotal, bubbleNote ->
                            val panFrac = (pageIdx.toFloat() + (panDone.toFloat() / panTotal.coerceAtLeast(1).toFloat())) / ch.pages.size.coerceAtLeast(1).toFloat()
                            reportTelemetry(
                                stage = MangaPipelineStage.VISION_SCAN,
                                detail = "🧠 $tierNote // P${pageIdx + 1}/${ch.pages.size} PANEL $panDone/$panTotal ($bubbleNote)",
                                progress = pageScanOffset + pageScanSpan * panFrac,
                                itemCurrent = pageIdx + 1,
                                itemTotal = ch.pages.size,
                                addLog = false
                            )
                        }
                    )
                    val thoughtPreview = analysis.thoughtTrace.replace('\n', ' ').trim().take(90)
                    if (thoughtPreview.isNotBlank()) {
                        appendLog("💭 P${pageIdx + 1} thought: \"$thoughtPreview...\"")
                    }

                    val panelByIdx = analysis.panels.associateBy { it.panelIndex }
                    var pageDialogueCount = 0
                    val nextPanels = page.panels.mapIndexed { pIdx, existingPanel ->
                        val spec = panelByIdx[pIdx]
                        if (spec == null) {
                            existingPanel
                        } else if (spec.lines.isEmpty() && existingPanel.lines.isNotEmpty()) {
                            // ✨ NO-WIPE LAW (v1.9.7): a failed/empty vision spec must never erase
                            // lines a previous pass already transcribed — an empty response means
                            // "no data", not "this panel is silent".
                            appendLog(
                                "⚠️ P${pageIdx + 1}.${pIdx + 1} vision returned 0 bubbles; " +
                                    "keeping ${existingPanel.lines.size} existing line(s)"
                            )
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
                                    appendLog("👤 Cast discovered: ${newChar.name} (${newChar.gender.tag})")
                                }
                                val cleanText = MangaTextBender.sanitizeMangaDialogue(ls.rawText)
                                if (cleanText.isNotEmpty()) {
                                    pageDialogueCount++
                                    if (!ls.needsUserDecision) {
                                        recentLines += "$speakerKey: $cleanText"
                                    }
                                    val key = matchedChar?.name ?: speakerKey
                                    sampleLinesBySpeaker.getOrPut(key) { mutableListOf() } += cleanText
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
                    reportTelemetry(
                        stage = MangaPipelineStage.VISION_SCAN,
                        detail = "🧠 PAGE ${pageIdx + 1}/${ch.pages.size} ($pageDialogueCount lines) // $engineBadge",
                        progress = progressOffset + pageScanSpan * ((pageIdx + 1).toFloat() / ch.pages.size.coerceAtLeast(1).toFloat()),
                        itemCurrent = pageIdx + 1,
                        itemTotal = ch.pages.size,
                        addLog = true
                    )
                } finally {
                    bmp.recycle()
                }
            }

            if (roster.isNotEmpty()) {
                reportTelemetry(
                    stage = MangaPipelineStage.VOICE_CASTING,
                    detail = "🎭 GEMMA 4 DESIGNING QWEN3 1.7B VOICES FOR ${roster.size} CHARACTER(S)...",
                    progress = castingOffset + castingSpan * 0.2f,
                    itemCurrent = 0,
                    itemTotal = roster.size,
                    addLog = true
                )
                val designedRoster = visionEngine.craftQwenVoiceDesignsForRoster(
                    seriesTitle = proj.seriesTitle,
                    storySummary = proj.storyContextSummary,
                    characters = roster,
                    sampleLinesBySpeaker = sampleLinesBySpeaker,
                    preferredVisionModel = proj.preferredVisionModel,
                    powerUserVisionUrl = proj.powerUserVisionUrl,
                    customModelPaths = modelPaths,
                    onLog = { msg -> appendLog(msg) }
                )
                roster.clear()
                roster.addAll(designedRoster)
                roster.forEach { chProf ->
                    appendLog("🎭 Cast [${chProf.name}]: \"${chProf.voiceDesignPrompt.take(72)}\"")
                }
                reportTelemetry(
                    stage = MangaPipelineStage.VOICE_CASTING,
                    detail = "🎭 DESIGNED ${roster.size} CUSTOM QWEN3 VOICE PROMPTS",
                    progress = castingOffset + castingSpan,
                    itemCurrent = roster.size,
                    itemTotal = roster.size,
                    addLog = true
                )
            }
        } finally {
            // Strict LMKD protection: release Gemma 4 LiteRT weights immediately after Vision + Casting pass
            visionEngine.unload()
            appendLog("🧹 Evicted Gemma 4 Vision VRAM -> ready for Qwen3 VoiceDesign")
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

    fun updatePanelCameraEffect(
        projectId: String,
        chapterId: String,
        pageIndex: Int,
        panelId: String,
        cameraEffect: MangaPanelCameraEffect
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
                                    if (pan.id != panelId) pan else pan.copy(cameraEffect = cameraEffect)
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
        videoQuality: MangaVideoQuality? = null,
        enableAtmosphereFx: Boolean? = null,
        includeNonBubbleText: Boolean? = null,
        lockVoicesViaCloning: Boolean? = null,
        powerUserVisionUrl: String? = null,
        preferredVisionModel: MangaVisionModelTier? = null,
        autoCookFullMp4: Boolean? = null,
        pauseOnFlaggedDecisions: Boolean? = null
    ) {
        if (readingOrder != null) {
            prefs.edit().putString("selected_reading_order", readingOrder.name).apply()
            _selectedReadingOrder.value = readingOrder
        }
        if (includeNonBubbleText != null) {
            prefs.edit().putBoolean("include_non_bubble_text", includeNonBubbleText).apply()
            _includeNonBubbleText.value = includeNonBubbleText
        }
        if (lockVoicesViaCloning != null) {
            prefs.edit().putBoolean("lock_voices_via_cloning", lockVoicesViaCloning).apply()
            _lockVoicesViaCloning.value = lockVoicesViaCloning
        }
        mutateProject(projectId) { proj ->
            val nextOrder = readingOrder ?: proj.readingOrder
            val updatedChapters = if (readingOrder != null && readingOrder != proj.readingOrder) {
                proj.chapters.map { ch ->
                    ch.copy(
                        readingOrder = nextOrder,
                        pages = ch.pages.map { pg ->
                            if (pg.panels.isEmpty()) {
                                pg
                            } else {
                                val orderedRects = MangaBfsPanelSlicer.orderPanelsForReadingDirection(
                                    boxes = pg.panels.map { it.panelRect },
                                    readingOrder = nextOrder
                                )
                                val pool = pg.panels.toMutableList()
                                val reordered = orderedRects.mapIndexed { newIdx, rect ->
                                    val pickIdx = pool.indexOfFirst { it.panelRect == rect }.takeIf { it >= 0 } ?: 0
                                    val orig = pool.removeAt(pickIdx)
                                    val (cs, ce) = MangaBfsPanelSlicer.computeKenBurnsPair(orig.panelRect, nextOrder)
                                    orig.copy(panelIndex = newIdx, cameraStartRect = cs, cameraEndRect = ce)
                                }
                                pg.copy(panels = reordered)
                            }
                        }
                    )
                }
            } else {
                proj.chapters
            }
            proj.copy(
                updatedAtEpochMs = System.currentTimeMillis(),
                readingOrder = nextOrder,
                videoAspect = videoAspect ?: proj.videoAspect,
                burnInSubtitles = burnInSubtitles ?: proj.burnInSubtitles,
                videoQuality = videoQuality ?: proj.videoQuality,
                enableAtmosphereFx = enableAtmosphereFx ?: proj.enableAtmosphereFx,
                includeNonBubbleText = includeNonBubbleText ?: proj.includeNonBubbleText,
                lockVoicesViaCloning = lockVoicesViaCloning ?: proj.lockVoicesViaCloning,
                powerUserVisionUrl = powerUserVisionUrl ?: proj.powerUserVisionUrl,
                preferredVisionModel = preferredVisionModel ?: proj.preferredVisionModel,
                autoCookFullMp4 = autoCookFullMp4 ?: proj.autoCookFullMp4,
                pauseOnFlaggedDecisions = pauseOnFlaggedDecisions ?: proj.pauseOnFlaggedDecisions,
                chapters = updatedChapters
            )
        }
    }

    fun autoResolveDecisionsAndProduce(
        projectId: String,
        chapterId: String,
        onComplete: (String) -> Unit
    ) {
        if (_isBusy.value) {
            onComplete("Manga Studio task already running!")
            return
        }
        mutateProject(projectId) { proj ->
            val roster = proj.characters.toMutableList()
            // ✨ NARRATOR IDENTITY LAW (v1.9.9): auto-resolved guesses map unknown speakers to a
            // REAL cast member — never to a synthesized NARRATOR entry (narration is MIKA's
            // hardcoded job, and placeholder names are not cast members). If no real character
            // exists, the UNKNOWN line stays UNKNOWN (no-label subtitle + smart-matcher voice).
            val fallbackChar = roster.firstOrNull { !MangaTextBender.isPlaceholderOrNarratorName(it.name) }

            val updatedChapters = proj.chapters.map { ch ->
                if (ch.id != chapterId) ch
                else ch.copy(
                    pages = ch.pages.map { pg ->
                        pg.copy(
                            panels = pg.panels.map { pan ->
                                pan.copy(
                                    lines = pan.lines.map { ln ->
                                        if (!ln.needsUserDecision) ln
                                        else {
                                            val candidate = ln.candidateSpeakers
                                                .firstNotNullOfOrNull { candName ->
                                                    roster.firstOrNull {
                                                        it.name.equals(candName, ignoreCase = true) &&
                                                            !MangaTextBender.isPlaceholderOrNarratorName(it.name)
                                                    }
                                                }
                                                ?: roster.firstOrNull { !MangaTextBender.isPlaceholderOrNarratorName(it.name) }
                                            if (candidate == null) ln
                                            else ln.copy(
                                                speakerId = candidate.id,
                                                speakerName = candidate.name,
                                                confidence = 0.85f,
                                                needsUserDecision = false,
                                                decisionReason = ""
                                            )
                                        }
                                    }
                                )
                            }
                        )
                    }
                )
            }
            val nextCh = updatedChapters.firstOrNull { it.id == chapterId }
            val syncedRoster = recomputeCharacterLineCounts(
                roster,
                nextCh,
                updatedChapters.filter { it.id != chapterId }
            )
            proj.copy(
                updatedAtEpochMs = System.currentTimeMillis(),
                characters = syncedRoster,
                chapters = updatedChapters
            )
        }
        produceDubbedChapterMp4(projectId, chapterId, onComplete)
    }

    // =========================================================================
    // ✨ PHASE 4 (VOICE FORGE) -> VRAM EVICT -> PHASE 5 (HARDWARE .MP4 MUX) ✨
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
            startTelemetryTicker(
                stage = MangaPipelineStage.VOICE_FORGE,
                detail = "🧹 EVICTING VISION VRAM & PREPARING VOICE CAST..."
            )
            try {
                val destNote = executeProduceDubbedChapterMp4(
                    projectId = projectId,
                    chapterId = chapterId,
                    progressOffset = 0f,
                    progressSpan = 1f
                )
                _overallProgress.value = 1f
                finishTelemetry("🎉 EXPORTED -> $destNote")
                onComplete("Dubbed Manga .MP4 ready: $destNote")
            } catch (ce: CancellationException) {
                throw ce
            } catch (err: Exception) {
                mutateChapterStatus(projectId, chapterId, MangaChapterStatus.FAILED, err.message)
                finishTelemetry("❌ PRODUCTION ERROR: ${err.message}")
                onComplete("Production failed: ${err.message}")
            } finally {
                backendRouter.markIdle()
                _isBusy.value = false
            }
        }
    }

    private suspend fun executeProduceDubbedChapterMp4(
        projectId: String,
        chapterId: String,
        progressOffset: Float,
        progressSpan: Float
    ): String {
        // Guarantee Gemma 4 Vision is completely out of RAM before loading Qwen3 / TTS
        visionEngine.unload()

        var proj = _projects.value.firstOrNull { it.id == projectId }
            ?: throw IllegalStateException("Project not found")
        var chapter = proj.chapters.firstOrNull { it.id == chapterId }
            ?: throw IllegalStateException("Chapter not found")

        val allLinesCount = chapter.totalLineCount.coerceAtLeast(1)
        val audioDir = File(projectDir(projectId), "chapters/$chapterId/audio").apply { mkdirs() }
        val baseProfile = repository.currentProfile
        val sampleRate = PurrDspProcessor.SAMPLE_RATE_HZ
        val designInstalled = repository.state.value.modelStates[Qwen3TtsEngine.SPEC_DESIGN_17B]?.state == DownloadState.INSTALLED

        if (proj.lockVoicesViaCloning && designInstalled) {
            proj = ensureCharacterVoiceClonesLocked(
                proj = proj,
                chapter = chapter,
                progress = progressOffset + progressSpan * 0.02f
            )
            chapter = proj.chapters.firstOrNull { it.id == chapterId } ?: chapter
        }
        val voices = repository.allAvailableVoices()

        mutateChapterStatus(projectId, chapterId, MangaChapterStatus.SYNTHESIZING_AUDIO, null)
        reportTelemetry(
            stage = MangaPipelineStage.VOICE_FORGE,
            detail = when {
                proj.lockVoicesViaCloning && resolvePreferredAutoCloneEngineId() != null ->
                    "🔒 VOICE-CLONE LOCK ACTIVE ($allLinesCount LINES)..."
                designInstalled ->
                    "🎙️ QWEN3 1.7B VOICEDESIGN SYNTHESIS ($allLinesCount LINES)..."
                else ->
                    "🎙️ VOICE SYNTHESIS ($allLinesCount LINES, ${chapter.title})..."
            },
            progress = progressOffset + progressSpan * 0.04f,
            itemCurrent = 0,
            itemTotal = allLinesCount,
            addLog = true
        )

        var linesDone = 0
        val renderedPages = ArrayList<MangaPageEntry>(chapter.pages.size)

        for (page in chapter.pages) {
            val renderedPanels = ArrayList<MangaPanelNode>(page.panels.size)
            for (panel in page.panels) {
                val renderedLines = ArrayList<MangaSpeechLine>(panel.lines.size)
                for (line in panel.lines) {
                    if (!kotlinx.coroutines.currentCoroutineContext().isActive) throw CancellationException("Cancelled")
                    linesDone++
                    val textToSpeak = line.sanitizedText.ifBlank { line.rawText }.trim()
                    if (textToSpeak.isEmpty()) {
                        renderedLines += line
                        continue
                    }
                    if (!proj.includeNonBubbleText && MangaTextBender.isNonBubbleOrCreditNoise(
                            line.speakerName,
                            textToSpeak,
                            trustedSpeaker = proj.characters.any { it.name.equals(line.speakerName.trim(), ignoreCase = true) } ||
                                line.speakerName.equals("NARRATOR", ignoreCase = true)
                        )
                    ) {
                        continue
                    }

                    reportTelemetry(
                        stage = MangaPipelineStage.VOICE_FORGE,
                        detail = "🎙️ FORGING VOICE [${line.speakerName}] ($linesDone/$allLinesCount)...",
                        progress = progressOffset + progressSpan * (0.05f + 0.52f * ((linesDone - 1).toFloat() / allLinesCount.toFloat())),
                        itemCurrent = linesDone,
                        itemTotal = allLinesCount,
                        addLog = true
                    )

                    val isNarratorLine = line.speakerName.equals("NARRATOR", ignoreCase = true)
                    // ✨ NARRATOR IDENTITY LAW (v1.9.9): MIKA's hardcoded narrator voice resolves
                    // in isolation — a legacy storyboard-seeded NARRATOR roster entry can never
                    // influence narration synthesis.
                    val charProfile = if (isNarratorLine) {
                        null
                    } else {
                        proj.characters.firstOrNull {
                            it.id == line.speakerId || it.name.equals(line.speakerName, ignoreCase = true)
                        }
                    }
                    val synthProfile = resolveSynthesisProfileForCharacter(
                        baseProfile = baseProfile,
                        character = charProfile,
                        availableVoices = voices,
                        lockVoicesViaCloning = proj.lockVoicesViaCloning,
                        isNarrator = isNarratorLine
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
                    reportTelemetry(
                        stage = MangaPipelineStage.VOICE_FORGE,
                        detail = "🎙️ VOICED [${line.speakerName}] $linesDone/$allLinesCount (${"%.1f".format(durMs / 1000f)}s via ${outcome.engineSummary.take(22)})",
                        progress = progressOffset + progressSpan * (0.05f + 0.52f * (linesDone.toFloat() / allLinesCount.toFloat())),
                        itemCurrent = linesDone,
                        itemTotal = allLinesCount,
                        addLog = true
                    )
                }
                renderedPanels += panel.copy(lines = renderedLines)
            }
            renderedPages += page.copy(panels = renderedPanels)
        }

        // Release all TTS VRAM before hardware H.264/AAC encoding
        reportTelemetry(
            stage = MangaPipelineStage.VIDEO_MUX,
            detail = "🧹 EVICTING TTS VRAM FOR H.264 + AAC ENCODE...",
            progress = progressOffset + progressSpan * 0.60f,
            itemCurrent = 0,
            itemTotal = 100,
            addLog = true
        )
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
        appendLog(
            "🎬 MUX CONFIG: ${proj.videoAspect.shortLabel} • ${proj.videoQuality.shortLabel} • SUBS=${if (proj.burnInSubtitles) "ON" else "OFF"}"
        )
        var lastMuxStageLog = ""
        val renderRes = MangaMotionVideoMuxer.renderChapterMp4(
            context = context,
            chapter = chapterReadyForMux,
            aspect = proj.videoAspect,
            burnInSubtitles = proj.burnInSubtitles,
            enableAtmosphereFx = proj.enableAtmosphereFx,
            destMp4File = outMp4File,
            quality = proj.videoQuality
        ) { p, label ->
            val prefix = label.substringBefore('(').trim()
            val shouldLog = prefix != lastMuxStageLog
            if (shouldLog) lastMuxStageLog = prefix
            reportTelemetry(
                stage = MangaPipelineStage.VIDEO_MUX,
                detail = "🎬 $label",
                progress = progressOffset + progressSpan * (0.61f + p * 0.39f),
                itemCurrent = (p * 100).toInt(),
                itemTotal = 100,
                addLog = shouldLog
            )
        }

        val finishedChapter = chapterReadyForMux.copy(
            status = MangaChapterStatus.COMPLETED,
            exportedMp4Path = renderRes.internalMp4File.absolutePath,
            publicDownloadPath = renderRes.publicDownloadPath,
            durationSec = renderRes.durationSec,
            errorMessage = null
        )
        mutateChapter(projectId, chapterId) { finishedChapter }

        return renderRes.publicDownloadPath.ifBlank { renderRes.internalMp4File.name }
    }

    private fun resolvePreferredAutoCloneEngineId(): String? {
        val states = repository.state.value.modelStates
        val candidates = listOf(
            Qwen3TtsEngine.SPEC_BASE_CLONE_06B,
            "sub_pocket_fp32",
            "rom_pocket_tts",
            "sub_chatterbox_turbo",
            "sub_zipvoice_full",
            "rom_zipvoice"
        )
        return candidates.firstOrNull { states[it]?.state == DownloadState.INSTALLED }
    }

    /**
     * Synthesizes one clean canonical reference WAV clip per active cast member via Qwen3 1.7B VoiceDesign
     * and locks them into the background voice cloner (Qwen3 0.6B Base preferred) so every chapter line
     * keeps identical timbre without emotional drift across panels, then evicts 1.7B before line synthesis.
     */
    private suspend fun ensureCharacterVoiceClonesLocked(
        proj: MangaSeriesProject,
        chapter: MangaChapterEntry,
        progress: Float
    ): MangaSeriesProject {
        val targetCloneEngineId = resolvePreferredAutoCloneEngineId() ?: return proj
        val activeSpeakers = chapter.pages
            .flatMap { it.panels }
            .flatMap { it.lines }
            .map { it.speakerName.trim().uppercase() }
            .filter { it.isNotEmpty() && it != "UNKNOWN" }
            .toSet()

        val charsToLock = proj.characters.filter { ch ->
            !MangaTextBender.isPlaceholderOrNarratorName(ch.name) &&
                ch.assignedVoiceId.isBlank() &&
                ch.voiceDesignPrompt.isNotBlank() &&
                (activeSpeakers.isEmpty() || ch.name.trim().uppercase() in activeSpeakers || ch.lineCount > 0)
        }
        if (charsToLock.isEmpty()) return proj

        val seedDir = File(projectDir(proj.id), "voice_seeds").apply { mkdirs() }
        val availableNow = repository.allAvailableVoices()
        val updatedCharacters = proj.characters.toMutableList()
        val designBase = repository.currentProfile.copy(
            selectedCartridgeId = "rom_qwen3_custom_06b",
            preferredBackend = BackendType.QWEN3_NATIVE,
            primaryVoiceId = "qwen3_design_custom",
            baseSpeed = 1.0f,
            basePitch = 1.0f,
            // ✨ DRY SEED LAW (v1.9.7): the clone reference must be clean studio audio — the
            // active profile's EQ/reverb would otherwise be baked into every cloned line's anchor.
            enableDsp = false,
            dspConfig = DspConfig(
                warmthEqDb = 0f,
                presenceEqDb = 0f,
                reverbWetMix = 0f,
                effectPreset = AcousticEffectPreset.NONE,
                enableSentenceTaper = false,
                antiPopFadeInMs = 0,
                normalizePeak = false
            )
        )
        var synthesizedAnySeed = false

        for ((idx, ch) in updatedCharacters.withIndex()) {
            if (ch !in charsToLock) continue
            if (!kotlinx.coroutines.currentCoroutineContext().isActive) throw CancellationException("Cancelled")

            val promptHash = Integer.toHexString("${ch.voiceDesignPrompt.trim()}|$targetCloneEngineId".hashCode())
            val existingCloneValid = ch.autoCloneVoiceId.isNotBlank() &&
                ch.autoClonePromptHash == promptHash &&
                File(ch.autoCloneSeedWavPath).let { it.isFile && it.length() > 2048L } &&
                availableNow.any { it.id == ch.autoCloneVoiceId }
            if (existingCloneValid) continue

            // ✨ EXPRESSIVE SEED LAW (v1.9.7): anchor the clone on the character's OWN transcribed
            // lines (register & excitement variety) instead of one generic neutral sentence — the
            // 0.6B cloner inherits the seed's prosody, so a flat seed meant flat dubbed voices.
            val seedTranscript = MangaTextBender.composeVoiceSeedTranscript(
                chapter.pages.asSequence()
                    .flatMap { it.panels.asSequence() }
                    .flatMap { it.lines.asSequence() }
                    .filter { line -> line.speakerName.trim().equals(ch.name.trim(), ignoreCase = true) }
                    .mapNotNull { line -> line.sanitizedText.ifBlank { line.rawText }.trim().takeIf(String::isNotEmpty) }
                    .toList()
            )

            reportTelemetry(
                stage = MangaPipelineStage.VOICE_FORGE,
                detail = "🔒 DESIGNING & LOCKING VOICE [${ch.name}]...",
                progress = progress,
                itemCurrent = idx + 1,
                itemTotal = updatedCharacters.size,
                addLog = true
            )

            val designProfile = designBase.copy(voiceDesignPrompt = ch.voiceDesignPrompt)
            val outcome = backendRouter.synthesizeSpeechChunk(
                chunk = PurrChunk.Speech(
                    text = seedTranscript,
                    role = if (ch.name.equals("NARRATOR", ignoreCase = true)) SegmentRole.NARRATION else SegmentRole.DIALOGUE,
                    targetVoiceId = "qwen3_design_custom",
                    speakingRate = 1.0f,
                    pitch = 1.0f,
                    volumeGain = 1.0f,
                    sentiment = SentimentResult()
                ),
                profile = designProfile
            )
            synthesizedAnySeed = true
            val pcmFloat = outcome.pcmFloat24k
            if (pcmFloat.isEmpty()) {
                appendLog("⚠️ Seed clip empty for [${ch.name}]; keeping direct VoiceDesign fallback")
                continue
            }

            val seedFile = File(seedDir, "${sanitizeSlug(ch.id)}_${promptHash}.wav")
            val pcm16 = PurrDspProcessor.float32ToPcm16Le(pcmFloat)
            writePcm16MonoWav(seedFile, pcm16, pcm16.size, PurrDspProcessor.SAMPLE_RATE_HZ)

            // NOTE: `family` is only consumed by the Sherpa clone lanes (Pocket/ZipVoice asset
            // checks & engine labels). Qwen3 Base clones route via `resolvedCloneEngineId` ->
            // QWEN3_NATIVE, so the ZIPVOICE stamp below is inert bookkeeping for the Qwen3 lane;
            // do NOT route Qwen3 clones through Sherpa based on this family.
            val family = if (targetCloneEngineId.contains("zipvoice") || targetCloneEngineId == Qwen3TtsEngine.SPEC_BASE_CLONE_06B) {
                SherpaModelFamily.ZIPVOICE
            } else {
                SherpaModelFamily.POCKET_TTS
            }
            val cloneId = "manga_auto_${sanitizeSlug(proj.id)}_${sanitizeSlug(ch.id)}"
            repository.upsertBackgroundClonedVoice(
                ClonedVoiceProfile(
                    id = cloneId,
                    displayName = "Manga Cast // ${ch.name}",
                    family = family,
                    gender = ch.gender,
                    referenceWavPath = seedFile.absolutePath,
                    referenceTranscript = seedTranscript,
                    tags = listOf("manga", "autoclone", targetCloneEngineId),
                    cloneEngineId = targetCloneEngineId
                )
            )
            updatedCharacters[idx] = ch.copy(
                autoCloneVoiceId = cloneId,
                autoCloneSeedWavPath = seedFile.absolutePath,
                autoClonePromptHash = promptHash
            )
            appendLog(
                "🔒 Locked [${ch.name}] seed -> $targetCloneEngineId " +
                    "(${pcmFloat.size / PurrDspProcessor.SAMPLE_RATE_HZ}s) seed=\"${seedTranscript.take(44)}…\""
            )
        }

        if (synthesizedAnySeed) {
            // Unload 1.7B VoiceDesign once before switching to the cloning backend for chapter lines
            backendRouter.evictAllVram()
        }

        val updatedProj = proj.copy(
            updatedAtEpochMs = System.currentTimeMillis(),
            characters = updatedCharacters
        )
        mutateProject(proj.id) { current ->
            current.copy(updatedAtEpochMs = System.currentTimeMillis(), characters = updatedCharacters)
        }
        return updatedProj
    }

    /**
     * ✨ CARTRIDGE GUARD (v1.9.7) ✨
     * Clone descriptors live in persisted state regardless of cartridge installation, and the
     * router deliberately never hijacks clone requests with CORE_OS speech — so routing to an
     * uninstalled clone yields EMPTY audio (silent subtitle ghosts). Detect it here and fall
     * through to the direct VoiceDesign / matcher fallbacks instead.
     */
    private fun cloneDescriptorUnavailable(descriptor: VoiceDescriptor): Boolean {
        if (!descriptor.isCloned) return false
        val requiredSpecId = descriptor.requiredSubCartridgeId ?: descriptor.cartridgeId
        return repository.state.value.modelStates[requiredSpecId]?.state != DownloadState.INSTALLED
    }

    fun resolveSynthesisProfileForCharacter(
        baseProfile: PurrProfile,
        character: MangaCharacterProfile?,
        availableVoices: List<VoiceDescriptor>,
        lockVoicesViaCloning: Boolean = true,
        isNarrator: Boolean = false
    ): PurrProfile {
        // 0. ✨ MIKA IS THE MANGA DUB NARRATOR (v1.9.8): narration lines resolve to M.I.K.A.'s
        // hardcoded Qwen3 1.7B VoiceDesign voice directly — no roster entry, no casting-pass
        // override, no clone lock. Falls through to the smart matcher only if the 1.7B
        // VoiceDesign cartridge isn't installed.
        if (isNarrator) {
            val narratorDesignInstalled = repository.state.value.modelStates[Qwen3TtsEngine.SPEC_DESIGN_17B]?.state == DownloadState.INSTALLED
            if (narratorDesignInstalled) {
                return baseProfile.copy(
                    primaryVoiceId = "qwen3_design_custom",
                    preferredBackend = BackendType.QWEN3_NATIVE,
                    selectedCartridgeId = "rom_qwen3_custom_06b",
                    voiceDesignPrompt = MangaTextBender.MIKA_NARRATOR_VOICE_PROMPT,
                    baseSpeed = baseProfile.baseSpeed.coerceIn(0.6f, 1.8f),
                    basePitch = baseProfile.basePitch.coerceIn(0.88f, 1.12f)
                )
            }
        }
        if (character == null) return baseProfile

        // 1. Explicit assigned voice (including manual Clone Studio voices)
        if (character.assignedVoiceId.isNotBlank()) {
            val matched = availableVoices.firstOrNull { it.id == character.assignedVoiceId }
            if (matched != null && !cloneDescriptorUnavailable(matched)) {
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

        // 2. Background VoiceDesign -> Qwen Voice Clone lock (prevents vocal drift across emotions/panels)
        if (lockVoicesViaCloning && character.autoCloneVoiceId.isNotBlank()) {
            val autoCloneMatched = availableVoices.firstOrNull { it.id == character.autoCloneVoiceId }
            if (autoCloneMatched != null && !cloneDescriptorUnavailable(autoCloneMatched)) {
                return baseProfile.copy(
                    primaryVoiceId = autoCloneMatched.id,
                    preferredBackend = autoCloneMatched.engine,
                    selectedCartridgeId = autoCloneMatched.cartridgeId,
                    sherpaFamily = autoCloneMatched.sherpaFamily ?: baseProfile.sherpaFamily,
                    baseSpeed = (baseProfile.baseSpeed * character.speedScale).coerceIn(0.6f, 1.8f),
                    // ✨ NO PITCH RE-SHIFT (v1.9.7): the seed clip already carries the designed
                    // register from its VoiceDesign prompt; re-shifting by pitchScale here
                    // double-applies the design and adds cubic-interpolation artifacts on top
                    // of the cloned timbre. Speed stays (WSOLA is far gentler than pitch shifting).
                    basePitch = baseProfile.basePitch.coerceIn(0.88f, 1.12f)
                )
            }
        }

        // 3. Direct Qwen3 1.7B VoiceDesign fallback (when clone lock is OFF or no cloner cartridge is installed)
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

    private fun extractCbzImages(
        uri: Uri,
        destDir: File,
        displayName: String = "chapter.cbz"
    ): List<File> {
        val tempArchive = File.createTempFile("purr_cbz_import_", ".tmp", context.cacheDir)
        try {
            val stream = context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("Android denied read access to '$displayName'. Try selecting the file from internal Downloads.")
            stream.use { input ->
                FileOutputStream(tempArchive).use { output ->
                    input.copyTo(output, bufferSize = 65536)
                }
            }
            if (!tempArchive.exists() || tempArchive.length() == 0L) {
                throw IllegalStateException("Selected file '$displayName' copied as 0 bytes.")
            }
            reportTelemetry(
                stage = MangaPipelineStage.EXTRACT_AND_SLICE,
                detail = "COPIED ${tempArchive.length() / 1024} KB -> PARSING ARCHIVE ($displayName)...",
                progress = 0.06f,
                addLog = true
            )
            return MangaCbzExtractorUtils.extractArchiveFromFile(
                archiveFile = tempArchive,
                destDir = destDir,
                displayName = displayName
            )
        } finally {
            runCatching { tempArchive.delete() }
        }
    }

    private fun sliceSinglePageFromDisk(
        pageIdx: Int,
        imgFile: File,
        readingOrder: MangaReadingOrder,
        maxPanels: Int = MangaBfsPanelSlicer.AUTO_MAX_PANELS_PER_PAGE
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

        val rects = MangaBfsPanelSlicer.slicePanelsFromLuminanceGrid(luminance, gridW, gridH, readingOrder, maxPanels)
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
}
