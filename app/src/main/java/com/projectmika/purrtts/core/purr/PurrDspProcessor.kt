package com.projectmika.purrtts.core.purr

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.random.Random

/**
 * ✨ MIKA'S REAL-TIME 24kHz PCM DSP & POST-EFFECTS RACK ✨
 * Runs in pure Kotlin over 24kHz mono float32 frames emitted by LiteRT / Sherpa-ONNX / Proxy
 * before streaming PCM16 or Float32 frames into Android's SynthesisCallback.
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

        if (dspEnabled) {
            // 1. Acoustic preset tweaks on top of base sliders
            var warmthDb = config.warmthEqDb
            var presenceDb = config.presenceEqDb
            var reverbWet = config.reverbWetMix
            var applyRadioBandpass = false

            when (config.effectPreset) {
                AcousticEffectPreset.NONE -> Unit
                AcousticEffectPreset.WARM_STUDIO -> {
                    warmthDb = max(warmthDb, 3.0f)
                }
                AcousticEffectPreset.ETHEREAL -> {
                    reverbWet = max(reverbWet, 0.28f)
                    warmthDb += 1.5f
                }
                AcousticEffectPreset.ANGELIC -> {
                    presenceDb += 3.5f
                    reverbWet = max(reverbWet, 0.22f)
                }
                AcousticEffectPreset.RADIO -> {
                    applyRadioBandpass = true
                    reverbWet = 0.02f
                }
                AcousticEffectPreset.IDOL -> {
                    presenceDb += 2.5f
                    reverbWet = max(reverbWet, 0.16f)
                }
            }

            // 2. Warmth Low-Shelf EQ (220 Hz) + Presence High-Shelf EQ (3800 Hz)
            if (abs(warmthDb) > 0.05f) {
                buffer = applyBiquadFilter(
                    samples = buffer,
                    coeffs = BiquadCoeffs.lowShelf(sampleRate.toDouble(), 220.0, 0.707, warmthDb.toDouble())
                )
            }
            if (abs(presenceDb) > 0.05f) {
                buffer = applyBiquadFilter(
                    samples = buffer,
                    coeffs = BiquadCoeffs.highShelf(sampleRate.toDouble(), 3800.0, 0.707, presenceDb.toDouble())
                )
            }
            if (applyRadioBandpass) {
                buffer = applyBiquadFilter(
                    samples = buffer,
                    coeffs = BiquadCoeffs.bandPass(sampleRate.toDouble(), 1450.0, 1.35)
                )
            }

            // 3. Subtle Schroeder Room Reverb
            if (reverbWet > 0.01f) {
                buffer = applySchroederReverb(buffer, sampleRate, reverbWet.coerceIn(0f, 0.65f))
            }
        }

        // 4. Gain + Soft-knee saturation + Peak normalization
        var peak = 0f
        for (s in buffer) {
            val a = abs(s)
            if (a > peak) peak = a
        }

        val normFactor = if (config.normalizePeak && peak > 0.005f) {
            0.89f / peak
        } else {
            1.0f
        }
        val combinedGain = (normFactor * volumeGain).coerceIn(0.1f, 3.0f)

        for (i in buffer.indices) {
            val scaled = buffer[i] * combinedGain
            // Soft tanh limiter prevents digital clipping while adding subtle analog warmth
            buffer[i] = (tanh((scaled * 1.05f).toDouble()) / tanh(1.05)).toFloat().coerceIn(-0.995f, 0.995f)
        }

        return buffer
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
     * Linear / cubic resampler so 16kHz / 22.05kHz Sherpa-ONNX models align cleanly to 24kHz.
     */
    fun resampleMono(samples: FloatArray, fromRate: Int, toRate: Int = SAMPLE_RATE_HZ): FloatArray {
        if (samples.isEmpty() || fromRate == toRate || fromRate <= 0) return samples
        val ratio = fromRate.toDouble() / toRate.toDouble()
        val outLen = max(1, (samples.size / ratio).toInt())
        val out = FloatArray(outLen)
        for (i in 0 until outLen) {
            val srcPos = i * ratio
            val idx0 = srcPos.toInt().coerceIn(0, samples.lastIndex)
            val idx1 = min(samples.lastIndex, idx0 + 1)
            val frac = (srcPos - idx0).toFloat()
            out[i] = samples[idx0] + (samples[idx1] - samples[idx0]) * frac
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

    private fun applySchroederReverb(samples: FloatArray, sampleRate: Int, wetMix: Float): FloatArray {
        val scale = sampleRate / 24000.0
        val combDelays = intArrayOf(
            (727 * scale).toInt().coerceAtLeast(16),
            (853 * scale).toInt().coerceAtLeast(16),
            (977 * scale).toInt().coerceAtLeast(16),
            (1103 * scale).toInt().coerceAtLeast(16)
        )
        val combFeedback = floatArrayOf(0.74f, 0.71f, 0.68f, 0.65f)
        val combBuffers = Array(combDelays.size) { FloatArray(combDelays[it]) }
        val combIdx = IntArray(combDelays.size)

        val allpassDelay = (149 * scale).toInt().coerceAtLeast(8)
        val allpassBuf = FloatArray(allpassDelay)
        var apIdx = 0

        val dryMix = 1.0f - wetMix * 0.5f
        val out = FloatArray(samples.size)

        for (i in samples.indices) {
            val dry = samples[i]
            var wet = 0f
            for (c in combDelays.indices) {
                val buf = combBuffers[c]
                val idx = combIdx[c]
                val delayed = buf[idx]
                buf[idx] = dry + delayed * combFeedback[c]
                combIdx[c] = (idx + 1) % buf.size
                wet += delayed
            }
            wet *= 0.25f

            val apDelayed = allpassBuf[apIdx]
            val apOut = -wet + apDelayed
            allpassBuf[apIdx] = wet + apDelayed * 0.5f
            apIdx = (apIdx + 1) % allpassDelay

            out[i] = dry * dryMix + apOut * wetMix
        }
        return out
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
