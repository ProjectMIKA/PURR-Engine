package com.projectmika.purrtts.engine.qwen3

import android.content.Context
import android.util.Log
import com.projectmika.purrtts.core.purr.ArchetypeTone
import com.projectmika.purrtts.core.purr.BackendType
import com.projectmika.purrtts.core.purr.ClonedVoiceProfile
import com.projectmika.purrtts.core.purr.HardwareStatusBadge
import com.projectmika.purrtts.core.purr.PurrDspProcessor
import com.projectmika.purrtts.core.purr.PurrProfile
import com.projectmika.purrtts.core.purr.PurrSentimentScorer
import com.projectmika.purrtts.engine.PurrSynthesizer
import com.projectmika.purrtts.engine.SynthesisOutcome
import java.io.File
import kotlin.math.roundToInt
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
 * ✨ MIKA'S LOCAL QWEN3-TTS ENGINE (0.6B CUSTOM / 0.6B BASE CLONE / 1.7B VOICEDESIGN) ✨
 * Backed by native `llama.cpp` GGUF Talker + Predictor and `ONNX Runtime` Codec24Hz Vocoder
 * via `libpurr_qwen3_jni.so`. Keeps at most one checkpoint resident with a 5-minute idle eviction
 * timer to protect Android LMKD while streaming long audiobooks cleanly.
 */
