package com.projectmika.purrtts.core.audiobook

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ✨ PURE-JVM TESTS FOR EBOOK PARSER & CHAPTER SPLITTER (TOOL-07) ✨
 */
class PurrEbookParserTest {

    @Test
    fun `epub parser extracts spine order ncx titles clean prose and filters frontmatter`() {
        val epubBytes = buildSyntheticEpub()
        val parsed = EbookParser.parseEpub(ByteArrayInputStream(epubBytes), fallbackTitle = "Fallback")

        assertEquals("Lanterns of the Cyber Harbor", parsed.title)
        assertEquals("Project MIKA", parsed.author)
        assertEquals("EPUB", parsed.sourceFormat)
        assertEquals(3, parsed.chapters.size)

        val cover = parsed.chapters[0]
        assertEquals("Cover", cover.title)
        assertFalse("Frontmatter cover excluded by default", cover.included)

        val ch1 = parsed.chapters[1]
        assertEquals("Chapter 1: Harbor Awakening", ch1.title)
        assertTrue(ch1.included)
        assertTrue(ch1.text.contains("Lanterns glowed"))
        assertTrue(ch1.text.contains("—"))
        assertFalse(ch1.text.contains("<script"))

        val ch2 = parsed.chapters[2]
        assertEquals("Chapter 2: Neural Tide", ch2.title)
        assertTrue(ch2.included)
        assertTrue(ch2.text.contains("\"Ready to sail,\" Elara whispered"))
    }

    @Test
    fun `plain text markdown and fb2 chapter splitting work with heading and fallback rules`() {
        val txt = """
            Copyright Page

            All rights reserved 2026.

            Prologue: Before the Dawn

            Rain washed across the silver cobblestones while the harbor bells tolled through the fog. Everyone waited in hushed anticipation for the morning packet boat to arrive with news from the capital city.

            Chapter 1: The Brass Compass

            Captain Elara opened the leather folio carefully and examined every inked coordinate on the yellowing parchment chart before nodding once to her navigator.
        """.trimIndent()

        val parsedTxt = EbookParser.parsePlainText(txt, fallbackTitle = "Compass", sourceFormat = "TXT")
        assertTrue(parsedTxt.chapters.size >= 3)
        val copyrightCh = parsedTxt.chapters.first()
        assertEquals("Copyright Page", copyrightCh.title)
        assertFalse(copyrightCh.included)
        assertTrue(parsedTxt.chapters.any { it.title == "Prologue: Before the Dawn" && it.included })
        assertTrue(parsedTxt.chapters.any { it.title == "Chapter 1: The Brass Compass" && it.included })

        val md = """
            # Part One: Signal
            ${"Static whispered through the brass speaker all morning long. ".repeat(12)}

            ## Chapter 2: Echo
            ${"Below deck the turbines settled into an even metallic purr. ".repeat(12)}
        """.trimIndent()
        val parsedMd = EbookParser.parsePlainText(md, fallbackTitle = "MD Book", sourceFormat = "MD")
        assertEquals(2, parsedMd.chapters.size)
        assertEquals("Part One: Signal", parsedMd.chapters[0].title)
        assertEquals("Chapter 2: Echo", parsedMd.chapters[1].title)

        // Monolithic text with no headings splits cleanly into Parts without breaking mid-paragraph
        val manyParagraphs = (1..30).joinToString("\n\n") { idx ->
            "Paragraph number $idx describes the steady hum of engines gliding beneath clear autumn stars across open water."
        }
        val splitParts = EbookParser.splitPlainTextIntoChapters(
            text = manyParagraphs,
            baseTitle = "Part",
            fallbackWordsPerPart = 180
        )
        assertTrue("Long headingless text splits into multiple parts", splitParts.size >= 2)
        assertEquals("Part 1", splitParts[0].title)
        assertTrue(splitParts.all { it.text.endsWith("open water.") })
    }

    private fun buildSyntheticEpub(): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            fun addEntry(name: String, content: String) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray(StandardCharsets.UTF_8))
                zos.closeEntry()
            }

            addEntry(
                "META-INF/container.xml",
                """
                <?xml version="1.0"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles>
                    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                  </rootfiles>
                </container>
                """.trimIndent()
            )
            addEntry(
                "OEBPS/content.opf",
                """
                <?xml version="1.0" encoding="utf-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="BookId">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>Lanterns of the Cyber Harbor</dc:title>
                    <dc:creator>Project MIKA</dc:creator>
                  </metadata>
                  <manifest>
                    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                    <item id="cover" href="text/cover.xhtml" media-type="application/xhtml+xml"/>
                    <item id="ch1" href="text/ch01.xhtml" media-type="application/xhtml+xml"/>
                    <item id="ch2" href="text/ch02.xhtml" media-type="application/xhtml+xml"/>
                    <item id="img1" href="images/cover.png" media-type="image/png"/>
                  </manifest>
                  <spine toc="ncx">
                    <itemref idref="cover"/>
                    <itemref idref="ch1"/>
                    <itemref idref="ch2"/>
                  </spine>
                </package>
                """.trimIndent()
            )
            addEntry(
                "OEBPS/toc.ncx",
                """
                <?xml version="1.0" encoding="utf-8"?>
                <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
                  <navMap>
                    <navPoint id="n0" playOrder="1"><navLabel><text>Cover</text></navLabel><content src="text/cover.xhtml"/></navPoint>
                    <navPoint id="n1" playOrder="2"><navLabel><text>Chapter 1: Harbor Awakening</text></navLabel><content src="text/ch01.xhtml#top"/></navPoint>
                    <navPoint id="n2" playOrder="3"><navLabel><text>Chapter 2: Neural Tide</text></navLabel><content src="text/ch02.xhtml"/></navPoint>
                  </navMap>
                </ncx>
                """.trimIndent()
            )
            addEntry("OEBPS/text/cover.xhtml", "<html><body><h1>Cover Art</h1></body></html>")
            addEntry(
                "OEBPS/text/ch01.xhtml",
                """
                <html>
                  <head><script>console.log('ignored')</script></head>
                  <body>
                    <p>Lanterns glowed along the pier &mdash; a quiet signal for every traveler returning home from deep sea voyages.</p>
                    <p>Nobody spoke until the bell struck midnight across the harbor.</p>
                  </body>
                </html>
                """.trimIndent()
            )
            addEntry(
                "OEBPS/text/ch02.xhtml",
                """
                <html>
                  <body>
                    <p>&ldquo;Ready to sail,&rdquo; Elara whispered to her crew beneath the cool stars as the deck vibrated softly below.</p>
                  </body>
                </html>
                """.trimIndent()
            )
            zos.putNextEntry(ZipEntry("OEBPS/images/cover.png"))
            zos.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
            zos.closeEntry()
        }
        return baos.toByteArray()
    }
}
