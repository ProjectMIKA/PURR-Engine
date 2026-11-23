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
enum class MangaReadingOrder(
    val label: String,
    val shortLabel: String,
    val instructionHint: String,
    val vlmPromptDirective: String
) {
    RTL_MANGA(
        label = "MANGA (Right -> Left)",
        shortLabel = "MANGA (R -> L)",
        instructionHint = "Japanese Manga (Default): Panels & speech bubbles read RIGHT -> LEFT, TOP -> BOTTOM. Rightmost bubble speaks first.",
        vlmPromptDirective = "CRITICAL MANGA READING DIRECTION (RIGHT-TO-LEFT): Read panels and speech bubbles from TOP-RIGHT toward BOTTOM-LEFT. When two speech bubbles are side-by-side, the RIGHT bubble is spoken FIRST and the LEFT bubble is spoken SECOND. When bubbles are stacked on the right before a left bubble, order them Top-Right -> Bottom-Right -> Left."
    ),
    LTR_COMIC(
        label = "COMIC / MANHWA (Left -> Right)",
        shortLabel = "COMIC (L -> R)",
        instructionHint = "Western Comic / Manhwa: Panels & speech bubbles read LEFT -> RIGHT, TOP -> BOTTOM. Leftmost bubble speaks first.",
        vlmPromptDirective = "CRITICAL COMIC READING DIRECTION (LEFT-TO-RIGHT): Read panels and speech bubbles from TOP-LEFT toward BOTTOM-RIGHT. When two speech bubbles are side-by-side, the LEFT bubble is spoken FIRST and the RIGHT bubble is spoken SECOND."
    ),
    WEBTOON_VERTICAL(
        label = "WEBTOON (Vertical Scroll)",
        shortLabel = "WEBTOON (TOP -> DOWN)",
        instructionHint = "Vertical Webtoon: Panels & speech bubbles read strictly TOP -> BOTTOM in vertical scroll order.",
        vlmPromptDirective = "CRITICAL WEBTOON READING DIRECTION (TOP-TO-BOTTOM): Read speech bubbles strictly from TOP to BOTTOM in vertical order."
    )
}

@Serializable
enum class MangaVideoAspect(
    val label: String,
    val shortLabel: String,
    val widthPx: Int,
    val heightPx: Int
) {
    VERTICAL_9_16("PORTRAIT (9:16)", "PORTRAIT 9:16", 720, 1280),
    LANDSCAPE_16_9("LANDSCAPE (16:9)", "LANDSCAPE 16:9", 1280, 720);

    fun resolveDimensions(quality: MangaVideoQuality): Pair<Int, Int> = when (this) {
        VERTICAL_9_16 -> quality.shortSidePx to quality.longSidePx
        LANDSCAPE_16_9 -> quality.longSidePx to quality.shortSidePx
    }
}

@Serializable
enum class MangaVideoQuality(
    val label: String,
    val shortLabel: String,
    val shortSidePx: Int,
    val longSidePx: Int,
    val videoBitrateBps: Int,
    val audioBitrateBps: Int,
    val maxPageDecodeDimPx: Int
) {
    SD_480P("480P (SD • FAST)", "480P", 480, 848, 3_500_000, 128_000, 2560),
    HD_720P("720P (HD • DEFAULT)", "720P", 720, 1280, 8_000_000, 128_000, 3200),
    FHD_1080P("1080P (FULL HD • CRISP)", "1080P", 1080, 1920, 14_000_000, 160_000, 4096)
}

