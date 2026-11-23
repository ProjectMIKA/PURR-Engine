package com.projectmika.purrtts.engine.manga

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import com.projectmika.purrtts.core.manga.MangaAtmospherePreset
import com.projectmika.purrtts.core.manga.MangaChapterEntry
import com.projectmika.purrtts.core.manga.MangaDeterministicEffectsEngine
import com.projectmika.purrtts.core.manga.MangaEmotionalPacer
import com.projectmika.purrtts.core.manga.MangaNormalizedRect
import com.projectmika.purrtts.core.manga.MangaPageEntry
import com.projectmika.purrtts.core.manga.MangaPanelCameraEffect
import com.projectmika.purrtts.core.manga.MangaPanelNode
import com.projectmika.purrtts.core.manga.MangaReadingOrder
import com.projectmika.purrtts.core.manga.MangaSpeechLine
import com.projectmika.purrtts.core.manga.MangaVideoAspect
import com.projectmika.purrtts.core.manga.MangaVideoQuality
import com.projectmika.purrtts.core.purr.PurrDspProcessor
import com.projectmika.purrtts.engine.audiobook.M4bAudiobookMuxer
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * ✨ HARDWARE MEDIACODEC MOTION-COMIC .MP4 VIDEO & AAC MUXER (TOOL-11) ✨
 *
 * Encodes a complete dubbed manga chapter into a hardware H.264/AVC + AAC `.mp4`:
 * - Uses EGL14 + `eglPresentationTimeANDROID` + deterministic output PTS normalization so
 *   every panel locks 1:1 with its spoken audio span (zero fast-forwarding or end-frame freezes).
 * - Decodes high-resolution page sources per `MangaVideoQuality` (480p / 720p / 1080p) and allocates
 *   dedicated bitrate headroom without rate-controller starvation.
 * - Executes `MangaDeterministicEffectsEngine` reading sweeps, readable entry/exit hold plateaus,
 *   impact bursts, and non-overlapping bottom subtitle HUDs.
 */
object MangaMotionVideoMuxer {

