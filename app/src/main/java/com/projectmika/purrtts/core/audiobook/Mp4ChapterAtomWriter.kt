package com.projectmika.purrtts.core.audiobook

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * ✨ PURE-KOTLIN MP4 / M4B CHAPTER & METADATA ATOM WRITER (TOOL-07) ✨
 *
 * Transforms a standard Android `MediaMuxer` MPEG-4/AAC audio file into a full `.m4b`
 * audiobook compatible with Smart AudioBook Player, Voice, Listen, Audiobookshelf, VLC & Apple Books:
 * 1. Patches `ftyp` major brand to `"M4B "` (zero byte-offset shift).
 * 2. Writes Nero `chpl` v1 chapter atom (`100ns` timestamps + UTF-8 chapter titles) inside `moov/udta`.
 * 3. Writes iTunes `meta`/`hdlr`/`ilst` atoms (`©nam`, `©ART`, `©alb`, `©too`, `stik = 2` [Audiobook]).
 * 4. If `moov` appears before `mdat` (fast-start layout), automatically shifts every `stco`/`co64`
 *    chunk offset by `+delta` so the file stays 100% seekable.
 *
 * PURE-JVM — zero Android dependencies so binary box writing is unit-tested in `Mp4ChapterAtomWriterTest`.
 */
data class M4bChapterMarker(
    val startMs: Long,
    val title: String
)

object Mp4ChapterAtomWriter {

    private data class Mp4BoxRange(
        val type: String,
        val startOffset: Int,
        val size: Int,
        val headerSize: Int
    ) {
        val endOffset: Int get() = startOffset + size
        val payloadOffset: Int get() = startOffset + headerSize
    }

    private val CONTAINER_ATOMS = setOf("moov", "trak", "mdia", "minf", "stbl", "edts")

    fun injectAudiobookMetadata(
        sourceMp4: File,
        destM4b: File,
        title: String,
        author: String,
        chapters: List<M4bChapterMarker>
    ) {
        val raw = sourceMp4.readBytes()
        val patched = injectAudiobookMetadata(raw, title, author, chapters)
        destM4b.parentFile?.mkdirs()
        destM4b.writeBytes(patched)
    }

    fun injectAudiobookMetadata(
        inputBytes: ByteArray,
        title: String,
        author: String,
        chapters: List<M4bChapterMarker>
    ): ByteArray {
        val topBoxes = parseBoxes(inputBytes, 0, inputBytes.size)
        val moovBox = topBoxes.firstOrNull { it.type == "moov" }
            ?: throw IllegalArgumentException("Invalid MP4/M4A file: missing 'moov' box")
        val ftypBox = topBoxes.firstOrNull { it.type == "ftyp" }
        val mdatBox = topBoxes.firstOrNull { it.type == "mdat" }

        val udtaAtom = buildUdtaAtom(title, author, chapters)
        val oldMoovPayloadStart = moovBox.payloadOffset
        val oldMoovChildren = parseBoxes(inputBytes, oldMoovPayloadStart, moovBox.endOffset)

        val newMoovPayload = ByteArrayOutputStream(moovBox.size + udtaAtom.size)
        for (child in oldMoovChildren) {
            if (child.type != "udta") {
                newMoovPayload.write(inputBytes, child.startOffset, child.size)
            }
        }
        newMoovPayload.write(udtaAtom)

        val rebuiltMoov = wrapBox("moov", newMoovPayload.toByteArray())
        val delta = rebuiltMoov.size - moovBox.size
        if (mdatBox != null && moovBox.startOffset < mdatBox.startOffset && delta != 0) {
            patchChunkOffsetsRecursive(rebuiltMoov, 8, rebuiltMoov.size, delta)
        }

        val out = ByteArrayOutputStream(inputBytes.size + delta.coerceAtLeast(0))
        out.write(inputBytes, 0, moovBox.startOffset)
        out.write(rebuiltMoov)
        out.write(inputBytes, moovBox.endOffset, inputBytes.size - moovBox.endOffset)

        val result = out.toByteArray()
        // Mark major brand as "M4B " at offset payloadOffset + 0..3 inside ftyp (4 bytes -> 4 bytes)
        if (ftypBox != null && ftypBox.size >= ftypBox.headerSize + 4) {
            val brandOffset = ftypBox.payloadOffset
            result[brandOffset] = 'M'.code.toByte()
            result[brandOffset + 1] = '4'.code.toByte()
            result[brandOffset + 2] = 'B'.code.toByte()
            result[brandOffset + 3] = ' '.code.toByte()
        }
        return result
    }

