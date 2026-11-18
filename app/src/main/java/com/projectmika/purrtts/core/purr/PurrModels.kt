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
    SUPERTONIC_3("Supertonic 3"),
    POCKET_TTS("Pocket TTS (Clone)"),
    ZIPVOICE("ZipVoice (Clone)"),
    PIPER("Piper VITS")
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
enum class AcousticEffectPreset(val label: String) {
    NONE("CLEAN_STUDIO"),
    WARM_STUDIO("WARM_LOFI"),
    ETHEREAL("ETHEREAL_REVERB"),
    ANGELIC("ANGELIC_SHIMMER"),
    RADIO("CYBER_RADIO"),
    IDOL("STAGE_IDOL")
}

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
    val sliceAtCommas: Boolean = true
)

@Serializable
data class SentimentCurveConfig(
    val enabled: Boolean = true,
    val toneOverride: ArchetypeTone = ArchetypeTone.AUTO,
    val joyRateDelta: Float = 0.04f,
    val joyPitchDelta: Float = 0.06f,
    val loveRateDelta: Float = -0.05f,
    val lovePitchDelta: Float = 0.03f,
    val angerRateDelta: Float = 0.05f,
    val angerPitchDelta: Float = 0.04f,
    val sadnessRateDelta: Float = -0.08f,
    val sadnessPitchDelta: Float = -0.05f,
    val flusteredRateDelta: Float = 0.06f,
    val flusteredPitchDelta: Float = 0.08f,
    val stutterIntensity: Float = 0.15f,
    val vowelStretchIntensity: Float = 0.15f
)

