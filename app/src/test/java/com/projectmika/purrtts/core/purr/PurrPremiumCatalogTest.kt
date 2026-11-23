package com.projectmika.purrtts.core.purr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ✨ PURE-JVM TESTS FOR THE 3-CATEGORY CARTRIDGE RACK (TTS + CLONING + TOOLS) ✨
 * Guards the 7 unified TTS hubs (`ROM-01..ROM-07`), the 5 dedicated Voice Cloning modules
 * (`CLN-01..CLN-05`), nested expansion/upgrade sub-cartridges, unique voice IDs, and SHA-256 metadata.
 */
class PurrPremiumCatalogTest {

    private val catalog = PurrDefaultPresets.modelCatalog()
    private val mainTtsHubs = catalog.filter { !it.isSubCartridge && it.category == CartridgeCategory.TTS }
    private val subTtsChips = catalog.filter { it.isSubCartridge && it.category == CartridgeCategory.TTS }
    private val mainCloningModules = catalog.filter { !it.isSubCartridge && it.category == CartridgeCategory.CLONING }
    private val subCloningChips = catalog.filter { it.isSubCartridge && it.category == CartridgeCategory.CLONING }

    @Test
    fun `top level tts catalog forms a clean 9-bay hub rack ROM-01 through ROM-09`() {
        assertEquals(9, mainTtsHubs.size)
        val codes = mainTtsHubs.map { it.romCode }
        assertEquals(
            listOf("ROM-01", "ROM-02", "ROM-03", "ROM-04", "ROM-05", "ROM-06", "ROM-07", "ROM-08", "ROM-09"),
            codes
        )
    }

    @Test
    fun `dedicated cloning category hosts CLN-01 through CLN-05 and studio sub-upgrades`() {
        assertEquals(5, mainCloningModules.size)
        assertEquals(
            listOf("CLN-01", "CLN-02", "CLN-03", "CLN-04", "CLN-05"),
            mainCloningModules.map { it.romCode }
        )
        assertEquals(
            setOf("rom_pocket_tts", "rom_zipvoice", "sub_chatterbox_turbo", "sub_qwen3_base_06b", "tool_clone_studio"),
            mainCloningModules.map { it.id }.toSet()
        )
        assertEquals(
            setOf("sub_pocket_fp32", "sub_zipvoice_full"),
            subCloningChips.map { it.id }.toSet()
        )
        assertTrue(mainCloningModules.first { it.id == "tool_clone_studio" }.isBuiltInOsCartridge)
    }

    @Test
    fun `heavy sub-cartridges and cloning engines exist with valid digest format and parent links`() {
        val checkedTtsSubIds = setOf(
            "rom_kokoro_fp32", "rom_kokoro_v11",
            "sub_qwen3_design_17b",
            "sub_kitten_mini", "sub_kitten_micro",
            "sub_melo_en", "rom_supertonic3", "rom_matcha_zh_en",
            "sub_inflect_micro", "sub_icefall_low"
        )
        val foundTts = subTtsChips.filter { it.id in checkedTtsSubIds }
        assertEquals(checkedTtsSubIds.size, foundTts.size)
        val ttsHubIds = mainTtsHubs.map { it.id }.toSet()
        foundTts.forEach { spec ->
            assertTrue("${spec.id} sha", spec.expectedSha256.matches(Regex("[0-9a-f]{64}")))
            assertTrue("${spec.id} url", spec.downloadUrl.startsWith("https://"))
            assertTrue("${spec.id} parent hub", spec.parentCartridgeId in ttsHubIds)
            assertTrue("${spec.id} size", spec.approximateSizeMb >= 40)
        }

        val checkedCloneEngineIds = setOf(
            "rom_pocket_tts", "sub_pocket_fp32",
            "rom_zipvoice", "sub_zipvoice_full",
            "sub_chatterbox_turbo", "sub_qwen3_base_06b"
        )
        val foundClone = catalog.filter { it.id in checkedCloneEngineIds && it.category == CartridgeCategory.CLONING }
        assertEquals(checkedCloneEngineIds.size, foundClone.size)
        foundClone.forEach { spec ->
            assertTrue("${spec.id} sha", spec.expectedSha256.matches(Regex("[0-9a-f]{64}")))
            assertTrue("${spec.id} url", spec.downloadUrl.startsWith("https://"))
            assertTrue("${spec.id} size", spec.approximateSizeMb >= 40)
        }
    }

    @Test
    fun `new top-level heavy engines ROM-03 ROM-04 ROM-05 have valid bundles and assets`() {
        val heavyTop = mainTtsHubs.filter {
            it.id in setOf("rom_qwen3_custom_06b", "rom_neutts_2e", "rom_soprano_80m")
        }
        assertEquals(3, heavyTop.size)
        heavyTop.forEach { spec ->
            assertTrue("${spec.id} sha", spec.expectedSha256.matches(Regex("[0-9a-f]{64}")))
            assertTrue("${spec.id} url", spec.downloadUrl.startsWith("https://"))
            assertTrue("${spec.id} extraAssets", spec.extraAssets.isNotEmpty())
        }
    }

    @Test
    fun `matcha sub-cartridge carries a verified 16khz vocos companion under ROM-07`() {
        val matcha = catalog.first { it.id == "rom_matcha_zh_en" }
        assertEquals("rom_piper", matcha.parentCartridgeId)
        assertEquals("vocos-16khz-univ.onnx", matcha.companionFileName)
        assertTrue(matcha.companionUrl.endsWith("vocos-16khz-univ.onnx"))
        assertEquals("b599142a1fb8ff03de3e84ac35ff537c619e56f4267a6fe894851a42844acf9e", matcha.companionExpectedSha256)
        assertEquals(51, matcha.companionApproxSizeMb)
    }