    private const val TAG = "MangaMotionVideoMuxer"
    private const val FPS = 24
    private const val ESTABLISH_HOLD_MS = 420L
    private const val PAN_TRANSITION_MS = 380L
    private const val EGL_RECORDABLE_ANDROID = 0x3142

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
        val cameraEffect: MangaPanelCameraEffect,
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
        quality: MangaVideoQuality = MangaVideoQuality.HD_720P,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): RenderResult {
        if (chapter.pages.isEmpty()) {
            throw IllegalStateException("Chapter has no pages to render")
        }

        val (width, height) = aspect.resolveDimensions(quality)
        val pad = minOf(width, height) * 0.028f
        val subtitleReserveH = if (burnInSubtitles) {
            if (width < height) height * 0.165f else height * 0.21f
        } else {
            0f
        }
        val availW = (width - pad * 2f).coerceAtLeast(64f)
        val availH = (height - pad * 2f - subtitleReserveH).coerceAtLeast(height * 0.45f)
        val stageAspect = availW / availH.coerceAtLeast(1f)

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
            val totalPanelsOnPage = page.panels.size
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
                val camEffect = MangaDeterministicEffectsEngine.resolveCameraEffect(
                    panel = panel,
                    totalPanelsOnPage = totalPanelsOnPage,
                    videoAspect = aspect
                )
                val entryState = MangaDeterministicEffectsEngine.computeViewportState(
                    panel = panel,
                    effect = camEffect,
                    progress = 0f,
                    readingOrder = chapter.readingOrder,
                    videoAspect = aspect,
                    stageAspect = stageAspect
                )
                val exitState = MangaDeterministicEffectsEngine.computeViewportState(
                    panel = panel,
                    effect = camEffect,
                    progress = 1f,
                    readingOrder = chapter.readingOrder,
                    videoAspect = aspect,
                    stageAspect = stageAspect
                )

                shots += PanTransitionShot(
                    startMs = cursorMs,
                    endMs = cursorMs + PAN_TRANSITION_MS,
                    page = page,
                    fromRect = prevRect,
                    toRect = entryState.sourceCropRect,
                    atmosphere = atmo
                )
                audioSpans += AudioSpan(cursorMs, PAN_TRANSITION_MS, null)
                cursorMs += PAN_TRANSITION_MS

                val panelStartMs = cursorMs
                val timedLines = ArrayList<TimedDialogueSpan>(panel.lines.size)
                if (panel.lines.isEmpty()) {
                    val silentHold = 750L
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
                    cameraEffect = camEffect,
                    lines = timedLines
                )
                prevRect = exitState.sourceCropRect
            }
        }

        val totalDurationMs = cursorMs.coerceAtLeast(1000L)
        val totalFrames = (((totalDurationMs * FPS) + 999L) / 1000L).toInt().coerceAtLeast(24)

        destMp4File.parentFile?.mkdirs()
        val tempVideoFile = File(destMp4File.parentFile, "${destMp4File.nameWithoutExtension}.vid.tmp.mp4")
        val tempAudioFile = File(destMp4File.parentFile, "${destMp4File.nameWithoutExtension}.aud.tmp.mp4")
        if (tempVideoFile.exists()) tempVideoFile.delete()
        if (tempAudioFile.exists()) tempAudioFile.delete()
        if (destMp4File.exists()) destMp4File.delete()

        try {
            onProgress(0.05f, "ENCODING VIDEO ($totalFrames FRAMES @ ${width}x${height} • ${quality.shortLabel})...")
            encodeVideoTrack(
                width = width,
                height = height,
                pad = pad,
                availW = availW,
                availH = availH,
                stageAspect = stageAspect,
                aspect = aspect,
                quality = quality,
                readingOrder = chapter.readingOrder,
                totalFrames = totalFrames,
                totalDurationMs = totalDurationMs,
                shots = shots,
                burnInSubtitles = burnInSubtitles,
                tempVideoFile = tempVideoFile,
                onProgress = { frameDone, frameTotal ->
                    val p = frameDone.toFloat() / frameTotal.coerceAtLeast(1).toFloat()
                    onProgress(
                        0.05f + p * 0.65f,
                        "RENDERING VIDEO FRAME $frameDone/$frameTotal (${(p * 100).toInt()}%)..."
                    )
                }
            )

            onProgress(0.71f, "ASSEMBLING PCM AUDIO TIMELINE (${audioSpans.size} SPANS)...")
            val pcmStream = buildCombinedPcmStream(audioSpans, sampleRate)
            onProgress(0.74f, "ENCODING AAC VOICEOVER TRACK (0%)...")
            encodeAacTrack(
                pcm16 = pcmStream,
                sampleRate = sampleRate,
                bitrateBps = quality.audioBitrateBps,
                tempAudioFile = tempAudioFile,
                onProgress = { p ->
                    onProgress(
                        0.74f + p * 0.16f,
                        "ENCODING AAC VOICEOVER TRACK (${(p * 100).toInt()}%)..."
                    )
                }
            )

            onProgress(0.91f, "MUXING .MP4 VIDEO & AUDIO...")
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
        pad: Float,
        availW: Float,
        availH: Float,
        stageAspect: Float,
        aspect: MangaVideoAspect,
        quality: MangaVideoQuality,
        readingOrder: MangaReadingOrder,
        totalFrames: Int,
        totalDurationMs: Long,
        shots: List<ShotSegment>,
        burnInSubtitles: Boolean,
        tempVideoFile: File,
        onProgress: (Int, Int) -> Unit
    ) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, quality.videoBitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = encoder.createInputSurface()
        encoder.start()

        val eglRenderer = runCatching {
            EglBitmapSurfaceRenderer(inputSurface, width, height)
        }.onFailure { err ->
            Log.w(TAG, "EGL surface presentation timer unavailable; using Surface Canvas + PTS override", err)
        }.getOrNull()
        val frameBitmap = if (eglRenderer != null) {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        } else {
            null
        }
        val bitmapCanvas = frameBitmap?.let(::Canvas)

        val muxer = MediaMuxer(tempVideoFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var trackIdx = -1
        var muxerStarted = false
        val bufferInfo = MediaCodec.BufferInfo()
        var encodedSampleCount = 0L
        var lastWrittenPtsUs = -1L

        fun drainVideo(endOfStream: Boolean, allowBriefWait: Boolean) {
            if (endOfStream) {
                encoder.signalEndOfInputStream()
            }
            var eosWaitTries = 0
            var pollCount = 0
            while (true) {
                val timeout = when {
                    endOfStream -> 10_000L
                    allowBriefWait && pollCount == 0 -> 2_500L
                    else -> 0L
                }
                pollCount++
                val outIdx = encoder.dequeueOutputBuffer(bufferInfo, timeout)
                when {
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (!endOfStream) break
                        if (++eosWaitTries >= 300) break
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxerStarted) {
                            trackIdx = muxer.addTrack(encoder.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                    }
                    outIdx >= 0 -> {
                        eosWaitTries = 0
                        val outBuf = encoder.getOutputBuffer(outIdx)
                        val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        if (outBuf != null && bufferInfo.size > 0 && muxerStarted && !isConfig) {
                            val targetPtsUs = ((encodedSampleCount * 1_000_000L) / FPS.toLong())
                                .coerceAtLeast(lastWrittenPtsUs + 1L)
                            bufferInfo.presentationTimeUs = targetPtsUs
                            lastWrittenPtsUs = targetPtsUs
                            encodedSampleCount++
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
            val maxTimeMs = (totalDurationMs - 1L).coerceAtLeast(0L)
            for (frameIdx in 0 until totalFrames) {
                val timeMs = ((frameIdx.toLong() * 1000L) / FPS.toLong()).coerceAtMost(maxTimeMs)
                val activeShot = shots.firstOrNull { timeMs in it.startMs until it.endMs } ?: shots.last()

                if (cachedPagePath != activeShot.page.localImagePath) {
                    cachedPageBmp?.recycle()
                    cachedBackdropBmp?.recycle()
                    cachedPagePath = activeShot.page.localImagePath
                    cachedPageBmp = decodeBoundedPageBitmap(cachedPagePath!!, quality.maxPageDecodeDimPx)
                    cachedBackdropBmp = cachedPageBmp?.let { createDimmedBackdropBitmap(it, width, height) }
                }

                if (eglRenderer != null && frameBitmap != null && bitmapCanvas != null) {
                    drawMangaFrame(
                        canvas = bitmapCanvas,
                        width = width,
                        height = height,
                        pad = pad,
                        availW = availW,
                        availH = availH,
                        stageAspect = stageAspect,
                        aspect = aspect,
                        readingOrder = readingOrder,
                        frameIdx = frameIdx,
                        timeMs = timeMs,
                        shot = activeShot,
                        pageBitmap = cachedPageBmp,
                        backdropBitmap = cachedBackdropBmp,
                        burnInSubtitles = burnInSubtitles,
                        paints = paint
                    )
                    val ptsNs = (frameIdx.toLong() * 1_000_000_000L) / FPS.toLong()
                    eglRenderer.drawBitmap(frameBitmap, ptsNs)
                } else {
                    val canvas = inputSurface.lockCanvas(null)
                    try {
                        drawMangaFrame(
                            canvas = canvas,
                            width = width,
                            height = height,
                            pad = pad,
                            availW = availW,
                            availH = availH,
                            stageAspect = stageAspect,
                            aspect = aspect,
                            readingOrder = readingOrder,
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
                }

                drainVideo(endOfStream = false, allowBriefWait = frameIdx >= 2)
                if (frameIdx % 8 == 0 || frameIdx == totalFrames - 1) {
                    onProgress(frameIdx + 1, totalFrames)
                }
            }
            drainVideo(endOfStream = true, allowBriefWait = true)
        } finally {
            cachedPageBmp?.recycle()
            cachedBackdropBmp?.recycle()
            frameBitmap?.recycle()
            runCatching { eglRenderer?.release() }
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
        pad: Float,
        availW: Float,
        availH: Float,
        stageAspect: Float,
        aspect: MangaVideoAspect,
        readingOrder: MangaReadingOrder,
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
        val camState: MangaDeterministicEffectsEngine.PanelCameraFrameState?

        when (shot) {
            is EstablishShot -> {
                viewportNorm = shot.focusRect
                atmosphere = MangaAtmospherePreset.NONE
                activeDialogue = null
                camState = null
            }
            is PanTransitionShot -> {
                val rawT = ((timeMs - shot.startMs).toFloat() / (shot.endMs - shot.startMs).coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)
                viewportNorm = lerpRectWithZoomArc(shot.fromRect, shot.toRect, rawT, aspect)
                atmosphere = shot.atmosphere
                activeDialogue = null
                camState = null
            }
            is PanelSpeakShot -> {
                val rawT = ((timeMs - shot.startMs).toFloat() / (shot.endMs - shot.startMs).coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)
                val state = MangaDeterministicEffectsEngine.computeViewportState(
                    panel = shot.panel,
                    effect = shot.cameraEffect,
                    progress = rawT,
                    readingOrder = readingOrder,
                    videoAspect = aspect,
                    stageAspect = stageAspect
                )
                camState = state
                viewportNorm = state.sourceCropRect
                atmosphere = shot.atmosphere
                activeDialogue = shot.lines.firstOrNull { timeMs in it.startMs until it.endMs }?.line
                    ?: shot.lines.lastOrNull { timeMs >= it.startMs }?.takeIf { timeMs <= it.endMs + 450L }?.line
            }
        }

        // Layer 2: Active camera crop rendered crisp inside upper stage (zero overlap with subtitles)
        if (pageBitmap != null) {
            val bmpW = pageBitmap.width
            val bmpH = pageBitmap.height
            val srcLeft = (viewportNorm.left * bmpW).toInt().coerceIn(0, bmpW - 2)
            val srcTop = (viewportNorm.top * bmpH).toInt().coerceIn(0, bmpH - 2)
            val srcRight = (viewportNorm.right * bmpW).toInt().coerceIn(srcLeft + 1, bmpW)
            val srcBottom = (viewportNorm.bottom * bmpH).toInt().coerceIn(srcTop + 1, bmpH)
            val srcRect = Rect(srcLeft, srcTop, srcRight, srcBottom)

            val cropRatio = srcRect.width().toFloat() / srcRect.height().toFloat().coerceAtLeast(1f)
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
            val shakePxX = (camState?.shakeOffsetXFrac ?: 0f) * width
            val shakePxY = (camState?.shakeOffsetYFrac ?: 0f) * height
            val dstLeft = pad + (availW - dstW) * 0.5f + shakePxX
            val dstTop = pad + (availH - dstH) * 0.5f + shakePxY
            val dstRect = RectF(dstLeft, dstTop, dstLeft + dstW, dstTop + dstH)

            // Crisp dual-stroke neon border (hardware-fast, zero CPU BlurMaskFilter stall)
            canvas.drawRoundRect(
                RectF(dstRect.left - 3f, dstRect.top - 3f, dstRect.right + 3f, dstRect.bottom + 3f),
                8f, 8f, paints.panelGlowPaint
            )
            canvas.drawBitmap(pageBitmap, srcRect, dstRect, paints.bitmapPaint)

            if (camState?.showSpeedLines == true) {
                drawImpactSpeedLines(canvas, width, height, dstRect, frameIdx, camState.impactFlashAlpha, paints)
            }

            canvas.drawRoundRect(dstRect, 6f, 6f, paints.panelBorderPaint)
        }

        // Layer 3: GachaSwipe AtmosphereEngine deterministic procedural FX
        if (atmosphere != MangaAtmospherePreset.NONE) {
            drawAtmosphereParticles(canvas, width, height, frameIdx, atmosphere, paints)
        }

        // Layer 4: Synchronized Speaker & Subtitle HUD pill (optional, strictly inside bottom zone)
        if (burnInSubtitles && activeDialogue != null && activeDialogue.sanitizedText.isNotBlank()) {
            drawSubtitleHud(canvas, width, height, pad, activeDialogue, paints)
        }
    }

    private fun drawImpactSpeedLines(
        canvas: Canvas,
        width: Int,
        height: Int,
        dstRect: RectF,
        frameIdx: Int,
        flashAlpha: Float,
        paints: RenderPaints
    ) {
        if (flashAlpha > 0.01f) {
            paints.fxPaint.style = Paint.Style.FILL
            paints.fxPaint.color = Color.argb((flashAlpha * 255).toInt().coerceIn(0, 90), 255, 255, 255)
            canvas.drawRoundRect(dstRect, 6f, 6f, paints.fxPaint)
        }
        val cx = dstRect.centerX()
        val cy = dstRect.centerY()
        val maxR = minOf(width, height) * 0.85f
        val innerR = minOf(dstRect.width(), dstRect.height()) * 0.44f
        paints.speedLinePaint.color = Color.argb(110, 220, 248, 255)
        for (i in 0 until 20) {
            val angle = ((i * 18f) + (frameIdx % 3) * 4f) * (PI.toFloat() / 180f)
            val dx = cos(angle)
            val dy = sin(angle)
            val r0 = innerR + ((i % 3) * 14f)
            canvas.drawLine(
                cx + dx * r0,
                cy + dy * r0,
                cx + dx * maxR,
                cy + dy * maxR,
                paints.speedLinePaint
            )
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
        pad: Float,
        line: MangaSpeechLine,
        paints: RenderPaints
    ) {
        val text = line.sanitizedText
        val maxBoxW = width * 0.92f
        val maxTextW = maxBoxW - 36f
        val wrapped = wrapLines(text, paints.subTextPaint, maxTextW).take(3)
        val lineH = paints.subTextPaint.textSize * 1.28f
        val speakerH = paints.speakerTextPaint.textSize * 1.28f
        val boxH = speakerH + wrapped.size * lineH + 20f
        val left = (width - maxBoxW) * 0.5f
        val bottom = height - pad.coerceIn(10f, 30f)
        val top = bottom - boxH
        val boxRect = RectF(left, top, left + maxBoxW, bottom)

        canvas.drawRoundRect(boxRect, 12f, 12f, paints.subBgPaint)
        canvas.drawRoundRect(boxRect, 12f, 12f, paints.subBorderPaint)

        val speakerLabel = line.speakerName.ifBlank { "SPEAKER" }.uppercase()
        canvas.drawText(speakerLabel, left + 18f, top + 10f + paints.speakerTextPaint.textSize, paints.speakerTextPaint)

        var textY = top + 14f + speakerH + paints.subTextPaint.textSize * 0.85f
        for (row in wrapped) {
            canvas.drawText(row, left + 18f, textY, paints.subTextPaint)
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
        bitrateBps: Int = 128_000,
        tempAudioFile: File,
        onProgress: (Float) -> Unit = {}
    ) {
        val safePcm = if (pcm16.size >= 2) pcm16 else ByteArray(2048)
        val evenSize = safePcm.size - (safePcm.size and 1)
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
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

        fun drainAudio(eos: Boolean): Boolean {
            var sawEos = false
            var eosWaitTries = 0
            while (true) {
                val timeout = if (eos) 10_000L else 0L
                val outIdx = encoder.dequeueOutputBuffer(info, timeout)
                when {
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (!eos) break
                        if (++eosWaitTries >= 250) break
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxerStarted) {
                            trackIdx = muxer.addTrack(encoder.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                    }
                    outIdx >= 0 -> {
                        eosWaitTries = 0
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
                        if (done) {
                            sawEos = true
                            break
                        }
                    }
                }
            }
            return sawEos
        }

        try {
            var off = 0
            var framesFed = 0L
            var lastReportedBucket = -1
            while (off < evenSize) {
                val inIdx = encoder.dequeueInputBuffer(10_000L)
                if (inIdx >= 0) {
                    val inBuf = encoder.getInputBuffer(inIdx)!!
                    inBuf.clear()
                    val step = minOf(inBuf.remaining(), evenSize - off) and 1.inv()
                    if (step > 0) {
                        inBuf.put(safePcm, off, step)
                        val ptsUs = (framesFed * 1_000_000L) / sampleRate.toLong()
                        framesFed += (step / 2L)
                        encoder.queueInputBuffer(inIdx, 0, step, ptsUs, 0)
                        off += step
                        val bucket = ((off.toFloat() / evenSize.coerceAtLeast(1).toFloat()) * 20f).toInt()
                        if (bucket != lastReportedBucket) {
                            lastReportedBucket = bucket
                            onProgress(off.toFloat() / evenSize.coerceAtLeast(1).toFloat())
                        }
                    }
                }
                drainAudio(eos = false)
            }
            onProgress(1f)

            var eosQueued = false
            for (attempt in 0 until 200) {
                val inIdx = encoder.dequeueInputBuffer(10_000L)
                if (inIdx >= 0) {
                    val eosPts = (framesFed * 1_000_000L) / sampleRate.toLong()
                    encoder.queueInputBuffer(inIdx, 0, 0, eosPts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    eosQueued = true
                    break
                }
                if (drainAudio(eos = false)) break
            }
            if (eosQueued) {
                drainAudio(eos = true)
            }
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
                buf.clear()
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
                buf.clear()
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
        return BitmapFactory.decodeFile(
            path,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        )
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

    private fun lerpRectWithZoomArc(
        from: MangaNormalizedRect,
        to: MangaNormalizedRect,
        rawT: Float,
        aspect: MangaVideoAspect
    ): MangaNormalizedRect {
        val clampedT = rawT.coerceIn(0f, 1f)
        val t = easeInOutCubic(clampedT)
        val cx = from.centerX + (to.centerX - from.centerX) * t
        val cy = from.centerY + (to.centerY - from.centerY) * t
        val baseW = from.width + (to.width - from.width) * t
        val baseH = from.height + (to.height - from.height) * t
        val peakArc = if (aspect == MangaVideoAspect.VERTICAL_9_16) 0.018f else 0.032f
        val arcExpand = 1f + sin(clampedT * PI.toFloat()) * peakArc
        val halfW = (baseW * arcExpand) * 0.5f
        val halfH = (baseH * arcExpand) * 0.5f
        val l = (cx - halfW).coerceIn(0f, 0.98f)
        val top = (cy - halfH).coerceIn(0f, 0.98f)
        val r = (cx + halfW).coerceIn(l + 0.02f, 1f)
        val b = (cy + halfH).coerceIn(top + 0.02f, 1f)
        return MangaNormalizedRect(l, top, r, b).clamped()
    }

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
            strokeWidth = (baseUnit * 0.011f).coerceIn(5f, 12f)
            color = Color.argb(65, 0, 229, 255)
        }
        val panelBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (baseUnit * 0.0042f).coerceIn(2.5f, 5f)
            color = Color.argb(235, 0, 229, 255)
        }
        val speedLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (baseUnit * 0.003f).coerceIn(1.8f, 4f)
            color = Color.argb(115, 220, 248, 255)
        }
        val fxPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val subBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(225, 8, 6, 16)
        }
        val subBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (baseUnit * 0.0035f).coerceIn(2f, 4.5f)
            color = Color.argb(215, 255, 51, 102)
        }
        val speakerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(255, 0, 229, 255)
            textSize = (baseUnit * 0.026f).coerceIn(13f, 32f)
            isFakeBoldText = true
        }
        val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(255, 245, 245, 250)
            textSize = (baseUnit * 0.032f).coerceIn(16f, 38f)
        }
    }

    /**
     * Renders `Bitmap` frames via OpenGL ES 2.0 onto `MediaCodec`'s `inputSurface` and stamps
     * explicit synthetic timestamps with `EGLExt.eglPresentationTimeANDROID` so hardware rate
     * control and muxed frame PTS stay locked cleanly at `FPS`.
     */
    private class EglBitmapSurfaceRenderer(
        inputSurface: Surface,
        private val width: Int,
        private val height: Int
    ) {
        private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
        private var programId: Int = 0
        private var textureId: Int = 0
        private var textureAllocated = false
        private var aPosHandle = -1
        private var aTexHandle = -1
        private var uTexHandle = -1

        private val quadVertices: FloatBuffer = makeFloatBuffer(
            floatArrayOf(
                -1f, -1f,
                1f, -1f,
                -1f, 1f,
                1f, 1f
            )
        )
        private val quadTexCoords: FloatBuffer = makeFloatBuffer(
            floatArrayOf(
                0f, 1f,
                1f, 1f,
                0f, 0f,
                1f, 0f
            )
        )

        init {
            try {
                eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }

                val vers = IntArray(2)
                check(EGL14.eglInitialize(eglDisplay, vers, 0, vers, 1)) { "eglInitialize failed" }

                val config = chooseEglConfig(eglDisplay)
                val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
                eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
                check(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }

                val surfAttribs = intArrayOf(EGL14.EGL_NONE)
                eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, config, inputSurface, surfAttribs, 0)
                check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }

                check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) { "eglMakeCurrent failed" }

                val vs = compileShader(
                    GLES20.GL_VERTEX_SHADER,
                    """
                    attribute vec2 aPosition;
                    attribute vec2 aTexCoord;
                    varying vec2 vTexCoord;
                    void main() {
                        gl_Position = vec4(aPosition, 0.0, 1.0);
                        vTexCoord = aTexCoord;
                    }
                    """.trimIndent()
                )
                val fs = compileShader(
                    GLES20.GL_FRAGMENT_SHADER,
                    """
                    precision mediump float;
                    varying vec2 vTexCoord;
                    uniform sampler2D uTexture;
                    void main() {
                        gl_FragColor = texture2D(uTexture, vTexCoord);
                    }
                    """.trimIndent()
                )
                programId = GLES20.glCreateProgram()
                GLES20.glAttachShader(programId, vs)
                GLES20.glAttachShader(programId, fs)
                GLES20.glLinkProgram(programId)
                val linkStatus = IntArray(1)
                GLES20.glGetProgramiv(programId, GLES20.GL_LINK_STATUS, linkStatus, 0)
                GLES20.glDeleteShader(vs)
                GLES20.glDeleteShader(fs)
                check(linkStatus[0] == GLES20.GL_TRUE) { "GLES program link failed" }

                aPosHandle = GLES20.glGetAttribLocation(programId, "aPosition")
                aTexHandle = GLES20.glGetAttribLocation(programId, "aTexCoord")
                uTexHandle = GLES20.glGetUniformLocation(programId, "uTexture")

                val texIds = IntArray(1)
                GLES20.glGenTextures(1, texIds, 0)
                textureId = texIds[0]
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            } catch (err: Throwable) {
                release()
                throw err
            }
        }

        fun drawBitmap(bitmap: Bitmap, ptsNs: Long) {
            if (EGL14.eglGetCurrentContext() != eglContext ||
                EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW) != eglSurface
            ) {
                EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
            }
            GLES20.glViewport(0, 0, width, height)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 4)
            if (!textureAllocated) {
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
                textureAllocated = true
            } else {
                GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bitmap)
            }

            GLES20.glUseProgram(programId)
            GLES20.glUniform1i(uTexHandle, 0)

            quadVertices.position(0)
            GLES20.glEnableVertexAttribArray(aPosHandle)
            GLES20.glVertexAttribPointer(aPosHandle, 2, GLES20.GL_FLOAT, false, 0, quadVertices)

            quadTexCoords.position(0)
            GLES20.glEnableVertexAttribArray(aTexHandle)
            GLES20.glVertexAttribPointer(aTexHandle, 2, GLES20.GL_FLOAT, false, 0, quadTexCoords)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            GLES20.glDisableVertexAttribArray(aPosHandle)
            GLES20.glDisableVertexAttribArray(aTexHandle)

            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, ptsNs)
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        }

        fun release() {
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                runCatching {
                    EGL14.eglMakeCurrent(
                        eglDisplay,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_CONTEXT
                    )
                }
                if (textureId != 0) {
                    runCatching { GLES20.glDeleteTextures(1, intArrayOf(textureId), 0) }
                    textureId = 0
                }
                if (programId != 0) {
                    runCatching { GLES20.glDeleteProgram(programId) }
                    programId = 0
                }
                if (eglSurface != EGL14.EGL_NO_SURFACE) {
                    runCatching { EGL14.eglDestroySurface(eglDisplay, eglSurface) }
                    eglSurface = EGL14.EGL_NO_SURFACE
                }
                if (eglContext != EGL14.EGL_NO_CONTEXT) {
                    runCatching { EGL14.eglDestroyContext(eglDisplay, eglContext) }
                    eglContext = EGL14.EGL_NO_CONTEXT
                }
                runCatching { EGL14.eglTerminate(eglDisplay) }
                eglDisplay = EGL14.EGL_NO_DISPLAY
            }
        }

        private fun chooseEglConfig(display: EGLDisplay): EGLConfig {
            val attribsPrimary = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE
            )
            val attribsFallback = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            if (EGL14.eglChooseConfig(display, attribsPrimary, 0, configs, 0, 1, numConfigs, 0) &&
                numConfigs[0] > 0 && configs[0] != null
            ) {
                return configs[0]!!
            }
            check(
                EGL14.eglChooseConfig(display, attribsFallback, 0, configs, 0, 1, numConfigs, 0) &&
                    numConfigs[0] > 0 && configs[0] != null
            ) { "eglChooseConfig failed" }
            return configs[0]!!
        }

        private fun compileShader(type: Int, src: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, src)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            check(status[0] == GLES20.GL_TRUE) { "GLES shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}" }
            return shader
        }

        private fun makeFloatBuffer(data: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(data.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(data)
                    position(0)
                }
    }
}
