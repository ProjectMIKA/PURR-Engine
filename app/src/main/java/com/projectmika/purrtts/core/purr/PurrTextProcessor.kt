package com.projectmika.purrtts.core.purr

import kotlin.random.Random

/**
 * ✨ MIKA'S P.U.R.R. TEXT PROCESSOR (OMNI-SYNTHESIS + MIKASTOP TOKENIZER) ✨
 *
 * Pre-synthesis pipeline executed before any text reaches LiteRT, Sherpa-ONNX, or Proxy:
 * 1. Scores chunk sentiment before stripping emojis.
 * 2. Splits `*action blocks*` vs `"dialogue"` vs plain narration according to ActionHandlingMode.
 * 3. Expands texting slang (~100 entries), anime honorifics, custom phonetic rules, and stutters.
 * 4. Tokenizes ellipses (`...`), em-dashes (`—`), `[pause:Nms]`, sentences, and clauses into
 *    deterministic `MIKASTOP` chunk boundaries mapped to exact 24kHz PCM silence buffers.
 * 5. Shreds hallucination triggers (multiple dots, stray brackets, unpronounceable symbols) and
 *    guarantees clean terminal punctuation per chunk.
 */
object PurrTextProcessor {

    private data class RawRoleSegment(
        val text: String,
        val role: SegmentRole
    )

    private val PHONETIC_STUTTER_MAP = mapOf(
        "a" to "ah", "b" to "buh", "c" to "kuh", "d" to "duh", "e" to "eh", "f" to "fuh",
        "g" to "guh", "h" to "heh", "i" to "ih", "j" to "juh", "k" to "kuh", "l" to "luh",
        "m" to "mm", "n" to "nn", "o" to "oh", "p" to "puh", "q" to "quh", "r" to "ruh",
        "s" to "sss", "t" to "tuh", "u" to "uh", "v" to "vuh", "w" to "wuh", "x" to "ecks",
        "y" to "yuh", "z" to "zuh",
        "th" to "thuh", "sh" to "shh", "ch" to "chuh", "ph" to "fuh", "wh" to "wuh", "qu" to "quh"
    )

    private val SLANG_DICTIONARY: Map<String, String> = linkedMapOf(
        "omfg" to "oh my fucking god",
        "omg" to "oh my god",
        "lmfao" to "laughing my ass off",
        "lmao" to "luh mao",
        "rofl" to "rolling on the floor laughing",
        "lol" to "ell oh ell",
        "wtf" to "what the fuck",
        "wth" to "what the hell",
        "stfu" to "shut the fuck up",
        "tf" to "the fuck",
        "mfs" to "motherfuckers",
        "mf" to "motherfucker",
        "bs" to "bullshit",
        "idk" to "I don't know",
        "idc" to "I don't care",
        "smh" to "shaking my head",
        "tbh" to "to be honest",
        "imho" to "in my honest opinion",
        "imo" to "in my opinion",
        "jk" to "just kidding",
        "brb" to "be right back",
        "gtg" to "got to go",
        "g2g" to "got to go",
        "btw" to "by the way",
        "omw" to "on my way",
        "ok" to "okay",
        "kk" to "okay",
        "pls" to "please",
        "plz" to "please",
        "tysm" to "thank you so much",
        "thx" to "thanks",
        "ty" to "thank you",
        "tho" to "though",
        "thru" to "through",
        "wdym" to "what do you mean",
        "rn" to "right now",
        "frfr" to "for real for real",
        "fr" to "for real",
        "bc" to "because",
        "cuz" to "because",
        "nvm" to "nevermind",
        "afaik" to "as far as I know",
        "ofc" to "of course",
        "ong" to "on god",
        "irl" to "in real life",
        "ngl" to "not gonna lie",
        "np" to "no problem",
        "yw" to "you're welcome",
        "ily2" to "I love you too",
        "ily" to "I love you",
        "gf" to "girlfriend",
        "bf" to "boyfriend",
        "tfw" to "that feeling when",
        "pov" to "point of view",
        "sus" to "suspicious",
        "af" to "as fuck",
        "tmi" to "too much information",
        "fyi" to "for your information",
        "ikr" to "I know, right",
        "ik" to "I know",
        "srsly" to "seriously",
        "yolo" to "you only live once",
        "asap" to "as soon as possible",
        "iykyk" to "if you know you know",
        "bff" to "best friend forever",
        "ttyl" to "talk to you later",
        "def" to "definitely",
        "probs" to "probably",
        "obv" to "obviously",
        "msg" to "message",
        "txt" to "text",
        "ppl" to "people",
        "idgi" to "I don't get it",
        "hru" to "how are you",
        "hbu" to "how about you",
        "ig" to "I guess",
        "wyd" to "what are you doing",
        "hyd" to "how are you doing",
        "istg" to "I swear to god",
        "stg" to "swear to god",
        "nothin" to "nothing",
        "somethin" to "something",
        "anythin" to "anything",
        "tryna" to "trying to",
        "gonna" to "going to",
        "wanna" to "want to",
        "gotta" to "got to",
        "outta" to "out of",
        "lemme" to "let me",
        "gimme" to "give me",
        "finna" to "fixing to",
        "boutta" to "about to",
        "kinda" to "kind of",
        "sorta" to "sort of"
    )

