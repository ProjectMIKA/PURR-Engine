package com.projectmika.purrtts.core.purr

import kotlinx.serialization.Serializable

/**
 * ✨ MIKA'S P.U.R.R. ENGINE DATA MODELS ✨
 * Shared immutable state contracts between the Android TextToSpeechService,
 * the dual-backend neural synthesizer router, and the Jetpack Compose Forge UI.
 */

@Serializable
enum class BackendType(val displayLabel: String) {
    LITERT_NPU_GPU("LiteRT NPU/GPU"),
    SHERPA_ONNX("Sherpa-ONNX"),
    EXTERNAL_PROXY("Proxy Passthrough")
}

@Serializable
enum class SherpaModelFamily(val label: String) {
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
    PROXY_LINK("[PROXY LINK]"),
    IDLE("[IDLE]")
}

@Serializable
enum class ActionHandlingMode(val label: String, val description: String) {
    STRIP_ACTIONS("STRIP_ACTIONS", "Remove *action blocks* and inject clean MIKASTOP pause"),
    NARRATE_SOFT("NARRATE_SOFT", "Speak *actions* softer and slower as narrator prose"),
    DUAL_VOICE_ROUTING("DUAL_VOICE", "Route \"dialogue\" to secondary Character Voice ID")
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
    val useOpenAiEndpoint: Boolean = true,
    val openAiBaseUrl: String = "http://192.168.1.50:8880/v1/audio/speech",
    val openAiApiKey: String = "",
    val openAiModel: String = "kokoro",
    val openAiVoice: String = "af_bella",
    val fallbackSystemEnginePackage: String = "com.google.android.tts"
)

@Serializable
data class PurrProfile(
    val id: String,
    val name: String,
    val preferredBackend: BackendType = BackendType.LITERT_NPU_GPU,
    val sherpaFamily: SherpaModelFamily = SherpaModelFamily.SUPERTONIC_3,
    val primaryVoiceId: String = "af_bella",
    val dialogueVoiceId: String = "af_sarah",
    val actionHandlingMode: ActionHandlingMode = ActionHandlingMode.NARRATE_SOFT,
    val narrationVolumeMultiplier: Float = 0.72f,
    val narrationRateMultiplier: Float = 0.92f,
    val baseSpeed: Float = 1.0f,
    val basePitch: Float = 1.0f,
    val characterTags: List<String> = listOf("cute", "energetic", "yandere"),
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
    val name: String,
    val subtitle: String,
    val backend: BackendType,
    val sherpaFamily: SherpaModelFamily? = null,
    val downloadUrl: String,
    val fileName: String,
    val expectedSha256: String,
    val approximateSizeMb: Int,
    val isArchiveTarBz2: Boolean = false,
    val hardwareAccelNote: String
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
    val activeProfileId: String = "gachaswipe_rp",
    val profiles: List<PurrProfile> = PurrDefaultPresets.builtInProfiles(),
    val customPhoneticRules: List<PhoneticRule> = PurrDefaultPresets.defaultCustomRules(),
    val clonedVoices: List<ClonedVoiceProfile> = emptyList(),
    val modelStates: Map<String, ModelBundleState> = emptyMap()
)

object PurrDefaultPresets {

    val TTS_TAG_POOL: List<String> = listOf(
        "female", "male", "androgynous", "cute", "mature", "shy", "energetic",
        "calm", "teasing", "yandere", "breathy", "deep", "high-pitched",
        "soft", "loud", "creepy", "tsundere", "ara-ara", "bratty",
        "american", "british", "japanese", "narrator", "audiobook", "warm"
    )

    /**
     * Exact curated tags ported from GachaSwipe's `GACHASWIPE_TTS_PRESETS` (src/data/constants.js),
     * expanded with the full 54-voice Kokoro-82M roster + Sherpa-ONNX & Proxy entries.
     */
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

