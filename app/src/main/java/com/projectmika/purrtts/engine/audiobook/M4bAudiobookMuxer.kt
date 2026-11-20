package com.projectmika.purrtts.engine.audiobook

import android.content.ContentValues
import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.projectmika.purrtts.core.audiobook.AudiobookChapter
import com.projectmika.purrtts.core.audiobook.M4bChapterMarker
import com.projectmika.purrtts.core.audiobook.Mp4ChapterAtomWriter
import com.projectmika.purrtts.core.purr.PurrDspProcessor
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ✨ AUDIOBOOK .M4B CHAPTER MUXER & PUBLIC DOWNLOADS PUBLISHER (TOOL-07) ✨
 *
 * Streams finished per-chapter 24kHz WAV tracks directly into Android hardware/software
 * `MediaCodec` AAC-LC (`audio/mp4a-latm`) -> `MediaMuxer` (`MUXER_OUTPUT_MPEG_4`), injects
 * universal Nero `chpl` chapter timestamps + iTunes audiobook (`stik = 2`) tags via
 * [Mp4ChapterAtomWriter], and saves the `.m4b` file into `Downloads/PURR-TTS/Audiobooks/`.
 */
object M4bAudiobookMuxer {

    data class WavSlice(
        val dataOffset: Long,
        val dataSizeBytes: Long,
        val sampleRateHz: Int,
        val channels: Int
    )

    data class CombineResult(
        val internalM4bFile: File,
        val publicDownloadPath: String,
        val totalDurationMs: Long,
        val chapterCount: Int
    )

    fun readWavSlice(wavFile: File): WavSlice {
        val len = wavFile.length()
        if (len <= 44L) {
            throw IllegalArgumentException("Chapter audio file is empty: ${wavFile.name}")
        }
        RandomAccessFile(wavFile, "r").use { raf ->
            val hdr = ByteArray(12)
            raf.readFully(hdr)
            val riff = String(hdr, 0, 4, Charsets.US_ASCII)
            val wave = String(hdr, 8, 4, Charsets.US_ASCII)
            if (riff != "RIFF" || wave != "WAVE") {
                return WavSlice(44L, len - 44L, PurrDspProcessor.SAMPLE_RATE_HZ, 1)
            }
            var sampleRate = PurrDspProcessor.SAMPLE_RATE_HZ
            var channels = 1
            var dataOffset = 44L
            var dataSize = len - 44L
            val chunkHdr = ByteArray(8)
            var pos = 12L
            while (pos + 8L <= len) {
                raf.seek(pos)
                raf.readFully(chunkHdr)
                val chunkId = String(chunkHdr, 0, 4, Charsets.US_ASCII)
                val chunkSize = ByteBuffer.wrap(chunkHdr, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                when (chunkId) {
                    "fmt " -> {
                        if (chunkSize >= 16L && pos + 8L + 16L <= len) {
                            val fmt = ByteArray(16)
                            raf.readFully(fmt)
                            val bb = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                            bb.short // format tag
                            channels = bb.short.toInt().coerceAtLeast(1)
                            sampleRate = bb.int.coerceAtLeast(8000)
                        }
                    }
                    "data" -> {
                        dataOffset = pos + 8L
                        dataSize = minOf(chunkSize, len - dataOffset)
                        break
                    }
                }
                val padded = chunkSize + (chunkSize and 1L)
                pos += 8L + padded
            }
            return WavSlice(dataOffset, dataSize, sampleRate, channels)
        }
    }

    fun combineChaptersToM4b(
        context: Context,
        bookTitle: String,
        author: String,
        chapters: List<AudiobookChapter>,
        chapterGapMs: Int,
        destM4bFile: File,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): CombineResult {
        if (chapters.isEmpty()) {
            throw IllegalArgumentException("No completed chapters selected to combine")
        }

        val slices = chapters.map { ch ->
            val file = File(ch.renderedWavPath)
            if (!file.exists()) {
                throw IllegalStateException("Missing chapter WAV on disk for '${ch.title}'")
            }
            Triple(ch, file, readWavSlice(file))
        }

        val sampleRate = slices.first().third.sampleRateHz
        val channels = slices.first().third.channels
        val bytesPerFrame = channels * 2
        val totalAudioBytes = slices.sumOf { it.third.dataSizeBytes }.coerceAtLeast(1L)

        destM4bFile.parentFile?.mkdirs()
        val tempRawMp4 = File(destM4bFile.parentFile, "${destM4bFile.nameWithoutExtension}.tmp.mp4")
        if (tempRawMp4.exists()) tempRawMp4.delete()

        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 96_000)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var trackIndex = -1
        val markers = mutableListOf<M4bChapterMarker>()

        var framesWrittenTotal = 0L
        var bytesFedTotal = 0L

        fun drainEncoder(bufferInfo: MediaCodec.BufferInfo, endOfStream: Boolean): Boolean {
            var sawEos = false
            while (true) {
                val timeoutUs = if (endOfStream) 10_000L else 0L
                val outIdx = encoder.dequeueOutputBuffer(bufferInfo, timeoutUs)
                when {
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (!endOfStream) break
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxerStarted) {
                            trackIndex = muxer!!.addTrack(encoder.outputFormat)
                            muxer!!.start()
                            muxerStarted = true
                        }
                    }
                    outIdx >= 0 -> {
                        val outBuf = encoder.getOutputBuffer(outIdx)
                        if (outBuf != null && bufferInfo.size > 0 && muxerStarted &&
                            (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                        ) {
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            muxer!!.writeSampleData(trackIndex, outBuf, bufferInfo)
                        }
                        val eos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        encoder.releaseOutputBuffer(outIdx, false)
                        if (eos) {
                            sawEos = true
                            break
                        }
                    }
                }
            }
            return sawEos
        }

