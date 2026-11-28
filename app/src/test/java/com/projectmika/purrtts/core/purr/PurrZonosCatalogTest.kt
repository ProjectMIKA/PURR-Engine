package com.projectmika.purrtts.core.purr

import com.projectmika.purrtts.engine.zonos3.Zonos3TtsEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ✨ PURE-JVM TESTS FOR THE ZONOS FAMILY (v2.7.0) ✨
 * Guards ROM-18 (ZONOS3-Mini MoE hub + voice packs + clone chip), ROM-19
 * (ZONOS1 Studio + Q4_K Lite swap), the 11 voice descriptors, the 60s/30s clone
 * windows, and the voice→embedding file map.
 */
class PurrZonosCatalogTest {

    private val catalog = PurrDefaultPresets.modelCatalog()

    @Test
    fun `zonos3 hub sits at ROM-18 with verified backbone and codec assets`() {
        val hub = catalog.first { it.id == "rom_zonos3_mini" }
        assertEquals("ROM-18", hub.romCode)
        assertEquals(BackendType.ZONOS3_NATIVE, hub.backend)
        assertEquals(CartridgeCategory.TTS, hub.category)
        assertTrue(hub.downloadUrl.endsWith("/gguf/backbone-ptq.gguf"))
        assertEquals("66fceb6d73a8c30bde80249e4112bb308380ab7f3384fa11c8e4048115e4ceec", hub.expectedSha256)
        assertEquals(632, hub.approximateSizeMb)
        val assetPaths = hub.extraAssets.map { it.relativePath }
        assertTrue(assetPaths.contains("zac-keep2.gguf"))
        assertTrue(assetPaths.contains("steer.gguf"))
        assertTrue(assetPaths.contains("superbpe8192.json"))
        assertTrue(assetPaths.contains("params.json"))
        hub.extraAssets.filter { it.expectedSha256.isNotBlank() }.forEach { asset ->
            assertTrue(asset.expectedSha256.matches(Regex("[0-9a-f]{64}")))
        }
    }

    @Test
    fun `zonos3 sub-chips link to the hub with voice packs and the clone encoder`() {
        val subs = catalog.filter { it.isSubCartridge && it.parentCartridgeId == "rom_zonos3_mini" }
        assertEquals(setOf("sub_zonos3_voices_en", "sub_zonos3_voices_ml", "sub_zonos3_clone"), subs.map { it.id }.toSet())
        subs.forEach { spec ->
            assertEquals(BackendType.ZONOS3_NATIVE, spec.backend)
            assertTrue(spec.expectedSha256.matches(Regex("[0-9a-f]{64}")))
            assertTrue(spec.downloadUrl.startsWith("https://huggingface.co/Zyphra/ZONOS3-Mini/"))
        }
        val cloneChip = subs.first { it.id == "sub_zonos3_clone" }
        assertTrue(cloneChip.downloadUrl.endsWith("/gguf/spk-encoder.gguf"))
        assertEquals(43, cloneChip.approximateSizeMb)
        assertEquals(CartridgeCategory.TTS, cloneChip.category)
        assertTrue(CartridgeCategory.CLONING in cloneChip.additionalCategories)
    }

    @Test
    fun `zonos1 studio hub sits at ROM-19 with dac and speaker encoder extras`() {
        val hub = catalog.first { it.id == "rom_zonos1_studio" }
        assertEquals("ROM-19", hub.romCode)
        assertEquals(BackendType.ZONOS1_NATIVE, hub.backend)
        // ✨ TAXONOMY LAW (v2.7.1): ZONOS1 is cloning-first — its primary home is the CLONE deck.
        assertEquals(CartridgeCategory.CLONING, hub.category)
        assertTrue(hub.downloadUrl.endsWith("/zonos1-q8_0.gguf"))
        assertEquals(1685, hub.approximateSizeMb)
        val assets = hub.extraAssets.map { it.relativePath }
        assertTrue(assets.contains("dac.gguf"))
        assertTrue(assets.contains("spk-encoder.gguf"))

        val q4 = catalog.first { it.id == "sub_zonos1_q4_lite" }
        assertEquals("SUB-19A", q4.romCode)
        assertEquals(CartridgeCategory.CLONING, q4.category)
        assertEquals("rom_zonos1_studio", q4.parentCartridgeId)
        assertTrue(q4.downloadUrl.endsWith("/zonos1-q4_k.gguf"))
        assertEquals(897, q4.approximateSizeMb)
    }

    @Test
    fun `eleven zonos3 default voices are registered under ROM-18 with the right packs`() {
        val zonosVoices = PurrDefaultPresets.builtInVoices().filter { it.engine == BackendType.ZONOS3_NATIVE }
        assertEquals(11, zonosVoices.size)
        assertTrue(zonosVoices.all { it.cartridgeId == "rom_zonos3_mini" })
        assertEquals(6, zonosVoices.count { it.requiredSubCartridgeId == "sub_zonos3_voices_en" })
        assertEquals(5, zonosVoices.count { it.requiredSubCartridgeId == "sub_zonos3_voices_ml" })
        assertTrue(zonosVoices.all { it.id.startsWith("zonos3_") })
        assertTrue(zonosVoices.all { "zonos" in it.nativeTags.map { tag -> tag.lowercase() } })
        assertEquals(zonosVoices.size, zonosVoices.map { it.id }.toSet().size)
        assertTrue(zonosVoices.all { Zonos3TtsEngine.VOICE_FILE_MAP.containsKey(it.id) })
    }

