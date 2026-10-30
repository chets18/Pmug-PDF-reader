package com.example.domain.tts

import android.content.Context
import android.util.Log

enum class TtsProviderMode(
    val displayName: String,
    val description: String,
    val provider: String? = null,
    val model: String? = null,
    val voice: String? = null,
    val instructions: String? = null
) {
    GEMINI_PUTER(
        "Gemini TTS (Puck)", "Expressive AI voice", "gemini", "gemini-3.8-flash-tts", "Puck",
        "Read academic material with clear, energetic, lively delivery. Sound engaged and confident, keep mathematical terms precise, and avoid rushing or adding dramatic emphasis."
    ),
    OPENAI_MINI_TTS("GPT-4o mini TTS", "Fast OpenAI voice", "openai", "gpt-4o-mini-tts", "alloy"),
    OPENAI_TTS_HD("TTS-1-HD", "High quality OpenAI voice", "openai", "tts-1-hd", "alloy"),
    PIPER_OFFLINE("Piper (download once)", "Offline neural voice; downloads a voice model on first use"),
    ANDROID_SYSTEM("Android System Speech", "Built-in device speech (Offline)");

    val isCloud: Boolean get() = provider != null
    val usesEstimatedWordTiming: Boolean get() = isCloud || this == PIPER_OFFLINE
    val fallsBackToAndroid: Boolean get() = isCloud || this == PIPER_OFFLINE

    companion object {
        fun fromVoiceId(voiceId: String): TtsProviderMode? = values().firstOrNull { it.voiceId == voiceId }
    }

    val voiceId: String get() = when (this) {
        GEMINI_PUTER -> "gemini_puter_puck"
        OPENAI_MINI_TTS -> "openai_gpt4o_mini_tts"
        OPENAI_TTS_HD -> "openai_tts_1_hd"
        PIPER_OFFLINE -> "piper_offline_lessac_medium"
        ANDROID_SYSTEM -> "android_system"
    }
}

