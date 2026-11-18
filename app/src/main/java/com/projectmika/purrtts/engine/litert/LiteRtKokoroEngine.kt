package com.projectmika.purrtts.engine.litert

import android.content.Context
import android.util.Log
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.projectmika.purrtts.core.purr.BackendType
import com.projectmika.purrtts.core.purr.HardwareStatusBadge
import com.projectmika.purrtts.core.purr.PurrDefaultPresets
import com.projectmika.purrtts.core.purr.PurrDspProcessor
import com.projectmika.purrtts.core.purr.PurrProfile
import com.projectmika.purrtts.engine.PurrSynthesizer
import com.projectmika.purrtts.engine.SynthesisOutcome
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
 * ✨ MIKA'S HARDWARE-ACCELERATED LITERT RUNTIME (KOKORO-82M GPU FP16) ✨
 *
 * Targets `litert-community/Kokoro-82M` (`kokoro_int8_gpu_fp16.litertlm`) + persistent OpenCL/QNN
 * `--cache_dir` and a 5-minute idle VRAM eviction timer so Android `lmkd` never terminates the service.
 * Returns empty PCM when the `.litertlm` cartridge is not slotted/loaded so `TtsBackendRouter`
 * fails soft to real speech (`CORE_OS` or `SherpaOnnx`) without synthetic tones.
 */
