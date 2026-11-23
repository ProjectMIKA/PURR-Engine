package com.projectmika.purrtts.ui.forge

import android.annotation.SuppressLint
import android.app.Application
import android.content.ContentValues
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.projectmika.purrtts.BuildConfig
import com.projectmika.purrtts.PurrApplication
import com.projectmika.purrtts.core.audiobook.AudiobookChapter
import com.projectmika.purrtts.core.audiobook.AudiobookProject
import com.projectmika.purrtts.core.purr.BackendType
import com.projectmika.purrtts.core.purr.CartridgeCategory
import com.projectmika.purrtts.core.purr.CartridgeFilterMode
import com.projectmika.purrtts.core.purr.CartridgeSortMode
import com.projectmika.purrtts.core.purr.ClonedVoiceProfile
import com.projectmika.purrtts.core.purr.DownloadState
import com.projectmika.purrtts.core.purr.ExportedAudioFile
import com.projectmika.purrtts.core.purr.HardwareProfile
import com.projectmika.purrtts.core.purr.HardwareStatusBadge
import com.projectmika.purrtts.core.purr.MikaStopConfig
import com.projectmika.purrtts.core.purr.ModelBundleSpec
import com.projectmika.purrtts.core.purr.PhoneticRule
import com.projectmika.purrtts.core.purr.PurrChunk
import com.projectmika.purrtts.core.purr.PurrCloneAudioCleaner
import com.projectmika.purrtts.core.purr.PurrDspProcessor
import com.projectmika.purrtts.core.purr.PurrForgeState
import com.projectmika.purrtts.core.purr.PurrProfile
import com.projectmika.purrtts.core.purr.PurrTextProcessor
import com.projectmika.purrtts.core.purr.PurrThemeConfig
import com.projectmika.purrtts.core.purr.PurrUpdateChecker
import com.projectmika.purrtts.core.purr.RuleCategory
import com.projectmika.purrtts.core.purr.SavedVoiceDesign
import com.projectmika.purrtts.core.purr.SherpaModelFamily
import com.projectmika.purrtts.core.purr.TUTORIAL_VERSION
import com.projectmika.purrtts.core.purr.VoiceDescriptor
import com.projectmika.purrtts.core.purr.VoiceDesignSpec
import com.projectmika.purrtts.core.purr.VoiceGender
import com.projectmika.purrtts.engine.CloneClipImporter
import com.projectmika.purrtts.engine.TtsBackendRouter
import com.projectmika.purrtts.engine.audiobook.AudiobookConstructorManager
import com.projectmika.purrtts.engine.qwen3.Qwen3TtsEngine
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * ✨ MIKA'S PURR-TTS VIEWMODEL ✨
 * Connects the streamlined 3-tab UI (`> VOICE & ENGINE`, `> PURR ENGINE`, `> CARTRIDGE BAY`),
 * real-time 24kHz coherent speech auditioning (with direct device TTS fallback when no offline
 * cartridge is slotted), ROM cartridge insert/eject animations, and 5-second WAV cloning.
 */
class TtsForgeViewModel(application: Application) : AndroidViewModel(application) {

    data class CartridgeAnimEvent(
        val spec: ModelBundleSpec,
        val isInsert: Boolean
    )

    private val app = application as PurrApplication
    val repository = app.repository
    val backendRouter = app.backendRouter
    val catalogManager = app.modelCatalogManager
    val audiobookManager = app.audiobookManager

    val audiobookProjects: StateFlow<List<AudiobookProject>> = audiobookManager.projects
    val activeAudiobookId: StateFlow<String?> = audiobookManager.activeProjectId
    val isBuildingAudiobook: StateFlow<Boolean> = audiobookManager.isBuilding
    val isCombiningAudiobook: StateFlow<Boolean> = audiobookManager.isCombining
    val audiobookStatusLabel: StateFlow<String> = audiobookManager.statusLabel
    val audiobookProgress: StateFlow<Float> = audiobookManager.overallProgress

    val forgeState: StateFlow<PurrForgeState> = repository.state
    val discoveredSystemVoices: StateFlow<List<VoiceDescriptor>> = repository.discoveredSystemVoices
    val telemetry: StateFlow<TtsBackendRouter.LiveTelemetry> = backendRouter.telemetry
    val vramRemainingSeconds: StateFlow<Int> = backendRouter.liteRtEngine.vramRemainingSeconds
    val hardwareProfile: HardwareProfile = backendRouter.hardwareProfile
    val modelCatalog: List<ModelBundleSpec> = catalogManager.catalog
    val mainCartridgeCatalog: List<ModelBundleSpec> = modelCatalog.filter {
        !it.isSubCartridge && (it.category == CartridgeCategory.TTS || (it.category == CartridgeCategory.CLONING && !it.isBuiltInOsCartridge))
    }
    val cloningCartridgeCatalog: List<ModelBundleSpec> = modelCatalog.filter {
        !it.isSubCartridge && it.category == CartridgeCategory.CLONING
    }
    val toolCartridgeCatalog: List<ModelBundleSpec> = modelCatalog.filter {
        !it.isSubCartridge && (it.category == CartridgeCategory.TOOLS || it.id == "tool_clone_studio")
    }

    fun subCartridgesFor(parentId: String): List<ModelBundleSpec> =
        modelCatalog.filter { it.isSubCartridge && it.parentCartridgeId == parentId }

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    private val _cartridgeAnim = MutableStateFlow<CartridgeAnimEvent?>(null)
    val cartridgeAnim: StateFlow<CartridgeAnimEvent?> = _cartridgeAnim.asStateFlow()

    // ✨ TRUST RENEWAL UX (v1.8.1): a calm audition line — the old default packed ellipses,
    // a phonetic stutter, and an action block, which MIKASTOP + chunking turned into a
    // minutes-long gauntlet on slow neural engines (Qwen3) that looked "stuck processing".
    private val _testInputText = MutableStateFlow(
        "Hey Master! It is really me, your PURR Engine, speaking in every app on this phone now. Nyaa!"
    )
    val testInputText: StateFlow<String> = _testInputText.asStateFlow()

    private val _isPlayingPreview = MutableStateFlow(false)
    val isPlayingPreview: StateFlow<Boolean> = _isPlayingPreview.asStateFlow()

    // ✨ TRUST RENEWAL UX (v1.8.1): per-chunk audition progress so slow neural engines
    // (Qwen3 ≈ 30s per 5-8s clip) never look frozen mid-audition.
    private val _auditionProgress = MutableStateFlow<String?>(null)
    val auditionProgress: StateFlow<String?> = _auditionProgress.asStateFlow()

