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
        preferredTier: MangaVisionModelTier = MangaVisionModelTier.AUTO,
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

        val e2b = explicitFiles.firstOrNull { it.name.contains("E2B", ignoreCase = true) }
            ?: candidateDirs.firstNotNullOfOrNull { dir ->
                File(dir, GEMMA_PRIMARY_FILE).takeIf { it.isFile && it.length() > 1024L * 1024L }
            }
        val e4b = explicitFiles.firstOrNull { it.name.contains("E4B", ignoreCase = true) }
            ?: candidateDirs.firstNotNullOfOrNull { dir ->
                File(dir, GEMMA_E4B_FILE).takeIf { it.isFile && it.length() > 1024L * 1024L }
            }

        val preferred = when (preferredTier) {
            MangaVisionModelTier.GEMMA4_E4B -> e4b ?: e2b
            MangaVisionModelTier.GEMMA4_E2B, MangaVisionModelTier.AUTO -> e2b ?: e4b
        }
        if (preferred != null) return preferred

        explicitFiles.firstOrNull()?.let { return it }

        return candidateDirs
            .filter { it.isDirectory }
            .flatMap { it.listFiles()?.toList().orEmpty() }
            .firstOrNull {
                it.isFile &&
                    it.name.contains("gemma", ignoreCase = true) &&
                    it.extension.equals("litertlm", ignoreCase = true) &&
                    it.length() > 1024L * 1024L
            }
    }

    fun isModelTierInstalled(
        tier: MangaVisionModelTier,
        customPaths: List<String> = emptyList()
    ): Boolean = when (tier) {
        MangaVisionModelTier.GEMMA4_E4B -> {
            val f = resolveInstalledGemmaModelFile(MangaVisionModelTier.GEMMA4_E4B, customPaths)
            f != null && f.name.contains("E4B", ignoreCase = true)
        }
        MangaVisionModelTier.GEMMA4_E2B -> {
            val f = resolveInstalledGemmaModelFile(MangaVisionModelTier.GEMMA4_E2B, customPaths)
            f != null && f.name.contains("E2B", ignoreCase = true)
        }
        MangaVisionModelTier.AUTO -> resolveInstalledGemmaModelFile(MangaVisionModelTier.AUTO, customPaths) != null
    }

    /**
     * Scales a manga page down to <=1024px and stamps numbered `[P1]`, `[P2]` Set-of-Mark frames
     * over the BFS-detected panels so Gemma 4 never has to guess pixel bounding boxes.
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
     * Phase 2A: Analyzes one manga page with Gemma 4 E2B/E4B LiteRT-LM (with Vision + Thinking)
     * or LAN Power-User Vision URL, extracting dialogue lines, speakers, mood, and voice traits.
     */
    suspend fun analyzeMangaPage(
        pageIndex: Int,
        pageBitmap: Bitmap,
        panels: List<MangaNormalizedRect>,
        readingOrder: MangaReadingOrder,
        existingCharacters: List<MangaCharacterProfile>,
        recentDialogueContext: List<String>,
        powerUserVisionUrl: String,
        preferredVisionModel: MangaVisionModelTier = MangaVisionModelTier.AUTO,
        customModelPaths: List<String> = emptyList(),
        onLog: (String) -> Unit = {}
    ): Pair<MangaVisionResponseParser.ParsedPageAnalysis, String> = withContext(Dispatchers.IO) {
        val annotatedFile = File(context.cacheDir, "manga_vision_page_$pageIndex.jpg")
        renderAnnotatedPageForVision(pageBitmap, panels, annotatedFile)
        val prompt = buildGemmaDirectorPrompt(
            pageIndex = pageIndex,
            panelCount = panels.size,
            readingOrder = readingOrder,
            existingCharacters = existingCharacters,
            recentDialogueContext = recentDialogueContext
        )

        // Tier 1: Optional Power-User External OpenAI-Compatible Vision Server
        if (powerUserVisionUrl.isNotBlank()) {
            onLog("Querying LAN Vision Endpoint for Page ${pageIndex + 1}...")
            val rawRemote = runCatching { queryOpenAiVisionEndpoint(powerUserVisionUrl.trim(), annotatedFile, prompt) }
                .onFailure { onLog("LAN Vision error: ${it.message}") }
                .getOrNull()
            if (!rawRemote.isNullOrBlank()) {
                val parsed = MangaVisionResponseParser.parsePageAnalysis(rawRemote, panels.size, existingCharacters)
                if (parsed.panels.isNotEmpty()) {
                    return@withContext parsed to "LAN Vision Endpoint"
                }
            }
        }

        // Tier 2: On-Device Google Gemma 4 E2B / E4B LiteRT-LM (with direct Vision + Thinking bindings)
        val localModel = resolveInstalledGemmaModelFile(preferredVisionModel, customModelPaths)
        if (localModel != null) {
            val modelLabel = if (localModel.name.contains("E4B", ignoreCase = true)) {
                "Gemma 4 E4B LiteRT"
            } else {
                "Gemma 4 E2B LiteRT"
            }
            onLog("Mounting $modelLabel (${localModel.name})...")

            val rawLocal = mutex.withLock {
                queryLiteRtGemmaMultimodalLocked(localModel, annotatedFile, prompt, onLog)
            }
            if (!rawLocal.isNullOrBlank()) {
                val parsed = MangaVisionResponseParser.parsePageAnalysis(rawLocal, panels.size, existingCharacters)
                if (parsed.panels.isNotEmpty()) {
                    return@withContext parsed to "$modelLabel ($activeBackendLabel)"
                }
                // Compact repair attempt if Gemma output freeform text instead of structured JSON
                onLog("Re-prompting $modelLabel for strict JSON on Page ${pageIndex + 1}...")
                val repairPrompt = buildCompactRepairPrompt(panels.size, rawLocal.take(600))
                val repairedRaw = mutex.withLock {
                    queryLiteRtGemmaTextLocked(localModel, repairPrompt, onLog)
                }
                if (!repairedRaw.isNullOrBlank()) {
                    val repairedParsed = MangaVisionResponseParser.parsePageAnalysis(repairedRaw, panels.size, existingCharacters)
                    if (repairedParsed.panels.isNotEmpty()) {
                        return@withContext repairedParsed to "$modelLabel ($activeBackendLabel)"
                    }
                }
                onLog("⚠️ $modelLabel returned unparseable layout on Page ${pageIndex + 1}; raw preview: ${rawLocal.take(120)}")
            } else {
                onLog("⚠️ $modelLabel returned empty output on Page ${pageIndex + 1}: ${lastEngineError ?: "unknown error"}")
            }
        } else {
            onLog("ℹ️ No local Gemma 4 .litertlm found in filesDir/models/litert (checked E2B/E4B)")
        }

        // Tier 3: Offline Heuristic Fallback (pre-populates panels cleanly for Pre-Production review)
        val diagReason = lastEngineError ?: if (localModel != null) {
            "Gemma 4 (${localModel.name}) produced no dialogue JSON — review panels below"
        } else {
            "Install SUB-11V Gemma 4 E2B [Recommended] or SUB-11X E4B for Auto-OCR"
        }
        val fallback = buildOfflineHeuristicAnalysis(
            pageIndex = pageIndex,
            panels = panels,
            existingCharacters = existingCharacters,
            modelWasInstalled = localModel != null,
            diagnosticReason = diagReason
        )
        fallback to if (localModel == null) {
            "Offline Layout Slicer (No Gemma 4 .litertlm)"
        } else {
            "Fallback Review ($diagReason)"
        }
    }

    /**
     * Phase 2B: Text-only Qwen3 1.7B VoiceDesign Casting Director.
     * Runs while Gemma 4 is already mounted in memory (zero extra model load) to refine every
     * character's acoustic Qwen3 VoiceDesign prompt, speaking cadence, and pitch scale from
     * their visual traits and spoken dialogue in this chapter.
     */
    suspend fun craftQwenVoiceDesignsForRoster(
        seriesTitle: String,
        storySummary: String,
        characters: List<MangaCharacterProfile>,
        sampleLinesBySpeaker: Map<String, List<String>>,
        preferredVisionModel: MangaVisionModelTier = MangaVisionModelTier.AUTO,
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
                rawCasting = mutex.withLock {
                    queryLiteRtGemmaTextLocked(localModel, castingPrompt, onLog)
                }
            }
        }

        if (!rawCasting.isNullOrBlank()) {
            val updated = MangaVisionResponseParser.parseVoiceDesignRoster(rawCasting, characters)
            onLog("🎭 Gemma crafted ${updated.size} Qwen3 VoiceDesign casting profile(s)")
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
Cyan boxes outline the exact reading-order panels labeled P1..P$panelCount.

KNOWN SERIES CHARACTER ROSTER:
$rosterSummary

RECENT DIALOGUE CONTEXT:
$contextLines

TASK:
1. First think carefully inside `<thought>...</thought>` about each panel P1..P$panelCount:
   - Who appears visually in the panel?
   - Read every speech bubble / narration box / SFX shout in proper ${readingOrder.label} reading order.
   - Follow speech bubble tails to identify the speaker (or use NARRATOR for rectangular narration boxes).
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
- Only set `"speaker": "UNKNOWN"` and `"confidence": 0.45` if it is genuinely impossible to tell which of two characters spoke without asking the user.
""".trim()
    }

    private fun buildCompactRepairPrompt(panelCount: Int, previousSnippet: String): String = """
Convert your previous manga page notes into strict JSON matching:
{"story_summary":"","panels":[{"panel_index":0,"scene_mood":"action","thought":"","lines":[{"bubble":1,"speaker":"NARRATOR","is_new_character":true,"gender":"male","visual_description":"","voice_design_prompt":"Clear dramatic anime voice","emotion":"neutral","confidence":0.85,"candidate_speakers":["NARRATOR"],"text":"Dialogue line"}]}]}
Valid panel_index range: 0..${maxOf(0, panelCount - 1)}. Output JSON ONLY.

Notes:
$previousSnippet
""".trim()

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
        onLog: (String) -> Unit
    ): Engine? {
        if (engineHandle != null && loadedModelPath == modelFile.absolutePath) {
            return engineHandle
        }
        unloadLocked()
        val cacheDir = File(context.cacheDir, "gemma_litert_cache").apply { mkdirs() }.absolutePath

        val configs = listOf(
            "GPU+VISION_GPU" to EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = Backend.GPU(),
                visionBackend = Backend.GPU(),
                audioBackend = null,
                maxNumTokens = 4096,
                maxNumImages = 1,
                cacheDir = cacheDir
            ),
            "GPU+VISION_CPU" to EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = Backend.GPU(),
                visionBackend = Backend.CPU(),
                audioBackend = null,
                maxNumTokens = 4096,
                maxNumImages = 1,
                cacheDir = cacheDir
            ),
            "CPU_VISION_CPU" to EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = Backend.CPU(),
                visionBackend = Backend.CPU(),
                audioBackend = null,
                maxNumTokens = 4096,
                maxNumImages = 1,
                cacheDir = cacheDir
            ),
            "CPU_COMPACT" to EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = Backend.CPU(),
                visionBackend = Backend.CPU(),
                audioBackend = null,
                maxNumTokens = 2048,
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
        onLog: (String) -> Unit
    ): String? {
        val engine = ensureLiteRtEngineMountedLocked(modelFile, onLog) ?: return null

        // Primary path: Conversation API with ThinkingConfig enabled
        val convConfigWithThinking = ConversationConfig(
            samplerConfig = SamplerConfig(topK = 40, topP = 0.92, temperature = 0.2, seed = 42),
            thinkingConfig = ThinkingConfig(enableThinking = true, thinkingTokenBudget = 1024)
        )
        val convAttempt = runCatching {
            val conv = runCatching { engine.createConversation(convConfigWithThinking) }
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
        onLog: (String) -> Unit
    ): String? {
        val engine = ensureLiteRtEngineMountedLocked(modelFile, onLog) ?: return null
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
