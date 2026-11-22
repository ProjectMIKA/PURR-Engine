package com.projectmika.purrtts.core.purr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ✨ PURE-JVM TESTS FOR THE NEURAL PURE BYPASS (v1.8.3) ✨
 * `shouldBypassEnhancers` is the master Neural Pure bypass for heavyweight voices. The old
 * two-gate split (`shouldBypassSlang` + profile `slangDuringNeuralPure`) is retired: with the
 * three-way bender split, SLANG is the only enhancer that survives Neural Pure on flagship
 * voices (governed solely by the profile's `enableSlang` toggle — enforced inside
 * PurrTextProcessor.process()), while honorifics & stutters stay bypassed with the rest.
 */
class PurrNeuralBypassTest {

    private fun profile(
        voiceId: String,
        autoBypass: Boolean = true
    ): PurrProfile = PurrProfile(id = "test", name = "Test").copy(
        primaryVoiceId = voiceId,
        autoBypassEnhancersForNeuralModels = autoBypass
    )

    @Test
    fun `qwen3 voice is detected as an advanced neural voice`() {
        assertTrue(PurrNeuralBypass.isAdvancedNeuralVoice(profile("qwen3_vivian")))
    }

    @Test
    fun `classic kokoro voice is not an advanced neural voice`() {
        assertFalse(PurrNeuralBypass.isAdvancedNeuralVoice(profile("af_heart")))
    }

    @Test
    fun `neural pure bypasses enhancers on flagship voices by default`() {
        assertTrue(PurrNeuralBypass.shouldBypassEnhancers(profile("qwen3_vivian")))
    }

    @Test
    fun `master toggle off disables the whole bypass`() {
        assertFalse(PurrNeuralBypass.shouldBypassEnhancers(profile("qwen3_vivian", autoBypass = false)))
    }
}
