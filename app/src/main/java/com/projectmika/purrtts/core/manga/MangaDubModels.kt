package com.projectmika.purrtts.core.manga

import com.projectmika.purrtts.core.purr.VoiceGender
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.charset.Charset
import java.util.Locale
import java.util.zip.ZipFile as JavaZipFile
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipFile as CommonsZipFile

/**
 * ✨ MIKA'S MANGA DUB & MOTION-COMIC STUDIO MODELS & PURE-KOTLIN ENGINES (TOOL-11) ✨
 *
 * Houses:
 * - Serializable Manga Series / Chapter / Panel / Cast Roster models
 * - GachaSwipe-ported BFS Edge-Connected Gutter Panel Slicer (`chromaKey.js` algorithm)
 * - GachaSwipe-ported Emotional Pacer & Atmosphere FX detector (`RoleplayRenderer.jsx` + `AtmosphereEngine.jsx`)
 * - MIKAReader-ported All-Caps Manga OCR Text Bender (`purrBender.js`)
 * - Gemma 4 E2B LiteRT-LM `<thought>` + JSON response parser
 */

@Serializable
enum class MangaReadingOrder(val label: String) {
    RTL_MANGA("MANGA (Right -> Left)"),
    LTR_COMIC("COMIC / MANHWA (Left -> Right)"),
    WEBTOON_VERTICAL("WEBTOON (Vertical Scroll)")
}

@Serializable
enum class MangaVideoAspect(val label: String, val widthPx: Int, val heightPx: Int) {
    LANDSCAPE_16_9("16:9 LANDSCAPE (1280x720)", 1280, 720),
    VERTICAL_9_16("9:16 VERTICAL (720x1280)", 720, 1280)
}

@Serializable
enum class MangaAtmospherePreset(val label: String) {
    AUTO("AUTO (Scene Mood)"),
    NONE("NONE"),
    EMBER_BATTLE("EMBER / BATTLE"),
    RAIN_STORM("RAIN / GLOOM"),
    SAKURA_PETALS("SAKURA / ROMANCE"),
    CYBER_SPARKLE("HOLO / SPARKLE")
}

@Serializable
enum class MangaChapterStatus(val label: String) {
    IMPORTED("IMPORTED"),
    ANALYZING_VISION("SCANNING (GEMMA 4 VISION)"),
    PRE_PRODUCTION_READY("PRE-PRODUCTION"),
    SYNTHESIZING_AUDIO("FORGING VOICES"),
    RENDERING_VIDEO("RENDERING .MP4"),
    COMPLETED("COMPLETED"),
    FAILED("FAILED")
}

@Serializable
data class MangaNormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = (right - left).coerceAtLeast(0.01f)
    val height: Float get() = (bottom - top).coerceAtLeast(0.01f)
    val centerX: Float get() = (left + right) * 0.5f
    val centerY: Float get() = (top + bottom) * 0.5f

    fun clamped(): MangaNormalizedRect {
        val l = left.coerceIn(0f, 0.99f)
        val t = top.coerceIn(0f, 0.99f)
        val r = right.coerceIn(l + 0.01f, 1f)
        val b = bottom.coerceIn(t + 0.01f, 1f)
        return MangaNormalizedRect(l, t, r, b)
    }
}

@Serializable
data class MangaSpeechLine(
    val id: String,
    val bubbleIndex: Int,
    val speakerId: String,
    val speakerName: String,
    val rawText: String,
    val sanitizedText: String,
    val emotionTag: String = "neutral",
    val confidence: Float = 0.95f,
    val needsUserDecision: Boolean = false,
    val decisionReason: String = "",
    val candidateSpeakers: List<String> = emptyList(),
    val bubbleRect: MangaNormalizedRect? = null,
    val renderedWavPath: String = "",
    val durationMs: Long = 0L,
    val postPauseMs: Int = 450
)

@Serializable
data class MangaPanelNode(
    val id: String,
    val pageIndex: Int,
    val panelIndex: Int,
    val panelRect: MangaNormalizedRect,
    val cameraStartRect: MangaNormalizedRect,
    val cameraEndRect: MangaNormalizedRect,
    val atmosphere: MangaAtmospherePreset = MangaAtmospherePreset.AUTO,
    val sceneMood: String = "",
    val gemmaThoughtTrace: String = "",
    val lines: List<MangaSpeechLine> = emptyList()
)

@Serializable
data class MangaPageEntry(
    val pageIndex: Int,
    val fileName: String,
    val localImagePath: String,
    val widthPx: Int,
    val heightPx: Int,
    val panels: List<MangaPanelNode> = emptyList()
)

@Serializable
data class MangaCharacterProfile(
    val id: String,
    val name: String,
    val gender: VoiceGender = VoiceGender.ANDROGYNOUS,
    val visualDescription: String = "",
    val avatarCropPath: String = "",
    val voiceDesignPrompt: String = "",
    val assignedVoiceId: String = "",
    val assignedVoiceLabel: String = "AUTO (Qwen VoiceDesign)",
    val speedScale: Float = 1.0f,
    val pitchScale: Float = 1.0f,
    val lineCount: Int = 0
)

@Serializable
data class MangaChapterEntry(
    val id: String,
    val title: String,
    val sourceCbzName: String,
    val readingOrder: MangaReadingOrder = MangaReadingOrder.RTL_MANGA,
    val status: MangaChapterStatus = MangaChapterStatus.IMPORTED,
    val pages: List<MangaPageEntry> = emptyList(),
    val exportedMp4Path: String = "",
    val publicDownloadPath: String = "",
    val durationSec: Float = 0f,
    val errorMessage: String? = null
) {
    val pendingDecisionCount: Int
        get() = pages.sumOf { p -> p.panels.sumOf { pan -> pan.lines.count { it.needsUserDecision } } }

    val totalLineCount: Int
        get() = pages.sumOf { p -> p.panels.sumOf { it.lines.size } }

    val totalPanelCount: Int
        get() = pages.sumOf { it.panels.size }
}

