package com.projectmika.purrtts.core.purr

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ✨ PURE-JVM TESTS FOR MIKA'S 24kHz DSP RACK (gap coverage) ✨
 * Complements [PurrTextProcessorTest] (which already covers the envelope taper,
 * clean-studio pass-through, Cubic Hermite resampler & audiobook stitching).
 * Here we guard the MIKASTOP silence byte contracts (PCM16 + Float32), the
 * float32 byte-format conversions, and the 32-band waveform telemetry.
 */
class PurrDspProcessorTest {

    // ---------- MIKASTOP silence contracts ----------

    @Test
    fun `float32 silence byte length is exact and zero filled`() {
        val bytes = PurrDspProcessor.createSilenceFloat32Le(sampleRate = 24000, durationMs = 500)
        // 12000 samples * 4 bytes
        assertEquals(48000, bytes.size)
        assertTrue(bytes.all { it == 0.toByte() })
    }

    @Test
    fun `pcm16 silence honors custom sample rate`() {
        val bytes = PurrDspProcessor.createSilencePcm16Le(sampleRate = 22050, durationMs = 100)
        // 2205 samples * 2 bytes
        assertEquals(4410, bytes.size)
        assertTrue(bytes.all { it == 0.toByte() })
    }

    @Test
    fun `silence durations clamp to the 0 to 5000ms transport guard`() {
        assertEquals(0, PurrDspProcessor.createSilencePcm16Le(durationMs = -50).size)
        // 5000ms clamp at 24kHz => 120000 samples => 240000 bytes
        assertEquals(240000, PurrDspProcessor.createSilencePcm16Le(durationMs = 99_999).size)
        assertEquals(480000, PurrDspProcessor.createSilenceFloat32Le(durationMs = 99_999).size)
    }

    // ---------- Float32 / PCM16 byte formats ----------

    @Test
    fun `float32 byte array encodes little endian ieee bits exactly`() {
        val bytes = PurrDspProcessor.float32ToByteArrayLe(floatArrayOf(0.0f, 0.5f, -1.0f, 2.0f))
        assertEquals(16, bytes.size)
        // NOTE: compare via .toInt() — assertEquals(Int, Byte) boxes to Integer-vs-Byte and always fails.
        // 0.0f => 0x00000000
        assertEquals(0, bytes[0].toInt()); assertEquals(0, bytes[1].toInt()); assertEquals(0, bytes[2].toInt()); assertEquals(0, bytes[3].toInt())
        // 0.5f => 0x3F000000 (biased exp 126) => LE 00 00 00 3F
        assertEquals(0, bytes[4].toInt()); assertEquals(0, bytes[5].toInt()); assertEquals(0, bytes[6].toInt()); assertEquals(0x3F.toByte(), bytes[7])
        // -1.0f => 0xBF800000 => LE 00 00 80 BF
        assertEquals(0, bytes[8].toInt()); assertEquals(0, bytes[9].toInt()); assertEquals(0x80.toByte(), bytes[10]); assertEquals(0xBF.toByte(), bytes[11])
        // 2.0f clamps to 1.0f => LE 00 00 80 3F
        assertEquals(0, bytes[12].toInt()); assertEquals(0, bytes[13].toInt()); assertEquals(0x80.toByte(), bytes[14]); assertEquals(0x3F.toByte(), bytes[15])
    }

    @Test
    fun `pcm16 conversion dithers but keeps zero silent and roundtrips accurately`() {
        val bytes = PurrDspProcessor.float32ToPcm16Le(floatArrayOf(0.0f, 0.5f, -1.0f))
        assertEquals(6, bytes.size)
        // Zero input: triangular dither magnitude < 1 LSB always truncates to integer 0.
        // (Compare via .toInt() — assertEquals(Int, Byte) boxes to Integer-vs-Byte and always fails.)
        assertEquals(0, bytes[0].toInt()); assertEquals(0, bytes[1].toInt())

        val decoded = PurrDspProcessor.pcm16LeToFloat32(bytes)
        assertEquals(3, decoded.size)
        assertEquals(0.0f, decoded[0], 0.0001f)
        assertEquals(0.5f, decoded[1], 0.001f)
        assertEquals(-1.0f, decoded[2], 0.001f)
    }

    @Test
    fun `float32 conversion clamps out of range samples`() {
        val decoded = PurrDspProcessor.pcm16LeToFloat32(
            PurrDspProcessor.float32ToPcm16Le(floatArrayOf(5.0f, -5.0f))
        )
        assertTrue(decoded[0] <= 1.0f && decoded[0] > 0.99f)
        assertTrue(decoded[1] >= -1.0f && decoded[1] < -0.99f)
    }

