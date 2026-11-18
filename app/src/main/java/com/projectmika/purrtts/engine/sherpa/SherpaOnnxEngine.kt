package com.projectmika.purrtts.engine.sherpa

import android.content.Context
import android.util.Log
import com.projectmika.purrtts.core.purr.BackendType
import com.projectmika.purrtts.core.purr.ClonedVoiceProfile
import com.projectmika.purrtts.core.purr.HardwareStatusBadge
import com.projectmika.purrtts.core.purr.PurrDspProcessor
import com.projectmika.purrtts.core.purr.PurrProfile
import com.projectmika.purrtts.core.purr.SherpaModelFamily
import com.projectmika.purrtts.engine.PurrSynthesizer
import com.projectmika.purrtts.engine.SynthesisOutcome
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * ✨ MIKA'S SHERPA-ONNX MULTI-ENGINE & ZERO-SHOT VOICE CLONING ADAPTER ✨
 *
 * Adapted from the open-source `k2-fsa/sherpa-onnx` JNI offline runtime (HayaiTTS pattern)
 * to support:
 * - **Supertonic 3** (fast NEON neural synthesis)
 * - **Pocket TTS** (5-second zero-shot voice cloning via `synthesizeCloned`)
 * - **ZipVoice** (flow-matching zero-shot voice cloning)
 * - **Piper VITS** (classic `.onnx` + `espeak-ng-data`)
 *
 * Uses safe reflection bindings to `com.k2fsa.sherpa.onnx.OfflineTts` when `libsherpa-onnx-jni.so`
 * is present, and seamlessly resamples 16kHz / 22.05kHz / 44.1kHz outputs to 24kHz mono float32.
 */