class Qwen3TtsEngine(
    private val context: Context,
    private val clonedVoicesProvider: () -> List<ClonedVoiceProfile>,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : PurrSynthesizer {

    companion object {
        private const val TAG = "PurrQwen3Engine"
        private const val SAMPLE_RATE_HZ = PurrDspProcessor.SAMPLE_RATE_HZ
        private const val IDLE_EVICTION_MS = 5 * 60 * 1000L

        const val SPEC_CUSTOM_06B = "rom_qwen3_custom_06b"
        const val SPEC_BASE_CLONE_06B = "sub_qwen3_base_06b"
        const val SPEC_DESIGN_17B = "sub_qwen3_design_17b"

        // Matches Qwen3-TTS `model_profile.json` language IDs
        const val LANG_AUTO = -1
        const val LANG_EN = 2050
        const val LANG_DE = 2053
        const val LANG_ES = 2054
        const val LANG_ZH = 2055
        const val LANG_JA = 2058
        const val LANG_FR = 2061
        const val LANG_KO = 2064
        const val LANG_RU = 2069
        const val LANG_IT = 2070
        const val LANG_PT = 2071

        private val SPEAKER_MAP = mapOf(
            "qwen3_vivian" to "vivian",
            "qwen3_serena" to "serena",
            "qwen3_ryan" to "ryan",
            "qwen3_aiden" to "aiden",
            "qwen3_dylan" to "dylan",
            "qwen3_eric" to "eric",
            "qwen3_ono_anna" to "ono_anna",
            "qwen3_sohee" to "sohee",
            "qwen3_uncle_fu" to "uncle_fu"
        )

        fun detectQwenLanguageId(text: String, fallbackLanguageCode: String = "en-US"): Int {
            var hasJaKana = false
            var hasKoHangul = false
            var hasCjk = false
            var hasCyrillic = false
            for (ch in text) {
                val code = ch.code
                when {
                    code in 0x3040..0x30FF -> hasJaKana = true
                    code in 0xAC00..0xD7AF || code in 0x1100..0x11FF -> hasKoHangul = true
                    code in 0x4E00..0x9FFF -> hasCjk = true
                    code in 0x0400..0x04FF -> hasCyrillic = true
                }
            }
            return when {
                hasJaKana -> LANG_JA
                hasKoHangul -> LANG_KO
                hasCjk -> LANG_ZH
                hasCyrillic -> LANG_RU
                fallbackLanguageCode.startsWith("ja", ignoreCase = true) -> LANG_JA
                fallbackLanguageCode.startsWith("ko", ignoreCase = true) -> LANG_KO
                fallbackLanguageCode.startsWith("zh", ignoreCase = true) -> LANG_ZH
                fallbackLanguageCode.startsWith("fr", ignoreCase = true) -> LANG_FR
                fallbackLanguageCode.startsWith("es", ignoreCase = true) -> LANG_ES
                fallbackLanguageCode.startsWith("de", ignoreCase = true) -> LANG_DE
                fallbackLanguageCode.startsWith("it", ignoreCase = true) -> LANG_IT
                fallbackLanguageCode.startsWith("pt", ignoreCase = true) -> LANG_PT
                fallbackLanguageCode.startsWith("ru", ignoreCase = true) -> LANG_RU
                else -> LANG_EN
            }
        }
    }

    override val backendType: BackendType = BackendType.QWEN3_NATIVE

    private val _statusFlow = MutableStateFlow(HardwareStatusBadge.IDLE)
    override val statusFlow: StateFlow<HardwareStatusBadge> = _statusFlow.asStateFlow()

    private val mutex = Mutex()
    private var idleJob: Job? = null
    private var activeHandle: Long = 0L
    private var activeSpecId: String? = null

    private fun modelDirForSpec(specId: String): File =
        File(context.filesDir, "models/native/$specId")

    private fun isSpecDownloaded(specId: String): Boolean {
        val dir = modelDirForSpec(specId)
        return File(dir, "qwen3_tts_talker.q5_k.gguf").exists() &&
            File(dir, "model_profile.json").exists() &&
            File(dir, "qwen3_tts_decoder.fp16.onnx").exists()
    }

    override val isModelReady: Boolean
        get() = isSpecDownloaded(SPEC_CUSTOM_06B) ||
            isSpecDownloaded(SPEC_BASE_CLONE_06B) ||
            isSpecDownloaded(SPEC_DESIGN_17B)

    override suspend fun warmup(profile: PurrProfile) {
        mutex.withLock {
            val cleanVoiceId = profile.primaryVoiceId.substringAfter(':')
            val targetSpec = resolveSpecForVoice(cleanVoiceId)
            ensureEngineLoadedLocked(targetSpec)
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
            return@withContext SynthesisOutcome(
                pcmFloat24k = FloatArray(0),
                sampleRateHz = SAMPLE_RATE_HZ,
                hardwareBadge = HardwareStatusBadge.IDLE,
                engineSummary = "Empty text chunk"
            )
        }

        if (!Qwen3NativeBridge.isLoaded) {
            return@withContext SynthesisOutcome(
                pcmFloat24k = FloatArray(0),
                sampleRateHz = SAMPLE_RATE_HZ,
                hardwareBadge = HardwareStatusBadge.IDLE,
                engineSummary = "Qwen3 native JNI runtime unavailable on this ABI",
                isFallbackNeuralPreview = true
            )
        }

        val cleanVoiceId = voiceId.substringAfter(':')
        val clonedVoice = clonedVoicesProvider().firstOrNull {
            it.id == cleanVoiceId && it.resolvedCloneEngineId == SPEC_BASE_CLONE_06B
        }
        val targetSpec = if (clonedVoice != null) SPEC_BASE_CLONE_06B else resolveSpecForVoice(cleanVoiceId)
        val nThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)

        mutex.withLock {
            if (!isSpecDownloaded(targetSpec)) {
                return@withContext SynthesisOutcome(
                    pcmFloat24k = FloatArray(0),
                    sampleRateHz = SAMPLE_RATE_HZ,
                    hardwareBadge = HardwareStatusBadge.IDLE,
                    engineSummary = "Qwen3 cartridge ($targetSpec) not downloaded yet",
                    isFallbackNeuralPreview = true
                )
            }

            val handle = ensureEngineLoadedLocked(targetSpec)
            if (handle == 0L) {
                val err = Qwen3NativeBridge.nativeGetLastError(0L).ifBlank { "nativeCreate failed" }
                return@withContext SynthesisOutcome(
                    pcmFloat24k = FloatArray(0),
                    sampleRateHz = SAMPLE_RATE_HZ,
                    hardwareBadge = HardwareStatusBadge.IDLE,
                    engineSummary = "Qwen3 load error: $err",
                    isFallbackNeuralPreview = true
                )
            }

            val langId = detectQwenLanguageId(text)
            val rawAudio: FloatArray? = when {
                clonedVoice != null -> {
                    val wavFile = File(clonedVoice.referenceWavPath)
                    if (!wavFile.exists() || wavFile.length() <= 44L) {
                        return@withContext SynthesisOutcome(
                            pcmFloat24k = FloatArray(0),
                            sampleRateHz = SAMPLE_RATE_HZ,
                            hardwareBadge = HardwareStatusBadge.IDLE,
                            engineSummary = "Qwen3 Base clone WAV missing for ${clonedVoice.displayName}",
                            isFallbackNeuralPreview = true
                        )
                    }
                    Qwen3NativeBridge.nativeSynthesizeClone(
                        handle = handle,
                        text = text,
                        refWavPath = wavFile.absolutePath,
                        refText = clonedVoice.referenceTranscript,
                        languageId = langId,
                        nThreads = nThreads
                    )
                }
                cleanVoiceId == "qwen3_base_clone_default" -> {
                    return@withContext SynthesisOutcome(
                        pcmFloat24k = FloatArray(0),
                        sampleRateHz = SAMPLE_RATE_HZ,
                        hardwareBadge = HardwareStatusBadge.IDLE,
                        engineSummary = "Record or import a 3s voice sample in Clone Studio (Qwen3 0.6B Base)",
                        isFallbackNeuralPreview = true
                    )
                }
                targetSpec == SPEC_DESIGN_17B -> {
                    val designPrompt = profile.voiceDesignPrompt.trim()
                        .ifBlank { "Warm, expressive studio narrator voice with crisp natural cadence." }
                    Qwen3NativeBridge.nativeSynthesizeDesign(
                        handle = handle,
                        text = text,
                        instruct = designPrompt,
                        languageId = langId,
                        nThreads = nThreads
                    )
                }
                else -> {
                    val speaker = SPEAKER_MAP[cleanVoiceId] ?: "vivian"
                    val instruct = buildSentimentInstruct(text, profile)
                    Qwen3NativeBridge.nativeSynthesizeCustom(
                        handle = handle,
                        text = text,
                        speaker = speaker,
                        instruct = instruct,
                        languageId = langId,
                        nThreads = nThreads
                    )
                }
            }

            if (rawAudio == null || rawAudio.isEmpty()) {
                val err = Qwen3NativeBridge.nativeGetLastError(handle).ifBlank { "Empty PCM buffer" }
                return@withContext SynthesisOutcome(
                    pcmFloat24k = FloatArray(0),
                    sampleRateHz = SAMPLE_RATE_HZ,
                    hardwareBadge = HardwareStatusBadge.IDLE,
                    engineSummary = "Qwen3 synthesis error: $err",
                    isFallbackNeuralPreview = true
                )
            }

            _statusFlow.value = HardwareStatusBadge.CPU_NEON
            var shaped = rawAudio
            // Apply clean speaking rate time-scaling only when significantly off 1.0x
            if (speakingRate !in 0.96f..1.04f) {
                shaped = timeScaleLinear(shaped, speakingRate.coerceIn(0.65f, 1.65f))
            }
            val finalPcm = if (pitch in 0.995f..1.005f) {
                shaped
            } else {
                PurrDspProcessor.shiftPitchCubic(shaped, pitch.coerceIn(0.88f, 1.12f))
            }

            val label = when {
                clonedVoice != null -> "Qwen3 0.6B Clone • ${clonedVoice.displayName}"
                targetSpec == SPEC_DESIGN_17B -> "Qwen3 1.7B VoiceDesign"
                else -> "Qwen3 0.6B • ${(SPEAKER_MAP[cleanVoiceId] ?: "vivian").replaceFirstChar { it.uppercase() }}"
            }
            SynthesisOutcome(
                pcmFloat24k = finalPcm,
                sampleRateHz = SAMPLE_RATE_HZ,
                hardwareBadge = HardwareStatusBadge.CPU_NEON,
                engineSummary = label
            )
        }
    }

    override suspend fun evictResources() {
        mutex.withLock {
            if (activeHandle != 0L) {
                runCatching { Qwen3NativeBridge.nativeDestroy(activeHandle) }
                activeHandle = 0L
                activeSpecId = null
                _statusFlow.value = HardwareStatusBadge.IDLE
                Log.i(TAG, "[QWEN3_EVICT] Destroyed native LunavoxEngine instance.")
            }
        }
    }

    private fun resolveSpecForVoice(cleanVoiceId: String): String = when {
        cleanVoiceId == "qwen3_design_custom" -> SPEC_DESIGN_17B
        cleanVoiceId == "qwen3_base_clone_default" -> SPEC_BASE_CLONE_06B
        else -> SPEC_CUSTOM_06B
    }

    private fun ensureEngineLoadedLocked(specId: String): Long {
        if (activeHandle != 0L && activeSpecId == specId) {
            return activeHandle
        }
        if (activeHandle != 0L) {
            runCatching { Qwen3NativeBridge.nativeDestroy(activeHandle) }
            activeHandle = 0L
            activeSpecId = null
        }

        val dir = modelDirForSpec(specId)
        if (!isSpecDownloaded(specId)) return 0L
        val nThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
        val libDir = Qwen3NativeBridge.nativeLibDir(context)
        val handle = Qwen3NativeBridge.nativeCreate(dir.absolutePath, libDir, nThreads)
        if (handle != 0L) {
            activeHandle = handle
            activeSpecId = specId
            _statusFlow.value = HardwareStatusBadge.CPU_NEON
            Log.i(TAG, "Mounted Qwen3-TTS [$specId] from ${dir.absolutePath} with $nThreads threads")
        }
        return handle
    }

    private fun buildSentimentInstruct(text: String, profile: PurrProfile): String {
        if (!profile.sentimentConfig.enabled) return ""
        val tone = profile.sentimentConfig.toneOverride
        if (tone == ArchetypeTone.NORMAL) return ""
        val sentiment = PurrSentimentScorer.score(text, tone)
        return when (sentiment.dominantLabel) {
            "JOY" -> "Cheerful, warm and bright tone"
            "LOVE" -> "Soft, gentle and affectionate tone"
            "ANGER" -> "Firm, sharp and annoyed tone"
            "SADNESS" -> "Quiet, melancholy and soft tone"
            "FLUSTERED" -> "Shy, flustered and hesitant tone"
            else -> ""
        }
    }

    private fun timeScaleLinear(samples: FloatArray, rate: Float): FloatArray {
        if (samples.size < 64 || rate in 0.96f..1.04f) return samples
        // Simple WSOLA-lite grain overlap-add to keep pitch intact when adjusting pace
        val outLen = (samples.size / rate).roundToInt().coerceAtLeast(16)
        val out = FloatArray(outLen)
        val winSize = 480 // 20ms at 24kHz
        val synHop = winSize / 2
        val anaHop = (synHop * rate).coerceAtLeast(16f)
        val norm = FloatArray(outLen)
        var synPos = 0
        var anaPos = 0f

        while (synPos < outLen) {
            val aStart = anaPos.roundToInt().coerceIn(0, (samples.size - 1).coerceAtLeast(0))
            for (i in 0 until winSize) {
                val dst = synPos + i
                val src = aStart + i
                if (dst >= outLen || src >= samples.size) break
                val w = 0.5f * (1f - kotlin.math.cos(2.0 * Math.PI * i / winSize).toFloat())
                out[dst] += samples[src] * w
                norm[dst] += w
            }
            synPos += synHop
            anaPos += anaHop
            if (anaPos >= samples.size - 1) break
        }
        for (i in out.indices) {
            if (norm[i] > 1e-4f) out[i] /= norm[i]
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
