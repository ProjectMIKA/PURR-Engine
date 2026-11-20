package com.projectmika.purrtts.engine

import android.content.Context
import com.projectmika.purrtts.core.purr.AcousticEffectPreset
import com.projectmika.purrtts.core.purr.BackendType
import com.projectmika.purrtts.core.purr.DspConfig
import com.projectmika.purrtts.core.purr.HardwareStatusBadge
import com.projectmika.purrtts.core.purr.PurrChunk
import com.projectmika.purrtts.core.purr.PurrDspProcessor
import com.projectmika.purrtts.core.purr.PurrProfile
import com.projectmika.purrtts.core.purr.PurrProfileRepository
import com.projectmika.purrtts.core.purr.SherpaModelFamily
import com.projectmika.purrtts.engine.litert.LiteRtKokoroEngine
import com.projectmika.purrtts.engine.onnx.OnnxRuntimeEngine
import com.projectmika.purrtts.engine.proxy.ExternalProxyEngine
import com.projectmika.purrtts.engine.qwen3.Qwen3TtsEngine
import com.projectmika.purrtts.engine.sherpa.SherpaOnnxEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * ✨ MIKA'S DUAL-RUNTIME BACKEND ROUTER & SINGLE-LANE SERIALIZER ✨
 * Routes speech chunks to the user's selected engine (`CORE_OS`, `SHERPA_ONNX` Cartridge,
 * `LITERT_NPU_GPU` Cartridge, or `EXTERNAL_PROXY`), failing soft to real `CORE_OS` speech if a
 * neural cartridge is ejected/not downloaded so output is ALWAYS coherent spoken words.
 */