@Serializable
data class DspConfig(
    val warmthEqDb: Float = 2.5f,
    val presenceEqDb: Float = 1.5f,
    val reverbWetMix: Float = 0.08f,
    val effectPreset: AcousticEffectPreset = AcousticEffectPreset.NONE,
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
    val characterTags: List<String> = listOf("cute", "energetic"),
    val affectionScore: Int = 75,
    val whitelist: Set<String> = emptySet(),
    val blacklist: Set<String> = emptySet(),
    val customVoiceTags: Map<String, Set<String>> = emptyMap(),
    val mikaStopConfig: MikaStopConfig = MikaStopConfig(),
    val sentimentConfig: SentimentCurveConfig = SentimentCurveConfig(),
    val dspConfig: DspConfig = DspConfig(),
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
    val isCloned: Boolean = false
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
    val isBuiltInOsCartridge: Boolean = false
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
data class PurrForgeState(
    val activeProfileId: String = "gachaswipe_default",
    val profiles: List<PurrProfile> = PurrDefaultPresets.builtInProfiles(),
    val customPhoneticRules: List<PhoneticRule> = PurrDefaultPresets.defaultCustomRules(),
    val clonedVoices: List<ClonedVoiceProfile> = emptyList(),
    val modelStates: Map<String, ModelBundleState> = mapOf(
        "rom_core_os" to ModelBundleState(
            specId = "rom_core_os",
            state = DownloadState.INSTALLED,
            sha256Verified = true,
            localPath = "android://system_tts"
        )
    ),
    val slottedCartridges: Set<String> = setOf("rom_core_os"),
    val hasMigratedCoreOsCartridge: Boolean = true
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

        val kokoroLiteRt = kokoroVoices(
            cartridgeId = "rom_kokoro_litert",
            backend = BackendType.LITERT_NPU_GPU,
            idPrefix = "litert:",
            displaySuffix = " [NPU/GPU]"
        )
        val kokoroNpuG2p = kokoroVoices(
            cartridgeId = "rom_kokoro_npu_g2p",
            backend = BackendType.LITERT_NPU_GPU,
            idPrefix = "npu:",
            displaySuffix = " [Hexagon NPU]"
        )
        val kokoroSherpa = kokoroVoices("rom_kokoro_sherpa", BackendType.SHERPA_ONNX)

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
                displayName = "Piper VITS // Lessac High",
                engine = BackendType.SHERPA_ONNX,
                cartridgeId = "rom_piper",
                sherpaFamily = SherpaModelFamily.PIPER,
                gender = VoiceGender.FEMALE,
                languageCode = "en-US",
                speakerIndex = 0,
                nativeTags = listOf("female", "american", "calm", "narrator", "audiobook")
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

        return coreOsVoices + kokoroLiteRt + kokoroNpuG2p + kokoroSherpa + sherpaSpecialized + proxyVoices
    }

    /**
     * 6 Intuitive Vibe Presets for Tab 2 (`> PURR ENGINE`) inspired by GachaSwipe's presets.
     */
    fun builtInProfiles(): List<PurrProfile> = listOf(
        PurrProfile(
            id = "gachaswipe_default",
            name = "GachaSwipe Default",
            badgeTag = "★ MIKA PICK",
            summary = "Balanced slang expansion, MIKASTOP breaths & expressive sentiment acting.",
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
            mikaStopConfig = MikaStopConfig(450, 320, 280, 140, 200, sliceAtCommas = true),
            sentimentConfig = SentimentCurveConfig(
                enabled = true,
                toneOverride = ArchetypeTone.AUTO,
                stutterIntensity = 0.12f,
                vowelStretchIntensity = 0.10f
            ),
            dspConfig = DspConfig(
                warmthEqDb = 2.5f,
                presenceEqDb = 1.5f,
                reverbWetMix = 0.08f,
                effectPreset = AcousticEffectPreset.WARM_STUDIO,
                normalizePeak = true
            )
        ),
        PurrProfile(
            id = "light_novel_reader",
            name = "Audiobook / Light Novel",
            badgeTag = "READER",
            summary = "Clean book prose, dual-voice dialogue split ready & steady breath pauses.",
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
            mikaStopConfig = MikaStopConfig(520, 360, 340, 170, 220, sliceAtCommas = true),
            sentimentConfig = SentimentCurveConfig(
                enabled = true,
                toneOverride = ArchetypeTone.AUTO,
                stutterIntensity = 0.0f,
                vowelStretchIntensity = 0.0f
            ),
            dspConfig = DspConfig(
                warmthEqDb = 3.0f,
                presenceEqDb = 1.0f,
                reverbWetMix = 0.04f,
                effectPreset = AcousticEffectPreset.WARM_STUDIO,
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
            basePitch = 1.05f,
            enableSlangAndHonorifics = true,
            enableMikaStopPauses = true,
            enableDsp = true,
            mikaStopConfig = MikaStopConfig(380, 260, 220, 110, 160, sliceAtCommas = true),
            sentimentConfig = SentimentCurveConfig(
                enabled = true,
                toneOverride = ArchetypeTone.TSUNDERE_LOVE,
                stutterIntensity = 0.20f,
                vowelStretchIntensity = 0.05f
            ),
            dspConfig = DspConfig(
                warmthEqDb = 1.5f,
                presenceEqDb = 2.8f,
                reverbWetMix = 0.05f,
                effectPreset = AcousticEffectPreset.NONE,
                normalizePeak = true
            )
        ),
        PurrProfile(
            id = "yandere_obsessive",
            name = "Yandere / Obsessive",
            badgeTag = "DEVOTED",
            summary = "Slow sultry breath pauses, subtle vowel stretch & intimate warm reverb.",
            selectedCartridgeId = "rom_core_os",
            preferredBackend = BackendType.CORE_OS,
            primaryVoiceId = "core_os_auto",
            dialogueVoiceId = "af_bella",
            enableDualVoiceSplit = false,
            actionHandlingMode = ActionHandlingMode.NARRATE_SOFT,
            narrationVolumeMultiplier = 0.72f,
            narrationRateMultiplier = 0.90f,
            baseSpeed = 0.93f,
            basePitch = 0.96f,
            enableSlangAndHonorifics = true,
            enableMikaStopPauses = true,
            enableDsp = true,
            mikaStopConfig = MikaStopConfig(620, 440, 360, 200, 260, sliceAtCommas = true),
            sentimentConfig = SentimentCurveConfig(
                enabled = true,
                toneOverride = ArchetypeTone.YANDERE_LOVE,
                stutterIntensity = 0.05f,
                vowelStretchIntensity = 0.22f
            ),
            dspConfig = DspConfig(
                warmthEqDb = 4.0f,
                presenceEqDb = 0.8f,
                reverbWetMix = 0.16f,
                effectPreset = AcousticEffectPreset.ETHEREAL,
                normalizePeak = true
            )
        ),
        PurrProfile(
            id = "shy_dandere",
            name = "Shy / Dandere",
            badgeTag = "SOFT",
            summary = "Gentle hesitant stutters, airy EQ & tender soft-spoken pauses.",
            selectedCartridgeId = "rom_core_os",
            preferredBackend = BackendType.CORE_OS,
            primaryVoiceId = "core_os_auto",
            dialogueVoiceId = "af_nicole",
            enableDualVoiceSplit = false,
            actionHandlingMode = ActionHandlingMode.NARRATE_SOFT,
            baseSpeed = 0.98f,
            basePitch = 1.04f,
            enableSlangAndHonorifics = true,
            enableMikaStopPauses = true,
            enableDsp = true,
            mikaStopConfig = MikaStopConfig(540, 380, 300, 170, 220, sliceAtCommas = true),
            sentimentConfig = SentimentCurveConfig(
                enabled = true,
                toneOverride = ArchetypeTone.SHY_LOVE,
                stutterIntensity = 0.25f,
                vowelStretchIntensity = 0.08f
            ),
            dspConfig = DspConfig(
                warmthEqDb = 2.0f,
                presenceEqDb = 2.5f,
                reverbWetMix = 0.10f,
                effectPreset = AcousticEffectPreset.ANGELIC,
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
            mikaStopConfig = MikaStopConfig(220, 150, 160, 80, 100, sliceAtCommas = false),
            sentimentConfig = SentimentCurveConfig(
                enabled = false,
                toneOverride = ArchetypeTone.NORMAL,
                stutterIntensity = 0.0f,
                vowelStretchIntensity = 0.0f
            ),
            dspConfig = DspConfig(
                warmthEqDb = 0f,
                presenceEqDb = 1.5f,
                reverbWetMix = 0.0f,
                effectPreset = AcousticEffectPreset.NONE,
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
     * 8 Physical ROM Cartridge Bundles in Tab 3 (`> CARTRIDGE BAY`).
     * ROM-01 (System Speech) is pre-installed and ejectable to disable OS voices.
     * ROM-02..ROM-08: Download -> Slam Cartridge In -> Voices appear ready in Tab 1 (`> VOICE & ENGINE`).
     */
    fun modelCatalog(): List<ModelBundleSpec> = listOf(
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
            isBuiltInOsCartridge = true
        ),
        ModelBundleSpec(
            id = "rom_kokoro_litert",
            romCode = "ROM-02",
            badge = "NPU_GPU_FP16",
            accentHex = 0xFFFF9100,
            name = "KOKORO_82M_NPU_GPU",
            subtitle = "litert-community/Kokoro-82M • 54 Voices • NPU / Adreno GPU FP16",
            description = "Primary hardware-accelerated Google AI Edge LiteRT-LM bundle (kokoro_int8_gpu_fp16.litertlm) with persistent OpenCL/QNN shader cache & 5-minute VRAM eviction.",
            backend = BackendType.LITERT_NPU_GPU,
            downloadUrl = "https://huggingface.co/litert-community/Kokoro-82M/resolve/main/kokoro_int8_gpu_fp16.litertlm",
            fileName = "kokoro_int8_gpu_fp16.litertlm",
            expectedSha256 = "1ef178b127ec48c737daf17eabb4cea5f6b3ffbc510aced270fe5c267bbeac52",
            approximateSizeMb = 281,
            isArchiveTarBz2 = false,
            hardwareAccelNote = "Adreno GPU FP16 + Hexagon NPU Ready"
        ),
        ModelBundleSpec(
            id = "rom_kokoro_npu_g2p",
            romCode = "ROM-03",
            badge = "HEXAGON_NPU",
            accentHex = 0xFF00FF9D,
            name = "KOKORO_G2P_HEXAGON_NPU",
            subtitle = "litert-community/Kokoro-G2P-en-US • Snapdragon Hexagon NPU / QAIRT",
            description = "Snapdragon Hexagon NPU / QNN HTP accelerated Grapheme-to-Phoneme model (dp_g2p_litert.tflite). Unlocks [NPU ACTIVE] G2P acceleration for all 54 Kokoro voices.",
            backend = BackendType.LITERT_NPU_GPU,
            downloadUrl = "https://huggingface.co/litert-community/Kokoro-G2P-en-US/resolve/main/dp_g2p_litert.tflite",
            fileName = "dp_g2p_litert.tflite",
            expectedSha256 = "f8d6bbd16197b2468f53273de13f971a0e1e8657709d7f4285f2cd1c31f26620",
            approximateSizeMb = 49,
            isArchiveTarBz2 = false,
            hardwareAccelNote = "Snapdragon Hexagon NPU / QNN HTP Delegate"
        ),
        ModelBundleSpec(
            id = "rom_kokoro_sherpa",
            romCode = "ROM-04",
            badge = "54_VOICES",
            accentHex = 0xFF00FF41,
            name = "KOKORO_82M_SHERPA",
            subtitle = "k2-fsa/sherpa-onnx • 54 Multi-Lang Voices • 24kHz INT8 ONNX",
            description = "The flagship GachaSwipe voice pack (af_bella, af_sarah, am_adam, bf_emma & 50 more). Runs completely offline via bundled sherpa-onnx JNI.",
            backend = BackendType.SHERPA_ONNX,
            sherpaFamily = SherpaModelFamily.KOKORO,
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-multi-lang-v1_0.tar.bz2",
            fileName = "kokoro-int8-multi-lang-v1_0.tar.bz2",
            expectedSha256 = "72c347230107ef8a31098ec4d468a605244beef5f1ed00db97efa13518800653",
            approximateSizeMb = 126,
            isArchiveTarBz2 = true,
            hardwareAccelNote = "Bundled sherpa-onnx JNI • ARM64 NEON multi-threaded"
        ),
        ModelBundleSpec(
            id = "rom_supertonic3",
            romCode = "ROM-05",
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
            hardwareAccelNote = "ARM64 NEON INT8 Supertonic 3"
        ),
        ModelBundleSpec(
            id = "rom_piper",
            romCode = "ROM-06",
            badge = "NARRATOR",
            accentHex = 0xFFFFD700,
            name = "PIPER_VITS_LESSAC",
            subtitle = "k2-fsa/sherpa-onnx • Lightweight Crisp Audiobook Narrator",
            description = "Classic rock-solid English VITS narrator voice with minimal RAM usage for long reading sessions.",
            backend = BackendType.SHERPA_ONNX,
            sherpaFamily = SherpaModelFamily.PIPER,
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-lessac-high.tar.bz2",
            fileName = "vits-piper-en_US-lessac-high.tar.bz2",
            expectedSha256 = "8619d204c7005866fe4f420181dfa79622af6a6222389f0b0818d2af31e0db0e",
            approximateSizeMb = 110,
            isArchiveTarBz2 = true,
            hardwareAccelNote = "VITS ONNX 22.05kHz -> 24kHz resampled"
        ),
        ModelBundleSpec(
            id = "rom_pocket_tts",
            romCode = "ROM-07",
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
            hardwareAccelNote = "Pocket TTS Zero-Shot Speaker Encoder"
        ),
        ModelBundleSpec(
            id = "rom_zipvoice",
            romCode = "ROM-08",
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
            hardwareAccelNote = "ONNX Runtime INT8 + Vocos"
        )
    )
}