    private val ACRONYM_WHITELIST = setOf(
        "NASA", "DNA", "FBI", "CIA", "USA", "UK", "OK", "TV", "AI", "ID",
        "UFO", "HUD", "OS", "TTS", "NPU", "GPU", "CPU", "PCM", "DSP", "VIP", "SSR"
    )

    private val REPLY_PREFIX_REGEX = Regex("\\[Replying to.*?]", RegexOption.IGNORE_CASE)
    private val EMOJI_VAPORIZER_REGEX = Regex("[\\x{1F300}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\u200B-\\u200D\\uFE0E\\uFE0F]")
    private val ROLE_SPLIT_REGEX = Regex("(\\*[^*]+\\*|\"[^\"]+\"|“[^”]+”)")
    private val EXPLICIT_PAUSE_REGEX = Regex("\\[\\s*pause\\s*:\\s*(\\d{1,4})\\s*(?:ms)?\\s*]", RegexOption.IGNORE_CASE)
    private val ORGANIC_STUTTER_REGEX = Regex("\\b([a-z]{1,2})-(?=[a-z])", RegexOption.IGNORE_CASE)
    private val ELLIPSIS_REGEX = Regex("(?:\\.\\s*){2,}|…+")
    private val EM_DASH_TILDE_REGEX = Regex("[—–~]+|--+")
    private val SENTENCE_END_REGEX = Regex("([.!?]+)(\\s|$)")
    private val CLAUSE_PAUSE_REGEX = Regex("([,;:]+)(\\s|$)")
    private val MIKASTOP_TOKEN_REGEX = Regex("\\[\\[MIKASTOP:(\\d+):([A-Z0-9_]+)]]")
    private val BARE_MIKASTOP_REGEX = Regex("(?<!\\[\\[)\\bmikastop\\b(?!:)", RegexOption.IGNORE_CASE)
    private val SIGH_REGEX = Regex("(?<!-)\\b(ah+|mm+|ugh+)\\b(?!-)|(?<!-)\\boh\\b(?=[.!?,;:]|$)(?!-)", RegexOption.IGNORE_CASE)
    private val XML_TAG_REGEX = Regex("</?[^>]+(?:>|$)")
    private val WEIRD_ASCII_REGEX = Regex("[\\[\\]{}()<>^|\\\\/=*%#$@+\"“”]")
    private val ACRONYM_RUN_REGEX = Regex("\\b[A-Z]{2,}\\b")
    private val COLLISION_SPACE_REGEX = Regex("\\s+([.?!,])")
    private val MULTI_SPACE_REGEX = Regex("\\s{2,}")
    private val MIXED_BANG_REGEX = Regex("[?!]+")
    private val MULTI_DOT_REGEX = Regex("\\.{2,}")
    private val TRAILING_DASH_REGEX = Regex("[\\-—–~]+$")
    private val LEADING_GARBAGE_REGEX = Regex("^[^a-zA-Z0-9(¿¡]+")
    private val DANGLING_QUOTE_REGEX = Regex("([.!?,;:])([^a-zA-Z0-9]+)$")

