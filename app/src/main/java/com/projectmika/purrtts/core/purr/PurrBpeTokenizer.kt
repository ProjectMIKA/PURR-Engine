package com.projectmika.purrtts.core.purr

import java.io.BufferedReader
import java.io.File
import java.io.Reader
import java.io.StringReader
import java.text.Normalizer

/**
 * ✨ MIKA'S PURE-KOTLIN HUGGING FACE BPE TOKENIZER ✨
 * Parses Hugging Face `tokenizer.json` directly with zero Python/Rust runtime dependencies:
 * - Honors `added_tokens` (O(1) bracket/angle-bracket matching so 65k+ `<|speech_N|>` tokens
 *   plus `[STOP]`, `[TEXT]`, `[START]`, `[laugh]`, `[sigh]`, `<|HAPPY|>` resolve in nanoseconds)
 * - Honors `normalizer` (e.g. Soprano's `Lowercase` + whitespace collapse)
 * - Honors `pre_tokenizer` (`ByteLevel` GPT-2/Qwen for Chatterbox & NeuTTS-2E vs
 *   `Digits + Split` literal UTF-8 BPE for Soprano-80M)
 * - Supports both `"a b"` string merges and `["a", "b"]` pair array merges
 *
 * PURE — no Android imports — unit-testable in `PurrHeavyEnginesTest`.
 */
