package com.projectmika.purrtts.engine

import com.projectmika.purrtts.core.purr.BackendType
import com.projectmika.purrtts.core.purr.HardwareStatusBadge
import com.projectmika.purrtts.core.purr.PurrProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * ✨ MIKA'S UNIFIED NEURAL SYNTHESIZER CONTRACT ✨
 * Common runtime interface implemented by:
 * 1) LiteRtKokoroEngine (NPU / Adreno GPU FP16 via Google AI Edge LiteRT-LM / MediaPipe Tasks Audio)
 * 2) SherpaOnnxEngine (Supertonic 3, Pocket TTS, ZipVoice, Piper via k2-fsa sherpa-onnx JNI)
 * 3) ExternalProxyEngine (LAN OpenAI /v1/audio/speech or secondary Android TTS engine)
 */
interface PurrSynthesizer {
    val backendType: BackendType
    val statusFlow: StateFlow<HardwareStatusBadge>
    val isModelReady: Boolean

    suspend fun warmup(profile: PurrProfile)

    /**
     * Synthesizes a pre-processed PURR speech chunk into normalized 24kHz mono float32 PCM.
     */
    suspend fun synthesizeChunk(
        text: String,
        voiceId: String,
        speakingRate: Float,
        pitch: Float,
        profile: PurrProfile
    ): SynthesisOutcome

    /**
     * Evicts GPU / NPU / ONNX weights and resident buffers to protect background service
     * from Android Low Memory Killer Daemon (lmkd).
     */
    suspend fun evictResources()
}

data class SynthesisOutcome(
    val pcmFloat24k: FloatArray,
    val sampleRateHz: Int = 24000,
    val hardwareBadge: HardwareStatusBadge,
    val engineSummary: String,
    val isFallbackNeuralPreview: Boolean = false
)

/**
 * Shared idle-eviction & Keep-Warm countdown timer used by all neural backends.
 * - `timeoutMs < 0`: ∞ Pinned (`remainingWarmSeconds = -1`, never auto-evicts).
 * - `timeoutMs == 0`: Immediate Evict (`TtsBackendRouter.onUtteranceComplete()` evicts right after speech).
 * - `timeoutMs > 0`: Ticks 1s countdown and invokes [onExpire] at 0s.
 */
class ResidencyTimer(
    private val scope: CoroutineScope,
    private val timeoutMsProvider: () -> Long,
    private val onExpire: suspend () -> Unit
) {
    private val _residentModelLabel = MutableStateFlow<String?>(null)
    val residentModelLabel: StateFlow<String?> = _residentModelLabel.asStateFlow()

    private val _remainingWarmSeconds = MutableStateFlow(0)
    val remainingWarmSeconds: StateFlow<Int> = _remainingWarmSeconds.asStateFlow()

    private var idleJob: Job? = null

    fun markMounted(label: String) {
        _residentModelLabel.value = label
    }

    fun markEvicted() {
        idleJob?.cancel()
        idleJob = null
        _residentModelLabel.value = null
        _remainingWarmSeconds.value = 0
    }

    fun beginSynthesis() {
        idleJob?.cancel()
        idleJob = null
    }

    fun scheduleOrRefresh(isWarmupGrace: Boolean = false) {
        if (_residentModelLabel.value == null) {
            idleJob?.cancel()
            idleJob = null
            _remainingWarmSeconds.value = 0
            return
        }
        val rawMs = timeoutMsProvider()
        val effectiveMs = if (isWarmupGrace && rawMs == 0L) 60_000L else rawMs
        when {
            effectiveMs < 0L -> {
                idleJob?.cancel()
                idleJob = null
                _remainingWarmSeconds.value = -1
            }
            effectiveMs == 0L -> {
                idleJob?.cancel()
                idleJob = null
                _remainingWarmSeconds.value = 0
            }
            else -> {
                idleJob?.cancel()
                idleJob = scope.launch {
                    var remainingMs = effectiveMs
                    while (remainingMs > 0L && isActive && _residentModelLabel.value != null) {
                        _remainingWarmSeconds.value = ((remainingMs + 999L) / 1000L).toInt()
                        val step = minOf(1000L, remainingMs)
                        delay(step)
                        remainingMs -= step
                    }
                    if (isActive && _residentModelLabel.value != null) {
                        _remainingWarmSeconds.value = 0
                        onExpire()
                    }
                }
            }
        }
    }

    suspend fun applyPolicyNow(evictAction: suspend () -> Unit) {
        if (_residentModelLabel.value == null) {
            idleJob?.cancel()
            idleJob = null
            _remainingWarmSeconds.value = 0
            return
        }
        if (timeoutMsProvider() == 0L) {
            evictAction()
        } else {
            scheduleOrRefresh()
        }
    }
}