    private fun parseBoxes(data: ByteArray, start: Int, end: Int): List<Mp4BoxRange> {
        val list = mutableListOf<Mp4BoxRange>()
        var cursor = start
        while (cursor + 8 <= end) {
            val rawSize = readInt32Be(data, cursor).toLong() and 0xFFFFFFFFL
            val type = String(data, cursor + 4, 4, StandardCharsets.US_ASCII)
            val (boxSizeLong, headerSize) = when (rawSize) {
                0L -> (end - cursor).toLong() to 8
                1L -> {
                    if (cursor + 16 > end) break
                    readInt64Be(data, cursor + 8) to 16
                }
                else -> rawSize to 8
            }
            if (boxSizeLong < headerSize || cursor + boxSizeLong > end) break
            val sizeInt = boxSizeLong.toInt()
            list += Mp4BoxRange(type, cursor, sizeInt, headerSize)
            cursor += sizeInt
        }
        return list
    }

    private fun patchChunkOffsetsRecursive(data: ByteArray, start: Int, end: Int, delta: Int) {
        for (box in parseBoxes(data, start, end)) {
            when (box.type) {
                "stco" -> {
                    val p = box.payloadOffset
                    if (p + 8 <= box.endOffset) {
                        val entryCount = readInt32Be(data, p + 4)
                        var pos = p + 8
                        for (i in 0 until entryCount) {
                            if (pos + 4 > box.endOffset) break
                            val oldOff = readInt32Be(data, pos).toLong() and 0xFFFFFFFFL
                            writeInt32Be(data, pos, (oldOff + delta).toInt())
                            pos += 4
                        }
                    }
                }
                "co64" -> {
                    val p = box.payloadOffset
                    if (p + 8 <= box.endOffset) {
                        val entryCount = readInt32Be(data, p + 4)
                        var pos = p + 8
                        for (i in 0 until entryCount) {
                            if (pos + 8 > box.endOffset) break
                            val oldOff = readInt64Be(data, pos)
                            writeInt64Be(data, pos, oldOff + delta.toLong())
                            pos += 8
                        }
                    }
                }
                in CONTAINER_ATOMS -> {
                    patchChunkOffsetsRecursive(data, box.payloadOffset, box.endOffset, delta)
                }
            }
        }
    }

    private fun buildUdtaAtom(
        title: String,
        author: String,
        chapters: List<M4bChapterMarker>
    ): ByteArray {
        val baos = ByteArrayOutputStream()
        baos.write(buildNeroChplAtom(chapters))
        baos.write(buildItunesMetaAtom(title, author))
        return wrapBox("udta", baos.toByteArray())
    }

    private fun buildNeroChplAtom(chapters: List<M4bChapterMarker>): ByteArray {
        val bounded = chapters.take(255)
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeByte(1) // version = 1
        dos.writeByte(0); dos.writeByte(0); dos.writeByte(0) // flags
        dos.writeInt(0) // reserved (version != 0)
        dos.writeByte(bounded.size)
        for (ch in bounded) {
            val timestamp100ns = ch.startMs.coerceAtLeast(0L) * 10_000L
            dos.writeLong(timestamp100ns)
            val titleBytes = truncateUtf8(ch.title.ifBlank { "Chapter" }, 255)
            dos.writeByte(titleBytes.size)
            dos.write(titleBytes)
        }
        dos.flush()
        return wrapBox("chpl", baos.toByteArray())
    }

