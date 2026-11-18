package com.projectmika.purrtts.core.purr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ✨ PURE-JVM TESTS FOR MIKA'S SENTIMENT & PROSODY SCORER ✨
 * Guards the five emotion axes, emoji boosts, forced archetype tones, the prosody
 * coercion bounds, and the v1.3.2 pitch-lock law (default basePitch = 1.0 must never
 * incur per-sentence pitch resampling — the intermodulation-buzz crash class).
 */
class PurrSentimentScorerTest {

    private val defaultProfile = PurrDefaultPresets.builtInProfiles().first()
        .copy(
            baseSpeed = 1.0f,
            basePitch = 1.0f,
            sentimentConfig = SentimentCurveConfig(enabled = true, toneOverride = ArchetypeTone.AUTO)
        )

    // ---------- Axis detection ----------

    @Test
    fun `joy lexicon drives joy axis and dominant label`() {
        val s = PurrSentimentScorer.score("Amazing! Haha this is so fun, yay purr~")
        assertTrue(s.joy > 0.3f)
        assertEquals("JOY", s.dominantLabel)
    }

    @Test
    fun `love lexicon drives love axis and dominant label`() {
        val s = PurrSentimentScorer.score("I love you Master, let me cuddle and cherish you forever")
        assertTrue(s.love > 0.3f)
        assertEquals("LOVE", s.dominantLabel)
    }

    @Test
    fun `anger lexicon drives anger axis and dominant label`() {
        val s = PurrSentimentScorer.score("Hmph! You are an idiot, baka! I hate this stupid thing")
        assertTrue(s.anger > 0.3f)
        assertEquals("ANGER", s.dominantLabel)
    }

    @Test
    fun `sadness lexicon drives sadness axis and dominant label`() {
        val s = PurrSentimentScorer.score("I am so lonely and crying tears of grief, it hurts...")
        assertTrue(s.sadness > 0.3f)
        assertEquals("SADNESS", s.dominantLabel)
    }

    @Test
    fun `flustered lexicon and stutter regex drive flustered axis`() {
        val s = PurrSentimentScorer.score("E-eep!! W-what are you saying, uhm, that makes me blush?!")
        assertTrue(s.flustered > 0.3f)
        assertEquals("FLUSTERED", s.dominantLabel)
    }

    @Test
    fun `heart emojis boost love and joy axes`() {
        val s = PurrSentimentScorer.score("❤️💖")
        assertEquals(1.0f, s.love, 0.0001f)
        assertEquals(1.0f, s.joy, 0.0001f)
    }

    @Test
    fun `sad and flustered emojis boost their axes`() {
        val sad = PurrSentimentScorer.score("😭💔")
        assertEquals(1.0f, sad.sadness, 0.0001f)
        assertEquals("SADNESS", sad.dominantLabel)

        val flustered = PurrSentimentScorer.score("?!")
        assertEquals(1.0f, flustered.flustered, 0.0001f)
        assertEquals("FLUSTERED", flustered.dominantLabel)
    }

    @Test
    fun `single weak hit below dominant threshold stays neutral`() {
        // 30 neutral words + 1 joy word => joy = 1/6 < 0.25 dominant threshold
        val filler = (1..29).joinToString(" ") { "word" }
        val s = PurrSentimentScorer.score("$filler amazing")
        assertTrue(s.joy in 0.01f..0.24f)
        assertEquals("NEUTRAL", s.dominantLabel)
    }

    // ---------- Forced archetype tones ----------

    @Test
    fun `forced love tones pin love axis with shy and tsundere fluster`() {
        val yandere = PurrSentimentScorer.score("whatever", ArchetypeTone.YANDERE_LOVE)
        assertEquals(1.0f, yandere.love, 0.0001f)
        assertEquals(0.0f, yandere.flustered, 0.0001f)

        val shy = PurrSentimentScorer.score("whatever", ArchetypeTone.SHY_LOVE)
        assertEquals(1.0f, shy.love, 0.0001f)
        assertEquals(0.7f, shy.flustered, 0.0001f)

        val tsundere = PurrSentimentScorer.score("whatever", ArchetypeTone.TSUNDERE_LOVE)
        assertEquals(0.7f, tsundere.flustered, 0.0001f)
    }