    fun process(
        rawText: String,
        profile: PurrProfile,
        customRules: List<PhoneticRule> = emptyList(),
        rng: Random = Random(rawText.hashCode())
    ): List<PurrChunk> {
        val strippedReply = REPLY_PREFIX_REGEX.replace(rawText, "").trim()
        if (strippedReply.isEmpty()) return emptyList()

        // 1. Split text into *action*, "dialogue", and plain narration segments BEFORE stripping quotes/asterisks.
        val roleSegments = splitIntoRoleSegments(strippedReply, profile.actionHandlingMode)
        val output = mutableListOf<PurrChunk>()

        for (segment in roleSegments) {
            val sentiment = PurrSentimentScorer.score(
                text = segment.text,
                toneOverride = profile.sentimentConfig.toneOverride
            )

            // If STRIP_ACTIONS converted `*action*` into an explicit MIKASTOP token, handle it directly.
            val processedWithRules = if (segment.role == SegmentRole.ACTION &&
                profile.actionHandlingMode == ActionHandlingMode.STRIP_ACTIONS
            ) {
                "[[MIKASTOP:${profile.mikaStopConfig.actionStripPauseMs}:ACTION_STRIP]]"
            } else {
                applyPhoneticAndSlangPipeline(
                    text = segment.text,
                    profile = profile,
                    sentiment = sentiment,
                    customRules = customRules,
                    rng = rng
                )
            }

            // 2. Tokenize punctuation & explicit pauses into deterministic MIKASTOP boundaries.
            val tokenized = tokenizeWithMikaStop(processedWithRules, profile.mikaStopConfig)

            // 3. Emit alternating Speech and Silence chunks.
            val prosody = PurrSentimentScorer.computeProsodyModulation(
                sentiment = sentiment,
                profile = profile,
                role = segment.role
            )
            val targetVoiceId = resolveTargetVoiceId(segment.role, profile)

            var cursor = 0
            for (match in MIKASTOP_TOKEN_REGEX.findAll(tokenized)) {
                val before = tokenized.substring(cursor, match.range.first)
                emitSpeechChunkIfValid(
                    rawChunk = before,
                    role = segment.role,
                    targetVoiceId = targetVoiceId,
                    prosody = prosody,
                    sentiment = sentiment,
                    out = output
                )

                val durationMs = match.groupValues[1].toIntOrNull()?.coerceIn(40, 4000) ?: 250
                val reason = match.groupValues[2]
                appendSilenceMerged(output, durationMs, reason)
                cursor = match.range.last + 1
            }

            if (cursor < tokenized.length) {
                val tail = tokenized.substring(cursor)
                emitSpeechChunkIfValid(
                    rawChunk = tail,
                    role = segment.role,
                    targetVoiceId = targetVoiceId,
                    prosody = prosody,
                    sentiment = sentiment,
                    out = output
                )
            }
        }

        // Drop trailing silence so Android SynthesisCallback finishes crisply after the last word.
        while (output.lastOrNull() is PurrChunk.Silence) {
            output.removeAt(output.lastIndex)
        }
        return output
    }

