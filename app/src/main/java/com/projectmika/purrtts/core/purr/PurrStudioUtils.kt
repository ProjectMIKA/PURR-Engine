package com.projectmika.purrtts.core.purr

import java.util.Locale

/**
 * ✨ PURR STUDIO SUITE UTILITIES (v1.8.8) ✨
 * Pure-Kotlin helpers backing `TOOL-08` (Multi-Voice Script Studio), `TOOL-09`
 * (Voice A/B Benchmark & Phonetic Lab), and `TOOL-10` (SRT Subtitle & Batch Studio).
 */

data class PurrScriptLine(
    val speakerTag: String,
    val text: String,
    val pauseAfterMs: Int = 220
)

data class PurrScriptCastSlot(
    val speakerTag: String,
    val voiceId: String,
    val speedScale: Float = 1.0f,
    val pitchScale: Float = 1.0f
)

data class PurrSubtitleCue(
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String
)

data class ScriptTemplate(
    val id: String,
    val label: String,
    val title: String,
    val script: String
)

data class PhoneticStressPrompt(
    val id: String,
    val label: String,
    val category: String,
    val prompt: String
)

object PurrScriptParser {

    private val SPEAKER_PREFIX_REGEX = Regex("""^\[?([A-Za-z0-9_.\-\s]{1,24})]?:\s+(.+)$""")
    private val INLINE_PAUSE_TAG_REGEX = Regex("""\[pause:(\d{1,5})(?:ms)?]\s*$""", RegexOption.IGNORE_CASE)

    val DEFAULT_TEMPLATES: List<ScriptTemplate> = listOf(
        ScriptTemplate(
            id = "podcast_duo",
            label = "PODCAST DUO",
            title = "Neon_Tech_Podcast_Ep01",
            script = """
                HOST_A: Welcome back to late-night Cyberdeck Radio! Tonight we are stress-testing live neural voices offline on mobile.
                HOST_B: Zero cloud latency, full hardware control, and multi-speaker casting right in your pocket. Let's hear that crisp breath pause! [pause:400ms]
                NARRATOR: The studio telemetry needles pulsed emerald as both channels locked onto the neural bus.
                HOST_A: Wait — did we really just swap between two completely different TTS engines mid-conversation?
                HOST_B: Smooth as velvet. Nyaa~
            """.trimIndent()
        ),
        ScriptTemplate(
            id = "vn_boss",
            label = "VN SCENE",
            title = "Astral_Citadel_Boss_Intro",
            script = """
                NARRATOR: Neon rain tapped against the glass catwalk high above Sector Nine. [pause:350ms]
                HEROINE: Step away from the terminal! We didn't come this far to hand over the source cartridge.
                RIVAL: Bold words for someone carrying an uncalibrated synth rig. Ready to hear what perfection sounds like?
                NARRATOR: Sparks danced across the floor as both audio cores spooled to full RPM.
            """.trimIndent()
        ),
        ScriptTemplate(
            id = "bilingual_show",
            label = "HOST + GUEST",
            title = "Creator_Interview_Take01",
            script = """
                HOST_A: Our first question today: what makes voice synthesis feel genuinely alive instead of robotic?
                GUEST: It is all about natural breath gaps, soft sentence decays, and matching the speaker's warmth to the scene!
                HOST_A: Couldn't agree more. Let's render this chapter straight to WAV and check the waveforms.
            """.trimIndent()
        )
    )

    /**
     * Parses multi-speaker screenplay / podcast scripts into ordered [PurrScriptLine] entries.
     * Supports `SPEAKER: line`, `[SPEAKER] line`, and plain continuation lines (assigned to
     * `NARRATOR` or alternating hosts). Optional trailing `[pause:500ms]` overrides inter-line gap.
     */
    fun parse(rawScript: String, defaultPauseMs: Int = 220): List<PurrScriptLine> {
        val bracketTagRegex = Regex("""^\[([A-Za-z0-9_.\-\s]{1,24})]\s+(.+)$""")
        val result = mutableListOf<PurrScriptLine>()
        for (rawLine in rawScript.lines()) {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("#")) continue

            var tag = "NARRATOR"
            var body = trimmed

            val colonMatch = SPEAKER_PREFIX_REGEX.matchEntire(trimmed)
            val bracketMatch = if (colonMatch == null) bracketTagRegex.matchEntire(trimmed) else null

            if (colonMatch != null) {
                tag = colonMatch.groupValues[1].trim().uppercase(Locale.US).replace(' ', '_')
                body = colonMatch.groupValues[2].trim()
            } else if (bracketMatch != null) {
                tag = bracketMatch.groupValues[1].trim().uppercase(Locale.US).replace(' ', '_')
                body = bracketMatch.groupValues[2].trim()
            }

            var pauseMs = defaultPauseMs.coerceIn(0, 5000)
            val pauseMatch = INLINE_PAUSE_TAG_REGEX.find(body)
            if (pauseMatch != null) {
                pauseMs = pauseMatch.groupValues[1].toIntOrNull()?.coerceIn(0, 5000) ?: pauseMs
                body = body.removeRange(pauseMatch.range).trim()
            }

            if (body.isNotEmpty()) {
                result += PurrScriptLine(
                    speakerTag = tag,
                    text = body,
                    pauseAfterMs = pauseMs
                )
            }
        }
        return result
    }

    /** Returns distinct speaker tags discovered in [lines] in order of first appearance. */
    fun discoverSpeakers(lines: List<PurrScriptLine>): List<String> =
        lines.map { it.speakerTag }.distinct()
}