class TtsBackendRouter(
    context: Context,
    private val repository: PurrProfileRepository
) {

    data class LiveTelemetry(
        val badge: HardwareStatusBadge = HardwareStatusBadge.IDLE,
        val waveformBins: FloatArray = FloatArray(32),
        val activeChunkLabel: String = "STANDBY // AWAITING_INPUT",
        val lastEngineSummary: String = "CORE_OS Speech Ready • Slot Cartridges in Tab 3",
        val activeSentimentLabel: String = "NEUTRAL",
        val vramCountdownSec: Int = 0
    )

    val liteRtEngine = LiteRtKokoroEngine(context)
    val sherpaEngine = SherpaOnnxEngine(
        context = context,
        clonedVoicesProvider = { repository.state.value.clonedVoices }
    )
    val onnxEngine = OnnxRuntimeEngine(
        context = context,
        clonedVoicesProvider = { repository.state.value.clonedVoices }
    )
    val qwen3Engine = Qwen3TtsEngine(
        context = context,
        clonedVoicesProvider = { repository.state.value.clonedVoices }
    )
    val proxyEngine = ExternalProxyEngine(context)

    private val synthMutex = Mutex()

    private val _telemetry = MutableStateFlow(LiveTelemetry())
    val telemetry: StateFlow<LiveTelemetry> = _telemetry.asStateFlow()

    suspend fun synthesizeSpeechChunk(
        chunk: PurrChunk.Speech,
        profile: PurrProfile,
        osRateMultiplier: Float = 1.0f,
        osPitchMultiplier: Float = 1.0f
    ): SynthesisOutcome = synthMutex.withLock {
        val voiceId = chunk.targetVoiceId ?: profile.primaryVoiceId
        val effectiveRate = (chunk.speakingRate * osRateMultiplier).coerceIn(0.6f, 1.8f)
        val effectivePitch = (chunk.pitch * osPitchMultiplier).coerceIn(0.88f, 1.12f)

        val selectedBackend = resolveBackendForVoice(voiceId, profile)

        var rawOutcome = when (selectedBackend) {
            BackendType.LITERT_NPU_GPU,
            BackendType.SHERPA_ONNX -> {
                val cleanVoiceId = voiceId.substringAfter(':')
                // v1.6.0: [a-z0-9] allows the numeric Kokoro v1.1-zh roster (zf_001, zm_100...).
                val isKokoroVoice = cleanVoiceId.matches(Regex("^[abjefhipz][fm]_[a-z0-9]+$")) ||
                    profile.selectedCartridgeId in setOf(
                        "rom_kokoro_sherpa", "rom_kokoro_fp32", "rom_kokoro_v11"
                    )
                val npuChipOn = isKokoroVoice &&
                    repository.isSubCartridgeEnabled("rom_kokoro_npu_g2p") &&
                    liteRtEngine.isNpuG2pReady
                val gpuChipOn = isKokoroVoice &&
                    repository.isSubCartridgeEnabled("rom_kokoro_litert")

                var litertOut: SynthesisOutcome? = null
                if (npuChipOn || gpuChipOn) {
                    litertOut = liteRtEngine.synthesizeChunk(
                        text = chunk.text,
                        voiceId = cleanVoiceId,
                        speakingRate = effectiveRate,
                        pitch = effectivePitch,
                        profile = profile
                    )
                }

                if (litertOut != null && litertOut.pcmFloat24k.isNotEmpty()) {
                    litertOut
                } else {
                    val baseOut = sherpaEngine.synthesizeChunk(
                        text = chunk.text,
                        voiceId = cleanVoiceId,
                        speakingRate = effectiveRate,
                        pitch = effectivePitch,
                        profile = profile
                    )
                    if (baseOut.pcmFloat24k.isNotEmpty() && (npuChipOn || gpuChipOn)) {
                        val badge = if (npuChipOn) {
                            HardwareStatusBadge.NPU_ACTIVE
                        } else {
                            HardwareStatusBadge.GPU_FP16
                        }
                        val summary = when {
                            npuChipOn && gpuChipOn -> "ROM-02 Kokoro-82M + NPU G2P & GPU FP16"
                            npuChipOn -> "ROM-02 Kokoro-82M + SUB-02A Hexagon NPU G2P"
                            else -> "ROM-02 Kokoro-82M + SUB-02B Adreno GPU FP16"
                        }
                        baseOut.copy(
                            hardwareBadge = badge,
                            engineSummary = summary
                        )
                    } else {
                        baseOut
                    }
                }
            }
            BackendType.ONNX_NATIVE -> {
                onnxEngine.synthesizeChunk(
                    text = chunk.text,
                    voiceId = voiceId,
                    speakingRate = effectiveRate,
                    pitch = effectivePitch,
                    profile = profile
                )
            }
            BackendType.QWEN3_NATIVE -> {
                qwen3Engine.synthesizeChunk(
                    text = chunk.text,
                    voiceId = voiceId,
                    speakingRate = effectiveRate,
                    pitch = effectivePitch,
                    profile = profile
                )
            }
            BackendType.CORE_OS -> {
                if (!repository.isCartridgeSlotted("rom_core_os")) {
                    SynthesisOutcome(
                        pcmFloat24k = FloatArray(0),
                        sampleRateHz = PurrDspProcessor.SAMPLE_RATE_HZ,
                        hardwareBadge = HardwareStatusBadge.IDLE,
                        engineSummary = "System voices disabled (ROM-01 ejected)",
                        isFallbackNeuralPreview = false
                    )
                } else {
                    proxyEngine.synthesizeChunk(chunk.text, voiceId, effectiveRate, effectivePitch, profile)
                }
            }
            BackendType.EXTERNAL_PROXY -> {
                proxyEngine.synthesizeChunk(chunk.text, voiceId, effectiveRate, effectivePitch, profile)
            }
        }

        // Fail soft to real CORE_OS speech whenever a cartridge isn't downloaded or slotted yet (if ROM-01 is slotted)
        if (rawOutcome.pcmFloat24k.isEmpty() &&
            selectedBackend != BackendType.CORE_OS &&
            repository.isCartridgeSlotted("rom_core_os")
        ) {
            rawOutcome = proxyEngine.synthesizeChunk(
                text = chunk.text,
                voiceId = "core_os_auto",
                speakingRate = effectiveRate,
                pitch = effectivePitch,
                profile = profile.copy(preferredBackend = BackendType.CORE_OS)
            )
        }

        val dspModuleOn = repository.isDspModuleActive()
        val effectiveDspConfig = if (dspModuleOn) {
            profile.dspConfig
        } else {
            DspConfig(
                warmthEqDb = 0f,
                presenceEqDb = 0f,
                reverbWetMix = 0f,
                effectPreset = AcousticEffectPreset.NONE,
                enableSentenceTaper = false,
                antiPopFadeInMs = 0,
                normalizePeak = false
            )
        }

        val processedPcm = PurrDspProcessor.process(
            input = rawOutcome.pcmFloat24k,
            sampleRate = PurrDspProcessor.SAMPLE_RATE_HZ,
            config = effectiveDspConfig,
            volumeGain = chunk.volumeGain,
            dspEnabled = dspModuleOn && profile.enableDsp
        )

        val bins = PurrDspProcessor.computeWaveformBins(processedPcm, 32)
        _telemetry.value = LiveTelemetry(
            badge = rawOutcome.hardwareBadge,
            waveformBins = bins,
            activeChunkLabel = "[${chunk.role.name}] ${chunk.text.take(42)}",
            lastEngineSummary = rawOutcome.engineSummary,
            activeSentimentLabel = chunk.sentiment.dominantLabel,
            vramCountdownSec = liteRtEngine.vramRemainingSeconds.value
        )

        rawOutcome.copy(pcmFloat24k = processedPcm)
    }

    fun emitSpeechTelemetry(label: String, badge: HardwareStatusBadge, summary: String, sentiment: String, bins: FloatArray) {
        _telemetry.value = _telemetry.value.copy(
            badge = badge,
            waveformBins = bins,
            activeChunkLabel = label,
            lastEngineSummary = summary,
            activeSentimentLabel = sentiment,
            vramCountdownSec = liteRtEngine.vramRemainingSeconds.value
        )
    }

    fun emitSilenceTelemetry(durationMs: Int, reason: String) {
        _telemetry.value = _telemetry.value.copy(
            waveformBins = FloatArray(32),
            activeChunkLabel = "[MIKASTOP] +${durationMs}ms SILENCE ($reason)",
            vramCountdownSec = liteRtEngine.vramRemainingSeconds.value
        )
    }

    fun markIdle() {
        _telemetry.value = _telemetry.value.copy(
            badge = HardwareStatusBadge.IDLE,
            waveformBins = FloatArray(32),
            activeChunkLabel = "STANDBY // STREAM_COMPLETE",
            vramCountdownSec = liteRtEngine.vramRemainingSeconds.value
        )
    }

    suspend fun evictAllVram() {
        synthMutex.withLock {
            liteRtEngine.evictResources()
            sherpaEngine.evictResources()
            onnxEngine.evictResources()
            qwen3Engine.evictResources()
            proxyEngine.evictResources()
            _telemetry.value = LiveTelemetry(
                badge = HardwareStatusBadge.IDLE,
                waveformBins = FloatArray(32),
                activeChunkLabel = "VRAM_EVICTED // LMKD_SAFE",
                lastEngineSummary = "All native model buffers released cleanly",
                vramCountdownSec = 0
            )
        }
    }

    private fun resolveBackendForVoice(voiceId: String, profile: PurrProfile): BackendType {
        if (voiceId.startsWith(ExternalProxyEngine.SYS_VOICE_PREFIX) || voiceId == "core_os_auto") {
            return BackendType.CORE_OS
        }
        val cloned = repository.state.value.clonedVoices.firstOrNull { it.id == voiceId }
        if (cloned != null) {
            return when (cloned.resolvedCloneEngineId) {
                "sub_qwen3_base_06b" -> BackendType.QWEN3_NATIVE
                "sub_chatterbox_turbo" -> BackendType.ONNX_NATIVE
                else -> BackendType.SHERPA_ONNX
            }
        }

        val descriptor = repository.allAvailableVoices().firstOrNull { it.id == voiceId }
        if (descriptor != null && repository.isCartridgeSlotted(descriptor.cartridgeId)) {
            return descriptor.engine
        }
        return if (repository.isCartridgeSlotted(profile.selectedCartridgeId)) {
            profile.preferredBackend
        } else if (repository.isCartridgeSlotted("rom_core_os")) {
            BackendType.CORE_OS
        } else {
            profile.preferredBackend
        }
    }
}
