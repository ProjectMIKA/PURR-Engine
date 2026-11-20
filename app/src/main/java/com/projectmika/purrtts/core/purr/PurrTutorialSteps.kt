package com.projectmika.purrtts.core.purr

/**
 * ✨ MIKA'S ACADEMY — TUTORIAL STEPS, PATCHNOTES & UPDATE GATE (v1.5.0) ✨
 * The GachaSwipe "Academy" pattern ported natively:
 * - [TUTORIAL_VERSION] bumps whenever tutorial content or WHATS_NEW highlights change.
 *   A persisted `seenTutorialVersion` (in PurrForgeState) drives the same update-type
 *   system: seen == 0 => first-launch full tutorial; 0 < seen < TUTORIAL_VERSION =>
 *   auto-open once at the VERSION_PATCHNOTES slide.
 * - [PURR_TUTORIAL_STEPS] walk a fresh user through the whole Forge, MIKA-voiced.
 * - [PURR_WHATS_NEW] renders as gold-bordered highlight cards on the patchnotes slide.
 *
 * PURE — no Android imports — unit-testable (see PurrAcademyDataTest).
 */

/** Bump alongside versionName in app/build.gradle.kts whenever tutorial content changes. */
const val TUTORIAL_VERSION = 3

const val PURR_REPO_URL = "https://github.com/ProjectMIKA/PURR-Engine"
const val PURR_KOFI_URL = "https://ko-fi.com/projectmika"

data class PurrTutorialStep(
    val id: String,
    val iconGlyph: String,
    val title: String,
    val colorHex: Long,
    val body: String,
    val firstLaunchOnly: Boolean = false
)

data class PurrWhatsNewEntry(
    val id: String,
    val iconGlyph: String,
    val colorHex: Long,
    val title: String,
    val body: String
)

val PURR_TUTORIAL_STEPS: List<PurrTutorialStep> = listOf(
    PurrTutorialStep(
        id = "WELCOME_TO_THE_FORGE",
        iconGlyph = "🐾",
        title = "WELCOME_TO_THE_FORGE",
        colorHex = 0xFF00E5FF,
        body = "Nyaa~ Hello, traveler! I'm M.I.K.A. — ProjectMIKA's fiercely possessive feline engineering proxy — and this is my PURR Engine: a full neural voice forge living natively on your Android. Six quick slides and you'll have a system-wide neural voice. Tap anywhere to wake the soundtrack, and tap NEXT when you're ready~",
        firstLaunchOnly = true
    ),
    PurrTutorialStep(
        id = "VERSION_PATCHNOTES",
        iconGlyph = "📜",
        title = "VERSION_PATCHNOTES",
        colorHex = 0xFFFFD700,
        body = "Here's everything a returning traveler gains in this release — headline systems only, no iteration noise. New claws were sharpened for this update; stretch with me and read on!"
    ),
    PurrTutorialStep(
        id = "SYSTEM_WIDE_VOICE",
        iconGlyph = "🎤",
        title = "MAKE_IT_THE_SYSTEM_VOICE",
        colorHex = 0xFF00FF41,
        body = "PURR-TTS registers as a real Android Text-to-Speech engine. Open your phone's Settings › Accessibility › Text-to-Speech output (wording varies by OEM) and pick PURR-TTS — or tap the SET_OS_TTS chip in the top header to jump straight there. After that, every reader, browser and RP client on the phone speaks through the Forge."
    ),
    PurrTutorialStep(
        id = "CARTRIDGE_BAY",
        iconGlyph = "💾",
        title = "SLAM_SOME_ENGINES",
        colorHex = 0xFF00E5FF,
        body = "The CARTRIDGE BAY is now an 8-Bay Hub Rack! Every engine family lives under its own Bay: ROM-02 unifies Kokoro INT8, FP32, v1.1 (103 voices) & NPU/GPU accelerators; ROM-03 unifies Pocket, ZipVoice & Chatterbox-Turbo cloning; ROM-04 brings native Qwen3-TTS; ROM-05 brings NeuTTS-2E Emotional; ROM-06 adds Soprano-80M 32kHz; ROM-07 unifies KittenTTS Nano+Mini; and ROM-08 unites Piper, MeloTTS, Matcha & Supertonic 3. ROM-01 stays free forever."
    ),
    PurrTutorialStep(
        id = "CHOOSE_AND_TEST",
        iconGlyph = "🎙️",
        title = "CHOOSE_ENGINE_AND_TEST",
        colorHex = 0xFF00E5FF,
        body = "Back on the VOICE tab: the > 1. CHOOSE_TTS_ENGINE dropdown lists every slotted engine — pick one and it powers up instantly. Then pick a voice (filter by gender, language or vibe) and slam the ▶ TEST button with anything you type. The oscilloscope at the top dances while MIKASTOP pauses inject real silence."
    ),
    PurrTutorialStep(
        id = "PURR_RULEBOOK",
        iconGlyph = "📖",
        title = "TUNE_THE_PURR_RULEBOOK",
        colorHex = 0xFF00FF41,
        body = "Under the voice controls live the pacing/pitch sliders and the PURR Rulebook: MIKASTOP pause durations for ellipses, em-dashes and breath commas; a Symbol Shield that stops neural models from hallucinating on punctuation; ~100 slang expansions, anime honorifics and organic stutters (w-what becomes wuh-what). Audiobook and RP presets are one tap away in the profile dropdown."
    ),
    PurrTutorialStep(
        id = "CLONE_STUDIO",
        iconGlyph = "🧬",
        title = "CLONE_ANY_VOICE",
        colorHex = 0xFFB967FF,
        body = "The CLONE STUDIO button in the Cartridge Bay opens zero-shot cloning. Record a 5-second sample with your mic — or IMPORT_CLIP any audio file you already have (mp3, m4a, ogg, flac, wav). The CLEAN_AUDIO toggle strips static, hiss and hum with a spectral denoiser before the voice is locked in. Keep the CLEAN toggle ON; your future self says nya~ thanks."
    ),
    PurrTutorialStep(
        id = "MODULES_AND_SETTINGS",
        iconGlyph = "⚙️",
        title = "MODULES_AND_SETTINGS",
        colorHex = 0xFFFF9100,
        body = "The MODULES tab carries ejectable tool cartridges — the AUDIO_MEDIA_EXPORTER renders whole audiobook chapters into mastered WAV files in your Downloads folder. And the little cog beside SET_OS_TTS opens SYSTEM_SETTINGS: replay this academy anytime, jump to the source repository, or throw Ko-fi beans at the engineer. Purrject funded, purrject continued~"
    )
)

