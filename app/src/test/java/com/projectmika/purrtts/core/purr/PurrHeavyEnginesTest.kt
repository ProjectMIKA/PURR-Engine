package com.projectmika.purrtts.core.purr

import com.projectmika.purrtts.engine.onnx.OnnxRuntimeEngine
import com.projectmika.purrtts.engine.qwen3.Qwen3TtsEngine
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ✨ PURE-JVM TESTS FOR v1.7.0 HEAVY ENGINES (BPE Tokenizer, Chatterbox Tags, NeuTTS Refs, FP16, Migration) ✨
 */
class PurrHeavyEnginesTest {

    @Test
    fun `bpe tokenizer handles ByteLevel, added_tokens, and both merge formats`() {
        val byteLevelJson = """
            {
              "normalizer": null,
              "pre_tokenizer": {"type": "ByteLevel"},
              "added_tokens": [
                {"id": 100, "content": "[laugh]"},
                {"id": 101, "content": "<|HAPPY|>"},
                {"id": 102, "content": "<|speech_42|>"}
              ],
              "model": {
                "vocab": {
                  "Ġ": 0, "h": 1, "i": 2, "hi": 3, "Ġhi": 4, "<unk>": 5
                },
                "merges": [
                  "h i",
                  ["Ġ", "hi"]
                ]
              }
            }
        """.trimIndent()

        val tok = PurrBpeTokenizer.fromJsonString(byteLevelJson)
        val ids = tok.encode("hi <|HAPPY|>[laugh]<|speech_42|>")
        assertTrue(ids.contains(3L) || ids.contains(4L))
        assertTrue(ids.contains(100L))
        assertTrue(ids.contains(101L))
        assertTrue(ids.contains(102L))
        assertEquals(100, tok.tokenToId("[laugh]"))
        assertEquals(101, tok.tokenToId("<|HAPPY|>"))
        assertEquals(102, tok.tokenToId("<|speech_42|>"))
    }

    @Test
    fun `bpe tokenizer handles Soprano lowercase normalizer and literal merges`() {
        val sopranoJson = """
            {
              "normalizer": {"type": "Sequence", "normalizers": [{"type": "Lowercase"}]},
              "pre_tokenizer": {"type": "Sequence", "pre_tokenizers": [{"type": "Digits", "individual_digits": true}]},
              "added_tokens": [
                {"id": 1, "content": "[TEXT]"},
                {"id": 2, "content": "[START]"},
                {"id": 3, "content": "[STOP]"}
              ],
              "model": {
                "vocab": {
                  " ": 4, "a": 5, "b": 6, "ab": 7, "1": 8, "2": 9
                },
                "merges": ["a b"]
              }
            }
        """.trimIndent()

        val tok = PurrBpeTokenizer.fromJsonString(sopranoJson)
        val ids = tok.encode("[STOP][TEXT]AB 12[START]")
        // [STOP]=3, [TEXT]=1, "AB" -> lowercased "ab"=7, " "=4, "1"=8, "2"=9, [START]=2
        assertArrayEquals(longArrayOf(3L, 1L, 7L, 4L, 8L, 9L, 2L), ids)
    }

    @Test
    fun `chatterbox tags survive Symbol Shield only when targeting Chatterbox`() {
        val raw = "Oh no! *laughs* Hold on [sigh] wait [gasp]!"
        val cbProfile = PurrDefaultPresets.builtInProfiles().first().copy(
            primaryVoiceId = "chatterbox_turbo_default",
            selectedCartridgeId = "rom_clone_hub",
            enableSymbolShield = true
        )
        val kokoroProfile = cbProfile.copy(
            primaryVoiceId = "af_bella",
            selectedCartridgeId = "rom_kokoro_sherpa"
        )

        val cbChunks = PurrTextProcessor.process(raw, cbProfile)
            .filterIsInstance<PurrChunk.Speech>()
            .joinToString(" ") { it.text }
        assertTrue("Chatterbox preserves [laugh]", cbChunks.contains("[laugh]"))
        assertTrue("Chatterbox preserves [sigh]", cbChunks.contains("[sigh]"))
        assertTrue("Chatterbox preserves [gasp]", cbChunks.contains("[gasp]"))

        val kokoroSanitized = PurrTextProcessor.sanitizeSpeechChunk(
            "Hello [sigh] friend [laugh]!",
            kokoroProfile,
            preserveChatterboxTags = false
        )
        assertFalse("Standard Symbol Shield still strips raw brackets", kokoroSanitized.contains("["))
    }

    @Test
    fun `neutts_2e_refs asset json contains valid codes and transcripts for all 4 speakers`() {
        val candidates = listOf(
            File("app/src/main/assets/neutts_2e_refs.json"),
            File("../app/src/main/assets/neutts_2e_refs.json")
        )
        val assetFile = candidates.firstOrNull { it.exists() }
        assertTrue("neutts_2e_refs.json exists", assetFile != null)

        val root = Json.parseToJsonElement(assetFile!!.readText()).jsonObject
        listOf("emily", "paul", "sophie", "steven").forEach { speaker ->
            val obj = root[speaker]?.jsonObject
            assertTrue("Missing $speaker in neutts_2e_refs.json", obj != null)
            val txt = obj!!["text"]?.jsonPrimitive?.content.orEmpty()
            val codes = obj["codes"]?.jsonArray ?: error("Missing codes for $speaker")
            assertTrue("Empty transcript for $speaker", txt.length > 20)
            assertTrue("Too few speech codes for $speaker", codes.size > 100)
            assertTrue(codes.all { it.jsonPrimitive.int >= 0 })
        }
    }

