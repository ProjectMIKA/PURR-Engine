package com.projectmika.purrtts.core.purr

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.random.Random

/**
 * ✨ MIKA'S REAL-TIME 24kHz PCM DSP & POST-EFFECTS RACK (GACHA-SWIPE EDITION) ✨
 * Ports GachaSwipe's `CyberAudioNote` WebAudio graph to clean 24kHz Kotlin PCM:
 * 1. Anti-Pop Fade-In (25ms) + GachaSwipe Exponential Sentence Tail Taper (120ms)
 * 2. 80Hz Rumble High-Pass + 8.5kHz High-Shelf De-Esser + 250Hz Peaking Chest Warmth EQ
 * 3. Dynamic Glue Compressor (-20dB, 2.5:1) with zero noise-floor pumping
 * 4. All 10 GachaSwipe Voice Effects (Clean Studio, Intimate Warmth, Ethereal, Angelic, Stage Mic, Radio, Tiny, Feral, Liquid, Monster)
 * 5. Master Soft Limiter (-1dB headroom)
 */
object PurrDspProcessor {

    const val SAMPLE_RATE_HZ = 24000

    fun process(
        input: FloatArray,
        sampleRate: Int = SAMPLE_RATE_HZ,
        config: DspConfig,
        volumeGain: Float = 1.0f,
        dspEnabled: Boolean = true
    ): FloatArray {
        if (input.isEmpty()) return input

        var buffer = input.copyOf()

        // 1. GachaSwipe Envelope Stage: 25ms Anti-Pop Fade-In + 120ms Sentence Tail Taper
        buffer = applyEnvelopeTaper(buffer, sampleRate, config)

        val isEffectActive = config.effectPreset != AcousticEffectPreset.NONE
        val isWarmPreset = config.effectPreset == AcousticEffectPreset.WARM_STUDIO
        val effectiveWarmthDb = if (isWarmPreset) max(config.warmthEqDb, 2.0f).toDouble() else config.warmthEqDb.toDouble()
        val effectivePresenceDb = if (isWarmPreset) min(config.presenceEqDb, -1.5f).toDouble() else config.presenceEqDb.toDouble()
        val needsEqOrDynamics = isEffectActive ||
            abs(effectiveWarmthDb) > 0.05 ||
            abs(effectivePresenceDb) > 0.05 ||
            config.reverbWetMix > 0.01f

        if (dspEnabled && needsEqOrDynamics) {
            // 2. Clean 80Hz High-Pass filter removes sub-bass mud only when EQ/effect shaping is active
            buffer = applyBiquadFilter(
                samples = buffer,
                coeffs = BiquadCoeffs.highPass(sampleRate.toDouble(), 80.0, 0.707)
            )

            // 3. Dynamic Emotion / Chest Warmth + De-Esser
            val warmthFreq = if (isWarmPreset) 200.0 else 250.0
            val deEsserFreq = if (isWarmPreset) 8000.0 else 8500.0

            if (abs(effectivePresenceDb) > 0.05) {
                buffer = applyBiquadFilter(
                    samples = buffer,
                    coeffs = BiquadCoeffs.highShelf(sampleRate.toDouble(), deEsserFreq, 0.707, effectivePresenceDb)
                )
            }
            if (abs(effectiveWarmthDb) > 0.05) {
                buffer = applyBiquadFilter(
                    samples = buffer,
                    coeffs = BiquadCoeffs.peaking(sampleRate.toDouble(), warmthFreq, 1.4, effectiveWarmthDb)
                )
            }

            // 4. Smooth Glue Compressor (only on non-Clean presets so Clean Studio stays 100% transparent)
            if (isEffectActive) {
                buffer = applyGlueCompressor(buffer, sampleRate)
            }

            // 5. Optional subtle room ambience (0% by default so Clean Studio stays dry & crystal clear)
            if (config.reverbWetMix > 0.01f &&
                config.effectPreset != AcousticEffectPreset.ETHEREAL &&
                config.effectPreset != AcousticEffectPreset.ANGELIC
            ) {
                buffer = applyDiffusedReverb(
                    samples = buffer,
                    sampleRate = sampleRate,
                    wetMix = config.reverbWetMix.coerceIn(0f, 0.50f),
                    decayFeedback = 0.68f,
                    dampAlpha = 0.55f
                )
            }

            // 6. GachaSwipe 10-Mode Character Effect Router
            buffer = applyEffectPreset(buffer, sampleRate, config)
        }

        // 7. Volume multiplier + High-Threshold Transparent Peak Ceiling (-0.7 dBFS)
        for (i in buffer.indices) {
            val scaled = buffer[i] * volumeGain.coerceIn(0.1f, 2.5f)
            val absS = abs(scaled)
            buffer[i] = if (absS <= 0.92f) {
                scaled
            } else {
                val excess = absS - 0.92f
                val limited = 0.92f + 0.05f * tanh((excess / 0.05f).toDouble()).toFloat()
                if (scaled < 0f) -limited else limited
            }.coerceIn(-0.97f, 0.97f)
        }

        return buffer
    }

