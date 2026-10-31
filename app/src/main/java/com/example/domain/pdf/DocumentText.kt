package com.example.domain.pdf

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.zip.ZipFile

/** Lightweight text import helpers. These keep non-PDF formats independent of the TTS engine. */
object DocumentText {
    fun decodeText(bytes: ByteArray): String {
        val candidates = listOf(StandardCharsets.UTF_8, CharsetWindows1252.charset)
        for (charset in candidates) try {
            val decoder = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            return decoder.decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        } catch (_: CharacterCodingException) { }
        return String(bytes, StandardCharsets.ISO_8859_1)
    }

    fun paginate(text: String, maxChars: Int = 2000): List<String> {
        require(maxChars > 0)
        val paragraphs = text.trim().split(Regex("\\n\\s*\\n")).filter { it.isNotBlank() }
        if (paragraphs.isEmpty()) return listOf("")
        val result = mutableListOf<String>()
        var page = StringBuilder()
        for (paragraph in paragraphs) {
            var remainder = paragraph.trim()
            while (remainder.isNotEmpty()) {
                val room = maxChars - page.length - if (page.isEmpty()) 0 else 2
                if (room <= 0) { result += page.toString(); page = StringBuilder(); continue }
                if (remainder.length <= room) {
                    if (page.isNotEmpty()) page.append("\n\n")
                    page.append(remainder); remainder = ""
                } else {
                    var cut = remainder.lastIndexOf(' ', room.coerceAtMost(remainder.length - 1)).takeIf { it > room / 2 } ?: room
                    if (page.isNotEmpty()) { result += page.toString(); page = StringBuilder() }
                    result += remainder.substring(0, cut).trim()
                    remainder = remainder.substring(cut).trimStart()
                }
                if (page.length >= maxChars) { result += page.toString(); page = StringBuilder() }
            }
        }
        if (page.isNotEmpty()) result += page.toString()
        return result.ifEmpty { listOf("") }
    }

    fun stripMarkdown(markdown: String): String {
        val out = StringBuilder()
        var inFence = false
        for (line in markdown.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                if (!inFence) out.append("\n\nCode block.\n\n")
                inFence = !inFence
                continue
            }
            if (inFence || trimmed.startsWith("|") || Regex("^[-| :]+$").matches(trimmed) || trimmed.startsWith("<img", true)) continue
            var text = line.replace(Regex("!\\[[^]]*]\\([^)]*\\)"), "")
            text = text.replace(Regex("\\[([^]]+)]\\([^)]*\\)"), "$1")
            text = text.replace(Regex("^\\s{0,3}#{1,6}\\s*"), "\n\n")
                .replace(Regex("^\\s*[-*+]\\s+"), "")
                .replace(Regex("^\\s*\\d+[.)]\\s+"), "")
                .replace(Regex("[*_~]{1,3}"), "")
            out.append(text).append('\n')
        }
        return out.toString().replace(Regex("\\n{3,}"), "\n\n").trim()
    }

    /** Reads paragraphs and runs from the main WordprocessingML part; tables are skipped. */
    fun extractDocx(file: File): String {
        ZipFile(file).use { zip ->
            val entry = zip.getEntry("word/document.xml") ?: error("This DOCX file has no document.xml part")
            val parser = Xml.newPullParser()
            parser.setInput(zip.getInputStream(entry), null)
            val result = StringBuilder()
            var inParagraph = false
            var tableDepth = 0
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "tbl" -> tableDepth++
                        "p" -> if (tableDepth == 0) inParagraph = true
                        "t" -> if (tableDepth == 0 && inParagraph) result.append(parser.nextText())
                        "tab" -> if (tableDepth == 0 && inParagraph) result.append('\t')
                        "br" -> if (tableDepth == 0 && inParagraph) result.append('\n')
                    }
                    XmlPullParser.END_TAG -> when (parser.name) {
                        "tbl" -> tableDepth--
                        "p" -> if (tableDepth == 0 && inParagraph) { result.append("\n\n"); inParagraph = false }
                    }
                }
                event = parser.next()
            }
            return result.toString().trim().ifEmpty { error("The DOCX file contains no readable text") }
        }
    }
    private object CharsetWindows1252 { val charset = java.nio.charset.Charset.forName("windows-1252") }
}

object PageExtractionPriority {
    fun order(pageCount: Int, targetPage: Int): List<Int> {
        if (pageCount <= 0) return emptyList()
        val target = targetPage.coerceIn(1, pageCount)
        return (listOf(target, target - 1, target + 1) + (1..pageCount).toList()).filter { it in 1..pageCount }.distinct()
    }
}

object PageNumberValidation {
    fun parse(input: String, pageCount: Int): Int? = input.toIntOrNull()?.takeIf { pageCount > 0 && it in 1..pageCount }
}
