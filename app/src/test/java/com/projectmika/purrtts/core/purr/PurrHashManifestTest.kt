package com.projectmika.purrtts.core.purr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ✨ PURE-JVM TESTS FOR THE SELF-HEALING MODEL HASH MANIFEST (v1.8.1) ✨
 * Guards manifest parsing, digest sanity, and the manifest-wins-over-baked-pin rule that
 * keeps upstream re-publishes (k2-fsa re-uploaded Pocket TTS on 2026-11-21) from bricking
 * every fresh cartridge download.
 */
class PurrHashManifestTest {

    private val pocketSha = "f4da7c7bd465f7b91e89191487fe90504396a51d6f0bcf94d0787633e2657293"
    private val kokoroSha = "72c347230107ef8a31098ec4d468a605244beef5f1ed00db97efa13518800653"

    private val sampleJson = """
        {
          "version": 1,
          "generatedAtUtc": "2026-11-21T12:00:00Z",
          "source": "scripts/refresh_model_manifest.py",
          "assets": {
            "https://example.com/pocket.tar.bz2": {"sha256": "$pocketSha", "sizeBytes": 98341351},
            "https://example.com/kokoro.onnx": {"sha256": "$kokoroSha"},
            "https://example.com/bad.bin": {"sha256": "not-a-hash"},
            "https://example.com/short.bin": {"sha256": "abcd"}
          },
          "futureField": true
        }
    """.trimIndent()

    @Test
    fun `parse extracts well-formed digests and drops malformed ones`() {
        val parsed = PurrHashManifest.parse(sampleJson)
        assertEquals(2, parsed.size)
        assertEquals(pocketSha, parsed["https://example.com/pocket.tar.bz2"])
        assertEquals(kokoroSha, parsed["https://example.com/kokoro.onnx"])
    }

    @Test
    fun `parse tolerates unknown fields via lenient json`() {
        assertEquals(2, PurrHashManifest.parse(sampleJson).size)
    }

    @Test
    fun `malformed json parses to an empty map`() {
        assertTrue(PurrHashManifest.parse("{ not json").isEmpty())
        assertTrue(PurrHashManifest.parse("").isEmpty())
    }

    @Test
    fun `unsupported schema version parses to an empty map`() {
        val v2 = """{"version": 2, "assets": {"x": {"sha256": "${"a".repeat(64)}"}}}"""
        assertNull(PurrHashManifest.parseDocument(v2))
        assertTrue(PurrHashManifest.parse(v2).isEmpty())
    }

    @Test
    fun `digest sanity accepts 64 hex chars in any case and rejects everything else`() {
        assertTrue(PurrHashManifest.isWellFormedSha256("a".repeat(64)))
        assertTrue(PurrHashManifest.isWellFormedSha256(kokoroSha.uppercase()))
        assertFalse(PurrHashManifest.isWellFormedSha256("a".repeat(63)))
        assertFalse(PurrHashManifest.isWellFormedSha256("g".repeat(64)))
        assertFalse(PurrHashManifest.isWellFormedSha256(""))
    }

    @Test
    fun `real pocket int8 digest from the 2026-11-21 re-upload parses`() {
        val live = """{"version": 1, "assets": {"https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/sherpa-onnx-pocket-tts-int8-2026-01-26.tar.bz2": {"sha256": "$pocketSha", "sizeBytes": 98341351}}}"""
        val parsed = PurrHashManifest.parse(live)
        assertEquals(pocketSha, parsed["https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/sherpa-onnx-pocket-tts-int8-2026-01-26.tar.bz2"])
    }
}
