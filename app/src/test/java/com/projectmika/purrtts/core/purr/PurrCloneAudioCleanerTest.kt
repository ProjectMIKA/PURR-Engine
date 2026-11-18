package com.projectmika.purrtts.core.purr

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ✨ PURE-JVM TESTS FOR MIKA'S CLONE REFERENCE AUDIO CLEANER ✨
 * Guards the import-path DSP chain: DC removal, rumble high-pass, the STFT
 * spectral-subtraction denoiser (seeded noise for determinism), silence trim,
 * best-voice-window selection, and the family reference-length contract.
 */
class PurrCloneAudioCleanerTest {

    private val rate = 24000

    private fun tone(freqHz: Double, seconds: Double, amplitude: Float = 0.5f): FloatArray {
        val n = (rate * seconds).toInt()
        return FloatArray(n) { i -> amplitude * sin(2.0 * PI * freqHz * i / rate).toFloat() }
    }

    private fun rms(samples: FloatArray): Double {
        if (samples.isEmpty()) return 0.0
        var acc = 0.0
        for (s in samples) acc += s.toDouble() * s
        return sqrt(acc / samples.size)
    }

    private fun concat(vararg parts: FloatArray): FloatArray {
        var total = 0
        for (p in parts) total += p.size
        val out = FloatArray(total)
        var pos = 0
        for (p in parts) {
            System.arraycopy(p, 0, out, pos, p.size)
            pos += p.size
        }
        return out
    }

    @Test
    fun `family clip targets are pocket five seconds zipvoice twelve`() {
        assertEquals(5, PurrCloneAudioCleaner.targetClipSeconds(SherpaModelFamily.POCKET_TTS))
        assertEquals(12, PurrCloneAudioCleaner.targetClipSeconds(SherpaModelFamily.ZIPVOICE))
        assertEquals(5, PurrCloneAudioCleaner.targetClipSeconds(SherpaModelFamily.KOKORO))
    }

    @Test
    fun `dc offset removal strips constant bias`() {
        val input = FloatArray(rate) { 0.3f + 0.1f * sin(2.0 * PI * 220.0 * it / rate).toFloat() }
        val cleaned = PurrCloneAudioCleaner.removeDcOffset(input)
        var mean = 0.0
        for (s in cleaned) mean += s
        mean /= cleaned.size
        assertTrue(abs(mean) < 1e-4)
    }

    @Test
    fun `peak normalization targets 0_92 and gates silent input`() {
        val quiet = FloatArray(rate) { 0.4f * sin(2.0 * PI * 330.0 * it / rate).toFloat() }
        val boosted = PurrCloneAudioCleaner.normalizePeak(quiet)
        assertEquals(0.92f, boosted.maxOf { abs(it) }, 0.01f)

        val silent = FloatArray(rate)
        assertEquals(0.0f, PurrCloneAudioCleaner.normalizePeak(silent).maxOf { abs(it) }, 1e-6f)
    }

    @Test
    fun `rumble high pass attenuates low end and preserves voice band`() {
        val low = PurrCloneAudioCleaner.applyBiquad(
            tone(30.0, 1.0, 0.5f),
            PurrCloneAudioCleaner.Biquad.highPass(rate.toDouble(), 85.0)
        )
        val mid = PurrCloneAudioCleaner.applyBiquad(
            tone(440.0, 1.0, 0.5f),
            PurrCloneAudioCleaner.Biquad.highPass(rate.toDouble(), 85.0)
        )
        // Skip the first 2000 samples (filter settling) when measuring.
        val lowRms = rms(low.copyOfRange(2000, low.size))
        val midRms = rms(mid.copyOfRange(2000, mid.size))
        // 2-pole Butterworth @ 30Hz vs 85Hz cutoff measures ~0.12 (Python-verified).
        assertTrue("low=$lowRms mid=$midRms", lowRms < midRms * 0.25)
        assertTrue(midRms > 0.25)
    }

    @Test
    fun `silence trim keeps voiced region and returns empty for pure silence`() {
        val clip = concat(
            PurrCloneAudioCleaner.seededNoise(rate, 1L, 0.001f),
            tone(300.0, 1.0),
            PurrCloneAudioCleaner.seededNoise(rate, 2L, 0.001f)
        )
        val trimmed = PurrCloneAudioCleaner.trimSilence(clip, rate)
        assertTrue(trimmed.size < clip.size)
        assertTrue(trimmed.size > rate) // the 1s tone survived
        assertEquals(0, PurrCloneAudioCleaner.trimSilence(FloatArray(rate * 2), rate).size)
    }

    @Test
    fun `best energy window locks onto the loudest region`() {
        val quietNoise = PurrCloneAudioCleaner.seededNoise(rate * 5, 3L, 0.01f)
        val loudTone = tone(280.0, 5.0, 0.6f)
        val clip = concat(quietNoise, loudTone, quietNoise)
        val window = PurrCloneAudioCleaner.bestEnergyWindow(clip, rate, rate * 5)
        assertTrue(rms(window) > 0.35)
        assertTrue(rms(window) > rms(clip))
    }

