package com.example

import android.app.Application
import com.example.domain.tts.AudioTrackPlaybackSink
import com.example.domain.tts.HybridTtsEngine
import com.example.domain.tts.PlaybackSession
import com.example.domain.tts.PlaybackUnit

/** Process-owned TTS and playback objects survive ReaderScreen recomposition and Activity changes. */
class PmugApplication : Application() {
    private val ttsEngineDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) { HybridTtsEngine(this) }
    val ttsEngine: HybridTtsEngine by ttsEngineDelegate
    private val playbackSessionDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        PlaybackSession(
            synthesizer = { unit: PlaybackUnit, speed, _ -> ttsEngine.synthesizeAudio(unit.spokenText, speed) },
            sink = AudioTrackPlaybackSink(22_050)
        )
    }
    val playbackSession: PlaybackSession by playbackSessionDelegate

    override fun onTerminate() {
        if (playbackSessionDelegate.isInitialized()) playbackSession.close()
        if (ttsEngineDelegate.isInitialized()) ttsEngine.shutdown()
        super.onTerminate()
    }
}
