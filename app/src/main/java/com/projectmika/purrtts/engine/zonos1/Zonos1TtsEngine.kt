package com.projectmika.purrtts.engine.zonos1

import android.content.Context
import android.util.Log
import com.projectmika.purrtts.core.purr.BackendType
import com.projectmika.purrtts.core.purr.ClonedVoiceProfile
import com.projectmika.purrtts.core.purr.HardwareStatusBadge
import com.projectmika.purrtts.core.purr.PurrProfile
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
 * ✨ MIKA'S ZONOS1 STUDIO ENGINE (1.6B DENSE • DAC-44kHz • 10-30s CLONE) ✨
 * Zonos-v0.1 on the native ggml/GGUF `zonos1.cpp` runtime via `libpurr_zonos1_jni.so`.
 * Q8_0 backbone by default; the SUB-19A Q4_K Lite chip transparently becomes the
 * backbone when mounted. Clone-first tier: voice identity comes from a cached
 * 128-d speaker embedding (spk-encoder.gguf) derived from a 10-30s reference.
 */
class Zonos1TtsEngine(
    private val context: Context,
    private val clonedVoicesProvider: () -> List<ClonedVoiceProfile>,
    private val idleTimeoutMsProvider: () -> Long = { IDLE_EVICTION_MS },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : PurrSynthesizer {

    companion object {
        private const val TAG = "PurrZonos1Engine"
        private const val SAMPLE_RATE_HZ = com.projectmika.purrtts.core.purr.PurrDspProcessor.SAMPLE_RATE_HZ
        private const val NATIVE_RATE_HZ = 44100
        private const val IDLE_EVICTION_MS = 5 * 60 * 1000L

        // ✨ v2.7.2: honest user-facing stub copy — the native runtime ships in v2.8 (bring-up gate).
        private const val NATIVE_PENDING_MESSAGE = "ZONOS native engine is arriving in the next update"

        const val SPEC_ZONOS1 = "rom_zonos1_studio"
        const val SPEC_Q4_LITE = "sub_zonos1_q4_lite"
    }

    override val backendType: BackendType = BackendType.ZONOS1_NATIVE

    private val _statusFlow = MutableStateFlow(HardwareStatusBadge.IDLE)
    override val statusFlow: StateFlow<HardwareStatusBadge> = _statusFlow.asStateFlow()

    private val mutex = Mutex()
    private val residencyTimer = ResidencyTimer(scope, idleTimeoutMsProvider) { evictResources() }
    val residentModelLabel: StateFlow<String?> = residencyTimer.residentModelLabel
    val remainingWarmSeconds: StateFlow<Int> = residencyTimer.remainingWarmSeconds
    private var activeHandle: Long = 0L
    private var activeBackbone: String? = null

    private fun modelDirForSpec(specId: String): File =
        File(context.filesDir, "models/native/$specId")

    private fun backboneFor(): File? {
        val q8 = File(modelDirForSpec(SPEC_ZONOS1), "zonos1-q8_0.gguf")
        if (q8.exists()) return q8
        val q4 = File(modelDirForSpec(SPEC_Q4_LITE), "zonos1-q4_k.gguf")
        if (q4.exists()) return q4
        return null
    }

    private fun isSpecDownloaded(): Boolean =
        backboneFor() != null && File(modelDirForSpec(SPEC_ZONOS1), "dac.gguf").exists()

    fun isSpecReadyForVoice(voiceId: String): Boolean {
        val clean = voiceId.substringAfter(':')
        return isSpecDownloaded() && clonedVoicesProvider().any {
            it.id == clean && it.resolvedCloneEngineId == SPEC_ZONOS1
        }
    }

    override val isModelReady: Boolean
        get() = isSpecDownloaded()

    override suspend fun warmup(profile: PurrProfile) {
        val loaded = mutex.withLock { ensureEngineLoadedLocked() != 0L }
        if (loaded) residencyTimer.scheduleOrRefresh(isWarmupGrace = true)
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
            if (!Zonos1NativeBridge.isLoaded) {
                return@withContext emptyOutcome(NATIVE_PENDING_MESSAGE)
            }
            if (!isSpecDownloaded()) {
                return@withContext emptyOutcome("Zonos1 cartridge (ROM-19) not downloaded yet")
            }
            val cleanVoiceId = voiceId.substringAfter(':')
            val cloned = clonedVoicesProvider().firstOrNull {
                it.id == cleanVoiceId && it.resolvedCloneEngineId == SPEC_ZONOS1
            }
            if (cloned == null) {
                return@withContext emptyOutcome("Zonos1 is a Studio clone tier — register a voice in Clone Studio first")
            }

            mutex.withLock {
                val referencePath = resolveReferenceLocked(cloned)
                    ?: return@withContext emptyOutcome("Zonos1 reference WAV missing for ${cloned.displayName}")
                val handle = ensureEngineLoadedLocked()
                if (handle == 0L) return@withContext emptyOutcome("Zonos1 load error: ${lastError()}")
                val raw = Zonos1NativeBridge.nativeSynthesize(
                    handle = handle,
                    text = text,
                    referencePath = referencePath,
                    emotionAxes = buildEmotionAxes(text, profile),
                    speakingRate = speakingRate,
                    languageHint = com.projectmika.purrtts.engine.zonos3.Zonos3TtsEngine
                        .detectLanguageHint(text, cloned.languageCode),
                    nThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
                )
                if (raw == null || raw.isEmpty()) {
                    return@withContext emptyOutcome("Zonos1 synthesis error: ${lastError()}")
                }
                _statusFlow.value = HardwareStatusBadge.CPU_NEON
                var shaped = com.projectmika.purrtts.core.purr.PurrDspProcessor
                    .resampleMono(raw, NATIVE_RATE_HZ, SAMPLE_RATE_HZ)
                if (speakingRate !in 0.94f..1.06f) {
                    shaped = com.projectmika.purrtts.core.purr.PurrDspProcessor
                        .timeScaleWsola(shaped, speakingRate.coerceIn(0.65f, 1.65f), SAMPLE_RATE_HZ)
                }
                val finalPcm = if (pitch in 0.995f..1.005f) shaped
                else com.projectmika.purrtts.core.purr.PurrDspProcessor
                    .shiftPitchCubic(shaped, pitch.coerceIn(0.88f, 1.12f))
                SynthesisOutcome(
                    pcmFloat24k = finalPcm,
                    sampleRateHz = SAMPLE_RATE_HZ,
                    hardwareBadge = HardwareStatusBadge.CPU_NEON,
                    engineSummary = "ZONOS1 Studio • ${cloned.displayName} [DAC 44kHz]"
                )
            }
        } finally {
            residencyTimer.scheduleOrRefresh()
        }
    }

    override suspend fun evictResources() {
        mutex.withLock {
            if (activeHandle != 0L) {
                runCatching { Zonos1NativeBridge.nativeDestroy(activeHandle) }
                activeHandle = 0L
                activeBackbone = null
                _statusFlow.value = HardwareStatusBadge.IDLE
                Log.i(TAG, "[ZONOS1_EVICT] Destroyed native zonos1 runtime instance.")
            }
            residencyTimer.markEvicted()
        }
    }

    private fun resolveReferenceLocked(cloned: ClonedVoiceProfile): String? {
        val wav = File(cloned.referenceWavPath)
        if (!wav.exists() || wav.length() <= 44L) return null
        val cacheDir = File(context.filesDir, "zonos1_emb_cache").apply { mkdirs() }
        val cacheFile = File(cacheDir, "${cloned.id}_${wav.lastModified()}.npy")
        if (cacheFile.exists() && cacheFile.length() > 0L) return cacheFile.absolutePath
        val handle = ensureEngineLoadedLocked()
        if (handle == 0L) return wav.absolutePath
        val ok = Zonos1NativeBridge.nativeEncodeSpeaker(handle, wav.absolutePath, cacheFile.absolutePath)
        return if (ok && cacheFile.exists()) cacheFile.absolutePath else wav.absolutePath
    }

    private fun buildEmotionAxes(text: String, profile: PurrProfile): FloatArray {
        val axes = FloatArray(5)
        if (!profile.sentimentConfig.enabled) return axes
        val sentiment = com.projectmika.purrtts.core.purr.PurrSentimentScorer
            .score(text, profile.sentimentConfig.toneOverride)
        when (sentiment.dominantLabel) {
            "JOY" -> axes[0] = 0.8f
            "LOVE" -> axes[1] = 0.8f
            "ANGER" -> axes[2] = 0.8f
            "SADNESS" -> axes[3] = 0.8f
            "FLUSTERED" -> axes[4] = 0.8f
        }
        return axes
    }

    private fun ensureEngineLoadedLocked(): Long {
        val backbone = backboneFor() ?: return 0L
        if (activeHandle != 0L && activeBackbone == backbone.absolutePath) return activeHandle
        if (activeHandle != 0L) {
            runCatching { Zonos1NativeBridge.nativeDestroy(activeHandle) }
            activeHandle = 0L
            activeBackbone = null
            residencyTimer.markEvicted()
        }
        val studioDir = modelDirForSpec(SPEC_ZONOS1)
        val altBackbone = backbone.absolutePath
            .takeIf { !it.startsWith(studioDir.absolutePath) }
            .orEmpty()
        val handle = Zonos1NativeBridge.nativeCreate(
            modelDir = studioDir.absolutePath,
            altBackbonePath = altBackbone,
            nativeLibDir = Zonos1NativeBridge.nativeLibDir(context),
            nThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
        )
        if (handle != 0L) {
            activeHandle = handle
            activeBackbone = backbone.absolutePath
            _statusFlow.value = HardwareStatusBadge.CPU_NEON
            val chipLabel = if (backbone.name.contains("q4", true)) "Q4_K" else "Q8_0"
            residencyTimer.markMounted("ZONOS1 Studio ($chipLabel • DAC 44kHz)")
            Log.i(TAG, "Mounted ZONOS1 ($chipLabel) from ${studioDir.absolutePath}")
        }
        return handle
    }

    private fun emptyOutcome(message: String): SynthesisOutcome = SynthesisOutcome(
        pcmFloat24k = FloatArray(0),
        sampleRateHz = SAMPLE_RATE_HZ,
        hardwareBadge = HardwareStatusBadge.IDLE,
        engineSummary = message,
        isFallbackNeuralPreview = true
    )

    private fun lastError(): String {
        val raw = runCatching { Zonos1NativeBridge.nativeGetLastError(activeHandle) }
            .getOrDefault("")
            .ifBlank { "unknown" }
        return if (raw.contains("not vendored", ignoreCase = true)) NATIVE_PENDING_MESSAGE else raw
    }
}