    /**
     * Ports GachaSwipe's `envGain` (25ms anti-pop fade-in + 120ms exponential sentence tail taper)
     * so sentence endings taper naturally and trailing neural artifacts are cleanly gated.
     */
    fun applyEnvelopeTaper(
        samples: FloatArray,
        sampleRate: Int,
        config: DspConfig
    ): FloatArray {
        if (samples.size < 32) return samples
        val out = samples.copyOf()

        // 25ms Anti-Pop Fade-In
        val fadeInSamples = ((config.antiPopFadeInMs.coerceIn(0, 80) * sampleRate) / 1000)
            .coerceAtMost(out.size / 5)
        if (fadeInSamples > 1) {
            for (i in 0 until fadeInSamples) {
                val progress = i.toDouble() / fadeInSamples.toDouble()
                val env = (0.001 * 1000.0.pow(progress)).toFloat().coerceIn(0.001f, 1.0f)
                out[i] *= env
            }
        }

        // 120ms Exponential Sentence Tail Taper (only on chunks >= 180ms so short interjections aren't choked)
        val minTaperBufferSamples = (sampleRate * 180) / 1000
        if (config.enableSentenceTaper && out.size >= minTaperBufferSamples) {
            val fadeOutSamples = ((config.sentenceTaperMs.coerceIn(20, 400) * sampleRate) / 1000)
                .coerceAtMost(out.size / 3)
            val startIdx = out.size - fadeOutSamples
            for (k in 0 until fadeOutSamples) {
                val progress = k.toDouble() / fadeOutSamples.toDouble()
                val env = (0.001).pow(progress).toFloat().coerceIn(0.001f, 1.0f)
                out[startIdx + k] *= env
            }
        }

        return out
    }

    private fun applyGlueCompressor(samples: FloatArray, sampleRate: Int): FloatArray {
        val out = FloatArray(samples.size)
        val attackCoeff = exp(-1.0 / (0.025 * sampleRate)).toFloat()
        val releaseCoeff = exp(-1.0 / (0.250 * sampleRate)).toFloat()
        val threshold = 0.25f // -12 dBFS (prevents low-level intermodulation buzz)
        val invRatio = 1.0 / 1.8
        var env = 0f

        for (i in samples.indices) {
            val x = samples[i]
            val absX = abs(x)
            env = if (absX > env) {
                attackCoeff * env + (1f - attackCoeff) * absX
            } else {
                releaseCoeff * env + (1f - releaseCoeff) * absX
            }
            val gain = if (env > threshold && env > 1e-6f) {
                val dbOver = 20.0 * log10((env / threshold).toDouble())
                val reductionDb = dbOver * (1.0 - invRatio)
                10.0.pow(-reductionDb / 20.0).toFloat()
            } else {
                1.0f
            }
            out[i] = x * gain * 1.04f
        }
        return out
    }

