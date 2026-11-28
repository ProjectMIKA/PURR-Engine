package com.projectmika.purrtts.engine

import android.content.Context
import com.projectmika.purrtts.core.purr.AcousticEffectPreset
import com.projectmika.purrtts.core.purr.BackendType
import com.projectmika.purrtts.core.purr.DownloadState
import com.projectmika.purrtts.core.purr.DspConfig
import com.projectmika.purrtts.core.purr.HardwareProfile
import com.projectmika.purrtts.core.purr.HardwareStatusBadge
import com.projectmika.purrtts.core.purr.PurrChunk
import com.projectmika.purrtts.core.purr.PurrDefaultPresets
import com.projectmika.purrtts.core.purr.PurrDspProcessor
import com.projectmika.purrtts.core.purr.PurrForgeState
import com.projectmika.purrtts.core.purr.PurrHardwareProbe
import com.projectmika.purrtts.core.purr.PurrNeuralBypass
import com.projectmika.purrtts.core.purr.PurrProfile
import com.projectmika.purrtts.core.purr.PurrProfileRepository
import com.projectmika.purrtts.core.purr.SherpaModelFamily
import com.projectmika.purrtts.core.purr.VramResidencyPolicy
import com.projectmika.purrtts.engine.litert.LiteRtKokoroEngine
import com.projectmika.purrtts.engine.onnx.OnnxRuntimeEngine
import com.projectmika.purrtts.engine.proxy.ExternalProxyEngine
import com.projectmika.purrtts.engine.qwen3.Qwen3TtsEngine
import com.projectmika.purrtts.engine.sherpa.SherpaOnnxEngine
import com.projectmika.purrtts.engine.zonos1.Zonos1TtsEngine
import com.projectmika.purrtts.engine.zonos3.Zonos3TtsEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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

    data class ResidencySnapshot(
        val isWarming: Boolean = false,
        val warmingLabel: String? = null,
        val residentLabel: String? = null,
        val residentBadge: HardwareStatusBadge = HardwareStatusBadge.IDLE,
        val remainingSeconds: Int = 0,
        val effectiveTimeoutSec: Int = 300,
        val foregroundServiceEnabled: Boolean = false
    )

    data class WarmupOutcome(
        val ok: Boolean,
        val message: String,
        val badge: HardwareStatusBadge
    )

    private val routerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _warmingState = MutableStateFlow<Pair<Boolean, String?>>(false to null)

    fun currentEffectiveTimeoutMs(): Long {
        val s = repository.state.value
        return VramResidencyPolicy.effectiveTimeoutMs(
            config = s.vramResidency,
            moduleSlotted = VramResidencyPolicy.TOOL_ID in s.slottedCartridges
        )
    }

    val hardwareProfile: HardwareProfile = PurrHardwareProbe.detectDevice()

    val liteRtEngine = LiteRtKokoroEngine(
        context = context,
        idleTimeoutMsProvider = { currentEffectiveTimeoutMs() }
    )
    val sherpaEngine = SherpaOnnxEngine(
        context = context,
        clonedVoicesProvider = { repository.state.value.clonedVoices },
        smartOffloadProvider = { repository.state.value.smartHardwareOffload },
        hardwareProfile = hardwareProfile,
        idleTimeoutMsProvider = { currentEffectiveTimeoutMs() }
    )
    val onnxEngine = OnnxRuntimeEngine(
        context = context,
        clonedVoicesProvider = { repository.state.value.clonedVoices },
        smartOffloadProvider = { repository.state.value.smartHardwareOffload },
        hardwareProfile = hardwareProfile,
        idleTimeoutMsProvider = { currentEffectiveTimeoutMs() }
    )
    val qwen3Engine = Qwen3TtsEngine(
        context = context,
        clonedVoicesProvider = { repository.state.value.clonedVoices },
        gpuOffloadProvider = {
            repository.state.value.qwen3GpuOffload ||
                (repository.state.value.smartHardwareOffload && hardwareProfile.hasAdrenoOpenCl)
        },
        idleTimeoutMsProvider = { currentEffectiveTimeoutMs() }
    )
    val zonos3Engine = Zonos3TtsEngine(
        context = context,
        clonedVoicesProvider = { repository.state.value.clonedVoices },
        idleTimeoutMsProvider = { currentEffectiveTimeoutMs() }
    )
    val zonos1Engine = Zonos1TtsEngine(
        context = context,
        clonedVoicesProvider = { repository.state.value.clonedVoices },
        idleTimeoutMsProvider = { currentEffectiveTimeoutMs() }
    )
    val proxyEngine = ExternalProxyEngine(context)

    private val synthMutex = Mutex()

    private val _telemetry = MutableStateFlow(LiveTelemetry())
    val telemetry: StateFlow<LiveTelemetry> = _telemetry.asStateFlow()

    private data class EngineResidencyEntry(
        val label: String?,
        val remainingSec: Int,
        val badge: HardwareStatusBadge
    )

    private fun residencyEntryFlow(
        label: StateFlow<String?>,
        sec: StateFlow<Int>,
        badge: StateFlow<HardwareStatusBadge>
    ): Flow<EngineResidencyEntry> = combine(label, sec, badge) { l, s, b ->
        EngineResidencyEntry(l, s, b)
    }

    private val enginesResidencyFlow: Flow<EngineResidencyEntry?> = combine(
        combine(
            residencyEntryFlow(qwen3Engine.residentModelLabel, qwen3Engine.remainingWarmSeconds, qwen3Engine.statusFlow),
            residencyEntryFlow(liteRtEngine.residentModelLabel, liteRtEngine.remainingWarmSeconds, liteRtEngine.statusFlow),
            residencyEntryFlow(sherpaEngine.residentModelLabel, sherpaEngine.remainingWarmSeconds, sherpaEngine.statusFlow),
            residencyEntryFlow(onnxEngine.residentModelLabel, onnxEngine.remainingWarmSeconds, onnxEngine.statusFlow)
        ) { q, l, s, o ->
            listOfNotNull(q, l, s, o).firstOrNull { it.label != null }
        },
        residencyEntryFlow(zonos3Engine.residentModelLabel, zonos3Engine.remainingWarmSeconds, zonos3Engine.statusFlow),
        residencyEntryFlow(zonos1Engine.residentModelLabel, zonos1Engine.remainingWarmSeconds, zonos1Engine.statusFlow)
    ) { base, z3, z1 ->
        listOfNotNull(base, z3, z1).firstOrNull { it.label != null }
    }

    val residencyFlow: StateFlow<ResidencySnapshot> = combine(
        enginesResidencyFlow,
        _warmingState,
        repository.state
    ) { active, warming, state ->
        val moduleSlotted = VramResidencyPolicy.TOOL_ID in state.slottedCartridges
        ResidencySnapshot(
            isWarming = warming.first,
            warmingLabel = warming.second,
            residentLabel = active?.label,
            residentBadge = active?.badge ?: HardwareStatusBadge.IDLE,
            remainingSeconds = active?.remainingSec ?: 0,
            effectiveTimeoutSec = VramResidencyPolicy.effectiveTimeoutSeconds(state.vramResidency, moduleSlotted),
            foregroundServiceEnabled = VramResidencyPolicy.shouldRunForegroundService(state.vramResidency, moduleSlotted)
        )
    }.stateIn(
        scope = routerScope,
        started = SharingStarted.Eagerly,
        initialValue = ResidencySnapshot()
    )

    init {
        routerScope.launch {
            repository.state
                .map { s ->
                    val slotted = VramResidencyPolicy.TOOL_ID in s.slottedCartridges
                    Triple(
                        VramResidencyPolicy.effectiveTimeoutSeconds(s.vramResidency, slotted),
                        s.vramResidency.basicKeepWarmEnabled,
                        slotted
                    )
                }
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    refreshResidencyPolicies()
                }
        }
    }

    suspend fun refreshResidencyPolicies() {
        synthMutex.withLock {
            liteRtEngine.applyResidencyPolicyNow()
            sherpaEngine.applyResidencyPolicyNow()
            onnxEngine.applyResidencyPolicyNow()
            qwen3Engine.applyResidencyPolicyNow()
            zonos3Engine.applyResidencyPolicyNow()
            zonos1Engine.applyResidencyPolicyNow()
        }
    }

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
                // Only route the 54 v1.0 base Kokoro voice IDs (af_*, am_*, bf_*, ...) through
                // LiteRtKokoroEngine so fp32_* / v11_* never bleed into the v1.0 LiteRT weights.
                val isKokoroVoice = cleanVoiceId.matches(Regex("^[abjefhipz][fm]_[a-z]+$"))
                val smartOffload = repository.state.value.smartHardwareOffload
                val npuChipOn = isKokoroVoice &&
                    (repository.isSubCartridgeEnabled("rom_kokoro_npu_g2p") || smartOffload) &&
                    liteRtEngine.isNpuG2pReady
                val gpuChipOn = isKokoroVoice &&
                    (repository.isSubCartridgeEnabled("rom_kokoro_litert") ||
                        (smartOffload && hardwareProfile.hasAdrenoOpenCl && liteRtEngine.kokoroModelFile.exists()))

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
                    profile = profile,
                    singleChunkOnly = chunk.singleChunkOnly
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
            BackendType.ZONOS3_NATIVE -> {
                zonos3Engine.synthesizeChunk(
                    text = chunk.text,
                    voiceId = voiceId,
                    speakingRate = effectiveRate,
                    pitch = effectivePitch,
                    profile = profile
                )
            }
            BackendType.ZONOS1_NATIVE -> {
                zonos1Engine.synthesizeChunk(
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

        // Never silently hijack explicit voice-clone, VoiceDesign, or Qwen3 requests — OR any
        // already-installed & slotted neural cartridge — with device OS speech.
        val cleanVoiceId = voiceId.substringAfter(':')
        val isExplicitCloneOrDesign = repository.state.value.clonedVoices.any { it.id == voiceId || it.id == cleanVoiceId } ||
            cleanVoiceId in setOf("qwen3_base_clone_default", "qwen3_design_custom")
        val targetVoiceDesc = repository.allAvailableVoices(includeMangaClones = true)
            .firstOrNull { it.id == cleanVoiceId }
        val targetCartridgeId = targetVoiceDesc?.cartridgeId ?: profile.selectedCartridgeId
        val requiredSpecId = targetVoiceDesc?.requiredSubCartridgeId ?: targetCartridgeId
        val isTargetInstalledAndSlotted =
            repository.state.value.modelStates[requiredSpecId]?.state == DownloadState.INSTALLED &&
                repository.isCartridgeSlotted(targetCartridgeId)

        // Fail soft to real CORE_OS speech whenever a standard cartridge isn't downloaded or slotted yet (if ROM-01 is slotted)
        if (rawOutcome.pcmFloat24k.isEmpty() &&
            selectedBackend != BackendType.CORE_OS &&
            selectedBackend != BackendType.QWEN3_NATIVE &&
            !isExplicitCloneOrDesign &&
            !isTargetInstalledAndSlotted &&
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
        val neuralPureActive = PurrNeuralBypass.shouldBypassEnhancers(profile, repository.state.value.clonedVoices)
        val effectiveDspConfig = when {
            !dspModuleOn -> DspConfig(
                warmthEqDb = 0f,
                presenceEqDb = 0f,
                reverbWetMix = 0f,
                effectPreset = AcousticEffectPreset.NONE,
                enableSentenceTaper = false,
                antiPopFadeInMs = 0,
                normalizePeak = false
            )
            neuralPureActive && profile.dspConfig.effectPreset in setOf(AcousticEffectPreset.NONE, AcousticEffectPreset.WARM_STUDIO) ->
                DspConfig(
                    warmthEqDb = 0f,
                    presenceEqDb = 0f,
                    reverbWetMix = 0f,
                    effectPreset = AcousticEffectPreset.NONE,
                    enableSentenceTaper = false,
                    antiPopFadeInMs = 10,
                    normalizePeak = false
                )
            else -> profile.dspConfig
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
            vramCountdownSec = residencyFlow.value.remainingSeconds
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
            vramCountdownSec = residencyFlow.value.remainingSeconds
        )
    }

    fun emitSilenceTelemetry(durationMs: Int, reason: String) {
        _telemetry.value = _telemetry.value.copy(
            waveformBins = FloatArray(32),
            activeChunkLabel = "[MIKASTOP] +${durationMs}ms SILENCE ($reason)",
            vramCountdownSec = residencyFlow.value.remainingSeconds
        )
    }

    fun markIdle() {
        _telemetry.value = _telemetry.value.copy(
            badge = HardwareStatusBadge.IDLE,
            waveformBins = FloatArray(32),
            activeChunkLabel = "STANDBY // STREAM_COMPLETE",
            vramCountdownSec = residencyFlow.value.remainingSeconds
        )
    }

    suspend fun onUtteranceComplete() {
        markIdle()
        if (currentEffectiveTimeoutMs() == 0L) {
            evictAllVram()
        }
    }

    fun selectedEngineTargetLabel(profile: PurrProfile, state: PurrForgeState): Pair<String, Boolean> {
        val voiceId = profile.primaryVoiceId
        val cleanId = voiceId.substringAfter(':')
        val cloned = state.clonedVoices.firstOrNull { it.id == voiceId || it.id == cleanId }
        if (cloned != null) {
            val engineId = cloned.resolvedCloneEngineId
            val ready = state.modelStates[engineId]?.state == DownloadState.INSTALLED
            val label = when (engineId) {
                "sub_qwen3_base_06b" -> "Qwen3 0.6B Clone"
                "sub_zonos3_clone" -> "ZONOS3 Clone"
                "rom_zonos1_studio" -> "ZONOS1 Studio"
                "sub_chatterbox_turbo" -> "Chatterbox Q4"
                "sub_pocket_fp32" -> "Pocket FP32"
                "sub_zipvoice_full" -> "ZipVoice Full"
                "rom_zipvoice" -> "ZipVoice"
                else -> "Pocket TTS"
            }
            return label to ready
        }
        val desc = repository.allAvailableVoices(includeMangaClones = false)
            .firstOrNull { it.id == cleanId || it.id == voiceId }
        val cartId = desc?.cartridgeId ?: profile.selectedCartridgeId
        if (cartId == "rom_core_os" || cartId == "proxy_lan" || desc?.engine == BackendType.CORE_OS || desc?.engine == BackendType.EXTERNAL_PROXY) {
            val label = if (cartId == "proxy_lan" || desc?.engine == BackendType.EXTERNAL_PROXY) "LAN Proxy" else "Core OS"
            return label to false
        }
        val reqSpecId = desc?.requiredSubCartridgeId ?: cartId
        val installed = state.modelStates[reqSpecId]?.state == DownloadState.INSTALLED ||
            state.modelStates[cartId]?.state == DownloadState.INSTALLED
        val label = when (cleanId) {
            "qwen3_design_custom" -> "Qwen3 1.7B Design"
            "qwen3_base_clone_default" -> "Qwen3 0.6B Clone"
            else -> when (reqSpecId) {
                "rom_qwen3_custom_06b" -> "Qwen3 0.6B"
                "sub_qwen3_design_17b" -> "Qwen3 1.7B Design"
                "sub_qwen3_base_06b" -> "Qwen3 0.6B Clone"
                "sub_zonos3_clone" -> "ZONOS3 Clone"
                "rom_zonos3_mini" -> "ZONOS3-Mini"
                "rom_zonos1_studio" -> "ZONOS1 Studio"
                "rom_kokoro_fp32" -> "Kokoro FP32"
                "rom_kokoro_v11" -> "Kokoro v1.1"
                "rom_kokoro_sherpa", "rom_kokoro_litert" -> "Kokoro 82M"
                "rom_soprano_80m" -> "Soprano 80M"
                "rom_neutts_2e" -> "NeuTTS Air"
                "rom_phonon_31m", "sub_phonon_fp32" -> "Phonon 31M"
                "sub_chatterbox_turbo" -> "Chatterbox Q4"
                else -> PurrDefaultPresets.modelCatalog().firstOrNull { it.id == reqSpecId }?.name
                    ?: PurrDefaultPresets.modelCatalog().firstOrNull { it.id == cartId }?.name
                    ?: desc?.displayName
                    ?: "Neural Engine"
            }
        }
        return label to installed
    }

    suspend fun warmSelectedProfile(profile: PurrProfile = repository.currentProfile): WarmupOutcome {
        val state = repository.state.value
        val (targetLabel, canWarm) = selectedEngineTargetLabel(profile, state)
        val backend = resolveBackendForVoice(profile.primaryVoiceId, profile)
        if (backend == BackendType.CORE_OS || backend == BackendType.EXTERNAL_PROXY) {
            return WarmupOutcome(
                ok = false,
                message = "$targetLabel streams directly — no RAM/VRAM preload needed",
                badge = HardwareStatusBadge.CORE_OS
            )
        }
        if (!canWarm) {
            return WarmupOutcome(
                ok = false,
                message = "$targetLabel not downloaded yet — install in Cartridge Bay first",
                badge = HardwareStatusBadge.IDLE
            )
        }
        _warmingState.value = true to targetLabel
        return try {
            synthMutex.withLock {
                // Keep at most one neural engine resident so warming a new model frees prior RAM/VRAM.
                when (backend) {
                    BackendType.QWEN3_NATIVE -> {
                        liteRtEngine.evictResources()
                        sherpaEngine.evictResources()
                        onnxEngine.evictResources()
                        qwen3Engine.warmup(profile)
                        val mounted = qwen3Engine.residentModelLabel.value
                        if (mounted != null) {
                            WarmupOutcome(true, "HOT in RAM: $mounted", qwen3Engine.statusFlow.value)
                        } else {
                            WarmupOutcome(false, "Failed to mount $targetLabel — check model files", HardwareStatusBadge.IDLE)
                        }
                    }
                    BackendType.ZONOS3_NATIVE -> {
                        liteRtEngine.evictResources()
                        sherpaEngine.evictResources()
                        onnxEngine.evictResources()
                        qwen3Engine.evictResources()
                        zonos1Engine.evictResources()
                        zonos3Engine.warmup(profile)
                        val mounted = zonos3Engine.residentModelLabel.value
                        if (mounted != null) {
                            WarmupOutcome(true, "HOT in RAM: $mounted", zonos3Engine.statusFlow.value)
                        } else {
                            WarmupOutcome(false, "Failed to mount $targetLabel — check model files", HardwareStatusBadge.IDLE)
                        }
                    }
                    BackendType.ZONOS1_NATIVE -> {
                        liteRtEngine.evictResources()
                        sherpaEngine.evictResources()
                        onnxEngine.evictResources()
                        qwen3Engine.evictResources()
                        zonos3Engine.evictResources()
                        zonos1Engine.warmup(profile)
                        val mounted = zonos1Engine.residentModelLabel.value
                        if (mounted != null) {
                            WarmupOutcome(true, "HOT in RAM: $mounted", zonos1Engine.statusFlow.value)
                        } else {
                            WarmupOutcome(false, "Failed to mount $targetLabel — check model files", HardwareStatusBadge.IDLE)
                        }
                    }
                    BackendType.ONNX_NATIVE -> {
                        liteRtEngine.evictResources()
                        sherpaEngine.evictResources()
                        qwen3Engine.evictResources()
                        onnxEngine.warmup(profile)
                        val mounted = onnxEngine.residentModelLabel.value
                        if (mounted != null) {
                            WarmupOutcome(true, "HOT in RAM: $mounted", onnxEngine.statusFlow.value)
                        } else {
                            WarmupOutcome(false, "Failed to mount $targetLabel — check model files", HardwareStatusBadge.IDLE)
                        }
                    }
                    BackendType.LITERT_NPU_GPU,
                    BackendType.SHERPA_ONNX -> {
                        onnxEngine.evictResources()
                        qwen3Engine.evictResources()
                        val cleanId = profile.primaryVoiceId.substringAfter(':')
                        val isKokoroVoice = cleanId.matches(Regex("^[abjefhipz][fm]_[a-z]+$"))
                        val smartOffload = state.smartHardwareOffload
                        val gpuChipOn = isKokoroVoice &&
                            (repository.isSubCartridgeEnabled("rom_kokoro_litert") ||
                                (smartOffload && hardwareProfile.hasAdrenoOpenCl && liteRtEngine.kokoroModelFile.exists()))
                        if (gpuChipOn && liteRtEngine.kokoroModelFile.exists()) {
                            sherpaEngine.evictResources()
                            liteRtEngine.warmup(profile)
                            val lMounted = liteRtEngine.residentModelLabel.value
                            if (lMounted != null) {
                                return@withLock WarmupOutcome(true, "HOT in VRAM: $lMounted", liteRtEngine.statusFlow.value)
                            }
                        }
                        liteRtEngine.evictResources()
                        sherpaEngine.warmup(profile)
                        val sMounted = sherpaEngine.residentModelLabel.value
                        if (sMounted != null) {
                            WarmupOutcome(true, "HOT in RAM: $sMounted", sherpaEngine.statusFlow.value)
                        } else {
                            WarmupOutcome(false, "Failed to mount $targetLabel — check cartridge in Tab 3", HardwareStatusBadge.IDLE)
                        }
                    }
                    else -> WarmupOutcome(false, "No neural preload required for $targetLabel", HardwareStatusBadge.CORE_OS)
                }
            }
        } finally {
            _warmingState.value = false to null
        }
    }

    suspend fun evictAllVram() {
        synthMutex.withLock {
            liteRtEngine.evictResources()
            sherpaEngine.evictResources()
            onnxEngine.evictResources()
            qwen3Engine.evictResources()
            zonos3Engine.evictResources()
            zonos1Engine.evictResources()
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
        val cleanId = voiceId.substringAfter(':')
        val cloned = repository.state.value.clonedVoices.firstOrNull {
            it.id == voiceId || it.id == cleanId
        }
        if (cloned != null) {
            // ✨ v2.7.2: single-source clone→backend map (PurrCloneEngineMap) — no more drift.
            return PurrCloneEngineMap.targetBackend(cloned.resolvedCloneEngineId)
        }

        // Route Qwen3 VoiceDesign / Base / Custom voices straight to QWEN3_NATIVE whenever their
        // native model files are installed, even if the parent hub card isn't manually slotted.
        if (cleanId == "qwen3_design_custom" || cleanId == "qwen3_base_clone_default" || cleanId.startsWith("qwen3_")) {
            if (qwen3Engine.isSpecReadyForVoice(cleanId)) {
                return BackendType.QWEN3_NATIVE
            }
        }

        // Route ZONOS3 default voices straight to the Zonos3 engine when ROM-18 + a voice
        // pack are installed, even if the hub card isn't manually slotted (Qwen3 parity).
        if (cleanId.startsWith("zonos3_")) {
            if (zonos3Engine.isSpecReadyForVoice(cleanId)) {
                return BackendType.ZONOS3_NATIVE
            }
        }

        val descriptor = repository.allAvailableVoices(includeMangaClones = true)
            .firstOrNull { it.id == cleanId || it.id == voiceId }
        if (descriptor != null &&
            (repository.isCartridgeSlotted(descriptor.cartridgeId) || profile.selectedCartridgeId == descriptor.cartridgeId)
        ) {
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
