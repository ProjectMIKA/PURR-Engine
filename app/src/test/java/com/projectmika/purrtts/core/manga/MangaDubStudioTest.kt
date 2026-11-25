package com.projectmika.purrtts.core.manga

import com.projectmika.purrtts.core.purr.OpenAiEndpointUtils
import com.projectmika.purrtts.core.purr.OpenAiVisionProviderPreset
import com.projectmika.purrtts.core.purr.PurrDspProcessor
import com.projectmika.purrtts.core.purr.VoiceGender
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset
import java.nio.file.Files
import kotlin.math.abs
import kotlin.math.sin
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ✨ PURE-JVM TESTS FOR TOOL-11 MANGA DUB & MOTION-COMIC STUDIO ✨
 * Covers GachaSwipe BFS panel slicing (RTL Manga vs LTR Comic), MIKAReader OCR sanitization,
 * GachaSwipe emotional pause pacing & atmosphere triggers, and Gemma 4 <thought> + JSON parsing.
 */
class MangaDubStudioTest {

    @Test
    fun `bfs panel slicer isolates 2x2 grid cleanly in RTL manga and LTR comic order`() {
        val w = 120
        val h = 160
        // White paper gutters (luminance 250), dark manga panels (luminance 40)
        val grid = IntArray(w * h) { 250 }

        fun fillPanel(x1: Int, y1: Int, x2: Int, y2: Int) {
            for (y in y1 until y2) {
                for (x in x1 until x2) {
                    grid[y * w + x] = 40
                }
            }
        }

        // Top-Left, Top-Right, Bottom-Left, Bottom-Right panels with white gutters between them
        fillPanel(10, 10, 54, 72)
        fillPanel(66, 10, 110, 72)
        fillPanel(10, 84, 54, 148)
        fillPanel(66, 84, 110, 148)

        val rtlPanels = MangaBfsPanelSlicer.slicePanelsFromLuminanceGrid(grid, w, h, MangaReadingOrder.RTL_MANGA)
        assertEquals(4, rtlPanels.size)
        // Row 1 Right -> Row 1 Left -> Row 2 Right -> Row 2 Left
        assertTrue(rtlPanels[0].centerX > 0.5f && rtlPanels[0].centerY < 0.5f)
        assertTrue(rtlPanels[1].centerX < 0.5f && rtlPanels[1].centerY < 0.5f)
        assertTrue(rtlPanels[2].centerX > 0.5f && rtlPanels[2].centerY > 0.5f)
        assertTrue(rtlPanels[3].centerX < 0.5f && rtlPanels[3].centerY > 0.5f)

        val ltrPanels = MangaBfsPanelSlicer.slicePanelsFromLuminanceGrid(grid, w, h, MangaReadingOrder.LTR_COMIC)
        assertEquals(4, ltrPanels.size)
        // Row 1 Left -> Row 1 Right -> Row 2 Left -> Row 2 Right
        assertTrue(ltrPanels[0].centerX < 0.5f && ltrPanels[0].centerY < 0.5f)
        assertTrue(ltrPanels[1].centerX > 0.5f && ltrPanels[1].centerY < 0.5f)
        assertTrue(ltrPanels[2].centerX < 0.5f && ltrPanels[2].centerY > 0.5f)
        assertTrue(ltrPanels[3].centerX > 0.5f && ltrPanels[3].centerY > 0.5f)
    }

    @Test
    fun `manga text bender converts all-caps lettering into natural speech while preserving whitelisted acronyms`() {
        val raw = "WHAT DID YOU SAY, KAKAROT?! IT'S OVER 9000 IN MY DNA!!!"
        val clean = MangaTextBender.sanitizeMangaDialogue(raw)
        assertEquals("What did you say, kakarot?! It's over 9000 in my DNA!", clean)
        assertFalse(clean.contains("WHAT"))
        assertTrue(clean.contains("DNA"))
    }

    @Test
    fun `emotional pacer and atmosphere fx resolver mirror gachaswipe dynamics`() {
        assertEquals(850, MangaEmotionalPacer.computePostLinePauseMs("Goodbye.", "sadness"))
        assertEquals(425, MangaEmotionalPacer.computePostLinePauseMs("Goodbye,", "sadness"))
        assertEquals(400, MangaEmotionalPacer.computePostLinePauseMs("Attack!", "anger battle"))
        assertEquals(700, MangaEmotionalPacer.computePostLinePauseMs("Forever.", "love"))

        assertEquals(
            MangaAtmospherePreset.EMBER_BATTLE,
            MangaEmotionalPacer.resolveAtmosphere(MangaAtmospherePreset.AUTO, "intense battle", "Flames erupt!")
        )
        assertEquals(
            MangaAtmospherePreset.RAIN_STORM,
            MangaEmotionalPacer.resolveAtmosphere(MangaAtmospherePreset.AUTO, "gloom", "Cold rain pours down")
        )
        assertEquals(
            MangaAtmospherePreset.SAKURA_PETALS,
            MangaEmotionalPacer.resolveAtmosphere(MangaAtmospherePreset.AUTO, "spring shrine", "Her blush glows")
        )
        assertEquals(
            MangaAtmospherePreset.CYBER_SPARKLE,
            MangaEmotionalPacer.resolveAtmosphere(MangaAtmospherePreset.AUTO, "cyberportal", "Neon magic awakens")
        )
    }

    @Test
    fun `gemma vision response parser extracts thinking trace and flags low-confidence speakers`() {
        val rawOutput = """
            <thought>Panel 1 tail points clearly to VEGETA_X; Panel 2 has a tail-less action shout bubble.</thought>
            ```json
            {
              "story_summary": "Vegeta_X confronts the mysterious signal.",
              "panels": [
                {
                  "panel_index": 0,
                  "scene_mood": "intense battle",
                  "lines": [
                    {
                      "bubble": 1,
                      "speaker": "VEGETA_X",
                      "is_new_character": true,
                      "gender": "male",
                      "voice_design_prompt": "Fierce raspy proud warrior prince",
                      "emotion": "anger",
                      "confidence": 0.94,
                      "text": "OUT OF MY WAY!"
                    }
                  ]
                },
                {
                  "panel_index": 1,
                  "scene_mood": "mystery",
                  "lines": [
                    {
                      "bubble": 1,
                      "speaker": "UNKNOWN",
                      "confidence": 0.45,
                      "candidate_speakers": ["VEGETA_X", "KAEL"],
                      "text": "WATCH OUT BEHIND YOU!"
                    }
                  ]
                }
              ]
            }
            ```
        """.trimIndent()

        val parsed = MangaVisionResponseParser.parsePageAnalysis(
            rawResponse = rawOutput,
            panelCount = 2,
            existingCharacters = listOf(MangaCharacterProfile(id = "c1", name = "KAEL", gender = VoiceGender.MALE))
        )
        assertTrue(parsed.thoughtTrace.contains("VEGETA_X"))
        assertEquals("Vegeta_X confronts the mysterious signal.", parsed.storySummary)
        assertEquals(2, parsed.panels.size)

        val line1 = parsed.panels[0].lines.single()
        assertEquals("VEGETA_X", line1.speakerName)
        assertEquals(VoiceGender.MALE, line1.gender)
        assertFalse(line1.needsUserDecision)

        val line2 = parsed.panels[1].lines.single()
        assertTrue(line2.needsUserDecision)
        assertTrue("KAEL" in line2.candidateSpeakers)
    }

