package com.projectmika.purrtts.engine.onnx

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxValue
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import ai.onnxruntime.providers.NNAPIFlags
import android.content.Context
import android.util.Log
import com.projectmika.purrtts.core.purr.ArchetypeTone
import com.projectmika.purrtts.core.purr.BackendType
import com.projectmika.purrtts.core.purr.ClonedVoiceProfile
import com.projectmika.purrtts.core.purr.HardwareProfile
import com.projectmika.purrtts.core.purr.HardwareStatusBadge
import com.projectmika.purrtts.core.purr.PurrBpeTokenizer
import com.projectmika.purrtts.core.purr.PurrDspProcessor
import com.projectmika.purrtts.core.purr.PurrHardwareProbe
import com.projectmika.purrtts.core.purr.PurrPhononTokenizer
import com.projectmika.purrtts.core.purr.PurrProfile
import com.projectmika.purrtts.core.purr.PurrSentimentScorer
import com.projectmika.purrtts.engine.PurrSynthesizer
import com.projectmika.purrtts.engine.SynthesisOutcome
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.EnumSet
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.PI
import kotlin.random.Random
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * ✨ MIKA'S MULTI-MODEL NATIVE ONNX RUNTIME ENGINE (v1.7.0) ✨
 * Executes three pure-local neural TTS models directly via `onnxruntime-android:1.31.1`:
 * 1. **Soprano-80M (ROM-06)** — 32kHz autoregressive Qwen3-architecture Vocos TTS
 * 2. **NeuTTS-2E Emotional (ROM-05)** — Qwen3-236M INT8 + NeuCodec INT8 with PURR emotion tags
 * 3. **Chatterbox-Turbo 350M Q4 (SUB-03E)** — Zero-shot voice cloning + `[laugh]`/`[sigh]`/`[gasp]`
 */