    // ---------- Waveform telemetry bins ----------

    @Test
    fun `waveform bins of silence are all zero`() {
        val bins = PurrDspProcessor.computeWaveformBins(FloatArray(4800), binCount = 32)
        assertEquals(32, bins.size)
        assertTrue(bins.all { it == 0.0f })
    }

    @Test
    fun `waveform bins of a tone are normalized positives in 32 lanes`() {
        val sampleRate = 24000
        val tone = FloatArray(4800) { i ->
            0.6f * sin(2.0 * PI * 440.0 * i / sampleRate).toFloat()
        }
        val bins = PurrDspProcessor.computeWaveformBins(tone, binCount = 32)
        assertEquals(32, bins.size)
        assertTrue(bins.all { it >= 0.0f && it <= 1.0f })
        assertTrue(bins.maxOrNull()!! > 0.4f)
        // A pure sine touches near-peak amplitude in most lanes over a full period
        assertTrue(bins.count { it > 0.2f } > 16)
    }

    @Test
    fun `waveform bins honor custom bin count and empty input`() {
        assertEquals(8, PurrDspProcessor.computeWaveformBins(FloatArray(100), binCount = 8).size)
        assertEquals(0, PurrDspProcessor.computeWaveformBins(FloatArray(100), binCount = 0).size)
        assertTrue(PurrDspProcessor.computeWaveformBins(FloatArray(0), binCount = 32).all { it == 0.0f })
    }

    // ---------- Resampler edge cases ----------

    @Test
    fun `resample mono identity rate returns the samples untouched`() {
        val samples = floatArrayOf(0.1f, -0.2f, 0.3f, -0.4f, 0.5f)
        val out = PurrDspProcessor.resampleMono(samples, fromRate = 24000, toRate = 24000)
        assertTrue(samples contentEquals out)
    }

    @Test
    fun `resample mono downsamples 48khz to 24khz with clean finite output`() {
        val sampleRate = 48000
        val src = FloatArray(12000) { i ->
            0.5f * sin(2.0 * PI * 440.0 * i / sampleRate).toFloat()
        }
        val out = PurrDspProcessor.resampleMono(src, fromRate = 48000, toRate = 24000)
        assertTrue(abs(out.size - 6000) <= 2)
        assertTrue(out.all { it.isFinite() && abs(it) <= 1.0f })
    }

    @Test
    fun `resample mono tiny buffers fall back to linear path`() {
        val out = PurrDspProcessor.resampleMono(floatArrayOf(0.5f, 0.5f, 0.5f), fromRate = 48000, toRate = 24000)
        assertEquals(1, out.size)
        assertEquals(0.5f, out[0], 0.0001f)
    }

    // ---------- WSOLA time-scaler ----------

    @Test
    fun `wsola returns neutral speech rate untouched bit-exact`() {
        val src = FloatArray(12000) { i -> 0.5f * sin(2.0 * PI * 220.0 * i / 24000).toFloat() }
        val same = PurrDspProcessor.timeScaleWsola(src, rate = 1.0f)
        assertTrue(src contentEquals same)
        val slight = PurrDspProcessor.timeScaleWsola(src, rate = 1.04f)
        assertTrue(src contentEquals slight)
    }

    @Test
    fun `wsola scales duration accurately and preserves 220hz pitch coherence`() {
        val sr = 24000
        val src = FloatArray(24000) { i -> 0.55f * sin(2.0 * PI * 220.0 * i / sr).toFloat() }
        val faster = PurrDspProcessor.timeScaleWsola(src, rate = 1.25f, sampleRate = sr)
        val slower = PurrDspProcessor.timeScaleWsola(src, rate = 0.80f, sampleRate = sr)

        assertTrue(abs(faster.size - 19200) <= 64)
        assertTrue(abs(slower.size - 30000) <= 64)
        assertTrue(faster.all { it.isFinite() && abs(it) <= 1.0f })
        assertTrue(slower.all { it.isFinite() && abs(it) <= 1.0f })

        fun zeroCrossingHz(buf: FloatArray): Double {
            val start = 1200
            val end = buf.size - 1200
            var crossings = 0
            for (i in start until end) {
                if (buf[i - 1] <= 0f && buf[i] > 0f) crossings++
            }
            val sec = (end - start).toDouble() / sr
            return crossings / sec
        }

        val fastHz = zeroCrossingHz(faster)
        val slowHz = zeroCrossingHz(slower)
        assertTrue("Expected ~220Hz fast, got $fastHz", abs(fastHz - 220.0) < 12.0)
        assertTrue("Expected ~220Hz slow, got $slowHz", abs(slowHz - 220.0) < 12.0)
    }
}