    private fun buildItunesMetaAtom(title: String, author: String): ByteArray {
        val baos = ByteArrayOutputStream()
        baos.write(byteArrayOf(0, 0, 0, 0)) // FullBox version + flags

        // hdlr (33 bytes total)
        val hdlrPayload = ByteArray(25)
        hdlrPayload[8] = 'm'.code.toByte()
        hdlrPayload[9] = 'd'.code.toByte()
        hdlrPayload[10] = 'i'.code.toByte()
        hdlrPayload[11] = 'r'.code.toByte()
        hdlrPayload[12] = 'a'.code.toByte()
        hdlrPayload[13] = 'p'.code.toByte()
        hdlrPayload[14] = 'p'.code.toByte()
        hdlrPayload[15] = 'l'.code.toByte()
        baos.write(wrapBox("hdlr", hdlrPayload))

        val ilstPayload = ByteArrayOutputStream()
        val safeTitle = title.ifBlank { "PURR Audiobook" }
        val safeAuthor = author.ifBlank { "PURR-TTS" }
        ilstPayload.write(wrapItunesTextAtom(0xA9, "nam", safeTitle))
        ilstPayload.write(wrapItunesTextAtom(0xA9, "ART", safeAuthor))
        ilstPayload.write(wrapItunesTextAtom(0xA9, "alb", safeTitle))
        ilstPayload.write(wrapItunesTextAtom(0xA9, "too", "PURR-TTS Audiobook Constructor"))
        // stik = 2 (Audiobook)
        ilstPayload.write(wrapItunesDataAtom("stik", dataType = 21, payload = byteArrayOf(2)))

        baos.write(wrapBox("ilst", ilstPayload.toByteArray()))
        return wrapBox("meta", baos.toByteArray())
    }

    private fun wrapItunesTextAtom(prefixByte: Int, threeChars: String, value: String): ByteArray {
        val typeBytes = byteArrayOf(
            prefixByte.toByte(),
            threeChars[0].code.toByte(),
            threeChars[1].code.toByte(),
            threeChars[2].code.toByte()
        )
        val dataPayload = wrapDataAtom(dataType = 1, payload = value.toByteArray(StandardCharsets.UTF_8))
        return wrapRawTypeBox(typeBytes, dataPayload)
    }

    private fun wrapItunesDataAtom(type: String, dataType: Int, payload: ByteArray): ByteArray {
        val dataBox = wrapDataAtom(dataType, payload)
        return wrapBox(type, dataBox)
    }

    private fun wrapDataAtom(dataType: Int, payload: ByteArray): ByteArray {
        val baos = ByteArrayOutputStream(8 + payload.size)
        val dos = DataOutputStream(baos)
        dos.writeInt(dataType)
        dos.writeInt(0) // locale
        dos.write(payload)
        dos.flush()
        return wrapBox("data", baos.toByteArray())
    }

    private fun wrapBox(type: String, payload: ByteArray): ByteArray =
        wrapRawTypeBox(type.toByteArray(StandardCharsets.US_ASCII), payload)

    private fun wrapRawTypeBox(typeBytes: ByteArray, payload: ByteArray): ByteArray {
        val out = ByteArray(8 + payload.size)
        writeInt32Be(out, 0, out.size)
        System.arraycopy(typeBytes, 0, out, 4, 4)
        System.arraycopy(payload, 0, out, 8, payload.size)
        return out
    }

    private fun truncateUtf8(text: String, maxBytes: Int): ByteArray {
        val raw = text.toByteArray(StandardCharsets.UTF_8)
        if (raw.size <= maxBytes) return raw
        var cut = maxBytes
        while (cut > 0 && (raw[cut].toInt() and 0xC0) == 0x80) {
            cut--
        }
        return raw.copyOf(cut)
    }

    private fun readInt32Be(buf: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(buf, offset, 4).order(ByteOrder.BIG_ENDIAN).int

    private fun readInt64Be(buf: ByteArray, offset: Int): Long =
        ByteBuffer.wrap(buf, offset, 8).order(ByteOrder.BIG_ENDIAN).long

    private fun writeInt32Be(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = ((value ushr 24) and 0xFF).toByte()
        buf[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        buf[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        buf[offset + 3] = (value and 0xFF).toByte()
    }

    private fun writeInt64Be(buf: ByteArray, offset: Int, value: Long) {
        for (b in 0 until 8) {
            buf[offset + b] = ((value ushr ((7 - b) * 8)) and 0xFFL).toByte()
        }
    }
}
