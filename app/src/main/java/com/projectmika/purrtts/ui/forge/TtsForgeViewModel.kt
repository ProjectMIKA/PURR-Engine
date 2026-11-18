package com.projectmika.purrtts.ui.forge

import android.annotation.SuppressLint
import android.app.Application
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.projectmika.purrtts.PurrApplication
import com.projectmika.purrtts.core.purr.ClonedVoiceProfile
import com.projectmika.purrtts.core.purr.DownloadState
import com.projectmika.purrtts.core.purr.HardwareStatusBadge
import com.projectmika.purrtts.core.purr.MikaStopConfig
import com.projectmika.purrtts.core.purr.ModelBundleSpec
import com.projectmika.purrtts.core.purr.PhoneticRule
import com.projectmika.purrtts.core.purr.PurrChunk
import com.projectmika.purrtts.core.purr.PurrDspProcessor
import com.projectmika.purrtts.core.purr.PurrForgeState
import com.projectmika.purrtts.core.purr.PurrProfile
import com.projectmika.purrtts.core.purr.PurrTextProcessor
import com.projectmika.purrtts.core.purr.RuleCategory
import com.projectmika.purrtts.core.purr.SherpaModelFamily
import com.projectmika.purrtts.core.purr.VoiceDescriptor
import com.projectmika.purrtts.core.purr.VoiceGender
import com.projectmika.purrtts.engine.TtsBackendRouter
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
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

    val forgeState: StateFlow<PurrForgeState> = repository.state
    val discoveredSystemVoices: StateFlow<List<VoiceDescriptor>> = repository.discoveredSystemVoices
    val telemetry: StateFlow<TtsBackendRouter.LiveTelemetry> = backendRouter.telemetry
    val vramRemainingSeconds: StateFlow<Int> = backendRouter.liteRtEngine.vramRemainingSeconds
    val modelCatalog: List<ModelBundleSpec> = catalogManager.catalog

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    private val _cartridgeAnim = MutableStateFlow<CartridgeAnimEvent?>(null)
    val cartridgeAnim: StateFlow<CartridgeAnimEvent?> = _cartridgeAnim.asStateFlow()

    private val _testInputText = MutableStateFlow(
        "O-oh! Wait... *blushes deeply* \"Are you serious right now, Master?!\" omg fr nya~"
    )
    val testInputText: StateFlow<String> = _testInputText.asStateFlow()

    private val _isPlayingPreview = MutableStateFlow(false)
    val isPlayingPreview: StateFlow<Boolean> = _isPlayingPreview.asStateFlow()

    private val _isRecordingClone = MutableStateFlow(false)
    val isRecordingClone: StateFlow<Boolean> = _isRecordingClone.asStateFlow()

    private val _recordingProgress = MutableStateFlow(0f)
    val recordingProgress: StateFlow<Float> = _recordingProgress.asStateFlow()

    private val _lastRecordedWavPath = MutableStateFlow<String?>(null)
    val lastRecordedWavPath: StateFlow<String?> = _lastRecordedWavPath.asStateFlow()

    private var activePlaybackJob: Job? = null
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

    fun dismissCartridgeAnim() {
        activeAnimJob?.cancel()
        _cartridgeAnim.value = null
    }

    fun updateTestInputText(text: String) {
        _testInputText.value = text
    }

    fun allAvailableVoices(): List<VoiceDescriptor> = repository.allAvailableVoices()

    fun previewChunks(text: String, profile: PurrProfile, rules: List<PhoneticRule>): List<PurrChunk> {
        return PurrTextProcessor.process(text, profile, rules)
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
        if (slotIn && modelState?.state != DownloadState.INSTALLED) {
            showToast("Download ${spec.romCode} first before slotting!")
            return
        }
        repository.setCartridgeSlotted(spec.id, slotIn)
        if (!slotIn) {
            viewModelScope.launch { backendRouter.evictAllVram() }
        } else {
            repository.selectEngineCartridge(spec.id)
        }

        activeAnimJob?.cancel()
        _cartridgeAnim.value = CartridgeAnimEvent(spec, slotIn)
        activeAnimJob = viewModelScope.launch {
            delay(1750L)
            _cartridgeAnim.value = null
        }
        showToast(if (slotIn) "💾 Slotted [${spec.romCode}: ${spec.name}]" else "⏏️ Ejected [${spec.romCode}: ${spec.name}]")
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
                    customRules = repository.state.value.customPhoneticRules
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

                for (chunk in chunks) {
                    if (!isActive) break
                    when (chunk) {
                        is PurrChunk.Silence -> {
                            backendRouter.emitSilenceTelemetry(chunk.durationMs, chunk.reason)
                            val silenceBytes = PurrDspProcessor.createSilencePcm16Le(sampleRate, chunk.durationMs)
                            audioTrack.write(silenceBytes, 0, silenceBytes.size)
                            delay(chunk.durationMs.toLong())
                        }
                        is PurrChunk.Speech -> {
                            val outcome = backendRouter.synthesizeSpeechChunk(chunk, effectiveProfile)
                            if (outcome.pcmFloat24k.isNotEmpty()) {
                                val pcmBytes = PurrDspProcessor.float32ToPcm16Le(outcome.pcmFloat24k)
                                var offset = 0
                                while (offset < pcmBytes.size && isActive) {
                                    val step = minOf(4096, pcmBytes.size - offset)
                                    audioTrack.write(pcmBytes, offset, step)
                                    offset += step
                                }
                            } else if (repository.isCartridgeSlotted("rom_core_os")) {
                                // Direct speaker fallback when OEM TTS blocks synthesizeToFile and ROM-01 is slotted
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
                                if (!spoken) {
                                    showToast("Install Google Speech Services or a ROM Cartridge in Tab 3!")
                                    break
                                }
                            } else {
                                showToast("No active voice cartridge ready — slam a cartridge in Tab 3!")
                                break
                            }
                        }
                    }
                }
            } catch (err: Exception) {
                showToast("Audition error: ${err.message}")
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

    fun stopAudition() {
        activePlaybackJob?.cancel()
        backendRouter.proxyEngine.stopDirectAudition()
        backendRouter.markIdle()
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

    fun registerClonedVoice(
        displayName: String,
        family: SherpaModelFamily,
        gender: VoiceGender,
        transcript: String,
        tagsRaw: String,
        wavPathOverride: String? = null
    ) {
        val cleanName = displayName.trim().ifEmpty { "Mika Clone" }
        val wavPath = wavPathOverride ?: _lastRecordedWavPath.value ?: createSyntheticPlaceholderRefWav(cleanName)
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
            tags = parsedTags
        )
        repository.registerClonedVoice(cloned)
        showToast("Registered Cloned Voice [$cleanName]")
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
