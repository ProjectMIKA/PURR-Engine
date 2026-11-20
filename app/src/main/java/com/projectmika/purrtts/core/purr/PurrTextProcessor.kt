package com.projectmika.purrtts.core.purr

import kotlin.random.Random

/**
 * ✨ MIKA'S P.U.R.R. TEXT PROCESSOR (GACHA-SWIPE TRIED & TRUE OMNI-SYNTHESIS + MIKASTOP) ✨
 *
 * Ports GachaSwipe's complete battle-tested `renderVoiceNote` pipeline (App.jsx):
 * 1. Sentiment & Tone Override Drama Matrix (`tsundere`, `yandere`, `shy`, `kuudere`, `genki`, `dominant`, `robot`).
 * 2. Optional `*action blocks*` vs `"dialogue"` splitting & optional Dual-Voice Routing.
 * 3. ~100-entry Texting Slang Translator + Anime Honorifics + Organic Stutter Translator (`w-what` -> `wuh-what`).
 * 4. MIKASTOP Pause Tokenizer (`...`, `—`, `[pause:Nms]`, sentences, breath commas) -> 24kHz silence chunks.
 * 5. Hallucination Shredder, Acronym Assassin & Punctuation Protector (commas kept as breath marks).
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

    val SLANG_DICTIONARY: Map<String, String> = linkedMapOf(
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

    val HONORIFIC_RULES_PREVIEW: List<Pair<String, String>> = listOf(
        "-san / -chan / -kun" to "sahn / chahn / koon",
        "-sama / -senpai / -sensei" to "sah-mah / sen-pie / sen-say",
        "nya~ / nyaa~" to "nyaah",
        "tch / ara-ara / fufu" to "tssk / ah-rah ah-rah / foo-foo",
        "w-what / b-baka" to "wuh-what / buh-baka (organic stutters)"
    )

    private val ACRONYM_WHITELIST = setOf(
        "NASA", "DNA", "FBI", "CIA", "USA", "UK", "OK", "TV", "AI", "ID",
        "UFO", "HUD", "OS", "TTS", "NPU", "GPU", "CPU", "PCM", "DSP", "VIP", "SSR"
    )

    private val STUTTER_DIGRAPHS = setOf("th", "sh", "ch", "ph", "wh", "qu")
    private val HONORIFIC_SUFFIXES = setOf("san", "chan", "kun", "sama", "senpai", "sensei")

    val CHATTERBOX_PARALINGUISTIC_TAGS: Set<String> = setOf(
        "laugh", "chuckle", "sigh", "gasp", "cough", "clear throat",
        "groan", "snort", "shush", "whispering", "crying", "yawn",
        "angry", "excited", "sad", "happy", "surprised", "fearful", "disgusted"
    )
    private val CHATTERBOX_TAG_REGEX = Regex("\\[(${CHATTERBOX_PARALINGUISTIC_TAGS.joinToString("|") { Regex.escape(it) }})]", RegexOption.IGNORE_CASE)
    private val CHATTERBOX_RESTORE_REGEX = Regex("\\bpurrcbtag(\\d+)purr\\b")
    private val ACTION_LAUGH_REGEX = Regex("\\*(?:laughs?|giggles?)\\*", RegexOption.IGNORE_CASE)
    private val ACTION_CHUCKLE_REGEX = Regex("\\*(?:chuckles?|snickers?)\\*", RegexOption.IGNORE_CASE)
    private val ACTION_SIGH_REGEX = Regex("\\*sighs?\\*", RegexOption.IGNORE_CASE)
    private val ACTION_GASP_REGEX = Regex("\\*gasps?\\*", RegexOption.IGNORE_CASE)
    private val ACTION_COUGH_REGEX = Regex("\\*coughs?\\*", RegexOption.IGNORE_CASE)
    private val ACTION_THROAT_REGEX = Regex("\\*clears?\\s+throat\\*", RegexOption.IGNORE_CASE)
    private val ACTION_GROAN_REGEX = Regex("\\*groans?\\*", RegexOption.IGNORE_CASE)
    private val ACTION_WHISPER_REGEX = Regex("\\*whispers?\\*", RegexOption.IGNORE_CASE)
    private val ACTION_YAWN_REGEX = Regex("\\*yawns?\\*", RegexOption.IGNORE_CASE)

    fun mapActionsToChatterboxTags(input: String): String {
        var s = input
        s = ACTION_LAUGH_REGEX.replace(s, " [laugh] ")
        s = ACTION_CHUCKLE_REGEX.replace(s, " [chuckle] ")
        s = ACTION_SIGH_REGEX.replace(s, " [sigh] ")
        s = ACTION_GASP_REGEX.replace(s, " [gasp] ")
        s = ACTION_COUGH_REGEX.replace(s, " [cough] ")
        s = ACTION_THROAT_REGEX.replace(s, " [clear throat] ")
        s = ACTION_GROAN_REGEX.replace(s, " [groan] ")
        s = ACTION_WHISPER_REGEX.replace(s, " [whispering] ")
        s = ACTION_YAWN_REGEX.replace(s, " [yawn] ")
        return MULTI_SPACE_REGEX.replace(s, " ").trim()
    }

    private val REPLY_PREFIX_REGEX = Regex("\\[Replying to.*?]", RegexOption.IGNORE_CASE)
    private val EMOJI_VAPORIZER_REGEX = Regex("[\\x{1F300}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\u200B-\\u200D\\uFE0E\\uFE0F]")
    private val ROLE_SPLIT_ACTION_ONLY_REGEX = Regex("(\\*[^*]+\\*)")
    private val ROLE_SPLIT_ACTION_AND_QUOTE_REGEX = Regex("(\\*[^*]+\\*|\"[^\"]+\"|“[^”]+”)")
    private val EXPLICIT_PAUSE_REGEX = Regex("\\[\\s*pause\\s*:\\s*(\\d{1,4})\\s*(?:ms)?\\s*]", RegexOption.IGNORE_CASE)
    private val COMPOUND_HYPHEN_REGEX = Regex("\\b([\\p{L}]{2,})-(?=([\\p{L}]{2,})\\b)")
    private val ORGANIC_STUTTER_REGEX = Regex("\\b([a-z]{1,2})-(?=\\1[a-z])", RegexOption.IGNORE_CASE)
    private val ABBREVIATION_DOT_REGEX = Regex("\\b(Mr|Mrs|Ms|Mx|Dr|Prof|St|Mt|Jr|Sr|vs|etc|Fig|Vol|Ch|No)\\.", RegexOption.IGNORE_CASE)
    private val LATIN_ABBR_DOT_REGEX = Regex("\\b([ei])\\.([eg])\\.", RegexOption.IGNORE_CASE)
    private val INITIAL_DOT_REGEX = Regex("\\b([A-Z])\\.(?=\\s+[A-Z])")
    private val DECIMAL_DOT_REGEX = Regex("(?<=\\d)\\.(?=\\d)")
    private val WORD_TOKEN_REGEX = Regex("[a-zA-Z0-9']+")
    private val ELLIPSIS_REGEX = Regex("(?:\\.\\s*){2,}|…+")
    private val EM_DASH_TILDE_REGEX = Regex("[—–~]+|--+")
    private val SENTENCE_END_REGEX = Regex("([.!?]+)(\\s|$)")
    private val CLAUSE_PAUSE_REGEX = Regex("([,;:]+)(\\s|$)")
    private val MIKASTOP_TOKEN_REGEX = Regex("\\[\\[MIKASTOP:(\\d+):([A-Z0-9_]+)]]")
    private val BARE_MIKASTOP_REGEX = Regex("(?<!\\[\\[)\\bmikastop\\b(?!:)", RegexOption.IGNORE_CASE)
    private val SIGH_REGEX = Regex("(?<!-)\\b(ah+|mm+|ugh+)\\b(?!-)|(?<!-)\\boh\\b(?=[.!?,;:]|$)(?!-)", RegexOption.IGNORE_CASE)
    private val XML_TAG_REGEX = Regex("</?[^>]+(?:>|$)")
    private val QUOTES_AND_LONE_APOSTROPHES_REGEX = Regex("[\"“”„«»‹›]|(?<![a-zA-Z])['’‘]|['’‘](?![a-zA-Z])")
    private val BRACKETS_AND_MARKDOWN_REGEX = Regex("[\\[\\]{}()<>\\\\/*_#~|`^]")
    private val WEIRD_ASCII_REGEX = Regex("[\\[\\]{}()<>^|\\\\/=*%#$@+\"“”]")
    private val ACRONYM_RUN_REGEX = Regex("\\b[A-Z]{2,}\\b")
    private val COLLISION_SPACE_REGEX = Regex("\\s+([.?!,])")
    private val MULTI_SPACE_REGEX = Regex("\\s{2,}")
    private val MIXED_BANG_REGEX = Regex("[?!]+")
    private val MULTI_DOT_REGEX = Regex("\\.{2,}")
    private val TRAILING_DASH_REGEX = Regex("[\\-—–~]+$")
    private val LEADING_GARBAGE_REGEX = Regex("^[^\\p{L}0-9(¿¡]+")
    private val DANGLING_QUOTE_REGEX = Regex("([.!?,;:])([^\\p{L}0-9]+)$")

    fun normalizeTypography(text: String): String {
        return text
            .replace(Regex("[\u2018\u2019\u02BC\uFF07]"), "'")
            .replace(Regex("[\u00A0\u2007\u202F]"), " ")
    }

    fun process(
        rawText: String,
        profile: PurrProfile,
        customRules: List<PhoneticRule> = emptyList(),
        rng: Random = Random(rawText.hashCode()),
        purrModuleActive: Boolean = true,
        symbolShieldModuleActive: Boolean = true,
        isChatterboxVoice: Boolean = false,
        clonedVoices: List<ClonedVoiceProfile> = emptyList()
    ): List<PurrChunk> {
        var strippedReply = normalizeTypography(REPLY_PREFIX_REGEX.replace(rawText, "")).trim()
        if (strippedReply.isEmpty()) return emptyList()

        val chatterboxActive = isChatterboxVoice || profile.primaryVoiceId.startsWith("chatterbox_")
        if (chatterboxActive) {
            strippedReply = mapActionsToChatterboxTags(strippedReply)
        }

        val neuralPureActive = PurrNeuralBypass.shouldBypassEnhancers(profile, clonedVoices)
        val effectivePurrRewriting = purrModuleActive && !neuralPureActive

        // Effective profile honors Tool Cartridge slotting AND automatically disables intrusive
        // enhancers when running heavyweight neural LLM TTS engines (`PurrNeuralBypass`).
        val effectiveProfile = if (neuralPureActive) {
            profile.copy(
                enableSlangAndHonorifics = false,
                enableMikaStopPauses = false,
                enableSymbolShield = symbolShieldModuleActive && profile.enableSymbolShield,
                symbolShieldConfig = profile.symbolShieldConfig.copy(
                    stripQuotationMarks = false,
                    commaHandling = CommaHandlingMode.KEEP_RAW
                ),
                actionHandlingMode = if (profile.enableDualVoiceSplit) {
                    profile.actionHandlingMode
                } else {
                    ActionHandlingMode.DUAL_VOICE_ROUTING
                },
                mikaStopConfig = profile.mikaStopConfig.copy(
                    targetChunkChars = maxOf(profile.mikaStopConfig.targetChunkChars, 260)
                ),
                sentimentConfig = profile.sentimentConfig.copy(
                    enabled = false,
                    toneOverride = ArchetypeTone.NORMAL,
                    stutterIntensity = 0f,
                    vowelStretchIntensity = 0f
                )
            )
        } else {
            profile.copy(
                enableSlangAndHonorifics = purrModuleActive && profile.enableSlangAndHonorifics,
                enableMikaStopPauses = purrModuleActive && profile.enableMikaStopPauses,
                enableSymbolShield = symbolShieldModuleActive && profile.enableSymbolShield,
                sentimentConfig = if (purrModuleActive) {
                    profile.sentimentConfig
                } else {
                    profile.sentimentConfig.copy(
                        enabled = false,
                        toneOverride = ArchetypeTone.NORMAL,
                        stutterIntensity = 0f,
                        vowelStretchIntensity = 0f
                    )
                }
            )
        }
        val effectiveRules = if (purrModuleActive) customRules else emptyList()

        // 1. Split text into *action* (and "dialogue" only when Dual-Voice Split is enabled) BEFORE symbol stripping.
        val roleSegments = splitIntoRoleSegments(
            text = strippedReply,
            actionMode = if (effectivePurrRewriting) effectiveProfile.actionHandlingMode else ActionHandlingMode.DUAL_VOICE_ROUTING,
            splitDialogueQuotes = effectiveProfile.enableDualVoiceSplit
        )
        val output = mutableListOf<PurrChunk>()

        for (segment in roleSegments) {
            val sentiment = if (effectivePurrRewriting) {
                PurrSentimentScorer.score(
                    text = segment.text,
                    toneOverride = effectiveProfile.sentimentConfig.toneOverride
                )
            } else {
                SentimentResult()
            }

            // If STRIP_ACTIONS converted `*action*` into an explicit MIKASTOP pause
            val processedWithRules = if (effectivePurrRewriting &&
                segment.role == SegmentRole.ACTION &&
                effectiveProfile.actionHandlingMode == ActionHandlingMode.STRIP_ACTIONS
            ) {
                if (effectiveProfile.enableMikaStopPauses) {
                    "[[MIKASTOP:${effectiveProfile.mikaStopConfig.actionStripPauseMs}:ACTION_STRIP]]"
                } else {
                    ""
                }
            } else {
                applyPhoneticAndSlangPipeline(
                    text = segment.text,
                    profile = effectiveProfile,
                    sentiment = sentiment,
                    customRules = effectiveRules,
                    rng = rng
                )
            }

            if (processedWithRules.isBlank()) continue

            // 2. Tokenize punctuation & explicit pauses into deterministic MIKASTOP boundaries.
            val tokenized = if (effectiveProfile.enableMikaStopPauses) {
                tokenizeWithMikaStop(processedWithRules, effectiveProfile.mikaStopConfig, effectiveProfile)
            } else if (!neuralPureActive &&
                effectiveProfile.enableSymbolShield &&
                effectiveProfile.symbolShieldConfig.commaHandling != CommaHandlingMode.KEEP_RAW
            ) {
                CLAUSE_PAUSE_REGEX.replace(processedWithRules, " ")
            } else {
                processedWithRules
            }

            // 3. Emit alternating Speech and Silence chunks.
            val prosody = PurrSentimentScorer.computeProsodyModulation(
                sentiment = sentiment,
                profile = effectiveProfile,
                role = segment.role
            )
            val targetVoiceId = resolveTargetVoiceId(segment.role, effectiveProfile)
            val preserveCb = chatterboxActive || targetVoiceId.startsWith("chatterbox_")

            var cursor = 0
            for (match in MIKASTOP_TOKEN_REGEX.findAll(tokenized)) {
                val before = tokenized.substring(cursor, match.range.first)
                emitSpeechChunkIfValid(
                    rawChunk = before,
                    role = segment.role,
                    targetVoiceId = targetVoiceId,
                    prosody = prosody,
                    sentiment = sentiment,
                    profile = effectiveProfile,
                    out = output,
                    preserveChatterboxTags = preserveCb,
                    neuralPureMode = neuralPureActive
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
                    profile = effectiveProfile,
                    out = output,
                    preserveChatterboxTags = preserveCb,
                    neuralPureMode = neuralPureActive
                )
            }
        }

        while (output.lastOrNull() is PurrChunk.Silence) {
            output.removeAt(output.lastIndex)
        }
        return if ((symbolShieldModuleActive || neuralPureActive) && effectiveProfile.mikaStopConfig.targetChunkChars >= 40) {
            coalesceSentenceChunks(output, effectiveProfile.mikaStopConfig.targetChunkChars)
        } else {
            output
        }
    }

    /**
     * Multi-page & long-text audiobook export chunker for `TOOL-05: AUDIO_MEDIA_EXPORTER`
     * and `TOOL-07: AUDIOBOOK_CONSTRUCTOR`. Preserves paragraph pauses while merging complete
     * sentences into smooth blocks (never splitting mid-sentence).
     */
    fun buildExportChunks(
        rawText: String,
        profile: PurrProfile,
        customRules: List<PhoneticRule> = emptyList(),
        purrModuleActive: Boolean = true,
        symbolShieldModuleActive: Boolean = true,
        targetChunkCharsOverride: Int = 240,
        paragraphPauseMs: Int = 550,
        isChatterboxVoice: Boolean = false,
        clonedVoices: List<ClonedVoiceProfile> = emptyList()
    ): List<PurrChunk> {
        val normalized = normalizeTypography(rawText).trim()
        if (normalized.isEmpty()) return emptyList()

        val paragraphs = normalized
            .split(Regex("\\r?\\n\\s*\\r?\\n+"))
            .map { it.replace(Regex("\\r?\\n+"), " ").trim() }
            .filter { it.isNotEmpty() }

        val effectiveTarget = if (targetChunkCharsOverride >= 60) {
            targetChunkCharsOverride
        } else {
            maxOf(profile.mikaStopConfig.targetChunkChars, 220)
        }

        val exportProfile = profile.copy(
            mikaStopConfig = profile.mikaStopConfig.copy(targetChunkChars = effectiveTarget)
        )

        val combined = mutableListOf<PurrChunk>()
        for (para in paragraphs) {
            val rawParaChunks = process(
                rawText = para,
                profile = exportProfile,
                customRules = customRules,
                purrModuleActive = purrModuleActive,
                symbolShieldModuleActive = symbolShieldModuleActive,
                isChatterboxVoice = isChatterboxVoice,
                clonedVoices = clonedVoices
            )
            // Always coalesce complete sentences for smooth long-form exports even if Symbol Shield is off.
            val coalesced = coalesceSentenceChunks(rawParaChunks, effectiveTarget)
            if (coalesced.isNotEmpty()) {
                if (combined.isNotEmpty() && paragraphPauseMs > 0) {
                    appendSilenceMerged(combined, paragraphPauseMs.coerceIn(150, 2500), "PARAGRAPH")
                }
                combined.addAll(coalesced)
            }
        }
        while (combined.lastOrNull() is PurrChunk.Silence) {
            combined.removeAt(combined.lastIndex)
        }
        return combined
    }

    /**
     * Loose sentence-boundary coalescer for Audiobooks / Light Novels:
     * Merges complete adjacent sentences until reaching `targetChunkChars` while stopping at
     * newlines, explicit pauses, ellipses, or role switches — never ending mid-sentence.
     */
    fun coalesceSentenceChunks(
        chunks: List<PurrChunk>,
        targetChunkChars: Int
    ): List<PurrChunk> {
        if (targetChunkChars < 40 || chunks.size <= 1) return chunks
        val result = mutableListOf<PurrChunk>()
        var i = 0
        while (i < chunks.size) {
            val current = chunks[i]
            if (current is PurrChunk.Speech) {
                var acc: PurrChunk.Speech = current
                var j = i + 1
                while (j < chunks.size) {
                    val nextItem = chunks[j]
                    val nextAfterPause = chunks.getOrNull(j + 1) as? PurrChunk.Speech
                    val isMergeablePause = nextItem is PurrChunk.Silence &&
                        (nextItem.reason == "SENTENCE" || nextItem.reason == "CLAUSE")
                    if (isMergeablePause &&
                        nextAfterPause != null &&
                        acc.role == nextAfterPause.role &&
                        acc.targetVoiceId == nextAfterPause.targetVoiceId &&
                        acc.text.length < targetChunkChars
                    ) {
                        acc = acc.copy(text = "${acc.text} ${nextAfterPause.text}".trim())
                        j += 2
                    } else if (nextItem is PurrChunk.Speech &&
                        acc.role == nextItem.role &&
                        acc.targetVoiceId == nextItem.targetVoiceId &&
                        acc.text.length < targetChunkChars
                    ) {
                        acc = acc.copy(text = "${acc.text} ${nextItem.text}".trim())
                        j += 1
                    } else {
                        break
                    }
                }
                result += acc
                i = j
            } else {
                result += current
                i += 1
            }
        }
        return result
    }

    private fun splitIntoRoleSegments(
        text: String,
        actionMode: ActionHandlingMode,
        splitDialogueQuotes: Boolean
    ): List<RawRoleSegment> {
        val segments = mutableListOf<RawRoleSegment>()
        val splitRegex = if (splitDialogueQuotes) ROLE_SPLIT_ACTION_AND_QUOTE_REGEX else ROLE_SPLIT_ACTION_ONLY_REGEX
        var lastIndex = 0

        for (match in splitRegex.findAll(text)) {
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

        // Vaporize emojis & percent/ampersand symbols (tried & true GachaSwipe rule)
        clean = EMOJI_VAPORIZER_REGEX.replace(clean, " ")
        clean = clean.replace("%", " percent ")
        clean = clean.replace("&", " and ")

        // User-configured custom PhoneticRule entries from PURR Rulebook
        for (rule in customRules) {
            if (!rule.enabled || rule.pattern.isBlank()) continue
            val options = if (rule.ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
            val regex = runCatching {
                if (rule.isRegex) Regex(rule.pattern, options)
                else Regex("\\b${Regex.escape(rule.pattern)}\\b", options)
            }.getOrNull() ?: continue
            clean = regex.replace(clean) { rule.replacement }
        }

        // Normalize compound non-stutter hyphenated words (`co-worker` -> `co worker`, `twenty-five` -> `twenty five`)
        if (profile.enableSymbolShield) {
            clean = COMPOUND_HYPHEN_REGEX.replace(clean) { m ->
                val left = m.groupValues[1]
                val right = m.groupValues[2]
                val leftLow = left.lowercase()
                val rightLow = right.lowercase()
                val isHonorific = rightLow in HONORIFIC_SUFFIXES || (leftLow == "ara" && rightLow == "ara")
                val isDigraphStutter = leftLow in STUTTER_DIGRAPHS && rightLow.startsWith(leftLow)
                if (isHonorific || isDigraphStutter) m.value else "$left "
            }
        }

        if (profile.enableSlangAndHonorifics) {
            // Organic Stutter Translator: strictly repeated-letter stutters (`w-what` -> `wuh-what`, `th-that` -> `thuh-that`)
            clean = ORGANIC_STUTTER_REGEX.replace(clean) { m ->
                val prefix = m.groupValues[1].lowercase()
                val sound = if (prefix.length == 1 || prefix in STUTTER_DIGRAPHS) {
                    PHONETIC_STUTTER_MAP[prefix]
                } else {
                    null
                }
                if (sound != null) "$sound-" else m.value
            }

            // Anime honorifics & vocalisms (applied before em-dash/tilde split so `nya~` becomes `nyaah`)
            clean = applyAnimeHonorificsAndVocalisms(clean)

            // ~100 GachaSwipe texting slang expansions
            for ((slang, expanded) in SLANG_DICTIONARY) {
                val wordRegex = Regex("\\b${Regex.escape(slang)}\\b", RegexOption.IGNORE_CASE)
                clean = wordRegex.replace(clean) { expanded }
            }
        }

        // GachaSwipe Drama Matrix optional stutter / vowel stretch (with strict "mika" immunity)
        val tone = profile.sentimentConfig.toneOverride
        if (profile.sentimentConfig.enabled && tone != ArchetypeTone.NORMAL) {
            if ((tone == ArchetypeTone.SHY || tone == ArchetypeTone.SHY_LOVE ||
                    tone == ArchetypeTone.TSUNDERE_LOVE || tone == ArchetypeTone.SHY_SADNESS) &&
                profile.sentimentConfig.stutterIntensity > 0.01f
            ) {
                clean = applyStutter(clean, profile.sentimentConfig.stutterIntensity, rng)
            }
            if ((tone == ArchetypeTone.YANDERE_LOVE || tone == ArchetypeTone.YANDERE_ANGER ||
                    tone == ArchetypeTone.DOMINANT_LOVE) &&
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

    fun tokenizeWithMikaStop(
        input: String,
        config: MikaStopConfig,
        profile: PurrProfile? = null
    ): String {
        var out = input
        val abbrSentinel = "@@ABBR_DOT@@"

        // Normalize bare MIKASTOP keywords without touching [[MIKASTOP:...]]
        out = BARE_MIKASTOP_REGEX.replace(out, " [[MIKASTOP:${config.sentencePauseMs}:MIKASTOP]] ")

        // Protect ellipses with a temporary sentinel so sentence-end matching does not double-count pauses
        val ellipsisSentinel = "@@ELLIPSIS_MIKASTOP@@"
        out = ELLIPSIS_REGEX.replace(out, "$ellipsisSentinel ")

        // Protect common audiobook titles, abbreviations, initials, and decimals from premature sentence breaks
        out = ABBREVIATION_DOT_REGEX.replace(out) { m -> "${m.groupValues[1]}$abbrSentinel" }
        out = LATIN_ABBR_DOT_REGEX.replace(out) { m -> "${m.groupValues[1]}$abbrSentinel${m.groupValues[2]}$abbrSentinel" }
        out = INITIAL_DOT_REGEX.replace(out) { m -> "${m.groupValues[1]}$abbrSentinel" }
        out = DECIMAL_DOT_REGEX.replace(out, abbrSentinel)

        // Em-dashes, en-dashes, double hyphens, and stray tildes
        out = EM_DASH_TILDE_REGEX.replace(out, " [[MIKASTOP:${config.emDashPauseMs}:EM_DASH]] ")

        // Line breaks
        out = out.replace(Regex("\\n+"), " [[MIKASTOP:${config.sentencePauseMs}:NEWLINE]] ")

        // Sentence boundaries (preserving attached terminal punctuation)
        out = SENTENCE_END_REGEX.replace(out) { m ->
            "${m.groupValues[1]} [[MIKASTOP:${config.sentencePauseMs}:SENTENCE]] "
        }

        // Restore protected abbreviation & decimal periods
        out = out.replace(abbrSentinel, ".")

        // Comma / clause handling (respects Symbol Shield, min-word guard & loose multi-sentence chunking)
        val shieldOn = profile?.enableSymbolShield ?: false
        val commaMode = profile?.symbolShieldConfig?.commaHandling ?: CommaHandlingMode.MIKA_PAUSE_AND_STRIP
        val keepWholeSentences = config.targetChunkChars >= 40

        if (keepWholeSentences) {
            if (shieldOn && commaMode != CommaHandlingMode.KEEP_RAW) {
                out = CLAUSE_PAUSE_REGEX.replace(out, " ")
            }
        } else if (shieldOn && commaMode == CommaHandlingMode.STRIP_NO_PAUSE) {
            out = CLAUSE_PAUSE_REGEX.replace(out, " ")
        } else if (config.sliceAtCommas) {
            val minWords = config.minWordsForCommaPause.coerceAtLeast(1)
            val sb = StringBuilder(out.length + 32)
            var cursor = 0
            var lastEmittedPauseInOut = 0
            for (match in CLAUSE_PAUSE_REGEX.findAll(out)) {
                val tokenIdx = out.lastIndexOf("[[MIKASTOP:", match.range.first)
                val tokenEnd = if (tokenIdx >= 0) {
                    val closeIdx = out.indexOf("]]", tokenIdx)
                    if (closeIdx in 0 until match.range.first) closeIdx + 2 else 0
                } else {
                    0
                }
                val segStart = maxOf(tokenEnd, lastEmittedPauseInOut)
                val sliceBeforeComma = out.substring(segStart, match.range.first)
                val wordCount = WORD_TOKEN_REGEX.findAll(sliceBeforeComma).count()
                sb.append(out.substring(cursor, match.range.first))
                if (wordCount >= minWords) {
                    val punct = if (shieldOn && commaMode != CommaHandlingMode.KEEP_RAW) "" else match.groupValues[1]
                    sb.append(punct).append(" [[MIKASTOP:${config.commaPauseMs}:CLAUSE]] ")
                    lastEmittedPauseInOut = match.range.last + 1
                } else {
                    if (shieldOn && commaMode != CommaHandlingMode.KEEP_RAW) {
                        sb.append(' ')
                    } else {
                        sb.append(match.value)
                    }
                }
                cursor = match.range.last + 1
            }
            if (cursor < out.length) {
                sb.append(out.substring(cursor))
            }
            out = sb.toString()
        } else if (shieldOn && commaMode != CommaHandlingMode.KEEP_RAW) {
            out = CLAUSE_PAUSE_REGEX.replace(out, " ")
        }

        // Expand protected ellipsis sentinels into exact MIKASTOP silence boundaries
        out = out.replace(ellipsisSentinel, ". [[MIKASTOP:${config.ellipsisPauseMs}:ELLIPSIS]] ")

        return out
    }

    fun sanitizeSpeechChunk(
        rawChunk: String,
        profile: PurrProfile = PurrProfile(id = "default", name = "Default"),
        preserveChatterboxTags: Boolean = false,
        neuralPureMode: Boolean = false
    ): String {
        var chunk = normalizeTypography(rawChunk).trim()
        if (chunk.isEmpty()) return ""

        val preservedCbTags = mutableListOf<String>()
        if (preserveChatterboxTags) {
            chunk = CHATTERBOX_TAG_REGEX.replace(chunk) { m ->
                val idx = preservedCbTags.size
                preservedCbTags += "[${m.groupValues[1].lowercase()}]"
                " purrcbtag${idx}purr "
            }
        }

        if (!neuralPureMode) {
            // Ensure phonetic sighs trigger breath artifacts on classic synthesizers
            chunk = SIGH_REGEX.replace(chunk) { m -> "${m.groupValues[1]}. " }
        }

        // Shred stray HTML/XML tags
        chunk = XML_TAG_REGEX.replace(chunk, " ")

        if (neuralPureMode) {
            // Neural Pure Mode: strip markdown formatting wrappers (* _ # ` ~ []) while keeping natural
            // quotes, commas, ellipses, capitalization, and expressive punctuation intact.
            chunk = chunk.replace(Regex("[*_`~^|\\\\]"), " ")
            if (!preserveChatterboxTags) {
                chunk = chunk.replace(Regex("[\\[\\]{}<>]"), " ")
            }
            chunk = COLLISION_SPACE_REGEX.replace(chunk, "$1")
            chunk = MULTI_SPACE_REGEX.replace(chunk, " ").trim()
        } else if (profile.enableSymbolShield) {
            val shield = profile.symbolShieldConfig
            if (shield.expandCommonSymbols) {
                chunk = chunk
                    .replace("@", " at ")
                    .replace("+", " plus ")
                    .replace("=", " equals ")
            }
            if (shield.stripBracketsAndMarkdown) {
                chunk = BRACKETS_AND_MARKDOWN_REGEX.replace(chunk, " ")
            }
            if (shield.stripQuotationMarks) {
                chunk = QUOTES_AND_LONE_APOSTROPHES_REGEX.replace(chunk, "")
            }
            if (shield.customSkippedChars.isNotEmpty()) {
                val skipSet = shield.customSkippedChars.toSet()
                val sb = StringBuilder(chunk.length)
                for (i in chunk.indices) {
                    val c = chunk[i]
                    val isInnerContraction = c == '\'' &&
                        i > 0 && i < chunk.lastIndex &&
                        chunk[i - 1].isLetter() && chunk[i + 1].isLetter()
                    if (c !in skipSet || isInnerContraction || c in ".?! ") {
                        sb.append(c)
                    } else {
                        sb.append(' ')
                    }
                }
                chunk = sb.toString()
            }
            if (shield.commaHandling != CommaHandlingMode.KEEP_RAW) {
                chunk = chunk.replace(Regex("[,;:]"), " ")
            }
        } else {
            chunk = WEIRD_ASCII_REGEX.replace(chunk, "")
        }

        if (!neuralPureMode) {
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
        }

        if (chunk.isEmpty()) return ""

        // Guarantee terminal punctuation (commas preserved only if KEEP_RAW)
        val terminalRegex = if (neuralPureMode) {
            Regex("[.!?,;:][\"'”’)]*$")
        } else if (!profile.enableSymbolShield ||
            profile.symbolShieldConfig.commaHandling == CommaHandlingMode.KEEP_RAW
        ) {
            Regex("[.!?,;:]$")
        } else {
            Regex("[.!?]$")
        }
        if (!terminalRegex.containsMatchIn(chunk)) {
            chunk = chunk.replace(Regex("[^\\p{L}0-9\"'”’)]$"), "") + "."
        }

        if (chunk == "." || chunk == "," || chunk == "!" || chunk == "?") return ""
        if (preservedCbTags.isNotEmpty()) {
            chunk = CHATTERBOX_RESTORE_REGEX.replace(chunk) { m ->
                val idx = m.groupValues[1].toIntOrNull()
                if (idx != null && idx in preservedCbTags.indices) preservedCbTags[idx] else ""
            }
            chunk = COLLISION_SPACE_REGEX.replace(chunk, "$1")
            chunk = MULTI_SPACE_REGEX.replace(chunk, " ").trim()
        }
        return chunk
    }

    private fun emitSpeechChunkIfValid(
        rawChunk: String,
        role: SegmentRole,
        targetVoiceId: String?,
        prosody: PurrSentimentScorer.ProsodyModulation,
        sentiment: SentimentResult,
        profile: PurrProfile,
        out: MutableList<PurrChunk>,
        preserveChatterboxTags: Boolean = false,
        neuralPureMode: Boolean = false
    ) {
        val sanitized = sanitizeSpeechChunk(
            rawChunk = rawChunk,
            profile = profile,
            preserveChatterboxTags = preserveChatterboxTags,
            neuralPureMode = neuralPureMode
        )
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
            profile.enableDualVoiceSplit &&
            profile.dialogueVoiceId.isNotBlank()
        ) {
            profile.dialogueVoiceId
        } else {
            profile.primaryVoiceId
        }
    }
}
