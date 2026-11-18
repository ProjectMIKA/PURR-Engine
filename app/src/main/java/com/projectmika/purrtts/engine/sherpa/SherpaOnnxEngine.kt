package com.projectmika.purrtts.engine.sherpa

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsPocketModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsZipVoiceModelConfig
import com.projectmika.purrtts.core.purr.BackendType
import com.projectmika.purrtts.core.purr.ClonedVoiceProfile
import com.projectmika.purrtts.core.purr.HardwareStatusBadge
import com.projectmika.purrtts.core.purr.PurrDefaultPresets
import com.projectmika.purrtts.core.purr.PurrDspProcessor
import com.projectmika.purrtts.core.purr.PurrProfile
import com.projectmika.purrtts.core.purr.SherpaModelFamily
import com.projectmika.purrtts.engine.PurrSynthesizer
import com.projectmika.purrtts.engine.SynthesisOutcome
import java.io.File
import java.io.FileInputStream
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * ✨ MIKA'S SHERPA-ONNX MULTI-ENGINE & ZERO-SHOT VOICE CLONING RUNTIME ✨
 *
 * Directly backed by the bundled `sherpa-onnx` JNI AAR (`com.k2fsa.sherpa.onnx.OfflineTts`)
 * supporting all 5 offline cartridge architectures:
 * - Kokoro-82M INT8 (54 voices)
 * - Supertonic 3 INT8
 * - Piper VITS
 * - Pocket TTS (5-second zero-shot cloning)
 * - ZipVoice (flow-matching zero-shot cloning)
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
    private var activeOfflineTts: OfflineTts? = null
    private var activeFamily: SherpaModelFamily? = null

    val modelsBaseDir: File by lazy {
        File(context.filesDir, "models/sherpa").apply { mkdirs() }
    }

    override val isModelReady: Boolean
        get() = modelsBaseDir.listFiles()?.any { it.isDirectory && it.listFiles()?.isNotEmpty() == true } == true

    fun isFamilyInstalled(family: SherpaModelFamily): Boolean {
        val dir = familyDir(family)
        return dir.walkTopDown().any { it.extension.equals("onnx", ignoreCase = true) }
    }

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
            val tts = activeOfflineTts
            if (tts == null) {
                return@withContext SynthesisOutcome(
                    pcmFloat24k = FloatArray(0),
                    sampleRateHz = SAMPLE_RATE_HZ,
                    hardwareBadge = HardwareStatusBadge.IDLE,
                    engineSummary = "${targetFamily.label} cartridge not installed",
                    isFallbackNeuralPreview = true
                )
            }

            val speakerId = resolveSpeakerId(voiceId, targetFamily)
            val generated = runCatching {
                tts.generate(text = text, sid = speakerId, speed = speakingRate.coerceIn(0.6f, 1.8f))
            }.getOrNull()

            if (generated == null || generated.samples.isEmpty()) {
                return@withContext SynthesisOutcome(
                    pcmFloat24k = FloatArray(0),
                    sampleRateHz = SAMPLE_RATE_HZ,
                    hardwareBadge = HardwareStatusBadge.IDLE,
                    engineSummary = "${targetFamily.label} synthesis returned empty buffer",
                    isFallbackNeuralPreview = true
                )
            }

            _statusFlow.value = HardwareStatusBadge.CPU_NEON
            val resampled = PurrDspProcessor.resampleMono(
                samples = generated.samples,
                fromRate = generated.sampleRate,
                toRate = SAMPLE_RATE_HZ
            )
            SynthesisOutcome(
                pcmFloat24k = applyPitchShift(resampled, pitch),
                sampleRateHz = SAMPLE_RATE_HZ,
                hardwareBadge = HardwareStatusBadge.CPU_NEON,
                engineSummary = "sherpa-onnx • ${targetFamily.label} (${generated.sampleRate}Hz -> 24kHz)"
            )
        }
    }

    suspend fun synthesizeCloned(
        text: String,
        clonedVoice: ClonedVoiceProfile,
        speakingRate: Float,
        pitch: Float
    ): SynthesisOutcome = withContext(Dispatchers.IO) {
        resetIdleTimer()

        mutex.withLock {
            ensureFamilyLoadedLocked(clonedVoice.family)
            val tts = activeOfflineTts
            if (tts == null) {
                return@withContext SynthesisOutcome(
                    pcmFloat24k = FloatArray(0),
                    sampleRateHz = SAMPLE_RATE_HZ,
                    hardwareBadge = HardwareStatusBadge.IDLE,
                    engineSummary = "${clonedVoice.family.label} cartridge not installed",
                    isFallbackNeuralPreview = true
                )
            }

            val refSamples = loadWavMonoFloat24k(File(clonedVoice.referenceWavPath))
            val generated = runCatching {
                if (refSamples.isNotEmpty()) {
                    val genCfg = GenerationConfig(
                        speed = speakingRate.coerceIn(0.6f, 1.8f),
                        referenceAudio = refSamples,
                        referenceSampleRate = SAMPLE_RATE_HZ,
                        referenceText = clonedVoice.referenceTranscript
                    )
                    tts.generateWithConfig(text, genCfg)
                } else {
                    tts.generate(text = text, sid = 0, speed = speakingRate.coerceIn(0.6f, 1.8f))
                }
            }.getOrNull()

            if (generated == null || generated.samples.isEmpty()) {
                return@withContext SynthesisOutcome(
                    pcmFloat24k = FloatArray(0),
                    sampleRateHz = SAMPLE_RATE_HZ,
                    hardwareBadge = HardwareStatusBadge.IDLE,
                    engineSummary = "${clonedVoice.family.label} clone synthesis failed",
                    isFallbackNeuralPreview = true
                )
            }

            _statusFlow.value = HardwareStatusBadge.CPU_NEON
            val resampled = PurrDspProcessor.resampleMono(
                samples = generated.samples,
                fromRate = generated.sampleRate,
                toRate = SAMPLE_RATE_HZ
            )
            SynthesisOutcome(
                pcmFloat24k = applyPitchShift(resampled, pitch),
                sampleRateHz = SAMPLE_RATE_HZ,
                hardwareBadge = HardwareStatusBadge.CPU_NEON,
                engineSummary = "${clonedVoice.family.label} Cloned • ${clonedVoice.displayName}"
            )
        }
    }

    override suspend fun evictResources() {
        mutex.withLock {
            runCatching { activeOfflineTts?.release() }
            activeOfflineTts = null
            activeFamily = null
            _statusFlow.value = HardwareStatusBadge.IDLE
            Log.i(TAG, "[ONNX_EVICT] Released sherpa-onnx OfflineTts native memory.")
        }
    }

    fun familyDir(family: SherpaModelFamily): File = when (family) {
        SherpaModelFamily.KOKORO -> File(modelsBaseDir, "kokoro")
        SherpaModelFamily.SUPERTONIC_3 -> File(modelsBaseDir, "supertonic3")
        SherpaModelFamily.POCKET_TTS -> File(modelsBaseDir, "pocket_tts")
        SherpaModelFamily.ZIPVOICE -> File(modelsBaseDir, "zipvoice")
        SherpaModelFamily.PIPER -> File(modelsBaseDir, "piper")
    }.apply { mkdirs() }

    private fun ensureFamilyLoadedLocked(family: SherpaModelFamily) {
        if (activeOfflineTts != null && activeFamily == family) return
        runCatching { activeOfflineTts?.release() }
        activeOfflineTts = null
        activeFamily = family

        val dir = familyDir(family)
        val files = dir.walkTopDown().filter { it.isFile }.toList()
        if (files.none { it.extension.equals("onnx", ignoreCase = true) }) return

        val tokensPath = files.firstOrNull { it.name == "tokens.txt" }?.absolutePath.orEmpty()
        val dataDir = dir.walkTopDown().firstOrNull { it.isDirectory && it.name == "espeak-ng-data" }?.absolutePath.orEmpty()
        val lexicons = files.filter { it.name.startsWith("lexicon") && it.extension == "txt" }
            .joinToString(",") { it.absolutePath }

        val modelConfig = when (family) {
            SherpaModelFamily.KOKORO -> {
                val onnx = files.firstOrNull { it.name.endsWith(".onnx") }?.absolutePath.orEmpty()
                val voices = files.firstOrNull { it.name == "voices.bin" }?.absolutePath.orEmpty()
                if (onnx.isEmpty() || voices.isEmpty()) return
                OfflineTtsModelConfig(
                    kokoro = OfflineTtsKokoroModelConfig(
                        model = onnx,
                        voices = voices,
                        tokens = tokensPath,
                        dataDir = dataDir,
                        lexicon = lexicons
                    ),
                    numThreads = 4,
                    provider = "cpu"
                )
            }
            SherpaModelFamily.SUPERTONIC_3 -> {
                val dur = files.firstOrNull { it.name.contains("duration_predictor") && it.extension == "onnx" }?.absolutePath.orEmpty()
                val txtEnc = files.firstOrNull { it.name.contains("text_encoder") && it.extension == "onnx" }?.absolutePath.orEmpty()
                val vecEst = files.firstOrNull { it.name.contains("vector_estimator") && it.extension == "onnx" }?.absolutePath.orEmpty()
                val voc = files.firstOrNull { it.name.contains("vocoder") && it.extension == "onnx" }?.absolutePath.orEmpty()
                val ttsJson = files.firstOrNull { it.name == "tts.json" }?.absolutePath.orEmpty()
                val uniIdx = files.firstOrNull { it.name.contains("unicode_indexer") }?.absolutePath.orEmpty()
                val vStyle = files.firstOrNull { it.name.contains("voice") && it.extension == "bin" }?.absolutePath.orEmpty()
                if (dur.isEmpty() || txtEnc.isEmpty() || vecEst.isEmpty() || voc.isEmpty()) return
                OfflineTtsModelConfig(
                    supertonic = OfflineTtsSupertonicModelConfig(
                        durationPredictor = dur,
                        textEncoder = txtEnc,
                        vectorEstimator = vecEst,
                        vocoder = voc,
                        ttsJson = ttsJson,
                        unicodeIndexer = uniIdx,
                        voiceStyle = vStyle
                    ),
                    numThreads = 4,
                    provider = "cpu"
                )
            }
            SherpaModelFamily.PIPER -> {
                val onnx = files.firstOrNull { it.extension.equals("onnx", ignoreCase = true) }?.absolutePath.orEmpty()
                if (onnx.isEmpty()) return
                OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = onnx,
                        tokens = tokensPath,
                        dataDir = dataDir
                    ),
                    numThreads = 4,
                    provider = "cpu"
                )
            }
            SherpaModelFamily.POCKET_TTS -> {
                val lmFlow = files.firstOrNull { it.name.contains("lm_flow") && it.extension == "onnx" }?.absolutePath.orEmpty()
                val lmMain = files.firstOrNull { it.name.contains("lm_main") && it.extension == "onnx" }?.absolutePath.orEmpty()
                val enc = files.firstOrNull { it.name.contains("encoder") && it.extension == "onnx" }?.absolutePath.orEmpty()
                val dec = files.firstOrNull { it.name.contains("decoder") && it.extension == "onnx" }?.absolutePath.orEmpty()
                val txtCond = files.firstOrNull { it.name.contains("text_conditioner") && it.extension == "onnx" }?.absolutePath.orEmpty()
                val vocab = files.firstOrNull { it.name.contains("vocab") && it.extension == "json" }?.absolutePath.orEmpty()
                val scores = files.firstOrNull { it.name.contains("token_scores") && it.extension == "json" }?.absolutePath.orEmpty()
                if (lmFlow.isEmpty() || lmMain.isEmpty()) return
                OfflineTtsModelConfig(
                    pocket = OfflineTtsPocketModelConfig(
                        lmFlow = lmFlow,
                        lmMain = lmMain,
                        encoder = enc,
                        decoder = dec,
                        textConditioner = txtCond,
                        vocabJson = vocab,
                        tokenScoresJson = scores
                    ),
                    numThreads = 4,
                    provider = "cpu"
                )
            }
            SherpaModelFamily.ZIPVOICE -> {
                val enc = files.firstOrNull { it.name.contains("encoder") && it.extension == "onnx" }?.absolutePath.orEmpty()
                val dec = files.firstOrNull { it.name.contains("decoder") && it.extension == "onnx" }?.absolutePath.orEmpty()
                val voc = files.firstOrNull { it.name.contains("vocoder") && it.extension == "onnx" }?.absolutePath.orEmpty()
                if (enc.isEmpty() || dec.isEmpty()) return
                OfflineTtsModelConfig(
                    zipvoice = OfflineTtsZipVoiceModelConfig(
                        tokens = tokensPath,
                        encoder = enc,
                        decoder = dec,
                        vocoder = voc,
                        dataDir = dataDir,
                        lexicon = lexicons
                    ),
                    numThreads = 4,
                    provider = "cpu"
                )
            }
        }

        runCatching {
            val config = OfflineTtsConfig(model = modelConfig)
            activeOfflineTts = OfflineTts(assetManager = null, config = config)
            _statusFlow.value = HardwareStatusBadge.CPU_NEON
            Log.i(TAG, "Mounted sherpa-onnx ${family.label} cleanly from ${dir.absolutePath}")
        }.onFailure { err ->
            Log.e(TAG, "Failed to mount sherpa-onnx ${family.label}: ${err.message}", err)
            activeOfflineTts = null
        }
    }

    private fun resolveSpeakerId(voiceId: String, family: SherpaModelFamily): Int {
        if (family == SherpaModelFamily.KOKORO) {
            return PurrDefaultPresets.kokoroVoices("rom_kokoro_sherpa", BackendType.SHERPA_ONNX)
                .firstOrNull { it.id == voiceId }?.speakerIndex ?: 0
        }
        return when {
            voiceId.endsWith("m1") -> 1
            else -> 0
        }
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
        voiceId.matches(Regex("^[abjefhipz][fm]_[a-z]+$")) -> SherpaModelFamily.KOKORO
        voiceId.contains("supertonic", ignoreCase = true) -> SherpaModelFamily.SUPERTONIC_3
        voiceId.contains("pocket", ignoreCase = true) -> SherpaModelFamily.POCKET_TTS
        voiceId.contains("zipvoice", ignoreCase = true) -> SherpaModelFamily.ZIPVOICE
        voiceId.contains("piper", ignoreCase = true) -> SherpaModelFamily.PIPER
        else -> defaultFamily
    }

    private fun applyPitchShift(samples: FloatArray, pitch: Float): FloatArray {
        if (pitch in 0.985f..1.015f || samples.isEmpty()) return samples
        val targetLen = (samples.size / pitch).toInt().coerceAtLeast(1)
        val out = FloatArray(targetLen)
        for (i in 0 until targetLen) {
            val pos = i * pitch
            val i0 = pos.toInt().coerceIn(0, samples.lastIndex)
            val i1 = (i0 + 1).coerceAtMost(samples.lastIndex)
            val frac = pos - i0
            out[i] = samples[i0] + (samples[i1] - samples[i0]) * frac
        }
        return out
    }

    private fun resetIdleTimer() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(IDLE_EVICTION_MS)
            evictResources()
        }
    }
}
