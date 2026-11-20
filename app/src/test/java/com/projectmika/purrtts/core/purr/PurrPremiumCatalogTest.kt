package com.projectmika.purrtts.core.purr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ✨ PURE-JVM TESTS FOR THE PREMIUM TTS TIER (v1.6.0) ✨
 * Guards the heavy-weight cartridge catalog (ROM-08/09/10) and their voice rosters:
 * valid digests, unique bay codes, the 54-voice fp32 reuse, the 103-speaker v1.1 roster,
 * and the Matcha voice. Also guards the heavy-model proxy presets.
 */
class PurrPremiumCatalogTest {

    private val catalog = PurrDefaultPresets.modelCatalog()

    @Test
    fun `premium cartridges exist with valid digest format`() {
        val premium = catalog.filter { it.id in setOf("rom_kokoro_fp32", "rom_kokoro_v11", "rom_matcha_zh_en") }
        assertEquals(3, premium.size)
        premium.forEach { spec ->
            assertTrue("${spec.id} sha", spec.expectedSha256.matches(Regex("[0-9a-f]{64}")))
            assertTrue("${spec.id} url", spec.downloadUrl.startsWith("https://"))
            assertTrue("${spec.id} tar", spec.isArchiveTarBz2)
            assertTrue("${spec.id} size", spec.approximateSizeMb >= 75)
        }
    }

    @Test
    fun `premium rom codes follow the rack sequence`() {
        val codes = catalog.filter { !it.isSubCartridge && it.category == CartridgeCategory.TTS }
            .map { it.romCode }
        assertEquals(codes.size, codes.toSet().size)
        assertTrue("ROM-08 present", "ROM-08" in codes)
        assertTrue("ROM-09 present", "ROM-09" in codes)
        assertTrue("ROM-10 present", "ROM-10" in codes)
    }

    @Test
    fun `matcha cartridge carries a verified 16khz vocos companion`() {
        val matcha = catalog.first { it.id == "rom_matcha_zh_en" }
        // matcha-icefall-zh-en is a 16kHz model — the vocoder MUST be vocos-16khz-univ
        // (the 22kHz example vocoder outputs robotic garbage — v1.6.1 scar).
        assertEquals("vocos-16khz-univ.onnx", matcha.companionFileName)
        assertTrue(matcha.companionUrl.endsWith("vocos-16khz-univ.onnx"))
        assertEquals("b599142a1fb8ff03de3e84ac35ff537c619e56f4267a6fe894851a42844acf9e", matcha.companionExpectedSha256)
        assertEquals(51, matcha.companionApproxSizeMb)
    }

    @Test
    fun `fp32 cartridge reuses the 54-voice roster with isolated mount dirs`() {
        val fp32Voices = PurrDefaultPresets.builtInVoices().filter { it.cartridgeId == "rom_kokoro_fp32" }
        assertEquals(54, fp32Voices.size)
        assertTrue(fp32Voices.all { it.requiredSubCartridgeId == "rom_kokoro_fp32" })
        assertTrue(fp32Voices.all { it.sherpaFamily == SherpaModelFamily.KOKORO })
        // Speaker IDs must mirror ROM-02's roster exactly (drop-in premium upgrade).
        val rom02 = PurrDefaultPresets.builtInVoices().filter { it.cartridgeId == "rom_kokoro_sherpa" }
        assertEquals(rom02.map { it.id }, fp32Voices.map { it.id })
        assertEquals(rom02.map { it.speakerIndex }, fp32Voices.map { it.speakerIndex })
    }

    @Test
    fun `kokoro v1_1 roster carries all 103 speakers with correct language split`() {
        val v11 = PurrDefaultPresets.builtInVoices().filter { it.cartridgeId == "rom_kokoro_v11" }
        assertEquals(103, v11.size)
        val ids = v11.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(55, v11.count { it.id.startsWith("zf_") })
        assertEquals(45, v11.count { it.id.startsWith("zm_") })
        // Roster order == speaker IDs in the weights: af_maple = 0, zf_001 = 3, zm_009 = 58, zm_100 = 102.
        assertEquals("af_maple", v11[0].id)
        assertEquals(0, v11[0].speakerIndex)
        assertEquals("zf_001", v11[3].id)
        assertEquals(3, v11[3].speakerIndex)
        assertEquals("zm_009", v11[58].id)
        assertEquals(58, v11[58].speakerIndex)
        assertEquals("zm_100", v11[102].id)
        assertEquals(102, v11[102].speakerIndex)
        assertTrue(v11.all { it.requiredSubCartridgeId == "rom_kokoro_v11" })
        assertEquals(3, v11.count { it.languageCode.startsWith("en") })
        assertEquals(100, v11.count { it.languageCode == "zh-CN" })
    }

    @Test
    fun `matcha narrator voice is registered with the matcha family`() {
        val matcha = PurrDefaultPresets.builtInVoices().filter { it.cartridgeId == "rom_matcha_zh_en" }
        assertEquals(1, matcha.size)
        assertEquals("matcha_narrator", matcha.first().id)
        assertEquals(SherpaModelFamily.MATCHA, matcha.first().sherpaFamily)
    }

    @Test
    fun `heavy model proxy presets are openai compatible and qwen free`() {
        assertTrue(PurrProxyPresets.PRESETS.isNotEmpty())
        PurrProxyPresets.PRESETS.forEach { preset ->
            assertTrue(preset.baseUrl.startsWith("http"))
            assertTrue(preset.baseUrl.endsWith("/v1/audio/speech"))
            assertTrue(preset.model.isNotBlank())
            assertTrue(preset.voice.isNotBlank())
        }
        assertTrue(PurrProxyPresets.PRESETS.none { it.label.contains("QWEN", ignoreCase = true) })
    }
}