object PurrSrtFormatter {

    val PHONETIC_CORPUS: List<PhoneticStressPrompt> = listOf(
        PhoneticStressPrompt(
            id = "numbers_dates",
            label = "NUMBERS & DATES",
            category = "NORMALIZATION",
            prompt = "On November 22nd, 2026 at 14:30 UTC, order #4,819 cost \$129.95 for 3.5 hours of compute."
        ),
        PhoneticStressPrompt(
            id = "sibilance_plosives",
            label = "SIBILANCE / PLOSIVES",
            category = "ACOUSTIC",
            prompt = "Crisp silver sparks spiraled softly past six brass switchboards beside the quiet crystal harbor."
        ),
        PhoneticStressPrompt(
            id = "purr_dialogue",
            label = "PURR SLANG & PROSODY",
            category = "ROLEPLAY",
            prompt = "O-oh! Wait... omg fr Mika-senpai, *leans closer softly* \"Did we seriously hit zero cloud latency?!\" nya~"
        ),
        PhoneticStressPrompt(
            id = "emotion_swing",
            label = "EMOTION DYNAMIC",
            category = "EXPRESSIVE",
            prompt = "Yes! We actually pulled it off!! Wait... why did the green indicator suddenly stop blinking? No, wait — it is fine!"
        ),
        PhoneticStressPrompt(
            id = "acronym_tech",
            label = "TECH & ACRONYMS",
            category = "PRONUNCIATION",
            prompt = "NPU FP16 ONNX inference runs at 4.2x real-time RTF across 24kHz PCM WAV and M4B chapters."
        )
    )

    /** Parses batch input lines into individual caption cue strings. */
    fun parseCueLines(rawText: String, splitSentences: Boolean = false): List<String> {
        val rawLines = rawText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (!splitSentences) return rawLines
        val out = mutableListOf<String>()
        val sentenceBoundary = Regex("""(?<=[.!?])\s+""")
        for (line in rawLines) {
            line.split(sentenceBoundary)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { out += it }
        }
        return out
    }

    /** Formats milliseconds into standard `.SRT` timestamp `HH:MM:SS,mmm`. */
    fun formatSrtTimestamp(ms: Long): String = formatTimestamp(ms, commaSeparator = ',')

    /** Formats milliseconds into standard WebVTT `.VTT` timestamp `HH:MM:SS.mmm`. */
    fun formatVttTimestamp(ms: Long): String = formatTimestamp(ms, commaSeparator = '.')

    private fun formatTimestamp(ms: Long, commaSeparator: Char): String {
        val clamped = ms.coerceAtLeast(0L)
        val hours = clamped / 3_600_000L
        val minutes = (clamped % 3_600_000L) / 60_000L
        val seconds = (clamped % 60_000L) / 1_000L
        val millis = clamped % 1_000L
        return String.format(
            Locale.US,
            "%02d:%02d:%02d%c%03d",
            hours,
            minutes,
            seconds,
            commaSeparator,
            millis
        )
    }

    /** Renders a standard UTF-8 SubRip (`.srt`) document from timed cues. */
    fun renderSrt(cues: List<PurrSubtitleCue>): String = buildString {
        cues.forEachIndexed { idx, cue ->
            append(idx + 1).append('\n')
            append(formatSrtTimestamp(cue.startMs))
                .append(" --> ")
                .append(formatSrtTimestamp(cue.endMs))
                .append('\n')
            append(cue.text.trim()).append("\n\n")
        }
    }

    /** Renders a standard WebVTT (`.vtt`) document from timed cues. */
    fun renderVtt(cues: List<PurrSubtitleCue>): String = buildString {
        append("WEBVTT\n\n")
        cues.forEach { cue ->
            append(formatVttTimestamp(cue.startMs))
                .append(" --> ")
                .append(formatVttTimestamp(cue.endMs))
                .append('\n')
            append(cue.text.trim()).append("\n\n")
        }
    }
}
