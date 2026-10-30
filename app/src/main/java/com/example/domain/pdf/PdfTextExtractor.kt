package com.example.domain.pdf

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.zip.Inflater
import java.io.File
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.json.JSONArray
import org.json.JSONObject

/**
 * PDF text extraction and readability checks. Production PDF paths use PDFBox Android so PDF font
 * encodings are interpreted instead of treating compressed stream bytes as readable characters.
 */
object PdfTextExtractor {

    @Volatile private var pdfBoxInitialized = false

    /** PDFBox reads the PDF font maps that the old byte-scanning parser ignored. */
    fun extractPdfFilePageByPage(
        context: Context,
        file: File,
        title: String,
        onPage: (pageNumber: Int, pageText: String, pageCount: Int) -> Unit
    ): ExtractionResult {
        return try {
            val cacheFile = File(file.parentFile, "${file.name}.text-cache.json")
            val cached = readTextCache(cacheFile, file, title)
            if (cached != null) {
                cached.rawPages.forEachIndexed { index, page -> onPage(index + 1, page, cached.estimatedPageCount) }
                return cached
            }
            if (!pdfBoxInitialized) synchronized(this) {
                if (!pdfBoxInitialized) {
                    PDFBoxResourceLoader.init(context.applicationContext)
                    pdfBoxInitialized = true
                }
            }
            PDDocument.load(file).use { document ->
                val count = document.numberOfPages
                val pages = ArrayList<String>(count)
                val stripper = PDFTextStripper()
                for (pageIndex in 0 until count) {
                    stripper.startPage = pageIndex + 1
                    stripper.endPage = pageIndex + 1
                    val pageText = stripper.getText(document).trim()
                    pages += pageText
                    onPage(pageIndex + 1, pageText, count)
                }
                val joined = pages.joinToString(" ")
                val letterRatio = joined.count { it.isLetter() }.toFloat() / joined.length.coerceAtLeast(1)
                val readable = joined.isNotBlank() && letterRatio >= 0.35f && Regex("[\\p{L}]{2,}").findAll(joined).count() >= 8
                ExtractionResult(cleanDocTitle(title), if (readable) pages else emptyList(), count, readable,
                    if (readable) null else "No readable selectable text found. This PDF may be scanned or use unsupported text encoding.")
                    .also { writeTextCache(cacheFile, file, it) }
            }
        } catch (e: Exception) {
            ExtractionResult(cleanDocTitle(title), emptyList(), 0, false, e.message ?: "Could not extract PDF text")
        }
    }

    fun extractPdfFile(context: Context, file: File, title: String): ExtractionResult {
        return try {
            val cacheFile = File(file.parentFile, "${file.name}.text-cache.json")
            readTextCache(cacheFile, file, title)?.let { return it }
            if (!pdfBoxInitialized) synchronized(this) {
                if (!pdfBoxInitialized) {
                    PDFBoxResourceLoader.init(context.applicationContext)
                    pdfBoxInitialized = true
                }
            }
            PDDocument.load(file).use { document ->
                val stripper = PDFTextStripper()
                stripper.startPage = 1
                stripper.endPage = document.numberOfPages
                val extracted = stripper.getText(document)
                val chunks = extracted.split('\u000C')
                val pages = (0 until document.numberOfPages).map { pageIndex -> chunks.getOrNull(pageIndex)?.trim().orEmpty() }
                val text = pages.joinToString(" ")
                val letterRatio = text.count { it.isLetter() }.toFloat() / text.length.coerceAtLeast(1)
                val wordCount = Regex("[\\p{L}]{2,}").findAll(text).count()
                val readable = text.isNotBlank() && letterRatio >= 0.35f && wordCount >= 8
                val result = ExtractionResult(
                    title = cleanDocTitle(title),
                    rawPages = if (readable) pages else emptyList(),
                    estimatedPageCount = document.numberOfPages,
                    isSuccessful = readable,
                    errorMessage = if (readable) null else "No readable selectable text found. This PDF may be scanned or use unsupported text encoding."
                )
                writeTextCache(cacheFile, file, result)
                result
            }
        } catch (e: Exception) {
            ExtractionResult(
                title = cleanDocTitle(title),
                rawPages = emptyList(),
                estimatedPageCount = 0,
                isSuccessful = false,
                errorMessage = e.message ?: "Could not extract PDF text"
            )
        }
    }