    private fun applyEffectPreset(
        input: FloatArray,
        sampleRate: Int,
        config: DspConfig
    ): FloatArray {
        val fs = sampleRate.toDouble()
        return when (config.effectPreset) {
            AcousticEffectPreset.NONE,
            AcousticEffectPreset.WARM_STUDIO -> input

            AcousticEffectPreset.ETHEREAL -> {
                applyDiffusedReverb(
                    samples = input,
                    sampleRate = sampleRate,
                    wetMix = max(config.reverbWetMix, 0.22f).coerceIn(0f, 0.55f),
                    decayFeedback = 0.80f,
                    dampAlpha = 0.45f
                )
            }

            AcousticEffectPreset.ANGELIC -> {
                val hpSend = applyBiquadFilter(input, BiquadCoeffs.highPass(fs, 300.0, 0.707))
                val wetOnly = applyDiffusedReverb(
                    samples = hpSend,
                    sampleRate = sampleRate,
                    wetMix = 1.0f,
                    decayFeedback = 0.74f,
                    dampAlpha = 0.62f,
                    wetOnly = true
                )
                val wetGain = max(config.reverbWetMix, 0.18f).coerceIn(0f, 0.50f)
                FloatArray(input.size) { i -> input[i] + wetOnly[i] * wetGain }
            }

            AcousticEffectPreset.IDOL -> {
                applySlapbackDelay(
                    samples = input,
                    sampleRate = sampleRate,
                    delayMs = 100,
                    feedback = 0.15f,
                    wetMix = max(config.reverbWetMix, 0.15f).coerceIn(0f, 0.40f)
                )
            }

            AcousticEffectPreset.TINY -> {
                applyBiquadFilter(input, BiquadCoeffs.highPass(fs, 450.0, 0.707))
            }

            AcousticEffectPreset.FERAL -> {
                val warmed = applyBiquadFilter(input, BiquadCoeffs.lowShelf(fs, 200.0, 0.707, 3.0))
                applySoftWaveshaper(warmed, drive = 0.65f, outputGain = 1.0f)
            }

            AcousticEffectPreset.LIQUID -> {
                val lp = applyBiquadFilter(input, BiquadCoeffs.lowPass(fs, 1200.0, 0.707))
                applyBiquadFilter(lp, BiquadCoeffs.peaking(fs, 400.0, 2.0, 3.0))
            }

            AcousticEffectPreset.RADIO -> {
                val bp = applyBiquadFilter(input, BiquadCoeffs.bandPass(fs, 1200.0, 0.8))
                applySoftWaveshaper(bp, drive = 2.6f, outputGain = 1.35f)
            }

            AcousticEffectPreset.MONSTER -> {
                val lp = applyBiquadFilter(input, BiquadCoeffs.lowPass(fs, 1800.0, 0.707))
                applyBiquadFilter(lp, BiquadCoeffs.lowShelf(fs, 250.0, 0.707, 4.0))
            }
        }
    }

    private fun applySoftWaveshaper(samples: FloatArray, drive: Float, outputGain: Float): FloatArray {
        val out = FloatArray(samples.size)
        for (i in samples.indices) {
            val x = samples[i]
            val shaped = ((1.0f + drive) * x) / (1.0f + drive * abs(x))
            out[i] = shaped * outputGain
        }
        return out
    }

    private fun applySlapbackDelay(
        samples: FloatArray,
        sampleRate: Int,
        delayMs: Int,
        feedback: Float,
        wetMix: Float
    ): FloatArray {
        val delaySamples = ((sampleRate * delayMs) / 1000).coerceAtLeast(16)
        val delayBuf = FloatArray(delaySamples)
        val out = FloatArray(samples.size)
        var idx = 0
        for (i in samples.indices) {
            val dry = samples[i]
            val delayed = delayBuf[idx]
            delayBuf[idx] = dry + delayed * feedback
            idx = (idx + 1) % delaySamples
            out[i] = dry + delayed * wetMix
        }
        return out
    }

    /**
     * Exact zero-filled 24kHz PCM16-LE silence buffer injected at MIKASTOP boundaries.
     */
    fun createSilencePcm16Le(sampleRate: Int = SAMPLE_RATE_HZ, durationMs: Int): ByteArray {
        val clampedMs = durationMs.coerceIn(0, 5000)
        val numSamples = ((sampleRate.toLong() * clampedMs) / 1000L).toInt()
        return ByteArray(numSamples * 2)
    }