class PurrBpeTokenizer private constructor(
    private val vocab: Map<String, Int>,
    private val idToToken: Map<Int, String>,
    private val addedTokens: Map<String, Int>,
    private val mergeRanks: Map<Long, Int>,
    private val useByteLevel: Boolean,
    private val lowercaseNormalizer: Boolean,
    private val collapseWhitespace: Boolean,
    private val splitDigitsIndividually: Boolean,
    private val unkId: Int
) {
    private val bpeCache = HashMap<String, List<String>>(2048)

    // Matches `<|...|>` or `[...]` special control / paralinguistic tokens fast before general regex
    private val controlCandidateRegex = Regex("<\\|[^|>\\s]{1,48}\\|>|\\[[a-zA-Z0-9_ ]{1,32}]")
    private val byteLevelPreTokRegex = Regex("'s|'t|'re|'ve|'m|'ll|'d| ?\\p{L}+| ?\\p{N}+| ?[^\\s\\p{L}\\p{N}]+|\\s+")
    private val splitDigitsPreTokRegex = Regex("\\s+|\\d|[\\p{L}_]+|[^\\w\\s]+")

    fun tokenToId(token: String): Int? = addedTokens[token] ?: vocab[token]

    fun encode(text: String, addSpecialControlScan: Boolean = true): LongArray {
        if (text.isEmpty()) return LongArray(0)
        val outIds = ArrayList<Int>(text.length / 2 + 16)

        if (!addSpecialControlScan || addedTokens.isEmpty()) {
            encodePlainSegment(text, outIds)
        } else {
            var cursor = 0
            for (m in controlCandidateRegex.findAll(text)) {
                val cand = m.value
                val specialId = addedTokens[cand] ?: continue
                if (m.range.first > cursor) {
                    encodePlainSegment(text.substring(cursor, m.range.first), outIds)
                }
                outIds.add(specialId)
                cursor = m.range.last + 1
            }
            if (cursor < text.length) {
                encodePlainSegment(text.substring(cursor), outIds)
            }
        }

        return LongArray(outIds.size) { idx -> outIds[idx].toLong() }
    }

    fun decode(ids: List<Int>, skipSpecialTokens: Boolean = false): String {
        if (ids.isEmpty()) return ""
        val sb = StringBuilder(ids.size * 3)
        for (id in ids) {
            val tok = idToToken[id] ?: continue
            if (skipSpecialTokens && tok in addedTokens) continue
            sb.append(tok)
        }
        val joined = sb.toString()
        return if (useByteLevel && !skipSpecialTokens) {
            decodeByteLevelString(joined)
        } else {
            joined
        }
    }

    /** Decode only plain tokens cleanly to UTF-8 while keeping control tokens readable as-is. */
    fun decodeToRawControlText(ids: List<Int>): String {
        val sb = StringBuilder(ids.size * 4)
        for (id in ids) {
            val tok = idToToken[id] ?: continue
            sb.append(tok)
        }
        return sb.toString()
    }

    private fun encodePlainSegment(segment: String, out: MutableList<Int>) {
        if (segment.isEmpty()) return
        var normalized = segment
        if (lowercaseNormalizer) normalized = normalized.lowercase()
        if (collapseWhitespace) normalized = normalized.replace(Regex("\\s+"), " ")

        val preRegex = if (useByteLevel) byteLevelPreTokRegex else splitDigitsPreTokRegex
        for (match in preRegex.findAll(normalized)) {
            val chunk = match.value
            if (chunk.isEmpty()) continue
            if (!useByteLevel && splitDigitsIndividually && chunk.length > 1 && chunk.all { it.isDigit() }) {
                for (ch in chunk) {
                    emitBpePiece(ch.toString(), out)
                }
                continue
            }
            val transformed = if (useByteLevel) toByteLevelUnicode(chunk) else chunk
            emitBpePiece(transformed, out)
        }
    }

    private fun emitBpePiece(piece: String, out: MutableList<Int>) {
        val directId = vocab[piece]
        if (directId != null) {
            out.add(directId)
            return
        }
        val bpeTokens = runBpe(piece)
        for (tok in bpeTokens) {
            val id = vocab[tok]
            if (id != null) {
                out.add(id)
            } else if (tok.length > 1) {
                for (ch in tok) {
                    out.add(vocab[ch.toString()] ?: unkId)
                }
            } else {
                out.add(unkId)
            }
        }
    }

    private fun runBpe(word: String): List<String> {
        if (word.length <= 1) return listOf(word)
        bpeCache[word]?.let { return it }

        val parts = ArrayList<String>(word.length)
        for (ch in word) parts.add(ch.toString())

        while (parts.size >= 2) {
            var bestRank = Int.MAX_VALUE
            var bestPairLeft: String? = null
            var bestPairRight: String? = null

            for (i in 0 until parts.size - 1) {
                val left = parts[i]
                val right = parts[i + 1]
                val idL = vocab[left] ?: continue
                val idR = vocab[right] ?: continue
                val key = (idL.toLong() shl 32) or (idR.toLong() and 0xFFFFFFFFL)
                val rank = mergeRanks[key] ?: continue
                if (rank < bestRank) {
                    bestRank = rank
                    bestPairLeft = left
                    bestPairRight = right
                }
            }
            if (bestPairLeft == null || bestPairRight == null) break

            val merged = bestPairLeft + bestPairRight
            var i = 0
            val next = ArrayList<String>(parts.size)
            while (i < parts.size) {
                if (i < parts.size - 1 && parts[i] == bestPairLeft && parts[i + 1] == bestPairRight) {
                    next.add(merged)
                    i += 2
                } else {
                    next.add(parts[i])
                    i += 1
                }
            }
            parts.clear()
            parts.addAll(next)
        }

        val frozen = parts.toList()
        if (bpeCache.size < 8192) {
            bpeCache[word] = frozen
        }
        return frozen
    }

    private fun toByteLevelUnicode(text: String): String {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder(bytes.size)
        for (b in bytes) {
            sb.append(BYTE_TO_UNICODE[b.toInt() and 0xFF])
        }
        return sb.toString()
    }

    private fun decodeByteLevelString(encoded: String): String {
        val byteBuf = ArrayList<Byte>(encoded.length)
        for (ch in encoded) {
            val b = UNICODE_TO_BYTE[ch]
            if (b != null) {
                byteBuf.add(b.toByte())
            } else {
                for (u in ch.toString().toByteArray(Charsets.UTF_8)) {
                    byteBuf.add(u)
                }
            }
        }
        return byteBuf.toByteArray().toString(Charsets.UTF_8)
    }

    companion object {
        private val BYTE_TO_UNICODE: CharArray = buildByteToUnicodeTable()
        private val UNICODE_TO_BYTE: Map<Char, Int> = buildMap {
            for (b in 0..255) {
                put(BYTE_TO_UNICODE[b], b)
            }
        }

        private fun buildByteToUnicodeTable(): CharArray {
            val table = CharArray(256)
            val isDirect = BooleanArray(256)
            for (b in 33..126) isDirect[b] = true
            for (b in 161..172) isDirect[b] = true
            for (b in 174..255) isDirect[b] = true

            var n = 0
            for (b in 0..255) {
                table[b] = if (isDirect[b]) b.toChar() else (256 + n++).toChar()
            }
            return table
        }

        fun fromFile(tokenizerJsonFile: File): PurrBpeTokenizer =
            tokenizerJsonFile.bufferedReader(Charsets.UTF_8, 65536).use { br ->
                fromReader(br)
            }

        fun fromJsonString(jsonText: String): PurrBpeTokenizer =
            StringReader(jsonText).use { sr -> fromReader(sr) }

        private fun fromReader(reader: Reader): PurrBpeTokenizer {
            val vocabMap = HashMap<String, Int>(16384)
            val idMap = HashMap<Int, String>(16384)
            val addedMap = HashMap<String, Int>(256)
            val mergeRanks = HashMap<Long, Int>(16384)
            val deferredMerges = ArrayList<Pair<String, String>>()

            var useByteLevel = false
            var lowercaseNormalizer = false
            var collapseWhitespace = false
            var splitDigitsIndividually = false
            var hasModel = false
            var hasVocab = false
            var unkTok: String? = null

            val parser = StreamingTokenizerJsonReader(reader)
            parser.expect('{')
            val first = parser.skipWs()
            if (first != '}'.code) {
                parser.unreadChar(first)
                while (true) {
                    val key = parser.readString()
                    parser.expect(':')
                    when (key) {
                        "normalizer" -> {
                            val flags = HashSet<String>(8)
                            parser.scanStringTokens(flags)
                            lowercaseNormalizer = "Lowercase" in flags
                            collapseWhitespace = "Replace" in flags
                        }
                        "pre_tokenizer" -> {
                            val flags = HashSet<String>(8)
                            parser.scanStringTokens(flags)
                            useByteLevel = "ByteLevel" in flags
                            splitDigitsIndividually = "Digits" in flags
                        }
                        "added_tokens" -> parser.parseAddedTokens(addedMap, vocabMap, idMap)
                        "model" -> {
                            hasModel = true
                            val mRes = parser.parseModelObject(vocabMap, idMap, mergeRanks, deferredMerges)
                            hasVocab = mRes.first
                            unkTok = mRes.second
                        }
                        else -> parser.skipValue()
                    }
                    val delim = parser.skipWs()
                    if (delim == ','.code) continue
                    if (delim == '}'.code) break
                    throw IllegalArgumentException("Expected ',' or '}' at top-level tokenizer.json, got ${delim.toChar()}")
                }
            }

            if (!hasModel) throw IllegalArgumentException("tokenizer.json missing `model` object")
            if (!hasVocab) throw IllegalArgumentException("tokenizer.json missing `model.vocab`")

            if (deferredMerges.isNotEmpty()) {
                deferredMerges.forEachIndexed { rank, pair ->
                    val idL = vocabMap[pair.first]
                    val idR = vocabMap[pair.second]
                    if (idL != null && idR != null) {
                        val key = (idL.toLong() shl 32) or (idR.toLong() and 0xFFFFFFFFL)
                        mergeRanks.putIfAbsent(key, rank)
                    }
                }
            }

            val unkId = unkTok?.let { vocabMap[it] }
                ?: vocabMap["[UNK]"]
                ?: vocabMap["<unk>"]
                ?: 0

            return PurrBpeTokenizer(
                vocab = vocabMap,
                idToToken = idMap,
                addedTokens = addedMap,
                mergeRanks = mergeRanks,
                useByteLevel = useByteLevel,
                lowercaseNormalizer = lowercaseNormalizer,
                collapseWhitespace = collapseWhitespace,
                splitDigitsIndividually = splitDigitsIndividually,
                unkId = unkId
            )
        }
    }
}