    private val _isRecordingClone = MutableStateFlow(false)
    val isRecordingClone: StateFlow<Boolean> = _isRecordingClone.asStateFlow()

    private val _recordingProgress = MutableStateFlow(0f)
    val recordingProgress: StateFlow<Float> = _recordingProgress.asStateFlow()

    private val _lastRecordedWavPath = MutableStateFlow<String?>(null)
    val lastRecordedWavPath: StateFlow<String?> = _lastRecordedWavPath.asStateFlow()

    // ✨ CLONE CLIP IMPORT (v1.4.0): user-imported audio → cleaned 24kHz reference
    private val _cleanImportedAudio = MutableStateFlow(true)
    val cleanImportedAudio: StateFlow<Boolean> = _cleanImportedAudio.asStateFlow()

    private val _isImportingClone = MutableStateFlow(false)
    val isImportingClone: StateFlow<Boolean> = _isImportingClone.asStateFlow()

    private val _cloneImportStatus = MutableStateFlow<String?>(null)
    val cloneImportStatus: StateFlow<String?> = _cloneImportStatus.asStateFlow()

    private val _importedCloneWavPath = MutableStateFlow<String?>(null)
    val importedCloneWavPath: StateFlow<String?> = _importedCloneWavPath.asStateFlow()

    /** Cleaned full-length import held in memory (24kHz mono); trimmed per-family at registration. */
    private var importedCloneSamples: FloatArray? = null

    private val _isExportingAudio = MutableStateFlow(false)
    val isExportingAudio: StateFlow<Boolean> = _isExportingAudio.asStateFlow()

    private val _exportProgressFraction = MutableStateFlow(0f)
    val exportProgressFraction: StateFlow<Float> = _exportProgressFraction.asStateFlow()

    private val _exportStatusLabel = MutableStateFlow("IDLE // READY TO RENDER")
    val exportStatusLabel: StateFlow<String> = _exportStatusLabel.asStateFlow()

    private var activePlaybackJob: Job? = null
    private var activeExportJob: Job? = null
    private var activeToastJob: Job? = null
    private var activeAnimJob: Job? = null

    init {
        refreshSystemVoices()
    }

    fun refreshSystemVoices() {
        viewModelScope.launch(Dispatchers.IO) {
            val sysVoices = backendRouter.proxyEngine.discoverSystemVoices(
                repository.currentProfile.proxyConfig.fallbackSystemEnginePackage
            )
            repository.updateDiscoveredSystemVoices(sysVoices)
        }
    }

    fun showToast(msg: String) {
        activeToastJob?.cancel()
        _toastMessage.value = msg
        activeToastJob = viewModelScope.launch {
            delay(3000L)
            _toastMessage.value = null
        }
    }

    // ✨ MIKA'S APP UPDATE CHECK (v1.8.4): MANUAL ONLY — one tap in Settings ▸
    // CHECK_FOR_UPDATES, zero background polling (no WorkManager, no alarms, no
    // silent traffic — Master's standing law). One GET to GitHub's releases/latest,
    // strict numeric compare against the running versionName, and (newer?) the
    // release page opens in the user's browser for downloading. Fail-soft: any
    // error toasts instead of throwing; the in-flight guard stops double taps.
    private val _updateCheckInFlight = MutableStateFlow(false)
    val updateCheckInFlight: StateFlow<Boolean> = _updateCheckInFlight.asStateFlow()

    fun checkForAppUpdates() {
        if (_updateCheckInFlight.value) return
        viewModelScope.launch {
            _updateCheckInFlight.value = true
            try {
                val latest = PurrUpdateChecker.fetchLatestRelease()
                when {
                    latest == null ->
                        showToast("Update check failed — is the network awake?")
                    !PurrUpdateChecker.isNewerVersion(latest.tagName, BuildConfig.VERSION_NAME) ->
                        showToast("PURR-TTS v${BuildConfig.VERSION_NAME} — you're current.")
                    else -> {
                        showToast("Update available: ${latest.tagName} — opening the release page…")
                        val url = latest.htmlUrl.ifBlank {
                            "https://github.com/ProjectMIKA/PURR-Engine/releases/latest"
                        }
                        runCatching {
                            getApplication<Application>().startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }.onFailure {
                            showToast("Couldn't open a browser — grab it at $url")
                        }
                    }
                }
            } finally {
                _updateCheckInFlight.value = false
            }
        }
    }

    fun dismissCartridgeAnim() {
        activeAnimJob?.cancel()
        _cartridgeAnim.value = null
    }

    fun updateTestInputText(text: String) {
        _testInputText.value = text
    }

    fun allAvailableVoices(): List<VoiceDescriptor> = repository.allAvailableVoices()

    private fun isChatterboxVoiceActive(profile: PurrProfile): Boolean {
        if (profile.primaryVoiceId.startsWith("chatterbox_") ||
            (profile.enableDualVoiceSplit && profile.dialogueVoiceId.startsWith("chatterbox_"))
        ) {
            return true
        }
        return repository.state.value.clonedVoices.any { c ->
            (c.id == profile.primaryVoiceId || (profile.enableDualVoiceSplit && c.id == profile.dialogueVoiceId)) &&
                c.resolvedCloneEngineId == "sub_chatterbox_turbo"
        }
    }

    fun previewChunks(text: String, profile: PurrProfile, rules: List<PhoneticRule>): List<PurrChunk> {
        return PurrTextProcessor.process(
            rawText = text,
            profile = profile,
            customRules = rules,
            purrModuleActive = repository.isPurrEngineModuleActive(),
            symbolShieldModuleActive = repository.isSymbolShieldModuleActive(),
            isChatterboxVoice = isChatterboxVoiceActive(profile),
            clonedVoices = repository.state.value.clonedVoices
        )
    }

    fun updateVoiceDesignPrompt(prompt: String) {
        repository.updateActiveProfile { p -> p.copy(voiceDesignPrompt = prompt) }
    }

    fun activateVoiceDesignAndAudition(prompt: String, sampleLine: String) {
        val cleanPrompt = prompt.trim()
        if (cleanPrompt.isEmpty()) {
            showToast("Enter or build a VoiceDesign description first!")
            return
        }
        val designReady = repository.state.value.modelStates[Qwen3TtsEngine.SPEC_DESIGN_17B]?.state == DownloadState.INSTALLED
        if (!designReady) {
            showToast("Download SUB-03A (Qwen3 1.7B VoiceDesign) in Cartridge Bay first!")
            return
        }
        repository.setCartridgeSlotted("rom_qwen3_custom_06b", true)
        repository.setSubCartridgeEnabled(Qwen3TtsEngine.SPEC_DESIGN_17B, true)
        repository.updateActiveProfile { p ->
            p.copy(
                selectedCartridgeId = "rom_qwen3_custom_06b",
                preferredBackend = BackendType.QWEN3_NATIVE,
                primaryVoiceId = "qwen3_design_custom",
                voiceDesignPrompt = cleanPrompt
            )
        }
        val textToSpeak = sampleLine.trim().ifBlank { _testInputText.value }
        auditionText(text = textToSpeak)
    }

