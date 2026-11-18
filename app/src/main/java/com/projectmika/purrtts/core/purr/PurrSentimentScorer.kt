package com.projectmika.purrtts.core.purr

import kotlin.math.max
import kotlin.math.min

/**
 * ✨ MIKA'S NEURAL SENTIMENT & PROSODY SCORER ✨
 * Ported from GachaSwipe's sentimentEngine.js + App.jsx Tone Override Matrix.
 * Scores each text chunk across joy, love, anger, sadness, and flustered axes
 * to drive per-chunk speakingRate, pitch, and archetype drama curves.
 */
object PurrSentimentScorer {

    private val JOY_LEXICON = setOf(
        "amazing", "awesome", "good", "great", "haha", "hehe", "lol", "cool", "fun",
        "smile", "happy", "glad", "proud", "smart", "genius", "sweet", "cute", "pretty",
        "beautiful", "compliment", "thank", "thanks", "yay", "purr", "enjoy", "wonderful",
        "perfect", "brilliant", "praise", "excited", "delight", "blessed", "cutie", "nya"
    )

    private val LOVE_LEXICON = setOf(
        "love", "adore", "cherish", "kiss", "hug", "cuddle", "snuggle", "marry", "date",
        "protect", "care", "precious", "sweetheart", "darling", "honey", "headpat",
        "headpats", "waifu", "mine", "forever", "devoted", "warm", "special", "babe",
        "sweetie", "master", "fufu", "beloved"
    )

    private val ANGER_LEXICON = setOf(
        "hate", "angry", "annoying", "idiot", "stupid", "dumb", "baka", "useless", "bad",
        "worst", "boring", "leave", "trash", "ugly", "fail", "loser", "pathetic", "die",
        "kill", "shutup", "jerk", "annoy", "ugh", "tch", "hmph"
    )

    private val SADNESS_LEXICON = setOf(
        "sad", "unhappy", "cry", "crying", "depressed", "lonely", "miss", "heartbroken",
        "tears", "hurt", "pain", "sorry", "grief", "down", "hopeless", "gloomy",
        "miserable", "sigh", "haaah", "alone"
    )

    private val FLUSTERED_LEXICON = setOf(
        "blush", "blushes", "embarrassed", "shy", "eep", "kyaa", "uhm", "umm", "wait",
        "serious", "flustered", "nervous", "stammer", "stutter", "b-baka", "w-what",
        "i-it", "u-um", "e-eep"
    )

    private val WORD_REGEX = Regex("[a-zA-Z'-]+")
    private val INTENSIFIER_REGEX = Regex("(!{2,}|\\?!|\\bso\\b|\\bvery\\b|\\breally\\b|~)", RegexOption.IGNORE_CASE)

    fun score(text: String, toneOverride: ArchetypeTone = ArchetypeTone.AUTO): SentimentResult {
        if (toneOverride != ArchetypeTone.AUTO) {
            return forcedSentiment(toneOverride)
        }
        if (text.isBlank()) {
            return SentimentResult()
        }

        val lower = text.lowercase()
        val words = WORD_REGEX.findAll(lower).map { it.value }.toList()
        val denom = max(1, min(6, words.size)).toFloat()

        var joyCount = 0f
        var loveCount = 0f
        var angerCount = 0f
        var sadnessCount = 0f
        var flusteredCount = 0f

        for (w in words) {
            val clean = w.trim('\'', '-')
            if (w in JOY_LEXICON || clean in JOY_LEXICON) joyCount += 1f
            if (w in LOVE_LEXICON || clean in LOVE_LEXICON) loveCount += 1.2f
            if (w in ANGER_LEXICON || clean in ANGER_LEXICON) angerCount += 1.3f
            if (w in SADNESS_LEXICON || clean in SADNESS_LEXICON) sadnessCount += 1.2f
            if (w in FLUSTERED_LEXICON || clean in FLUSTERED_LEXICON || Regex("^[a-z]{1,2}-[a-z]+$").matches(w)) {
                flusteredCount += 1.25f
            }
        }

        if (INTENSIFIER_REGEX.containsMatchIn(lower)) {
            if (loveCount > 0f) loveCount *= 1.3f
            if (joyCount > 0f) joyCount *= 1.2f
            if (angerCount > 0f) angerCount *= 1.3f
            if (sadnessCount > 0f) sadnessCount *= 1.2f
            if (flusteredCount > 0f) flusteredCount *= 1.25f
        }

        if (text.contains("❤️") || text.contains("💖") || text.contains("💕") ||
            text.contains("🥰") || text.contains("✨") || text.contains("🐾")
        ) {
            loveCount += 2f
            joyCount += 1f
        }
        if (text.contains("💢") || text.contains("😡") || text.contains("🤬")) {
            angerCount += 2f
        }
        if (text.contains("😢") || text.contains("😭") || text.contains("🥺") || text.contains("💔")) {
            sadnessCount += 2f
        }
        if (text.contains("😳") || text.contains("🙈") || text.contains("💦") || text.contains("?!")) {
            flusteredCount += 1.5f
        }

        val joy = min(1f, joyCount / denom)
        val love = min(1f, loveCount / denom)
        val anger = min(1f, angerCount / denom)
        val sadness = min(1f, sadnessCount / denom)
        val flustered = min(1f, flusteredCount / denom)

        val dominant = listOf(
            "JOY" to joy,
            "LOVE" to love,
            "ANGER" to anger,
            "SADNESS" to sadness,
            "FLUSTERED" to flustered
        ).maxByOrNull { it.second }?.let { if (it.second >= 0.25f) it.first else "NEUTRAL" } ?: "NEUTRAL"

        return SentimentResult(
            joy = joy,
            love = love,
            anger = anger,
            sadness = sadness,
            flustered = flustered,
            dominantLabel = dominant
        )
    }

