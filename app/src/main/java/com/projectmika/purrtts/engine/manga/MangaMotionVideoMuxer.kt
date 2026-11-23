package com.projectmika.purrtts.engine.manga

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.projectmika.purrtts.core.manga.MangaAtmospherePreset
import com.projectmika.purrtts.core.manga.MangaChapterEntry
import com.projectmika.purrtts.core.manga.MangaEmotionalPacer
import com.projectmika.purrtts.core.manga.MangaNormalizedRect
import com.projectmika.purrtts.core.manga.MangaPageEntry
import com.projectmika.purrtts.core.manga.MangaPanelNode
import com.projectmika.purrtts.core.manga.MangaSpeechLine
import com.projectmika.purrtts.core.manga.MangaVideoAspect
import com.projectmika.purrtts.core.purr.PurrDspProcessor
import com.projectmika.purrtts.engine.audiobook.M4bAudiobookMuxer
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * ✨ HARDWARE MEDIACODEC MOTION-COMIC .MP4 VIDEO & AAC MUXER (TOOL-11) ✨
 *
 * Encodes a complete dubbed manga chapter into a hardware H.264/AVC + AAC `.mp4`:
 * - Uses isolated single-track Video + Audio encodes followed by a fast dual-track `MediaExtractor`
 *   mux so `MediaCodec` buffers never deadlock across differing format-ready timings.
 * - Renders Holodeck dimmed page backdrops (`HolodeckOverlay.jsx`), cubic-bezier panel camera pans,
 *   GachaSwipe Ken Burns drifts (`photoViewerCamera.js`), `AtmosphereEngine` weather/combat particles,
 *   and synchronized bottom speaker subtitle HUD pills.
 */
object MangaMotionVideoMuxer {

    private const val FPS = 24
    private const val VIDEO_BITRATE = 4_000_000
    private const val ESTABLISH_HOLD_MS = 650L
    private const val PAN_TRANSITION_MS = 400L

    data class RenderResult(
        val internalMp4File: File,
        val publicDownloadPath: String,
        val durationSec: Float
    )

    private sealed interface ShotSegment {
        val startMs: Long
        val endMs: Long
        val page: MangaPageEntry
    }

    private data class EstablishShot(
        override val startMs: Long,
        override val endMs: Long,
        override val page: MangaPageEntry,
        val focusRect: MangaNormalizedRect
    ) : ShotSegment

    private data class PanTransitionShot(
        override val startMs: Long,
        override val endMs: Long,
        override val page: MangaPageEntry,
        val fromRect: MangaNormalizedRect,
        val toRect: MangaNormalizedRect,
        val atmosphere: MangaAtmospherePreset
    ) : ShotSegment

    private data class TimedDialogueSpan(
        val startMs: Long,
        val endMs: Long,
        val line: MangaSpeechLine
    )

    private data class PanelSpeakShot(
        override val startMs: Long,
        override val endMs: Long,
        override val page: MangaPageEntry,
        val panel: MangaPanelNode,
        val atmosphere: MangaAtmospherePreset,
        val lines: List<TimedDialogueSpan>
    ) : ShotSegment

    private data class AudioSpan(
        val startMs: Long,
        val durationMs: Long,
        val wavFile: File?
    )