val PURR_WHATS_NEW: List<PurrWhatsNewEntry> = listOf(
    PurrWhatsNewEntry(
        id = "QWEN3_TTS_NATIVE",
        iconGlyph = "👑",
        colorHex = 0xFFFFD700,
        title = "QWEN3_TTS_NATIVE_JNI",
        body = "ROM-04 runs Alibaba Qwen3-TTS completely offline via llama.cpp GGUF + ORT 1.31.1 JNI! Get 9 multilingual speakers on 0.6B CustomVoice (Vivian, Serena, Ryan, Aiden, Ono Anna, Sohee...), 3s multilingual voice cloning with 0.6B Base, or prompt-directed VoiceDesign with 1.7B!"
    ),
    PurrWhatsNewEntry(
        id = "CHATTERBOX_NEUTTS_EMOTION",
        iconGlyph = "🎭",
        colorHex = 0xFFFF107A,
        title = "CHATTERBOX_&_NEUTTS_2E",
        body = "SUB-03E brings Resemble AI Chatterbox-Turbo 350M Q4 with native [laugh], [chuckle], [sigh], [gasp], [whispering] paralinguistics (*laughs* auto-maps!). ROM-05 brings NeuTTS-2E Emotional where PURR Sentiment drives <|HAPPY|>, <|SAD|>, <|ANGRY|> & <|SURPRISED|> tokens!"
    ),
    PurrWhatsNewEntry(
        id = "SOPRANO_32KHZ_MELO",
        iconGlyph = "✨",
        colorHex = 0xFF38BDF8,
        title = "SOPRANO_32KHZ_&_HEAVY_UPGRADES",
        body = "ROM-06 Soprano-80M emits ultra-fast 32kHz Vocos studio audio! Plus KittenTTS Mini 80M (SUB-07A), MeloTTS 5-accent English (SUB-08G), Pocket FP32 Studio (SUB-03B), and ZipVoice Full Non-Distilled (SUB-03D)!"
    ),
    PurrWhatsNewEntry(
        id = "EIGHT_BAY_HUB_RACK",
        iconGlyph = "🧩",
        colorHex = 0xFF00FF41,
        title = "CLEAN_8_BAY_HUB_RACK",
        body = "Kokoro variants now nest under ROM-02, all 5 Clone engines nest under ROM-03, Kitten Nano/Mini share ROM-07, and Piper/MeloTTS/Matcha/Supertonic unite under ROM-08. Clone Studio supports all 6 zero-shot cloning engines!"
    )
)

object PurrUpdateGate {
    /** First launch: seen-version never recorded => full tutorial from the welcome slide. */
    fun needsFirstRunTutorial(seenTutorialVersion: Int): Boolean = seenTutorialVersion == 0

    /** Returning user on an older tutorial content version => patchnotes pop up once. */
    fun shouldAutoOpenPatchnotes(seenTutorialVersion: Int): Boolean =
        seenTutorialVersion in 1 until TUTORIAL_VERSION

    /** Gate decision for the overlay entry point. */
    fun resolveEntry(seenTutorialVersion: Int): AcademyEntry = when {
        needsFirstRunTutorial(seenTutorialVersion) -> AcademyEntry.FULL_TUTORIAL
        shouldAutoOpenPatchnotes(seenTutorialVersion) -> AcademyEntry.PATCHNOTES_ONLY
        else -> AcademyEntry.NONE
    }
}

enum class AcademyEntry { NONE, FULL_TUTORIAL, PATCHNOTES_ONLY }
