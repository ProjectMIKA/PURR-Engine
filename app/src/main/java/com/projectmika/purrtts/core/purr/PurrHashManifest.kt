package com.projectmika.purrtts.core.purr

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * ✨ MIKA'S SELF-HEALING MODEL HASH MANIFEST (v1.8.1) ✨
 *
 * WHY THIS EXISTS: the catalog's baked-in `expectedSha256` pins rot. Upstream model hosts
 * serve MUTABLE urls — k2-fsa silently re-uploads release assets under the same
 * `tts-models` tag (Pocket TTS INT8/FP32 re-uploaded 2026-11-21 and every fresh install
 * started failing SHA-256), and HuggingFace `resolve/main` URLs move with the branch tip.
 * The fail-closed SHA-256 law then (correctly!) rejects every download forever until a new
 * APK ships new pins.
 *
 * THE FIX: a tiny manifest JSON hosted in this repo (`model_manifest.json`, served via
 * raw.githubusercontent) carries the CURRENT verified digest per asset URL. Downloads
 * resolve `manifest → baked pin` (manifest wins when present and well-formed; baked pin
 * remains the offline floor). On a mismatch the manager force-refreshes the manifest ONCE
 * before failing closed, so upstream re-publishes self-heal without an APK release.
 *
 * PURE KOTLIN: no Android imports — parse/resolve logic is unit-testable on the JVM.
 */
@Serializable
data class PurrManifestAsset(
    val sha256: String,
    @SerialName("sizeBytes") val sizeBytes: Long? = null
)

@Serializable
data class PurrHashManifestDocument(
    val version: Int = 1,
    val generatedAtUtc: String = "",
    val source: String = "",
    val assets: Map<String, PurrManifestAsset> = emptyMap()
)

object PurrHashManifest {

    val LENIENT_JSON: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Parses a manifest document, returning null when the JSON is malformed or the
     * schema version is unsupported (callers then fall back to baked catalog pins).
     */
    fun parseDocument(json: String): PurrHashManifestDocument? = runCatching {
        val doc = LENIENT_JSON.decodeFromString<PurrHashManifestDocument>(json)
        if (doc.version != 1) return@runCatching null
        doc
    }.getOrNull()

    /**
     * Parses a manifest into a simple `assetUrl → sha256` map, dropping any entry whose
     * digest is not a well-formed 64-char hex string (fail-closed: garbage never wins
     * over a baked pin).
     */
    fun parse(json: String): Map<String, String> =
        parseDocument(json)?.assets
            ?.mapValues { it.value.sha256.trim() }
            ?.filterValues { isWellFormedSha256(it) }
            ?: emptyMap()

    fun isWellFormedSha256(hex: String): Boolean =
        hex.length == 64 && hex.all { (it in '0'..'9') || (it in 'a'..'f') || (it in 'A'..'F') }
}