    /**
     * Exact zero-filled 24kHz Float32-LE silence buffer injected at MIKASTOP boundaries.
     */
    fun createSilenceFloat32Le(sampleRate: Int = SAMPLE_RATE_HZ, durationMs: Int): ByteArray {
        val clampedMs = durationMs.coerceIn(0, 5000)
        val numSamples = ((sampleRate.toLong() * clampedMs) / 1000L).toInt()
        return ByteArray(numSamples * 4)
    }

    /**
     * Converts normalized float32 [-1.0, 1.0] mono audio to PCM 16-bit Little Endian bytes
     * with subtle triangular PDF dither to avoid quantization noise on soft tails.
     */
    fun float32ToPcm16Le(samples: FloatArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        var outIdx = 0
        for (sample in samples) {
            val clamped = sample.coerceIn(-1.0f, 1.0f)
            val dither = (Random.nextFloat() - Random.nextFloat()) / 32768f
            val pcm = ((clamped + dither) * 32767.0f).toInt().coerceIn(-32768, 32767)
            out[outIdx++] = (pcm and 0xFF).toByte()
            out[outIdx++] = ((pcm ushr 8) and 0xFF).toByte()
        }
        return out
    }

    fun float32ToByteArrayLe(samples: FloatArray): ByteArray {
        val out = ByteArray(samples.size * 4)
        var idx = 0
        for (s in samples) {
            val bits = java.lang.Float.floatToIntBits(s.coerceIn(-1.0f, 1.0f))
            out[idx++] = (bits and 0xFF).toByte()
            out[idx++] = ((bits ushr 8) and 0xFF).toByte()
            out[idx++] = ((bits ushr 16) and 0xFF).toByte()
            out[idx++] = ((bits ushr 24) and 0xFF).toByte()
        }
        return out
    }

    fun pcm16LeToFloat32(pcmBytes: ByteArray): FloatArray {
        val sampleCount = pcmBytes.size / 2
        val out = FloatArray(sampleCount)
        for (i in 0 until sampleCount) {
            val lo = pcmBytes[i * 2].toInt() and 0xFF
            val hi = pcmBytes[i * 2 + 1].toInt()
            val sample = (hi shl 8) or lo
            out[i] = (sample / 32768.0f).coerceIn(-1f, 1f)
        }
        return out
    }

    /**
     * 4-point Catmull-Rom Cubic Hermite resampler so 16kHz / 22.05kHz Sherpa-ONNX models align cleanly
     * to 24kHz without linear-interpolation aliasing buzz.
     */
    fun resampleMono(samples: FloatArray, fromRate: Int, toRate: Int = SAMPLE_RATE_HZ): FloatArray {
        if (samples.isEmpty() || fromRate == toRate || fromRate <= 0) return samples
        if (samples.size < 4) {
            val ratio = fromRate.toDouble() / toRate.toDouble()
            val outLen = max(1, (samples.size / ratio).toInt())
            return FloatArray(outLen) { i ->
                val srcPos = i * ratio
                val idx0 = srcPos.toInt().coerceIn(0, samples.lastIndex)
                val idx1 = min(samples.lastIndex, idx0 + 1)
                val frac = (srcPos - idx0).toFloat()
                samples[idx0] + (samples[idx1] - samples[idx0]) * frac
            }
        }
        val ratio = fromRate.toDouble() / toRate.toDouble()
        val outLen = max(1, (samples.size / ratio).toInt())
        val out = FloatArray(outLen)
        val lastIdx = samples.lastIndex
        for (i in 0 until outLen) {
            val srcPos = i * ratio
            val i1 = srcPos.toInt().coerceIn(0, lastIdx)
            val t = (srcPos - i1).toFloat()
            val y0 = samples[(i1 - 1).coerceAtLeast(0)]
            val y1 = samples[i1]
            val y2 = samples[(i1 + 1).coerceAtMost(lastIdx)]
            val y3 = samples[(i1 + 2).coerceAtMost(lastIdx)]

            val a0 = -0.5f * y0 + 1.5f * y1 - 1.5f * y2 + 0.5f * y3
            val a1 = y0 - 2.5f * y1 + 2.0f * y2 - 0.5f * y3
            val a2 = -0.5f * y0 + 0.5f * y2
            val a3 = y1

            out[i] = (((a0 * t + a1) * t + a2) * t + a3).coerceIn(-1.0f, 1.0f)
        }
        return out
    }

