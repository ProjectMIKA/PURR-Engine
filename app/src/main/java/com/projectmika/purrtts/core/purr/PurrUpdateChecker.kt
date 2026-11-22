package com.projectmika.purrtts.core.purr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

/**
 * ✨ MIKA'S PURR-TTS UPDATE CHECKER (v1.8.4) ✨
 * MANUAL update check for the Settings sheet — deliberately NO background polling:
 * zero scheduled work, zero wake-locks, zero silent traffic (Master's standing law).
 * One tap → one GET to GitHub's `releases/latest` endpoint → version compare →
 * (newer?) the caller hands the release page to the user's browser for downloading.
 *
 * Pure JVM (java.net + kotlinx.serialization — no Android imports) so the compare
 * and parse halves stay unit-testable in CI (`PurrUpdateCheckerTest`).
 *
 * Fail-soft everywhere: any network/parse failure returns null = "check failed"
 * (the UI toasts it) — it never throws into the caller and never hangs the sheet
 * (both socket timeouts are capped at 10s).
 */
object PurrUpdateChecker {

    /**
     * GitHub's always-current release endpoint. Unauthenticated = 60 req/hr per IP,
     * which is plenty for a manual button — this is exactly why there is no polling.
     */
    const val RELEASES_LATEST_URL = "https://api.github.com/repos/ProjectMIKA/PURR-Engine/releases/latest"

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class LatestRelease(
        val tagName: String = "",
        val name: String = "",
        val htmlUrl: String = ""
    )

    /**
     * ✨ SEMVER LAW: strict numeric compare over dot-separated parts. `v`/`V` prefix
     * tolerated, missing parts read as 0, and pre-release/build suffixes (`-beta`,
     * `+meta`) are STRIPPED before comparing — a suffix never promotes a version,
     * so `1.8.3-beta` == `1.8.3` (not newer). Tags on this repo are plain `vX.Y.Z`.
     */
    fun isNewerVersion(latest: String, current: String): Boolean {
        fun parts(version: String): List<Int> = version.trim()
            .removePrefix("v").removePrefix("V")
            .substringBefore('-').substringBefore('+')
            .split('.')
            .map { it.toIntOrNull() ?: 0 }
        val latestParts = parts(latest)
        val currentParts = parts(current)
        for (index in 0 until maxOf(latestParts.size, currentParts.size)) {
            val latestValue = latestParts.getOrElse(index) { 0 }
            val currentValue = currentParts.getOrElse(index) { 0 }
            if (latestValue != currentValue) return latestValue > currentValue
        }
        return false
    }

    /** Parses a `releases/latest` JSON payload; fails soft to null on anything odd. */
    fun parseLatestRelease(payload: String): LatestRelease? = runCatching {
        json.decodeFromString<LatestRelease>(payload)
    }.getOrNull()

    /**
     * One blocking GET — call from a coroutine (this switches to `Dispatchers.IO`
     * itself). 10s connect/read timeouts, a `User-Agent` header (GitHub's API
     * requires one), and fail-soft to null on ANY error.
     */
    suspend fun fetchLatestRelease(): LatestRelease? = withContext(Dispatchers.IO) {
        runCatching {
            val connection = URL(RELEASES_LATEST_URL).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", "PURR-TTS-UpdateCheck")
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                if (connection.responseCode !in 200..299) return@runCatching null
                val payload = connection.inputStream.bufferedReader().use { reader ->
                    reader.readText()
                }
                parseLatestRelease(payload)
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }
}