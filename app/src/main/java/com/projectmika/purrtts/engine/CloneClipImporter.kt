package com.projectmika.purrtts.engine

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log

/**
 * ✨ MIKA'S CLONE CLIP IMPORTER (ANY AUDIO → MONO PCM) ✨
 * Decodes a user-selected audio document (mp3, m4a/aac, ogg/opus, flac, wav — anything the
 * platform decoder handles) into mono float32 PCM at its native sample rate via MediaExtractor
 * + MediaCodec. Output feeds PurrCloneAudioCleaner → selectBestClip → the clone reference WAV.
 *
 * Guardrails:
 * - Hard 30s capture cap (memory guard; Pocket TTS needs ~5s, ZipVoice ~12s of reference).
 * - Stereo/multichannel downmixes to mono by channel averaging.
 * - Any decoder error fails loudly (caller shows a toast) — never silently returns junk audio.
 */
object CloneClipImporter {

    private const val TAG = "PurrCloneImport"
    private const val MAX_CAPTURE_SECONDS = 30
    private const val TIMEOUT_US = 10_000L

    data class DecodedClip(val samples: FloatArray, val sampleRateHz: Int)

    /**
     * Decodes [uri] to mono float samples. Returns null when the document has no audio track
     * or the platform decoder cannot handle the format.
     */
    fun decodeToMonoPcm(context: Context, uri: Uri): DecodedClip? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)

            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val trackFormat = extractor.getTrackFormat(i)
                val mime = trackFormat.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = trackFormat
                    extractor.selectTrack(i)
                    break
                }
            }
            if (trackIndex < 0 || format == null) {
                Log.w(TAG, "[IMPORT_ERR] No audio track in selected document.")
                return null
            }

            val mime = format.getString(MediaFormat.KEY_MIME)!!
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val maxSamples = MAX_CAPTURE_SECONDS * sampleRate.toLong() * channels
            val accumulated = ArrayList<Float>(MAX_CAPTURE_SECONDS * sampleRate)
            val info = MediaCodec.BufferInfo()
            var sawInputEos = false
            var sawOutputEos = false

            while (!sawOutputEos && accumulated.size < maxSamples) {
                if (!sawInputEos) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inIndex)!!
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            sawInputEos = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIndex >= 0 -> {
                        val outBuffer = codec.getOutputBuffer(outIndex)!!
                        if (info.size > 0 && channels > 0) {
                            val shortBuf = outBuffer.order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            val frameCount = info.size / 2 / channels
                            for (f in 0 until frameCount) {
                                var acc = 0f
                                for (c in 0 until channels) acc += shortBuf.get(f * channels + c) / 32768f
                                accumulated.add((acc / channels).coerceIn(-1f, 1f))
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEos = true
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFormat = codec.outputFormat
                        sampleRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                }
            }

            if (accumulated.isEmpty()) {
                Log.w(TAG, "[IMPORT_ERR] Decoder produced no PCM for $uri")
                return null
            }
            Log.i(TAG, "[IMPORT_OK] Decoded ${accumulated.size} mono samples @ ${sampleRate}Hz")
            return DecodedClip(accumulated.toFloatArray(), sampleRate)
        } catch (err: Exception) {
            Log.e(TAG, "[IMPORT_ERR] Decode failed: ${err.message}", err)
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }
}
