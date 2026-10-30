package com.example.domain.tts

interface TtsEngineListener {
    fun onSentenceStarted(sentenceId: Int)
    fun onSentenceCompleted(sentenceId: Int)
    fun onError(sentenceId: Int, message: String)
    fun onEngineReady()
    fun onWordRange(sentenceId: Int, start: Int, end: Int) {}
    fun onProviderChanged(mode: TtsProviderMode) {}
}

interface TtsEngine {
    val isInitialized: Boolean
    fun setListener(listener: TtsEngineListener)
    fun speakSentence(sentenceId: Int, text: String, rate: Float, pitch: Float)
    fun stop()
    fun shutdown()
    fun getAvailableVoices(): List<VoiceOption>
    fun setVoice(voiceId: String)
}

data class VoiceOption(
    val id: String,
    val name: String,
    val locale: String,
    val isNetworkConnectionRequired: Boolean = false,
    val quality: String = "Standard"
)
