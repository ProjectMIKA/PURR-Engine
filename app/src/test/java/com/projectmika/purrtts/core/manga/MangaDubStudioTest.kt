package com.projectmika.purrtts.core.manga

import com.projectmika.purrtts.core.purr.VoiceGender
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset
import java.nio.file.Files
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
    fun `gemma4 vision tier defaults to AUTO and deserializes legacy project json cleanly`() {
        assertTrue(MangaVisionModelTier.GEMMA4_E2B.isRecommended)
        assertFalse(MangaVisionModelTier.GEMMA4_E4B.isRecommended)
        assertEquals("sub_gemma4_e2b_vision", MangaVisionModelTier.GEMMA4_E2B.specId)
        assertEquals("sub_gemma4_e4b_vision", MangaVisionModelTier.GEMMA4_E4B.specId)

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
        assertEquals(MangaVisionModelTier.AUTO, decoded.preferredVisionModel)

        val encoded = parser.encodeToString(decoded.copy(preferredVisionModel = MangaVisionModelTier.GEMMA4_E4B))
        assertEquals(MangaVisionModelTier.GEMMA4_E4B, parser.decodeFromString<MangaSeriesProject>(encoded).preferredVisionModel)
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
}
