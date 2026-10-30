package com.example.domain.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale

class AndroidTtsEngine(private val context: Context) : TtsEngine {

    private var tts: TextToSpeech? = null
    private var listener: TtsEngineListener? = null
    override var isInitialized: Boolean = false
        private set

    private var pendingSpeech: Triple<Int, String, Float>? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.let { engine ->
                    val result = engine.setLanguage(Locale.US)
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        Log.w("AndroidTtsEngine", "Locale.US not supported or missing data, falling back to default")
                        engine.setLanguage(Locale.getDefault())
                    }
                    isInitialized = true
                    setupUtteranceListener()
                    listener?.onEngineReady()

                    // If a speech request was made while initializing, trigger it now
                    pendingSpeech?.let { (id, text, rate) ->
                        speakSentence(id, text, rate, 1.0f)
                        pendingSpeech = null
                    }
                }
            } else {
                Log.e("AndroidTtsEngine", "TextToSpeech init failed with status: $status")
                listener?.onError(-1, "TextToSpeech initialization failed")
            }
        }
    }

    private fun setupUtteranceListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                val sentenceId = utteranceId?.toIntOrNull() ?: return
                listener?.onSentenceStarted(sentenceId)
            }

            override fun onDone(utteranceId: String?) {
                val sentenceId = utteranceId?.toIntOrNull() ?: return
                listener?.onSentenceCompleted(sentenceId)
            }

            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                val sentenceId = utteranceId?.toIntOrNull() ?: return
                listener?.onWordRange(sentenceId, start, end)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                val sentenceId = utteranceId?.toIntOrNull() ?: return
                listener?.onError(sentenceId, "TTS playback error")
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                val sentenceId = utteranceId?.toIntOrNull() ?: return
                listener?.onError(sentenceId, "TTS error code: $errorCode")
            }
        })
    }

    override fun setListener(listener: TtsEngineListener) {
        this.listener = listener
        if (isInitialized) {
            listener.onEngineReady()
        }
    }

    override fun speakSentence(sentenceId: Int, text: String, rate: Float, pitch: Float) {
        if (!isInitialized || tts == null) {
            pendingSpeech = Triple(sentenceId, text, rate)
            return
        }

        try {
            tts?.setSpeechRate(rate)
            tts?.setPitch(pitch)

            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, sentenceId.toString())
            }

            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, sentenceId.toString())
        } catch (e: Exception) {
            Log.e("AndroidTtsEngine", "Error speaking sentence: ${e.message}")
            listener?.onError(sentenceId, e.message ?: "Failed to speak")
        }
    }

    override fun stop() {
        try {
            tts?.stop()
        } catch (e: Exception) {
            Log.e("AndroidTtsEngine", "Error stopping TTS: ${e.message}")
        }
    }

    override fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
            tts = null
            isInitialized = false
        } catch (e: Exception) {
            Log.e("AndroidTtsEngine", "Error shutting down TTS: ${e.message}")
        }
    }

    override fun getAvailableVoices(): List<VoiceOption> {
        val list = mutableListOf<VoiceOption>()
        try {
            tts?.voices?.forEach { voice ->
                if (voice.locale.language == "en") {
                    val quality = when (voice.quality) {
                        Voice.QUALITY_VERY_HIGH -> "Very High (Neural)"
                        Voice.QUALITY_HIGH -> "High"
                        Voice.QUALITY_NORMAL -> "Standard"
                        else -> "Compact"
                    }
                    list.add(
                        VoiceOption(
                            id = voice.name,
                            name = formatVoiceName(voice.name),
                            locale = voice.locale.displayName,
                            isNetworkConnectionRequired = voice.isNetworkConnectionRequired,
                            quality = quality
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.w("AndroidTtsEngine", "Could not fetch voices: ${e.message}")
        }

        if (list.isEmpty()) {
            list.add(VoiceOption("default", "Standard Academic Voice", "English (US)"))
        }

        return list.sortedByDescending { it.quality }
    }

    private fun formatVoiceName(rawName: String): String {
        return when {
            rawName.contains("female", ignoreCase = true) -> "Academic Female (Natural)"
            rawName.contains("male", ignoreCase = true) -> "Academic Male (Natural)"
            rawName.contains("en-us-x-sfg", ignoreCase = true) -> "Neural Studio Voice"
            else -> rawName.substringAfter("en-us-").replace("-", " ").replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
        }
    }

    override fun setVoice(voiceId: String) {
        if (!isInitialized || voiceId == "default") return
        try {
            tts?.voices?.find { it.name == voiceId }?.let { voice ->
                tts?.voice = voice
            }
        } catch (e: Exception) {
            Log.w("AndroidTtsEngine", "Failed to set voice: ${e.message}")
        }
    }
}
