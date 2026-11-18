package com.projectmika.purrtts.engine

import com.projectmika.purrtts.core.purr.BackendType
import com.projectmika.purrtts.core.purr.HardwareStatusBadge
import com.projectmika.purrtts.core.purr.PurrProfile
import kotlinx.coroutines.flow.StateFlow

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