    /**
     * 4-point Catmull-Rom Cubic Hermite pitch resampler (subtly clamped to 0.88x..1.12x)
     * preserving high-frequency vocal crispness without linear-interpolation muffling.
     */
    fun shiftPitchCubic(samples: FloatArray, pitchFactor: Float): FloatArray {
        val p = pitchFactor.coerceIn(0.88f, 1.12f)
        if (p in 0.995f..1.005f || samples.size < 8) return samples
        val targetLen = (samples.size / p).toInt().coerceAtLeast(1)
        val out = FloatArray(targetLen)
        val lastIdx = samples.lastIndex
        for (i in 0 until targetLen) {
            val pos = i.toDouble() * p
            val i1 = pos.toInt().coerceIn(0, lastIdx)
            val t = (pos - i1).toFloat()
            val y0 = samples[(i1 - 1).coerceAtLeast(0)]
            val y1 = samples[i1]
            val y2 = samples[(i1 + 1).coerceAtMost(lastIdx)]
            val y3 = samples[(i1 + 2).coerceAtMost(lastIdx)]

            val a0 = -0.5f * y0 + 1.5f * y1 - 1.5f * y2 + 0.5f * y3
            val a1 = y0 - 2.5f * y1 + 2.0f * y2 - 0.5f * y3
            val a2 = -0.5f * y0 + 0.5f * y2
            val a3 = y1

            out[i] = (((a0 * t + a1) * t + a2) * t + a3).coerceIn(-1.0f, 1.0f)
        }
        return out
    }

    fun computeWaveformBins(samples: FloatArray, binCount: Int = 32): FloatArray {
        val bins = FloatArray(binCount)
        if (samples.isEmpty() || binCount <= 0) return bins
        val step = max(1, samples.size / binCount)
        for (b in 0 until binCount) {
            val start = b * step
            val end = min(samples.size, start + step)
            if (start >= samples.size) break
            var maxAmp = 0f
            for (i in start until end) {
                val a = abs(samples[i])
                if (a > maxAmp) maxAmp = a
            }
            bins[b] = maxAmp.coerceIn(0f, 1f)
        }
        return bins
    }

    private fun applyBiquadFilter(samples: FloatArray, coeffs: BiquadCoeffs): FloatArray {
        val out = FloatArray(samples.size)
        var x1 = 0.0
        var x2 = 0.0
        var y1 = 0.0
        var y2 = 0.0
        for (i in samples.indices) {
            val x0 = samples[i].toDouble()
            val y0 = coeffs.b0 * x0 + coeffs.b1 * x1 + coeffs.b2 * x2 - coeffs.a1 * y1 - coeffs.a2 * y2
            x2 = x1
            x1 = x0
            y2 = y1
            y1 = y0
            out[i] = y0.toFloat()
        }
        return out
    }

