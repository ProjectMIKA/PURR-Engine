package com.projectmika.purrtts.core.purr

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * ✨ MIKA'S CLONE REFERENCE AUDIO CLEANER (IMPORT PATH DSP) ✨
 * Pure Kotlin pipeline that turns a raw user-imported audio clip into a clean
 * monophonic voice-cloning reference:
 * 1. DC offset removal (recordings with a bias saturate the spectral floor)
 * 2. 85Hz rumble/hum high-pass (kills mains hum, mic handling thumps, static low-end)
 * 3. STFT spectral-subtraction denoiser — learns the noise floor from the quietest
 *    frames of the clip itself, then subtracts it from the magnitude spectrum with a
 *    musical-noise floor so voices never sound underwater. Three strengths.
 * 4. Peak normalization to the 0.92 transport ceiling.
 *
 * `selectBestClip` trims silence and picks the highest-energy window of the target
 * family duration (Pocket TTS ≈ 5s, ZipVoice ≈ 12s) with anti-pop edge fades.
 *
 * PURE — no Android imports — fully unit-testable (see PurrCloneAudioCleanerTest).
 */
object PurrCloneAudioCleaner {

    /** Clipping reference window per Sherpa clone family (seconds of reference audio). */
    fun targetClipSeconds(family: SherpaModelFamily): Int = when (family) {
        SherpaModelFamily.ZIPVOICE -> 12
        else -> 5
    }

    enum class NoiseReduction(val beta: Float, val spectralFloor: Float, val label: String) {
        OFF(0f, 1f, "OFF"),
        LIGHT(0.9f, 0.10f, "LIGHT"),
        STANDARD(1.5f, 0.07f, "STANDARD"),
        AGGRESSIVE(2.4f, 0.045f, "AGGRESSIVE")
    }

    data class CleanConfig(
        val removeDcOffset: Boolean = true,
        val highPassRumbleHz: Double = 85.0,
        val noiseReduction: NoiseReduction = NoiseReduction.STANDARD,
        val normalizePeak: Boolean = true
    )

    // ---------- Public pipeline ----------

    /**
     * Cleans a mono float clip at [sampleRate] and returns the processed audio.
     * Input is expected normalized to roughly [-1, 1] (platform decoders guarantee this).
     */
    fun cleanClip(samples: FloatArray, sampleRate: Int, config: CleanConfig = CleanConfig()): FloatArray {
        if (samples.isEmpty() || sampleRate <= 0) return samples
        var buffer = samples.copyOf()

        if (config.removeDcOffset) buffer = removeDcOffset(buffer)
        if (config.highPassRumbleHz > 0.0) {
            buffer = applyBiquad(buffer, Biquad.highPass(sampleRate.toDouble(), config.highPassRumbleHz))
        }
        if (config.noiseReduction != NoiseReduction.OFF) {
            buffer = spectralSubtract(
                buffer,
                sampleRate = sampleRate,
                beta = config.noiseReduction.beta,
                floor = config.noiseReduction.spectralFloor
            )
        }
        if (config.normalizePeak) buffer = normalizePeak(buffer)
        return buffer
    }

    /**
     * Trims silence from both ends, then keeps the highest-energy window of
     * [targetSeconds] (clamped to the clip length) with 12ms anti-pop edge fades.
     */
    fun selectBestClip(samples: FloatArray, sampleRate: Int, targetSeconds: Int): FloatArray {
        if (samples.isEmpty() || sampleRate <= 0) return samples
        val trimmed = trimSilence(samples, sampleRate)
        val targetLen = targetSeconds.coerceAtLeast(1) * sampleRate
        if (trimmed.size <= targetLen) return applyEdgeFades(trimmed, sampleRate)

        val best = bestEnergyWindow(trimmed, sampleRate, targetLen)
        return applyEdgeFades(best, sampleRate)
    }

    // ---------- Stage primitives ----------

    fun removeDcOffset(samples: FloatArray): FloatArray {
        if (samples.isEmpty()) return samples
        var sum = 0.0
        for (s in samples) sum += s
        val dc = (sum / samples.size).toFloat()
        if (abs(dc) < 1e-5f) return samples
        val out = FloatArray(samples.size)
        for (i in samples.indices) out[i] = samples[i] - dc
        return out
    }

    fun normalizePeak(samples: FloatArray, targetPeak: Float = 0.92f): FloatArray {
        if (samples.isEmpty()) return samples
        var peak = 0f
        for (s in samples) peak = max(peak, abs(s))
        if (peak < 1e-4f) return samples.copyOf()
        val gain = (targetPeak / peak).coerceAtMost(4.0f)
        val out = FloatArray(samples.size)
        for (i in samples.indices) out[i] = (samples[i] * gain).coerceIn(-1f, 1f)
        return out
    }