class SherpaOnnxEngine(
    private val context: Context,
    private val clonedVoicesProvider: () -> List<ClonedVoiceProfile>,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : PurrSynthesizer {

    companion object {
        private const val TAG = "PurrSherpaONNX"
        private const val SAMPLE_RATE_HZ = 24000
        private const val IDLE_EVICTION_MS = 5 * 60 * 1000L
    }

    override val backendType: BackendType = BackendType.SHERPA_ONNX

    private val _statusFlow = MutableStateFlow(HardwareStatusBadge.IDLE)
    override val statusFlow: StateFlow<HardwareStatusBadge> = _statusFlow.asStateFlow()

    private val mutex = Mutex()
    private var idleJob: Job? = null
    private var activeOfflineTts: Any? = null
    private var activeFamily: SherpaModelFamily? = null

    val modelsBaseDir: File by lazy {
        File(context.filesDir, "models/sherpa").apply { mkdirs() }
    }

    override val isModelReady: Boolean
        get() = modelsBaseDir.listFiles()?.any { it.isDirectory && it.listFiles()?.isNotEmpty() == true } == true

    override suspend fun warmup(profile: PurrProfile) {
        mutex.withLock {
            ensureFamilyLoadedLocked(profile.sherpaFamily)
        }
        resetIdleTimer()
    }

    override suspend fun synthesizeChunk(
        text: String,
        voiceId: String,
        speakingRate: Float,
        pitch: Float,
        profile: PurrProfile
    ): SynthesisOutcome = withContext(Dispatchers.IO) {
        resetIdleTimer()

        val clonedVoice = clonedVoicesProvider().firstOrNull { it.id == voiceId }
        if (clonedVoice != null) {
            return@withContext synthesizeCloned(
                text = text,
                clonedVoice = clonedVoice,
                speakingRate = speakingRate,
                pitch = pitch
            )
        }

        val targetFamily = inferFamilyFromVoice(voiceId, profile.sherpaFamily)
        mutex.withLock {
            ensureFamilyLoadedLocked(targetFamily)

            val speakerId = when {
                voiceId.endsWith("aria_warm") -> 1
                else -> 0
            }

            val jniAudio = tryInvokeSherpaGenerate(
                text = text,
                speakerId = speakerId,
                speed = speakingRate,
                referenceSamples = null,
                referenceSampleRate = SAMPLE_RATE_HZ,
                referencePrompt = null
            )

            _statusFlow.value = HardwareStatusBadge.CPU_NEON

            if (jniAudio != null && jniAudio.samples.isNotEmpty()) {
                val resampled = PurrDspProcessor.resampleMono(
                    samples = jniAudio.samples,
                    fromRate = jniAudio.sampleRate,
                    toRate = SAMPLE_RATE_HZ
                )
                return@withContext SynthesisOutcome(
                    pcmFloat24k = resampled,
                    sampleRateHz = SAMPLE_RATE_HZ,
                    hardwareBadge = HardwareStatusBadge.CPU_NEON,
                    engineSummary = "sherpa-onnx • ${targetFamily.label} (${jniAudio.sampleRate}Hz -> 24kHz)"
                )
            }

            val fallback = synthesizeSherpaPreview(text, targetFamily, speakingRate, pitch)
            SynthesisOutcome(
                pcmFloat24k = fallback,
                sampleRateHz = SAMPLE_RATE_HZ,
                hardwareBadge = HardwareStatusBadge.CPU_NEON,
                engineSummary = "sherpa-onnx Preview (${targetFamily.label} — download .tar.bz2 for JNI)",
                isFallbackNeuralPreview = true
            )
        }
    }

    /**
     * Zero-shot 5-second voice cloning pipeline for **Pocket TTS** and **ZipVoice** via
     * `OfflineTts.generateWithCallback` / `generate(text, sid, speed)` with reference WAV conditioning.
     */
    suspend fun synthesizeCloned(
        text: String,
        clonedVoice: ClonedVoiceProfile,
        speakingRate: Float,
        pitch: Float
    ): SynthesisOutcome = withContext(Dispatchers.IO) {
        resetIdleTimer()

        mutex.withLock {
            ensureFamilyLoadedLocked(clonedVoice.family)

            val refSamples = loadWavMonoFloat24k(File(clonedVoice.referenceWavPath))
            val jniCloned = tryInvokeSherpaGenerate(
                text = text,
                speakerId = 0,
                speed = speakingRate,
                referenceSamples = refSamples,
                referenceSampleRate = SAMPLE_RATE_HZ,
                referencePrompt = clonedVoice.referenceTranscript
            )

            _statusFlow.value = HardwareStatusBadge.CPU_NEON
            if (jniCloned != null && jniCloned.samples.isNotEmpty()) {
                val resampled = PurrDspProcessor.resampleMono(
                    samples = jniCloned.samples,
                    fromRate = jniCloned.sampleRate,
                    toRate = SAMPLE_RATE_HZ
                )
                return@withContext SynthesisOutcome(
                    pcmFloat24k = resampled,
                    sampleRateHz = SAMPLE_RATE_HZ,
                    hardwareBadge = HardwareStatusBadge.CPU_NEON,
                    engineSummary = "${clonedVoice.family.label} Cloned Voice • ${clonedVoice.displayName}"
                )
            }

            // Modulate preview timbre from reference WAV RMS/pitch centroid when JNI bundle isn't unpacked yet
            val refEnergy = if (refSamples.isNotEmpty()) {
                refSamples.take(2400).map { kotlin.math.abs(it) }.average().toFloat().coerceIn(0.25f, 1.0f)
            } else {
                0.55f
            }
            val preview = synthesizeSherpaPreview(
                text = text,
                family = clonedVoice.family,
                speakingRate = speakingRate,
                pitch = pitch * (0.94f + refEnergy * 0.12f)
            )
            SynthesisOutcome(
                pcmFloat24k = preview,
                sampleRateHz = SAMPLE_RATE_HZ,
                hardwareBadge = HardwareStatusBadge.CPU_NEON,
                engineSummary = "${clonedVoice.family.label} Clone Preview • ${clonedVoice.displayName}",
                isFallbackNeuralPreview = true
            )
        }
    }

    override suspend fun evictResources() {
        mutex.withLock {
            runCatching {
                activeOfflineTts?.javaClass?.getMethod("release")?.invoke(activeOfflineTts)
            }
            activeOfflineTts = null
            activeFamily = null
            _statusFlow.value = HardwareStatusBadge.IDLE
            Log.i(TAG, "[ONNX_EVICT] Released sherpa-onnx OfflineTts native memory.")
        }
    }

    fun familyDir(family: SherpaModelFamily): File = when (family) {
        SherpaModelFamily.SUPERTONIC_3 -> File(modelsBaseDir, "supertonic3")
        SherpaModelFamily.POCKET_TTS -> File(modelsBaseDir, "pocket_tts")
        SherpaModelFamily.ZIPVOICE -> File(modelsBaseDir, "zipvoice")
        SherpaModelFamily.PIPER -> File(modelsBaseDir, "piper")
    }.apply { mkdirs() }

    private fun ensureFamilyLoadedLocked(family: SherpaModelFamily) {
        if (activeOfflineTts != null && activeFamily == family) return
        runCatching { activeOfflineTts?.javaClass?.getMethod("release")?.invoke(activeOfflineTts) }
        activeOfflineTts = null
        activeFamily = family

        val dir = familyDir(family)
        val onnxFiles = dir.walkTopDown().filter { it.extension.equals("onnx", ignoreCase = true) }.toList()
        if (onnxFiles.isEmpty()) return

        runCatching {
            val offlineTtsClass = Class.forName("com.k2fsa.sherpa.onnx.OfflineTts")
            val configClass = Class.forName("com.k2fsa.sherpa.onnx.OfflineTtsConfig")
            val modelConfigClass = Class.forName("com.k2fsa.sherpa.onnx.OfflineTtsModelConfig")

            val modelPath = onnxFiles.first().absolutePath
            val tokensPath = dir.walkTopDown().firstOrNull { it.name == "tokens.txt" }?.absolutePath ?: ""
            val dataDir = dir.walkTopDown().firstOrNull { it.isDirectory && it.name == "espeak-ng-data" }?.absolutePath ?: ""

            val modelConfig = modelConfigClass.getDeclaredConstructor().newInstance().apply {
                runCatching { javaClass.getMethod("setNumThreads", Int::class.javaPrimitiveType).invoke(this, 4) }
                runCatching { javaClass.getMethod("setProvider", String::class.java).invoke(this, "cpu") }
            }

            // Configure family-specific model descriptor (VITS / Pocket / ZipVoice / Supertonic)
            when (family) {
                SherpaModelFamily.PIPER -> {
                    runCatching {
                        val vitsClass = Class.forName("com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig")
                        val vits = vitsClass.getDeclaredConstructor().newInstance()
                        runCatching { vitsClass.getMethod("setModel", String::class.java).invoke(vits, modelPath) }
                        runCatching { vitsClass.getMethod("setTokens", String::class.java).invoke(vits, tokensPath) }
                        runCatching { vitsClass.getMethod("setDataDir", String::class.java).invoke(vits, dataDir) }
                        modelConfigClass.getMethod("setVits", vitsClass).invoke(modelConfig, vits)
                    }
                }
                SherpaModelFamily.ZIPVOICE -> {
                    runCatching {
                        val zvClass = Class.forName("com.k2fsa.sherpa.onnx.OfflineTtsZipvoiceModelConfig")
                        val zv = zvClass.getDeclaredConstructor().newInstance()
                        runCatching { zvClass.getMethod("setTokens", String::class.java).invoke(zv, tokensPath) }
                        runCatching { zvClass.getMethod("setDataDir", String::class.java).invoke(zv, dataDir) }
                        modelConfigClass.getMethod("setZipvoice", zvClass).invoke(modelConfig, zv)
                    }
                }
                SherpaModelFamily.POCKET_TTS -> {
                    runCatching {
                        val ptClass = Class.forName("com.k2fsa.sherpa.onnx.OfflineTtsPocketModelConfig")
                        val pt = ptClass.getDeclaredConstructor().newInstance()
                        modelConfigClass.getMethod("setPocket", ptClass).invoke(modelConfig, pt)
                    }
                }
                SherpaModelFamily.SUPERTONIC_3 -> {
                    runCatching {
                        val stClass = Class.forName("com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig")
                        val st = stClass.getDeclaredConstructor().newInstance()
                        modelConfigClass.getMethod("setSupertonic", stClass).invoke(modelConfig, st)
                    }
                }
            }

            val config = configClass.getDeclaredConstructor().newInstance()
            configClass.getMethod("setModel", modelConfigClass).invoke(config, modelConfig)
            activeOfflineTts = offlineTtsClass.getDeclaredConstructor(
                android.content.res.AssetManager::class.java,
                configClass
            ).newInstance(null, config)
            _statusFlow.value = HardwareStatusBadge.CPU_NEON
            Log.i(TAG, "Loaded sherpa-onnx ${family.label} from ${dir.absolutePath}")
        }.onFailure { err ->
            Log.w(TAG, "sherpa-onnx JNI library unavailable or signature mismatch: ${err.message}")
        }
    }

    private data class SherpaGeneratedAudio(val samples: FloatArray, val sampleRate: Int)

    private fun tryInvokeSherpaGenerate(
        text: String,
        speakerId: Int,
        speed: Float,
        referenceSamples: FloatArray?,
        referenceSampleRate: Int,
        referencePrompt: String?
    ): SherpaGeneratedAudio? {
        val tts = activeOfflineTts ?: return null
        return runCatching {
            val result = if (referenceSamples != null && referenceSamples.isNotEmpty()) {
                val cloneMethod = tts.javaClass.methods.firstOrNull {
                    it.name == "generateWithPrompt" || it.name == "generateZeroShot"
                }
                if (cloneMethod != null && cloneMethod.parameterCount >= 4) {
                    cloneMethod.invoke(tts, text, referencePrompt ?: "", referenceSamples, referenceSampleRate)
                } else {
                    tts.javaClass.getMethod(
                        "generate",
                        String::class.java,
                        Int::class.javaPrimitiveType,
                        Float::class.javaPrimitiveType
                    ).invoke(tts, text, speakerId, speed)
                }
            } else {
                tts.javaClass.getMethod(
                    "generate",
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    Float::class.javaPrimitiveType
                ).invoke(tts, text, speakerId, speed)
            } ?: return null

            val samples = result.javaClass.getMethod("getSamples").invoke(result) as? FloatArray ?: return null
            val sr = result.javaClass.getMethod("getSampleRate").invoke(result) as? Int ?: SAMPLE_RATE_HZ
            SherpaGeneratedAudio(samples, sr)
        }.getOrNull()
    }

    fun loadWavMonoFloat24k(wavFile: File): FloatArray {
        if (!wavFile.exists() || wavFile.length() <= 44L) return FloatArray(0)
        return runCatching {
            FileInputStream(wavFile).use { fis ->
                val allBytes = fis.readBytes()
                if (allBytes.size <= 44) return FloatArray(0)
                val header = ByteBuffer.wrap(allBytes, 0, 44).order(ByteOrder.LITTLE_ENDIAN)
                val channels = header.getShort(22).toInt().coerceAtLeast(1)
                val sampleRate = header.getInt(24).coerceAtLeast(8000)
                val bitsPerSample = header.getShort(34).toInt().coerceAtLeast(16)

                val dataBytes = allBytes.copyOfRange(44, allBytes.size)
                val rawFloat = if (bitsPerSample == 16) {
                    PurrDspProcessor.pcm16LeToFloat32(dataBytes)
                } else {
                    val buf = ByteBuffer.wrap(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
                    FloatArray(dataBytes.size / 4) { buf.float }
                }

                val mono = if (channels > 1) {
                    FloatArray(rawFloat.size / channels) { i ->
                        var sum = 0f
                        for (c in 0 until channels) sum += rawFloat[i * channels + c]
                        sum / channels
                    }
                } else {
                    rawFloat
                }

                PurrDspProcessor.resampleMono(mono, sampleRate, SAMPLE_RATE_HZ)
            }
        }.getOrElse { FloatArray(0) }
    }

    private fun inferFamilyFromVoice(voiceId: String, defaultFamily: SherpaModelFamily): SherpaModelFamily = when {
        voiceId.contains("supertonic", ignoreCase = true) -> SherpaModelFamily.SUPERTONIC_3
        voiceId.contains("pocket", ignoreCase = true) -> SherpaModelFamily.POCKET_TTS
        voiceId.contains("zipvoice", ignoreCase = true) -> SherpaModelFamily.ZIPVOICE
        voiceId.contains("piper", ignoreCase = true) -> SherpaModelFamily.PIPER
        else -> defaultFamily
    }

    private fun resetIdleTimer() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(IDLE_EVICTION_MS)
            evictResources()
        }
    }

    private fun synthesizeSherpaPreview(
        text: String,
        family: SherpaModelFamily,
        speakingRate: Float,
        pitch: Float
    ): FloatArray {
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return FloatArray(0)
        val baseF0 = when (family) {
            SherpaModelFamily.SUPERTONIC_3 -> 225.0
            SherpaModelFamily.POCKET_TTS -> 208.0
            SherpaModelFamily.ZIPVOICE -> 235.0
            SherpaModelFamily.PIPER -> 196.0
        } * pitch
        val wordSamples = (SAMPLE_RATE_HZ * 0.19 / speakingRate.coerceIn(0.65f, 1.5f)).toInt().coerceAtLeast(1000)
        val out = FloatArray(words.size * wordSamples)
        words.forEachIndexed { idx, w ->
            val f1 = 480.0 + (w.length % 5) * 65.0
            val baseIdx = idx * wordSamples
            for (i in 0 until wordSamples) {
                val norm = i.toDouble() / wordSamples
                val env = sin(PI * norm)
                val t = i.toDouble() / SAMPLE_RATE_HZ
                val s = 0.55 * sin(2.0 * PI * baseF0 * t) + 0.25 * sin(2.0 * PI * f1 * t)
                out[baseIdx + i] = (s * env * 0.42).toFloat()
            }
        }
        return out
    }
}