    /**
     * Full 54-voice Kokoro-82M roster + Sherpa-ONNX (Supertonic 3, Pocket TTS, ZipVoice, Piper) + Proxy.
     */
    fun builtInVoices(): List<VoiceDescriptor> {
        val kokoroIds = listOf(
            // American Female (11)
            "af_heart", "af_alloy", "af_aoede", "af_bella", "af_jessica", "af_kore",
            "af_nicole", "af_nova", "af_river", "af_sarah", "af_sky",
            // American Male (9)
            "am_adam", "am_echo", "am_eric", "am_fenrir", "am_liam", "am_michael",
            "am_onyx", "am_puck", "am_santa",
            // British Female (4) + Male (4)
            "bf_alice", "bf_emma", "bf_isabella", "bf_lily",
            "bm_daniel", "bm_fable", "bm_george", "bm_lewis",
            // Japanese (5)
            "jf_alpha", "jf_gongitsune", "jf_nezumi", "jf_tebukuro", "jm_kumo",
            // Mandarin Chinese (8)
            "zf_xiaobei", "zf_xiaoni", "zf_xiaoxiao", "zf_xiaoyi",
            "zm_yunjian", "zm_yunxi", "zm_yunxia", "zm_yunyang",
            // Spanish (3), French (1), Hindi (4), Italian (2), Portuguese (3) => Total 54 voices
            "ef_dora", "em_alex", "em_santa",
            "ff_siwis",
            "hf_alpha", "hf_beta", "hm_omega", "hm_psi",
            "if_sara", "im_nicola",
            "pf_dora", "pm_alex", "pm_santa"
        )

        val kokoroVoices = kokoroIds.mapIndexed { idx, id ->
            val prefix = id.substringBefore('_')
            val gender = when {
                prefix.endsWith("m") -> VoiceGender.MALE
                else -> VoiceGender.FEMALE
            }
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
            val prettyName = id.split('_').joinToString(" ") { part ->
                part.replaceFirstChar { it.uppercase() }
            }
            val tags = GACHASWIPE_TTS_PRESETS[id] ?: listOf(
                gender.tag,
                when (lang) {
                    "en-US" -> "american"
                    "en-GB" -> "british"
                    "ja-JP" -> "japanese"
                    else -> lang.lowercase()
                },
                "kokoro"
            )
            VoiceDescriptor(
                id = id,
                displayName = "Kokoro // $prettyName",
                engine = BackendType.LITERT_NPU_GPU,
                gender = gender,
                languageCode = lang,
                speakerIndex = idx,
                nativeTags = tags
            )
        }

        val sherpaVoices = listOf(
            VoiceDescriptor(
                id = "supertonic3_mika_en",
                displayName = "Supertonic 3 // MIKA-EN",
                engine = BackendType.SHERPA_ONNX,
                sherpaFamily = SherpaModelFamily.SUPERTONIC_3,
                gender = VoiceGender.FEMALE,
                languageCode = "en-US",
                speakerIndex = 0,
                nativeTags = listOf("female", "american", "cute", "energetic", "teasing", "supertonic")
            ),
            VoiceDescriptor(
                id = "supertonic3_aria_warm",
                displayName = "Supertonic 3 // Aria Warm",
                engine = BackendType.SHERPA_ONNX,
                sherpaFamily = SherpaModelFamily.SUPERTONIC_3,
                gender = VoiceGender.FEMALE,
                languageCode = "en-US",
                speakerIndex = 1,
                nativeTags = listOf("female", "american", "warm", "calm", "narrator", "audiobook")
            ),
            VoiceDescriptor(
                id = "pocket_tts_zero_shot",
                displayName = "Pocket TTS // Zero-Shot Core",
                engine = BackendType.SHERPA_ONNX,
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
                sherpaFamily = SherpaModelFamily.ZIPVOICE,
                gender = VoiceGender.FEMALE,
                languageCode = "en-US",
                speakerIndex = 0,
                nativeTags = listOf("female", "american", "soft", "breathy", "clone")
            ),
            VoiceDescriptor(
                id = "piper_libritts_lessac",
                displayName = "Piper // Lessac High",
                engine = BackendType.SHERPA_ONNX,
                sherpaFamily = SherpaModelFamily.PIPER,
                gender = VoiceGender.FEMALE,
                languageCode = "en-US",
                speakerIndex = 0,
                nativeTags = listOf("female", "american", "calm", "narrator", "audiobook")
            ),
            VoiceDescriptor(
                id = "piper_ryan_deep",
                displayName = "Piper // Ryan Deep",
                engine = BackendType.SHERPA_ONNX,
                sherpaFamily = SherpaModelFamily.PIPER,
                gender = VoiceGender.MALE,
                languageCode = "en-US",
                speakerIndex = 0,
                nativeTags = listOf("male", "american", "deep", "calm", "narrator")
            )
        )

        val proxyVoices = listOf(
            VoiceDescriptor(
                id = "proxy_openai_endpoint",
                displayName = "Proxy // LAN OpenAI TTS",
                engine = BackendType.EXTERNAL_PROXY,
                gender = VoiceGender.ANDROGYNOUS,
                languageCode = "en-US",
                nativeTags = listOf("androgynous", "cloud", "proxy")
            )
        )

        return kokoroVoices + sherpaVoices + proxyVoices
    }

