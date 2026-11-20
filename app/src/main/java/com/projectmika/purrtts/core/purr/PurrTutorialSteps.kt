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
const val TUTORIAL_VERSION = 2

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
        body = "The CARTRIDGE BAY tab now holds 10 swappable TTS engines. ROM-03: KITTEN_TTS_NANO is the quick win — only 30MB for 8 expressive voices. ROM-02: KOKORO_82M packs 54 neural voices, ROM-09 adds 103 Chinese+English speakers, and ROM-08/ROM-10 are the premium tier: full-precision FP32 weights and the blazing Matcha-TTS flow engine. ROM-01 is your device's built-in speech, free forever. Downloads are SHA-256 verified and resumable."
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
        id = "THE_HEAVY_WEIGHTS",
        iconGlyph = "🏋️",
        colorHex = 0xFFFFD700,
        title = "THE_HEAVY_WEIGHTS",
        body = "Three premium engine cartridges landed in the Bay: ROM-08 KOKORO_82M_FP32 (unquantized studio weights, same 54 voices as ROM-02, 8GB+ RAM), ROM-09 KOKORO_V11_MULTILING (140MB INT8), and ROM-10 MATCHA_ZH_EN — a completely new flow-matching architecture with the Vocos vocoder. The rack is now 10 bays!"
    ),
    PurrWhatsNewEntry(
        id = "THE_THOUSAND_VOICES",
        iconGlyph = "🎙️",
        colorHex = 0xFF00E5FF,
        title = "THE_103_SPEAKERS",
        body = "ROM-09 ships Kokoro v1.1-zh: 103 speakers — af_maple, af_sol and bf_vale in English, plus 100 Chinese voices (55 female zf_*, 45 male zm_*). Fully bilingual synthesis with dual lexicons, all offline."
    ),
    PurrWhatsNewEntry(
        id = "THE_MATCHA_FLOW",
        iconGlyph = "🍵",
        colorHex = 0xFF9146FF,
        title = "THE_MATCHA_FLOW",
        body = "ROM-10 brings Matcha-TTS flow synthesis — a different neural architecture with astonishingly natural prosody and blazing speed (RTF < 1 even on old hardware). The Vocos vocoder downloads as a verified companion asset."
    ),
    PurrWhatsNewEntry(
        id = "THE_HEAVY_BRIDGE",
        iconGlyph = "🌉",
        colorHex = 0xFF00FF41,
        title = "THE_HEAVY_BRIDGE",
        body = "The LAN proxy panel now has HEAVY_MODEL_PRESETS: one tap configures Kokoro-FastAPI, openedai-speech, F5-TTS or the official OpenAI TTS — run CosyVoice, F5-TTS or XTTS-v2 on your PC and let the phone stream. (Qwen TTS stays cloud-WebSocket-only, so no preset for it.)"
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