    @Test
    fun `forced anger tones pin anger axis for all dere variants`() {
        for (tone in listOf(
            ArchetypeTone.ANGER,
            ArchetypeTone.TSUNDERE_ANGER,
            ArchetypeTone.KUUDERE_ANGER,
            ArchetypeTone.YANDERE_ANGER
        )) {
            val s = PurrSentimentScorer.score("i love you", tone)
            assertEquals(1.0f, s.anger, 0.0001f)
            assertEquals("ANGER", s.dominantLabel)
        }
    }

    @Test
    fun `flat and robot tones force full neutral`() {
        for (tone in listOf(ArchetypeTone.KUUDERE, ArchetypeTone.ROBOT, ArchetypeTone.NORMAL)) {
            val s = PurrSentimentScorer.score("i love you so much baka", tone)
            assertEquals(0f, s.joy, 0.0001f)
            assertEquals(0f, s.love, 0.0001f)
            assertEquals(0f, s.anger, 0.0001f)
            assertEquals(0f, s.sadness, 0.0001f)
            assertEquals(0f, s.flustered, 0.0001f)
            assertEquals("NEUTRAL", s.dominantLabel)
        }
    }

    @Test
    fun `blank text with auto tone is fully neutral`() {
        val s = PurrSentimentScorer.score("   ")
        assertEquals("NEUTRAL", s.dominantLabel)
        assertEquals(0f, s.joy, 0.0001f)
        assertEquals(0f, s.sadness, 0.0001f)
    }

    // ---------- Prosody modulation ----------

    @Test
    fun `sadness slows speech while flustered speeds it up`() {
        val sad = PurrSentimentScorer.score("I am so lonely and crying tears of grief...")
        val flustered = PurrSentimentScorer.score("E-eep!! W-what are you saying?!")

        val sadMod = PurrSentimentScorer.computeProsodyModulation(sad, defaultProfile, SegmentRole.DIALOGUE)
        val flusteredMod = PurrSentimentScorer.computeProsodyModulation(flustered, defaultProfile, SegmentRole.DIALOGUE)

        assertTrue(sadMod.speakingRate < 1.0f)
        assertTrue(flusteredMod.speakingRate > 1.0f)
    }

    @Test
    fun `pitch lock law keeps pitch bit exact at default base pitch`() {
        // v1.3.2 scar: automatic sentiment acting must modulate pacing ONLY when
        // basePitch == 1.0 — pitch resampling caused the intermodulation buzz.
        val texts = listOf(
            "I love you so much Master",
            "Hmph baka!!",
            "E-eep w-what?!",
            "I am so lonely..."
        )
        for (tone in ArchetypeTone.entries) {
            for (text in texts) {
                val sentiment = PurrSentimentScorer.score(text, tone)
                val mod = PurrSentimentScorer.computeProsodyModulation(
                    sentiment,
                    defaultProfile,
                    SegmentRole.DIALOGUE
                )
                assertEquals("tone=$tone text=$text", 1.0f, mod.pitch, 0.0001f)
            }
        }
    }

    @Test
    fun `robot override pins rate to 0_98 and kuudere to 0_97`() {
        val robotMod = PurrSentimentScorer.computeProsodyModulation(
            PurrSentimentScorer.score("beep", ArchetypeTone.ROBOT),
            defaultProfile.copy(sentimentConfig = SentimentCurveConfig(enabled = true, toneOverride = ArchetypeTone.ROBOT)),
            SegmentRole.DIALOGUE
        )
        assertEquals(0.98f, robotMod.speakingRate, 0.0001f)
        assertEquals(1.0f, robotMod.pitch, 0.0001f)

        val kuudereMod = PurrSentimentScorer.computeProsodyModulation(
            PurrSentimentScorer.score("hmph", ArchetypeTone.KUUDERE),
            defaultProfile.copy(sentimentConfig = SentimentCurveConfig(enabled = true, toneOverride = ArchetypeTone.KUUDERE)),
            SegmentRole.DIALOGUE
        )
        assertEquals(0.97f, kuudereMod.speakingRate, 0.0001f)
    }