class OnnxRuntimeEngine(
    private val context: Context,
    private val clonedVoicesProvider: () -> List<ClonedVoiceProfile>,
    private val smartOffloadProvider: () -> Boolean = { false },
    private val hardwareProfile: HardwareProfile = PurrHardwareProbe.detectDevice(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : PurrSynthesizer {

    companion object {
        private const val TAG = "PurrOnnxNative"
        private const val SAMPLE_RATE_HZ = PurrDspProcessor.SAMPLE_RATE_HZ
        private const val IDLE_EVICTION_MS = 5 * 60 * 1000L

        const val SPEC_CHATTERBOX = "sub_chatterbox_turbo"
        const val SPEC_NEUTTS_2E = "rom_neutts_2e"
        const val SPEC_SOPRANO = "rom_soprano_80m"
        const val SPEC_PHONON_31M = "rom_phonon_31m"
        const val SPEC_PHONON_FP32 = "sub_phonon_fp32"

        private val PHONON_VOICE_FILES = mapOf(
            "phonon_zoey" to "NbpkqMVS3CJeq2j8.bin",
            "phonon_juniper" to "NIWIOv5vTi5eRMj8.bin",
            "phonon_harper" to "4SZHfMpw-p46Ywgs.bin",
            "phonon_everly" to "vjFyp2BcCyTlUmYb.bin",
            "phonon_easton" to "LMDIYE0Oa377Qh6Z.bin",
            "phonon_micah" to "40QrMVgAxFiUnP9f.bin",
            "phonon_miles" to "d9XCyhjo4JhkHF1d.bin",
            "phonon_preston" to "qIxlZ7Ei8KvWlyQH.bin",
            "phonon_maeve" to "6PWnV0Nq4wu7RVBT.bin",
            "phonon_freya" to "GgfEkEJtxZR7gnpy.bin",
            "phonon_alfie" to "X6NceqF285Gr04gb.bin",
            "phonon_hugh" to "YyKcxPG7iIgS3jsK.bin",
            "phonon_silas" to "xPDpsV3F592srkjA.bin",
            "phonon_morwenna" to "nOykTmcH9ydsGGIc.bin",
            "phonon_snowqueen" to "NIWoEurJHMJjyUTe.bin",
            "phonon_aldric" to "gSgG5Tor0GPEnqzC.bin"
        )

        /**
         * Transposes Gradium Phonon `.bin` speaker latents from disk layout `[1, 512, frames]`
         * into the ONNX `audio_prompt_latents` tensor layout `[frames, 512]`.
         */
        fun transposePhononVoiceLatents(raw: FloatArray, voiceDim: Int = 512): FloatArray {
            if (raw.isEmpty() || voiceDim <= 0 || raw.size < voiceDim) return FloatArray(0)
            val frames = raw.size / voiceDim
            val out = FloatArray(frames * voiceDim)
            for (f in 0 until frames) {
                val outBase = f * voiceDim
                for (d in 0 until voiceDim) {
                    out[outBase + d] = raw[d * frames + f]
                }
            }
            return out
        }

        private const val CHATTERBOX_START_TOKEN = 6561L
        private const val CHATTERBOX_STOP_TOKEN = 6562L
        private const val CHATTERBOX_SILENCE_TOKEN = 4299L

        private val SPEECH_TAG_REGEX = Regex("<\\|speech_(\\d+)\\|>")

        /** IEEE-754 binary16 -> binary32 pure-Kotlin conversion (safe on JVM & API 26+). */
        fun fp16BitsToFloat(bits: Short): Float {
            val h = bits.toInt() and 0xFFFF
            val sign = (h and 0x8000) shl 16
            var exp = (h ushr 10) and 0x1F
            var mant = h and 0x03FF
            return when (exp) {
                0 -> {
                    if (mant == 0) {
                        java.lang.Float.intBitsToFloat(sign)
                    } else {
                        exp = 1
                        while ((mant and 0x0400) == 0) {
                            mant = mant shl 1
                            exp -= 1
                        }
                        mant = mant and 0x03FF
                        val fExp = (exp + 127 - 15) shl 23
                        java.lang.Float.intBitsToFloat(sign or fExp or (mant shl 13))
                    }
                }
                31 -> {
                    val fExp = 0xFF shl 23
                    java.lang.Float.intBitsToFloat(sign or fExp or (mant shl 13))
                }
                else -> {
                    val fExp = (exp + 127 - 15) shl 23
                    java.lang.Float.intBitsToFloat(sign or fExp or (mant shl 13))
                }
            }
        }

        /** IEEE-754 binary32 -> binary16 round-to-nearest-even pure-Kotlin conversion. */
        fun floatToFp16Bits(value: Float): Short {
            val bits = java.lang.Float.floatToIntBits(value)
            val sign = (bits ushr 16) and 0x8000
            var valBits = (bits and 0x7FFFFFFF) + 0x1000
            if (valBits >= 0x47800000) {
                return if ((bits and 0x7FFFFFFF) >= 0x47800000) {
                    if (valBits < 0x7F800000) {
                        (sign or 0x7C00).toShort()
                    } else {
                        (sign or 0x7C00 or ((bits and 0x007FFFFF) ushr 13)).toShort()
                    }
                } else {
                    (sign or 0x7BFF).toShort()
                }
            }
            if (valBits >= 0x38800000) {
                return (sign or ((valBits - 0x38000000) ushr 13)).toShort()
            }
            if (valBits < 0x33000000) {
                return sign.toShort()
            }
            valBits = (bits and 0x7FFFFFFF) ushr 23
            val shift = (113 - valBits).coerceIn(1, 24)
            return (sign or (((bits and 0x007FFFFF) or 0x00800000) + (0x800000 ushr shift) ushr (126 - valBits))).toShort()
        }
    }

    private enum class LoadedFamily { NONE, SOPRANO, NEUTTS, CHATTERBOX, PHONON }

    private data class TensorPayload(
        val floats: FloatArray?,
        val longs: LongArray?,
        val ints: IntArray?,
        val shape: LongArray,
        val javaType: OnnxJavaType
    )

    private data class NeuTtsRef(val text: String, val codes: IntArray)

    private data class ChatterboxPromptCache(
        val cacheKey: String,
        val condEmb: TensorPayload,
        val promptToken: LongArray,
        val speakerEmbeddings: TensorPayload,
        val speakerFeatures: TensorPayload
    )

    override val backendType: BackendType = BackendType.ONNX_NATIVE

    private val _statusFlow = MutableStateFlow(HardwareStatusBadge.IDLE)
    override val statusFlow: StateFlow<HardwareStatusBadge> = _statusFlow.asStateFlow()

    private val mutex = Mutex()
    private var idleJob: Job? = null
    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

    private var loadedFamily = LoadedFamily.NONE
    private var activeSmartOffload = false
    private var activeUsedNnapi = false
    private var sopranoBackbone: OrtSession? = null
    private var sopranoDecoder: OrtSession? = null
    private var sopranoTokenizer: PurrBpeTokenizer? = null

    private var neuBackbone: OrtSession? = null
    private var neuCodec: OrtSession? = null
    private var neuTokenizer: PurrBpeTokenizer? = null
    private var neuRefs: Map<String, NeuTtsRef>? = null

    private var cbSpeechEncoder: OrtSession? = null
    private var cbEmbedTokens: OrtSession? = null
    private var cbLanguageModel: OrtSession? = null
    private var cbCondDecoder: OrtSession? = null
    private var cbTokenizer: PurrBpeTokenizer? = null
    private var cbPromptCache: ChatterboxPromptCache? = null

    private var phononFlowModel: OrtSession? = null
    private var phononMimiDecoder: OrtSession? = null
    private var phononTokenizer: PurrPhononTokenizer? = null
    private var phononLoadedFp32: Boolean = false
    private val phononVoiceCache = HashMap<String, FloatArray>()

    private fun nativeDir(specId: String): File = File(context.filesDir, "models/native/$specId")

    private fun isPhononInstalled(): Boolean {
        val baseDir = nativeDir(SPEC_PHONON_31M)
        val fp32Dir = nativeDir(SPEC_PHONON_FP32)
        val hasTokenizer = File(baseDir, "tokenizer.json").exists() || File(fp32Dir, "tokenizer.json").exists()
        val hasQ4 = File(baseDir, "onnx/model_q4.onnx").exists() &&
            File(baseDir, "onnx/mimi_decoder_fp16.onnx").exists()
        val hasFp32 = File(fp32Dir, "onnx/model.onnx").exists() &&
            File(fp32Dir, "onnx/mimi_decoder.onnx").exists()
        return hasTokenizer && (hasQ4 || hasFp32)
    }

    private fun isSopranoInstalled(): Boolean {
        val d = nativeDir(SPEC_SOPRANO)
        return File(d, "soprano_backbone_kv.onnx").exists() &&
            File(d, "soprano_decoder.onnx").exists() &&
            File(d, "tokenizer.json").exists()
    }

    private fun isNeuTtsInstalled(): Boolean {
        val d = nativeDir(SPEC_NEUTTS_2E)
        return File(d, "model_int8.onnx").exists() &&
            File(d, "neucodec_decoder_int8.onnx").exists() &&
            File(d, "tokenizer.json").exists()
    }

    private fun isChatterboxInstalled(): Boolean {
        val d = nativeDir(SPEC_CHATTERBOX)
        return File(d, "language_model_q4.onnx").exists() &&
            File(d, "language_model_q4.onnx_data").exists() &&
            File(d, "embed_tokens_q4.onnx").exists() &&
            File(d, "speech_encoder_q4.onnx").exists() &&
            File(d, "conditional_decoder_q4.onnx").exists() &&
            File(d, "tokenizer.json").exists()
    }

    override val isModelReady: Boolean
        get() = isSopranoInstalled() || isNeuTtsInstalled() || isChatterboxInstalled() || isPhononInstalled()

    override suspend fun warmup(profile: PurrProfile) {
        mutex.withLock {
            val clean = profile.primaryVoiceId.substringAfter(':')
            when {
                clean.startsWith("soprano_") && isSopranoInstalled() -> ensureSopranoLoadedLocked()
                clean.startsWith("neutts_") && isNeuTtsInstalled() -> ensureNeuTtsLoadedLocked()
                clean.startsWith("phonon_") && isPhononInstalled() -> ensurePhononLoadedLocked()
                (clean.startsWith("chatterbox_") || clonedVoicesProvider().any {
                    it.id == clean && it.resolvedCloneEngineId == SPEC_CHATTERBOX
                }) && isChatterboxInstalled() -> ensureChatterboxLoadedLocked()
            }
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
        if (text.isBlank()) {
            return@withContext SynthesisOutcome(FloatArray(0), SAMPLE_RATE_HZ, HardwareStatusBadge.IDLE, "Empty text chunk")
        }

        val cleanVoiceId = voiceId.substringAfter(':')
        val clonedCb = clonedVoicesProvider().firstOrNull {
            it.id == cleanVoiceId && it.resolvedCloneEngineId == SPEC_CHATTERBOX
        }

        mutex.withLock {
            val outcome = runCatching {
                when {
                    cleanVoiceId.startsWith("soprano_") -> synthesizeSopranoLocked(text)
                    cleanVoiceId.startsWith("neutts_") -> synthesizeNeuTtsLocked(text, cleanVoiceId, profile)
                    cleanVoiceId.startsWith("phonon_") -> synthesizePhononLocked(text, cleanVoiceId)
                    clonedCb != null -> synthesizeChatterboxLocked(
                        text = text,
                        refWavFile = File(clonedCb.referenceWavPath),
                        voiceLabel = clonedCb.displayName
                    )
                    cleanVoiceId.startsWith("chatterbox_") -> synthesizeChatterboxLocked(
                        text = text,
                        refWavFile = File(nativeDir(SPEC_CHATTERBOX), "emily_neutral.wav"),
                        voiceLabel = "Emily Studio"
                    )
                    else -> SynthesisOutcome(
                        pcmFloat24k = FloatArray(0),
                        sampleRateHz = SAMPLE_RATE_HZ,
                        hardwareBadge = HardwareStatusBadge.IDLE,
                        engineSummary = "Unknown ONNX_NATIVE voice: $cleanVoiceId",
                        isFallbackNeuralPreview = true
                    )
                }
            }.getOrElse { err ->
                Log.e(TAG, "ONNX native synthesis failure ($cleanVoiceId): ${err.message}", err)
                SynthesisOutcome(
                    pcmFloat24k = FloatArray(0),
                    sampleRateHz = SAMPLE_RATE_HZ,
                    hardwareBadge = HardwareStatusBadge.IDLE,
                    engineSummary = "ONNX runtime error: ${err.message ?: "inference failed"}",
                    isFallbackNeuralPreview = true
                )
            }

            if (outcome.pcmFloat24k.isEmpty()) return@withContext outcome

            val activeBadge = when {
                activeSmartOffload && activeUsedNnapi -> HardwareStatusBadge.NPU_ACTIVE
                else -> HardwareStatusBadge.CPU_NEON
            }
            _statusFlow.value = activeBadge
            var pcm = outcome.pcmFloat24k
            if (speakingRate !in 0.94f..1.06f) {
                pcm = PurrDspProcessor.timeScaleWsola(pcm, speakingRate.coerceIn(0.65f, 1.65f), SAMPLE_RATE_HZ)
            }
            if (pitch !in 0.995f..1.005f) {
                pcm = PurrDspProcessor.shiftPitchCubic(pcm, pitch.coerceIn(0.88f, 1.12f))
            }
            val summary = if (activeSmartOffload) {
                "${outcome.engineSummary} • Smart Offload"
            } else {
                outcome.engineSummary
            }
            outcome.copy(
                pcmFloat24k = pcm,
                hardwareBadge = activeBadge,
                engineSummary = summary
            )
        }
    }

    override suspend fun evictResources() {
        mutex.withLock {
            closeAllSessionsLocked()
            _statusFlow.value = HardwareStatusBadge.IDLE
            Log.i(TAG, "[ONNX_NATIVE_EVICT] Released all ONNX Runtime sessions cleanly.")
        }
    }

    private fun closeAllSessionsLocked() {
        runCatching { sopranoBackbone?.close() }
        runCatching { sopranoDecoder?.close() }
        sopranoBackbone = null
        sopranoDecoder = null
        sopranoTokenizer = null

        runCatching { neuBackbone?.close() }
        runCatching { neuCodec?.close() }
        neuBackbone = null
        neuCodec = null
        neuTokenizer = null

        runCatching { cbSpeechEncoder?.close() }
        runCatching { cbEmbedTokens?.close() }
        runCatching { cbLanguageModel?.close() }
        runCatching { cbCondDecoder?.close() }
        cbSpeechEncoder = null
        cbEmbedTokens = null
        cbLanguageModel = null
        cbCondDecoder = null
        cbTokenizer = null
        cbPromptCache = null

        runCatching { phononFlowModel?.close() }
        runCatching { phononMimiDecoder?.close() }
        phononFlowModel = null
        phononMimiDecoder = null
        phononTokenizer = null
        phononLoadedFp32 = false
        phononVoiceCache.clear()

        loadedFamily = LoadedFamily.NONE
        activeSmartOffload = false
        activeUsedNnapi = false
    }

    private fun buildSessionOptions(
        threads: Int = hardwareProfile.optimalCpuThreads,
        preferNnapi: Boolean = false,
        smartOffload: Boolean = false
    ): OrtSession.SessionOptions {
        return OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(threads)
            setInterOpNumThreads(1)
            if (smartOffload) {
                if (preferNnapi && hardwareProfile.hasNnapi) {
                    runCatching { addNnapi(EnumSet.of(NNAPIFlags.USE_FP16)) }
                }
                runCatching {
                    addXnnpack(mapOf("intra_op_num_threads" to threads.toString()))
                }
            }
        }
    }

    /**
     * Creates an ORT session with optional NNAPI FP16 delegation under Smart Offload, always
     * failing soft to XNNPACK/CPU if a particular graph contains unsupported NNAPI operators.
     */
    private fun createSessionWithFallback(
        modelPath: String,
        threads: Int,
        preferNnapi: Boolean,
        smartOffload: Boolean
    ): Pair<OrtSession, Boolean> {
        if (smartOffload && preferNnapi && hardwareProfile.hasNnapi) {
            val nnapiAttempt = runCatching {
                buildSessionOptions(threads = threads, preferNnapi = true, smartOffload = true).use { opts ->
                    env.createSession(modelPath, opts)
                }
            }
            if (nnapiAttempt.isSuccess) {
                return nnapiAttempt.getOrThrow() to true
            }
            Log.w(TAG, "NNAPI delegation rejected for ${File(modelPath).name}, falling back to XNNPACK/CPU: ${nnapiAttempt.exceptionOrNull()?.message}")
        }
        val cpuSession = buildSessionOptions(threads = threads, preferNnapi = false, smartOffload = smartOffload).use { opts ->
            env.createSession(modelPath, opts)
        }
        return cpuSession to false
    }

    // =========================================================================
    // 1. SOPRANO-80M 32kHz ENGINE (ROM-05)
    // =========================================================================

    private fun ensureSopranoLoadedLocked(): Boolean {
        val smartOffload = smartOffloadProvider()
        if (loadedFamily == LoadedFamily.SOPRANO &&
            activeSmartOffload == smartOffload &&
            sopranoBackbone != null &&
            sopranoDecoder != null
        ) {
            return true
        }
        if (!isSopranoInstalled()) return false
        closeAllSessionsLocked()
        val dir = nativeDir(SPEC_SOPRANO)
        val threads = hardwareProfile.optimalCpuThreads
        val (backboneSession, _) = createSessionWithFallback(
            modelPath = File(dir, "soprano_backbone_kv.onnx").absolutePath,
            threads = threads,
            preferNnapi = false,
            smartOffload = smartOffload
        )
        val (decoderSession, decoderUsedNnapi) = createSessionWithFallback(
            modelPath = File(dir, "soprano_decoder.onnx").absolutePath,
            threads = threads,
            preferNnapi = true,
            smartOffload = smartOffload
        )
        sopranoBackbone = backboneSession
        sopranoDecoder = decoderSession
        sopranoTokenizer = PurrBpeTokenizer.fromFile(File(dir, "tokenizer.json"))
        loadedFamily = LoadedFamily.SOPRANO
        activeSmartOffload = smartOffload
        activeUsedNnapi = decoderUsedNnapi
        Log.i(TAG, "Loaded Soprano-80M ONNX (32kHz, smart=$smartOffload, nnapi=$decoderUsedNnapi) cleanly")
        return true
    }

    private fun synthesizeSopranoLocked(text: String): SynthesisOutcome {
        if (!ensureSopranoLoadedLocked()) {
            return SynthesisOutcome(FloatArray(0), SAMPLE_RATE_HZ, HardwareStatusBadge.IDLE, "Soprano-80M cartridge not installed", true)
        }
        val backbone = sopranoBackbone!!
        val decoder = sopranoDecoder!!
        val tokenizer = sopranoTokenizer!!

        val prompt = "[STOP][TEXT]${text.trim()}[START]"
        val promptIds = tokenizer.encode(prompt)
        if (promptIds.isEmpty()) {
            return SynthesisOutcome(FloatArray(0), SAMPLE_RATE_HZ, HardwareStatusBadge.IDLE, "Soprano tokenization empty", true)
        }

        val eosId = tokenizer.tokenToId("[STOP]") ?: 3
        val backboneInputs = backbone.inputInfo
        val outputNames = backbone.outputNames
        val logitsName = outputNames.firstOrNull { it.contains("logit", ignoreCase = true) } ?: outputNames.first()
        val hiddenName = outputNames.firstOrNull { it.contains("hidden", ignoreCase = true) }
            ?: outputNames.firstOrNull { it != logitsName && !it.startsWith("present.") }
            ?: throw IllegalStateException("Soprano hidden output missing")

        val pastMap = HashMap<String, TensorPayload>()
        for ((name, infoNode) in backboneInputs) {
            if (name.startsWith("past_key_values.")) {
                val info = infoNode.info as TensorInfo
                val dims = info.shape.mapIndexed { idx, d ->
                    when {
                        idx == 0 -> 1L
                        d <= 0L -> if (idx == 2) 0L else if (idx == 1) 1L else 128L
                        else -> d
                    }
                }.toLongArray()
                pastMap[name] = TensorPayload(FloatArray(0), null, null, dims, info.type)
            }
        }

        val rng = Random(42)
        val hiddenFrames = ArrayList<FloatArray>(128)
        val generatedTokenHistory = ArrayList<Int>(128)
        var stepIds = promptIds
        var posStart = 0L
        val maxNewTokens = (text.length * 4 + 90).coerceIn(120, 360)

        for (step in 0 until maxNewTokens) {
            val seqLen = stepIds.size.toLong()
            val feeds = HashMap<String, OnnxTensor>()
            try {
                feeds["input_ids"] = createLongTensor(stepIds, longArrayOf(1L, seqLen))
                if ("attention_mask" in backboneInputs) {
                    val totalLen = posStart + seqLen
                    feeds["attention_mask"] = createLongTensor(LongArray(totalLen.toInt()) { 1L }, longArrayOf(1L, totalLen))
                }
                if ("position_ids" in backboneInputs) {
                    feeds["position_ids"] = createLongTensor(LongArray(stepIds.size) { i -> posStart + i }, longArrayOf(1L, seqLen))
                }
                for ((kName, payload) in pastMap) {
                    feeds[kName] = tensorFromPayload(payload)
                }

                backbone.run(feeds).use { result ->
                    val logitsVal = result.get(logitsName).orElse(null) as OnnxTensor
                    val hiddenVal = result.get(hiddenName).orElse(null) as OnnxTensor

                    val lastLogits = extractLastStepVector(logitsVal)
                    val frameVec = extractLastStepVector(hiddenVal)

                    for (outName in outputNames) {
                        if (outName.startsWith("present.")) {
                            val pastKey = outName.replaceFirst("present.", "past_key_values.")
                            if (pastKey in backboneInputs) {
                                val pTensor = result.get(outName).orElse(null) as? OnnxTensor
                                if (pTensor != null) {
                                    pastMap[pastKey] = copyTensorPayload(pTensor)
                                }
                            }
                        }
                    }

                    // Suppress control tokens [TEXT]=1, [START]=2; suppress [STOP]=3 for first 4 steps
                    if (step < 4 && eosId < lastLogits.size) lastLogits[eosId] = Float.NEGATIVE_INFINITY
                    if (1 < lastLogits.size) lastLogits[1] = Float.NEGATIVE_INFINITY
                    if (2 < lastLogits.size) lastLogits[2] = Float.NEGATIVE_INFINITY

                    val nextId = sampleTopKTopP(
                        logits = lastLogits,
                        history = generatedTokenHistory,
                        repetitionPenalty = 1.12f,
                        temperature = 0.35f,
                        topK = 24,
                        topP = 0.92f,
                        minP = 0.0f,
                        rng = rng
                    )
                    if (nextId == eosId) {
                        // Do not emit EOS hidden state (matches Soprano Python inference)
                        return decodeSopranoFramesLocked(decoder, hiddenFrames)
                    }
                    hiddenFrames.add(frameVec)
                    generatedTokenHistory.add(nextId)
                    posStart += seqLen
                    stepIds = longArrayOf(nextId.toLong())
                }
            } finally {
                feeds.values.forEach { runCatching { it.close() } }
            }
        }

        return decodeSopranoFramesLocked(decoder, hiddenFrames)
    }

    private fun decodeSopranoFramesLocked(decoder: OrtSession, frames: List<FloatArray>): SynthesisOutcome {
        if (frames.isEmpty()) {
            return SynthesisOutcome(FloatArray(0), SAMPLE_RATE_HZ, HardwareStatusBadge.IDLE, "Soprano emitted 0 frames", true)
        }
        val tCount = frames.size
        val hDim = frames[0].size
        // Decoder expects [1, hidden_dim (512), T]
        val transposed = FloatArray(hDim * tCount)
        for (c in 0 until hDim) {
            val rowOffset = c * tCount
            for (t in 0 until tCount) {
                transposed[rowOffset + t] = frames[t][c]
            }
        }
        val inputName = decoder.inputNames.firstOrNull { it.contains("hidden", ignoreCase = true) }
            ?: decoder.inputNames.first()
        val inputType = (decoder.inputInfo[inputName]?.info as? TensorInfo)?.type ?: OnnxJavaType.FLOAT
        val inTensor = createFloatTensorMatching(transposed, longArrayOf(1L, hDim.toLong(), tCount.toLong()), inputType)
        try {
            decoder.run(mapOf(inputName to inTensor)).use { out ->
                val rawAudio = extractFloatArray(out.get(0) as OnnxTensor)
                val resampled = PurrDspProcessor.resampleMono(rawAudio, 32000, SAMPLE_RATE_HZ)
                return SynthesisOutcome(
                    pcmFloat24k = resampled,
                    sampleRateHz = SAMPLE_RATE_HZ,
                    hardwareBadge = HardwareStatusBadge.CPU_NEON,
                    engineSummary = "Soprano-80M ONNX (32kHz -> 24kHz, ${tCount}f)"
                )
            }
        } finally {
            runCatching { inTensor.close() }
        }
    }

    // =========================================================================
    // 2. NEUTTS-2E EMOTIONAL ENGINE (ROM-05)
    // =========================================================================

    private fun ensureNeuTtsLoadedLocked(): Boolean {
        val smartOffload = smartOffloadProvider()
        if (loadedFamily == LoadedFamily.NEUTTS &&
            activeSmartOffload == smartOffload &&
            neuBackbone != null &&
            neuCodec != null
        ) {
            return true
        }
        if (!isNeuTtsInstalled()) return false
        closeAllSessionsLocked()
        val dir = nativeDir(SPEC_NEUTTS_2E)
        // NeuTTS-2E benchmark proves 4 intra-op threads is peak throughput on CPU EP
        val threads = hardwareProfile.optimalCpuThreads.coerceAtMost(4)
        val (backboneSession, _) = createSessionWithFallback(
            modelPath = File(dir, "model_int8.onnx").absolutePath,
            threads = threads,
            preferNnapi = false,
            smartOffload = smartOffload
        )
        val (codecSession, codecUsedNnapi) = createSessionWithFallback(
            modelPath = File(dir, "neucodec_decoder_int8.onnx").absolutePath,
            threads = threads,
            preferNnapi = true,
            smartOffload = smartOffload
        )
        neuBackbone = backboneSession
        neuCodec = codecSession
        neuTokenizer = PurrBpeTokenizer.fromFile(File(dir, "tokenizer.json"))
        if (neuRefs == null) {
            neuRefs = loadNeuTtsRefsFromAssets()
        }
        loadedFamily = LoadedFamily.NEUTTS
        activeSmartOffload = smartOffload
        activeUsedNnapi = codecUsedNnapi
        Log.i(TAG, "Loaded NeuTTS-2E INT8 + NeuCodec INT8 (smart=$smartOffload, nnapi=$codecUsedNnapi) cleanly")
        return true
    }

    private fun loadNeuTtsRefsFromAssets(): Map<String, NeuTtsRef> {
        val raw = context.assets.open("neutts_2e_refs.json").bufferedReader().use { it.readText() }
        val root = Json.parseToJsonElement(raw).jsonObject
        val out = LinkedHashMap<String, NeuTtsRef>(root.size)
        for ((key, el) in root) {
            val obj = el.jsonObject
            val refText = obj["text"]?.jsonPrimitive?.content.orEmpty()
            val arr = obj["codes"]?.jsonArray ?: continue
            val codes = IntArray(arr.size) { idx -> arr[idx].jsonPrimitive.int }
            out[key] = NeuTtsRef(refText, codes)
        }
        return out
    }

    private fun resolveNeuTtsEmotionTag(text: String, profile: PurrProfile): String? {
        if (!profile.sentimentConfig.enabled) return null
        val tone = profile.sentimentConfig.toneOverride
        if (tone == ArchetypeTone.NORMAL) return null
        val sentiment = PurrSentimentScorer.score(text, tone)
        return when (sentiment.dominantLabel) {
            "ANGER" -> "<|ANGRY|>"
            "SADNESS" -> "<|SAD|>"
            "JOY", "LOVE" -> "<|HAPPY|>"
            "FLUSTERED" -> "<|SURPRISED|>"
            else -> null
        }
    }

    private fun synthesizeNeuTtsLocked(
        text: String,
        cleanVoiceId: String,
        profile: PurrProfile
    ): SynthesisOutcome {
        if (!ensureNeuTtsLoadedLocked()) {
            return SynthesisOutcome(FloatArray(0), SAMPLE_RATE_HZ, HardwareStatusBadge.IDLE, "NeuTTS-2E cartridge not installed", true)
        }
        val backbone = neuBackbone!!
        val codec = neuCodec!!
        val tokenizer = neuTokenizer!!

        val speakerKey = cleanVoiceId.removePrefix("neutts_").lowercase()
        val ref = neuRefs?.get(speakerKey) ?: neuRefs?.values?.firstOrNull()
            ?: return SynthesisOutcome(FloatArray(0), SAMPLE_RATE_HZ, HardwareStatusBadge.IDLE, "NeuTTS-2E speaker ref missing", true)

        val textPromptStart = tokenizer.tokenToId("<|TEXT_PROMPT_START|>")
            ?: return SynthesisOutcome(FloatArray(0), SAMPLE_RATE_HZ, HardwareStatusBadge.IDLE, "NeuTTS tokenizer missing start control", true)
        val textPromptEnd = tokenizer.tokenToId("<|TEXT_PROMPT_END|>") ?: (textPromptStart + 1)
        val speechStart = tokenizer.tokenToId("<|SPEECH_GENERATION_START|>") ?: (textPromptEnd + 1)
        val speechEnd = tokenizer.tokenToId("<|SPEECH_GENERATION_END|>") ?: (speechStart + 1)

        val normRef = ref.text.replace('“', '"').replace('”', '"').replace('’', '\'').replace('‘', '\'')
        val normInput = text.trim().replace('“', '"').replace('”', '"').replace('’', '\'').replace('‘', '\'')
        val emotionTag = resolveNeuTtsEmotionTag(normInput, profile)
        val emotionId = emotionTag?.let { tokenizer.tokenToId(it) }

        val textIds: LongArray = if (emotionId == null) {
            tokenizer.encode("$normRef $normInput", addSpecialControlScan = false)
        } else {
            val left = tokenizer.encode(normRef, addSpecialControlScan = false)
            val right = tokenizer.encode(normInput, addSpecialControlScan = false)
            LongArray(left.size + 1 + right.size).also { arr ->
                left.copyInto(arr, 0)
                arr[left.size] = emotionId.toLong()
                right.copyInto(arr, left.size + 1)
            }
        }

        val refCodeList = ArrayList<Long>(ref.codes.size)
        for (c in ref.codes) {
            tokenizer.tokenToId("<|speech_$c|>")?.let { refCodeList.add(it.toLong()) }
        }
        val refCodeIds = refCodeList.toLongArray()

        val promptIds = LongArray(3 + textIds.size + refCodeIds.size)
        var p = 0
        promptIds[p++] = textPromptStart.toLong()
        textIds.copyInto(promptIds, p); p += textIds.size
        promptIds[p++] = textPromptEnd.toLong()
        promptIds[p++] = speechStart.toLong()
        refCodeIds.copyInto(promptIds, p)

        val backboneInputs = backbone.inputInfo
        val outputNames = backbone.outputNames
        val logitsName = outputNames.firstOrNull { it.contains("logit", ignoreCase = true) } ?: outputNames.first()

        val pastMap = HashMap<String, TensorPayload>()
        for ((name, infoNode) in backboneInputs) {
            if (name.startsWith("past_key_values.")) {
                val info = infoNode.info as TensorInfo
                val dims = info.shape.mapIndexed { idx, d ->
                    when {
                        idx == 0 -> 1L
                        idx == 2 -> 0L
                        d <= 0L -> if (idx == 1) 4L else 128L
                        else -> d
                    }
                }.toLongArray()
                pastMap[name] = TensorPayload(FloatArray(0), null, null, dims, info.type)
            }
        }

        val rng = Random(42)
        val generated = ArrayList<Int>(256)
        var stepIds = promptIds
        var posStart = 0L
        val minNewTokens = 25
        val maxNewTokens = (normInput.length * 5 + 140).coerceIn(150, 480)

        for (step in 0 until maxNewTokens) {
            val seqLen = stepIds.size.toLong()
            val feeds = HashMap<String, OnnxTensor>()
            try {
                feeds["input_ids"] = createLongTensor(stepIds, longArrayOf(1L, seqLen))
                if ("attention_mask" in backboneInputs) {
                    val totalLen = posStart + seqLen
                    feeds["attention_mask"] = createLongTensor(LongArray(totalLen.toInt()) { 1L }, longArrayOf(1L, totalLen))
                }
                if ("position_ids" in backboneInputs) {
                    feeds["position_ids"] = createLongTensor(LongArray(stepIds.size) { i -> posStart + i }, longArrayOf(1L, seqLen))
                }
                for ((kName, payload) in pastMap) {
                    feeds[kName] = tensorFromPayload(payload)
                }

                backbone.run(feeds).use { result ->
                    val logitsVal = result.get(logitsName).orElse(null) as OnnxTensor
                    val lastLogits = extractLastStepVector(logitsVal)

                    if (generated.size < minNewTokens && speechEnd < lastLogits.size) {
                        lastLogits[speechEnd] = Float.NEGATIVE_INFINITY
                    }

                    for (outName in outputNames) {
                        if (outName.startsWith("present.")) {
                            val pastKey = outName.replaceFirst("present.", "past_key_values.")
                            if (pastKey in backboneInputs) {
                                val pTensor = result.get(outName).orElse(null) as? OnnxTensor
                                if (pTensor != null) {
                                    pastMap[pastKey] = copyTensorPayload(pTensor)
                                }
                            }
                        }
                    }

                    val nextId = sampleTopKTopP(
                        logits = lastLogits,
                        history = emptyList(),
                        repetitionPenalty = 1.0f,
                        temperature = 0.90f,
                        topK = 50,
                        topP = 0.95f,
                        minP = 0.05f,
                        rng = rng
                    )
                    if (nextId == speechEnd) {
                        return decodeNeuCodecLocked(codec, tokenizer, generated, speakerKey, emotionTag)
                    }
                    generated.add(nextId)
                    posStart += seqLen
                    stepIds = longArrayOf(nextId.toLong())
                }
            } finally {
                feeds.values.forEach { runCatching { it.close() } }
            }
        }

        return decodeNeuCodecLocked(codec, tokenizer, generated, speakerKey, emotionTag)
    }

    private fun decodeNeuCodecLocked(
        codec: OrtSession,
        tokenizer: PurrBpeTokenizer,
        generatedIds: List<Int>,
        speakerKey: String,
        emotionTag: String?
    ): SynthesisOutcome {
        if (generatedIds.isEmpty()) {
            return SynthesisOutcome(FloatArray(0), SAMPLE_RATE_HZ, HardwareStatusBadge.IDLE, "NeuTTS-2E generated 0 tokens", true)
        }
        val decodedText = tokenizer.decodeToRawControlText(generatedIds)
        val speechCodes = SPEECH_TAG_REGEX.findAll(decodedText)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .toList()
        if (speechCodes.isEmpty()) {
            return SynthesisOutcome(FloatArray(0), SAMPLE_RATE_HZ, HardwareStatusBadge.IDLE, "NeuTTS-2E emitted 0 speech codes", true)
        }

        val inputName = codec.inputNames.firstOrNull { it.contains("code", ignoreCase = true) } ?: codec.inputNames.first()
        val expectedType = (codec.inputInfo[inputName]?.info as? TensorInfo)?.type ?: OnnxJavaType.INT32
        val shape = longArrayOf(1L, 1L, speechCodes.size.toLong())
        val codesTensor = if (expectedType == OnnxJavaType.INT64) {
            createLongTensor(LongArray(speechCodes.size) { speechCodes[it].toLong() }, shape)
        } else {
            createIntTensor(IntArray(speechCodes.size) { speechCodes[it] }, shape)
        }
        try {
            codec.run(mapOf(inputName to codesTensor)).use { result ->
                val audio = extractFloatArray(result.get(0) as OnnxTensor)
                val emotionLabel = emotionTag?.removePrefix("<|")?.removeSuffix("|>") ?: "NEUTRAL"
                return SynthesisOutcome(
                    pcmFloat24k = audio,
                    sampleRateHz = SAMPLE_RATE_HZ,
                    hardwareBadge = HardwareStatusBadge.CPU_NEON,
                    engineSummary = "NeuTTS-2E • ${speakerKey.replaceFirstChar { it.uppercase() }} [$emotionLabel]"
                )
            }
        } finally {
            runCatching { codesTensor.close() }
        }
    }

    // =========================================================================
    // 3. CHATTERBOX-TURBO 350M Q4 ENGINE (SUB-03E)
    // =========================================================================

    private fun ensureChatterboxLoadedLocked(): Boolean {
        val smartOffload = smartOffloadProvider()
        if (loadedFamily == LoadedFamily.CHATTERBOX &&
            activeSmartOffload == smartOffload &&
            cbSpeechEncoder != null &&
            cbEmbedTokens != null &&
            cbLanguageModel != null &&
            cbCondDecoder != null
        ) {
            return true
        }
        if (!isChatterboxInstalled()) return false
        closeAllSessionsLocked()
        val dir = nativeDir(SPEC_CHATTERBOX)
        val threads = hardwareProfile.optimalCpuThreads.coerceAtMost(4)
        val (encSession, encNnapi) = createSessionWithFallback(
            modelPath = File(dir, "speech_encoder_q4.onnx").absolutePath,
            threads = threads,
            preferNnapi = true,
            smartOffload = smartOffload
        )
        val (embSession, _) = createSessionWithFallback(
            modelPath = File(dir, "embed_tokens_q4.onnx").absolutePath,
            threads = threads,
            preferNnapi = false,
            smartOffload = smartOffload
        )
        val (lmSession, _) = createSessionWithFallback(
            modelPath = File(dir, "language_model_q4.onnx").absolutePath,
            threads = threads,
            preferNnapi = false,
            smartOffload = smartOffload
        )
        val (decSession, decNnapi) = createSessionWithFallback(
            modelPath = File(dir, "conditional_decoder_q4.onnx").absolutePath,
            threads = threads,
            preferNnapi = true,
            smartOffload = smartOffload
        )
        cbSpeechEncoder = encSession
        cbEmbedTokens = embSession
        cbLanguageModel = lmSession
        cbCondDecoder = decSession
        cbTokenizer = PurrBpeTokenizer.fromFile(File(dir, "tokenizer.json"))
        loadedFamily = LoadedFamily.CHATTERBOX
        activeSmartOffload = smartOffload
        activeUsedNnapi = encNnapi || decNnapi
        Log.i(TAG, "Loaded Chatterbox-Turbo 350M Q4 (4 ONNX graphs, smart=$smartOffload, nnapi=$activeUsedNnapi) cleanly")
        return true
    }

    private fun getOrComputeChatterboxPromptLocked(refWavFile: File): ChatterboxPromptCache? {
        if (!refWavFile.exists() || refWavFile.length() <= 44L) return null
        val key = "${refWavFile.absolutePath}:${refWavFile.lastModified()}:${refWavFile.length()}"
        cbPromptCache?.let { if (it.cacheKey == key) return it }

        val refAudio = loadWavMonoFloat24k(refWavFile)
        if (refAudio.isEmpty()) return null

        val encoder = cbSpeechEncoder!!
        val inName = encoder.inputNames.firstOrNull { it.contains("audio", ignoreCase = true) } ?: encoder.inputNames.first()
        val inType = (encoder.inputInfo[inName]?.info as? TensorInfo)?.type ?: OnnxJavaType.FLOAT
        val inTensor = createFloatTensorMatching(refAudio, longArrayOf(1L, refAudio.size.toLong()), inType)

        try {
            encoder.run(mapOf(inName to inTensor)).use { result ->
                val outNames = encoder.outputNames.toList()
                val condIdx = outNames.indexOfFirst { it.contains("cond", ignoreCase = true) }.let { if (it >= 0) it else 0 }
                val promptIdx = outNames.indexOfFirst { it.contains("prompt", ignoreCase = true) }.let { if (it >= 0) it else 1 }
                val embIdx = outNames.indexOfFirst { it.contains("speaker_emb", ignoreCase = true) }.let { if (it >= 0) it else 2 }
                val featIdx = outNames.indexOfFirst { it.contains("speaker_feat", ignoreCase = true) }.let { if (it >= 0) it else 3 }

                val condEmb = copyTensorPayload(result.get(condIdx) as OnnxTensor)
                val promptPayload = copyTensorPayload(result.get(promptIdx) as OnnxTensor)
                val speakerEmb = copyTensorPayload(result.get(embIdx) as OnnxTensor)
                val speakerFeat = copyTensorPayload(result.get(featIdx) as OnnxTensor)

                val promptTokens = promptPayload.longs
                    ?: promptPayload.ints?.let { arr -> LongArray(arr.size) { arr[it].toLong() } }
                    ?: LongArray(0)

                val computed = ChatterboxPromptCache(
                    cacheKey = key,
                    condEmb = condEmb,
                    promptToken = promptTokens,
                    speakerEmbeddings = speakerEmb,
                    speakerFeatures = speakerFeat
                )
                cbPromptCache = computed
                return computed
            }
        } finally {
            runCatching { inTensor.close() }
        }
    }

    private fun synthesizeChatterboxLocked(
        text: String,
        refWavFile: File,
        voiceLabel: String
    ): SynthesisOutcome {
        if (!ensureChatterboxLoadedLocked()) {
            return SynthesisOutcome(FloatArray(0), SAMPLE_RATE_HZ, HardwareStatusBadge.IDLE, "Chatterbox-Turbo cartridge not installed", true)
        }
        val promptCache = getOrComputeChatterboxPromptLocked(refWavFile)
            ?: return SynthesisOutcome(FloatArray(0), SAMPLE_RATE_HZ, HardwareStatusBadge.IDLE, "Chatterbox reference WAV missing (${refWavFile.name})", true)

        val embedTokens = cbEmbedTokens!!
        val lm = cbLanguageModel!!
        val condDecoder = cbCondDecoder!!
        val tokenizer = cbTokenizer!!

        val textIds = tokenizer.encode(text.trim(), addSpecialControlScan = true)
        if (textIds.isEmpty()) {
            return SynthesisOutcome(FloatArray(0), SAMPLE_RATE_HZ, HardwareStatusBadge.IDLE, "Chatterbox empty text tokens", true)
        }

        val embedInName = embedTokens.inputNames.firstOrNull { it.contains("input", ignoreCase = true) } ?: embedTokens.inputNames.first()
        val lmInputs = lm.inputInfo
        val lmOutNames = lm.outputNames
        val logitsName = lmOutNames.firstOrNull { it.contains("logit", ignoreCase = true) } ?: lmOutNames.first()
        val targetEmbType = (lmInputs["inputs_embeds"]?.info as? TensorInfo)?.type ?: promptCache.condEmb.javaType

        val pastMap = HashMap<String, TensorPayload>()
        for ((name, infoNode) in lmInputs) {
            if (name.startsWith("past_key_values.")) {
                val info = infoNode.info as TensorInfo
                val dims = info.shape.mapIndexed { idx, d ->
                    when {
                        idx == 0 -> 1L
                        idx == 2 -> 0L
                        d <= 0L -> if (idx == 1) 16L else 64L
                        else -> d
                    }
                }.toLongArray()
                pastMap[name] = TensorPayload(FloatArray(0), null, null, dims, info.type)
            }
        }

        val generatedSpeechTokens = ArrayList<Long>(256)
        val penaltyTokens = HashSet<Int>()
        var currentInputIds = textIds
        var posStart = 0L
        val maxNewTokens = (text.length * 6 + 150).coerceIn(160, 650)

        for (step in 0 until maxNewTokens) {
            var stepEmbedPayload = runChatterboxEmbed(embedTokens, embedInName, currentInputIds)
            if (step == 0) {
                // Concatenate cond_emb [1, C, H] + text_embeds [1, L, H] along axis 1
                val condFloats = promptCache.condEmb.floats ?: FloatArray(0)
                val textFloats = stepEmbedPayload.floats ?: FloatArray(0)
                val hiddenDim = stepEmbedPayload.shape.last().toInt().coerceAtLeast(1)
                val totalFrames = ((condFloats.size + textFloats.size) / hiddenDim).toLong()
                val merged = FloatArray(condFloats.size + textFloats.size)
                condFloats.copyInto(merged, 0)
                textFloats.copyInto(merged, condFloats.size)
                stepEmbedPayload = TensorPayload(merged, null, null, longArrayOf(1L, totalFrames, hiddenDim.toLong()), targetEmbType)
            }

            val seqLen = stepEmbedPayload.shape[1]
            val feeds = HashMap<String, OnnxTensor>()
            try {
                feeds["inputs_embeds"] = createFloatTensorMatching(stepEmbedPayload.floats!!, stepEmbedPayload.shape, targetEmbType)
                if ("attention_mask" in lmInputs) {
                    val totalLen = posStart + seqLen
                    feeds["attention_mask"] = createLongTensor(LongArray(totalLen.toInt()) { 1L }, longArrayOf(1L, totalLen))
                }
                if ("position_ids" in lmInputs) {
                    feeds["position_ids"] = createLongTensor(LongArray(seqLen.toInt()) { i -> posStart + i }, longArrayOf(1L, seqLen))
                }
                for ((kName, payload) in pastMap) {
                    feeds[kName] = tensorFromPayload(payload)
                }

                lm.run(feeds).use { result ->
                    val logitsVal = result.get(logitsName).orElse(null) as OnnxTensor
                    val lastLogits = extractLastStepVector(logitsVal)

                    // Apply repetition_penalty = 1.2f matching ResembleAI reference script
                    for (prevId in penaltyTokens) {
                        if (prevId in lastLogits.indices) {
                            val s = lastLogits[prevId]
                            lastLogits[prevId] = if (s < 0f) s * 1.2f else s / 1.2f
                        }
                    }

                    // Greedy argmax (matching Chatterbox-Turbo ONNX official script)
                    var bestIdx = 0
                    var bestVal = Float.NEGATIVE_INFINITY
                    for (v in lastLogits.indices) {
                        if (lastLogits[v] > bestVal) {
                            bestVal = lastLogits[v]
                            bestIdx = v
                        }
                    }

                    if (bestIdx.toLong() == CHATTERBOX_STOP_TOKEN) {
                        return decodeChatterboxAudioLocked(
                            condDecoder = condDecoder,
                            promptCache = promptCache,
                            generatedSpeechTokens = generatedSpeechTokens,
                            voiceLabel = voiceLabel
                        )
                    }

                    for (outName in lmOutNames) {
                        if (outName.startsWith("present.")) {
                            val pastKey = outName.replaceFirst("present.", "past_key_values.")
                            if (pastKey in lmInputs) {
                                val pTensor = result.get(outName).orElse(null) as? OnnxTensor
                                if (pTensor != null) {
                                    pastMap[pastKey] = copyTensorPayload(pTensor)
                                }
                            }
                        }
                    }

                    generatedSpeechTokens.add(bestIdx.toLong())
                    penaltyTokens.add(bestIdx)
                    posStart += seqLen
                    currentInputIds = longArrayOf(bestIdx.toLong())
                }
            } finally {
                feeds.values.forEach { runCatching { it.close() } }
            }
        }

        return decodeChatterboxAudioLocked(condDecoder, promptCache, generatedSpeechTokens, voiceLabel)
    }

    private fun runChatterboxEmbed(embedTokens: OrtSession, inputName: String, ids: LongArray): TensorPayload {
        val inTensor = createLongTensor(ids, longArrayOf(1L, ids.size.toLong()))
        try {
            embedTokens.run(mapOf(inputName to inTensor)).use { result ->
                return copyTensorPayload(result.get(0) as OnnxTensor)
            }
        } finally {
            runCatching { inTensor.close() }
        }
    }

    private fun decodeChatterboxAudioLocked(
        condDecoder: OrtSession,
        promptCache: ChatterboxPromptCache,
        generatedSpeechTokens: List<Long>,
        voiceLabel: String
    ): SynthesisOutcome {
        if (generatedSpeechTokens.isEmpty()) {
            return SynthesisOutcome(FloatArray(0), SAMPLE_RATE_HZ, HardwareStatusBadge.IDLE, "Chatterbox emitted 0 speech tokens", true)
        }
        val promptToks = promptCache.promptToken
        val totalLen = promptToks.size + generatedSpeechTokens.size + 3
        val speechTokens = LongArray(totalLen)
        promptToks.copyInto(speechTokens, 0)
        for (i in generatedSpeechTokens.indices) {
            speechTokens[promptToks.size + i] = generatedSpeechTokens[i]
        }
        speechTokens[totalLen - 3] = CHATTERBOX_SILENCE_TOKEN
        speechTokens[totalLen - 2] = CHATTERBOX_SILENCE_TOKEN
        speechTokens[totalLen - 1] = CHATTERBOX_SILENCE_TOKEN

        val decoderInfo = condDecoder.inputInfo
        val feeds = HashMap<String, OnnxTensor>()
        try {
            val speechTokType = (decoderInfo["speech_tokens"]?.info as? TensorInfo)?.type ?: OnnxJavaType.INT64
            feeds["speech_tokens"] = if (speechTokType == OnnxJavaType.INT32) {
                createIntTensor(IntArray(speechTokens.size) { speechTokens[it].toInt() }, longArrayOf(1L, totalLen.toLong()))
            } else {
                createLongTensor(speechTokens, longArrayOf(1L, totalLen.toLong()))
            }

            val embKey = condDecoder.inputNames.firstOrNull { it.contains("speaker_emb", ignoreCase = true) } ?: "speaker_embeddings"
            val embType = (decoderInfo[embKey]?.info as? TensorInfo)?.type ?: promptCache.speakerEmbeddings.javaType
            feeds[embKey] = createFloatTensorMatching(
                promptCache.speakerEmbeddings.floats ?: FloatArray(0),
                promptCache.speakerEmbeddings.shape,
                embType
            )

            val featKey = condDecoder.inputNames.firstOrNull { it.contains("speaker_feat", ignoreCase = true) } ?: "speaker_features"
            val featType = (decoderInfo[featKey]?.info as? TensorInfo)?.type ?: promptCache.speakerFeatures.javaType
            feeds[featKey] = createFloatTensorMatching(
                promptCache.speakerFeatures.floats ?: FloatArray(0),
                promptCache.speakerFeatures.shape,
                featType
            )

            condDecoder.run(feeds).use { res ->
                val wav = extractFloatArray(res.get(0) as OnnxTensor)
                return SynthesisOutcome(
                    pcmFloat24k = wav,
                    sampleRateHz = SAMPLE_RATE_HZ,
                    hardwareBadge = HardwareStatusBadge.CPU_NEON,
                    engineSummary = "Chatterbox-Turbo Q4 • $voiceLabel"
                )
            }
        } finally {
            feeds.values.forEach { runCatching { it.close() } }
        }
    }

    // =========================================================================
    // SHARED SAMPLING & ORT DIRECT-BUFFER HELPERS
    // =========================================================================

    private fun sampleTopKTopP(
        logits: FloatArray,
        history: List<Int>,
        repetitionPenalty: Float,
        temperature: Float,
        topK: Int,
        topP: Float,
        minP: Float,
        rng: Random
    ): Int {
        if (repetitionPenalty != 1.0f && history.isNotEmpty()) {
            for (tok in history) {
                if (tok in logits.indices) {
                    val s = logits[tok]
                    logits[tok] = if (s < 0f) s * repetitionPenalty else s / repetitionPenalty
                }
            }
        }

        val invTemp = 1f / temperature.coerceAtLeast(0.05f)
        val k = topK.coerceIn(1, logits.size)
        // Track top-k indices cleanly via insertion into fixed small buffer
        val topIdx = IntArray(k) { -1 }
        val topVals = FloatArray(k) { Float.NEGATIVE_INFINITY }

        for (i in logits.indices) {
            val v = logits[i] * invTemp
            if (!v.isFinite()) continue
            if (v > topVals[k - 1]) {
                var pos = k - 1
                while (pos > 0 && v > topVals[pos - 1]) {
                    topVals[pos] = topVals[pos - 1]
                    topIdx[pos] = topIdx[pos - 1]
                    pos--
                }
                topVals[pos] = v
                topIdx[pos] = i
            }
        }

        val validCount = topIdx.count { it >= 0 }
        if (validCount == 0) return 0
        val maxVal = topVals[0]
        val probs = FloatArray(validCount)
        var sum = 0f
        for (i in 0 until validCount) {
            val p = exp((topVals[i] - maxVal).toDouble()).toFloat()
            probs[i] = p
            sum += p
        }
        if (sum <= 0f) return topIdx[0]
        for (i in probs.indices) probs[i] /= sum

        var cutoff = validCount
        if (topP < 1.0f) {
            var cum = 0f
            for (i in 0 until validCount) {
                cum += probs[i]
                if (cum >= topP) {
                    cutoff = i + 1
                    break
                }
            }
        }
        if (minP > 0f) {
            val thresh = minP * probs[0]
            var minPCut = 1
            for (i in 1 until cutoff) {
                if (probs[i] < thresh) break
                minPCut = i + 1
            }
            cutoff = minPCut
        }

        var subSum = 0f
        for (i in 0 until cutoff) subSum += probs[i]
        val r = rng.nextFloat() * subSum
        var acc = 0f
        for (i in 0 until cutoff) {
            acc += probs[i]
            if (acc >= r) return topIdx[i]
        }
        return topIdx[0]
    }

    private fun createLongTensor(data: LongArray, shape: LongArray): OnnxTensor {
        val bb = ByteBuffer.allocateDirect(data.size * 8).order(ByteOrder.nativeOrder())
        val lb = bb.asLongBuffer()
        lb.put(data)
        lb.rewind()
        return OnnxTensor.createTensor(env, lb, shape)
    }

    private fun createIntTensor(data: IntArray, shape: LongArray): OnnxTensor {
        val bb = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder())
        val ib = bb.asIntBuffer()
        ib.put(data)
        ib.rewind()
        return OnnxTensor.createTensor(env, ib, shape)
    }

    private fun createFloatTensorMatching(data: FloatArray, shape: LongArray, targetType: OnnxJavaType): OnnxTensor {
        return when (targetType) {
            OnnxJavaType.FLOAT16 -> {
                val bb = ByteBuffer.allocateDirect(data.size * 2).order(ByteOrder.nativeOrder())
                val sb = bb.asShortBuffer()
                for (f in data) sb.put(floatToFp16Bits(f))
                sb.rewind()
                OnnxTensor.createTensor(env, sb, shape, OnnxJavaType.FLOAT16)
            }
            OnnxJavaType.BFLOAT16 -> {
                val bb = ByteBuffer.allocateDirect(data.size * 2).order(ByteOrder.nativeOrder())
                val sb = bb.asShortBuffer()
                for (f in data) sb.put((java.lang.Float.floatToRawIntBits(f) ushr 16).toShort())
                sb.rewind()
                OnnxTensor.createTensor(env, sb, shape, OnnxJavaType.BFLOAT16)
            }
            else -> {
                val bb = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder())
                val fb = bb.asFloatBuffer()
                fb.put(data)
                fb.rewind()
                OnnxTensor.createTensor(env, fb, shape)
            }
        }
    }

    private fun tensorFromPayload(payload: TensorPayload): OnnxTensor {
        return when {
            payload.floats != null -> createFloatTensorMatching(payload.floats, payload.shape, payload.javaType)
            payload.longs != null -> createLongTensor(payload.longs, payload.shape)
            payload.ints != null -> createIntTensor(payload.ints, payload.shape)
            else -> createFloatTensorMatching(FloatArray(0), payload.shape, payload.javaType)
        }
    }

    private fun copyTensorPayload(tensor: OnnxTensor): TensorPayload {
        val info = tensor.info
        val shape = info.shape.copyOf()
        val count = shape.fold(1L) { acc, v -> acc * v.coerceAtLeast(0L) }.toInt()
        return when (info.type) {
            OnnxJavaType.FLOAT -> {
                val out = FloatArray(count)
                tensor.floatBuffer?.let { it.rewind(); it.get(out) }
                TensorPayload(out, null, null, shape, OnnxJavaType.FLOAT)
            }
            OnnxJavaType.FLOAT16 -> {
                val tmp = ShortArray(count)
                tensor.shortBuffer?.let { it.rewind(); it.get(tmp) }
                val out = FloatArray(count) { fp16BitsToFloat(tmp[it]) }
                TensorPayload(out, null, null, shape, OnnxJavaType.FLOAT16)
            }
            OnnxJavaType.BFLOAT16 -> {
                val tmp = ShortArray(count)
                tensor.shortBuffer?.let { it.rewind(); it.get(tmp) }
                val out = FloatArray(count) { java.lang.Float.intBitsToFloat(tmp[it].toInt() shl 16) }
                TensorPayload(out, null, null, shape, OnnxJavaType.BFLOAT16)
            }
            OnnxJavaType.INT64 -> {
                val out = LongArray(count)
                tensor.longBuffer?.let { it.rewind(); it.get(out) }
                TensorPayload(null, out, null, shape, OnnxJavaType.INT64)
            }
            OnnxJavaType.INT32 -> {
                val out = IntArray(count)
                tensor.intBuffer?.let { it.rewind(); it.get(out) }
                TensorPayload(null, null, out, shape, OnnxJavaType.INT32)
            }
            else -> TensorPayload(FloatArray(0), null, null, shape, info.type)
        }
    }

    private fun extractFloatArray(value: OnnxValue): FloatArray {
        val tensor = value as? OnnxTensor ?: return FloatArray(0)
        return copyTensorPayload(tensor).floats ?: FloatArray(0)
    }

    /**
     * Reads ONLY the final sequence step `[dim]` directly from an ORT `[B, seq, dim]` buffer
     * (FLOAT / FLOAT16 / BFLOAT16) without allocating the full `[seq * dim]` matrix on heap.
     * Prevents Android OOM on large vocabularies such as NeuTTS-2E's 217,226-token head.
     */
    private fun extractLastStepVector(tensor: OnnxTensor): FloatArray {
        val info = tensor.info
        val shape = info.shape
        if (shape.isEmpty()) return FloatArray(0)
        val dim = shape.last().toInt().coerceAtLeast(0)
        if (dim == 0) return FloatArray(0)
        val seqLen = if (shape.size >= 2) shape[shape.size - 2].toInt().coerceAtLeast(1) else 1
        val startOffset = (seqLen - 1) * dim

        return when (info.type) {
            OnnxJavaType.FLOAT -> {
                val out = FloatArray(dim)
                val fb = tensor.floatBuffer
                if (fb != null) {
                    fb.position(startOffset)
                    fb.get(out, 0, dim)
                }
                out
            }
            OnnxJavaType.FLOAT16 -> {
                val tmp = ShortArray(dim)
                val sb = tensor.shortBuffer
                if (sb != null) {
                    sb.position(startOffset)
                    sb.get(tmp, 0, dim)
                }
                FloatArray(dim) { fp16BitsToFloat(tmp[it]) }
            }
            OnnxJavaType.BFLOAT16 -> {
                val tmp = ShortArray(dim)
                val sb = tensor.shortBuffer
                if (sb != null) {
                    sb.position(startOffset)
                    sb.get(tmp, 0, dim)
                }
                FloatArray(dim) { java.lang.Float.intBitsToFloat(tmp[it].toInt() shl 16) }
            }
            else -> FloatArray(0)
        }
    }

    private fun loadWavMonoFloat24k(wavFile: File): FloatArray {
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

    private fun ensurePhononLoadedLocked(): Boolean {
        val baseDir = nativeDir(SPEC_PHONON_31M)
        val fp32Dir = nativeDir(SPEC_PHONON_FP32)
        val useFp32 = File(fp32Dir, "onnx/model.onnx").exists() && File(fp32Dir, "onnx/mimi_decoder.onnx").exists()
        if (loadedFamily == LoadedFamily.PHONON &&
            phononFlowModel != null &&
            phononMimiDecoder != null &&
            phononTokenizer != null &&
            phononLoadedFp32 == useFp32
        ) {
            return true
        }
        val flowFile = if (useFp32) File(fp32Dir, "onnx/model.onnx") else File(baseDir, "onnx/model_q4.onnx")
        val mimiFile = if (useFp32) File(fp32Dir, "onnx/mimi_decoder.onnx") else File(baseDir, "onnx/mimi_decoder_fp16.onnx")
        val tokFile = File(baseDir, "tokenizer.json").takeIf { it.exists() } ?: File(fp32Dir, "tokenizer.json")
        if (!flowFile.exists() || !mimiFile.exists() || !tokFile.exists()) return false

        closeAllSessionsLocked()
        val smartOffload = smartOffloadProvider()
        val threads = hardwareProfile.optimalCpuThreads
        // Phonon relies on MatMulNBits & GroupQueryAttention -> stay on XNNPACK/CPU (`preferNnapi = false`)
        val (flowSession, _) = createSessionWithFallback(flowFile.absolutePath, threads, preferNnapi = false, smartOffload = smartOffload)
        val (mimiSession, _) = createSessionWithFallback(mimiFile.absolutePath, threads, preferNnapi = false, smartOffload = smartOffload)

        phononFlowModel = flowSession
        phononMimiDecoder = mimiSession
        phononTokenizer = PurrPhononTokenizer.fromFile(tokFile)
        phononLoadedFp32 = useFp32
        loadedFamily = LoadedFamily.PHONON
        activeSmartOffload = smartOffload
        activeUsedNnapi = false
        Log.i(TAG, "[ONNX_NATIVE_MOUNT] Mounted Phonon 31M (${if (useFp32) "FP32" else "Q4"})")
        return true
    }

    private fun loadPhononSpeakerLatents(cleanVoiceId: String): Pair<FloatArray, Int> {
        val baseDir = nativeDir(SPEC_PHONON_31M)
        val voicesDir = File(baseDir, "voices")
        val preferredName = PHONON_VOICE_FILES[cleanVoiceId] ?: "NbpkqMVS3CJeq2j8.bin"
        val voiceFile = File(voicesDir, preferredName).takeIf { it.isFile && it.length() >= 512L * 4L }
            ?: voicesDir.listFiles()?.firstOrNull { it.isFile && it.extension.equals("bin", true) && it.length() >= 512L * 4L }
            ?: return FloatArray(0) to 0
        val cached = phononVoiceCache[voiceFile.name]
        if (cached != null) return cached to (cached.size / 512)

        val bytes = voiceFile.readBytes()
        val floatCount = bytes.size / 4
        val raw = FloatArray(floatCount)
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until floatCount) raw[i] = bb.float
        val transposed = transposePhononVoiceLatents(raw, voiceDim = 512)
        phononVoiceCache[voiceFile.name] = transposed
        return transposed to (transposed.size / 512)
    }

    private fun synthesizePhononLocked(text: String, cleanVoiceId: String): SynthesisOutcome {
        if (!ensurePhononLoadedLocked()) {
            return SynthesisOutcome(
                pcmFloat24k = FloatArray(0),
                sampleRateHz = SAMPLE_RATE_HZ,
                hardwareBadge = HardwareStatusBadge.IDLE,
                engineSummary = "Gradium Phonon 31M (ROM-17) is not installed",
                isFallbackNeuralPreview = true
            )
        }
        val flowModel = phononFlowModel!!
        val mimiDecoder = phononMimiDecoder!!
        val tokenizer = phononTokenizer!!

        val (voiceLatents, voiceFrames) = loadPhononSpeakerLatents(cleanVoiceId)
        if (voiceFrames <= 0 || voiceLatents.isEmpty()) {
            return SynthesisOutcome(
                pcmFloat24k = FloatArray(0),
                sampleRateHz = SAMPLE_RATE_HZ,
                hardwareBadge = HardwareStatusBadge.IDLE,
                engineSummary = "Phonon speaker latents missing in ROM-17 voices/",
                isFallbackNeuralPreview = true
            )
        }

        val chunks = tokenizer.splitIntoChunks(text, maxTokens = 48)
        if (chunks.isEmpty()) {
            return SynthesisOutcome(FloatArray(0), SAMPLE_RATE_HZ, HardwareStatusBadge.CPU_NEON, "Empty Phonon input")
        }

        val flowInputInfo = flowModel.inputInfo
        val flowInputNames = flowInputInfo.keys
        val numKvLayers = (0..15).count { "past_key_values.$it.key" in flowInputNames }.coerceAtLeast(6)
        val noiseStd = sqrt(0.3f)
        val rng = Random(System.nanoTime())
        val pcmSegments = ArrayList<FloatArray>(chunks.size * 2)
        val gapSamples = FloatArray(1440) // 60ms natural boundary at 24 kHz

        fun nextGaussianNoise(count: Int): FloatArray {
            val out = FloatArray(count)
            var i = 0
            while (i < count) {
                val u1 = rng.nextDouble().coerceIn(1e-7, 1.0 - 1e-7)
                val u2 = rng.nextDouble()
                val mag = sqrt(-2.0 * ln(u1))
                val z0 = (mag * cos(2.0 * PI * u2)).toFloat() * noiseStd
                val z1 = (mag * kotlin.math.sin(2.0 * PI * u2)).toFloat() * noiseStd
                out[i++] = z0
                if (i < count) out[i++] = z1
            }
            return out
        }

        for ((chunkIdx, chunkPair) in chunks.withIndex()) {
            val (chunkIds, wordCount) = chunkPair
            val textLen = chunkIds.size
            val maxBlocks = (((textLen / 3.0 + 2.5) * 12.5) / 8.0).toInt().coerceIn(3, 40)
            val framesAfterEos = if (wordCount <= 4) 3 else 1
            var pastSeqLen = 0L
            var pastAudioLatents = TensorPayload(FloatArray(0), null, null, longArrayOf(1L, 0L, 32L), OnnxJavaType.FLOAT)
            val pastKv = HashMap<String, TensorPayload>(numKvLayers * 2)
            for (l in 0 until numKvLayers) {
                val kType = (flowInputInfo["past_key_values.$l.key"]?.info as? TensorInfo)?.type ?: OnnxJavaType.FLOAT
                val vType = (flowInputInfo["past_key_values.$l.value"]?.info as? TensorInfo)?.type ?: OnnxJavaType.FLOAT
                pastKv["past_key_values.$l.key"] = TensorPayload(FloatArray(0), null, null, longArrayOf(1L, 8L, 0L, 64L), kType)
                pastKv["past_key_values.$l.value"] = TensorPayload(FloatArray(0), null, null, longArrayOf(1L, 8L, 0L, 64L), vType)
            }

            var eosHitFrame = -1
            var finalLatents = FloatArray(0)
            var finalFrames = 0

            for (step in 0 until maxBlocks) {
                val isFirst = step == 0
                val stepInputIds = if (isFirst) chunkIds else LongArray(0)
                val stepVoice = if (isFirst) voiceLatents else FloatArray(0)
                val stepVoiceFrames = if (isFirst) voiceFrames.toLong() else 0L
                val newSeqLen = (if (isFirst) voiceFrames.toLong() + textLen.toLong() + 8L else 8L)
                val totalSeqLen = pastSeqLen + newSeqLen
                val posIds = LongArray(newSeqLen.toInt()) { pastSeqLen + it.toLong() }
                val attnMask = LongArray(totalSeqLen.toInt()) { 1L }

                val feeds = HashMap<String, OnnxTensor>(flowInputNames.size)
                try {
                    feeds["input_ids"] = createLongTensor(stepInputIds, longArrayOf(1L, stepInputIds.size.toLong()))
                    val apType = (flowInputInfo["audio_prompt_latents"]?.info as? TensorInfo)?.type ?: OnnxJavaType.FLOAT
                    feeds["audio_prompt_latents"] = createFloatTensorMatching(stepVoice, longArrayOf(1L, stepVoiceFrames, 512L), apType)
                    if ("attention_mask" in flowInputNames) {
                        feeds["attention_mask"] = createLongTensor(attnMask, longArrayOf(1L, totalSeqLen))
                    }
                    if ("position_ids" in flowInputNames) {
                        feeds["position_ids"] = createLongTensor(posIds, longArrayOf(1L, newSeqLen))
                    }
                    val pastLatType = (flowInputInfo["past_key_values.audio_latents"]?.info as? TensorInfo)?.type ?: OnnxJavaType.FLOAT
                    feeds["past_key_values.audio_latents"] = if (pastAudioLatents.floats != null) {
                        createFloatTensorMatching(pastAudioLatents.floats!!, pastAudioLatents.shape, pastLatType)
                    } else {
                        createFloatTensorMatching(FloatArray(0), longArrayOf(1L, 0L, 32L), pastLatType)
                    }
                    val noiseType = (flowInputInfo["noise"]?.info as? TensorInfo)?.type ?: OnnxJavaType.FLOAT
                    feeds["noise"] = createFloatTensorMatching(nextGaussianNoise(8 * 32), longArrayOf(1L, 8L, 32L), noiseType)
                    for ((k, payload) in pastKv) {
                        if (k in flowInputNames) feeds[k] = tensorFromPayload(payload)
                    }

                    flowModel.run(feeds).use { result ->
                        val eosTensor = result.get("eos_logits").orElse(null)
                        val eosLogits = if (eosTensor != null) extractFloatArray(eosTensor) else FloatArray(0)
                        for (f in 0 until minOf(8, eosLogits.size)) {
                            val globalFrame = step * 8 + f
                            if (eosHitFrame < 0 && globalFrame >= 2 && eosLogits[f] > 4.0f) {
                                eosHitFrame = globalFrame
                            }
                        }

                        val framesSoFar = (step + 1) * 8
                        val stopFrameCount = if (eosHitFrame >= 0) eosHitFrame + 1 + framesAfterEos else Int.MAX_VALUE
                        if (framesSoFar >= stopFrameCount || step == maxBlocks - 1) {
                            val latVal = result.get("audio_latents").orElse(null)
                            val rawLatents = if (latVal != null) extractFloatArray(latVal) else FloatArray(0)
                            val clampedFrames = minOf(framesSoFar, stopFrameCount).coerceAtMost(rawLatents.size / 32)
                            finalFrames = clampedFrames
                            finalLatents = if (clampedFrames > 0) rawLatents.copyOfRange(0, clampedFrames * 32) else FloatArray(0)
                        } else {
                            (result.get("present.audio_latents").orElse(null) as? OnnxTensor)?.let {
                                pastAudioLatents = copyTensorPayload(it)
                            }
                            for (l in 0 until numKvLayers) {
                                (result.get("present.$l.key").orElse(null) as? OnnxTensor)?.let {
                                    pastKv["past_key_values.$l.key"] = copyTensorPayload(it)
                                }
                                (result.get("present.$l.value").orElse(null) as? OnnxTensor)?.let {
                                    pastKv["past_key_values.$l.value"] = copyTensorPayload(it)
                                }
                            }
                            pastSeqLen = totalSeqLen
                        }
                    }
                } finally {
                    feeds.values.forEach { runCatching { it.close() } }
                }
                if (finalFrames > 0 || (eosHitFrame >= 0 && (step + 1) * 8 >= eosHitFrame + 1 + framesAfterEos)) {
                    break
                }
            }

            if (finalFrames > 0 && finalLatents.size >= finalFrames * 32) {
                val mimiInfo = mimiDecoder.inputInfo
                val mimiFeeds = HashMap<String, OnnxTensor>(2)
                try {
                    val latType = (mimiInfo["audio_latents"]?.info as? TensorInfo)?.type ?: OnnxJavaType.FLOAT
                    mimiFeeds["audio_latents"] = createFloatTensorMatching(finalLatents, longArrayOf(1L, finalFrames.toLong(), 32L), latType)
                    if ("num_frames" in mimiInfo) {
                        val nfType = (mimiInfo["num_frames"]?.info as? TensorInfo)?.type ?: OnnxJavaType.INT64
                        mimiFeeds["num_frames"] = if (nfType == OnnxJavaType.INT32) {
                            createIntTensor(intArrayOf(finalFrames), longArrayOf(1L))
                        } else {
                            createLongTensor(longArrayOf(finalFrames.toLong()), longArrayOf(1L))
                        }
                    }
                    mimiDecoder.run(mimiFeeds).use { decOut ->
                        val audioVal = decOut.get("audio_values").orElse(null)
                        val decodedPcm = if (audioVal != null) extractFloatArray(audioVal) else FloatArray(0)
                        val lenTensor = decOut.get("audio_lengths").orElse(null) as? OnnxTensor
                        val reportedLen = lenTensor?.let {
                            val p = copyTensorPayload(it)
                            p.longs?.firstOrNull()?.toInt() ?: p.ints?.firstOrNull()
                        } ?: decodedPcm.size
                        val keepLen = reportedLen.coerceIn(0, decodedPcm.size)
                        if (keepLen > 0) {
                            if (chunkIdx > 0 && pcmSegments.isNotEmpty()) pcmSegments += gapSamples
                            pcmSegments += decodedPcm.copyOfRange(0, keepLen)
                        }
                    }
                } finally {
                    mimiFeeds.values.forEach { runCatching { it.close() } }
                }
            }
        }

        val totalLen = pcmSegments.sumOf { it.size }
        val combined = FloatArray(totalLen)
        var pos = 0
        for (seg in pcmSegments) {
            seg.copyInto(combined, pos)
            pos += seg.size
        }
        val voiceTag = cleanVoiceId.removePrefix("phonon_").replaceFirstChar { it.uppercaseChar() }
        val modeLabel = if (phononLoadedFp32) "FP32" else "Q4"
        return SynthesisOutcome(
            pcmFloat24k = combined,
            sampleRateHz = SAMPLE_RATE_HZ,
            hardwareBadge = HardwareStatusBadge.CPU_NEON,
            engineSummary = "Phonon 31M $modeLabel ($voiceTag)",
            isFallbackNeuralPreview = false
        )
    }

    private fun resetIdleTimer() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(IDLE_EVICTION_MS)
            evictResources()
        }
    }
}