    private fun readTextCache(cacheFile: File, sourceFile: File, title: String): ExtractionResult? = runCatching {
        if (!cacheFile.isFile) return null
        val json = JSONObject(cacheFile.readText())
        if (json.optLong("sourceLength") != sourceFile.length() || json.optLong("sourceModified") != sourceFile.lastModified()) return null
        val pageArray = json.getJSONArray("pages")
        val pages = List(pageArray.length()) { index -> pageArray.getString(index) }
        ExtractionResult(
            title,
            pages,
            json.optInt("pageCount", pages.size),
            json.optBoolean("successful"),
            json.optString("error").takeIf { it.isNotBlank() }
        )
    }.getOrNull()

    private fun writeTextCache(cacheFile: File, sourceFile: File, result: ExtractionResult) {
        runCatching {
            val json = JSONObject()
                .put("sourceLength", sourceFile.length())
                .put("sourceModified", sourceFile.lastModified())
                .put("pageCount", result.estimatedPageCount)
                .put("successful", result.isSuccessful)
                .put("error", result.errorMessage ?: "")
                .put("pages", JSONArray(result.rawPages))
            val temp = File(cacheFile.parentFile, "${cacheFile.name}.tmp")
            temp.writeText(json.toString())
            if (!temp.renameTo(cacheFile)) {
                cacheFile.writeText(json.toString())
                temp.delete()
            }
        }
    }

    data class ExtractionResult(
        val title: String,
        val rawPages: List<String>,
        val estimatedPageCount: Int,
        val isSuccessful: Boolean,
        val errorMessage: String? = null
    )