/**
 * Zero-AST streaming JSON parser tailored for HuggingFace `tokenizer.json`.
 * Prevents 200MB+ DOM/String heap spikes on Android when loading 150k-vocab + 65k-added-token files.
 */
private class StreamingTokenizerJsonReader(private val reader: Reader) {
    private var pushedBack: Int = -2
    private val strBuf = StringBuilder(64)

    fun nextChar(): Int {
        if (pushedBack != -2) {
            val c = pushedBack
            pushedBack = -2
            return c
        }
        return reader.read()
    }

    fun unreadChar(c: Int) {
        pushedBack = c
    }

    fun skipWs(): Int {
        var c = nextChar()
        while (c == ' '.code || c == '\t'.code || c == '\n'.code || c == '\r'.code) {
            c = nextChar()
        }
        return c
    }

    fun expect(expected: Char) {
        val c = skipWs()
        if (c != expected.code) {
            throw IllegalArgumentException("Expected '$expected', got '${if (c == -1) "EOF" else c.toChar().toString()}'")
        }
    }

    fun readString(): String {
        expect('"')
        strBuf.setLength(0)
        while (true) {
            val c = nextChar()
            if (c == -1) throw IllegalArgumentException("Unterminated JSON string")
            if (c == '"'.code) break
            if (c == '\\'.code) {
                val esc = nextChar()
                when (esc) {
                    '"'.code, '\\'.code, '/'.code -> strBuf.append(esc.toChar())
                    'b'.code -> strBuf.append('\b')
                    'f'.code -> strBuf.append('\u000C')
                    'n'.code -> strBuf.append('\n')
                    'r'.code -> strBuf.append('\r')
                    't'.code -> strBuf.append('\t')
                    'u'.code -> {
                        var code = 0
                        for (i in 0 until 4) {
                            val h = nextChar()
                            val d = when (h) {
                                in '0'.code..'9'.code -> h - '0'.code
                                in 'a'.code..'f'.code -> 10 + (h - 'a'.code)
                                in 'A'.code..'F'.code -> 10 + (h - 'A'.code)
                                else -> throw IllegalArgumentException("Invalid \\u escape in JSON")
                            }
                            code = (code shl 4) or d
                        }
                        strBuf.append(code.toChar())
                    }
                    -1 -> throw IllegalArgumentException("Unterminated escape sequence in JSON string")
                    else -> strBuf.append(esc.toChar())
                }
            } else {
                strBuf.append(c.toChar())
            }
        }
        return strBuf.toString()
    }