    fun saveVoiceDesignPreset(name: String, prompt: String, spec: VoiceDesignSpec) {
        val cleanName = name.trim().ifEmpty { "Designed Voice" }
        val cleanPrompt = prompt.trim()
        if (cleanPrompt.isEmpty()) return
        val entry = SavedVoiceDesign(
            id = "vd_${cleanName.lowercase().replace(Regex("[^a-z0-9]+"), "_")}_${System.currentTimeMillis() % 10000}",
            name = cleanName,
            prompt = cleanPrompt,
            spec = spec,
            createdAtEpochMs = System.currentTimeMillis()
        )
        repository.saveVoiceDesign(entry)
        repository.updateActiveProfile { p -> p.copy(voiceDesignPrompt = cleanPrompt) }
        showToast("Saved VoiceDesign [$cleanName]")
    }

    fun deleteVoiceDesignPreset(designId: String) {
        repository.deleteVoiceDesign(designId)
        showToast("Deleted VoiceDesign preset")
    }

    fun freezeVoiceDesignAsClone(
        name: String,
        prompt: String,
        gender: VoiceGender,
        sampleLine: String
    ) {
        val cleanPrompt = prompt.trim()
        val designReady = repository.state.value.modelStates[Qwen3TtsEngine.SPEC_DESIGN_17B]?.state == DownloadState.INSTALLED
        if (!designReady) {
            showToast("Download SUB-03A (Qwen3 1.7B VoiceDesign) first to freeze a clone seed!")
            return
        }
        val qwenBaseReady = repository.state.value.modelStates[Qwen3TtsEngine.SPEC_BASE_CLONE_06B]?.state == DownloadState.INSTALLED
        val pocketReady = repository.state.value.modelStates["rom_pocket_tts"]?.state == DownloadState.INSTALLED
        val targetCloneEngine = when {
            qwenBaseReady -> Qwen3TtsEngine.SPEC_BASE_CLONE_06B
            pocketReady -> "rom_pocket_tts"
            else -> Qwen3TtsEngine.SPEC_BASE_CLONE_06B
        }
        val cleanSample = sampleLine.trim().ifBlank {
            "Hello Master, this reference sample locks my custom voice identity cleanly."
        }
        viewModelScope.launch(Dispatchers.IO) {
            showToast("Synthesizing 1.7B VoiceDesign clone seed...")
            val designProfile = repository.currentProfile.copy(
                selectedCartridgeId = "rom_qwen3_custom_06b",
                preferredBackend = BackendType.QWEN3_NATIVE,
                primaryVoiceId = "qwen3_design_custom",
                voiceDesignPrompt = cleanPrompt,
                baseSpeed = 1.0f,
                basePitch = 1.0f
            )
            val outcome = backendRouter.synthesizeSpeechChunk(
                chunk = PurrChunk.Speech(
                    text = cleanSample,
                    role = com.projectmika.purrtts.core.purr.SegmentRole.DIALOGUE,
                    targetVoiceId = "qwen3_design_custom",
                    speakingRate = 1.0f,
                    pitch = 1.0f,
                    volumeGain = 1.0f,
                    sentiment = com.projectmika.purrtts.core.purr.SentimentResult()
                ),
                profile = designProfile
            )
            if (outcome.pcmFloat24k.isEmpty()) {
                showToast(outcome.engineSummary.ifBlank { "VoiceDesign render failed" })
                return@launch
            }
            val outDir = File(app.filesDir, "cloned_refs").apply { mkdirs() }
            val seedFile = File(outDir, "vd_seed_${System.currentTimeMillis()}.wav")
            val pcm16 = PurrDspProcessor.float32ToPcm16Le(outcome.pcmFloat24k)
            writePcm16MonoWav(seedFile, pcm16, pcm16.size, PurrDspProcessor.SAMPLE_RATE_HZ)

            registerClonedVoice(
                displayName = name.trim().ifEmpty { "Designed_Clone" },
                family = if (targetCloneEngine == Qwen3TtsEngine.SPEC_BASE_CLONE_06B) SherpaModelFamily.ZIPVOICE else SherpaModelFamily.POCKET_TTS,
                gender = gender,
                transcript = cleanSample,
                tagsRaw = "voicedesign, qwen3, locked",
                wavPathOverride = seedFile.absolutePath,
                cloneEngineId = targetCloneEngine
            )
        }
    }

    fun selectProfile(profileId: String) {
        repository.selectProfile(profileId)
        showToast("Preset [${repository.currentProfile.name}] active!")
    }

    fun saveAsNewProfile(name: String) {
        repository.saveAsNewProfile(name)
        showToast("Saved preset [$name]")
    }

    fun updateActiveProfile(transform: (PurrProfile) -> PurrProfile) {
        repository.updateActiveProfile(transform)
    }

    fun updateMikaStopConfig(transform: (MikaStopConfig) -> MikaStopConfig) {
        repository.updateActiveProfile { p -> p.copy(mikaStopConfig = transform(p.mikaStopConfig)) }
    }

    fun setCatalogSortMode(mode: CartridgeSortMode) {
        repository.setCatalogSortMode(mode)
    }

    fun setCatalogFilterMode(mode: CartridgeFilterMode) {
        repository.setCatalogFilterMode(mode)
    }

    fun updateThemeConfig(transform: (PurrThemeConfig) -> PurrThemeConfig) {
        repository.updateThemeConfig(transform)
    }

    // ✨ SMART HARDWARE OFFLOAD (v1.8.5): experimental auto-offload across supported accelerators.
    fun setSmartHardwareOffload(enabled: Boolean) {
        repository.setSmartHardwareOffload(enabled)
        viewModelScope.launch { backendRouter.evictAllVram() }
        showToast(
            if (enabled) "Smart Hardware Offload ON (${hardwareProfile.recommendedStrategyLabel})"
            else "Smart Hardware Offload OFF — reverted to manual/CPU settings"
        )
    }

