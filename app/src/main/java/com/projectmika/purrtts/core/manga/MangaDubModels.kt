package com.projectmika.purrtts.core.manga

import com.projectmika.purrtts.core.purr.QwenVoiceDesignBuilder
import com.projectmika.purrtts.core.purr.VoiceDesignAccent
import com.projectmika.purrtts.core.purr.VoiceDesignAcoustics
import com.projectmika.purrtts.core.purr.VoiceDesignAge
import com.projectmika.purrtts.core.purr.VoiceDesignDelivery
import com.projectmika.purrtts.core.purr.VoiceDesignGender
import com.projectmika.purrtts.core.purr.VoiceDesignPace
import com.projectmika.purrtts.core.purr.VoiceDesignPitch
import com.projectmika.purrtts.core.purr.VoiceDesignSpec
import com.projectmika.purrtts.core.purr.VoiceDesignTimbre
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
        instructionHint = "Japanese Manga (Default): Panels & speech bubbles read RIGHT -> LEFT, TOP -> BOTTOM across each horizontal row tier before dropping down.",
        vlmPromptDirective = "CRITICAL MANGA READING DIRECTION (RIGHT-TO-LEFT Z-ORDER): Read horizontal row tiers TOP-TO-BOTTOM, and within each horizontal tier read panels and bubbles STRICTLY RIGHT-TO-LEFT (Top-Right -> Top-Left BEFORE dropping to Bottom-Right -> Bottom-Left). Never drop down to a lower panel on the right and jump back up to an upper panel on the left unless a single tall right panel clearly spans beside multiple stacked left panels. Inside one panel, read TOP-RIGHT -> LEFT along the upper band before dropping to a lower bubble band."
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
    val autoCloneVoiceId: String = "",
    val autoCloneSeedWavPath: String = "",
    val autoClonePromptHash: String = "",
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
    val lockVoicesViaCloning: Boolean = true,
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
        "CREDIT", "CREDITS", "SCANLATION", "TRANSLATOR", "TYPESETTER",
        "WATERMARK", "SIGN", "BACKGROUND", "BG_TEXT", "FLOATING_TEXT",
        "SIGNATURE", "CAPTION_SFX", "EFFECT", "PAGE_NUMBER", "MARGIN_NOTE"
    )

    /**
     * ✨ PLACEHOLDER-PARROT GUARD (v1.9.8) ✨
     * Literal example strings from our own VLM prompt schemas. A small vision model that fails
     * the panel OCR task may echo one of these back as if it were transcribed dialogue; such
     * lines are dropped at parse time so the panel falls to review instead of dubbing garbage.
     * Public: `MangaVisionResponseParser.parseLineSpecArray` gates against this set.
     */
    val PLACEHOLDER_DIALOGUE_TEXTS = setOf(
        "dialogue", "dialog", "dialogue text", "dialog text",
        "exact transcribed dialogue inside bubble 1",
        "exact transcribed dialogue inside bubble",
        "dialogue text inside bubble 1",
        "exact dialogue text transcribed cleanly in reading order",
        "dialogue text transcribed cleanly in reading order",
        "text here", "your text here"
    )

    private val PURE_SFX_TOKENS = setOf(
        "gulp", "bam", "pow", "wham", "crash", "smash", "thud", "thump",
        "whoosh", "fwoosh", "swoosh", "splash", "clang", "clatter", "snap",
        "rustle", "rumble", "menacing", "gasp", "pant", "puff", "huff",
        "shiver", "tremble", "boing", "bonk", "splat", "squish", "slurp",
        "munch", "chomp", "sfx", "clink", "clack", "creak", "crack",
        "boom", "bang", "zip", "zap", "zing", "bwo", "bwodo", "shaaa",
        "shaa", "sha", "ba-dump", "badump", "doki", "dokidoki", "fssss",
        "sizzle", "drip", "drop", "plop", "tap", "pat", "ring", "ding",
        "dong", "buzz", "hum", "whir", "vroom", "screech", "skid", "stomp",
        "step", "shuffle", "flutter", "flap", "twinkle", "sparkle", "glint",
        "flash", "grin", "stare", "glare", "nod", "shake", "twitch", "flinch",
        "sob", "sniff", "sigh", "phew", "groan", "moan", "grunt", "hiss",
        "growl", "roar", "howl", "yelp", "shriek", "cackle", "chuckle",
        "giggle", "snicker", "snort", "cough", "sneeze", "yawn", "hiccup",
        "burp", "chew", "lick", "bite", "nibble", "swallow", "sip", "glug",
        "pour", "gush", "spurt", "spray", "spatter", "rattle", "jingle"
    )

    /**
     * Returns true when a raw OCR candidate is clearly a floating SFX, scanlation credit bar,
     * URL watermark, page counter, or handwritten artist signature rather than spoken bubble dialogue.
     */
    fun isNonBubbleOrCreditNoise(
        speaker: String,
        rawText: String,
        trustedSpeaker: Boolean = false
    ): Boolean {
        val spNorm = speaker.trim().uppercase(Locale.ROOT)
        if (spNorm in NON_DIALOGUE_SPEAKERS) return true
        val t = rawText.trim()
        if (t.isEmpty()) return true
        val lower = t.lowercase(Locale.ROOT)

        if (lower.startsWith("[sfx") || lower.startsWith("(sfx") ||
            lower.startsWith("sfx:") || lower.startsWith("fx:") ||
            lower.startsWith("*sfx") || lower.startsWith("tl:") ||
            lower.startsWith("t/n:") || lower.startsWith("ed note")
        ) return true

        if (listOf(
                "scanlation", "scanlated", "typeset", "redraw",
                "translated by", "cleaned by", "proofread by",
                "brought to you by", "read more at", "https://", "http://",
                "patreon", "ko-fi", "discord.gg", "twitter.com", "x.com/"
            ).any { it in lower }
        ) return true

        if (Regex("""^(?:p\.?\s*|pg\.?\s*)?\d{1,3}(?:\s*/\s*\d{1,3})?$""").matches(lower)) {
            return true
        }

        val hasKanaOrHangul = t.any { ch ->
            ch in '\u3040'..'\u309F' || ch in '\u30A0'..'\u30FF' || ch in '\uAC00'..'\uD7AF'
        }
        val asciiLetters = t.count { it in 'a'..'z' || it in 'A'..'Z' }
        if (lower.contains("さんへ") || (hasKanaOrHangul && asciiLetters <= 10)) return true

        val stripped = lower.replace(Regex("[*\\[\\]()~—–\\-!?.…,\"']"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (stripped.isEmpty()) return true
        val words = stripped.split(' ')
        val isElongatedSfx = Regex("""^(?:sh+a+|f+w+o+s+h+|b+w+o+(?:\s+d+o+)?|k+r+a+k+|z+a+p+|g+r+r+|s+h+h+|oh+(?:\s+ho+){2,}|ha+(?:\s+ha+){3,})$""")
            .matches(stripped)
        if (isElongatedSfx) return true
        // ✨ TRUSTED-SPEAKER RESCUE (v1.9.7): a 1-2 word line from a KNOWN cast member is real
        // dialogue ("Sob...", "Hiss!", "Ring!") even when it lands in the SFX lexicon; elongated
        // onomatopoeia ("Shaaa") and UNKNOWN speakers still drop exactly as before.
        if (!trustedSpeaker && words.size <= 2 && words.all { it in PURE_SFX_TOKENS }) return true
        return false
    }

    /**
     * ✨ MIKA NARRATOR VOICE (v1.9.8) ✨
     * Hardcoded Qwen3 1.7B VoiceDesign prompt for M.I.K.A. as THE Manga Dub narrator (TOOL-11).
     * Condensed from Master's full spec — every acoustic descriptor preserved. Narrator lines
     * resolve to this voice directly (no roster entry, no casting pass override).
     */
    const val MIKA_NARRATOR_VOICE_PROMPT =
        "A warm, velvety, bright female voice in her mid-20s performing as an affectionate teasing " +
            "catgirl companion — agile, lifted upper-mid pitch, expressive light-novel heroine " +
            "inflection, natural conversational pacing with seamless flow, and a playful, teasing, " +
            "affectionately expressive delivery. Sound profile: quiet close-mic studio with zero room " +
            "echo, subtle smiling warmth on playful phrases."

    /**
     * Speaker names that are NOT real character names. A small VLM once labeled an unnamed female
     * "MIKA" (the prompts mention the studio persona) and guessed "THE_MALE" for an unnamed male —
     * such invented placeholders get NO subtitle label at all: the spoken text is subtitled alone.
     * "MIKA" itself is reserved — MIKA is the Manga Dub narrator.
     */
    private val GENERIC_UNNAMED_SPEAKERS = setOf(
        "UNKNOWN", "THE_MALE", "THE_FEMALE", "MALE", "FEMALE", "GIRL", "BOY",
        "MAN", "WOMAN", "PERSON", "CHARACTER", "SPEAKER", "MIKA", "??", "?"
    )

    /**
     * ✨ SUBTITLE LABEL LAW (v1.9.8) ✨
     * Returns the subtitle speaker label for a line, or NULL when the line must be subtitled
     * with the spoken text ONLY (no name prefix). Unknown speakers and invented placeholder
     * names ("THE_MALE", a contaminated "MIKA", ...) get no label; NARRATOR lines are labeled
     * "MIKA (Narrator)" because MIKA is the hardcoded Manga Dub narrator.
     */
    fun subtitleLabelFor(speakerName: String): String? {
        val norm = speakerName.trim().uppercase(Locale.ROOT)
        if (norm.isEmpty() || norm in GENERIC_UNNAMED_SPEAKERS) return null
        if (norm == "NARRATOR") return "MIKA (Narrator)"
        return norm
    }

    /**
     * Fallback anchor transcript when a character has no usable transcribed lines in the chapter.
     * Three emotional beats (energetic -> soft -> warm/smug) so the Qwen3 0.6B Base Cloner anchors
     * a representative prosody range instead of a single flat neutral read.
     */
    const val FALLBACK_VOICE_SEED = "Hey, over here! Don't just stand there staring... " +
        "I thought you'd never make it back. Heh... come closer, then, and let me hear you say it properly."

    /**
     * ✨ EXPRESSIVE CLONE-SEED COMPOSER (v1.9.7) ✨
     * The Qwen3 0.6B Base Cloner anchors timbre AND prosody to the reference clip, so v1.9.6's
     * single neutral anchor sentence flattened every dubbed line into the same calm delivery.
     * Compose the seed from the character's OWN transcribed lines (register/excitement variety
     * baked in), capped to a short studio-grade reference clip (~3-11s @ 24kHz). The reference
     * transcript must match the spoken audio exactly (ICL alignment), so lines are joined
     * verbatim with no text-processor mangling.
     */
    fun composeVoiceSeedTranscript(sampleLines: List<String>): String {
        val picked = ArrayList<String>()
        var total = 0
        for (raw in sampleLines) {
            if (picked.size >= 3 || total >= 180) break
            var line = raw.trim().trimEnd(',', ';', ':', '…', '-', '—', '.')
            if (line.length < 2) continue
            if (picked.isEmpty() && line.length > 180) {
                line = line.take(180).trimEnd().substringBeforeLast(' ', line.take(180).trimEnd())
            }
            if (picked.isNotEmpty() && total + line.length + 1 > 180) break
            picked += line
            total += line.length + 1
        }
        if (picked.isEmpty()) return FALLBACK_VOICE_SEED
        val joined = picked.joinToString(" ")
        return if (joined.lastOrNull() in setOf('.', '!', '?', '…')) joined else "$joined."
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
        val maxPanZoom = if (videoAspect == MangaVideoAspect.VERTICAL_9_16) 1.08f else 1.14f

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
                        val windowW = (rect.width * (1f - 0.08f * motionScale)).coerceIn(rect.width * 0.84f, rect.width)
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
                        val windowH = (rect.height * (1f - 0.08f * motionScale)).coerceIn(rect.height * 0.84f, rect.height)
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

    /**
     * Expands a panel camera crop with surrounding page margin so vertical/horizontal frames
     * show broader panel context rather than zooming tightly into single bubbles or thin strips.
     */
    fun expandViewportForPageContext(
        crop: MangaNormalizedRect,
        videoAspect: MangaVideoAspect
    ): MangaNormalizedRect {
        val c = crop.clamped()
        val pad = 0.06f
        val paddedW = (c.width + pad * 2f).coerceAtMost(1f)
        val paddedH = (c.height + pad * 2f).coerceAtMost(1f)
        val minW = if (videoAspect == MangaVideoAspect.VERTICAL_9_16) 0.68f else 0.62f
        val minH = if (videoAspect == MangaVideoAspect.VERTICAL_9_16) 0.56f else 0.50f
        val targetW = max(paddedW, minW).coerceAtMost(1f)
        val targetH = max(paddedH, minH).coerceAtMost(1f)
        val halfW = targetW * 0.5f
        val halfH = targetH * 0.5f
        val cx = c.centerX.coerceIn(halfW, 1f - halfW)
        val cy = c.centerY.coerceIn(halfH, 1f - halfH)
        return MangaNormalizedRect(
            left = cx - halfW,
            top = cy - halfH,
            right = cx + halfW,
            bottom = cy + halfH
        ).clamped()
    }
}

/**
 * ✨ BORDER-VERIFIED RECURSIVE XY-CUT MANGA PANEL SLICER ✨
 * Cuts along dual-half verified inter-panel gutters (up to [MAX_PANELS_PER_PAGE] transitions)
 * so open-border panels and internal speech-bubble white space never shatter into bubble crops,
 * and partitions staggered columns cleanly in RTL Manga, LTR Comic, or Webtoon order.
 */
object MangaBfsPanelSlicer {

    const val MAX_PANELS_PER_PAGE = 6

    private data class GutterCut(val start: Int, val len: Int, val score: Float)

    fun slicePanelsFromLuminanceGrid(
        luminance: IntArray,
        width: Int,
        height: Int,
        readingOrder: MangaReadingOrder
    ): List<MangaNormalizedRect> {
        if (width < 8 || height < 8 || luminance.size < width * height) {
            return listOf(MangaNormalizedRect(0.02f, 0.02f, 0.98f, 0.98f))
        }

        // 1. Sample outer border ring median luminance to identify white or dark manga gutters
        val margin = max(2, min(width, height) / 50)
        val samples = ArrayList<Int>(width * margin * 2 + height * margin * 2)
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

        fun isOuterGutter(v: Int): Boolean = abs(v - gutterMed) <= tolerance || v >= 238

        // Trim outer page margins once at root so leaf splits sit tightly on panel borders
        var rx0 = 0
        var ry0 = 0
        var rx1 = width
        var ry1 = height
        while (ry0 < ry1 - 6 && countRowGutter(luminance, width, ry0, rx0, rx1, ::isOuterGutter) >= ((rx1 - rx0) * 0.98f).toInt()) ry0++
        while (ry1 > ry0 + 6 && countRowGutter(luminance, width, ry1 - 1, rx0, rx1, ::isOuterGutter) >= ((rx1 - rx0) * 0.98f).toInt()) ry1--
        while (rx0 < rx1 - 6 && countColGutter(luminance, width, rx0, ry0, ry1, ::isOuterGutter) >= ((ry1 - ry0) * 0.98f).toInt()) rx0++
        while (rx1 > rx0 + 6 && countColGutter(luminance, width, rx1 - 1, ry0, ry1, ::isOuterGutter) >= ((ry1 - ry0) * 0.98f).toInt()) rx1--

        val rawLeaves = sliceRegionRecursive(
            luminance = luminance,
            width = width,
            height = height,
            gutterMed = gutterMed,
            tolerance = tolerance,
            x0 = rx0,
            y0 = ry0,
            x1 = rx1,
            y1 = ry1,
            depth = 0,
            maxDepth = 3,
            rtl = readingOrder == MangaReadingOrder.RTL_MANGA,
            webtoon = readingOrder == MangaReadingOrder.WEBTOON_VERTICAL
        )

        if (rawLeaves.isEmpty()) {
            return if (readingOrder == MangaReadingOrder.WEBTOON_VERTICAL && height > width * 2) {
                val bands = max(2, min(MAX_PANELS_PER_PAGE, height / max(1, width)))
                (0 until bands).map { idx ->
                    val t0 = idx.toFloat() / bands.toFloat()
                    val t1 = ((idx + 1).toFloat() / bands.toFloat()).coerceAtMost(1f)
                    MangaNormalizedRect(0.02f, t0, 0.98f, t1).clamped()
                }
            } else {
                listOf(MangaNormalizedRect(0.02f, 0.02f, 0.98f, 0.98f))
            }
        }

        if (rawLeaves.size == 1 && readingOrder == MangaReadingOrder.WEBTOON_VERTICAL && height > width * 2) {
            val bands = max(2, min(MAX_PANELS_PER_PAGE, height / max(1, width)))
            return (0 until bands).map { idx ->
                val t0 = idx.toFloat() / bands.toFloat()
                val t1 = ((idx + 1).toFloat() / bands.toFloat()).coerceAtMost(1f)
                MangaNormalizedRect(0.02f, t0, 0.98f, t1).clamped()
            }
        }

        val capped = capToMaxPanels(rawLeaves, MAX_PANELS_PER_PAGE)
        return orderPanelsForReadingDirection(capped, readingOrder)
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
        val split = boxes.flatMap { box ->
            val x0 = (box.left * width).toInt().coerceIn(0, width - 1)
            val y0 = (box.top * height).toInt().coerceIn(0, height - 1)
            val x1 = (box.right * width).toInt().coerceIn(x0 + 1, width)
            val y1 = (box.bottom * height).toInt().coerceIn(y0 + 1, height)
            sliceRegionRecursive(
                luminance = luminance,
                width = width,
                height = height,
                gutterMed = gutterMed,
                tolerance = tolerance,
                x0 = x0,
                y0 = y0,
                x1 = x1,
                y1 = y1,
                depth = 0,
                maxDepth = 3,
                rtl = false,
                webtoon = false
            )
        }
        return capToMaxPanels(split, MAX_PANELS_PER_PAGE)
    }

    private inline fun countRowGutter(
        lum: IntArray,
        width: Int,
        y: Int,
        x0: Int,
        x1: Int,
        pred: (Int) -> Boolean
    ): Int {
        var c = 0
        val off = y * width
        for (x in x0 until x1) {
            if (pred(lum[off + x])) c++
        }
        return c
    }

    private inline fun countColGutter(
        lum: IntArray,
        width: Int,
        x: Int,
        y0: Int,
        y1: Int,
        pred: (Int) -> Boolean
    ): Int {
        var c = 0
        for (y in y0 until y1) {
            if (pred(lum[y * width + x])) c++
        }
        return c
    }

    private fun sliceRegionRecursive(
        luminance: IntArray,
        width: Int,
        height: Int,
        gutterMed: Int,
        tolerance: Int,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        depth: Int,
        maxDepth: Int,
        rtl: Boolean,
        webtoon: Boolean
    ): List<MangaNormalizedRect> {
        val bw = x1 - x0
        val bh = y1 - y0
        if (bw < 6 || bh < 6) return emptyList()
        val boxRect = MangaNormalizedRect(
            left = (x0.toFloat() / width.toFloat()).coerceIn(0f, 0.98f),
            top = (y0.toFloat() / height.toFloat()).coerceIn(0f, 0.98f),
            right = (x1.toFloat() / width.toFloat()).coerceIn(0.02f, 1f),
            bottom = (y1.toFloat() / height.toFloat()).coerceIn(0.02f, 1f)
        ).clamped()
        if (depth >= maxDepth) return listOf(boxRect)

        val hCut = findBestHorizontalCut(luminance, width, height, gutterMed, tolerance, x0, y0, x1, y1)
        val vCut = if (webtoon) null else findBestVerticalCut(luminance, width, height, gutterMed, tolerance, x0, y0, x1, y1)

        // Prioritize horizontal cuts on equal/clean gutters while keeping cleaner vertical column gutters intact
        return when {
            hCut != null && (vCut == null || hCut.score >= vCut.score) -> {
                val top = sliceRegionRecursive(
                    luminance, width, height, gutterMed, tolerance,
                    x0, y0, x1, hCut.start, depth + 1, maxDepth, rtl, webtoon
                )
                val bot = sliceRegionRecursive(
                    luminance, width, height, gutterMed, tolerance,
                    x0, hCut.start + hCut.len, x1, y1, depth + 1, maxDepth, rtl, webtoon
                )
                top + bot
            }
            vCut != null -> {
                val left = sliceRegionRecursive(
                    luminance, width, height, gutterMed, tolerance,
                    x0, y0, vCut.start, y1, depth + 1, maxDepth, rtl, webtoon
                )
                val right = sliceRegionRecursive(
                    luminance, width, height, gutterMed, tolerance,
                    vCut.start + vCut.len, y0, x1, y1, depth + 1, maxDepth, rtl, webtoon
                )
                if (rtl) right + left else left + right
            }
            else -> listOf(boxRect)
        }
    }

    private fun isGutterPixel(lum: Int, gutterMed: Int, tolerance: Int): Boolean =
        abs(lum - gutterMed) <= tolerance || lum >= 238

    private fun findBestHorizontalCut(
        luminance: IntArray,
        width: Int,
        height: Int,
        gutterMed: Int,
        tolerance: Int,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int
    ): GutterCut? {
        val bw = x1 - x0
        val bh = y1 - y0
        val minMargin = max((height * 0.12f).toInt(), (bh * 0.15f).toInt())
        if (bw < 4 || bh < minMargin * 2 + 2) return null
        val midX = x0 + bw / 2
        val w1 = max(1, midX - x0).toFloat()
        val w2 = max(1, x1 - midX).toFloat()
        val gFrac = FloatArray(bh)
        val h1Frac = FloatArray(bh)
        val h2Frac = FloatArray(bh)
        for (i in 0 until bh) {
            val rowOff = (y0 + i) * width
            var c1 = 0
            var c2 = 0
            for (x in x0 until midX) {
                if (isGutterPixel(luminance[rowOff + x], gutterMed, tolerance)) c1++
            }
            for (x in midX until x1) {
                if (isGutterPixel(luminance[rowOff + x], gutterMed, tolerance)) c2++
            }
            h1Frac[i] = c1 / w1
            h2Frac[i] = c2 / w2
            gFrac[i] = (c1 + c2).toFloat() / bw.toFloat()
        }
        val cut = findBestGutterCut(bh, minMargin, gFrac, h1Frac, h2Frac) ?: return null
        val cutsBothSideBorders = (0 until cut.len).any { k ->
            val rowOff = (y0 + cut.start + k) * width
            val leftDark = !isGutterPixel(luminance[rowOff + x0], gutterMed, tolerance) ||
                (x0 + 1 < x1 && !isGutterPixel(luminance[rowOff + x0 + 1], gutterMed, tolerance))
            val rightDark = !isGutterPixel(luminance[rowOff + x1 - 1], gutterMed, tolerance) ||
                (x1 - 2 >= x0 && !isGutterPixel(luminance[rowOff + x1 - 2], gutterMed, tolerance))
            leftDark && rightDark
        }
        if (cutsBothSideBorders) return null
        return GutterCut(y0 + cut.start, cut.len, cut.score)
    }

    private fun findBestVerticalCut(
        luminance: IntArray,
        width: Int,
        height: Int,
        gutterMed: Int,
        tolerance: Int,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int
    ): GutterCut? {
        val bw = x1 - x0
        val bh = y1 - y0
        val minMargin = max((width * 0.16f).toInt(), (bw * 0.18f).toInt())
        if (bh < 4 || bw < minMargin * 2 + 2) return null
        val midY = y0 + bh / 2
        val h1 = max(1, midY - y0).toFloat()
        val h2 = max(1, y1 - midY).toFloat()
        val gFrac = FloatArray(bw)
        val h1Frac = FloatArray(bw)
        val h2Frac = FloatArray(bw)
        for (i in 0 until bw) {
            val x = x0 + i
            var c1 = 0
            var c2 = 0
            for (y in y0 until midY) {
                if (isGutterPixel(luminance[y * width + x], gutterMed, tolerance)) c1++
            }
            for (y in midY until y1) {
                if (isGutterPixel(luminance[y * width + x], gutterMed, tolerance)) c2++
            }
            h1Frac[i] = c1 / h1
            h2Frac[i] = c2 / h2
            gFrac[i] = (c1 + c2).toFloat() / bh.toFloat()
        }
        val cut = findBestGutterCut(bw, minMargin, gFrac, h1Frac, h2Frac) ?: return null
        val cutsBothEndBorders = (0 until cut.len).any { k ->
            val cx = x0 + cut.start + k
            val topDark = !isGutterPixel(luminance[y0 * width + cx], gutterMed, tolerance) ||
                (y0 + 1 < y1 && !isGutterPixel(luminance[(y0 + 1) * width + cx], gutterMed, tolerance))
            val botDark = !isGutterPixel(luminance[(y1 - 1) * width + cx], gutterMed, tolerance) ||
                (y1 - 2 >= y0 && !isGutterPixel(luminance[(y1 - 2) * width + cx], gutterMed, tolerance))
            topDark && botDark
        }
        if (cutsBothEndBorders) return null
        return GutterCut(x0 + cut.start, cut.len, cut.score)
    }

    private fun findBestGutterCut(
        profileLen: Int,
        minMargin: Int,
        gFrac: FloatArray,
        h1Frac: FloatArray,
        h2Frac: FloatArray
    ): GutterCut? {
        if (profileLen < minMargin * 2 + 2) return null
        var best: GutterCut? = null
        var i = minMargin
        val end = profileLen - minMargin
        while (i < end) {
            if (!(gFrac[i] >= 0.90f && h1Frac[i] >= 0.85f && h2Frac[i] >= 0.85f)) {
                i++
                continue
            }
            val rStart = i
            while (i < end && gFrac[i] >= 0.90f && h1Frac[i] >= 0.85f && h2Frac[i] >= 0.85f) {
                i++
            }
            val rEnd = i - 1
            val rLen = rEnd - rStart + 1
            var peakG = 0f
            var sumG = 0f
            var minHalfPeak = 0f
            for (k in rStart..rEnd) {
                if (gFrac[k] > peakG) peakG = gFrac[k]
                sumG += gFrac[k]
                val mh = min(h1Frac[k], h2Frac[k])
                if (mh > minHalfPeak) minHalfPeak = mh
            }
            val avgG = sumG / rLen.toFloat()
            var leftMinG = 1f
            for (k in max(0, rStart - 3) until rStart) {
                if (gFrac[k] < leftMinG) leftMinG = gFrac[k]
            }
            var rightMinG = 1f
            for (k in (rEnd + 1) until min(profileLen, rEnd + 4)) {
                if (gFrac[k] < rightMinG) rightMinG = gFrac[k]
            }
            val hasLeftBorder = leftMinG <= 0.55f
            val hasRightBorder = rightMinG <= 0.55f
            val isWidePure = rLen >= 3 && avgG >= 0.985f && (hasLeftBorder || hasRightBorder || avgG >= 0.995f)
            val isValid = (hasLeftBorder && hasRightBorder) ||
                (peakG >= 0.96f && minHalfPeak >= 0.92f && (hasLeftBorder || hasRightBorder)) ||
                isWidePure
            if (isValid) {
                val borderBonus = (if (hasLeftBorder) 1.5f else 0f) + (if (hasRightBorder) 1.5f else 0f)
                val score = (peakG + minHalfPeak) * 2.0f + borderBonus + min(rLen, 6) * 0.25f
                if (best == null || score > best.score) {
                    best = GutterCut(rStart, rLen, score)
                }
            }
        }
        return best
    }

    private fun capToMaxPanels(
        panels: List<MangaNormalizedRect>,
        maxPanels: Int
    ): List<MangaNormalizedRect> {
        if (panels.size <= maxPanels) return panels
        val list = panels.toMutableList()
        while (list.size > maxPanels && list.size >= 2) {
            var bestIdx = 0
            var bestArea = Float.MAX_VALUE
            for (idx in 0 until list.size - 1) {
                val a = list[idx]
                val b = list[idx + 1]
                val uW = max(a.right, b.right) - min(a.left, b.left)
                val uH = max(a.bottom, b.bottom) - min(a.top, b.top)
                val unionArea = uW * uH
                if (unionArea < bestArea) {
                    bestArea = unionArea
                    bestIdx = idx
                }
            }
            val a = list[bestIdx]
            val b = list.removeAt(bestIdx + 1)
            list[bestIdx] = MangaNormalizedRect(
                left = min(a.left, b.left),
                top = min(a.top, b.top),
                right = max(a.right, b.right),
                bottom = max(a.bottom, b.bottom)
            ).clamped()
        }
        return list
    }

    fun orderPanelsForReadingDirection(
        boxes: List<MangaNormalizedRect>,
        readingOrder: MangaReadingOrder
    ): List<MangaNormalizedRect> {
        if (boxes.size <= 1) return boxes
        if (readingOrder == MangaReadingOrder.WEBTOON_VERTICAL) {
            return boxes.sortedWith(compareBy<MangaNormalizedRect>({ it.top }, { it.centerX }))
        }
        return partitionByGuillotine(boxes, rtl = readingOrder == MangaReadingOrder.RTL_MANGA)
    }

    private fun partitionByGuillotine(
        pool: List<MangaNormalizedRect>,
        rtl: Boolean
    ): List<MangaNormalizedRect> {
        if (pool.size <= 1) return pool

        // 1. Clean horizontal row guillotine cut (guarantees upper row always precedes lower row)
        val yCandidates = pool.flatMap { listOf(it.bottom, it.top) }.distinct().sorted()
        for (y in yCandidates) {
            val upper = pool.filter { it.bottom <= y + 0.025f }
            val lower = pool.filter { it.top >= y - 0.025f && it !in upper }
            if (upper.isNotEmpty() && lower.isNotEmpty() && upper.size + lower.size == pool.size) {
                return partitionByGuillotine(upper, rtl) + partitionByGuillotine(lower, rtl)
            }
        }

        // 2. Clean vertical column guillotine cut (used for true tall-sidebar columns like a tall right panel
        // beside stacked left panels, NEVER for staggered 2x2 grids where row-first Z-order applies)
        val xCandidates = pool.flatMap { listOf(it.right, it.left) }.distinct().sorted()
        for (x in xCandidates) {
            val left = pool.filter { it.right <= x + 0.025f }
            val right = pool.filter { it.left >= x - 0.025f && it !in left }
            if (left.isNotEmpty() && right.isNotEmpty() && left.size + right.size == pool.size) {
                val bothMultiRow = left.distinctRowCount() >= 2 && right.distinctRowCount() >= 2
                val hasTrueTallColumnBridge = left.hasBridgingColumnPanelAgainst(right) || right.hasBridgingColumnPanelAgainst(left)
                if (bothMultiRow && !hasTrueTallColumnBridge) {
                    continue
                }
                return if (rtl) {
                    partitionByGuillotine(right, rtl) + partitionByGuillotine(left, rtl)
                } else {
                    partitionByGuillotine(left, rtl) + partitionByGuillotine(right, rtl)
                }
            }
        }

        // 3. Fallback topological row sort for interlocking non-guillotine panels
        val orderedByTop = pool.sortedBy { it.top }
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
        val result = ArrayList<MangaNormalizedRect>(pool.size)
        for (row in rows) {
            if (row.size <= 1) {
                result += row
                continue
            }
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
                val poolReady = ready.ifEmpty { (0 until n).filter { !done[it] } }
                val pick = poolReady.minWith(
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

    private fun List<MangaNormalizedRect>.distinctRowCount(): Int {
        if (size <= 1) return size
        val sorted = sortedBy { it.top }
        var rows = 1
        var rowBot = sorted.first().bottom
        for (i in 1 until sorted.size) {
            val box = sorted[i]
            if (box.top >= rowBot - 0.04f) {
                rows++
                rowBot = box.bottom
            } else {
                rowBot = max(rowBot, box.bottom)
            }
        }
        return rows
    }

    private fun List<MangaNormalizedRect>.hasBridgingColumnPanelAgainst(other: List<MangaNormalizedRect>): Boolean {
        if (other.size < 2) return false
        return any { panel ->
            val overlappedRows = other.count { o ->
                val overlapH = (min(panel.bottom, o.bottom) - max(panel.top, o.top)).coerceAtLeast(0f)
                overlapH >= o.height.coerceAtLeast(0.01f) * 0.30f
            }
            overlappedRows >= 2
        }
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
        val decisionReason: String,
        val xPos: Float? = null,
        val yPos: Float? = null
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
        includeNonBubbleText: Boolean = false,
        readingOrder: MangaReadingOrder = MangaReadingOrder.RTL_MANGA
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
                    val parsedLines = parseLineSpecArray(linesArr, pIdx, knownNames, includeNonBubbleText, readingOrder)
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
        includeNonBubbleText: Boolean = false,
        readingOrder: MangaReadingOrder = MangaReadingOrder.RTL_MANGA
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
            // ✨ v1.9.7 TRUNCATION: an opener with NO closer anywhere means the model ran out of
            // tokens mid-object — hand the partial body to repairTruncatedJson instead of
            // discarding it outright (previously jsonCandidate was null → zero lines).
            objStart >= 0 || arrStart >= 0 -> {
                val start = when {
                    objStart < 0 -> arrStart
                    arrStart in 0 until objStart -> arrStart
                    else -> objStart
                }
                cleaned.substring(start)
            }
            else -> null
        }

        var parsedMood = ""
        var parsedThought = ""
        var parsedLines: List<DiscoveredLineSpec> = emptyList()

        if (jsonCandidate != null) {
            val sanitized = cleanupTrailingCommas(jsonCandidate)
            parsePanelJsonBody(sanitized, panelIndex, knownNames, includeNonBubbleText, readingOrder)
                ?.let { parsed ->
                    parsedMood = parsed.mood
                    parsedThought = parsed.thought
                    parsedLines = parsed.lines
                }
            // ✨ TRUNCATED-JSON RESCUE (v1.9.7): KV-cache panel turns emit under a tight
            // maxOutputToken budget, so busy multi-bubble panels can cut off mid-object and
            // previously parsed to ZERO lines. Balance the brackets deterministically and retry
            // before the text fallback — a shortened tail line beats a silently dropped panel.
            if (parsedLines.isEmpty()) {
                val repairedCandidate = repairTruncatedJson(jsonCandidate)
                if (repairedCandidate != null) {
                    val repaired = parsePanelJsonBody(
                        cleanupTrailingCommas(repairedCandidate),
                        panelIndex,
                        knownNames,
                        includeNonBubbleText,
                        readingOrder
                    )
                    if (repaired != null && repaired.lines.isNotEmpty()) {
                        parsedMood = repaired.mood
                        parsedThought = repaired.thought
                        parsedLines = repaired.lines
                    }
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
        includeNonBubbleText: Boolean = false,
        readingOrder: MangaReadingOrder = MangaReadingOrder.RTL_MANGA
    ): List<DiscoveredLineSpec> {
        val rawList = linesArr.mapIndexedNotNull { lIdx, lElem ->
            val lObj = lElem as? JsonObject ?: return@mapIndexedNotNull null
            val rawText = ((lObj["text"] ?: lObj["dialogue"] ?: lObj["line"] ?: lObj["speech"]) as? JsonPrimitive)
                ?.contentOrNull.orEmpty().trim()
            if (rawText.isEmpty()) return@mapIndexedNotNull null
            // ✨ PLACEHOLDER-PARROT GUARD (v1.9.8): small VLMs (Gemma E2B) sometimes answer the
            // schema EXAMPLE verbatim ("Dialogue text" + gender:male from the repair prompt)
            // instead of transcribing the panel — parroted placeholder lines must NEVER reach
            // the voice forge; the panel falls through to review instead of dubbing garbage.
            val normalizedText = rawText.lowercase(Locale.ROOT).trim().trimEnd('.', '!', '?', '…', ' ')
            if (normalizedText.isEmpty() || normalizedText in MangaTextBender.PLACEHOLDER_DIALOGUE_TEXTS) {
                return@mapIndexedNotNull null
            }
            val speakerRaw = ((lObj["speaker"] ?: lObj["character"] ?: lObj["name"]) as? JsonPrimitive)
                ?.contentOrNull.orEmpty().trim()
                .ifEmpty { "UNKNOWN" }
            val containerType = ((lObj["container_type"] ?: lObj["bubble_type"] ?: lObj["container"] ?: lObj["type"]) as? JsonPrimitive)
                ?.contentOrNull.orEmpty().trim().lowercase(Locale.ROOT)
            val isExplicitNonBubble = containerType in setOf(
                "sfx", "floating_sfx", "floating", "background", "bg_text",
                "credit", "credits", "credit_or_signature", "signature",
                "watermark", "margin", "margin_note", "sign", "onomatopoeia", "page_number"
            )
            // ✨ TRUSTED-SPEAKER RESCUE (v1.9.7): a speaker that already exists in the cast roster
            // (or NARRATOR) is far likelier to be real dialogue than floating lettering.
            val trustedSpeaker = knownNames.any { it.equals(speakerRaw, ignoreCase = true) } ||
                speakerRaw.equals("NARRATOR", ignoreCase = true)
            // ✨ MISLABEL RESCUE (v1.9.7): a KNOWN speaker attributed to an SFX-labelled container
            // still speaks real sentences ("Get back here!" mislabeled as floating_sfx by a small
            // VLM) — rescue it only when the text passes the UNTRUSTED noise check, so pure
            // onomatopoeia ("GULP") and elongated SFX ("Shaaa") stay excluded.
            val isSpeechBubble = ((lObj["is_speech_bubble"] as? JsonPrimitive)?.booleanOrNull ?: true) &&
                (!isExplicitNonBubble ||
                    (trustedSpeaker && !MangaTextBender.isNonBubbleOrCreditNoise(speakerRaw, rawText)))
            if (!includeNonBubbleText && (!isSpeechBubble ||
                    MangaTextBender.isNonBubbleOrCreditNoise(speakerRaw, rawText, trustedSpeaker))
            ) {
                return@mapIndexedNotNull null
            }
            val xPos = ((lObj["x_pos"] ?: lObj["x"] ?: lObj["cx"]) as? JsonPrimitive)?.floatOrNull?.let { v ->
                if (v > 1.5f) (v / 1000f).coerceIn(0f, 1f) else v.coerceIn(0f, 1f)
            }
            val yPos = ((lObj["y_pos"] ?: lObj["y"] ?: lObj["cy"]) as? JsonPrimitive)?.floatOrNull?.let { v ->
                if (v > 1.5f) (v / 1000f).coerceIn(0f, 1f) else v.coerceIn(0f, 1f)
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
            val tailTarget = ((lObj["tail_target"] ?: lObj["tail"]) as? JsonPrimitive)
                ?.contentOrNull.orEmpty().trim()
            val isNarrationBox = containerType.contains("narration") || speakerRaw.equals("NARRATOR", ignoreCase = true)
            val tailAmbiguous = tailTarget.equals("unclear", ignoreCase = true) ||
                tailTarget.equals("ambiguous", ignoreCase = true) ||
                tailTarget.equals("unknown", ignoreCase = true) ||
                tailTarget.equals("none", ignoreCase = true) ||
                tailTarget == "?"
            val ambiguousSpeaker = speakerRaw.equals("UNKNOWN", ignoreCase = true) ||
                speakerRaw.equals("AMBIGUOUS", ignoreCase = true) ||
                speakerRaw == "?"
            val needsDecision = ambiguousSpeaker || (!isNarrationBox && tailAmbiguous && conf < 0.85f) || conf < 0.58f
            val reason = when {
                ambiguousSpeaker -> "VLM flagged ambiguous speaker in Panel ${pIdx + 1} — tap a character below"
                !isNarrationBox && tailAmbiguous && conf < 0.85f -> "Bubble tail target unclear in Panel ${pIdx + 1} — verify speaker attribution"
                conf < 0.58f -> "Low VLM confidence (${(conf * 100).toInt()}%) — verify speaker attribution"
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
                decisionReason = reason,
                xPos = xPos,
                yPos = yPos
            )
        }
        return sortLinesByReadingOrder(rawList, readingOrder)
    }

    private fun sortLinesByReadingOrder(
        lines: List<DiscoveredLineSpec>,
        readingOrder: MangaReadingOrder
    ): List<DiscoveredLineSpec> {
        if (lines.size <= 1) return lines
        val coordCount = lines.count { it.xPos != null }
        if (coordCount < 2) return lines

        if (readingOrder == MangaReadingOrder.WEBTOON_VERTICAL) {
            return lines.sortedWith(
                compareBy<DiscoveredLineSpec>(
                    { it.yPos ?: 0.5f },
                    { it.xPos ?: 0.5f },
                    { it.bubbleIndex }
                )
            ).mapIndexed { idx, item -> item.copy(bubbleIndex = idx + 1) }
        }

        val byY = lines.sortedBy { it.yPos ?: 0.5f }
        val bands = ArrayList<MutableList<DiscoveredLineSpec>>()
        var bandAnchorY = -1f
        for (item in byY) {
            val y = item.yPos ?: 0.5f
            if (bands.isEmpty() || (y - bandAnchorY) > 0.20f) {
                bands += mutableListOf(item)
                bandAnchorY = y
            } else {
                bands.last() += item
                bandAnchorY = bands.last().minOf { it.yPos ?: 0.5f }
            }
        }
        val rtl = readingOrder == MangaReadingOrder.RTL_MANGA
        val sorted = bands.flatMap { band ->
            band.sortedWith(
                compareBy<DiscoveredLineSpec>(
                    { if (rtl) -(it.xPos ?: 0.5f) else (it.xPos ?: 0.5f) },
                    { it.yPos ?: 0.5f },
                    { it.bubbleIndex }
                )
            )
        }
        return sorted.mapIndexed { idx, item -> item.copy(bubbleIndex = idx + 1) }
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
            val rawVoice = (update["voice_design_prompt"] as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
                .ifBlank { ch.voiceDesignPrompt }
            val hasStructuredAttrs = listOf("age", "pitch", "timbre", "timbres", "pace", "delivery", "accent", "acoustics")
                .any { update.containsKey(it) }
            val nextVoice = compileRosterVoiceDesignPrompt(
                characterName = ch.name,
                gender = nextGender,
                visualDesc = nextVis,
                rawPrompt = rawVoice,
                update = update,
                hasStructuredAttrs = hasStructuredAttrs
            )
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

    private fun compileRosterVoiceDesignPrompt(
        characterName: String,
        gender: VoiceGender,
        visualDesc: String,
        rawPrompt: String,
        update: JsonObject,
        hasStructuredAttrs: Boolean
    ): String {
        val wordCount = rawPrompt.trim().split(Regex("""\s+""")).count { it.isNotBlank() }
        if (!hasStructuredAttrs && wordCount >= 28) return rawPrompt

        val vdGender = when (gender) {
            VoiceGender.FEMALE -> VoiceDesignGender.FEMALE
            VoiceGender.MALE -> VoiceDesignGender.MALE
            VoiceGender.ANDROGYNOUS -> VoiceDesignGender.ANDROGYNOUS
        }
        val corpus = "$rawPrompt $visualDesc $characterName".lowercase(Locale.ROOT)
        fun strField(key: String) = (update[key] as? JsonPrimitive)?.contentOrNull.orEmpty().trim().uppercase(Locale.ROOT)

        val age = when (strField("age")) {
            "TEEN" -> VoiceDesignAge.TEEN
            "YOUNG_ADULT" -> VoiceDesignAge.YOUNG_ADULT
            "ADULT" -> VoiceDesignAge.ADULT
            "MIDDLE_AGED" -> VoiceDesignAge.MIDDLE_AGED
            "ELDERLY" -> VoiceDesignAge.ELDERLY
            else -> when {
                listOf("elder", "old", "grand", "ancient").any { it in corpus } -> VoiceDesignAge.ELDERLY
                listOf("teen", "school", "kid", "child").any { it in corpus } -> VoiceDesignAge.TEEN
                else -> VoiceDesignAge.YOUNG_ADULT
            }
        }
        val pitch = when (strField("pitch")) {
            "DEEP_BASS" -> VoiceDesignPitch.DEEP_BASS
            "LOW_BARITONE" -> VoiceDesignPitch.LOW_BARITONE
            "MID_NATURAL" -> VoiceDesignPitch.MID_NATURAL
            "BRIGHT_UPPER_MID" -> VoiceDesignPitch.BRIGHT_UPPER_MID
            "HIGH_SOPRANO" -> VoiceDesignPitch.HIGH_SOPRANO
            else -> when (vdGender) {
                VoiceDesignGender.FEMALE -> if ("mature" in corpus || "deep" in corpus) VoiceDesignPitch.MID_NATURAL else VoiceDesignPitch.BRIGHT_UPPER_MID
                VoiceDesignGender.MALE -> if ("boy" in corpus || "teen" in corpus) VoiceDesignPitch.MID_NATURAL else VoiceDesignPitch.LOW_BARITONE
                VoiceDesignGender.ANDROGYNOUS -> VoiceDesignPitch.MID_NATURAL
            }
        }
        val timbreRaw = (update["timbres"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.uppercase(Locale.ROOT) }
            ?: strField("timbre").takeIf { it.isNotEmpty() }?.split(',', '|')?.map { it.trim() }.orEmpty()
        val timbres = timbreRaw.mapNotNull { runCatching { VoiceDesignTimbre.valueOf(it) }.getOrNull() }
            .ifEmpty {
                listOf(
                    if ("raspy" in corpus || "gruff" in corpus) VoiceDesignTimbre.RASPY_GRAVELLY else VoiceDesignTimbre.WARM_VELVETY,
                    VoiceDesignTimbre.CRISP_CLEAR
                )
            }
        val pace = runCatching { VoiceDesignPace.valueOf(strField("pace")) }.getOrElse {
            if ("fast" in corpus || "brisk" in corpus || "energetic" in corpus) VoiceDesignPace.ENERGETIC_BRISK else VoiceDesignPace.NATURAL_FLOWING
        }
        val delivery = runCatching { VoiceDesignDelivery.valueOf(strField("delivery")) }.getOrElse {
            when {
                listOf("cheerful", "upbeat", "genki", "playful").any { it in corpus } -> VoiceDesignDelivery.CHEERFUL_UPBEAT
                listOf("serious", "butler", "calm", "stoic", "command").any { it in corpus } -> VoiceDesignDelivery.SERIOUS_AUTHORITATIVE
                listOf("shy", "hesitant", "nervous").any { it in corpus } -> VoiceDesignDelivery.SHY_HESITANT
                listOf("teas", "smug", "ojou").any { it in corpus } -> VoiceDesignDelivery.PLAYFUL_TEASING
                else -> VoiceDesignDelivery.WARM_CONFIDENT
            }
        }
        val accent = runCatching { VoiceDesignAccent.valueOf(strField("accent")) }.getOrElse {
            VoiceDesignAccent.ANIME_EXPRESSIVE
        }
        val acoustics = runCatching { VoiceDesignAcoustics.valueOf(strField("acoustics")) }.getOrElse {
            VoiceDesignAcoustics.CLOSE_MIC_STUDIO
        }
        val personaRole = (update["persona_role"] as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
            .ifBlank { visualDesc.takeIf { it.isNotBlank() } ?: "Manga character ($characterName)" }
        val customNuance = ((update["nuance"] as? JsonPrimitive)?.contentOrNull.orEmpty().trim() +
            (if (rawPrompt.isNotBlank()) " $rawPrompt" else "")).trim()
            .ifBlank { "Distinct, emotionally expressive manga voice acting with steady pitch identity across lines." }

        return QwenVoiceDesignBuilder.composePrompt(
            VoiceDesignSpec(
                gender = vdGender,
                age = age,
                pitch = pitch,
                timbres = timbres,
                pace = pace,
                delivery = delivery,
                accent = accent,
                acoustics = acoustics,
                personaRole = personaRole,
                customNuance = customNuance
            )
        )
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
            val trustedSpeaker = knownNames.any { it.equals(speaker, ignoreCase = true) } ||
                speaker.equals("NARRATOR", ignoreCase = true)
            if (!includeNonBubbleText && MangaTextBender.isNonBubbleOrCreditNoise(speaker, text, trustedSpeaker)) return@forEach
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

    private class PanelJsonBodyParse(
        val mood: String,
        val thought: String,
        val lines: List<DiscoveredLineSpec>
    )

    private fun parsePanelJsonBody(
        sanitized: String,
        panelIndex: Int,
        knownNames: List<String>,
        includeNonBubbleText: Boolean,
        readingOrder: MangaReadingOrder
    ): PanelJsonBodyParse? {
        return runCatching {
            var mood = ""
            var thought = ""
            var lines: List<DiscoveredLineSpec> = emptyList()
            when (val elem = lenientJson.parseToJsonElement(sanitized)) {
                is JsonObject -> {
                    mood = ((elem["scene_mood"] ?: elem["mood"]) as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
                    thought = (elem["thought"] as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
                    val directLines = (elem["lines"] ?: elem["dialogue"]) as? JsonArray
                    if (directLines != null) {
                        lines = parseLineSpecArray(directLines, panelIndex, knownNames, includeNonBubbleText, readingOrder)
                    } else {
                        val panelsArr = elem["panels"] as? JsonArray
                        if (panelsArr != null) {
                            val combined = ArrayList<DiscoveredLineSpec>()
                            for (pElem in panelsArr) {
                                val pObj = pElem as? JsonObject ?: continue
                                if (mood.isEmpty()) {
                                    mood = ((pObj["scene_mood"] ?: pObj["mood"]) as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
                                }
                                if (thought.isEmpty()) {
                                    thought = (pObj["thought"] as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
                                }
                                val arr = (pObj["lines"] ?: pObj["dialogue"]) as? JsonArray ?: continue
                                combined += parseLineSpecArray(arr, panelIndex, knownNames, includeNonBubbleText, readingOrder)
                            }
                            lines = combined
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
                            if (mood.isEmpty()) {
                                mood = ((pObj["scene_mood"] ?: pObj["mood"]) as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
                            }
                            val arr = (pObj["lines"] ?: pObj["dialogue"]) as? JsonArray ?: continue
                            combined += parseLineSpecArray(arr, panelIndex, knownNames, includeNonBubbleText, readingOrder)
                        }
                        lines = combined
                    } else {
                        lines = parseLineSpecArray(elem, panelIndex, knownNames, includeNonBubbleText, readingOrder)
                    }
                }
                else -> Unit
            }
            PanelJsonBodyParse(mood, thought, lines)
        }.getOrNull()
    }

    /**
     * ✨ TRUNCATED-JSON REPAIR (v1.9.7) ✨
     * Local VLM panel turns emit under a tight maxOutputToken budget; a busy multi-bubble panel
     * can cut off mid-object. Deterministically closes unbalanced strings/brackets so a shortened
     * final line survives instead of the whole panel silently dropping to zero bubbles.
     * Returns null when the payload is NOT a simple truncation (mismatched brackets, or complete
     * JSON that failed to parse for another reason) — those fall through to the text fallback.
     */
    private fun repairTruncatedJson(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        var inString = false
        var escaped = false
        val stack = ArrayDeque<Char>()
        for (ch in trimmed) {
            when {
                escaped -> escaped = false
                ch == '\\' && inString -> escaped = true
                ch == '"' -> inString = !inString
                !inString && (ch == '{' || ch == '[') -> stack.addLast(if (ch == '{') '}' else ']')
                !inString && (ch == '}' || ch == ']') -> {
                    if (stack.isEmpty() || stack.removeLastOrNull() != ch) return null
                }
            }
        }
        if (stack.isEmpty() && !inString && !escaped) return null // not a truncation

        var repaired = trimmed
        if (escaped) repaired = repaired.dropLast(1) // dangling escape backslash before the close
        if (inString) {
            repaired += '"'
            // A final string that is NOT preceded by ':' is a partial KEY — drop the dangling
            // member; when it IS a value (…"text":"cut off sente), keep the shortened value.
            val closeIdx = repaired.length - 1
            val openIdx = repaired.lastIndexOf('"', closeIdx - 1)
            if (openIdx > 0) {
                val before = repaired.substring(0, openIdx).trimEnd()
                if (!before.endsWith(':')) repaired = before
            }
        }
        // Strip dangling member fragments left by the truncation, then close the bracket stack.
        repaired = repaired
            .replace(Regex(",\\s*[^,:\\[\\]{}\"\\s][^,:\\[\\]{}]*$"), "")
            .replace(Regex(",\\s*\"[^\"]*\"\\s*:\\s*$"), "")
            .replace(Regex(":\\s*$"), "")
            .replace(Regex(",\\s*$"), "")
        for (closer in stack.reversed()) repaired += closer
        return repaired
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
