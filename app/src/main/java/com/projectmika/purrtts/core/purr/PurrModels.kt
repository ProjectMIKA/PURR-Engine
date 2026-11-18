package com.projectmika.purrtts.core.purr

import kotlinx.serialization.Serializable

/**
 * ✨ MIKA'S P.U.R.R. ENGINE DATA MODELS ✨
 * Shared immutable contracts between PurrTtsService, TtsBackendRouter,
 * Cartridge Bay manager, and the streamlined Compose UI.
 */

@Serializable
enum class BackendType(val displayLabel: String) {
    CORE_OS("System Speech (Built-in)"),
    SHERPA_ONNX("Sherpa-ONNX Cartridge"),
    LITERT_NPU_GPU("LiteRT GPU/NPU Cartridge"),
    EXTERNAL_PROXY("LAN OpenAI / Proxy")
}

@Serializable
enum class SherpaModelFamily(val label: String) {
    KOKORO("Kokoro-82M INT8"),
    KITTEN_TTS("KittenTTS Nano"),
    SUPERTONIC_3("Supertonic 3"),
    POCKET_TTS("Pocket TTS (Clone)"),
    ZIPVOICE("ZipVoice (Clone)"),
    PIPER("Piper VITS")
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
    val joyPitchDelta: Float = 0.025f,
    val loveRateDelta: Float = -0.05f,
    val lovePitchDelta: Float = 0.015f,
    val angerRateDelta: Float = 0.05f,
    val angerPitchDelta: Float = 0.020f,
    val sadnessRateDelta: Float = -0.08f,
    val sadnessPitchDelta: Float = -0.025f,
    val flusteredRateDelta: Float = 0.06f,
    val flusteredPitchDelta: Float = 0.035f,
    val stutterIntensity: Float = 0.15f,
    val vowelStretchIntensity: Float = 0.15f
)