    /** Frame-RMS silence trim: keeps ~120ms of breathing room past the first/last voiced frame. */
    fun trimSilence(samples: FloatArray, sampleRate: Int): FloatArray {
        if (samples.isEmpty()) return samples
        val frame = max(1, sampleRate / 50) // 20ms frames
        val frameCount = samples.size / frame
        if (frameCount == 0) return samples

        var maxRms = 1e-6f
        val rms = FloatArray(frameCount)
        for (f in 0 until frameCount) {
            var acc = 0.0
            val start = f * frame
            for (i in start until start + frame) acc += samples[i].toDouble() * samples[i]
            rms[f] = sqrt(acc / frame).toFloat()
            if (rms[f] > maxRms) maxRms = rms[f]
        }
        val threshold = max(maxRms * 0.06f, 8e-3f)

        var first = 0
        while (first < frameCount && rms[first] < threshold) first++
        var last = frameCount - 1
        while (last > first && rms[last] < threshold) last--
        if (first >= last) return FloatArray(0)

        val startSample = max(first - 6, 0) * frame // ~120ms lead-in
        val endSample = min(samples.size, (last + 1) * frame + 6 * frame)
        return samples.copyOfRange(startSample, endSample)
    }

    fun applyEdgeFades(samples: FloatArray, sampleRate: Int, fadeMs: Int = 12): FloatArray {
        val out = samples.copyOf()
        if (samples.size < 2) return out
        val fade = min(samples.size / 2, max(1, sampleRate * fadeMs / 1000))
        for (i in 0 until fade) {
            val g = i.toFloat() / fade
            out[i] *= g
            out[samples.size - 1 - i] *= g
        }
        return out
    }

    fun bestEnergyWindow(samples: FloatArray, sampleRate: Int, windowLen: Int): FloatArray {
        if (windowLen >= samples.size) return samples.copyOf()
        val step = max(1, sampleRate / 4) // 250ms sliding hop
        var bestStart = 0
        var bestScore = -1.0
        var start = 0
        while (start + windowLen <= samples.size) {
            var acc = 0.0
            var i = start
            while (i < start + windowLen) {
                val end = min(start + windowLen, i + step)
                var frameAcc = 0.0
                var n = 0
                while (i < end) {
                    frameAcc += samples[i].toDouble() * samples[i]
                    i++
                    n++
                }
                acc += sqrt(frameAcc / max(1, n))
            }
            val score = acc / max(1, windowLen / step)
            if (score > bestScore) {
                bestScore = score
                bestStart = start
            }
            start += step
        }
        return samples.copyOfRange(bestStart, bestStart + windowLen)
    }

    // ---------- Biquad (self-contained 2-pole filter) ----------

