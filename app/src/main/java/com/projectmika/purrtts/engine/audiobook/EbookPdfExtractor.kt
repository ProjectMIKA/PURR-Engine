package com.projectmika.purrtts.engine.audiobook

import com.projectmika.purrtts.core.audiobook.AudiobookChapter
import com.projectmika.purrtts.core.audiobook.EbookParser
import com.projectmika.purrtts.core.audiobook.ParsedEbook
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.InputStream

/**
 * ✨ PDF EBOOK TEXT & BOOKMARK-CHAPTER EXTRACTOR (TOOL-07) ✨
 * Uses `com.tom-roush:pdfbox-android` to read PDF metadata, PDF outline bookmarks (when present
 * for exact chapter boundaries), and clean reflowed prose for narration.
 */
object EbookPdfExtractor {

    private val SOFT_HYPHEN_WRAP_REGEX = Regex("([\\p{L}])\\-\\r?\\n([\\p{L}])")
    private val MID_SENTENCE_WRAP_REGEX = Regex("([\\p{Ll},;:])\\r?\\n([\\p{Ll}])")

    fun parsePdf(input: InputStream, fallbackTitle: String = "Imported PDF"): ParsedEbook {
        PDDocument.load(input).use { doc ->
            val info = doc.documentInformation
            val docTitle = info?.title?.trim()?.takeIf { it.isNotBlank() } ?: fallbackTitle
            val docAuthor = info?.author?.trim()?.takeIf { it.isNotBlank() } ?: "Unknown Author"
            val totalPages = doc.numberOfPages
            if (totalPages <= 0) {
                throw IllegalArgumentException("PDF has zero pages")
            }

            val bookmarkStarts = extractBookmarkChapters(doc, totalPages)
            val chapters: List<AudiobookChapter> = if (bookmarkStarts.size >= 2) {
                val stripper = PDFTextStripper().apply { sortByPosition = true }
                val rawSections = mutableListOf<Pair<String, String>>()
                for (i in bookmarkStarts.indices) {
                    val (title, startPage) = bookmarkStarts[i]
                    val endPage = if (i + 1 < bookmarkStarts.size) {
                        (bookmarkStarts[i + 1].second - 1).coerceAtLeast(startPage)
                    } else {
                        totalPages
                    }
                    stripper.startPage = startPage
                    stripper.endPage = endPage
                    val cleaned = normalizePdfText(stripper.getText(doc))
                    if (EbookParser.countWords(cleaned) > 0) {
                        rawSections += title to cleaned
                    }
                }
                rawSections.mapIndexed { idx, (title, body) ->
                    val words = EbookParser.countWords(body)
                    AudiobookChapter(
                        index = idx,
                        title = title.ifBlank { "Chapter ${idx + 1}" },
                        text = body,
                        wordCount = words,
                        estimatedDurationMs = EbookParser.estimateDurationMs(words),
                        included = !EbookParser.isLikelyNonStorySection(title, words)
                    )
                }
            } else {
                // Extract pages in safe 60-page batches to keep mobile heap bounded on huge PDFs.
                val sb = StringBuilder()
                val stripper = PDFTextStripper().apply { sortByPosition = true }
                var page = 1
                while (page <= totalPages) {
                    val batchEnd = minOf(page + 59, totalPages)
                    stripper.startPage = page
                    stripper.endPage = batchEnd
                    sb.append(stripper.getText(doc)).append("\n\n")
                    page = batchEnd + 1
                }
                val normalized = normalizePdfText(sb.toString())
                EbookParser.splitPlainTextIntoChapters(normalized, baseTitle = "Part")
            }

            if (chapters.isEmpty()) {
                throw IllegalArgumentException("No selectable text found in PDF (scanned image-only PDFs are unsupported)")
            }
            return ParsedEbook(
                title = docTitle,
                author = docAuthor,
                sourceFormat = "PDF",
                chapters = chapters
            )
        }
    }

    private fun extractBookmarkChapters(doc: PDDocument, totalPages: Int): List<Pair<String, Int>> {
        val rootOutline = runCatching { doc.documentCatalog?.documentOutline }.getOrNull() ?: return emptyList()
        val raw = mutableListOf<Pair<String, Int>>()

        fun collect(firstItem: PDOutlineItem?) {
            var cur = firstItem
            while (cur != null) {
                val title = cur.title?.trim().orEmpty()
                val pageObj = runCatching { cur.findDestinationPage(doc) }.getOrNull()
                val pageZero = if (pageObj != null) runCatching { doc.pages.indexOf(pageObj) }.getOrDefault(-1) else -1
                if (title.isNotEmpty() && pageZero in 0 until totalPages) {
                    raw += title to (pageZero + 1)
                }
                if (raw.size < 300) {
                    collect(cur.firstChild)
                }
                cur = cur.nextSibling
            }
        }
        collect(rootOutline.firstChild)

        return raw
            .sortedBy { it.second }
            .distinctBy { it.second }
    }

    private fun normalizePdfText(raw: String): String {
        var s = raw.replace("\r\n", "\n").replace('\r', '\n')
        s = SOFT_HYPHEN_WRAP_REGEX.replace(s, "$1$2")
        s = MID_SENTENCE_WRAP_REGEX.replace(s, "$1 $2")
        return s.replace(Regex("\\n{3,}"), "\n\n").trim()
    }
}
