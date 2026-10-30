package com.example.piper

import android.content.Context
import com.example.domain.tts.PiperProvider
import com.example.domain.tts.PiperTtsEngine
import com.example.domain.tts.TtsEngineListener
import com.example.domain.tts.VoiceOption
import java.io.File

class PiperProviderImpl(context: Context) : PiperProvider {
    private val appContext = context.applicationContext
    private val engine = PiperTtsEngine(appContext)

    override val isInitialized: Boolean
        get() = engine.isInitialized

    override fun isInstalled(): Boolean = engine.isVoiceInstalled()
    override fun setListener(listener: TtsEngineListener) = engine.setListener(listener)
    override fun install(progressCallback: (Int) -> Unit, onComplete: (Result<Unit>) -> Unit) = engine.install(progressCallback, onComplete)
    override fun speakSentence(sentenceId: Int, text: String, rate: Float, pitch: Float) = engine.speakSentence(sentenceId, text, rate, pitch)
    override fun synthesize(sentenceId: Int, sentence: String, rate: Float, pitch: Float) = engine.speakSentence(sentenceId, sentence, rate, pitch)
    override fun getAvailableVoices(): List<VoiceOption> = engine.getAvailableVoices()
    override fun setVoice(voiceId: String) = engine.setVoice(voiceId)
    override fun runSelfTest(report: (String, Boolean, String) -> Unit) = engine.runSelfTest(report)
    override fun stop() = engine.stop()
    override fun shutdown() = engine.shutdown()
    override fun remove(onComplete: (Boolean) -> Unit) = engine.deleteDownloadedVoice { deleted ->
        val root = File(appContext.filesDir, "tts/piper")
        val allDeleted = root.deleteRecursively() || !root.exists()
        onComplete(deleted && allDeleted)
    }
}
