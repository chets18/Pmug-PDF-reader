package com.example.domain.tts

/** Base-app boundary for the optional Play-delivered Piper feature. */
interface PiperProvider : TtsEngine {
    fun isInstalled(): Boolean
    fun install(progressCallback: (Int) -> Unit, onComplete: (Result<Unit>) -> Unit)
    fun synthesize(sentenceId: Int, sentence: String, rate: Float, pitch: Float)
    fun synthesizeAudio(sentence: String, rate: Float): SynthesizedAudio
    fun warmUp()
    fun runSelfTest(report: (stage: String, passed: Boolean, detail: String) -> Unit)
    override fun stop()
    override fun shutdown()
    fun remove(onComplete: (Boolean) -> Unit)
}