@Serializable
enum class MangaVisionModelTier(
    val specId: String,
    val fileName: String,
    val shortLabel: String,
    val displayTitle: String,
    val sizeNote: String,
    val isRecommended: Boolean = false
) {
    AUTO(
        specId = "sub_gemma4_e2b_vision",
        fileName = "gemma-4-E2B-it-gpu.litertlm",
        shortLabel = "AUTO (E2B REC.)",
        displayTitle = "Auto — Prefer Gemma 4 E2B [Recommended]",
        sizeNote = "Auto-picks installed E2B (Recommended) or E4B",
        isRecommended = true
    ),
    GEMMA4_E2B(
        specId = "sub_gemma4_e2b_vision",
        fileName = "gemma-4-E2B-it-gpu.litertlm",
        shortLabel = "GEMMA 4 E2B",
        displayTitle = "Gemma 4 E2B LiteRT [RECOMMENDED]",
        sizeNote = "2.2 GB • Fastest Vision + Thinking",
        isRecommended = true
    ),
    GEMMA4_E4B(
        specId = "sub_gemma4_e4b_vision",
        fileName = "gemma-4-E4B-it-gpu.litertlm",
        shortLabel = "GEMMA 4 E4B",
        displayTitle = "Gemma 4 E4B LiteRT [PRO 4B]",
        sizeNote = "3.1 GB • Higher Accuracy (8GB+ RAM)",
        isRecommended = false
    )
}

enum class MangaPipelineStage(val stepNum: Int, val label: String) {
    IDLE(0, "IDLE"),
    EXTRACT_AND_SLICE(1, "1/5 UNPACK & SLICE"),
    VISION_SCAN(2, "2/5 GEMMA VISION OCR"),
    VOICE_CASTING(3, "3/5 QWEN VOICEDESIGN CAST"),
    VOICE_FORGE(4, "4/5 VOICE SYNTHESIS"),
    VIDEO_MUX(5, "5/5 MOTION .MP4 MUX")
}

data class MangaJobTelemetry(
    val stage: MangaPipelineStage = MangaPipelineStage.IDLE,
    val detailText: String = "IDLE // IMPORT A .CBZ OR LOAD DEMO CHAPTER",
    val itemCurrent: Int = 0,
    val itemTotal: Int = 0,
    val elapsedMs: Long = 0L,
    val estimatedRemainingSec: Int? = null,
    val logTail: List<String> = emptyList()
)

@Serializable
data class MangaSeriesProject(
    val id: String,
    val seriesTitle: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val readingOrder: MangaReadingOrder = MangaReadingOrder.RTL_MANGA,
    val videoAspect: MangaVideoAspect = MangaVideoAspect.LANDSCAPE_16_9,
    val burnInSubtitles: Boolean = true,
    val enableAtmosphereFx: Boolean = true,
    val powerUserVisionUrl: String = "",
    val preferredVisionModel: MangaVisionModelTier = MangaVisionModelTier.AUTO,
    val autoCookFullMp4: Boolean = true,
    val pauseOnFlaggedDecisions: Boolean = true,
    val storyContextSummary: String = "",
    val characters: List<MangaCharacterProfile> = emptyList(),
    val chapters: List<MangaChapterEntry> = emptyList()
)

@Serializable
data class MangaCloneHandoffTarget(
    val projectId: String,
    val characterId: String,
    val characterName: String,
    val suggestedGender: VoiceGender = VoiceGender.ANDROGYNOUS,
    val suggestedTags: String = "manga, anime, character"
)

/**
 * ✨ MANGA OCR TEXT BENDER (Ported from MIKAReader `purrBender.js`) ✨
 * English manga lettering is conventionally ALL-CAPS ("WHAT DID YOU SAY, KAKAROT?!"),
 * which makes neural TTS engines spell out capitalized words or stutter on repeated marks.
 */
object MangaTextBender {

    val ACRONYM_WHITELIST: Set<String> = setOf(
        "NASA", "DNA", "FBI", "CIA", "USA", "UK", "OK", "TV",
        "AI", "ID", "UFO", "HUD", "SSR", "RPG", "HP", "MP"
    )

    fun sanitizeMangaDialogue(raw: String): String {
        var out = raw
            .replace(Regex("<[^>]+>"), " ")
            .replace(Regex("[*_`~#]{1,3}"), "")
            .replace("\u2014", ", ")
            .replace("\u2013", ", ")
            .replace("--", ", ")
            .replace("\u2026", ".")
            .replace(Regex("""["“”„«»]"""), "")
            .replace(Regex("\\s+"), " ")
            .trim()

        if (out.isEmpty()) return ""

        // Collapse excessive manga punctuation storms cleanly
        out = out
            .replace(Regex("!{2,}"), "!")
            .replace(Regex("\\?{2,}"), "?")
            .replace(Regex("""[?!]*\?[?!]*![?!]*"""), "?!")
            .replace(Regex("""[?!]*![?!]*\?[?!]*"""), "?!")
            .replace(Regex("\\.{2,}"), ".")
            .replace(Regex("\\s+([,.!?])"), "$1")

        // Acronym Assassin: lowercase 2+ uppercase runs & all-caps contractions unless whitelisted
        out = Regex("""\b([A-Z]+)(?:'([A-Z]+))?\b""").replace(out) { m ->
            val stem = m.groupValues[1]
            val suffix = m.groupValues[2]
            when {
                suffix.isNotEmpty() -> {
                    if (stem in ACRONYM_WHITELIST) "$stem'${suffix.lowercase()}"
                    else "${stem.lowercase()}'${suffix.lowercase()}"
                }
                stem.length >= 2 && stem !in ACRONYM_WHITELIST -> stem.lowercase()
                else -> stem
            }
        }

        // Fix standalone lowercase pronoun "i" and contractions ("i'm", "i'll", "i've", "i'd")
        out = Regex("""\bi(?=('(?:m|ll|ve|d)\b|\b))""").replace(out) { "I" }

        // Capitalize first letter after start of string or sentence boundary (. ! ?)
        val chars = out.toCharArray()
        var capitalizeNext = true
        for (i in chars.indices) {
            val c = chars[i]
            if (capitalizeNext && c.isLetter()) {
                chars[i] = c.uppercaseChar()
                capitalizeNext = false
            } else if (c == '.' || c == '!' || c == '?') {
                capitalizeNext = true
            } else if (!c.isWhitespace() && c != '\'' && c != ',') {
                capitalizeNext = false
            }
        }
        out = String(chars).trim()

        if (out.isNotEmpty() && !out.last().let { it == '.' || it == '!' || it == '?' }) {
            out += "."
        }
        return out
    }
}

/**
 * ✨ MANGA EMOTIONAL PACER & ATMOSPHERE FX RESOLVER ✨
 * Ported directly from GachaSwipe's `RoleplayRenderer.jsx` pause logic & `AtmosphereEngine.jsx`.
 */
object MangaEmotionalPacer {

