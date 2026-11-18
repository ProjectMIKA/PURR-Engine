package com.projectmika.purrtts.core.purr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ✨ PURE-JVM TESTS FOR MIKA'S NEURAL VOICE MATCHER ✨
 * Guards the whitelist/blacklist contract, language & gender gates, tag scoring
 * with Forge custom-tag bonus, the primary-voice tiebreak, and the deterministic
 * name-hash fallback (same query + pool => same voice, always).
 */
class PurrVoiceMatcherTest {

    private val voices = listOf(
        VoiceDescriptor(
            id = "af_sarah",
            displayName = "Sarah",
            engine = BackendType.SHERPA_ONNX,
            gender = VoiceGender.FEMALE,
            languageCode = "en-US",
            nativeTags = listOf("female", "american", "calm", "ara-ara")
        ),
        VoiceDescriptor(
            id = "af_bella",
            displayName = "Bella",
            engine = BackendType.SHERPA_ONNX,
            gender = VoiceGender.FEMALE,
            languageCode = "en-US",
            nativeTags = listOf("female", "american", "cute", "energetic")
        ),
        VoiceDescriptor(
            id = "am_adam",
            displayName = "Adam",
            engine = BackendType.SHERPA_ONNX,
            gender = VoiceGender.MALE,
            languageCode = "en-US",
            nativeTags = listOf("male", "american", "deep", "calm", "narrator")
        ),
        VoiceDescriptor(
            id = "bf_emma",
            displayName = "Emma",
            engine = BackendType.SHERPA_ONNX,
            gender = VoiceGender.FEMALE,
            languageCode = "en-GB",
            nativeTags = listOf("female", "british", "maid")
        )
    )

    private fun profile(
        primaryVoiceId: String = "core_os_auto",
        whitelist: Set<String> = emptySet(),
        blacklist: Set<String> = emptySet(),
        characterTags: List<String> = emptyList(),
        customVoiceTags: Map<String, Set<String>> = emptyMap()
    ): PurrProfile = PurrDefaultPresets.builtInProfiles().first().copy(
        primaryVoiceId = primaryVoiceId,
        whitelist = whitelist,
        blacklist = blacklist,
        characterTags = characterTags,
        customVoiceTags = customVoiceTags
    )

    @Test
    fun `empty voice pool returns null instead of crashing`() {
        assertNull(PurrVoiceMatcher.assignVoice(emptyList(), profile()))
    }

    @Test
    fun `blacklisted voices are never picked even with dominant tags`() {
        val picked = PurrVoiceMatcher.assignVoice(
            voices,
            profile(blacklist = setOf("af_sarah"), characterTags = listOf("ara-ara"))
        )
        assertNotEquals("af_sarah", picked?.id)
        assertTrue(picked != null && picked.id in voices.map { it.id })
    }

    @Test
    fun `populated whitelist restricts the pool`() {
        val picked = PurrVoiceMatcher.assignVoice(
            voices,
            profile(whitelist = setOf("bf_emma"), characterTags = listOf("ara-ara"))
        )
        assertEquals("bf_emma", picked?.id)
    }

    @Test
    fun `whitelist with no surviving members collapses back to full pool`() {
        val picked = PurrVoiceMatcher.assignVoice(
            voices,
            profile(whitelist = setOf("ghost_voice"), characterTags = listOf("ara-ara"))
        )
        assertEquals("af_sarah", picked?.id)
    }

    @Test
    fun `character tag scoring picks the matching voice`() {
        val picked = PurrVoiceMatcher.assignVoice(voices, profile(characterTags = listOf("ara-ara")))
        assertEquals("af_sarah", picked?.id)
    }

    @Test
    fun `forge custom voice tags earn scoring bonus`() {
        // bella: custom "ara-ara" match (2) + custom bonus (2) = 4 > sarah native match (2)
        val picked = PurrVoiceMatcher.assignVoice(
            voices,
            profile(
                characterTags = listOf("ara-ara"),
                customVoiceTags = mapOf("af_bella" to setOf("ara-ara"))
            )
        )
        assertEquals("af_bella", picked?.id)
    }

    @Test
    fun `generic tags alone never decide the winner`() {
        // "female"/"american" are GENERIC_TAGS and score nothing => deterministic hash
        // fallback over the full pool. Same query twice MUST resolve identically and
        // always land inside the pool — never null, never a pool-external id.
        val first = PurrVoiceMatcher.assignVoice(
            voices,
            profile(),
            queryName = "MIKA",
            queryTags = listOf("female", "american")
        )
        val second = PurrVoiceMatcher.assignVoice(
            voices,
            profile(),
            queryName = "MIKA",
            queryTags = listOf("female", "american")
        )
        assertEquals(first?.id, second?.id)
        assertTrue(first != null && first.id in voices.map { it.id })
    }

    @Test
    fun `primary voice wins score ties`() {
        // sarah & adam both carry "calm" => tie 2:2, primary +1 decides for adam.
        val picked = PurrVoiceMatcher.assignVoice(
            voices,
            profile(primaryVoiceId = "am_adam", characterTags = listOf("calm"))
        )
        assertEquals("am_adam", picked?.id)
    }

    @Test
    fun `language gate keeps preferred language voices when available`() {
        val picked = PurrVoiceMatcher.assignVoice(
            voices,
            profile(characterTags = listOf("maid")),
            queryName = "Tester",
            preferredLanguage = "en"
        )
        assertTrue(picked?.languageCode?.startsWith("en") == true)
        assertEquals("bf_emma", picked?.id)
    }

    @Test
    fun `gender gate filters to preferred gender when pool allows`() {
        val picked = PurrVoiceMatcher.assignVoice(
            voices,
            profile(),
            queryName = "Tester",
            preferredGender = VoiceGender.MALE
        )
        assertEquals("am_adam", picked?.id)
    }

    @Test
    fun `deterministic name hash fallback is stable across calls`() {
        val a = PurrVoiceMatcher.assignVoice(voices, profile(), queryName = "Ellie")
        val b = PurrVoiceMatcher.assignVoice(voices, profile(), queryName = "Ellie")
        assertEquals(a?.id, b?.id)

        // Hash fallback must return a member of the pool, never null.
        assertTrue(a != null && a.id in voices.map { it.id })
    }

    @Test
    fun `built in catalog integrates with matcher constraints`() {
        val builtIns = PurrDefaultPresets.builtInVoices()
        assertTrue(builtIns.isNotEmpty())
        val picked = PurrVoiceMatcher.assignVoice(
            builtIns,
            profile(whitelist = setOf("af_sky", "af_sarah"), blacklist = setOf("af_sarah"))
        )
        assertEquals("af_sky", picked?.id)
    }
}
