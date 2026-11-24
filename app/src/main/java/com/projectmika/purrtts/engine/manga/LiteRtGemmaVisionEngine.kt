package com.projectmika.purrtts.engine.manga

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.Base64
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.InputData
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.SessionConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import com.projectmika.purrtts.core.manga.MangaCharacterProfile
import com.projectmika.purrtts.core.manga.MangaNormalizedRect
import com.projectmika.purrtts.core.manga.MangaReadingOrder
import com.projectmika.purrtts.core.manga.MangaVisionModelTier
import com.projectmika.purrtts.core.manga.MangaVisionResponseParser
import com.projectmika.purrtts.core.purr.VoiceGender
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * ✨ GEMMA 4 E2B/E4B LITERT-LM MULTIMODAL VISION & QWEN VOICEDESIGN DIRECTOR (TOOL-11 / SUB-11V / SUB-11X) ✨
 *
 * Uses direct compile-time bindings to `com.google.ai.edge.litertlm` (`litertlm-android:0.19.1`)
 * with explicit `visionBackend` + `maxNumImages = 1` + `ThinkingConfig(enableThinking = true)`:
 * - Phase 2A: Scans cyan-boxed `[P1..PN]` Set-of-Mark panels for OCR dialogue, speakers, thoughts & mood.
 * - Phase 2B: While Gemma 4 remains resident in RAM, crafts specialized Qwen3 1.7B VoiceDesign
 *   acoustic casting prompts tailored to each character's visual identity & dialogue lines.
 * - Strictly evicts LiteRT-LM memory via `unload()` before TTS synthesis starts.
 */
class LiteRtGemmaVisionEngine(private val context: Context) {

    companion object {
        private const val TAG = "PurrGemmaVision"
        const val GEMMA_VISION_SPEC_ID = "sub_gemma4_e2b_vision"
        const val GEMMA_E2B_SPEC_ID = "sub_gemma4_e2b_vision"
        const val GEMMA_E4B_SPEC_ID = "sub_gemma4_e4b_vision"
        const val GEMMA_PRIMARY_FILE = "gemma-4-E2B-it-gpu.litertlm"
        const val GEMMA_E4B_FILE = "gemma-4-E4B-it-gpu.litertlm"
    }

    private val mutex = Mutex()
    private var loadedModelPath: String? = null
    private var engineHandle: Engine? = null
    private var activeBackendLabel: String = "NONE"
    private var activeMaxImages: Int = 1
    private var activeMaxTokens: Int = 4096

    @Volatile
    var lastEngineError: String? = null
        private set

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

    fun resolveInstalledGemmaModelFile(
        preferredTier: MangaVisionModelTier = MangaVisionModelTier.GEMMA4_E2B,
        customPaths: List<String> = emptyList()
    ): File? {
        val candidateDirs = listOf(
            File(context.filesDir, "models/litert"),
            File(context.filesDir, "models/downloads")
        )
        val explicitFiles = customPaths
            .filter { it.isNotBlank() }
            .map(::File)
            .filter { it.isFile && it.length() > 1024L * 1024L }

        fun findTierFile(tier: MangaVisionModelTier): File? {
            return explicitFiles.firstOrNull { it.name.equals(tier.fileName, ignoreCase = true) }
                ?: candidateDirs.firstNotNullOfOrNull { dir ->
                    File(dir, tier.fileName).takeIf { it.isFile && it.length() > 1024L * 1024L }
                }
        }

        // Strict preference: always honor the user's selected model when installed
        findTierFile(preferredTier)?.let { return it }

        // Only fall back to another installed VLM if the selected model is not on disk
        for (tier in MangaVisionModelTier.entries) {
            if (tier != preferredTier) {
                findTierFile(tier)?.let { return it }
            }
        }

        explicitFiles.firstOrNull()?.let { return it }

        return candidateDirs
            .filter { it.isDirectory }
            .flatMap { it.listFiles()?.toList().orEmpty() }
            .firstOrNull {
                it.isFile &&
                    it.extension.equals("litertlm", ignoreCase = true) &&
                    it.length() > 1024L * 1024L
            }
    }

    fun isModelTierInstalled(
        tier: MangaVisionModelTier,
        customPaths: List<String> = emptyList()
    ): Boolean {
        val resolved = resolveInstalledGemmaModelFile(tier, customPaths) ?: return false
        return resolved.name.equals(tier.fileName, ignoreCase = true)
    }

    private fun resolveTierForFile(
        modelFile: File,
        preferredTier: MangaVisionModelTier
    ): MangaVisionModelTier =
        MangaVisionModelTier.entries.firstOrNull {
            modelFile.name.equals(it.fileName, ignoreCase = true)
        } ?: preferredTier