    fun computePostLinePauseMs(text: String, emotionTag: String): Int {
        val em = emotionTag.lowercase()
        var gapMs = when {
            listOf("sad", "grief", "hesitant", "sorrow", "whisper", "melancholy").any { it in em } -> 850
            listOf("love", "warm", "intimate", "tender", "affection", "blush").any { it in em } -> 700
            listOf("joy", "excited", "happy", "cheerful", "laugh", "genki").any { it in em } -> 450
            listOf("anger", "shout", "battle", "furious", "rage", "fierce", "scream").any { it in em } -> 400
            else -> 500
        }
        val trimmed = text.trim()
        if (trimmed.endsWith(",") || trimmed.endsWith(";") || trimmed.endsWith(":") || trimmed.endsWith("...")) {
            gapMs /= 2
        }
        return gapMs.coerceIn(180, 1200)
    }

    fun resolveAtmosphere(
        preset: MangaAtmospherePreset,
        sceneMood: String,
        dialogueText: String
    ): MangaAtmospherePreset {
        if (preset != MangaAtmospherePreset.AUTO) return preset
        val corpus = "$sceneMood $dialogueText".lowercase()
        return when {
            listOf(
                "fire", "flame", "ember", "battle", "attack", "blast",
                "aura", "power", "fight", "explosion", "demon", "warrior", "clash"
            ).any { it in corpus } -> MangaAtmospherePreset.EMBER_BATTLE

            listOf(
                "rain", "storm", "umbrella", "tears", "gloom",
                "dark", "thunder", "wet", "shadow", "midnight"
            ).any { it in corpus } -> MangaAtmospherePreset.RAIN_STORM

            listOf(
                "sakura", "blossom", "petal", "love", "blush",
                "heart", "spring", "shrine", "romance", "kiss"
            ).any { it in corpus } -> MangaAtmospherePreset.SAKURA_PETALS

            listOf(
                "magic", "neon", "cyber", "holo", "divine",
                "star", "galaxy", "portal", "spell", "dream"
            ).any { it in corpus } -> MangaAtmospherePreset.CYBER_SPARKLE

            else -> MangaAtmospherePreset.NONE
        }
    }
}

/**
 * ✨ BFS EDGE-CONNECTED MANGA PANEL SLICER (Ported from GachaSwipe `chromaKey.js`) ✨
 * Flood-fills gutters starting from the border ring of the page luminance grid to isolate
 * exact panel regions cleanly in RTL Manga, LTR Comic, or Webtoon vertical reading order.
 */
object MangaBfsPanelSlicer {