@Serializable
enum class MangaPanelCameraEffect(val label: String) {
    AUTO("AUTO (Deterministic)"),
    READABILITY_HOLD("CRISP HOLD"),
    READING_AXIS_PAN("READING SWEEP"),
    DRAMATIC_PUSH_IN("DRAMATIC PUSH-IN"),
    REVEAL_PULL_BACK("SPLASH PULL-BACK"),
    IMPACT_SHAKE_ZOOM("IMPACT BURST"),
    DIAGONAL_DRIFT("KEN BURNS DRIFT")
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
    val cameraEffect: MangaPanelCameraEffect = MangaPanelCameraEffect.AUTO,
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
    val supportsThinking: Boolean = false,
    val maxContextTokens: Int = 4096
) {
    GEMMA4_E2B(
        specId = "sub_gemma4_e2b_vision",
        fileName = "gemma-4-E2B-it-gpu.litertlm",
        shortLabel = "GEMMA 4 E2B",
        displayTitle = "Gemma 4 E2B LiteRT (2.2 GB)",
        sizeNote = "2.2 GB • Vision + Thinking",
        supportsThinking = true,
        maxContextTokens = 4096
    ),
    GEMMA4_E4B(
        specId = "sub_gemma4_e4b_vision",
        fileName = "gemma-4-E4B-it-gpu.litertlm",
        shortLabel = "GEMMA 4 E4B",
        displayTitle = "Gemma 4 E4B LiteRT (3.1 GB)",
        sizeNote = "3.1 GB • 4B Vision + Thinking",
        supportsThinking = true,
        maxContextTokens = 4096
    ),
    QWEN35_VL_2B(
        specId = "sub_qwen35_vl_2b_vision",
        fileName = "Qwen3.5-2B-VL_int8.litertlm",
        shortLabel = "QWEN 3.5 VL 2B",
        displayTitle = "Qwen 3.5 VL 2B LiteRT (3.0 GB)",
        sizeNote = "3.0 GB • 24-Layer ViT Manga OCR",
        supportsThinking = false,
        maxContextTokens = 4096
    ),
    LFM25_VL_3B(
        specId = "sub_lfm25_vl_3b_vision",
        fileName = "LFM2.5-VL-3B_int4.litertlm",
        shortLabel = "LFM 2.5 VL 3B",
        displayTitle = "Liquid LFM 2.5 VL 3B LiteRT (2.2 GB)",
        sizeNote = "2.2 GB • SigLIP2 Hybrid VLM",
        supportsThinking = false,
        maxContextTokens = 4096
    ),
    QWEN35_VL_08B(
        specId = "sub_qwen35_vl_08b_vision",
        fileName = "Qwen3.5-0.8B-VL_int8.litertlm",
        shortLabel = "QWEN 3.5 VL 0.8B",
        displayTitle = "Qwen 3.5 VL 0.8B LiteRT (1.2 GB)",
        sizeNote = "1.2 GB • Fast Lightweight OCR",
        supportsThinking = false,
        maxContextTokens = 4096
    ),
    INTERNVL3_1B(
        specId = "sub_internvl3_1b_vision",
        fileName = "InternVL3-1B.litertlm",
        shortLabel = "INTERNVL3 1B",
        displayTitle = "InternVL3 1B LiteRT (705 MB)",
        sizeNote = "705 MB • Ultra-Light InternViT",
        supportsThinking = false,
        maxContextTokens = 2048
    );

    companion object {
        fun fromNameOrDefault(raw: String?): MangaVisionModelTier {
            val cleaned = raw?.trim().orEmpty()
            if (cleaned.isEmpty() || cleaned.equals("AUTO", ignoreCase = true)) {
                return GEMMA4_E2B
            }
            return entries.firstOrNull { it.name.equals(cleaned, ignoreCase = true) } ?: GEMMA4_E2B
        }
    }
}

enum class MangaPipelineStage(val stepNum: Int, val label: String) {
    IDLE(0, "IDLE"),
    EXTRACT_AND_SLICE(1, "1/5 UNPACK & SLICE"),
    VISION_SCAN(2, "2/5 VLM PANEL OCR"),
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
    val videoAspect: MangaVideoAspect = MangaVideoAspect.VERTICAL_9_16,
    val burnInSubtitles: Boolean = true,
    val videoQuality: MangaVideoQuality = MangaVideoQuality.HD_720P,
    val enableAtmosphereFx: Boolean = true,
    val includeNonBubbleText: Boolean = false,
    val powerUserVisionUrl: String = "",
    val preferredVisionModel: MangaVisionModelTier = MangaVisionModelTier.GEMMA4_E2B,
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

    private val NON_DIALOGUE_SPEAKERS = setOf(
        "SFX", "SOUND", "SOUND EFFECT", "SOUND_EFFECT", "ONOMATOPOEIA",
        "CREDIT", "CREDITS", "SCANLATION", "WATERMARK", "SIGN",
        "BACKGROUND", "BG_TEXT", "FLOATING_TEXT", "SIGNATURE"
    )

    private val PURE_SFX_TOKENS = setOf(
        "gulp", "bam", "pow", "wham", "crash", "smash", "thud", "thump",
        "whoosh", "fwoosh", "swoosh", "splash", "clang", "clatter", "snap",
        "rustle", "rumble", "menacing", "gasp", "pant", "puff", "huff",
        "shiver", "tremble", "boing", "bonk", "splat", "squish", "slurp",
        "munch", "chomp", "sfx", "clink", "clack", "creak", "crack",
        "boom", "bang", "zip", "zap", "zing", "bwo", "bwodo"
    )

