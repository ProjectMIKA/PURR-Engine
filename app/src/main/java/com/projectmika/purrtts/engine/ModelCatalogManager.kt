package com.projectmika.purrtts.engine

import android.content.Context
import android.util.Log
import com.projectmika.purrtts.core.purr.BackendType
import com.projectmika.purrtts.core.purr.BundleAssetFile
import com.projectmika.purrtts.core.purr.DownloadState
import com.projectmika.purrtts.core.purr.ModelBundleSpec
import com.projectmika.purrtts.core.purr.ModelBundleState
import com.projectmika.purrtts.core.purr.PurrDefaultPresets
import com.projectmika.purrtts.core.purr.PurrHashManifest
import com.projectmika.purrtts.core.purr.PurrProfileRepository
import com.projectmika.purrtts.core.purr.SherpaModelFamily
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

/**
 * ✨ MIKA'S HUGGING FACE / GITHUB MODEL & VOICE BUNDLE MANAGER ✨
 * Supports HTTP Range resumable downloads, streaming SHA-256 verification,
 * and safe `.tar.bz2` archive unpacking for sherpa-onnx bundles.
 */
class ModelCatalogManager(
    private val context: Context,
    private val repository: PurrProfileRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {

    companion object {
        private const val TAG = "PurrCatalog"

        // ✨ TRUST RENEWAL (v1.8.1): upstream model hosts are MUTABLE — k2-fsa re-uploads release
        // assets under the same `tts-models` tag (Pocket INT8/FP32 on 2026-11-21, Kokoro packs on
        // 2026-10-26) and HuggingFace `resolve/main` moves with the branch tip. Baked catalog pins
        // rot and the fail-closed SHA-256 law then rejects every fresh download forever. This
        // manifest is the live source of truth; baked pins are the offline floor only.
        private const val MANIFEST_URL =
            "https://raw.githubusercontent.com/ProjectMIKA/PURR-Engine/main/model_manifest.json"
    }

    val catalog: List<ModelBundleSpec> = PurrDefaultPresets.modelCatalog()

    private val activeJobs = ConcurrentHashMap<String, Job>()

    // ✨ TRUST RENEWAL (v1.8.1): live expected-hash registry (assetUrl → sha256). Fetched from the
    // manifest at download start, force-refreshed ONCE on any SHA mismatch, cached to disk so an
    // offline device still gets the last-good truth instead of a rotten baked pin.
    @Volatile
    private var manifestByAssetUrl: Map<String, String> = emptyMap()
    private val manifestClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
    private val manifestCacheFile: File by lazy {
        File(context.filesDir, "models/manifest_cache.json")
    }

    init {
        // ✨ TRUST RENEWAL (v1.8.1): preload the manifest so every download/verify resolves the
        // freshest known-good expected hash before the baked pin is ever consulted.
        scope.launch { runCatching { refreshManifest(force = false) } }

        // ✨ SELF-HEALING STARTUP INTEGRITY & COMPANION MIGRATION (v1.8.8):
        // Verify every cartridge persisted as INSTALLED actually has intact extracted weights
        // and complete dependencies (tokens.txt, espeak-ng-data/phontab.., companion vocoder).
        // Auto re-extract if the verified .tar.bz2 sits on disk; otherwise clear phantom state.
        scope.launch {
            verifyInstalledBundlesAtStartup()
        }
    }

    private suspend fun verifyInstalledBundlesAtStartup() = withContext(Dispatchers.IO) {
        catalog.forEach { spec ->
            if (spec.isBuiltInOsCartridge) return@forEach
            val state = repository.state.value.modelStates[spec.id] ?: return@forEach
            if (state.state != DownloadState.INSTALLED) return@forEach

            when {
                spec.isArchiveTarBz2 && spec.sherpaFamily != null -> {
                    val extractDir = resolveSherpaExtractDir(spec)
                    if (!hasCompleteSherpaExtract(spec, extractDir)) {
                        val archiveFile = resolveDestinationFile(spec)
                        if (archiveFile.isFile && archiveFile.length() > 1024L) {
                            Log.i(TAG, "[SELF_HEAL] Re-extracting incomplete Sherpa bundle ${spec.id} from ${archiveFile.name}")
                            val recovered = runCatching {
                                extractDir.deleteRecursively()
                                extractTarBz2(archiveFile, extractDir)
                                hasCompleteSherpaExtract(spec, extractDir)
                            }.getOrDefault(false)
                            if (!recovered) {
                                Log.w(TAG, "[SELF_HEAL] Re-extract incomplete for ${spec.id}; resetting to NOT_INSTALLED")
                                repository.updateModelState(
                                    spec.id,
                                    ModelBundleState(
                                        specId = spec.id,
                                        state = DownloadState.NOT_INSTALLED,
                                        errorMessage = "Incomplete extract detected — tap DOWNLOAD to repair"
                                    )
                                )
                                return@forEach
                            }
                        } else {
                            Log.w(TAG, "[SELF_HEAL] Missing extracted weights & archive for ${spec.id}; resetting state")
                            repository.updateModelState(
                                spec.id,
                                ModelBundleState(
                                    specId = spec.id,
                                    state = DownloadState.NOT_INSTALLED,
                                    errorMessage = "Model files missing on disk — tap DOWNLOAD to restore"
                                )
                            )
                            return@forEach
                        }
                    }

                    if (spec.companionUrl.isNotBlank()) {
                        val companion = File(extractDir, spec.companionFileName)
                        if (!companion.exists() || companion.length() <= 1024L) {
                            Log.i(TAG, "Companion migration: fetching ${spec.companionFileName} for ${spec.id}")
                            runCatching { downloadCompanionAsset(spec, extractDir) }
                                .onFailure { Log.w(TAG, "Companion migration failed for ${spec.id}: ${it.message}") }
                        }
                    }
                }
                spec.extraAssets.isNotEmpty() || spec.backend == BackendType.ONNX_NATIVE || spec.backend == BackendType.QWEN3_NATIVE -> {
                    val dir = resolveNativeModelDir(spec.id)
                    val primaryOk = File(dir, spec.fileName).let { it.isFile && it.length() > 0L }
                    val extrasOk = spec.extraAssets.all { asset ->
                        File(dir, asset.relativePath).let { it.isFile && it.length() > 0L }
                    }
                    if (!primaryOk || !extrasOk) {
                        Log.w(TAG, "[SELF_HEAL] Native bundle ${spec.id} missing asset files; marking NOT_INSTALLED")
                        repository.updateModelState(
                            spec.id,
                            ModelBundleState(
                                specId = spec.id,
                                state = DownloadState.NOT_INSTALLED,
                                errorMessage = "Incomplete bundle files — tap DOWNLOAD to resume"
                            )
                        )
                    }
                }
                spec.backend == BackendType.LITERT_NPU_GPU -> {
                    val target = resolveDestinationFile(spec)
                    if (!target.isFile || target.length() <= 1024L) {
                        repository.updateModelState(
                            spec.id,
                            ModelBundleState(specId = spec.id, state = DownloadState.NOT_INSTALLED)
                        )
                    }
                }
            }
        }
    }

    private fun hasCompleteSherpaExtract(spec: ModelBundleSpec, extractDir: File): Boolean {
        if (!extractDir.exists() || !extractDir.isDirectory) return false
        val files = extractDir.walkTopDown()
            .filter {
                it.isFile &&
                    it.length() > 0L &&
                    !it.absolutePath.contains("${File.separator}espeak-ng-data${File.separator}")
            }
            .toList()
        if (files.none { it.extension.equals("onnx", ignoreCase = true) }) return false
        val espeakDir = extractDir.walkTopDown().firstOrNull { it.isDirectory && it.name == "espeak-ng-data" }
        if (espeakDir != null) {
            val espeakValid = File(espeakDir, "phontab").isFile &&
                File(espeakDir, "phonindex").isFile &&
                File(espeakDir, "phondata").isFile &&
                File(espeakDir, "intonations").isFile
            if (!espeakValid) return false
        }
        val sharedEspeakValid = File(context.filesDir, "models/sherpa/shared_espeak/espeak-ng-data/phondata").isFile
        return when (spec.sherpaFamily) {
            SherpaModelFamily.KOKORO,
            SherpaModelFamily.KITTEN_TTS -> files.any { it.name == "voices.bin" } &&
                files.any { it.name == "tokens.txt" } &&
                (espeakDir != null || sharedEspeakValid)
            SherpaModelFamily.SUPERTONIC_3 -> files.any { it.name == "tts.json" } &&
                files.any { it.name.contains("unicode_indexer") } &&
                files.any { it.name.contains("voice") && it.extension.equals("bin", true) }
            // ✨ ZONOS (v2.7.0): native ggml bundles never use the sherpa extract path —
            // branches kept for exhaustiveness; never reached in practice.
            SherpaModelFamily.ZONOS3,
            SherpaModelFamily.ZONOS1 -> false
            SherpaModelFamily.POCKET_TTS -> files.any { it.name.contains("lm_flow") } &&
                files.any { it.name.contains("lm_main") } &&
                files.any { it.name.contains("encoder") } &&
                files.any { it.name.contains("decoder") } &&
                files.any { it.name.contains("vocab") && it.extension.equals("json", true) }
            SherpaModelFamily.ZIPVOICE -> files.any { it.name == "tokens.txt" } &&
                files.any { it.name.contains("encoder") && it.extension.equals("onnx", true) } &&
                files.any { it.name.contains("decoder") && it.extension.equals("onnx", true) } &&
                files.any { it.name.contains("vocoder") && it.extension.equals("onnx", true) }
            SherpaModelFamily.MATCHA -> files.any { it.name == "tokens.txt" } &&
                files.any {
                    it.extension.equals("onnx", true) &&
                        !it.name.contains("vocos", true) &&
                        !it.name.contains("hifigan", true) &&
                        !it.name.contains("vocoder", true)
                } &&
                (spec.companionFileName.isNullOrBlank() || files.any { it.name == spec.companionFileName })
            SherpaModelFamily.PIPER -> files.any { it.name == "tokens.txt" } &&
                (files.any { it.name.startsWith("lexicon") } ||
                    espeakDir != null ||
                    sharedEspeakValid ||
                    spec.id == "rom_meta_mms" ||
                    spec.id.startsWith("sub_mms_"))
            SherpaModelFamily.MELO_TTS -> files.any { it.name == "tokens.txt" }
            null -> true
        }
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val downloadsTempDir: File by lazy {
        File(context.filesDir, "models/downloads").apply { mkdirs() }
    }

    fun startOrResumeDownload(spec: ModelBundleSpec) {
        if (spec.isBuiltInOsCartridge) {
            repository.updateModelState(
                spec.id,
                ModelBundleState(
                    specId = spec.id,
                    state = DownloadState.INSTALLED,
                    sha256Verified = true,
                    localPath = spec.downloadUrl
                )
            )
            return
        }
        if (activeJobs[spec.id]?.isActive == true) return
        val job = scope.launch {
            downloadAndInstall(spec)
        }
        activeJobs[spec.id] = job
    }

    fun pauseDownload(specId: String) {
        activeJobs.remove(specId)?.cancel()
        val existing = repository.state.value.modelStates[specId] ?: ModelBundleState(specId)
        repository.updateModelState(
            specId,
            existing.copy(state = DownloadState.NOT_INSTALLED, errorMessage = "Paused — resume anytime")
        )
    }

    suspend fun verifyInstalledSha256(spec: ModelBundleSpec): Boolean = withContext(Dispatchers.IO) {
        val target = resolveDestinationFile(spec)
        if (!target.exists()) return@withContext false

        // ✨ TRUST RENEWAL (v1.8.1): verify against the live manifest digest when available —
        // an upstream re-publish must not brand a previously-valid install as corrupt.
        refreshManifest(force = false)
        val expectedSha = expectedSha256For(spec.downloadUrl, spec.expectedSha256)

        repository.updateModelState(
            spec.id,
            (repository.state.value.modelStates[spec.id] ?: ModelBundleState(spec.id))
                .copy(state = DownloadState.VERIFYING, errorMessage = null)
        )

        val digest = computeSha256(target)
        val matched = digest.equals(expectedSha, ignoreCase = true)
        repository.updateModelState(
            spec.id,
            ModelBundleState(
                specId = spec.id,
                state = if (matched) DownloadState.INSTALLED else DownloadState.FAILED,
                downloadedBytes = target.length(),
                totalBytes = target.length(),
                sha256Verified = matched,
                localPath = target.absolutePath,
                errorMessage = if (matched) null else "SHA-256 mismatch ($digest vs expected ${expectedSha.take(12)}...)"
            )
        )
        matched
    }

    fun deleteModelBundle(spec: ModelBundleSpec) {
        if (spec.isBuiltInOsCartridge) {
            repository.updateModelState(
                spec.id,
                ModelBundleState(
                    specId = spec.id,
                    state = DownloadState.INSTALLED,
                    sha256Verified = true,
                    localPath = spec.downloadUrl
                )
            )
            repository.setCartridgeSlotted(spec.id, false)
            return
        }
        activeJobs.remove(spec.id)?.cancel()
        runCatching { File(downloadsTempDir, "${spec.fileName}.part").delete() }
        runCatching { resolveDestinationFile(spec).delete() }
        if (spec.companionFileName.isNotBlank()) {
            runCatching { File(downloadsTempDir, "${spec.companionFileName}.part").delete() }
        }
        if (spec.isArchiveTarBz2 && spec.sherpaFamily != null) {
            runCatching { resolveSherpaExtractDir(spec).deleteRecursively() }
        }
        if (spec.backend == BackendType.ONNX_NATIVE || spec.backend == BackendType.QWEN3_NATIVE ||
            spec.backend == BackendType.ZONOS3_NATIVE || spec.backend == BackendType.ZONOS1_NATIVE) {
            runCatching { resolveNativeModelDir(spec.id).deleteRecursively() }
        }
        repository.updateModelState(spec.id, ModelBundleState(specId = spec.id, state = DownloadState.NOT_INSTALLED))
    }

    private suspend fun downloadAndInstall(spec: ModelBundleSpec) = withContext(Dispatchers.IO) {
        if (spec.extraAssets.isNotEmpty() || spec.backend == BackendType.ONNX_NATIVE ||
            spec.backend == BackendType.QWEN3_NATIVE || spec.backend == BackendType.ZONOS3_NATIVE ||
            spec.backend == BackendType.ZONOS1_NATIVE) {
            downloadMultiFileNativeBundle(spec)
            return@withContext
        }
        val partFile = File(downloadsTempDir, "${spec.fileName}.part")
        val finalFile = resolveDestinationFile(spec)

        try {
            // ✨ TRUST RENEWAL (v1.8.1): resolve the expected hash from the live manifest first
            // (baked pin = offline floor), then stream with Range resume + the 416 self-heal.
            refreshManifest(force = false)
            val bakedSha = spec.expectedSha256
            val expectedSha = expectedSha256For(spec.downloadUrl, bakedSha)
            val streamed = streamAssetToPart(
                assetUrl = spec.downloadUrl,
                partFile = partFile,
                expectedSha = expectedSha
            ) { downloaded, total ->
                repository.updateModelState(
                    spec.id,
                    ModelBundleState(
                        specId = spec.id,
                        state = DownloadState.DOWNLOADING,
                        downloadedBytes = downloaded,
                        totalBytes = total
                    )
                )
            }
            if (!streamed) throw IllegalStateException("Empty HTTP body")

            // SHA-256 Verification before unpacking / committing
            repository.updateModelState(
                spec.id,
                ModelBundleState(
                    specId = spec.id,
                    state = DownloadState.VERIFYING,
                    downloadedBytes = partFile.length(),
                    totalBytes = partFile.length()
                )
            )
            verifyAgainstExpectedSha(spec, partFile, bakedSha, spec.downloadUrl, spec.fileName)

            finalFile.parentFile?.mkdirs()
            if (finalFile.exists()) finalFile.delete()
            if (!partFile.renameTo(finalFile)) {
                partFile.copyTo(finalFile, overwrite = true)
                partFile.delete()
            }

            if (spec.isArchiveTarBz2 && spec.sherpaFamily != null) {
                repository.updateModelState(
                    spec.id,
                    ModelBundleState(
                        specId = spec.id,
                        state = DownloadState.EXTRACTING,
                        downloadedBytes = finalFile.length(),
                        totalBytes = finalFile.length(),
                        sha256Verified = true
                    )
                )
                val extractDir = resolveSherpaExtractDir(spec)
                runCatching { extractDir.deleteRecursively() }
                extractTarBz2(finalFile, extractDir)
            }

            // ✨ COMPANION ASSET (v1.6.0): Matcha's Vocos vocoder — download, verify, place.
            if (spec.companionUrl.isNotBlank()) {
                repository.updateModelState(
                    spec.id,
                    (repository.state.value.modelStates[spec.id] ?: ModelBundleState(specId = spec.id)).copy(
                        state = DownloadState.EXTRACTING,
                        sha256Verified = true,
                        errorMessage = "Fetching companion: ${spec.companionFileName}"
                    )
                )
                downloadCompanionAsset(spec, resolveSherpaExtractDir(spec))
            }

            repository.updateModelState(
                spec.id,
                ModelBundleState(
                    specId = spec.id,
                    state = DownloadState.INSTALLED,
                    downloadedBytes = finalFile.length(),
                    totalBytes = finalFile.length(),
                    sha256Verified = true,
                    localPath = finalFile.absolutePath
                )
            )
            Log.i(TAG, "Successfully installed ${spec.name} (${finalFile.length()} bytes, SHA-256 verified)")
        } catch (err: Exception) {
            Log.w(TAG, "Download/install failed for ${spec.id}: ${err.message}")
            repository.updateModelState(
                spec.id,
                ModelBundleState(
                    specId = spec.id,
                    state = DownloadState.FAILED,
                    downloadedBytes = if (partFile.exists()) partFile.length() else 0L,
                    totalBytes = spec.approximateSizeMb * 1024L * 1024L,
                    errorMessage = err.message ?: "Download failed"
                )
            )
        } finally {
            activeJobs.remove(spec.id)
        }
    }

    /**
     * ✨ TRUST RENEWAL (v1.8.1): resolves the expected SHA-256 for an asset. The live manifest
     * wins when it carries a well-formed digest for the URL; the baked catalog pin is the
     * offline floor only. Fail-closed law unchanged: bytes are only ever committed after
     * matching a trusted expected hash.
     */
    fun expectedSha256For(assetUrl: String, bakedPin: String): String {
        val fromManifest = manifestByAssetUrl[assetUrl]
        return if (fromManifest != null && PurrHashManifest.isWellFormedSha256(fromManifest)) fromManifest else bakedPin
    }

    /**
     * ✨ TRUST RENEWAL (v1.8.1): fetches [MANIFEST_URL] and updates the in-memory registry +
     * last-good disk cache. `force = false` no-ops when a manifest is already loaded (download
     * start); `force = true` runs on any SHA mismatch before failing closed. Network failure
     * degrades gracefully: last-good cache → baked catalog pins.
     */
    suspend fun refreshManifest(force: Boolean): Boolean = withContext(Dispatchers.IO) {
        if (!force && manifestByAssetUrl.isNotEmpty()) return@withContext true
        try {
            val body = manifestClient.newCall(Request.Builder().url(MANIFEST_URL).build()).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                resp.body?.string() ?: throw IllegalStateException("Empty manifest body")
            }
            val parsed = PurrHashManifest.parse(body)
            if (parsed.isEmpty()) throw IllegalStateException("Manifest parsed empty")
            manifestByAssetUrl = parsed
            runCatching {
                manifestCacheFile.parentFile?.mkdirs()
                manifestCacheFile.writeText(body)
            }
            Log.i(TAG, "[TRUST_RENEWAL] Live manifest refreshed: ${parsed.size} assets")
            true
        } catch (err: Exception) {
            val cached = runCatching { PurrHashManifest.parse(manifestCacheFile.readText()) }.getOrDefault(emptyMap())
            if (cached.isNotEmpty() && manifestByAssetUrl.isEmpty()) {
                manifestByAssetUrl = cached
                Log.w(TAG, "[TRUST_RENEWAL] Manifest fetch failed (${err.message}); using last-good cached manifest")
            } else {
                Log.w(TAG, "[TRUST_RENEWAL] Manifest unavailable (${err.message}); using baked catalog pins")
            }
            false
        }
    }

    /**
     * ✨ TRUST RENEWAL (v1.8.1) STREAM HELPER ✨
     * Streams a remote asset into [partFile] with HTTP-Range resume and the 416 self-heal:
     * a leftover COMPLETE `.part` file (typical after a stale-pin rejection, or when an
     * upstream re-upload changed the asset size) previously dead-ended every retry with
     * "HTTP 416" — now it is hash-verified and promoted, or cleaned and restarted from byte 0.
     * Returns true when [partFile] holds bytes worth hash-verifying.
     */
    private fun streamAssetToPart(
        assetUrl: String,
        partFile: File,
        expectedSha: String,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): Boolean {
        var cleanAttemptDone = false
        while (true) {
            val existingBytes = if (cleanAttemptDone) 0L else if (partFile.exists()) partFile.length() else 0L
            val reqBuilder = Request.Builder().url(assetUrl)
            if (existingBytes > 0L) {
                reqBuilder.addHeader("Range", "bytes=$existingBytes-")
            }

            var rangeUnsatisfiable = false
            var streamed = false
            httpClient.newCall(reqBuilder.build()).execute().use { response ->
                when {
                    response.code == 416 && existingBytes > 0L -> {
                        rangeUnsatisfiable = true
                        Log.i(TAG, "Range unsatisfiable — .part file (${partFile.length()} bytes) already spans the remote asset; verifying instead of dead-ending")
                    }
                    !response.isSuccessful && response.code != 206 -> {
                        throw IllegalStateException("HTTP ${response.code}: ${response.message}")
                    }
                    else -> {
                        streamed = true
                        val isResumed = response.code == 206
                        val base = if (isResumed) existingBytes else 0L
                        val body = response.body ?: throw IllegalStateException("Empty HTTP body")
                        val contentLength = body.contentLength()
                        val totalBytes = if (contentLength > 0L) base + contentLength else base

                        onProgress(base, totalBytes)

                        body.byteStream().use { input ->
                            FileOutputStream(partFile, isResumed).use { output ->
                                val buffer = ByteArray(64 * 1024)
                                var current = base
                                var lastUiEmit = 0L
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read == -1) break
                                    output.write(buffer, 0, read)
                                    current += read
                                    val now = System.currentTimeMillis()
                                    if (now - lastUiEmit >= 200L) {
                                        lastUiEmit = now
                                        onProgress(current, totalBytes)
                                    }
                                }
                                output.flush()
                            }
                        }
                    }
                }
            }

            if (streamed) return true
            if (rangeUnsatisfiable) {
                if (computeSha256(partFile).equals(expectedSha, ignoreCase = true)) return true
                if (cleanAttemptDone) return false
                Log.w(TAG, "Leftover .part failed SHA pre-check (${partFile.length()} bytes) — restarting clean")
                runCatching { partFile.delete() }
                cleanAttemptDone = true
                continue
            }
            return false
        }
    }

    /**
     * ✨ TRUST RENEWAL (v1.8.1) VERIFY ✨ — hashes [file] against the manifest-resolved expected
     * digest; on mismatch, force-refreshes the manifest ONCE and re-verifies (an upstream
     * re-publish between catalog releases rescues itself). Throws an actionable error when the
     * fail-closed law still rejects the bytes.
     */
    private suspend fun verifyAgainstExpectedSha(
        spec: ModelBundleSpec,
        file: File,
        bakedPin: String,
        assetUrl: String,
        label: String
    ) {
        val firstSha = expectedSha256For(assetUrl, bakedPin)
        // Legacy semantics preserved: assets with no trusted digest configured are not verified.
        if (!PurrHashManifest.isWellFormedSha256(firstSha)) return

        val actual = computeSha256(file)
        if (actual.equals(firstSha, ignoreCase = true)) return

        val rescuedSha = runCatching {
            refreshManifest(force = true)
            val refreshed = expectedSha256For(assetUrl, bakedPin)
            if (!refreshed.equals(firstSha, ignoreCase = true) &&
                computeSha256(file).equals(refreshed, ignoreCase = true)
            ) {
                refreshed
            } else {
                null
            }
        }.getOrNull()

        if (rescuedSha != null) {
            Log.i(TAG, "[TRUST_RENEWAL] Manifest refresh rescued $label for ${spec.id} — new expected hash accepted")
            return
        }

        throw IllegalStateException(
            "Upstream asset changed — SHA-256 mismatch on $label (got ${actual.take(12)}...). " +
                "Bytes rejected by the fail-closed trust law; update model_manifest.json / catalog pins."
        )
    }

    /**
     * ✨ MULTI-FILE NATIVE BUNDLE INSTALLER (v1.7.0) ✨
     * Downloads primary GGUF/ONNX file + all `extraAssets` (preserving relative subdirs such as
     * `embeddings/`) into `filesDir/models/native/<specId>/` with HTTP Range resume & SHA-256 checks.
     */
    private suspend fun downloadMultiFileNativeBundle(spec: ModelBundleSpec) = withContext(Dispatchers.IO) {
        val targetDir = resolveNativeModelDir(spec.id).apply { mkdirs() }
        val extraApproxSum = spec.extraAssets.sumOf { it.approxSizeBytes.coerceAtLeast(1024L) }
        val totalBytesTarget = spec.approximateSizeMb.toLong() * 1024L * 1024L
        val primaryApproxBytes = (totalBytesTarget - extraApproxSum).coerceAtLeast(1024L * 1024L)
        val allAssets = listOf(
            BundleAssetFile(
                relativePath = spec.fileName,
                url = spec.downloadUrl,
                expectedSha256 = spec.expectedSha256,
                approxSizeBytes = primaryApproxBytes
            )
        ) + spec.extraAssets
        val totalApproxBytes = allAssets.sumOf { it.approxSizeBytes.coerceAtLeast(1024L) }
            .coerceAtLeast(totalBytesTarget)
        var completedBytes = 0L

        try {
            allAssets.forEachIndexed { idx, asset ->
                val destFile = File(targetDir, asset.relativePath)
                destFile.parentFile?.mkdirs()
                // ✨ TRUST RENEWAL (v1.8.1): manifest-resolved expected hash (baked pin = floor).
                val expectedSha = expectedSha256For(asset.url, asset.expectedSha256)
                val alreadyValid = destFile.exists() && destFile.length() > 0L && (
                    expectedSha.length != 64 ||
                        computeSha256(destFile).equals(expectedSha, ignoreCase = true)
                    )
                if (alreadyValid) {
                    completedBytes += destFile.length()
                    return@forEachIndexed
                }

                val partFile = File(downloadsTempDir, "${spec.id}_${asset.relativePath.replace('/', '_')}.part")
                val streamed = streamAssetToPart(
                    assetUrl = asset.url,
                    partFile = partFile,
                    expectedSha = expectedSha
                ) { downloaded, _ ->
                    repository.updateModelState(
                        spec.id,
                        ModelBundleState(
                            specId = spec.id,
                            state = DownloadState.DOWNLOADING,
                            downloadedBytes = (completedBytes + downloaded).coerceAtMost(totalApproxBytes),
                            totalBytes = totalApproxBytes,
                            errorMessage = "[${idx + 1}/${allAssets.size}] ${asset.relativePath.substringAfterLast('/')}"
                        )
                    )
                }
                if (!streamed) throw IllegalStateException("Empty body on ${asset.relativePath}")

                if (expectedSha.length == 64) {
                    repository.updateModelState(
                        spec.id,
                        ModelBundleState(
                            specId = spec.id,
                            state = DownloadState.VERIFYING,
                            downloadedBytes = (completedBytes + partFile.length()).coerceAtMost(totalApproxBytes),
                            totalBytes = totalApproxBytes,
                            errorMessage = "SHA-256 [${idx + 1}/${allAssets.size}] ${asset.relativePath.substringAfterLast('/')}"
                        )
                    )
                    // ✨ TRUST RENEWAL (v1.8.1): manifest-rescue + actionable mismatch errors.
                    verifyAgainstExpectedSha(spec, partFile, asset.expectedSha256, asset.url, asset.relativePath)
                }

                if (destFile.exists()) destFile.delete()
                if (!partFile.renameTo(destFile)) {
                    partFile.copyTo(destFile, overwrite = true)
                    partFile.delete()
                }
                completedBytes += destFile.length()
            }

            repository.updateModelState(
                spec.id,
                ModelBundleState(
                    specId = spec.id,
                    state = DownloadState.INSTALLED,
                    downloadedBytes = completedBytes,
                    totalBytes = completedBytes,
                    sha256Verified = true,
                    localPath = targetDir.absolutePath
                )
            )
            Log.i(TAG, "Successfully installed native bundle ${spec.name} (${allAssets.size} files)")
        } catch (err: Exception) {
            Log.w(TAG, "Native bundle install failed for ${spec.id}: ${err.message}")
            repository.updateModelState(
                spec.id,
                ModelBundleState(
                    specId = spec.id,
                    state = DownloadState.FAILED,
                    downloadedBytes = completedBytes,
                    totalBytes = totalApproxBytes,
                    errorMessage = err.message ?: "Download failed"
                )
            )
        } finally {
            activeJobs.remove(spec.id)
        }
    }

    private fun mirrorSharedEspeakDataIfValid(extractDir: File) {
        val espeakDir = extractDir.walkTopDown().firstOrNull { it.isDirectory && it.name == "espeak-ng-data" } ?: return
        val isComplete = File(espeakDir, "phontab").isFile &&
            File(espeakDir, "phonindex").isFile &&
            File(espeakDir, "phondata").isFile &&
            File(espeakDir, "intonations").isFile
        if (!isComplete) return
        val sharedDir = File(context.filesDir, "models/sherpa/shared_espeak/espeak-ng-data")
        val sharedReady = File(sharedDir, "phontab").isFile &&
            File(sharedDir, "phonindex").isFile &&
            File(sharedDir, "phondata").isFile &&
            File(sharedDir, "intonations").isFile
        if (!sharedReady) {
            sharedDir.parentFile?.mkdirs()
            runCatching { espeakDir.copyRecursively(sharedDir, overwrite = true) }
        }
    }

    private fun extractTarBz2(archiveFile: File, targetDir: File) {
        targetDir.mkdirs()
        val stagingDir = File(targetDir.parentFile ?: targetDir, "${targetDir.name}.extract_tmp")
        stagingDir.deleteRecursively()
        stagingDir.mkdirs()
        try {
            val canonicalRoot = stagingDir.canonicalPath
            BufferedInputStream(FileInputStream(archiveFile)).use { bis ->
                BZip2CompressorInputStream(bis).use { bzIn ->
                    TarArchiveInputStream(bzIn).use { tarIn ->
                        while (true) {
                            val entry = tarIn.nextEntry ?: break
                            val outFile = File(stagingDir, entry.name)
                            // ZipSlip / TarSlip path traversal guard
                            if (!outFile.canonicalPath.startsWith(canonicalRoot)) {
                                throw SecurityException("Blocked unsafe tar entry path: ${entry.name}")
                            }
                            if (entry.isDirectory) {
                                outFile.mkdirs()
                            } else {
                                outFile.parentFile?.mkdirs()
                                FileOutputStream(outFile).use { out ->
                                    tarIn.copyTo(out)
                                }
                            }
                        }
                    }
                }
            }
            stagingDir.copyRecursively(targetDir, overwrite = true)
            mirrorSharedEspeakDataIfValid(targetDir)
        } finally {
            stagingDir.deleteRecursively()
        }
    }

    private fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        FileInputStream(file).use { fis ->
            while (true) {
                val read = fis.read(buf)
                if (read == -1) break
                digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun resolveDestinationFile(spec: ModelBundleSpec): File = when (spec.backend) {
        BackendType.LITERT_NPU_GPU -> File(context.filesDir, "models/litert/${spec.fileName}")
        BackendType.SHERPA_ONNX -> File(context.filesDir, "models/downloads/${spec.fileName}")
        BackendType.ONNX_NATIVE,
        BackendType.QWEN3_NATIVE,
        BackendType.ZONOS3_NATIVE,
        BackendType.ZONOS1_NATIVE -> File(resolveNativeModelDir(spec.id), spec.fileName)
        BackendType.CORE_OS,
        BackendType.EXTERNAL_PROXY -> File(context.filesDir, "models/proxy/${spec.fileName}")
    }

    fun resolveNativeModelDir(specId: String): File =
        File(context.filesDir, "models/native/$specId")

    private fun resolveSherpaExtractDir(spec: ModelBundleSpec): File = when (spec.sherpaFamily) {
        // ✨ HUB TIER (v1.7.0): each variant extracts into an isolated dir so weights never collide
        SherpaModelFamily.KOKORO -> if (spec.id != "rom_kokoro_sherpa") {
            File(context.filesDir, "models/sherpa/kokoro_${spec.id}")
        } else {
            File(context.filesDir, "models/sherpa/kokoro")
        }
        SherpaModelFamily.KITTEN_TTS -> if (spec.id != "rom_kitten_tts") {
            File(context.filesDir, "models/sherpa/kitten_${spec.id}")
        } else {
            File(context.filesDir, "models/sherpa/kitten")
        }
        // ✨ ZONOS (v2.7.0): isolated dirs for type safety — Zonos specs never carry a
        // sherpa family, so this branch is unreachable in practice.
        SherpaModelFamily.ZONOS3,
        SherpaModelFamily.ZONOS1 -> File(context.filesDir, "models/sherpa/zonos_${spec.id}")
        SherpaModelFamily.SUPERTONIC_3 -> File(context.filesDir, "models/sherpa/supertonic3")
        SherpaModelFamily.POCKET_TTS -> if (spec.id != "rom_pocket_tts") {
            File(context.filesDir, "models/sherpa/pocket_${spec.id}")
        } else {
            File(context.filesDir, "models/sherpa/pocket_tts")
        }
        SherpaModelFamily.ZIPVOICE -> if (spec.id != "rom_zipvoice") {
            File(context.filesDir, "models/sherpa/zipvoice_${spec.id}")
        } else {
            File(context.filesDir, "models/sherpa/zipvoice")
        }
        SherpaModelFamily.MATCHA -> if (spec.id != "rom_matcha_zh_en") {
            File(context.filesDir, "models/sherpa/matcha_${spec.id}")
        } else {
            File(context.filesDir, "models/sherpa/matcha")
        }
        SherpaModelFamily.MELO_TTS -> File(context.filesDir, "models/sherpa/melo_${spec.id}")
        SherpaModelFamily.PIPER -> if (spec.id != "rom_piper") {
            File(context.filesDir, "models/sherpa/piper_${spec.id}")
        } else {
            File(context.filesDir, "models/sherpa/piper")
        }
        null -> File(context.filesDir, "models/sherpa/other")
    }

    /**
     * ✨ COMPANION ASSET PIPELINE (v1.6.0): downloads + SHA-256-verifies an optional companion
     * file (Matcha's Vocos vocoder) and places it into the cartridge's extract dir. Resume-safe.
     */
    private suspend fun downloadCompanionAsset(spec: ModelBundleSpec, extractDir: File) = withContext(Dispatchers.IO) {
        val partFile = File(downloadsTempDir, "${spec.companionFileName}.part")
        val finalFile = File(extractDir, spec.companionFileName)
        if (finalFile.exists() && finalFile.length() > 0L) return@withContext

        try {
            // ✨ TRUST RENEWAL (v1.8.1): manifest-resolved companion hash + 416 self-heal streaming.
            val expectedSha = expectedSha256For(spec.companionUrl, spec.companionExpectedSha256)
            val streamed = streamAssetToPart(
                assetUrl = spec.companionUrl,
                partFile = partFile,
                expectedSha = expectedSha
            ) { downloaded, total ->
                repository.updateModelState(
                    spec.id,
                    (repository.state.value.modelStates[spec.id] ?: ModelBundleState(specId = spec.id)).copy(
                        state = DownloadState.EXTRACTING,
                        downloadedBytes = downloaded,
                        totalBytes = total,
                        errorMessage = "Fetching companion: ${spec.companionFileName}"
                    )
                )
            }
            if (!streamed) throw IllegalStateException("Empty companion body")

            verifyAgainstExpectedSha(spec, partFile, spec.companionExpectedSha256, spec.companionUrl, spec.companionFileName)
            extractDir.mkdirs()
            // ✨ VOCODER HYGIENE (v1.6.1 scar): remove stale vocoder variants (e.g. the wrong-rate
            // vocos-22khz from v1.6.0) so the engine's first-match vocoder pick can never grab a
            // mismatched sample rate. The companion IS the authoritative vocoder for this spec.
            extractDir.listFiles()
                ?.filter {
                    it.extension.equals("onnx", ignoreCase = true) &&
                        it.name != spec.companionFileName &&
                        (it.name.contains("vocos", ignoreCase = true) ||
                            it.name.contains("hifigan", ignoreCase = true) ||
                            it.name.contains("vocoder", ignoreCase = true))
                }
                ?.forEach { stale ->
                    runCatching { stale.delete() }
                    Log.i(TAG, "Removed stale vocoder variant: ${stale.name}")
                }
            if (finalFile.exists()) finalFile.delete()
            if (!partFile.renameTo(finalFile)) {
                partFile.copyTo(finalFile, overwrite = true)
                partFile.delete()
            }
            Log.i(TAG, "Companion asset installed: ${spec.companionFileName} (SHA-256 verified)")
        } catch (err: Exception) {
            runCatching { partFile.delete() }
            throw IllegalStateException("Companion download failed: ${err.message}", err)
        }
    }
}