    private fun splitIntoRoleSegments(
        text: String,
        actionMode: ActionHandlingMode
    ): List<RawRoleSegment> {
        val segments = mutableListOf<RawRoleSegment>()
        var lastIndex = 0

        for (match in ROLE_SPLIT_REGEX.findAll(text)) {
            if (match.range.first > lastIndex) {
                val plain = text.substring(lastIndex, match.range.first).trim()
                if (plain.isNotEmpty()) {
                    segments += RawRoleSegment(plain, SegmentRole.NARRATION)
                }
            }

            val token = match.value
            if (token.startsWith("*") && token.endsWith("*")) {
                val inner = token.substring(1, token.length - 1).trim()
                if (inner.isNotEmpty() || actionMode == ActionHandlingMode.STRIP_ACTIONS) {
                    segments += RawRoleSegment(inner, SegmentRole.ACTION)
                }
            } else {
                // Quoted dialogue ("..." or “...”)
                val inner = token.substring(1, token.length - 1).trim()
                if (inner.isNotEmpty()) {
                    segments += RawRoleSegment(inner, SegmentRole.DIALOGUE)
                }
            }
            lastIndex = match.range.last + 1
        }

        if (lastIndex < text.length) {
            val tail = text.substring(lastIndex).trim()
            if (tail.isNotEmpty()) {
                segments += RawRoleSegment(tail, SegmentRole.NARRATION)
            }
        }

        return segments
    }

