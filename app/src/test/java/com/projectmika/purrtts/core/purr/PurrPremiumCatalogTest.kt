package com.projectmika.purrtts.core.purr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ✨ PURE-JVM TESTS FOR THE 3-CATEGORY CARTRIDGE RACK (TTS + CLONING + TOOLS) ✨
 * Guards the 14 unified TTS hubs (`ROM-01..ROM-14`), the 5 dedicated Voice Cloning modules
 * (`CLN-01..CLN-05`), the 7 Cyberdeck OS Tools (`TOOL-01..TOOL-07`), nested sub-cartridges,
 * unique voice IDs, and SHA-256 metadata.
 */
class PurrPremiumCatalogTest {

    private val catalog = PurrDefaultPresets.modelCatalog()
    private val mainTtsHubs = catalog.filter { !it.isSubCartridge && it.category == CartridgeCategory.TTS }
    private val subTtsChips = catalog.filter { it.isSubCartridge && it.category == CartridgeCategory.TTS }
    private val mainCloningModules = catalog.filter { !it.isSubCartridge && it.category == CartridgeCategory.CLONING }
    private val subCloningChips = catalog.filter { it.isSubCartridge && it.category == CartridgeCategory.CLONING }
    private val toolsModules = catalog.filter { !it.isSubCartridge && it.category == CartridgeCategory.TOOLS }

    @Test
    fun `top level tts catalog forms a 17-hub rack ROM-01 through ROM-17`() {
        assertEquals(17, mainTtsHubs.size)
        val codes = mainTtsHubs.map { it.romCode }
        assertEquals(
            (1..17).map { idx -> "ROM-%02d".format(idx) },
            codes
        )
        val expectedIds = setOf(
            "rom_coqui_vits",
            "rom_meta_mms",
            "rom_vctk_studio",
            "rom_matcha_en",
            "rom_mimic3_neural",
            "rom_vits_ljs",
            "rom_aishell3_zh",
            "rom_phonon_31m"
        )
        assertTrue(mainTtsHubs.map { it.id }.toSet().containsAll(expectedIds))
    }

    @Test
    fun `tools category hosts TOOL-01 through TOOL-11 plus Gemma 4 E2B Recommended and E4B Vision sub-cartridges`() {
        assertEquals(11, toolsModules.size)
        assertEquals(
            (1..11).map { idx -> "TOOL-%02d".format(idx) },
            toolsModules.map { it.romCode }
        )
        val themeTool = toolsModules.first { it.id == "tool_theme_engine" }
        assertTrue(themeTool.isBuiltInOsCartridge)
        assertEquals(CartridgeCategory.TOOLS, themeTool.category)
        assertTrue("tool_theme_engine" in PurrForgeState().slottedCartridges)

        val studioIds = setOf(
            "tool_script_studio",
            "tool_voice_benchmark",
            "tool_srt_batch_studio",
            "tool_manga_dub_studio"
        )
        studioIds.forEach { id ->
            val mod = toolsModules.first { it.id == id }
            assertTrue(mod.isBuiltInOsCartridge)
            assertEquals(CartridgeCategory.TOOLS, mod.category)
            assertTrue(id in PurrForgeState().slottedCartridges)
        }

        val gemmaE2b = catalog.first { it.id == "sub_gemma4_e2b_vision" }
        assertTrue(gemmaE2b.isSubCartridge)
        assertEquals("tool_manga_dub_studio", gemmaE2b.parentCartridgeId)
        assertEquals("GEMMA4_E2B", gemmaE2b.badge)
        assertEquals(BackendType.LITERT_NPU_GPU, gemmaE2b.backend)
        assertTrue(gemmaE2b.downloadUrl.contains("gemma-4-E2B-it-gpu.litertlm"))

        val gemmaE4b = catalog.first { it.id == "sub_gemma4_e4b_vision" }
        assertTrue(gemmaE4b.isSubCartridge)
        assertEquals("SUB-11X", gemmaE4b.romCode)
        assertEquals("tool_manga_dub_studio", gemmaE4b.parentCartridgeId)
        assertEquals(3158, gemmaE4b.approximateSizeMb)
        assertEquals(BackendType.LITERT_NPU_GPU, gemmaE4b.backend)
        assertTrue(gemmaE4b.downloadUrl.contains("gemma-4-E4B-it-gpu.litertlm"))

        val extraVisionIds = listOf(
            "sub_qwen35_vl_2b_vision",
            "sub_lfm25_vl_3b_vision",
            "sub_qwen35_vl_08b_vision",
            "sub_internvl3_1b_vision"
        )
        extraVisionIds.forEach { vid ->
            val vSpec = catalog.first { it.id == vid }
            assertTrue(vSpec.isSubCartridge)
            assertEquals("tool_manga_dub_studio", vSpec.parentCartridgeId)
            assertEquals(BackendType.LITERT_NPU_GPU, vSpec.backend)
        }
    }

