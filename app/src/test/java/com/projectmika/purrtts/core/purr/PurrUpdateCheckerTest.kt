package com.projectmika.purrtts.core.purr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ✨ PURE-JVM TESTS FOR THE MANUAL APP UPDATE CHECKER ✨
 * Guards the two halves that run without a network: the strict numeric version
 * compare (pre-release suffixes never promote — `1.8.3-beta` == `1.8.3`) and the
 * fail-soft parse of GitHub's `releases/latest` payload.
 */
class PurrUpdateCheckerTest {

    // ── isNewerVersion ──

    @Test
    fun `equal versions are not newer`() {
        assertFalse(PurrUpdateChecker.isNewerVersion("v1.8.3", "1.8.3"))
        assertFalse(PurrUpdateChecker.isNewerVersion("1.8.3", "v1.8.3"))
    }

    @Test
    fun `older versions are not newer`() {
        assertFalse(PurrUpdateChecker.isNewerVersion("v1.8.2", "1.8.3"))
        assertFalse(PurrUpdateChecker.isNewerVersion("v1.7.9", "1.8.3"))
    }

    @Test
    fun `patch minor and major bumps are newer`() {
        assertTrue(PurrUpdateChecker.isNewerVersion("v1.8.4", "1.8.3"))
        assertTrue(PurrUpdateChecker.isNewerVersion("v1.9.0", "1.8.3"))
        assertTrue(PurrUpdateChecker.isNewerVersion("v2.0.0", "1.8.3"))
    }

    @Test
    fun `missing parts read as zero`() {
        assertTrue(PurrUpdateChecker.isNewerVersion("v1.9", "1.8.3"))
        assertFalse(PurrUpdateChecker.isNewerVersion("v1.8", "1.8.3"))
    }

    @Test
    fun `prerelease suffix never promotes a version`() {
        assertFalse(PurrUpdateChecker.isNewerVersion("v1.8.3-beta", "1.8.3"))
        assertFalse(PurrUpdateChecker.isNewerVersion("v1.8.3+build.7", "1.8.3"))
        assertTrue(PurrUpdateChecker.isNewerVersion("v1.8.4-rc1", "1.8.3"))
    }

    // ── parseLatestRelease ──

    @Test
    fun `parses the github releases latest payload`() {
        val payload = """
            {
              "url": "https://api.github.com/repos/ProjectMIKA/PURR-Engine/releases/1",
              "tag_name": "v1.8.4",
              "name": "PURR-TTS v1.8.4 // The Decoupled Dialects",
              "html_url": "https://github.com/ProjectMIKA/PURR-Engine/releases/tag/v1.8.4",
              "body": "release notes body",
              "draft": false,
              "prerelease": false
            }
        """.trimIndent()
        val release = PurrUpdateChecker.parseLatestRelease(payload)
        assertEquals("v1.8.4", release?.tagName)
        assertEquals("PURR-TTS v1.8.4 // The Decoupled Dialects", release?.name)
        assertEquals(
            "https://github.com/ProjectMIKA/PURR-Engine/releases/tag/v1.8.4",
            release?.htmlUrl
        )
    }

    @Test
    fun `malformed payload fails soft to null`() {
        assertNull(PurrUpdateChecker.parseLatestRelease("not json at all"))
        assertNull(PurrUpdateChecker.parseLatestRelease(""))
    }
}