    fun applyPhoneticAndSlangPipeline(
        text: String,
        profile: PurrProfile,
        sentiment: SentimentResult,
        customRules: List<PhoneticRule> = emptyList(),
        rng: Random = Random(0)
    ): String {
        var clean = text

        // Protect explicit [pause:Nms] tags before symbol scrubbing
        clean = EXPLICIT_PAUSE_REGEX.replace(clean) { m ->
            val ms = (m.groupValues[1].toIntOrNull() ?: 300).coerceIn(40, 4000)
            " [[MIKASTOP:$ms:TAG_PAUSE]] "
        }

        // Vaporize emojis & percent/ampersand symbols
        clean = EMOJI_VAPORIZER_REGEX.replace(clean, " ")
        clean = clean.replace("%", " percent ")
        clean = clean.replace("&", " and ")

        // Apply user-configured PhoneticRule entries from PURR Rulebook
        for (rule in customRules) {
            if (!rule.enabled || rule.pattern.isBlank()) continue
            val options = if (rule.ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
            val regex = runCatching {
                if (rule.isRegex) Regex(rule.pattern, options)
                else Regex("\\b${Regex.escape(rule.pattern)}\\b", options)
            }.getOrNull() ?: continue
            clean = regex.replace(clean) { rule.replacement }
        }

        // Organic Stutter Translator: `w-what` -> `wuh-what`, `th-that` -> `thuh-that`
        // (Run before honorific syllable hyphenation so `ah-rah` / `sah-mah` stay untouched.)
        clean = ORGANIC_STUTTER_REGEX.replace(clean) { m ->
            val prefix = m.groupValues[1].lowercase()
            val sound = PHONETIC_STUTTER_MAP[prefix] ?: PHONETIC_STUTTER_MAP[prefix.take(1)]
            if (sound != null) "$sound-" else m.value
        }

        // Anime honorifics & vocalisms (applied before em-dash/tilde split so `nya~` becomes `nyaah`)
        clean = applyAnimeHonorificsAndVocalisms(clean)

        // Apply ~100 GachaSwipe texting slang expansions
        for ((slang, expanded) in SLANG_DICTIONARY) {
            val wordRegex = Regex("\\b${Regex.escape(slang)}\\b", RegexOption.IGNORE_CASE)
            clean = wordRegex.replace(clean) { expanded }
        }

        // Drama Matrix optional stutter / vowel stretch (with strict "mika" immunity)
        val tone = profile.sentimentConfig.toneOverride
        if (profile.sentimentConfig.enabled) {
            if ((tone == ArchetypeTone.SHY || tone == ArchetypeTone.SHY_LOVE || tone == ArchetypeTone.TSUNDERE_LOVE) &&
                profile.sentimentConfig.stutterIntensity > 0.01f
            ) {
                clean = applyStutter(clean, profile.sentimentConfig.stutterIntensity, rng)
            }
            if ((tone == ArchetypeTone.YANDERE_LOVE || tone == ArchetypeTone.DOMINANT_LOVE) &&
                profile.sentimentConfig.vowelStretchIntensity > 0.01f
            ) {
                clean = stretchVowels(clean, profile.sentimentConfig.vowelStretchIntensity, rng)
            }
        }

        return clean
    }

    private fun applyAnimeHonorificsAndVocalisms(input: String): String {
        var out = input
        out = out.replace(Regex("-san\\b", RegexOption.IGNORE_CASE), " sahn")
        out = out.replace(Regex("-chan\\b", RegexOption.IGNORE_CASE), " chahn")
        out = out.replace(Regex("-kun\\b", RegexOption.IGNORE_CASE), " koon")
        out = out.replace(Regex("-sama\\b", RegexOption.IGNORE_CASE), " sah-mah")
        out = out.replace(Regex("-senpai\\b", RegexOption.IGNORE_CASE), " sen-pie")
        out = out.replace(Regex("-sensei\\b", RegexOption.IGNORE_CASE), " sen-say")
        out = out.replace(Regex("\\bnyaa*~+", RegexOption.IGNORE_CASE), "nyaah")
        out = out.replace(Regex("\\bnyaa+\\b", RegexOption.IGNORE_CASE), "nyaah")
        out = out.replace(Regex("\\btch\\b", RegexOption.IGNORE_CASE), "tssk")
        out = out.replace(Regex("\\bara[-\\s]ara\\b", RegexOption.IGNORE_CASE), "ah-rah ah-rah")
        out = out.replace(Regex("\\bfufu+\\b", RegexOption.IGNORE_CASE), "foo-foo")
        out = out.replace(Regex("\\bkyaa+\\b", RegexOption.IGNORE_CASE), "kee-yah")
        return out
    }

    fun applyStutter(input: String, intensity: Float = 0.15f, rng: Random = Random(0)): String {
        return input.split(' ').joinToString(" ") { word ->
            if (word.startsWith("[[MIKASTOP:") || word.lowercase().contains("mika")) {
                word
            } else if (word.length > 2 && word.first().isLetter() && rng.nextFloat() < intensity && !word.contains('-')) {
                val lower = word.lowercase()
                val sound = PHONETIC_STUTTER_MAP[lower.take(2)] ?: PHONETIC_STUTTER_MAP[lower.take(1)] ?: lower.take(1)
                "$sound-$word"
            } else {
                word
            }
        }
    }

    fun stretchVowels(input: String, intensity: Float = 0.15f, rng: Random = Random(0)): String {
        return input.split(' ').joinToString(" ") { word ->
            if (word.startsWith("[[MIKASTOP:") || word.lowercase().contains("mika")) {
                word
            } else if (word.length > 3 && rng.nextFloat() < intensity) {
                word.replaceFirst(Regex("([aeiouyAEIOUY])"), "$1$1$1")
            } else {
                word
            }
        }
    }

    fun tokenizeWithMikaStop(input: String, config: MikaStopConfig): String {
        var out = input

        // Normalize bare MIKASTOP keywords
        out = BARE_MIKASTOP_REGEX.replace(out, " [[MIKASTOP:${config.sentencePauseMs}:MIKASTOP]] ")

        // Protect ellipses with a temporary sentinel so sentence-end matching does not double-count pauses
        val ellipsisSentinel = "@@ELLIPSIS_MIKASTOP@@"
        out = ELLIPSIS_REGEX.replace(out, "$ellipsisSentinel ")

        // Em-dashes, en-dashes, double hyphens, and stray tildes
        out = EM_DASH_TILDE_REGEX.replace(out, " [[MIKASTOP:${config.emDashPauseMs}:EM_DASH]] ")

        // Line breaks
        out = out.replace(Regex("\\n+"), " [[MIKASTOP:${config.sentencePauseMs}:NEWLINE]] ")

        // Sentence boundaries (preserving attached terminal punctuation)
        out = SENTENCE_END_REGEX.replace(out) { m ->
            "${m.groupValues[1]} [[MIKASTOP:${config.sentencePauseMs}:SENTENCE]] "
        }

        // Optional comma/clause slicing for natural neural breath pauses
        if (config.sliceAtCommas) {
            out = CLAUSE_PAUSE_REGEX.replace(out) { m ->
                "${m.groupValues[1]} [[MIKASTOP:${config.commaPauseMs}:CLAUSE]] "
            }
        }

        // Expand protected ellipsis sentinels into exact MIKASTOP silence boundaries
        out = out.replace(ellipsisSentinel, ". [[MIKASTOP:${config.ellipsisPauseMs}:ELLIPSIS]] ")

        return out
    }

    fun sanitizeSpeechChunk(rawChunk: String): String {
        var chunk = rawChunk.trim()
        if (chunk.isEmpty()) return ""

        // Ensure phonetic sighs trigger breath artifacts
        chunk = SIGH_REGEX.replace(chunk) { m -> "${m.groupValues[1]}. " }

        // Shred stray HTML/XML tags and weird ASCII
        chunk = XML_TAG_REGEX.replace(chunk, " ")
        chunk = WEIRD_ASCII_REGEX.replace(chunk, "")

        // Acronym Assassin: lowercase 2+ uppercase runs unless whitelisted
        chunk = ACRONYM_RUN_REGEX.replace(chunk) { m ->
            if (m.value in ACRONYM_WHITELIST) m.value else m.value.lowercase()
        }

        // Collapse whitespace collisions
        chunk = COLLISION_SPACE_REGEX.replace(chunk, "$1")
        chunk = MULTI_SPACE_REGEX.replace(chunk, " ").trim()

        // Collapse `?!` or `!?` to primary emotion
        chunk = MIXED_BANG_REGEX.replace(chunk) { m ->
            if (m.value.contains('?')) "?" else "!"
        }

        // Shred multi-dot runs into a single confident period
        chunk = MULTI_DOT_REGEX.replace(chunk, ".")

        // Strip trailing dashes and leading non-alnum garbage
        chunk = TRAILING_DASH_REGEX.replace(chunk, "")
        chunk = LEADING_GARBAGE_REGEX.replace(chunk, "")
        chunk = DANGLING_QUOTE_REGEX.replace(chunk, "$1").trim()

        if (chunk.isEmpty()) return ""

        // Guarantee terminal punctuation (commas preserved as breath marks)
        if (!Regex("[.!?,;:]$").containsMatchIn(chunk)) {
            chunk = chunk.replace(Regex("[^a-zA-Z0-9]$"), "") + "."
        }

        if (chunk == "." || chunk == "," || chunk == "!" || chunk == "?") return ""
        return chunk
    }

    private fun emitSpeechChunkIfValid(
        rawChunk: String,
        role: SegmentRole,
        targetVoiceId: String?,
        prosody: PurrSentimentScorer.ProsodyModulation,
        sentiment: SentimentResult,
        out: MutableList<PurrChunk>
    ) {
        val sanitized = sanitizeSpeechChunk(rawChunk)
        if (sanitized.isNotEmpty()) {
            out += PurrChunk.Speech(
                text = sanitized,
                role = role,
                targetVoiceId = targetVoiceId,
                speakingRate = prosody.speakingRate,
                pitch = prosody.pitch,
                volumeGain = prosody.volumeGain,
                sentiment = sentiment
            )
        }
    }

    private fun appendSilenceMerged(
        out: MutableList<PurrChunk>,
        durationMs: Int,
        reason: String
    ) {
        if (out.isEmpty() || durationMs <= 0) return
        val last = out.lastOrNull()
        if (last is PurrChunk.Silence) {
            out[out.lastIndex] = PurrChunk.Silence(
                durationMs = (last.durationMs + durationMs).coerceAtMost(3000),
                reason = "${last.reason}+$reason"
            )
        } else {
            out += PurrChunk.Silence(durationMs = durationMs, reason = reason)
        }
    }

    private fun resolveTargetVoiceId(role: SegmentRole, profile: PurrProfile): String {
        return if (role == SegmentRole.DIALOGUE &&
            profile.actionHandlingMode == ActionHandlingMode.DUAL_VOICE_ROUTING &&
            profile.dialogueVoiceId.isNotBlank()
        ) {
            profile.dialogueVoiceId
        } else {
            profile.primaryVoiceId
        }
    }
}
