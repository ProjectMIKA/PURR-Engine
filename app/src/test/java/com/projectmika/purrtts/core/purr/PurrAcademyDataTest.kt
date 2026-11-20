package com.projectmika.purrtts.core.purr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ✨ PURE-JVM TESTS FOR MIKA'S ACADEMY (tutorial data + update gate) ✨
 * Guards tutorial content integrity and the GachaSwipe-style update gate:
 * seen == 0 => first-run walkthrough; 0 < seen < TUTORIAL_VERSION => patchnotes popup once.
 */
class PurrAcademyDataTest {

    @Test
    fun `tutorial steps are complete unique and well formed`() {
        assertTrue(PURR_TUTORIAL_STEPS.isNotEmpty())
        val ids = PURR_TUTORIAL_STEPS.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue("exactly one patchnotes slide", PURR_TUTORIAL_STEPS.count { it.id == "VERSION_PATCHNOTES" } == 1)
        assertTrue("exactly one first-launch slide", PURR_TUTORIAL_STEPS.count { it.firstLaunchOnly } == 1)
        PURR_TUTORIAL_STEPS.forEach { step ->
            assertTrue("blank title: ${step.id}", step.title.isNotBlank())
            assertTrue("blank body: ${step.id}", step.body.length > 40)
            assertTrue("color missing alpha: ${step.id}", step.colorHex in 0xFF000000..0xFFFFFFFF)
        }
    }

    @Test
    fun `whats new highlights are populated and unique`() {
        assertTrue(PURR_WHATS_NEW.isNotEmpty())
        val ids = PURR_WHATS_NEW.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        PURR_WHATS_NEW.forEach { entry ->
            assertTrue(entry.title.isNotBlank())
            assertTrue(entry.body.length > 40)
            assertTrue(entry.colorHex in 0xFF000000..0xFFFFFFFF)
        }
    }

    @Test
    fun `first launch triggers the full walkthrough`() {
        assertEquals(true, PurrUpdateGate.needsFirstRunTutorial(0))
        assertEquals(false, PurrUpdateGate.needsFirstRunTutorial(1))
        assertEquals(AcademyEntry.FULL_TUTORIAL, PurrUpdateGate.resolveEntry(0))
    }

    @Test
    fun `older seen version auto opens patchnotes exactly once`() {
        // seen == 0 is ALWAYS a first launch (full walkthrough), regardless of TUTORIAL_VERSION.
        assertEquals(false, PurrUpdateGate.shouldAutoOpenPatchnotes(0))
        assertEquals(false, PurrUpdateGate.shouldAutoOpenPatchnotes(TUTORIAL_VERSION))
        assertEquals(false, PurrUpdateGate.shouldAutoOpenPatchnotes(TUTORIAL_VERSION + 5))

        // 0 < seen < TUTORIAL_VERSION => returning user on older content => patchnotes once.
        // Only exists once content has bumped at least twice (TUTORIAL_VERSION >= 2):
        // at TUTORIAL_VERSION == 1 the only unseen value is 0 == never launched.
        if (TUTORIAL_VERSION >= 2) {
            assertEquals(true, PurrUpdateGate.shouldAutoOpenPatchnotes(TUTORIAL_VERSION - 1))
            assertEquals(AcademyEntry.PATCHNOTES_ONLY, PurrUpdateGate.resolveEntry(TUTORIAL_VERSION - 1))
        } else {
            assertEquals(false, PurrUpdateGate.shouldAutoOpenPatchnotes(0))
            assertEquals(AcademyEntry.FULL_TUTORIAL, PurrUpdateGate.resolveEntry(0))
        }
        assertEquals(AcademyEntry.NONE, PurrUpdateGate.resolveEntry(TUTORIAL_VERSION))
    }

    @Test
    fun `link constants are https urls`() {
        assertTrue(PURR_REPO_URL.startsWith("https://"))
        assertTrue(PURR_KOFI_URL.startsWith("https://"))
        assertTrue(PURR_KOFI_URL.contains("ko-fi.com"))
    }
}