    fun extractTextFromUri(context: Context, uri: Uri): ExtractionResult {
        return try {
            val mimeType = context.contentResolver.getType(uri) ?: ""
            val fileName = getFileName(context, uri)

            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                if (mimeType.contains("pdf", ignoreCase = true) || fileName.endsWith(".pdf", ignoreCase = true) || mimeType == "*/*" || mimeType.contains("octet-stream", ignoreCase = true)) {
                    extractFromPdfStream(context, inputStream, fileName)
                } else {
                    // Plain text, markdown, or LaTeX document
                    val content = inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                    ExtractionResult(
                        title = fileName.substringBeforeLast("."),
                        rawPages = listOf(content),
                        estimatedPageCount = 1,
                        isSuccessful = true
                    )
                }
            } ?: ExtractionResult(
                title = "Unknown Document",
                rawPages = emptyList(),
                estimatedPageCount = 0,
                isSuccessful = false,
                errorMessage = "Could not open document stream"
            )
        } catch (e: Exception) {
            ExtractionResult(
                title = "Document",
                rawPages = emptyList(),
                estimatedPageCount = 0,
                isSuccessful = false,
                errorMessage = e.message ?: "Failed to extract text from document"
            )
        }
    }

    fun extractFromPdfStream(context: Context, inputStream: InputStream, title: String): ExtractionResult {
        val tempFile = File.createTempFile("pmug_extract_", ".pdf", context.cacheDir)
        return try {
            tempFile.outputStream().use { output -> inputStream.copyTo(output) }
            extractPdfFile(context, tempFile, title)
        } catch (e: Exception) {
            ExtractionResult(title, emptyList(), 0, false, e.message ?: "Could not read PDF")
        } finally {
            tempFile.delete()
        }
    }

    private fun getFileName(context: Context, uri: Uri): String {
        var name = ""
        try {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        name = it.getString(nameIndex) ?: ""
                    }
                }
            }
        } catch (_: Exception) {}

        if (name.isBlank()) {
            name = uri.lastPathSegment?.substringAfterLast("/") ?: "Document.pdf"
        }
        if (!name.endsWith(".pdf", ignoreCase = true) && !name.contains(".")) {
            name = "$name.pdf"
        }
        return name
    }

    @Deprecated("Use extractPdfFile(context, file, title) or the Context overload")
    fun extractFromPdfStream(inputStream: InputStream, title: String): ExtractionResult {
        val bytes = inputStream.readBytes()
        val pages = mutableListOf<String>()

        try {
            // Find all stream ... endstream blocks in the PDF
            val streams = extractStreams(bytes)

            for (streamBytes in streams) {
                val streamContent = String(streamBytes, StandardCharsets.ISO_8859_1)
                // Extract text operators like (Text) Tj, [...] TJ, <Hex> Tj, ' and "
                val pageText = extractTextFromOperators(streamContent)
                if (pageText.isNotBlank()) {
                    pages.add(pageText)
                }
            }

            // Fallback 1: Broad string scan across decompressed streams
            if (pages.isEmpty()) {
                val fallbackText = fallbackStringExtraction(bytes)
                if (fallbackText.isNotBlank()) {
                    pages.add(fallbackText)
                }
            }

            // Fallback 2: Direct ASCII/UTF-8 run extraction across whole PDF file
            if (pages.isEmpty()) {
                val rawTextRun = extractRawTextRuns(bytes)
                if (rawTextRun.isNotBlank()) {
                    pages.add(rawTextRun)
                }
            }

            val docTitle = cleanDocTitle(title)

            return if (pages.isNotEmpty()) {
                ExtractionResult(
                    title = docTitle,
                    rawPages = pages,
                    estimatedPageCount = pages.size,
                    isSuccessful = true
                )
            } else {
                ExtractionResult(
                    title = docTitle,
                    rawPages = listOf("This PDF does not contain extractable text or consists exclusively of scanned images. Please choose a text-based academic document."),
                    estimatedPageCount = 1,
                    isSuccessful = false,
                    errorMessage = "No text found in PDF"
                )
            }
        } catch (e: Exception) {
            return ExtractionResult(
                title = title,
                rawPages = emptyList(),
                estimatedPageCount = 0,
                isSuccessful = false,
                errorMessage = e.message
            )
        }
    }

    private fun cleanDocTitle(filename: String): String {
        return filename
            .substringBeforeLast(".")
            .replace("_", " ")
            .replace("-", " ")
            .trim()
            .ifBlank { "Academic Document" }
    }

    private fun extractStreams(bytes: ByteArray): List<ByteArray> {
        val streams = mutableListOf<ByteArray>()
        var index = 0
        val streamKeyword = "stream".toByteArray(StandardCharsets.ISO_8859_1)
        val endStreamKeyword = "endstream".toByteArray(StandardCharsets.ISO_8859_1)

        while (index < bytes.size) {
            val streamPos = indexOf(bytes, streamKeyword, index)
            if (streamPos == -1) break

            var dataStart = streamPos + streamKeyword.size
            if (dataStart < bytes.size && bytes[dataStart] == '\r'.code.toByte()) dataStart++
            if (dataStart < bytes.size && bytes[dataStart] == '\n'.code.toByte()) dataStart++

            val endStreamPos = indexOf(bytes, endStreamKeyword, dataStart)
            if (endStreamPos == -1) break

            var dataEnd = endStreamPos
            while (dataEnd > dataStart && (bytes[dataEnd - 1] == '\r'.code.toByte() || bytes[dataEnd - 1] == '\n'.code.toByte() || bytes[dataEnd - 1] == ' '.code.toByte())) {
                dataEnd--
            }

            val rawStream = bytes.copyOfRange(dataStart, dataEnd)

            // Decompress FlateDecode
            val decompressed = tryDecompressFlate(rawStream)
            if (decompressed != null && decompressed.isNotEmpty()) {
                streams.add(decompressed)

                // Check for nested streams inside Object Streams (/ObjStm)
                val nested = extractStreams(decompressed)
                if (nested.isNotEmpty()) {
                    streams.addAll(nested)
                }
            } else if (rawStream.isNotEmpty()) {
                streams.add(rawStream)
            }

            index = endStreamPos + endStreamKeyword.size
        }

        return streams
    }

    private fun tryDecompressFlate(data: ByteArray): ByteArray? {
        if (data.size < 2) return null
        return try {
            val inflater = Inflater(false)
            val outputStream = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            inflater.setInput(data)

            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                }
                outputStream.write(buffer, 0, count)
            }
            inflater.end()
            val result = outputStream.toByteArray()
            if (result.isNotEmpty()) result else null
        } catch (_: Exception) {
            try {
                val inflater = Inflater(true)
                val outputStream = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                inflater.setInput(data)
                while (!inflater.finished()) {
                    val count = inflater.inflate(buffer)
                    if (count == 0) break
                    outputStream.write(buffer, 0, count)
                }
                inflater.end()
                val result = outputStream.toByteArray()
                if (result.isNotEmpty()) result else null
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun extractTextFromOperators(content: String): String {
        val sb = StringBuilder()
        var i = 0
        val len = content.length

        while (i < len) {
            if (content[i] == '(') {
                val strContent = parsePdfString(content, i)
                i = strContent.second

                var lookAhead = i
                while (lookAhead < len && content[lookAhead].isWhitespace()) lookAhead++
                if (lookAhead < len) {
                    val c1 = content[lookAhead]
                    val c2 = if (lookAhead + 1 < len) content[lookAhead + 1] else ' '
                    if ((c1 == 'T' && (c2 == 'j' || c2 == 'J')) || c1 == '\'' || c1 == '"') {
                        sb.append(strContent.first).append(" ")
                        i = lookAhead + (if (c1 == 'T') 2 else 1)
                    }
                }
            } else if (content[i] == '[') {
                val arrayContent = parsePdfArray(content, i)
                i = arrayContent.second
                var lookAhead = i
                while (lookAhead < len && content[lookAhead].isWhitespace()) lookAhead++
                if (lookAhead < len - 1 && content[lookAhead] == 'T' && content[lookAhead + 1] == 'J') {
                    sb.append(arrayContent.first).append(" ")
                    i = lookAhead + 2
                }
            } else if (content[i] == '<' && i + 1 < len && content[i + 1] != '<') {
                val closeHex = content.indexOf('>', i)
                if (closeHex != -1) {
                    val hexStr = content.substring(i + 1, closeHex).filter { !it.isWhitespace() }
                    val decoded = decodeHexString(hexStr)
                    i = closeHex + 1
                    var lookAhead = i
                    while (lookAhead < len && content[lookAhead].isWhitespace()) lookAhead++
                    if (lookAhead < len) {
                        val c1 = content[lookAhead]
                        val c2 = if (lookAhead + 1 < len) content[lookAhead + 1] else ' '
                        if ((c1 == 'T' && (c2 == 'j' || c2 == 'J')) || c1 == '\'' || c1 == '"') {
                            sb.append(decoded).append(" ")
                            i = lookAhead + (if (c1 == 'T') 2 else 1)
                        }
                    }
                } else {
                    i++
                }
            } else {
                i++
            }
        }

        return sb.toString().trim()
    }

    private fun parsePdfString(text: String, startIndex: Int): Pair<String, Int> {
        val sb = StringBuilder()
        var i = startIndex + 1
        var depth = 1
        val len = text.length

        while (i < len && depth > 0) {
            val c = text[i]
            if (c == '\\') {
                if (i + 1 < len) {
                    val next = text[i + 1]
                    when (next) {
                        'n' -> { sb.append('\n'); i += 2 }
                        'r' -> { sb.append('\r'); i += 2 }
                        't' -> { sb.append('\t'); i += 2 }
                        'b' -> { sb.append('\b'); i += 2 }
                        'f' -> { sb.append('\u000C'); i += 2 }
                        '(', ')' -> { sb.append(next); i += 2 }
                        '\\' -> { sb.append('\\'); i += 2 }
                        in '0'..'7' -> {
                            var octalLen = 0
                            var octalVal = 0
                            while (octalLen < 3 && i + 1 + octalLen < len && text[i + 1 + octalLen] in '0'..'7') {
                                octalVal = octalVal * 8 + (text[i + 1 + octalLen] - '0')
                                octalLen++
                            }
                            sb.append(octalVal.toChar())
                            i += 1 + octalLen
                        }
                        else -> { sb.append(next); i += 2 }
                    }
                } else {
                    i++
                }
            } else if (c == '(') {
                depth++
                sb.append(c)
                i++
            } else if (c == ')') {
                depth--
                if (depth > 0) sb.append(c)
                i++
            } else {
                sb.append(c)
                i++
            }
        }

        return Pair(sb.toString(), i)
    }

    private fun parsePdfArray(text: String, startIndex: Int): Pair<String, Int> {
        val sb = StringBuilder()
        var i = startIndex + 1
        val len = text.length

        while (i < len && text[i] != ']') {
            if (text[i] == '(') {
                val parsed = parsePdfString(text, i)
                sb.append(parsed.first)
                i = parsed.second
            } else if (text[i] == '<' && i + 1 < len && text[i + 1] != '<') {
                val closeHex = text.indexOf('>', i)
                if (closeHex != -1) {
                    val hexStr = text.substring(i + 1, closeHex).filter { !it.isWhitespace() }
                    sb.append(decodeHexString(hexStr))
                    i = closeHex + 1
                } else {
                    i++
                }
            } else {
                i++
            }
        }

        return Pair(sb.toString(), if (i < len) i + 1 else len)
    }

    private fun decodeHexString(hex: String): String {
        if (hex.isBlank()) return ""
        val sb = StringBuilder()

        // Handle UTF-16BE encoding (starting with FEFF or 4-digit hex sequence)
        if (hex.length >= 8 && (hex.startsWith("FEFF", ignoreCase = true) || (hex.length % 4 == 0 && hex.startsWith("00")))) {
            val start = if (hex.startsWith("FEFF", ignoreCase = true)) 4 else 0
            var i = start
            while (i <= hex.length - 4) {
                val code = hex.substring(i, i + 4).toIntOrNull(16)
                if (code != null) {
                    if (code in 32..126 || code in 128..0xFFFF) {
                        sb.append(code.toChar())
                    } else if (code == 10 || code == 13) {
                        sb.append(' ')
                    }
                }
                i += 4
            }
            if (sb.isNotBlank()) return sb.toString()
        }

        // Standard 2-digit ASCII hex
        var i = 0
        while (i <= hex.length - 2) {
            val byteVal = hex.substring(i, i + 2).toIntOrNull(16)
            if (byteVal != null && byteVal in 32..126) {
                sb.append(byteVal.toChar())
            } else if (byteVal == 10 || byteVal == 13) {
                sb.append(' ')
            }
            i += 2
        }

        return sb.toString()
    }

    private fun fallbackStringExtraction(bytes: ByteArray): String {
        val sb = StringBuilder()
        val text = String(bytes, StandardCharsets.ISO_8859_1)

        // Find all parenthesis strings (...)
        val regex = Regex("\\(([^()]{3,})\\)")
        for (match in regex.findAll(text)) {
            val content = match.groupValues[1]
            if (isCleanTextSnippet(content)) {
                sb.append(content).append(" ")
            }
        }

        return sb.toString().trim()
    }

    private fun extractRawTextRuns(bytes: ByteArray): String {
        val sb = StringBuilder()
        var currentRun = StringBuilder()

        for (b in bytes) {
            val ch = b.toInt() and 0xFF
            if ((ch in 32..126) || ch == 10 || ch == 13 || ch == 9) {
                currentRun.append(ch.toChar())
            } else {
                if (currentRun.length >= 5) {
                    val str = currentRun.toString().trim()
                    if (isCleanTextSnippet(str)) {
                        sb.append(str).append(" ")
                    }
                }
                currentRun = StringBuilder()
            }
        }

        if (currentRun.length >= 5) {
            val str = currentRun.toString().trim()
            if (isCleanTextSnippet(str)) {
                sb.append(str).append(" ")
            }
        }

        return sb.toString().trim()
    }

    private fun isCleanTextSnippet(text: String): Boolean {
        if (text.length < 3) return false
        val invalidKeywords = setOf(
            "Filter", "FlateDecode", "ObjStm", "Length", "Type", "Catalog",
            "Pages", "Font", "FontDescriptor", "Encoding", "Widths", "XObject"
        )
        if (invalidKeywords.any { text.contains(it, ignoreCase = true) }) return false
        val letterCount = text.count { it.isLetter() }
        return letterCount >= (text.length * 0.35)
    }

    private fun indexOf(source: ByteArray, target: ByteArray, fromIndex: Int): Int {
        if (target.isEmpty()) return 0
        val max = source.size - target.size
        for (i in fromIndex..max) {
            var found = true
            for (j in target.indices) {
                if (source[i + j] != target[j]) {
                    found = false
                    break
                }
            }
            if (found) return i
        }
        return -1
    }
}
