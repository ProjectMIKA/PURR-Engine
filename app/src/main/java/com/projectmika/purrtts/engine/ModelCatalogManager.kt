package com.projectmika.purrtts.engine

import android.content.Context
import android.util.Log
import com.projectmika.purrtts.core.purr.BackendType
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
        if (spec.isArchiveTarBz2 && spec.sherpaFamily != null) {
            runCatching { resolveSherpaExtractDir(spec).deleteRecursively() }
        }
        repository.updateModelState(spec.id, ModelBundleState(specId = spec.id, state = DownloadState.NOT_INSTALLED))
    }

    private suspend fun downloadAndInstall(spec: ModelBundleSpec) = withContext(Dispatchers.IO) {
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
        BackendType.CORE_OS,
        BackendType.EXTERNAL_PROXY -> File(context.filesDir, "models/proxy/${spec.fileName}")
    }

    private fun resolveSherpaExtractDir(spec: ModelBundleSpec): File = when (spec.sherpaFamily) {
        SherpaModelFamily.KOKORO -> File(context.filesDir, "models/sherpa/kokoro")
        SherpaModelFamily.KITTEN_TTS -> File(context.filesDir, "models/sherpa/kitten")
        SherpaModelFamily.SUPERTONIC_3 -> File(context.filesDir, "models/sherpa/supertonic3")
        SherpaModelFamily.POCKET_TTS -> File(context.filesDir, "models/sherpa/pocket_tts")
        SherpaModelFamily.ZIPVOICE -> File(context.filesDir, "models/sherpa/zipvoice")
        SherpaModelFamily.PIPER -> if (spec.isSubCartridge) {
            File(context.filesDir, "models/sherpa/piper_${spec.id}")
        } else {
            File(context.filesDir, "models/sherpa/piper")
        }
        null -> File(context.filesDir, "models/sherpa/other")
    }
}
