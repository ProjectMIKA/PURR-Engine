package com.projectmika.purrtts.core.purr

import kotlinx.serialization.Serializable

/**
 * ✨ MIKA'S P.U.R.R. ENGINE DATA MODELS ✨
 * Shared immutable contracts between PurrTtsService, TtsBackendRouter,
 * Cartridge Bay manager, and the streamlined Compose UI.
 */

/**
 * ✨ HEAVY-MODEL PROXY PRESETS (v1.6.0) ✨
 * The "not meant for mobile" tier: any OpenAI-compatible TTS server carries the heavy brain
 * (CosyVoice, F5-TTS, XTTS-v2, Kokoro fp16 on a GPU box...) while the phone streams audio.
 * Known-open Qwen TTS verdict: Qwen-Audio-TTS / Qwen3-TTS are DashScope WebSocket-only,
 * closed weights — NOT reachable through this OpenAI-compatible bridge, so no preset.
 */
data class ProxyPreset(
    val id: String,
    val label: String,
    val baseUrl: String,
    val model: String,
    val voice: String,
    val note: String
)

object PurrProxyPresets {
    val PRESETS: List<ProxyPreset> = listOf(
        ProxyPreset(
            id = "kokoro_fastapi_lan",
            label = "KOKORO_FASTAPI // LAN",
            baseUrl = "http://192.168.1.50:8880/v1/audio/speech",
            model = "kokoro",
            voice = "af_heart",
            note = "Kokoro-82M FP16 on your PC — studio quality, GPU speed"
        ),
        ProxyPreset(
            id = "openedai_speech_lan",
            label = "OPENEDAI_SPEECH // LAN",
            baseUrl = "http://192.168.1.50:8000/v1/audio/speech",
            model = "tts-1",
            voice = "alloy",
            note = "openedai-speech server — XTTS / Piper mix on your PC"
        ),
        ProxyPreset(
            id = "f5_tts_lan",
            label = "F5_TTS // LAN",
            baseUrl = "http://192.168.1.50:8080/v1/audio/speech",
            model = "f5-tts",
            voice = "default",
            note = "F5-TTS OpenAI-compatible wrapper — zero-shot cloning server-side"
        ),
        ProxyPreset(
            id = "openai_official",
            label = "OPENAI_CLOUD",
            baseUrl = "https://api.openai.com/v1/audio/speech",
            model = "gpt-4o-mini-tts",
            voice = "coral",
            note = "Official OpenAI TTS — needs an API key, cloud-only"
        )
    )
}

@Serializable
enum class BackendType(val displayLabel: String) {
    CORE_OS("System Speech (Built-in)"),
    SHERPA_ONNX("Sherpa-ONNX Cartridge"),
    LITERT_NPU_GPU("LiteRT GPU/NPU Cartridge"),
    ONNX_NATIVE("ONNX Runtime Native"),
    QWEN3_NATIVE("Qwen3-TTS GGUF+ONNX Native"),
    EXTERNAL_PROXY("LAN OpenAI / Proxy")
}

@Serializable
enum class SherpaModelFamily(val label: String) {
    KOKORO("Kokoro-82M INT8"),
    KITTEN_TTS("KittenTTS Nano"),
    SUPERTONIC_3("Supertonic 3"),
    POCKET_TTS("Pocket TTS (Clone)"),
    ZIPVOICE("ZipVoice (Clone)"),
    PIPER("Piper VITS"),
    MATCHA("Matcha-TTS"),
    MELO_TTS("MeloTTS English")
}

@Serializable
enum class CartridgeCategory(val label: String) {
    TTS("TTS_ENGINES"),
    TOOLS("TOOL_MODULES")
}

@Serializable
enum class HardwareStatusBadge(val terminalLabel: String) {
    NPU_ACTIVE("[NPU ACTIVE]"),
    GPU_FP16("[GPU FP16]"),
    CPU_NEON("[CPU NEON]"),
    CORE_OS("[CORE_OS]"),
    PROXY_LINK("[PROXY LINK]"),
    IDLE("[IDLE]")
}

@Serializable
enum class ActionHandlingMode(val label: String, val description: String) {
    NARRATE_SOFT("SOFT_NARRATION", "Read *actions* softer and slower as narrator prose"),
    STRIP_ACTIONS("SKIP_ACTIONS", "Skip *action blocks* and insert a clean MIKASTOP pause"),
    DUAL_VOICE_ROUTING("NORMAL_CADENCE", "Read *actions* at normal pace with narrator voice")
}

@Serializable
enum class SegmentRole {
    NARRATION,
    DIALOGUE,
    ACTION
}

@Serializable
enum class ArchetypeTone(val label: String) {
    AUTO("AUTO (Sentiment)"),
    NORMAL("NORMAL (Flat)"),
    JOY("JOY"),
    GENKI_JOY("GENKI_JOY"),
    LOVE("LOVE"),
    YANDERE_LOVE("YANDERE_LOVE"),
    SHY_LOVE("SHY_LOVE"),
    DOMINANT_LOVE("DOMINANT_LOVE"),
    TSUNDERE_LOVE("TSUNDERE_LOVE"),
    ANGER("ANGER"),
    TSUNDERE_ANGER("TSUNDERE_ANGER"),
    KUUDERE_ANGER("KUUDERE_ANGER"),
    YANDERE_ANGER("YANDERE_ANGER"),
    SADNESS("SADNESS"),
    SHY_SADNESS("SHY_SADNESS"),
    SHY("SHY / FLUSTERED"),
    KUUDERE("KUUDERE"),
    ROBOT("ROBOT / ANDROID")
}

@Serializable
enum class AcousticEffectPreset(val label: String, val summary: String = "") {
    NONE("CLEAN_STUDIO", "Crystal-clear voice with GachaSwipe 120ms sentence taper & 8.5kHz de-esser"),
    WARM_STUDIO("INTIMATE_WARMTH", "Chest resonance peaking at 250Hz (Q=1.5) + smooth de-esser"),
    ETHEREAL("ETHEREAL", "Ghost / Demon 2.0s lush exponential decay aura"),
    ANGELIC("ANGELIC", "Goddess / Saint 300Hz high-pass + 1.5s airy shimmer"),
    IDOL("STAGE_MIC", "Idol / Singer 100ms stage slapback delay (15% wet)"),
    RADIO("CYBER_RADIO", "Mecha / Android 1200Hz bandpass + harmonic waveshaper"),
    TINY("TINY_CHIBI", "Fairy / Chibi 450Hz high-pass filter"),
    FERAL("FERAL_BEAST", "Beast / Dragon 200Hz low-shelf + warm saturation"),
    LIQUID("LIQUID_AURA", "Slime / Mermaid 1200Hz low-pass + 400Hz resonant peak"),
    MONSTER("DEEP_MONSTER", "Deep / Ogre 1800Hz low-pass + 250Hz +4dB sub-boost")
}

@Serializable
enum class CommaHandlingMode(val label: String, val description: String) {
    MIKA_PAUSE_AND_STRIP("MIKA_PAUSE_STRIP", "MIKA inserts a breath pause and strips the comma so TTS never hallucinates"),
    STRIP_NO_PAUSE("STRIP_NO_PAUSE", "Strip commas without pausing for smooth continuous audiobook flow"),
    KEEP_RAW("PASS_TO_TTS", "Pass raw commas directly to the TTS model")
}

@Serializable
data class SymbolShieldConfig(
    val stripQuotationMarks: Boolean = true,
    val commaHandling: CommaHandlingMode = CommaHandlingMode.MIKA_PAUSE_AND_STRIP,
    val stripBracketsAndMarkdown: Boolean = true,
    val expandCommonSymbols: Boolean = true,
    val customSkippedChars: String = "\"“”„«»‹›*_#~|^`[]{}()<>\\/$§¶†‡•◆★☆"
)

@Serializable
enum class VoiceGender(val tag: String) {
    FEMALE("female"),
    MALE("male"),
    ANDROGYNOUS("androgynous")
}

@Serializable
enum class RuleCategory(val label: String) {
    SLANG("SLANG"),
    HONORIFIC("HONORIFIC"),
    STUTTER("STUTTER"),
    CUSTOM("CUSTOM")
}

@Serializable
data class PhoneticRule(
    val id: String,
    val pattern: String,
    val replacement: String,
    val isRegex: Boolean = false,
    val ignoreCase: Boolean = true,
    val enabled: Boolean = true,
    val category: RuleCategory = RuleCategory.CUSTOM
)

@Serializable
data class MikaStopConfig(
    val ellipsisPauseMs: Int = 450,
    val emDashPauseMs: Int = 320,
    val sentencePauseMs: Int = 280,
    val commaPauseMs: Int = 140,
    val actionStripPauseMs: Int = 200,
    val sliceAtCommas: Boolean = true,
    val minWordsForCommaPause: Int = 3,
    val targetChunkChars: Int = 0
)

@Serializable
data class SentimentCurveConfig(
    val enabled: Boolean = true,
    val toneOverride: ArchetypeTone = ArchetypeTone.AUTO,
    val joyRateDelta: Float = 0.04f,
    val joyPitchDelta: Float = 0.0f,
    val loveRateDelta: Float = -0.05f,
    val lovePitchDelta: Float = 0.0f,
    val angerRateDelta: Float = 0.05f,
    val angerPitchDelta: Float = 0.0f,
    val sadnessRateDelta: Float = -0.08f,
    val sadnessPitchDelta: Float = 0.0f,
    val flusteredRateDelta: Float = 0.06f,
    val flusteredPitchDelta: Float = 0.0f,
    val stutterIntensity: Float = 0.15f,
    val vowelStretchIntensity: Float = 0.15f
)

@Serializable
data class DspConfig(
    val warmthEqDb: Float = 0.0f,
    val presenceEqDb: Float = 0.0f,
    val reverbWetMix: Float = 0.0f,
    val effectPreset: AcousticEffectPreset = AcousticEffectPreset.NONE,
    val enableSentenceTaper: Boolean = true,
    val sentenceTaperMs: Int = 120,
    val antiPopFadeInMs: Int = 25,
    val normalizePeak: Boolean = true,
    val useFloat32Callback: Boolean = false
)

@Serializable
data class ProxyEndpointConfig(
    val useOpenAiEndpoint: Boolean = false,
    val openAiBaseUrl: String = "http://192.168.1.50:8880/v1/audio/speech",
    val openAiApiKey: String = "",
    val openAiModel: String = "kokoro",
    val openAiVoice: String = "af_bella",
    val fallbackSystemEnginePackage: String = ""
)

@Serializable
data class PurrProfile(
    val id: String,
    val name: String,
    val badgeTag: String = "CUSTOM",
    val summary: String = "Custom PURR speech preset",
    val selectedCartridgeId: String = "rom_core_os",
    val preferredBackend: BackendType = BackendType.CORE_OS,
    val sherpaFamily: SherpaModelFamily = SherpaModelFamily.KOKORO,
    val primaryVoiceId: String = "core_os_auto",
    val dialogueVoiceId: String = "af_sarah",
    val enableDualVoiceSplit: Boolean = false,
    val actionHandlingMode: ActionHandlingMode = ActionHandlingMode.NARRATE_SOFT,
    val narrationVolumeMultiplier: Float = 0.78f,
    val narrationRateMultiplier: Float = 0.92f,
    val baseSpeed: Float = 1.0f,
    val basePitch: Float = 1.0f,
    val enableSlangAndHonorifics: Boolean = true,
    val enableMikaStopPauses: Boolean = true,
    val enableDsp: Boolean = true,
    val enableSymbolShield: Boolean = true,
    val characterTags: List<String> = listOf("cute", "energetic"),
    val affectionScore: Int = 75,
    val whitelist: Set<String> = emptySet(),
    val blacklist: Set<String> = emptySet(),
    val customVoiceTags: Map<String, Set<String>> = emptyMap(),
    val mikaStopConfig: MikaStopConfig = MikaStopConfig(),
    val sentimentConfig: SentimentCurveConfig = SentimentCurveConfig(),
    val dspConfig: DspConfig = DspConfig(),
    val symbolShieldConfig: SymbolShieldConfig = SymbolShieldConfig(),
    val proxyConfig: ProxyEndpointConfig = ProxyEndpointConfig(),
    val voiceDesignPrompt: String = "Warm, expressive young woman with crisp close-mic clarity and gentle natural pacing",
    val autoBypassEnhancersForNeuralModels: Boolean = true
)

@Serializable
data class SentimentResult(
    val joy: Float = 0f,
    val love: Float = 0f,
    val anger: Float = 0f,
    val sadness: Float = 0f,
    val flustered: Float = 0f,
    val dominantLabel: String = "NEUTRAL"
)

sealed interface PurrChunk {
    data class Speech(
        val text: String,
        val role: SegmentRole,
        val targetVoiceId: String?,
        val speakingRate: Float,
        val pitch: Float,
        val volumeGain: Float,
        val sentiment: SentimentResult
    ) : PurrChunk

    data class Silence(
        val durationMs: Int,
        val reason: String
    ) : PurrChunk
}

@Serializable
data class VoiceDescriptor(
    val id: String,
    val displayName: String,
    val engine: BackendType,
    val cartridgeId: String = "rom_core_os",
    val sherpaFamily: SherpaModelFamily? = null,
    val gender: VoiceGender = VoiceGender.FEMALE,
    val languageCode: String = "en-US",
    val speakerIndex: Int = 0,
    val nativeTags: List<String> = emptyList(),
    val isCloned: Boolean = false,
    val requiredSubCartridgeId: String? = null
)

@Serializable
data class ClonedVoiceProfile(
    val id: String,
    val displayName: String,
    val family: SherpaModelFamily = SherpaModelFamily.POCKET_TTS,
    val cloneEngineId: String = "",
    val gender: VoiceGender = VoiceGender.FEMALE,
    val languageCode: String = "en-US",
    val referenceWavPath: String,
    val referenceTranscript: String,
    val tags: List<String> = listOf("cloned", "custom")
) {
    val resolvedCloneEngineId: String
        get() = cloneEngineId.ifBlank {
            if (family == SherpaModelFamily.ZIPVOICE) "rom_zipvoice" else "rom_pocket_tts"
        }
}

@Serializable
data class BundleAssetFile(
    val relativePath: String,
    val url: String,
    val expectedSha256: String = "",
    val approxSizeBytes: Long = 0L
)

@Serializable
enum class DownloadState {
    NOT_INSTALLED,
    DOWNLOADING,
    VERIFYING,
    EXTRACTING,
    INSTALLED,
    FAILED
}

@Serializable
data class ModelBundleSpec(
    val id: String,
    val romCode: String,
    val badge: String,
    val accentHex: Long,
    val name: String,
    val subtitle: String,
    val description: String,
    val backend: BackendType,
    val sherpaFamily: SherpaModelFamily? = null,
    val downloadUrl: String,
    val fileName: String,
    val expectedSha256: String,
    val approximateSizeMb: Int,
    val isArchiveTarBz2: Boolean = false,
    val hardwareAccelNote: String,
    val isBuiltInOsCartridge: Boolean = false,
    val isHubContainer: Boolean = false,
    val unlocksSummary: String = "",
    val isSubCartridge: Boolean = false,
    val parentCartridgeId: String? = null,
    // ✨ PREMIUM TIER (v1.6.0): optional companion asset (e.g. Matcha's vocoder) downloaded,
    // SHA-256-verified and unpacked into the same extract dir as the main archive.
    val companionUrl: String = "",
    val companionFileName: String = "",
    val companionExpectedSha256: String = "",
    val companionApproxSizeMb: Int = 0,
    // ✨ MULTI-FILE HEAVY BUNDLES (v1.7.0): extra ONNX/GGUF/NPY/Tokenizer assets for
    // Qwen3-TTS, Chatterbox-Turbo, NeuTTS-2E, and Soprano-80M.
    val extraAssets: List<BundleAssetFile> = emptyList(),
    val category: CartridgeCategory = CartridgeCategory.TTS
)