    /**
     * Damped 6-line velvet reverb + allpass diffuser (zero metallic ringing).
     */
    private fun applyDiffusedReverb(
        samples: FloatArray,
        sampleRate: Int,
        wetMix: Float,
        decayFeedback: Float = 0.72f,
        dampAlpha: Float = 0.50f,
        wetOnly: Boolean = false
    ): FloatArray {
        val scale = sampleRate / 24000.0
        val combDelays = intArrayOf(
            (557 * scale).toInt().coerceAtLeast(16),
            (673 * scale).toInt().coerceAtLeast(16),
            (809 * scale).toInt().coerceAtLeast(16),
            (967 * scale).toInt().coerceAtLeast(16),
            (1151 * scale).toInt().coerceAtLeast(16),
            (1361 * scale).toInt().coerceAtLeast(16)
        )
        val combBuffers = Array(combDelays.size) { FloatArray(combDelays[it]) }
        val combIdx = IntArray(combDelays.size)
        val combLpState = FloatArray(combDelays.size)

        val ap1Delay = (113 * scale).toInt().coerceAtLeast(8)
        val ap2Delay = (193 * scale).toInt().coerceAtLeast(8)
        val ap1Buf = FloatArray(ap1Delay)
        val ap2Buf = FloatArray(ap2Delay)
        var ap1Idx = 0
        var ap2Idx = 0

        val out = FloatArray(samples.size)

        for (i in samples.indices) {
            val dry = samples[i]
            var wet = 0f
            for (c in combDelays.indices) {
                val buf = combBuffers[c]
                val idx = combIdx[c]
                val rawDelayed = buf[idx]
                // 1-pole high-frequency damping eliminates metallic ringing in the tail
                val damped = dampAlpha * rawDelayed + (1f - dampAlpha) * combLpState[c]
                combLpState[c] = damped
                buf[idx] = dry + damped * (decayFeedback - c * 0.025f)
                combIdx[c] = (idx + 1) % buf.size
                wet += if (c % 2 == 0) damped else -damped
            }
            wet /= combDelays.size.toFloat()

            val ap1Delayed = ap1Buf[ap1Idx]
            val ap1Out = -wet * 0.5f + ap1Delayed
            ap1Buf[ap1Idx] = wet + ap1Delayed * 0.5f
            ap1Idx = (ap1Idx + 1) % ap1Delay

            val ap2Delayed = ap2Buf[ap2Idx]
            val ap2Out = -ap1Out * 0.5f + ap2Delayed
            ap2Buf[ap2Idx] = ap1Out + ap2Delayed * 0.5f
            ap2Idx = (ap2Idx + 1) % ap2Delay

            out[i] = if (wetOnly) ap2Out else (dry + ap2Out * wetMix)
        }
        return out
    }

    data class ExportAudioSegment(
        val pcmFloat24k: FloatArray,
        val isSilence: Boolean
    )