    // ✨ HARDWARE OFFLOAD (v1.8.3): global device-level toggle — Settings ▸ HARDWARE_OFFLOAD.
    fun setQwen3GpuOffload(enabled: Boolean) {
        repository.setQwen3GpuOffload(enabled)
    }

    fun selectEngineCartridge(cartridgeId: String) {
        repository.selectEngineCartridge(cartridgeId)
        showToast("Engine -> ${cartridgeId.uppercase()}")
    }

    fun selectPrimaryVoice(voice: VoiceDescriptor) {
        repository.updateActiveProfile { p ->
            p.copy(
                primaryVoiceId = voice.id,
                preferredBackend = voice.engine,
                selectedCartridgeId = voice.cartridgeId,
                sherpaFamily = voice.sherpaFamily ?: p.sherpaFamily
            )
        }
        showToast("Voice -> ${voice.displayName}")
    }

    fun selectDialogueVoice(voice: VoiceDescriptor) {
        repository.updateActiveProfile { p ->
            p.copy(
                dialogueVoiceId = voice.id,
                enableDualVoiceSplit = true
            )
        }
        showToast("Dialogue Voice -> ${voice.displayName}")
    }

    fun toggleCartridgeSlotted(spec: ModelBundleSpec, slotIn: Boolean) {
        val modelState = repository.state.value.modelStates[spec.id]
        val hasStandaloneSubReady = subCartridgesFor(spec.id).any {
            it.id !in setOf("rom_kokoro_npu_g2p", "rom_kokoro_litert") &&
                repository.state.value.modelStates[it.id]?.state == DownloadState.INSTALLED
        }
        val isReady = spec.isBuiltInOsCartridge || modelState?.state == DownloadState.INSTALLED || hasStandaloneSubReady
        if (slotIn && !isReady) {
            showToast("Download ${spec.romCode} first before slotting!")
            return
        }
        repository.setCartridgeSlotted(spec.id, slotIn)
        val isSpeechEngine = spec.category == CartridgeCategory.TTS ||
            (spec.category == CartridgeCategory.CLONING && !spec.isBuiltInOsCartridge)
        if (!slotIn && isSpeechEngine) {
            viewModelScope.launch { backendRouter.evictAllVram() }
        } else if (slotIn && isSpeechEngine) {
            repository.selectEngineCartridge(spec.id)
        }

        activeAnimJob?.cancel()
        _cartridgeAnim.value = CartridgeAnimEvent(spec, slotIn)
        activeAnimJob = viewModelScope.launch {
            delay(1750L)
            _cartridgeAnim.value = null
        }
        showToast(if (slotIn) "[SLOTTED] ${spec.romCode}: ${spec.name}" else "[EJECTED] ${spec.romCode}: ${spec.name}")
    }

    fun toggleSubCartridgeEnabled(spec: ModelBundleSpec, enabled: Boolean) {
        val modelState = repository.state.value.modelStates[spec.id]
        if (enabled && modelState?.state != DownloadState.INSTALLED) {
            showToast("Download ${spec.romCode} first before enabling!")
            return
        }
        repository.setSubCartridgeEnabled(spec.id, enabled)
        if (!enabled) {
            viewModelScope.launch { backendRouter.evictAllVram() }
        }
        showToast(if (enabled) "[ACTIVE] ${spec.romCode}: ${spec.name}" else "[BYPASSED] ${spec.romCode}: ${spec.name}")
    }

    fun addPhoneticRule(pattern: String, replacement: String, isRegex: Boolean, category: RuleCategory) {
        if (pattern.isBlank()) return
        val rule = PhoneticRule(
            id = "rule_${System.currentTimeMillis()}",
            pattern = pattern.trim(),
            replacement = replacement.trim(),
            isRegex = isRegex,
            enabled = true,
            category = category
        )
        repository.addOrUpdatePhoneticRule(rule)
        showToast("Added rule [$pattern -> $replacement]")
    }

    fun togglePhoneticRule(ruleId: String) {
        repository.togglePhoneticRule(ruleId)
    }

    fun deletePhoneticRule(ruleId: String) {
        repository.deletePhoneticRule(ruleId)
        showToast("Deleted rule")
    }

