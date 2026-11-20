package com.projectmika.purrtts.engine

import android.content.Context
import android.util.Log
import com.projectmika.purrtts.core.purr.BackendType
import com.projectmika.purrtts.core.purr.BundleAssetFile
import com.projectmika.purrtts.core.purr.DownloadState
import com.projectmika.purrtts.core.purr.ModelBundleSpec
import com.projectmika.purrtts.core.purr.ModelBundleState
import com.projectmika.purrtts.core.purr.PurrDefaultPresets
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
    }

    val catalog: List<ModelBundleSpec> = PurrDefaultPresets.modelCatalog()

    private val activeJobs = ConcurrentHashMap<String, Job>()

    init {
        // ✨ SELF-HEALING COMPANION MIGRATION (v1.6.1): if a companion spec changed (e.g. the
        // Matcha vocoder switched 22kHz -> 16kHz), existing INSTALLED cartridges are missing the
        // new companion file — re-fetch it silently in the background so the engine never mounts
        // a stale/mismatched asset.
        scope.launch {
            catalog.filter { it.companionUrl.isNotBlank() }.forEach { spec ->
                val state = repository.state.value.modelStates[spec.id] ?: return@forEach
                if (state.state != DownloadState.INSTALLED) return@forEach
                val extractDir = resolveSherpaExtractDir(spec)
                val companion = File(extractDir, spec.companionFileName)
                if (!companion.exists() || companion.length() == 0L) {
                    Log.i(TAG, "Companion migration: fetching ${spec.companionFileName} for ${spec.id}")
                    runCatching { downloadCompanionAsset(spec, extractDir) }
                        .onFailure { Log.w(TAG, "Companion migration failed for ${spec.id}: ${it.message}") }
                }
            }
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

        repository.updateModelState(
            spec.id,
            (repository.state.value.modelStates[spec.id] ?: ModelBundleState(spec.id))
                .copy(state = DownloadState.VERIFYING, errorMessage = null)
        )

        val digest = computeSha256(target)
        val matched = digest.equals(spec.expectedSha256, ignoreCase = true)
        repository.updateModelState(
            spec.id,
            ModelBundleState(
                specId = spec.id,
                state = if (matched) DownloadState.INSTALLED else DownloadState.FAILED,
                downloadedBytes = target.length(),
                totalBytes = target.length(),
                sha256Verified = matched,
                localPath = target.absolutePath,
                errorMessage = if (matched) null else "SHA-256 mismatch ($digest)"
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
        if (spec.backend == BackendType.ONNX_NATIVE || spec.backend == BackendType.QWEN3_NATIVE) {
            runCatching { resolveNativeModelDir(spec.id).deleteRecursively() }
        }
        repository.updateModelState(spec.id, ModelBundleState(specId = spec.id, state = DownloadState.NOT_INSTALLED))
    }

    private suspend fun downloadAndInstall(spec: ModelBundleSpec) = withContext(Dispatchers.IO) {
        if (spec.extraAssets.isNotEmpty() || spec.backend == BackendType.ONNX_NATIVE || spec.backend == BackendType.QWEN3_NATIVE) {
            downloadMultiFileNativeBundle(spec)
            return@withContext
        }
        val partFile = File(downloadsTempDir, "${spec.fileName}.part")
        val finalFile = resolveDestinationFile(spec)

        try {
            var existingBytes = if (partFile.exists()) partFile.length() else 0L
            val reqBuilder = Request.Builder().url(spec.downloadUrl)
            if (existingBytes > 0L) {
                reqBuilder.addHeader("Range", "bytes=$existingBytes-")
            }

            httpClient.newCall(reqBuilder.build()).execute().use { response ->
                if (!response.isSuccessful && response.code != 206) {
                    throw IllegalStateException("HTTP ${response.code}: ${response.message}")
                }

                val isResumed = response.code == 206
                if (!isResumed) {
                    existingBytes = 0L
                }

                val body = response.body ?: throw IllegalStateException("Empty HTTP body")
                val contentLength = body.contentLength()
                val totalBytes = if (contentLength > 0L) existingBytes + contentLength else existingBytes

                repository.updateModelState(
                    spec.id,
                    ModelBundleState(
                        specId = spec.id,
                        state = DownloadState.DOWNLOADING,
                        downloadedBytes = existingBytes,
                        totalBytes = totalBytes
                    )
                )

                body.byteStream().use { input ->
                    FileOutputStream(partFile, isResumed).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var current = existingBytes
                        var lastUiEmit = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            current += read
                            val now = System.currentTimeMillis()
                            if (now - lastUiEmit >= 200L) {
                                lastUiEmit = now
                                repository.updateModelState(
                                    spec.id,
                                    ModelBundleState(
                                        specId = spec.id,
                                        state = DownloadState.DOWNLOADING,
                                        downloadedBytes = current,
                                        totalBytes = totalBytes
                                    )
                                )
                            }
                        }
                        output.flush()
                    }
                }
            }

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
            val actualSha = computeSha256(partFile)
            if (!actualSha.equals(spec.expectedSha256, ignoreCase = true)) {
                throw IllegalStateException("SHA-256 mismatch! Expected ${spec.expectedSha256.take(12)}..., got ${actualSha.take(12)}...")
            }

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
                extractTarBz2(finalFile, resolveSherpaExtractDir(spec))
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
                val alreadyValid = destFile.exists() && destFile.length() > 0L && (
                    asset.expectedSha256.length != 64 ||
                        computeSha256(destFile).equals(asset.expectedSha256, ignoreCase = true)
                    )
                if (alreadyValid) {
                    completedBytes += destFile.length()
                    return@forEachIndexed
                }

                val partFile = File(downloadsTempDir, "${spec.id}_${asset.relativePath.replace('/', '_')}.part")
                var existingBytes = if (partFile.exists()) partFile.length() else 0L
                val reqBuilder = Request.Builder().url(asset.url)
                if (existingBytes > 0L) {
                    reqBuilder.addHeader("Range", "bytes=$existingBytes-")
                }

                httpClient.newCall(reqBuilder.build()).execute().use { response ->
                    if (!response.isSuccessful && response.code != 206) {
                        throw IllegalStateException("HTTP ${response.code} on ${asset.relativePath}")
                    }
                    val isResumed = response.code == 206
                    if (!isResumed) existingBytes = 0L
                    val body = response.body ?: throw IllegalStateException("Empty body on ${asset.relativePath}")

                    body.byteStream().use { input ->
                        FileOutputStream(partFile, isResumed).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var current = existingBytes
                            var lastUiEmit = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read == -1) break
                                output.write(buffer, 0, read)
                                current += read
                                val now = System.currentTimeMillis()
                                if (now - lastUiEmit >= 200L) {
                                    lastUiEmit = now
                                    repository.updateModelState(
                                        spec.id,
                                        ModelBundleState(
                                            specId = spec.id,
                                            state = DownloadState.DOWNLOADING,
                                            downloadedBytes = (completedBytes + current).coerceAtMost(totalApproxBytes),
                                            totalBytes = totalApproxBytes,
                                            errorMessage = "[${idx + 1}/${allAssets.size}] ${asset.relativePath.substringAfterLast('/')}"
                                        )
                                    )
                                }
                            }
                            output.flush()
                        }
                    }
                }

                if (asset.expectedSha256.length == 64) {
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
                    val actualSha = computeSha256(partFile)
                    if (!actualSha.equals(asset.expectedSha256, ignoreCase = true)) {
                        partFile.delete()
                        throw IllegalStateException("SHA-256 mismatch on ${asset.relativePath}")
                    }
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

    private fun extractTarBz2(archiveFile: File, targetDir: File) {
        targetDir.mkdirs()
        val canonicalRoot = targetDir.canonicalPath
        BufferedInputStream(FileInputStream(archiveFile)).use { bis ->
            BZip2CompressorInputStream(bis).use { bzIn ->
                TarArchiveInputStream(bzIn).use { tarIn ->
                    while (true) {
                        val entry = tarIn.nextEntry ?: break
                        val outFile = File(targetDir, entry.name)
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
        BackendType.QWEN3_NATIVE -> File(resolveNativeModelDir(spec.id), spec.fileName)
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
        SherpaModelFamily.MATCHA -> File(context.filesDir, "models/sherpa/matcha")
        SherpaModelFamily.MELO_TTS -> File(context.filesDir, "models/sherpa/melo_${spec.id}")
        SherpaModelFamily.PIPER -> if (spec.isSubCartridge) {
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
            var existingBytes = if (partFile.exists()) partFile.length() else 0L
            val reqBuilder = Request.Builder().url(spec.companionUrl)
            if (existingBytes > 0L) {
                reqBuilder.addHeader("Range", "bytes=$existingBytes-")
            }

            httpClient.newCall(reqBuilder.build()).execute().use { response ->
                if (!response.isSuccessful && response.code != 206) {
                    throw IllegalStateException("Companion HTTP ${response.code}: ${response.message}")
                }
                val isResumed = response.code == 206
                if (!isResumed) existingBytes = 0L
                val body = response.body ?: throw IllegalStateException("Empty companion body")
                val contentLength = body.contentLength()
                val totalBytes = if (contentLength > 0L) existingBytes + contentLength else existingBytes

                body.byteStream().use { input ->
                    FileOutputStream(partFile, isResumed).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var current = existingBytes
                        var lastUiEmit = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            current += read
                            val now = System.currentTimeMillis()
                            if (now - lastUiEmit >= 200L) {
                                lastUiEmit = now
                                repository.updateModelState(
                                    spec.id,
                                    (repository.state.value.modelStates[spec.id] ?: ModelBundleState(specId = spec.id)).copy(
                                        state = DownloadState.EXTRACTING,
                                        downloadedBytes = current,
                                        totalBytes = totalBytes,
                                        errorMessage = "Fetching companion: ${spec.companionFileName}"
                                    )
                                )
                            }
                        }
                        output.flush()
                    }
                }
            }

            val actualSha = computeSha256(partFile)
            if (!actualSha.equals(spec.companionExpectedSha256, ignoreCase = true)) {
                throw IllegalStateException(
                    "Companion SHA-256 mismatch! Expected ${spec.companionExpectedSha256.take(12)}..., got ${actualSha.take(12)}..."
                )
            }
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
