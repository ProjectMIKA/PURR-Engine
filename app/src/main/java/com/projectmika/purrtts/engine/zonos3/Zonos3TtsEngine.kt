package com.projectmika.purrtts.engine.zonos3

import android.content.Context
import android.util.Log
import com.projectmika.purrtts.core.purr.BackendType
import com.projectmika.purrtts.core.purr.ClonedVoiceProfile
import com.projectmika.purrtts.core.purr.HardwareStatusBadge
import com.projectmika.purrtts.core.purr.PurrDspProcessor
import com.projectmika.purrtts.core.purr.PurrProfile
import com.projectmika.purrtts.core.purr.PurrSentimentScorer
import com.projectmika.purrtts.engine.PurrSynthesizer
import com.projectmika.purrtts.engine.ResidencyTimer
import com.projectmika.purrtts.engine.SynthesisOutcome
import java.io.File
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
 * ✨ MIKA'S ZONOS3-MINI ENGINE (100M-ACTIVE MoE • REAL-TIME • 60s CLONE) ✨
 * Zyphra ZONOS3-Mini on the native ggml/GGUF `zonos3.cpp` runtime via
 * `libpurr_zonos3_jni.so`. Renders 44.1kHz mono through ZAC-1, resampled to PURR's
 * 24kHz pipeline. Default voices are 192-d MECT-B2 embeddings (SUB-18A/B); clones
 * encode once (up to 60s reference) into a cached embedding (SUB-18C).
 */