    fun renderChapterMp4(
        context: Context,
        chapter: MangaChapterEntry,
        aspect: MangaVideoAspect,
        burnInSubtitles: Boolean,
        enableAtmosphereFx: Boolean,
        destMp4File: File,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): RenderResult {
        if (chapter.pages.isEmpty()) {
            throw IllegalStateException("Chapter has no pages to render")
        }

        val sampleRate = PurrDspProcessor.SAMPLE_RATE_HZ
        val shots = ArrayList<ShotSegment>()
        val audioSpans = ArrayList<AudioSpan>()
        var cursorMs = 0L

        for (page in chapter.pages) {
            val fullPageRect = MangaNormalizedRect(0.01f, 0.01f, 0.99f, 0.99f)
            shots += EstablishShot(cursorMs, cursorMs + ESTABLISH_HOLD_MS, page, fullPageRect)
            audioSpans += AudioSpan(cursorMs, ESTABLISH_HOLD_MS, null)
            cursorMs += ESTABLISH_HOLD_MS

            var prevRect = fullPageRect
            for (panel in page.panels) {
                val atmo = if (enableAtmosphereFx) {
                    MangaEmotionalPacer.resolveAtmosphere(
                        preset = panel.atmosphere,
                        sceneMood = panel.sceneMood,
                        dialogueText = panel.lines.joinToString(" ") { it.sanitizedText }
                    )
                } else {
                    MangaAtmospherePreset.NONE
                }

                shots += PanTransitionShot(
                    startMs = cursorMs,
                    endMs = cursorMs + PAN_TRANSITION_MS,
                    page = page,
                    fromRect = prevRect,
                    toRect = panel.cameraStartRect,
                    atmosphere = atmo
                )
                audioSpans += AudioSpan(cursorMs, PAN_TRANSITION_MS, null)
                cursorMs += PAN_TRANSITION_MS

                val panelStartMs = cursorMs
                val timedLines = ArrayList<TimedDialogueSpan>(panel.lines.size)
                if (panel.lines.isEmpty()) {
                    val silentHold = 1800L
                    audioSpans += AudioSpan(cursorMs, silentHold, null)
                    cursorMs += silentHold
                } else {
                    for (line in panel.lines) {
                        val wav = line.renderedWavPath.takeIf { it.isNotBlank() }?.let(::File)
                            ?.takeIf { it.isFile && it.length() > 44L }
                        val speechDurMs = when {
                            wav != null -> {
                                val slice = M4bAudiobookMuxer.readWavSlice(wav)
                                val bytesPerSec = slice.sampleRateHz.toLong() * slice.channels.toLong() * 2L
                                ((slice.dataSizeBytes * 1000L) / bytesPerSec.coerceAtLeast(1L)).coerceAtLeast(350L)
                            }
                            line.durationMs > 0L -> line.durationMs
                            else -> maxOf(1200L, line.sanitizedText.length * 55L)
                        }
                        val lineStart = cursorMs
                        val lineEnd = lineStart + speechDurMs
                        timedLines += TimedDialogueSpan(lineStart, lineEnd, line)
                        audioSpans += AudioSpan(lineStart, speechDurMs, wav)
                        cursorMs = lineEnd

                        val pauseMs = line.postPauseMs.toLong().coerceIn(150L, 1200L)
                        audioSpans += AudioSpan(cursorMs, pauseMs, null)
                        cursorMs += pauseMs
                    }
                }
                shots += PanelSpeakShot(
                    startMs = panelStartMs,
                    endMs = cursorMs,
                    page = page,
                    panel = panel,
                    atmosphere = atmo,
                    lines = timedLines
                )
                prevRect = panel.cameraEndRect
            }
        }

        val totalDurationMs = cursorMs.coerceAtLeast(1000L)
        val totalFrames = ((totalDurationMs * FPS) / 1000L).toInt().coerceAtLeast(24)

        destMp4File.parentFile?.mkdirs()
        val tempVideoFile = File(destMp4File.parentFile, "${destMp4File.nameWithoutExtension}.vid.tmp.mp4")
        val tempAudioFile = File(destMp4File.parentFile, "${destMp4File.nameWithoutExtension}.aud.tmp.mp4")
        if (tempVideoFile.exists()) tempVideoFile.delete()
        if (tempAudioFile.exists()) tempAudioFile.delete()
        if (destMp4File.exists()) destMp4File.delete()

        try {
            encodeVideoTrack(
                width = aspect.widthPx,
                height = aspect.heightPx,
                totalFrames = totalFrames,
                totalDurationMs = totalDurationMs,
                shots = shots,
                burnInSubtitles = burnInSubtitles,
                tempVideoFile = tempVideoFile,
                onProgress = { p -> onProgress(0.05f + p * 0.65f, "RENDERING VIDEO FRAMES (${(p * 100).toInt()}%)...") }
            )

            onProgress(0.74f, "ENCODING AAC VOICEOVER TRACK...")
            val pcmStream = buildCombinedPcmStream(audioSpans, sampleRate)
            encodeAacTrack(
                pcm16 = pcmStream,
                sampleRate = sampleRate,
                tempAudioFile = tempAudioFile
            )

            onProgress(0.90f, "MUXING .MP4 VIDEO & AUDIO...")
            muxVideoAndAudio(tempVideoFile, tempAudioFile, destMp4File)

            onProgress(0.96f, "PUBLISHING TO DOWNLOADS/PURR-TTS/MANGADUBS...")
            val publicPath = publishMp4ToDownloads(context, destMp4File, destMp4File.name)
            onProgress(1.0f, "COMPLETED // ${destMp4File.name}")
            return RenderResult(
                internalMp4File = destMp4File,
                publicDownloadPath = publicPath,
                durationSec = totalDurationMs / 1000f
            )
        } finally {
            runCatching { tempVideoFile.delete() }
            runCatching { tempAudioFile.delete() }
        }
    }

