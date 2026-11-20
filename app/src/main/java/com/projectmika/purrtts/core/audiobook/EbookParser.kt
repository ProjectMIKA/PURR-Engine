package com.projectmika.purrtts.core.audiobook

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.serialization.Serializable
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * ✨ MIKA'S MULTI-FORMAT EBOOK PARSER & CHAPTER SPLITTER (TOOL-07) ✨
 *
 * Extracts clean narration text and intelligent chapter boundaries from:
 * - `.epub` (OPF spine order + NCX / EPUB3 NAV chapter titles + XHTML cleanup)
 * - `.txt` / `.md` (intelligent heading/chapter boundary detection + paragraph-safe chunk fallback)
 * - `.html` / `.htm` / `.xhtml` (HTML sectioning + clean prose extraction)
 * - `.fb2` (FictionBook XML `<section>` chapter extraction)
 * - `.docx` (WordprocessingML `word/document.xml` paragraphs + heading splitting)
 * (`.pdf` is extracted via `EbookPdfExtractor` using PdfBox-Android and fed into `splitPlainTextIntoChapters`.)
 *
 * PURE-JVM — zero Android dependencies so every parser path is unit-tested in CI.
 */

@Serializable
enum class ChapterRenderStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    FAILED
}

@Serializable
data class AudiobookChapter(
    val index: Int,
    val title: String,
    val text: String,
    val wordCount: Int,
    val estimatedDurationMs: Long,
    val included: Boolean = true,
    val status: ChapterRenderStatus = ChapterRenderStatus.PENDING,
    val renderedWavPath: String = "",
    val renderedDurationMs: Long = 0L,
    val totalChunks: Int = 0,
    val completedChunks: Int = 0,
    val errorMessage: String? = null
)

@Serializable
data class AudiobookProject(
    val id: String,
    val title: String,
    val author: String = "Unknown Author",
    val sourceFormat: String,
    val sourceFileName: String,
    val createdAtEpochMs: Long = 0L,
    val targetChunkChars: Int = 260,
    val paragraphPauseMs: Int = 550,
    val chapterGapMs: Int = 1400,
    val normalizeLoudness: Boolean = true,
    val smoothTransitions: Boolean = true,
    val chapters: List<AudiobookChapter> = emptyList(),
    val combinedM4bPath: String = "",
    val combinedPublicPath: String = "",
    val combinedDurationMs: Long = 0L
) {
    val includedChapters: List<AudiobookChapter>
        get() = chapters.filter { it.included }

    val allIncludedCompleted: Boolean
        get() {
            val inc = includedChapters
            return inc.isNotEmpty() && inc.all { it.status == ChapterRenderStatus.COMPLETED && it.renderedWavPath.isNotBlank() }
        }
}

data class ParsedEbook(
    val title: String,
    val author: String,
    val sourceFormat: String,
    val chapters: List<AudiobookChapter>
)

object EbookParser {

    private const val MAX_ENTRY_BYTES = 16 * 1024 * 1024
    private const val MAX_TOTAL_TEXT_BYTES = 48 * 1024 * 1024

    private val NON_TEXT_ZIP_EXTENSIONS = setOf(
        "png", "jpg", "jpeg", "webp", "gif", "bmp", "ico", "svg",
        "ttf", "otf", "woff", "woff2", "eot",
        "mp3", "m4a", "m4b", "aac", "ogg", "opus", "wav", "flac", "mp4", "webm"
    )

    private val CHAPTER_HEADING_REGEX = Regex(
        "^(?:" +
            "(?:#{1,3}\\s+.+)" +
            "|(?:(?:CHAPTER|Chapter|chapter|PART|Part|part|BOOK|Book|book|ACT|Act|VOLUME|Volume)\\s+(?:[IVXLCDM]+|\\d+|One|Two|Three|Four|Five|Six|Seven|Eight|Nine|Ten|Eleven|Twelve|Thirteen|Fourteen|Fifteen|Sixteen|Seventeen|Eighteen|Nineteen|Twenty|[A-Z][a-z]+)(?:\\s*[:.\\-–—]\\s*.*)?)" +
            "|(?:(?:PROLOGUE|Prologue|EPILOGUE|Epilogue|INTRODUCTION|Introduction|PREFACE|Preface|FOREWORD|Foreword|AFTERWORD|Afterword|INTERLUDE|Interlude|PRELUDE|Prelude)(?:\\s*[:.\\-–—]\\s*.*)?)" +
            "|(?:\\d{1,3}\\.\\s+[A-Z][^,]{2,65})" +
            ")$"
    )