    fun slicePanelsFromLuminanceGrid(
        luminance: IntArray,
        width: Int,
        height: Int,
        readingOrder: MangaReadingOrder
    ): List<MangaNormalizedRect> {
        if (width < 8 || height < 8 || luminance.size < width * height) {
            return listOf(MangaNormalizedRect(0.02f, 0.02f, 0.98f, 0.98f))
        }

        // 1. Sample border ring median luminance to identify white or black manga gutters
        val margin = max(2, min(width, height) / 50)
        val samples = ArrayList<Int>(width * margin + height * margin)
        for (y in 0 until margin) {
            for (x in 0 until width) samples += luminance[y * width + x]
        }
        for (y in (height - margin) until height) {
            for (x in 0 until width) samples += luminance[y * width + x]
        }
        for (y in 0 until height) {
            for (x in 0 until margin) samples += luminance[y * width + x]
            for (x in (width - margin) until width) samples += luminance[y * width + x]
        }
        samples.sort()
        val gutterMed = samples[samples.size / 2]
        val tolerance = 34

        fun isGutterCandidate(lum: Int): Boolean = abs(lum - gutterMed) <= tolerance

        // 2. Edge-connected BFS flood fill for gutter backdrop
        val total = width * height
        val isGutter = BooleanArray(total)
        val queue = IntArray(total)
        var head = 0
        var tail = 0

        fun seedPixel(idx: Int) {
            if (!isGutter[idx] && isGutterCandidate(luminance[idx])) {
                isGutter[idx] = true
                queue[tail++] = idx
            }
        }

        for (y in 0 until height) {
            for (x in 0 until margin) seedPixel(y * width + x)
            for (x in (width - margin) until width) seedPixel(y * width + x)
        }
        for (y in 0 until margin) {
            for (x in 0 until width) seedPixel(y * width + x)
        }
        for (y in (height - margin) until height) {
            for (x in 0 until width) seedPixel(y * width + x)
        }

        val dx4 = intArrayOf(1, -1, 0, 0)
        val dy4 = intArrayOf(0, 0, 1, -1)
        while (head < tail) {
            val cur = queue[head++]
            val cx = cur % width
            val cy = cur / width
            for (d in 0 until 4) {
                val nx = cx + dx4[d]
                val ny = cy + dy4[d]
                if (nx in 0 until width && ny in 0 until height) {
                    val nIdx = ny * width + nx
                    if (!isGutter[nIdx] && isGutterCandidate(luminance[nIdx])) {
                        isGutter[nIdx] = true
                        queue[tail++] = nIdx
                    }
                }
            }
        }

        // Also treat thin horizontal/vertical continuous gutter bands between panels as gutters
        // so closed-margin comics still separate rows/columns cleanly.
        for (y in 0 until height) {
            var gutterRun = 0
            for (x in 0 until width) {
                if (isGutterCandidate(luminance[y * width + x])) gutterRun++
            }
            if (gutterRun >= (width * 0.96f).toInt()) {
                val rowOff = y * width
                for (x in 0 until width) {
                    if (isGutterCandidate(luminance[rowOff + x])) isGutter[rowOff + x] = true
                }
            }
        }
        for (x in 0 until width) {
            var gutterRun = 0
            for (y in 0 until height) {
                if (isGutterCandidate(luminance[y * width + x])) gutterRun++
            }
            if (gutterRun >= (height * 0.96f).toInt()) {
                for (y in 0 until height) {
                    if (isGutterCandidate(luminance[y * width + x])) isGutter[y * width + x] = true
                }
            }
        }

        // 3. Connected components of non-gutter panel pixels
        val visited = BooleanArray(total)
        val rawBoxes = ArrayList<MangaNormalizedRect>()
        val minAreaPx = (total * 0.035f).toInt().coerceAtLeast(16)
        val dx8 = intArrayOf(-1, 0, 1, -1, 1, -1, 0, 1)
        val dy8 = intArrayOf(-1, -1, -1, 0, 0, 1, 1, 1)

        for (startIdx in 0 until total) {
            if (isGutter[startIdx] || visited[startIdx]) continue
            var qh = 0
            var qt = 0
            visited[startIdx] = true
            queue[qt++] = startIdx
            var minX = startIdx % width
            var maxX = minX
            var minY = startIdx / width
            var maxY = minY
            var pixelCount = 0

            while (qh < qt) {
                val idx = queue[qh++]
                pixelCount++
                val px = idx % width
                val py = idx / width
                if (px < minX) minX = px
                if (px > maxX) maxX = px
                if (py < minY) minY = py
                if (py > maxY) maxY = py

                for (k in 0 until 8) {
                    val nx = px + dx8[k]
                    val ny = py + dy8[k]
                    if (nx in 0 until width && ny in 0 until height) {
                        val nIdx = ny * width + nx
                        if (!visited[nIdx] && !isGutter[nIdx]) {
                            visited[nIdx] = true
                            queue[qt++] = nIdx
                        }
                    }
                }
            }

            val boxW = maxX - minX + 1
            val boxH = maxY - minY + 1
            val boxArea = boxW * boxH
            if (boxArea >= minAreaPx && pixelCount >= minAreaPx / 4) {
                val rect = MangaNormalizedRect(
                    left = (minX.toFloat() / width.toFloat()).coerceIn(0f, 0.98f),
                    top = (minY.toFloat() / height.toFloat()).coerceIn(0f, 0.98f),
                    right = ((maxX + 1).toFloat() / width.toFloat()).coerceIn(0.02f, 1f),
                    bottom = ((maxY + 1).toFloat() / height.toFloat()).coerceIn(0.02f, 1f)
                ).clamped()
                rawBoxes += rect
            }
        }

        // 4. Deduplicate & drop boxes >=75% contained inside larger boxes
        val sortedByArea = rawBoxes.sortedByDescending { it.width * it.height }
        val kept = ArrayList<MangaNormalizedRect>()
        for (cand in sortedByArea) {
            val candArea = cand.width * cand.height
            val dominated = kept.any { outer ->
                val ix1 = max(cand.left, outer.left)
                val iy1 = max(cand.top, outer.top)
                val ix2 = min(cand.right, outer.right)
                val iy2 = min(cand.bottom, outer.bottom)
                if (ix2 <= ix1 || iy2 <= iy1) false
                else ((ix2 - ix1) * (iy2 - iy1)) / candArea >= 0.75f
            }
            if (!dominated) kept += cand
        }

        // Splash page or Webtoon vertical strip fallback
        if (kept.isEmpty()) {
            return if (readingOrder == MangaReadingOrder.WEBTOON_VERTICAL && height > width * 2) {
                val bands = max(2, min(6, height / max(1, width)))
                (0 until bands).map { idx ->
                    val t0 = idx.toFloat() / bands.toFloat()
                    val t1 = ((idx + 1).toFloat() / bands.toFloat()).coerceAtMost(1f)
                    MangaNormalizedRect(0.02f, t0, 0.98f, t1).clamped()
                }
            } else {
                listOf(MangaNormalizedRect(0.02f, 0.02f, 0.98f, 0.98f))
            }
        }

        if (kept.size == 1 && readingOrder == MangaReadingOrder.WEBTOON_VERTICAL && height > width * 2) {
            val bands = max(2, min(6, height / max(1, width)))
            return (0 until bands).map { idx ->
                val t0 = idx.toFloat() / bands.toFloat()
                val t1 = ((idx + 1).toFloat() / bands.toFloat()).coerceAtMost(1f)
                MangaNormalizedRect(0.02f, t0, 0.98f, t1).clamped()
            }
        }

        // 5. Group into reading rows and order by RTL Manga / LTR Comic / Vertical
        val orderedByTop = kept.sortedBy { it.top }
        val rows = ArrayList<MutableList<MangaNormalizedRect>>()
        for (box in orderedByTop) {
            val targetRow = rows.firstOrNull { row ->
                row.any { existing ->
                    val overlapTop = max(existing.top, box.top)
                    val overlapBot = min(existing.bottom, box.bottom)
                    val overlapH = (overlapBot - overlapTop).coerceAtLeast(0f)
                    val minH = min(existing.height, box.height).coerceAtLeast(0.01f)
                    (overlapH / minH) >= 0.35f || abs(existing.centerY - box.centerY) < minH * 0.40f
                }
            }
            if (targetRow != null) {
                targetRow += box
            } else {
                rows += mutableListOf(box)
            }
        }

        rows.sortBy { row -> row.minOf { it.top } }
        val result = ArrayList<MangaNormalizedRect>(kept.size)
        for (row in rows) {
            when (readingOrder) {
                MangaReadingOrder.RTL_MANGA -> row.sortByDescending { it.centerX }
                MangaReadingOrder.LTR_COMIC,
                MangaReadingOrder.WEBTOON_VERTICAL -> row.sortBy { it.centerX }
            }
            result += row
        }
        return result
    }

    /**
     * Ported from GachaSwipe `photoViewerCamera.js`: generates a subtle 6% zoomed-in Ken Burns
     * drift pair biased from reading entry corner to exit corner.
     */
    fun computeKenBurnsPair(
        panelRect: MangaNormalizedRect,
        readingOrder: MangaReadingOrder
    ): Pair<MangaNormalizedRect, MangaNormalizedRect> {
        val shrinkX = panelRect.width * 0.06f
        val shrinkY = panelRect.height * 0.06f
        val rtl = readingOrder == MangaReadingOrder.RTL_MANGA
        val start = if (rtl) {
            MangaNormalizedRect(
                left = panelRect.left + shrinkX * 0.8f,
                top = panelRect.top + shrinkY * 0.2f,
                right = panelRect.right - shrinkX * 0.2f,
                bottom = panelRect.bottom - shrinkY * 0.8f
            ).clamped()
        } else {
            MangaNormalizedRect(
                left = panelRect.left + shrinkX * 0.2f,
                top = panelRect.top + shrinkY * 0.2f,
                right = panelRect.right - shrinkX * 0.8f,
                bottom = panelRect.bottom - shrinkY * 0.8f
            ).clamped()
        }
        val end = if (rtl) {
            MangaNormalizedRect(
                left = panelRect.left + shrinkX * 0.2f,
                top = panelRect.top + shrinkY * 0.8f,
                right = panelRect.right - shrinkX * 0.8f,
                bottom = panelRect.bottom - shrinkY * 0.2f
            ).clamped()
        } else {
            MangaNormalizedRect(
                left = panelRect.left + shrinkX * 0.8f,
                top = panelRect.top + shrinkY * 0.8f,
                right = panelRect.right - shrinkX * 0.2f,
                bottom = panelRect.bottom - shrinkY * 0.2f
            ).clamped()
        }
        return start to end
    }
}

/**
 * ✨ GEMMA 4 E2B VISION + THINKING STRUCTURED RESPONSE PARSER ✨
 */
object MangaVisionResponseParser {