@Serializable
data class DspConfig(
    val warmthEqDb: Float = 2.0f,
    val presenceEqDb: Float = -4.0f,
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
    val proxyConfig: ProxyEndpointConfig = ProxyEndpointConfig()
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
    val gender: VoiceGender = VoiceGender.FEMALE,
    val languageCode: String = "en-US",
    val referenceWavPath: String,
    val referenceTranscript: String,
    val tags: List<String> = listOf("cloned", "custom")
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
    val unlocksSummary: String = "",
    val isSubCartridge: Boolean = false,
    val parentCartridgeId: String? = null,
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
        )
    ),
    val slottedCartridges: Set<String> = setOf(
        "rom_core_os",
        "tool_purr_engine",
        "tool_dsp_rack",
        "tool_symbol_shield",
        "tool_clone_studio",
        "tool_audio_exporter"
    ),
    val enabledSubCartridges: Set<String> = emptySet()
)

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
        displaySuffix: String = ""
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
                nativeTags = tags
            )
        }
    }

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

        val sherpaSpecialized = listOf(
            VoiceDescriptor(
                id = "supertonic3_f1",
                displayName = "Supertonic 3 // Female Lead (F1)",
                engine = BackendType.SHERPA_ONNX,
                cartridgeId = "rom_supertonic3",
                sherpaFamily = SherpaModelFamily.SUPERTONIC_3,
                gender = VoiceGender.FEMALE,
                languageCode = "en-US",
                speakerIndex = 0,
                nativeTags = listOf("female", "american", "cute", "energetic", "supertonic")
            ),
            VoiceDescriptor(
                id = "supertonic3_m1",
                displayName = "Supertonic 3 // Male Lead (M1)",
                engine = BackendType.SHERPA_ONNX,
                cartridgeId = "rom_supertonic3",
                sherpaFamily = SherpaModelFamily.SUPERTONIC_3,
                gender = VoiceGender.MALE,
                languageCode = "en-US",
                speakerIndex = 1,
                nativeTags = listOf("male", "american", "calm", "narrator", "supertonic")
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
                id = "pocket_tts_zero_shot",
                displayName = "Pocket TTS // Base Zero-Shot",
                engine = BackendType.SHERPA_ONNX,
                cartridgeId = "rom_pocket_tts",
                sherpaFamily = SherpaModelFamily.POCKET_TTS,
                gender = VoiceGender.ANDROGYNOUS,
                languageCode = "en-US",
                speakerIndex = 0,
                nativeTags = listOf("androgynous", "american", "clone", "warm")
            ),
            VoiceDescriptor(
                id = "zipvoice_flow_en",
                displayName = "ZipVoice // Flow Neural",
                engine = BackendType.SHERPA_ONNX,
                cartridgeId = "rom_zipvoice",
                sherpaFamily = SherpaModelFamily.ZIPVOICE,
                gender = VoiceGender.FEMALE,
                languageCode = "en-US",
                speakerIndex = 0,
                nativeTags = listOf("female", "american", "soft", "breathy", "clone")
            )
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

        return coreOsVoices + kokoroSherpa + kittenVoices + sherpaSpecialized + proxyVoices
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
                warmthEqDb = 2.0f,
                presenceEqDb = -4.0f,
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
                warmthEqDb = 1.5f,
                presenceEqDb = -3.5f,
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
                warmthEqDb = 1.5f,
                presenceEqDb = -3.0f,
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
                warmthEqDb = 3.0f,
                presenceEqDb = -4.5f,
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
                warmthEqDb = 2.0f,
                presenceEqDb = -3.0f,
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

    /**
     * 7 Main TTS Cartridges (`ROM-01`..`ROM-07`), Expansion Sub-Cartridges (`SUB-02A/B` & `SUB-05A..F`),
     * plus 4 Built-In Tool Cartridges (`TOOL-01`..`TOOL-04`) under `CartridgeCategory.TOOLS`.
     */
    fun modelCatalog(): List<ModelBundleSpec> = listOf(
        // =========================================================================
        // TTS ENGINES (ROM-01 .. ROM-07 + EXPANSION CHIPS)
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
            badge = "54_VOICES",
            accentHex = 0xFF00FF41,
            name = "KOKORO_82M_NEURAL",
            subtitle = "Flagship 54-Voice Pack (126MB Base) + Optional NPU & GPU Sub-Cartridges",
            description = "Download & slam this 126MB base cartridge to unlock all 54 GachaSwipe voices offline. Optionally install the Hexagon NPU or Adreno GPU sub-cartridges below to accelerate it!",
            backend = BackendType.SHERPA_ONNX,
            sherpaFamily = SherpaModelFamily.KOKORO,
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-multi-lang-v1_0.tar.bz2",
            fileName = "kokoro-int8-multi-lang-v1_0.tar.bz2",
            expectedSha256 = "72c347230107ef8a31098ec4d468a605244beef5f1ed00db97efa13518800653",
            approximateSizeMb = 126,
            isArchiveTarBz2 = true,
            hardwareAccelNote = "Bundled sherpa-onnx JNI • ARM64 NEON Base (+ Optional NPU/GPU Sub-Chips)",
            unlocksSummary = "UNLOCKS: All 54 Multi-Language Kokoro Voices (af_bella, af_sarah, am_adam, bf_emma, jf_alpha & 49 more) 100% offline.",
            category = CartridgeCategory.TTS
        ),
        ModelBundleSpec(
            id = "rom_kitten_tts",
            romCode = "ROM-03",
            badge = "30MB_8_VOICES",
            accentHex = 0xFFFF9E00,
            name = "KITTEN_TTS_NANO",
            subtitle = "k2-fsa/sherpa-onnx • Ultra-Compact 30MB Expressive 8-Voice Engine",
            description = "Tiny-footprint KittenTTS Nano v0.8 INT8 model delivering 8 expressive English voices (4 female, 4 male) with lightning-fast CPU synthesis.",
            backend = BackendType.SHERPA_ONNX,
            sherpaFamily = SherpaModelFamily.KITTEN_TTS,
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kitten-nano-en-v0_8-int8.tar.bz2",
            fileName = "kitten-nano-en-v0_8-int8.tar.bz2",
            expectedSha256 = "6fa5be852612ce761094ba74ee6123b4fc4acfefa79bf64dc63acae4a83af2fd",
            approximateSizeMb = 30,
            isArchiveTarBz2 = true,
            hardwareAccelNote = "KittenTTS Nano v0.8 INT8 • Ultra-Fast 30MB",
            unlocksSummary = "UNLOCKS: 8 expressive English KittenTTS voices (F1-F4 & M1-M4) in just 30 MB.",
            category = CartridgeCategory.TTS
        ),
        ModelBundleSpec(
            id = "rom_supertonic3",
            romCode = "ROM-04",
            badge = "LOW_LATENCY",
            accentHex = 0xFF38BDF8,
            name = "SUPERTONIC_3_TTS",
            subtitle = "k2-fsa/sherpa-onnx • Ultra-Fast Natural Conversational Speech",
            description = "Snappy multilingual neural synthesis optimized for rapid chat turn-taking and low battery draw.",
            backend = BackendType.SHERPA_ONNX,
            sherpaFamily = SherpaModelFamily.SUPERTONIC_3,
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2",
            fileName = "sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2",
            expectedSha256 = "82fa96f91c4ef8abaae3a14a3f4153facf88bed821d1f7331cec2700f432c427",
            approximateSizeMb = 123,
            isArchiveTarBz2 = true,
            hardwareAccelNote = "ARM64 NEON INT8 Supertonic 3",
            unlocksSummary = "UNLOCKS: 2 ultra-fast conversational voices (Supertonic F1 Female & M1 Male) for low-latency chat.",
            category = CartridgeCategory.TTS
        ),
        ModelBundleSpec(
            id = "rom_piper",
            romCode = "ROM-05",
            badge = "NARRATOR_HUB",
            accentHex = 0xFFFFD700,
            name = "PIPER_VITS_COLLECTION",
            subtitle = "k2-fsa/sherpa-onnx • Lessac Base + 6 Expandable Piper Voice Chips",
            description = "Rock-solid VITS audiobook & character narrator bay. Includes Lessac High US Base plus 6 downloadable expansion voices (Amy, Ryan, Cori UK, Alba UK, GLaDOS AI, Kristin).",
            backend = BackendType.SHERPA_ONNX,
            sherpaFamily = SherpaModelFamily.PIPER,
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-lessac-high.tar.bz2",
            fileName = "vits-piper-en_US-lessac-high.tar.bz2",
            expectedSha256 = "8619d204c7005866fe4f420181dfa79622af6a6222389f0b0818d2af31e0db0e",
            approximateSizeMb = 110,
            isArchiveTarBz2 = true,
            hardwareAccelNote = "VITS ONNX 22.05kHz -> 24kHz resampled",
            unlocksSummary = "UNLOCKS: Piper Lessac High (US Base) + 6 optional Piper Expansion Voice Sub-Cartridges below.",
            category = CartridgeCategory.TTS
        ),
        ModelBundleSpec(
            id = "rom_pocket_tts",
            romCode = "ROM-06",
            badge = "5S_CLONE",
            accentHex = 0xFFB533FF,
            name = "POCKET_TTS_CLONER",
            subtitle = "k2-fsa/sherpa-onnx • 5-Second Zero-Shot Voice Cloning",
            description = "Record any 5-second reference clip and clone that voice timbre offline in real time.",
            backend = BackendType.SHERPA_ONNX,
            sherpaFamily = SherpaModelFamily.POCKET_TTS,
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/sherpa-onnx-pocket-tts-int8-2026-01-26.tar.bz2",
            fileName = "sherpa-onnx-pocket-tts-int8-2026-01-26.tar.bz2",
            expectedSha256 = "2f3b88823cbbb9bf0b2477ec8ae7b3fec417b3a87b6bb5f256dba66f2ad967cb",
            approximateSizeMb = 94,
            isArchiveTarBz2 = true,
            hardwareAccelNote = "Pocket TTS Zero-Shot Speaker Encoder",
            unlocksSummary = "UNLOCKS: 5-second zero-shot Voice Cloning in the Clone Studio + Pocket TTS base voice.",
            category = CartridgeCategory.TTS
        ),
        ModelBundleSpec(
            id = "rom_zipvoice",
            romCode = "ROM-07",
            badge = "FLOW_CLONE",
            accentHex = 0xFFFF107A,
            name = "ZIPVOICE_FLOW_CLONE",
            subtitle = "k2-fsa/sherpa-onnx • High-Fidelity Flow-Matching Clone",
            description = "Distilled flow-matching zero-shot voice cloning engine with expressive prosody transfer.",
            backend = BackendType.SHERPA_ONNX,
            sherpaFamily = SherpaModelFamily.ZIPVOICE,
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/sherpa-onnx-zipvoice-distill-int8-zh-en-emilia.tar.bz2",
            fileName = "sherpa-onnx-zipvoice-distill-int8-zh-en-emilia.tar.bz2",
            expectedSha256 = "77219c8b40f4ee8d73a7f902305ff6c1128ef9b54461c41b4ca6ed890b6c2803",
            approximateSizeMb = 104,
            isArchiveTarBz2 = true,
            hardwareAccelNote = "ONNX Runtime INT8 + Vocos",
            unlocksSummary = "UNLOCKS: High-fidelity flow-matching zero-shot Voice Cloning in the Clone Studio + ZipVoice base voice.",
            category = CartridgeCategory.TTS
        ),
        // Optional Expansion Sub-Cartridges for ROM-02 (KOKORO_82M_NEURAL)
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
            unlocksSummary = "ACTIVATES: [NPU ACTIVE] hardware phoneme acceleration for all 54 Kokoro voices on ROM-02.",
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
            unlocksSummary = "ACTIVATES: [GPU FP16] hardware synthesis + 5m VRAM cache for ROM-02 (auto-falls back to ROM-02 Base if unsupported by device GPU).",
            isSubCartridge = true,
            parentCartridgeId = "rom_kokoro_sherpa",
            category = CartridgeCategory.TTS
        ),
        // 6 Optional Downloadable Piper Voice Expansion Sub-Cartridges for ROM-05 (PIPER_VITS_COLLECTION)
        ModelBundleSpec(
            id = "sub_piper_amy",
            romCode = "SUB-05A",
            badge = "US_WARM",
            accentHex = 0xFFFFD700,
            name = "PIPER_AMY_US",
            subtitle = "en_US-amy-medium • Warm Friendly American Female (+64 MB)",
            description = "Adds the popular Piper Amy Medium US English female voice to ROM-05.",
            backend = BackendType.SHERPA_ONNX,
            sherpaFamily = SherpaModelFamily.PIPER,
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-amy-medium.tar.bz2",
            fileName = "vits-piper-en_US-amy-medium.tar.bz2",
            expectedSha256 = "9a5d1fc497f85e8022b785bff5f8105203b1e33099ee6265203efc70b0cb0264",
            approximateSizeMb = 64,
            isArchiveTarBz2 = true,
            hardwareAccelNote = "Piper VITS ONNX Medium (en-US)",
            unlocksSummary = "ADDS VOICE: Piper VITS // Amy Medium (US Warm) to the Voice selector.",
            isSubCartridge = true,
            parentCartridgeId = "rom_piper",
            category = CartridgeCategory.TTS
        ),
        ModelBundleSpec(
            id = "sub_piper_ryan",
            romCode = "SUB-05B",
            badge = "US_DEEP_MALE",
            accentHex = 0xFF00E5FF,
            name = "PIPER_RYAN_US",
            subtitle = "en_US-ryan-high • Deep Resonant Male Narrator (+110 MB)",
            description = "Adds the studio-grade Piper Ryan High male audiobook voice to ROM-05.",
            backend = BackendType.SHERPA_ONNX,
            sherpaFamily = SherpaModelFamily.PIPER,
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-ryan-high.tar.bz2",
            fileName = "vits-piper-en_US-ryan-high.tar.bz2",
            expectedSha256 = "6a71edf4d308b9cb2eaeadc8d1f3c6bf96120ecb7fe52c29a2b6e139c59760ed",
            approximateSizeMb = 110,
            isArchiveTarBz2 = true,
            hardwareAccelNote = "Piper VITS ONNX High (en-US)",
            unlocksSummary = "ADDS VOICE: Piper VITS // Ryan High (US Deep Narrator) to the Voice selector.",
            isSubCartridge = true,
            parentCartridgeId = "rom_piper",
            category = CartridgeCategory.TTS
        ),
        ModelBundleSpec(
            id = "sub_piper_cori",
            romCode = "SUB-05C",
            badge = "UK_ELEGANT",
            accentHex = 0xFFB533FF,
            name = "PIPER_CORI_GB",
            subtitle = "en_GB-cori-high • Refined British Female Narrator (+110 MB)",
            description = "Adds the high-resolution Piper Cori British RP female voice to ROM-05.",
            backend = BackendType.SHERPA_ONNX,
            sherpaFamily = SherpaModelFamily.PIPER,
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_GB-cori-high.tar.bz2",
            fileName = "vits-piper-en_GB-cori-high.tar.bz2",
            expectedSha256 = "42922f07738fcde2e49eed4e959635692f73b933de35a6b7c1010162ff566292",
            approximateSizeMb = 110,
            isArchiveTarBz2 = true,
            hardwareAccelNote = "Piper VITS ONNX High (en-GB)",
            unlocksSummary = "ADDS VOICE: Piper VITS // Cori High (UK Elegant) to the Voice selector.",
            isSubCartridge = true,
            parentCartridgeId = "rom_piper",
            category = CartridgeCategory.TTS
        ),
        ModelBundleSpec(
            id = "sub_piper_alba",
            romCode = "SUB-05D",
            badge = "SCOTTISH_UK",
            accentHex = 0xFF00FF41,
            name = "PIPER_ALBA_GB",
            subtitle = "en_GB-alba-medium • Expressive Scottish/UK Female (+64 MB)",
            description = "Adds the charming Scottish-accented Piper Alba female voice to ROM-05.",
            backend = BackendType.SHERPA_ONNX,
            sherpaFamily = SherpaModelFamily.PIPER,
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_GB-alba-medium.tar.bz2",
            fileName = "vits-piper-en_GB-alba-medium.tar.bz2",
            expectedSha256 = "fcd45962906933eec4431d3688f7d74aaac8713c87c6717f91fd3b23463aa1a1",
            approximateSizeMb = 64,
            isArchiveTarBz2 = true,
            hardwareAccelNote = "Piper VITS ONNX Medium (en-GB)",
            unlocksSummary = "ADDS VOICE: Piper VITS // Alba Medium (Scottish/UK) to the Voice selector.",
            isSubCartridge = true,
            parentCartridgeId = "rom_piper",
            category = CartridgeCategory.TTS
        ),
        ModelBundleSpec(
            id = "sub_piper_glados",
            romCode = "SUB-05E",
            badge = "CYBER_AI",
            accentHex = 0xFFFF107A,
            name = "PIPER_GLADOS_AI",
            subtitle = "en_US-glados-high • Iconic Cybernetic Android AI Voice (+110 MB)",
            description = "Adds the signature dry, calculating cybernetic AI voice (GLaDOS style) to ROM-05.",
            backend = BackendType.SHERPA_ONNX,
            sherpaFamily = SherpaModelFamily.PIPER,
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-glados-high.tar.bz2",
            fileName = "vits-piper-en_US-glados-high.tar.bz2",
            expectedSha256 = "dcb52d4be94200d42b4d97ebb82ec398be27039f4719207283fa5e7d931b1cf3",
            approximateSizeMb = 110,
            isArchiveTarBz2 = true,
            hardwareAccelNote = "Piper VITS ONNX High (Cyber AI)",
            unlocksSummary = "ADDS VOICE: Piper VITS // GLaDOS (Cybernetic AI) to the Voice selector.",
            isSubCartridge = true,
            parentCartridgeId = "rom_piper",
            category = CartridgeCategory.TTS
        ),
        ModelBundleSpec(
            id = "sub_piper_kristin",
            romCode = "SUB-05F",
            badge = "US_STORY",
            accentHex = 0xFFFF9E00,
            name = "PIPER_KRISTIN_US",
            subtitle = "en_US-kristin-medium • Clear US Audiobook Storyteller (+64 MB)",
            description = "Adds the articulate Piper Kristin Medium US storyteller voice to ROM-05.",
            backend = BackendType.SHERPA_ONNX,
            sherpaFamily = SherpaModelFamily.PIPER,
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-kristin-medium.tar.bz2",
            fileName = "vits-piper-en_US-kristin-medium.tar.bz2",
            expectedSha256 = "c2206f572df2956c50b1ae3367eebce3853c663e890cba8048cd62b1e4dbe6c7",
            approximateSizeMb = 64,
            isArchiveTarBz2 = true,
            hardwareAccelNote = "Piper VITS ONNX Medium (en-US)",
            unlocksSummary = "ADDS VOICE: Piper VITS // Kristin Medium (US Storyteller) to the Voice selector.",
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
            subtitle = "5-Second Zero-Shot Mic Reference Recorder & Cloner Workshop",
            description = "Dedicated studio OS app for recording & registering 5-second zero-shot voices with Pocket TTS (ROM-06) or ZipVoice (ROM-07).",
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
        )
    )
}