    @Test
    fun `yandere anger clamps pace to at most 0_94`() {
        val mod = PurrSentimentScorer.computeProsodyModulation(
            PurrSentimentScorer.score("die", ArchetypeTone.YANDERE_ANGER),
            defaultProfile.copy(sentimentConfig = SentimentCurveConfig(enabled = true, toneOverride = ArchetypeTone.YANDERE_ANGER)),
            SegmentRole.DIALOGUE
        )
        assertTrue(mod.speakingRate <= 0.94f)
        assertEquals(1.0f, mod.pitch, 0.0001f)
    }

    @Test
    fun `custom pitch deltas shift pitch when explicitly configured`() {
        val customProfile = defaultProfile.copy(
            sentimentConfig = SentimentCurveConfig(
                enabled = true,
                toneOverride = ArchetypeTone.AUTO,
                flusteredPitchDelta = 0.03f,
                sadnessPitchDelta = -0.02f
            )
        )
        val flustered = PurrSentimentScorer.score("E-eep w-what that makes me blush")
        val sad = PurrSentimentScorer.score("lonely crying tears of grief")
        val flusteredMod = PurrSentimentScorer.computeProsodyModulation(flustered, customProfile, SegmentRole.DIALOGUE)
        val sadMod = PurrSentimentScorer.computeProsodyModulation(sad, customProfile, SegmentRole.DIALOGUE)

        assertTrue(flusteredMod.pitch > 1.0f)
        assertTrue(sadMod.pitch < 1.0f)
        assertTrue(flusteredMod.pitch > sadMod.pitch)
    }

    @Test
    fun `action role reads softer and slower via narration multipliers`() {
        val sentiment = PurrSentimentScorer.score("she leans closer")
        val dialogueMod = PurrSentimentScorer.computeProsodyModulation(sentiment, defaultProfile, SegmentRole.DIALOGUE)
        val actionMod = PurrSentimentScorer.computeProsodyModulation(sentiment, defaultProfile, SegmentRole.ACTION)

        assertTrue(actionMod.speakingRate < dialogueMod.speakingRate)
        assertEquals(defaultProfile.narrationVolumeMultiplier, actionMod.volumeGain, 0.0001f)
        assertEquals(1.0f, dialogueMod.volumeGain, 0.0001f)
    }

    @Test
    fun `prosody outputs coerce into safe transport bounds`() {
        val wildProfile = defaultProfile.copy(
            baseSpeed = 1.0f,
            basePitch = 1.0f,
            narrationRateMultiplier = 4.0f,
            narrationVolumeMultiplier = 4.0f,
            sentimentConfig = SentimentCurveConfig(
                enabled = true,
                toneOverride = ArchetypeTone.AUTO,
                angerRateDelta = 2.0f,
                angerPitchDelta = 2.0f
            )
        )
        val angry = PurrSentimentScorer.score("hate baka", ArchetypeTone.AUTO)
        val mod = PurrSentimentScorer.computeProsodyModulation(angry, wildProfile, SegmentRole.ACTION)

        assertTrue(mod.speakingRate in 0.65f..1.50f)
        assertTrue(mod.pitch in 0.88f..1.12f)
        assertTrue(mod.volumeGain in 0.30f..1.25f)
        assertEquals(1.50f, mod.speakingRate, 0.0001f)
        assertEquals(1.12f, mod.pitch, 0.0001f)
        assertEquals(1.25f, mod.volumeGain, 0.0001f)
    }

    @Test
    fun `disabled sentiment curve falls back to flat base pacing`() {
        val flatProfile = defaultProfile.copy(sentimentConfig = defaultProfile.sentimentConfig.copy(enabled = false))
        val sad = PurrSentimentScorer.score("I am so lonely and crying tears of grief...")
        val mod = PurrSentimentScorer.computeProsodyModulation(sad, flatProfile, SegmentRole.DIALOGUE)

        assertEquals(1.0f, mod.speakingRate, 0.0001f)
        assertEquals(1.0f, mod.pitch, 0.0001f)
        assertEquals(1.0f, mod.volumeGain, 0.0001f)
    }
}
