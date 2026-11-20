package com.projectmika.purrtts.core.purr

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ✨ PURE-JVM TESTS FOR QWEN3 1.7B VOICEDESIGN BUILDER (TOOL-06) ✨
 */
class QwenVoiceDesignBuilderTest {

    @Test
    fun `composePrompt weaves demographics timbre delivery accent acoustics and optional nuance`() {
        val spec = VoiceDesignSpec(
            gender = VoiceDesignGender.MALE,
            age = VoiceDesignAge.MIDDLE_AGED,
            pitch = VoiceDesignPitch.DEEP_BASS,
            timbres = listOf(VoiceDesignTimbre.RASPY_GRAVELLY, VoiceDesignTimbre.WARM_VELVETY),
            pace = VoiceDesignPace.SLOW_DELIBERATE,
            delivery = VoiceDesignDelivery.SERIOUS_AUTHORITATIVE,
            accent = VoiceDesignAccent.BRITISH_RP,
            acoustics = VoiceDesignAcoustics.AUDIOBOOK_BOOTH,
            personaRole = "an investigative archivist",
            customNuance = "Subtle gravitas on chapter transitions"
        )

        val prompt = QwenVoiceDesignBuilder.composePrompt(spec)
        assertTrue(prompt.contains("male", ignoreCase = true))
        assertTrue(prompt.contains("middle-aged", ignoreCase = true))
        assertTrue(prompt.contains("deep, resonant bass", ignoreCase = true))
        assertTrue(prompt.contains("gravelly", ignoreCase = true))
        assertTrue(prompt.contains("velvety", ignoreCase = true))
        assertTrue(prompt.contains("British RP", ignoreCase = true))
        assertTrue(prompt.contains("investigative archivist", ignoreCase = true))
        assertTrue(prompt.contains("Subtle gravitas on chapter transitions.", ignoreCase = true))
    }

    @Test
    fun `curated presets build valid distinct prompts and SavedVoiceDesign round-trips cleanly`() {
        val prompts = QwenVoiceDesignBuilder.CURATED_PRESETS.map { (label, spec) ->
            val composed = QwenVoiceDesignBuilder.composePrompt(spec)
            assertTrue("Short prompt for $label", composed.length >= 60)
            composed
        }
        assertEquals(QwenVoiceDesignBuilder.CURATED_PRESETS.size, prompts.toSet().size)

        val saved = SavedVoiceDesign(
            id = "vd_test_1",
            name = "Test MIKA",
            prompt = prompts.first(),
            spec = QwenVoiceDesignBuilder.CURATED_PRESETS.first().second,
            createdAtEpochMs = 123456789L
        )
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        val decoded = json.decodeFromString<SavedVoiceDesign>(json.encodeToString(saved))
        assertEquals(saved, decoded)
    }
}