    fun builtInProfiles(): List<PurrProfile> = listOf(
        PurrProfile(
            id = "gachaswipe_rp",
            name = "GachaSwipe RP",
            preferredBackend = BackendType.LITERT_NPU_GPU,
            primaryVoiceId = "af_bella",
            dialogueVoiceId = "af_sarah",
            actionHandlingMode = ActionHandlingMode.DUAL_VOICE_ROUTING,
            narrationVolumeMultiplier = 0.72f,
            narrationRateMultiplier = 0.92f,
            baseSpeed = 1.0f,
            basePitch = 1.02f,
            characterTags = listOf("cute", "energetic", "yandere"),
            affectionScore = 82,
            whitelist = setOf("af_bella", "af_sarah", "af_nicole", "af_sky", "af_heart", "am_adam", "bf_isabella", "supertonic3_mika_en"),
            mikaStopConfig = MikaStopConfig(
                ellipsisPauseMs = 450,
                emDashPauseMs = 320,
                sentencePauseMs = 280,
                commaPauseMs = 140,
                actionStripPauseMs = 200,
                sliceAtCommas = true
            ),
            sentimentConfig = SentimentCurveConfig(
                enabled = true,
                toneOverride = ArchetypeTone.AUTO,
                stutterIntensity = 0.15f,
                vowelStretchIntensity = 0.15f
            ),
            dspConfig = DspConfig(
                warmthEqDb = 2.5f,
                presenceEqDb = 1.8f,
                reverbWetMix = 0.09f,
                effectPreset = AcousticEffectPreset.WARM_STUDIO,
                normalizePeak = true
            )
        ),
        PurrProfile(
            id = "light_novel_reader",
            name = "Light Novel Reader",
            preferredBackend = BackendType.LITERT_NPU_GPU,
            primaryVoiceId = "af_sky",
            dialogueVoiceId = "af_bella",
            actionHandlingMode = ActionHandlingMode.DUAL_VOICE_ROUTING,
            narrationVolumeMultiplier = 0.85f,
            narrationRateMultiplier = 0.96f,
            baseSpeed = 1.04f,
            basePitch = 1.0f,
            characterTags = listOf("narrator", "audiobook", "calm", "warm"),
            affectionScore = 50,
            whitelist = setOf("af_sky", "af_heart", "af_river", "af_bella", "am_michael", "bm_lewis", "piper_libritts_lessac"),
            mikaStopConfig = MikaStopConfig(
                ellipsisPauseMs = 520,
                emDashPauseMs = 360,
                sentencePauseMs = 340,
                commaPauseMs = 170,
                actionStripPauseMs = 240,
                sliceAtCommas = true
            ),
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
            id = "notification_assistant",
            name = "Notification Assistant",
            preferredBackend = BackendType.SHERPA_ONNX,
            sherpaFamily = SherpaModelFamily.SUPERTONIC_3,
            primaryVoiceId = "supertonic3_aria_warm",
            dialogueVoiceId = "supertonic3_mika_en",
            actionHandlingMode = ActionHandlingMode.STRIP_ACTIONS,
            narrationVolumeMultiplier = 1.0f,
            narrationRateMultiplier = 1.0f,
            baseSpeed = 1.12f,
            basePitch = 1.0f,
            characterTags = listOf("calm", "clear", "american"),
            affectionScore = 30,
            whitelist = setOf("supertonic3_aria_warm", "supertonic3_mika_en", "af_kore", "piper_libritts_lessac"),
            mikaStopConfig = MikaStopConfig(
                ellipsisPauseMs = 250,
                emDashPauseMs = 180,
                sentencePauseMs = 180,
                commaPauseMs = 90,
                actionStripPauseMs = 120,
                sliceAtCommas = false
            ),
            sentimentConfig = SentimentCurveConfig(
                enabled = false,
                toneOverride = ArchetypeTone.NORMAL,
                stutterIntensity = 0.0f,
                vowelStretchIntensity = 0.0f
            ),
            dspConfig = DspConfig(
                warmthEqDb = 1.0f,
                presenceEqDb = 3.0f,
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

    fun modelCatalog(): List<ModelBundleSpec> = listOf(
        ModelBundleSpec(
            id = "kokoro_82m_litert",
            name = "Kokoro-82M (INT8 GPU FP16)",
            subtitle = "litert-community/Kokoro-82M • 54 voices • 24kHz mono float32 PCM",
            backend = BackendType.LITERT_NPU_GPU,
            downloadUrl = "https://huggingface.co/litert-community/Kokoro-82M/resolve/main/kokoro_int8_gpu_fp16.litertlm",
            fileName = "kokoro_int8_gpu_fp16.litertlm",
            expectedSha256 = "1ef178b127ec48c737daf17eabb4cea5f6b3ffbc510aced270fe5c267bbeac52",
            approximateSizeMb = 281,
            isArchiveTarBz2 = false,
            hardwareAccelNote = "Adreno GPU FP16 + persistent OpenCL cache"
        ),
        ModelBundleSpec(
            id = "kokoro_g2p_en_us",
            name = "Kokoro-G2P-en-US (Hexagon NPU)",
            subtitle = "litert-community/Kokoro-G2P-en-US • QAIRT accelerated grapheme-to-phoneme",
            backend = BackendType.LITERT_NPU_GPU,
            downloadUrl = "https://huggingface.co/litert-community/Kokoro-G2P-en-US/resolve/main/dp_g2p_litert.tflite",
            fileName = "dp_g2p_litert.tflite",
            expectedSha256 = "f8d6bbd16197b2468f53273de13f971a0e1e8657709d7f4285f2cd1c31f26620",
            approximateSizeMb = 49,
            isArchiveTarBz2 = false,
            hardwareAccelNote = "Snapdragon Hexagon NPU / QNN HTP delegate"
        ),
        ModelBundleSpec(
            id = "sherpa_supertonic_3",
            name = "Supertonic 3 (sherpa-onnx)",
            subtitle = "k2-fsa/sherpa-onnx • Ultra-low latency CPU NEON neural TTS",
            backend = BackendType.SHERPA_ONNX,
            sherpaFamily = SherpaModelFamily.SUPERTONIC_3,
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2",
            fileName = "sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2",
            expectedSha256 = "82fa96f91c4ef8abaae3a14a3f4153facf88bed821d1f7331cec2700f432c427",
            approximateSizeMb = 123,
            isArchiveTarBz2 = true,
            hardwareAccelNote = "ARM64 NEON multi-threaded int8"
        ),
        ModelBundleSpec(
            id = "sherpa_pocket_tts",
            name = "Pocket TTS (Zero-Shot Cloner)",
            subtitle = "k2-fsa/sherpa-onnx • 5-second reference audio voice cloning",
            backend = BackendType.SHERPA_ONNX,
            sherpaFamily = SherpaModelFamily.POCKET_TTS,
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/sherpa-onnx-pocket-tts-int8-2026-01-26.tar.bz2",
            fileName = "sherpa-onnx-pocket-tts-int8-2026-01-26.tar.bz2",
            expectedSha256 = "2f3b88823cbbb9bf0b2477ec8ae7b3fec417b3a87b6bb5f256dba66f2ad967cb",
            approximateSizeMb = 94,
            isArchiveTarBz2 = true,
            hardwareAccelNote = "Zero-shot speaker encoder + CPU NEON"
        ),
        ModelBundleSpec(
            id = "sherpa_zipvoice",
            name = "ZipVoice (Flow-Matching Clone)",
            subtitle = "k2-fsa/sherpa-onnx • High-fidelity flow-matching zero-shot TTS",
            backend = BackendType.SHERPA_ONNX,
            sherpaFamily = SherpaModelFamily.ZIPVOICE,
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/sherpa-onnx-zipvoice-distill-int8-zh-en-emilia.tar.bz2",
            fileName = "sherpa-onnx-zipvoice-distill-int8-zh-en-emilia.tar.bz2",
            expectedSha256 = "77219c8b40f4ee8d73a7f902305ff6c1128ef9b54461c41b4ca6ed890b6c2803",
            approximateSizeMb = 104,
            isArchiveTarBz2 = true,
            hardwareAccelNote = "ONNX Runtime INT8 + Vocos vocoder"
        ),
        ModelBundleSpec(
            id = "sherpa_piper_lessac",
            name = "Piper VITS (en_US-lessac-high)",
            subtitle = "k2-fsa/sherpa-onnx • Lightweight classic VITS + espeak-ng",
            backend = BackendType.SHERPA_ONNX,
            sherpaFamily = SherpaModelFamily.PIPER,
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-lessac-high.tar.bz2",
            fileName = "vits-piper-en_US-lessac-high.tar.bz2",
            expectedSha256 = "8619d204c7005866fe4f420181dfa79622af6a6222389f0b0818d2af31e0db0e",
            approximateSizeMb = 110,
            isArchiveTarBz2 = true,
            hardwareAccelNote = "VITS ONNX 22.05kHz -> 24kHz resampled"
        )
    )
}
