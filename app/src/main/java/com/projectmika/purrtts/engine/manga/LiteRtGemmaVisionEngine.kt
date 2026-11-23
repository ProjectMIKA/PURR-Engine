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
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
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
     * Crops a single BFS-detected panel with 1.5% bubble-safe padding and scales up to 960px
     * so on-device VLMs see sharp, readable speech bubbles inside that exact panel.
     */
    fun renderPanelCropForVision(
        pageBitmap: Bitmap,
        panelRect: MangaNormalizedRect,
        targetFile: File
    ) {
        val padFrac = 0.015f
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
            maxSide > 960f -> 960f / maxSide
            maxSide < 560f -> minOf(2.0f, 640f / maxSide)
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
                recentDialogueContext = recentDialogueContext
            )
            onLog("Querying LAN Vision Endpoint for Page ${pageIndex + 1}...")
            val rawRemote = runCatching { queryOpenAiVisionEndpoint(powerUserVisionUrl.trim(), annotatedFile, prompt) }
                .onFailure { onLog("LAN Vision error: ${it.message}") }
                .getOrNull()
            runCatching { annotatedFile.delete() }
            if (!rawRemote.isNullOrBlank()) {
                val parsed = MangaVisionResponseParser.parsePageAnalysis(rawRemote, targetPanels.size, existingCharacters)
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
            val panelSpecs = ArrayList<MangaVisionResponseParser.DiscoveredPanelSpec>(targetPanels.size)
            val thoughtSnippets = ArrayList<String>()
            var mountFailed = false

            for ((panelIdx, rect) in targetPanels.withIndex()) {
                val cropFile = File(context.cacheDir, "manga_vision_crop_${pageIndex}_${panelIdx}.jpg")
                renderPanelCropForVision(pageBitmap, rect, cropFile)
                val panelPrompt = buildSinglePanelDirectorPrompt(
                    pageIndex = pageIndex,
                    panelIndex = panelIdx,
                    totalPanelsOnPage = targetPanels.size,
                    readingOrder = readingOrder,
                    existingCharacters = existingCharacters,
                    recentDialogueContext = runningContext
                )

                val rawPanel = mutex.withLock {
                    queryLiteRtGemmaMultimodalLocked(
                        modelFile = localModel,
                        annotatedJpegFile = cropFile,
                        prompt = panelPrompt,
                        enableThinking = activeTier.supportsThinking,
                        maxTokens = activeTier.maxContextTokens,
                        onLog = onLog
                    )
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
                    existingCharacters = existingCharacters
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
                            existingCharacters = existingCharacters
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
                }
                if (panelSpec.thoughtTrace.isNotBlank()) {
                    thoughtSnippets += "P${panelIdx + 1}: ${panelSpec.thoughtTrace.take(80)}"
                }
                panelSpecs += panelSpec
                onPanelProgress(panelIdx + 1, targetPanels.size, "${panelSpec.lines.size} bubble(s)")
            }

            if (!mountFailed && panelSpecs.isNotEmpty()) {
                val combinedThought = thoughtSnippets.joinToString(" | ").take(360)
                return@withContext MangaVisionResponseParser.ParsedPageAnalysis(
                    thoughtTrace = combinedThought,
                    storySummary = "",
                    panels = panelSpecs
                ) to "${activeTier.shortLabel} ($activeBackendLabel)"
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
            diagnosticReason = diagReason
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

        var rawCasting: String? = null
        if (powerUserVisionUrl.isNotBlank()) {
            rawCasting = runCatching { queryOpenAiTextEndpoint(powerUserVisionUrl.trim(), castingPrompt) }.getOrNull()
        }
        if (rawCasting.isNullOrBlank()) {
            val localModel = resolveInstalledGemmaModelFile(preferredVisionModel, customModelPaths)
            if (localModel != null) {
                val activeTier = resolveTierForFile(localModel, preferredVisionModel)
                rawCasting = mutex.withLock {
                    queryLiteRtGemmaTextLocked(localModel, castingPrompt, activeTier.maxContextTokens, onLog)
                }
            }
        }

        if (!rawCasting.isNullOrBlank()) {
            val updated = MangaVisionResponseParser.parseVoiceDesignRoster(rawCasting, characters)
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
        recentDialogueContext: List<String>
    ): String {
        val rosterSummary = if (existingCharacters.isEmpty()) {
            "None yet — name speakers consistently (e.g. NARRATOR, PROTAGONIST, HEROINE, RIVAL, or visible name)."
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

        return """
You are scanning ONE zoomed comic/manga panel: Page ${pageIndex + 1}, Panel ${panelIndex + 1} of $totalPanelsOnPage.
${readingOrder.vlmPromptDirective}

KNOWN CAST ROSTER:
$rosterSummary

PREVIOUS DIALOGUE:
$contextLines

TASK:
Transcribe EVERY SINGLE speech bubble, thought cloud, shout burst, and rectangular narration caption visible inside THIS panel image, ordered strictly by ${readingOrder.label}. Do not skip small bubbles.
Trace bubble tails carefully to identify each speaker (or NARRATOR for boxed captions).

Output ONLY valid JSON matching this schema:
{
  "mood": "intense battle | warm romance | rain gloom | calm mystery | action | silent_panel",
  "thought": "brief note tracing bubble tails inside this panel",
  "lines": [
    {
      "bubble": 1,
      "speaker": "CHARACTER_NAME or NARRATOR or PROTAGONIST",
      "is_new_character": true,
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
If there is NO spoken text in this panel, output: {"mood":"silent_panel","lines":[]}.
Only use "speaker": "UNKNOWN" with "confidence": 0.45 when the bubble tail truly leaves speaker ambiguous.
""".trim()
    }

    private fun buildCompactSinglePanelRepairPrompt(previousSnippet: String): String = """
Convert these single-panel manga transcription notes into strict JSON matching:
{"mood":"action","lines":[{"bubble":1,"speaker":"NARRATOR","is_new_character":false,"gender":"male","emotion":"neutral","confidence":0.85,"text":"Dialogue text"}]}
Output JSON ONLY.

Notes:
$previousSnippet
""".trim()

    private fun buildGemmaDirectorPrompt(
        pageIndex: Int,
        panelCount: Int,
        readingOrder: MangaReadingOrder,
        existingCharacters: List<MangaCharacterProfile>,
        recentDialogueContext: List<String>
    ): String {
        val rosterSummary = if (existingCharacters.isEmpty()) {
            "None yet — name speakers consistently (e.g. NARRATOR, PROTAGONIST, HEROINE, RIVAL, or their actual manga name if visible)."
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

        return """
You are MIKA's Manga Dubbing & Voice Casting Director scanning Page ${pageIndex + 1} (${readingOrder.label}).
${readingOrder.vlmPromptDirective}
Cyan boxes outline the exact reading-order panels labeled P1..P$panelCount.

KNOWN SERIES CHARACTER ROSTER:
$rosterSummary

RECENT DIALOGUE CONTEXT:
$contextLines

TASK:
1. First think carefully inside `<thought>...</thought>` about each panel P1..P$panelCount:
   - Read every speech bubble / narration box / SFX shout in strict ${readingOrder.label} order.
   - Follow speech bubble tails to identify each speaker (or NARRATOR for boxed captions).
2. Immediately output ONLY a valid JSON object matching this schema:
{
  "story_summary": "1-sentence running summary update",
  "panels": [
    {
      "panel_index": 0,
      "scene_mood": "intense battle | warm romance | rain gloom | calm mystery | action",
      "thought": "brief reasoning for this panel",
      "lines": [
        {
          "bubble": 1,
          "speaker": "CHARACTER_NAME or NARRATOR or PROTAGONIST",
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
- Transcribe ALL spoken text visible inside bubbles/narration boxes. Skip empty panels with `"lines": []`.
- Only set `"speaker": "UNKNOWN"` and `"confidence": 0.45` if it is genuinely impossible to tell which speaker owns a bubble.
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
You are casting voices for the dubbed anime adaptation of "$seriesTitle" using Qwen3-TTS 1.7B VoiceDesign.
Story context: ${storySummary.ifBlank { "Chapter 1" }}

Below is the discovered cast roster. For EVERY character, craft a rich acoustic VoiceDesign instruction specifically optimized for Qwen3-TTS 1.7B VoiceDesign (describe vocal pitch register, texture/raspy/breathy/resonant quality, age archetype, tempo, and dramatic delivery — 15 to 30 words, no character proper names inside the voice_design_prompt).

ROSTER:
$rosterBlock

Output ONLY valid JSON:
{
  "characters": [
    {
      "name": "EXACT_CHARACTER_NAME",
      "gender": "male | female | androgynous",
      "visual_description": "refined 1-line character summary",
      "voice_design_prompt": "Crisp low-baritone raspy warrior voice with fierce gravelly projection, punchy dramatic anime cadence, slight growl on emphasis",
      "speed_scale": 1.0,
      "pitch_scale": 1.0
    }
  ]
}
Speed_scale range: 0.85..1.15. Pitch_scale range: 0.90..1.10.
""".trim()
    }

    private fun ensureLiteRtEngineMountedLocked(
        modelFile: File,
        maxTokens: Int = 4096,
        onLog: (String) -> Unit
    ): Engine? {
        if (engineHandle != null && loadedModelPath == modelFile.absolutePath) {
            return engineHandle
        }
        unloadLocked()
        val cacheDir = File(context.cacheDir, "gemma_litert_cache").apply { mkdirs() }.absolutePath
        val safeTokens = maxTokens.coerceIn(1024, 4096)
        val compactTokens = minOf(safeTokens, 2048)

        val configs = listOf(
            "GPU+VISION_GPU" to EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = Backend.GPU(),
                visionBackend = Backend.GPU(),
                audioBackend = null,
                maxNumTokens = safeTokens,
                maxNumImages = 1,
                cacheDir = cacheDir
            ),
            "GPU+VISION_CPU" to EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = Backend.GPU(),
                visionBackend = Backend.CPU(),
                audioBackend = null,
                maxNumTokens = safeTokens,
                maxNumImages = 1,
                cacheDir = cacheDir
            ),
            "CPU_VISION_CPU" to EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = Backend.CPU(),
                visionBackend = Backend.CPU(),
                audioBackend = null,
                maxNumTokens = safeTokens,
                maxNumImages = 1,
                cacheDir = cacheDir
            ),
            "CPU_COMPACT" to EngineConfig(
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
        for ((label, cfg) in configs) {
            val attempt = runCatching {
                Engine(cfg).apply { initialize() }
            }
            val engine = attempt.getOrNull()
            if (engine != null) {
                engineHandle = engine
                loadedModelPath = modelFile.absolutePath
                activeBackendLabel = label
                lastEngineError = null
                Log.i(TAG, "[GEMMA4_VISION] Mounted ${modelFile.name} via $label")
                onLog("Mounted ${modelFile.name} [$label]")
                return engine
            } else {
                val msg = attempt.exceptionOrNull()?.message ?: "init failed"
                lastErr = "$label: $msg"
                Log.w(TAG, "[GEMMA4_VISION] Mount attempt $label skipped: $msg")
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
        diagnosticReason: String
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
            storySummary = "Page ${pageIndex + 1} prepared for Pre-Production review.",
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
        Log.i(TAG, "[VRAM_EVICT] Unloaded Gemma 4 LiteRT Vision Engine")
    }
}
