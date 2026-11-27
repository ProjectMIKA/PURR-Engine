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
import com.projectmika.purrtts.engine.ResidencyTimer
import com.projectmika.purrtts.engine.SynthesisOutcome
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val idleTimeoutMsProvider: () -> Long = { IDLE_EVICTION_MS },
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
    private val residencyTimer = ResidencyTimer(scope, idleTimeoutMsProvider) { evictResources() }
    val residentModelLabel: StateFlow<String?> = residencyTimer.residentModelLabel
    val remainingWarmSeconds: StateFlow<Int> = residencyTimer.remainingWarmSeconds
    private var activeOfflineTts: OfflineTts? = null
    private var activeMountKey: String? = null
    private var activeSmartOffload: Boolean = false
    private var activeUsedNnapi: Boolean = false
    private var lastMountError: String? = null

    val modelsBaseDir: File by lazy {
        File(context.filesDir, "models/sherpa").apply { mkdirs() }
    }

    private val sharedEspeakDir: File by lazy {
        File(modelsBaseDir, "shared_espeak/espeak-ng-data")
    }

    override val isModelReady: Boolean
        get() = modelsBaseDir.listFiles()?.any { it.isDirectory && it.listFiles()?.isNotEmpty() == true } == true

    private data class MountTarget(
        val family: SherpaModelFamily,
        val subCartridgeId: String?,
        val speakerIndex: Int,
        val voiceDisplayName: String?,
        val cartridgeId: String?
    )

    fun isFamilyInstalled(family: SherpaModelFamily, subCartridgeId: String? = null): Boolean {
        val dir = familyDir(family, subCartridgeId)
        return hasMinimumRequiredAssets(dir, family)
    }

    private fun isCompleteEspeakDir(dir: File): Boolean =
        dir.isDirectory &&
            dir.name == "espeak-ng-data" &&
            File(dir, "phontab").isFile &&
            File(dir, "phonindex").isFile &&
            File(dir, "phondata").isFile &&
            File(dir, "intonations").isFile

    private fun resolveMatchaVocoderForDir(dir: File, files: List<File>): String {
        val local = files.firstOrNull {
            it.extension.equals("onnx", ignoreCase = true) && isVocoderOnnx(it)
        }?.absolutePath.orEmpty()
        if (local.isNotEmpty()) return local
        val expectedVocoder = if (dir.name == "matcha") "vocos-16khz-univ.onnx" else "vocos-22khz-univ.onnx"
        val siblingCandidate = listOf("matcha", "matcha_rom_matcha_en", "matcha_sub_matcha_en")
            .map { File(modelsBaseDir, "$it/$expectedVocoder") }
            .firstOrNull { it.isFile && it.length() > 1024L }
        return siblingCandidate?.absolutePath.orEmpty()
    }

    private fun isCharacterFrontendVits(subCartridgeId: String?, dir: File): Boolean {
        val id = subCartridgeId.orEmpty()
        return id == "rom_meta_mms" ||
            id.startsWith("sub_mms_") ||
            dir.name.contains("mms", ignoreCase = true)
    }

    private fun hasMinimumRequiredAssets(dir: File, family: SherpaModelFamily): Boolean {
        if (!dir.exists() || !dir.isDirectory) return false
        val files = dir.walkTopDown()
            .filter { it.isFile && it.length() > 0L && !it.absolutePath.contains("${File.separator}espeak-ng-data${File.separator}") }
            .toList()
        if (files.none { it.extension.equals("onnx", ignoreCase = true) }) return false
        val hasEspeak = dir.walkTopDown().any { isCompleteEspeakDir(it) } || isCompleteEspeakDir(sharedEspeakDir)
        return when (family) {
            SherpaModelFamily.KOKORO -> files.any { it.name == "voices.bin" } &&
                files.any { it.name == "tokens.txt" } &&
                hasEspeak
            SherpaModelFamily.KITTEN_TTS -> files.any { it.name == "voices.bin" } &&
                files.any { it.name == "tokens.txt" } &&
                hasEspeak
            // ✨ ZONOS (v2.7.0): native ggml — never validated through the sherpa path.
            SherpaModelFamily.ZONOS3,
            SherpaModelFamily.ZONOS1 -> false
            SherpaModelFamily.SUPERTONIC_3 -> {
                files.any { it.name.contains("duration_predictor") && it.extension.equals("onnx", true) } &&
                    files.any { it.name.contains("text_encoder") && it.extension.equals("onnx", true) } &&
                    files.any { it.name.contains("vector_estimator") && it.extension.equals("onnx", true) } &&
                    files.any { it.name.contains("vocoder") && it.extension.equals("onnx", true) } &&
                    files.any { it.name == "tts.json" } &&
                    files.any { it.name.contains("unicode_indexer") } &&
                    files.any { it.name.contains("voice") && it.extension.equals("bin", true) }
            }
            SherpaModelFamily.POCKET_TTS -> {
                files.any { it.name.contains("lm_flow") && it.extension.equals("onnx", true) } &&
                    files.any { it.name.contains("lm_main") && it.extension.equals("onnx", true) } &&
                    files.any { it.name.contains("encoder") && it.extension.equals("onnx", true) } &&
                    files.any { it.name.contains("decoder") && it.extension.equals("onnx", true) } &&
                    files.any { it.name.contains("text_conditioner") && it.extension.equals("onnx", true) } &&
                    files.any { it.name.contains("vocab") && it.extension.equals("json", true) } &&
                    files.any { it.name.contains("token_scores") && it.extension.equals("json", true) }
            }
            SherpaModelFamily.ZIPVOICE -> {
                files.any { it.name == "tokens.txt" } &&
                    files.any { it.name.contains("encoder") && it.extension.equals("onnx", true) } &&
                    files.any { it.name.contains("decoder") && it.extension.equals("onnx", true) } &&
                    files.any { it.name.contains("vocoder") && it.extension.equals("onnx", true) }
            }
            SherpaModelFamily.MATCHA -> files.any { it.name == "tokens.txt" } &&
                files.any { it.extension.equals("onnx", true) && !isVocoderOnnx(it) } &&
                resolveMatchaVocoderForDir(dir, files).isNotEmpty()
            SherpaModelFamily.PIPER -> files.any { it.name == "tokens.txt" } &&
                (files.any { it.name.startsWith("lexicon") } || hasEspeak || isCharacterFrontendVits(null, dir))
            SherpaModelFamily.MELO_TTS -> files.any { it.name == "tokens.txt" }
        }
    }

    private fun isVocoderOnnx(file: File): Boolean =
        file.name.contains("vocos", ignoreCase = true) ||
            file.name.contains("hifigan", ignoreCase = true) ||
            file.name.contains("vocoder", ignoreCase = true)

    private fun resolveMountTarget(voiceId: String, profile: PurrProfile): MountTarget {
        val cleanVoiceId = voiceId.substringAfter(':')
        val allBuiltIn = PurrDefaultPresets.builtInVoices()
        val requestedDesc = allBuiltIn.firstOrNull { it.id == cleanVoiceId }
        val requestedFamily = requestedDesc?.sherpaFamily ?: inferFamilyFromVoice(voiceId, profile.sherpaFamily)
        val requestedSub = requestedDesc?.requiredSubCartridgeId
            ?: requestedDesc?.cartridgeId?.takeIf { it !in setOf("rom_piper", "rom_kokoro_sherpa", "rom_kitten_tts") }

        // If the requested voice's directory has valid assets, use it directly.
        if (hasMinimumRequiredAssets(familyDir(requestedFamily, requestedSub), requestedFamily)) {
            return MountTarget(
                family = requestedFamily,
                subCartridgeId = requestedSub,
                speakerIndex = requestedDesc?.speakerIndex ?: inferSpeakerFallback(cleanVoiceId),
                voiceDisplayName = requestedDesc?.displayName,
                cartridgeId = requestedDesc?.cartridgeId ?: profile.selectedCartridgeId
            )
        }

        // Hub fallback: if user slotted a hub where only a standalone sub-cartridge (or base) is installed,
        // route cleanly to the first installed variant under that same hub instead of mounting an empty folder.
        val targetHubId = requestedDesc?.cartridgeId ?: profile.selectedCartridgeId
        val installedSibling = allBuiltIn.firstOrNull { candidate ->
            candidate.cartridgeId == targetHubId &&
                candidate.sherpaFamily != null &&
                run {
                    val cSub = candidate.requiredSubCartridgeId
                        ?: candidate.cartridgeId.takeIf { it !in setOf("rom_piper", "rom_kokoro_sherpa", "rom_kitten_tts") }
                    hasMinimumRequiredAssets(familyDir(candidate.sherpaFamily, cSub), candidate.sherpaFamily)
                }
        }
        if (installedSibling != null && installedSibling.sherpaFamily != null) {
            val resolvedSub = installedSibling.requiredSubCartridgeId
                ?: installedSibling.cartridgeId.takeIf { it !in setOf("rom_piper", "rom_kokoro_sherpa", "rom_kitten_tts") }
            return MountTarget(
                family = installedSibling.sherpaFamily,
                subCartridgeId = resolvedSub,
                speakerIndex = installedSibling.speakerIndex,
                voiceDisplayName = installedSibling.displayName,
                cartridgeId = installedSibling.cartridgeId
            )
        }

        return MountTarget(
            family = requestedFamily,
            subCartridgeId = requestedSub,
            speakerIndex = requestedDesc?.speakerIndex ?: inferSpeakerFallback(cleanVoiceId),
            voiceDisplayName = requestedDesc?.displayName,
            cartridgeId = requestedDesc?.cartridgeId ?: profile.selectedCartridgeId
        )
    }

    private fun resolveEffectiveCloneEngineId(clonedVoice: ClonedVoiceProfile): String {
        val preferredEngineId = clonedVoice.resolvedCloneEngineId
        if (hasMinimumRequiredAssets(familyDir(clonedVoice.family, preferredEngineId), clonedVoice.family)) {
            return preferredEngineId
        }
        val siblingId = when (clonedVoice.family) {
            SherpaModelFamily.POCKET_TTS -> if (preferredEngineId == "sub_pocket_fp32") "rom_pocket_tts" else "sub_pocket_fp32"
            SherpaModelFamily.ZIPVOICE -> if (preferredEngineId == "sub_zipvoice_full") "rom_zipvoice" else "sub_zipvoice_full"
            else -> preferredEngineId
        }
        return if (hasMinimumRequiredAssets(familyDir(clonedVoice.family, siblingId), clonedVoice.family)) siblingId else preferredEngineId
    }

    override suspend fun warmup(profile: PurrProfile) {
        val loaded = mutex.withLock {
            val cleanVoiceId = profile.primaryVoiceId.substringAfter(':')
            val clonedVoice = clonedVoicesProvider().firstOrNull {
                it.id == profile.primaryVoiceId || it.id == cleanVoiceId
            }
            if (clonedVoice != null) {
                ensureFamilyLoadedLocked(
                    family = clonedVoice.family,
                    subCartridgeId = resolveEffectiveCloneEngineId(clonedVoice)
                )
            } else {
                val target = resolveMountTarget(profile.primaryVoiceId, profile)
                ensureFamilyLoadedLocked(
                    family = target.family,
                    subCartridgeId = target.subCartridgeId
                )
            }
            activeOfflineTts != null
        }
        if (loaded) {
            residencyTimer.scheduleOrRefresh(isWarmupGrace = true)
        }
    }

    suspend fun applyResidencyPolicyNow() {
        residencyTimer.applyPolicyNow { evictResources() }
    }

    override suspend fun synthesizeChunk(
        text: String,
        voiceId: String,
        speakingRate: Float,
        pitch: Float,
        profile: PurrProfile
    ): SynthesisOutcome = withContext(Dispatchers.IO) {
        residencyTimer.beginSynthesis()
        try {
            val clonedVoice = clonedVoicesProvider().firstOrNull { it.id == voiceId }
            if (clonedVoice != null) {
                return@withContext synthesizeCloned(
                    text = text,
                    clonedVoice = clonedVoice,
                    speakingRate = speakingRate,
                    pitch = pitch
                )
            }

            val mountTarget = resolveMountTarget(voiceId, profile)
            val targetFamily = mountTarget.family
            val subCartridgeId = mountTarget.subCartridgeId

            mutex.withLock {
            ensureFamilyLoadedLocked(targetFamily, subCartridgeId)
            val tts = activeOfflineTts
            if (tts == null) {
                // ✨ TRUST RENEWAL UX (v1.8.1): name the CARTRIDGE to slam, not just the voice —
                // "Af Heart cartridge not installed" sent users hunting for a voice they had
                // already downloaded while the actual missing piece was its parent ROM bay.
                val voiceLabel = mountTarget.voiceDisplayName ?: targetFamily.label
                val cartridgeLabel = cartridgeLabelFor(
                    cartridgeId = mountTarget.cartridgeId,
                    subCartridgeId = subCartridgeId,
                    family = targetFamily
                )
                val mountReason = lastMountError
                val summary = if (!mountReason.isNullOrBlank() && familyDir(targetFamily, subCartridgeId).walkTopDown().any { it.extension.equals("onnx", true) }) {
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
            val speakerId = mountTarget.speakerIndex
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
                engineSummary = "sherpa-onnx • ${mountTarget.voiceDisplayName ?: targetFamily.label} (${finalGenerated.sampleRate}Hz -> 24kHz)$suffix"
            )
            }
        } finally {
            residencyTimer.scheduleOrRefresh()
        }
    }

    suspend fun synthesizeCloned(
        text: String,
        clonedVoice: ClonedVoiceProfile,
        speakingRate: Float,
        pitch: Float
    ): SynthesisOutcome = withContext(Dispatchers.IO) {
        residencyTimer.beginSynthesis()
        try {
            val effectiveEngineId = resolveEffectiveCloneEngineId(clonedVoice)

            mutex.withLock {
            ensureFamilyLoadedLocked(clonedVoice.family, effectiveEngineId)
            val tts = activeOfflineTts
            if (tts == null) {
                // ✨ TRUST RENEWAL UX (v1.8.1): name the clone engine cartridge that is missing.
                val cloneSpec = PurrDefaultPresets.modelCatalog().firstOrNull { it.id == effectiveEngineId }
                val cartridgeLabel = cloneSpec?.let { "${it.romCode} ${it.name}" } ?: clonedVoice.family.label
                val mountReason = lastMountError
                val summary = if (!mountReason.isNullOrBlank() && familyDir(clonedVoice.family, effectiveEngineId).walkTopDown().any { it.extension.equals("onnx", true) }) {
                    "$cartridgeLabel failed to mount: $mountReason"
                } else {
                    "$cartridgeLabel cartridge not installed (needed by clone ${clonedVoice.displayName} — download it in Tab 3)"
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
                ensureFamilyLoadedLocked(clonedVoice.family, effectiveEngineId, forceCpu = true)
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
        } finally {
            residencyTimer.scheduleOrRefresh()
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
            residencyTimer.markEvicted()
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
        // ✨ ZONOS (v2.7.0): native ggml dirs — never routed through sherpa in practice.
        SherpaModelFamily.ZONOS3 -> File(modelsBaseDir, "zonos3")
        SherpaModelFamily.ZONOS1 -> File(modelsBaseDir, "zonos1")
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

    private fun shortMountLabelFor(family: SherpaModelFamily, subCartridgeId: String?): String = when (subCartridgeId) {
        "rom_kokoro_fp32" -> "Kokoro 82M FP32"
        "rom_kokoro_v11" -> "Kokoro v1.1"
        "sub_pocket_fp32" -> "Pocket FP32"
        "sub_zipvoice_full" -> "ZipVoice Full"
        "rom_inflect_v2" -> "Inflect V2"
        "rom_icefall_vits" -> "Icefall VITS"
        "rom_coqui_vits" -> "Coqui VITS"
        "rom_meta_mms" -> "Meta MMS"
        "rom_vctk_studio" -> "VCTK Studio"
        "rom_mimic3_neural" -> "Mimic3 Neural"
        "rom_ljs_lexicon" -> "LJS Lexicon"
        "rom_aishell3_zh" -> "AISHELL-3 174"
        else -> when (family) {
            SherpaModelFamily.KOKORO -> "Kokoro 82M"
            SherpaModelFamily.KITTEN_TTS -> "KittenTTS"
            SherpaModelFamily.SUPERTONIC_3 -> "Supertonic 3"
            SherpaModelFamily.POCKET_TTS -> "Pocket TTS"
            SherpaModelFamily.ZIPVOICE -> "ZipVoice"
            SherpaModelFamily.MATCHA -> "Matcha"
            SherpaModelFamily.PIPER -> "Piper VITS"
            SherpaModelFamily.MELO_TTS -> "MeloTTS"
            SherpaModelFamily.ZONOS3 -> "Zonos3 Mini"
            SherpaModelFamily.ZONOS1 -> "Zonos1 Studio"
        }
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
        residencyTimer.markEvicted()

        val dir = familyDir(family, subCartridgeId)
        fun scanNonEspeakFiles(root: File): List<File> = root.walkTopDown()
            .filter {
                it.isFile &&
                    it.length() > 0L &&
                    !it.absolutePath.contains("${File.separator}espeak-ng-data${File.separator}")
            }
            .toList()

        var files = scanNonEspeakFiles(dir)
        if (!hasMinimumRequiredAssets(dir, family)) {
            if (tryRepairSherpaDirFromArchiveLocked(family, subCartridgeId, dir)) {
                files = scanNonEspeakFiles(dir)
            }
        }
        if (files.none { it.extension.equals("onnx", ignoreCase = true) }) {
            // ✨ TRUST RENEWAL (v1.8.1): say WHY the mount refused — support gold when a user
            // swears they downloaded a cartridge but every voice fail-softs to OS speech.
            lastMountError = "Missing .onnx model under ${dir.name} (${files.size} files)"
            Log.w(TAG, "[MOUNT_MISS] family=$family sub=$subCartridgeId — no .onnx under ${dir.absolutePath} (${files.size} files present)")
            return
        }

        val tokensPath = files.firstOrNull { it.name == "tokens.txt" }?.absolutePath.orEmpty()
        val rawEspeakDir = dir.walkTopDown().firstOrNull { it.isDirectory && it.name == "espeak-ng-data" }
        if (rawEspeakDir != null && !isCompleteEspeakDir(rawEspeakDir)) {
            if (tryRepairSherpaDirFromArchiveLocked(family, subCartridgeId, dir)) {
                files = scanNonEspeakFiles(dir)
            }
        }

        val sortedLexiconFiles = files.filter { it.name.startsWith("lexicon") && it.extension.equals("txt", ignoreCase = true) }
            .sortedWith(
                compareBy<File>(
                    { f ->
                        when (f.name) {
                            "lexicon.txt" -> 0
                            "lexicon-us-en.txt" -> 1
                            "lexicon-gb-en.txt" -> 2
                            "lexicon-zh.txt" -> 3
                            else -> 4
                        }
                    },
                    { it.name }
                )
            )
        val multiLexicons = sortedLexiconFiles.joinToString(",") { it.absolutePath }
        val singleLexicon = sortedLexiconFiles.firstOrNull()?.absolutePath.orEmpty()

        val repairedEspeakDir = dir.walkTopDown().firstOrNull { it.isDirectory && it.name == "espeak-ng-data" }
        val allowSharedEspeakFallback = when (family) {
            SherpaModelFamily.KOKORO,
            SherpaModelFamily.KITTEN_TTS -> true
            SherpaModelFamily.PIPER -> sortedLexiconFiles.isEmpty() && !isCharacterFrontendVits(subCartridgeId, dir)
            else -> false
        }
        val dataDir = resolveStableEspeakDir(repairedEspeakDir, allowSharedEspeakFallback)
        if (repairedEspeakDir != null && dataDir.isEmpty()) {
            lastMountError = "Incomplete espeak-ng-data in ${dir.name} (missing phontab/phonindex/phondata/intonations) — re-download cartridge"
            Log.w(TAG, "[MOUNT_MISS] $lastMountError")
            return
        }

        // Order rule FSTs properly (`phone*` -> `date*` -> `number*` -> `new_heteronym*` -> others)
        // matching k2-fsa's upstream generate-tts-apk-script pipeline. For MeloTTS ZH-EN exclude
        // `number.fst` if absent or keep only upstream rules; NEVER load redundant 180MB rule.far
        // when `.fst` rule files are already present (prevents mobile OOM / mount timeout).
        val ruleFstList = files.filter { it.extension.equals("fst", ignoreCase = true) }
            .sortedWith(
                compareBy<File>(
                    { f ->
                        val n = f.name.lowercase()
                        when {
                            n.startsWith("phone") -> 0
                            n.startsWith("date") -> 1
                            n.startsWith("number") -> 2
                            n.startsWith("new_heteronym") -> 3
                            else -> 4
                        }
                    },
                    { it.name }
                )
            )
        val ruleFsts = ruleFstList.joinToString(",") { it.absolutePath }
        val allowRuleFarsWithFsts = subCartridgeId == "rom_aishell3_zh"
        val ruleFars = if (ruleFsts.isEmpty() || allowRuleFarsWithFsts) {
            files.filter { it.extension.equals("far", ignoreCase = true) }
                .sortedBy { it.name }
                .joinToString(",") { it.absolutePath }
        } else {
            ""
        }

        val threads = if (smartOffload) hardwareProfile.optimalCpuThreads else 4

        fun pickPreferredOnnx(candidates: List<File>): String {
            val best = candidates.firstOrNull { it.name.contains("int8", ignoreCase = true) }
                ?: candidates.firstOrNull()
            return best?.absolutePath.orEmpty()
        }

        fun resolveMatchaVocoder(): String = resolveMatchaVocoderForDir(dir, files)

        fun buildConfigForProvider(providerName: String): OfflineTtsModelConfig? = when (family) {
            // ✨ ZONOS (v2.7.0): native ggml engines — never mounted through sherpa-onnx.
            SherpaModelFamily.ZONOS3,
            SherpaModelFamily.ZONOS1 -> null
            SherpaModelFamily.KOKORO -> {
                val onnx = pickPreferredOnnx(files.filter { it.extension.equals("onnx", ignoreCase = true) })
                val voices = files.firstOrNull { it.name == "voices.bin" }?.absolutePath.orEmpty()
                if (onnx.isEmpty() || voices.isEmpty() || tokensPath.isEmpty() || dataDir.isEmpty()) {
                    lastMountError = "Missing Kokoro asset (onnx=${onnx.isNotEmpty()}, voices=${voices.isNotEmpty()}, tokens=${tokensPath.isNotEmpty()}, espeak=${dataDir.isNotEmpty()})"
                    Log.w(TAG, "[MOUNT_MISS] $lastMountError")
                    null
                } else {
                    OfflineTtsModelConfig(
                        kokoro = OfflineTtsKokoroModelConfig(
                            model = onnx,
                            voices = voices,
                            tokens = tokensPath,
                            dataDir = dataDir,
                            lexicon = multiLexicons
                        ),
                        numThreads = threads,
                        provider = providerName
                    )
                }
            }
            SherpaModelFamily.KITTEN_TTS -> {
                val onnx = pickPreferredOnnx(files.filter { it.extension.equals("onnx", ignoreCase = true) })
                val voices = files.firstOrNull { it.name == "voices.bin" }?.absolutePath.orEmpty()
                if (onnx.isEmpty() || voices.isEmpty() || tokensPath.isEmpty() || dataDir.isEmpty()) {
                    lastMountError = "Missing KittenTTS asset (onnx=${onnx.isNotEmpty()}, voices=${voices.isNotEmpty()}, tokens=${tokensPath.isNotEmpty()}, espeak=${dataDir.isNotEmpty()})"
                    Log.w(TAG, "[MOUNT_MISS] $lastMountError")
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
                val dur = files.firstOrNull { it.name.contains("duration_predictor") && it.extension.equals("onnx", true) }?.absolutePath.orEmpty()
                val txtEnc = files.firstOrNull { it.name.contains("text_encoder") && it.extension.equals("onnx", true) }?.absolutePath.orEmpty()
                val vecEst = files.firstOrNull { it.name.contains("vector_estimator") && it.extension.equals("onnx", true) }?.absolutePath.orEmpty()
                val voc = files.firstOrNull { it.name.contains("vocoder") && it.extension.equals("onnx", true) }?.absolutePath.orEmpty()
                val ttsJson = files.firstOrNull { it.name == "tts.json" }?.absolutePath.orEmpty()
                val uniIdx = files.firstOrNull { it.name.contains("unicode_indexer") }?.absolutePath.orEmpty()
                val vStyle = files.firstOrNull { it.name.contains("voice") && it.extension.equals("bin", true) }?.absolutePath.orEmpty()
                if (dur.isEmpty() || txtEnc.isEmpty() || vecEst.isEmpty() || voc.isEmpty() || ttsJson.isEmpty() || uniIdx.isEmpty() || vStyle.isEmpty()) {
                    lastMountError = "Missing Supertonic 3 asset (dp=${dur.isNotEmpty()}, enc=${txtEnc.isNotEmpty()}, vec=${vecEst.isNotEmpty()}, voc=${voc.isNotEmpty()}, json=${ttsJson.isNotEmpty()}, idx=${uniIdx.isNotEmpty()}, voice=${vStyle.isNotEmpty()})"
                    Log.w(TAG, "[MOUNT_MISS] $lastMountError")
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
                val acoustic = files.firstOrNull {
                    it.extension.equals("onnx", ignoreCase = true) && !isVocoderOnnx(it)
                }?.absolutePath.orEmpty()
                val vocoder = resolveMatchaVocoder()
                if (acoustic.isEmpty() || vocoder.isEmpty() || tokensPath.isEmpty()) {
                    lastMountError = "Missing Matcha asset (acoustic=${acoustic.isNotEmpty()}, vocoder=${vocoder.isNotEmpty()}, tokens=${tokensPath.isNotEmpty()})"
                    Log.w(TAG, "[MOUNT_MISS] $lastMountError in ${dir.absolutePath}")
                    null
                } else {
                    OfflineTtsModelConfig(
                        matcha = OfflineTtsMatchaModelConfig(
                            acousticModel = acoustic,
                            vocoder = vocoder,
                            lexicon = multiLexicons,
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
                val onnx = pickPreferredOnnx(files.filter { it.extension.equals("onnx", ignoreCase = true) })
                // OfflineTtsVitsImpl (Lexicon / MeloTtsLexicon) takes a SINGLE file path (not comma-joined)
                val vitsLexicon = if (dataDir.isNotEmpty() && family == SherpaModelFamily.PIPER) "" else singleLexicon
                if (onnx.isEmpty() || tokensPath.isEmpty()) {
                    lastMountError = "Missing VITS asset (onnx=${onnx.isNotEmpty()}, tokens=${tokensPath.isNotEmpty()})"
                    Log.w(TAG, "[MOUNT_MISS] $lastMountError in ${dir.absolutePath}")
                    null
                } else {
                    OfflineTtsModelConfig(
                        vits = OfflineTtsVitsModelConfig(
                            model = onnx,
                            lexicon = vitsLexicon,
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
                val lmFlow = files.firstOrNull { it.name.contains("lm_flow") && it.extension.equals("onnx", true) }?.absolutePath.orEmpty()
                val lmMain = files.firstOrNull { it.name.contains("lm_main") && it.extension.equals("onnx", true) }?.absolutePath.orEmpty()
                val enc = files.firstOrNull { it.name.contains("encoder") && it.extension.equals("onnx", true) }?.absolutePath.orEmpty()
                val dec = files.firstOrNull { it.name.contains("decoder") && it.extension.equals("onnx", true) }?.absolutePath.orEmpty()
                val txtCond = files.firstOrNull { it.name.contains("text_conditioner") && it.extension.equals("onnx", true) }?.absolutePath.orEmpty()
                val vocab = files.firstOrNull { it.name.contains("vocab") && it.extension.equals("json", true) }?.absolutePath.orEmpty()
                val scores = files.firstOrNull { it.name.contains("token_scores") && it.extension.equals("json", true) }?.absolutePath.orEmpty()
                if (lmFlow.isEmpty() || lmMain.isEmpty() || enc.isEmpty() || dec.isEmpty() || txtCond.isEmpty() || vocab.isEmpty() || scores.isEmpty()) {
                    lastMountError = "Missing Pocket TTS asset (flow=${lmFlow.isNotEmpty()}, main=${lmMain.isNotEmpty()}, enc=${enc.isNotEmpty()}, dec=${dec.isNotEmpty()}, cond=${txtCond.isNotEmpty()}, vocab=${vocab.isNotEmpty()}, scores=${scores.isNotEmpty()})"
                    Log.w(TAG, "[MOUNT_MISS] $lastMountError")
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
                val enc = files.firstOrNull { it.name.contains("encoder") && it.extension.equals("onnx", true) }?.absolutePath.orEmpty()
                val dec = files.firstOrNull { it.name.contains("decoder") && it.extension.equals("onnx", true) }?.absolutePath.orEmpty()
                val voc = files.firstOrNull { it.name.contains("vocoder") && it.extension.equals("onnx", true) }?.absolutePath.orEmpty()
                if (enc.isEmpty() || dec.isEmpty() || voc.isEmpty() || tokensPath.isEmpty()) {
                    lastMountError = "Missing ZipVoice asset (enc=${enc.isNotEmpty()}, dec=${dec.isNotEmpty()}, voc=${voc.isNotEmpty()}, tokens=${tokensPath.isNotEmpty()})"
                    Log.w(TAG, "[MOUNT_MISS] $lastMountError")
                    null
                } else {
                    OfflineTtsModelConfig(
                        zipvoice = OfflineTtsZipVoiceModelConfig(
                            tokens = tokensPath,
                            encoder = enc,
                            decoder = dec,
                            vocoder = voc,
                            dataDir = dataDir,
                            lexicon = singleLexicon
                        ),
                        numThreads = threads,
                        provider = providerName
                    )
                }
            }
        }

        val supportsTextRules = family in setOf(
            SherpaModelFamily.KOKORO,
            SherpaModelFamily.MATCHA,
            SherpaModelFamily.MELO_TTS,
            SherpaModelFamily.PIPER
        )
        val ruleFstsArg = if (supportsTextRules) ruleFsts else ""
        val ruleFarsArg = if (supportsTextRules && (ruleFsts.isEmpty() || allowRuleFarsWithFsts)) ruleFars else ""

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
                OfflineTts(
                    assetManager = null,
                    config = OfflineTtsConfig(
                        model = nnapiCfg,
                        ruleFsts = ruleFstsArg,
                        ruleFars = ruleFarsArg
                    )
                )
            }
            if (nnapiAttempt.isSuccess) {
                activeOfflineTts = nnapiAttempt.getOrThrow()
                activeUsedNnapi = true
                _statusFlow.value = HardwareStatusBadge.NPU_ACTIVE
                residencyTimer.markMounted("${shortMountLabelFor(family, subCartridgeId)} (NPU)")
                Log.i(TAG, "Mounted sherpa-onnx ${family.label} via NNAPI (Smart Offload) from ${dir.absolutePath}")
                return
            }
            Log.w(TAG, "sherpa-onnx ${family.label} NNAPI mount failed, falling back to CPU: ${nnapiAttempt.exceptionOrNull()?.message}")
        }

        val cpuCfg = buildConfigForProvider("cpu") ?: return
        val cpuAttempt = runCatching {
            val config = OfflineTtsConfig(
                model = cpuCfg,
                ruleFsts = ruleFstsArg,
                ruleFars = ruleFarsArg
            )
            OfflineTts(assetManager = null, config = config)
        }
        val mountedTts = cpuAttempt.getOrNull() ?: run {
            val firstErr = cpuAttempt.exceptionOrNull()
            // Self-heal once from cached .tar.bz2 in case extraction was partial or interrupted
            if (tryRepairSherpaDirFromArchiveLocked(family, subCartridgeId, dir)) {
                val retryAttempt = runCatching {
                    val config = OfflineTtsConfig(
                        model = cpuCfg,
                        ruleFsts = ruleFstsArg,
                        ruleFars = ruleFarsArg
                    )
                    OfflineTts(assetManager = null, config = config)
                }
                if (retryAttempt.isSuccess) {
                    retryAttempt.getOrThrow()
                } else {
                    val retryErr = retryAttempt.exceptionOrNull() ?: firstErr
                    lastMountError = retryErr?.message?.takeIf { it.isNotBlank() }
                        ?: retryErr?.javaClass?.simpleName
                        ?: "OfflineTts mount failed"
                    Log.e(TAG, "Failed to mount sherpa-onnx ${family.label} after repair: $lastMountError", retryErr)
                    activeOfflineTts = null
                    return
                }
            } else {
                lastMountError = firstErr?.message?.takeIf { it.isNotBlank() }
                    ?: firstErr?.javaClass?.simpleName
                    ?: "OfflineTts mount failed"
                Log.e(TAG, "Failed to mount sherpa-onnx ${family.label}: $lastMountError", firstErr)
                activeOfflineTts = null
                return
            }
        }
        activeOfflineTts = mountedTts
        _statusFlow.value = HardwareStatusBadge.CPU_NEON
        residencyTimer.markMounted(shortMountLabelFor(family, subCartridgeId))
        Log.i(TAG, "Mounted sherpa-onnx ${family.label} cleanly from ${dir.absolutePath}")
    }

    private fun resolveStableEspeakDir(rawEspeakDir: File?, allowSharedFallback: Boolean): String {
        if (rawEspeakDir != null) {
            if (!isCompleteEspeakDir(rawEspeakDir)) return ""
            if (!isCompleteEspeakDir(sharedEspeakDir)) {
                sharedEspeakDir.parentFile?.mkdirs()
                runCatching { rawEspeakDir.copyRecursively(sharedEspeakDir, overwrite = true) }
            }
            return sharedEspeakDir.takeIf { isCompleteEspeakDir(it) }?.absolutePath ?: rawEspeakDir.absolutePath
        }
        return if (allowSharedFallback && isCompleteEspeakDir(sharedEspeakDir)) sharedEspeakDir.absolutePath else ""
    }

    private fun tryRepairSherpaDirFromArchiveLocked(
        family: SherpaModelFamily,
        subCartridgeId: String?,
        targetDir: File
    ): Boolean {
        val catalog = PurrDefaultPresets.modelCatalog()
        val spec = subCartridgeId?.let { id -> catalog.firstOrNull { it.id == id } }
            ?: catalog.firstOrNull { !it.isSubCartridge && it.sherpaFamily == family }
            ?: return false
        val downloadsDir = File(context.filesDir, "models/downloads")
        val archiveFile = File(downloadsDir, spec.fileName)
        if (!archiveFile.isFile || archiveFile.length() <= 1024L || !spec.isArchiveTarBz2) {
            return false
        }
        val stagingDir = File(targetDir.parentFile ?: targetDir, "${targetDir.name}.repair_tmp")
        return runCatching {
            stagingDir.deleteRecursively()
            stagingDir.mkdirs()
            targetDir.mkdirs()
            val canonicalRoot = stagingDir.canonicalPath
            BufferedInputStream(FileInputStream(archiveFile)).use { bis ->
                BZip2CompressorInputStream(bis).use { bzIn ->
                    TarArchiveInputStream(bzIn).use { tarIn ->
                        while (true) {
                            val entry = tarIn.nextEntry ?: break
                            val outFile = File(stagingDir, entry.name)
                            if (!outFile.canonicalPath.startsWith(canonicalRoot)) {
                                throw SecurityException("Blocked unsafe tar entry path: ${entry.name}")
                            }
                            if (entry.isDirectory) {
                                outFile.mkdirs()
                            } else {
                                outFile.parentFile?.mkdirs()
                                FileOutputStream(outFile).use { out -> tarIn.copyTo(out) }
                            }
                        }
                    }
                }
            }
            stagingDir.copyRecursively(targetDir, overwrite = true)
            targetDir.walkTopDown().firstOrNull { it.isDirectory && it.name == "espeak-ng-data" }?.let { espeak ->
                if (isCompleteEspeakDir(espeak) && !isCompleteEspeakDir(sharedEspeakDir)) {
                    sharedEspeakDir.parentFile?.mkdirs()
                    runCatching { espeak.copyRecursively(sharedEspeakDir, overwrite = true) }
                }
            }
            val companionName = spec.companionFileName
            if (!companionName.isNullOrBlank()) {
                val cachedCompanion = File(downloadsDir, companionName)
                val destCompanion = File(targetDir, companionName)
                if (cachedCompanion.isFile && cachedCompanion.length() > 1024L && (!destCompanion.isFile || destCompanion.length() <= 1024L)) {
                    cachedCompanion.copyTo(destCompanion, overwrite = true)
                }
            }
            Log.i(TAG, "[SELF_HEAL] Repaired sherpa extract for ${spec.id} into ${targetDir.name}")
            true
        }.getOrElse { err ->
            Log.w(TAG, "[SELF_HEAL] Archive repair failed for ${spec.id}: ${err.message}")
            false
        }.also {
            runCatching { stagingDir.deleteRecursively() }
        }
    }

    private fun inferSpeakerFallback(voiceId: String): Int {
        val cleanVoiceId = voiceId.substringAfter(':')
        val descriptor = PurrDefaultPresets.builtInVoices().firstOrNull { it.id == cleanVoiceId }
        if (descriptor != null) return descriptor.speakerIndex
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
                cleanId.startsWith("mimic3_") ||
                cleanId.startsWith("ljs_") ||
                cleanId.startsWith("aishell3_") -> SherpaModelFamily.PIPER
            else -> defaultFamily
        }
    }
}
