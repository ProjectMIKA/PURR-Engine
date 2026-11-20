package com.projectmika.purrtts.core.purr

import java.io.BufferedReader
import java.io.File
import java.io.Reader
import java.io.StringReader

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