    data class DiscoveredLineSpec(
        val bubbleIndex: Int,
        val speakerName: String,
        val isNewCharacter: Boolean,
        val gender: VoiceGender,
        val visualDescription: String,
        val voiceDesignPrompt: String,
        val emotion: String,
        val confidence: Float,
        val candidateSpeakers: List<String>,
        val rawText: String,
        val needsUserDecision: Boolean,
        val decisionReason: String
    )

    data class DiscoveredPanelSpec(
        val panelIndex: Int,
        val sceneMood: String,
        val thoughtTrace: String,
        val lines: List<DiscoveredLineSpec>
    )

    data class ParsedPageAnalysis(
        val thoughtTrace: String,
        val storySummary: String,
        val panels: List<DiscoveredPanelSpec>
    )

    private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parsePageAnalysis(
        rawResponse: String,
        panelCount: Int,
        existingCharacters: List<MangaCharacterProfile>
    ): ParsedPageAnalysis {
        var thoughtTrace = ""
        var cleaned = rawResponse.trim()

        val thoughtMatch = Regex("""<(?:thought|thinking)>([\s\S]*?)</(?:thought|thinking)>""", RegexOption.IGNORE_CASE)
            .find(cleaned)
        if (thoughtMatch != null) {
            thoughtTrace = thoughtMatch.groupValues[1].trim()
            cleaned = cleaned.replaceRange(thoughtMatch.range, "").trim()
        }

        // Strip markdown fences if model wrapped JSON
        cleaned = cleaned
            .replace(Regex("""```(?:json)?\s*""", RegexOption.IGNORE_CASE), "")
            .replace("```", "")
            .trim()

        val objStart = cleaned.indexOf('{')
        val objEnd = cleaned.lastIndexOf('}')
        val arrStart = cleaned.indexOf('[')
        val arrEnd = cleaned.lastIndexOf(']')

        val jsonCandidate: String? = when {
            arrStart >= 0 && arrEnd > arrStart && (objStart < 0 || arrStart < objStart) ->
                "{\"panels\":${cleaned.substring(arrStart, arrEnd + 1)}}"
            objStart >= 0 && objEnd > objStart ->
                cleaned.substring(objStart, objEnd + 1)
            arrStart >= 0 && arrEnd > arrStart ->
                "{\"panels\":${cleaned.substring(arrStart, arrEnd + 1)}}"
            else -> null
        }

        if (jsonCandidate != null) {
            val sanitizedJson = cleanupTrailingCommas(jsonCandidate)
            val parsedJson = runCatching {
                val root = lenientJson.parseToJsonElement(sanitizedJson) as? JsonObject ?: return@runCatching null
                val summary = (root["story_summary"] as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
                val panelsArr = root["panels"] as? JsonArray ?: JsonArray(emptyList())
                val knownNames = existingCharacters.map { it.name }.distinct()

                val rawPanelNums = panelsArr.mapIndexedNotNull { idx, elem ->
                    val pObj = elem as? JsonObject ?: return@mapIndexedNotNull null
                    parsePanelNumber(pObj["panel_index"] ?: pObj["panel"])
                }
                val looksOneBased = rawPanelNums.isNotEmpty() &&
                    rawPanelNums.all { it in 1..max(1, panelCount) } &&
                    rawPanelNums.none { it == 0 }

                val parsedPanels = panelsArr.mapIndexedNotNull { idx, elem ->
                    val pObj = elem as? JsonObject ?: return@mapIndexedNotNull null
                    val rawNum = parsePanelNumber(pObj["panel_index"] ?: pObj["panel"])
                    val adjusted = if (rawNum != null && looksOneBased) rawNum - 1 else rawNum
                    val pIdx = (adjusted ?: idx).coerceIn(0, max(0, panelCount - 1))
                    val mood = ((pObj["scene_mood"] ?: pObj["mood"]) as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
                    val panelThought = (pObj["thought"] as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
                    val linesArr = (pObj["lines"] ?: pObj["dialogue"]) as? JsonArray ?: JsonArray(emptyList())

                    val parsedLines = linesArr.mapIndexedNotNull { lIdx, lElem ->
                        val lObj = lElem as? JsonObject ?: return@mapIndexedNotNull null
                        val rawText = ((lObj["text"] ?: lObj["dialogue"] ?: lObj["line"] ?: lObj["speech"]) as? JsonPrimitive)
                            ?.contentOrNull.orEmpty().trim()
                        if (rawText.isEmpty()) return@mapIndexedNotNull null
                        val speakerRaw = ((lObj["speaker"] ?: lObj["character"] ?: lObj["name"]) as? JsonPrimitive)
                            ?.contentOrNull.orEmpty().trim()
                            .ifEmpty { "UNKNOWN" }
                        val isNew = (lObj["is_new_character"] as? JsonPrimitive)?.booleanOrNull ?: false
                        val genderStr = (lObj["gender"] as? JsonPrimitive)?.contentOrNull.orEmpty().lowercase()
                        val gender = when {
                            genderStr.startsWith("f") -> VoiceGender.FEMALE
                            genderStr.startsWith("m") -> VoiceGender.MALE
                            else -> VoiceGender.ANDROGYNOUS
                        }
                        val visualDesc = (lObj["visual_description"] as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
                        val designPrompt = ((lObj["voice_design_prompt"] ?: lObj["voice_prompt"]) as? JsonPrimitive)
                            ?.contentOrNull.orEmpty().trim()
                        val emotion = (lObj["emotion"] as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
                            .ifEmpty { "neutral" }
                        val conf = (lObj["confidence"] as? JsonPrimitive)?.floatOrNull?.coerceIn(0f, 1f) ?: 0.88f
                        val bubbleNum = (lObj["bubble"] as? JsonPrimitive)?.intOrNull ?: (lIdx + 1)
                        val explicitCandidates = (lObj["candidate_speakers"] as? JsonArray)
                            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { s -> s.isNotEmpty() } }
                            .orEmpty()
                        val ambiguousSpeaker = speakerRaw.equals("UNKNOWN", ignoreCase = true) ||
                            speakerRaw.equals("AMBIGUOUS", ignoreCase = true) ||
                            speakerRaw == "?"
                        val needsDecision = ambiguousSpeaker || conf < 0.55f
                        val reason = when {
                            ambiguousSpeaker -> "Gemma flagged ambiguous speaker in Panel ${pIdx + 1} — tap a character below"
                            conf < 0.55f -> "Low Gemma confidence (${(conf * 100).toInt()}%) — verify speaker attribution"
                            else -> ""
                        }
                        val mergedCandidates = (explicitCandidates + knownNames).distinct().take(6)

                        DiscoveredLineSpec(
                            bubbleIndex = bubbleNum,
                            speakerName = if (ambiguousSpeaker) "UNKNOWN" else speakerRaw,
                            isNewCharacter = isNew && !ambiguousSpeaker,
                            gender = gender,
                            visualDescription = visualDesc,
                            voiceDesignPrompt = designPrompt,
                            emotion = emotion,
                            confidence = conf,
                            candidateSpeakers = mergedCandidates,
                            rawText = rawText,
                            needsUserDecision = needsDecision,
                            decisionReason = reason
                        )
                    }
                    DiscoveredPanelSpec(
                        panelIndex = pIdx,
                        sceneMood = mood,
                        thoughtTrace = panelThought.ifEmpty { thoughtTrace },
                        lines = parsedLines
                    )
                }
                ParsedPageAnalysis(thoughtTrace = thoughtTrace, storySummary = summary, panels = parsedPanels)
            }.getOrNull()

            if (parsedJson != null && parsedJson.panels.isNotEmpty()) {
                return parsedJson
            }
        }

        // Structured plain-text fallback if Gemma responded outside JSON
        val textFallback = parseStructuredTextFallback(cleaned, panelCount, existingCharacters, thoughtTrace)
        return textFallback ?: ParsedPageAnalysis(thoughtTrace = thoughtTrace, storySummary = "", panels = emptyList())
    }

    fun parseVoiceDesignRoster(
        rawResponse: String,
        existingRoster: List<MangaCharacterProfile>
    ): List<MangaCharacterProfile> {
        var cleaned = rawResponse.trim()
            .replace(Regex("""<(?:thought|thinking)>[\s\S]*?</(?:thought|thinking)>""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""```(?:json)?\s*""", RegexOption.IGNORE_CASE), "")
            .replace("```", "")
            .trim()
        val objStart = cleaned.indexOf('{')
        val objEnd = cleaned.lastIndexOf('}')
        if (objStart < 0 || objEnd <= objStart) return existingRoster

        val jsonSlice = cleanupTrailingCommas(cleaned.substring(objStart, objEnd + 1))
        val parsedMap = runCatching {
            val root = lenientJson.parseToJsonElement(jsonSlice) as? JsonObject ?: return@runCatching null
            val arr = root["characters"] as? JsonArray ?: return@runCatching null
            val byName = HashMap<String, JsonObject>()
            for (elem in arr) {
                val obj = elem as? JsonObject ?: continue
                val n = (obj["name"] as? JsonPrimitive)?.contentOrNull?.trim()?.uppercase() ?: continue
                byName[n] = obj
            }
            byName
        }.getOrNull() ?: return existingRoster

        return existingRoster.map { ch ->
            val update = parsedMap[ch.name.uppercase()]
                ?: parsedMap.entries.firstOrNull { (k, _) ->
                    k.contains(ch.name, ignoreCase = true) || ch.name.contains(k, ignoreCase = true)
                }?.value
                ?: return@map ch

            val gStr = (update["gender"] as? JsonPrimitive)?.contentOrNull.orEmpty().lowercase()
            val nextGender = when {
                gStr.startsWith("f") -> VoiceGender.FEMALE
                gStr.startsWith("m") -> VoiceGender.MALE
                gStr.startsWith("a") -> VoiceGender.ANDROGYNOUS
                else -> ch.gender
            }
            val nextVis = (update["visual_description"] as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
                .ifBlank { ch.visualDescription }
            val nextVoice = (update["voice_design_prompt"] as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
                .ifBlank { ch.voiceDesignPrompt }
            val nextSpeed = ((update["speed_scale"] as? JsonPrimitive)?.floatOrNull ?: ch.speedScale).coerceIn(0.85f, 1.15f)
            val nextPitch = ((update["pitch_scale"] as? JsonPrimitive)?.floatOrNull ?: ch.pitchScale).coerceIn(0.90f, 1.10f)

            ch.copy(
                gender = nextGender,
                visualDescription = nextVis,
                voiceDesignPrompt = nextVoice,
                speedScale = nextSpeed,
                pitchScale = nextPitch
            )
        }
    }

    private fun cleanupTrailingCommas(jsonText: String): String =
        jsonText.replace(Regex(",\\s*([}\\]])"), "$1")

    private fun parsePanelNumber(elem: kotlinx.serialization.json.JsonElement?): Int? {
        val prim = elem as? JsonPrimitive ?: return null
        prim.intOrNull?.let { return it }
        val digits = prim.contentOrNull.orEmpty().replace(Regex("[^0-9]"), "")
        return digits.toIntOrNull()
    }

    private fun parseStructuredTextFallback(
        body: String,
        panelCount: Int,
        existingCharacters: List<MangaCharacterProfile>,
        thoughtTrace: String
    ): ParsedPageAnalysis? {
        val knownNames = existingCharacters.map { it.name }.distinct()
        val lineRe = Regex("""^\s*(?:\[?P(\d+)\]?\s*[-:]\s*)?([A-Za-z0-9_ ]{2,22}):\s*["“]?(.+?)["”]?\s*$""")
        val grouped = LinkedHashMap<Int, MutableList<DiscoveredLineSpec>>()
        body.lines().forEach { line ->
            val m = lineRe.find(line) ?: return@forEach
            val pIdx = ((m.groupValues[1].toIntOrNull() ?: 1) - 1).coerceIn(0, max(0, panelCount - 1))
            val speaker = m.groupValues[2].trim().uppercase()
            val text = m.groupValues[3].trim()
            if (text.length < 2 || speaker in setOf("THOUGHT", "MOOD", "SCENE", "SUMMARY")) return@forEach
            val list = grouped.getOrPut(pIdx) { mutableListOf() }
            list += DiscoveredLineSpec(
                bubbleIndex = list.size + 1,
                speakerName = speaker,
                isNewCharacter = existingCharacters.none { it.name.equals(speaker, ignoreCase = true) },
                gender = VoiceGender.ANDROGYNOUS,
                visualDescription = "",
                voiceDesignPrompt = "Expressive anime character dialogue voice, clear articulation",
                emotion = "neutral",
                confidence = 0.78f,
                candidateSpeakers = knownNames,
                rawText = text,
                needsUserDecision = speaker == "UNKNOWN",
                decisionReason = if (speaker == "UNKNOWN") "Confirm speaker" else ""
            )
        }
        if (grouped.isEmpty()) return null
        val panels = grouped.entries.map { (pIdx, lines) ->
            DiscoveredPanelSpec(panelIndex = pIdx, sceneMood = "action", thoughtTrace = thoughtTrace, lines = lines)
        }
        return ParsedPageAnalysis(thoughtTrace = thoughtTrace, storySummary = "", panels = panels)
    }
}

object MangaNaturalOrderComparator : Comparator<String> {
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

object MangaTelemetryUtils {
    fun estimateRemainingSec(elapsedMs: Long, fractionDone: Float): Int? {
        if (elapsedMs < 1500L || fractionDone < 0.03f || fractionDone >= 0.995f) return null
        val totalEstimatedMs = (elapsedMs.toDouble() / fractionDone.toDouble())
        val remMs = (totalEstimatedMs - elapsedMs.toDouble()).coerceAtLeast(0.0)
        return (remMs / 1000.0).toInt().coerceAtLeast(1)
    }

    fun formatDurationShort(secondsTotal: Int): String {
        val s = secondsTotal.coerceAtLeast(0)
        val mins = s / 60
        val secs = s % 60
        return if (mins > 0) "%dm %02ds".format(mins, secs) else "${secs}s"
    }
}

/**
 * ✨ HARDENED 4-STRATEGY MANGA ARCHIVE EXTRACTOR (TOOL-11) ✨
 *
 * Android's built-in `java.util.zip.ZipInputStream` rejects `.cbz` packs containing:
 * - `STORED` entries with Data Descriptors (`EXT` flag),
 * - Non-UTF8 filenames (Shift-JIS / MS932 / CP437 / Latin-1),
 * - Certain comic-packer central directories or TAR (`.cbt`) containers.
 *
 * This extractor reads from a seekable file on disk and cascades across:
 * 1. Apache Commons Compress `ZipFile` (UTF-8, Shift_JIS, CP437, ISO-8859-1)
 * 2. Apache Commons Compress `ZipArchiveInputStream` (`allowStoredEntriesWithDataDescriptor = true`)
 * 3. Android / JVM `java.util.zip.ZipFile`
 * 4. Apache Commons Compress `TarArchiveInputStream`
 * with magic-byte sniffing for extensionless images and clear diagnostics for RAR (`.cbr`), 7z, or PDF.
 */
object MangaCbzExtractorUtils {

    val ALLOWED_IMAGE_EXT: Set<String> = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif")
    private val ZIP_CHARSETS: List<String> = listOf("UTF-8", "Shift_JIS", "CP437", "ISO-8859-1")

    fun detectUnsupportedContainerHint(header: ByteArray, length: Int): String? {
        if (length >= 4 &&
            header[0] == 'R'.code.toByte() &&
            header[1] == 'a'.code.toByte() &&
            header[2] == 'r'.code.toByte() &&
            header[3] == '!'.code.toByte()
        ) {
            return "File is a RAR (.cbr) comic archive. Convert or repack it into standard ZIP (.cbz) to import."
        }
        if (length >= 6 &&
            header[0] == 0x37.toByte() &&
            header[1] == 0x7A.toByte() &&
            header[2] == 0xBC.toByte() &&
            header[3] == 0xAF.toByte() &&
            header[4] == 0x27.toByte() &&
            header[5] == 0x1C.toByte()
        ) {
            return "File is a 7-Zip (.cb7) archive. Convert or repack it into standard ZIP (.cbz) to import."
        }
        if (length >= 5 &&
            header[0] == '%'.code.toByte() &&
            header[1] == 'P'.code.toByte() &&
            header[2] == 'D'.code.toByte() &&
            header[3] == 'F'.code.toByte() &&
            header[4] == '-'.code.toByte()
        ) {
            return "File is a .pdf document, not a .cbz image archive."
        }
        return null
    }

    fun detectImageExtensionByMagic(header: ByteArray, length: Int): String? {
        if (length >= 3 &&
            header[0] == 0xFF.toByte() &&
            header[1] == 0xD8.toByte() &&
            header[2] == 0xFF.toByte()
        ) {
            return "jpg"
        }
        if (length >= 8 &&
            header[0] == 0x89.toByte() &&
            header[1] == 0x50.toByte() &&
            header[2] == 0x4E.toByte() &&
            header[3] == 0x47.toByte() &&
            header[4] == 0x0D.toByte() &&
            header[5] == 0x0A.toByte() &&
            header[6] == 0x1A.toByte() &&
            header[7] == 0x0A.toByte()
        ) {
            return "png"
        }
        if (length >= 12 &&
            header[0] == 'R'.code.toByte() &&
            header[1] == 'I'.code.toByte() &&
            header[2] == 'F'.code.toByte() &&
            header[3] == 'F'.code.toByte() &&
            header[8] == 'W'.code.toByte() &&
            header[9] == 'E'.code.toByte() &&
            header[10] == 'B'.code.toByte() &&
            header[11] == 'P'.code.toByte()
        ) {
            return "webp"
        }
        if (length >= 6 &&
            header[0] == 'G'.code.toByte() &&
            header[1] == 'I'.code.toByte() &&
            header[2] == 'F'.code.toByte() &&
            header[3] == '8'.code.toByte()
        ) {
            return "gif"
        }
        if (length >= 2 &&
            header[0] == 'B'.code.toByte() &&
            header[1] == 'M'.code.toByte()
        ) {
            return "bmp"
        }
        return null
    }

    fun extractArchiveFromFile(
        archiveFile: File,
        destDir: File,
        displayName: String = archiveFile.name
    ): List<File> {
        if (!archiveFile.isFile || archiveFile.length() == 0L) {
            throw IllegalStateException("Archive file '$displayName' is empty or unreadable (0 bytes).")
        }
        destDir.mkdirs()

        val header = ByteArray(16)
        val headerRead = archiveFile.inputStream().use { it.read(header) }
        detectUnsupportedContainerHint(header, headerRead)?.let { hint ->
            throw IllegalStateException(hint)
        }

        val failures = mutableListOf<String>()

        // Strategy 1: Commons Compress random-access ZipFile across UTF-8 / Shift_JIS / CP437 / Latin-1
        for (cs in ZIP_CHARSETS) {
            clearTempExtracts(destDir)
            val hit = runCatching { extractWithCommonsZipFile(archiveFile, destDir, cs) }
            val list = hit.getOrNull().orEmpty()
            if (list.isNotEmpty()) return normalizeSortedPages(list, destDir)
            hit.exceptionOrNull()?.message?.let { failures += "ZipFile($cs): $it" }
        }

        // Strategy 2: Commons Compress streaming ZipArchiveInputStream with Data Descriptor support
        for (cs in listOf("UTF-8", "Shift_JIS", "CP437")) {
            clearTempExtracts(destDir)
            val hit = runCatching { extractWithCommonsZipStream(archiveFile, destDir, cs) }
            val list = hit.getOrNull().orEmpty()
            if (list.isNotEmpty()) return normalizeSortedPages(list, destDir)
            hit.exceptionOrNull()?.message?.let { failures += "ZipStream($cs): $it" }
        }

        // Strategy 3: JDK/Android native java.util.zip.ZipFile
        clearTempExtracts(destDir)
        val jdkHit = runCatching { extractWithJavaZipFile(archiveFile, destDir) }
        val jdkList = jdkHit.getOrNull().orEmpty()
        if (jdkList.isNotEmpty()) return normalizeSortedPages(jdkList, destDir)
        jdkHit.exceptionOrNull()?.message?.let { failures += "JdkZipFile: $it" }

        // Strategy 4: TAR (.cbt / mislabeled tarballs)
        clearTempExtracts(destDir)
        val tarHit = runCatching { extractWithTarStream(archiveFile, destDir) }
        val tarList = tarHit.getOrNull().orEmpty()
        if (tarList.isNotEmpty()) return normalizeSortedPages(tarList, destDir)
        tarHit.exceptionOrNull()?.message?.let { failures += "Tar: $it" }

        clearTempExtracts(destDir)
        val reason = failures.firstOrNull()?.take(140) ?: "0 image entries"
        throw IllegalStateException("Could not extract comic pages from '$displayName' ($reason).")
    }

    private fun clearTempExtracts(destDir: File) {
        destDir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("tmp_") }
            ?.forEach { runCatching { it.delete() } }
    }

    private fun extractWithCommonsZipFile(
        archiveFile: File,
        destDir: File,
        charsetName: String
    ): List<Pair<String, File>> {
        val out = mutableListOf<Pair<String, File>>()
        val zip = CommonsZipFile.builder()
            .setFile(archiveFile)
            .setCharset(Charset.forName(charsetName))
            .get()
        zip.use { zf ->
            val entries = zf.entries
            var idx = 0
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                val norm = normalizeEntryName(entry.name)
                if (shouldSkipEntry(norm)) continue
                zf.getInputStream(entry).use { entryStream ->
                    copyImageEntryIfSupported(norm, entryStream, destDir, idx++)?.let { pair ->
                        out += pair
                    }
                }
            }
        }
        return out
    }

    private fun extractWithCommonsZipStream(
        archiveFile: File,
        destDir: File,
        charsetName: String
    ): List<Pair<String, File>> {
        val out = mutableListOf<Pair<String, File>>()
        ZipArchiveInputStream(
            BufferedInputStream(archiveFile.inputStream(), 65536),
            charsetName,
            true,
            true
        ).use { zis ->
            var idx = 0
            while (true) {
                val entry = zis.nextEntry ?: break
                if (entry.isDirectory) continue
                val norm = normalizeEntryName(entry.name)
                if (shouldSkipEntry(norm)) continue
                copyImageEntryIfSupported(norm, zis, destDir, idx++)?.let { pair ->
                    out += pair
                }
            }
        }
        return out
    }

    private fun extractWithJavaZipFile(archiveFile: File, destDir: File): List<Pair<String, File>> {
        val out = mutableListOf<Pair<String, File>>()
        JavaZipFile(archiveFile).use { zf ->
            val entries = zf.entries()
            var idx = 0
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                val norm = normalizeEntryName(entry.name)
                if (shouldSkipEntry(norm)) continue
                zf.getInputStream(entry).use { entryStream ->
                    copyImageEntryIfSupported(norm, entryStream, destDir, idx++)?.let { pair ->
                        out += pair
                    }
                }
            }
        }
        return out
    }

    private fun extractWithTarStream(archiveFile: File, destDir: File): List<Pair<String, File>> {
        val out = mutableListOf<Pair<String, File>>()
        TarArchiveInputStream(BufferedInputStream(archiveFile.inputStream(), 65536)).use { tis ->
            var idx = 0
            while (true) {
                val entry = tis.nextEntry ?: break
                if (entry.isDirectory) continue
                val norm = normalizeEntryName(entry.name)
                if (shouldSkipEntry(norm)) continue
                copyImageEntryIfSupported(norm, tis, destDir, idx++)?.let { pair ->
                    out += pair
                }
            }
        }
        return out
    }

    private fun normalizeEntryName(rawName: String): String =
        rawName.replace('\\', '/').trimStart('/')

    private fun shouldSkipEntry(normName: String): Boolean {
        if (normName.isEmpty()) return true
        val base = normName.substringAfterLast('/')
        return normName.contains("__MACOSX", ignoreCase = true) ||
            base.startsWith("._") ||
            base.equals("Thumbs.db", ignoreCase = true) ||
            base.equals(".DS_Store", ignoreCase = true)
    }

    private fun copyImageEntryIfSupported(
        normName: String,
        stream: InputStream,
        destDir: File,
        tempIdx: Int
    ): Pair<String, File>? {
        val declaredExt = normName.substringAfterLast('/', normName)
            .substringAfterLast('.', "")
            .lowercase(Locale.US)
            .takeIf { it in ALLOWED_IMAGE_EXT }

        val peek = ByteArray(16)
        var peekLen = 0
        while (peekLen < peek.size) {
            val r = stream.read(peek, peekLen, peek.size - peekLen)
            if (r <= 0) break
            peekLen += r
        }
        if (peekLen <= 0) return null

        val resolvedExt = declaredExt ?: detectImageExtensionByMagic(peek, peekLen) ?: return null
        val outFile = File(destDir, "tmp_%05d.%s".format(tempIdx, resolvedExt))
        FileOutputStream(outFile).use { fos ->
            fos.write(peek, 0, peekLen)
            val buf = ByteArray(16384)
            while (true) {
                val r = stream.read(buf)
                if (r < 0) break
                if (r > 0) fos.write(buf, 0, r)
            }
        }
        if (outFile.length() < 32L) {
            outFile.delete()
            return null
        }
        return normName to outFile
    }

    private fun normalizeSortedPages(items: List<Pair<String, File>>, destDir: File): List<File> {
        val sorted = items.sortedWith(compareBy(MangaNaturalOrderComparator) { it.first })
        return sorted.mapIndexed { pageIdx, (_, tmpFile) ->
            val ext = tmpFile.extension.lowercase(Locale.US).ifBlank { "jpg" }
            val cleanFile = File(destDir, "page_%05d.%s".format(pageIdx, ext))
            if (cleanFile.exists() && cleanFile != tmpFile) cleanFile.delete()
            if (tmpFile == cleanFile || tmpFile.renameTo(cleanFile)) {
                cleanFile
            } else {
                tmpFile
            }
        }
    }
}
