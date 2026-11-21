package com.projectmika.purrtts.core.purr

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
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
        assertEquals(defaultProfile.mikaStopConfig.ellipsisPauseMs + 500, silences[0].durationMs)
        assertEquals(defaultProfile.mikaStopConfig.emDashPauseMs, silences[1].durationMs)
    }

    @Test
    fun `exact 24khz pcm16 silence byte length matches durationMs`() {
        val silenceBytes = PurrDspProcessor.createSilencePcm16Le(sampleRate = 24000, durationMs = 500)
        assertEquals(24000, silenceBytes.size)
        assertTrue(silenceBytes.all { it == 0.toByte() })
    }

    @Test
    fun `action blocks and optional dual voice routing behave cleanly`() {
        val input = "*she leans closer* \"Did you miss me?\" she whispered."

        val stripProfile = defaultProfile.copy(actionHandlingMode = ActionHandlingMode.STRIP_ACTIONS)
        val strippedChunks = PurrTextProcessor.process(input, stripProfile)
        val strippedSpeech = strippedChunks.filterIsInstance<PurrChunk.Speech>().joinToString(" ") { it.text }
        assertFalse(strippedSpeech.contains("leans closer", ignoreCase = true))
        assertTrue(strippedSpeech.contains("Did you miss me", ignoreCase = true))

        // When optional dual voice split is OFF, both narration & dialogue stay on primaryVoiceId
        val singleProfile = defaultProfile.copy(
            enableDualVoiceSplit = false,
            actionHandlingMode = ActionHandlingMode.NARRATE_SOFT,
            primaryVoiceId = "af_sky",
            dialogueVoiceId = "af_bella"
        )
        val singleSpeech = PurrTextProcessor.process(input, singleProfile).filterIsInstance<PurrChunk.Speech>()
        assertTrue(singleSpeech.all { it.targetVoiceId == "af_sky" })

        // When optional dual voice split is ON, dialogue switches to dialogueVoiceId
        val dualProfile = singleProfile.copy(enableDualVoiceSplit = true)
        val dualSpeech = PurrTextProcessor.process(input, dualProfile).filterIsInstance<PurrChunk.Speech>()
        val actionChunk = dualSpeech.first { it.role == SegmentRole.ACTION }
        val dialogueChunk = dualSpeech.first { it.role == SegmentRole.DIALOGUE }
        assertEquals("af_sky", actionChunk.targetVoiceId)
        assertEquals("af_bella", dialogueChunk.targetVoiceId)
    }

    @Test
    fun `disabling slang and mikastop toggles respects simple mode`() {
        val cleanProfile = defaultProfile.copy(
            enableSlangAndHonorifics = false,
            enableMikaStopPauses = false
        )
        val chunks = PurrTextProcessor.process("omg wait... right now", cleanProfile)
        val silences = chunks.filterIsInstance<PurrChunk.Silence>()
        val speechText = chunks.filterIsInstance<PurrChunk.Speech>().joinToString(" ") { it.text }
        assertEquals(0, silences.size)
        assertFalse(speechText.contains("oh my god", ignoreCase = true))
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
        // Default basePitch = 1.0f avoids unintended per-sentence pitch resampling artifacts
        assertEquals(1.0f, flusteredMod.pitch, 0.0001f)
        assertEquals(1.0f, sadMod.pitch, 0.0001f)

        val customPitchProfile = defaultProfile.copy(
            sentimentConfig = defaultProfile.sentimentConfig.copy(
                flusteredPitchDelta = 0.03f,
                sadnessPitchDelta = -0.02f
            )
        )
        val flusteredCustom = PurrSentimentScorer.computeProsodyModulation(flustered, customPitchProfile, SegmentRole.DIALOGUE)
        val sadCustom = PurrSentimentScorer.computeProsodyModulation(sad, customPitchProfile, SegmentRole.DIALOGUE)
        assertTrue(flusteredCustom.pitch > sadCustom.pitch)
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

    @Test
    fun `audiobook abbreviations curly apostrophes and hyphenated compounds stay intact`() {
        val audiobookProfile = PurrDefaultPresets.builtInProfiles()
            .first { it.id == "light_novel_reader" }
            .copy(enableDualVoiceSplit = false)
        val input = "Mr. Smith wouldn’t visit his co-worker or Dr. Lee after re-reading v1.2."
        val chunks = PurrTextProcessor.process(input, audiobookProfile)
        val speechChunks = chunks.filterIsInstance<PurrChunk.Speech>()

        assertEquals(1, speechChunks.size)
        val rendered = speechChunks.first().text
        assertTrue(rendered.contains("Mr. Smith"))
        assertTrue(rendered.contains("wouldn't"))
        assertTrue(rendered.contains("co worker"))
        assertTrue(rendered.contains("Dr. Lee"))
        assertTrue(rendered.contains("re reading"))
        assertTrue(rendered.contains("1.2"))
    }

    @Test
    fun `short comma openers do not isolate into one word chunks`() {
        val audiobookProfile = PurrDefaultPresets.builtInProfiles()
            .first { it.id == "light_novel_reader" }
            .copy(
                enableDualVoiceSplit = false,
                mikaStopConfig = MikaStopConfig(sliceAtCommas = true, minWordsForCommaPause = 3)
            )
        val input = "Well, I think we should finally open the heavy wooden door right now."
        val speechChunks = PurrTextProcessor.process(input, audiobookProfile)
            .filterIsInstance<PurrChunk.Speech>()

        assertEquals(1, speechChunks.size)
        assertTrue(speechChunks.first().text.startsWith("Well I think", ignoreCase = true))
    }

    @Test
    fun `symbol shield strips quotes commas and skipped chars while keeping mikastop`() {
        val profile = defaultProfile.copy(
            enableDualVoiceSplit = false,
            enableSymbolShield = true,
            symbolShieldConfig = SymbolShieldConfig(commaHandling = CommaHandlingMode.MIKA_PAUSE_AND_STRIP),
            mikaStopConfig = MikaStopConfig(
                ellipsisPauseMs = 450,
                emDashPauseMs = 320,
                sentencePauseMs = 280,
                commaPauseMs = 140,
                actionStripPauseMs = 200,
                sliceAtCommas = true,
                minWordsForCommaPause = 1
            )
        )
        val input = "\"Hello there, [Master]!\" he whispered — #chapter_1"
        val chunks = PurrTextProcessor.process(input, profile)
        val silences = chunks.filterIsInstance<PurrChunk.Silence>()
        val speechText = chunks.filterIsInstance<PurrChunk.Speech>().joinToString(" ") { it.text }

        assertTrue(silences.isNotEmpty())
        assertFalse(speechText.contains('"'))
        assertFalse(speechText.contains(','))
        assertFalse(speechText.contains('['))
        assertFalse(speechText.contains(']'))
        assertFalse(speechText.contains('#'))
        assertFalse(speechText.contains('_'))
        assertTrue(speechText.contains("Hello there", ignoreCase = true))
    }

    @Test
    fun `dsp processor applies gachaswipe anti pop fade in and 120ms sentence tail taper`() {
        val sampleRate = 24000
        val input = FloatArray(sampleRate / 2) { i ->
            0.4f * sin(2.0 * PI * 440.0 * i / sampleRate).toFloat()
        }
        val config = DspConfig(
            effectPreset = AcousticEffectPreset.NONE,
            enableSentenceTaper = true,
            sentenceTaperMs = 120,
            antiPopFadeInMs = 25,
            warmthEqDb = 2.0f,
            presenceEqDb = -4.0f,
            reverbWetMix = 0.0f
        )

        val processed = PurrDspProcessor.process(input, sampleRate, config, volumeGain = 1.0f, dspEnabled = true)
        assertEquals(input.size, processed.size)

        val midPeak = processed.slice(sampleRate / 6 until sampleRate / 4).maxOf { abs(it) }
        val tailPeak = processed.takeLast(sampleRate / 200).maxOf { abs(it) }
        assertTrue(midPeak > 0.15f)
        assertTrue(tailPeak < 0.03f)
        assertTrue(abs(processed.first()) < 0.02f)
    }

    @Test
    fun `loose targetChunkChars coalesces complete sentences without mid sentence splits`() {
        val audiobookProfile = PurrDefaultPresets.builtInProfiles()
            .first { it.id == "light_novel_reader" }
            .copy(
                enableDualVoiceSplit = false,
                mikaStopConfig = MikaStopConfig(
                    sentencePauseMs = 300,
                    sliceAtCommas = false,
                    targetChunkChars = 120
                )
            )
        val s1 = "The evening wind blew softly across the quiet harbor."
        val s2 = "Captain Elara adjusted her brass compass and watched the horizon ahead."
        val s3 = "Tomorrow the real journey across the sea would finally begin."
        val input = "$s1 $s2 $s3"

        val chunks = PurrTextProcessor.process(input, audiobookProfile)
        val speechChunks = chunks.filterIsInstance<PurrChunk.Speech>()

        assertEquals(2, speechChunks.size)
        assertTrue(speechChunks[0].text.contains(s1))
        assertTrue(speechChunks[0].text.contains(s2))
        assertTrue(speechChunks[0].text.endsWith("."))
        assertEquals(s3, speechChunks[1].text)
    }

    @Test
    fun `ejecting purr or symbol shield tool modules bypasses their pipelines cleanly`() {
        val input = "Wait, omg... \"Are you sure?\" fr"

        val bypassedPurr = PurrTextProcessor.process(
            rawText = input,
            profile = defaultProfile,
            purrModuleActive = false,
            symbolShieldModuleActive = true
        )
        val bypassedPurrSpeech = bypassedPurr.filterIsInstance<PurrChunk.Speech>().joinToString(" ") { it.text }
        val bypassedPurrSilences = bypassedPurr.filterIsInstance<PurrChunk.Silence>()
        assertEquals(0, bypassedPurrSilences.size)
        assertFalse(bypassedPurrSpeech.contains("oh my god", ignoreCase = true))
        assertFalse(bypassedPurrSpeech.contains('"'))

        val bypassedShield = PurrTextProcessor.process(
            rawText = "Hello there, friend. Wait, next sentence.",
            profile = defaultProfile.copy(
                mikaStopConfig = defaultProfile.mikaStopConfig.copy(targetChunkChars = 180)
            ),
            purrModuleActive = true,
            symbolShieldModuleActive = false
        )
        val shieldedSpeech = bypassedShield.filterIsInstance<PurrChunk.Speech>()
        assertTrue(shieldedSpeech.size >= 2)
    }

    @Test
    fun `catalog includes kittentts 6 piper expansion packs and 7 default-on tool cartridges`() {
        val catalog = PurrDefaultPresets.modelCatalog()
        val voices = PurrDefaultPresets.builtInVoices()
        val defaultState = PurrForgeState()

        assertTrue(catalog.any { it.id == "rom_kitten_tts" && it.sherpaFamily == SherpaModelFamily.KITTEN_TTS && it.category == CartridgeCategory.TTS })
        assertEquals(8, voices.count { it.cartridgeId == "rom_kitten_tts" && it.requiredSubCartridgeId == null })
        assertEquals(8, voices.count { it.cartridgeId == "rom_kitten_tts" && it.requiredSubCartridgeId == "sub_kitten_mini" })

        val narratorSubs = catalog.filter { it.parentCartridgeId == "rom_piper" && it.isSubCartridge }
        assertEquals(9, narratorSubs.size)
        assertEquals(6, narratorSubs.count { it.id.startsWith("sub_piper_") })
        assertEquals(6, voices.count { it.requiredSubCartridgeId?.startsWith("sub_piper_") == true })

        val expectedTools = setOf(
            "tool_purr_engine",
            "tool_dsp_rack",
            "tool_symbol_shield",
            "tool_clone_studio",
            "tool_audio_exporter",
            "tool_voice_design",
            "tool_audiobook_constructor"
        )
        val tools = catalog.filter { it.category == CartridgeCategory.TOOLS }
        assertEquals(expectedTools, tools.map { it.id }.toSet())
        assertTrue(expectedTools.all { it in defaultState.slottedCartridges })
    }

    @Test
    fun `neural pure mode auto bypasses slang stutters and comma chopping for heavyweight neural models`() {
        val sample = "O-oh, wait... omg fr Mika-senpai, \"Are you serious right now?!\" nya~"
        val qwenProfile = defaultProfile.copy(
            selectedCartridgeId = "rom_qwen3_custom_06b",
            preferredBackend = BackendType.QWEN3_NATIVE,
            primaryVoiceId = "qwen3_vivian",
            autoBypassEnhancersForNeuralModels = true
        )
        assertTrue(PurrNeuralBypass.shouldBypassEnhancers(qwenProfile))

        val qwenChunks = PurrTextProcessor.process(sample, qwenProfile)
        val qwenSpeech = qwenChunks.filterIsInstance<PurrChunk.Speech>().joinToString(" ") { it.text }
        val qwenSilences = qwenChunks.filterIsInstance<PurrChunk.Silence>()

        // ✨ SLANG CARVE-OUT (default ON): slang expansion, organic stutters & honorifics ride
        // along on flagship voices — texting shorthand must be spoken meaningfully...
        assertTrue(qwenSpeech.contains("oh my god", ignoreCase = true))
        assertTrue(qwenSpeech.contains("for real", ignoreCase = true))
        assertTrue(qwenSpeech.contains("sen-pie", ignoreCase = true))
        assertTrue(qwenSpeech.contains("oh-oh", ignoreCase = true))
        assertFalse(qwenSpeech.contains("omg"))
        // ...while the intrusive enhancers stay bypassed: commas kept raw, quotes intact,
        // MIKASTOP pause injection off (0 silences), no sentiment-driven micro-slicing.
        assertTrue(qwenSpeech.contains(","))
        assertTrue(qwenSpeech.contains("\"Are you serious right now?!\""))
        assertEquals(0, qwenSilences.size)

        // Explicitly disabling the carve-out restores the full v1.8.0 bypass (slang included)
        val fullBypassChunks = PurrTextProcessor.process(
            sample,
            qwenProfile.copy(slangDuringNeuralPure = false)
        )
        val fullBypassSpeech = fullBypassChunks.filterIsInstance<PurrChunk.Speech>().joinToString(" ") { it.text }
        assertFalse(fullBypassSpeech.contains("oh my god", ignoreCase = true))
        assertFalse(fullBypassSpeech.contains("for real", ignoreCase = true))
        assertFalse(fullBypassSpeech.contains("sen-pie", ignoreCase = true))
        assertTrue(fullBypassSpeech.contains("omg"))
        assertTrue(fullBypassSpeech.contains(","))
        assertTrue(fullBypassSpeech.contains("\"Are you serious right now?!\""))
        assertEquals(0, fullBypassChunks.filterIsInstance<PurrChunk.Silence>().size)

        // Flagship user override works: turning off Neural Pure re-enables classic PURR middleware
        val overrideChunks = PurrTextProcessor.process(
            sample,
            qwenProfile.copy(autoBypassEnhancersForNeuralModels = false)
        )
        val overrideSpeech = overrideChunks.filterIsInstance<PurrChunk.Speech>().joinToString(" ") { it.text }
        assertTrue(overrideSpeech.contains("oh my god", ignoreCase = true))

        // Standard lightweight engines still keep full PURR enhancements on by default
        assertFalse(PurrNeuralBypass.shouldBypassEnhancers(defaultProfile))
        val kokoroInt8 = defaultProfile.copy(
            selectedCartridgeId = "rom_kokoro_sherpa",
            preferredBackend = BackendType.SHERPA_ONNX,
            primaryVoiceId = "af_bella"
        )
        assertFalse(PurrNeuralBypass.shouldBypassEnhancers(kokoroInt8))

        val neuttsProfile = defaultProfile.copy(
            selectedCartridgeId = "rom_neutts_2e",
            preferredBackend = BackendType.ONNX_NATIVE,
            primaryVoiceId = "neutts_emily"
        )
        assertTrue(PurrNeuralBypass.shouldBypassEnhancers(neuttsProfile))
    }

    @Test
    fun `audiobook media exporter chunks paragraphs cleanly and stitches smoothly without clipping`() {
        val bookText = """
            Chapter One. The lanterns glowed along the harbor wall.

            Captain Elara opened her logbook. "Set sail at dawn!" she commanded.
        """.trimIndent()

        val exportChunks = PurrTextProcessor.buildExportChunks(
            rawText = bookText,
            profile = defaultProfile.copy(enableDualVoiceSplit = false),
            purrModuleActive = true,
            symbolShieldModuleActive = true,
            targetChunkCharsOverride = 220,
            paragraphPauseMs = 550
        )
        val silences = exportChunks.filterIsInstance<PurrChunk.Silence>()
        val speeches = exportChunks.filterIsInstance<PurrChunk.Speech>()
        assertTrue(speeches.size >= 2)
        assertTrue(silences.any { it.reason.contains("PARAGRAPH") && it.durationMs == 550 })

        val sampleRate = 24000
        val seg1 = PurrDspProcessor.ExportAudioSegment(
            pcmFloat24k = FloatArray(sampleRate / 4) { i -> 0.55f * sin(2.0 * PI * 330.0 * i / sampleRate).toFloat() },
            isSilence = false
        )
        val gap = PurrDspProcessor.ExportAudioSegment(pcmFloat24k = FloatArray(sampleRate / 10), isSilence = true)
        val seg2 = PurrDspProcessor.ExportAudioSegment(
            pcmFloat24k = FloatArray(sampleRate / 4) { i -> 0.25f * sin(2.0 * PI * 440.0 * i / sampleRate).toFloat() },
            isSilence = false
        )
        val seg3 = PurrDspProcessor.ExportAudioSegment(
            pcmFloat24k = FloatArray(sampleRate / 4) { i -> 0.50f * sin(2.0 * PI * 520.0 * i / sampleRate).toFloat() },
            isSilence = false
        )

        val stitched = PurrDspProcessor.stitchAndMasterAudiobook(
            segments = listOf(seg1, gap, seg2, seg3),
            sampleRate = sampleRate,
            normalizeLoudness = true,
            smoothTransitions = true
        )
        val overlapSamples = (sampleRate * 0.018f).toInt()
        assertEquals(seg1.pcmFloat24k.size + gap.pcmFloat24k.size + seg2.pcmFloat24k.size + seg3.pcmFloat24k.size - overlapSamples, stitched.size)
        assertTrue(stitched.all { abs(it) <= 0.90f })
    }

    @Test
    fun `cubic hermite resampler and difficult oov words preserve fidelity`() {
        // 1. Verify Clean Studio keeps pure midrange tone free of compressor buzz or HF loss
        val sampleRate = 24000
        val input = FloatArray(sampleRate / 4) { i ->
            0.35f * sin(2.0 * PI * 1000.0 * i / sampleRate).toFloat()
        }
        val cleanProcessed = PurrDspProcessor.process(
            input = input,
            sampleRate = sampleRate,
            config = DspConfig(
                warmthEqDb = 0f,
                presenceEqDb = 0f,
                reverbWetMix = 0f,
                effectPreset = AcousticEffectPreset.NONE,
                enableSentenceTaper = false,
                antiPopFadeInMs = 0
            ),
            volumeGain = 1.0f,
            dspEnabled = true
        )
        for (i in input.indices) {
            assertEquals(input[i], cleanProcessed[i], 1e-4f)
        }

        // 2. Verify 4-point Cubic Hermite resampler (22050Hz -> 24000Hz) produces clean, finite samples
        val src22k = FloatArray(22050 / 2) { i ->
            0.5f * sin(2.0 * PI * 440.0 * i / 22050.0).toFloat()
        }
        val resampled24k = PurrDspProcessor.resampleMono(src22k, fromRate = 22050, toRate = 24000)
        assertTrue(abs(resampled24k.size - 12000) <= 2)
        assertTrue(resampled24k.all { it.isFinite() && abs(it) <= 1.0f })

        // 3. Verify difficult, accented, and compound words survive PURR text processing intact
        val readerProfile = PurrDefaultPresets.builtInProfiles()
            .first { it.id == "light_novel_reader" }
            .copy(enableDualVoiceSplit = false)
        val trickyInput = "Antidisestablishmentarianism and state-of-the-art café techniques wouldn't fail."
        val chunks = PurrTextProcessor.process(trickyInput, readerProfile)
        val rendered = chunks.filterIsInstance<PurrChunk.Speech>().joinToString(" ") { it.text }
        assertTrue(rendered.contains("Antidisestablishmentarianism", ignoreCase = true))
        assertTrue(rendered.contains("state of the art", ignoreCase = true))
        assertTrue(rendered.contains("café", ignoreCase = true))
        assertTrue(rendered.contains("wouldn't", ignoreCase = true))
    }
}
