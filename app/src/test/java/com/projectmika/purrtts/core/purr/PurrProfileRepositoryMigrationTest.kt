package com.projectmika.purrtts.core.purr

import java.io.File
import java.nio.file.Files
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ✨ PURE-JVM TESTS FOR THE v1.8.3 STATE-FILE MIGRATION ✨
 * `PurrProfileRepository.migrateLegacyEnhancerFlags` upgrades v1.8.2 state files BEFORE decoding:
 * the combined `enableSlangAndHonorifics` bender flag seeds the new `enableSlang` /
 * `enableHonorifics` / `enableStutters` trio, and a per-profile `qwen3GpuOffload` hoists into the
 * GLOBAL `PurrForgeState.qwen3GpuOffload` (hardware outlives vibe presets). Without this
 * raw-JSON pass, `ignoreUnknownKeys` would silently default every new flag to ON and flip
 * clean-prose presets into slang mode.
 *
 * ✨ Master's call: users who explicitly DISABLED the v1.8.2 flagship carve-out
 * (`slangDuringNeuralPure = false` — full v1.8.0 Neural Pure) START WITH SLANG OFF everywhere;
 * their expressed clean-speech preference wins. One tap re-enables it.
 */
class PurrProfileRepositoryMigrationTest {

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    /** Encodes a real default profile, then downgrades it to the v1.8.2 legacy key shape. */
    private fun legacyProfileJson(
        combined: Boolean,
        slangOnFlagship: Boolean? = true,
        gpuOffload: Boolean? = null
    ): String {
        val base = json.encodeToString(PurrProfile(id = "p1", name = "Test Preset"))
            .let { Json.parseToJsonElement(it).jsonObject }
        val legacyKeys = setOf(
            "enableSlang", "enableHonorifics", "enableStutters",
            "slangDuringNeuralPure", "qwen3GpuOffload"
        )
        return buildJsonObject {
            base.forEach { (key, value) ->
                if (key !in legacyKeys) put(key, value)
            }
            put("enableSlangAndHonorifics", JsonPrimitive(combined))
            if (slangOnFlagship != null) put("slangDuringNeuralPure", JsonPrimitive(slangOnFlagship))
            if (gpuOffload != null) put("qwen3GpuOffload", JsonPrimitive(gpuOffload))
        }.toString()
    }

    private fun repositoryFor(profileJson: String): Pair<PurrProfileRepository, File> {
        val dir = Files.createTempDirectory("purr_mig").toFile()
        File(dir, "purr_forge_state.json").writeText(
            """{"activeProfileId":"p1","profiles":[$profileJson]}"""
        )
        return PurrProfileRepository(dir) to dir
    }

    @Test
    fun `legacy combined flag seeds all three bender toggles on`() {
        val (repo, dir) = repositoryFor(legacyProfileJson(combined = true))
        try {
            val profile = repo.state.value.profiles.first()
            assertTrue(profile.enableSlang)
            assertTrue(profile.enableHonorifics)
            assertTrue(profile.enableStutters)
            assertFalse(repo.state.value.qwen3GpuOffload)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `legacy combined flag seeds all three bender toggles off for clean prose presets`() {
        val (repo, dir) = repositoryFor(legacyProfileJson(combined = false))
        try {
            val profile = repo.state.value.profiles.first()
            assertFalse(profile.enableSlang)
            assertFalse(profile.enableHonorifics)
            assertFalse(profile.enableStutters)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `explicit full neural pure user starts with slang off everywhere`() {
        // slangDuringNeuralPure = false was the "restore full v1.8.0 Neural Pure" choice —
        // the migrated Slang toggle honors that preference instead of defaulting ON.
        val (repo, dir) = repositoryFor(legacyProfileJson(combined = true, slangOnFlagship = false))
        try {
            val profile = repo.state.value.profiles.first()
            assertFalse(profile.enableSlang)
            assertTrue(profile.enableHonorifics)
            assertTrue(profile.enableStutters)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `profile level gpu offload hoists into the global hardware offload state`() {
        val (repo, dir) = repositoryFor(legacyProfileJson(combined = true, gpuOffload = true))
        try {
            assertTrue(repo.state.value.qwen3GpuOffload)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `already migrated state passes through without re-seeding`() {
        val migrated = buildJsonObject {
            put("id", JsonPrimitive("p1"))
            put("name", JsonPrimitive("Test Preset"))
            put("enableSlang", JsonPrimitive(false))
            put("enableHonorifics", JsonPrimitive(true))
            put("enableStutters", JsonPrimitive(true))
        }.toString()
        val (repo, dir) = repositoryFor(migrated)
        try {
            val profile = repo.state.value.profiles.first()
            assertFalse(profile.enableSlang)
            assertTrue(profile.enableHonorifics)
            assertTrue(profile.enableStutters)
        } finally {
            dir.deleteRecursively()
        }
    }
}