    @Test
    fun `fp32 sub-cartridge reuses the 54-voice roster inside Kokoro Hub with unique prefixed ids`() {
        val fp32Voices = PurrDefaultPresets.builtInVoices().filter { it.requiredSubCartridgeId == "rom_kokoro_fp32" }
        assertEquals(54, fp32Voices.size)
        assertTrue(fp32Voices.all { it.cartridgeId == "rom_kokoro_sherpa" })
        assertTrue(fp32Voices.all { it.sherpaFamily == SherpaModelFamily.KOKORO })
        val rom02 = PurrDefaultPresets.builtInVoices().filter {
            it.cartridgeId == "rom_kokoro_sherpa" && it.requiredSubCartridgeId == null
        }
        assertEquals(rom02.map { it.id }, fp32Voices.map { it.id.removePrefix("fp32_") })
        assertEquals(rom02.map { it.speakerIndex }, fp32Voices.map { it.speakerIndex })
        assertTrue(fp32Voices.all { it.id.startsWith("fp32_") })
    }

    @Test
    fun `kokoro v1_1 roster carries all 103 speakers inside Kokoro Hub`() {
        val v11 = PurrDefaultPresets.builtInVoices().filter { it.requiredSubCartridgeId == "rom_kokoro_v11" }
        assertEquals(103, v11.size)
        assertTrue(v11.all { it.cartridgeId == "rom_kokoro_sherpa" })
        assertEquals("v11_af_maple", v11[0].id)
        assertEquals(0, v11[0].speakerIndex)
        assertEquals("v11_zf_001", v11[3].id)
        assertEquals(3, v11[3].speakerIndex)
        assertEquals("v11_zm_009", v11[58].id)
        assertEquals(58, v11[58].speakerIndex)
        assertEquals("v11_zm_100", v11[102].id)
        assertEquals(102, v11[102].speakerIndex)
        assertEquals(3, v11.count { it.languageCode.startsWith("en") })
        assertEquals(100, v11.count { it.languageCode == "zh-CN" })
    }

    @Test
    fun `all built-in voice ids are globally unique across cartridges`() {
        val all = PurrDefaultPresets.builtInVoices()
        val ids = all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `all new v1_7 voices are registered under their expected hub and backend`() {
        val all = PurrDefaultPresets.builtInVoices().associateBy { it.id }
        assertEquals(BackendType.QWEN3_NATIVE, all["qwen3_vivian"]?.engine)
        assertEquals("rom_qwen3_custom_06b", all["qwen3_vivian"]?.cartridgeId)
        assertEquals("sub_qwen3_design_17b", all["qwen3_design_custom"]?.requiredSubCartridgeId)
        assertEquals(BackendType.ONNX_NATIVE, all["neutts_emily"]?.engine)
        assertEquals("rom_neutts_2e", all["neutts_emily"]?.cartridgeId)
        assertEquals(BackendType.ONNX_NATIVE, all["soprano_studio_32k"]?.engine)
        assertEquals("rom_soprano_80m", all["soprano_studio_32k"]?.cartridgeId)
        assertEquals(BackendType.ONNX_NATIVE, all["chatterbox_turbo_default"]?.engine)
        assertEquals("sub_chatterbox_turbo", all["chatterbox_turbo_default"]?.cartridgeId)
        assertEquals(null, all["chatterbox_turbo_default"]?.requiredSubCartridgeId)
        assertEquals(BackendType.QWEN3_NATIVE, all["qwen3_base_clone_default"]?.engine)
        assertEquals("sub_qwen3_base_06b", all["qwen3_base_clone_default"]?.cartridgeId)
        assertEquals(null, all["qwen3_base_clone_default"]?.requiredSubCartridgeId)
        assertEquals(SherpaModelFamily.MELO_TTS, all["melo_en_us"]?.sherpaFamily)
        assertEquals("sub_melo_en", all["melo_en_us"]?.requiredSubCartridgeId)
        assertEquals(BackendType.SHERPA_ONNX, all["matcha_narrator"]?.engine)
        assertEquals("rom_matcha_zh_en", all["matcha_narrator"]?.requiredSubCartridgeId)
    }

    @Test
    fun `heavy model proxy presets remain available`() {
        assertTrue(PurrProxyPresets.PRESETS.size >= 9)
        assertTrue(PurrProxyPresets.PRESETS.any { it.id == "outetts_lan" })
        assertTrue(PurrProxyPresets.PRESETS.any { it.id == "orpheus_lan" })
        assertTrue(PurrProxyPresets.PRESETS.any { it.id == "vibevoice_lan" })
        assertTrue(PurrProxyPresets.PRESETS.any { it.id == "spark_tts_lan" })
        assertTrue(PurrProxyPresets.PRESETS.any { it.id == "cosyvoice2_lan" })
        PurrProxyPresets.PRESETS.forEach { preset ->
            assertTrue(preset.baseUrl.startsWith("http"))
            assertTrue(preset.baseUrl.endsWith("/v1/audio/speech"))
            assertTrue(preset.model.isNotBlank())
            assertTrue(preset.voice.isNotBlank())
        }
    }
}
