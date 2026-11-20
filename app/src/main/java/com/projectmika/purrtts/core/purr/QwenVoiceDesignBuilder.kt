package com.projectmika.purrtts.core.purr

import kotlinx.serialization.Serializable

/**
 * ✨ QWEN3-TTS 1.7B VOICEDESIGN STRUCTURED PROMPT BUILDER (TOOL-06) ✨
 *
 * Models the full acoustic, demographic, prosodic, accent, and environment prompt space of
 * `Qwen/Qwen3-TTS-12Hz-1.7B-VoiceDesign` (`SUB-04B`), compiling user choices into fluent
 * natural-language `instruct` descriptions.
 *
 * PURE-JVM — unit tested in `QwenVoiceDesignBuilderTest`.
 */

@Serializable
enum class VoiceDesignGender(val label: String, val promptPhrase: String) {
    FEMALE("Female", "female"),
    MALE("Male", "male"),
    ANDROGYNOUS("Androgynous", "androgynous-sounding")
}

@Serializable
enum class VoiceDesignAge(val label: String, val promptPhrase: String) {
    TEEN("Late Teen (17-19)", "in their late teens"),
    YOUNG_ADULT("Young Adult (20s)", "in their mid-20s"),
    ADULT("Adult (30s)", "in their 30s"),
    MIDDLE_AGED("Mature (40s-50s)", "middle-aged"),
    ELDERLY("Elder (70s+)", "elderly")
}

@Serializable
enum class VoiceDesignPitch(val label: String, val promptPhrase: String) {
    DEEP_BASS("Deep Bass", "with a deep, resonant bass register"),
    LOW_BARITONE("Low / Alto", "with a rich low-pitched chest register"),
    MID_NATURAL("Balanced Mid", "with a clear, natural mid-range pitch"),
    BRIGHT_UPPER_MID("Upper-Mid / Agile", "with an agile, lifted upper-mid pitch"),
    HIGH_SOPRANO("High Soprano", "with a bright, high-pitched soprano register")
}

@Serializable
enum class VoiceDesignTimbre(val label: String, val promptPhrase: String) {
    WARM_VELVETY("Warm & Velvety", "warm and velvety"),
    CRISP_CLEAR("Crisp & Clear", "crisp, articulate, and clear"),
    BREATHY_AIRY("Breathy & Airy", "soft, breathy, and airy"),
    RASPY_GRAVELLY("Raspy & Gravelly", "textured with subtle gravelly rasp"),
    SILKY_SULTRY("Silky & Sultry", "smooth, silky, and intimate"),
    BRIGHT_SPARKLY("Bright & Lively", "bright, vibrant, and expressive"),
    HOARSE_CROAKY("Hoarse & Weathered", "hoarse, slightly croaky, and weathered"),
    NASAL_QUIRKY("Light & Quirky", "light, slightly nasal, and characterful")
}

@Serializable
enum class VoiceDesignPace(val label: String, val promptPhrase: String) {
    SLOW_DELIBERATE("Slow & Deliberate", "slow, deliberate pacing"),
    CALM_STEADY("Calm & Measured", "calm, measured pacing"),
    NATURAL_FLOWING("Natural & Flowing", "natural conversational pacing with seamless flow"),
    ENERGETIC_BRISK("Brisk & Energetic", "lively, brisk, and energetic rhythm"),
    RAPID_URGENT("Rapid & Urgent", "fast, dynamic, urgent cadence")
}

@Serializable
enum class VoiceDesignDelivery(val label: String, val promptPhrase: String) {
    WARM_CONFIDENT("Warm & Confident", "warm, confident, and inviting tone"),
    PLAYFUL_TEASING("Playful & Teasing", "playful, teasing, and affectionately expressive delivery"),
    SHY_HESITANT("Shy & Delicate", "gentle, slightly shy and hesitant delivery with soft vowel inflections"),
    SERIOUS_AUTHORITATIVE("Commanding & Steady", "authoritative, composed, and commanding delivery"),
    MYSTERIOUS_HUSHED("Hushed & Tense", "hushed, low-energy, mysterious and tense delivery"),
    CHEERFUL_UPBEAT("Cheerful & Bright", "bright, upbeat, cheerful tone radiating warmth"),
    MELANCHOLIC_SOFT("Wistful & Soft", "quiet, wistful, slightly melancholic delivery"),
    DRY_SARCASTIC("Dry & Wry", "dry, deadpan, and subtly sarcastic delivery"),
    GRUMPY_FRUSTRATED("Grumpy & Gruff", "gruff, impatient, and slightly cranky delivery")
}

@Serializable
enum class VoiceDesignAccent(val label: String, val promptPhrase: String) {
    NEUTRAL_AMERICAN("Neutral American", "clean neutral American pronunciation"),
    BRITISH_RP("British RP", "refined British RP articulation"),
    ANIME_EXPRESSIVE("Anime / VN Lead", "expressive light-novel heroine inflection"),
    SOFT_EUROPEAN("Soft Continental", "lightly textured European English cadence"),
    JAPANESE_NATIVE("Japanese Native", "natural Japanese vocal cadence and crisp mora timing"),
    CHINESE_MANDARIN("Mandarin Native", "clear, tonal Mandarin Chinese articulation"),
    KOREAN_NATIVE("Korean Native", "clean, melodic Korean vocal cadence"),
    BROADCAST_CLEAN("Broadcast Diction", "razor-sharp television broadcast diction")
}

