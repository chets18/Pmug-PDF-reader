package com.example

import com.example.domain.tts.PiperVoiceCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PiperVoiceTest {

    @Test
    fun testCatalogDefaultVoice() {
        val defaultVoice = PiperVoiceCatalog.getVoice(PiperVoiceCatalog.defaultVoiceId)
        assertNotNull(defaultVoice)
        assertEquals("en_US-ryan-medium", defaultVoice.id)
        assertEquals("male", defaultVoice.gender)
        assertEquals("US", defaultVoice.accent)
    }

    @Test
    fun testCatalogParsingAndVoices() {
        val voices = PiperVoiceCatalog.voices
        assertTrue(voices.isNotEmpty())
        voices.forEach { v ->
            assertNotNull(v.id)
            assertNotNull(v.downloadUrl)
            assertTrue(v.expectedDownloadSize > 0L)
            assertTrue(v.sampleRate > 0)
        }
    }
}
