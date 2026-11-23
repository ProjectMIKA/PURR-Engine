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
            selectedCartridgeId = "sub_chatterbox_turbo",
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

            // Verify Qwen3 Base & Chatterbox clone routing into dedicated CLONING cartridges
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
            assertEquals("sub_qwen3_base_06b", repo.currentProfile.selectedCartridgeId)
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
            assertEquals("sub_chatterbox_turbo", repo.currentProfile.selectedCartridgeId)
            assertEquals(BackendType.ONNX_NATIVE, repo.currentProfile.preferredBackend)
            assertTrue("sub_chatterbox_turbo" in repo.state.value.slottedCartridges)

            // Verify legacy rom_clone_hub profiles migrate cleanly to top-level CLONING cartridges
            val legacyCloneHubDir = Files.createTempDirectory("purr_repo_clone_mig").toFile()
            try {
                File(legacyCloneHubDir, "purr_forge_state.json").writeText(
                    json.encodeToString(
                        PurrForgeState(
                            activeProfileId = "gachaswipe_default",
                            profiles = listOf(
                                PurrDefaultPresets.builtInProfiles().first().copy(
                                    selectedCartridgeId = "rom_clone_hub",
                                    primaryVoiceId = "chatterbox_turbo_default",
                                    preferredBackend = BackendType.ONNX_NATIVE
                                )
                            ),
                            slottedCartridges = setOf("rom_core_os", "rom_clone_hub"),
                            modelStates = mapOf(
                                "sub_chatterbox_turbo" to ModelBundleState("sub_chatterbox_turbo", DownloadState.INSTALLED, sha256Verified = true)
                            )
                        )
                    )
                )
                val migratedCloneRepo = PurrProfileRepository(legacyCloneHubDir)
                assertEquals("sub_chatterbox_turbo", migratedCloneRepo.currentProfile.selectedCartridgeId)
                assertFalse("rom_clone_hub" in migratedCloneRepo.state.value.slottedCartridges)
                assertTrue("sub_chatterbox_turbo" in migratedCloneRepo.state.value.slottedCartridges)
            } finally {
                legacyCloneHubDir.deleteRecursively()
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `qwen3 model_profile instruct_support parser handles true and false cleanly`() {
        assertFalse(Qwen3TtsEngine.parseInstructSupport("""{"model_type": "custom", "instruct_support": false}"""))
        assertTrue(Qwen3TtsEngine.parseInstructSupport("""{"model_type": "design", "instruct_support": true}"""))
        assertFalse(Qwen3TtsEngine.parseInstructSupport("""{"model_type": "base"}"""))
    }

    @Test
    fun `streaming bpe tokenizer loads large added_tokens and nested metadata from file`() {
        val tempFile = File.createTempFile("purr_stream_tok_", ".json")
        try {
            val sb = StringBuilder()
            sb.append("""{"version":"1.0","truncation":null,"padding":{"strategy":"longest"},""")
            sb.append(""""normalizer":{"type":"NFC"},"pre_tokenizer":{"type":"Sequence","pretokenizers":[{"type":"ByteLevel"}]},""")
            sb.append(""""added_tokens":[""")
            sb.append("""{"id":151671,"content":"<|TEXT_PROMPT_START|>","special":true},""")
            sb.append("""{"id":151672,"content":"<|TEXT_PROMPT_END|>","special":true},""")
            sb.append("""{"id":217221,"content":"<|HAPPY|>","special":true},""")
            for (i in 0 until 1000) {
                if (i > 0) sb.append(',')
                sb.append("""{"id":${151680 + i},"content":"<|speech_$i|>","special":true}""")
            }
            sb.append("""],"model":{"type":"BPE","unk_token":"<unk>","vocab":{"\"q\"":0,"Ġhello":1,"Ġworld":2,"<unk>":3},"merges":[]}}""")
            tempFile.writeText(sb.toString())

            val tok = PurrBpeTokenizer.fromFile(tempFile)
            assertEquals(151671, tok.tokenToId("<|TEXT_PROMPT_START|>"))
            assertEquals(217221, tok.tokenToId("<|HAPPY|>"))
            assertEquals(151680 + 999, tok.tokenToId("<|speech_999|>"))
            assertEquals(0, tok.tokenToId("\"q\""))
            val ids = tok.encode("<|TEXT_PROMPT_START|><|HAPPY|><|speech_42|><|TEXT_PROMPT_END|>")
            assertArrayEquals(longArrayOf(151671L, 217221L, (151680 + 42).toLong(), 151672L), ids)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `persists catalog sort filter theme config and auto-slots theme engine on upgrade`() {
        val repoDir = Files.createTempDirectory("purr_v186_state").toFile()
        try {
            val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
            File(repoDir, "purr_forge_state.json").writeText(
                json.encodeToString(
                    PurrForgeState(
                        knownToolCatalogVersion = 2,
                        slottedCartridges = setOf("rom_core_os", "tool_purr_engine")
                    )
                )
            )
            val repo = PurrProfileRepository(repoDir)
            assertTrue("tool_theme_engine" in repo.state.value.slottedCartridges)
            assertTrue("tool_script_studio" in repo.state.value.slottedCartridges)
            assertTrue("tool_voice_benchmark" in repo.state.value.slottedCartridges)
            assertTrue("tool_srt_batch_studio" in repo.state.value.slottedCartridges)
            assertTrue("tool_manga_dub_studio" in repo.state.value.slottedCartridges)

            repo.setCatalogSortMode(CartridgeSortMode.SIZE)
            repo.setCatalogFilterMode(CartridgeFilterMode.DOWNLOADED)
            repo.updateThemeConfig {
                PurrThemeConfig(
                    mode = PurrThemeMode.NEON_GLOW,
                    neonBackgroundHex = 0xFF000000L,
                    neonPrimaryHex = 0xFF00E5FFL,
                    glowIntensity = 0.92f
                )
            }

            val reloaded = PurrProfileRepository(repoDir)
            assertEquals(CartridgeSortMode.SIZE, reloaded.state.value.catalogSortMode)
            assertEquals(CartridgeFilterMode.DOWNLOADED, reloaded.state.value.catalogFilterMode)
            assertEquals(PurrThemeMode.NEON_GLOW, reloaded.state.value.themeConfig.mode)
            assertEquals(0xFF000000L, reloaded.state.value.themeConfig.neonBackgroundHex)
            assertEquals(0xFF00E5FFL, reloaded.state.value.themeConfig.neonPrimaryHex)
            assertEquals(0.92f, reloaded.state.value.themeConfig.glowIntensity, 0.001f)
        } finally {
            repoDir.deleteRecursively()
        }
    }

    @Test
    fun `phonon tokenizer normalizes text applies unigram viterbi byte fallback and splits chunks`() {
        val sampleJson = """
            {
              "version": "1.0",
              "model": {
                "type": "Unigram",
                "unk_id": 0,
                "byte_fallback": true,
                "vocab": [
                  ["\u0000<unk>", 0.0],
                  ["\u0000<s>", 0.0],
                  ["\u0000</s>", 0.0],
                  ["\u0000<pad>", 0.0],
                  ["<|audio_bos|>", 0.0],
                  ["<|audio_prompt|>", 0.0],
                  ["<|audio_latent|>", 0.0],
                  ["<0x41>", 0.0],
                  ["<0x5A>", 0.0],
                  ["<0xE2>", 0.0],
                  ["<0x82>", 0.0],
                  ["<0xAC>", 0.0],
                  ["▁", -2.5],
                  ["▁Hello", -1.2],
                  ["▁world", -1.4],
                  [".", -1.1],
                  [",", -1.1],
                  ["!", -1.1]
                ]
              }
            }
        """.trimIndent()
        val tok = PurrPhononTokenizer.fromJsonString(sampleJson)
        val encoded = tok.encode("“hello   world”")
        assertTrue(encoded.isNotEmpty())

        // Known Unigram pieces win over byte fallback; unknown Euro sign (€ -> 0xE2 0x82 0xAC) triggers byte tokens
        val helloWorld = tok.encode("hello world")
        assertArrayEquals(longArrayOf(13L, 14L, 15L), helloWorld)

        val euroIds = tok.encode("€", normalizeSentence = false)
        assertArrayEquals(longArrayOf(12L, 9L, 10L, 11L), euroIds)

        val chunks = tok.splitIntoChunks("Hello world! Hello world.", maxTokens = 4)
        assertEquals(2, chunks.size)
        assertTrue(chunks.all { it.first.isNotEmpty() && it.second >= 2 })
    }

    @Test
    fun `phonon voice latent transpose reorders disk layout 512xF to Fx512`() {
        val raw = floatArrayOf(
            // dim 0: f0=1, f1=2, f2=3
            1f, 2f, 3f,
            // dim 1: f0=10, f1=20, f2=30
            10f, 20f, 30f
        )
        val out = OnnxRuntimeEngine.transposePhononVoiceLatents(raw, voiceDim = 2)
        assertArrayEquals(
            floatArrayOf(1f, 10f, 2f, 20f, 3f, 30f),
            out,
            0.0001f
        )
    }

    @Test
    fun `phonon rom 17 sub 17a and 16 voices are wired in catalog`() {
        val catalog = PurrDefaultPresets.modelCatalog()
        val rom17 = catalog.firstOrNull { it.id == "rom_phonon_31m" }
        val sub17 = catalog.firstOrNull { it.id == "sub_phonon_fp32" }
        assertTrue(rom17 != null && rom17.romCode == "ROM-17")
        assertTrue(sub17 != null && sub17.romCode == "SUB-17A" && sub17.parentCartridgeId == "rom_phonon_31m")
        val phononVoices = PurrDefaultPresets.builtInVoices().filter { it.cartridgeId == "rom_phonon_31m" }
        assertEquals(16, phononVoices.size)
        assertTrue(phononVoices.all { it.engine == BackendType.ONNX_NATIVE && it.id.startsWith("phonon_") })
    }
}