    private val NON_STORY_TITLE_REGEX = Regex(
        "^(?:cover|cover\\s*page|title\\s*page|half\\s*title|copyright|copyright\\s*page|table\\s*of\\s*contents|contents|toc|dedication|acknowledg(?:e)?ments?|about\\s+the\\s+author|about\\s+author|also\\s+by.*|other\\s+books.*|colophon|imprint|front\\s*matter|back\\s*matter|index|glossary|bibliography|publisher'?s\\s+note|legal\\s+notice|list\\s+of\\s+illustrations|illustrations)$",
        RegexOption.IGNORE_CASE
    )

    fun inferFormat(fileName: String, mimeType: String? = null): String {
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.US)
        if (ext in setOf("epub", "pdf", "txt", "md", "markdown", "html", "htm", "xhtml", "fb2", "docx")) {
            return when (ext) {
                "markdown" -> "md"
                "htm", "xhtml" -> "html"
                else -> ext
            }
        }
        val mime = mimeType?.lowercase(Locale.US).orEmpty()
        return when {
            "epub" in mime -> "epub"
            "pdf" in mime -> "pdf"
            "html" in mime -> "html"
            "wordprocessingml" in mime -> "docx"
            "fictionbook" in mime || "fb2" in mime -> "fb2"
            else -> "txt"
        }
    }

    fun parseNonPdfStream(
        input: InputStream,
        fileName: String,
        mimeType: String? = null
    ): ParsedEbook {
        val format = inferFormat(fileName, mimeType)
        val fallbackTitle = cleanBookTitleFromFileName(fileName)
        return when (format) {
            "epub" -> parseEpub(input, fallbackTitle)
            "docx" -> parseDocx(input, fallbackTitle)
            "fb2" -> parseFb2(decodeTextBytes(input.readBytes()), fallbackTitle)
            "html" -> parseHtml(decodeTextBytes(input.readBytes()), fallbackTitle)
            else -> parsePlainText(decodeTextBytes(input.readBytes()), fallbackTitle, sourceFormat = format.uppercase(Locale.US))
        }
    }

    fun parseEpub(input: InputStream, fallbackTitle: String = "Imported EPUB"): ParsedEbook {
        val zipEntries = readZipTextEntries(input)
        if (zipEntries.isEmpty()) {
            throw IllegalArgumentException("Empty or unreadable EPUB archive")
        }

        val containerBytes = findZipEntry(zipEntries, "META-INF/container.xml")
        val opfPath = containerBytes?.let { parseOpfPathFromContainer(it) }
            ?: zipEntries.keys.firstOrNull { it.endsWith(".opf", ignoreCase = true) }
            ?: throw IllegalArgumentException("Missing OPF package descriptor in EPUB")

        val opfBytes = findZipEntry(zipEntries, opfPath)
            ?: throw IllegalArgumentException("Missing OPF file: $opfPath")
        val opfDir = opfPath.substringBeforeLast('/', "")
        val opfDoc = parseXml(opfBytes)

        val title = firstDescendantText(opfDoc.documentElement, "title")
            ?.takeIf { it.isNotBlank() } ?: fallbackTitle
        val author = firstDescendantText(opfDoc.documentElement, "creator")
            ?.takeIf { it.isNotBlank() } ?: "Unknown Author"

        data class ManifestItem(
            val id: String,
            val path: String,
            val mediaType: String,
            val properties: String
        )

        val manifestById = linkedMapOf<String, ManifestItem>()
        val itemNodes = opfDoc.getElementsByTagNameNS("*", "item")
        val fallbackItemNodes = if (itemNodes.length == 0) opfDoc.getElementsByTagName("item") else itemNodes
        for (i in 0 until fallbackItemNodes.length) {
            val el = fallbackItemNodes.item(i) as? Element ?: continue
            val id = el.getAttribute("id")
            val href = el.getAttribute("href")
            if (id.isNotBlank() && href.isNotBlank()) {
                manifestById[id] = ManifestItem(
                    id = id,
                    path = resolveZipPath(opfDir, href),
                    mediaType = el.getAttribute("media-type"),
                    properties = el.getAttribute("properties")
                )
            }
        }

        val spineNode = opfDoc.getElementsByTagNameNS("*", "spine").item(0) as? Element
            ?: opfDoc.getElementsByTagName("spine").item(0) as? Element
        val ncxId = spineNode?.getAttribute("toc").orEmpty()
        val spineItemIds = mutableListOf<String>()
        val itemRefNodes = opfDoc.getElementsByTagNameNS("*", "itemref")
        val fallbackRefNodes = if (itemRefNodes.length == 0) opfDoc.getElementsByTagName("itemref") else itemRefNodes
        for (i in 0 until fallbackRefNodes.length) {
            val el = fallbackRefNodes.item(i) as? Element ?: continue
            val idref = el.getAttribute("idref")
            if (idref.isNotBlank()) spineItemIds += idref
        }

        // Collect TOC labels from EPUB3 nav XHTML and/or EPUB2 toc.ncx
        val tocTitlesByPath = linkedMapOf<String, String>()
        val navItem = manifestById.values.firstOrNull { "nav" in it.properties.split(Regex("\\s+")) }
        if (navItem != null) {
            findZipEntry(zipEntries, navItem.path)?.let { navBytes ->
                extractNavTitles(decodeTextBytes(navBytes), navItem.path.substringBeforeLast('/', ""), tocTitlesByPath)
            }
        }
        val ncxItem = manifestById[ncxId]
            ?: manifestById.values.firstOrNull {
                it.mediaType.equals("application/x-dtbncx+xml", ignoreCase = true) ||
                    it.path.endsWith(".ncx", ignoreCase = true)
            }
        if (ncxItem != null && tocTitlesByPath.isEmpty()) {
            findZipEntry(zipEntries, ncxItem.path)?.let { ncxBytes ->
                extractNcxTitles(ncxBytes, ncxItem.path.substringBeforeLast('/', ""), tocTitlesByPath)
            }
        }

        val orderedSpineItems = if (spineItemIds.isNotEmpty()) {
            spineItemIds.mapNotNull { manifestById[it] }
        } else {
            manifestById.values.filter {
                it.mediaType.contains("html", ignoreCase = true) ||
                    it.path.endsWith(".xhtml", ignoreCase = true) ||
                    it.path.endsWith(".html", ignoreCase = true)
            }
        }

        val navPathNorm = navItem?.path?.lowercase(Locale.US)
        val rawChapters = mutableListOf<Pair<String, String>>()

        for (item in orderedSpineItems) {
            if (navPathNorm != null && item.path.lowercase(Locale.US) == navPathNorm && orderedSpineItems.size > 2) {
                continue
            }
            val bytes = findZipEntry(zipEntries, item.path) ?: continue
            val html = decodeTextBytes(bytes)
            val bodyText = htmlToNarrationText(html)
            if (bodyText.isBlank()) continue

            val tocTitle = tocTitlesByPath[item.path.lowercase(Locale.US)]
                ?: extractFirstHtmlHeading(html)
            val fallbackName = tocTitle ?: "Section ${rawChapters.size + 1}"

            // Handle single-blob EPUBs whose spine only has 1 huge HTML document
            if (bodyText.length > 18_000 && orderedSpineItems.size <= 3) {
                val subChapters = splitPlainTextIntoChapters(bodyText, baseTitle = fallbackName)
                if (subChapters.size > 1) {
                    subChapters.forEach { rawChapters += it.title to it.text }
                    continue
                }
            }
            rawChapters += fallbackName to bodyText
        }

        val finalized = buildChapterModels(rawChapters)
        if (finalized.isEmpty()) {
            throw IllegalArgumentException("No readable chapters found inside EPUB")
        }
        return ParsedEbook(
            title = title,
            author = author,
            sourceFormat = "EPUB",
            chapters = finalized
        )
    }

    fun parseDocx(input: InputStream, fallbackTitle: String = "Imported DOCX"): ParsedEbook {
        val zipEntries = readZipTextEntries(input)
        val docBytes = findZipEntry(zipEntries, "word/document.xml")
            ?: throw IllegalArgumentException("Missing word/document.xml in DOCX archive")

        val doc = parseXml(docBytes)
        val pNodes = doc.getElementsByTagNameNS("*", "p")
        val fallbackPNodes = if (pNodes.length == 0) doc.getElementsByTagName("w:p") else pNodes
        val paragraphs = mutableListOf<String>()
        for (i in 0 until fallbackPNodes.length) {
            val p = fallbackPNodes.item(i) as? Element ?: continue
            val text = collectXmlText(p).trim()
            if (text.isNotEmpty()) paragraphs += text
        }
        val plain = paragraphs.joinToString("\n\n")
        return parsePlainText(plain, fallbackTitle = fallbackTitle, sourceFormat = "DOCX")
    }

    fun parseFb2(xmlContent: String, fallbackTitle: String = "Imported FB2"): ParsedEbook {
        val doc = parseXml(xmlContent.toByteArray(StandardCharsets.UTF_8))
        val title = firstDescendantText(doc.documentElement, "book-title")
            ?.takeIf { it.isNotBlank() } ?: fallbackTitle
        val firstName = firstDescendantText(doc.documentElement, "first-name").orEmpty().trim()
        val lastName = firstDescendantText(doc.documentElement, "last-name").orEmpty().trim()
        val author = listOf(firstName, lastName).filter { it.isNotEmpty() }.joinToString(" ")
            .ifBlank { "Unknown Author" }

        val bodies = doc.getElementsByTagNameNS("*", "body")
        val mainBody = if (bodies.length > 0) bodies.item(0) as? Element else doc.documentElement
        val rawChapters = mutableListOf<Pair<String, String>>()

        if (mainBody != null) {
            val childNodes = mainBody.childNodes
            for (i in 0 until childNodes.length) {
                val sec = childNodes.item(i) as? Element ?: continue
                if (localTagName(sec) != "section") continue
                val secTitle = extractFb2SectionTitle(sec) ?: "Chapter ${rawChapters.size + 1}"
                val paragraphs = extractFb2Paragraphs(sec)
                val joined = paragraphs.joinToString("\n\n").trim()
                if (joined.isNotEmpty()) {
                    rawChapters += secTitle to joined
                }
            }
        }

        if (rawChapters.isEmpty()) {
            val fallbackText = mainBody?.let { collectXmlText(it) }.orEmpty().trim()
            return parsePlainText(fallbackText, title, "FB2").copy(author = author)
        }
        return ParsedEbook(
            title = title,
            author = author,
            sourceFormat = "FB2",
            chapters = buildChapterModels(rawChapters)
        )
    }

    fun parseHtml(htmlContent: String, fallbackTitle: String = "Imported HTML"): ParsedEbook {
        val titleMatch = Regex("<title[^>]*>([\\s\\S]*?)</title>", RegexOption.IGNORE_CASE).find(htmlContent)
        val docTitle = titleMatch?.groupValues?.get(1)?.let { cleanInlineHtml(it) }?.takeIf { it.isNotBlank() }
            ?: fallbackTitle
        val text = htmlToNarrationText(htmlContent)
        return parsePlainText(text, fallbackTitle = docTitle, sourceFormat = "HTML")
    }

    fun parsePlainText(
        rawText: String,
        fallbackTitle: String = "Imported Ebook",
        sourceFormat: String = "TXT"
    ): ParsedEbook {
        val normalized = rawText
            .replace("\uFEFF", "")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()
        if (normalized.isEmpty()) {
            throw IllegalArgumentException("Imported document contains no readable text")
        }
        val chapters = splitPlainTextIntoChapters(normalized, baseTitle = "Part")
        return ParsedEbook(
            title = fallbackTitle,
            author = "Unknown Author",
            sourceFormat = sourceFormat,
            chapters = chapters
        )
    }

    /**
     * Splits arbitrary plain text (TXT, MD, PDF extraction, monolithic HTML) into chapters using
     * recognized heading lines (`Chapter 1`, `PART II`, `# Title`, `Prologue`, etc.), falling
     * back cleanly to paragraph-safe ~2,500-word parts when no headings exist.
     */
    fun splitPlainTextIntoChapters(
        text: String,
        baseTitle: String = "Part",
        fallbackWordsPerPart: Int = 2500
    ): List<AudiobookChapter> {
        val lines = text.lines()
        val sections = mutableListOf<Pair<String, StringBuilder>>()
        var currentTitle: String? = null
        var currentBody = StringBuilder()

        fun flushCurrent() {
            val bodyText = currentBody.toString().replace(Regex("\\n{3,}"), "\n\n").trim()
            if (bodyText.isNotEmpty() || currentTitle != null) {
                val heading = currentTitle?.trim()?.takeIf { it.isNotEmpty() }
                if (bodyText.isNotEmpty()) {
                    sections += (heading ?: "Opening") to StringBuilder(bodyText)
                }
            }
            currentBody = StringBuilder()
        }

        for (i in lines.indices) {
            val rawLine = lines[i]
            val trimmed = rawLine.trim()
            val prevBlank = (i == 0) || lines[i - 1].isBlank()
            val nextBlank = (i == lines.lastIndex) || lines[i + 1].isBlank()

            if (isStandaloneChapterHeading(trimmed, prevBlank, nextBlank)) {
                flushCurrent()
                currentTitle = cleanHeadingLabel(trimmed)
                // Also include the spoken chapter heading at top of the chapter text
                currentBody.append(currentTitle).append("\n\n")
            } else {
                currentBody.append(rawLine).append('\n')
            }
        }
        flushCurrent()

        if (sections.size >= 2) {
            return buildChapterModels(sections.map { it.first to it.second.toString() })
        }

        // If fewer than 2 headings matched, split cleanly by paragraph groups so huge single-file books
        // remain pausable, resumable, and chaptered cleanly.
        val whole = (sections.firstOrNull()?.second?.toString() ?: text).trim()
        val totalWords = countWords(whole)
        if (totalWords <= fallbackWordsPerPart) {
            val title = sections.firstOrNull()?.first?.takeIf { it != "Opening" } ?: "$baseTitle 1"
            return buildChapterModels(listOf(title to whole))
        }

        val paragraphs = whole.split(Regex("\\n\\s*\\n+")).map { it.trim() }.filter { it.isNotEmpty() }
        val chunkedParts = mutableListOf<Pair<String, String>>()
        val partBuf = StringBuilder()
        var partWords = 0
        var partIdx = 1

        for (para in paragraphs) {
            val w = countWords(para)
            if (partWords > 0 && partWords + w > fallbackWordsPerPart) {
                chunkedParts += "$baseTitle $partIdx" to partBuf.toString().trim()
                partIdx++
                partBuf.clear()
                partWords = 0
            }
            if (partBuf.isNotEmpty()) partBuf.append("\n\n")
            partBuf.append(para)
            partWords += w
        }
        if (partBuf.isNotEmpty()) {
            chunkedParts += "$baseTitle $partIdx" to partBuf.toString().trim()
        }
        return buildChapterModels(chunkedParts)
    }

    fun isLikelyNonStorySection(title: String, wordCount: Int): Boolean {
        if (wordCount < 15) return true
        val clean = title.trim().replace(Regex("\\s+"), " ")
        return NON_STORY_TITLE_REGEX.matches(clean)
    }

    fun countWords(text: String): Int {
        var count = 0
        var inWord = false
        for (ch in text) {
            if (!ch.isWhitespace()) {
                if (!inWord) {
                    count++
                    inWord = true
                }
            } else {
                inWord = false
            }
        }
        return count
    }

    fun estimateDurationMs(wordCount: Int): Long {
        if (wordCount <= 0) return 0L
        // ~150 spoken words per minute = 400 ms per word
        return (wordCount * 400L).coerceAtLeast(1000L)
    }

    fun htmlToNarrationText(html: String): String {
        var s = html
            .replace(Regex("(?is)<head[^>]*>.*?</head>"), " ")
            .replace(Regex("(?is)<script[^>]*>.*?</script>"), " ")
            .replace(Regex("(?is)<style[^>]*>.*?</style>"), " ")
            .replace(Regex("(?is)<svg[^>]*>.*?</svg>"), " ")
            .replace(Regex("(?is)<(?:rt|rp)[^>]*>.*?</(?:rt|rp)>"), "")
            .replace(Regex("(?is)<br\\s*/?>"), "\n")
            .replace(Regex("(?is)</(?:p|h[1-6]|div|li|blockquote|section|article|tr)>"), "\n\n")
            .replace(Regex("(?is)<(?:p|h[1-6]|li|blockquote)(?:\\s[^>]*)?>"), "")
            .replace(Regex("(?is)<[^>]+>"), " ")

        s = decodeHtmlEntities(s)
        val lines = s.lines().map { line ->
            line.replace(Regex("[ \\t\\x0B\\f]+"), " ").trim()
        }
        return lines.joinToString("\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    private fun buildChapterModels(rawChapters: List<Pair<String, String>>): List<AudiobookChapter> {
        val filtered = rawChapters
            .map { (rawTitle, rawBody) ->
                val cleanTitle = cleanHeadingLabel(rawTitle).ifBlank { "Chapter" }
                val cleanBody = rawBody.replace(Regex("\\n{3,}"), "\n\n").trim()
                cleanTitle to cleanBody
            }
            .filter { (_, body) -> countWords(body) > 0 }

        return filtered.mapIndexed { idx, (title, body) ->
            val words = countWords(body)
            AudiobookChapter(
                index = idx,
                title = title,
                text = body,
                wordCount = words,
                estimatedDurationMs = estimateDurationMs(words),
                included = !isLikelyNonStorySection(title, words),
                status = ChapterRenderStatus.PENDING
            )
        }
    }

    private fun isStandaloneChapterHeading(line: String, prevBlank: Boolean, nextBlank: Boolean): Boolean {
        if (line.length < 3 || line.length > 96) return false
        if (line.startsWith("#")) return CHAPTER_HEADING_REGEX.matches(line)
        if (!prevBlank && !nextBlank) return false
        if (line.endsWith(",") || line.endsWith(";") || line.endsWith("\"") || line.endsWith("'")) return false
        return CHAPTER_HEADING_REGEX.matches(line)
    }

    private fun cleanHeadingLabel(raw: String): String =
        raw.trim().removePrefix("###").removePrefix("##").removePrefix("#").trim()

    private fun extractFirstHtmlHeading(html: String): String? {
        val m = Regex("(?is)<h[1-3][^>]*>(.*?)</h[1-3]>").find(html)
            ?: Regex("(?is)<title[^>]*>(.*?)</title>").find(html)
        val text = m?.groupValues?.get(1)?.let { cleanInlineHtml(it) }?.trim()
        return text?.takeIf { it.isNotEmpty() && it.length <= 90 }
    }

    private fun cleanInlineHtml(raw: String): String =
        decodeHtmlEntities(raw.replace(Regex("(?is)<[^>]+>"), " "))
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun decodeHtmlEntities(text: String): String {
        var s = text
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&mdash;", " — ")
            .replace("&ndash;", " – ")
            .replace("&hellip;", "...")
            .replace("&lsquo;", "'")
            .replace("&rsquo;", "'")
            .replace("&ldquo;", "\"")
            .replace("&rdquo;", "\"")

        s = Regex("&#(\\d{1,6});").replace(s) { m ->
            val cp = m.groupValues[1].toIntOrNull()
            if (cp != null && Character.isValidCodePoint(cp)) String(Character.toChars(cp)) else " "
        }
        s = Regex("&#x([0-9a-fA-F]{1,6});").replace(s) { m ->
            val cp = m.groupValues[1].toIntOrNull(16)
            if (cp != null && Character.isValidCodePoint(cp)) String(Character.toChars(cp)) else " "
        }
        return s
    }

    private fun readZipTextEntries(input: InputStream): Map<String, ByteArray> {
        val map = linkedMapOf<String, ByteArray>()
        var totalBytes = 0L
        ZipInputStream(input).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (entry.isDirectory) {
                    zis.closeEntry()
                    continue
                }
                val ext = entry.name.substringAfterLast('.', "").lowercase(Locale.US)
                if (ext in NON_TEXT_ZIP_EXTENSIONS) {
                    zis.closeEntry()
                    continue
                }
                val buf = ByteArrayOutputStream()
                val tmp = ByteArray(8192)
                var entrySize = 0
                while (true) {
                    val read = zis.read(tmp)
                    if (read <= 0) break
                    entrySize += read
                    if (entrySize > MAX_ENTRY_BYTES || totalBytes + entrySize > MAX_TOTAL_TEXT_BYTES) {
                        throw IllegalArgumentException("Archive entry exceeds safe text memory limit")
                    }
                    buf.write(tmp, 0, read)
                }
                totalBytes += entrySize
                map[normalizeZipPath(entry.name)] = buf.toByteArray()
                zis.closeEntry()
            }
        }
        return map
    }

    private fun findZipEntry(entries: Map<String, ByteArray>, path: String): ByteArray? {
        val norm = normalizeZipPath(path)
        entries[norm]?.let { return it }
        return entries.entries.firstOrNull { it.key.equals(norm, ignoreCase = true) }?.value
    }

    private fun parseOpfPathFromContainer(containerBytes: ByteArray): String? {
        val doc = runCatching { parseXml(containerBytes) }.getOrNull() ?: return null
        val nodes = doc.getElementsByTagNameNS("*", "rootfile")
        val fallback = if (nodes.length == 0) doc.getElementsByTagName("rootfile") else nodes
        val el = fallback.item(0) as? Element ?: return null
        return el.getAttribute("full-path").takeIf { it.isNotBlank() }
    }

    private fun extractNcxTitles(
        ncxBytes: ByteArray,
        ncxDir: String,
        out: MutableMap<String, String>
    ) {
        val doc = runCatching { parseXml(ncxBytes) }.getOrNull() ?: return
        val navPoints = doc.getElementsByTagNameNS("*", "navPoint")
        val fallback = if (navPoints.length == 0) doc.getElementsByTagName("navPoint") else navPoints
        for (i in 0 until fallback.length) {
            val np = fallback.item(i) as? Element ?: continue
            val contentEl = firstDirectChild(np, "content") ?: continue
            val src = contentEl.getAttribute("src").substringBefore('#').trim()
            if (src.isEmpty()) continue
            val label = firstDirectChild(np, "navLabel")?.let { collectXmlText(it) }?.trim().orEmpty()
            val fullPath = resolveZipPath(ncxDir, src).lowercase(Locale.US)
            if (label.isNotEmpty() && fullPath !in out) {
                out[fullPath] = label
            }
        }
    }

    private fun extractNavTitles(
        navHtml: String,
        navDir: String,
        out: MutableMap<String, String>
    ) {
        val linkRegex = Regex("(?is)<a\\s+[^>]*href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>")
        for (m in linkRegex.findAll(navHtml)) {
            val href = m.groupValues[1].substringBefore('#').trim()
            val label = cleanInlineHtml(m.groupValues[2])
            if (href.isNotEmpty() && label.isNotEmpty()) {
                val fullPath = resolveZipPath(navDir, href).lowercase(Locale.US)
                if (fullPath !in out) {
                    out[fullPath] = label
                }
            }
        }
    }

    private fun extractFb2SectionTitle(section: Element): String? {
        val titleEl = firstDirectChild(section, "title") ?: return null
        return collectXmlText(titleEl).replace(Regex("\\s+"), " ").trim().takeIf { it.isNotEmpty() }
    }

    private fun extractFb2Paragraphs(section: Element): List<String> {
        val out = mutableListOf<String>()
        val children = section.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node.nodeType != Node.ELEMENT_NODE) continue
            val el = node as Element
            when (localTagName(el)) {
                "title" -> extractFb2SectionTitle(section)?.let { out += it }
                "p", "subtitle", "text-author", "v" -> {
                    val t = collectXmlText(el).replace(Regex("\\s+"), " ").trim()
                    if (t.isNotEmpty()) out += t
                }
                "cite", "epigraph", "poem", "stanza", "section" -> {
                    out += extractFb2Paragraphs(el)
                }
            }
        }
        return out
    }

    private fun parseXml(bytes: ByteArray) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        isExpandEntityReferences = false
    }.newDocumentBuilder().parse(ByteArrayInputStream(bytes))

    private fun localTagName(el: Element): String =
        (el.localName ?: el.tagName.substringAfter(':')).lowercase(Locale.US)

    private fun firstDirectChild(parent: Element, localName: String): Element? {
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val el = children.item(i) as? Element ?: continue
            if (localTagName(el).equals(localName, ignoreCase = true)) return el
        }
        return null
    }

    private fun firstDescendantText(root: Element, localName: String): String? {
        val nodes = root.getElementsByTagNameNS("*", localName)
        if (nodes.length > 0) return collectXmlText(nodes.item(0)).trim()
        val raw = root.getElementsByTagName(localName)
        if (raw.length > 0) return collectXmlText(raw.item(0)).trim()
        return null
    }

    private fun collectXmlText(node: Node): String {
        val sb = StringBuilder()
        fun walk(n: Node) {
            if (n.nodeType == Node.TEXT_NODE || n.nodeType == Node.CDATA_SECTION_NODE) {
                sb.append(n.nodeValue.orEmpty())
            } else {
                val children = n.childNodes
                for (i in 0 until children.length) walk(children.item(i))
            }
        }
        walk(node)
        return sb.toString()
    }

    private fun normalizeZipPath(path: String): String {
        val parts = path.replace('\\', '/').split('/')
        val resolved = ArrayDeque<String>()
        for (seg in parts) {
            when {
                seg.isEmpty() || seg == "." -> Unit
                seg == ".." -> if (resolved.isNotEmpty()) resolved.removeLast()
                else -> resolved.addLast(seg)
            }
        }
        return resolved.joinToString("/")
    }

    private fun resolveZipPath(baseDir: String, relativeHref: String): String {
        val decodedHref = decodePercentEncoding(relativeHref.substringBefore('#').substringBefore('?'))
        val combined = if (baseDir.isBlank()) decodedHref else "$baseDir/$decodedHref"
        return normalizeZipPath(combined)
    }

    private fun decodePercentEncoding(s: String): String {
        if ('%' !in s) return s
        val out = ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '%' && i + 2 < s.length) {
                val hex = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (hex != null) {
                    out.write(hex)
                    i += 3
                    continue
                }
            }
            val bytes = ch.toString().toByteArray(StandardCharsets.UTF_8)
            out.write(bytes)
            i++
        }
        return out.toString(StandardCharsets.UTF_8.name())
    }

    private fun decodeTextBytes(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, StandardCharsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charset.forName("UTF-16BE"))
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charset.forName("UTF-16LE"))
        }
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun cleanBookTitleFromFileName(fileName: String): String =
        fileName.substringAfterLast('/')
            .substringBeforeLast('.')
            .replace('_', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifEmpty { "PURR Audiobook" }
}
