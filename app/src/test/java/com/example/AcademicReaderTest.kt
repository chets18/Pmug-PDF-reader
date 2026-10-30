package com.example

import com.example.domain.pdf.AcademicTextCleaner
import com.example.domain.pdf.SentenceSplitter
import com.example.domain.retention.RetentionEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AcademicReaderTest {

    @Test
    fun testHyphenationRepair() {
        val raw = listOf("This is a study on aero-\n dynamic heating in hyper-\n sonic regimes.")
        val cleaned = AcademicTextCleaner.cleanExtractedText(raw)
        assertTrue(cleaned.first().contains("aerodynamic"))
        assertTrue(cleaned.first().contains("hypersonic"))
        assertFalse(cleaned.first().contains("aero-"))
    }

    @Test
    fun testAcademicSentenceSplittingWithAbbreviationsAndDecimals() {
        val text = "According to Dr. Allen et al., the bow shock wave detaches at Mach 5.2. However, Eq. 4 shows q = 1/2 rho v^2."
        val sentences = SentenceSplitter.splitIntoSentences(text)

        // Should split into exactly 2 sentences, not break on "Dr.", "et al.", "Mach 5.2", or "Eq. 4"
        assertEquals(2, sentences.size)
        assertTrue(sentences[0].text.contains("Mach 5.2"))
        assertTrue(sentences[1].text.contains("Eq. 4"))
    }

    @Test
    fun testMathPhoneticPreparation() {
        val mathSentence = "The heat transfer q is proportional to M_\\infty^2 and delta T."
        val spoken = AcademicTextCleaner.prepareForSpeech(mathSentence)
        assertTrue(spoken.contains("Mach infinity"))
        assertTrue(spoken.contains("squared"))
        assertTrue(spoken.contains("delta"))
    }

    @Test
    fun testBionicReadingSplit() {
        val (bold1, rest1) = RetentionEngine.splitForBionicReading("hypersonic")
        assertEquals("hype", bold1)
        assertEquals("rsonic", rest1)

        val (bold2, rest2) = RetentionEngine.splitForBionicReading("of")
        assertEquals("of", bold2)
        assertEquals("", rest2)
    }
}