    @Test
    fun `script parser and srt subtitle formatter format multi-voice lines and timecodes accurately`() {
        val script = """
            // scene header
            HOST_A: Hello listeners! [pause:450ms]
            [HOST_B] Glad to be here tonight.
            Plain narrator line.
        """.trimIndent()
        val lines = PurrScriptParser.parse(script, defaultPauseMs = 200)
        assertEquals(3, lines.size)
        assertEquals("HOST_A", lines[0].speakerTag)
        assertEquals("Hello listeners!", lines[0].text)
        assertEquals(450, lines[0].pauseAfterMs)
        assertEquals("HOST_B", lines[1].speakerTag)
        assertEquals(200, lines[1].pauseAfterMs)
        assertEquals("NARRATOR", lines[2].speakerTag)
        assertEquals(listOf("HOST_A", "HOST_B", "NARRATOR"), PurrScriptParser.discoverSpeakers(lines))

        val cues = listOf(
            PurrSubtitleCue(index = 1, startMs = 0L, endMs = 1520L, text = "First cue"),
            PurrSubtitleCue(index = 2, startMs = 1820L, endMs = 3665L, text = "Second cue")
        )
        val srt = PurrSrtFormatter.renderSrt(cues)
        assertTrue(srt.contains("00:00:00,000 --> 00:00:01,520"))
        assertTrue(srt.contains("00:00:01,820 --> 00:00:03,665"))
        val vtt = PurrSrtFormatter.renderVtt(cues)
        assertTrue(vtt.startsWith("WEBVTT"))
        assertTrue(vtt.contains("00:00:00.000 --> 00:00:01.520"))
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
            "sub_melo_en", "sub_melo_zh_en", "rom_supertonic3", "rom_matcha_zh_en",
            "sub_piper_jenny", "sub_piper_alan",
            "sub_inflect_micro", "sub_icefall_low",
            "sub_coqui_ljspeech", "sub_coqui_ljspeech_neon",
            "sub_mms_spa", "sub_mms_fra", "sub_mms_deu",
            "sub_mimic3_ko", "sub_phonon_fp32"
        )
        val foundTts = subTtsChips.filter { it.id in checkedTtsSubIds }
        assertEquals(checkedTtsSubIds.size, foundTts.size)
        val ttsHubIds = mainTtsHubs.map { it.id }.toSet()
        foundTts.forEach { spec ->
            assertTrue("${spec.id} sha", spec.expectedSha256.matches(Regex("[0-9a-f]{64}")))
            assertTrue("${spec.id} url", spec.downloadUrl.startsWith("https://"))
            assertTrue("${spec.id} parent hub", spec.parentCartridgeId in ttsHubIds)
            assertTrue("${spec.id} size", spec.approximateSizeMb >= 25)
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
            assertTrue("${spec.id} size", spec.approximateSizeMb >= 25)
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
    fun `matcha modules carry verified 16khz and 22khz vocos companions`() {
        val matchaZhEn = catalog.first { it.id == "rom_matcha_zh_en" }
        assertEquals("rom_piper", matchaZhEn.parentCartridgeId)
        assertEquals("vocos-16khz-univ.onnx", matchaZhEn.companionFileName)
        assertTrue(matchaZhEn.companionUrl.endsWith("vocos-16khz-univ.onnx"))
        assertEquals("b599142a1fb8ff03de3e84ac35ff537c619e56f4267a6fe894851a42844acf9e", matchaZhEn.companionExpectedSha256)
        assertEquals(51, matchaZhEn.companionApproxSizeMb)

        val matchaEn = catalog.first { it.id == "rom_matcha_en" }
        assertFalse(matchaEn.isSubCartridge)
        assertEquals("ROM-13", matchaEn.romCode)
        assertEquals("vocos-22khz-univ.onnx", matchaEn.companionFileName)
        assertTrue(matchaEn.companionUrl.endsWith("vocos-22khz-univ.onnx"))
        assertEquals("0574a135aa1db2de6e181050db2ec528496cacd4a4701fc5d7faf9f9804c0081", matchaEn.companionExpectedSha256)
        assertEquals(51, matchaEn.companionApproxSizeMb)
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
    fun `v1_8_6 new sherpa engines and voices wire to expected hubs, families, and sub-cartridges`() {
        val all = PurrDefaultPresets.builtInVoices().associateBy { it.id }
        assertEquals("sub_piper_jenny", all["piper_jenny_uk"]?.requiredSubCartridgeId)
        assertEquals("sub_piper_alan", all["piper_alan_uk"]?.requiredSubCartridgeId)
        assertEquals(SherpaModelFamily.MELO_TTS, all["melo_zh_en"]?.sherpaFamily)
        assertEquals("sub_melo_zh_en", all["melo_zh_en"]?.requiredSubCartridgeId)

        assertEquals("rom_coqui_vits", all["coqui_vctk_0"]?.cartridgeId)
        assertEquals("sub_coqui_ljspeech", all["coqui_ljspeech"]?.requiredSubCartridgeId)
        assertEquals("sub_coqui_ljspeech_neon", all["coqui_ljspeech_neon"]?.requiredSubCartridgeId)

        assertEquals("rom_meta_mms", all["mms_eng"]?.cartridgeId)
        assertEquals("sub_mms_spa", all["mms_spa"]?.requiredSubCartridgeId)
        assertEquals("sub_mms_fra", all["mms_fra"]?.requiredSubCartridgeId)
        assertEquals("sub_mms_deu", all["mms_deu"]?.requiredSubCartridgeId)

        assertEquals("rom_vctk_studio", all["vctk_s0"]?.cartridgeId)
        assertEquals(SherpaModelFamily.MATCHA, all["matcha_en_ljspeech"]?.sherpaFamily)
        assertEquals("rom_matcha_en", all["matcha_en_ljspeech"]?.cartridgeId)

        assertEquals("rom_mimic3_neural", all["mimic3_es_mailabs"]?.cartridgeId)
        assertEquals("sub_mimic3_ko", all["mimic3_ko_kss"]?.requiredSubCartridgeId)

        assertEquals("rom_vits_ljs", all["ljs_lexicon_en"]?.cartridgeId)
        assertEquals(SherpaModelFamily.PIPER, all["ljs_lexicon_en"]?.sherpaFamily)
        assertEquals(BackendType.SHERPA_ONNX, all["ljs_lexicon_en"]?.engine)

        val aishellVoices = (0..9).map { idx -> all["aishell3_s$idx"] }
        assertTrue(aishellVoices.all { it != null && it.cartridgeId == "rom_aishell3_zh" && it.sherpaFamily == SherpaModelFamily.PIPER })
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
