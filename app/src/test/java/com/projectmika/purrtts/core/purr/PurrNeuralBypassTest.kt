package com.projectmika.purrtts.core.purr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ✨ PURE-JVM TESTS FOR THE NEURAL PURE SLANG CARVE-OUT ✨
 * Guards the two-gate split: `shouldBypassEnhancers` stays the master Neural Pure bypass for
 * heavyweight voices, while `shouldBypassSlang` lets users opt flagship voices back INTO the
 * slang & honorifics pipeline (text normalization aid) without re-enabling the intrusive
 * enhancers (stutters, comma-chopping, micro-chunking).
 */
class PurrNeuralBypassTest {

    private fun profile(
        voiceId: String,
        autoBypass: Boolean = true,
        slangDuringPure: Boolean = false
    ): PurrProfile = PurrProfile(id = "test", name = "Test").copy(
        primaryVoiceId = voiceId,
        autoBypassEnhancersForNeuralModels = autoBypass,
        slangDuringNeuralPure = slangDuringPure
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

    @Test
    fun `flagship voices keep slang by default after the carve-out`() {
        // slangDuringNeuralPure defaults to TRUE — large models read texting shorthand verbatim
        // otherwise, so slang expansion is on unless the user explicitly disables it.
        val defaultProfile = PurrProfile(id = "test", name = "Test").copy(primaryVoiceId = "qwen3_vivian")
        assertFalse(PurrNeuralBypass.shouldBypassSlang(defaultProfile))
    }

    @Test
    fun `slang stays bypassed during neural pure when the carve-out is explicitly off`() {
        assertTrue(PurrNeuralBypass.shouldBypassSlang(profile("qwen3_vivian")))
    }

    @Test
    fun `slang carve-out returns flagship voices to the slang pipeline`() {
        assertFalse(PurrNeuralBypass.shouldBypassSlang(profile("qwen3_vivian", slangDuringPure = true)))
    }

    @Test
    fun `slang carve-out does not re-enable slang for non flagship voices`() {
        assertFalse(PurrNeuralBypass.shouldBypassSlang(profile("af_heart", slangDuringPure = true)))
    }

    @Test
    fun `slang carve-out still respects the master toggle`() {
        assertFalse(
            PurrNeuralBypass.shouldBypassSlang(
                profile("qwen3_vivian", autoBypass = false, slangDuringPure = true)
            )
        )
    }
}