    /**
     * Returns true when a raw OCR candidate is clearly a floating SFX, scanlation credit bar,
     * URL watermark, or handwritten artist signature rather than spoken bubble dialogue.
     */
    fun isNonBubbleOrCreditNoise(speaker: String, rawText: String): Boolean {
        val spNorm = speaker.trim().uppercase(Locale.ROOT)
        if (spNorm in NON_DIALOGUE_SPEAKERS) return true
        val t = rawText.trim()
        if (t.isEmpty()) return true
        val lower = t.lowercase(Locale.ROOT)

        if (lower.startsWith("[sfx") || lower.startsWith("(sfx") ||
            lower.startsWith("sfx:") || lower.startsWith("fx:") ||
            lower.startsWith("*sfx") || lower.startsWith("tl:") ||
            lower.startsWith("t/n:")
        ) return true

        if (listOf(
                "scanlation", "scanlated", "typeset", "redraw",
                "translated by", "cleaned by", "proofread by",
                "brought to you by", "read more at", "https://", "http://"
            ).any { it in lower }
        ) return true

        val hasKana = t.any { ch -> ch in '\u3040'..'\u309F' || ch in '\u30A0'..'\u30FF' }
        val asciiLetters = t.count { it in 'a'..'z' || it in 'A'..'Z' }
        if (lower.contains("さんへ") || (hasKana && asciiLetters <= 8)) return true

        val stripped = lower.replace(Regex("[*\\[\\]()~—–\\-!?.…,\"']"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (stripped.isEmpty()) return true
        val words = stripped.split(' ')
        val isElongatedSfx = Regex("""^(?:sh+a+|f+w+o+s+h+|b+w+o+(?:\s+d+o+)?|k+r+a+k+|z+a+p+|g+r+r+|s+h+h+)$""")
            .matches(stripped)
        if (isElongatedSfx) return true
        if (words.size <= 2 && words.all { it in PURE_SFX_TOKENS }) return true
        return false
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
 * ✨ DETERMINISTIC PANEL CAMERA & MOTION FX ENGINE (Zero-LLM, Zero-Hallucination) ✨
 *
 * Computes repeatable camera choreography directly from panel geometry (`area`, `aspect`),
 * output aspect ratio, reading direction, and scene/dialogue intensity.
 * Protects comic speech bubbles via entry/exit read holds and full-margin framing.
 */
object MangaDeterministicEffectsEngine {

    data class PanelCameraFrameState(
        val sourceCropRect: MangaNormalizedRect,
        val zoomScale: Float = 1.0f,
        val panOffsetXFrac: Float = 0f,
        val panOffsetYFrac: Float = 0f,
        val shakeOffsetXFrac: Float = 0f,
        val shakeOffsetYFrac: Float = 0f,
        val impactFlashAlpha: Float = 0f,
        val showSpeedLines: Boolean = false
    )

    private val IMPACT_KEYWORDS = listOf(
        "anger", "shout", "battle", "furious", "rage", "fierce", "scream",
        "explosion", "attack", "blast", "clash", "shock", "punch", "strike", "smash", "power"
    )

    fun resolveCameraEffect(
        panel: MangaPanelNode,
        totalPanelsOnPage: Int,
        videoAspect: MangaVideoAspect
    ): MangaPanelCameraEffect {
        if (panel.cameraEffect != MangaPanelCameraEffect.AUTO) {
            return panel.cameraEffect
        }

        val rect = panel.panelRect.clamped()
        val area = rect.width * rect.height
        val panelAspect = rect.width / rect.height.coerceAtLeast(0.05f)

        val dialogueCorpus = buildString {
            append(panel.sceneMood)
            append(' ')
            panel.lines.forEach { ln ->
                append(ln.emotionTag)
                append(' ')
                append(ln.rawText)
                append(' ')
                append(ln.sanitizedText)
                append(' ')
            }
        }.lowercase(Locale.ROOT)

        val hasBurstPunctuation = "?!" in dialogueCorpus ||
            "!?" in dialogueCorpus ||
            "!!" in dialogueCorpus
        if ((hasBurstPunctuation || IMPACT_KEYWORDS.any { it in dialogueCorpus }) && panel.lines.isNotEmpty()) {
            return MangaPanelCameraEffect.IMPACT_SHAKE_ZOOM
        }

        val wideInPortrait = videoAspect == MangaVideoAspect.VERTICAL_9_16 &&
            panelAspect >= 2.10f &&
            rect.width >= 0.65f
        val tallInLandscape = videoAspect == MangaVideoAspect.LANDSCAPE_16_9 &&
            ((panelAspect <= 0.62f && rect.height >= 0.55f) ||
                (panelAspect >= 2.35f && rect.width >= 0.72f))
        if (wideInPortrait || tallInLandscape) {
            return MangaPanelCameraEffect.READING_AXIS_PAN
        }

        val seed = ((panel.pageIndex * 31 + panel.panelIndex * 17) and 0x7FFFFFFF)
        return when {
            area >= 0.45f || totalPanelsOnPage == 1 -> {
                if (seed % 2 == 0) MangaPanelCameraEffect.REVEAL_PULL_BACK
                else MangaPanelCameraEffect.DRAMATIC_PUSH_IN
            }
            area < 0.18f -> MangaPanelCameraEffect.READABILITY_HOLD
            area >= 0.30f -> {
                if (seed % 2 == 0) MangaPanelCameraEffect.DRAMATIC_PUSH_IN
                else MangaPanelCameraEffect.DIAGONAL_DRIFT
            }
            else -> MangaPanelCameraEffect.DIAGONAL_DRIFT
        }
    }

    fun computeViewportState(
        panel: MangaPanelNode,
        effect: MangaPanelCameraEffect,
        progress: Float,
        readingOrder: MangaReadingOrder,
        videoAspect: MangaVideoAspect,
        stageAspect: Float
    ): PanelCameraFrameState {
        val rawT = progress.coerceIn(0f, 1f)
        // Entry (0..14%) and exit (86..100%) hold plateaus let viewers read first/last speech bubbles cleanly.
        val easedT = when {
            rawT <= 0.14f -> 0f
            rawT >= 0.86f -> 1f
            else -> {
                val u = (rawT - 0.14f) / 0.72f
                if (u < 0.5f) 4f * u * u * u else 1f - (-2f * u + 2f).let { it * it * it } / 2f
            }
        }

        val resolvedEffect = if (effect == MangaPanelCameraEffect.AUTO) {
            resolveCameraEffect(panel, totalPanelsOnPage = 2, videoAspect = videoAspect)
        } else {
            effect
        }

        val rect = panel.panelRect.clamped()
        val panelAspect = rect.width / rect.height.coerceAtLeast(0.05f)
        val rtl = readingOrder == MangaReadingOrder.RTL_MANGA
        // Portrait 9:16 uses noticeably gentler zoom/pan than Landscape 16:9 so panels stay steady and readable.
        val motionScale = if (videoAspect == MangaVideoAspect.VERTICAL_9_16) 0.55f else 0.85f
        val maxPanZoom = if (videoAspect == MangaVideoAspect.VERTICAL_9_16) 1.16f else 1.25f

        return when (resolvedEffect) {
            MangaPanelCameraEffect.READABILITY_HOLD -> {
                PanelCameraFrameState(
                    sourceCropRect = rect,
                    zoomScale = 1.0f
                )
            }

            MangaPanelCameraEffect.READING_AXIS_PAN -> {
                when {
                    panelAspect > stageAspect * 1.15f -> {
                        val windowW = (rect.width * (1f - 0.14f * motionScale)).coerceIn(rect.width * 0.78f, rect.width)
                        val minLeft = rect.left
                        val maxLeft = (rect.right - windowW).coerceAtLeast(rect.left)
                        val startLeft = if (rtl) maxLeft else minLeft
                        val endLeft = if (rtl) minLeft else maxLeft
                        val curLeft = startLeft + (endLeft - startLeft) * easedT
                        val crop = MangaNormalizedRect(
                            left = curLeft,
                            top = rect.top,
                            right = (curLeft + windowW).coerceAtMost(rect.right),
                            bottom = rect.bottom
                        ).clamped()
                        PanelCameraFrameState(
                            sourceCropRect = crop,
                            zoomScale = (rect.width / windowW).coerceIn(1.0f, maxPanZoom),
                            panOffsetXFrac = ((crop.centerX - rect.centerX) / rect.width.coerceAtLeast(0.01f))
                        )
                    }
                    panelAspect < stageAspect * 0.85f -> {
                        val windowH = (rect.height * (1f - 0.14f * motionScale)).coerceIn(rect.height * 0.78f, rect.height)
                        val minTop = rect.top
                        val maxTop = (rect.bottom - windowH).coerceAtLeast(rect.top)
                        val curTop = minTop + (maxTop - minTop) * easedT
                        val crop = MangaNormalizedRect(
                            left = rect.left,
                            top = curTop,
                            right = rect.right,
                            bottom = (curTop + windowH).coerceAtMost(rect.bottom)
                        ).clamped()
                        PanelCameraFrameState(
                            sourceCropRect = crop,
                            zoomScale = (rect.height / windowH).coerceIn(1.0f, maxPanZoom),
                            panOffsetYFrac = ((crop.centerY - rect.centerY) / rect.height.coerceAtLeast(0.01f))
                        )
                    }
                    else -> {
                        val dx = rect.width * (0.015f * motionScale)
                        val startL = if (rtl) rect.left + dx else rect.left
                        val endL = if (rtl) rect.left else rect.left + dx
                        val curL = startL + (endL - startL) * easedT
                        PanelCameraFrameState(
                            sourceCropRect = MangaNormalizedRect(
                                left = curL,
                                top = rect.top,
                                right = curL + (rect.width - dx),
                                bottom = rect.bottom
                            ).clamped(),
                            zoomScale = 1.0f + 0.018f * motionScale,
                            panOffsetXFrac = if (rtl) (0.01f - 0.02f * easedT) * motionScale else (-0.01f + 0.02f * easedT) * motionScale
                        )
                    }
                }
            }

            MangaPanelCameraEffect.DRAMATIC_PUSH_IN -> {
                val insetFrac = 0.018f * motionScale * easedT
                PanelCameraFrameState(
                    sourceCropRect = insetRect(rect, insetFrac),
                    zoomScale = 1.0f + 0.024f * motionScale * easedT
                )
            }

            MangaPanelCameraEffect.REVEAL_PULL_BACK -> {
                val insetFrac = 0.024f * motionScale * (1f - easedT)
                PanelCameraFrameState(
                    sourceCropRect = insetRect(rect, insetFrac),
                    zoomScale = 1.0f + 0.032f * motionScale * (1f - easedT)
                )
            }

            MangaPanelCameraEffect.IMPACT_SHAKE_ZOOM -> {
                val activeShake = rawT in 0f..0.28f
                val decay = if (activeShake) kotlin.math.exp(-rawT * 9.0f) else 0f
                val shakeX = if (activeShake) kotlin.math.sin(rawT * 52f) * (0.009f * motionScale) * decay else 0f
                val shakeY = if (activeShake) kotlin.math.cos(rawT * 43f) * (0.007f * motionScale) * decay else 0f
                val flash = if (rawT < 0.09f) (1f - rawT / 0.09f) * 0.25f else 0f
                val microInset = 0.012f * motionScale * (1f - easedT)
                PanelCameraFrameState(
                    sourceCropRect = insetRect(rect, microInset),
                    zoomScale = if (activeShake) 1.0f + 0.025f * motionScale else 1.0f + 0.008f * motionScale,
                    shakeOffsetXFrac = shakeX,
                    shakeOffsetYFrac = shakeY,
                    impactFlashAlpha = flash,
                    showSpeedLines = true
                )
            }

            MangaPanelCameraEffect.DIAGONAL_DRIFT,
            MangaPanelCameraEffect.AUTO -> {
                val sx = rect.width * (0.009f * motionScale)
                val sy = rect.height * (0.009f * motionScale)
                val startRect = MangaNormalizedRect(
                    left = rect.left + if (rtl) sx * 0.8f else sx * 0.2f,
                    top = rect.top + sy * 0.2f,
                    right = rect.right - if (rtl) sx * 0.2f else sx * 0.8f,
                    bottom = rect.bottom - sy * 0.8f
                ).clamped()
                val endRect = MangaNormalizedRect(
                    left = rect.left + if (rtl) sx * 0.2f else sx * 0.8f,
                    top = rect.top + sy * 0.8f,
                    right = rect.right - if (rtl) sx * 0.8f else sx * 0.2f,
                    bottom = rect.bottom - sy * 0.2f
                ).clamped()
                val curCrop = MangaNormalizedRect(
                    left = startRect.left + (endRect.left - startRect.left) * easedT,
                    top = startRect.top + (endRect.top - startRect.top) * easedT,
                    right = startRect.right + (endRect.right - startRect.right) * easedT,
                    bottom = startRect.bottom + (endRect.bottom - startRect.bottom) * easedT
                ).clamped()
                PanelCameraFrameState(
                    sourceCropRect = curCrop,
                    zoomScale = 1.0f + 0.014f * motionScale,
                    panOffsetXFrac = (if (rtl) 0.006f - 0.012f * easedT else -0.006f + 0.012f * easedT) * motionScale,
                    panOffsetYFrac = (-0.006f + 0.012f * easedT) * motionScale
                )
            }
        }
    }

    private fun insetRect(rect: MangaNormalizedRect, frac: Float): MangaNormalizedRect {
        val f = frac.coerceIn(0f, 0.20f)
        val dx = rect.width * f
        val dy = rect.height * f
        return MangaNormalizedRect(
            left = rect.left + dx,
            top = rect.top + dy,
            right = rect.right - dx,
            bottom = rect.bottom - dy
        ).clamped()
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

        // 4B. Split oversized composite panel boxes that still hide continuous internal gutters
        val splitPanels = splitCompositePanelBoxes(kept, luminance, gutterMed, tolerance, width, height)

        // 5. Group into reading rows and order deterministically with topological tie-breaking
        return orderPanelsForReadingDirection(splitPanels, readingOrder)
    }

    fun splitCompositePanelBoxes(
        boxes: List<MangaNormalizedRect>,
        luminance: IntArray,
        gutterMed: Int,
        tolerance: Int,
        width: Int,
        height: Int
    ): List<MangaNormalizedRect> {
        if (width < 8 || height < 8 || luminance.size < width * height) return boxes

        fun isGutterPx(idx: Int): Boolean =
            abs(luminance[idx] - gutterMed) <= tolerance || luminance[idx] >= 235

        fun splitRecursive(box: MangaNormalizedRect, depth: Int): List<MangaNormalizedRect> {
            if (depth >= 4 || (box.width < 0.26f && box.height < 0.26f)) return listOf(box)
            val x0 = (box.left * width).toInt().coerceIn(0, width - 1)
            val x1 = (box.right * width).toInt().coerceIn(x0 + 1, width)
            val y0 = (box.top * height).toInt().coerceIn(0, height - 1)
            val y1 = (box.bottom * height).toInt().coerceIn(y0 + 1, height)
            val bw = x1 - x0
            val bh = y1 - y0
            if (bw < 6 || bh < 6) return listOf(box)

            // Try horizontal split across internal gutter band
            if (box.height >= 0.26f) {
                val marginY = max(2, (height * 0.11f).toInt())
                val startY = y0 + marginY
                val endY = y1 - marginY
                var runStart = -1
                var bestStart = -1
                var bestLen = 0
                for (y in startY until endY) {
                    var gCount = 0
                    val rowOff = y * width
                    for (x in x0 until x1) {
                        if (isGutterPx(rowOff + x)) gCount++
                    }
                    val isGutterRow = gCount >= (bw * 0.82f).toInt()
                    if (isGutterRow) {
                        if (runStart < 0) runStart = y
                        val len = y - runStart + 1
                        if (len > bestLen) {
                            bestLen = len
                            bestStart = runStart
                        }
                    } else {
                        runStart = -1
                    }
                }
                if (bestLen >= 2 && bestStart >= startY) {
                    val topFrac = (bestStart.toFloat() / height.toFloat()).coerceIn(box.top + 0.11f, box.bottom - 0.11f)
                    val botFrac = ((bestStart + bestLen).toFloat() / height.toFloat()).coerceIn(topFrac, box.bottom - 0.11f)
                    if (topFrac - box.top >= 0.11f && box.bottom - botFrac >= 0.11f) {
                        val upper = MangaNormalizedRect(box.left, box.top, box.right, topFrac).clamped()
                        val lower = MangaNormalizedRect(box.left, botFrac, box.right, box.bottom).clamped()
                        return splitRecursive(upper, depth + 1) + splitRecursive(lower, depth + 1)
                    }
                }
            }

            // Try vertical split across internal gutter band
            if (box.width >= 0.26f) {
                val marginX = max(2, (width * 0.11f).toInt())
                val startX = x0 + marginX
                val endX = x1 - marginX
                var runStart = -1
                var bestStart = -1
                var bestLen = 0
                for (x in startX until endX) {
                    var gCount = 0
                    for (y in y0 until y1) {
                        if (isGutterPx(y * width + x)) gCount++
                    }
                    val isGutterCol = gCount >= (bh * 0.82f).toInt()
                    if (isGutterCol) {
                        if (runStart < 0) runStart = x
                        val len = x - runStart + 1
                        if (len > bestLen) {
                            bestLen = len
                            bestStart = runStart
                        }
                    } else {
                        runStart = -1
                    }
                }
                if (bestLen >= 2 && bestStart >= startX) {
                    val leftFrac = (bestStart.toFloat() / width.toFloat()).coerceIn(box.left + 0.11f, box.right - 0.11f)
                    val rightFrac = ((bestStart + bestLen).toFloat() / width.toFloat()).coerceIn(leftFrac, box.right - 0.11f)
                    if (leftFrac - box.left >= 0.11f && box.right - rightFrac >= 0.11f) {
                        val leftBox = MangaNormalizedRect(box.left, box.top, leftFrac, box.bottom).clamped()
                        val rightBox = MangaNormalizedRect(rightFrac, box.top, box.right, box.bottom).clamped()
                        return splitRecursive(leftBox, depth + 1) + splitRecursive(rightBox, depth + 1)
                    }
                }
            }

            return listOf(box)
        }

        return boxes.flatMap { splitRecursive(it, 0) }
    }

    fun orderPanelsForReadingDirection(
        boxes: List<MangaNormalizedRect>,
        readingOrder: MangaReadingOrder
    ): List<MangaNormalizedRect> {
        if (boxes.size <= 1) return boxes
        if (readingOrder == MangaReadingOrder.WEBTOON_VERTICAL) {
            return boxes.sortedWith(compareBy<MangaNormalizedRect>({ it.top }, { it.centerX }))
        }

        val orderedByTop = boxes.sortedBy { it.top }
        val rows = ArrayList<MutableList<MangaNormalizedRect>>()
        for (box in orderedByTop) {
            val targetRow = rows.firstOrNull { row ->
                row.any { existing ->
                    val overlapTop = max(existing.top, box.top)
                    val overlapBot = min(existing.bottom, box.bottom)
                    val overlapH = (overlapBot - overlapTop).coerceAtLeast(0f)
                    val maxH = max(existing.height, box.height).coerceAtLeast(0.01f)
                    val minH = min(existing.height, box.height).coerceAtLeast(0.01f)
                    (overlapH / maxH) >= 0.45f || abs(existing.centerY - box.centerY) < minH * 0.35f
                }
            }
            if (targetRow != null) {
                targetRow += box
            } else {
                rows += mutableListOf(box)
            }
        }

        rows.sortBy { row -> row.minOf { it.top } }
        val result = ArrayList<MangaNormalizedRect>(boxes.size)
        val rtl = readingOrder == MangaReadingOrder.RTL_MANGA

        for (row in rows) {
            if (row.size <= 1) {
                result += row
                continue
            }
            // Kahn's topological sort inside the row guarantees stacked panels in the same column
            // always sort top-to-bottom while horizontally distinct panels sort strictly by RTL / LTR.
            val n = row.size
            val indegree = IntArray(n)
            val adj = Array(n) { ArrayList<Int>() }
            for (i in 0 until n) {
                for (j in i + 1 until n) {
                    val a = row[i]
                    val b = row[j]
                    val overlapW = (min(a.right, b.right) - max(a.left, b.left)).coerceAtLeast(0f)
                    val minW = min(a.width, b.width).coerceAtLeast(0.01f)
                    val minH = min(a.height, b.height).coerceAtLeast(0.01f)
                    val sameColumn = (overlapW / minW) >= 0.35f && abs(a.centerY - b.centerY) > minH * 0.25f
                    if (sameColumn) {
                        if (a.top <= b.top) {
                            adj[i] += j; indegree[j]++
                        } else {
                            adj[j] += i; indegree[i]++
                        }
                    } else if (rtl) {
                        if (a.centerX >= b.centerX) {
                            adj[i] += j; indegree[j]++
                        } else {
                            adj[j] += i; indegree[i]++
                        }
                    } else {
                        if (a.centerX <= b.centerX) {
                            adj[i] += j; indegree[j]++
                        } else {
                            adj[j] += i; indegree[i]++
                        }
                    }
                }
            }
            val done = BooleanArray(n)
            repeat(n) {
                val ready = (0 until n).filter { !done[it] && indegree[it] == 0 }
                val pool = ready.ifEmpty { (0 until n).filter { !done[it] } }
                val pick = pool.minWith(
                    compareBy<Int>(
                        { row[it].top },
                        { if (rtl) -row[it].centerX else row[it].centerX }
                    )
                )
                done[pick] = true
                result += row[pick]
                for (nxt in adj[pick]) {
                    indegree[nxt] = (indegree[nxt] - 1).coerceAtLeast(0)
                }
            }
        }
        return result
    }

    /**
     * Ported from GachaSwipe `photoViewerCamera.js`: generates a bubble-safe 1.5% zoomed-in Ken Burns
     * drift pair biased from reading entry corner to exit corner without clipping speech bubbles.
     */
    fun computeKenBurnsPair(
        panelRect: MangaNormalizedRect,
        readingOrder: MangaReadingOrder
    ): Pair<MangaNormalizedRect, MangaNormalizedRect> {
        val shrinkX = panelRect.width * 0.015f
        val shrinkY = panelRect.height * 0.015f
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

    data class ParsedStoryboardCharacter(
        val name: String,
        val gender: VoiceGender,
        val visualDescription: String,
        val voiceDesignPrompt: String
    )

    data class ParsedStoryboardBible(
        val storySummary: String,
        val characters: List<ParsedStoryboardCharacter>,
        val pageSynopses: Map<Int, String>
    )

    private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parseStoryboardPass(rawResponse: String): ParsedStoryboardBible? {
        var cleaned = rawResponse.trim()
            .replace(Regex("""<(?:thought|thinking)>[\s\S]*?</(?:thought|thinking)>""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""```(?:json)?\s*""", RegexOption.IGNORE_CASE), "")
            .replace("```", "")
            .trim()
        val objStart = cleaned.indexOf('{')
        val objEnd = cleaned.lastIndexOf('}')
        if (objStart < 0 || objEnd <= objStart) return null
        val jsonSlice = cleanupTrailingCommas(cleaned.substring(objStart, objEnd + 1))

        return runCatching {
            val root = lenientJson.parseToJsonElement(jsonSlice) as? JsonObject ?: return@runCatching null
            val summary = (root["story_summary"] as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
            val chars = (root["characters"] as? JsonArray).orEmpty().mapNotNull { elem ->
                val obj = elem as? JsonObject ?: return@mapNotNull null
                val rawName = ((obj["name"] ?: obj["speaker"]) as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
                if (rawName.isEmpty() || rawName.equals("UNKNOWN", ignoreCase = true)) return@mapNotNull null
                val genderStr = (obj["gender"] as? JsonPrimitive)?.contentOrNull.orEmpty().lowercase(Locale.ROOT)
                val gender = when {
                    genderStr.startsWith("f") -> VoiceGender.FEMALE
                    genderStr.startsWith("m") -> VoiceGender.MALE
                    else -> VoiceGender.ANDROGYNOUS
                }
                val vis = (obj["visual_description"] as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
                val voicePrompt = ((obj["voice_design_prompt"] ?: obj["voice_prompt"]) as? JsonPrimitive)
                    ?.contentOrNull.orEmpty().trim()
                ParsedStoryboardCharacter(
                    name = rawName.uppercase(Locale.ROOT),
                    gender = gender,
                    visualDescription = vis,
                    voiceDesignPrompt = voicePrompt
                )
            }
            val synopses = LinkedHashMap<Int, String>()
            (root["page_synopses"] as? JsonArray).orEmpty().forEach { elem ->
                val obj = elem as? JsonObject ?: return@forEach
                val pageNum = parsePanelNumber(obj["page"] ?: obj["page_number"]) ?: return@forEach
                val syn = ((obj["synopsis"] ?: obj["summary"]) as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
                if (pageNum >= 1 && syn.isNotEmpty()) {
                    synopses[pageNum - 1] = syn
                }
            }
            if (summary.isBlank() && chars.isEmpty() && synopses.isEmpty()) null
            else ParsedStoryboardBible(storySummary = summary, characters = chars, pageSynopses = synopses)
        }.getOrNull()
    }

    fun parsePageAnalysis(
        rawResponse: String,
        panelCount: Int,
        existingCharacters: List<MangaCharacterProfile>,
        includeNonBubbleText: Boolean = false
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
                    val parsedLines = parseLineSpecArray(linesArr, pIdx, knownNames, includeNonBubbleText)
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
        val textFallback = parseStructuredTextFallback(
            body = cleaned,
            panelCount = panelCount,
            existingCharacters = existingCharacters,
            thoughtTrace = thoughtTrace,
            includeNonBubbleText = includeNonBubbleText
        )
        return textFallback ?: ParsedPageAnalysis(thoughtTrace = thoughtTrace, storySummary = "", panels = emptyList())
    }

    /**
     * Parses a focused single-panel VLM response, locking all discovered dialogue lines to [panelIndex]
     * so bubbles never drift across panels even when scanning busy spreads panel-by-panel.
     */
    fun parseSinglePanelAnalysis(
        rawResponse: String,
        panelIndex: Int,
        existingCharacters: List<MangaCharacterProfile>,
        includeNonBubbleText: Boolean = false
    ): DiscoveredPanelSpec {
        var thoughtTrace = ""
        var cleaned = rawResponse.trim()

        val thoughtMatch = Regex("""<(?:thought|thinking)>([\s\S]*?)</(?:thought|thinking)>""", RegexOption.IGNORE_CASE)
            .find(cleaned)
        if (thoughtMatch != null) {
            thoughtTrace = thoughtMatch.groupValues[1].trim()
            cleaned = cleaned.replaceRange(thoughtMatch.range, "").trim()
        }

        cleaned = cleaned
            .replace(Regex("""```(?:json)?\s*""", RegexOption.IGNORE_CASE), "")
            .replace("```", "")
            .trim()

        val objStart = cleaned.indexOf('{')
        val objEnd = cleaned.lastIndexOf('}')
        val arrStart = cleaned.indexOf('[')
        val arrEnd = cleaned.lastIndexOf(']')
        val knownNames = existingCharacters.map { it.name }.distinct()

        val jsonCandidate: String? = when {
            arrStart >= 0 && arrEnd > arrStart && (objStart < 0 || arrStart < objStart) ->
                cleaned.substring(arrStart, arrEnd + 1)
            objStart >= 0 && objEnd > objStart ->
                cleaned.substring(objStart, objEnd + 1)
            arrStart >= 0 && arrEnd > arrStart ->
                cleaned.substring(arrStart, arrEnd + 1)
            else -> null
        }

        var parsedMood = ""
        var parsedThought = ""
        var parsedLines: List<DiscoveredLineSpec> = emptyList()

        if (jsonCandidate != null) {
            val sanitized = cleanupTrailingCommas(jsonCandidate)
            runCatching {
                when (val elem = lenientJson.parseToJsonElement(sanitized)) {
                    is JsonObject -> {
                        parsedMood = ((elem["scene_mood"] ?: elem["mood"]) as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
                        parsedThought = (elem["thought"] as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
                        val directLines = (elem["lines"] ?: elem["dialogue"]) as? JsonArray
                        if (directLines != null) {
                            parsedLines = parseLineSpecArray(directLines, panelIndex, knownNames, includeNonBubbleText)
                        } else {
                            val panelsArr = elem["panels"] as? JsonArray
                            if (panelsArr != null) {
                                val combined = ArrayList<DiscoveredLineSpec>()
                                for (pElem in panelsArr) {
                                    val pObj = pElem as? JsonObject ?: continue
                                    if (parsedMood.isEmpty()) {
                                        parsedMood = ((pObj["scene_mood"] ?: pObj["mood"]) as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
                                    }
                                    if (parsedThought.isEmpty()) {
                                        parsedThought = (pObj["thought"] as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
                                    }
                                    val arr = (pObj["lines"] ?: pObj["dialogue"]) as? JsonArray ?: continue
                                    combined += parseLineSpecArray(arr, panelIndex, knownNames, includeNonBubbleText)
                                }
                                parsedLines = combined
                            }
                        }
                    }
                    is JsonArray -> {
                        val firstObj = elem.firstOrNull() as? JsonObject
                        if (firstObj != null && (firstObj.containsKey("lines") || firstObj.containsKey("dialogue")) &&
                            !firstObj.containsKey("text") && !firstObj.containsKey("speech")
                        ) {
                            val combined = ArrayList<DiscoveredLineSpec>()
                            for (pElem in elem) {
                                val pObj = pElem as? JsonObject ?: continue
                                if (parsedMood.isEmpty()) {
                                    parsedMood = ((pObj["scene_mood"] ?: pObj["mood"]) as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
                                }
                                val arr = (pObj["lines"] ?: pObj["dialogue"]) as? JsonArray ?: continue
                                combined += parseLineSpecArray(arr, panelIndex, knownNames, includeNonBubbleText)
                            }
                            parsedLines = combined
                        } else {
                            parsedLines = parseLineSpecArray(elem, panelIndex, knownNames, includeNonBubbleText)
                        }
                    }
                    else -> Unit
                }
            }
        }

        if (parsedLines.isEmpty()) {
            val textFallback = parseStructuredTextFallback(
                body = cleaned,
                panelCount = 1,
                existingCharacters = existingCharacters,
                thoughtTrace = thoughtTrace,
                includeNonBubbleText = includeNonBubbleText
            )
            if (textFallback != null) {
                parsedLines = textFallback.panels.flatMap { it.lines }
            }
        }

        return DiscoveredPanelSpec(
            panelIndex = panelIndex,
            sceneMood = parsedMood.ifBlank { "manga_panel" },
            thoughtTrace = parsedThought.ifBlank { thoughtTrace },
            lines = parsedLines
        )
    }

    private fun parseLineSpecArray(
        linesArr: JsonArray,
        pIdx: Int,
        knownNames: List<String>,
        includeNonBubbleText: Boolean = false
    ): List<DiscoveredLineSpec> = linesArr.mapIndexedNotNull { lIdx, lElem ->
        val lObj = lElem as? JsonObject ?: return@mapIndexedNotNull null
        val rawText = ((lObj["text"] ?: lObj["dialogue"] ?: lObj["line"] ?: lObj["speech"]) as? JsonPrimitive)
            ?.contentOrNull.orEmpty().trim()
        if (rawText.isEmpty()) return@mapIndexedNotNull null
        val speakerRaw = ((lObj["speaker"] ?: lObj["character"] ?: lObj["name"]) as? JsonPrimitive)
            ?.contentOrNull.orEmpty().trim()
            .ifEmpty { "UNKNOWN" }
        val isSpeechBubble = (lObj["is_speech_bubble"] as? JsonPrimitive)?.booleanOrNull ?: true
        if (!includeNonBubbleText && (!isSpeechBubble || MangaTextBender.isNonBubbleOrCreditNoise(speakerRaw, rawText))) {
            return@mapIndexedNotNull null
        }
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
            ambiguousSpeaker -> "VLM flagged ambiguous speaker in Panel ${pIdx + 1} — tap a character below"
            conf < 0.55f -> "Low VLM confidence (${(conf * 100).toInt()}%) — verify speaker attribution"
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
        thoughtTrace: String,
        includeNonBubbleText: Boolean = false
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
            if (!includeNonBubbleText && MangaTextBender.isNonBubbleOrCreditNoise(speaker, text)) return@forEach
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