    /**
     * Stitches multiple synthesized chunks and MIKASTOP silence buffers into one cohesive
     * 24kHz mono master track:
     * 1. Removes per-chunk DC offset.
     * 2. Optionally matches per-chunk RMS loudness toward -18 dBFS (`targetRms = 0.125f`).
     * 3. Applies raised-cosine boundary de-clicking and 18ms overlap-add crossfading between
     *    adjacent speech segments so sentences blend smoothly without clicks or sudden drops.
     * 4. Applies a final -1.0 dBFS soft-knee master limiter.
     */
    fun stitchAndMasterAudiobook(
        segments: List<ExportAudioSegment>,
        sampleRate: Int = SAMPLE_RATE_HZ,
        normalizeLoudness: Boolean = true,
        smoothTransitions: Boolean = true
    ): FloatArray {
        val nonEmpty = segments.filter { it.pcmFloat24k.isNotEmpty() }
        if (nonEmpty.isEmpty()) return FloatArray(0)

        val overlapLen = if (smoothTransitions) (sampleRate * 0.018f).toInt().coerceAtLeast(16) else 0
        val fadeInEdge = if (smoothTransitions) (sampleRate * 0.012f).toInt().coerceAtLeast(16) else 0
        val fadeOutEdge = if (smoothTransitions) (sampleRate * 0.018f).toInt().coerceAtLeast(16) else 0

        val prepared = nonEmpty.mapIndexed { idx, seg ->
            if (seg.isSilence) {
                seg.copy(pcmFloat24k = FloatArray(seg.pcmFloat24k.size))
            } else {
                val buf = seg.pcmFloat24k.copyOf()
                // 1. Remove any per-chunk DC offset
                var mean = 0.0
                for (s in buf) mean += s
                val meanF = (mean / buf.size.coerceAtLeast(1)).toFloat()
                if (abs(meanF) > 1e-5f) {
                    for (i in buf.indices) buf[i] -= meanF
                }

                // 2. Match per-chunk RMS loudness toward -18 dBFS
                if (normalizeLoudness) {
                    var sumSq = 0.0
                    var activeCount = 0
                    for (s in buf) {
                        if (abs(s) > 0.004f) {
                            sumSq += s * s
                            activeCount++
                        }
                    }
                    val rms = if (activeCount > 0) kotlin.math.sqrt(sumSq / activeCount).toFloat() else 0f
                    if (rms > 0.003f) {
                        val gain = (0.125f / rms).coerceIn(0.60f, 1.65f)
                        for (i in buf.indices) buf[i] *= gain
                    }
                }

                // 3. Raised-cosine boundary smoothing
                if (smoothTransitions) {
                    val prevSeg = nonEmpty.getOrNull(idx - 1)
                    val nextSeg = nonEmpty.getOrNull(idx + 1)
                    val inLen = if (prevSeg != null && !prevSeg.isSilence && overlapLen > 0) {
                        overlapLen.coerceAtMost(buf.size / 3)
                    } else {
                        fadeInEdge.coerceAtMost(buf.size / 4)
                    }
                    val outLen = if (nextSeg != null && !nextSeg.isSilence && overlapLen > 0) {
                        overlapLen.coerceAtMost(buf.size / 3)
                    } else {
                        fadeOutEdge.coerceAtMost(buf.size / 4)
                    }
                    for (i in 0 until inLen) {
                        val w = 0.5f * (1f - cos(PI * i / inLen).toFloat())
                        buf[i] *= w
                    }
                    for (i in 0 until outLen) {
                        val pos = buf.size - outLen + i
                        val w = 0.5f * (1f + cos(PI * i / outLen).toFloat())
                        buf[pos] *= w
                    }
                }
                ExportAudioSegment(pcmFloat24k = buf, isSilence = false)
            }
        }

        // Compute total output length accounting for adjacent speech crossfade overlaps
        var totalSamples = 0
        for (i in prepared.indices) {
            val seg = prepared[i]
            val prev = prepared.getOrNull(i - 1)
            val canOverlap = smoothTransitions &&
                prev != null && !prev.isSilence && !seg.isSilence &&
                prev.pcmFloat24k.size > overlapLen * 2 &&
                seg.pcmFloat24k.size > overlapLen * 2
            totalSamples += seg.pcmFloat24k.size
            if (canOverlap) {
                totalSamples -= overlapLen
            }
        }
        if (totalSamples <= 0) return FloatArray(0)

        val master = FloatArray(totalSamples)
        var writeCursor = 0
        for (i in prepared.indices) {
            val seg = prepared[i]
            val prev = prepared.getOrNull(i - 1)
            val canOverlap = smoothTransitions &&
                prev != null && !prev.isSilence && !seg.isSilence &&
                prev.pcmFloat24k.size > overlapLen * 2 &&
                seg.pcmFloat24k.size > overlapLen * 2
            val startPos = if (canOverlap) (writeCursor - overlapLen).coerceAtLeast(0) else writeCursor
            val src = seg.pcmFloat24k
            for (k in src.indices) {
                val dstIdx = startPos + k
                if (dstIdx < master.size) {
                    master[dstIdx] += src[k]
                }
            }
            writeCursor = startPos + src.size
        }

        // 4. Master -1.0 dBFS Soft-Knee Limiter
        val kneeStart = 0.78f
        val headroom = 0.111f // max 0.891 (-1 dBFS)
        for (i in master.indices) {
            val s = master[i]
            val a = abs(s)
            if (a > kneeStart) {
                val excess = (a - kneeStart) / headroom
                val limited = kneeStart + headroom * tanh(excess)
                master[i] = if (s >= 0f) limited else -limited
            }
        }
        return master
    }

