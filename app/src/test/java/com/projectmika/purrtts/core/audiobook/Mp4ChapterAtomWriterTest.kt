package com.projectmika.purrtts.core.audiobook

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ✨ PURE-JVM TESTS FOR MP4 / M4B NERO CHAPTER (`chpl`) & METADATA WRITER (TOOL-07) ✨
 */
class Mp4ChapterAtomWriterTest {

    @Test
    fun `injects M4B brand chpl chapter markers and iTunes stik without corrupting moov-after-mdat`() {
        val mdatOffset = 20 // right after 20-byte ftyp
        val stcoOriginalOffset = mdatOffset + 8
        val syntheticMp4 = buildSyntheticMp4(moovBeforeMdat = false, stcoValue = stcoOriginalOffset)

        val chapters = listOf(
            M4bChapterMarker(0L, "Prologue"),
            M4bChapterMarker(42_500L, "Chapter 1: Harbor Dusk — Élan")
        )
        val out = Mp4ChapterAtomWriter.injectAudiobookMetadata(
            inputBytes = syntheticMp4,
            title = "Chronicles of MIKA",
            author = "ProjectMIKA",
            chapters = chapters
        )

        // ftyp major brand patched to "M4B "
        assertEquals("M4B ", String(out, 8, 4, StandardCharsets.US_ASCII))

        val outText = String(out, StandardCharsets.ISO_8859_1)
        assertTrue("Contains Nero chpl atom", outText.contains("chpl"))
        assertTrue("Contains iTunes ilst metadata", outText.contains("ilst"))
        assertTrue("Contains Audiobook stik marker", outText.contains("stik"))
        assertTrue("Contains first chapter", outText.contains("Prologue"))

        // Verify stco chunk offset was NOT shifted because mdat precedes moov
        val stcoOff = findFirstBoxPayload(out, "stco")!!
        val patchedChunkOffset = ByteBuffer.wrap(out, stcoOff + 8, 4).order(ByteOrder.BIG_ENDIAN).int
        assertEquals(stcoOriginalOffset, patchedChunkOffset)

        // Verify Nero chpl timestamp 42,500 ms == 425,000,000 (100ns units)
        val chplOff = findFirstBoxPayload(out, "chpl")!!
        assertEquals(1, out[chplOff].toInt()) // version 1
        assertEquals(2, out[chplOff + 8].toInt()) // 2 chapters
        val firstStart100ns = ByteBuffer.wrap(out, chplOff + 9, 8).order(ByteOrder.BIG_ENDIAN).long
        assertEquals(0L, firstStart100ns)
        val firstTitleLen = out[chplOff + 17].toInt() and 0xFF
        val secondEntryStart = chplOff + 18 + firstTitleLen
        val secondStart100ns = ByteBuffer.wrap(out, secondEntryStart, 8).order(ByteOrder.BIG_ENDIAN).long
        assertEquals(425_000_000L, secondStart100ns)
    }

    @Test
    fun `shifts stco chunk offsets cleanly by delta when moov precedes mdat`() {
        val originalStco = 200
        val syntheticMp4 = buildSyntheticMp4(moovBeforeMdat = true, stcoValue = originalStco)

        val out = Mp4ChapterAtomWriter.injectAudiobookMetadata(
            inputBytes = syntheticMp4,
            title = "FastStart Book",
            author = "MIKA",
            chapters = listOf(M4bChapterMarker(0L, "Only Chapter"))
        )
        val delta = out.size - syntheticMp4.size
        assertTrue("Metadata injection expands moov", delta > 40)

        val stcoOff = findFirstBoxPayload(out, "stco")!!
        val updatedStco = ByteBuffer.wrap(out, stcoOff + 8, 4).order(ByteOrder.BIG_ENDIAN).int
        assertEquals(originalStco + delta, updatedStco)
    }

    private fun buildSyntheticMp4(moovBeforeMdat: Boolean, stcoValue: Int): ByteArray {
        val ftyp = wrapBox("ftyp", "mp42".toByteArray(StandardCharsets.US_ASCII) + byteArrayOf(0, 0, 0, 0, 'i'.code.toByte(), 's'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte()))
        val mdat = wrapBox("mdat", ByteArray(64) { 0x11 })

        val stcoBaos = ByteArrayOutputStream()
        DataOutputStream(stcoBaos).apply {
            writeInt(0) // full box version/flags
            writeInt(1) // entry_count = 1
            writeInt(stcoValue)
            flush()
        }
        val stco = wrapBox("stco", stcoBaos.toByteArray())
        val stbl = wrapBox("stbl", stco)
        val minf = wrapBox("minf", stbl)
        val mdia = wrapBox("mdia", minf)
        val trak = wrapBox("trak", mdia)
        val moov = wrapBox("moov", trak)

        val out = ByteArrayOutputStream()
        out.write(ftyp)
        if (moovBeforeMdat) {
            out.write(moov)
            out.write(mdat)
        } else {
            out.write(mdat)
            out.write(moov)
        }
        return out.toByteArray()
    }

    private fun wrapBox(type: String, payload: ByteArray): ByteArray {
        val size = 8 + payload.size
        val bb = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        bb.putInt(size)
        bb.put(type.toByteArray(StandardCharsets.US_ASCII))
        bb.put(payload)
        return bb.array()
    }

    private fun findFirstBoxPayload(data: ByteArray, targetType: String): Int? {
        for (i in 0 until data.size - 8) {
            if (data[i + 4] == targetType[0].code.toByte() &&
                data[i + 5] == targetType[1].code.toByte() &&
                data[i + 6] == targetType[2].code.toByte() &&
                data[i + 7] == targetType[3].code.toByte()
            ) {
                val sz = ByteBuffer.wrap(data, i, 4).order(ByteOrder.BIG_ENDIAN).int
                if (sz in 8..(data.size - i)) {
                    return i + 8
                }
            }
        }
        return null
    }
}