    data class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
        companion object {
            fun highPass(sampleRate: Double, freqHz: Double, q: Double = 0.707): Biquad {
                val w0 = 2.0 * PI * freqHz / sampleRate
                val cw = cos(w0)
                val alpha = sin(w0) / (2.0 * q)
                val a0 = 1.0 + alpha
                return Biquad(
                    b0 = ((1.0 + cw) / 2.0) / a0,
                    b1 = (-(1.0 + cw)) / a0,
                    b2 = ((1.0 + cw) / 2.0) / a0,
                    a1 = (-2.0 * cw) / a0,
                    a2 = (1.0 - alpha) / a0
                )
            }
        }
    }

    fun applyBiquad(samples: FloatArray, coeffs: Biquad): FloatArray {
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

    // ---------- STFT spectral-subtraction denoiser ----------

    private const val FFT_SIZE = 1024
    private const val HOP_SIZE = 256

    /**
     * Ephraim-style magnitude subtraction: builds a noise-floor profile from the
     * quietest ~12% of STFT frames (min 8), subtracts `beta * noiseMag` from every
     * frame with a per-bin `floor` ratio so cleaned bins keep a touch of energy
     * (anti-musical-noise), keeps original phase, and overlap-adds with a Hann window.
     */
    fun spectralSubtract(samples: FloatArray, sampleRate: Int, beta: Float, floor: Float): FloatArray {
        if (samples.size < FFT_SIZE) return samples.copyOf()
        val window = hannWindow(FFT_SIZE)
        val frameCount = (samples.size - FFT_SIZE) / HOP_SIZE + 1
        if (frameCount < 8) return samples.copyOf()

        // 1. Pass: magnitudes + frame energies.
        val halfBins = FFT_SIZE / 2 + 1
        val mags = Array(frameCount) { FloatArray(halfBins) }
        val frameEnergy = FloatArray(frameCount)
        val real = DoubleArray(FFT_SIZE)
        val imag = DoubleArray(FFT_SIZE)
        for (f in 0 until frameCount) {
            val offset = f * HOP_SIZE
            for (i in 0 until FFT_SIZE) {
                real[i] = samples[offset + i] * window[i]
                imag[i] = 0.0
            }
            fft(real, imag)
            var energy = 0.0
            for (k in 0 until halfBins) {
                val m = sqrt(real[k] * real[k] + imag[k] * imag[k]).toFloat()
                mags[f][k] = m
                energy += m
            }
            frameEnergy[f] = (energy / halfBins).toFloat()
        }

        // 2. Noise profile = mean magnitude of the quietest ~12% frames.
        val order = (0 until frameCount).sortedBy { frameEnergy[it] }
        val profileCount = max(8, (frameCount * 0.12f).toInt()).coerceAtMost(frameCount)
        val noiseMag = FloatArray(halfBins)
        for (idx in 0 until profileCount) {
            val f = order[idx]
            for (k in noiseMag.indices) noiseMag[k] += mags[f][k]
        }
        for (k in noiseMag.indices) noiseMag[k] /= profileCount

        // 3. Subtract with spectral floor, keep phase, overlap-add.
        //    NOTE: norm is indexed by GLOBAL output position — frames overlap, so every
        //    output sample accumulates the summed squared-window gain of ALL frames that
        //    cover it (Hann @ hop 256 => 1.5 in the interior, win² at the clip edges).
        val norm = DoubleArray(samples.size)
        val out = DoubleArray(samples.size)
        for (f in 0 until frameCount) {
            val offset = f * HOP_SIZE
            for (i in 0 until FFT_SIZE) {
                real[i] = samples[offset + i] * window[i]
                imag[i] = 0.0
            }
            fft(real, imag)
            for (k in 0 until halfBins) {
                val re = real[k]
                val im = imag[k]
                val mag = sqrt(re * re + im * im)
                if (mag < 1e-9) continue
                val phaseRe = re / mag
                val phaseIm = im / mag
                var cleanMag = mag - beta * noiseMag[k]
                cleanMag = max(cleanMag, mag * floor)
                val binRe = cleanMag * phaseRe
                val binIm = cleanMag * phaseIm
                if (k == 0 || k == FFT_SIZE / 2) {
                    imag[k] = 0.0
                    real[k] = binRe
                } else {
                    real[k] = binRe
                    imag[k] = binIm
                    real[FFT_SIZE - k] = binRe
                    imag[FFT_SIZE - k] = -binIm
                }
            }
            ifft(real, imag)
            for (i in 0 until FFT_SIZE) {
                val pos = offset + i
                if (pos < samples.size) {
                    out[pos] += real[i] * window[i]
                    norm[pos] += window[i] * window[i]
                }
            }
        }
        val result = FloatArray(samples.size)
        for (i in samples.indices) {
            val n = norm[i]
            result[i] = if (n > 1e-6) (out[i] / n).toFloat() else samples[i]
        }
        return result
    }

    // ---------- In-place iterative radix-2 FFT ----------

    private fun hannWindow(n: Int): DoubleArray = DoubleArray(n) { i ->
        0.5 * (1.0 - cos(2.0 * PI * i / n))
    }

    private fun fft(real: DoubleArray, imag: DoubleArray) {
        val n = real.size
        // Bit-reversal permutation.
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                val tr = real[i]
                real[i] = real[j]
                real[j] = tr
                val ti = imag[i]
                imag[i] = imag[j]
                imag[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wRe = cos(ang)
            val wIm = sin(ang)
            var start = 0
            val half = len shr 1
            while (start < n) {
                var curRe = 1.0
                var curIm = 0.0
                for (k in 0 until half) {
                    val uRe = real[start + k]
                    val uIm = imag[start + k]
                    val vRe = real[start + k + half] * curRe - imag[start + k + half] * curIm
                    val vIm = real[start + k + half] * curIm + imag[start + k + half] * curRe
                    real[start + k] = uRe + vRe
                    imag[start + k] = uIm + vIm
                    real[start + k + half] = uRe - vRe
                    imag[start + k + half] = uIm - vIm
                    val nextRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nextRe
                }
                start += len
            }
            len = len shl 1
        }
    }

    private fun ifft(real: DoubleArray, imag: DoubleArray) {
        val n = real.size
        for (i in 0 until n) imag[i] = -imag[i]
        fft(real, imag)
        for (i in 0 until n) {
            real[i] /= n
            imag[i] = -imag[i] / n
        }
    }

    /** Deterministic pseudo-noise for tests and demos (pure, seeded). */
    fun seededNoise(samples: Int, seed: Long, amplitude: Float): FloatArray {
        val rng = Random(seed)
        return FloatArray(samples) { (rng.nextDouble() * 2.0 - 1.0).toFloat() * amplitude }
    }
}