@Serializable
data class ModelBundleState(
    val specId: String,
    val state: DownloadState = DownloadState.NOT_INSTALLED,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val sha256Verified: Boolean = false,
    val localPath: String = "",
    val errorMessage: String? = null
) {
    val progressFraction: Float
        get() = if (totalBytes > 0L) (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
}

@Serializable
data class ExportedAudioFile(
    val id: String,
    val title: String,
    val fileName: String,
    val localPath: String,
    val publicDownloadPath: String = "",
    val durationSec: Float = 0f,
    val sizeBytes: Long = 0L,
    val chunkCount: Int = 1,
    val voiceLabel: String = "",
    val createdAtEpochMs: Long = 0L
)

@Serializable
data class PurrForgeState(
    val activeProfileId: String = "gachaswipe_default",
    val profiles: List<PurrProfile> = PurrDefaultPresets.builtInProfiles(),
    val customPhoneticRules: List<PhoneticRule> = PurrDefaultPresets.defaultCustomRules(),
    val clonedVoices: List<ClonedVoiceProfile> = emptyList(),
    val exportedAudioFiles: List<ExportedAudioFile> = emptyList(),
    val modelStates: Map<String, ModelBundleState> = mapOf(
        "rom_core_os" to ModelBundleState(
            specId = "rom_core_os",
            state = DownloadState.INSTALLED,
            sha256Verified = true,
            localPath = "android://system_tts"
        ),
        "tool_purr_engine" to ModelBundleState(
            specId = "tool_purr_engine",
            state = DownloadState.INSTALLED,
            sha256Verified = true,
            localPath = "builtin://tool/purr_engine"
        ),
        "tool_dsp_rack" to ModelBundleState(
            specId = "tool_dsp_rack",
            state = DownloadState.INSTALLED,
            sha256Verified = true,
            localPath = "builtin://tool/dsp_rack"
        ),
        "tool_symbol_shield" to ModelBundleState(
            specId = "tool_symbol_shield",
            state = DownloadState.INSTALLED,
            sha256Verified = true,
            localPath = "builtin://tool/symbol_shield"
        ),
        "tool_clone_studio" to ModelBundleState(
            specId = "tool_clone_studio",
            state = DownloadState.INSTALLED,
            sha256Verified = true,
            localPath = "builtin://tool/clone_studio"
        ),
        "tool_audio_exporter" to ModelBundleState(
            specId = "tool_audio_exporter",
            state = DownloadState.INSTALLED,
            sha256Verified = true,
            localPath = "builtin://tool/audio_exporter"
        ),
        "tool_voice_design" to ModelBundleState(
            specId = "tool_voice_design",
            state = DownloadState.INSTALLED,
            sha256Verified = true,
            localPath = "builtin://tool/voice_design"
        ),
        "tool_audiobook_constructor" to ModelBundleState(
            specId = "tool_audiobook_constructor",
            state = DownloadState.INSTALLED,
            sha256Verified = true,
            localPath = "builtin://tool/audiobook_constructor"
        )
    ),
    val slottedCartridges: Set<String> = setOf(
        "rom_core_os",
        "tool_purr_engine",
        "tool_dsp_rack",
        "tool_symbol_shield",
        "tool_clone_studio",
        "tool_audio_exporter",
        "tool_voice_design",
        "tool_audiobook_constructor"
    ),
    val enabledSubCartridges: Set<String> = emptySet(),
    val savedVoiceDesigns: List<SavedVoiceDesign> = emptyList(),
    val knownToolCatalogVersion: Int = 2,
    // ✨ MIKA'S ACADEMY (v1.5.0): persisted tutorial-content version (0 = never seen).
    // Drives PurrUpdateGate: first-launch full walkthrough + one-time patchnotes popup per bump.
    val seenTutorialVersion: Int = 0
)

/**
 * ✨ NEURAL PURE MODE GUARD (v1.8.0) ✨
 * Heavyweight neural LLM/flow models (Qwen3-TTS, NeuTTS-2E, Chatterbox-Turbo, Soprano-80M,
 * Studio FP32/Full clones & Kokoro v1.1/FP32) naturally perform their own prosody and text
 * normalization — aggressive slang expansion, stutters, comma-stripping & micro-chunking make
 * them sound worse, so PURR enhancers automatically switch to Neural Pure Mode when active.
 */
object PurrNeuralBypass {

    private val ADVANCED_CARTRIDGES = setOf(
        "rom_qwen3_custom_06b",
        "rom_neutts_2e",
        "rom_soprano_80m"
    )

    private val ADVANCED_VOICE_PREFIXES = listOf(
        "qwen3_",
        "neutts_",
        "soprano_",
        "chatterbox_",
        "fp32_",
        "v11_",
        "kmini_",
        "melo_"
    )

    private val ADVANCED_EXACT_VOICE_IDS = setOf(
        "zipvoice_full_studio",
        "pocket_fp32_zero_shot",
        "matcha_narrator"
    )

    private val ADVANCED_CLONE_ENGINE_IDS = setOf(
        "sub_qwen3_base_06b",
        "sub_chatterbox_turbo",
        "sub_zipvoice_full",
        "sub_pocket_fp32"
    )

    fun isAdvancedNeuralVoice(
        profile: PurrProfile,
        clonedVoices: List<ClonedVoiceProfile> = emptyList()
    ): Boolean {
        if (profile.preferredBackend == BackendType.QWEN3_NATIVE ||
            profile.preferredBackend == BackendType.ONNX_NATIVE
        ) {
            return true
        }
        if (profile.selectedCartridgeId in ADVANCED_CARTRIDGES) {
            return true
        }
        val cleanId = profile.primaryVoiceId.substringAfter(':')
        if (ADVANCED_VOICE_PREFIXES.any { cleanId.startsWith(it) } ||
            cleanId in ADVANCED_EXACT_VOICE_IDS
        ) {
            return true
        }
        return clonedVoices.any {
            it.id == cleanId && it.resolvedCloneEngineId in ADVANCED_CLONE_ENGINE_IDS
        }
    }

    fun shouldBypassEnhancers(
        profile: PurrProfile,
        clonedVoices: List<ClonedVoiceProfile> = emptyList()
    ): Boolean = profile.autoBypassEnhancersForNeuralModels && isAdvancedNeuralVoice(profile, clonedVoices)
}

object PurrDefaultPresets {

    val GACHASWIPE_TTS_PRESETS: Map<String, List<String>> = mapOf(
        "af_bella" to listOf("female", "american", "cute", "energetic", "high-pitched", "yandere"),
        "af_sarah" to listOf("female", "american", "calm", "mature", "soft", "ara-ara", "oneesan"),
        "af_nicole" to listOf("female", "american", "breathy", "soft", "shy", "dandere"),
        "af_sky" to listOf("female", "american", "calm", "soft", "kuudere"),
        "af_alloy" to listOf("female", "american", "energetic", "loud", "tomboy", "tsundere"),
        "af_aoede" to listOf("female", "american", "cute", "shy", "dandere"),
        "af_jessica" to listOf("female", "american", "mature", "deep", "dominant", "mommy"),
        "af_kore" to listOf("female", "american", "calm", "mature", "kuudere"),
        "af_river" to listOf("female", "american", "calm", "soft", "narrator"),
        "af_heart" to listOf("female", "american", "warm", "cute", "soft", "narrator"),
        "af_nova" to listOf("female", "american", "energetic", "teasing", "idol"),
        "am_adam" to listOf("male", "american", "deep", "calm", "narrator"),
        "am_echo" to listOf("male", "american", "energetic", "loud"),
        "am_eric" to listOf("male", "american", "calm", "deep"),
        "am_fenrir" to listOf("male", "american", "energetic", "deep"),
        "am_liam" to listOf("male", "american", "young", "energetic", "boyish"),
        "am_michael" to listOf("male", "american", "mature", "deep", "audiobook"),
        "am_onyx" to listOf("male", "american", "deep", "calm", "narrator"),
        "am_puck" to listOf("male", "american", "energetic", "teasing"),
        "am_santa" to listOf("male", "american", "warm", "deep", "mature"),
        "bf_emma" to listOf("female", "british", "mature", "calm", "maid", "elegant"),
        "bf_isabella" to listOf("female", "british", "cute", "energetic", "oujo-sama", "himedere"),
        "bf_alice" to listOf("female", "british", "calm", "soft", "shy"),
        "bf_lily" to listOf("female", "british", "cute", "high-pitched", "childhood friend"),
        "bm_george" to listOf("male", "british", "deep", "calm", "butler"),
        "bm_fable" to listOf("male", "british", "energetic", "narrator"),
        "bm_lewis" to listOf("male", "british", "calm", "audiobook"),
        "bm_daniel" to listOf("male", "british", "mature", "deep")
    )

    fun kokoroVoices(
        cartridgeId: String,
        backend: BackendType,
        idPrefix: String = "",
        displaySuffix: String = "",
        requiredSubCartridgeId: String? = null
    ): List<VoiceDescriptor> {
        val kokoroIds = listOf(
            "af_heart", "af_alloy", "af_aoede", "af_bella", "af_jessica", "af_kore",
            "af_nicole", "af_nova", "af_river", "af_sarah", "af_sky",
            "am_adam", "am_echo", "am_eric", "am_fenrir", "am_liam", "am_michael",
            "am_onyx", "am_puck", "am_santa",
            "bf_alice", "bf_emma", "bf_isabella", "bf_lily",
            "bm_daniel", "bm_fable", "bm_george", "bm_lewis",
            "jf_alpha", "jf_gongitsune", "jf_nezumi", "jf_tebukuro", "jm_kumo",
            "zf_xiaobei", "zf_xiaoni", "zf_xiaoxiao", "zf_xiaoyi",
            "zm_yunjian", "zm_yunxi", "zm_yunxia", "zm_yunyang",
            "ef_dora", "em_alex", "em_santa",
            "ff_siwis",
            "hf_alpha", "hf_beta", "hm_omega", "hm_psi",
            "if_sara", "im_nicola",
            "pf_dora", "pm_alex", "pm_santa"
        )
        return kokoroIds.mapIndexed { idx, id ->
            val prefix = id.substringBefore('_')
            val gender = if (prefix.endsWith("m")) VoiceGender.MALE else VoiceGender.FEMALE
            val lang = when (prefix.firstOrNull()) {
                'a' -> "en-US"
                'b' -> "en-GB"
                'j' -> "ja-JP"
                'z' -> "zh-CN"
                'e' -> "es-ES"
                'f' -> "fr-FR"
                'h' -> "hi-IN"
                'i' -> "it-IT"
                'p' -> "pt-BR"
                else -> "en-US"
            }
            val prettyName = id.split('_').joinToString(" ") { p ->
                p.replaceFirstChar { it.uppercase() }
            } + displaySuffix
            val tags = GACHASWIPE_TTS_PRESETS[id] ?: listOf(gender.tag, lang.lowercase(), "kokoro")
            VoiceDescriptor(
                id = "$idPrefix$id",
                displayName = prettyName,
                engine = backend,
                cartridgeId = cartridgeId,
                sherpaFamily = if (backend == BackendType.SHERPA_ONNX) SherpaModelFamily.KOKORO else null,
                gender = gender,
                languageCode = lang,
                speakerIndex = idx,
                nativeTags = tags,
                requiredSubCartridgeId = requiredSubCartridgeId
            )
        }
    }

    /**
     * ✨ KOKORO v1.1-zh ROSTER (ROM-09, 103 speakers) ✨
     * Speaker IDs 0..102 in weights order: 2 English (af_maple, af_sol, bf_vale = 3),
     * 55 Chinese female (zf_*), 45 Chinese male (zm_*). sid = list index.
     */
    private val KOKORO_V11_ROSTER: List<String> = listOf(
        "af_maple", "af_sol", "bf_vale",
        "zf_001", "zf_002", "zf_003", "zf_004", "zf_005", "zf_006", "zf_007", "zf_008", "zf_017",
        "zf_018", "zf_019", "zf_021", "zf_022", "zf_023", "zf_024", "zf_026", "zf_027", "zf_028",
        "zf_032", "zf_036", "zf_038", "zf_039", "zf_040", "zf_042", "zf_043", "zf_044", "zf_046",
        "zf_047", "zf_048", "zf_049", "zf_051", "zf_059", "zf_060", "zf_067", "zf_070", "zf_071",
        "zf_072", "zf_073", "zf_074", "zf_075", "zf_076", "zf_077", "zf_078", "zf_079", "zf_083",
        "zf_084", "zf_085", "zf_086", "zf_087", "zf_088", "zf_090", "zf_092", "zf_093", "zf_094",
        "zf_099",
        "zm_009", "zm_010", "zm_011", "zm_012", "zm_013", "zm_014", "zm_015", "zm_016", "zm_020",
        "zm_025", "zm_029", "zm_030", "zm_031", "zm_033", "zm_034", "zm_035", "zm_037", "zm_041",
        "zm_045", "zm_050", "zm_052", "zm_053", "zm_054", "zm_055", "zm_056", "zm_057", "zm_058",
        "zm_061", "zm_062", "zm_063", "zm_064", "zm_065", "zm_066", "zm_068", "zm_069", "zm_080",
        "zm_081", "zm_082", "zm_089", "zm_091", "zm_095", "zm_096", "zm_097", "zm_098", "zm_100"
    )

    fun kokoroV11Voices(
        cartridgeId: String,
        backend: BackendType,
        requiredSubCartridgeId: String = "rom_kokoro_v11"
    ): List<VoiceDescriptor> =
        KOKORO_V11_ROSTER.mapIndexed { sid, id ->
            val prefix = id.substringBefore('_')
            val gender = if (prefix.endsWith("m")) VoiceGender.MALE else VoiceGender.FEMALE
            val lang = when (prefix.firstOrNull()) {
                'z' -> "zh-CN"
                'b' -> "en-GB"
                else -> "en-US"
            }
            VoiceDescriptor(
                // ✨ v1.6.2: "v11_" prefix — voice IDs must be globally unique across cartridges
                // (ROM-02 and sub-cartridges share base names; a bare id would resolve to the WRONG
                // descriptor + mount the wrong weights dir, fail-softing to CORE_OS).
                id = "v11_$id",
                displayName = id.split('_').joinToString(" ") { p -> p.replaceFirstChar { it.uppercase() } } +
                    if (lang == "zh-CN") " (中文)" else "",
                engine = backend,
                cartridgeId = cartridgeId,
                sherpaFamily = SherpaModelFamily.KOKORO,
                gender = gender,
                languageCode = lang,
                speakerIndex = sid,
                nativeTags = listOf(gender.tag, if (lang == "zh-CN") "chinese" else "american", "kokoro", "v11", "multilingual"),
                requiredSubCartridgeId = requiredSubCartridgeId
            )
        }

    fun matchaVoices(
        cartridgeId: String,
        backend: BackendType,
        requiredSubCartridgeId: String? = "rom_matcha_zh_en"
    ): List<VoiceDescriptor> = listOf(
        VoiceDescriptor(
            id = "matcha_narrator",
            displayName = "Matcha Narrator (ZH/EN)",
            engine = backend,
            cartridgeId = cartridgeId,
            sherpaFamily = SherpaModelFamily.MATCHA,
            gender = VoiceGender.FEMALE,
            languageCode = "zh-CN",
            speakerIndex = 0,
            nativeTags = listOf("female", "narrator", "matcha", "flow", "chinese", "multilingual", "bilingual"),
            requiredSubCartridgeId = requiredSubCartridgeId
        )
    )

    fun builtInVoices(): List<VoiceDescriptor> {
        val coreOsVoices = listOf(
            VoiceDescriptor(
                id = "core_os_auto",
                displayName = "Android Device Default Voice",
                engine = BackendType.CORE_OS,
                cartridgeId = "rom_core_os",
                gender = VoiceGender.ANDROGYNOUS,
                languageCode = "en-US",
                nativeTags = listOf("built-in", "coherent", "always-ready")
            )
        )

        val kokoroSherpa = kokoroVoices("rom_kokoro_sherpa", BackendType.SHERPA_ONNX)
        val kokoroFp32 = kokoroVoices(
            "rom_kokoro_sherpa",
            BackendType.SHERPA_ONNX,
            idPrefix = "fp32_",
            displaySuffix = " FP32",
            requiredSubCartridgeId = "rom_kokoro_fp32"
        )
        val kokoroV11 = kokoroV11Voices(
            "rom_kokoro_sherpa",
            BackendType.SHERPA_ONNX,
            requiredSubCartridgeId = "rom_kokoro_v11"
        )
        val matcha = matchaVoices("rom_piper", BackendType.SHERPA_ONNX, requiredSubCartridgeId = "rom_matcha_zh_en")

        val kittenVoices = listOf(
            VoiceDescriptor(
                id = "kitten_f1",
                displayName = "Kitten F1 (Expressive)",
                engine = BackendType.SHERPA_ONNX,
                cartridgeId = "rom_kitten_tts",
                sherpaFamily = SherpaModelFamily.KITTEN_TTS,
                gender = VoiceGender.FEMALE,
                languageCode = "en-US",
                speakerIndex = 0,
                nativeTags = listOf("female", "american", "energetic", "expressive", "kittentts")
            ),
            VoiceDescriptor(
                id = "kitten_m1",
                displayName = "Kitten M1 (Expressive)",
                engine = BackendType.SHERPA_ONNX,
                cartridgeId = "rom_kitten_tts",
                sherpaFamily = SherpaModelFamily.KITTEN_TTS,
                gender = VoiceGender.MALE,
                languageCode = "en-US",
                speakerIndex = 1,
                nativeTags = listOf("male", "american", "cool", "expressive", "kittentts")
            ),
            VoiceDescriptor(
                id = "kitten_f2",
                displayName = "Kitten F2 (Soft)",
                engine = BackendType.SHERPA_ONNX,
                cartridgeId = "rom_kitten_tts",
                sherpaFamily = SherpaModelFamily.KITTEN_TTS,
                gender = VoiceGender.FEMALE,
                languageCode = "en-US",
                speakerIndex = 2,
                nativeTags = listOf("female", "american", "soft", "gentle", "kittentts")
            ),
            VoiceDescriptor(
                id = "kitten_m2",
                displayName = "Kitten M2 (Calm)",
                engine = BackendType.SHERPA_ONNX,
                cartridgeId = "rom_kitten_tts",
                sherpaFamily = SherpaModelFamily.KITTEN_TTS,
                gender = VoiceGender.MALE,
                languageCode = "en-US",
                speakerIndex = 3,
                nativeTags = listOf("male", "american", "calm", "storyteller", "kittentts")
            ),
            VoiceDescriptor(
                id = "kitten_f3",
                displayName = "Kitten F3 (Bright)",
                engine = BackendType.SHERPA_ONNX,
                cartridgeId = "rom_kitten_tts",
                sherpaFamily = SherpaModelFamily.KITTEN_TTS,
                gender = VoiceGender.FEMALE,
                languageCode = "en-US",
                speakerIndex = 4,
                nativeTags = listOf("female", "american", "cute", "bright", "kittentts")
            ),
            VoiceDescriptor(
                id = "kitten_m3",
                displayName = "Kitten M3 (Deep)",
                engine = BackendType.SHERPA_ONNX,
                cartridgeId = "rom_kitten_tts",
                sherpaFamily = SherpaModelFamily.KITTEN_TTS,
                gender = VoiceGender.MALE,
                languageCode = "en-US",
                speakerIndex = 5,
                nativeTags = listOf("male", "american", "deep", "mature", "kittentts")
            ),
            VoiceDescriptor(
                id = "kitten_f4",
                displayName = "Kitten F4 (Storyteller)",
                engine = BackendType.SHERPA_ONNX,
                cartridgeId = "rom_kitten_tts",
                sherpaFamily = SherpaModelFamily.KITTEN_TTS,
                gender = VoiceGender.FEMALE,
                languageCode = "en-US",
                speakerIndex = 6,
                nativeTags = listOf("female", "american", "narrator", "storyteller", "kittentts")
            ),
            VoiceDescriptor(
                id = "kitten_m4",
                displayName = "Kitten M4 (Narrator)",
                engine = BackendType.SHERPA_ONNX,
                cartridgeId = "rom_kitten_tts",
                sherpaFamily = SherpaModelFamily.KITTEN_TTS,
                gender = VoiceGender.MALE,
                languageCode = "en-US",
                speakerIndex = 7,
                nativeTags = listOf("male", "american", "narrator", "storyteller", "kittentts")
            )
        )

        val kittenMiniVoices = (0..7).map { idx ->
            val isFemale = idx % 2 == 0
            val num = (idx / 2) + 1
            val tag = if (isFemale) "F$num" else "M$num"
            VoiceDescriptor(
                id = "kmini_${tag.lowercase()}",
                displayName = "Kitten Mini 80M // $tag",
                engine = BackendType.SHERPA_ONNX,
                cartridgeId = "rom_kitten_tts",
                sherpaFamily = SherpaModelFamily.KITTEN_TTS,
                gender = if (isFemale) VoiceGender.FEMALE else VoiceGender.MALE,
                languageCode = "en-US",
                speakerIndex = idx,
                nativeTags = listOf(if (isFemale) "female" else "male", "american", "kittenmini", "expressive"),
                requiredSubCartridgeId = "sub_kitten_mini"
            )
        }

        val sherpaSpecialized = listOf(
            VoiceDescriptor(
                id = "supertonic3_f1",
                displayName = "Supertonic 3 // Female Lead (F1)",
                engine = BackendType.SHERPA_ONNX,
                cartridgeId = "rom_piper",
                sherpaFamily = SherpaModelFamily.SUPERTONIC_3,
                gender = VoiceGender.FEMALE,
                languageCode = "en-US",
                speakerIndex = 0,
                nativeTags = listOf("female", "american", "cute", "energetic", "supertonic"),
                requiredSubCartridgeId = "rom_supertonic3"
            ),
            VoiceDescriptor(
                id = "supertonic3_m1",
                displayName = "Supertonic 3 // Male Lead (M1)",
                engine = BackendType.SHERPA_ONNX,
                cartridgeId = "rom_piper",
                sherpaFamily = SherpaModelFamily.SUPERTONIC_3,
                gender = VoiceGender.MALE,
                languageCode = "en-US",
                speakerIndex = 1,
                nativeTags = listOf("male", "american", "calm", "narrator", "supertonic"),
                requiredSubCartridgeId = "rom_supertonic3"
            ),
            VoiceDescriptor(
                id = "piper_libritts_lessac",
                displayName = "Piper VITS // Lessac High (US Base)",
                engine = BackendType.SHERPA_ONNX,
                cartridgeId = "rom_piper",
                sherpaFamily = SherpaModelFamily.PIPER,
                gender = VoiceGender.FEMALE,
                languageCode = "en-US",
                speakerIndex = 0,
                nativeTags = listOf("female", "american", "calm", "narrator", "audiobook")
            ),
            VoiceDescriptor(
                id = "piper_amy",
                displayName = "Piper VITS // Amy Medium (US Warm)",
                engine = BackendType.SHERPA_ONNX,
                cartridgeId = "rom_piper",
                sherpaFamily = SherpaModelFamily.PIPER,
                gender = VoiceGender.FEMALE,
                languageCode = "en-US",
                speakerIndex = 0,
                nativeTags = listOf("female", "american", "warm", "friendly", "piper"),
                requiredSubCartridgeId = "sub_piper_amy"
            ),
            VoiceDescriptor(
                id = "piper_ryan",
                displayName = "Piper VITS // Ryan High (US Deep Narrator)",
                engine = BackendType.SHERPA_ONNX,
                cartridgeId = "rom_piper",
                sherpaFamily = SherpaModelFamily.PIPER,
                gender = VoiceGender.MALE,
                languageCode = "en-US",
                speakerIndex = 0,
                nativeTags = listOf("male", "american", "deep", "narrator", "audiobook", "piper"),
                requiredSubCartridgeId = "sub_piper_ryan"
            ),
            VoiceDescriptor(
                id = "piper_cori",
                displayName = "Piper VITS // Cori High (UK Elegant)",
                engine = BackendType.SHERPA_ONNX,
                cartridgeId = "rom_piper",
                sherpaFamily = SherpaModelFamily.PIPER,
                gender = VoiceGender.FEMALE,
                languageCode = "en-GB",
                speakerIndex = 0,
                nativeTags = listOf("female", "british", "elegant", "narrator", "piper"),
                requiredSubCartridgeId = "sub_piper_cori"
            ),
            VoiceDescriptor(
                id = "piper_alba",
                displayName = "Piper VITS // Alba Medium (Scottish/UK)",
                engine = BackendType.SHERPA_ONNX,
                cartridgeId = "rom_piper",
                sherpaFamily = SherpaModelFamily.PIPER,
                gender = VoiceGender.FEMALE,
                languageCode = "en-GB",
                speakerIndex = 0,
                nativeTags = listOf("female", "british", "scottish", "soft", "piper"),
                requiredSubCartridgeId = "sub_piper_alba"
            ),
            VoiceDescriptor(
                id = "piper_glados",
                displayName = "Piper VITS // GLaDOS (Cybernetic AI)",
                engine = BackendType.SHERPA_ONNX,
                cartridgeId = "rom_piper",
                sherpaFamily = SherpaModelFamily.PIPER,
                gender = VoiceGender.FEMALE,
                languageCode = "en-US",
                speakerIndex = 0,
                nativeTags = listOf("female", "american", "cool", "android", "ai", "piper"),
                requiredSubCartridgeId = "sub_piper_glados"
            ),
            VoiceDescriptor(
                id = "piper_kristin",
                displayName = "Piper VITS // Kristin Medium (US Storyteller)",
                engine = BackendType.SHERPA_ONNX,
                cartridgeId = "rom_piper",
                sherpaFamily = SherpaModelFamily.PIPER,
                gender = VoiceGender.FEMALE,
                languageCode = "en-US",
                speakerIndex = 0,
                nativeTags = listOf("female", "american", "storyteller", "warm", "piper"),
                requiredSubCartridgeId = "sub_piper_kristin"
            ),
            VoiceDescriptor(
                id = "melo_en_us",
                displayName = "MeloTTS // English US",
                engine = BackendType.SHERPA_ONNX,
                cartridgeId = "rom_piper",
                sherpaFamily = SherpaModelFamily.MELO_TTS,
                gender = VoiceGender.FEMALE,
                languageCode = "en-US",
                speakerIndex = 0,
                nativeTags = listOf("female", "american", "melotts", "clear"),
                requiredSubCartridgeId = "sub_melo_en"
            ),
            VoiceDescriptor(
                id = "melo_en_br",
                displayName = "MeloTTS // English British",
                engine = BackendType.SHERPA_ONNX,
                cartridgeId = "rom_piper",
                sherpaFamily = SherpaModelFamily.MELO_TTS,
                gender = VoiceGender.FEMALE,
                languageCode = "en-GB",
                speakerIndex = 1,
                nativeTags = listOf("female", "british", "melotts", "elegant"),
                requiredSubCartridgeId = "sub_melo_en"
            ),
            VoiceDescriptor(
                id = "melo_en_in",
                displayName = "MeloTTS // English India",
                engine = BackendType.SHERPA_ONNX,
                cartridgeId = "rom_piper",
                sherpaFamily = SherpaModelFamily.MELO_TTS,
                gender = VoiceGender.FEMALE,
                languageCode = "en-IN",
                speakerIndex = 2,
                nativeTags = listOf("female", "indian", "melotts"),
                requiredSubCartridgeId = "sub_melo_en"
            ),
            VoiceDescriptor(
                id = "melo_en_au",
                displayName = "MeloTTS // English Australian",
                engine = BackendType.SHERPA_ONNX,
                cartridgeId = "rom_piper",
                sherpaFamily = SherpaModelFamily.MELO_TTS,
                gender = VoiceGender.FEMALE,
                languageCode = "en-AU",
                speakerIndex = 3,
                nativeTags = listOf("female", "australian", "melotts"),
                requiredSubCartridgeId = "sub_melo_en"
            ),
            VoiceDescriptor(
                id = "melo_en_default",
                displayName = "MeloTTS // English Neutral Studio",
                engine = BackendType.SHERPA_ONNX,
                cartridgeId = "rom_piper",
                sherpaFamily = SherpaModelFamily.MELO_TTS,
                gender = VoiceGender.FEMALE,
                languageCode = "en-US",
                speakerIndex = 4,
                nativeTags = listOf("female", "narrator", "melotts"),
                requiredSubCartridgeId = "sub_melo_en"
            ),
            VoiceDescriptor(
                id = "pocket_tts_zero_shot",
                displayName = "Pocket TTS INT8 // Base Zero-Shot",
                engine = BackendType.SHERPA_ONNX,
                cartridgeId = "rom_clone_hub",
                sherpaFamily = SherpaModelFamily.POCKET_TTS,
                gender = VoiceGender.ANDROGYNOUS,
                languageCode = "en-US",
                speakerIndex = 0,
                nativeTags = listOf("androgynous", "american", "clone", "warm"),
                requiredSubCartridgeId = "rom_pocket_tts"
            ),
            VoiceDescriptor(
                id = "pocket_fp32_zero_shot",
                displayName = "Pocket TTS FP32 // Studio Base",
                engine = BackendType.SHERPA_ONNX,
                cartridgeId = "rom_clone_hub",
                sherpaFamily = SherpaModelFamily.POCKET_TTS,
                gender = VoiceGender.ANDROGYNOUS,
                languageCode = "en-US",
                speakerIndex = 0,
                nativeTags = listOf("androgynous", "american", "clone", "studio"),
                requiredSubCartridgeId = "sub_pocket_fp32"
            ),
            VoiceDescriptor(
                id = "zipvoice_flow_en",
                displayName = "ZipVoice Distill // Flow Neural",
                engine = BackendType.SHERPA_ONNX,
                cartridgeId = "rom_clone_hub",
                sherpaFamily = SherpaModelFamily.ZIPVOICE,
                gender = VoiceGender.FEMALE,
                languageCode = "en-US",
                speakerIndex = 0,
                nativeTags = listOf("female", "american", "soft", "breathy", "clone"),
                requiredSubCartridgeId = "rom_zipvoice"
            ),
            VoiceDescriptor(
                id = "zipvoice_full_studio",
                displayName = "ZipVoice Full // Studio Flow Clone",
                engine = BackendType.SHERPA_ONNX,
                cartridgeId = "rom_clone_hub",
                sherpaFamily = SherpaModelFamily.ZIPVOICE,
                gender = VoiceGender.FEMALE,
                languageCode = "en-US",
                speakerIndex = 0,
                nativeTags = listOf("female", "american", "studio", "clone"),
                requiredSubCartridgeId = "sub_zipvoice_full"
            ),
            VoiceDescriptor(
                id = "chatterbox_turbo_default",
                displayName = "Chatterbox-Turbo 350M // Paralinguistic Studio",
                engine = BackendType.ONNX_NATIVE,
                cartridgeId = "rom_clone_hub",
                gender = VoiceGender.FEMALE,
                languageCode = "en-US",
                speakerIndex = 0,
                nativeTags = listOf("female", "american", "chatterbox", "clone", "laugh", "sigh", "expressive"),
                requiredSubCartridgeId = "sub_chatterbox_turbo"
            )
        )

        val heavyNativeVoices = listOf(
            // ROM-04: Qwen3-TTS 0.6B CustomVoice speakers + Base 3s Clone + 1.7B VoiceDesign
            VoiceDescriptor("qwen3_vivian", "Qwen3-TTS // Vivian (Warm Studio)", BackendType.QWEN3_NATIVE, "rom_qwen3_custom_06b", gender = VoiceGender.FEMALE, languageCode = "en-US", nativeTags = listOf("female", "qwen3", "warm", "multilingual")),
            VoiceDescriptor("qwen3_serena", "Qwen3-TTS // Serena (Soft Velvet)", BackendType.QWEN3_NATIVE, "rom_qwen3_custom_06b", gender = VoiceGender.FEMALE, languageCode = "en-US", nativeTags = listOf("female", "qwen3", "soft", "calm", "multilingual")),
            VoiceDescriptor("qwen3_ryan", "Qwen3-TTS // Ryan (Heroic Male)", BackendType.QWEN3_NATIVE, "rom_qwen3_custom_06b", gender = VoiceGender.MALE, languageCode = "en-US", nativeTags = listOf("male", "qwen3", "energetic", "multilingual")),
            VoiceDescriptor("qwen3_aiden", "Qwen3-TTS // Aiden (Clear US)", BackendType.QWEN3_NATIVE, "rom_qwen3_custom_06b", gender = VoiceGender.MALE, languageCode = "en-US", nativeTags = listOf("male", "qwen3", "american", "narrator")),
            VoiceDescriptor("qwen3_dylan", "Qwen3-TTS // Dylan (Beijing/ZH-EN)", BackendType.QWEN3_NATIVE, "rom_qwen3_custom_06b", gender = VoiceGender.MALE, languageCode = "zh-CN", nativeTags = listOf("male", "qwen3", "chinese", "multilingual")),
            VoiceDescriptor("qwen3_eric", "Qwen3-TTS // Eric (Sichuan/ZH-EN)", BackendType.QWEN3_NATIVE, "rom_qwen3_custom_06b", gender = VoiceGender.MALE, languageCode = "zh-CN", nativeTags = listOf("male", "qwen3", "chinese", "multilingual")),
            VoiceDescriptor("qwen3_ono_anna", "Qwen3-TTS // Ono Anna (Japanese)", BackendType.QWEN3_NATIVE, "rom_qwen3_custom_06b", gender = VoiceGender.FEMALE, languageCode = "ja-JP", nativeTags = listOf("female", "qwen3", "japanese", "cute")),
            VoiceDescriptor("qwen3_sohee", "Qwen3-TTS // Sohee (Korean/Bright)", BackendType.QWEN3_NATIVE, "rom_qwen3_custom_06b", gender = VoiceGender.FEMALE, languageCode = "ko-KR", nativeTags = listOf("female", "qwen3", "korean", "bright")),
            VoiceDescriptor("qwen3_uncle_fu", "Qwen3-TTS // Uncle Fu (Mature Baritone)", BackendType.QWEN3_NATIVE, "rom_qwen3_custom_06b", gender = VoiceGender.MALE, languageCode = "zh-CN", nativeTags = listOf("male", "qwen3", "deep", "mature")),
            VoiceDescriptor("qwen3_base_clone_default", "Qwen3-TTS 0.6B Base // Clone Engine", BackendType.QWEN3_NATIVE, "rom_qwen3_custom_06b", gender = VoiceGender.FEMALE, languageCode = "en-US", nativeTags = listOf("female", "qwen3", "clone", "multilingual"), requiredSubCartridgeId = "sub_qwen3_base_06b"),
            VoiceDescriptor("qwen3_design_custom", "Qwen3-TTS 1.7B // Prompt VoiceDesign", BackendType.QWEN3_NATIVE, "rom_qwen3_custom_06b", gender = VoiceGender.ANDROGYNOUS, languageCode = "en-US", nativeTags = listOf("androgynous", "qwen3", "voicedesign", "prompt"), requiredSubCartridgeId = "sub_qwen3_design_17b"),

            // ROM-05: NeuTTS-2E Emotional
            VoiceDescriptor("neutts_emily", "NeuTTS-2E // Emily (Emotional US)", BackendType.ONNX_NATIVE, "rom_neutts_2e", gender = VoiceGender.FEMALE, languageCode = "en-US", speakerIndex = 0, nativeTags = listOf("female", "american", "neutts", "emotional", "happy")),
            VoiceDescriptor("neutts_paul", "NeuTTS-2E // Paul (Emotional Deep)", BackendType.ONNX_NATIVE, "rom_neutts_2e", gender = VoiceGender.MALE, languageCode = "en-US", speakerIndex = 1, nativeTags = listOf("male", "american", "neutts", "emotional", "narrator")),
            VoiceDescriptor("neutts_sophie", "NeuTTS-2E // Sophie (Soft Emotional)", BackendType.ONNX_NATIVE, "rom_neutts_2e", gender = VoiceGender.FEMALE, languageCode = "en-US", speakerIndex = 2, nativeTags = listOf("female", "american", "neutts", "emotional", "soft")),
            VoiceDescriptor("neutts_steven", "NeuTTS-2E // Steven (Expressive Male)", BackendType.ONNX_NATIVE, "rom_neutts_2e", gender = VoiceGender.MALE, languageCode = "en-US", speakerIndex = 3, nativeTags = listOf("male", "american", "neutts", "emotional", "expressive")),

            // ROM-06: Soprano-80M 32kHz LLM
            VoiceDescriptor("soprano_studio_32k", "Soprano-80M // 32kHz Studio LLM", BackendType.ONNX_NATIVE, "rom_soprano_80m", gender = VoiceGender.FEMALE, languageCode = "en-US", speakerIndex = 0, nativeTags = listOf("female", "american", "soprano", "32khz", "studio"))
        )

        val proxyVoices = listOf(
            VoiceDescriptor(
                id = "proxy_openai_endpoint",
                displayName = "LAN OpenAI / Kokoro-FastAPI",
                engine = BackendType.EXTERNAL_PROXY,
                cartridgeId = "proxy_lan",
                gender = VoiceGender.ANDROGYNOUS,
                languageCode = "en-US",
                nativeTags = listOf("androgynous", "cloud", "proxy")
            )
        )

        return coreOsVoices + kokoroSherpa + kokoroFp32 + kokoroV11 + matcha + kittenVoices + kittenMiniVoices + sherpaSpecialized + heavyNativeVoices + proxyVoices
    }

    /**
     * 6 Intuitive Vibe Presets for Tab 2 (`> PURR ENGINE`) inspired by GachaSwipe's presets.
     */
    fun builtInProfiles(): List<PurrProfile> = listOf(
        PurrProfile(
            id = "gachaswipe_default",
            name = "GachaSwipe Default",
            badgeTag = "MIKA PICK",
            summary = "Balanced slang expansion, MIKASTOP breaths & clean GachaSwipe studio taper.",
            selectedCartridgeId = "rom_core_os",
            preferredBackend = BackendType.CORE_OS,
            primaryVoiceId = "core_os_auto",
            dialogueVoiceId = "af_bella",
            enableDualVoiceSplit = false,
            actionHandlingMode = ActionHandlingMode.NARRATE_SOFT,
            narrationVolumeMultiplier = 0.80f,
            narrationRateMultiplier = 0.94f,
            baseSpeed = 1.0f,
            basePitch = 1.0f,
            enableSlangAndHonorifics = true,
            enableMikaStopPauses = true,
            enableDsp = true,
            enableSymbolShield = true,
            mikaStopConfig = MikaStopConfig(450, 320, 280, 140, 200, sliceAtCommas = true, minWordsForCommaPause = 3),
            sentimentConfig = SentimentCurveConfig(
                enabled = true,
                toneOverride = ArchetypeTone.AUTO,
                stutterIntensity = 0.12f,
                vowelStretchIntensity = 0.10f
            ),
            dspConfig = DspConfig(
                warmthEqDb = 0.0f,
                presenceEqDb = 0.0f,
                reverbWetMix = 0.0f,
                effectPreset = AcousticEffectPreset.NONE,
                enableSentenceTaper = true,
                sentenceTaperMs = 120,
                normalizePeak = true
            )
        ),
        PurrProfile(
            id = "light_novel_reader",
            name = "Audiobook / Light Novel",
            badgeTag = "READER",
            summary = "Clean book prose, symbol shield, dual-voice split ready & steady breath pauses.",
            selectedCartridgeId = "rom_core_os",
            preferredBackend = BackendType.CORE_OS,
            primaryVoiceId = "core_os_auto",
            dialogueVoiceId = "af_bella",
            enableDualVoiceSplit = true,
            actionHandlingMode = ActionHandlingMode.NARRATE_SOFT,
            narrationVolumeMultiplier = 0.85f,
            narrationRateMultiplier = 0.96f,
            baseSpeed = 1.04f,
            basePitch = 1.0f,
            enableSlangAndHonorifics = false,
            enableMikaStopPauses = true,
            enableDsp = true,
            enableSymbolShield = true,
            mikaStopConfig = MikaStopConfig(480, 320, 300, 140, 200, sliceAtCommas = false, minWordsForCommaPause = 4, targetChunkChars = 220),
            sentimentConfig = SentimentCurveConfig(
                enabled = true,
                toneOverride = ArchetypeTone.AUTO,
                stutterIntensity = 0.0f,
                vowelStretchIntensity = 0.0f
            ),
            dspConfig = DspConfig(
                warmthEqDb = 0.0f,
                presenceEqDb = 0.0f,
                reverbWetMix = 0.0f,
                effectPreset = AcousticEffectPreset.NONE,
                enableSentenceTaper = true,
                sentenceTaperMs = 120,
                normalizePeak = true
            )
        ),
        PurrProfile(
            id = "tsundere_bratty",
            name = "Tsundere / Bratty",
            badgeTag = "SPICY",
            summary = "Punchy exclamations, flustered stutters & snappy comedic pacing.",
            selectedCartridgeId = "rom_core_os",
            preferredBackend = BackendType.CORE_OS,
            primaryVoiceId = "core_os_auto",
            dialogueVoiceId = "af_alloy",
            enableDualVoiceSplit = false,
            actionHandlingMode = ActionHandlingMode.STRIP_ACTIONS,
            baseSpeed = 1.05f,
            basePitch = 1.02f,
            enableSlangAndHonorifics = true,
            enableMikaStopPauses = true,
            enableDsp = true,
            enableSymbolShield = true,
            mikaStopConfig = MikaStopConfig(380, 260, 220, 110, 160, sliceAtCommas = true, minWordsForCommaPause = 3),
            sentimentConfig = SentimentCurveConfig(
                enabled = true,
                toneOverride = ArchetypeTone.TSUNDERE_LOVE,
                stutterIntensity = 0.20f,
                vowelStretchIntensity = 0.05f
            ),
            dspConfig = DspConfig(
                warmthEqDb = 0.0f,
                presenceEqDb = 0.0f,
                reverbWetMix = 0.0f,
                effectPreset = AcousticEffectPreset.NONE,
                enableSentenceTaper = true,
                sentenceTaperMs = 110,
                normalizePeak = true
            )
        ),
        PurrProfile(
            id = "yandere_obsessive",
            name = "Yandere / Obsessive",
            badgeTag = "DEVOTED",
            summary = "Slow sultry breath pauses, subtle vowel stretch & close whispered clarity.",
            selectedCartridgeId = "rom_core_os",
            preferredBackend = BackendType.CORE_OS,
            primaryVoiceId = "core_os_auto",
            dialogueVoiceId = "af_bella",
            enableDualVoiceSplit = false,
            actionHandlingMode = ActionHandlingMode.NARRATE_SOFT,
            narrationVolumeMultiplier = 0.72f,
            narrationRateMultiplier = 0.90f,
            baseSpeed = 0.93f,
            basePitch = 0.98f,
            enableSlangAndHonorifics = true,
            enableMikaStopPauses = true,
            enableDsp = true,
            enableSymbolShield = true,
            mikaStopConfig = MikaStopConfig(620, 440, 360, 200, 260, sliceAtCommas = true, minWordsForCommaPause = 3),
            sentimentConfig = SentimentCurveConfig(
                enabled = true,
                toneOverride = ArchetypeTone.YANDERE_LOVE,
                stutterIntensity = 0.05f,
                vowelStretchIntensity = 0.22f
            ),
            dspConfig = DspConfig(
                warmthEqDb = 0.0f,
                presenceEqDb = 0.0f,
                reverbWetMix = 0.0f,
                effectPreset = AcousticEffectPreset.NONE,
                enableSentenceTaper = true,
                sentenceTaperMs = 140,
                normalizePeak = true
            )
        ),
        PurrProfile(
            id = "shy_dandere",
            name = "Shy / Dandere",
            badgeTag = "SOFT",
            summary = "Gentle hesitant stutters, clean air EQ & tender soft-spoken pauses.",
            selectedCartridgeId = "rom_core_os",
            preferredBackend = BackendType.CORE_OS,
            primaryVoiceId = "core_os_auto",
            dialogueVoiceId = "af_nicole",
            enableDualVoiceSplit = false,
            actionHandlingMode = ActionHandlingMode.NARRATE_SOFT,
            baseSpeed = 0.98f,
            basePitch = 1.02f,
            enableSlangAndHonorifics = true,
            enableMikaStopPauses = true,
            enableDsp = true,
            enableSymbolShield = true,
            mikaStopConfig = MikaStopConfig(540, 380, 300, 170, 220, sliceAtCommas = true, minWordsForCommaPause = 3),
            sentimentConfig = SentimentCurveConfig(
                enabled = true,
                toneOverride = ArchetypeTone.SHY_LOVE,
                stutterIntensity = 0.25f,
                vowelStretchIntensity = 0.08f
            ),
            dspConfig = DspConfig(
                warmthEqDb = 0.0f,
                presenceEqDb = 0.0f,
                reverbWetMix = 0.0f,
                effectPreset = AcousticEffectPreset.NONE,
                enableSentenceTaper = true,
                sentenceTaperMs = 130,
                normalizePeak = true
            )
        ),
        PurrProfile(
            id = "clean_fast",
            name = "Clean & Fast",
            badgeTag = "SYSTEM",
            summary = "Zero drama filters or reverb — fast, crystal-clear everyday OS reading.",
            selectedCartridgeId = "rom_core_os",
            preferredBackend = BackendType.CORE_OS,
            primaryVoiceId = "core_os_auto",
            dialogueVoiceId = "af_kore",
            enableDualVoiceSplit = false,
            actionHandlingMode = ActionHandlingMode.STRIP_ACTIONS,
            baseSpeed = 1.10f,
            basePitch = 1.0f,
            enableSlangAndHonorifics = false,
            enableMikaStopPauses = false,
            enableDsp = false,
            enableSymbolShield = true,
            mikaStopConfig = MikaStopConfig(220, 150, 160, 80, 100, sliceAtCommas = false, minWordsForCommaPause = 4),
            sentimentConfig = SentimentCurveConfig(
                enabled = false,
                toneOverride = ArchetypeTone.NORMAL,
                stutterIntensity = 0.0f,
                vowelStretchIntensity = 0.0f
            ),
            dspConfig = DspConfig(
                warmthEqDb = 0f,
                presenceEqDb = 0f,
                reverbWetMix = 0.0f,
                effectPreset = AcousticEffectPreset.NONE,
                enableSentenceTaper = true,
                sentenceTaperMs = 90,
                normalizePeak = true
            )
        )
    )

    fun defaultCustomRules(): List<PhoneticRule> = listOf(
        PhoneticRule(
            id = "rule_mika_brand",
            pattern = "M\\.I\\.K\\.A\\.",
            replacement = "Mika",
            isRegex = true,
            ignoreCase = true,
            enabled = true,
            category = RuleCategory.CUSTOM
        ),
        PhoneticRule(
            id = "rule_purr_brand",
            pattern = "P\\.U\\.R\\.R\\.",
            replacement = "Purr",
            isRegex = true,
            ignoreCase = true,
            enabled = true,
            category = RuleCategory.CUSTOM
        )
    )

    private fun lunavoxQwen3Assets(
        hfSubDir: String,
        predictorSha: String,
        decoderSha: String,
        textEmbSha: String,
        textEmbSizeBytes: Long,
        codecShas: List<String>,
        codecEmbSizeBytes: Long = 8388736L,
        codec0SizeBytes: Long = 12583040L,
        includeBaseCloneEncoders: Boolean = false,
        includeDesignProj: Boolean = false
    ): List<BundleAssetFile> {
        val base = "https://huggingface.co/wkwong/Lunavox-Qwen3-TTS-GGUF/resolve/main/$hfSubDir"
        val out = mutableListOf(
            BundleAssetFile("qwen3_tts_predictor.q8_0.gguf", "$base/qwen3_tts_predictor.q8_0.gguf", predictorSha, 151124320L),
            BundleAssetFile("qwen3_tts_decoder.fp16.onnx", "$base/qwen3_tts_decoder.fp16.onnx", decoderSha, 230025129L),
            BundleAssetFile("tokenizer.json", "$base/tokenizer.json", "21cbba93fc2b3723c68db44ba5a8ff1a22a3f7bed34a5adbd5a165e17fff551c", 11424108L),
            BundleAssetFile("model_profile.json", "$base/model_profile.json", "", 3000L),
            BundleAssetFile("embeddings/text_embedding_projected.npy", "$base/embeddings/text_embedding_projected.npy", textEmbSha, textEmbSizeBytes)
        )
        codecShas.forEachIndexed { idx, sha ->
            val sz = if (idx == 0) codec0SizeBytes else codecEmbSizeBytes
            out += BundleAssetFile("embeddings/codec_embedding_$idx.npy", "$base/embeddings/codec_embedding_$idx.npy", sha, sz)
        }
        if (includeBaseCloneEncoders) {
            out += BundleAssetFile("qwen3_tts_codec_encoder.fp16.onnx", "$base/qwen3_tts_codec_encoder.fp16.onnx", "4c31acf59a98fbb304754dbc9cf159de46cd33a5e1b0cf44770c1eb90d779825", 113983925L)
            out += BundleAssetFile("qwen3_tts_speaker_encoder.fp16.onnx", "$base/qwen3_tts_speaker_encoder.fp16.onnx", "782551fba10c14fc1abfc9100cf93e678a062cc2c7c063ec6c5fd6bb0088e439", 17991711L)
        }
        if (includeDesignProj) {
            out += BundleAssetFile("embeddings/proj_weight.npy", "$base/embeddings/proj_weight.npy", "e57c0515ff31947cd90ff730c9253ec533825788b4572fc7e49980107b42f188", 8388736L)
            out += BundleAssetFile("embeddings/proj_bias.npy", "$base/embeddings/proj_bias.npy", "", 4224L)
        }
        return out
    }

    /**
     * ✨ CLEAN 8-BAY HUB CARTRIDGE RACK (v1.7.0) ✨
     * ROM-01..ROM-08 organize every TTS engine cleanly by family, with accelerators,
     * FP32/v1.1 upgrades, cloning engines, and extra voice packs nested inside their Hub bays.
     * Plus 5 Built-In Tool Cartridges (`TOOL-01`..`TOOL-05`) under `CartridgeCategory.TOOLS`.
     */
    fun modelCatalog(): List<ModelBundleSpec> = listOf(
        // =========================================================================
        // 8 MAIN TTS HUB CARTRIDGES (ROM-01 .. ROM-08)
        // =========================================================================
        ModelBundleSpec(
            id = "rom_core_os",
            romCode = "ROM-01",
            badge = "SYSTEM_TTS",
            accentHex = 0xFF00E5FF,
            name = "ANDROID_OS_SPEECH",
            subtitle = "Built-in Device Speech Engine • 0MB Download • Eject to Disable",
            description = "Bridges PURR middleware & 24kHz DSP to your phone's built-in Android TTS voices. Eject this cartridge anytime to completely disable system voices.",
            backend = BackendType.CORE_OS,
            downloadUrl = "android://system_tts",
            fileName = "system_tts.rom",
            expectedSha256 = "0000000000000000000000000000000000000000000000000000000000000000",
            approximateSizeMb = 0,
            isArchiveTarBz2 = false,
            hardwareAccelNote = "Android OS Binder Bridge • Ejectable",
            isBuiltInOsCartridge = true,
            unlocksSummary = "UNLOCKS: Your phone's built-in Android voices (0 MB download). Eject to completely disable system voices.",
            category = CartridgeCategory.TTS
        ),
        ModelBundleSpec(
            id = "rom_kokoro_sherpa",
            romCode = "ROM-02",
            badge = "KOKORO_HUB",
            accentHex = 0xFF00FF41,
            name = "KOKORO_NEURAL_HUB",
            subtitle = "Unified Kokoro Bay • Base 54v (126MB) + NPU/GPU, FP32 & v1.1 (103v)",
            description = "The complete Kokoro family in one clean hub! Install the 126MB Base 54-voice cartridge, or pick the unquantized FP32 Studio weights (334MB), Kokoro v1.1 103-speaker EN+ZH pack (140MB), or Hexagon NPU / Adreno GPU accelerators below.",
            backend = BackendType.SHERPA_ONNX,
            sherpaFamily = SherpaModelFamily.KOKORO,
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-multi-lang-v1_0.tar.bz2",
            fileName = "kokoro-int8-multi-lang-v1_0.tar.bz2",
            expectedSha256 = "72c347230107ef8a31098ec4d468a605244beef5f1ed00db97efa13518800653",
            approximateSizeMb = 126,
            isArchiveTarBz2 = true,
            hardwareAccelNote = "ARM64 NEON Base + NPU/GPU/FP32/v1.1 Sub-Cartridges",
            unlocksSummary = "UNLOCKS: All 54 Kokoro v1.0 Base voices + hosts Kokoro FP32, v1.1 (103v), NPU & GPU chips.",
            category = CartridgeCategory.TTS
        ),
        ModelBundleSpec(
            id = "rom_clone_hub",
            romCode = "ROM-03",
            badge = "ZERO_SHOT_CLONE",
            accentHex = 0xFFB533FF,
            name = "VOICE_CLONE_HUB",
            subtitle = "Zero-Shot Voice Cloning Bay • Pocket, ZipVoice & Chatterbox-Turbo (350M)",
            description = "All zero-shot voice cloning engines in one bay! Download any engine chip below — Pocket TTS (fast 5s), Pocket FP32 Studio, ZipVoice (flow-matching), or flagship Chatterbox-Turbo (with native [laugh]/[sigh]/[gasp] tags).",
            backend = BackendType.SHERPA_ONNX,
            downloadUrl = "builtin://hub/voice_clone",
            fileName = "clone_hub.rom",
            expectedSha256 = "0000000000000000000000000000000000000000000000000000000000000000",
            approximateSizeMb = 0,
            isArchiveTarBz2 = false,
            isHubContainer = true,
            hardwareAccelNote = "Container Hub • Auto-Slots When Any Clone Engine Installs",
            unlocksSummary = "HOSTS: Pocket INT8/FP32, ZipVoice INT8/Full & Chatterbox-Turbo 350M cloning engines.",
            category = CartridgeCategory.TTS
        ),
        ModelBundleSpec(
            id = "rom_qwen3_custom_06b",
            romCode = "ROM-04",
            badge = "QWEN3_FLAGSHIP",
            accentHex = 0xFFFFD700,
            name = "QWEN3_TTS_STUDIO",
            subtitle = "Alibaba Qwen3-TTS 0.6B CustomVoice (GGUF+ONNX) + Base Clone & 1.7B Design",
            description = "Exclusive local Qwen3-TTS powered by llama.cpp + ONNX Runtime JNI! Base cartridge unlocks 9 multilingual studio speakers (Vivian, Serena, Ryan, Aiden, Ono Anna, Sohee & more). Add 0.6B Base for 3s multilingual cloning or 1.7B VoiceDesign for prompt-created voices! Needs 8GB+ RAM.",
            backend = BackendType.QWEN3_NATIVE,
            downloadUrl = "https://huggingface.co/wkwong/Lunavox-Qwen3-TTS-GGUF/resolve/main/custom_small/qwen3_tts_talker.q5_k.gguf",
            fileName = "qwen3_tts_talker.q5_k.gguf",
            expectedSha256 = "b95606a3dd0c659f975af6bd6cc18c9580367da16bacbf95742cd0712e49f444",
            approximateSizeMb = 1053,
            isArchiveTarBz2 = false,
            hardwareAccelNote = "llama.cpp b8470 GGUF + ORT 1.31.1 ONNX JNI • 8GB+ RAM",
            unlocksSummary = "UNLOCKS: Qwen3-TTS 0.6B (9 Multilingual Studio Voices) + hosts 0.6B Base Cloner & 1.7B VoiceDesign.",
            extraAssets = lunavoxQwen3Assets(
                hfSubDir = "custom_small",
                predictorSha = "87d8db0655badec054959b6bdd75ac093d241fd6cd40aee487fe5090129428a8",
                decoderSha = "82d043dcff8c8bfaafd2c05bd5c85e922e2d50a9bdc4c7d45eea895edced2137",
                textEmbSha = "dd26819ba74560d424190a93dfe18e846f2c144f408d27fcc93f4c11a5ea8451",
                textEmbSizeBytes = 311165056L,
                codecShas = listOf(
                    "65a94ed3c86cb17e40504a581c746b274a4e56ba200c6f99f383a1bb6274cfbd",
                    "9b1bd89a06be7fc6eb07e6b03d6d3fdc4762b5d0e0db124d74c608c0638082e3",
                    "f0d6e187dec6f037980e38911b27016c0531bb34718bb96ccb954366fc495d21",
                    "5ae15feb83dc7f97e6f939fe98c9dda91c08749c2a69384d71a8414166792825",
                    "2d740ecfaa6591dcf014570a322192dd022f169bcca80849c41801ff496a866a",
                    "3b466f7d3213b7a8b29381ee47338a9ac4de30460ac3d2defdaceaa0bab657e2",
                    "494fde5327423a7462d5e3063dcd8f2ad87de411fe5093c587cf8e56b3ae53df",
                    "e6d6780db3685caee084edbce3b2517e5fa52b6a077890dff9c14b6638bfb564",
                    "4d10c51b112e7188062a712e0d664a22dd448cea52ed102e094943c871ec3577",
                    "3bc9ba51d40d91be04f478d9330363af3d1ac1218144e7bf0c8b920bd4d1832a",
                    "41add7203fe62f322fa66ad353d5a91f265413a5c711681769d5979e526ced51",
                    "0e6d1bd968d39d53064bf430a0117cd83d452ee3ecf78dbe2648ad4b783d9091",
                    "c9a1cb9ed2749c9f94fc29a939afdbb8ec341ba3f734c10b507f82a441a7c20e",
                    "4cc774bda9a3b868461a36d08cd92370b71e162589598d1796b66fd1bb0520d1",
                    "bf0454c373ff115501d1642675125146e92d91703442ffdf87a35a7b4fe94919",
                    "57cb0ce39de77611a7be8f3932debe3a2fa9eba9189e7b3fac890c482ce9a8ba"
                )
            ),
            category = CartridgeCategory.TTS
        ),
        ModelBundleSpec(
            id = "rom_neutts_2e",
            romCode = "ROM-05",
            badge = "EMOTION_LLM",
            accentHex = 0xFFFF107A,
            name = "NEUTTS_2E_EMOTIONAL",
            subtitle = "Neuphonic NeuTTS-2E (Qwen3-236M INT8 + NeuCodec) • 7 Native Emotions (~654MB)",
            description = "On-device emotional Qwen3-236M LLM speech engine paired with NeuCodec INT8. Automatically maps PURR sentiment (happy, sad, angry, surprised, calm) into native emotion control tokens for Emily, Paul, Sophie & Steven!",
            backend = BackendType.ONNX_NATIVE,
            downloadUrl = "https://huggingface.co/Danny-Dasilva/neutts-2e-onnx/resolve/main/model_int8.onnx",
            fileName = "model_int8.onnx",
            expectedSha256 = "ead89cf159e98db93605f5ebc1985993e30c8500ee18393b823ca06a57e44cc0",
            approximateSizeMb = 654,
            isArchiveTarBz2 = false,
            hardwareAccelNote = "ONNX Runtime INT8 • 7 Native Emotions + NeuCodec",
            unlocksSummary = "UNLOCKS: Emily, Paul, Sophie & Steven with automatic PURR sentiment-driven emotion tokens!",
            extraAssets = listOf(
                BundleAssetFile("neucodec_decoder_int8.onnx", "https://huggingface.co/aoiandroid/neuphonic-neucodec-onnx-decoder-int8-mirror/resolve/main/model.onnx", "3ddd9e56396e6029e0e948ac0255c89c803f981f23dcf4c154f50820bd74a6b3", 312292102L),
                BundleAssetFile("tokenizer.json", "https://huggingface.co/Danny-Dasilva/neutts-2e-onnx/resolve/main/tokenizer.json", "4124ffb80d856316c5f0bcad7c51742ffad141db65f3f1e8fcc2b34d4d498f34", 24063947L)
            ),
            category = CartridgeCategory.TTS
        ),
        ModelBundleSpec(
            id = "rom_soprano_80m",
            romCode = "ROM-06",
            badge = "32KHZ_STUDIO_LLM",
            accentHex = 0xFF38BDF8,
            name = "SOPRANO_32KHZ_LLM",
            subtitle = "ekwek/Soprano-80M ONNX • Ultra-Fast 32kHz Autoregressive Vocos (~362MB)",
            description = "Compact 80M-parameter Qwen3-architecture neural TTS emitting ultra-crisp 32kHz Vocos studio waveforms directly on ONNX Runtime CPU — crisp highs above 24kHz!",
            backend = BackendType.ONNX_NATIVE,
            downloadUrl = "https://huggingface.co/KevinAHM/soprano-onnx/resolve/main/onnx/soprano_backbone_kv.onnx",
            fileName = "soprano_backbone_kv.onnx",
            expectedSha256 = "46989d2bf5615d26ed6adea563d53498ee79ce1ad33731f26ba97e5299917681",
            approximateSizeMb = 362,
            isArchiveTarBz2 = false,
            hardwareAccelNote = "ONNX Runtime KV-Cache + 32kHz Vocos Decoder",
            unlocksSummary = "UNLOCKS: Soprano-80M 32kHz studio narrator voice with ultra-low latency.",
            extraAssets = listOf(
                BundleAssetFile("soprano_decoder.onnx", "https://huggingface.co/KevinAHM/soprano-onnx/resolve/main/onnx/soprano_decoder.onnx", "41c885da9fd3d038320fab51500fb7f53a34ef5f9e402407d86de3aefb3e3e5e", 292183L),
                BundleAssetFile("soprano_decoder.onnx.data", "https://huggingface.co/KevinAHM/soprano-onnx/resolve/main/onnx/soprano_decoder.onnx.data", "b5ef3e4fe11c5eddf1b78afa10a0cecba4b8710b84b78c57b9661d7a56005a45", 57937920L),
                BundleAssetFile("tokenizer.json", "https://huggingface.co/KevinAHM/soprano-onnx/resolve/main/tokenizer.json", "", 1630675L)
            ),
            category = CartridgeCategory.TTS
        ),
        ModelBundleSpec(
            id = "rom_kitten_tts",
            romCode = "ROM-07",
            badge = "KITTEN_HUB",
            accentHex = 0xFFFF9E00,
            name = "KITTEN_TTS_HUB",
            subtitle = "KittenTTS Nano (30MB Base) + Mini 80M Upgrade (64MB)",
            description = "Compact ultra-expressive 8-voice English hub. Slam the 30MB Nano INT8 base or upgrade to the 80M KittenTTS Mini v0.8 sub-cartridge below for richer tone!",
            backend = BackendType.SHERPA_ONNX,
            sherpaFamily = SherpaModelFamily.KITTEN_TTS,
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kitten-nano-en-v0_8-int8.tar.bz2",
            fileName = "kitten-nano-en-v0_8-int8.tar.bz2",
            expectedSha256 = "6fa5be852612ce761094ba74ee6123b4fc4acfefa79bf64dc63acae4a83af2fd",
            approximateSizeMb = 30,
            isArchiveTarBz2 = true,
            hardwareAccelNote = "KittenTTS v0.8 • Nano 30MB + Mini 80M Option",
            unlocksSummary = "UNLOCKS: 8 KittenTTS Nano voices (F1-F4 & M1-M4) + optional Mini 80M upgrade below.",
            category = CartridgeCategory.TTS
        ),
        ModelBundleSpec(
            id = "rom_piper",
            romCode = "ROM-08",
            badge = "NARRATOR_HUB",
            accentHex = 0xFF9146FF,
            name = "NARRATOR_VITS_FLOW_HUB",
            subtitle = "Piper VITS (7 Voices), MeloTTS EN (5 Accents), Matcha Flow & Supertonic 3",
            description = "All classic & flow-matching audiobook narrators in one bay! Includes Piper Lessac US Base plus 9 standalone voice & engine packs below (Amy, Ryan, Cori, Alba, GLaDOS, Kristin, MeloTTS English, Supertonic 3, Matcha-TTS).",
            backend = BackendType.SHERPA_ONNX,
            sherpaFamily = SherpaModelFamily.PIPER,
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-lessac-high.tar.bz2",
            fileName = "vits-piper-en_US-lessac-high.tar.bz2",
            expectedSha256 = "8619d204c7005866fe4f420181dfa79622af6a6222389f0b0818d2af31e0db0e",
            approximateSizeMb = 110,
            isArchiveTarBz2 = true,
            hardwareAccelNote = "Piper VITS + MeloTTS + Matcha + Supertonic Sub-Chips",
            unlocksSummary = "UNLOCKS: Piper Lessac US Base + 9 optional Narrator, MeloTTS & Flow Sub-Cartridges below.",
            category = CartridgeCategory.TTS
        ),

        // =========================================================================
        // SUB-CARTRIDGES: ROM-02 (KOKORO_NEURAL_HUB)
        // =========================================================================
        ModelBundleSpec(
            id = "rom_kokoro_npu_g2p",
            romCode = "SUB-02A",
            badge = "HEXAGON_NPU",
            accentHex = 0xFF00FF9D,
            name = "HEXAGON_NPU_G2P_CHIP",
            subtitle = "Snapdragon Hexagon NPU / QAIRT Phoneme Co-Processor (+49 MB)",
            description = "Optional add-on chip for ROM-02. Offloads English Grapheme-to-Phoneme (G2P) conversion onto the Snapdragon Hexagon NPU.",
            backend = BackendType.LITERT_NPU_GPU,
            downloadUrl = "https://huggingface.co/litert-community/Kokoro-G2P-en-US/resolve/main/dp_g2p_litert.tflite",
            fileName = "dp_g2p_litert.tflite",
            expectedSha256 = "f8d6bbd16197b2468f53273de13f971a0e1e8657709d7f4285f2cd1c31f26620",
            approximateSizeMb = 49,
            isArchiveTarBz2 = false,
            hardwareAccelNote = "Snapdragon Hexagon NPU / QNN HTP Delegate",
            unlocksSummary = "ACTIVATES: [NPU ACTIVE] hardware phoneme acceleration for Kokoro voices.",
            isSubCartridge = true,
            parentCartridgeId = "rom_kokoro_sherpa",
            category = CartridgeCategory.TTS
        ),
        ModelBundleSpec(
            id = "rom_kokoro_litert",
            romCode = "SUB-02B",
            badge = "ADRENO_GPU",
            accentHex = 0xFFFF9100,
            name = "ADRENO_GPU_FP16_CHIP",
            subtitle = "LiteRT-LM Adreno GPU FP16 Neural Accelerator (+281 MB)",
            description = "Optional add-on chip for ROM-02. Mounts Google AI Edge GPU FP16 weights with persistent OpenCL shader cache & 5-minute VRAM auto-eviction.",
            backend = BackendType.LITERT_NPU_GPU,
            downloadUrl = "https://huggingface.co/litert-community/Kokoro-82M/resolve/main/kokoro_int8_gpu_fp16.litertlm",
            fileName = "kokoro_int8_gpu_fp16.litertlm",
            expectedSha256 = "1ef178b127ec48c737daf17eabb4cea5f6b3ffbc510aced270fe5c267bbeac52",
            approximateSizeMb = 281,
            isArchiveTarBz2 = false,
            hardwareAccelNote = "Adreno GPU FP16 + Persistent OpenCL Cache",
            unlocksSummary = "ACTIVATES: [GPU FP16] hardware synthesis + 5m VRAM cache for ROM-02.",
            isSubCartridge = true,
            parentCartridgeId = "rom_kokoro_sherpa",
            category = CartridgeCategory.TTS
        ),
        ModelBundleSpec(
            id = "rom_kokoro_fp32",
            romCode = "SUB-02C",
            badge = "FP32_STUDIO",
            accentHex = 0xFFFFD700,
            name = "KOKORO_82M_FP32",
            subtitle = "Studio-Precision Unquantized Weights • 54 FP32 Voices • 8GB+ RAM (+334 MB)",
            description = "Unquantized full-precision Kokoro-82M weights with richer breath, sibilance & emotional depth. Works standalone under ROM-02 without needing INT8 Base!",
            backend = BackendType.SHERPA_ONNX,
            sherpaFamily = SherpaModelFamily.KOKORO,
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-multi-lang-v1_0.tar.bz2",
            fileName = "kokoro-multi-lang-v1_0.tar.bz2",
            expectedSha256 = "60703707bc05ccd48d60e1eb723f38b07d6e474795a0b6a588d7220400e0f7a4",
            approximateSizeMb = 334,
            isArchiveTarBz2 = true,
            hardwareAccelNote = "FP32 CPU • 8GB+ RAM Recommended • 5-min Idle Eviction",
            unlocksSummary = "ADDS VOICES: 54 full-precision FP32 Kokoro voices (fp32_af_heart, etc.) to ROM-02.",
            isSubCartridge = true,
            parentCartridgeId = "rom_kokoro_sherpa",
            category = CartridgeCategory.TTS
        ),
        ModelBundleSpec(
            id = "rom_kokoro_v11",
            romCode = "SUB-02D",
            badge = "103_VOICES_ZH_EN",
            accentHex = 0xFF00E5FF,
            name = "KOKORO_V11_MULTILING",
            subtitle = "Kokoro v1.1-zh INT8 • 103 Speakers • Chinese + English (+140 MB)",
            description = "103 Kokoro v1.1 speakers: 100 Chinese voices (zf_*, zm_*) plus af_maple, af_sol and bf_vale in English. Works standalone under ROM-02!",
            backend = BackendType.SHERPA_ONNX,
            sherpaFamily = SherpaModelFamily.KOKORO,
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-multi-lang-v1_1.tar.bz2",
            fileName = "kokoro-int8-multi-lang-v1_1.tar.bz2",
            expectedSha256 = "259c9c8bc906d6078e4e0570421885dabb68b539c2f970fcbb890a58eeaf3e9c",
            approximateSizeMb = 140,
            isArchiveTarBz2 = true,
            hardwareAccelNote = "INT8 CPU • ARM64 NEON • Dual Lexicon (EN + ZH)",
            unlocksSummary = "ADDS VOICES: 103 Kokoro v1.1 bilingual speakers (v11_*) to ROM-02.",
            isSubCartridge = true,
            parentCartridgeId = "rom_kokoro_sherpa",
            category = CartridgeCategory.TTS
        ),

        // =========================================================================
        // SUB-CARTRIDGES: ROM-03 (VOICE_CLONE_HUB)
        // =========================================================================
        ModelBundleSpec(
            id = "rom_pocket_tts",
            romCode = "SUB-03A",
            badge = "5S_CLONE_INT8",
            accentHex = 0xFFB533FF,
            name = "POCKET_TTS_INT8",
            subtitle = "Fast 5-Second Zero-Shot CPU Cloner (+94 MB)",
            description = "Compact 5-second zero-shot cloning model via sherpa-onnx. Fast on every phone.",
            backend = BackendType.SHERPA_ONNX,
            sherpaFamily = SherpaModelFamily.POCKET_TTS,
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/sherpa-onnx-pocket-tts-int8-2026-01-26.tar.bz2",
            fileName = "sherpa-onnx-pocket-tts-int8-2026-01-26.tar.bz2",
            expectedSha256 = "2f3b88823cbbb9bf0b2477ec8ae7b3fec417b3a87b6bb5f256dba66f2ad967cb",
            approximateSizeMb = 94,
            isArchiveTarBz2 = true,
            hardwareAccelNote = "Pocket TTS INT8 Zero-Shot Speaker Encoder",
            unlocksSummary = "ENABLES: Pocket INT8 5s cloning in Clone Studio + base voice under ROM-03.",
            isSubCartridge = true,
            parentCartridgeId = "rom_clone_hub",
            category = CartridgeCategory.TTS
        ),
        ModelBundleSpec(
            id = "sub_pocket_fp32",
            romCode = "SUB-03B",
            badge = "5S_CLONE_FP32",
            accentHex = 0xFFD946EF,
            name = "POCKET_TTS_FP32_STUDIO",
            subtitle = "Unquantized Pocket TTS Studio Cloner (+160 MB)",
            description = "Full-precision Pocket TTS zero-shot cloner weights with higher timbre fidelity.",
            backend = BackendType.SHERPA_ONNX,
            sherpaFamily = SherpaModelFamily.POCKET_TTS,
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/sherpa-onnx-pocket-tts-2026-01-26.tar.bz2",
            fileName = "sherpa-onnx-pocket-tts-2026-01-26.tar.bz2",
            expectedSha256 = "61422a478ef09d9b2c067261fb822e11786e0c86842c76d8633b7069968be7b5",
            approximateSizeMb = 160,
            isArchiveTarBz2 = true,
            hardwareAccelNote = "Pocket TTS FP32 Unquantized Studio Weights",
            unlocksSummary = "ENABLES: Pocket FP32 Studio cloning in Clone Studio + base voice under ROM-03.",
            isSubCartridge = true,
            parentCartridgeId = "rom_clone_hub",
            category = CartridgeCategory.TTS
        ),
        ModelBundleSpec(
            id = "rom_zipvoice",
            romCode = "SUB-03C",
            badge = "FLOW_CLONE_INT8",
            accentHex = 0xFFFF107A,
            name = "ZIPVOICE_DISTILL_INT8",
            subtitle = "Distilled Flow-Matching Zero-Shot Clone (+104 MB)",
            description = "Compact distilled flow-matching cloner with expressive prosody transfer.",
            backend = BackendType.SHERPA_ONNX,
            sherpaFamily = SherpaModelFamily.ZIPVOICE,
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/sherpa-onnx-zipvoice-distill-int8-zh-en-emilia.tar.bz2",
            fileName = "sherpa-onnx-zipvoice-distill-int8-zh-en-emilia.tar.bz2",
            expectedSha256 = "77219c8b40f4ee8d73a7f902305ff6c1128ef9b54461c41b4ca6ed890b6c2803",
            approximateSizeMb = 104,
            isArchiveTarBz2 = true,
            hardwareAccelNote = "ONNX Runtime INT8 + Vocos",
            unlocksSummary = "ENABLES: ZipVoice INT8 cloning in Clone Studio + ZipVoice base voice.",
            isSubCartridge = true,
            parentCartridgeId = "rom_clone_hub",
            category = CartridgeCategory.TTS
        ),
        ModelBundleSpec(
            id = "sub_zipvoice_full",
            romCode = "SUB-03D",
            badge = "FLOW_CLONE_FULL",
            accentHex = 0xFFFF4D6D,
            name = "ZIPVOICE_FULL_STUDIO",
            subtitle = "Non-Distilled High-Steps Flow-Matching Clone • 8GB+ RAM (+605 MB)",
            description = "Heavyweight un-distilled ZipVoice Emilia checkpoint delivering richer acoustic texture during 12s reference cloning.",
            backend = BackendType.SHERPA_ONNX,
            sherpaFamily = SherpaModelFamily.ZIPVOICE,
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/sherpa-onnx-zipvoice-zh-en-emilia.tar.bz2",
            fileName = "sherpa-onnx-zipvoice-zh-en-emilia.tar.bz2",
            expectedSha256 = "098feeb0566875751eb8776b87060b9889b32b17d3649504e4514ce2b6a85100",
            approximateSizeMb = 605,
            isArchiveTarBz2 = true,
            hardwareAccelNote = "Full Non-Distilled ZipVoice Studio + Vocos",
            unlocksSummary = "ENABLES: Full ZipVoice Studio cloning in Clone Studio + studio base voice.",
            isSubCartridge = true,
            parentCartridgeId = "rom_clone_hub",
            category = CartridgeCategory.TTS
        ),
        ModelBundleSpec(
            id = "sub_chatterbox_turbo",
            romCode = "SUB-03E",
            badge = "CHATTERBOX_350M",
            accentHex = 0xFFFF6B35,
            name = "CHATTERBOX_TURBO_350M",
            subtitle = "Resemble AI Chatterbox-Turbo Q4 ONNX • Zero-Shot + [laugh]/[sigh]/[gasp] (~691 MB)",
            description = "350M LLM + 1-step distilled flow decoder on ONNX Runtime! Clones ANY voice from Clone Studio AND natively speaks paralinguistic reaction tags like [laugh], [chuckle], [sigh], [gasp], [cough], [whispering] — PURR maps *actions* automatically!",
            backend = BackendType.ONNX_NATIVE,
            downloadUrl = "https://huggingface.co/ResembleAI/chatterbox-turbo-ONNX/resolve/main/onnx/language_model_q4.onnx_data",
            fileName = "language_model_q4.onnx_data",
            expectedSha256 = "2c029dc0acf48752473d8c74c72b5ceaaad76b9886fe106eaf2022142d5b5d5e",
            approximateSizeMb = 691,
            isArchiveTarBz2 = false,
            hardwareAccelNote = "ONNX Runtime Q4 4-Graph + Native Paralinguistics • MIT",
            unlocksSummary = "ENABLES: Chatterbox-Turbo 350M zero-shot cloning & native [laugh]/[sigh]/[gasp] expressions!",
            isSubCartridge = true,
            parentCartridgeId = "rom_clone_hub",
            extraAssets = listOf(
                BundleAssetFile("language_model_q4.onnx", "https://huggingface.co/ResembleAI/chatterbox-turbo-ONNX/resolve/main/onnx/language_model_q4.onnx", "b39d03d3f8b943b9e60c6fce3fb41191dbc1df4589f913291db1e214eef669b1", 274572L),
                BundleAssetFile("embed_tokens_q4.onnx", "https://huggingface.co/ResembleAI/chatterbox-turbo-ONNX/resolve/main/onnx/embed_tokens_q4.onnx", "fd6ba1d22902e8f539d3dd6d7c1c44b98ebb4c84ebbb5e47fcb826ddcf667561", 2844L),
                BundleAssetFile("embed_tokens_q4.onnx_data", "https://huggingface.co/ResembleAI/chatterbox-turbo-ONNX/resolve/main/onnx/embed_tokens_q4.onnx_data", "f54a51e234b509b64c3a03bb79e1149fba7e2eba6c2d9c222f18883379e1f5d8", 37286384L),
                BundleAssetFile("speech_encoder_q4.onnx", "https://huggingface.co/ResembleAI/chatterbox-turbo-ONNX/resolve/main/onnx/speech_encoder_q4.onnx", "37956c20b67bed85a0da4bc83509d67b5969a1b257d1c546516a5236a17ad71e", 1200346L),
                BundleAssetFile("speech_encoder_q4.onnx_data", "https://huggingface.co/ResembleAI/chatterbox-turbo-ONNX/resolve/main/onnx/speech_encoder_q4.onnx_data", "58956db217c6443e49c91bdd54d7cf76b4a243f225c748b7bf746459fc27bc7d", 229560112L),
                BundleAssetFile("conditional_decoder_q4.onnx", "https://huggingface.co/ResembleAI/chatterbox-turbo-ONNX/resolve/main/onnx/conditional_decoder_q4.onnx", "dccb7a6cea3472dc7f7d070eeb70ade18e6327fb4ec61a3d62cf211bfed90ea2", 2179022L),
                BundleAssetFile("conditional_decoder_q4.onnx_data", "https://huggingface.co/ResembleAI/chatterbox-turbo-ONNX/resolve/main/onnx/conditional_decoder_q4.onnx_data", "b5c5317e0b79a1a19dd3d5e2b2091ea06b15716716ab801a54eaeb906c6971ec", 246397384L),
                BundleAssetFile("tokenizer.json", "https://huggingface.co/ResembleAI/chatterbox-turbo-ONNX/resolve/main/tokenizer.json", "", 3562272L),
                BundleAssetFile("emily_neutral.wav", "https://huggingface.co/Danny-Dasilva/neutts-2e-onnx/resolve/main/samples/emily_neutral.wav", "af84bfde96bcaddd9d303e0213d84be8c920a981561b65eea07c68d951f6e4d1", 267884L)
            ),
            category = CartridgeCategory.TTS
        ),

        // =========================================================================
        // SUB-CARTRIDGES: ROM-04 (QWEN3_TTS_STUDIO)
        // =========================================================================
        ModelBundleSpec(
            id = "sub_qwen3_base_06b",
            romCode = "SUB-04A",
            badge = "3S_MULTILANG_CLONE",
            accentHex = 0xFFFFB703,
            name = "QWEN3_06B_BASE_CLONER",
            subtitle = "Qwen3-TTS 0.6B Base • 3s Multilingual Zero-Shot Cloning (~1.17 GB)",
            description = "Dedicated 0.6B Base Qwen3-TTS checkpoint with speaker & codec encoders for 3-second zero-shot cloning across 10 languages!",
            backend = BackendType.QWEN3_NATIVE,
            downloadUrl = "https://huggingface.co/wkwong/Lunavox-Qwen3-TTS-GGUF/resolve/main/base_small/qwen3_tts_talker.q5_k.gguf",
            fileName = "qwen3_tts_talker.q5_k.gguf",
            expectedSha256 = "21e5780209f969c2293daa0f17eb96b436377ab75f280b0f0f10fb52ebad1b43",
            approximateSizeMb = 1173,
            isArchiveTarBz2 = false,
            hardwareAccelNote = "Qwen3-TTS 0.6B Base + Speaker/Codec Encoders • 8GB+ RAM",
            unlocksSummary = "ENABLES: 3-second Qwen3 multilingual voice cloning in Clone Studio under ROM-04.",
            isSubCartridge = true,
            parentCartridgeId = "rom_qwen3_custom_06b",
            extraAssets = lunavoxQwen3Assets(
                hfSubDir = "base_small",
                predictorSha = "80d942f3c9665688aabf6c282a162b3786b6e648ff9741d56994791b439f91e2",
                decoderSha = "82d043dcff8c8bfaafd2c05bd5c85e922e2d50a9bdc4c7d45eea895edced2137",
                textEmbSha = "e809a93cd073d3976fc5b092f067de5520bca85323c20357f0d9c01612c84a63",
                textEmbSizeBytes = 311165056L,
                codecShas = listOf(
                    "47fa9e30f98b1528fc9b332d314f22a32fa33e187509a4d3537f8b2c31199e39",
                    "2cf4b5d6f6fca977657bc82634eea275cd072036a6a16484febd41b5445abff1",
                    "c8655ecd642f17d04a85ea37c2665205a8666843925227b25dc8c8bceb8015f5",
                    "5436624853b3091bc1b88cf37a4d60af46346c508237d72e597bed561a5b2138",
                    "1041b78ea5158c96cda4aae45bd21707a6fe8c92e6831715075d70d7b5b8e77a",
                    "17af8770454ab26ca82f092d0d5a634680e4d14412d327fb8b6f94a57b256800",
                    "b9abbf163b50ba0207e2196772c67b2e1025cf7f4fbe378c6a44123869253ad9",
                    "1fb06414cb7d4edcd73142e79cbb4bd46f918517e5812b73345ade9535c6eafb",
                    "08b8d7bd125d75ffa42f515153e66ade42cb16c2eea6ebe1935a2ac2509cbad0",
                    "a2cf331bb3e2f2c0d44c91c55af045b9c975199331e4566a373b883ce04e9964",
                    "389422edef8c0b6dbf6cdb48359a0274b11984c04668c004810866784dab2942",
                    "bf17275629b464a4a38826beeb79b99e0e58cc02c530da2e572b9b7d9972fb2d",
                    "dee4af40f34626a4951fba2b894120c8cf8293024ae068d1f2579f356c6e2284",
                    "e307b70fbaddc6e7e48259b4e8c9417f24b155be9281205f38bbf40c64359951",
                    "cc15bfc4290be77523f8f14c78b9b1710042b5d078291ee87038dfb7ecb3f185",
                    "aa0de98365775e1cb968514595ff7e23936bea21c2bda6e5bab7e7052cdf97ba"
                ),
                includeBaseCloneEncoders = true
            ),
            category = CartridgeCategory.TTS
        ),
        ModelBundleSpec(
            id = "sub_qwen3_design_17b",
            romCode = "SUB-04B",
            badge = "1.7B_VOICEDESIGN",
            accentHex = 0xFFFF007F,
            name = "QWEN3_17B_VOICEDESIGN",
            subtitle = "Qwen3-TTS 1.7B VoiceDesign • Prompt-Directed Custom Voices (~2.1 GB)",
            description = "Massive 1.7B Qwen3-TTS VoiceDesign model! Type any natural English voice description (\"playful anime girl, soft close whisper\") and synthesize bespoke voices offline. Needs 12GB+ RAM device.",
            backend = BackendType.QWEN3_NATIVE,
            downloadUrl = "https://huggingface.co/wkwong/Lunavox-Qwen3-TTS-GGUF/resolve/main/design/qwen3_tts_talker.q5_k.gguf",
            fileName = "qwen3_tts_talker.q5_k.gguf",
            expectedSha256 = "16950c48333f1ecbdcc85c32824b086da18e05fc4497557cd7df3ee4ded947ed",
            approximateSizeMb = 2100,
            isArchiveTarBz2 = false,
            hardwareAccelNote = "1.7B Qwen3 VoiceDesign • 12GB+ RAM Flagship Devices",
            unlocksSummary = "UNLOCKS: qwen3_design_custom (create any voice live from a text prompt under ROM-04).",
            isSubCartridge = true,
            parentCartridgeId = "rom_qwen3_custom_06b",
            extraAssets = lunavoxQwen3Assets(
                hfSubDir = "design",
                predictorSha = "bc65c25c53d172af6f2d5b90cefa648c65178c3f833067d2e26d7c07c972fc72",
                decoderSha = "82d043dcff8c8bfaafd2c05bd5c85e922e2d50a9bdc4c7d45eea895edced2137",
                textEmbSha = "4d6c2bc959b3a810aeedf1b0e8637600531892f98894edacf33231be15a7d9c3",
                textEmbSizeBytes = 622329984L,
                codecShas = listOf(
                    "1140e81db3203d0e9118569f264271174588115d29b257a6a857be5fd4692a2f",
                    "d817a2fddbd2b6b4a48fc82eb6dae03c7e85976c3c978632ef8d3d463f405e8f",
                    "dacdb94f57da9b2c52ac7122c44bbe5d0b0161c01bb959fcec20aadfefadd557",
                    "cd159613ad05d6c13b110cfd897e58c2dcc4992b16b65a8885195e2a85bc4f22",
                    "591f29341005ef44a08374f7db6fa09331bfd40099222a30841a6a30d468307f",
                    "b3475def6be6832ec81be51ca1a578cc602dfd5e8da943514d54590263c494ea",
                    "1c6b04c82af4377f570205b7fe012b19c1fbe145e55bd23ded18b17817afd02e",
                    "818807b351bf14a35673f0947fdb56b5cd5603b89ee794479d75c74d19c1058b",
                    "ab66016da143999347eb38855dbf4960043895b642ed217260b020020c43bc75",
                    "78c897e85d9f7ed30d15f9d7d3b2c74b8ce36ff61314a7b81712b39cb4a7e22b",
                    "034ca0184c8e546d6de7e61dcdcab8632260e034e91727ba0c7b6e9d7fef997c",
                    "223221fe7d3812e87ab768772ad43ece3bcdc4fd3a9a1553fdac728d5804dc06",
                    "70032ec9f813bab56ae782c225afd6923d4fab61693ea7d9031b25e5801633d7",
                    "8273bcfe29249ce1f40b8a2f50a221757cfd20a1470fff0dad0af1ee3fbd981b",
                    "e98208b4cfe5b7ad0d587ff6102bffdef64cddbd501ec0a74a2b216717170259",
                    "c08e5712f1aa9da944c8725e53e5c7294eee4260e4d686e8b2add990bc85550d"
                ),
                codecEmbSizeBytes = 16777344L,
                codec0SizeBytes = 25165952L,
                includeDesignProj = true
            ),
            category = CartridgeCategory.TTS
        ),

        // =========================================================================
        // SUB-CARTRIDGE: ROM-07 (KITTEN_TTS_HUB)
        // =========================================================================
        ModelBundleSpec(
            id = "sub_kitten_mini",
            romCode = "SUB-07A",
            badge = "KITTEN_80M_MINI",
            accentHex = 0xFFFFB703,
            name = "KITTEN_TTS_MINI_80M",
            subtitle = "Full 80M KittenTTS v0.8 • Same 8 Voices at 5x Weight Fidelity (+64 MB)",
            description = "Heavyweight 80M upgrade for KittenTTS Hub delivering cleaner sibilance and deeper expression than Nano 30MB.",
            backend = BackendType.SHERPA_ONNX,
            sherpaFamily = SherpaModelFamily.KITTEN_TTS,
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kitten-mini-en-v0_8.tar.bz2",
            fileName = "kitten-mini-en-v0_8.tar.bz2",
            expectedSha256 = "518f9b130320f690d5b5476df77bde4215fca67773cda16710318e5081234b9d",
            approximateSizeMb = 64,
            isArchiveTarBz2 = true,
            hardwareAccelNote = "KittenTTS Mini v0.8 (80M) + espeak-ng",
            unlocksSummary = "ADDS VOICES: 8 full-weight KittenTTS Mini 80M voices (kmini_*) to ROM-07.",
            isSubCartridge = true,
            parentCartridgeId = "rom_kitten_tts",
            category = CartridgeCategory.TTS
        ),

        // =========================================================================
        // SUB-CARTRIDGES: ROM-08 (NARRATOR_VITS_FLOW_HUB)
        // =========================================================================
        ModelBundleSpec(
            id = "sub_piper_amy",
            romCode = "SUB-08A",
            badge = "US_WARM",
            accentHex = 0xFFFFD700,
            name = "PIPER_AMY_US",
            subtitle = "en_US-amy-medium • Warm Friendly American Female (+64 MB)",
            description = "Adds the popular Piper Amy Medium US English female voice to ROM-08.",
            backend = BackendType.SHERPA_ONNX,
            sherpaFamily = SherpaModelFamily.PIPER,
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-amy-medium.tar.bz2",
            fileName = "vits-piper-en_US-amy-medium.tar.bz2",
            expectedSha256 = "9a5d1fc497f85e8022b785bff5f8105203b1e33099ee6265203efc70b0cb0264",
            approximateSizeMb = 64,
            isArchiveTarBz2 = true,
            hardwareAccelNote = "Piper VITS ONNX Medium (en-US)",
            unlocksSummary = "ADDS VOICE: Piper VITS // Amy Medium (US Warm) to ROM-08.",
            isSubCartridge = true,
            parentCartridgeId = "rom_piper",
            category = CartridgeCategory.TTS
        ),
        ModelBundleSpec(
            id = "sub_piper_ryan",
            romCode = "SUB-08B",
            badge = "US_DEEP_MALE",
            accentHex = 0xFF00E5FF,
            name = "PIPER_RYAN_US",
            subtitle = "en_US-ryan-high • Deep Resonant Male Narrator (+110 MB)",
            description = "Adds the studio-grade Piper Ryan High male audiobook voice to ROM-08.",
            backend = BackendType.SHERPA_ONNX,
            sherpaFamily = SherpaModelFamily.PIPER,
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-ryan-high.tar.bz2",
            fileName = "vits-piper-en_US-ryan-high.tar.bz2",
            expectedSha256 = "6a71edf4d308b9cb2eaeadc8d1f3c6bf96120ecb7fe52c29a2b6e139c59760ed",
            approximateSizeMb = 110,
            isArchiveTarBz2 = true,
            hardwareAccelNote = "Piper VITS ONNX High (en-US)",
            unlocksSummary = "ADDS VOICE: Piper VITS // Ryan High (US Deep Narrator) to ROM-08.",
            isSubCartridge = true,
            parentCartridgeId = "rom_piper",
            category = CartridgeCategory.TTS
        ),
        ModelBundleSpec(
            id = "sub_piper_cori",
            romCode = "SUB-08C",
            badge = "UK_ELEGANT",
            accentHex = 0xFFB533FF,
            name = "PIPER_CORI_GB",
            subtitle = "en_GB-cori-high • Refined British Female Narrator (+110 MB)",
            description = "Adds the high-resolution Piper Cori British RP female voice to ROM-08.",
            backend = BackendType.SHERPA_ONNX,
            sherpaFamily = SherpaModelFamily.PIPER,
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_GB-cori-high.tar.bz2",
            fileName = "vits-piper-en_GB-cori-high.tar.bz2",
            expectedSha256 = "42922f07738fcde2e49eed4e959635692f73b933de35a6b7c1010162ff566292",
            approximateSizeMb = 110,
            isArchiveTarBz2 = true,
            hardwareAccelNote = "Piper VITS ONNX High (en-GB)",
            unlocksSummary = "ADDS VOICE: Piper VITS // Cori High (UK Elegant) to ROM-08.",
            isSubCartridge = true,
            parentCartridgeId = "rom_piper",
            category = CartridgeCategory.TTS
        ),
        ModelBundleSpec(
            id = "sub_piper_alba",
            romCode = "SUB-08D",
            badge = "SCOTTISH_UK",
            accentHex = 0xFF00FF41,
            name = "PIPER_ALBA_GB",
            subtitle = "en_GB-alba-medium • Expressive Scottish/UK Female (+64 MB)",
            description = "Adds the charming Scottish-accented Piper Alba female voice to ROM-08.",
            backend = BackendType.SHERPA_ONNX,
            sherpaFamily = SherpaModelFamily.PIPER,
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_GB-alba-medium.tar.bz2",
            fileName = "vits-piper-en_GB-alba-medium.tar.bz2",
            expectedSha256 = "fcd45962906933eec4431d3688f7d74aaac8713c87c6717f91fd3b23463aa1a1",
            approximateSizeMb = 64,
            isArchiveTarBz2 = true,
            hardwareAccelNote = "Piper VITS ONNX Medium (en-GB)",
            unlocksSummary = "ADDS VOICE: Piper VITS // Alba Medium (Scottish/UK) to ROM-08.",
            isSubCartridge = true,
            parentCartridgeId = "rom_piper",
            category = CartridgeCategory.TTS
        ),
        ModelBundleSpec(
            id = "sub_piper_glados",
            romCode = "SUB-08E",
            badge = "CYBER_AI",
            accentHex = 0xFFFF107A,
            name = "PIPER_GLADOS_AI",
            subtitle = "en_US-glados-high • Iconic Cybernetic Android AI Voice (+110 MB)",
            description = "Adds the signature dry, calculating cybernetic AI voice (GLaDOS style) to ROM-08.",
            backend = BackendType.SHERPA_ONNX,
            sherpaFamily = SherpaModelFamily.PIPER,
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-glados-high.tar.bz2",
            fileName = "vits-piper-en_US-glados-high.tar.bz2",
            expectedSha256 = "dcb52d4be94200d42b4d97ebb82ec398be27039f4719207283fa5e7d931b1cf3",
            approximateSizeMb = 110,
            isArchiveTarBz2 = true,
            hardwareAccelNote = "Piper VITS ONNX High (Cyber AI)",
            unlocksSummary = "ADDS VOICE: Piper VITS // GLaDOS (Cybernetic AI) to ROM-08.",
            isSubCartridge = true,
            parentCartridgeId = "rom_piper",
            category = CartridgeCategory.TTS
        ),
        ModelBundleSpec(
            id = "sub_piper_kristin",
            romCode = "SUB-08F",
            badge = "US_STORY",
            accentHex = 0xFFFF9E00,
            name = "PIPER_KRISTIN_US",
            subtitle = "en_US-kristin-medium • Clear US Audiobook Storyteller (+64 MB)",
            description = "Adds the articulate Piper Kristin Medium US storyteller voice to ROM-08.",
            backend = BackendType.SHERPA_ONNX,
            sherpaFamily = SherpaModelFamily.PIPER,
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-kristin-medium.tar.bz2",
            fileName = "vits-piper-en_US-kristin-medium.tar.bz2",
            expectedSha256 = "c2206f572df2956c50b1ae3367eebce3853c663e890cba8048cd62b1e4dbe6c7",
            approximateSizeMb = 64,
            isArchiveTarBz2 = true,
            hardwareAccelNote = "Piper VITS ONNX Medium (en-US)",
            unlocksSummary = "ADDS VOICE: Piper VITS // Kristin Medium (US Storyteller) to ROM-08.",
            isSubCartridge = true,
            parentCartridgeId = "rom_piper",
            category = CartridgeCategory.TTS
        ),
        ModelBundleSpec(
            id = "sub_melo_en",
            romCode = "SUB-08G",
            badge = "MELOTTS_5ACCENTS",
            accentHex = 0xFF00E5FF,
            name = "MELO_TTS_ENGLISH",
            subtitle = "MeloTTS VITS English • 5 Native Accents (US, UK, IN, AU, Studio) (+155 MB)",
            description = "MyShell AI's MeloTTS English model featuring 5 crisp English speakers (US, British, Indian, Australian, Neutral Studio).",
            backend = BackendType.SHERPA_ONNX,
            sherpaFamily = SherpaModelFamily.MELO_TTS,
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-melo-tts-en.tar.bz2",
            fileName = "vits-melo-tts-en.tar.bz2",
            expectedSha256 = "f87bc5752ea3ec34273a2cc0c5086854c18b6b89dfd0534b5248e86a14cedb5d",
            approximateSizeMb = 155,
            isArchiveTarBz2 = true,
            hardwareAccelNote = "MeloTTS VITS + CMU Lexicon",
            unlocksSummary = "ADDS VOICES: 5 English accent voices (melo_en_us..default) to ROM-08.",
            isSubCartridge = true,
            parentCartridgeId = "rom_piper",
            category = CartridgeCategory.TTS
        ),
        ModelBundleSpec(
            id = "rom_supertonic3",
            romCode = "SUB-08H",
            badge = "LOW_LATENCY",
            accentHex = 0xFF38BDF8,
            name = "SUPERTONIC_3_TTS",
            subtitle = "k2-fsa/sherpa-onnx • Ultra-Fast Natural Conversational Speech (+123 MB)",
            description = "Snappy multilingual neural synthesis optimized for rapid chat turn-taking and low battery draw.",
            backend = BackendType.SHERPA_ONNX,
            sherpaFamily = SherpaModelFamily.SUPERTONIC_3,
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2",
            fileName = "sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2",
            expectedSha256 = "82fa96f91c4ef8abaae3a14a3f4153facf88bed821d1f7331cec2700f432c427",
            approximateSizeMb = 123,
            isArchiveTarBz2 = true,
            hardwareAccelNote = "ARM64 NEON INT8 Supertonic 3",
            unlocksSummary = "ADDS VOICES: Supertonic F1 Female & M1 Male to ROM-08.",
            isSubCartridge = true,
            parentCartridgeId = "rom_piper",
            category = CartridgeCategory.TTS
        ),
        ModelBundleSpec(
            id = "rom_matcha_zh_en",
            romCode = "SUB-08I",
            badge = "FLOW_NARRATOR",
            accentHex = 0xFF9146FF,
            name = "MATCHA_ZH_EN",
            subtitle = "Matcha-TTS Flow Synthesis (ZH+EN) + 16kHz Vocos Vocoder (75MB + 51MB)",
            description = "Matcha-TTS flow-matching synthesis with astonishingly natural prosody and blazing speed (RTF < 1). Ships with the verified 16kHz Vocos universal vocoder.",
            backend = BackendType.SHERPA_ONNX,
            sherpaFamily = SherpaModelFamily.MATCHA,
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/matcha-icefall-zh-en.tar.bz2",
            fileName = "matcha-icefall-zh-en.tar.bz2",
            expectedSha256 = "271b804af570400d3bcdcb53bf6e53cc9f75180ee763b9f13eb5eaf2b0d086ef",
            approximateSizeMb = 75,
            isArchiveTarBz2 = true,
            companionUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/vocoder-models/vocos-16khz-univ.onnx",
            companionFileName = "vocos-16khz-univ.onnx",
            companionExpectedSha256 = "b599142a1fb8ff03de3e84ac35ff537c619e56f4267a6fe894851a42844acf9e",
            companionApproxSizeMb = 51,
            hardwareAccelNote = "ONNX CPU • Flow-Matching + 16kHz Vocos Vocoder",
            unlocksSummary = "ADDS VOICE: Matcha Narrator (ZH/EN) to ROM-08.",
            isSubCartridge = true,
            parentCartridgeId = "rom_piper",
            category = CartridgeCategory.TTS
        ),
        // =========================================================================
        // BUILT-IN OS TOOL MODULES (TOOL-01 .. TOOL-04)
        // =========================================================================
        ModelBundleSpec(
            id = "tool_purr_engine",
            romCode = "TOOL-01",
            badge = "CORE_MIDDLEWARE",
            accentHex = 0xFFFF107A,
            name = "PURR_ENGINE_CORE",
            subtitle = "Vibe Presets, Slang/Honorifics, Stutters, Emotion & MIKASTOP Pauses",
            description = "Core P.U.R.R. text & prosody OS module. Ejecting turns off PURR text rewriting, sentiment modulation & MIKASTOP timing and removes its OS icon from Modules.",
            backend = BackendType.CORE_OS,
            downloadUrl = "builtin://tool/purr_engine",
            fileName = "purr_engine.tool",
            expectedSha256 = "0000000000000000000000000000000000000000000000000000000000000000",
            approximateSizeMb = 0,
            isArchiveTarBz2 = false,
            hardwareAccelNote = "PURR Text & Prosody Middleware • Ejectable",
            isBuiltInOsCartridge = true,
            unlocksSummary = "ENABLES: P.U.R.R. Engine OS Module (Vibe Presets, MIKASTOP breath pauses, Slang/Honorifics & Rulebook).",
            category = CartridgeCategory.TOOLS
        ),
        ModelBundleSpec(
            id = "tool_dsp_rack",
            romCode = "TOOL-02",
            badge = "24KHZ_AUDIO_DSP",
            accentHex = 0xFF00E5FF,
            name = "ACOUSTIC_DSP_RACK",
            subtitle = "GachaSwipe 24kHz Post-DSP, 120ms Sentence Taper & 10 Voice Effects",
            description = "GachaSwipe CyberAudioNote mastering chain: 80Hz high-pass, 250Hz chest warmth, 8.5kHz de-esser, glue compressor & 10 effects. Ejecting bypasses all DSP.",
            backend = BackendType.CORE_OS,
            downloadUrl = "builtin://tool/dsp_rack",
            fileName = "dsp_rack.tool",
            expectedSha256 = "0000000000000000000000000000000000000000000000000000000000000000",
            approximateSizeMb = 0,
            isArchiveTarBz2 = false,
            hardwareAccelNote = "Real-Time 24kHz PCM Effect Rack • Ejectable",
            isBuiltInOsCartridge = true,
            unlocksSummary = "ENABLES: Acoustic DSP Rack OS Module (120ms Tail Taper + 10 Character Voice Effects).",
            category = CartridgeCategory.TOOLS
        ),
        ModelBundleSpec(
            id = "tool_symbol_shield",
            romCode = "TOOL-03",
            badge = "AUDIOBOOK_GUARD",
            accentHex = 0xFF00FF41,
            name = "SYMBOL_SHIELD_GUARD",
            subtitle = "Anti-Hallucination Symbol & Punctuation Shield + Loose Chunking",
            description = "Intercepts quotes, commas, brackets & markdown before neural synthesis and groups loose multi-sentence audiobook blocks. Ejecting passes raw symbols.",
            backend = BackendType.CORE_OS,
            downloadUrl = "builtin://tool/symbol_shield",
            fileName = "symbol_shield.tool",
            expectedSha256 = "0000000000000000000000000000000000000000000000000000000000000000",
            approximateSizeMb = 0,
            isArchiveTarBz2 = false,
            hardwareAccelNote = "Audiobook Sanitizer & Chunk Coalescer • Ejectable",
            isBuiltInOsCartridge = true,
            unlocksSummary = "ENABLES: Symbol & Chunk Shield OS Module (Punctuation Filter + Loose Multi-Sentence Chunker).",
            category = CartridgeCategory.TOOLS
        ),
        ModelBundleSpec(
            id = "tool_clone_studio",
            romCode = "TOOL-04",
            badge = "5S_VOICE_CLONER",
            accentHex = 0xFFB533FF,
            name = "VOICE_CLONE_STUDIO",
            subtitle = "3-12s Zero-Shot Mic Reference Recorder & Cloner Workshop",
            description = "Dedicated studio OS app for recording & registering custom zero-shot cloned voices across Pocket TTS, ZipVoice, Chatterbox-Turbo (ROM-03) and Qwen3-TTS Base (ROM-04).",
            backend = BackendType.CORE_OS,
            downloadUrl = "builtin://tool/clone_studio",
            fileName = "clone_studio.tool",
            expectedSha256 = "0000000000000000000000000000000000000000000000000000000000000000",
            approximateSizeMb = 0,
            isArchiveTarBz2 = false,
            hardwareAccelNote = "16kHz Mono Reference Recorder • Ejectable",
            isBuiltInOsCartridge = true,
            unlocksSummary = "ENABLES: Voice Clone Studio OS Module for custom reference voice recording.",
            category = CartridgeCategory.TOOLS
        ),
        ModelBundleSpec(
            id = "tool_audio_exporter",
            romCode = "TOOL-05",
            badge = "WAV_DOWNLOAD",
            accentHex = 0xFFFFD700,
            name = "AUDIO_MEDIA_EXPORTER",
            subtitle = "Text & Audiobook Chapter -> Mastered 24kHz .WAV Media Downloader",
            description = "Synthesizes short messages or multi-page audiobook chapters with smart sentence chunking, crossfade blending, RMS leveling & direct Downloads export.",
            backend = BackendType.CORE_OS,
            downloadUrl = "builtin://tool/audio_exporter",
            fileName = "audio_exporter.tool",
            expectedSha256 = "0000000000000000000000000000000000000000000000000000000000000000",
            approximateSizeMb = 0,
            isArchiveTarBz2 = false,
            hardwareAccelNote = "Multi-Chunk Audiobook Stitcher & WAV Mastering • Ejectable",
            isBuiltInOsCartridge = true,
            unlocksSummary = "ENABLES: Audio Media Exporter OS Module for rendering & downloading mastered .WAV speech files.",
            category = CartridgeCategory.TOOLS
        ),
        ModelBundleSpec(
            id = "tool_voice_design",
            romCode = "TOOL-06",
            badge = "1.7B_PROMPT_LAB",
            accentHex = 0xFFFF007F,
            name = "QWEN3_VOICE_DESIGN",
            subtitle = "Qwen3 1.7B Prompt Builder • Gender, Pitch, Timbre, Pace, Accent & Lock-Clone",
            description = "Dedicated Cyberdeck OS studio for Qwen3-TTS 1.7B VoiceDesign (SUB-04B): build natural-language voice prompts from structured acoustic controls, save custom voice presets, audition live, or lock into a reusable Clone reference.",
            backend = BackendType.CORE_OS,
            downloadUrl = "builtin://tool/voice_design",
            fileName = "voice_design.tool",
            expectedSha256 = "0000000000000000000000000000000000000000000000000000000000000000",
            approximateSizeMb = 0,
            isArchiveTarBz2 = false,
            hardwareAccelNote = "Qwen3-TTS 1.7B VoiceDesign Prompt Studio • Ejectable",
            isBuiltInOsCartridge = true,
            unlocksSummary = "ENABLES: Qwen3 VoiceDesign Studio OS Module for crafting and saving custom prompt-driven voices.",
            category = CartridgeCategory.TOOLS
        ),
        ModelBundleSpec(
            id = "tool_audiobook_constructor",
            romCode = "TOOL-07",
            badge = "M4B_BOOK_FORGE",
            accentHex = 0xFF38BDF8,
            name = "AUDIOBOOK_CONSTRUCTOR",
            subtitle = "EPUB / PDF / TXT / MD / DOCX / FB2 -> Chaptered .M4B Audiobook Builder",
            description = "Imports full ebooks across formats, splits intelligently into selectable chapters, synthesizes per-chapter audio with Pause & Resume-Later persistence, and combines completed chapters into a standard chaptered .M4B audiobook.",
            backend = BackendType.CORE_OS,
            downloadUrl = "builtin://tool/audiobook_constructor",
            fileName = "audiobook_constructor.tool",
            expectedSha256 = "0000000000000000000000000000000000000000000000000000000000000000",
            approximateSizeMb = 0,
            isArchiveTarBz2 = false,
            hardwareAccelNote = "Multi-Format Ebook Parser + Resumable .M4B Chapter Muxer • Optional Tool",
            isBuiltInOsCartridge = true,
            unlocksSummary = "ENABLES: Audiobook Constructor OS Module for chapter-by-chapter resumable .M4B creation.",
            category = CartridgeCategory.TOOLS
        )
    )
}