    /**
     * Auditions text using PURR middleware + active slotted engine (or real Android device
     * speech fallback so voices NEVER emit musical sine-wave tones).
     */
    fun auditionText(text: String = _testInputText.value, overrideVoice: VoiceDescriptor? = null) {
        activePlaybackJob?.cancel()
        backendRouter.proxyEngine.stopDirectAudition()
        activePlaybackJob = viewModelScope.launch(Dispatchers.IO) {
            _isPlayingPreview.value = true
            var audioTrack: AudioTrack? = null
            try {
                val baseProfile = repository.currentProfile
                val effectiveProfile = if (overrideVoice != null) {
                    baseProfile.copy(
                        primaryVoiceId = overrideVoice.id,
                        preferredBackend = overrideVoice.engine,
                        selectedCartridgeId = overrideVoice.cartridgeId,
                        sherpaFamily = overrideVoice.sherpaFamily ?: baseProfile.sherpaFamily
                    )
                } else {
                    baseProfile
                }

                val chunks = PurrTextProcessor.process(
                    rawText = text,
                    profile = effectiveProfile,
                    customRules = repository.state.value.customPhoneticRules,
                    purrModuleActive = repository.isPurrEngineModuleActive(),
                    symbolShieldModuleActive = repository.isSymbolShieldModuleActive(),
                    isChatterboxVoice = isChatterboxVoiceActive(effectiveProfile),
                    clonedVoices = repository.state.value.clonedVoices
                )
                if (chunks.isEmpty()) {
                    showToast("Nothing speakable after PURR filter!")
                    return@launch
                }

                val sampleRate = PurrDspProcessor.SAMPLE_RATE_HZ
                val minBuf = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(4096)

                audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(minBuf * 2)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                audioTrack.play()

                var playedAnyAudio = false
                var showedFallbackToast = false
                var lastEmptySummary: String? = null
                // ✨ TRUST RENEWAL UX (v1.8.1): per-chunk progress for the audition HUD.
                val speechTotal = chunks.count { it is PurrChunk.Speech }
                var speechIndex = 0

                for ((chunkIndex, chunk) in chunks.withIndex()) {
                    if (!isActive) break
                    when (chunk) {
                        is PurrChunk.Silence -> {
                            _auditionProgress.value = "[MIKASTOP] +${chunk.durationMs}ms pause • chunk ${chunkIndex + 1}/${chunks.size}"
                            backendRouter.emitSilenceTelemetry(chunk.durationMs, chunk.reason)
                            val silenceBytes = PurrDspProcessor.createSilencePcm16Le(sampleRate, chunk.durationMs)
                            var offset = 0
                            while (offset < silenceBytes.size && isActive) {
                                val step = minOf(4096, silenceBytes.size - offset)
                                audioTrack.write(silenceBytes, offset, step)
                                offset += step
                            }
                        }
                        is PurrChunk.Speech -> {
                            speechIndex++
                            _auditionProgress.value = "[$speechIndex/$speechTotal] NEURAL • ${chunk.text.take(26)}"
                            val outcome = backendRouter.synthesizeSpeechChunk(chunk, effectiveProfile)
                            if (outcome.pcmFloat24k.isNotEmpty()) {
                                playedAnyAudio = true
                                val pcmBytes = PurrDspProcessor.float32ToPcm16Le(outcome.pcmFloat24k)
                                var offset = 0
                                while (offset < pcmBytes.size && isActive) {
                                    val step = minOf(4096, pcmBytes.size - offset)
                                    audioTrack.write(pcmBytes, offset, step)
                                    offset += step
                                }
                            } else if (effectiveProfile.preferredBackend == BackendType.CORE_OS &&
                                repository.isCartridgeSlotted("rom_core_os")
                            ) {
                                // Direct speaker fallback when OEM TTS blocks synthesizeToFile and ROM-01 is explicitly active
                                val pulseBins = FloatArray(32) { i -> 0.25f + 0.55f * abs(sin(i * 0.45f)) }
                                backendRouter.emitSpeechTelemetry(
                                    label = "[${chunk.role.name}] ${chunk.text.take(40)}",
                                    badge = HardwareStatusBadge.CORE_OS,
                                    summary = "Direct System Speech Audition",
                                    sentiment = chunk.sentiment.dominantLabel,
                                    bins = pulseBins
                                )
                                val spoken = backendRouter.proxyEngine.speakDirectAudition(
                                    text = chunk.text,
                                    voiceId = chunk.targetVoiceId ?: effectiveProfile.primaryVoiceId,
                                    speed = chunk.speakingRate,
                                    pitch = chunk.pitch,
                                    preferredPkg = effectiveProfile.proxyConfig.fallbackSystemEnginePackage
                                )
                                if (spoken) {
                                    playedAnyAudio = true
                                } else {
                                    showToast("Install Google Speech Services or a ROM Cartridge in Tab 3!")
                                    showedFallbackToast = true
                                    break
                                }
                            } else {
                                lastEmptySummary = outcome.engineSummary.ifBlank {
                                    "No active voice cartridge ready — slam a cartridge in Tab 3!"
                                }
                                showToast(lastEmptySummary)
                                showedFallbackToast = true
                                break
                            }
                        }
                    }
                }
                if (!playedAnyAudio && !showedFallbackToast && !lastEmptySummary.isNullOrBlank()) {
                    showToast(lastEmptySummary)
                }
            } catch (err: Exception) {
                showToast("Audition error: ${err.message}")
            } finally {
                runCatching {
                    audioTrack?.stop()
                    audioTrack?.release()
                }
                backendRouter.markIdle()
                _auditionProgress.value = null
                _isPlayingPreview.value = false
            }
        }
    }

    fun stopAudition() {
        activePlaybackJob?.cancel()
        backendRouter.proxyEngine.stopDirectAudition()
        backendRouter.markIdle()
        _auditionProgress.value = null
        _isPlayingPreview.value = false
    }

    fun startModelDownload(spec: ModelBundleSpec) {
        catalogManager.startOrResumeDownload(spec)
        showToast("Downloading ${spec.romCode}: ${spec.name}...")
    }

    fun pauseModelDownload(specId: String) {
        catalogManager.pauseDownload(specId)
        showToast("Paused download")
    }

    fun verifyModelChecksum(spec: ModelBundleSpec) {
        viewModelScope.launch {
            val ok = catalogManager.verifyInstalledSha256(spec)
            showToast(if (ok) "SHA-256 VERIFIED: ${spec.romCode}" else "SHA-256 MISMATCH: ${spec.romCode}")
        }
    }

    fun deleteModelBundle(spec: ModelBundleSpec) {
        repository.setCartridgeSlotted(spec.id, false)
        catalogManager.deleteModelBundle(spec)
        showToast("Deleted ${spec.romCode} bundle")
    }

    fun evictAllVramNow() {
        viewModelScope.launch {
            backendRouter.evictAllVram()
            showToast("Evicted native TTS model memory")
        }
    }

