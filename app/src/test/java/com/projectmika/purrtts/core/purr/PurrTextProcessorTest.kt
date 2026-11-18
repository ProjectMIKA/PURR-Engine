package com.projectmika.purrtts.core.purr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PurrTextProcessorTest {

    private val defaultProfile = PurrDefaultPresets.builtInProfiles().first()

    @Test
    fun `slang honorifics and stutters expand cleanly with mika immunity`() {
        val input = "O-oh! Wait... omg fr Mika-senpai, mika-chan nya~"
        val chunks = PurrTextProcessor.process(input, defaultProfile)
        val speechText = chunks.filterIsInstance<PurrChunk.Speech>().joinToString(" ") { it.text }

        assertTrue(speechText.contains("oh-oh", ignoreCase = true))
        assertTrue(speechText.contains("oh my god", ignoreCase = true))
        assertTrue(speechText.contains("for real", ignoreCase = true))
        assertTrue(speechText.contains("sen-pie", ignoreCase = true))
        assertTrue(speechText.contains("chahn", ignoreCase = true))
        assertTrue(speechText.contains("nyaah", ignoreCase = true))
        assertFalse(speechText.contains("mm-mika", ignoreCase = true))
    }

    @Test
    fun `mikastop parses ellipsis emdash and explicit pause tags into exact silence chunks`() {
        val input = "Wait... [pause:500ms] Stop — right there!"
        val chunks = PurrTextProcessor.process(input, defaultProfile)

        val silences = chunks.filterIsInstance<PurrChunk.Silence>()
        assertEquals(2, silences.size)
        // Ellipsis (450ms) + explicit [pause:500ms] coalesce cleanly to 950ms
        assertEquals(defaultProfile.mikaStopConfig.ellipsisPauseMs + 500, silences[0].durationMs)
        assertEquals(defaultProfile.mikaStopConfig.emDashPauseMs, silences[1].durationMs)
    }

    @Test
    fun `exact 24khz pcm16 silence byte length matches durationMs`() {
        val silenceBytes = PurrDspProcessor.createSilencePcm16Le(sampleRate = 24000, durationMs = 500)
        // 24000 samples/sec * 0.5 sec * 2 bytes/sample = 24000 bytes
        assertEquals(24000, silenceBytes.size)
        assertTrue(silenceBytes.all { it == 0.toByte() })
    }

    @Test
    fun `action blocks and dialogue route cleanly across modes`() {
        val input = "*she leans closer* \"Did you miss me?\" she whispered."

        val stripProfile = defaultProfile.copy(actionHandlingMode = ActionHandlingMode.STRIP_ACTIONS)
        val strippedChunks = PurrTextProcessor.process(input, stripProfile)
        val strippedSpeech = strippedChunks.filterIsInstance<PurrChunk.Speech>().joinToString(" ") { it.text }
        assertFalse(strippedSpeech.contains("leans closer", ignoreCase = true))
        assertTrue(strippedSpeech.contains("Did you miss me", ignoreCase = true))

        val dualProfile = defaultProfile.copy(
            actionHandlingMode = ActionHandlingMode.DUAL_VOICE_ROUTING,
            primaryVoiceId = "af_sky",
            dialogueVoiceId = "af_bella"
        )
        val dualSpeech = PurrTextProcessor.process(input, dualProfile).filterIsInstance<PurrChunk.Speech>()
        val actionChunk = dualSpeech.first { it.role == SegmentRole.ACTION }
        val dialogueChunk = dualSpeech.first { it.role == SegmentRole.DIALOGUE }
        assertEquals("af_sky", actionChunk.targetVoiceId)
        assertEquals("af_bella", dialogueChunk.targetVoiceId)
    }

    @Test
    fun `sentiment scorer detects emotion axes and modulates prosody`() {
        val flustered = PurrSentimentScorer.score("E-eep!! *blushes* w-what are you saying?!")
        assertTrue(flustered.flustered > 0.3f)

        val sad = PurrSentimentScorer.score("I am so lonely and crying tears of grief...")
        assertTrue(sad.sadness > 0.3f)

        val sadMod = PurrSentimentScorer.computeProsodyModulation(sad, defaultProfile, SegmentRole.DIALOGUE)
        val flusteredMod = PurrSentimentScorer.computeProsodyModulation(flustered, defaultProfile, SegmentRole.DIALOGUE)
        assertTrue(flusteredMod.speakingRate > sadMod.speakingRate)
        assertTrue(flusteredMod.pitch > sadMod.pitch)
    }

    @Test
    fun `voice matcher respects blacklist and whitelist constraints`() {
        val voices = PurrDefaultPresets.builtInVoices()
        val profile = defaultProfile.copy(
            whitelist = setOf("af_sky", "af_sarah"),
            blacklist = setOf("af_sarah"),
            characterTags = listOf("ara-ara", "calm")
        )
        val picked = PurrVoiceMatcher.assignVoice(voices, profile, queryName = "Tester")
        assertEquals("af_sky", picked?.id)
    }
}