    @Test
    fun `hardened cbz extractor unpacks nested zip natural order shift-jis and magic-byte extensionless images`() {
        val tempRoot = Files.createTempDirectory("purr_cbz_test").toFile()
        try {
            val cbzFile = File(tempRoot, "manga_test.cbz")
            val destDir = File(tempRoot, "out")
            val fakePng = byteArrayOf(
                0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                0, 0, 0, 13, 73, 72, 68, 82, 0, 0, 0, 1, 0, 0, 0, 1, 8, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0
            )
            val fakeJpg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 16, 74, 70, 73, 70, 0, 1) +
                ByteArray(32) { 1 }

            ZipArchiveOutputStream(cbzFile.outputStream()).use { zos ->
                zos.setEncoding("Shift_JIS")
                fun addEntry(name: String, bytes: ByteArray) {
                    val entry = ZipArchiveEntry(name)
                    zos.putArchiveEntry(entry)
                    zos.write(bytes)
                    zos.closeArchiveEntry()
                }
                addEntry("__MACOSX/._001.png", ByteArray(40))
                addEntry("ch01/10.jpg", fakeJpg)
                addEntry("ch01/2_extensionless", fakePng) // magic byte PNG detection!
                addEntry("第1話/page_01.jpg", fakeJpg)
            }

            val pages = MangaCbzExtractorUtils.extractArchiveFromFile(cbzFile, destDir, "manga_test.cbz")
            assertEquals(3, pages.size)
            assertTrue(pages[0].name.startsWith("page_00000."))
            assertEquals("png", pages[1].extension)
            assertTrue(pages.all { it.exists() && it.length() >= 32L })
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `cbz extractor identifies rar cbr and pdf containers with explicit guidance`() {
        val tempRoot = Files.createTempDirectory("purr_cbz_err_test").toFile()
        try {
            val fakeRar = File(tempRoot, "secret_cbr.cbz").apply {
                writeBytes(byteArrayOf('R'.code.toByte(), 'a'.code.toByte(), 'r'.code.toByte(), '!'.code.toByte(), 0x1A, 0x07, 1, 0) + ByteArray(32))
            }
            val rarErr = runCatching {
                MangaCbzExtractorUtils.extractArchiveFromFile(fakeRar, File(tempRoot, "out1"))
            }.exceptionOrNull()
            assertNotNull(rarErr)
            assertTrue(rarErr!!.message!!.contains("RAR (.cbr)", ignoreCase = true))

            val fakePdf = File(tempRoot, "doc.cbz").apply {
                writeBytes("%PDF-1.7\n...".toByteArray() + ByteArray(32))
            }
            val pdfErr = runCatching {
                MangaCbzExtractorUtils.extractArchiveFromFile(fakePdf, File(tempRoot, "out2"))
            }.exceptionOrNull()
            assertNotNull(pdfErr)
            assertTrue(pdfErr!!.message!!.contains(".pdf", ignoreCase = true))
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `vision model catalog exposes 6 models, drops AUTO, and migrates legacy AUTO safely`() {
        assertEquals(6, MangaVisionModelTier.entries.size)
        assertEquals("sub_gemma4_e2b_vision", MangaVisionModelTier.GEMMA4_E2B.specId)
        assertEquals("sub_gemma4_e4b_vision", MangaVisionModelTier.GEMMA4_E4B.specId)
        assertEquals("sub_qwen35_vl_2b_vision", MangaVisionModelTier.QWEN35_VL_2B.specId)
        assertEquals("sub_lfm25_vl_3b_vision", MangaVisionModelTier.LFM25_VL_3B.specId)
        assertEquals("sub_qwen35_vl_08b_vision", MangaVisionModelTier.QWEN35_VL_08B.specId)
        assertEquals("sub_internvl3_1b_vision", MangaVisionModelTier.INTERNVL3_1B.specId)

        assertEquals(MangaVisionModelTier.GEMMA4_E2B, MangaVisionModelTier.fromNameOrDefault("AUTO"))
        assertEquals(MangaVisionModelTier.GEMMA4_E2B, MangaVisionModelTier.fromNameOrDefault(null))
        assertEquals(MangaVisionModelTier.GEMMA4_E4B, MangaVisionModelTier.fromNameOrDefault("GEMMA4_E4B"))
        assertEquals(MangaVisionModelTier.QWEN35_VL_2B, MangaVisionModelTier.fromNameOrDefault("QWEN35_VL_2B"))

        val legacyJson = """
            {
              "id": "p1",
              "seriesTitle": "Legacy Series",
              "createdAtEpochMs": 1000,
              "updatedAtEpochMs": 2000
            }
        """.trimIndent()
        val parser = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val decoded = parser.decodeFromString<MangaSeriesProject>(legacyJson)
        assertEquals(MangaVisionModelTier.GEMMA4_E2B, decoded.preferredVisionModel)

        val encoded = parser.encodeToString(decoded.copy(preferredVisionModel = MangaVisionModelTier.LFM25_VL_3B))
        assertEquals(MangaVisionModelTier.LFM25_VL_3B, parser.decodeFromString<MangaSeriesProject>(encoded).preferredVisionModel)
    }

    @Test
    fun `telemetry eta calculator and formatter produce accurate remaining estimates`() {
        assertNull(MangaTelemetryUtils.estimateRemainingSec(500L, 0.5f))
        assertNull(MangaTelemetryUtils.estimateRemainingSec(5000L, 0.01f))
        assertNull(MangaTelemetryUtils.estimateRemainingSec(10_000L, 1.0f))

        // At 10s elapsed and 25% complete -> ~40s total -> ~30s remaining
        val eta = MangaTelemetryUtils.estimateRemainingSec(10_000L, 0.25f)
        assertEquals(30, eta)
        assertEquals("1m 05s", MangaTelemetryUtils.formatDurationShort(65))
        assertEquals("9s", MangaTelemetryUtils.formatDurationShort(9))
    }

    @Test
    fun `parser accepts 1-based panel aliases, trailing commas, and non-blocking confidence above 55 percent`() {
        val raw = """
            <thought>Panel P1 shows Akira speaking; Panel P2 has Aria responding gently.</thought>
            [
              {
                "panel": "P1",
                "mood": "action",
                "lines": [
                  {
                    "character": "AKIRA",
                    "confidence": 0.65,
                    "dialogue": "Hold the gate!",
                  },
                ],
              },
              {
                "panel": "P2",
                "mood": "calm",
                "lines": [
                  {
                    "character": "ARIA",
                    "confidence": 0.45,
                    "speech": "Right behind you.",
                  }
                ]
              }
            ]
        """.trimIndent()

        val parsed = MangaVisionResponseParser.parsePageAnalysis(
            rawResponse = raw,
            panelCount = 2,
            existingCharacters = emptyList()
        )
        assertEquals(2, parsed.panels.size)
        assertEquals(0, parsed.panels[0].panelIndex)
        assertEquals(1, parsed.panels[1].panelIndex)

        val l1 = parsed.panels[0].lines.single()
        assertEquals("AKIRA", l1.speakerName)
        assertEquals("Hold the gate!", l1.rawText)
        assertFalse(l1.needsUserDecision)

        val l2 = parsed.panels[1].lines.single()
        assertEquals("ARIA", l2.speakerName)
        assertTrue(l2.needsUserDecision)
    }

    @Test
    fun `voice design roster parser updates qwen3 prompts speed and pitch while preserving assigned clone`() {
        val initialRoster = listOf(
            MangaCharacterProfile(
                id = "c_veg",
                name = "VEGETA_X",
                gender = VoiceGender.MALE,
                assignedVoiceId = "clone_veg_custom",
                assignedVoiceLabel = "Vegeta Clone"
            ),
            MangaCharacterProfile(
                id = "c_aria",
                name = "ARIA",
                gender = VoiceGender.FEMALE
            )
        )
        val rawCasting = """
            ```json
            {
              "characters": [
                {
                  "name": "VEGETA_X",
                  "gender": "male",
                  "visual_description": "Proud armored Saiyan warrior",
                  "voice_design_prompt": "Gravelly medium-low warrior baritone with fierce explosive projection",
                  "speed_scale": 1.12,
                  "pitch_scale": 0.94,
                },
                {
                  "name": "ARIA",
                  "gender": "female",
                  "visual_description": "Calm navigator with glowing visor",
                  "voice_design_prompt": "Crisp bright young soprano with calm steady tactical cadence",
                  "speed_scale": 1.0,
                  "pitch_scale": 1.04
                }
              ]
            }
            ```
        """.trimIndent()

        val updated = MangaVisionResponseParser.parseVoiceDesignRoster(rawCasting, initialRoster)
        assertEquals(2, updated.size)
        assertEquals("clone_veg_custom", updated[0].assignedVoiceId)
        assertTrue(updated[0].voiceDesignPrompt.contains("warrior baritone"))
        assertEquals(1.12f, updated[0].speedScale, 0.001f)
        assertEquals(0.94f, updated[0].pitchScale, 0.001f)

        assertTrue(updated[1].voiceDesignPrompt.contains("soprano"))
        assertEquals(VoiceGender.FEMALE, updated[1].gender)
        assertEquals(1.04f, updated[1].pitchScale, 0.001f)
    }

    @Test
    fun `project defaults use portrait 9_16 aspect, subtitles on, and 720p quality while resolving dimensions accurately`() {
        val defaultProj = MangaSeriesProject(
            id = "proj_def",
            seriesTitle = "Portrait Test",
            createdAtEpochMs = 1000L,
            updatedAtEpochMs = 2000L
        )
        assertEquals(MangaVideoAspect.VERTICAL_9_16, defaultProj.videoAspect)
        assertTrue(defaultProj.burnInSubtitles)
        assertEquals(MangaVideoQuality.HD_720P, defaultProj.videoQuality)

        // Legacy project JSON without aspect/quality fields falls back to Portrait + 720p defaults
        val parser = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val legacy = parser.decodeFromString<MangaSeriesProject>(
            """{"id":"old","seriesTitle":"Old","createdAtEpochMs":1,"updatedAtEpochMs":2}"""
        )
        assertEquals(MangaVideoAspect.VERTICAL_9_16, legacy.videoAspect)
        assertTrue(legacy.burnInSubtitles)
        assertEquals(MangaVideoQuality.HD_720P, legacy.videoQuality)

        assertEquals(480 to 848, MangaVideoAspect.VERTICAL_9_16.resolveDimensions(MangaVideoQuality.SD_480P))
        assertEquals(720 to 1280, MangaVideoAspect.VERTICAL_9_16.resolveDimensions(MangaVideoQuality.HD_720P))
        assertEquals(1080 to 1920, MangaVideoAspect.VERTICAL_9_16.resolveDimensions(MangaVideoQuality.FHD_1080P))

        assertEquals(848 to 480, MangaVideoAspect.LANDSCAPE_16_9.resolveDimensions(MangaVideoQuality.SD_480P))
        assertEquals(1280 to 720, MangaVideoAspect.LANDSCAPE_16_9.resolveDimensions(MangaVideoQuality.HD_720P))
        assertEquals(1920 to 1080, MangaVideoAspect.LANDSCAPE_16_9.resolveDimensions(MangaVideoQuality.FHD_1080P))
    }

    @Test
    fun `deterministic effects engine selects repeatable camera effects from panel geometry and context`() {
        val shoutPanel = MangaPanelNode(
            id = "p_shout",
            pageIndex = 0,
            panelIndex = 0,
            panelRect = MangaNormalizedRect(0.05f, 0.05f, 0.95f, 0.45f),
            cameraStartRect = MangaNormalizedRect(0.05f, 0.05f, 0.95f, 0.45f),
            cameraEndRect = MangaNormalizedRect(0.05f, 0.05f, 0.95f, 0.45f),
            sceneMood = "intense clash",
            lines = listOf(
                MangaSpeechLine(
                    id = "l1",
                    bubbleIndex = 1,
                    speakerId = "c1",
                    speakerName = "VEGETA_X",
                    rawText = "OUT OF MY WAY!!",
                    sanitizedText = "Out of my way!",
                    emotionTag = "anger shout"
                )
            )
        )
        assertEquals(
            MangaPanelCameraEffect.IMPACT_SHAKE_ZOOM,
            MangaDeterministicEffectsEngine.resolveCameraEffect(shoutPanel, 2, MangaVideoAspect.VERTICAL_9_16)
        )
        val impactState = MangaDeterministicEffectsEngine.computeViewportState(
            panel = shoutPanel,
            effect = MangaPanelCameraEffect.IMPACT_SHAKE_ZOOM,
            progress = 0.04f,
            readingOrder = MangaReadingOrder.RTL_MANGA,
            videoAspect = MangaVideoAspect.VERTICAL_9_16,
            stageAspect = 0.65f
        )
        assertTrue(impactState.showSpeedLines)
        assertTrue(impactState.impactFlashAlpha > 0f)
        assertTrue(impactState.shakeOffsetXFrac != 0f || impactState.shakeOffsetYFrac != 0f)

        val wideCalmPanel = MangaPanelNode(
            id = "p_wide",
            pageIndex = 0,
            panelIndex = 1,
            panelRect = MangaNormalizedRect(0.04f, 0.10f, 0.96f, 0.40f),
            cameraStartRect = MangaNormalizedRect(0.04f, 0.10f, 0.96f, 0.40f),
            cameraEndRect = MangaNormalizedRect(0.04f, 0.10f, 0.96f, 0.40f),
            sceneMood = "calm street",
            lines = listOf(
                MangaSpeechLine(
                    id = "l2",
                    bubbleIndex = 1,
                    speakerId = "c2",
                    speakerName = "KAEL",
                    rawText = "Wait a moment.",
                    sanitizedText = "Wait a moment.",
                    emotionTag = "calm"
                )
            )
        )
        assertEquals(
            MangaPanelCameraEffect.READING_AXIS_PAN,
            MangaDeterministicEffectsEngine.resolveCameraEffect(wideCalmPanel, 3, MangaVideoAspect.VERTICAL_9_16)
        )

        val compactPanel = wideCalmPanel.copy(
            id = "p_small",
            panelRect = MangaNormalizedRect(0.10f, 0.10f, 0.45f, 0.45f)
        )
        assertEquals(
            MangaPanelCameraEffect.READABILITY_HOLD,
            MangaDeterministicEffectsEngine.resolveCameraEffect(compactPanel, 4, MangaVideoAspect.VERTICAL_9_16)
        )

        val splashPanel = wideCalmPanel.copy(
            id = "p_splash",
            panelRect = MangaNormalizedRect(0.02f, 0.02f, 0.98f, 0.98f)
        )
        val firstSplash = MangaDeterministicEffectsEngine.resolveCameraEffect(splashPanel, 1, MangaVideoAspect.VERTICAL_9_16)
        val secondSplash = MangaDeterministicEffectsEngine.resolveCameraEffect(splashPanel, 1, MangaVideoAspect.VERTICAL_9_16)
        assertEquals(firstSplash, secondSplash)
        assertTrue(
            firstSplash == MangaPanelCameraEffect.REVEAL_PULL_BACK ||
                firstSplash == MangaPanelCameraEffect.DRAMATIC_PUSH_IN
        )
    }

    @Test
    fun `deterministic reading axis pan sweeps wide panels right-to-left for manga and left-to-right for comic`() {
        val widePanel = MangaPanelNode(
            id = "pan_sweep",
            pageIndex = 0,
            panelIndex = 0,
            panelRect = MangaNormalizedRect(0.04f, 0.10f, 0.96f, 0.40f),
            cameraStartRect = MangaNormalizedRect(0.04f, 0.10f, 0.96f, 0.40f),
            cameraEndRect = MangaNormalizedRect(0.04f, 0.10f, 0.96f, 0.40f)
        )

        val rtlStart = MangaDeterministicEffectsEngine.computeViewportState(
            panel = widePanel,
            effect = MangaPanelCameraEffect.READING_AXIS_PAN,
            progress = 0.0f,
            readingOrder = MangaReadingOrder.RTL_MANGA,
            videoAspect = MangaVideoAspect.VERTICAL_9_16,
            stageAspect = 0.65f
        )
        val rtlHold = MangaDeterministicEffectsEngine.computeViewportState(
            panel = widePanel,
            effect = MangaPanelCameraEffect.READING_AXIS_PAN,
            progress = 0.10f,
            readingOrder = MangaReadingOrder.RTL_MANGA,
            videoAspect = MangaVideoAspect.VERTICAL_9_16,
            stageAspect = 0.65f
        )
        val rtlEnd = MangaDeterministicEffectsEngine.computeViewportState(
            panel = widePanel,
            effect = MangaPanelCameraEffect.READING_AXIS_PAN,
            progress = 1.0f,
            readingOrder = MangaReadingOrder.RTL_MANGA,
            videoAspect = MangaVideoAspect.VERTICAL_9_16,
            stageAspect = 0.65f
        )

        // Full vertical height stays unclipped; entry plateau holds at initial framing; sweep goes Right -> Left
        assertEquals(0.10f, rtlStart.sourceCropRect.top, 0.001f)
        assertEquals(0.40f, rtlStart.sourceCropRect.bottom, 0.001f)
        assertEquals(rtlStart.sourceCropRect.left, rtlHold.sourceCropRect.left, 0.0001f)
        assertTrue(rtlStart.sourceCropRect.centerX > rtlEnd.sourceCropRect.centerX)

        val ltrStart = MangaDeterministicEffectsEngine.computeViewportState(
            panel = widePanel,
            effect = MangaPanelCameraEffect.READING_AXIS_PAN,
            progress = 0.0f,
            readingOrder = MangaReadingOrder.LTR_COMIC,
            videoAspect = MangaVideoAspect.VERTICAL_9_16,
            stageAspect = 0.65f
        )
        val ltrEnd = MangaDeterministicEffectsEngine.computeViewportState(
            panel = widePanel,
            effect = MangaPanelCameraEffect.READING_AXIS_PAN,
            progress = 1.0f,
            readingOrder = MangaReadingOrder.LTR_COMIC,
            videoAspect = MangaVideoAspect.VERTICAL_9_16,
            stageAspect = 0.65f
        )
        assertTrue(ltrStart.sourceCropRect.centerX < ltrEnd.sourceCropRect.centerX)
    }

    @Test
    fun `reading order defaults to RTL_MANGA with clear user hints and vlm prompt directives`() {
        val defProj = MangaSeriesProject(
            id = "p_dir",
            seriesTitle = "Dir Test",
            createdAtEpochMs = 1L,
            updatedAtEpochMs = 2L
        )
        assertEquals(MangaReadingOrder.RTL_MANGA, defProj.readingOrder)
        assertTrue(MangaReadingOrder.RTL_MANGA.shortLabel.contains("R -> L"))
        assertTrue(MangaReadingOrder.RTL_MANGA.instructionHint.contains("RIGHT -> LEFT"))
        assertTrue(MangaReadingOrder.RTL_MANGA.vlmPromptDirective.contains("RIGHT-TO-LEFT"))
        assertTrue(MangaReadingOrder.LTR_COMIC.vlmPromptDirective.contains("LEFT-TO-RIGHT"))
        assertTrue(MangaReadingOrder.WEBTOON_VERTICAL.vlmPromptDirective.contains("TOP-TO-BOTTOM"))
    }

    @Test
    fun `parseSinglePanelAnalysis locks exact panel index and extracts multi-bubble dialogue`() {
        val rawSingle = """
            <thought>Right bubble belongs to KAEL, left bubble belongs to VEGETA_X.</thought>
            ```json
            {
              "mood": "tense standoff",
              "lines": [
                {
                  "bubble": 1,
                  "speaker": "KAEL",
                  "gender": "male",
                  "emotion": "calm",
                  "confidence": 0.92,
                  "text": "WAIT RIGHT THERE!"
                },
                {
                  "bubble": 2,
                  "speaker": "VEGETA_X",
                  "gender": "male",
                  "emotion": "anger",
                  "confidence": 0.95,
                  "text": "STEP ASIDE!"
                }
              ]
            }
            ```
        """.trimIndent()

        val spec = MangaVisionResponseParser.parseSinglePanelAnalysis(
            rawResponse = rawSingle,
            panelIndex = 3,
            existingCharacters = emptyList()
        )
        assertEquals(3, spec.panelIndex)
        assertEquals("tense standoff", spec.sceneMood)
        assertEquals(2, spec.lines.size)
        assertEquals("KAEL", spec.lines[0].speakerName)
        assertEquals("WAIT RIGHT THERE!", spec.lines[0].rawText)
        assertEquals("VEGETA_X", spec.lines[1].speakerName)
        assertEquals("STEP ASIDE!", spec.lines[1].rawText)

        // Direct JSON array of lines also binds cleanly to locked panelIndex
        val rawArr = """[{"speaker":"ARIA","confidence":0.89,"text":"Watch out!"}]"""
        val arrSpec = MangaVisionResponseParser.parseSinglePanelAnalysis(rawArr, panelIndex = 5, existingCharacters = emptyList())
        assertEquals(5, arrSpec.panelIndex)
        assertEquals(1, arrSpec.lines.size)
        assertEquals("ARIA", arrSpec.lines.single().speakerName)
    }

    @Test
    fun `bfs slicer topological order and composite splitter handle staggered columns and internal gutters`() {
        // Staggered layout: tall right panel + two stacked left panels (should read RightTall -> LeftTop -> LeftBot in RTL)
        val leftTop = MangaNormalizedRect(0.05f, 0.05f, 0.45f, 0.45f)
        val leftBot = MangaNormalizedRect(0.05f, 0.50f, 0.45f, 0.90f)
        val rightTall = MangaNormalizedRect(0.50f, 0.05f, 0.95f, 0.90f)

        val rtlOrdered = MangaBfsPanelSlicer.orderPanelsForReadingDirection(
            boxes = listOf(leftBot, rightTall, leftTop),
            readingOrder = MangaReadingOrder.RTL_MANGA
        )
        assertEquals(listOf(rightTall, leftTop, leftBot), rtlOrdered)

        // Mirrored LTR layout: tall left panel + two stacked right panels (LeftTall -> RightTop -> RightBot)
        val leftTall = MangaNormalizedRect(0.05f, 0.05f, 0.45f, 0.90f)
        val rightTop = MangaNormalizedRect(0.50f, 0.05f, 0.95f, 0.45f)
        val rightBot = MangaNormalizedRect(0.50f, 0.50f, 0.95f, 0.90f)
        val ltrOrdered = MangaBfsPanelSlicer.orderPanelsForReadingDirection(
            boxes = listOf(rightBot, leftTall, rightTop),
            readingOrder = MangaReadingOrder.LTR_COMIC
        )
        assertEquals(listOf(leftTall, rightTop, rightBot), ltrOrdered)

        // Composite splitter separates a single merged full-page box along an internal white horizontal gutter
        val w = 100
        val h = 100
        val lum = IntArray(w * h) { 40 }
        // Paint a 4px white horizontal gutter band around y = 48..51
        for (y in 48..51) {
            for (x in 0 until w) {
                lum[y * w + x] = 250
            }
        }
        val mergedBox = MangaNormalizedRect(0.04f, 0.04f, 0.96f, 0.96f)
        val split = MangaBfsPanelSlicer.splitCompositePanelBoxes(
            boxes = listOf(mergedBox),
            luminance = lum,
            gutterMed = 250,
            tolerance = 34,
            width = w,
            height = h
        )
        assertEquals(2, split.size)
        assertTrue(split[0].bottom <= 0.50f)
        assertTrue(split[1].top >= 0.50f)
    }

    @Test
    fun `vertical aspect applies calmer zoom and pan motion scale than landscape and tightens wide pan trigger`() {
        val defaultProj = MangaSeriesProject(
            id = "p_motion",
            seriesTitle = "Motion Scale Test",
            createdAtEpochMs = 1L,
            updatedAtEpochMs = 2L
        )
        assertFalse(defaultProj.includeNonBubbleText)

        // Moderately wide box (1.67 aspect, width 0.60) should NOT trigger READING_AXIS_PAN in Vertical 9:16
        val moderatePanel = MangaPanelNode(
            id = "p_mod",
            pageIndex = 0,
            panelIndex = 0,
            panelRect = MangaNormalizedRect(0.10f, 0.10f, 0.70f, 0.46f),
            cameraStartRect = MangaNormalizedRect(0.10f, 0.10f, 0.70f, 0.46f),
            cameraEndRect = MangaNormalizedRect(0.10f, 0.10f, 0.70f, 0.46f)
        )
        val modEffect = MangaDeterministicEffectsEngine.resolveCameraEffect(
            moderatePanel,
            totalPanelsOnPage = 3,
            videoAspect = MangaVideoAspect.VERTICAL_9_16
        )
        assertTrue(modEffect != MangaPanelCameraEffect.READING_AXIS_PAN)

        val wideStrip = MangaPanelNode(
            id = "p_strip",
            pageIndex = 0,
            panelIndex = 0,
            panelRect = MangaNormalizedRect(0.04f, 0.10f, 0.96f, 0.40f),
            cameraStartRect = MangaNormalizedRect(0.04f, 0.10f, 0.96f, 0.40f),
            cameraEndRect = MangaNormalizedRect(0.04f, 0.10f, 0.96f, 0.40f)
        )
        val portraitPush = MangaDeterministicEffectsEngine.computeViewportState(
            panel = wideStrip,
            effect = MangaPanelCameraEffect.DRAMATIC_PUSH_IN,
            progress = 1.0f,
            readingOrder = MangaReadingOrder.RTL_MANGA,
            videoAspect = MangaVideoAspect.VERTICAL_9_16,
            stageAspect = 0.5625f
        )
        val landscapePush = MangaDeterministicEffectsEngine.computeViewportState(
            panel = wideStrip,
            effect = MangaPanelCameraEffect.DRAMATIC_PUSH_IN,
            progress = 1.0f,
            readingOrder = MangaReadingOrder.RTL_MANGA,
            videoAspect = MangaVideoAspect.LANDSCAPE_16_9,
            stageAspect = 1.7778f
        )
        assertTrue(portraitPush.zoomScale < landscapePush.zoomScale)
        assertTrue(portraitPush.zoomScale <= 1.02f)

        val portraitPan = MangaDeterministicEffectsEngine.computeViewportState(
            panel = wideStrip,
            effect = MangaPanelCameraEffect.READING_AXIS_PAN,
            progress = 0.5f,
            readingOrder = MangaReadingOrder.RTL_MANGA,
            videoAspect = MangaVideoAspect.VERTICAL_9_16,
            stageAspect = 0.5625f
        )
        val landscapePan = MangaDeterministicEffectsEngine.computeViewportState(
            panel = wideStrip,
            effect = MangaPanelCameraEffect.READING_AXIS_PAN,
            progress = 0.5f,
            readingOrder = MangaReadingOrder.RTL_MANGA,
            videoAspect = MangaVideoAspect.LANDSCAPE_16_9,
            stageAspect = 0.65f
        )
        assertTrue(portraitPan.zoomScale < landscapePan.zoomScale)
        assertTrue(portraitPan.zoomScale <= 1.16f)
    }

    @Test
    fun `non-bubble SFX credits and signatures are filtered by default and preserved when experimental toggle enabled`() {
        assertTrue(MangaTextBender.isNonBubbleOrCreditNoise("SFX", "Shaaa"))
        assertTrue(MangaTextBender.isNonBubbleOrCreditNoise("UNKNOWN", "GULP"))
        assertTrue(MangaTextBender.isNonBubbleOrCreditNoise("UNKNOWN", "BWO DO"))
        assertTrue(MangaTextBender.isNonBubbleOrCreditNoise("NARRATOR", "Scanlation by: JasmineTea, Dead-chan"))
        assertTrue(MangaTextBender.isNonBubbleOrCreditNoise("UNKNOWN", "karateさんへ"))
        assertTrue(MangaTextBender.isNonBubbleOrCreditNoise("PROTAGONIST", "[SFX] CRASH!"))

        assertFalse(MangaTextBender.isNonBubbleOrCreditNoise("SEBASTIAN", "One."))
        assertFalse(MangaTextBender.isNonBubbleOrCreditNoise("SEBASTIAN", "In two."))
        assertFalse(MangaTextBender.isNonBubbleOrCreditNoise("OJOU-SAMA", "Huh? Why?"))
        assertFalse(MangaTextBender.isNonBubbleOrCreditNoise("OJOU-SAMA", "A week?!"))
        assertFalse(MangaTextBender.isNonBubbleOrCreditNoise("SEBASTIAN", "So you did drink them all. You're crazy."))

        // ✨ v1.9.7 TRUSTED-SPEAKER RESCUE: roster characters keep short emotional dialogue
        assertFalse(MangaTextBender.isNonBubbleOrCreditNoise("SEBASTIAN", "Sob...", trustedSpeaker = true))
        assertFalse(MangaTextBender.isNonBubbleOrCreditNoise("OJOU-SAMA", "Hiss!", trustedSpeaker = true))
        // Elongated onomatopoeia and UNKNOWN speakers still drop exactly as before
        assertTrue(MangaTextBender.isNonBubbleOrCreditNoise("SEBASTIAN", "Shaaa", trustedSpeaker = true))
        assertTrue(MangaTextBender.isNonBubbleOrCreditNoise("UNKNOWN", "Sob..."))

        val mixedPayload = """
            {
              "mood": "comedy panic",
              "lines": [
                {"bubble": 1, "is_speech_bubble": false, "speaker": "UNKNOWN", "text": "OH-HO HO HO"},
                {"bubble": 2, "is_speech_bubble": true, "speaker": "SFX", "text": "BWO DO"},
                {"bubble": 3, "is_speech_bubble": true, "speaker": "NARRATOR", "text": "Scanlation by: JasmineTea, Dead-chan"},
                {"bubble": 4, "is_speech_bubble": true, "speaker": "UNKNOWN", "text": "karateさんへ"},
                {"bubble": 5, "is_speech_bubble": true, "speaker": "SEBASTIAN", "gender": "male", "confidence": 0.94, "text": "The delayed-onset test items have all been emptied out, huh."}
              ]
            }
        """.trimIndent()

        val defaultFiltered = MangaVisionResponseParser.parseSinglePanelAnalysis(
            rawResponse = mixedPayload,
            panelIndex = 2,
            existingCharacters = emptyList(),
            includeNonBubbleText = false
        )
        assertEquals(1, defaultFiltered.lines.size)
        assertEquals("SEBASTIAN", defaultFiltered.lines.single().speakerName)
        assertEquals(
            "The delayed-onset test items have all been emptied out, huh.",
            defaultFiltered.lines.single().rawText
        )

        val experimentalAll = MangaVisionResponseParser.parseSinglePanelAnalysis(
            rawResponse = mixedPayload,
            panelIndex = 2,
            existingCharacters = emptyList(),
            includeNonBubbleText = true
        )
        assertEquals(5, experimentalAll.lines.size)
    }

    @Test
    fun `parseStoryboardPass extracts canonical cast and page synopses across multi-page sheet`() {
        val storyboardJson = """
            <thought>Page 1 towel girl is the same character named OJOU-SAMA on Page 2; rabbit butler is SEBASTIAN.</thought>
            ```json
            {
              "story_summary": "Ojou-sama steps out of the bath, chugs delayed-onset test potions, and suddenly grows gigantic while Sebastian counts down.",
              "characters": [
                {
                  "name": "ojou-sama",
                  "gender": "female",
                  "visual_description": "Young woman wearing a bath towel on Page 1 and dark skirt suit on Page 2",
                  "voice_design_prompt": "Haughty playful anime ojou-sama soprano, cheerful laugh, sharp startled shriek"
                },
                {
                  "name": "SEBASTIAN",
                  "gender": "male",
                  "visual_description": "White rabbit butler in lab coat and tie with spectacles",
                  "voice_design_prompt": "Composed deadpan English-style butler baritone, dry understated delivery"
                }
              ],
              "page_synopses": [
                {"page": 1, "synopsis": "Ojou-sama exits the bath and drinks all test bottles before Sebastian notices."},
                {"page": 2, "synopsis": "Sebastian counts to three and Ojou-sama balloons into a giant."}
              ]
            }
            ```
        """.trimIndent()

        val bible = MangaVisionResponseParser.parseStoryboardPass(storyboardJson)
        assertNotNull(bible)
        assertTrue(bible!!.storySummary.contains("Ojou-sama"))
        assertEquals(2, bible.characters.size)
        assertEquals("OJOU-SAMA", bible.characters[0].name)
        assertEquals(VoiceGender.FEMALE, bible.characters[0].gender)
        assertEquals("SEBASTIAN", bible.characters[1].name)
        assertEquals(VoiceGender.MALE, bible.characters[1].gender)
        assertEquals(2, bible.pageSynopses.size)
        assertTrue(bible.pageSynopses[0].orEmpty().contains("exits the bath"))
        assertTrue(bible.pageSynopses[1].orEmpty().contains("giant"))
    }

    @Test
    fun `splitCompositePanelBoxes recursively splits multi-level dense stacked panels`() {
        val w = 120
        val h = 120
        val lum = IntArray(w * h) { 40 }
        // Vertical gutter splitting left half from tall right splash panel (x = 58..61)
        for (y in 0 until h) {
            for (x in 58..61) {
                lum[y * w + x] = 248
            }
        }
        // Two horizontal gutters inside left half splitting 3 stacked panels (y = 38..40, y = 78..80)
        for (x in 0..57) {
            for (y in 38..40) lum[y * w + x] = 248
            for (y in 78..80) lum[y * w + x] = 248
        }
        val mergedBox = MangaNormalizedRect(0.02f, 0.02f, 0.98f, 0.98f)
        val split = MangaBfsPanelSlicer.splitCompositePanelBoxes(
            boxes = listOf(mergedBox),
            luminance = lum,
            gutterMed = 248,
            tolerance = 28,
            width = w,
            height = h
        )
        assertEquals(4, split.size)
    }

    @Test
    fun `border-verified xy-cut slicer avoids bubble over-segmentation caps at 5 panels and preserves RTL guillotine order`() {
        val w = 120
        val h = 160
        // White paper backdrop
        val lum = IntArray(w * h) { 252 }

        fun drawFramedPanel(x0: Int, y0: Int, x1: Int, y1: Int, interiorLum: Int = 210) {
            for (y in y0..y1) {
                for (x in x0..x1) {
                    val onBorder = (x == x0 || x == x1 || y == y0 || y == y1)
                    lum[y * w + x] = if (onBorder) 20 else interiorLum
                }
            }
            // Place a bright white interior speech bubble inside the panel so old bubble-fragmenting splitters would choke
            val bx0 = x0 + (x1 - x0) / 4
            val bx1 = x1 - (x1 - x0) / 4
            val by0 = y0 + (y1 - y0) / 5
            val by1 = y0 + (y1 - y0) * 2 / 4
            for (y in by0..by1) {
                for (x in bx0..bx1) {
                    lum[y * w + x] = 250
                }
            }
        }

        // Dense manga layout: Tall Right panel + 3 Stacked Left panels (Page 1 layout)
        drawFramedPanel(64, 4, 115, 155, interiorLum = 242)
        drawFramedPanel(4, 4, 56, 50, interiorLum = 180)
        drawFramedPanel(4, 56, 56, 102, interiorLum = 180)
        drawFramedPanel(4, 108, 56, 155, interiorLum = 180)

        val rtlPanels = MangaBfsPanelSlicer.slicePanelsFromLuminanceGrid(
            luminance = lum,
            width = w,
            height = h,
            readingOrder = MangaReadingOrder.RTL_MANGA
        )
        assertTrue(rtlPanels.size <= MangaBfsPanelSlicer.MAX_PANELS_PER_PAGE)
        assertEquals(4, rtlPanels.size)
        // Must start on tall right panel before descending through LeftTop -> LeftMid -> LeftBot
        assertTrue(rtlPanels[0].centerX > 0.5f && rtlPanels[0].height > 0.80f)
        assertTrue(rtlPanels[1].centerX < 0.5f && rtlPanels[1].centerY < 0.35f)
        assertTrue(rtlPanels[2].centerX < 0.5f && rtlPanels[2].centerY in 0.35f..0.66f)
        assertTrue(rtlPanels[3].centerX < 0.5f && rtlPanels[3].centerY > 0.66f)
    }

    @Test
    fun `expandViewportForPageContext widens tight panel crops to preserve surrounding page context`() {
        val tightBox = MangaNormalizedRect(0.05f, 0.10f, 0.28f, 0.30f)
        val portraitExpanded = MangaDeterministicEffectsEngine.expandViewportForPageContext(
            tightBox,
            MangaVideoAspect.VERTICAL_9_16
        )
        assertTrue(portraitExpanded.width >= 0.679f)
        assertTrue(portraitExpanded.height >= 0.559f)
        assertTrue(portraitExpanded.left <= tightBox.left)
        assertTrue(portraitExpanded.top <= tightBox.top)

        val landscapeExpanded = MangaDeterministicEffectsEngine.expandViewportForPageContext(
            tightBox,
            MangaVideoAspect.LANDSCAPE_16_9
        )
        assertTrue(landscapeExpanded.width >= 0.619f)
        assertTrue(landscapeExpanded.height >= 0.499f)
    }

    @Test
    fun `parseSinglePanelAnalysis deterministically sorts bubbles by RTL and LTR using x_pos coordinates`() {
        val backwardsRasterJson = """
            {
              "mood": "comedy panic",
              "lines": [
                {
                  "bubble": 1,
                  "x_pos": 0.18,
                  "y_pos": 0.25,
                  "is_speech_bubble": true,
                  "speaker": "OJOU-SAMA",
                  "confidence": 0.92,
                  "text": "Ooh, what nice drinks do we have here?"
                },
                {
                  "bubble": 2,
                  "x_pos": 0.84,
                  "y_pos": 0.22,
                  "is_speech_bubble": true,
                  "speaker": "OJOU-SAMA",
                  "confidence": 0.94,
                  "text": "But wow, am I thirsty."
                }
              ]
            }
        """.trimIndent()

        val rtlSpec = MangaVisionResponseParser.parseSinglePanelAnalysis(
            rawResponse = backwardsRasterJson,
            panelIndex = 0,
            existingCharacters = emptyList(),
            readingOrder = MangaReadingOrder.RTL_MANGA
        )
        assertEquals(2, rtlSpec.lines.size)
        assertEquals("But wow, am I thirsty.", rtlSpec.lines[0].rawText)
        assertEquals(1, rtlSpec.lines[0].bubbleIndex)
        assertEquals("Ooh, what nice drinks do we have here?", rtlSpec.lines[1].rawText)
        assertEquals(2, rtlSpec.lines[1].bubbleIndex)

        val ltrSpec = MangaVisionResponseParser.parseSinglePanelAnalysis(
            rawResponse = backwardsRasterJson,
            panelIndex = 0,
            existingCharacters = emptyList(),
            readingOrder = MangaReadingOrder.LTR_COMIC
        )
        assertEquals("Ooh, what nice drinks do we have here?", ltrSpec.lines[0].rawText)
        assertEquals("But wow, am I thirsty.", ltrSpec.lines[1].rawText)
    }

    @Test
    fun `bfs panel slicer reads staggered 2x2 RTL page row-first Z-order without column dropping`() {
        val w = 120
        val h = 160
        val lum = IntArray(w * h) { 250 }

        fun drawPanel(x1: Int, y1: Int, x2: Int, y2: Int) {
            for (y in y1 until y2) {
                for (x in x1 until x2) {
                    val isBorder = x == x1 || x == x2 - 1 || y == y1 || y == y2 - 1
                    lum[y * w + x] = if (isBorder) 15 else 180
                }
            }
        }

        // Staggered 2x2 grid sharing a center vertical gutter, with slightly offset horizontal row boundaries
        drawPanel(4, 4, 56, 74)     // Top-Left
        drawPanel(62, 6, 115, 80)   // Top-Right
        drawPanel(4, 78, 56, 155)   // Bottom-Left
        drawPanel(62, 84, 115, 155) // Bottom-Right

        val rtl = MangaBfsPanelSlicer.slicePanelsFromLuminanceGrid(
            luminance = lum,
            width = w,
            height = h,
            readingOrder = MangaReadingOrder.RTL_MANGA
        )
        assertEquals(4, rtl.size)
        // Row-first Z-order: Top-Right -> Top-Left -> Bottom-Right -> Bottom-Left
        assertTrue(rtl[0].centerX > 0.5f && rtl[0].centerY < 0.5f)
        assertTrue(rtl[1].centerX < 0.5f && rtl[1].centerY < 0.5f)
        assertTrue(rtl[2].centerX > 0.5f && rtl[2].centerY > 0.5f)
        assertTrue(rtl[3].centerX < 0.5f && rtl[3].centerY > 0.5f)
    }

    @Test
    fun `parser enforces container_type non-bubble exclusion, tightened band Z-order, and tail ambiguity`() {
        val rawPanelJson = """
            {
              "mood": "comedy",
              "lines": [
                {
                  "bubble": 1,
                  "x_pos": 0.86,
                  "y_pos": 0.42,
                  "container_type": "speech_bubble",
                  "tail_target": "SEBASTIAN",
                  "speaker": "SEBASTIAN",
                  "confidence": 0.92,
                  "text": "Lower right first?"
                },
                {
                  "bubble": 2,
                  "x_pos": 0.16,
                  "y_pos": 0.14,
                  "container_type": "speech_bubble",
                  "tail_target": "OJOU",
                  "speaker": "OJOU",
                  "confidence": 0.90,
                  "text": "Upper left earlier!"
                },
                {
                  "bubble": 3,
                  "x_pos": 0.50,
                  "y_pos": 0.92,
                  "container_type": "credit_or_signature",
                  "speaker": "OJOU",
                  "confidence": 0.95,
                  "text": "Scanlation by: JasmineTea"
                },
                {
                  "bubble": 4,
                  "x_pos": 0.45,
                  "y_pos": 0.65,
                  "container_type": "floating_sfx",
                  "speaker": "OJOU",
                  "confidence": 0.95,
                  "text": "SHAAA!"
                },
                {
                  "bubble": 5,
                  "x_pos": 0.14,
                  "y_pos": 0.72,
                  "container_type": "speech_bubble",
                  "tail_target": "unclear",
                  "speaker": "OJOU",
                  "confidence": 0.62,
                  "text": "Wait what?"
                }
              ]
            }
        """.trimIndent()

        val filtered = MangaVisionResponseParser.parseSinglePanelAnalysis(
            rawResponse = rawPanelJson,
            panelIndex = 0,
            existingCharacters = emptyList(),
            includeNonBubbleText = false,
            readingOrder = MangaReadingOrder.RTL_MANGA
        )
        assertEquals(3, filtered.lines.size)
        // Upper band (0.14) strictly precedes lower band (0.42), which precedes bottom band (0.72)
        assertEquals("Upper left earlier!", filtered.lines[0].rawText)
        assertEquals("Lower right first?", filtered.lines[1].rawText)
        assertEquals("Wait what?", filtered.lines[2].rawText)
        assertTrue(filtered.lines[2].needsUserDecision)

        val unfiltered = MangaVisionResponseParser.parseSinglePanelAnalysis(
            rawResponse = rawPanelJson,
            panelIndex = 0,
            existingCharacters = emptyList(),
            includeNonBubbleText = true,
            readingOrder = MangaReadingOrder.RTL_MANGA
        )
        assertEquals(5, unfiltered.lines.size)
    }

    @Test
    fun `voice casting parser compiles structured VoiceDesignBuilder prompts and clone lock defaults true`() {
        val defaultProj = MangaSeriesProject(
            id = "p1",
            seriesTitle = "Test Series",
            createdAtEpochMs = 1L,
            updatedAtEpochMs = 1L
        )
        assertTrue(defaultProj.lockVoicesViaCloning)
        assertFalse(defaultProj.includeNonBubbleText)

        val roster = listOf(
            MangaCharacterProfile(id = "c1", name = "SEBASTIAN", gender = VoiceGender.MALE)
        )
        val rawCasting = """
            {
              "characters": [
                {
                  "name": "SEBASTIAN",
                  "gender": "male",
                  "age": "ADULT",
                  "pitch": "LOW_BARITONE",
                  "timbres": ["WARM_VELVETY", "CRISP_CLEAR"],
                  "pace": "CALM_STEADY",
                  "delivery": "SERIOUS_AUTHORITATIVE",
                  "accent": "BRITISH_RP",
                  "acoustics": "CLOSE_MIC_STUDIO",
                  "persona_role": "Composed rabbit scientist butler",
                  "nuance": "Dry deadpan composure with silky authority.",
                  "visual_description": "Rabbit scientist in lab coat",
                  "speed_scale": 1.0,
                  "pitch_scale": 0.98
                }
              ]
            }
        """.trimIndent()
        val updated = MangaVisionResponseParser.parseVoiceDesignRoster(rawCasting, roster)
        assertEquals(1, updated.size)
        assertTrue(updated[0].voiceDesignPrompt.contains("low-pitched chest register", ignoreCase = true))
        assertTrue(updated[0].voiceDesignPrompt.contains("Rabbit scientist in lab coat", ignoreCase = true) ||
            updated[0].voiceDesignPrompt.contains("scientist butler", ignoreCase = true))
    }

    @Test
    fun `parseSinglePanelAnalysis repairs truncated kv turn json instead of dropping the panel`() {
        // v1.9.6 KV-cache panel turns emit under a tight output-token budget; a busy panel cut off
        // mid-string used to parse to ZERO lines and wipe the panel. The v1.9.7 bracket-balancing
        // rescue keeps the line (with a shortened tail) instead of dropping the whole panel.
        val truncatedValue = """{"mood":"action","lines":[{"bubble":1,"x_pos":0.8,"y_pos":0.2,"container_type":"speech_bubble","speaker":"SEBASTIAN","text":"Where did the teacups"""
        val repairedValue = MangaVisionResponseParser.parseSinglePanelAnalysis(
            rawResponse = truncatedValue,
            panelIndex = 0,
            existingCharacters = emptyList(),
            includeNonBubbleText = false
        )
        assertEquals(1, repairedValue.lines.size)
        assertEquals("SEBASTIAN", repairedValue.lines[0].speakerName)
        assertTrue(repairedValue.lines[0].rawText.startsWith("Where did the teacups"))

        // Truncation right after a completed line object keeps the earlier line via the normal
        // lastIndexOf('}') slice — the dangling partial member is simply outside the candidate.
        val truncatedKey = """{"mood":"action","lines":[{"bubble":1,"text":"Hi there."},{"speak"""
        val repairedKey = MangaVisionResponseParser.parseSinglePanelAnalysis(
            rawResponse = truncatedKey,
            panelIndex = 1,
            existingCharacters = emptyList(),
            includeNonBubbleText = false
        )
        assertEquals(1, repairedKey.lines.size)
        assertEquals("Hi there.", repairedKey.lines[0].rawText)
    }

    @Test
    fun `known speaker rescues mislabeled sfx container and short emotional dialogue`() {
        val raw = """
            {
              "mood": "action",
              "lines": [
                {"bubble": 1, "x_pos": 0.9, "y_pos": 0.2, "container_type": "floating_sfx", "speaker": "OJOU", "text": "Get back here!"},
                {"bubble": 2, "x_pos": 0.3, "y_pos": 0.5, "container_type": "speech_bubble", "speaker": "OJOU", "text": "Sob..."},
                {"bubble": 3, "x_pos": 0.5, "y_pos": 0.7, "container_type": "floating_sfx", "speaker": "UNKNOWN", "text": "Get back here!"},
                {"bubble": 4, "x_pos": 0.6, "y_pos": 0.8, "container_type": "speech_bubble", "speaker": "UNKNOWN", "text": "Sob..."}
              ]
            }
        """.trimIndent()
        val roster = listOf(
            MangaCharacterProfile(id = "c1", name = "OJOU", gender = VoiceGender.FEMALE)
        )
        val filtered = MangaVisionResponseParser.parseSinglePanelAnalysis(
            rawResponse = raw,
            panelIndex = 0,
            existingCharacters = roster,
            includeNonBubbleText = false
        )
        // Known speaker: mislabeled SFX container with real dialogue rescued (mislabel rescue);
        // short emotional line rescued via the trusted-speaker bypass. UNKNOWN speaker: the same
        // texts still drop exactly as in v1.9.6.
        assertEquals(listOf("Get back here!", "Sob..."), filtered.lines.map { it.rawText })
    }

    @Test
    fun `voice seed composer builds expressive transcript from character lines with fallback`() {
        val composed = MangaTextBender.composeVoiceSeedTranscript(
            listOf("You're late again, Sebastian!", "H-how could you eat them all?!", "", "   ")
        )
        assertTrue(composed.contains("You're late again, Sebastian!"))
        assertTrue(composed.contains("eat them all"))
        assertTrue(composed.endsWith("!"))
        assertTrue(composed.length <= 190)

        // A single over-long line is word-boundary capped instead of dropped
        val capped = MangaTextBender.composeVoiceSeedTranscript(listOf("word ".repeat(150)))
        assertTrue(capped.length <= 180)
        assertTrue(capped.startsWith("word"))

        // No usable lines -> deterministic multi-emotion fallback anchor
        assertEquals(MangaTextBender.FALLBACK_VOICE_SEED, MangaTextBender.composeVoiceSeedTranscript(emptyList()))
        assertEquals(MangaTextBender.FALLBACK_VOICE_SEED, MangaTextBender.composeVoiceSeedTranscript(listOf("", "  ")))
    }

    @Test
    fun `placeholder parrot responses are dropped instead of dubbed`() {
        // Field scar (v1.9.7): Gemma E2B answered the repair-prompt schema EXAMPLE verbatim —
        // "Dialogue text" in a male voice got dubbed as a real line. The parrot guard drops it
        // so the panel falls through to review instead of voicing garbage.
        val parrot = """{"mood":"action","lines":[{"bubble":1,"x_pos":0.5,"y_pos":0.3,"container_type":"speech_bubble","speaker":"NARRATOR","is_new_character":false,"gender":"male","emotion":"neutral","confidence":0.85,"text":"Dialogue text"}]}"""
        val parsed = MangaVisionResponseParser.parseSinglePanelAnalysis(
            rawResponse = parrot,
            panelIndex = 0,
            existingCharacters = emptyList(),
            includeNonBubbleText = false
        )
        assertEquals(0, parsed.lines.size)

        // Real dialogue with similar shape survives untouched
        val real = """{"mood":"action","lines":[{"bubble":1,"container_type":"speech_bubble","speaker":"NARRATOR","text":"Dialog text was never here. Look at this mess!"}]}"""
        val parsedReal = MangaVisionResponseParser.parseSinglePanelAnalysis(
            rawResponse = real,
            panelIndex = 0,
            existingCharacters = emptyList(),
            includeNonBubbleText = false
        )
        assertEquals(1, parsedReal.lines.size)
    }

    @Test
    fun `subtitle labels hide placeholder speakers and name mika as narrator`() {
        assertNull(MangaTextBender.subtitleLabelFor("UNKNOWN"))
        assertNull(MangaTextBender.subtitleLabelFor("THE_MALE"))
        assertNull(MangaTextBender.subtitleLabelFor("THE_FEMALE"))
        assertNull(MangaTextBender.subtitleLabelFor("THE_WOMAN"))
        assertNull(MangaTextBender.subtitleLabelFor("MIKA"))
        assertNull(MangaTextBender.subtitleLabelFor(""))
        assertEquals("MIKA (Narrator)", MangaTextBender.subtitleLabelFor("NARRATOR"))
        assertEquals("MIKA (Narrator)", MangaTextBender.subtitleLabelFor("narrator"))
        assertEquals("SEBASTIAN", MangaTextBender.subtitleLabelFor("sebastian"))
    }

    @Test
    fun `generic role voices cover every gender for universal cast coverage`() {
        val male = MangaTextBender.genericRoleVoicePrompt(VoiceGender.MALE)
        val female = MangaTextBender.genericRoleVoicePrompt(VoiceGender.FEMALE)
        val andro = MangaTextBender.genericRoleVoicePrompt(VoiceGender.ANDROGYNOUS)
        assertTrue(male.contains("male", ignoreCase = true))
        assertTrue(female.contains("female", ignoreCase = true))
        assertTrue(andro.contains("androgynous", ignoreCase = true))
        assertTrue(male != female && female != andro && male != andro)
        // No default-voice escape hatch: all role prompts are Qwen VoiceDesign-grade descriptions
        listOf(male, female, andro).forEach { p ->
            assertTrue(p.contains("close-mic studio", ignoreCase = true))
            assertTrue(p.length in 80..300)
        }
    }

    @Test
    fun `narrator names are barred from the roster but placeholder characters still get voiced`() {
        // v1.9.9 field scar: the roster law wrongly included placeholder names, so THE_WOMAN
        // (a real unnamed cast member who merely gets no subtitle LABEL) was excluded from the
        // voice-clone lock — nobody got cloned and every line fell back to the default voice.
        // Law split: only NARRATOR/NARRATION labels are barred from the roster; placeholder
        // naming stays a subtitle-DISPLAY rule.
        assertTrue(MangaTextBender.isNarratorName("NARRATOR"))
        assertTrue(MangaTextBender.isNarratorName("narrator"))
        assertTrue(MangaTextBender.isNarratorName("NARRATION"))
        assertTrue(MangaTextBender.isNarratorName(""))
        assertFalse(MangaTextBender.isNarratorName("THE_WOMAN"))
        assertFalse(MangaTextBender.isNarratorName("THE_MALE"))
        assertFalse(MangaTextBender.isNarratorName("MIKA"))
        assertFalse(MangaTextBender.isNarratorName("UNKNOWN"))
        assertFalse(MangaTextBender.isNarratorName("SEBASTIAN"))
        assertFalse(MangaTextBender.isNarratorName("OJOU-SAMA"))

        // Placeholder family stays a subtitle-DISPLAY rule only
        assertTrue(MangaTextBender.isPlaceholderSpeakerName("THE_WOMAN"))
        assertTrue(MangaTextBender.isPlaceholderSpeakerName("NARRATOR"))
        assertTrue(MangaTextBender.isPlaceholderSpeakerName("UNKNOWN"))
        assertFalse(MangaTextBender.isPlaceholderSpeakerName("SEBASTIAN"))
        assertNull(MangaTextBender.subtitleLabelFor("THE_WOMAN"))
    }

    @Test
    fun `mika narrator voice prompt is hardcoded and expressive`() {
        val prompt = MangaTextBender.MIKA_NARRATOR_VOICE_PROMPT
        assertTrue(prompt.contains("catgirl companion", ignoreCase = true))
        assertTrue(prompt.contains("close-mic studio", ignoreCase = true))
        assertTrue(prompt.contains("playful", ignoreCase = true))
        assertTrue(prompt.length in 80..400)
    }

    @Test
    fun `xy-cut slicer keeps all eight verified panels in a 2x4 grid under AUTO ceiling`() {
        // v1.9.8: cap 6 amputated verified panels on 8-panel grids (capToMaxPanels merged two
        // adjacent pairs into double-width crops). AUTO (8) = the recursion's natural maxDepth-3
        // ceiling, so every evidence-verified panel survives.
        val w = 132
        val h = 152
        val lum = IntArray(w * h) { 252 }

        fun drawFramedPanel(x0: Int, y0: Int, x1: Int, y1: Int) {
            for (y in y0..y1) {
                for (x in x0..x1) {
                    val onBorder = (x == x0 || x == x1 || y == y0 || y == y1)
                    lum[y * w + x] = if (onBorder) 20 else 210
                }
            }
        }

        // 2 rows x 4 columns of framed panels with clean white gutters
        val cols = listOf(4 to 31, 36 to 63, 68 to 95, 100 to 127)
        val rows = listOf(4 to 72, 80 to 147)
        for ((y0, y1) in rows) {
            for ((x0, x1) in cols) {
                drawFramedPanel(x0, y0, x1, y1)
            }
        }

        val autoPanels = MangaBfsPanelSlicer.slicePanelsFromLuminanceGrid(
            luminance = lum,
            width = w,
            height = h,
            readingOrder = MangaReadingOrder.RTL_MANGA
        )
        assertEquals(8, autoPanels.size)
        // RTL: first panel is top-right, last is bottom-left
        assertTrue(autoPanels[0].centerX > 0.5f && autoPanels[0].centerY < 0.5f)
        assertTrue(autoPanels[7].centerX < 0.5f && autoPanels[7].centerY > 0.5f)

        // Explicit cap 6 still merges the smallest adjacent unions (user override honored)
        val cappedPanels = MangaBfsPanelSlicer.slicePanelsFromLuminanceGrid(
            luminance = lum,
            width = w,
            height = h,
            readingOrder = MangaReadingOrder.RTL_MANGA,
            maxPanels = 6
        )
        assertEquals(6, cappedPanels.size)
    }

    @Test
    fun `single bubble sanitizer strips leading punctuation hallucinations and preserves multi-sentence bubbles unbroken`() {
        // Leading dashes/ellipses must never become leading commas or periods before spoken words
        assertEquals("Wait!", MangaTextBender.sanitizeMangaDialogue("--WAIT!"))
        assertEquals("Huh?!", MangaTextBender.sanitizeMangaDialogue("...Huh?!"))
        assertEquals("Stop right there, fool!", MangaTextBender.sanitizeMangaDialogue("— — STOP RIGHT THERE, FOOL!!"))
        assertEquals("No way. Seriously?!", MangaTextBender.sanitizeMangaDialogue("… — NO WAY... SERIOUSLY?!"))
        assertEquals("No way, seriously?!", MangaTextBender.sanitizeMangaDialogue("… — NO WAY — SERIOUSLY?!"))

        // Very long multi-sentence speech bubble stays ONE unbroken single string
        val longBubble = "--LISTEN TO ME CAREFULLY! " +
            "FIRST WE SECURE THE REACTOR CORE, THEN WE HOLD THE PERIMETER, AND FINALLY WE SIGNAL THE ORBITAL FLEET BEFORE NIGHTFALL!! " +
            "NO ONE LEAVES THIS DECK UNTIL EVERY SINGLE CITIZEN IS SAFELY INSIDE THE SHELTER."
        val prepared = MangaTextBender.prepareSingleBubbleSpeechText(longBubble)
        assertTrue(prepared.startsWith("Listen to me carefully!"))
        assertTrue(prepared.contains("secure the reactor core"))
        assertTrue(prepared.contains("safely inside the shelter."))
        assertFalse(prepared.contains('\n'))
    }

    @Test
    fun `onset conditioner and clone seed conditioner zero dc bias fade smoothly and append trailing silence`() {
        val sr = PurrDspProcessor.SAMPLE_RATE_HZ
        // Synthetic 1-second 220Hz tone + 0.12 DC offset + dead-air margin
        val raw = FloatArray(sr) { i ->
            0.12f + 0.45f * sin(2.0 * kotlin.math.PI * 220.0 * i / sr).toFloat()
        }
        val conditioned = PurrDspProcessor.conditionDubClipOnset(raw, sr)
        assertEquals(0f, conditioned[0], 0.0001f)
        assertTrue(abs(conditioned[1]) < 0.02f)

        val seed = PurrDspProcessor.prepareCleanCloneSeedPcm(raw, sr, tailSilenceMs = 120)
        assertEquals(0f, seed[0], 0.0001f)
        val tailLen = (sr * 120) / 1000
        assertTrue(seed.size > tailLen)
        val tailSlice = seed.copyOfRange(seed.size - tailLen, seed.size)
        assertTrue(tailSlice.all { it == 0f })
    }

    @Test
    fun `cast consolidator merges cross-page aliases locks genders preserves narrator and honors character count baseline`() {
        val initialRoster = listOf(
            MangaCharacterProfile(id = "c1", name = "TOWEL_GIRL", gender = VoiceGender.ANDROGYNOUS, visualDescription = "Young girl in bath towel"),
            MangaCharacterProfile(id = "c2", name = "BLONDE_WOMAN", gender = VoiceGender.FEMALE, visualDescription = "Blonde woman in dark suit"),
            MangaCharacterProfile(id = "c3", name = "SEBASTIAN", gender = VoiceGender.ANDROGYNOUS, visualDescription = "Butler gentleman in coat"),
            MangaCharacterProfile(id = "c_extra", name = "PASSERBY", gender = VoiceGender.MALE, visualDescription = "Background man")
        )
        val pages = listOf(
            MangaPageEntry(
                pageIndex = 0,
                fileName = "p1.jpg",
                localImagePath = "/tmp/p1.jpg",
                widthPx = 800,
                heightPx = 1200,
                panels = listOf(
                    MangaPanelNode(
                        id = "pan_0_0",
                        pageIndex = 0,
                        panelIndex = 0,
                        panelRect = MangaNormalizedRect(0f, 0f, 1f, 1f),
                        cameraStartRect = MangaNormalizedRect(0f, 0f, 1f, 1f),
                        cameraEndRect = MangaNormalizedRect(0f, 0f, 1f, 1f),
                        lines = listOf(
                            MangaSpeechLine("l_nar", 1, "char_narrator", "NARRATOR", "Chapter One.", "Chapter One."),
                            MangaSpeechLine("l_1", 2, "c1", "TOWEL_GIRL", "So thirsty!", "So thirsty!", genderHint = VoiceGender.FEMALE)
                        )
                    )
                )
            ),
            MangaPageEntry(
                pageIndex = 1,
                fileName = "p2.jpg",
                localImagePath = "/tmp/p2.jpg",
                widthPx = 800,
                heightPx = 1200,
                panels = listOf(
                    MangaPanelNode(
                        id = "pan_1_0",
                        pageIndex = 1,
                        panelIndex = 0,
                        panelRect = MangaNormalizedRect(0f, 0f, 1f, 1f),
                        cameraStartRect = MangaNormalizedRect(0f, 0f, 1f, 1f),
                        cameraEndRect = MangaNormalizedRect(0f, 0f, 1f, 1f),
                        lines = listOf(
                            MangaSpeechLine("l_2", 1, "c2", "BLONDE_WOMAN", "Wait for me!", "Wait for me!"),
                            MangaSpeechLine("l_3", 2, "c3", "SEBASTIAN", "Right away, my lady.", "Right away, my lady."),
                            MangaSpeechLine("l_4", 3, "c_extra", "PASSERBY", "Look out!", "Look out!")
                        )
                    )
                )
            )
        )

        val consolidationRaw = """
            {
              "cohesive_story_summary": "Ojou-sama changes from a towel into her suit while Sebastian attends to her.",
              "canonical_characters": [
                {
                  "canonical_name": "OJOU_SAMA",
                  "merged_aliases": ["TOWEL_GIRL", "BLONDE_WOMAN"],
                  "gender": "female",
                  "visual_description": "Young woman seen in bath towel on Page 1 and dark suit on Page 2",
                  "voice_design_prompt": "Bright haughty young anime heroine voice, close-mic studio"
                },
                {
                  "canonical_name": "SEBASTIAN",
                  "merged_aliases": [],
                  "gender": "male",
                  "visual_description": "Butler gentleman in coat",
                  "voice_design_prompt": "Composed deep butler baritone, close-mic studio"
                },
                {
                  "canonical_name": "PASSERBY",
                  "merged_aliases": [],
                  "gender": "male",
                  "visual_description": "Background man",
                  "voice_design_prompt": "Ordinary male bystander voice"
                }
              ],
              "line_fixes": [
                {"line_id": "l_4", "speaker": "SEBASTIAN", "gender": "male"}
              ]
            }
        """.trimIndent()
        val parsed = MangaVisionResponseParser.parseCastConsolidation(consolidationRaw)
        assertNotNull(parsed)

        // With explicit expectedCharacterCount = 2, cast unifies to OJOU_SAMA & SEBASTIAN, while NARRATOR stays untouched
        val (mergedCast, mergedPages) = MangaCastConsolidator.consolidateChapterCastAndLines(
            roster = initialRoster,
            pages = pages,
            consolidation = parsed,
            expectedCharacterCount = 2
        )
        assertEquals(2, mergedCast.size)
        assertTrue(mergedCast.none { MangaTextBender.isNarratorName(it.name) })
        val ojou = mergedCast.first { it.name == "OJOU_SAMA" }
        val seb = mergedCast.first { it.name == "SEBASTIAN" }
        assertEquals(VoiceGender.FEMALE, ojou.gender)
        assertEquals(VoiceGender.MALE, seb.gender)

        val flatLines = mergedPages.flatMap { it.panels }.flatMap { it.lines }
        assertEquals("NARRATOR", flatLines.first { it.id == "l_nar" }.speakerName)
        assertEquals("OJOU_SAMA", flatLines.first { it.id == "l_1" }.speakerName)
        assertEquals("OJOU_SAMA", flatLines.first { it.id == "l_2" }.speakerName)
        assertEquals(VoiceGender.FEMALE, flatLines.first { it.id == "l_2" }.genderHint)
        assertEquals("SEBASTIAN", flatLines.first { it.id == "l_3" }.speakerName)
        assertEquals("SEBASTIAN", flatLines.first { it.id == "l_4" }.speakerName)
    }

    @Test
    fun `openai vision endpoint url normalizer handles presets base urls and custom paths cleanly`() {
        assertEquals(
            "https://api.nano-gpt.com/api/v1/chat/completions",
            OpenAiEndpointUtils.normalizeChatCompletionsUrl("https://api.nano-gpt.com/api/v1")
        )
        assertEquals(
            "https://openrouter.ai/api/v1/chat/completions",
            OpenAiEndpointUtils.normalizeChatCompletionsUrl("https://openrouter.ai/api/v1/")
        )
        assertEquals(
            "https://api.cline.bot/api/v1/chat/completions",
            OpenAiEndpointUtils.normalizeChatCompletionsUrl("https://api.cline.bot/api/v1/chat/completions")
        )
        assertEquals(
            "http://192.168.1.50:8080/v1/chat/completions",
            OpenAiEndpointUtils.normalizeChatCompletionsUrl("http://192.168.1.50:8080")
        )
        assertEquals(
            "https://api.cline.bot/api/v1/chat/completions",
            OpenAiEndpointUtils.normalizeChatCompletionsUrl("https://api.cline.bot")
        )
        assertEquals(
            "https://api.nano-gpt.com/api/v1/models",
            OpenAiEndpointUtils.deriveModelsEndpointUrl("https://api.nano-gpt.com/api/v1/chat/completions")
        )
        assertEquals(
            "sk-abc123",
            OpenAiEndpointUtils.sanitizeBearerApiKey("  Bearer  sk-abc123 ")
        )
        assertEquals(
            OpenAiVisionProviderPreset.CUSTOM,
            OpenAiVisionProviderPreset.entries.last()
        )
    }

    @Test
    fun `openai response extractor unwraps clinepass envelopes nanogpt reasoning fields and sse chunks`() {
        val clinePassWrapped = """
            {"success":true,"data":{"choices":[{"index":0,"message":{"role":"assistant","content":"OK"}}]}}
        """.trimIndent()
        assertEquals("OK", OpenAiEndpointUtils.extractChatCompletionTextOrThrow(clinePassWrapped))

        val nanoGptReasoningOnly = """
            {"choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"","reasoning":"OK from reasoning"}}]}
        """.trimIndent()
        assertEquals("OK from reasoning", OpenAiEndpointUtils.extractChatCompletionTextOrThrow(nanoGptReasoningOnly))

        val nanoGptLegacyReasoning = """
            {"choices":[{"index":0,"message":{"role":"assistant","content":null,"reasoning_content":"Legacy reasoning text"}}]}
        """.trimIndent()
        assertEquals("Legacy reasoning text", OpenAiEndpointUtils.extractChatCompletionTextOrThrow(nanoGptLegacyReasoning))

        val sseBody = """
            data: {"choices":[{"delta":{"content":"Hel"}}]}
            data: {"choices":[{"delta":{"content":"lo!"}}]}
            data: [DONE]
        """.trimIndent()
        assertEquals("Hello!", OpenAiEndpointUtils.extractChatCompletionTextOrThrow(sseBody))

        val errBody = """{"error":{"message":"Model not found","code":"404"}}"""
        val err = runCatching { OpenAiEndpointUtils.extractChatCompletionTextOrThrow(errBody) }.exceptionOrNull()
        assertNotNull(err)
        assertTrue(err!!.message!!.contains("Model not found"))

        val modelsJson = """
            {"object":"list","data":[{"id":"text-embedding-3-small"},{"id":"z-ai/glm-5.3-flash","architecture":{"input_modalities":["text","image"]}},{"id":"cline-pass/kimi-k3"}]}
        """.trimIndent()
        val parsedModels = OpenAiEndpointUtils.parseModelsListIds(modelsJson)
        assertFalse("text-embedding-3-small" in parsedModels)
        assertTrue("z-ai/glm-5.3-flash" in parsedModels)
        assertTrue("cline-pass/kimi-k3" in parsedModels)
    }
}