class HybridTtsEngine(
    context: Context,
    defaultMode: TtsProviderMode = TtsProviderMode.GEMINI_PUTER
) : TtsEngine, TtsEngineListener {

    private val puterEngine = PuterTtsEngine(context)
    private val piperEngine = PiperFeatureManager(context)
    private val androidEngine = AndroidTtsEngine(context)

    var currentMode: TtsProviderMode = defaultMode
        private set

    private var activeEngine: TtsEngine = engineFor(defaultMode)

    private var listener: TtsEngineListener? = null

    private var lastSentenceId: Int = -1
    private var lastText: String = ""
    private var lastRate: Float = 1.0f
    private var lastPitch: Float = 1.0f

    init {
        puterEngine.setListener(this)
        puterEngine.setMode(defaultMode)
        piperEngine.setListener(this)
        androidEngine.setListener(this)
    }

    override val isInitialized: Boolean
        get() = activeEngine.isInitialized

    fun switchProvider(mode: TtsProviderMode) {
        if (currentMode == mode) return
        stop()
        currentMode = mode
        activeEngine = engineFor(mode)
        listener?.onProviderChanged(mode)
    }

    /** Removes Piper's optional downloaded model and frees its app-private storage. */
    fun removePiperDownloadedModel(onComplete: (Boolean) -> Unit = {}) {
        if (currentMode == TtsProviderMode.PIPER_OFFLINE) switchProvider(TtsProviderMode.ANDROID_SYSTEM)
        piperEngine.remove(onComplete)
    }

    fun preparePiper(progress: (Int) -> Unit, onComplete: (Result<Unit>) -> Unit) =
        piperEngine.prepare(progress, onComplete)

    val piperAvailable: Boolean get() = piperEngine.isModuleInstalled || com.example.BuildConfig.PIPER_BUNDLED_FOR_DEBUG
    val piperVoiceInstalled: Boolean get() = piperEngine.isVoiceInstalled
    val puterSignedIn: Boolean get() = puterEngine.isSignedIn()
    fun signInToPuter() = puterEngine.signIn()
    fun refreshPuterSignInStatus() = puterEngine.refreshSignedInStatus()

    private fun engineFor(mode: TtsProviderMode): TtsEngine = when {
        mode.isCloud -> {
            puterEngine.setMode(mode)
            puterEngine
        }
        mode == TtsProviderMode.PIPER_OFFLINE -> piperEngine
        else -> androidEngine
    }

    override fun setListener(listener: TtsEngineListener) {
        this.listener = listener
    }

    override fun speakSentence(sentenceId: Int, text: String, rate: Float, pitch: Float) {
        lastSentenceId = sentenceId
        lastText = text
        lastRate = rate
        lastPitch = pitch

        try {
            activeEngine.speakSentence(sentenceId, text, rate, pitch)
        } catch (e: Exception) {
            Log.e("HybridTtsEngine", "Active engine error: ${e.message}, falling back to Android TTS")
            fallbackToAndroid(sentenceId, text, rate, pitch, e.message ?: "Could not start cloud speech")
        }
    }

    private fun fallbackToAndroid(sentenceId: Int, text: String, rate: Float, pitch: Float, errorMessage: String) {
        if (activeEngine != androidEngine) {
            androidEngine.speakSentence(sentenceId, text, rate, pitch)
            listener?.onError(sentenceId, "${currentMode.displayName} failed; Android speech handled this sentence. $errorMessage")
        }
    }

    override fun stop() {
        puterEngine.stop()
        piperEngine.stop()
        androidEngine.stop()
    }

    override fun shutdown() {
        puterEngine.shutdown()
        piperEngine.shutdown()
        androidEngine.shutdown()
    }

    override fun getAvailableVoices(): List<VoiceOption> {
        val list = mutableListOf<VoiceOption>()
        list.addAll(listOf(TtsProviderMode.GEMINI_PUTER, TtsProviderMode.OPENAI_MINI_TTS, TtsProviderMode.OPENAI_TTS_HD, TtsProviderMode.PIPER_OFFLINE).map { mode ->
            VoiceOption(mode.voiceId, mode.displayName, "English (US)", true, mode.description)
        })
        list.addAll(androidEngine.getAvailableVoices())
        return list
    }

    override fun setVoice(voiceId: String) {
        val selectedMode = TtsProviderMode.fromVoiceId(voiceId)
        if (selectedMode?.isCloud == true || selectedMode == TtsProviderMode.PIPER_OFFLINE) {
            switchProvider(selectedMode)
        } else {
            switchProvider(TtsProviderMode.ANDROID_SYSTEM)
            androidEngine.setVoice(voiceId)
        }
    }

    // TtsEngineListener forwarding
    override fun onSentenceStarted(sentenceId: Int) {
        listener?.onSentenceStarted(sentenceId)
    }

    override fun onSentenceCompleted(sentenceId: Int) {
        listener?.onSentenceCompleted(sentenceId)
    }

    override fun onError(sentenceId: Int, message: String) {
        if (currentMode.isCloud && message.contains("unauthorized", ignoreCase = true)) {
            listener?.onError(sentenceId, "Sign in to Puter to use cloud voices. Open voice settings and choose Sign in to Puter.")
            return
        }
        if (currentMode.fallsBackToAndroid) {
            val failedMode = currentMode
            Log.w("HybridTtsEngine", "${failedMode.displayName} error ($message); using Android for this sentence.")
            if (sentenceId != -1 && lastText.isNotBlank()) {
                androidEngine.speakSentence(sentenceId, lastText, lastRate, lastPitch)
            }
            listener?.onError(sentenceId, "${failedMode.displayName} failed; Android speech handled this sentence. $message")
        } else {
            listener?.onError(sentenceId, message)
        }
    }

    override fun onEngineReady() {
        listener?.onEngineReady()
    }

    override fun onWordRange(sentenceId: Int, start: Int, end: Int) {
        listener?.onWordRange(sentenceId, start, end)
    }

    override fun onProviderChanged(mode: TtsProviderMode) {
        listener?.onProviderChanged(mode)
    }
}
