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
}