    @Test
    fun `clone clip target duration adapts across all 6 clone engine specs`() {
        assertEquals(5, PurrCloneAudioCleaner.targetClipSecondsForEngine("rom_pocket_tts"))
        assertEquals(5, PurrCloneAudioCleaner.targetClipSecondsForEngine("sub_pocket_fp32"))
        assertEquals(12, PurrCloneAudioCleaner.targetClipSecondsForEngine("rom_zipvoice"))
        assertEquals(12, PurrCloneAudioCleaner.targetClipSecondsForEngine("sub_zipvoice_full"))
        assertEquals(12, PurrCloneAudioCleaner.targetClipSecondsForEngine("sub_chatterbox_turbo"))
        assertEquals(12, PurrCloneAudioCleaner.targetClipSecondsForEngine("sub_qwen3_base_06b"))
    }

    @Test
    fun `fp16 ieee754 conversion and qwen language detector work accurately on JVM`() {
        val samples = floatArrayOf(0f, 1f, -1f, 0.5f, -0.25f, 3.5f, -12.25f)
        for (v in samples) {
            val h = OnnxRuntimeEngine.floatToFp16Bits(v)
            val round = OnnxRuntimeEngine.fp16BitsToFloat(h)
            assertEquals(v, round, 0.02f)
        }
        assertEquals(Qwen3TtsEngine.LANG_EN, Qwen3TtsEngine.detectQwenLanguageId("Hello world"))
        assertEquals(Qwen3TtsEngine.LANG_JA, Qwen3TtsEngine.detectQwenLanguageId("こんにちは、マスター！"))
        assertEquals(Qwen3TtsEngine.LANG_KO, Qwen3TtsEngine.detectQwenLanguageId("안녕하세요 테스트입니다"))
        assertEquals(Qwen3TtsEngine.LANG_ZH, Qwen3TtsEngine.detectQwenLanguageId("你好，欢迎使用语音合成"))
    }

    @Test
    fun `repository migrates v1_6_2 legacy cartridges to v1_7_0 hubs and routes 6 clone engines`() {
        val tempDir = Files.createTempDirectory("purr_repo_v170_test").toFile()
        try {
            val legacyStateFile = File(tempDir, "purr_forge_state.json")
            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }
            val oldState = PurrForgeState(
                activeProfileId = "gachaswipe_default",
                profiles = listOf(
                    PurrDefaultPresets.builtInProfiles().first().copy(
                        selectedCartridgeId = "rom_kokoro_fp32",
                        primaryVoiceId = "fp32_af_bella",
                        preferredBackend = BackendType.SHERPA_ONNX
                    )
                ),
                slottedCartridges = setOf("rom_core_os", "rom_kokoro_fp32", "rom_matcha_zh_en"),
                modelStates = mapOf(
                    "rom_kokoro_fp32" to ModelBundleState("rom_kokoro_fp32", DownloadState.INSTALLED, sha256Verified = true),
                    "rom_matcha_zh_en" to ModelBundleState("rom_matcha_zh_en", DownloadState.INSTALLED, sha256Verified = true)
                )
            )
            legacyStateFile.writeText(json.encodeToString(oldState))

            val repo = PurrProfileRepository(tempDir)
            val migrated = repo.state.value
            assertEquals("rom_kokoro_sherpa", repo.currentProfile.selectedCartridgeId)
            assertTrue("rom_kokoro_fp32" in migrated.enabledSubCartridges)
            assertTrue("rom_matcha_zh_en" in migrated.enabledSubCartridges)
            assertTrue("rom_kokoro_sherpa" in migrated.slottedCartridges)
            assertTrue("rom_piper" in migrated.slottedCartridges)

            // Verify Qwen3 Base & Chatterbox clone routing
            repo.registerClonedVoice(
                ClonedVoiceProfile(
                    id = "clone_qwen",
                    displayName = "Qwen Clone",
                    family = SherpaModelFamily.ZIPVOICE,
                    referenceWavPath = "/tmp/qwen.wav",
                    referenceTranscript = "hello",
                    cloneEngineId = "sub_qwen3_base_06b"
                )
            )
            assertEquals("rom_qwen3_custom_06b", repo.currentProfile.selectedCartridgeId)
            assertEquals(BackendType.QWEN3_NATIVE, repo.currentProfile.preferredBackend)

            repo.registerClonedVoice(
                ClonedVoiceProfile(
                    id = "clone_cb",
                    displayName = "CB Clone",
                    family = SherpaModelFamily.POCKET_TTS,
                    referenceWavPath = "/tmp/cb.wav",
                    referenceTranscript = "hello",
                    cloneEngineId = "sub_chatterbox_turbo"
                )
            )
            assertEquals("rom_clone_hub", repo.currentProfile.selectedCartridgeId)
            assertEquals(BackendType.ONNX_NATIVE, repo.currentProfile.preferredBackend)
            assertTrue("sub_chatterbox_turbo" in repo.state.value.enabledSubCartridges)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
