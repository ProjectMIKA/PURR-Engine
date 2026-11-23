package com.projectmika.purrtts.engine.sherpa

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKittenModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsMatchaModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsPocketModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsZipVoiceModelConfig
import com.projectmika.purrtts.core.purr.BackendType
import com.projectmika.purrtts.core.purr.ClonedVoiceProfile
import com.projectmika.purrtts.core.purr.HardwareProfile
import com.projectmika.purrtts.core.purr.HardwareStatusBadge
import com.projectmika.purrtts.core.purr.PurrDefaultPresets
import com.projectmika.purrtts.core.purr.PurrDspProcessor
import com.projectmika.purrtts.core.purr.PurrHardwareProbe
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
 * supporting all 6 offline cartridge architectures:
 * - Kokoro-82M INT8 (54 voices)
 * - KittenTTS Nano v0.8 INT8 (8 voices, 30MB)
 * - Supertonic 3 INT8
 * - Piper VITS (Multi-voice Collection)
 * - Pocket TTS (5-second zero-shot cloning)
 * - ZipVoice (flow-matching zero-shot cloning)
 */
class SherpaOnnxEngine(
    private val context: Context,
    private val clonedVoicesProvider: () -> List<ClonedVoiceProfile>,
    private val smartOffloadProvider: () -> Boolean = { false },
    private val hardwareProfile: HardwareProfile = PurrHardwareProbe.detectDevice(),
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
    private var activeMountKey: String? = null
    private var activeSmartOffload: Boolean = false
    private var activeUsedNnapi: Boolean = false
    private var lastMountError: String? = null

    val modelsBaseDir: File by lazy {
        File(context.filesDir, "models/sherpa").apply { mkdirs() }
    }

    override val isModelReady: Boolean
        get() = modelsBaseDir.listFiles()?.any { it.isDirectory && it.listFiles()?.isNotEmpty() == true } == true

    fun isFamilyInstalled(family: SherpaModelFamily, subCartridgeId: String? = null): Boolean {
        val dir = familyDir(family, subCartridgeId)
        return dir.walkTopDown().any { it.extension.equals("onnx", ignoreCase = true) }
    }

    override suspend fun warmup(profile: PurrProfile) {
        mutex.withLock {
            val cleanVoiceId = profile.primaryVoiceId.substringAfter(':')
            val voiceDesc = PurrDefaultPresets.builtInVoices().firstOrNull { it.id == cleanVoiceId }
            val dirKey = voiceDesc?.requiredSubCartridgeId
                ?: voiceDesc?.cartridgeId?.takeIf { it != "rom_piper" && it != "rom_kokoro_sherpa" && it != "rom_kitten_tts" }
            ensureFamilyLoadedLocked(
                family = voiceDesc?.sherpaFamily ?: profile.sherpaFamily,
                subCartridgeId = dirKey
            )
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

        val cleanVoiceId = voiceId.substringAfter(':')
        val voiceDesc = PurrDefaultPresets.builtInVoices().firstOrNull { it.id == cleanVoiceId }
        val targetFamily = voiceDesc?.sherpaFamily ?: inferFamilyFromVoice(voiceId, profile.sherpaFamily)
        val subCartridgeId = voiceDesc?.requiredSubCartridgeId
            ?: voiceDesc?.cartridgeId?.takeIf { it != "rom_piper" && it != "rom_kokoro_sherpa" && it != "rom_kitten_tts" }

        mutex.withLock {
            ensureFamilyLoadedLocked(targetFamily, subCartridgeId)
            val tts = activeOfflineTts
            if (tts == null) {
                // ✨ TRUST RENEWAL UX (v1.8.1): name the CARTRIDGE to slam, not just the voice —
                // "Af Heart cartridge not installed" sent users hunting for a voice they had
                // already downloaded while the actual missing piece was its parent ROM bay.
                val voiceLabel = voiceDesc?.displayName ?: targetFamily.label
                val cartridgeLabel = cartridgeLabelFor(
                    cartridgeId = voiceDesc?.cartridgeId,
                    subCartridgeId = voiceDesc?.requiredSubCartridgeId,
                    family = targetFamily
                )
                val mountReason = lastMountError
                val summary = if (!mountReason.isNullOrBlank() && isFamilyInstalled(targetFamily, subCartridgeId)) {
                    "$cartridgeLabel failed to mount: $mountReason"
                } else {
                    "$voiceLabel offline — $cartridgeLabel cartridge not installed (download it in Tab 3)"
                }
                return@withContext SynthesisOutcome(
                    pcmFloat24k = FloatArray(0),
                    sampleRateHz = SAMPLE_RATE_HZ,
                    hardwareBadge = HardwareStatusBadge.IDLE,
                    engineSummary = summary,
                    isFallbackNeuralPreview = true
                )
            }

            val clampedPitch = pitch.coerceIn(0.88f, 1.12f)
            val compensatedSpeed = (speakingRate / clampedPitch).coerceIn(0.6f, 1.8f)
            val speakerId = resolveSpeakerId(voiceId, targetFamily)
            var genResult = runCatching {
                tts.generate(text = text, sid = speakerId, speed = compensatedSpeed)
            }
            var generated = genResult.getOrNull()

            // Runtime NNAPI -> CPU fail-soft retry: some Android NNAPI drivers accept dynamic
            // shapes at init time and only fail/return empty inside generate().
            if ((generated == null || generated.samples.isEmpty()) && activeUsedNnapi) {
                Log.w(TAG, "[NNAPI_FALLBACK] ${targetFamily.label} failed under NNAPI (${genResult.exceptionOrNull()?.message ?: "empty"}); reloading CPU")
                ensureFamilyLoadedLocked(targetFamily, subCartridgeId, forceCpu = true)
                activeOfflineTts?.let { cpuTts ->
                    genResult = runCatching {
                        cpuTts.generate(text = text, sid = speakerId, speed = compensatedSpeed)
                    }
                    generated = genResult.getOrNull()
                }
            }

            val finalGenerated = generated
            if (finalGenerated == null || finalGenerated.samples.isEmpty()) {
                val errDetail = genResult.exceptionOrNull()?.message ?: lastMountError
                val errSuffix = if (!errDetail.isNullOrBlank()) " ($errDetail)" else ""
                Log.w(TAG, "[GEN_EMPTY] ${targetFamily.label} sub=$subCartridgeId sid=$speakerId$errSuffix")
                return@withContext SynthesisOutcome(
                    pcmFloat24k = FloatArray(0),
                    sampleRateHz = SAMPLE_RATE_HZ,
                    hardwareBadge = HardwareStatusBadge.IDLE,
                    engineSummary = "${targetFamily.label} synthesis returned empty buffer$errSuffix",
                    isFallbackNeuralPreview = true
                )
            }

            val activeBadge = if (activeSmartOffload && activeUsedNnapi) {
                HardwareStatusBadge.NPU_ACTIVE
            } else {
                HardwareStatusBadge.CPU_NEON
            }
            _statusFlow.value = activeBadge
            val resampled = PurrDspProcessor.resampleMono(
                samples = finalGenerated.samples,
                fromRate = finalGenerated.sampleRate,
                toRate = SAMPLE_RATE_HZ
            )
            val suffix = if (activeSmartOffload) {
                if (activeUsedNnapi) " • Smart NPU/NNAPI" else " • Smart Offload"
            } else {
                ""
            }
            SynthesisOutcome(
                pcmFloat24k = PurrDspProcessor.shiftPitchCubic(resampled, clampedPitch),
                sampleRateHz = SAMPLE_RATE_HZ,
                hardwareBadge = activeBadge,
                engineSummary = "sherpa-onnx • ${voiceDesc?.displayName ?: targetFamily.label} (${finalGenerated.sampleRate}Hz -> 24kHz)$suffix"
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
            ensureFamilyLoadedLocked(clonedVoice.family, clonedVoice.resolvedCloneEngineId)
            val tts = activeOfflineTts
            if (tts == null) {
                // ✨ TRUST RENEWAL UX (v1.8.1): name the clone engine cartridge that is missing.
                val cloneSpec = PurrDefaultPresets.modelCatalog().firstOrNull { it.id == clonedVoice.resolvedCloneEngineId }
                val cartridgeLabel = cloneSpec?.let { "${it.romCode} ${it.name}" } ?: clonedVoice.family.label
                return@withContext SynthesisOutcome(
                    pcmFloat24k = FloatArray(0),
                    sampleRateHz = SAMPLE_RATE_HZ,
                    hardwareBadge = HardwareStatusBadge.IDLE,
                    engineSummary = "$cartridgeLabel cartridge not installed (needed by clone ${clonedVoice.displayName} — download it in Tab 3)",
                    isFallbackNeuralPreview = true
                )
            }

            val clampedPitch = pitch.coerceIn(0.88f, 1.12f)
            val compensatedSpeed = (speakingRate / clampedPitch).coerceIn(0.6f, 1.8f)
            val refSamples = loadWavMonoFloat24k(File(clonedVoice.referenceWavPath))
            var genResult = runCatching {
                if (refSamples.isNotEmpty()) {
                    val genCfg = GenerationConfig(
                        speed = compensatedSpeed,
                        referenceAudio = refSamples,
                        referenceSampleRate = SAMPLE_RATE_HZ,
                        referenceText = clonedVoice.referenceTranscript
                    )
                    tts.generateWithConfig(text, genCfg)
                } else {
                    tts.generate(text = text, sid = 0, speed = compensatedSpeed)
                }
            }
            var generated = genResult.getOrNull()

            if ((generated == null || generated.samples.isEmpty()) && activeUsedNnapi) {
                Log.w(TAG, "[NNAPI_FALLBACK] ${clonedVoice.family.label} clone failed under NNAPI; retrying CPU")
                ensureFamilyLoadedLocked(clonedVoice.family, clonedVoice.resolvedCloneEngineId, forceCpu = true)
                activeOfflineTts?.let { cpuTts ->
                    genResult = runCatching {
                        if (refSamples.isNotEmpty()) {
                            val genCfg = GenerationConfig(
                                speed = compensatedSpeed,
                                referenceAudio = refSamples,
                                referenceSampleRate = SAMPLE_RATE_HZ,
                                referenceText = clonedVoice.referenceTranscript
                            )
                            cpuTts.generateWithConfig(text, genCfg)
                        } else {
                            cpuTts.generate(text = text, sid = 0, speed = compensatedSpeed)
                        }
                    }
                    generated = genResult.getOrNull()
                }
            }

            val finalGenerated = generated
            if (finalGenerated == null || finalGenerated.samples.isEmpty()) {
                val errDetail = genResult.exceptionOrNull()?.message ?: lastMountError
                val errSuffix = if (!errDetail.isNullOrBlank()) " ($errDetail)" else ""
                return@withContext SynthesisOutcome(
                    pcmFloat24k = FloatArray(0),
                    sampleRateHz = SAMPLE_RATE_HZ,
                    hardwareBadge = HardwareStatusBadge.IDLE,
                    engineSummary = "${clonedVoice.family.label} clone synthesis failed$errSuffix",
                    isFallbackNeuralPreview = true
                )
            }

            val activeBadge = if (activeSmartOffload && activeUsedNnapi) {
                HardwareStatusBadge.NPU_ACTIVE
            } else {
                HardwareStatusBadge.CPU_NEON
            }
            _statusFlow.value = activeBadge
            val resampled = PurrDspProcessor.resampleMono(
                samples = finalGenerated.samples,
                fromRate = finalGenerated.sampleRate,
                toRate = SAMPLE_RATE_HZ
            )
            val suffix = if (activeSmartOffload) {
                if (activeUsedNnapi) " • Smart NPU/NNAPI" else " • Smart Offload"
            } else {
                ""
            }
            SynthesisOutcome(
                pcmFloat24k = PurrDspProcessor.shiftPitchCubic(resampled, clampedPitch),
                sampleRateHz = SAMPLE_RATE_HZ,
                hardwareBadge = activeBadge,
                engineSummary = "${clonedVoice.family.label} Cloned • ${clonedVoice.displayName}$suffix"
            )
        }
    }

    override suspend fun evictResources() {
        mutex.withLock {
            runCatching { activeOfflineTts?.release() }
            activeOfflineTts = null
            activeMountKey = null
            activeSmartOffload = false
            activeUsedNnapi = false
            _statusFlow.value = HardwareStatusBadge.IDLE
            Log.i(TAG, "[ONNX_EVICT] Released sherpa-onnx OfflineTts native memory.")
        }
    }

    fun familyDir(family: SherpaModelFamily, subCartridgeId: String? = null): File = when (family) {
        // ✨ HUB TIER (v1.7.0): isolated variant directories so weights never collide
        SherpaModelFamily.KOKORO -> if (subCartridgeId != null && subCartridgeId != "rom_kokoro_sherpa") {
            File(modelsBaseDir, "kokoro_$subCartridgeId")
        } else {
            File(modelsBaseDir, "kokoro")
        }
        SherpaModelFamily.KITTEN_TTS -> if (subCartridgeId != null && subCartridgeId != "rom_kitten_tts") {
            File(modelsBaseDir, "kitten_$subCartridgeId")
        } else {
            File(modelsBaseDir, "kitten")
        }
        SherpaModelFamily.SUPERTONIC_3 -> File(modelsBaseDir, "supertonic3")
        SherpaModelFamily.POCKET_TTS -> if (subCartridgeId != null && subCartridgeId != "rom_pocket_tts") {
            File(modelsBaseDir, "pocket_$subCartridgeId")
        } else {
            File(modelsBaseDir, "pocket_tts")
        }
        SherpaModelFamily.ZIPVOICE -> if (subCartridgeId != null && subCartridgeId != "rom_zipvoice") {
            File(modelsBaseDir, "zipvoice_$subCartridgeId")
        } else {
            File(modelsBaseDir, "zipvoice")
        }
        SherpaModelFamily.MATCHA -> if (subCartridgeId != null && subCartridgeId != "rom_matcha_zh_en") {
            File(modelsBaseDir, "matcha_$subCartridgeId")
        } else {
            File(modelsBaseDir, "matcha")
        }
        SherpaModelFamily.MELO_TTS -> File(modelsBaseDir, "melo_${subCartridgeId ?: "sub_melo_en"}")
        SherpaModelFamily.PIPER -> if (subCartridgeId != null && subCartridgeId != "rom_piper") {
            File(modelsBaseDir, "piper_$subCartridgeId")
        } else {
            File(modelsBaseDir, "piper")
        }
    }.apply { mkdirs() }

    /**
     * ✨ TRUST RENEWAL UX (v1.8.1): resolves the human-facing cartridge label (ROM code + name)
     * for a missing voice mount, so failure messages name the downloadable piece instead of
     * making users hunt for a voice they may have already installed.
     */
    private fun cartridgeLabelFor(cartridgeId: String?, subCartridgeId: String?, family: SherpaModelFamily): String {
        val catalog = PurrDefaultPresets.modelCatalog()
        val spec = subCartridgeId?.let { id -> catalog.firstOrNull { it.id == id } }
            ?: cartridgeId?.let { id -> catalog.firstOrNull { it.id == id } }
            ?: catalog.firstOrNull { it.sherpaFamily == family && !it.isSubCartridge }
        return spec?.let { "${it.romCode} ${it.name}" } ?: family.label
    }

    private fun ensureFamilyLoadedLocked(
        family: SherpaModelFamily,
        subCartridgeId: String? = null,
        forceCpu: Boolean = false
    ) {
        val smartOffload = smartOffloadProvider()
        val mountKey = "${subCartridgeId ?: family.name}|smart=$smartOffload|cpu=$forceCpu"
        if (activeOfflineTts != null && activeMountKey == mountKey) return
        runCatching { activeOfflineTts?.release() }
        activeOfflineTts = null
        activeMountKey = mountKey
        activeSmartOffload = smartOffload
        activeUsedNnapi = false
        lastMountError = null

        val dir = familyDir(family, subCartridgeId)
        val files = dir.walkTopDown().filter { it.isFile }.toList()
        if (files.none { it.extension.equals("onnx", ignoreCase = true) }) {
            // ✨ TRUST RENEWAL (v1.8.1): say WHY the mount refused — support gold when a user
            // swears they downloaded a cartridge but every voice fail-softs to OS speech.
            lastMountError = "Missing .onnx model under ${dir.name} (${files.size} files)"
            Log.w(TAG, "[MOUNT_MISS] family=$family sub=$subCartridgeId — no .onnx under ${dir.absolutePath} (${files.size} files present)")
            return
        }

        val tokensPath = files.firstOrNull { it.name == "tokens.txt" }?.absolutePath.orEmpty()
        val dataDir = dir.walkTopDown().firstOrNull { it.isDirectory && it.name == "espeak-ng-data" }?.absolutePath.orEmpty()
        val lexicons = files.filter { it.name.startsWith("lexicon") && it.extension == "txt" }
            .sortedBy { f ->
                when (f.name) {
                    "lexicon-us-en.txt" -> 0
                    "lexicon-gb-en.txt" -> 1
                    "lexicon-zh.txt" -> 2
                    else -> 3
                }
            }
            .joinToString(",") { it.absolutePath }
        val ruleFsts = files.filter { it.extension.equals("fst", ignoreCase = true) }
            .sortedBy { it.name }
            .joinToString(",") { it.absolutePath }

        val threads = if (smartOffload) hardwareProfile.optimalCpuThreads else 4

        fun buildConfigForProvider(providerName: String): OfflineTtsModelConfig? = when (family) {
            SherpaModelFamily.KOKORO -> {
                val onnx = files.firstOrNull { it.name.endsWith(".onnx") }?.absolutePath.orEmpty()
                val voices = files.firstOrNull { it.name == "voices.bin" }?.absolutePath.orEmpty()
                if (onnx.isEmpty() || voices.isEmpty()) {
                    Log.w(TAG, "[MOUNT_MISS] kokoro missing required assets (onnx=$onnx, voices=$voices)")
                    null
                } else {
                    OfflineTtsModelConfig(
                        kokoro = OfflineTtsKokoroModelConfig(
                            model = onnx,
                            voices = voices,
                            tokens = tokensPath,
                            dataDir = dataDir,
                            lexicon = lexicons
                        ),
                        numThreads = threads,
                        provider = providerName
                    )
                }
            }
            SherpaModelFamily.KITTEN_TTS -> {
                val onnx = files.firstOrNull { it.name.endsWith(".onnx") }?.absolutePath.orEmpty()
                val voices = files.firstOrNull { it.name == "voices.bin" }?.absolutePath.orEmpty()
                if (onnx.isEmpty() || voices.isEmpty() || tokensPath.isEmpty() || dataDir.isEmpty()) {
                    Log.w(TAG, "KittenTTS missing required asset (onnx=$onnx, voices=$voices, tokens=$tokensPath, dataDir=$dataDir)")
                    null
                } else {
                    OfflineTtsModelConfig(
                        kitten = OfflineTtsKittenModelConfig(
                            model = onnx,
                            voices = voices,
                            tokens = tokensPath,
                            dataDir = dataDir,
                            lengthScale = 1.0f
                        ),
                        numThreads = threads,
                        provider = providerName
                    )
                }
            }
            SherpaModelFamily.SUPERTONIC_3 -> {
                val dur = files.firstOrNull { it.name.contains("duration_predictor") && it.extension == "onnx" }?.absolutePath.orEmpty()
                val txtEnc = files.firstOrNull { it.name.contains("text_encoder") && it.extension == "onnx" }?.absolutePath.orEmpty()
                val vecEst = files.firstOrNull { it.name.contains("vector_estimator") && it.extension == "onnx" }?.absolutePath.orEmpty()
                val voc = files.firstOrNull { it.name.contains("vocoder") && it.extension == "onnx" }?.absolutePath.orEmpty()
                val ttsJson = files.firstOrNull { it.name == "tts.json" }?.absolutePath.orEmpty()
                val uniIdx = files.firstOrNull { it.name.contains("unicode_indexer") }?.absolutePath.orEmpty()
                val vStyle = files.firstOrNull { it.name.contains("voice") && it.extension == "bin" }?.absolutePath.orEmpty()
                if (dur.isEmpty() || txtEnc.isEmpty() || vecEst.isEmpty() || voc.isEmpty()) {
                    null
                } else {
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
                        numThreads = threads,
                        provider = providerName
                    )
                }
            }
            SherpaModelFamily.MATCHA -> {
                // Acoustic model + Vocos/HiFi-GAN vocoder (companion asset).
                val acoustic = files.firstOrNull {
                    it.extension.equals("onnx", ignoreCase = true) &&
                        !it.name.contains("vocos", ignoreCase = true) &&
                        !it.name.contains("hifigan", ignoreCase = true) &&
                        !it.name.contains("vocoder", ignoreCase = true)
                }?.absolutePath.orEmpty()
                val vocoder = files.firstOrNull {
                    it.extension.equals("onnx", ignoreCase = true) &&
                        (it.name.contains("vocos", ignoreCase = true) ||
                            it.name.contains("hifigan", ignoreCase = true) ||
                            it.name.contains("vocoder", ignoreCase = true))
                }?.absolutePath.orEmpty()
                if (acoustic.isEmpty() || vocoder.isEmpty()) {
                    Log.w(TAG, "Matcha missing required asset (acoustic=$acoustic, vocoder=$vocoder)")
                    null
                } else {
                    OfflineTtsModelConfig(
                        matcha = OfflineTtsMatchaModelConfig(
                            acousticModel = acoustic,
                            vocoder = vocoder,
                            lexicon = lexicons,
                            tokens = tokensPath,
                            dataDir = dataDir,
                            noiseScale = 0.667f,
                            lengthScale = 1.0f
                        ),
                        numThreads = threads,
                        provider = providerName
                    )
                }
            }
            SherpaModelFamily.PIPER,
            SherpaModelFamily.MELO_TTS -> {
                val onnxFiles = files.filter { it.extension.equals("onnx", ignoreCase = true) }
                val onnx = (onnxFiles.firstOrNull { it.name.contains("int8", ignoreCase = true) }
                    ?: onnxFiles.firstOrNull())?.absolutePath.orEmpty()
                if (onnx.isEmpty() || tokensPath.isEmpty()) {
                    lastMountError = "Missing VITS asset (onnx=${onnx.isNotEmpty()}, tokens=${tokensPath.isNotEmpty()})"
                    Log.w(TAG, "[MOUNT_MISS] $lastMountError in ${dir.absolutePath}")
                    null
                } else {
                    OfflineTtsModelConfig(
                        vits = OfflineTtsVitsModelConfig(
                            model = onnx,
                            lexicon = lexicons,
                            tokens = tokensPath,
                            dataDir = dataDir,
                            noiseScale = 0.667f,
                            noiseScaleW = 0.8f,
                            lengthScale = 1.0f
                        ),
                        numThreads = threads,
                        provider = providerName
                    )
                }
            }
            SherpaModelFamily.POCKET_TTS -> {
                val lmFlow = files.firstOrNull { it.name.contains("lm_flow") && it.extension == "onnx" }?.absolutePath.orEmpty()
                val lmMain = files.firstOrNull { it.name.contains("lm_main") && it.extension == "onnx" }?.absolutePath.orEmpty()
                val enc = files.firstOrNull { it.name.contains("encoder") && it.extension == "onnx" }?.absolutePath.orEmpty()
                val dec = files.firstOrNull { it.name.contains("decoder") && it.extension == "onnx" }?.absolutePath.orEmpty()
                val txtCond = files.firstOrNull { it.name.contains("text_conditioner") && it.extension == "onnx" }?.absolutePath.orEmpty()
                val vocab = files.firstOrNull { it.name.contains("vocab") && it.extension == "json" }?.absolutePath.orEmpty()
                val scores = files.firstOrNull { it.name.contains("token_scores") && it.extension == "json" }?.absolutePath.orEmpty()
                if (lmFlow.isEmpty() || lmMain.isEmpty()) {
                    null
                } else {
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
                        numThreads = threads,
                        provider = providerName
                    )
                }
            }
            SherpaModelFamily.ZIPVOICE -> {
                val enc = files.firstOrNull { it.name.contains("encoder") && it.extension == "onnx" }?.absolutePath.orEmpty()
                val dec = files.firstOrNull { it.name.contains("decoder") && it.extension == "onnx" }?.absolutePath.orEmpty()
                val voc = files.firstOrNull { it.name.contains("vocoder") && it.extension == "onnx" }?.absolutePath.orEmpty()
                if (enc.isEmpty() || dec.isEmpty()) {
                    null
                } else {
                    OfflineTtsModelConfig(
                        zipvoice = OfflineTtsZipVoiceModelConfig(
                            tokens = tokensPath,
                            encoder = enc,
                            decoder = dec,
                            vocoder = voc,
                            dataDir = dataDir,
                            lexicon = lexicons
                        ),
                        numThreads = threads,
                        provider = providerName
                    )
                }
            }
        }

        val ruleFstsArg = if (family == SherpaModelFamily.KOKORO || family == SherpaModelFamily.MELO_TTS) ruleFsts else ""

        // ✨ SMART HARDWARE OFFLOAD (v1.8.5): try NNAPI first when enabled & supported on
        // architectures with predictable shapes (Kokoro/Kitten/Supertonic/Pocket/ZipVoice).
        // Skip dynamic-shape VITS/Melo/Matcha graphs where Android NNAPI frequently accepts
        // session init and then produces empty buffers at generate().
        val allowNnapi = !forceCpu &&
            smartOffload &&
            hardwareProfile.hasNnapi &&
            family !in setOf(SherpaModelFamily.PIPER, SherpaModelFamily.MELO_TTS, SherpaModelFamily.MATCHA)
        if (allowNnapi) {
            val nnapiCfg = buildConfigForProvider("nnapi") ?: return
            val nnapiAttempt = runCatching {
                OfflineTts(assetManager = null, config = OfflineTtsConfig(model = nnapiCfg, ruleFsts = ruleFstsArg))
            }
            if (nnapiAttempt.isSuccess) {
                activeOfflineTts = nnapiAttempt.getOrThrow()
                activeUsedNnapi = true
                _statusFlow.value = HardwareStatusBadge.NPU_ACTIVE
                Log.i(TAG, "Mounted sherpa-onnx ${family.label} via NNAPI (Smart Offload) from ${dir.absolutePath}")
                return
            }
            Log.w(TAG, "sherpa-onnx ${family.label} NNAPI mount failed, falling back to CPU: ${nnapiAttempt.exceptionOrNull()?.message}")
        }

        val cpuCfg = buildConfigForProvider("cpu") ?: return
        runCatching {
            val config = OfflineTtsConfig(model = cpuCfg, ruleFsts = ruleFstsArg)
            activeOfflineTts = OfflineTts(assetManager = null, config = config)
            _statusFlow.value = HardwareStatusBadge.CPU_NEON
            Log.i(TAG, "Mounted sherpa-onnx ${family.label} cleanly from ${dir.absolutePath}")
        }.onFailure { err ->
            lastMountError = err.message ?: "OfflineTts mount failed"
            Log.e(TAG, "Failed to mount sherpa-onnx ${family.label}: ${err.message}", err)
            activeOfflineTts = null
        }
    }

    private fun resolveSpeakerId(voiceId: String, family: SherpaModelFamily): Int {
        val cleanVoiceId = voiceId.substringAfter(':')
        val descriptor = PurrDefaultPresets.builtInVoices().firstOrNull { it.id == cleanVoiceId }
        if (descriptor != null) return descriptor.speakerIndex
        if (family == SherpaModelFamily.KOKORO) {
            return PurrDefaultPresets.kokoroVoices("rom_kokoro_sherpa", BackendType.SHERPA_ONNX)
                .firstOrNull { it.id == cleanVoiceId }?.speakerIndex ?: 0
        }
        return when {
            cleanVoiceId.endsWith("m1") -> 1
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

    private fun inferFamilyFromVoice(voiceId: String, defaultFamily: SherpaModelFamily): SherpaModelFamily {
        val cleanId = voiceId.substringAfter(':')
        return when {
            cleanId.matches(Regex("^[abjefhipz][fm]_[a-z0-9]+$")) ||
                cleanId.startsWith("fp32_") || cleanId.startsWith("v11_") -> SherpaModelFamily.KOKORO
            cleanId.contains("kitten", ignoreCase = true) ||
                cleanId.startsWith("kmini_") || cleanId.startsWith("kmicro_") -> SherpaModelFamily.KITTEN_TTS
            cleanId.contains("melo", ignoreCase = true) -> SherpaModelFamily.MELO_TTS
            cleanId.contains("supertonic", ignoreCase = true) -> SherpaModelFamily.SUPERTONIC_3
            cleanId.contains("pocket", ignoreCase = true) -> SherpaModelFamily.POCKET_TTS
            cleanId.contains("zipvoice", ignoreCase = true) -> SherpaModelFamily.ZIPVOICE
            cleanId.contains("matcha", ignoreCase = true) -> SherpaModelFamily.MATCHA
            cleanId.contains("piper", ignoreCase = true) ||
                cleanId.startsWith("inflect_") ||
                cleanId.startsWith("icefall_") ||
                cleanId.startsWith("coqui_") ||
                cleanId.startsWith("mms_") ||
                cleanId.startsWith("vctk_") ||
                cleanId.startsWith("mimic3_") -> SherpaModelFamily.PIPER
            else -> defaultFamily
        }
    }

    private fun resetIdleTimer() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(IDLE_EVICTION_MS)
            evictResources()
        }
    }
}
