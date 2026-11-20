package com.projectmika.purrtts.engine.audiobook

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.projectmika.purrtts.core.audiobook.AudiobookChapter
import com.projectmika.purrtts.core.audiobook.AudiobookProject
import com.projectmika.purrtts.core.audiobook.ChapterRenderStatus
import com.projectmika.purrtts.core.audiobook.EbookParser
import com.projectmika.purrtts.core.purr.PurrChunk
import com.projectmika.purrtts.core.purr.PurrDspProcessor
import com.projectmika.purrtts.core.purr.PurrProfileRepository
import com.projectmika.purrtts.core.purr.PurrTextProcessor
import com.projectmika.purrtts.engine.TtsBackendRouter
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
 * ✨ MIKA'S RESUMABLE AUDIOBOOK CONSTRUCTOR MANAGER (TOOL-07) ✨
 *
 * Manages persistent ebook projects under `filesDir/audiobook_projects/<bookId>/`,
 * sequential chapter-by-chapter neural synthesis with per-chunk checkpoints (surviving
 * Pause, Cancel, and full app restarts), and full `.m4b` chaptered audiobook combining.
 */
class AudiobookConstructorManager(
    private val context: Context,
    private val repository: PurrProfileRepository,
    private val backendRouter: TtsBackendRouter
) {
    enum class ChapterFilterMode { ALL, STORY_ONLY, NONE }

    private val projectsRoot = File(context.filesDir, "audiobook_projects").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _projects = MutableStateFlow<List<AudiobookProject>>(emptyList())
    val projects: StateFlow<List<AudiobookProject>> = _projects.asStateFlow()

    private val _activeProjectId = MutableStateFlow<String?>(null)
    val activeProjectId: StateFlow<String?> = _activeProjectId.asStateFlow()

    private val _isBuilding = MutableStateFlow(false)
    val isBuilding: StateFlow<Boolean> = _isBuilding.asStateFlow()

    private val _isCombining = MutableStateFlow(false)
    val isCombining: StateFlow<Boolean> = _isCombining.asStateFlow()

    private val _statusLabel = MutableStateFlow("IDLE // IMPORT AN EBOOK TO BEGIN")
    val statusLabel: StateFlow<String> = _statusLabel.asStateFlow()

    private val _overallProgress = MutableStateFlow(0f)
    val overallProgress: StateFlow<Float> = _overallProgress.asStateFlow()

    @Volatile
    private var pauseRequested: Boolean = false
    private var buildJob: Job? = null
    private var combineJob: Job? = null

    init {
        reloadAllProjects()
    }

    fun reloadAllProjects() {
        val loaded = projectsRoot.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { dir ->
                val file = File(dir, "project.json")
                if (!file.exists()) return@mapNotNull null
                runCatching {
                    val raw = json.decodeFromString<AudiobookProject>(file.readText())
                    // Heal any chapter left IN_PROGRESS by a prior process kill -> PENDING so user can resume cleanly
                    val healed = raw.chapters.map { ch ->
                        if (ch.status == ChapterRenderStatus.IN_PROGRESS) ch.copy(status = ChapterRenderStatus.PENDING)
                        else ch
                    }
                    if (healed != raw.chapters) {
                        val updated = raw.copy(chapters = healed)
                        persistProject(updated)
                        updated
                    } else {
                        raw
                    }
                }.getOrNull()
            }
            ?.sortedByDescending { it.createdAtEpochMs }
            .orEmpty()

        _projects.value = loaded
        if (_activeProjectId.value == null || loaded.none { it.id == _activeProjectId.value }) {
            _activeProjectId.value = loaded.firstOrNull()?.id
        }
        loaded.firstOrNull { it.id == _activeProjectId.value }?.let { updateProgressSummary(it) }
    }

    fun selectProject(projectId: String) {
        _activeProjectId.value = projectId
        _projects.value.firstOrNull { it.id == projectId }?.let { updateProgressSummary(it) }
    }

    suspend fun importEbook(uri: Uri): Result<AudiobookProject> = runCatching {
        val (fileName, mimeType) = resolveFileNameAndMime(uri)
        val format = EbookParser.inferFormat(fileName, mimeType)

        val parsed = context.contentResolver.openInputStream(uri)?.use { stream ->
            when (format) {
                "pdf" -> EbookPdfExtractor.parsePdf(stream, fallbackTitle = fileName.substringBeforeLast('.'))
                else -> EbookParser.parseNonPdfStream(stream, fileName, mimeType)
            }
        } ?: throw IllegalStateException("Could not open selected ebook file")

        val safeSlug = parsed.title.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(24).ifEmpty { "book" }
        val projectId = "book_${safeSlug}_${System.currentTimeMillis()}"
        val project = AudiobookProject(
            id = projectId,
            title = parsed.title,
            author = parsed.author,
            sourceFormat = parsed.sourceFormat,
            sourceFileName = fileName,
            createdAtEpochMs = System.currentTimeMillis(),
            chapters = parsed.chapters
        )
        persistAndReplace(project)
        _activeProjectId.value = projectId
        updateProgressSummary(project)
        project
    }

    fun deleteProject(projectId: String) {
        if (_isBuilding.value && _activeProjectId.value == projectId) {
            pauseBuild()
        }
        runCatching { projectDir(projectId).deleteRecursively() }
        val remaining = _projects.value.filterNot { it.id == projectId }
        _projects.value = remaining
        if (_activeProjectId.value == projectId) {
            _activeProjectId.value = remaining.firstOrNull()?.id
        }
    }

    fun toggleChapterIncluded(projectId: String, chapterIndex: Int, included: Boolean) {
        mutateProject(projectId) { proj ->
            proj.copy(
                chapters = proj.chapters.map { ch ->
                    if (ch.index == chapterIndex) ch.copy(included = included) else ch
                }
            )
        }
    }

    fun applyChapterFilter(projectId: String, mode: ChapterFilterMode) {
        mutateProject(projectId) { proj ->
            proj.copy(
                chapters = proj.chapters.map { ch ->
                    val inc = when (mode) {
                        ChapterFilterMode.ALL -> true
                        ChapterFilterMode.STORY_ONLY -> !EbookParser.isLikelyNonStorySection(ch.title, ch.wordCount)
                        ChapterFilterMode.NONE -> false
                    }
                    ch.copy(included = inc)
                }
            )
        }
    }

    fun renameChapter(projectId: String, chapterIndex: Int, newTitle: String) {
        val clean = newTitle.trim()
        if (clean.isEmpty()) return
        mutateProject(projectId) { proj ->
            proj.copy(
                chapters = proj.chapters.map { ch ->
                    if (ch.index == chapterIndex) ch.copy(title = clean) else ch
                }
            )
        }
    }

    fun updateProjectOptions(
        projectId: String,
        title: String? = null,
        author: String? = null,
        targetChunkChars: Int? = null,
        paragraphPauseMs: Int? = null,
        chapterGapMs: Int? = null,
        normalizeLoudness: Boolean? = null,
        smoothTransitions: Boolean? = null
    ) {
        mutateProject(projectId) { proj ->
            proj.copy(
                title = title ?: proj.title,
                author = author ?: proj.author,
                targetChunkChars = targetChunkChars ?: proj.targetChunkChars,
                paragraphPauseMs = paragraphPauseMs ?: proj.paragraphPauseMs,
                chapterGapMs = chapterGapMs ?: proj.chapterGapMs,
                normalizeLoudness = normalizeLoudness ?: proj.normalizeLoudness,
                smoothTransitions = smoothTransitions ?: proj.smoothTransitions
            )
        }
    }

    fun retryChapter(projectId: String, chapterIndex: Int) {
        runCatching { chunkCacheDir(projectId, chapterIndex).deleteRecursively() }
        mutateProject(projectId) { proj ->
            proj.copy(
                chapters = proj.chapters.map { ch ->
                    if (ch.index == chapterIndex) {
                        ch.copy(
                            status = ChapterRenderStatus.PENDING,
                            completedChunks = 0,
                            totalChunks = 0,
                            errorMessage = null
                        )
                    } else {
                        ch
                    }
                }
            )
        }
    }

    fun startOrResumeBuild(projectId: String, onMessage: (String) -> Unit) {
        if (_isBuilding.value || _isCombining.value) {
            onMessage("Audiobook task already running!")
            return
        }
        val initialProj = _projects.value.firstOrNull { it.id == projectId } ?: return
        if (initialProj.includedChapters.isEmpty()) {
            onMessage("Select at least one chapter to construct!")
            return
        }
        if (initialProj.allIncludedCompleted) {
            onMessage("All selected chapters are complete — ready to combine into .M4B!")
            return
        }

        pauseRequested = false
        buildJob?.cancel()
        buildJob = scope.launch {
            _isBuilding.value = true
            try {
                while (isActive && !pauseRequested) {
                    val proj = _projects.value.firstOrNull { it.id == projectId } ?: break
                    val nextChapter = proj.chapters.firstOrNull {
                        it.included && (it.status == ChapterRenderStatus.PENDING || it.status == ChapterRenderStatus.FAILED)
                    } ?: break

                    renderSingleChapter(proj, nextChapter)
                    val refreshed = _projects.value.firstOrNull { it.id == projectId }
                    if (refreshed != null) updateProgressSummary(refreshed)
                }

                val latest = _projects.value.firstOrNull { it.id == projectId }
                if (pauseRequested) {
                    _statusLabel.value = "PAUSED // PROGRESS SAVED — TAP RESUME ANYTIME"
                    onMessage("Paused! Completed chapters & chunks saved.")
                } else if (latest?.allIncludedCompleted == true) {
                    _statusLabel.value = "ALL CHAPTERS COMPLETE // READY TO COMBINE .M4B"
                    onMessage("All selected chapters complete! Ready to combine .M4B!")
                } else {
                    _statusLabel.value = "BUILD HALTED // CHECK CHAPTER ERRORS"
                }
            } catch (_: CancellationException) {
                _statusLabel.value = "PAUSED // PROGRESS SAVED — TAP RESUME ANYTIME"
            } catch (err: Exception) {
                _statusLabel.value = "BUILD ERROR // ${err.message}"
                onMessage("Audiobook build error: ${err.message}")
            } finally {
                _isBuilding.value = false
                backendRouter.markIdle()
            }
        }
    }

    fun pauseBuild() {
        pauseRequested = true
        buildJob?.cancel()
        _isBuilding.value = false
        _statusLabel.value = "PAUSED // PROGRESS SAVED — TAP RESUME ANYTIME"
    }

    fun combineIntoFullAudiobook(projectId: String, onMessage: (String) -> Unit) {
        if (_isBuilding.value || _isCombining.value) {
            onMessage("Finish or pause the active chapter build first!")
            return
        }
        val proj = _projects.value.firstOrNull { it.id == projectId } ?: return
        val readyChapters = proj.chapters.filter {
            it.included && it.status == ChapterRenderStatus.COMPLETED && it.renderedWavPath.isNotBlank()
        }
        if (readyChapters.isEmpty()) {
            onMessage("No completed chapters available to combine yet!")
            return
        }

        combineJob?.cancel()
        combineJob = scope.launch {
            _isCombining.value = true
            _overallProgress.value = 0.02f
            _statusLabel.value = "PREPARING .M4B AUDIOBOOK MUXER..."
            try {
                val safeName = proj.title
                    .replace(Regex("[^a-zA-Z0-9._-]+"), "_")
                    .trim('_')
                    .ifEmpty { "PURR_Audiobook" }
                    .take(48)
                val destM4b = File(projectDir(projectId), "$safeName.m4b")
                val res = M4bAudiobookMuxer.combineChaptersToM4b(
                    context = context,
                    bookTitle = proj.title,
                    author = proj.author,
                    chapters = readyChapters,
                    chapterGapMs = proj.chapterGapMs,
                    destM4bFile = destM4b,
                    onProgress = { frac, msg ->
                        _overallProgress.value = frac
                        _statusLabel.value = msg
                    }
                )
                mutateProject(projectId) { cur ->
                    cur.copy(
                        combinedM4bPath = res.internalM4bFile.absolutePath,
                        combinedPublicPath = res.publicDownloadPath,
                        combinedDurationMs = res.totalDurationMs
                    )
                }
                val locationNote = res.publicDownloadPath.ifBlank { res.internalM4bFile.name }
                _statusLabel.value = "READY // $locationNote (${res.chapterCount} CHAPTERS)"
                onMessage("[M4B READY] $locationNote")
            } catch (err: Exception) {
                _statusLabel.value = "MUX ERROR // ${err.message}"
                onMessage("M4B combine error: ${err.message}")
            } finally {
                _isCombining.value = false
            }
        }
    }

    fun exportChapterToDownloads(projectId: String, chapterIndex: Int, onMessage: (String) -> Unit) {
        scope.launch {
            val proj = _projects.value.firstOrNull { it.id == projectId } ?: return@launch
            val ch = proj.chapters.firstOrNull { it.index == chapterIndex } ?: return@launch
            val wav = File(ch.renderedWavPath)
            if (!wav.exists()) {
                onMessage("Render this chapter first!")
                return@launch
            }
            val safeBook = proj.title.replace(Regex("[^a-zA-Z0-9._-]+"), "_").trim('_').take(28).ifEmpty { "Book" }
            val safeCh = ch.title.replace(Regex("[^a-zA-Z0-9._-]+"), "_").trim('_').take(28).ifEmpty { "Chapter_${chapterIndex + 1}" }
            val outName = "${safeBook}_Ch%03d_%s.wav".format(chapterIndex + 1, safeCh)
            val savedPath = M4bAudiobookMuxer.copyAudiobookFileToPublicDownloads(
                context = context,
                sourceFile = wav,
                fileName = outName,
                mimeType = "audio/wav",
                subFolder = "Audiobooks/$safeBook"
            )
            onMessage(if (savedPath.isNotBlank()) "[SAVED] $savedPath" else "Saved inside app storage")
        }
    }

    private suspend fun renderSingleChapter(proj: AudiobookProject, chapter: AudiobookChapter) {
        val activeProfile = repository.currentProfile
        val cloned = repository.state.value.clonedVoices
        val isCbVoice = activeProfile.primaryVoiceId.startsWith("chatterbox_") ||
            cloned.any {
                (it.id == activeProfile.primaryVoiceId || (activeProfile.enableDualVoiceSplit && it.id == activeProfile.dialogueVoiceId)) &&
                    it.resolvedCloneEngineId == "sub_chatterbox_turbo"
            }

        val chunks = PurrTextProcessor.buildExportChunks(
            rawText = chapter.text,
            profile = activeProfile,
            customRules = repository.state.value.customPhoneticRules,
            purrModuleActive = repository.isPurrEngineModuleActive(),
            symbolShieldModuleActive = repository.isSymbolShieldModuleActive(),
            targetChunkCharsOverride = proj.targetChunkChars,
            paragraphPauseMs = proj.paragraphPauseMs,
            isChatterboxVoice = isCbVoice,
            clonedVoices = cloned
        )

        val speechChunksTotal = chunks.count { it is PurrChunk.Speech }.coerceAtLeast(1)
        val cacheDir = chunkCacheDir(proj.id, chapter.index).apply { mkdirs() }
        val sampleRate = PurrDspProcessor.SAMPLE_RATE_HZ
        val segments = ArrayList<PurrDspProcessor.ExportAudioSegment>(chunks.size)
        var speechIdx = 0

        updateChapterState(proj.id, chapter.index) {
            it.copy(
                status = ChapterRenderStatus.IN_PROGRESS,
                totalChunks = speechChunksTotal,
                errorMessage = null
            )
        }

        try {
            for (chunk in chunks) {
                if (pauseRequested) {
                    updateChapterState(proj.id, chapter.index) {
                        it.copy(status = ChapterRenderStatus.PENDING, completedChunks = speechIdx)
                    }
                    return
                }
                when (chunk) {
                    is PurrChunk.Silence -> {
                        val count = ((chunk.durationMs * sampleRate) / 1000).coerceAtLeast(1)
                        segments += PurrDspProcessor.ExportAudioSegment(FloatArray(count), isSilence = true)
                    }
                    is PurrChunk.Speech -> {
                        val cachedChunkFile = File(cacheDir, "chunk_%05d.f32".format(speechIdx))
                        val pcmFloat: FloatArray = if (cachedChunkFile.exists() && cachedChunkFile.length() >= 4L) {
                            readFloatArrayLe(cachedChunkFile)
                        } else {
                            val outcome = backendRouter.synthesizeSpeechChunk(chunk, activeProfile)
                            if (outcome.pcmFloat24k.isEmpty()) {
                                val err = outcome.engineSummary.ifBlank { "No audio produced for chunk $speechIdx" }
                                throw IllegalStateException(err)
                            }
                            writeFloatArrayLeAtomic(cachedChunkFile, outcome.pcmFloat24k)
                            outcome.pcmFloat24k
                        }

                        segments += PurrDspProcessor.ExportAudioSegment(pcmFloat, isSilence = false)
                        speechIdx++

                        updateChapterState(proj.id, chapter.index) {
                            it.copy(completedChunks = speechIdx, totalChunks = speechChunksTotal)
                        }
                        val incCount = proj.includedChapters.size.coerceAtLeast(1)
                        val doneBefore = proj.includedChapters.count { it.status == ChapterRenderStatus.COMPLETED }
                        val chFrac = speechIdx.toFloat() / speechChunksTotal.toFloat()
                        _overallProgress.value = ((doneBefore + chFrac) / incCount.toFloat()).coerceIn(0.01f, 0.99f)
                        _statusLabel.value = "CH ${doneBefore + 1}/$incCount (${chapter.title.take(20)}) • $speechIdx/$speechChunksTotal"
                    }
                }
            }

            if (pauseRequested) {
                updateChapterState(proj.id, chapter.index) {
                    it.copy(status = ChapterRenderStatus.PENDING, completedChunks = speechIdx)
                }
                return
            }

            _statusLabel.value = "MASTERING CHAPTER ${chapter.index + 1}..."
            val mastered = PurrDspProcessor.stitchAndMasterAudiobook(
                segments = segments,
                sampleRate = sampleRate,
                normalizeLoudness = proj.normalizeLoudness,
                smoothTransitions = proj.smoothTransitions
            )
            val pcm16 = PurrDspProcessor.float32ToPcm16Le(mastered)
            val chaptersDir = File(projectDir(proj.id), "chapters").apply { mkdirs() }
            val wavFile = File(chaptersDir, "ch_%03d.wav".format(chapter.index))
            val tmpWav = File(chaptersDir, "ch_%03d.wav.tmp".format(chapter.index))
            writePcm16MonoWav(tmpWav, pcm16, pcm16.size, sampleRate)
            if (wavFile.exists()) wavFile.delete()
            tmpWav.renameTo(wavFile)
            runCatching { cacheDir.deleteRecursively() }

            val durMs = ((mastered.size.toLong() * 1000L) / sampleRate.toLong()).coerceAtLeast(100L)
            updateChapterState(proj.id, chapter.index) {
                it.copy(
                    status = ChapterRenderStatus.COMPLETED,
                    renderedWavPath = wavFile.absolutePath,
                    renderedDurationMs = durMs,
                    completedChunks = speechChunksTotal,
                    totalChunks = speechChunksTotal,
                    errorMessage = null
                )
            }
        } catch (err: Exception) {
            if (pauseRequested || err is CancellationException) {
                updateChapterState(proj.id, chapter.index) {
                    it.copy(status = ChapterRenderStatus.PENDING, completedChunks = speechIdx)
                }
                if (err is CancellationException && !pauseRequested) throw err
            } else {
                updateChapterState(proj.id, chapter.index) {
                    it.copy(
                        status = ChapterRenderStatus.FAILED,
                        completedChunks = speechIdx,
                        errorMessage = err.message ?: "Synthesis error"
                    )
                }
                throw err
            }
        }
    }

    private fun updateChapterState(
        projectId: String,
        chapterIndex: Int,
        transform: (AudiobookChapter) -> AudiobookChapter
    ) {
        mutateProject(projectId) { proj ->
            proj.copy(
                chapters = proj.chapters.map { ch ->
                    if (ch.index == chapterIndex) transform(ch) else ch
                }
            )
        }
    }

    private fun mutateProject(projectId: String, transform: (AudiobookProject) -> AudiobookProject) {
        synchronized(this) {
            val current = _projects.value.firstOrNull { it.id == projectId } ?: return
            val updated = transform(current)
            persistAndReplace(updated)
        }
    }

    private fun persistAndReplace(project: AudiobookProject) {
        persistProject(project)
        val next = _projects.value.filterNot { it.id == project.id } + project
        _projects.value = next.sortedByDescending { it.createdAtEpochMs }
    }

    private fun persistProject(project: AudiobookProject) {
        runCatching {
            val dir = projectDir(project.id).apply { mkdirs() }
            val target = File(dir, "project.json")
            val tmp = File(dir, "project.json.tmp")
            tmp.writeText(json.encodeToString(project))
            if (target.exists()) target.delete()
            tmp.renameTo(target)
        }
    }

    private fun updateProgressSummary(project: AudiobookProject) {
        val inc = project.includedChapters
        if (inc.isEmpty()) {
            _overallProgress.value = 0f
            if (!_isBuilding.value && !_isCombining.value) {
                _statusLabel.value = "SELECT CHAPTERS TO CONSTRUCT"
            }
            return
        }
        val completed = inc.count { it.status == ChapterRenderStatus.COMPLETED }
        _overallProgress.value = (completed.toFloat() / inc.size.toFloat()).coerceIn(0f, 1f)
        if (!_isBuilding.value && !_isCombining.value) {
            _statusLabel.value = when {
                project.combinedPublicPath.isNotBlank() ->
                    "M4B EXPORTED // ${completed}/${inc.size} CHAPTERS"
                completed == inc.size ->
                    "ALL ${inc.size} CHAPTERS COMPLETE // READY TO COMBINE .M4B"
                completed > 0 || inc.any { it.completedChunks > 0 } ->
                    "PAUSED // $completed / ${inc.size} CHAPTERS DONE — TAP RESUME"
                else ->
                    "READY // ${inc.size} SELECTED CHAPTERS (${inc.sumOf { it.wordCount }} WORDS)"
            }
        }
    }

    private fun projectDir(projectId: String): File = File(projectsRoot, projectId)
    private fun chunkCacheDir(projectId: String, chapterIndex: Int): File =
        File(projectDir(projectId), "chapters/ch_%03d_chunks".format(chapterIndex))

    private fun resolveFileNameAndMime(uri: Uri): Pair<String, String?> {
        var name: String? = null
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) name = c.getString(idx)
                }
            }
        }
        val resolvedName = name?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "ebook_${System.currentTimeMillis()}.epub"
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        return resolvedName to mime
    }

    private fun writeFloatArrayLeAtomic(target: File, data: FloatArray) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        val buf = ByteBuffer.allocate(data.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.asFloatBuffer().put(data)
        FileOutputStream(tmp).use { it.write(buf.array()) }
        if (target.exists()) target.delete()
        tmp.renameTo(target)
    }

    private fun readFloatArrayLe(file: File): FloatArray {
        val bytes = file.readBytes()
        val count = bytes.size / 4
        val out = FloatArray(count)
        ByteBuffer.wrap(bytes, 0, count * 4).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out)
        return out
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
