package com.projectmika.purrtts.core.purr

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
        private val jsonParser = Json { ignoreUnknownKeys = true }

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
            fromJsonString(tokenizerJsonFile.readText(Charsets.UTF_8))

        fun fromJsonString(jsonText: String): PurrBpeTokenizer {
            val root = jsonParser.parseToJsonElement(jsonText).jsonObject
            val modelObj = root["model"]?.jsonObject
                ?: throw IllegalArgumentException("tokenizer.json missing `model` object")

            val vocabObj = modelObj["vocab"]?.jsonObject
                ?: throw IllegalArgumentException("tokenizer.json missing `model.vocab`")
            val vocabMap = HashMap<String, Int>(vocabObj.size * 2)
            val idMap = HashMap<Int, String>(vocabObj.size * 2)

            for ((tok, el) in vocabObj) {
                val id = el.jsonPrimitive.intOrNull ?: continue
                vocabMap[tok] = id
                idMap[id] = tok
            }

            val addedMap = HashMap<String, Int>(64)
            root["added_tokens"]?.jsonArray?.forEach { entry ->
                val obj = entry.jsonObject
                val id = obj["id"]?.jsonPrimitive?.intOrNull ?: return@forEach
                val content = obj["content"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                addedMap[content] = id
                vocabMap.putIfAbsent(content, id)
                idMap.putIfAbsent(id, content)
            }

            val mergesArr = modelObj["merges"]?.jsonArray
            val mergeRanks = HashMap<Long, Int>((mergesArr?.size ?: 0) * 2)
            mergesArr?.forEachIndexed { rank, item ->
                val pair: Pair<String, String>? = when (item) {
                    is JsonArray -> {
                        if (item.size >= 2) {
                            item[0].jsonPrimitive.content to item[1].jsonPrimitive.content
                        } else {
                            null
                        }
                    }
                    is JsonPrimitive -> {
                        val s = item.content
                        val sp = s.indexOf(' ')
                        if (sp > 0 && sp < s.lastIndex) {
                            s.substring(0, sp) to s.substring(sp + 1)
                        } else {
                            null
                        }
                    }
                    else -> null
                }
                if (pair != null) {
                    val idL = vocabMap[pair.first]
                    val idR = vocabMap[pair.second]
                    if (idL != null && idR != null) {
                        val key = (idL.toLong() shl 32) or (idR.toLong() and 0xFFFFFFFFL)
                        mergeRanks.putIfAbsent(key, rank)
                    }
                }
            }

            val normJson = root["normalizer"]?.toString().orEmpty()
            val preTokJson = root["pre_tokenizer"]?.toString().orEmpty()
            val unkTok = modelObj["unk_token"]?.jsonPrimitive?.contentOrNull
            val unkId = unkTok?.let { vocabMap[it] }
                ?: vocabMap["[UNK]"]
                ?: vocabMap["<unk>"]
                ?: 0

            return PurrBpeTokenizer(
                vocab = vocabMap,
                idToToken = idMap,
                addedTokens = addedMap,
                mergeRanks = mergeRanks,
                useByteLevel = preTokJson.contains("\"ByteLevel\""),
                lowercaseNormalizer = normJson.contains("\"Lowercase\""),
                collapseWhitespace = normJson.contains("\"Replace\""),
                splitDigitsIndividually = preTokJson.contains("\"Digits\""),
                unkId = unkId
            )
        }
    }
}