class Zonos3TtsEngine(
    private val context: Context,
    private val clonedVoicesProvider: () -> List<ClonedVoiceProfile>,
    private val idleTimeoutMsProvider: () -> Long = { IDLE_EVICTION_MS },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : PurrSynthesizer {

    companion object {
        private const val TAG = "PurrZonos3Engine"
        private const val SAMPLE_RATE_HZ = PurrDspProcessor.SAMPLE_RATE_HZ
        private const val NATIVE_RATE_HZ = 44100
        private const val IDLE_EVICTION_MS = 5 * 60 * 1000L

        // ✨ v2.7.2: honest user-facing stub copy — the native runtime ships in v2.8 (bring-up gate).
        private const val NATIVE_PENDING_MESSAGE = "ZONOS native engine is arriving in the next update"

        const val SPEC_ZONOS3 = "rom_zonos3_mini"
        const val SPEC_CLONE_CHIP = "sub_zonos3_clone"

        // Default voice id → speaker-embedding path inside the ROM-18 model dir.
        val VOICE_FILE_MAP = mapOf(
            "zonos3_cillian" to "voices/English/Cillian_en-Irish_M_1.embedding.npy",
            "zonos3_imogen" to "voices/English/Imogen_en-British_F_1.embedding.npy",
            "zonos3_jett" to "voices/English/Jett_en-energetic_M_1.embedding.npy",
            "zonos3_thaddeus" to "voices/English/Thaddeus_en-announcervoice_M_1.embedding.npy",
            "zonos3_willow" to "voices/English/Willow_en-asrm_F_1.embedding.npy",
            "zonos3_wyatt" to "voices/English/Wyatt_en-American_M_1.embedding.npy",
            "zonos3_rodrigo" to "voices/Spanish/Rodrigo_es_M_1.embedding.npy",
            "zonos3_ximena" to "voices/Spanish/Ximena_es_F_1.embedding.npy",
            "zonos3_liesel" to "voices/German/Liesel_de_F_1.embedding.npy",
            "zonos3_wolfgang" to "voices/German/Wolfgang_de_M_1.embedding.npy",
            "zonos3_gaspard" to "voices/French/Gaspard_fr_M_1.embedding.npy"
        )

        fun detectLanguageHint(text: String, fallbackLanguageCode: String = "en-US"): String {
            for (ch in text) {
                when (ch.code) {
                    in 0x3040..0x30FF -> return "ja"
                    in 0xAC00..0xD7AF, in 0x1100..0x11FF -> return "ko"
                    in 0x4E00..0x9FFF -> return "zh"
                    in 0x0400..0x04FF -> return "ru"
                    in 0x0900..0x097F -> return "hi"
                }
            }
            return when {
                fallbackLanguageCode.startsWith("es", true) -> "es"
                fallbackLanguageCode.startsWith("de", true) -> "de"
                fallbackLanguageCode.startsWith("fr", true) -> "fr"
                fallbackLanguageCode.startsWith("it", true) -> "it"
                fallbackLanguageCode.startsWith("pt", true) -> "pt"
                else -> "en"
            }
        }
    }

    override val backendType: BackendType = BackendType.ZONOS3_NATIVE

    private val _statusFlow = MutableStateFlow(HardwareStatusBadge.IDLE)
    override val statusFlow: StateFlow<HardwareStatusBadge> = _statusFlow.asStateFlow()

    private val mutex = Mutex()
    private val residencyTimer = ResidencyTimer(scope, idleTimeoutMsProvider) { evictResources() }
    val residentModelLabel: StateFlow<String?> = residencyTimer.residentModelLabel
    val remainingWarmSeconds: StateFlow<Int> = residencyTimer.remainingWarmSeconds
    private var activeHandle: Long = 0L
    private var activeSpecId: String? = null

    private fun modelDirForSpec(specId: String): File =
        File(context.filesDir, "models/native/$specId")

    private fun embeddingCacheDir(): File =
        File(context.filesDir, "zonos3_emb_cache").apply { mkdirs() }

    private fun isSpecDownloaded(specId: String): Boolean {
        val dir = modelDirForSpec(specId)
        return File(dir, "backbone-ptq.gguf").exists() &&
            File(dir, "zac-keep2.gguf").exists()
    }

    private fun isCloneChipPresent(): Boolean =
        File(modelDirForSpec(SPEC_CLONE_CHIP), "spk-encoder.gguf").exists() ||
            File(modelDirForSpec(SPEC_ZONOS3), "spk-encoder.gguf").exists()

    fun isSpecReadyForVoice(voiceId: String): Boolean {
        val clean = voiceId.substringAfter(':')
        return isSpecDownloaded(SPEC_ZONOS3) &&
            (VOICE_FILE_MAP.containsKey(clean) || clonedVoicesProvider().any { it.id == clean })
    }

    override val isModelReady: Boolean
        get() = isSpecDownloaded(SPEC_ZONOS3)

    override suspend fun warmup(profile: PurrProfile) {
        val loaded = mutex.withLock {
            ensureEngineLoadedLocked(SPEC_ZONOS3) != 0L
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
            if (text.isBlank()) return@withContext emptyOutcome("Empty text chunk")
            if (!Zonos3NativeBridge.isLoaded) {
                return@withContext emptyOutcome(NATIVE_PENDING_MESSAGE)
            }
            if (!isSpecDownloaded(SPEC_ZONOS3)) {
                return@withContext emptyOutcome("Zonos3 cartridge (ROM-18) not downloaded yet")
            }

            val cleanVoiceId = voiceId.substringAfter(':')
            val cloned = clonedVoicesProvider().firstOrNull {
                it.id == cleanVoiceId && it.resolvedCloneEngineId == SPEC_CLONE_CHIP
            }
            val nThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
            val languageHint = resolveLanguageHint(cleanVoiceId, cloned, text)

            mutex.withLock {
                val embeddingPath = resolveEmbeddingPathLocked(cleanVoiceId, cloned)
                    ?: return@withContext emptyOutcome(
                        if (cloned != null) "Clone embedding unavailable — mount the ZONOS3 Clone Chip (SUB-18C)"
                        else "Zonos3 voice pack missing — install SUB-18A/B in Cartridge Bay"
                    )

                val handle = ensureEngineLoadedLocked(SPEC_ZONOS3)
                if (handle == 0L) {
                    return@withContext emptyOutcome("Zonos3 load error: ${lastError()}")
                }
                val emotion = buildEmotionAxes(text, profile)
                val raw = Zonos3NativeBridge.nativeSynthesize(
                    handle = handle,
                    text = text,
                    voiceEmbeddingPath = embeddingPath,
                    emotionAxes = emotion,
                    speakingRate = speakingRate,
                    languageHint = languageHint,
                    nThreads = nThreads
                )
                if (raw == null || raw.isEmpty()) {
                    return@withContext emptyOutcome("Zonos3 synthesis error: ${lastError()}")
                }

                _statusFlow.value = HardwareStatusBadge.CPU_NEON
                var shaped = PurrDspProcessor.resampleMono(raw, NATIVE_RATE_HZ, SAMPLE_RATE_HZ)
                if (speakingRate !in 0.94f..1.06f) {
                    shaped = PurrDspProcessor.timeScaleWsola(shaped, speakingRate.coerceIn(0.65f, 1.65f), SAMPLE_RATE_HZ)
                }
                val finalPcm = if (pitch in 0.995f..1.005f) {
                    shaped
                } else {
                    PurrDspProcessor.shiftPitchCubic(shaped, pitch.coerceIn(0.88f, 1.12f))
                }
                val voiceLabel = cloned?.displayName
                    ?: cleanVoiceId.removePrefix("zonos3_").replaceFirstChar { it.uppercase() }
                SynthesisOutcome(
                    pcmFloat24k = finalPcm,
                    sampleRateHz = SAMPLE_RATE_HZ,
                    hardwareBadge = HardwareStatusBadge.CPU_NEON,
                    engineSummary = "ZONOS3-Mini • $voiceLabel" + if (cloned != null) " [60s CLONE]" else ""
                )
            }
        } finally {
            residencyTimer.scheduleOrRefresh()
        }
    }

    override suspend fun evictResources() {
        mutex.withLock {
            if (activeHandle != 0L) {
                runCatching { Zonos3NativeBridge.nativeDestroy(activeHandle) }
                activeHandle = 0L
                activeSpecId = null
                _statusFlow.value = HardwareStatusBadge.IDLE
                Log.i(TAG, "[ZONOS3_EVICT] Destroyed native zonos3 runtime instance.")
            }
            residencyTimer.markEvicted()
        }
    }

    private fun emptyOutcome(message: String): SynthesisOutcome = SynthesisOutcome(
        pcmFloat24k = FloatArray(0),
        sampleRateHz = SAMPLE_RATE_HZ,
        hardwareBadge = HardwareStatusBadge.IDLE,
        engineSummary = message,
        isFallbackNeuralPreview = true
    )

    private fun lastError(): String {
        val raw = runCatching { Zonos3NativeBridge.nativeGetLastError(activeHandle) }
            .getOrDefault("")
            .ifBlank { "unknown" }
        return if (raw.contains("not vendored", ignoreCase = true)) NATIVE_PENDING_MESSAGE else raw
    }

    private fun resolveLanguageHint(cleanVoiceId: String, cloned: ClonedVoiceProfile?, text: String): String {
        if (cloned != null) return detectLanguageHint(text, cloned.languageCode)
        val fallback = when (cleanVoiceId) {
            "zonos3_rodrigo", "zonos3_ximena" -> "es-ES"
            "zonos3_liesel", "zonos3_wolfgang" -> "de-DE"
            "zonos3_gaspard" -> "fr-FR"
            else -> "en-US"
        }
        return detectLanguageHint(text, fallback)
    }

    /**
     * Default voices: the shipped 192-d embedding file. Cloned voices: encode the
     * (up to 60s) reference WAV ONCE into a cached embedding keyed by wavPath+mtime.
     */
    private fun resolveEmbeddingPathLocked(cleanVoiceId: String, cloned: ClonedVoiceProfile?): String? {
        if (cloned != null) {
            val wav = File(cloned.referenceWavPath)
            if (!wav.exists() || wav.length() <= 44L) return null
            val cacheFile = File(embeddingCacheDir(), "${cloned.id}_${wav.lastModified()}.npy")
            if (cacheFile.exists() && cacheFile.length() > 0L) return cacheFile.absolutePath
            if (!isCloneChipPresent()) return null
            val handle = ensureEngineLoadedLocked(SPEC_ZONOS3)
            if (handle == 0L) return null
            val ok = Zonos3NativeBridge.nativeEncodeSpeaker(handle, wav.absolutePath, cacheFile.absolutePath)
            return if (ok && cacheFile.exists() && cacheFile.length() > 0L) cacheFile.absolutePath else null
        }
        val relativePath = VOICE_FILE_MAP[cleanVoiceId] ?: return null
        val file = File(modelDirForSpec(SPEC_ZONOS3), relativePath)
        return if (file.exists() && file.length() > 0L) file.absolutePath else null
    }

    /** PURR's five sentiment axes → native steer.gguf emotion directions. */
    private fun buildEmotionAxes(text: String, profile: PurrProfile): FloatArray {
        val axes = FloatArray(5)
        if (!profile.sentimentConfig.enabled) return axes
        val sentiment = PurrSentimentScorer.score(text, profile.sentimentConfig.toneOverride)
        when (sentiment.dominantLabel) {
            "JOY" -> axes[0] = 0.8f
            "LOVE" -> axes[1] = 0.8f
            "ANGER" -> axes[2] = 0.8f
            "SADNESS" -> axes[3] = 0.8f
            "FLUSTERED" -> axes[4] = 0.8f
        }
        return axes
    }

    private fun ensureEngineLoadedLocked(specId: String): Long {
        if (activeHandle != 0L && activeSpecId == specId) return activeHandle
        if (activeHandle != 0L) {
            runCatching { Zonos3NativeBridge.nativeDestroy(activeHandle) }
            activeHandle = 0L
            activeSpecId = null
            residencyTimer.markEvicted()
        }
        val dir = modelDirForSpec(specId)
        if (!isSpecDownloaded(specId)) return 0L
        val nThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
        val libDir = Zonos3NativeBridge.nativeLibDir(context)
        val handle = Zonos3NativeBridge.nativeCreate(dir.absolutePath, libDir, nThreads)
        if (handle != 0L) {
            activeHandle = handle
            activeSpecId = specId
            _statusFlow.value = HardwareStatusBadge.CPU_NEON
            residencyTimer.markMounted("ZONOS3-Mini (100M MoE • CPU)")
            Log.i(TAG, "Mounted ZONOS3-Mini from ${dir.absolutePath}")
        }
        return handle
    }
}
