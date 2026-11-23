package com.projectmika.purrtts.engine.manga

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.Base64
import android.util.Log
import com.projectmika.purrtts.core.manga.MangaBfsPanelSlicer
import com.projectmika.purrtts.core.manga.MangaCharacterProfile
import com.projectmika.purrtts.core.manga.MangaNormalizedRect
import com.projectmika.purrtts.core.manga.MangaReadingOrder
import com.projectmika.purrtts.core.manga.MangaVisionModelTier
import com.projectmika.purrtts.core.manga.MangaVisionResponseParser
import com.projectmika.purrtts.core.purr.VoiceGender
import java.io.ByteArrayOutputStream
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
 * ✨ GEMMA 4 E2B LITERT-LM MULTIMODAL VISION & THINKING DIRECTOR (TOOL-11 / SUB-11V) ✨
 *
 * Mounts `gemma-4-E2B-it-gpu.litertlm` via Google AI Edge LiteRT-LM (`litertlm-android`)
 * on GPU/CPU with reflection-safe API binding, annotates sliced manga panels with visual
 * `[P1..PN]` Set-of-Mark badges, collects chain-of-thought reasoning + structured dialogue JSON,
 * and supports both an optional power-user OpenAI-compatible Vision URL and an offline fallback
 * so Pre-Production always completes cleanly and VRAM is evicted before TTS synthesis starts.
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
    private var engineHandle: AutoCloseable? = null
    private var activeBackendLabel: String = "NONE"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

    fun resolveInstalledGemmaModelFile(
        preferredTier: MangaVisionModelTier = MangaVisionModelTier.AUTO
    ): File? {
        val litertDir = File(context.filesDir, "models/litert")
        if (!litertDir.isDirectory) return null
        val e2b = File(litertDir, GEMMA_PRIMARY_FILE).takeIf { it.isFile && it.length() > 1024L * 1024L }
        val e4b = File(litertDir, GEMMA_E4B_FILE).takeIf { it.isFile && it.length() > 1024L * 1024L }
        val preferred = when (preferredTier) {
            MangaVisionModelTier.GEMMA4_E4B -> e4b ?: e2b
            MangaVisionModelTier.GEMMA4_E2B, MangaVisionModelTier.AUTO -> e2b ?: e4b
        }
        if (preferred != null) return preferred
        return litertDir.listFiles()
            ?.firstOrNull {
                it.isFile &&
                    it.name.contains("gemma", ignoreCase = true) &&
                    it.extension.equals("litertlm", ignoreCase = true) &&
                    it.length() > 1024L * 1024L
            }
    }

    fun isModelTierInstalled(tier: MangaVisionModelTier): Boolean {
        val litertDir = File(context.filesDir, "models/litert")
        return when (tier) {
            MangaVisionModelTier.GEMMA4_E4B -> File(litertDir, GEMMA_E4B_FILE).let { it.isFile && it.length() > 1024L * 1024L }
            MangaVisionModelTier.GEMMA4_E2B -> File(litertDir, GEMMA_PRIMARY_FILE).let { it.isFile && it.length() > 1024L * 1024L }
            MangaVisionModelTier.AUTO -> resolveInstalledGemmaModelFile(MangaVisionModelTier.AUTO) != null
        }
    }

    val isOnDeviceGemmaReady: Boolean
        get() = resolveInstalledGemmaModelFile() != null

    /**
     * Scales a manga page down to <=1024px and stamps numbered `[P1]`, `[P2]` Set-of-Mark frames
     * over the BFS-detected panels so Gemma 4 E2B never has to guess pixel bounding boxes.
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
            scaled.compress(Bitmap.CompressFormat.JPEG, 88, fos)
        }
        if (scaled !== pageBitmap) {
            scaled.recycle()
        }
    }

    /**
     * Analyzes one manga page with Gemma 4 E2B LiteRT (or Power-User Vision Endpoint / offline
     * fallback) using the numbered panel regions and prior series character memory.
     */
    suspend fun analyzeMangaPage(
        pageIndex: Int,
        pageBitmap: Bitmap,
        panels: List<MangaNormalizedRect>,
        readingOrder: MangaReadingOrder,
        existingCharacters: List<MangaCharacterProfile>,
        recentDialogueContext: List<String>,
        powerUserVisionUrl: String,
        preferredVisionModel: MangaVisionModelTier = MangaVisionModelTier.AUTO
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

        // Tier 1: Optional Power-User External OpenAI-Compatible Vision Server (if explicitly configured)
        if (powerUserVisionUrl.isNotBlank()) {
            val rawRemote = runCatching { queryOpenAiVisionEndpoint(powerUserVisionUrl.trim(), annotatedFile, prompt) }.getOrNull()
            if (!rawRemote.isNullOrBlank()) {
                val parsed = MangaVisionResponseParser.parsePageAnalysis(rawRemote, panels.size, existingCharacters)
                if (parsed.panels.isNotEmpty()) {
                    return@withContext parsed to "LAN Vision Endpoint"
                }
            }
        }

        // Tier 2: On-Device Google Gemma 4 E2B / E4B LiteRT-LM (with Thinking Mode)
        val localModel = resolveInstalledGemmaModelFile(preferredVisionModel)
        if (localModel != null) {
            val modelLabel = if (localModel.name.contains("E4B", ignoreCase = true)) {
                "Gemma 4 E4B LiteRT"
            } else {
                "Gemma 4 E2B LiteRT"
            }
            val rawLocal = mutex.withLock {
                queryLiteRtGemmaOnDevice(localModel, annotatedFile, prompt)
            }
            if (!rawLocal.isNullOrBlank()) {
                val parsed = MangaVisionResponseParser.parsePageAnalysis(rawLocal, panels.size, existingCharacters)
                if (parsed.panels.isNotEmpty()) {
                    return@withContext parsed to "$modelLabel ($activeBackendLabel)"
                }
            }
        }

        // Tier 3: Offline Heuristic Fallback (pre-populates panels cleanly for Pre-Production review)
        val fallback = buildOfflineHeuristicAnalysis(
            pageIndex = pageIndex,
            panels = panels,
            existingCharacters = existingCharacters
        )
        fallback to if (localModel == null) {
            "Offline Layout Slicer (Install SUB-11V Gemma 4 E2B [Rec] or SUB-11X E4B)"
        } else {
            "Gemma Fallback Review"
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
            "None yet — create clear character names (e.g. HERO_1, RIVAL_1, NARRATOR) when discovered."
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
You are MIKA's Manga Dubbing Director scanning Page ${pageIndex + 1} (${readingOrder.label}).
Cyan boxes label the exact reading-order panels [P1..P$panelCount].

KNOWN SERIES CHARACTER ROSTER:
$rosterSummary

RECENT DIALOGUE CONTEXT:
$contextLines

TASK:
First use a <thought>...</thought> block to trace who appears in each numbered panel P1..P$panelCount, which direction each speech bubble tail points, the scene mood, and who is speaking.
Then output ONLY a valid JSON object matching this exact schema:
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
          "speaker": "CHARACTER_NAME or NARRATOR or UNKNOWN",
          "is_new_character": false,
          "gender": "male | female | androgynous",
          "visual_description": "short appearance traits if new character",
          "voice_design_prompt": "Qwen3 VoiceDesign acoustic prompt for timbre, pitch, energy",
          "emotion": "fierce | calm | sad | joyful | shy | angry | neutral",
          "confidence": 0.92,
          "candidate_speakers": ["CHAR_A", "CHAR_B"],
          "text": "Exact dialogue text in reading order"
        }
      ]
    }
  ]
}
If a floating/tail-less speech bubble's speaker is ambiguous, set "speaker": "UNKNOWN", "confidence": 0.45, and list plausible "candidate_speakers" so the user decides in Pre-Production.
""".trim()
    }

    private fun ensureLiteRtEngineMountedLocked(modelFile: File): AutoCloseable? {
        if (engineHandle != null && loadedModelPath == modelFile.absolutePath) {
            return engineHandle
        }
        unloadLocked()
        val cacheDir = File(context.cacheDir, "gemma_litert_cache").apply { mkdirs() }.absolutePath
        val gpuAttempt = tryInitLiteRtEngine(modelFile.absolutePath, cacheDir, preferGpu = true)
        if (gpuAttempt != null) {
            engineHandle = gpuAttempt
            loadedModelPath = modelFile.absolutePath
            activeBackendLabel = "GPU"
            return gpuAttempt
        }
        val cpuAttempt = tryInitLiteRtEngine(modelFile.absolutePath, cacheDir, preferGpu = false)
        if (cpuAttempt != null) {
            engineHandle = cpuAttempt
            loadedModelPath = modelFile.absolutePath
            activeBackendLabel = "CPU"
            return cpuAttempt
        }
        return null
    }

    private fun tryInitLiteRtEngine(
        modelPath: String,
        cachePath: String,
        preferGpu: Boolean
    ): AutoCloseable? = runCatching {
        val backendBase = Class.forName("com.google.ai.edge.litertlm.Backend")
        val targetClass = Class.forName(
            if (preferGpu) "com.google.ai.edge.litertlm.Backend\$GPU"
            else "com.google.ai.edge.litertlm.Backend\$CPU"
        )
        val backendInstance = targetClass.getDeclaredConstructor().newInstance()
        val cpuInstance = Class.forName("com.google.ai.edge.litertlm.Backend\$CPU")
            .getDeclaredConstructor().newInstance()

        val configClass = Class.forName("com.google.ai.edge.litertlm.EngineConfig")
        val ctors = configClass.constructors.sortedBy { it.parameterCount }
        var builtConfig: Any? = null
        for (ctor in ctors) {
            val params = ctor.parameterTypes
            if (params.isEmpty() || params[0] != String::class.java) continue
            val args = arrayOfNulls<Any?>(params.size)
            args[0] = modelPath
            var backendSlots = 0
            var stringSlots = 1
            var valid = true
            for (i in 1 until params.size) {
                val pt = params[i]
                when {
                    backendBase.isAssignableFrom(pt) -> {
                        args[i] = if (backendSlots == 0) backendInstance else if (preferGpu) backendInstance else cpuInstance
                        backendSlots++
                    }
                    pt == String::class.java -> {
                        args[i] = if (stringSlots == 1) cachePath else null
                        stringSlots++
                    }
                    pt == Int::class.javaPrimitiveType || pt == Int::class.javaObjectType -> args[i] = 2048
                    pt == Boolean::class.javaPrimitiveType || pt == Boolean::class.javaObjectType -> args[i] = true
                    else -> valid = false
                }
            }
            if (valid) {
                builtConfig = runCatching { ctor.newInstance(*args) }.getOrNull()
                if (builtConfig != null) break
            }
        }
        if (builtConfig == null) return null

        val engineClass = Class.forName("com.google.ai.edge.litertlm.Engine")
        val engine = engineClass.getDeclaredConstructor(configClass).newInstance(builtConfig)
        engineClass.getMethod("initialize").invoke(engine)
        Log.i(TAG, "[GEMMA4_VISION] Mounted $modelPath (GPU=$preferGpu)")
        engine as? AutoCloseable
    }.onFailure { err ->
        Log.w(TAG, "[GEMMA4_VISION] Init (GPU=$preferGpu) skipped: ${err.message}")
    }.getOrNull()

    private fun queryLiteRtGemmaOnDevice(
        modelFile: File,
        annotatedJpegFile: File,
        prompt: String
    ): String? = runCatching {
        val engine = ensureLiteRtEngineMountedLocked(modelFile) ?: return null
        val conv = (engine.javaClass.methods.firstOrNull {
            it.name == "createConversation" && it.parameterCount == 0
        }?.invoke(engine) ?: run {
            val ccClass = Class.forName("com.google.ai.edge.litertlm.ConversationConfig")
            val defaultCc = ccClass.constructors.firstOrNull { it.parameterCount == 0 }?.newInstance()
            engine.javaClass.methods.firstOrNull {
                it.name == "createConversation" && it.parameterCount == 1
            }?.invoke(engine, defaultCc)
        }) as? AutoCloseable ?: return null

        conv.use { session ->
            val msgObj = buildMultimodalMessage(annotatedJpegFile, prompt)
            val sendMsgMethod = msgObj?.let { m ->
                session.javaClass.methods.firstOrNull {
                    it.name == "sendMessage" && it.parameterCount == 1 &&
                        it.parameterTypes[0].isAssignableFrom(m.javaClass)
                }
            }
            val resp = if (sendMsgMethod != null && msgObj != null) {
                sendMsgMethod.invoke(session, msgObj)
            } else {
                session.javaClass.methods.firstOrNull {
                    it.name == "sendMessage" && it.parameterCount == 1 && it.parameterTypes[0] == String::class.java
                }?.invoke(session, prompt)
            }
            extractTextFromLiteRtResponse(resp)
        }
    }.onFailure { err ->
        Log.w(TAG, "[GEMMA4_VISION] Inference error: ${err.message}")
    }.getOrNull()

    private fun buildMultimodalMessage(imageFile: File, prompt: String): Any? = runCatching {
        val textClass = Class.forName("com.google.ai.edge.litertlm.Content\$Text")
        val textContent = textClass.getDeclaredConstructor(String::class.java).newInstance(prompt)

        val imgContent = runCatching {
            val imgFileClass = Class.forName("com.google.ai.edge.litertlm.Content\$ImageFile")
            imgFileClass.getDeclaredConstructor(String::class.java).newInstance(imageFile.absolutePath)
        }.getOrElse {
            val imgBytesClass = Class.forName("com.google.ai.edge.litertlm.Content\$ImageBytes")
            imgBytesClass.getDeclaredConstructor(ByteArray::class.java).newInstance(imageFile.readBytes())
        }

        val contentList = listOf(imgContent, textContent)
        val messageClass = Class.forName("com.google.ai.edge.litertlm.Message")
        val contentClass = Class.forName("com.google.ai.edge.litertlm.Content")

        // Support Message.of(vararg Content), Message.of(List<Content>), or Message(List<Content>)
        val ofList = messageClass.methods.firstOrNull {
            it.name == "of" && it.parameterCount == 1 && List::class.java.isAssignableFrom(it.parameterTypes[0])
        }
        if (ofList != null) return@runCatching ofList.invoke(null, contentList)

        val ofVararg = messageClass.methods.firstOrNull {
            it.name == "of" && it.parameterCount == 1 && it.parameterTypes[0].isArray
        }
        if (ofVararg != null) {
            val arr = java.lang.reflect.Array.newInstance(contentClass, 2)
            java.lang.reflect.Array.set(arr, 0, imgContent)
            java.lang.reflect.Array.set(arr, 1, textContent)
            return@runCatching ofVararg.invoke(null, arr)
        }

        val ctorList = messageClass.constructors.firstOrNull {
            it.parameterCount == 1 && List::class.java.isAssignableFrom(it.parameterTypes[0])
        }
        ctorList?.newInstance(contentList)
    }.getOrNull()

    private fun extractTextFromLiteRtResponse(resp: Any?): String? {
        if (resp == null) return null
        if (resp is String) return resp
        val textMethod = resp.javaClass.methods.firstOrNull {
            it.name in setOf("getText", "text", "getContent") && it.parameterCount == 0
        }
        val direct = textMethod?.invoke(resp)?.toString()
        if (!direct.isNullOrBlank()) return direct
        return resp.toString()
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
        existingCharacters: List<MangaCharacterProfile>
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
                thoughtTrace = "BFS gutter slicer isolated Panel P${pIdx + 1} on page ${pageIndex + 1}; awaiting Gemma 4 E2B OCR or Pre-Production confirmation.",
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
                        rawText = "Page ${pageIndex + 1}, Panel ${pIdx + 1} line — tap to type or confirm dialogue.",
                        needsUserDecision = conf < 0.72f,
                        decisionReason = "Gemma 4 E2B Vision cartridge not installed — confirm speaker & dialogue or install SUB-11V"
                    )
                )
            )
        }
        return MangaVisionResponseParser.ParsedPageAnalysis(
            thoughtTrace = "BFS sliced ${panels.size} panel(s) cleanly.",
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
        Log.i(TAG, "[VRAM_EVICT] Unloaded Gemma 4 E2B LiteRT Vision Engine")
    }
}