    @SuppressLint("MissingPermission")
    fun recordFiveSecondReferenceSample() {
        if (_isRecordingClone.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _isRecordingClone.value = true
            _recordingProgress.value = 0f
            val sampleRate = 24000
            val totalSeconds = 5
            val targetBytes = sampleRate * totalSeconds * 2
            val outDir = File(app.filesDir, "cloned_refs").apply { mkdirs() }
            val wavFile = File(outDir, "ref_${System.currentTimeMillis()}.wav")

            var recorder: AudioRecord? = null
            try {
                val minBuf = AudioRecord.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(4096)

                recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf * 2
                )
                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    throw IllegalStateException("Microphone unavailable — grant RECORD_AUDIO permission")
                }

                val capturedPcm = ByteArray(targetBytes)
                var written = 0
                val readBuf = ByteArray(minBuf)
                recorder.startRecording()
                while (written < targetBytes && isActive) {
                    val read = recorder.read(readBuf, 0, minOf(readBuf.size, targetBytes - written))
                    if (read > 0) {
                        System.arraycopy(readBuf, 0, capturedPcm, written, read)
                        written += read
                        _recordingProgress.value = (written.toFloat() / targetBytes.toFloat()).coerceIn(0f, 1f)
                    }
                }
                recorder.stop()
                writePcm16MonoWav(wavFile, capturedPcm, written, sampleRate)
                _lastRecordedWavPath.value = wavFile.absolutePath
                showToast("Captured 5s 24kHz reference WAV!")
            } catch (err: Exception) {
                showToast("Mic capture error: ${err.message}")
            } finally {
                runCatching { recorder?.release() }
                _isRecordingClone.value = false
            }
        }
    }

    /**
     * ✨ CLONE CLIP IMPORT (v1.4.0): decodes any user-selected audio document (mp3/m4a/ogg/
     * flac/wav/opus) into a cleaned 24kHz mono reference held in memory. At registration the
     * clip is trimmed to the selected family's best-voice window (Pocket ≈ 5s, ZipVoice ≈ 12s).
     * The optional CLEAN_AUDIO toggle runs the pure-Kotlin denoise chain: DC removal → 85Hz
     * rumble high-pass → STFT spectral-subtraction (learns the clip's own noise floor) →
     * peak normalization.
     */
    fun importCloneClip(uri: Uri) {
        if (_isImportingClone.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _isImportingClone.value = true
            try {
                _cloneImportStatus.value = "> DECODING_AUDIO_CLIP..."
                val decoded = CloneClipImporter.decodeToMonoPcm(app, uri)
                    ?: throw IllegalStateException("No decodable audio track in that file")

                _cloneImportStatus.value = "> RESAMPLING ${decoded.sampleRateHz}HZ -> 24KHZ..."
                val mono24k = PurrDspProcessor.resampleMono(decoded.samples, decoded.sampleRateHz, 24000)

                val cleaned = if (_cleanImportedAudio.value) {
                    _cloneImportStatus.value = "> CLEANING_NOISE // SPECTRAL_SUBTRACT..."
                    PurrCloneAudioCleaner.cleanClip(mono24k, 24000)
                } else {
                    mono24k
                }

                importedCloneSamples = cleaned
                _importedCloneWavPath.value = null
                val seconds = cleaned.size / 24000.0
                val suffix = if (_cleanImportedAudio.value) " • CLEANED" else " • RAW"
                _cloneImportStatus.value = "READY: %.1fs @ 24kHz%s".format(seconds, suffix)
                showToast("Imported clone clip (%.1fs)".format(seconds))
            } catch (err: Exception) {
                _cloneImportStatus.value = "IMPORT FAILED: ${err.message}"
                showToast("Import failed: ${err.message}")
            } finally {
                _isImportingClone.value = false
            }
        }
    }

    fun toggleCleanImportedAudio(enabled: Boolean) {
        _cleanImportedAudio.value = enabled
    }

    fun registerClonedVoice(
        displayName: String,
        family: SherpaModelFamily,
        gender: VoiceGender,
        transcript: String,
        tagsRaw: String,
        wavPathOverride: String? = null,
        cloneEngineId: String = if (family == SherpaModelFamily.ZIPVOICE) "rom_zipvoice" else "rom_pocket_tts"
    ): Boolean {
        val cleanName = displayName.trim().ifEmpty { "Mika Clone" }
        val existingImportedPath = _importedCloneWavPath.value?.takeIf { File(it).exists() && File(it).length() > 44L }
        val existingRecordedPath = _lastRecordedWavPath.value?.takeIf { File(it).exists() && File(it).length() > 44L }
        val wavPath = when {
            // Imported clip: trim to the selected engine's best-voice window and write the WAV.
            importedCloneSamples != null -> {
                val targetSeconds = PurrCloneAudioCleaner.targetClipSecondsForEngine(cloneEngineId)
                val trimmed = PurrCloneAudioCleaner.selectBestClip(
                    importedCloneSamples!!, 24000, targetSeconds
                )
                val outDir = File(app.filesDir, "cloned_refs").apply { mkdirs() }
                val file = File(outDir, "import_${System.currentTimeMillis()}.wav")
                val pcm16 = PurrDspProcessor.float32ToPcm16Le(trimmed)
                writePcm16MonoWav(file, pcm16, pcm16.size, 24000)
                importedCloneSamples = null
                _importedCloneWavPath.value = file.absolutePath
                _cloneImportStatus.value = null
                showToast("Reference trimmed to ${targetSeconds}s")
                file.absolutePath
            }
            wavPathOverride != null -> wavPathOverride
            existingImportedPath != null -> existingImportedPath
            existingRecordedPath != null -> existingRecordedPath
            else -> {
                showToast("Record a 5s sample or import an audio clip first!")
                return false
            }
        }
        val parsedTags = tagsRaw.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            .ifEmpty { listOf("cloned", "custom", gender.tag) }
        val voiceId = "clone_${cleanName.lowercase().replace(Regex("[^a-z0-9]+"), "_")}_${System.currentTimeMillis() % 10000}"

        val cloned = ClonedVoiceProfile(
            id = voiceId,
            displayName = cleanName,
            family = family,
            gender = gender,
            languageCode = "en-US",
            referenceWavPath = wavPath,
            referenceTranscript = transcript.trim().ifEmpty { "Sample reference voice for zero-shot cloning." },
            tags = parsedTags,
            cloneEngineId = cloneEngineId
        )
        repository.registerClonedVoice(cloned)
        val engineInstalled = repository.state.value.modelStates[cloneEngineId]?.state == DownloadState.INSTALLED
        showToast(
            if (engineInstalled) "Registered Cloned Voice [$cleanName]"
            else "Registered [$cleanName] — download ${cloneEngineId.uppercase()} in Cartridge Bay > CLONING to speak!"
        )
        return true
    }

    fun removeClonedVoice(voiceId: String) {
        repository.removeClonedVoice(voiceId)
        showToast("Removed cloned voice")
    }

    /** ✨ ACADEMY (v1.5.0): persist the tutorial content version after finish/dismiss. */
    fun markTutorialSeen() {
        repository.markTutorialSeen(TUTORIAL_VERSION)
    }

    /**
     * Synthesizes a short message or multi-page audiobook chapter chunk-by-chunk, blends chunks
     * smoothly with raised-cosine crossfading & RMS loudness leveling, writes a mastered
     * 24kHz mono `.wav` file, and saves a copy to `Downloads/PURR-TTS/`.
     */
    fun renderAndExportMediaFile(
        title: String,
        rawText: String,
        targetChunkChars: Int = 240,
        paragraphPauseMs: Int = 550,
        normalizeLoudness: Boolean = true,
        smoothTransitions: Boolean = true
    ) {
        val cleanText = rawText.trim()
        if (cleanText.isEmpty()) {
            showToast("Enter some text or paste audiobook pages first!")
            return
        }
        if (_isExportingAudio.value) {
            showToast("Render already in progress...")
            return
        }

        activeExportJob?.cancel()
        activeExportJob = viewModelScope.launch(Dispatchers.IO) {
            _isExportingAudio.value = true
            _exportProgressFraction.value = 0.02f
            _exportStatusLabel.value = "CHUNKING TEXT BOUNDARIES..."

            try {
                val activeProfile = repository.currentProfile
                val purrOn = repository.isPurrEngineModuleActive()
                val shieldOn = repository.isSymbolShieldModuleActive()
                val chunks = PurrTextProcessor.buildExportChunks(
                    rawText = cleanText,
                    profile = activeProfile,
                    customRules = repository.state.value.customPhoneticRules,
                    purrModuleActive = purrOn,
                    symbolShieldModuleActive = shieldOn,
                    targetChunkCharsOverride = targetChunkChars,
                    paragraphPauseMs = paragraphPauseMs,
                    isChatterboxVoice = isChatterboxVoiceActive(activeProfile),
                    clonedVoices = repository.state.value.clonedVoices
                )

                val speechCount = chunks.count { it is PurrChunk.Speech }.coerceAtLeast(1)
                if (chunks.none { it is PurrChunk.Speech }) {
                    _exportStatusLabel.value = "NOTHING SPEAKABLE AFTER FILTER"
                    showToast("No speakable words found in input text!")
                    return@launch
                }

                val sampleRate = PurrDspProcessor.SAMPLE_RATE_HZ
                val segments = ArrayList<PurrDspProcessor.ExportAudioSegment>(chunks.size)
                var speechDone = 0
                var renderedSpeechChunks = 0

                for (chunk in chunks) {
                    if (!isActive) return@launch
                    when (chunk) {
                        is PurrChunk.Silence -> {
                            val silenceCount = ((chunk.durationMs * sampleRate) / 1000).coerceAtLeast(1)
                            segments += PurrDspProcessor.ExportAudioSegment(
                                pcmFloat24k = FloatArray(silenceCount),
                                isSilence = true
                            )
                        }
                        is PurrChunk.Speech -> {
                            speechDone++
                            _exportProgressFraction.value = (speechDone.toFloat() / (speechCount + 1).toFloat()).coerceIn(0.05f, 0.90f)
                            _exportStatusLabel.value = "SYNTHESIZING CHUNK $speechDone / $speechCount..."
                            val outcome = backendRouter.synthesizeSpeechChunk(chunk, activeProfile)
                            if (outcome.pcmFloat24k.isNotEmpty()) {
                                segments += PurrDspProcessor.ExportAudioSegment(
                                    pcmFloat24k = outcome.pcmFloat24k,
                                    isSilence = false
                                )
                                renderedSpeechChunks++
                            }
                        }
                    }
                }

                if (renderedSpeechChunks == 0) {
                    _exportStatusLabel.value = "FAILED // NO AUDIO GENERATED"
                    showToast("No TTS cartridge or system synthesizer produced audio!")
                    return@launch
                }

                _exportProgressFraction.value = 0.94f
                _exportStatusLabel.value = "BLENDING & MASTERING 24KHZ TRACK..."
                val masteredFloat = PurrDspProcessor.stitchAndMasterAudiobook(
                    segments = segments,
                    sampleRate = sampleRate,
                    normalizeLoudness = normalizeLoudness,
                    smoothTransitions = smoothTransitions
                )

                val pcm16Bytes = PurrDspProcessor.float32ToPcm16Le(masteredFloat)
                val safeTitle = title.trim()
                    .ifEmpty { "PURR_Audiobook" }
                    .replace(Regex("[^a-zA-Z0-9._-]+"), "_")
                    .trim('_')
                    .ifEmpty { "PURR_Audio" }
                    .take(36)
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val fileName = "${safeTitle}_$timestamp.wav"

                val exportDir = File(app.filesDir, "exported_audio").apply { mkdirs() }
                val localWavFile = File(exportDir, fileName)
                writePcm16MonoWav(localWavFile, pcm16Bytes, pcm16Bytes.size, sampleRate)

                val publicDownloadLabel = copyWavToPublicDownloads(localWavFile, fileName)
                val durationSec = masteredFloat.size.toFloat() / sampleRate.toFloat()
                val voiceLabel = allAvailableVoices()
                    .firstOrNull { it.id == activeProfile.primaryVoiceId }
                    ?.displayName ?: activeProfile.primaryVoiceId

                val record = ExportedAudioFile(
                    id = "exp_${System.currentTimeMillis()}",
                    title = title.trim().ifEmpty { safeTitle },
                    fileName = fileName,
                    localPath = localWavFile.absolutePath,
                    publicDownloadPath = publicDownloadLabel,
                    durationSec = durationSec,
                    sizeBytes = localWavFile.length(),
                    chunkCount = renderedSpeechChunks,
                    voiceLabel = voiceLabel,
                    createdAtEpochMs = System.currentTimeMillis()
                )
                repository.registerExportedAudio(record)

                _exportProgressFraction.value = 1.0f
                _exportStatusLabel.value = "READY // ${"%.1f".format(durationSec)}s (${renderedSpeechChunks} CHUNKS)"
                showToast(
                    if (publicDownloadLabel.isNotBlank()) "[SAVED] $publicDownloadLabel"
                    else "[EXPORTED] $fileName (${"%.1f".format(durationSec)}s)"
                )
            } catch (err: Exception) {
                _exportStatusLabel.value = "ERROR // ${err.message ?: "Export failed"}"
                showToast("Export failed: ${err.message}")
            } finally {
                _isExportingAudio.value = false
                backendRouter.markIdle()
            }
        }
    }

    fun cancelAudioExport() {
        activeExportJob?.cancel()
        activeExportJob = null
        _isExportingAudio.value = false
        _exportProgressFraction.value = 0f
        _exportStatusLabel.value = "CANCELLED // READY TO RENDER"
        showToast("Cancelled audio export")
    }

    fun playExportedAudioFile(entry: ExportedAudioFile) {
        playWavFileInternal(
            wavFile = File(entry.localPath),
            label = "[MEDIA] ${entry.title}",
            summary = "${entry.fileName} (${"%.1f".format(entry.durationSec)}s)"
        )
    }

    fun playCompletedChapterWav(chapter: AudiobookChapter) {
        playWavFileInternal(
            wavFile = File(chapter.renderedWavPath),
            label = "[BOOK] ${chapter.title}",
            summary = "${chapter.wordCount}w (${chapter.renderedDurationMs / 1000}s)"
        )
    }

    private fun playWavFileInternal(wavFile: File, label: String, summary: String) {
        if (!wavFile.exists() || wavFile.length() <= 44L) {
            showToast("Audio file not found on disk!")
            return
        }
        activePlaybackJob?.cancel()
        activePlaybackJob = viewModelScope.launch(Dispatchers.IO) {
            _isPlayingPreview.value = true
            var audioTrack: AudioTrack? = null
            try {
                val allBytes = FileInputStream(wavFile).use { it.readBytes() }
                if (allBytes.size <= 44) return@launch
                val pcmData = allBytes.copyOfRange(44, allBytes.size)
                val sampleRate = PurrDspProcessor.SAMPLE_RATE_HZ
                val minBuf = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(4096)

                audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(minBuf * 2)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                audioTrack.play()

                val stepSize = 4096
                var offset = 0
                while (offset < pcmData.size && isActive) {
                    val count = minOf(stepSize, pcmData.size - offset)
                    val written = audioTrack.write(pcmData, offset, count)
                    if (written <= 0) break
                    val sliceFloat = PurrDspProcessor.pcm16LeToFloat32(pcmData.copyOfRange(offset, offset + count))
                    backendRouter.emitSpeechTelemetry(
                        label = label,
                        badge = HardwareStatusBadge.CPU_NEON,
                        summary = summary,
                        sentiment = "PLAYBACK",
                        bins = PurrDspProcessor.computeWaveformBins(sliceFloat, 32)
                    )
                    offset += count
                }
            } catch (err: Exception) {
                showToast("Playback error: ${err.message}")
            } finally {
                runCatching {
                    audioTrack?.stop()
                    audioTrack?.release()
                }
                backendRouter.markIdle()
                _isPlayingPreview.value = false
            }
        }
    }

    // ✨ AUDIOBOOK CONSTRUCTOR (TOOL-07) ACTIONS
    fun importAudiobookDocument(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            showToast("Parsing ebook chapters...")
            val result = audiobookManager.importEbook(uri)
            result.onSuccess { proj ->
                val activeCh = proj.includedChapters.size
                showToast("Imported '${proj.title}' (${proj.chapters.size} sections, $activeCh selected)")
            }.onFailure { err ->
                showToast("Ebook import failed: ${err.message}")
            }
        }
    }

    fun selectAudiobookProject(projectId: String) = audiobookManager.selectProject(projectId)

    fun deleteAudiobookProject(projectId: String) {
        audiobookManager.deleteProject(projectId)
        showToast("Deleted audiobook project")
    }

    fun toggleAudiobookChapter(projectId: String, chapterIndex: Int, included: Boolean) =
        audiobookManager.toggleChapterIncluded(projectId, chapterIndex, included)

    fun applyAudiobookChapterFilter(projectId: String, mode: AudiobookConstructorManager.ChapterFilterMode) =
        audiobookManager.applyChapterFilter(projectId, mode)

    fun renameAudiobookChapter(projectId: String, chapterIndex: Int, newTitle: String) =
        audiobookManager.renameChapter(projectId, chapterIndex, newTitle)

    fun updateAudiobookProjectOptions(
        projectId: String,
        title: String? = null,
        author: String? = null,
        targetChunkChars: Int? = null,
        paragraphPauseMs: Int? = null,
        chapterGapMs: Int? = null,
        normalizeLoudness: Boolean? = null,
        smoothTransitions: Boolean? = null
    ) = audiobookManager.updateProjectOptions(
        projectId = projectId,
        title = title,
        author = author,
        targetChunkChars = targetChunkChars,
        paragraphPauseMs = paragraphPauseMs,
        chapterGapMs = chapterGapMs,
        normalizeLoudness = normalizeLoudness,
        smoothTransitions = smoothTransitions
    )

    fun startOrResumeAudiobookBuild(projectId: String) =
        audiobookManager.startOrResumeBuild(projectId) { msg -> showToast(msg) }

    fun pauseAudiobookBuild() {
        audiobookManager.pauseBuild()
        showToast("Audiobook build paused — progress saved!")
    }

    fun retryAudiobookChapter(projectId: String, chapterIndex: Int) =
        audiobookManager.retryChapter(projectId, chapterIndex)

    fun combineAudiobookToM4b(projectId: String) =
        audiobookManager.combineIntoFullAudiobook(projectId) { msg -> showToast(msg) }

    fun exportAudiobookChapterWav(projectId: String, chapterIndex: Int) =
        audiobookManager.exportChapterToDownloads(projectId, chapterIndex) { msg -> showToast(msg) }

    fun saveExportedFileToDownloads(entry: ExportedAudioFile) {
        viewModelScope.launch(Dispatchers.IO) {
            val wavFile = File(entry.localPath)
            if (!wavFile.exists()) {
                showToast("Source WAV file missing!")
                return@launch
            }
            val pathLabel = copyWavToPublicDownloads(wavFile, entry.fileName)
            if (pathLabel.isNotBlank()) {
                repository.registerExportedAudio(entry.copy(publicDownloadPath = pathLabel))
                showToast("[SAVED] $pathLabel")
            } else {
                showToast("Saved in app storage: ${wavFile.name}")
            }
        }
    }

    fun deleteExportedAudioFile(entry: ExportedAudioFile) {
        runCatching { File(entry.localPath).delete() }
        repository.removeExportedAudio(entry.id)
        showToast("Deleted [${entry.fileName}]")
    }

    private fun copyWavToPublicDownloads(wavFile: File, fileName: String): String {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "audio/wav")
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/PURR-TTS")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = app.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { out ->
                        FileInputStream(wavFile).use { input -> input.copyTo(out) }
                    }
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    return@runCatching "Downloads/PURR-TTS/$fileName"
                }
            }
            @Suppress("DEPRECATION")
            val downloadsDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "PURR-TTS"
            ).apply { mkdirs() }
            val dest = File(downloadsDir, fileName)
            wavFile.copyTo(dest, overwrite = true)
            "Downloads/PURR-TTS/$fileName"
        }.getOrElse { "" }
    }

    private fun createSyntheticPlaceholderRefWav(label: String): String {
        val outDir = File(app.filesDir, "cloned_refs").apply { mkdirs() }
        val file = File(outDir, "seed_${label.hashCode()}.wav")
        if (!file.exists()) {
            val pcm = ByteArray(24000 * 2)
            writePcm16MonoWav(file, pcm, pcm.size, 24000)
        }
        return file.absolutePath
    }

    private fun writePcm16MonoWav(file: File, pcmData: ByteArray, dataLength: Int, sampleRate: Int) {
        FileOutputStream(file).use { fos ->
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray(Charsets.US_ASCII))
            header.putInt(36 + dataLength)
            header.put("WAVE".toByteArray(Charsets.US_ASCII))
            header.put("fmt ".toByteArray(Charsets.US_ASCII))
            header.putInt(16)
            header.putShort(1.toShort())
            header.putShort(1.toShort())
            header.putInt(sampleRate)
            header.putInt(sampleRate * 2)
            header.putShort(2.toShort())
            header.putShort(16.toShort())
            header.put("data".toByteArray(Charsets.US_ASCII))
            header.putInt(dataLength)
            fos.write(header.array())
            fos.write(pcmData, 0, dataLength)
        }
    }
}
