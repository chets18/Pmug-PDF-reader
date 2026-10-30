package com.example

import com.example.domain.pdf.AcademicTextCleaner
import com.example.domain.pdf.PdfTextExtractor
import com.example.domain.pdf.SentenceSplitter
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PdfExtractionTest {

    @Test
    fun testSamplePdfsExtractionAndGenerateReport() {
        val sampleDir = File("../sample_pdfs")
        val fallbackDir = File("sample_pdfs")
        val targetDir = if (sampleDir.exists()) sampleDir else fallbackDir

        val reportFile = File("../extraction_report.md")

        val pdfFiles = targetDir.listFiles { _, name -> name.endsWith(".pdf", ignoreCase = true) } ?: emptyArray()
        val reportLines = mutableListOf<String>()
        reportLines.add("# Pmug PDF Extraction Report\n")
        reportLines.add("| File | Pages | Sentences | Chars Removed | Verdict | First 3 Sentences |")
        reportLines.add("|---|---|---|---|---|---|")

        for (file in pdfFiles) {
            val extraction = file.inputStream().use { input ->
                PdfTextExtractor.extractFromPdfStream(input, file.nameWithoutExtension)
            }

            val rawChars = extraction.rawPages.sumOf { it.length }
            val cleanedPages = AcademicTextCleaner.cleanExtractedText(extraction.rawPages)
            val fullCleaned = cleanedPages.firstOrNull() ?: ""
            val cleanedChars = fullCleaned.length
            val charsRemoved = (rawChars - cleanedChars).coerceAtLeast(0)

            val sentences = SentenceSplitter.splitIntoSentences(fullCleaned)
            val firstThree = sentences.take(3).joinToString(" ") { it.text.replace("|", "/") }

            val verdict = when {
                !extraction.isSuccessful || sentences.isEmpty() -> "fails: no extractable text"
                sentences.size < 2 -> "partial: short text"
                else -> "reads fine"
            }

            reportLines.add("| `${file.name}` | ${extraction.estimatedPageCount} | ${sentences.size} | $charsRemoved | **$verdict** | \"$firstThree\" |")
            assertTrue("PDF ${file.name} extraction must not crash", sentences.isNotEmpty())
        }

        reportFile.writeText(reportLines.joinToString("\n"))
    }
}