        fun feedPcmBytes(chunk: ByteArray, length: Int, bufferInfo: MediaCodec.BufferInfo) {
            var off = 0
            while (off < length) {
                val inIdx = encoder.dequeueInputBuffer(10_000L)
                if (inIdx >= 0) {
                    val inBuf = encoder.getInputBuffer(inIdx)!!
                    inBuf.clear()
                    val step = minOf(inBuf.remaining(), length - off)
                    inBuf.put(chunk, off, step)
                    val ptsUs = (framesWrittenTotal * 1_000_000L) / sampleRate
                    framesWrittenTotal += (step / bytesPerFrame).toLong()
                    encoder.queueInputBuffer(inIdx, 0, step, ptsUs, 0)
                    off += step
                }
                drainEncoder(bufferInfo, endOfStream = false)
            }
        }

        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            muxer = MediaMuxer(tempRawMp4.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val bufferInfo = MediaCodec.BufferInfo()
            val ioBuf = ByteArray(8192)
            val gapFrames = ((chapterGapMs.coerceIn(0, 5000) * sampleRate) / 1000)
            val silenceGapChunk = ByteArray(minOf(4096, gapFrames * bytesPerFrame).coerceAtLeast(bytesPerFrame))

            onProgress(0.05f, "ENCODING AAC AUDIOBOOK TRACK...")

            slices.forEachIndexed { idx, (chapter, file, slice) ->
                val startMs = (framesWrittenTotal * 1000L) / sampleRate
                markers += M4bChapterMarker(startMs = startMs, title = chapter.title)

                FileInputStream(file).use { fis ->
                    var skipped = 0L
                    while (skipped < slice.dataOffset) {
                        val s = fis.skip(slice.dataOffset - skipped)
                        if (s <= 0) break
                        skipped += s
                    }
                    var remaining = slice.dataSizeBytes
                    while (remaining > 0L) {
                        val readMax = minOf(ioBuf.size.toLong(), remaining).toInt()
                        val read = fis.read(ioBuf, 0, readMax)
                        if (read <= 0) break
                        val evenBytes = read - (read % bytesPerFrame)
                        if (evenBytes > 0) {
                            feedPcmBytes(ioBuf, evenBytes, bufferInfo)
                        }
                        remaining -= read
                        bytesFedTotal += read
                        val frac = (0.05f + 0.80f * (bytesFedTotal.toFloat() / totalAudioBytes.toFloat())).coerceIn(0.05f, 0.85f)
                        onProgress(frac, "MUXING CHAPTER ${idx + 1}/${slices.size}: ${chapter.title}")
                    }
                }

                if (idx < slices.lastIndex && gapFrames > 0) {
                    var gapBytesLeft = gapFrames * bytesPerFrame
                    while (gapBytesLeft > 0) {
                        val step = minOf(silenceGapChunk.size, gapBytesLeft)
                        feedPcmBytes(silenceGapChunk, step, bufferInfo)
                        gapBytesLeft -= step
                    }
                }
            }

            // Signal End of Stream
            while (true) {
                val eosIdx = encoder.dequeueInputBuffer(10_000L)
                if (eosIdx >= 0) {
                    val ptsUs = (framesWrittenTotal * 1_000_000L) / sampleRate
                    encoder.queueInputBuffer(eosIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    break
                }
                drainEncoder(bufferInfo, endOfStream = false)
            }
            drainEncoder(bufferInfo, endOfStream = true)
        } finally {
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
            if (muxerStarted) {
                runCatching { muxer?.stop() }
            }
            runCatching { muxer?.release() }
        }

        onProgress(0.90f, "WRITING .M4B CHAPTER ATOMS & METADATA...")
        Mp4ChapterAtomWriter.injectAudiobookMetadata(
            sourceMp4 = tempRawMp4,
            destM4b = destM4bFile,
            title = bookTitle,
            author = author,
            chapters = markers
        )
        runCatching { tempRawMp4.delete() }

        onProgress(0.96f, "SAVING .M4B TO DOWNLOADS...")
        val publicPath = copyAudiobookFileToPublicDownloads(
            context = context,
            sourceFile = destM4bFile,
            fileName = destM4bFile.name,
            mimeType = "audio/mp4",
            subFolder = "Audiobooks"
        )
        val totalDurationMs = (framesWrittenTotal * 1000L) / sampleRate
        onProgress(1.0f, "READY // ${slices.size} CHAPTERS (${totalDurationMs / 1000}s)")

        return CombineResult(
            internalM4bFile = destM4bFile,
            publicDownloadPath = publicPath,
            totalDurationMs = totalDurationMs,
            chapterCount = slices.size
        )
    }

    fun copyAudiobookFileToPublicDownloads(
        context: Context,
        sourceFile: File,
        fileName: String,
        mimeType: String,
        subFolder: String
    ): String {
        return runCatching {
            val relPath = "${Environment.DIRECTORY_DOWNLOADS}/PURR-TTS/$subFolder"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(MediaStore.Downloads.RELATIVE_PATH, relPath)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    try {
                        resolver.openOutputStream(uri)?.use { out ->
                            FileInputStream(sourceFile).use { input -> input.copyTo(out) }
                        }
                        values.clear()
                        values.put(MediaStore.Downloads.IS_PENDING, 0)
                        resolver.update(uri, values, null, null)
                        return@runCatching "Downloads/PURR-TTS/$subFolder/$fileName"
                    } catch (e: Exception) {
                        runCatching { resolver.delete(uri, null, null) }
                        throw e
                    }
                }
            }
            @Suppress("DEPRECATION")
            val downloadsDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "PURR-TTS/$subFolder"
            ).apply { mkdirs() }
            val dest = File(downloadsDir, fileName)
            sourceFile.copyTo(dest, overwrite = true)
            "Downloads/PURR-TTS/$subFolder/$fileName"
        }.getOrElse { "" }
    }
}