@Serializable
enum class VoiceDesignAcoustics(val label: String, val promptPhrase: String) {
    CLOSE_MIC_STUDIO("Close-Mic Studio", "recorded in a quiet close-mic studio with zero room echo"),
    ASMR_WHISPER("Near-Field ASMR", "whisper-close near the condenser capsule picking up delicate breath detail"),
    AUDIOBOOK_BOOTH("Audiobook Booth", "balanced isolation-booth clarity ideal for long-form narration"),
    STAGE_PROJECTED("Projected Stage", "clear open projection with confident vocal presence"),
    NATURAL_ROOM("Natural Room", "relaxed natural near-room acoustic presence")
}

@Serializable
data class VoiceDesignSpec(
    val gender: VoiceDesignGender = VoiceDesignGender.FEMALE,
    val age: VoiceDesignAge = VoiceDesignAge.YOUNG_ADULT,
    val pitch: VoiceDesignPitch = VoiceDesignPitch.MID_NATURAL,
    val timbres: List<VoiceDesignTimbre> = listOf(
        VoiceDesignTimbre.WARM_VELVETY,
        VoiceDesignTimbre.CRISP_CLEAR
    ),
    val pace: VoiceDesignPace = VoiceDesignPace.NATURAL_FLOWING,
    val delivery: VoiceDesignDelivery = VoiceDesignDelivery.PLAYFUL_TEASING,
    val accent: VoiceDesignAccent = VoiceDesignAccent.NEUTRAL_AMERICAN,
    val acoustics: VoiceDesignAcoustics = VoiceDesignAcoustics.CLOSE_MIC_STUDIO,
    val personaRole: String = "",
    val customNuance: String = ""
)

@Serializable
data class SavedVoiceDesign(
    val id: String,
    val name: String,
    val prompt: String,
    val spec: VoiceDesignSpec,
    val createdAtEpochMs: Long = 0L
)

object QwenVoiceDesignBuilder {

    fun composePrompt(spec: VoiceDesignSpec): String {
        val cleanTimbres = spec.timbres.distinct().take(3)
        val timbrePhrase = when (cleanTimbres.size) {
            0 -> "clear, natural"
            1 -> cleanTimbres[0].promptPhrase
            2 -> "${cleanTimbres[0].promptPhrase} and ${cleanTimbres[1].promptPhrase}"
            else -> "${cleanTimbres[0].promptPhrase}, ${cleanTimbres[1].promptPhrase}, and ${cleanTimbres[2].promptPhrase}"
        }

        val rolePrefix = spec.personaRole.trim()
        val opener = if (rolePrefix.isNotEmpty()) {
            "A ${spec.gender.promptPhrase} voice ${spec.age.promptPhrase} performing as $rolePrefix, ${spec.pitch.promptPhrase}."
        } else {
            "A ${spec.gender.promptPhrase} voice ${spec.age.promptPhrase}, ${spec.pitch.promptPhrase}."
        }

        val middle = "Vocal timbre is $timbrePhrase, using ${spec.accent.promptPhrase}, ${spec.pace.promptPhrase}, and a ${spec.delivery.promptPhrase}."
        val tail = "Sound profile: ${spec.acoustics.promptPhrase}."
        val extra = spec.customNuance.trim()

        return buildString {
            append(opener)
            append(' ')
            append(middle)
            append(' ')
            append(tail)
            if (extra.isNotEmpty()) {
                append(' ')
                append(extra.trimEnd('.', ' '))
                append('.')
            }
        }
    }