class LiteRtKokoroEngine(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : PurrSynthesizer {

    companion object {
        private const val TAG = "PurrLiteRT"
        private const val SAMPLE_RATE_HZ = 24000
        const val IDLE_VRAM_EVICTION_MS = 5 * 60 * 1000L
    }

    override val backendType: BackendType = BackendType.LITERT_NPU_GPU

    private val _statusFlow = MutableStateFlow(HardwareStatusBadge.IDLE)
    override val statusFlow: StateFlow<HardwareStatusBadge> = _statusFlow.asStateFlow()

    private val _vramRemainingSeconds = MutableStateFlow(0)
    val vramRemainingSeconds: StateFlow<Int> = _vramRemainingSeconds.asStateFlow()

    private val engineMutex = Mutex()
    private var idleEvictionJob: Job? = null

    private var kokoroEngineHandle: AutoCloseable? = null
    private var g2pCompiledModel: CompiledModel? = null
    private var mediaPipeTtsHandle: AutoCloseable? = null

    private val modelDir: File by lazy {
        File(context.filesDir, "models/litert").apply { mkdirs() }
    }

    private val kernelCacheDir: File by lazy {
        File(context.cacheDir, "litert_opencl_qnn_cache").apply { mkdirs() }
    }

    val kokoroModelFile: File
        get() = File(modelDir, "kokoro_int8_gpu_fp16.litertlm")

    val g2pModelFile: File
        get() = File(modelDir, "dp_g2p_litert.tflite")

    val isNpuG2pReady: Boolean
        get() = g2pModelFile.exists() && g2pModelFile.length() > 1024L

    override val isModelReady: Boolean
        get() = (kokoroModelFile.exists() && kokoroModelFile.length() > 1024L) || isNpuG2pReady

    private val isKokoroLmFileReady: Boolean
        get() = kokoroModelFile.exists() && kokoroModelFile.length() > 1024L

    override suspend fun warmup(profile: PurrProfile) {
        engineMutex.withLock {
            ensureInitializedLocked()
        }
        resetIdleEvictionTimer()
    }

    override suspend fun synthesizeChunk(
        text: String,
        voiceId: String,
        speakingRate: Float,
        pitch: Float,
        profile: PurrProfile
    ): SynthesisOutcome = withContext(Dispatchers.IO) {
        if (!isKokoroLmFileReady) {
            return@withContext SynthesisOutcome(
                pcmFloat24k = FloatArray(0),
                sampleRateHz = SAMPLE_RATE_HZ,
                hardwareBadge = HardwareStatusBadge.IDLE,
                engineSummary = if (isNpuG2pReady) {
                    "Hexagon NPU G2P active — routing waveform via Sherpa Kokoro"
                } else {
                    "LiteRT GPU cartridge not installed"
                },
                isFallbackNeuralPreview = true
            )
        }

        resetIdleEvictionTimer()

        engineMutex.withLock {
            ensureInitializedLocked()

            val clampedPitch = pitch.coerceIn(0.88f, 1.12f)
            val compensatedSpeed = (speakingRate / clampedPitch).coerceIn(0.6f, 1.8f)
            val cleanVoiceId = voiceId.substringAfter(':')
            val speakerIndex = PurrDefaultPresets.kokoroVoices("rom_kokoro_litert", BackendType.LITERT_NPU_GPU)
                .firstOrNull { it.id == cleanVoiceId }?.speakerIndex ?: 0

            val phonemeTokens = runG2pPhonemizer(text)

            val mpSamples = trySynthesizeViaMediaPipeTasks(text, cleanVoiceId, compensatedSpeed)
            if (mpSamples != null && mpSamples.isNotEmpty()) {
                _statusFlow.value = HardwareStatusBadge.GPU_FP16
                return@withContext SynthesisOutcome(
                    pcmFloat24k = PurrDspProcessor.shiftPitchCubic(mpSamples, clampedPitch),
                    sampleRateHz = SAMPLE_RATE_HZ,
                    hardwareBadge = HardwareStatusBadge.GPU_FP16,
                    engineSummary = "MediaPipe Tasks TTS • Kokoro-82M GPU FP16"
                )
            }

            val lmSamples = trySynthesizeViaLiteRtLm(phonemeTokens, speakerIndex, compensatedSpeed)
            if (lmSamples != null && lmSamples.isNotEmpty()) {
                val activeBadge = if (g2pCompiledModel != null) {
                    HardwareStatusBadge.NPU_ACTIVE
                } else {
                    HardwareStatusBadge.GPU_FP16
                }
                _statusFlow.value = activeBadge
                return@withContext SynthesisOutcome(
                    pcmFloat24k = PurrDspProcessor.shiftPitchCubic(lmSamples, clampedPitch),
                    sampleRateHz = SAMPLE_RATE_HZ,
                    hardwareBadge = activeBadge,
                    engineSummary = "LiteRT-LM Kokoro-82M ($activeBadge)"
                )
            }

            SynthesisOutcome(
                pcmFloat24k = FloatArray(0),
                sampleRateHz = SAMPLE_RATE_HZ,
                hardwareBadge = HardwareStatusBadge.IDLE,
                engineSummary = "LiteRT runtime unavailable — failing over",
                isFallbackNeuralPreview = true
            )
        }
    }

    override suspend fun evictResources() {
        engineMutex.withLock {
            runCatching { mediaPipeTtsHandle?.close() }
            mediaPipeTtsHandle = null

            runCatching { kokoroEngineHandle?.close() }
            kokoroEngineHandle = null

            runCatching { g2pCompiledModel?.close() }
            g2pCompiledModel = null

            _statusFlow.value = HardwareStatusBadge.IDLE
            _vramRemainingSeconds.value = 0
            Log.i(TAG, "[VRAM_EVICT] Released LiteRT GPU FP16 & NPU contexts cleanly.")
        }
    }

    private fun ensureInitializedLocked() {
        val cachePath = kernelCacheDir.absolutePath

        if (g2pCompiledModel == null && g2pModelFile.exists()) {
            runCatching {
                val options = CompiledModel.Options(
                    Accelerator.NPU,
                    Accelerator.GPU,
                    Accelerator.CPU
                ).apply {
                    gpuOptions = CompiledModel.GpuOptions(
                        serializationDir = cachePath,
                        serializeProgramCache = true,
                        precision = CompiledModel.GpuOptions.Precision.FP16
                    )
                }
                g2pCompiledModel = CompiledModel.create(g2pModelFile.absolutePath, options)
                _statusFlow.value = HardwareStatusBadge.NPU_ACTIVE
            }
        }

        if (kokoroEngineHandle == null && isKokoroLmFileReady) {
            val gpuEngine = tryInitLiteRtLmEngine(
                modelPath = kokoroModelFile.absolutePath,
                cachePath = cachePath,
                preferGpu = true
            )
            if (gpuEngine != null) {
                kokoroEngineHandle = gpuEngine
                _statusFlow.value = if (g2pCompiledModel != null) {
                    HardwareStatusBadge.NPU_ACTIVE
                } else {
                    HardwareStatusBadge.GPU_FP16
                }
            } else {
                val cpuEngine = tryInitLiteRtLmEngine(
                    modelPath = kokoroModelFile.absolutePath,
                    cachePath = cachePath,
                    preferGpu = false
                )
                if (cpuEngine != null) {
                    kokoroEngineHandle = cpuEngine
                    _statusFlow.value = HardwareStatusBadge.CPU_NEON
                }
            }
        }

        if (mediaPipeTtsHandle == null && isKokoroLmFileReady) {
            mediaPipeTtsHandle = tryInitMediaPipeTts(kokoroModelFile.absolutePath)
        }
    }

    private fun tryInitLiteRtLmEngine(
        modelPath: String,
        cachePath: String,
        preferGpu: Boolean
    ): AutoCloseable? = runCatching {
        val backendClass = Class.forName("com.google.ai.edge.litertlm.Backend")
        val backendSubClass = Class.forName(
            if (preferGpu) "com.google.ai.edge.litertlm.Backend\$GPU"
            else "com.google.ai.edge.litertlm.Backend\$CPU"
        )
        val backendInstance = backendSubClass.getDeclaredConstructor().newInstance()
        val configClass = Class.forName("com.google.ai.edge.litertlm.EngineConfig")
        val configCtor = configClass.constructors.firstOrNull { ctor ->
            ctor.parameterTypes.size >= 3 &&
                ctor.parameterTypes[0] == String::class.java &&
                backendClass.isAssignableFrom(ctor.parameterTypes[1])
        } ?: return null

        val args = arrayOfNulls<Any?>(configCtor.parameterTypes.size)
        args[0] = modelPath
        args[1] = backendInstance
        for (i in 2 until configCtor.parameterTypes.size) {
            if (configCtor.parameterTypes[i] == String::class.java) {
                args[i] = cachePath
            }
        }
        val engineConfig = configCtor.newInstance(*args)
        val engineClass = Class.forName("com.google.ai.edge.litertlm.Engine")
        val engine = engineClass.getDeclaredConstructor(configClass).newInstance(engineConfig)
        engineClass.getMethod("initialize").invoke(engine)
        engine as? AutoCloseable
    }.getOrNull()

    private fun tryInitMediaPipeTts(modelPath: String): AutoCloseable? = runCatching {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(modelPath)
            .setDelegate(Delegate.GPU)
            .build()

        val ttsClass = Class.forName("com.google.mediapipe.tasks.audio.texttospeech.TextToSpeech")
        val optsClass = Class.forName("com.google.mediapipe.tasks.audio.texttospeech.TextToSpeechOptions")
        val optsBuilder = optsClass.getMethod("builder").invoke(null)
        optsBuilder.javaClass.getMethod("setBaseOptions", BaseOptions::class.java)
            .invoke(optsBuilder, baseOptions)
        val builtOpts = optsBuilder.javaClass.getMethod("build").invoke(optsBuilder)
        ttsClass.getMethod("createFromOptions", Context::class.java, optsClass)
            .invoke(null, context, builtOpts) as? AutoCloseable
    }.getOrNull()

    private fun trySynthesizeViaMediaPipeTasks(
        text: String,
        voiceId: String,
        speakingRate: Float
    ): FloatArray? {
        val handle = mediaPipeTtsHandle ?: return null
        return runCatching {
            val synthOptsClass = Class.forName("com.google.mediapipe.tasks.audio.texttospeech.SynthesisOptions")
            val b = synthOptsClass.getMethod("builder").invoke(null)
            runCatching { b.javaClass.getMethod("setVoiceId", String::class.java).invoke(b, voiceId) }
            runCatching { b.javaClass.getMethod("setSpeakingRate", Float::class.javaPrimitiveType).invoke(b, speakingRate) }
            val synthOpts = b.javaClass.getMethod("build").invoke(b)
            val result = handle.javaClass
                .getMethod("synthesize", String::class.java, synthOptsClass)
                .invoke(handle, text, synthOpts)
            val audioMethod = result?.javaClass?.methods?.firstOrNull {
                it.name in setOf("audioData", "pcmFloatData", "samples") && it.parameterCount == 0
            }
            when (val raw = audioMethod?.invoke(result)) {
                is FloatArray -> raw
                is ByteBuffer -> {
                    raw.order(ByteOrder.LITTLE_ENDIAN)
                    FloatArray(raw.remaining() / 4) { raw.float }
                }
                else -> null
            }
        }.getOrNull()
    }

    private fun runG2pPhonemizer(text: String): String {
        val model = g2pCompiledModel ?: return text
        return runCatching {
            val inputBufs = model.createInputBuffers()
            val outputBufs = model.createOutputBuffers()
            try {
                val encoded = text.lowercase().take(128).map { it.code }.toIntArray()
                if (inputBufs.isNotEmpty()) {
                    inputBufs[0].writeInt(encoded)
                    model.run(inputBufs, outputBufs)
                    if (outputBufs.isNotEmpty()) {
                        val ids = outputBufs[0].readInt()
                        if (ids.isNotEmpty()) return ids.joinToString(" ")
                    }
                }
                text
            } finally {
                inputBufs.forEach { runCatching { it.close() } }
                outputBufs.forEach { runCatching { it.close() } }
            }
        }.getOrDefault(text)
    }

    private fun trySynthesizeViaLiteRtLm(
        phonemes: String,
        speakerIndex: Int,
        speakingRate: Float
    ): FloatArray? {
        val engine = kokoroEngineHandle ?: return null
        return runCatching {
            val conv = engine.javaClass.getMethod("createConversation").invoke(engine) as? AutoCloseable
                ?: return null
            conv.use { session ->
                val prompt = "<|voice:$speakerIndex|><|speed:$speakingRate|>$phonemes"
                val response = session.javaClass
                    .getMethod("sendMessage", String::class.java)
                    .invoke(session, prompt)
                val bytes = response?.toString()?.toByteArray(Charsets.ISO_8859_1) ?: return null
                if (bytes.size >= 4096) {
                    val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                    FloatArray(bytes.size / 4) { buf.float.coerceIn(-1f, 1f) }
                } else {
                    null
                }
            }
        }.getOrNull()
    }

    private fun resetIdleEvictionTimer() {
        idleEvictionJob?.cancel()
        idleEvictionJob = scope.launch {
            var remainingMs = IDLE_VRAM_EVICTION_MS
            while (remainingMs > 0 && isActive) {
                _vramRemainingSeconds.value = (remainingMs / 1000L).toInt()
                val step = minOf(1000L, remainingMs)
                delay(step)
                remainingMs -= step
            }
            if (isActive) {
                evictResources()
            }
        }
    }
}