    private data class BiquadCoeffs(
        val b0: Double,
        val b1: Double,
        val b2: Double,
        val a1: Double,
        val a2: Double
    ) {
        companion object {
            fun lowShelf(fs: Double, fc: Double, q: Double, gainDb: Double): BiquadCoeffs {
                val a = 10.0.pow(gainDb / 40.0)
                val w0 = 2.0 * PI * fc / fs
                val cosW0 = cos(w0)
                val sinW0 = sin(w0)
                val alpha = sinW0 / (2.0 * q)
                val twoSqrtAAlpha = 2.0 * kotlin.math.sqrt(a) * alpha

                val a0 = (a + 1.0) + (a - 1.0) * cosW0 + twoSqrtAAlpha
                val b0 = a * ((a + 1.0) - (a - 1.0) * cosW0 + twoSqrtAAlpha)
                val b1 = 2.0 * a * ((a - 1.0) - (a + 1.0) * cosW0)
                val b2 = a * ((a + 1.0) - (a - 1.0) * cosW0 - twoSqrtAAlpha)
                val a1 = -2.0 * ((a - 1.0) + (a + 1.0) * cosW0)
                val a2 = (a + 1.0) + (a - 1.0) * cosW0 - twoSqrtAAlpha
                return BiquadCoeffs(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
            }

            fun highShelf(fs: Double, fc: Double, q: Double, gainDb: Double): BiquadCoeffs {
                val a = 10.0.pow(gainDb / 40.0)
                val w0 = 2.0 * PI * fc / fs
                val cosW0 = cos(w0)
                val sinW0 = sin(w0)
                val alpha = sinW0 / (2.0 * q)
                val twoSqrtAAlpha = 2.0 * kotlin.math.sqrt(a) * alpha

                val a0 = (a + 1.0) - (a - 1.0) * cosW0 + twoSqrtAAlpha
                val b0 = a * ((a + 1.0) + (a - 1.0) * cosW0 + twoSqrtAAlpha)
                val b1 = -2.0 * a * ((a - 1.0) + (a + 1.0) * cosW0)
                val b2 = a * ((a + 1.0) + (a - 1.0) * cosW0 - twoSqrtAAlpha)
                val a1 = 2.0 * ((a - 1.0) - (a + 1.0) * cosW0)
                val a2 = (a + 1.0) - (a - 1.0) * cosW0 - twoSqrtAAlpha
                return BiquadCoeffs(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
            }

            fun peaking(fs: Double, fc: Double, q: Double, gainDb: Double): BiquadCoeffs {
                val a = 10.0.pow(gainDb / 40.0)
                val w0 = 2.0 * PI * fc / fs
                val cosW0 = cos(w0)
                val sinW0 = sin(w0)
                val alpha = sinW0 / (2.0 * q)

                val a0 = 1.0 + alpha / a
                val b0 = 1.0 + alpha * a
                val b1 = -2.0 * cosW0
                val b2 = 1.0 - alpha * a
                val a1 = -2.0 * cosW0
                val a2 = 1.0 - alpha / a
                return BiquadCoeffs(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
            }

            fun highPass(fs: Double, fc: Double, q: Double): BiquadCoeffs {
                val w0 = 2.0 * PI * fc / fs
                val cosW0 = cos(w0)
                val sinW0 = sin(w0)
                val alpha = sinW0 / (2.0 * q)

                val a0 = 1.0 + alpha
                val b0 = (1.0 + cosW0) / 2.0
                val b1 = -(1.0 + cosW0)
                val b2 = (1.0 + cosW0) / 2.0
                val a1 = -2.0 * cosW0
                val a2 = 1.0 - alpha
                return BiquadCoeffs(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
            }

            fun lowPass(fs: Double, fc: Double, q: Double): BiquadCoeffs {
                val w0 = 2.0 * PI * fc / fs
                val cosW0 = cos(w0)
                val sinW0 = sin(w0)
                val alpha = sinW0 / (2.0 * q)

                val a0 = 1.0 + alpha
                val b0 = (1.0 - cosW0) / 2.0
                val b1 = 1.0 - cosW0
                val b2 = (1.0 - cosW0) / 2.0
                val a1 = -2.0 * cosW0
                val a2 = 1.0 - alpha
                return BiquadCoeffs(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
            }

            fun bandPass(fs: Double, fc: Double, q: Double): BiquadCoeffs {
                val w0 = 2.0 * PI * fc / fs
                val cosW0 = cos(w0)
                val sinW0 = sin(w0)
                val alpha = sinW0 / (2.0 * q)
                val a0 = 1.0 + alpha
                val b0 = alpha
                val b1 = 0.0
                val b2 = -alpha
                val a1 = -2.0 * cosW0
                val a2 = 1.0 - alpha
                return BiquadCoeffs(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
            }
        }
    }
}