    private fun forcedSentiment(tone: ArchetypeTone): SentimentResult = when (tone) {
        ArchetypeTone.JOY, ArchetypeTone.GENKI_JOY -> SentimentResult(joy = 1f, dominantLabel = "JOY")
        ArchetypeTone.LOVE, ArchetypeTone.YANDERE_LOVE, ArchetypeTone.SHY_LOVE,
        ArchetypeTone.DOMINANT_LOVE, ArchetypeTone.TSUNDERE_LOVE -> SentimentResult(
            love = 1f,
            flustered = if (tone == ArchetypeTone.SHY_LOVE || tone == ArchetypeTone.TSUNDERE_LOVE) 0.7f else 0f,
            dominantLabel = "LOVE"
        )
        ArchetypeTone.ANGER, ArchetypeTone.TSUNDERE_ANGER,
        ArchetypeTone.KUUDERE_ANGER, ArchetypeTone.YANDERE_ANGER -> SentimentResult(anger = 1f, dominantLabel = "ANGER")
        ArchetypeTone.SADNESS, ArchetypeTone.SHY_SADNESS -> SentimentResult(
            sadness = 1f,
            flustered = if (tone == ArchetypeTone.SHY_SADNESS) 0.5f else 0f,
            dominantLabel = "SADNESS"
        )
        ArchetypeTone.SHY -> SentimentResult(flustered = 1f, dominantLabel = "FLUSTERED")
        ArchetypeTone.KUUDERE, ArchetypeTone.ROBOT, ArchetypeTone.NORMAL -> SentimentResult(dominantLabel = "NEUTRAL")
        ArchetypeTone.AUTO -> SentimentResult()
    }

    data class ProsodyModulation(
        val speakingRate: Float,
        val pitch: Float,
        val volumeGain: Float
    )

    fun computeProsodyModulation(
        sentiment: SentimentResult,
        profile: PurrProfile,
        role: SegmentRole
    ): ProsodyModulation {
        var rate = profile.baseSpeed
        var pitch = profile.basePitch
        var volume = 1.0f

        val curve = profile.sentimentConfig
        if (curve.enabled && curve.toneOverride != ArchetypeTone.NORMAL) {
            rate += (sentiment.joy * curve.joyRateDelta) +
                (sentiment.love * curve.loveRateDelta) +
                (sentiment.anger * curve.angerRateDelta) +
                (sentiment.sadness * curve.sadnessRateDelta) +
                (sentiment.flustered * curve.flusteredRateDelta)

            pitch += (sentiment.joy * curve.joyPitchDelta) +
                (sentiment.love * curve.lovePitchDelta) +
                (sentiment.anger * curve.angerPitchDelta) +
                (sentiment.sadness * curve.sadnessPitchDelta) +
                (sentiment.flustered * curve.flusteredPitchDelta)

            // Subtle GachaSwipe archetype overrides
            when (curve.toneOverride) {
                ArchetypeTone.YANDERE_ANGER, ArchetypeTone.YANDERE_LOVE -> {
                    rate = min(rate, 0.94f)
                    pitch -= 0.02f
                }
                ArchetypeTone.DOMINANT_LOVE -> {
                    rate = min(rate, 0.95f)
                    pitch -= 0.025f
                }
                ArchetypeTone.ROBOT -> {
                    rate = 0.98f
                    pitch = 1.0f
                }
                ArchetypeTone.KUUDERE, ArchetypeTone.KUUDERE_ANGER -> {
                    rate = 0.97f
                    pitch -= 0.01f
                }
                else -> Unit
            }
        }

        if (role == SegmentRole.ACTION || (role == SegmentRole.NARRATION && profile.actionHandlingMode == ActionHandlingMode.NARRATE_SOFT)) {
            if (role == SegmentRole.ACTION) {
                rate *= profile.narrationRateMultiplier
                volume *= profile.narrationVolumeMultiplier
            }
        }

        return ProsodyModulation(
            speakingRate = rate.coerceIn(0.65f, 1.50f),
            pitch = pitch.coerceIn(0.88f, 1.12f),
            volumeGain = volume.coerceIn(0.30f, 1.25f)
        )
    }
}