    fun readIntOrNull(): Int? {
        var c = skipWs()
        if (c == 'n'.code) {
            unreadChar(c)
            skipPrimitive()
            return null
        }
        var neg = false
        if (c == '-'.code) {
            neg = true
            c = nextChar()
        }
        if (c !in '0'.code..'9'.code) {
            unreadChar(c)
            skipPrimitive()
            return null
        }
        var valAcc = 0L
        while (c in '0'.code..'9'.code) {
            valAcc = valAcc * 10L + (c - '0'.code)
            c = nextChar()
        }
        while (c == '.'.code || c == 'e'.code || c == 'E'.code || c == '+'.code || c == '-'.code || c in '0'.code..'9'.code) {
            c = nextChar()
        }
        unreadChar(c)
        return (if (neg) -valAcc else valAcc).toInt()
    }

    fun readStringOrNull(): String? {
        val c = skipWs()
        unreadChar(c)
        if (c == '"'.code) return readString()
        skipValue()
        return null
    }

    private fun skipPrimitive() {
        var c = nextChar()
        while (c != -1 && c != ','.code && c != '}'.code && c != ']'.code &&
            c != ' '.code && c != '\t'.code && c != '\n'.code && c != '\r'.code
        ) {
            c = nextChar()
        }
        unreadChar(c)
    }

    fun skipValue() {
        val c = skipWs()
        when (c) {
            '{'.code -> {
                val n = skipWs()
                if (n != '}'.code) {
                    unreadChar(n)
                    while (true) {
                        readString()
                        expect(':')
                        skipValue()
                        val d = skipWs()
                        if (d == ','.code) continue
                        if (d == '}'.code) break
                        throw IllegalArgumentException("Invalid object in JSON")
                    }
                }
            }
            '['.code -> {
                val n = skipWs()
                if (n != ']'.code) {
                    unreadChar(n)
                    while (true) {
                        skipValue()
                        val d = skipWs()
                        if (d == ','.code) continue
                        if (d == ']'.code) break
                        throw IllegalArgumentException("Invalid array in JSON")
                    }
                }
            }
            '"'.code -> {
                unreadChar(c)
                readString()
            }
            -1 -> Unit
            else -> {
                unreadChar(c)
                skipPrimitive()
            }
        }
    }

    fun scanStringTokens(outStrings: MutableSet<String>) {
        val c = skipWs()
        when (c) {
            '{'.code -> {
                val n = skipWs()
                if (n != '}'.code) {
                    unreadChar(n)
                    while (true) {
                        readString()
                        expect(':')
                        scanStringTokens(outStrings)
                        val d = skipWs()
                        if (d == ','.code) continue
                        if (d == '}'.code) break
                        throw IllegalArgumentException("Malformed object while scanning section flags")
                    }
                }
            }
            '['.code -> {
                val n = skipWs()
                if (n != ']'.code) {
                    unreadChar(n)
                    while (true) {
                        scanStringTokens(outStrings)
                        val d = skipWs()
                        if (d == ','.code) continue
                        if (d == ']'.code) break
                        throw IllegalArgumentException("Malformed array while scanning section flags")
                    }
                }
            }
            '"'.code -> {
                unreadChar(c)
                val s = readString()
                if (s.length <= 32) outStrings.add(s)
            }
            else -> {
                unreadChar(c)
                skipPrimitive()
            }
        }
    }