    val CURATED_PRESETS: List<Pair<String, VoiceDesignSpec>> = listOf(
        "MIKA Companion" to VoiceDesignSpec(
            gender = VoiceDesignGender.FEMALE,
            age = VoiceDesignAge.YOUNG_ADULT,
            pitch = VoiceDesignPitch.BRIGHT_UPPER_MID,
            timbres = listOf(VoiceDesignTimbre.WARM_VELVETY, VoiceDesignTimbre.BRIGHT_SPARKLY),
            pace = VoiceDesignPace.NATURAL_FLOWING,
            delivery = VoiceDesignDelivery.PLAYFUL_TEASING,
            accent = VoiceDesignAccent.ANIME_EXPRESSIVE,
            acoustics = VoiceDesignAcoustics.CLOSE_MIC_STUDIO,
            personaRole = "an affectionate cyberdeck AI companion",
            customNuance = "Subtle smiling warmth on playful phrases"
        ),
        "Audiobook Narrator" to VoiceDesignSpec(
            gender = VoiceDesignGender.FEMALE,
            age = VoiceDesignAge.ADULT,
            pitch = VoiceDesignPitch.MID_NATURAL,
            timbres = listOf(VoiceDesignTimbre.WARM_VELVETY, VoiceDesignTimbre.CRISP_CLEAR),
            pace = VoiceDesignPace.CALM_STEADY,
            delivery = VoiceDesignDelivery.WARM_CONFIDENT,
            accent = VoiceDesignAccent.NEUTRAL_AMERICAN,
            acoustics = VoiceDesignAcoustics.AUDIOBOOK_BOOTH,
            personaRole = "a seasoned fiction audiobook narrator",
            customNuance = "Consistent narrative poise across long passages"
        ),
        "Noir Baritone" to VoiceDesignSpec(
            gender = VoiceDesignGender.MALE,
            age = VoiceDesignAge.MIDDLE_AGED,
            pitch = VoiceDesignPitch.DEEP_BASS,
            timbres = listOf(VoiceDesignTimbre.RASPY_GRAVELLY, VoiceDesignTimbre.WARM_VELVETY),
            pace = VoiceDesignPace.SLOW_DELIBERATE,
            delivery = VoiceDesignDelivery.SERIOUS_AUTHORITATIVE,
            accent = VoiceDesignAccent.NEUTRAL_AMERICAN,
            acoustics = VoiceDesignAcoustics.CLOSE_MIC_STUDIO,
            personaRole = "a world-weary noir detective",
            customNuance = "Weighted low resonance at sentence endings"
        ),
        "Midnight ASMR" to VoiceDesignSpec(
            gender = VoiceDesignGender.FEMALE,
            age = VoiceDesignAge.YOUNG_ADULT,
            pitch = VoiceDesignPitch.MID_NATURAL,
            timbres = listOf(VoiceDesignTimbre.BREATHY_AIRY, VoiceDesignTimbre.SILKY_SULTRY),
            pace = VoiceDesignPace.SLOW_DELIBERATE,
            delivery = VoiceDesignDelivery.MYSTERIOUS_HUSHED,
            accent = VoiceDesignAccent.NEUTRAL_AMERICAN,
            acoustics = VoiceDesignAcoustics.ASMR_WHISPER,
            personaRole = "a soothing late-night whisper host",
            customNuance = "Soft unhurried breath releases between clauses"
        ),
        "Shy VN Lead" to VoiceDesignSpec(
            gender = VoiceDesignGender.FEMALE,
            age = VoiceDesignAge.TEEN,
            pitch = VoiceDesignPitch.BRIGHT_UPPER_MID,
            timbres = listOf(VoiceDesignTimbre.BREATHY_AIRY, VoiceDesignTimbre.CRISP_CLEAR),
            pace = VoiceDesignPace.CALM_STEADY,
            delivery = VoiceDesignDelivery.SHY_HESITANT,
            accent = VoiceDesignAccent.ANIME_EXPRESSIVE,
            acoustics = VoiceDesignAcoustics.CLOSE_MIC_STUDIO,
            personaRole = "a tender, slightly flustered visual novel protagonist"
        ),
        "Tavern Elder" to VoiceDesignSpec(
            gender = VoiceDesignGender.MALE,
            age = VoiceDesignAge.ELDERLY,
            pitch = VoiceDesignPitch.LOW_BARITONE,
            timbres = listOf(VoiceDesignTimbre.HOARSE_CROAKY, VoiceDesignTimbre.RASPY_GRAVELLY),
            pace = VoiceDesignPace.SLOW_DELIBERATE,
            delivery = VoiceDesignDelivery.GRUMPY_FRUSTRATED,
            accent = VoiceDesignAccent.BRITISH_RP,
            acoustics = VoiceDesignAcoustics.NATURAL_ROOM,
            personaRole = "a crusty old guildmaster"
        ),
        "News Anchor" to VoiceDesignSpec(
            gender = VoiceDesignGender.FEMALE,
            age = VoiceDesignAge.ADULT,
            pitch = VoiceDesignPitch.BRIGHT_UPPER_MID,
            timbres = listOf(VoiceDesignTimbre.CRISP_CLEAR, VoiceDesignTimbre.BRIGHT_SPARKLY),
            pace = VoiceDesignPace.ENERGETIC_BRISK,
            delivery = VoiceDesignDelivery.CHEERFUL_UPBEAT,
            accent = VoiceDesignAccent.BROADCAST_CLEAN,
            acoustics = VoiceDesignAcoustics.STAGE_PROJECTED,
            personaRole = "a flagship evening broadcast anchor"
        ),
        "Cool Cyber AI" to VoiceDesignSpec(
            gender = VoiceDesignGender.ANDROGYNOUS,
            age = VoiceDesignAge.YOUNG_ADULT,
            pitch = VoiceDesignPitch.MID_NATURAL,
            timbres = listOf(VoiceDesignTimbre.CRISP_CLEAR, VoiceDesignTimbre.SILKY_SULTRY),
            pace = VoiceDesignPace.CALM_STEADY,
            delivery = VoiceDesignDelivery.DRY_SARCASTIC,
            accent = VoiceDesignAccent.BRITISH_RP,
            acoustics = VoiceDesignAcoustics.CLOSE_MIC_STUDIO,
            personaRole = "a detached orbital ship intelligence"
        )
    )
}