    /**
     * Scales a manga page down to <=1024px and stamps numbered `[P1]`, `[P2]` Set-of-Mark frames
     * (used by LAN full-page vision mode).
     */
    fun renderAnnotatedPageForVision(
        pageBitmap: Bitmap,
        panels: List<MangaNormalizedRect>,
        targetFile: File
    ) {
        val maxDim = 1024f
        val scale = minOf(1f, maxDim / maxOf(pageBitmap.width, pageBitmap.height).toFloat().coerceAtLeast(1f))
        val outW = (pageBitmap.width * scale).toInt().coerceAtLeast(64)
        val outH = (pageBitmap.height * scale).toInt().coerceAtLeast(64)
        val scaled = Bitmap.createScaledBitmap(pageBitmap, outW, outH, true)
        val canvas = Canvas(scaled)

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = Color.argb(220, 0, 229, 255)
        }
        val tagBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(230, 12, 8, 22)
        }
        val tagTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(255, 0, 255, 136)
            textSize = maxOf(18f, outW * 0.032f)
            isFakeBoldText = true
        }

        panels.forEachIndexed { idx, rect ->
            val l = rect.left * outW
            val t = rect.top * outH
            val r = rect.right * outW
            val b = rect.bottom * outH
            canvas.drawRect(l, t, r, b, borderPaint)

            val label = "P${idx + 1}"
            val textW = tagTextPaint.measureText(label)
            val pad = 8f
            val box = RectF(l + 4f, t + 4f, l + textW + pad * 2f + 4f, t + tagTextPaint.textSize + pad * 1.4f + 4f)
            canvas.drawRoundRect(box, 6f, 6f, tagBgPaint)
            canvas.drawText(label, box.left + pad, box.bottom - pad * 0.55f, tagTextPaint)
        }

        targetFile.parentFile?.mkdirs()
        FileOutputStream(targetFile).use { fos ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 90, fos)
        }
        if (scaled !== pageBitmap) {
            scaled.recycle()
        }
    }

    /**
     * Crops a single BFS-detected panel with 3.5% bubble-safe padding and scales up to 1088px
     * so protruding edge speech bubbles are never clipped and small lettering stays razor-sharp.
     */
    fun renderPanelCropForVision(
        pageBitmap: Bitmap,
        panelRect: MangaNormalizedRect,
        targetFile: File
    ) {
        val padFrac = 0.035f
        val lFrac = (panelRect.left - padFrac).coerceIn(0f, 0.98f)
        val tFrac = (panelRect.top - padFrac).coerceIn(0f, 0.98f)
        val rFrac = (panelRect.right + padFrac).coerceIn(lFrac + 0.02f, 1f)
        val bFrac = (panelRect.bottom + padFrac).coerceIn(tFrac + 0.02f, 1f)

        val x = (lFrac * pageBitmap.width).toInt().coerceIn(0, (pageBitmap.width - 2).coerceAtLeast(0))
        val y = (tFrac * pageBitmap.height).toInt().coerceIn(0, (pageBitmap.height - 2).coerceAtLeast(0))
        val w = ((rFrac - lFrac) * pageBitmap.width).toInt().coerceIn(2, (pageBitmap.width - x).coerceAtLeast(2))
        val h = ((bFrac - tFrac) * pageBitmap.height).toInt().coerceIn(2, (pageBitmap.height - y).coerceAtLeast(2))

        val cropped = Bitmap.createBitmap(pageBitmap, x, y, w, h)
        val maxSide = maxOf(cropped.width, cropped.height).toFloat().coerceAtLeast(1f)
        val scale = when {
            maxSide > 1088f -> 1088f / maxSide
            maxSide < 640f -> minOf(2.0f, 768f / maxSide)
            else -> 1.0f
        }
        val outBmp = if (kotlin.math.abs(scale - 1.0f) > 0.02f) {
            Bitmap.createScaledBitmap(
                cropped,
                (cropped.width * scale).toInt().coerceAtLeast(32),
                (cropped.height * scale).toInt().coerceAtLeast(32),
                true
            )
        } else {
            cropped
        }

        targetFile.parentFile?.mkdirs()
        FileOutputStream(targetFile).use { fos ->
            outBmp.compress(Bitmap.CompressFormat.JPEG, 92, fos)
        }
        if (outBmp !== cropped && outBmp !== pageBitmap) outBmp.recycle()
        if (cropped !== pageBitmap) cropped.recycle()
    }

    /**
     * Composites up to 6 chapter pages onto one labeled 1024x1024 storyboard contact sheet
     * so the Vision LLM grasps whole-chapter character identity, outfits, and plot before panel OCR.
     */
    fun renderChapterStoryboardSheet(
        pageFiles: List<Pair<Int, File>>,
        targetFile: File
    ): Boolean {
        val samplePairs = pageFiles.take(6)
        if (samplePairs.isEmpty()) return false

        val count = samplePairs.size
        val cols = if (count <= 1) 1 else 2
        val rows = ((count + cols - 1) / cols).coerceAtLeast(1)
        val canvasSize = 1024
        val pad = 12
        val cellW = (canvasSize - pad * (cols + 1)) / cols
        val cellH = (canvasSize - pad * (rows + 1)) / rows
        val outBmp = Bitmap.createBitmap(canvasSize, canvasSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(outBmp)
        canvas.drawColor(Color.WHITE)

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
            color = Color.argb(230, 0, 180, 220)
        }
        val tagBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(235, 10, 12, 24)
        }
        val tagTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(255, 0, 255, 170)
            textSize = 22f
            isFakeBoldText = true
        }

        val decodeOpts = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = 2
        }

        samplePairs.forEachIndexed { slotIdx, (pageNumber1Based, file) ->
            val src = android.graphics.BitmapFactory.decodeFile(file.absolutePath, decodeOpts)
                ?: android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                ?: return@forEachIndexed
            try {
                val c = slotIdx % cols
                val r = slotIdx / cols
                val cellLeft = (pad + c * (cellW + pad)).toFloat()
                val cellTop = (pad + r * (cellH + pad)).toFloat()

                val scale = minOf(
                    cellW.toFloat() / src.width.coerceAtLeast(1).toFloat(),
                    cellH.toFloat() / src.height.coerceAtLeast(1).toFloat()
                )
                val drawW = src.width * scale
                val drawH = src.height * scale
                val dx = cellLeft + (cellW - drawW) * 0.5f
                val dy = cellTop + (cellH - drawH) * 0.5f
                val dstRect = RectF(dx, dy, dx + drawW, dy + drawH)
                canvas.drawBitmap(src, null, dstRect, null)
                canvas.drawRect(dstRect, borderPaint)

                val label = "PAGE $pageNumber1Based"
                val textW = tagTextPaint.measureText(label)
                val pill = RectF(dx + 6f, dy + 6f, dx + textW + 22f, dy + tagTextPaint.textSize + 18f)
                canvas.drawRoundRect(pill, 6f, 6f, tagBgPaint)
                canvas.drawText(label, pill.left + 8f, pill.bottom - 7f, tagTextPaint)
            } finally {
                src.recycle()
            }
        }

        targetFile.parentFile?.mkdirs()
        FileOutputStream(targetFile).use { fos ->
            outBmp.compress(Bitmap.CompressFormat.JPEG, 88, fos)
        }
        outBmp.recycle()
        return true
    }

    /**
     * Phase 2A-Pre: Single fast multi-page Storyboard Pass giving the VLM whole-chapter plot context
     * and canonical cast identity before zoomed per-panel OCR starts.
     */
    suspend fun runChapterStoryboardPass(
        seriesTitle: String,
        pageFiles: List<File>,
        readingOrder: MangaReadingOrder,
        existingCharacters: List<MangaCharacterProfile>,
        powerUserVisionUrl: String,
        preferredVisionModel: MangaVisionModelTier = MangaVisionModelTier.GEMMA4_E2B,
        customModelPaths: List<String> = emptyList(),
        onLog: (String) -> Unit = {}
    ): MangaVisionResponseParser.ParsedStoryboardBible? = withContext(Dispatchers.IO) {
        if (pageFiles.isEmpty()) return@withContext null

        val sampledPages: List<Pair<Int, File>> = if (pageFiles.size <= 6) {
            pageFiles.mapIndexed { idx, f -> (idx + 1) to f }
        } else {
            val indices = LinkedHashSet<Int>().apply {
                add(0)
                add(1)
                for (step in 1..3) {
                    add(((step.toFloat() / 4f) * (pageFiles.size - 1)).toInt().coerceIn(0, pageFiles.lastIndex))
                }
                add(pageFiles.lastIndex)
            }.take(6)
            indices.map { idx -> (idx + 1) to pageFiles[idx] }
        }

        val sheetFile = File(context.cacheDir, "manga_storyboard_sheet_${System.currentTimeMillis()}.jpg")
        try {
            if (!renderChapterStoryboardSheet(sampledPages, sheetFile)) return@withContext null
            val prompt = buildStoryboardPassPrompt(
                seriesTitle = seriesTitle,
                totalPages = pageFiles.size,
                readingOrder = readingOrder,
                existingCharacters = existingCharacters
            )

            var rawResponse: String? = null
            if (powerUserVisionUrl.isNotBlank()) {
                rawResponse = runCatching { queryOpenAiVisionEndpoint(powerUserVisionUrl.trim(), sheetFile, prompt) }
                    .onFailure { onLog("LAN Storyboard error: ${it.message}") }
                    .getOrNull()
            }
            if (rawResponse.isNullOrBlank()) {
                val localModel = resolveInstalledGemmaModelFile(preferredVisionModel, customModelPaths)
                if (localModel != null) {
                    val activeTier = resolveTierForFile(localModel, preferredVisionModel)
                    onLog("🗺️ Running Chapter Storyboard Context Pass (${sampledPages.size} pages via ${activeTier.shortLabel})...")
                    rawResponse = mutex.withLock {
                        queryLiteRtGemmaMultimodalLocked(
                            modelFile = localModel,
                            annotatedJpegFile = sheetFile,
                            prompt = prompt,
                            enableThinking = activeTier.supportsThinking,
                            maxTokens = activeTier.maxContextTokens,
                            onLog = onLog
                        )
                    }
                }
            }
            if (rawResponse.isNullOrBlank()) return@withContext null
            MangaVisionResponseParser.parseStoryboardPass(rawResponse)
        } finally {
            runCatching { sheetFile.delete() }
        }
    }

    private fun buildStoryboardPassPrompt(
        seriesTitle: String,
        totalPages: Int,
        readingOrder: MangaReadingOrder,
        existingCharacters: List<MangaCharacterProfile>
    ): String {
        val knownBlock = if (existingCharacters.isEmpty()) {
            "None yet — inspect ALL pages in the sheet to find characters' actual names where spoken (even if revealed on later pages)."
        } else {
            existingCharacters.joinToString("\n") { "- ${it.name} (${it.gender.tag}): ${it.visualDescription}" }
        }
        return """
You are MIKA's Manga Storyboard Director previewing "$seriesTitle" ($totalPages page chapter, ${readingOrder.label}).
This contact sheet shows multiple pages together so you understand the FULL story arc and character identities before detailed panel scanning begins.
${readingOrder.vlmPromptDirective}

KNOWN CAST SO FAR:
$knownBlock

TASK:
Look across ALL pages on this contact sheet as one continuous story:
1. Identify the recurring characters across scene changes & outfit switches (for example: if an unnamed character in a bath towel on PAGE 1 turns out to be named OJOU-SAMA or SEBASTIAN on PAGE 2, unify them under ONE canonical uppercase name!).
   - NEVER use placeholder names (MIKA, THE_MALE, THE_FEMALE, GIRL, BOY). Only list characters whose name is visually confirmed somewhere on the sheet.
2. Summarize the overall chapter premise in 1-2 sentences.
3. Give a brief 1-sentence beat summary for each labeled PAGE.

Output ONLY valid JSON:
{
  "story_summary": "1-2 sentence chapter plot connecting all pages together",
  "characters": [
    {
      "name": "CANONICAL_UPPERCASE_NAME",
      "gender": "male | female | androgynous",
      "visual_description": "hair, face/species traits, and outfit across pages",
      "voice_design_prompt": "12-20 word Qwen3-TTS VoiceDesign note"
    }
  ],
  "page_synopses": [
    {"page": 1, "synopsis": "What happens on Page 1 and which characters appear"},
    {"page": 2, "synopsis": "What happens on Page 2 and who is named"}
  ]
}
""".trim()
    }

    /**
     * Phase 2A: Analyzes one manga page panel-by-panel using zoomed high-res crops on the selected
     * on-device LiteRT-LM Vision LLM (or LAN Power-User Vision server), enforcing strict panel locking
     * and reading-order bubble transcription.
     */
    suspend fun analyzeMangaPage(
        pageIndex: Int,
        pageBitmap: Bitmap,
        panels: List<MangaNormalizedRect>,
        readingOrder: MangaReadingOrder,
        existingCharacters: List<MangaCharacterProfile>,
        recentDialogueContext: List<String>,
        powerUserVisionUrl: String,
        preferredVisionModel: MangaVisionModelTier = MangaVisionModelTier.GEMMA4_E2B,
        customModelPaths: List<String> = emptyList(),
        storySummary: String = "",
        pageSynopsis: String = "",
        includeNonBubbleText: Boolean = false,
        kvCacheEnabled: Boolean = false,
        onLog: (String) -> Unit = {},
        onPanelProgress: (Int, Int, String) -> Unit = { _, _, _ -> }
    ): Pair<MangaVisionResponseParser.ParsedPageAnalysis, String> = withContext(Dispatchers.IO) {
        val targetPanels = panels.ifEmpty { listOf(MangaNormalizedRect(0.01f, 0.01f, 0.99f, 0.99f)) }

        // Tier 1: Optional Power-User External OpenAI-Compatible Vision Server
        if (powerUserVisionUrl.isNotBlank()) {
            val annotatedFile = File(context.cacheDir, "manga_vision_page_$pageIndex.jpg")
            renderAnnotatedPageForVision(pageBitmap, targetPanels, annotatedFile)
            val prompt = buildGemmaDirectorPrompt(
                pageIndex = pageIndex,
                panelCount = targetPanels.size,
                readingOrder = readingOrder,
                existingCharacters = existingCharacters,
                recentDialogueContext = recentDialogueContext,
                storySummary = storySummary,
                pageSynopsis = pageSynopsis,
                includeNonBubbleText = includeNonBubbleText
            )
            onLog("Querying LAN Vision Endpoint for Page ${pageIndex + 1}...")
            val rawRemote = runCatching { queryOpenAiVisionEndpoint(powerUserVisionUrl.trim(), annotatedFile, prompt) }
                .onFailure { onLog("LAN Vision error: ${it.message}") }
                .getOrNull()
            runCatching { annotatedFile.delete() }
            if (!rawRemote.isNullOrBlank()) {
                val parsed = MangaVisionResponseParser.parsePageAnalysis(
                    rawResponse = rawRemote,
                    panelCount = targetPanels.size,
                    existingCharacters = existingCharacters,
                    includeNonBubbleText = includeNonBubbleText,
                    readingOrder = readingOrder
                )
                if (parsed.panels.isNotEmpty()) {
                    return@withContext parsed to "LAN Vision Endpoint"
                }
            }
        }

        // Tier 2: On-Device LiteRT-LM Vision LLM — zoomed per-panel crops for sharp OCR + exact alignment
        val localModel = resolveInstalledGemmaModelFile(preferredVisionModel, customModelPaths)
        if (localModel != null) {
            val activeTier = resolveTierForFile(localModel, preferredVisionModel)
            if (!localModel.name.equals(preferredVisionModel.fileName, ignoreCase = true)) {
                onLog("⚠️ ${preferredVisionModel.shortLabel} not installed; using ${activeTier.shortLabel} (${localModel.name})")
            } else {
                onLog("🧠 Selected VLM: ${activeTier.shortLabel} (${localModel.name})")
            }

            val runningContext = recentDialogueContext.toMutableList()
            val runningRoster = existingCharacters.toMutableList()
            val panelSpecs = ArrayList<MangaVisionResponseParser.DiscoveredPanelSpec>(targetPanels.size)
            val thoughtSnippets = ArrayList<String>()
            var mountFailed = false
            // ✨ v1.9.8: KV-cache panel scanning is OPT-IN (advanced settings toggle) — the compact
            // preface + tiny turn prompts made small VLMs (Gemma E2B) fail the OCR task entirely
            // and parrot the schema example. Default OFF preserves the proven full panel prompt.
            val usePageKvCache = kvCacheEnabled && activeTier.maxContextTokens >= 4096 && targetPanels.size >= 2
            var pageKvConv: Conversation? = null
            var kvImagesUsed = 0
            var kvEstTokens = 0
            var kvRosterCursor = runningRoster.size

            try {
                for ((panelIdx, rect) in targetPanels.withIndex()) {
                    val cropFile = File(context.cacheDir, "manga_vision_crop_${pageIndex}_${panelIdx}.jpg")
                    renderPanelCropForVision(pageBitmap, rect, cropFile)

                    var rawPanel: String? = null
                    if (usePageKvCache) {
                        rawPanel = mutex.withLock {
                            val engine = ensureLiteRtEngineMountedLocked(
                                modelFile = localModel,
                                maxTokens = activeTier.maxContextTokens,
                                onLog = onLog,
                                desiredMaxImages = minOf(4, targetPanels.size).coerceAtLeast(2)
                            ) ?: return@withLock null
                            if (activeMaxImages < 2) return@withLock null

                            val needRotate = pageKvConv == null ||
                                kvImagesUsed >= activeMaxImages ||
                                kvEstTokens >= (activeMaxTokens - 1050)
                            if (needRotate) {
                                runCatching { pageKvConv?.close() }
                                pageKvConv = null
                                kvImagesUsed = 0
                                val sharedPreface = buildPageKvSharedPreface(
                                    pageIndex = pageIndex,
                                    readingOrder = readingOrder,
                                    knownCharacters = runningRoster,
                                    storySummary = storySummary,
                                    pageSynopsis = pageSynopsis,
                                    includeNonBubbleText = includeNonBubbleText
                                )
                                kvEstTokens = estimateApproxTokens(sharedPreface)
                                kvRosterCursor = runningRoster.size
                                val convCfg = if (activeTier.supportsThinking) {
                                    ConversationConfig(
                                        systemInstruction = Contents.of(sharedPreface),
                                        samplerConfig = SamplerConfig(topK = 40, topP = 0.92, temperature = 0.2, seed = 42),
                                        prefillPrefaceOnInit = true,
                                        // ✨ v1.9.7: busy multi-bubble panels truncated mid-JSON at 768
                                        // output tokens (thinking ate 384 of them) — widened the JSON
                                        // budget and slimmed the thinking budget so panels parse.
                                        maxOutputToken = 1024,
                                        thinkingConfig = ThinkingConfig(enableThinking = true, thinkingTokenBudget = 256)
                                    )
                                } else {
                                    ConversationConfig(
                                        systemInstruction = Contents.of(sharedPreface),
                                        samplerConfig = SamplerConfig(topK = 40, topP = 0.92, temperature = 0.2, seed = 42),
                                        prefillPrefaceOnInit = true,
                                        maxOutputToken = 1024
                                    )
                                }
                                pageKvConv = runCatching { engine.createConversation(convCfg) }.getOrNull()
                            }

                            val conv = pageKvConv ?: return@withLock null
                            val newlyAddedChars = runningRoster.drop(kvRosterCursor)
                            kvRosterCursor = runningRoster.size
                            val turnPrompt = buildPageKvTurnPrompt(
                                panelIndex = panelIdx,
                                totalPanelsOnPage = targetPanels.size,
                                newlyDiscoveredChars = newlyAddedChars,
                                recentDialogueContext = runningContext,
                                includeNonBubbleText = includeNonBubbleText
                            )
                            val turnResult = runCatching {
                                val msg = conv.sendMessage(
                                    Contents.of(listOf(Content.ImageBytes(cropFile.readBytes()), Content.Text(turnPrompt)))
                                )
                                extractFullResponseFromMessage(msg)
                            }
                            if (turnResult.isFailure || turnResult.getOrNull().isNullOrBlank()) {
                                runCatching { conv.close() }
                                pageKvConv = null
                                kvImagesUsed = 0
                                null
                            } else {
                                val outText = turnResult.getOrNull()
                                kvImagesUsed++
                                kvEstTokens += 300 + estimateApproxTokens(turnPrompt) + estimateApproxTokens(outText.orEmpty())
                                outText
                            }
                        }
                    }

                    if (rawPanel.isNullOrBlank()) {
                        val panelPrompt = buildSinglePanelDirectorPrompt(
                            pageIndex = pageIndex,
                            panelIndex = panelIdx,
                            totalPanelsOnPage = targetPanels.size,
                            readingOrder = readingOrder,
                            existingCharacters = runningRoster,
                            recentDialogueContext = runningContext,
                            storySummary = storySummary,
                            pageSynopsis = pageSynopsis,
                            includeNonBubbleText = includeNonBubbleText
                        )
                        rawPanel = mutex.withLock {
                            queryLiteRtGemmaMultimodalLocked(
                                modelFile = localModel,
                                annotatedJpegFile = cropFile,
                                prompt = panelPrompt,
                                enableThinking = activeTier.supportsThinking,
                                maxTokens = activeTier.maxContextTokens,
                                onLog = onLog
                            )
                        }
                    }
                    runCatching { cropFile.delete() }

                    if (rawPanel.isNullOrBlank()) {
                        if (engineHandle == null) {
                            mountFailed = true
                            onLog("⚠️ ${activeTier.shortLabel} mount/inference failed on P${panelIdx + 1}: ${lastEngineError ?: "unknown error"}")
                            break
                        }
                        onLog("⚠️ Empty response on Page ${pageIndex + 1} P${panelIdx + 1}; continuing")
                        panelSpecs += MangaVisionResponseParser.DiscoveredPanelSpec(
                            panelIndex = panelIdx,
                            sceneMood = "manga_panel",
                            thoughtTrace = "",
                            lines = emptyList()
                        )
                        onPanelProgress(panelIdx + 1, targetPanels.size, "0 bubble(s)")
                        continue
                    }

                    var panelSpec = MangaVisionResponseParser.parseSinglePanelAnalysis(
                        rawResponse = rawPanel,
                        panelIndex = panelIdx,
                        existingCharacters = runningRoster,
                        includeNonBubbleText = includeNonBubbleText,
                        readingOrder = readingOrder
                    )
                    // If model emitted freeform prose instead of JSON and omitted empty `"lines": []`, try compact JSON repair
                    if (panelSpec.lines.isEmpty() && !rawPanel.contains("\"lines\"") && rawPanel.length > 40 && rawPanel.none { it == '{' }) {
                        val repairPrompt = buildCompactSinglePanelRepairPrompt(rawPanel.take(500))
                        val repairedRaw = mutex.withLock {
                            queryLiteRtGemmaTextLocked(localModel, repairPrompt, activeTier.maxContextTokens, onLog)
                        }
                        if (!repairedRaw.isNullOrBlank()) {
                            val repaired = MangaVisionResponseParser.parseSinglePanelAnalysis(
                                rawResponse = repairedRaw,
                                panelIndex = panelIdx,
                                existingCharacters = runningRoster,
                                includeNonBubbleText = includeNonBubbleText,
                                readingOrder = readingOrder
                            )
                            if (repaired.lines.isNotEmpty()) {
                                panelSpec = repaired
                            }
                        }
                    }

                    panelSpec.lines.forEach { ln ->
                        if (ln.rawText.isNotBlank()) {
                            runningContext += "${ln.speakerName}: ${ln.rawText}"
                        }
                        if (!ln.speakerName.equals("UNKNOWN", ignoreCase = true) &&
                            runningRoster.none { it.name.equals(ln.speakerName, ignoreCase = true) }
                        ) {
                            runningRoster += MangaCharacterProfile(
                                id = "tmp_${runningRoster.size}",
                                name = ln.speakerName,
                                gender = ln.gender,
                                visualDescription = ln.visualDescription,
                                voiceDesignPrompt = ln.voiceDesignPrompt
                            )
                        }
                    }
                    if (panelSpec.thoughtTrace.isNotBlank()) {
                        thoughtSnippets += "P${panelIdx + 1}: ${panelSpec.thoughtTrace.take(80)}"
                    }
                    panelSpecs += panelSpec
                    onPanelProgress(panelIdx + 1, targetPanels.size, "${panelSpec.lines.size} bubble(s)")
                }
            } finally {
                runCatching { pageKvConv?.close() }
            }

            if (!mountFailed && panelSpecs.isNotEmpty()) {
                val combinedThought = thoughtSnippets.joinToString(" | ").take(360)
                return@withContext MangaVisionResponseParser.ParsedPageAnalysis(
                    thoughtTrace = combinedThought,
                    storySummary = storySummary,
                    panels = panelSpecs
                ) to "${activeTier.shortLabel} ($activeBackendLabel${if (usePageKvCache && activeMaxImages >= 2) " KVx$activeMaxImages" else ""})"
            }
        } else {
            onLog("ℹ️ No local .litertlm VLM found in filesDir/models/litert for ${preferredVisionModel.shortLabel}")
        }

        // Tier 3: Offline Heuristic Fallback (pre-populates panels cleanly for Pre-Production review)
        val diagReason = lastEngineError ?: if (localModel != null) {
            "VLM (${localModel.name}) produced no dialogue JSON — review panels below"
        } else {
            "Install ${preferredVisionModel.shortLabel} (${preferredVisionModel.romCodeIfAny()}) for Auto-OCR"
        }
        val fallback = buildOfflineHeuristicAnalysis(
            pageIndex = pageIndex,
            panels = targetPanels,
            existingCharacters = existingCharacters,
            modelWasInstalled = localModel != null,
            diagnosticReason = diagReason,
            storySummary = storySummary
        )
        fallback to if (localModel == null) {
            "Offline Layout Slicer (Install ${preferredVisionModel.shortLabel})"
        } else {
            "Fallback Review ($diagReason)"
        }
    }

    private fun MangaVisionModelTier.romCodeIfAny(): String = when (this) {
        MangaVisionModelTier.GEMMA4_E2B -> "SUB-11V"
        MangaVisionModelTier.GEMMA4_E4B -> "SUB-11X"
        MangaVisionModelTier.QWEN35_VL_2B -> "SUB-11Q"
        MangaVisionModelTier.LFM25_VL_3B -> "SUB-11L"
        MangaVisionModelTier.QWEN35_VL_08B -> "SUB-11F"
        MangaVisionModelTier.INTERNVL3_1B -> "SUB-11I"
    }

    /**
     * Phase 2B: Text-only Qwen3 1.7B VoiceDesign Casting Director.
     * Runs while the selected Vision LLM is already mounted in memory (zero extra model load) to refine every
     * character's acoustic Qwen3 VoiceDesign prompt, speaking cadence, and pitch scale from
     * their visual traits and spoken dialogue in this chapter.
     */
    suspend fun craftQwenVoiceDesignsForRoster(
        seriesTitle: String,
        storySummary: String,
        characters: List<MangaCharacterProfile>,
        sampleLinesBySpeaker: Map<String, List<String>>,
        preferredVisionModel: MangaVisionModelTier = MangaVisionModelTier.GEMMA4_E2B,
        powerUserVisionUrl: String = "",
        customModelPaths: List<String> = emptyList(),
        onLog: (String) -> Unit = {}
    ): List<MangaCharacterProfile> = withContext(Dispatchers.IO) {
        if (characters.isEmpty()) return@withContext characters

        val castingPrompt = buildQwenVoiceCastingPrompt(
            seriesTitle = seriesTitle,
            storySummary = storySummary,
            characters = characters,
            sampleLinesBySpeaker = sampleLinesBySpeaker
        )

        var updatedRoster = characters
        var rawCasting: String? = null
        if (powerUserVisionUrl.isNotBlank()) {
            rawCasting = runCatching { queryOpenAiTextEndpoint(powerUserVisionUrl.trim(), castingPrompt) }.getOrNull()
        }
        if (rawCasting.isNullOrBlank()) {
            val localModel = resolveInstalledGemmaModelFile(preferredVisionModel, customModelPaths)
            if (localModel != null) {
                val activeTier = resolveTierForFile(localModel, preferredVisionModel)
                if (characters.size > 5 && activeTier.maxContextTokens >= 4096) {
                    val chunks = characters.chunked(4)
                    val sharedPreface = buildCastingPrefillPreface(seriesTitle, storySummary)
                    val clonedOutputs = mutex.withLock {
                        queryBatchedPrefillCloneLocked(
                            modelFile = localModel,
                            sharedPreface = sharedPreface,
                            chunkPrompts = chunks.map { chunk ->
                                buildQwenVoiceCastingPrompt(
                                    seriesTitle = seriesTitle,
                                    storySummary = storySummary,
                                    characters = chunk,
                                    sampleLinesBySpeaker = sampleLinesBySpeaker
                                )
                            },
                            maxTokens = activeTier.maxContextTokens,
                            onLog = onLog
                        )
                    }
                    if (!clonedOutputs.isNullOrEmpty()) {
                        clonedOutputs.forEach { out ->
                            updatedRoster = MangaVisionResponseParser.parseVoiceDesignRoster(out, updatedRoster)
                        }
                        onLog("🎭 VLM cast ${updatedRoster.size} VoiceDesign profile(s) via Session KV-Clone (${chunks.size} batches)")
                        return@withContext updatedRoster
                    }
                }
                rawCasting = mutex.withLock {
                    queryLiteRtGemmaTextLocked(localModel, castingPrompt, activeTier.maxContextTokens, onLog)
                }
            }
        }

        if (!rawCasting.isNullOrBlank()) {
            val updated = MangaVisionResponseParser.parseVoiceDesignRoster(rawCasting, updatedRoster)
            onLog("🎭 VLM crafted ${updated.size} Qwen3 VoiceDesign casting profile(s)")
            return@withContext updated
        }

        // Deterministic enrichment fallback if text-only pass had no model available
        characters.map { ch ->
            if (ch.voiceDesignPrompt.isBlank()) {
                val basePrompt = when (ch.gender) {
                    VoiceGender.FEMALE -> "Expressive young anime heroine voice, clear crisp articulation, warm bright midrange, dynamic emotional projection"
                    VoiceGender.MALE -> "Confident anime protagonist baritone-tenor, punchy consonant attack, rich chest resonance, studio dub clarity"
                    VoiceGender.ANDROGYNOUS -> "Commanding theatrical narrator voice, balanced poise, crisp diction, dramatic pacing"
                }
                ch.copy(voiceDesignPrompt = basePrompt)
            } else {
                ch
            }
        }
    }

    private fun buildSinglePanelDirectorPrompt(
        pageIndex: Int,
        panelIndex: Int,
        totalPanelsOnPage: Int,
        readingOrder: MangaReadingOrder,
        existingCharacters: List<MangaCharacterProfile>,
        recentDialogueContext: List<String>,
        storySummary: String = "",
        pageSynopsis: String = "",
        includeNonBubbleText: Boolean = false
    ): String {
        val rosterSummary = if (existingCharacters.isEmpty()) {
            "None yet — name speakers consistently using their canonical uppercase name from STORYBOARD CAST BIBLE below (or NARRATOR for boxed captions)."
        } else {
            existingCharacters.joinToString("\n") { ch ->
                "- ${ch.name} (${ch.gender.tag}): ${ch.visualDescription.ifBlank { ch.voiceDesignPrompt }}"
            }
        }
        val contextLines = if (recentDialogueContext.isEmpty()) {
            "(Opening panel)"
        } else {
            recentDialogueContext.takeLast(5).joinToString("\n")
        }
        val storyBlock = buildString {
            if (storySummary.isNotBlank()) appendLine("CHAPTER STORYBOARD BIBLE: $storySummary")
            if (pageSynopsis.isNotBlank()) appendLine("PAGE ${pageIndex + 1} BEAT: $pageSynopsis")
        }.trim().ifEmpty { "(Scanning sequential story — keep cast identity consistent across outfit changes or duplicate comedy shots.)" }

        val bubbleScopeRule = if (includeNonBubbleText) {
            "EXPERIMENTAL ALL-TEXT MODE: Transcribe enclosed speech bubbles, thought clouds, rectangular narration captions, AND dramatic SFX/floating background shouts visible inside THIS panel image. Classify each entry accurately via \"container_type\"."
        } else {
            "STRICT SPEECH-BUBBLE-ONLY MODE: ONLY transcribe text enclosed inside distinct speech bubbles, thought clouds, jagged speech bursts belonging to a speaker, or rectangular narration boxes. COMPLETELY IGNORE standalone SFX lettering floating on the artwork (e.g. \"Shaaa\", \"GULP\", \"BWO DO\"), unboxed background laughs, scanlation credit bars, page numbers, watermarks, and handwritten signatures. Set \"container_type\" truthfully; never emit SFX or credits."
        }

        val readingPosRule = when (readingOrder) {
            MangaReadingOrder.RTL_MANGA ->
                "STRICT MANGA RIGHT-TO-LEFT Z-ORDER: First visually count ALL enclosed speech bubbles (including small edge bubbles or tiny side interjections). Scan upper vertical bands FIRST from RIGHT (\"x_pos\" ~ 1.0) to LEFT (\"x_pos\" ~ 0.0), then move down to lower rows from RIGHT to LEFT. Never jump down and back up out of order."
            MangaReadingOrder.LTR_COMIC ->
                "STRICT COMIC LEFT-TO-RIGHT Z-ORDER: First visually count ALL enclosed speech bubbles. Scan upper bands FIRST from LEFT (\"x_pos\" ~ 0.0) to RIGHT (\"x_pos\" ~ 1.0), then lower rows LEFT to RIGHT."
            MangaReadingOrder.WEBTOON_VERTICAL ->
                "STRICT WEBTOON TOP-TO-BOTTOM ORDER: Count ALL enclosed speech bubbles and read strictly TOPMOST (\"y_pos\" ~ 0.0) to BOTTOMMOST (\"y_pos\" ~ 1.0)."
        }

        return """
You are scanning ONE zoomed comic/manga panel: Page ${pageIndex + 1}, Panel ${panelIndex + 1} of $totalPanelsOnPage.
${readingOrder.vlmPromptDirective}
$readingPosRule

$storyBlock

KNOWN CAST ROSTER (ALWAYS reuse canonical names instead of inventing duplicate characters when outfits change):
$rosterSummary

PREVIOUS DIALOGUE:
$contextLines

TASK:
$bubbleScopeRule
SPEAKER & BUBBLE-TAIL ATTRIBUTION RULES:
- For every bubble, inspect its pointed tail direction, tiny inline chibi speaker icon, or jagged scream burst pointing toward a character (or follow split `--` sentence continuations belonging to the previous bubble's speaker). Record who the tail points to in `"tail_target"`.
- If a bubble has NO clear tail and NO chibi head icon (e.g. off-panel interruption among multiple present characters), set `"speaker": "UNKNOWN"`, `"tail_target": "unclear"`, `"confidence": 0.45`, and list likely `"candidate_speakers"`.
- Order lines strictly by ${readingOrder.label} and provide normalized bubble center coordinates `"x_pos"` (0.0=left..1.0=right) and `"y_pos"` (0.0=top..1.0=bottom).

NAMING & SCOPE RULES:
- NEVER invent placeholder names (MIKA, THE_MALE, THE_FEMALE, GIRL, BOY, MAN, WOMAN, CHARACTER, SPEAKER). If a speaker's name is not confirmed inside THIS panel (spoken aloud, written, or unmistakable from context), use "UNKNOWN" — unnamed characters stay UNKNOWN.
- Transcribe ONLY text visible inside THIS panel crop. Never carry over or invent bubbles from other pages or panels.
- Boxed narration captions use speaker "NARRATOR" (MIKA voices the narration).

Output ONLY valid JSON matching this schema:
{
  "mood": "intense battle | warm romance | rain gloom | calm mystery | action | silent_panel",
  "thought": "count enclosed bubbles, note edge bubbles & trace every bubble tail in reading order",
  "lines": [
    {
      "bubble": 1,
      "x_pos": 0.85,
      "y_pos": 0.22,
      "container_type": "speech_bubble | thought_bubble | scream_burst | narration_box | floating_sfx | credit_or_signature",
      "tail_target": "pointed tail character or narrator_box or unclear",
      "speaker": "CANONICAL_CHARACTER_NAME or NARRATOR or UNKNOWN",
      "is_new_character": false,
      "gender": "male | female | androgynous",
      "visual_description": "concise appearance if new character",
      "voice_design_prompt": "12-20 word vocal casting note for pitch, timbre, energy",
      "emotion": "fierce | calm | sad | joyful | shy | angry | neutral",
      "confidence": 0.90,
      "candidate_speakers": ["CHAR_A", "CHAR_B"],
      "text": "Exact transcribed dialogue inside bubble 1"
    }
  ]
}
If there is NO spoken speech-bubble text in this panel, output: {"mood":"silent_panel","lines":[]}.
""".trim()
    }

    private fun buildCompactSinglePanelRepairPrompt(previousSnippet: String): String = """
Convert these single-panel manga transcription notes into strict JSON matching:
{"mood":"action","lines":[{"bubble":1,"x_pos":0.85,"y_pos":0.25,"container_type":"speech_bubble","tail_target":"narrator_box","speaker":"NARRATOR","is_new_character":false,"gender":"male","emotion":"neutral","confidence":0.85,"text":"Dialogue text"}]}
Output JSON ONLY.

Notes:
$previousSnippet
""".trim()

    private fun buildGemmaDirectorPrompt(
        pageIndex: Int,
        panelCount: Int,
        readingOrder: MangaReadingOrder,
        existingCharacters: List<MangaCharacterProfile>,
        recentDialogueContext: List<String>,
        storySummary: String = "",
        pageSynopsis: String = "",
        includeNonBubbleText: Boolean = false
    ): String {
        val rosterSummary = if (existingCharacters.isEmpty()) {
            "None yet — name speakers consistently using their canonical uppercase name."
        } else {
            existingCharacters.joinToString("\n") { ch ->
                "- ${ch.name} (${ch.gender.tag}): ${ch.visualDescription.ifBlank { ch.voiceDesignPrompt }}"
            }
        }
        val contextLines = if (recentDialogueContext.isEmpty()) {
            "(Start of chapter)"
        } else {
            recentDialogueContext.takeLast(5).joinToString("\n")
        }
        val storyBlock = buildString {
            if (storySummary.isNotBlank()) appendLine("CHAPTER STORYBOARD BIBLE: $storySummary")
            if (pageSynopsis.isNotBlank()) appendLine("PAGE ${pageIndex + 1} BEAT: $pageSynopsis")
        }.trim().ifEmpty { "(Keep character identity consistent across outfit switches.)" }

        val bubbleRule = if (includeNonBubbleText) {
            "Read speech bubbles, narration boxes, and dramatic SFX lettering in strict ${readingOrder.label} order and label each via \"container_type\"."
        } else {
            "ONLY transcribe enclosed speech bubbles, thought clouds, scream bursts, and boxed narration captions in strict ${readingOrder.label} order. COMPLETELY SKIP floating SFX, scanlation credits, page numbers, watermarks, and signatures."
        }

        val readingPosRule = when (readingOrder) {
            MangaReadingOrder.RTL_MANGA ->
                "IMPORTANT: Inside every panel, scan upper horizontal bands RIGHTMOST (\"x_pos\" near 1.0) to LEFTMOST (\"x_pos\" near 0.0) before lower bands."
            MangaReadingOrder.LTR_COMIC ->
                "IMPORTANT: Inside every panel, scan upper horizontal bands LEFTMOST (\"x_pos\" near 0.0) to RIGHTMOST (\"x_pos\" near 1.0) before lower bands."
            MangaReadingOrder.WEBTOON_VERTICAL ->
                "IMPORTANT: Inside every panel, bubble 1 is the TOPMOST bubble (lowest \"y_pos\" near 0.0), reading downward."
        }

        return """
You are MIKA's Manga Dubbing & Voice Casting Director scanning Page ${pageIndex + 1} (${readingOrder.label}).
${readingOrder.vlmPromptDirective}
$readingPosRule
Cyan boxes outline the exact reading-order panels labeled P1..P$panelCount.

$storyBlock

KNOWN SERIES CHARACTER ROSTER:
$rosterSummary

RECENT DIALOGUE CONTEXT:
$contextLines

TASK:
1. First think carefully inside `<thought>...</thought>` about each panel P1..P$panelCount:
   - $bubbleRule
   - Inspect every edge bubble, pointed bubble tail, miniature inline speaker icon, and `--` sentence continuation to identify each speaker accurately (or set UNKNOWN / confidence 0.45 with candidate_speakers if unclear).
2. Immediately output ONLY a valid JSON object matching this schema:
{
  "story_summary": "1-sentence running summary update",
  "panels": [
    {
      "panel_index": 0,
      "scene_mood": "intense battle | warm romance | rain gloom | calm mystery | action",
      "thought": "brief reasoning tracing bubble count and tails",
      "lines": [
        {
          "bubble": 1,
          "x_pos": 0.85,
          "y_pos": 0.22,
          "container_type": "speech_bubble | thought_bubble | scream_burst | narration_box",
          "tail_target": "pointed tail character or narrator_box or unclear",
          "speaker": "CHARACTER_NAME or NARRATOR or UNKNOWN",
          "is_new_character": true,
          "gender": "male | female | androgynous",
          "visual_description": "concise appearance traits if new character (age, vibe, armor/uniform)",
          "voice_design_prompt": "Qwen3-TTS 1.7B VoiceDesign prompt: pitch, vocal timbre, grit/warmth, energy, pacing",
          "emotion": "fierce | calm | sad | joyful | shy | angry | neutral",
          "confidence": 0.90,
          "candidate_speakers": ["CHAR_A", "CHAR_B"],
          "text": "Exact dialogue text transcribed cleanly in reading order"
        }
      ]
    }
  ]
}
Rules:
- Use 0-based panel_index (0 to ${maxOf(0, panelCount - 1)}).
- Skip empty/SFX-only panels with `"lines": []`.
- Only set `"speaker": "UNKNOWN"` and `"confidence": 0.45` if the tail leaves speaker identity genuinely ambiguous.
- NEVER invent placeholder names (MIKA, THE_MALE, THE_FEMALE, GIRL, BOY, MAN, WOMAN, CHARACTER). Unnamed speakers stay "UNKNOWN"; boxed narration captions use "NARRATOR".
- Transcribe ONLY text visible on THIS page image — never carry bubbles over from other pages.
""".trim()
    }

    private fun buildQwenVoiceCastingPrompt(
        seriesTitle: String,
        storySummary: String,
        characters: List<MangaCharacterProfile>,
        sampleLinesBySpeaker: Map<String, List<String>>
    ): String {
        val rosterBlock = characters.joinToString("\n\n") { ch ->
            val samples = sampleLinesBySpeaker[ch.id]
                ?: sampleLinesBySpeaker[ch.name]
                ?: emptyList()
            val sampleStr = if (samples.isEmpty()) "(no sample lines)" else samples.take(3).joinToString(" | ") { "\"$it\"" }
            """
            CHARACTER: ${ch.name}
            GENDER: ${ch.gender.tag}
            VISUAL APPEARANCE: ${ch.visualDescription.ifBlank { "Manga character" }}
            CURRENT VOICE DRAFT: ${ch.voiceDesignPrompt.ifBlank { "None" }}
            SAMPLE LINES: $sampleStr
            """.trimIndent()
        }

        return """
You are casting locked canonical character voices for "$seriesTitle" using Qwen3-TTS 1.7B VoiceDesign (each voice will generate a clean reference anchor clip for the entire chapter, so avoid hyper-transient emotion instructions like "currently crying" and specify their enduring vocal identity).
Story context: ${storySummary.ifBlank { "Chapter 1" }}

ROSTER:
$rosterBlock

Output ONLY valid JSON with structured casting attributes AND a detailed acoustic description (no character proper names inside voice_design_prompt):
{
  "characters": [
    {
      "name": "EXACT_CHARACTER_NAME",
      "gender": "male | female | androgynous",
      "age": "TEEN | YOUNG_ADULT | ADULT | MIDDLE_AGED | ELDERLY",
      "pitch": "DEEP_BASS | LOW_BARITONE | MID_NATURAL | BRIGHT_UPPER_MID | HIGH_SOPRANO",
      "timbres": ["WARM_VELVETY", "CRISP_CLEAR"],
      "pace": "SLOW_DELIBERATE | CALM_STEADY | NATURAL_FLOWING | ENERGETIC_BRISK | RAPID_URGENT",
      "delivery": "WARM_CONFIDENT | PLAYFUL_TEASING | SHY_HESITANT | SERIOUS_AUTHORITATIVE | MYSTERIOUS_HUSHED | CHEERFUL_UPBEAT | MELANCHOLIC_SOFT | DRY_SARCASTIC | GRUMPY_FRUSTRATED",
      "accent": "ANIME_EXPRESSIVE | NEUTRAL_AMERICAN | BRITISH_RP | BROADCAST_CLEAN",
      "acoustics": "CLOSE_MIC_STUDIO | AUDIOBOOK_BOOTH | STAGE_PROJECTED",
      "persona_role": "e.g. aristocratic anime heiress or calm eccentric scientist",
      "nuance": "10-20 word acoustic color note describing stable vocal signature, resonance, and diction",
      "visual_description": "refined 1-line character summary",
      "voice_design_prompt": "Detailed 25-40 word acoustic VoiceDesign description covering gender, age, pitch, timbre, cadence, and studio microphone feel",
      "speed_scale": 1.0,
      "pitch_scale": 1.0
    }
  ]
}
Allowed timbres: WARM_VELVETY, CRISP_CLEAR, BREATHY_AIRY, RASPY_GRAVELLY, SILKY_SULTRY, BRIGHT_SPARKLY, HOARSE_CROAKY, NASAL_QUIRKY.
Speed_scale range: 0.85..1.15. Pitch_scale range: 0.90..1.10.
""".trim()
    }

    @OptIn(ExperimentalApi::class)
    private fun ensureLiteRtEngineMountedLocked(
        modelFile: File,
        maxTokens: Int = 4096,
        onLog: (String) -> Unit,
        desiredMaxImages: Int = 1
    ): Engine? {
        val safeTokens = maxTokens.coerceIn(1024, 4096)
        val wantImages = if (safeTokens >= 4096) desiredMaxImages.coerceIn(1, 4) else 1
        if (engineHandle != null &&
            loadedModelPath == modelFile.absolutePath &&
            activeMaxImages >= wantImages
        ) {
            return engineHandle
        }
        if (engineHandle != null && loadedModelPath == modelFile.absolutePath && wantImages > activeMaxImages) {
            // Avoid churn if higher maxImages wasn't explicitly needed when engine is already warm
            if (desiredMaxImages <= 1) return engineHandle
        }
        unloadLocked()
        runCatching {
            // Keep <thought> channel tokens out of persistent Conversation KV cache so long pages don't overflow
            ExperimentalFlags.filterChannelContentFromKvCache = true
        }
        val cacheDir = File(context.noBackupFilesDir, "litert_kv_program_cache").apply { mkdirs() }.absolutePath
        val compactTokens = minOf(safeTokens, 2048)

        val candidates = ArrayList<Triple<String, Int, EngineConfig>>()
        if (wantImages > 1) {
            candidates += Triple(
                "GPU+VISION_GPU",
                wantImages,
                EngineConfig(
                    modelPath = modelFile.absolutePath,
                    backend = Backend.GPU(),
                    visionBackend = Backend.GPU(),
                    audioBackend = null,
                    maxNumTokens = safeTokens,
                    maxNumImages = wantImages,
                    cacheDir = cacheDir
                )
            )
            candidates += Triple(
                "GPU+VISION_CPU",
                wantImages,
                EngineConfig(
                    modelPath = modelFile.absolutePath,
                    backend = Backend.GPU(),
                    visionBackend = Backend.CPU(),
                    audioBackend = null,
                    maxNumTokens = safeTokens,
                    maxNumImages = wantImages,
                    cacheDir = cacheDir
                )
            )
        }
        candidates += Triple(
            "GPU+VISION_GPU",
            1,
            EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = Backend.GPU(),
                visionBackend = Backend.GPU(),
                audioBackend = null,
                maxNumTokens = safeTokens,
                maxNumImages = 1,
                cacheDir = cacheDir
            )
        )
        candidates += Triple(
            "GPU+VISION_CPU",
            1,
            EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = Backend.GPU(),
                visionBackend = Backend.CPU(),
                audioBackend = null,
                maxNumTokens = safeTokens,
                maxNumImages = 1,
                cacheDir = cacheDir
            )
        )
        candidates += Triple(
            "CPU_VISION_CPU",
            1,
            EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = Backend.CPU(),
                visionBackend = Backend.CPU(),
                audioBackend = null,
                maxNumTokens = safeTokens,
                maxNumImages = 1,
                cacheDir = cacheDir
            )
        )
        candidates += Triple(
            "CPU_COMPACT",
            1,
            EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = Backend.CPU(),
                visionBackend = Backend.CPU(),
                audioBackend = null,
                maxNumTokens = compactTokens,
                maxNumImages = 1,
                cacheDir = cacheDir
            )
        )

        var lastErr: String? = null
        for ((label, imgSlots, cfg) in candidates) {
            val attempt = runCatching {
                Engine(cfg).apply { initialize() }
            }
            val engine = attempt.getOrNull()
            if (engine != null) {
                engineHandle = engine
                loadedModelPath = modelFile.absolutePath
                activeBackendLabel = label
                activeMaxImages = imgSlots
                activeMaxTokens = cfg.maxNumTokens ?: safeTokens
                lastEngineError = null
                Log.i(TAG, "[GEMMA4_VISION] Mounted ${modelFile.name} via $label (maxImages=$imgSlots, cache=noBackup)")
                onLog("Mounted ${modelFile.name} [$label | KV=$imgSlots]")
                return engine
            } else {
                val msg = attempt.exceptionOrNull()?.message ?: "init failed"
                lastErr = "$label(img=$imgSlots): $msg"
                Log.w(TAG, "[GEMMA4_VISION] Mount attempt $label (img=$imgSlots) skipped: $msg")
            }
        }
        lastEngineError = lastErr
        onLog("❌ Failed mounting ${modelFile.name}: $lastErr")
        return null
    }

    private fun queryLiteRtGemmaMultimodalLocked(
        modelFile: File,
        annotatedJpegFile: File,
        prompt: String,
        enableThinking: Boolean = true,
        maxTokens: Int = 4096,
        onLog: (String) -> Unit
    ): String? {
        val engine = ensureLiteRtEngineMountedLocked(modelFile, maxTokens, onLog) ?: return null

        val primaryConvConfig = if (enableThinking) {
            ConversationConfig(
                samplerConfig = SamplerConfig(topK = 40, topP = 0.92, temperature = 0.2, seed = 42),
                thinkingConfig = ThinkingConfig(enableThinking = true, thinkingTokenBudget = 512)
            )
        } else {
            ConversationConfig(
                samplerConfig = SamplerConfig(topK = 40, topP = 0.92, temperature = 0.2, seed = 42)
            )
        }
        val convAttempt = runCatching {
            val conv = runCatching { engine.createConversation(primaryConvConfig) }
                .getOrElse { engine.createConversation(ConversationConfig()) }
            conv.use { session ->
                val msg = runCatching {
                    session.sendMessage(
                        Contents.of(listOf(Content.ImageFile(annotatedJpegFile.absolutePath), Content.Text(prompt)))
                    )
                }.getOrElse {
                    session.sendMessage(
                        Contents.of(listOf(Content.ImageBytes(annotatedJpegFile.readBytes()), Content.Text(prompt)))
                    )
                }
                extractFullResponseFromMessage(msg)
            }
        }
        val convText = convAttempt.getOrNull()
        if (!convText.isNullOrBlank()) {
            return convText
        }
        convAttempt.exceptionOrNull()?.let { err ->
            lastEngineError = "Conversation vision call failed: ${err.message}"
            Log.w(TAG, "[GEMMA4_VISION] Conversation call failed: ${err.message}", err)
            onLog("⚠️ Conversation fallback -> trying Session API (${err.message})")
        }

        // Secondary path: Low-level Session API with InputData.Image + InputData.Text
        val sessAttempt = runCatching {
            engine.createSession(
                SessionConfig(samplerConfig = SamplerConfig(topK = 40, topP = 0.92, temperature = 0.2, seed = 42))
            ).use { s ->
                s.generateContent(
                    listOf(
                        InputData.Image(annotatedJpegFile.readBytes()),
                        InputData.Text(prompt)
                    )
                )
            }
        }
        sessAttempt.exceptionOrNull()?.let { err ->
            lastEngineError = "Session vision call failed: ${err.message}"
            Log.w(TAG, "[GEMMA4_VISION] Session call failed: ${err.message}", err)
        }
        return sessAttempt.getOrNull()
    }

    private fun queryLiteRtGemmaTextLocked(
        modelFile: File,
        prompt: String,
        maxTokens: Int = 4096,
        onLog: (String) -> Unit
    ): String? {
        val engine = ensureLiteRtEngineMountedLocked(modelFile, maxTokens, onLog) ?: return null
        val convAttempt = runCatching {
            val cfg = ConversationConfig(
                samplerConfig = SamplerConfig(topK = 40, topP = 0.92, temperature = 0.35, seed = 42)
            )
            engine.createConversation(cfg).use { session ->
                val msg = session.sendMessage(Contents.of(prompt))
                extractFullResponseFromMessage(msg)
            }
        }
        val convText = convAttempt.getOrNull()
        if (!convText.isNullOrBlank()) return convText

        return runCatching {
            engine.createSession(SessionConfig()).use { s ->
                s.generateContent(listOf(InputData.Text(prompt)))
            }
        }.onFailure { err ->
            lastEngineError = "Text inference failed: ${err.message}"
            Log.w(TAG, "[GEMMA4_VISION] Text inference failed: ${err.message}")
        }.getOrNull()
    }

    private fun extractFullResponseFromMessage(msg: Message): String {
        val textBody = msg.contents.contents
            .mapNotNull { c -> (c as? Content.Text)?.text }
            .joinToString("\n")
            .ifBlank { msg.toString() }
            .trim()
        val thoughtChannel = (msg.channels["thought"] ?: msg.channels["thinking"]).orEmpty().trim()
        return if (thoughtChannel.isNotBlank() && !textBody.contains("<thought", ignoreCase = true)) {
            "<thought>\n$thoughtChannel\n</thought>\n$textBody"
        } else {
            textBody
        }
    }

    private fun queryOpenAiVisionEndpoint(
        endpointUrl: String,
        annotatedJpegFile: File,
        prompt: String
    ): String? {
        val b64 = Base64.encodeToString(annotatedJpegFile.readBytes(), Base64.NO_WRAP)
        val payload = buildJsonObject {
            put("model", "gemma-4-E2B-it")
            put("temperature", 0.2)
            put("max_tokens", 1600)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "image_url")
                            put("image_url", buildJsonObject {
                                put("url", "data:image/jpeg;base64,$b64")
                            })
                        })
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", prompt)
                        })
                    })
                })
            })
        }.toString()

        return executeOpenAiChatRequest(endpointUrl, payload)
    }

    private fun queryOpenAiTextEndpoint(endpointUrl: String, prompt: String): String? {
        val payload = buildJsonObject {
            put("model", "gemma-4-E2B-it")
            put("temperature", 0.35)
            put("max_tokens", 1400)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", prompt)
                })
            })
        }.toString()
        return executeOpenAiChatRequest(endpointUrl, payload)
    }

    private fun executeOpenAiChatRequest(endpointUrl: String, payload: String): String? {
        val req = Request.Builder()
            .url(endpointUrl)
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        httpClient.newCall(req).execute().use { res ->
            if (!res.isSuccessful) return null
            val body = res.body?.string() ?: return null
            val root = lenientJson.parseToJsonElement(body) as? JsonObject ?: return null
            val choices = root["choices"] as? JsonArray ?: return null
            val first = choices.firstOrNull() as? JsonObject ?: return null
            val msg = first["message"] as? JsonObject ?: return null
            return (msg["content"] as? JsonPrimitive)?.contentOrNull
        }
    }

    private fun buildOfflineHeuristicAnalysis(
        pageIndex: Int,
        panels: List<MangaNormalizedRect>,
        existingCharacters: List<MangaCharacterProfile>,
        modelWasInstalled: Boolean,
        diagnosticReason: String,
        storySummary: String = ""
    ): MangaVisionResponseParser.ParsedPageAnalysis {
        val fallbackChars = if (existingCharacters.isNotEmpty()) {
            existingCharacters.map { it.name }
        } else {
            listOf("NARRATOR", "PROTAGONIST", "RIVAL")
        }
        val panelsOut = panels.mapIndexed { pIdx, _ ->
            val guessedSpeaker = if (pIdx == 0 && pageIndex == 0) "NARRATOR" else "UNKNOWN"
            val conf = if (guessedSpeaker == "NARRATOR") 0.85f else 0.42f
            MangaVisionResponseParser.DiscoveredPanelSpec(
                panelIndex = pIdx,
                sceneMood = "manga_panel",
                thoughtTrace = if (modelWasInstalled) {
                    "BFS isolated Panel P${pIdx + 1}; Vision note: $diagnosticReason"
                } else {
                    "BFS isolated Panel P${pIdx + 1} on page ${pageIndex + 1}; awaiting Gemma 4 OCR or manual confirmation."
                },
                lines = listOf(
                    MangaVisionResponseParser.DiscoveredLineSpec(
                        bubbleIndex = 1,
                        speakerName = guessedSpeaker,
                        isNewCharacter = existingCharacters.none { it.name == guessedSpeaker },
                        gender = VoiceGender.ANDROGYNOUS,
                        visualDescription = "Manga panel subject #${pIdx + 1}",
                        voiceDesignPrompt = "Expressive anime character voice, clear close-mic clarity and dramatic manga pacing",
                        emotion = "neutral",
                        confidence = conf,
                        candidateSpeakers = fallbackChars,
                        rawText = "Page ${pageIndex + 1}, Panel ${pIdx + 1} line — tap to edit or confirm speaker.",
                        needsUserDecision = conf < 0.55f,
                        decisionReason = diagnosticReason
                    )
                )
            )
        }
        return MangaVisionResponseParser.ParsedPageAnalysis(
            thoughtTrace = "BFS sliced ${panels.size} panel(s) cleanly ($diagnosticReason).",
            storySummary = storySummary.ifBlank { "Page ${pageIndex + 1} prepared for Pre-Production review." },
            panels = panelsOut
        )
    }

    suspend fun unload() = mutex.withLock {
        unloadLocked()
    }

    private fun unloadLocked() {
        runCatching { engineHandle?.close() }
        engineHandle = null
        loadedModelPath = null
        activeBackendLabel = "NONE"
        activeMaxImages = 1
        activeMaxTokens = 4096
        Log.i(TAG, "[VRAM_EVICT] Unloaded Gemma 4 LiteRT Vision Engine")
    }

    private fun estimateApproxTokens(text: String): Int =
        ((text.length + 3) / 4).coerceAtLeast(1)

    private fun buildPageKvSharedPreface(
        pageIndex: Int,
        readingOrder: MangaReadingOrder,
        knownCharacters: List<MangaCharacterProfile>,
        storySummary: String,
        pageSynopsis: String,
        includeNonBubbleText: Boolean
    ): String {
        val roster = if (knownCharacters.isEmpty()) {
            "None yet — introduce consistent uppercase names (or NARRATOR for boxed captions)."
        } else {
            knownCharacters.take(12).joinToString("; ") { "${it.name}(${it.gender.tag})" }
        }
        val scope = if (includeNonBubbleText) {
            "Transcribe enclosed bubbles, narration boxes, AND visible SFX/floating text accurately via container_type."
        } else {
            "STRICT SPEECH-BUBBLE-ONLY: Transcribe ONLY enclosed speech bubbles, thought clouds, scream bursts, and narration boxes. IGNORE floating SFX, watermarks, page numbers, scanlation credits, and signatures."
        }
        return """
You are MIKA's Manga Dubbing Director scanning sequential cropped panels on Page ${pageIndex + 1}.
${readingOrder.vlmPromptDirective}
Story Bible: ${storySummary.ifBlank { "Keep cast identity consistent across outfits." }}
${if (pageSynopsis.isNotBlank()) "Page Beat: $pageSynopsis" else ""}
Known Cast: $roster
Rules: $scope
Trace pointed bubble tails, chibi speaker icons, and `--` continuations into `"tail_target"`; use speaker `"UNKNOWN"` + confidence `0.45` only if genuinely ambiguous.
NEVER invent placeholder names (MIKA, THE_MALE, THE_FEMALE, GIRL, BOY) — unnamed speakers stay "UNKNOWN"; narration boxes use "NARRATOR". Transcribe ONLY text visible in each cropped panel, never from other pages.
Respond with JSON ONLY matching:
{"mood":"action","thought":"bubble count + tails","lines":[{"bubble":1,"x_pos":0.85,"y_pos":0.22,"container_type":"speech_bubble","tail_target":"CHAR","speaker":"CHAR","is_new_character":false,"gender":"female","visual_description":"","voice_design_prompt":"","emotion":"neutral","confidence":0.90,"candidate_speakers":[],"text":"..."}]}
Output {"mood":"silent_panel","lines":[]} if a panel has no valid spoken lines.
""".trim()
    }

    private fun buildPageKvTurnPrompt(
        panelIndex: Int,
        totalPanelsOnPage: Int,
        newlyDiscoveredChars: List<MangaCharacterProfile>,
        recentDialogueContext: List<String>,
        includeNonBubbleText: Boolean
    ): String {
        val deltaRoster = if (newlyDiscoveredChars.isEmpty()) "" else
            "Newly seen cast: " + newlyDiscoveredChars.joinToString("; ") { "${it.name}(${it.gender.tag})" } + "\n"
        val prev = recentDialogueContext.takeLast(3).joinToString(" | ").ifBlank { "(none)" }
        val modeNote = if (includeNonBubbleText) "ALL-TEXT" else "SPEECH-BUBBLE-ONLY (skip SFX/credits)"
        return """
Scan Panel ${panelIndex + 1}/$totalPanelsOnPage [$modeNote].
${deltaRoster}Recent lines: $prev
Count every valid bubble (including small edge bubbles), trace each tail, sort by reading order, output JSON ONLY.
""".trim()
    }

    private fun buildCastingPrefillPreface(seriesTitle: String, storySummary: String): String = """
You are casting canonical voices for the anime dub of "$seriesTitle" using Qwen3-TTS 1.7B VoiceDesign.
Story Context: ${storySummary.ifBlank { "Manga series adaptation" }}
Every character needs structured VoiceDesign attributes (gender, age, pitch, timbres, pace, delivery, accent, acoustics, persona_role, nuance) plus a 25-40 word voice_design_prompt specifying enduring vocal identity (no character proper names inside voice_design_prompt). Output valid JSON ONLY.
""".trim()

    private fun queryBatchedPrefillCloneLocked(
        modelFile: File,
        sharedPreface: String,
        chunkPrompts: List<String>,
        maxTokens: Int,
        onLog: (String) -> Unit
    ): List<String>? {
        val engine = ensureLiteRtEngineMountedLocked(modelFile, maxTokens, onLog, 1) ?: return null
        return runCatching {
            val baseSession = engine.createSession(
                SessionConfig(samplerConfig = SamplerConfig(topK = 40, topP = 0.92, temperature = 0.3, seed = 42))
            )
            baseSession.use { root ->
                root.runPrefill(listOf(InputData.Text(sharedPreface)))
                chunkPrompts.map { chunkPrompt ->
                    root.clone().use { branch ->
                        branch.generateContent(listOf(InputData.Text(chunkPrompt)))
                    }
                }
            }
        }.onFailure { err ->
            Log.w(TAG, "[KV_CLONE] Batched casting prefill/clone fallback: ${err.message}")
        }.getOrNull()
    }
}
