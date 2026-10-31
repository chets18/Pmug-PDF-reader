package com.example.domain.tts

/** Base-app boundary for the optional Play-delivered Piper feature. */
interface PiperProvider : TtsEngine {
    fun isInstalled(voiceId: String = PiperVoiceCatalog.defaultVoiceId): Boolean
    fun install(voiceId: String = PiperVoiceCatalog.defaultVoiceId, progressCallback: (Int) -> Unit, onComplete: (Result<Unit>) -> Unit)
    fun synthesize(sentenceId: Int, sentence: String, rate: Float, pitch: Float)
    fun synthesizeAudio(sentence: String, rate: Float): SynthesizedAudio
    fun warmUp()
    fun runSelfTest(report: (stage: String, passed: Boolean, detail: String) -> Unit)
    override fun stop()
    override fun shutdown()
    fun deleteVoice(voiceId: String, onComplete: (Boolean) -> Unit)
    fun remove(onComplete: (Boolean) -> Unit)
}
