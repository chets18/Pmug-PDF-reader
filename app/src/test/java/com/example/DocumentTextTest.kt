package com.example

import com.example.domain.pdf.DocumentText
import com.example.domain.pdf.PageExtractionPriority
import com.example.domain.pdf.PageNumberValidation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DocumentTextTest {
    @Test fun markdownRemovesMarkupAndIgnoresTablesAndImages() {
        val text = DocumentText.stripMarkdown("# Heading\nA **bold** [link](https://x).\n\n|a|b|\n|--|--|\n![alt](img.png)\n```kotlin\nprintln(1)\n```")
        assertTrue(text.contains("Heading"))
        assertTrue(text.contains("A bold link."))
        assertTrue(text.contains("Code block."))
        assertTrue(!text.contains("println") && !text.contains("img.png") && !text.contains("|a|b|"))
    }

    @Test fun textPaginatorKeepsParagraphsAndBoundsPages() {
        val pages = DocumentText.paginate((1..12).joinToString("\n\n") { "Paragraph $it " + "x".repeat(180) }, 400)
        assertTrue(pages.size > 1)
        assertTrue(pages.all { it.length <= 400 })
        assertTrue(pages.joinToString(" ").contains("Paragraph 12"))
        assertEquals(listOf(""), DocumentText.paginate(" \n "))
    }

    @Test fun docxExtractorReadsParagraphsAndSkipsTableContent() {
        val file = File.createTempFile("pmug-test", ".docx")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write("""<?xml version="1.0"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>Title</w:t></w:r></w:p><w:p><w:r><w:t>Body text.</w:t></w:r></w:p><w:tbl><w:tr><w:tc><w:p><w:r><w:t>Ignore me</w:t></w:r></w:p></w:tc></w:tr></w:tbl></w:body></w:document>""".toByteArray())
            zip.closeEntry()
        }
        try {
            val text = DocumentText.extractDocx(file)
            assertTrue(text.contains("Title") && text.contains("Body text."))
            assertTrue(!text.contains("Ignore me"))
        } finally { file.delete() }
    }

    @Test fun targetPageGetsPriorityAndPageNumberIsValidated() {
        assertEquals(listOf(5, 4, 6, 1, 2, 3, 7), PageExtractionPriority.order(7, 5))
        assertEquals(7, PageNumberValidation.parse("7", 7))
        assertNull(PageNumberValidation.parse("8", 7))
        assertNull(PageNumberValidation.parse("x", 7))
    }
}