    @Test
    fun `zonos clone reference windows are sixty and thirty seconds`() {
        assertEquals(60, PurrCloneAudioCleaner.targetClipSecondsForEngine("sub_zonos3_clone"))
        assertEquals(30, PurrCloneAudioCleaner.targetClipSecondsForEngine("rom_zonos1_studio"))
        assertEquals(60, PurrCloneAudioCleaner.targetClipSeconds(SherpaModelFamily.ZONOS3))
        assertEquals(30, PurrCloneAudioCleaner.targetClipSeconds(SherpaModelFamily.ZONOS1))
        assertEquals(5, PurrCloneAudioCleaner.targetClipSecondsForEngine("rom_pocket_tts"))
        assertEquals(12, PurrCloneAudioCleaner.targetClipSecondsForEngine("rom_zipvoice"))
    }

    @Test
    fun `zonos3 voice map points every embedding into per-language voice directories`() {
        val map = Zonos3TtsEngine.VOICE_FILE_MAP
        assertEquals(11, map.size)
        assertTrue(map.values.all { it.startsWith("voices/") && it.endsWith(".embedding.npy") })
        assertTrue(map.values.any { it.contains("/English/") })
        assertTrue(map.values.any { it.contains("/Spanish/") })
        assertTrue(map.values.any { it.contains("/German/") })
        assertTrue(map.values.any { it.contains("/French/") })
    }

    @Test
    fun `zonos3 language hint detects scripts and falls back to voice language`() {
        assertEquals("ja", Zonos3TtsEngine.detectLanguageHint("こんにちは", "en-US"))
        assertEquals("zh", Zonos3TtsEngine.detectLanguageHint("你好世界", "en-US"))
        assertEquals("ko", Zonos3TtsEngine.detectLanguageHint("안녕하세요", "en-US"))
        assertEquals("es", Zonos3TtsEngine.detectLanguageHint("hola amigos", "es-ES"))
        assertEquals("de", Zonos3TtsEngine.detectLanguageHint("guten tag", "de-DE"))
        assertEquals("fr", Zonos3TtsEngine.detectLanguageHint("bonjour", "fr-FR"))
        assertEquals("en", Zonos3TtsEngine.detectLanguageHint("hello there", "en-US"))
    }

    @Test
    fun `clone engine map routes zonos clones to their own decks backends and labels`() {
        assertEquals("rom_zonos3_mini", PurrCloneEngineMap.targetCartridge("sub_zonos3_clone"))
        assertEquals("rom_zonos1_studio", PurrCloneEngineMap.targetCartridge("rom_zonos1_studio"))
        assertEquals(BackendType.ZONOS3_NATIVE, PurrCloneEngineMap.targetBackend("sub_zonos3_clone"))
        assertEquals(BackendType.ZONOS1_NATIVE, PurrCloneEngineMap.targetBackend("rom_zonos1_studio"))
        assertEquals("sub_zonos3_clone", PurrCloneEngineMap.requiredSubCartridge("sub_zonos3_clone"))
        assertEquals(null, PurrCloneEngineMap.requiredSubCartridge("rom_zonos1_studio"))
        assertEquals("ZONOS3-Mini 60s", PurrCloneEngineMap.engineLabel("sub_zonos3_clone", SherpaModelFamily.ZONOS3))
        assertEquals("ZONOS1 Studio", PurrCloneEngineMap.engineLabel("rom_zonos1_studio", SherpaModelFamily.ZONOS1))
        // Legacy mappings must stay byte-identical (no regression for the older engines).
        assertEquals("rom_pocket_tts", PurrCloneEngineMap.targetCartridge("unknown_engine"))
        assertEquals(BackendType.SHERPA_ONNX, PurrCloneEngineMap.targetBackend("unknown_engine"))
        assertEquals(null, PurrCloneEngineMap.requiredSubCartridge("unknown_engine"))
        assertEquals("sub_qwen3_base_06b", PurrCloneEngineMap.targetCartridge("sub_qwen3_base_06b"))
        assertEquals("rom_zipvoice", PurrCloneEngineMap.targetCartridge("sub_zipvoice_full"))
        assertEquals("rom_zipvoice", PurrCloneEngineMap.targetCartridge("rom_zipvoice"))
        assertEquals("rom_pocket_tts", PurrCloneEngineMap.targetCartridge("sub_pocket_fp32"))
        assertEquals("sub_pocket_fp32", PurrCloneEngineMap.requiredSubCartridge("sub_pocket_fp32"))
        assertEquals("ZipVoice Full", PurrCloneEngineMap.engineLabel("sub_zipvoice_full", SherpaModelFamily.ZIPVOICE))
    }
}