    fun parseAddedTokens(
        addedMap: MutableMap<String, Int>,
        vocabMap: MutableMap<String, Int>,
        idMap: MutableMap<Int, String>
    ) {
        val c = skipWs()
        if (c == 'n'.code) {
            unreadChar(c)
            skipPrimitive()
            return
        }
        if (c != '['.code) {
            unreadChar(c)
            skipValue()
            return
        }
        val first = skipWs()
        if (first == ']'.code) return
        unreadChar(first)
        while (true) {
            expect('{')
            val f2 = skipWs()
            var id: Int? = null
            var content: String? = null
            if (f2 != '}'.code) {
                unreadChar(f2)
                while (true) {
                    val k = readString()
                    expect(':')
                    when (k) {
                        "id" -> id = readIntOrNull()
                        "content" -> content = readStringOrNull()
                        else -> skipValue()
                    }
                    val d = skipWs()
                    if (d == ','.code) continue
                    if (d == '}'.code) break
                    throw IllegalArgumentException("Malformed added_tokens entry")
                }
            }
            if (id != null && content != null) {
                addedMap[content] = id
                vocabMap.putIfAbsent(content, id)
                idMap.putIfAbsent(id, content)
            }
            val arrD = skipWs()
            if (arrD == ','.code) continue
            if (arrD == ']'.code) break
            throw IllegalArgumentException("Malformed added_tokens array")
        }
    }

    fun parseModelObject(
        vocabMap: MutableMap<String, Int>,
        idMap: MutableMap<Int, String>,
        mergeRanks: MutableMap<Long, Int>,
        deferredMerges: MutableList<Pair<String, String>>
    ): Pair<Boolean, String?> {
        val c = skipWs()
        if (c != '{'.code) {
            unreadChar(c)
            skipValue()
            return false to null
        }
        var hasVocab = false
        var unkTok: String? = null
        val first = skipWs()
        if (first != '}'.code) {
            unreadChar(first)
            while (true) {
                val k = readString()
                expect(':')
                when (k) {
                    "vocab" -> {
                        hasVocab = true
                        parseVocabMap(vocabMap, idMap)
                    }
                    "merges" -> parseMergesArray(vocabMap, mergeRanks, deferredMerges)
                    "unk_token" -> unkTok = readStringOrNull()
                    else -> skipValue()
                }
                val d = skipWs()
                if (d == ','.code) continue
                if (d == '}'.code) break
                throw IllegalArgumentException("Malformed model object in tokenizer.json")
            }
        }
        return hasVocab to unkTok
    }

    private fun parseVocabMap(
        vocabMap: MutableMap<String, Int>,
        idMap: MutableMap<Int, String>
    ) {
        expect('{')
        val first = skipWs()
        if (first == '}'.code) return
        unreadChar(first)
        while (true) {
            val tok = readString()
            expect(':')
            val id = readIntOrNull()
            if (id != null) {
                vocabMap[tok] = id
                idMap[id] = tok
            }
            val d = skipWs()
            if (d == ','.code) continue
            if (d == '}'.code) break
            throw IllegalArgumentException("Malformed vocab map in tokenizer.json")
        }
    }

    private fun parseMergesArray(
        vocabMap: Map<String, Int>,
        mergeRanks: MutableMap<Long, Int>,
        deferredMerges: MutableList<Pair<String, String>>
    ) {
        val c = skipWs()
        if (c == 'n'.code) {
            unreadChar(c)
            skipPrimitive()
            return
        }
        if (c != '['.code) {
            unreadChar(c)
            skipValue()
            return
        }
        val first = skipWs()
        if (first == ']'.code) return
        unreadChar(first)

        var rank = 0
        while (true) {
            val itemC = skipWs()
            var pair: Pair<String, String>? = null
            when (itemC) {
                '"'.code -> {
                    unreadChar(itemC)
                    val s = readString()
                    val sp = s.indexOf(' ')
                    if (sp > 0 && sp < s.lastIndex) {
                        pair = s.substring(0, sp) to s.substring(sp + 1)
                    }
                }
                '['.code -> {
                    val f = skipWs()
                    if (f != ']'.code) {
                        unreadChar(f)
                        val l = readStringOrNull()
                        val d1 = skipWs()
                        var r: String? = null
                        if (d1 == ','.code) {
                            r = readStringOrNull()
                            var d2 = skipWs()
                            while (d2 == ','.code) {
                                skipValue()
                                d2 = skipWs()
                            }
                            if (d2 != ']'.code) throw IllegalArgumentException("Expected ']' in merge pair")
                        } else if (d1 != ']'.code) {
                            throw IllegalArgumentException("Malformed merge pair array")
                        }
                        if (l != null && r != null) pair = l to r
                    }
                }
                else -> {
                    unreadChar(itemC)
                    skipValue()
                }
            }

            if (pair != null) {
                if (vocabMap.isNotEmpty() && deferredMerges.isEmpty()) {
                    val idL = vocabMap[pair.first]
                    val idR = vocabMap[pair.second]
                    if (idL != null && idR != null) {
                        val key = (idL.toLong() shl 32) or (idR.toLong() and 0xFFFFFFFFL)
                        mergeRanks.putIfAbsent(key, rank)
                    }
                } else {
                    deferredMerges.add(pair)
                }
                rank++
            }

            val d = skipWs()
            if (d == ','.code) continue
            if (d == ']'.code) break
            throw IllegalArgumentException("Malformed merges array")
        }
    }
}