    private fun encodeVideoTrack(
        width: Int,
        height: Int,
        totalFrames: Int,
        totalDurationMs: Long,
        shots: List<ShotSegment>,
        burnInSubtitles: Boolean,
        tempVideoFile: File,
        onProgress: (Float) -> Unit
    ) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = encoder.createInputSurface()
        encoder.start()

        val muxer = MediaMuxer(tempVideoFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var trackIdx = -1
        var muxerStarted = false
        val bufferInfo = MediaCodec.BufferInfo()

        fun drainVideo(endOfStream: Boolean) {
            if (endOfStream) {
                encoder.signalEndOfInputStream()
            }
            while (true) {
                val timeout = if (endOfStream) 10_000L else 0L
                val outIdx = encoder.dequeueOutputBuffer(bufferInfo, timeout)
                when {
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (!endOfStream) break
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxerStarted) {
                            trackIdx = muxer.addTrack(encoder.outputFormat)
                            muxer.start()
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
                            muxer.writeSampleData(trackIdx, outBuf, bufferInfo)
                        }
                        val eos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        encoder.releaseOutputBuffer(outIdx, false)
                        if (eos) break
                    }
                }
            }
        }

        var cachedPagePath: String? = null
        var cachedPageBmp: Bitmap? = null
        var cachedBackdropBmp: Bitmap? = null

        val paint = RenderPaints(width, height)

        try {
            for (frameIdx in 0 until totalFrames) {
                val timeMs = (frameIdx.toLong() * 1000L) / FPS.toLong()
                val activeShot = shots.firstOrNull { timeMs in it.startMs until it.endMs } ?: shots.last()

                if (cachedPagePath != activeShot.page.localImagePath) {
                    cachedPageBmp?.recycle()
                    cachedBackdropBmp?.recycle()
                    cachedPagePath = activeShot.page.localImagePath
                    cachedPageBmp = decodeBoundedPageBitmap(cachedPagePath!!, 1920)
                    cachedBackdropBmp = cachedPageBmp?.let { createDimmedBackdropBitmap(it, width, height) }
                }

                val canvas = inputSurface.lockCanvas(null)
                try {
                    drawMangaFrame(
                        canvas = canvas,
                        width = width,
                        height = height,
                        frameIdx = frameIdx,
                        timeMs = timeMs,
                        shot = activeShot,
                        pageBitmap = cachedPageBmp,
                        backdropBitmap = cachedBackdropBmp,
                        burnInSubtitles = burnInSubtitles,
                        paints = paint
                    )
                } finally {
                    inputSurface.unlockCanvasAndPost(canvas)
                }

                drainVideo(endOfStream = false)
                if (frameIdx % 12 == 0 || frameIdx == totalFrames - 1) {
                    onProgress((frameIdx + 1).toFloat() / totalFrames.toFloat())
                }
            }
            drainVideo(endOfStream = true)
        } finally {
            cachedPageBmp?.recycle()
            cachedBackdropBmp?.recycle()
            runCatching { inputSurface.release() }
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
            runCatching {
                if (muxerStarted) muxer.stop()
                muxer.release()
            }
        }
    }

    private fun drawMangaFrame(
        canvas: Canvas,
        width: Int,
        height: Int,
        frameIdx: Int,
        timeMs: Long,
        shot: ShotSegment,
        pageBitmap: Bitmap?,
        backdropBitmap: Bitmap?,
        burnInSubtitles: Boolean,
        paints: RenderPaints
    ) {
        canvas.drawColor(Color.rgb(6, 4, 12))

        // Layer 1: Holodeck dimmed parallax full-page backdrop
        if (backdropBitmap != null) {
            canvas.drawBitmap(backdropBitmap, 0f, 0f, null)
        }

        val viewportNorm: MangaNormalizedRect
        val atmosphere: MangaAtmospherePreset
        val activeDialogue: MangaSpeechLine?

        when (shot) {
            is EstablishShot -> {
                viewportNorm = shot.focusRect
                atmosphere = MangaAtmospherePreset.NONE
                activeDialogue = null
            }
            is PanTransitionShot -> {
                val rawT = ((timeMs - shot.startMs).toFloat() / (shot.endMs - shot.startMs).coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)
                val eased = easeInOutCubic(rawT)
                viewportNorm = lerpRect(shot.fromRect, shot.toRect, eased)
                atmosphere = shot.atmosphere
                activeDialogue = null
            }
            is PanelSpeakShot -> {
                val rawT = ((timeMs - shot.startMs).toFloat() / (shot.endMs - shot.startMs).coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)
                viewportNorm = lerpRect(shot.panel.cameraStartRect, shot.panel.cameraEndRect, rawT)
                atmosphere = shot.atmosphere
                activeDialogue = shot.lines.firstOrNull { timeMs in it.startMs until it.endMs }?.line
            }
        }

        // Layer 2: Active camera crop rendered crisp & centered with cyber frame border
        if (pageBitmap != null) {
            val bmpW = pageBitmap.width
            val bmpH = pageBitmap.height
            val srcLeft = (viewportNorm.left * bmpW).toInt().coerceIn(0, bmpW - 2)
            val srcTop = (viewportNorm.top * bmpH).toInt().coerceIn(0, bmpH - 2)
            val srcRight = (viewportNorm.right * bmpW).toInt().coerceIn(srcLeft + 1, bmpW)
            val srcBottom = (viewportNorm.bottom * bmpH).toInt().coerceIn(srcTop + 1, bmpH)
            val srcRect = Rect(srcLeft, srcTop, srcRight, srcBottom)

            val pad = minOf(width, height) * 0.045f
            val availW = width - pad * 2f
            val availH = height - pad * 2f
            val cropRatio = (srcRect.width().toFloat() / srcRect.height().toFloat().coerceAtLeast(1f))
            val frameRatio = availW / availH.coerceAtLeast(1f)
            val dstW: Float
            val dstH: Float
            if (cropRatio > frameRatio) {
                dstW = availW
                dstH = availW / cropRatio
            } else {
                dstH = availH
                dstW = availH * cropRatio
            }
            val dstLeft = (width - dstW) * 0.5f
            val dstTop = (height - dstH) * 0.46f
            val dstRect = RectF(dstLeft, dstTop, dstLeft + dstW, dstTop + dstH)

            // Subtle drop shadow + neon accent rim
            canvas.drawRoundRect(
                RectF(dstRect.left - 3f, dstRect.top - 3f, dstRect.right + 3f, dstRect.bottom + 3f),
                8f, 8f, paints.panelGlowPaint
            )
            canvas.drawBitmap(pageBitmap, srcRect, dstRect, paints.bitmapPaint)
            canvas.drawRoundRect(dstRect, 6f, 6f, paints.panelBorderPaint)
        }

        // Layer 3: GachaSwipe AtmosphereEngine deterministic procedural FX
        if (atmosphere != MangaAtmospherePreset.NONE) {
            drawAtmosphereParticles(canvas, width, height, frameIdx, atmosphere, paints)
        }

        // Layer 4: Synchronized Speaker & Subtitle HUD pill
        if (burnInSubtitles && activeDialogue != null && activeDialogue.sanitizedText.isNotBlank()) {
            drawSubtitleHud(canvas, width, height, activeDialogue, paints)
        }
    }

    private fun drawAtmosphereParticles(
        canvas: Canvas,
        width: Int,
        height: Int,
        frameIdx: Int,
        preset: MangaAtmospherePreset,
        paints: RenderPaints
    ) {
        val t = frameIdx / FPS.toFloat()
        when (preset) {
            MangaAtmospherePreset.EMBER_BATTLE -> {
                for (i in 0 until 16) {
                    val speed = 0.22f + (i % 5) * 0.06f
                    val yFrac = 1.05f - ((t * speed + i * 0.13f) % 1.15f)
                    val xFrac = ((i * 0.17f + sin(t * 1.4f + i) * 0.03f) % 1f).let { if (it < 0f) it + 1f else it }
                    val r = 4f + (i % 4) * 2.5f
                    paints.fxPaint.color = if (i % 2 == 0) Color.argb(195, 255, 110, 0) else Color.argb(215, 255, 215, 60)
                    canvas.drawCircle(xFrac * width, yFrac * height, r, paints.fxPaint)
                }
            }
            MangaAtmospherePreset.RAIN_STORM -> {
                paints.fxPaint.strokeWidth = 2.2f
                paints.fxPaint.color = Color.argb(135, 175, 235, 255)
                for (i in 0 until 26) {
                    val speed = 0.9f + (i % 4) * 0.25f
                    val y = ((t * speed + i * 0.09f) % 1.1f) * height
                    val x = ((i * 0.073f + t * 0.15f) % 1f) * width
                    canvas.drawLine(x, y, x - 14f, y + 46f, paints.fxPaint)
                }
            }
            MangaAtmospherePreset.SAKURA_PETALS -> {
                for (i in 0 until 14) {
                    val yFrac = ((t * (0.16f + (i % 3) * 0.05f) + i * 0.11f) % 1.1f)
                    val xFrac = ((i * 0.19f + cos(t + i) * 0.05f) % 1f).let { if (it < 0f) it + 1f else it }
                    paints.fxPaint.color = Color.argb(190, 255, 182, 205)
                    canvas.drawOval(
                        xFrac * width - 7f, yFrac * height - 4f,
                        xFrac * width + 7f, yFrac * height + 4f,
                        paints.fxPaint
                    )
                }
            }
            MangaAtmospherePreset.CYBER_SPARKLE -> {
                for (i in 0 until 14) {
                    val alpha = (95 + (140 * (0.5f + 0.5f * sin(t * 3.5f + i * 1.1f))).toInt()).coerceIn(40, 235)
                    val x = ((i * 0.137f + 0.07f) % 0.94f + 0.03f) * width
                    val y = ((i * 0.293f + 0.11f) % 0.88f + 0.06f) * height
                    paints.fxPaint.color = Color.argb(alpha, 0, 229, 255)
                    canvas.drawCircle(x, y, 3.5f + (i % 3) * 1.5f, paints.fxPaint)
                }
            }
            else -> Unit
        }
    }

    private fun drawSubtitleHud(
        canvas: Canvas,
        width: Int,
        height: Int,
        line: MangaSpeechLine,
        paints: RenderPaints
    ) {
        val text = line.sanitizedText
        val maxBoxW = width * 0.86f
        val maxTextW = maxBoxW - 44f
        val wrapped = wrapLines(text, paints.subTextPaint, maxTextW).take(3)
        val lineH = paints.subTextPaint.textSize * 1.32f
        val speakerH = paints.speakerTextPaint.textSize * 1.35f
        val boxH = speakerH + wrapped.size * lineH + 28f
        val left = (width - maxBoxW) * 0.5f
        val bottom = height - minOf(height * 0.045f, 34f)
        val top = bottom - boxH
        val boxRect = RectF(left, top, left + maxBoxW, bottom)

        canvas.drawRoundRect(boxRect, 14f, 14f, paints.subBgPaint)
        canvas.drawRoundRect(boxRect, 14f, 14f, paints.subBorderPaint)

        val speakerLabel = line.speakerName.ifBlank { "SPEAKER" }.uppercase()
        canvas.drawText(speakerLabel, left + 22f, top + 14f + paints.speakerTextPaint.textSize, paints.speakerTextPaint)

        var textY = top + 18f + speakerH + paints.subTextPaint.textSize * 0.85f
        for (row in wrapped) {
            canvas.drawText(row, left + 22f, textY, paints.subTextPaint)
            textY += lineH
        }
    }

    private fun wrapLines(text: String, paint: Paint, maxWidth: Float): List<String> {
        val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val lines = ArrayList<String>()
        var current = ""
        for (word in words) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (paint.measureText(candidate) <= maxWidth || current.isEmpty()) {
                current = candidate
            } else {
                lines += current
                current = word
            }
        }
        if (current.isNotEmpty()) lines += current
        return lines
    }

    private fun buildCombinedPcmStream(
        audioSpans: List<AudioSpan>,
        sampleRate: Int
    ): ByteArray {
        val out = ByteArrayOutputStream(audioSpans.sumOf { ((it.durationMs * sampleRate) / 1000L).toInt() * 2 })
        val silenceChunk = ByteArray(4096)
        for (span in audioSpans) {
            val targetBytes = (((span.durationMs * sampleRate) / 1000L).toInt() * 2).coerceAtLeast(2)
            val wav = span.wavFile
            if (wav != null && wav.isFile && wav.length() > 44L) {
                val slice = M4bAudiobookMuxer.readWavSlice(wav)
                val filePcm = FileInputStream(wav).use { fis ->
                    var skipped = 0L
                    while (skipped < slice.dataOffset) {
                        val s = fis.skip(slice.dataOffset - skipped)
                        if (s <= 0) break
                        skipped += s
                    }
                    val buf = ByteArray(slice.dataSizeBytes.toInt().coerceAtLeast(0))
                    var off = 0
                    while (off < buf.size) {
                        val n = fis.read(buf, off, buf.size - off)
                        if (n <= 0) break
                        off += n
                    }
                    buf
                }
                val writeLen = minOf(filePcm.size, targetBytes)
                out.write(filePcm, 0, writeLen)
                var rem = targetBytes - writeLen
                while (rem > 0) {
                    val step = minOf(rem, silenceChunk.size)
                    out.write(silenceChunk, 0, step)
                    rem -= step
                }
            } else {
                var rem = targetBytes
                while (rem > 0) {
                    val step = minOf(rem, silenceChunk.size)
                    out.write(silenceChunk, 0, step)
                    rem -= step
                }
            }
        }
        return out.toByteArray()
    }

    private fun encodeAacTrack(
        pcm16: ByteArray,
        sampleRate: Int,
        tempAudioFile: File
    ) {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 96_000)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()

        val muxer = MediaMuxer(tempAudioFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var trackIdx = -1
        var muxerStarted = false
        val info = MediaCodec.BufferInfo()

        fun drainAudio(eos: Boolean) {
            while (true) {
                val timeout = if (eos) 10_000L else 0L
                val outIdx = encoder.dequeueOutputBuffer(info, timeout)
                when {
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (!eos) break
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxerStarted) {
                            trackIdx = muxer.addTrack(encoder.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                    }
                    outIdx >= 0 -> {
                        val outBuf = encoder.getOutputBuffer(outIdx)
                        if (outBuf != null && info.size > 0 && muxerStarted &&
                            (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                        ) {
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            muxer.writeSampleData(trackIdx, outBuf, info)
                        }
                        val done = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        encoder.releaseOutputBuffer(outIdx, false)
                        if (done) break
                    }
                }
            }
        }

        try {
            var off = 0
            var framesFed = 0L
            while (off < pcm16.size) {
                val inIdx = encoder.dequeueInputBuffer(10_000L)
                if (inIdx >= 0) {
                    val inBuf = encoder.getInputBuffer(inIdx)!!
                    inBuf.clear()
                    val step = minOf(inBuf.remaining(), pcm16.size - off)
                    inBuf.put(pcm16, off, step)
                    val ptsUs = (framesFed * 1_000_000L) / sampleRate.toLong()
                    framesFed += (step / 2L)
                    encoder.queueInputBuffer(inIdx, 0, step, ptsUs, 0)
                    off += step
                }
                drainAudio(eos = false)
            }
            while (true) {
                val inIdx = encoder.dequeueInputBuffer(10_000L)
                if (inIdx >= 0) {
                    val eosPts = (framesFed * 1_000_000L) / sampleRate.toLong()
                    encoder.queueInputBuffer(inIdx, 0, 0, eosPts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    break
                }
            }
            drainAudio(eos = true)
        } finally {
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
            runCatching {
                if (muxerStarted) muxer.stop()
                muxer.release()
            }
        }
    }

    private fun muxVideoAndAudio(
        videoFile: File,
        audioFile: File,
        destMp4File: File
    ) {
        val vExt = MediaExtractor()
        val aExt = MediaExtractor()
        vExt.setDataSource(videoFile.absolutePath)
        aExt.setDataSource(audioFile.absolutePath)
        val muxer = MediaMuxer(destMp4File.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        try {
            vExt.selectTrack(0)
            aExt.selectTrack(0)
            val vTrack = muxer.addTrack(vExt.getTrackFormat(0))
            val aTrack = muxer.addTrack(aExt.getTrackFormat(0))
            muxer.start()

            val buf = ByteBuffer.allocateDirect(512 * 1024)
            val info = MediaCodec.BufferInfo()

            while (true) {
                val size = vExt.readSampleData(buf, 0)
                if (size < 0) break
                info.offset = 0
                info.size = size
                info.presentationTimeUs = vExt.sampleTime
                info.flags = if ((vExt.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                    MediaCodec.BUFFER_FLAG_KEY_FRAME
                } else {
                    0
                }
                muxer.writeSampleData(vTrack, buf, info)
                vExt.advance()
            }

            while (true) {
                val size = aExt.readSampleData(buf, 0)
                if (size < 0) break
                info.offset = 0
                info.size = size
                info.presentationTimeUs = aExt.sampleTime
                info.flags = 0
                muxer.writeSampleData(aTrack, buf, info)
                aExt.advance()
            }
        } finally {
            runCatching { vExt.release() }
            runCatching { aExt.release() }
            runCatching {
                muxer.stop()
                muxer.release()
            }
        }
    }

    private fun decodeBoundedPageBitmap(path: String, maxDim: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, opts)
        var sample = 1
        while (opts.outWidth / sample > maxDim || opts.outHeight / sample > maxDim) {
            sample *= 2
        }
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun createDimmedBackdropBitmap(page: Bitmap, width: Int, height: Int): Bitmap {
        val bg = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bg)
        val small = Bitmap.createScaledBitmap(
            page,
            (width / 6).coerceAtLeast(32),
            (height / 6).coerceAtLeast(32),
            true
        )
        c.drawBitmap(small, null, Rect(0, 0, width, height), Paint(Paint.FILTER_BITMAP_FLAG))
        small.recycle()

        val grad = Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                Color.argb(218, 6, 4, 14),
                Color.argb(236, 12, 6, 20),
                Shader.TileMode.CLAMP
            )
        }
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), grad)
        return bg
    }

    private fun easeInOutCubic(t: Float): Float =
        if (t < 0.5f) 4f * t * t * t else 1f - (-2f * t + 2f).let { it * it * it } / 2f

    private fun lerpRect(a: MangaNormalizedRect, b: MangaNormalizedRect, t: Float): MangaNormalizedRect =
        MangaNormalizedRect(
            left = a.left + (b.left - a.left) * t,
            top = a.top + (b.top - a.top) * t,
            right = a.right + (b.right - a.right) * t,
            bottom = a.bottom + (b.bottom - a.bottom) * t
        ).clamped()

    private fun publishMp4ToDownloads(context: Context, sourceFile: File, fileName: String): String = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "video/mp4")
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/PURR-TTS/MangaDubs")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { out ->
                    FileInputStream(sourceFile).use { input -> input.copyTo(out) }
                }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                return@runCatching "Downloads/PURR-TTS/MangaDubs/$fileName"
            }
        }
        @Suppress("DEPRECATION")
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "PURR-TTS/MangaDubs"
        ).apply { mkdirs() }
        val dest = File(dir, fileName)
        sourceFile.copyTo(dest, overwrite = true)
        "Downloads/PURR-TTS/MangaDubs/$fileName"
    }.getOrDefault("")

    private class RenderPaints(width: Int, height: Int) {
        val baseUnit = minOf(width, height).toFloat()
        val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val panelGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 6f
            color = Color.argb(120, 0, 229, 255)
            maskFilter = BlurMaskFilter(10f, BlurMaskFilter.Blur.NORMAL)
        }
        val panelBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = Color.argb(230, 0, 229, 255)
        }
        val fxPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val subBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(215, 8, 6, 16)
        }
        val subBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
            color = Color.argb(210, 255, 51, 102)
        }
        val speakerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(255, 0, 229, 255)
            textSize = (baseUnit * 0.028f).coerceIn(16f, 28f)
            isFakeBoldText = true
        }
        val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(255, 245, 245, 250)
            textSize = (baseUnit * 0.032f).coerceIn(18f, 32f)
        }
    }
}
