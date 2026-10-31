package com.example

import com.example.domain.tts.TtsProviderMode
import com.example.domain.tts.PositionMapper
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TtsEngineTest {

    @Test
    fun testPuterVoiceMigrationToPiper() {
        assertEquals(TtsProviderMode.PIPER_OFFLINE, TtsProviderMode.fromVoiceId("gemini_puter_puck"))
        assertEquals(TtsProviderMode.PIPER_OFFLINE, TtsProviderMode.fromVoiceId("openai_gpt4o_mini_tts"))
        assertEquals(TtsProviderMode.PIPER_OFFLINE, TtsProviderMode.fromVoiceId("openai_tts_1_hd"))
        assertEquals(TtsProviderMode.PIPER_OFFLINE, TtsProviderMode.fromVoiceId("piper_offline_lessac_medium"))
        assertEquals(TtsProviderMode.ANDROID_SYSTEM, TtsProviderMode.fromVoiceId("android_system"))
    }

    @Test
    fun testPositionMapper() {
        val mapper = PositionMapper()
        // Record mappings at 1.0x speed
        mapper.addMapping(0L, 0L, 1.0f)
        mapper.addMapping(1000L, 1000L, 1.0f)

        assertEquals(0L, mapper.mapOutputToSource(0L))
        assertEquals(500L, mapper.mapOutputToSource(500L))
        assertEquals(1000L, mapper.mapOutputToSource(1000L))
    }
}