    @Test
    fun `spectral subtraction suppresses noise floor far more than voiced regions`() {
        // Deterministic fixture: stationary 60Hz mains hum across the WHOLE clip (the classic
        // static/hum cleanup case) plus a 440Hz voice burst only in the middle second. The
        // quietest frames are hum-only, so the learned profile is the hum spectrum itself —
        // subtraction must crush the hum while the voice bins (magnitudes far above the floor)
        // survive. No RNG: the result is identical on every machine and seed.
        val input = FloatArray(rate * 3) { i ->
            val hum = 0.15f * sin(2.0 * PI * 60.0 * i / rate).toFloat()
            val voice = if (i in rate until rate * 2) {
                0.4f * sin(2.0 * PI * 440.0 * i / rate).toFloat()
            } else 0f
            hum + voice
        }

        val cleaned = PurrCloneAudioCleaner.spectralSubtract(
            input, rate, beta = 1.5f, floor = 0.07f
        )

        // Hum-only region (first half-second) vs voiced region (middle half-second).
        val humBefore = rms(input.copyOfRange(0, rate / 2))
        val humAfter = rms(cleaned.copyOfRange(0, rate / 2))
        val voicedBefore = rms(input.copyOfRange(rate + rate / 4, rate + rate / 2))
        val voicedAfter = rms(cleaned.copyOfRange(rate + rate / 4, rate + rate / 2))

        // Diagnostic: Gradle prints test stdout into the failure report — keeps CI debuggable.
        println("[SPEC_TEST] hum $humBefore -> $humAfter (ratio ${humAfter / humBefore}) | " +
            "voiced $voicedBefore -> $voicedAfter (ratio ${voicedAfter / voicedBefore})")

        assertTrue(
            "humBefore=$humBefore humAfter=$humAfter",
            humAfter < humBefore * 0.5 // stationary hum is learned exactly: ~0.07 floor expected
        )
        assertTrue("voicedBefore=$voicedBefore voicedAfter=$voicedAfter", voicedAfter > voicedBefore * 0.6)
    }

    @Test
    fun `select best clip returns family window with anti pop fades`() {
        val quietNoise = PurrCloneAudioCleaner.seededNoise(rate * 2, 7L, 0.008f)
        val voiced = concat(
            tone(300.0, 3.0, 0.5f),
            PurrCloneAudioCleaner.seededNoise(rate, 8L, 0.02f),
            tone(420.0, 3.0, 0.5f)
        )
        val clip = concat(quietNoise, voiced)
        val selected = PurrCloneAudioCleaner.selectBestClip(clip, rate, 5)

        assertEquals(rate * 5, selected.size)
        assertTrue(selected.all { it.isFinite() })
        // Anti-pop fades: the very first/last samples must be near zero.
        assertTrue(abs(selected.first()) < 0.05f)
        assertTrue(abs(selected.last()) < 0.05f)
    }

    @Test
    fun `clean clip end to end removes bias noise and normalizes`() {
        val noise = PurrCloneAudioCleaner.seededNoise(rate * 3, 99L, 0.08f)
        val biasedTone = FloatArray(rate * 3) { i ->
            0.3f + 0.45f * sin(2.0 * PI * 330.0 * i / rate).toFloat()
        }
        val dirty = concat(noise, biasedTone)

        val cleaned = PurrCloneAudioCleaner.cleanClip(dirty, rate)
        assertTrue(cleaned.all { it.isFinite() && abs(it) <= 1.0f })
        assertEquals(0.92f, cleaned.maxOf { abs(it) }, 0.02f)

        var mean = 0.0
        for (s in cleaned) mean += s
        mean /= cleaned.size
        assertTrue(abs(mean) < 0.02)
    }

    @Test
    fun `fully disabled config returns an untouched copy`() {
        val input = PurrCloneAudioCleaner.seededNoise(rate, 11L, 0.2f)
        val passthrough = PurrCloneAudioCleaner.cleanClip(
            input, rate,
            PurrCloneAudioCleaner.CleanConfig(
                removeDcOffset = false,
                highPassRumbleHz = 0.0,
                noiseReduction = PurrCloneAudioCleaner.NoiseReduction.OFF,
                normalizePeak = false
            )
        )
        assertTrue(input contentEquals passthrough)
    }

    @Test
    fun `seeded noise is deterministic per seed`() {
        val a = PurrCloneAudioCleaner.seededNoise(1000, 5L, 0.5f)
        val b = PurrCloneAudioCleaner.seededNoise(1000, 5L, 0.5f)
        val c = PurrCloneAudioCleaner.seededNoise(1000, 6L, 0.5f)
        assertTrue(a contentEquals b)
        assertTrue(!(a contentEquals c))
    }

    @Test
    fun `empty and tiny inputs never crash the pipeline`() {
        assertTrue(PurrCloneAudioCleaner.cleanClip(FloatArray(0), rate).isEmpty())
        assertEquals(0, PurrCloneAudioCleaner.selectBestClip(FloatArray(0), rate, 5).size)
        assertEquals(0, PurrCloneAudioCleaner.trimSilence(FloatArray(0), rate).size)
        val tiny = FloatArray(10) { 0.5f }
        assertTrue(PurrCloneAudioCleaner.spectralSubtract(tiny, rate, 1.5f, 0.07f).size == 10)
    }
}