/**
 * ✨ PURE-KOTLIN GRADIUM PHONON UNIGRAM + BYTE-FALLBACK TOKENIZER ✨
 * Parses Hugging Face Unigram `tokenizer.json` (`model.vocab` [[piece, score], ...]) for Phonon 31M:
 * - Applies Phonon's quote/dash/diacritic/sentence-case/trailing-period normalization + Metaspace (`▁`)
 * - Viterbi DP longest/highest-probability segmentation across real subword pieces
 * - UTF-8 `<0xXX>` byte-fallback encoding for unseen characters
 */
class PurrPhononTokenizer private constructor(
    private val pieceToEntry: Map<String, Pair<Int, Float>>,
    private val byteTokenIds: IntArray,
    private val unkId: Int,
    private val maxPieceLen: Int
) {

    fun encode(rawText: String, normalizeSentence: Boolean = true): LongArray {
        val text = if (normalizeSentence) normalizeForPhonon(rawText) else rawText.trim()
        if (text.isEmpty()) return LongArray(0)
        val escaped = "\u2581" + text.replace(' ', '\u2581')
        val n = escaped.length
        val bestScore = FloatArray(n + 1) { Float.NEGATIVE_INFINITY }
        val prevIdx = IntArray(n + 1) { -1 }
        val chosenIds = Array<IntArray?>(n + 1) { null }
        bestScore[0] = 0f

        for (i in 0 until n) {
            val base = bestScore[i]
            if (base.isInfinite() && base < 0f) continue
            val maxLen = minOf(maxPieceLen, n - i)
            var matchedChar = false
            for (len in 1..maxLen) {
                val sub = escaped.substring(i, i + len)
                val entry = pieceToEntry[sub] ?: continue
                if (len == 1) matchedChar = true
                val cand = base + entry.second
                if (cand > bestScore[i + len]) {
                    bestScore[i + len] = cand
                    prevIdx[i + len] = i
                    chosenIds[i + len] = intArrayOf(entry.first)
                }
            }
            if (!matchedChar) {
                val step = if (Character.isHighSurrogate(escaped[i]) && i + 1 < n && Character.isLowSurrogate(escaped[i + 1])) 2 else 1
                val bytes = escaped.substring(i, i + step).toByteArray(Charsets.UTF_8)
                val cand = base - (40.0f * bytes.size)
                if (cand > bestScore[i + step]) {
                    bestScore[i + step] = cand
                    prevIdx[i + step] = i
                    chosenIds[i + step] = IntArray(bytes.size) { bIdx ->
                        byteTokenIds[bytes[bIdx].toInt() and 0xFF]
                    }
                }
            }
        }

        val reversedChunks = ArrayList<IntArray>()
        var cur = n
        while (cur > 0) {
            val ids = chosenIds[cur]
            val p = prevIdx[cur]
            if (ids == null || p < 0) break
            reversedChunks += ids
            cur = p
        }
        val flat = ArrayList<Long>(n)
        for (k in reversedChunks.lastIndex downTo 0) {
            for (id in reversedChunks[k]) {
                flat += id.toLong()
            }
        }
        return flat.toLongArray()
    }

    /**
     * Splits arbitrary prose into Phonon-safe sentence/clause chunks (`<= maxTokens` soft cap),
     * returning `(tokenIds, wordCount)` per chunk so short-text EOS framing matches upstream.
     */
    fun splitIntoChunks(text: String, maxTokens: Int = 48): List<Pair<LongArray, Int>> {
        val cleaned = text.replace(Regex("\\s+"), " ").trim()
        if (cleaned.isEmpty()) return emptyList()
        val sentenceRegex = Regex("""[^.!?;]+(?:[.!?;]+|$)""")
        val rawSentences = sentenceRegex.findAll(cleaned).map { it.value.trim() }.filter { it.isNotEmpty() }.toList()
        val units = ArrayList<String>()

        for (sent in rawSentences.ifEmpty { listOf(cleaned) }) {
            val encoded = encode(sent)
            if (encoded.size <= maxTokens) {
                units += sent
            } else {
                val clauses = sent.split(Regex("""(?<=[,:;])\s+""")).filter { it.isNotBlank() }
                var buf = StringBuilder()
                for (clause in clauses) {
                    val words = clause.split(' ').filter { it.isNotEmpty() }
                    for (w in words) {
                        val candidate = if (buf.isEmpty()) w else "${buf} $w"
                        if (buf.isNotEmpty() && encode(candidate).size > maxTokens) {
                            units += buf.toString()
                            buf = StringBuilder(w)
                        } else {
                            if (buf.isNotEmpty()) buf.append(' ')
                            buf.append(w)
                        }
                    }
                }
                if (buf.isNotEmpty()) units += buf.toString()
            }
        }

        val merged = ArrayList<String>()
        var curBuf = StringBuilder()
        for (u in units) {
            if (curBuf.isEmpty()) {
                curBuf.append(u)
            } else {
                val combined = "$curBuf $u"
                if (encode(combined).size <= maxTokens) {
                    curBuf.append(' ').append(u)
                } else {
                    merged += curBuf.toString()
                    curBuf = StringBuilder(u)
                }
            }
        }
        if (curBuf.isNotEmpty()) merged += curBuf.toString()

        return merged.mapNotNull { chunkText ->
            val ids = encode(chunkText)
            if (ids.isEmpty()) null else {
                val words = chunkText.trim().split(Regex("\\s+")).count { it.isNotBlank() }.coerceAtLeast(1)
                ids to words
            }
        }
    }

    companion object {
        fun normalizeForPhonon(raw: String): String {
            var s = raw.replace(Regex("\\s+"), " ").trim()
            if (s.isEmpty()) return ""
            s = s.replace(Regex("""[“”»«‹›„‟″‶⹂〝〞＂]"""), "\"")
                .replace(Regex("""[‘’‚‛′‵՚＇ʼʻʹ´`]"""), "'")
                .replace(Regex("""[‐‑‒―–—]"""), "-")
            s = Normalizer.normalize(s, Normalizer.Form.NFD)
                .replace(Regex("\\p{Mn}+"), "")
                .trim()
            if (s.isEmpty()) return ""
            if (s[0].isLowerCase()) {
                s = s[0].uppercaseChar() + s.substring(1)
            }
            if (Character.isLetterOrDigit(s.last())) {
                s += "."
            }
            return s
        }

        fun fromFile(tokenizerJsonFile: File): PurrPhononTokenizer =
            tokenizerJsonFile.bufferedReader(Charsets.UTF_8).use { fromReader(it) }

        fun fromJsonString(json: String): PurrPhononTokenizer =
            StringReader(json).use { fromReader(it) }

        fun fromReader(reader: Reader): PurrPhononTokenizer {
            val br = if (reader is BufferedReader) reader else BufferedReader(reader, 64 * 1024)
            val parser = PhononJsonStreamParser(br)
            return parser.parse()
        }
    }

    private class PhononJsonStreamParser(private val r: BufferedReader) {
        private var pushed = -2

        private fun nextChar(): Int {
            if (pushed != -2) {
                val c = pushed
                pushed = -2
                return c
            }
            return r.read()
        }

        private fun unreadChar(c: Int) {
            pushed = c
        }

        private fun skipWs(): Int {
            while (true) {
                val c = nextChar()
                if (c == -1) return -1
                if (c != ' '.code && c != '\n'.code && c != '\r'.code && c != '\t'.code) return c
            }
        }

        private fun expectChar(expected: Char) {
            val c = skipWs()
            if (c != expected.code) throw IllegalArgumentException("Expected '$expected', found '${c.toChar()}'")
        }

        private fun readString(): String {
            val start = skipWs()
            if (start != '"'.code) throw IllegalArgumentException("Expected string opening quote")
            val sb = StringBuilder()
            while (true) {
                val c = nextChar()
                if (c == -1) throw IllegalArgumentException("Unterminated string")
                if (c == '"'.code) break
                if (c == '\\'.code) {
                    when (val esc = nextChar()) {
                        '"'.code -> sb.append('"')
                        '\\'.code -> sb.append('\\')
                        '/'.code -> sb.append('/')
                        'b'.code -> sb.append('\b')
                        'f'.code -> sb.append('\u000C')
                        'n'.code -> sb.append('\n')
                        'r'.code -> sb.append('\r')
                        't'.code -> sb.append('\t')
                        'u'.code -> {
                            val h = CharArray(4) { nextChar().toChar() }
                            sb.append(String(h).toInt(16).toChar())
                        }
                        else -> sb.append(esc.toChar())
                    }
                } else {
                    sb.append(c.toChar())
                }
            }
            return sb.toString()
        }

        private fun readNumberAsDouble(): Double {
            val sb = StringBuilder()
            var c = skipWs()
            while (c != -1 && (c in '0'.code..'9'.code || c == '-'.code || c == '+'.code || c == '.'.code || c == 'e'.code || c == 'E'.code)) {
                sb.append(c.toChar())
                c = nextChar()
            }
            if (c != -1) unreadChar(c)
            return sb.toString().toDoubleOrNull() ?: 0.0
        }

        private fun skipValue() {
            when (val c = skipWs()) {
                '"'.code -> { unreadChar(c); readString() }
                '{'.code -> {
                    val f = skipWs()
                    if (f == '}'.code) return
                    unreadChar(f)
                    while (true) {
                        readString()
                        expectChar(':')
                        skipValue()
                        val d = skipWs()
                        if (d == ','.code) continue
                        if (d == '}'.code) break
                        throw IllegalArgumentException("Malformed skipped object")
                    }
                }
                '['.code -> {
                    val f = skipWs()
                    if (f == ']'.code) return
                    unreadChar(f)
                    while (true) {
                        skipValue()
                        val d = skipWs()
                        if (d == ','.code) continue
                        if (d == ']'.code) break
                        throw IllegalArgumentException("Malformed skipped array")
                    }
                }
                else -> {
                    if (c == -1) return
                    var cur = c
                    while (cur != -1 && cur != ','.code && cur != '}'.code && cur != ']'.code && cur > ' '.code) {
                        cur = nextChar()
                    }
                    if (cur != -1) unreadChar(cur)
                }
            }
        }

        fun parse(): PurrPhononTokenizer {
            var unkId = 0
            val pieceMap = HashMap<String, Pair<Int, Float>>(4096)
            val byteTokenIds = IntArray(256) { -1 }
            var maxLen = 1

            expectChar('{')
            val first = skipWs()
            if (first != '}'.code) {
                unreadChar(first)
                while (true) {
                    val key = readString()
                    expectChar(':')
                    if (key == "model") {
                        expectChar('{')
                        val mf = skipWs()
                        if (mf != '}'.code) {
                            unreadChar(mf)
                            while (true) {
                                val mk = readString()
                                expectChar(':')
                                when (mk) {
                                    "unk_id" -> unkId = readNumberAsDouble().toInt()
                                    "vocab" -> {
                                        expectChar('[')
                                        val vf = skipWs()
                                        if (vf != ']'.code) {
                                            unreadChar(vf)
                                            var idx = 0
                                            while (true) {
                                                expectChar('[')
                                                val piece = readString()
                                                expectChar(',')
                                                val score = readNumberAsDouble().toFloat()
                                                expectChar(']')
                                                if (piece.startsWith("<0x") && piece.endsWith(">") && piece.length == 6) {
                                                    val bVal = piece.substring(3, 5).toIntOrNull(16)
                                                    if (bVal != null && bVal in 0..255) {
                                                        byteTokenIds[bVal] = idx
                                                    }
                                                } else if (!piece.startsWith("\u0000") &&
                                                    !piece.startsWith("<|") &&
                                                    (idx >= 660 || score < 0f)
                                                ) {
                                                    pieceMap[piece] = idx to score
                                                    if (piece.length > maxLen) maxLen = piece.length
                                                }
                                                idx++
                                                val d = skipWs()
                                                if (d == ','.code) continue
                                                if (d == ']'.code) break
                                                throw IllegalArgumentException("Malformed vocab array")
                                            }
                                        }
                                    }
                                    else -> skipValue()
                                }
                                val md = skipWs()
                                if (md == ','.code) continue
                                if (md == '}'.code) break
                                throw IllegalArgumentException("Malformed model object")
                            }
                        }
                    } else {
                        skipValue()
                    }
                    val d = skipWs()
                    if (d == ','.code) continue
                    if (d == '}'.code) break
                    throw IllegalArgumentException("Malformed root object")
                }
            }
            for (b in 0..255) {
                if (byteTokenIds[b] < 0) byteTokenIds[b] = unkId
            }
            return PurrPhononTokenizer(pieceMap, byteTokenIds, unkId, maxLen)
        }
    }
}
