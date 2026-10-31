package com.example.domain.tts

import android.content.Context
import android.util.Log

enum class TtsProviderMode(
    val displayName: String,
    val description: String
) {
    PIPER_OFFLINE("Piper (offline neural voice)", "Offline neural voice; downloads a voice model on first use"),
    ANDROID_SYSTEM("Android System Speech", "Built-in device speech (Offline)");

    val isCloud: Boolean get() = false
    val usesEstimatedWordTiming: Boolean get() = true
    val fallsBackToAndroid: Boolean get() = true

    companion object {
        fun fromVoiceId(voiceId: String): TtsProviderMode {
            return when (voiceId) {
                "gemini_puter_puck", "openai_gpt4o_mini_tts", "openai_tts_1_hd" -> PIPER_OFFLINE // Migrate old cloud providers to Piper
                else -> values().firstOrNull { it.voiceId == voiceId } ?: PIPER_OFFLINE
            }
        }
    }

    val voiceId: String get() = when (this) {
        PIPER_OFFLINE -> "piper_offline_lessac_medium"
        ANDROID_SYSTEM -> "android_system"
    }
}

class HybridTtsEngine(
    context: Context,
    defaultMode: TtsProviderMode = TtsProviderMode.PIPER_OFFLINE
) : TtsEngine, TtsEngineListener {

    companion object {
        private const val TAG = "PmugTts"
    }

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
        Log.i(TAG, "PmugTts: Switched TTS provider mode to ${mode.displayName}")
    }

    fun removePiperDownloadedModel(onComplete: (Boolean) -> Unit = {}) {
        if (currentMode == TtsProviderMode.PIPER_OFFLINE) switchProvider(TtsProviderMode.ANDROID_SYSTEM)
        piperEngine.remove(onComplete)
    }

    fun preparePiper(voiceId: String = PiperVoiceCatalog.defaultVoiceId, progress: (Int) -> Unit, onComplete: (Result<Unit>) -> Unit) =
        piperEngine.prepare(voiceId, progress) { result ->
            if (result.isSuccess) piperEngine.warmUp()
            onComplete(result)
        }

    fun deletePiperVoice(voiceId: String, onComplete: (Boolean) -> Unit = {}) {
        piperEngine.deleteVoice(voiceId, onComplete)
    }

    val piperAvailable: Boolean get() = piperEngine.isModuleInstalled || com.example.BuildConfig.PIPER_BUNDLED_FOR_DEBUG
    val piperVoiceInstalled: Boolean get() = piperEngine.isVoiceInstalled()
    fun isPiperVoiceInstalled(voiceId: String): Boolean = piperEngine.isVoiceInstalled(voiceId)

    fun synthesizeAudio(text: String, rate: Float): SynthesizedAudio {
        return if (currentMode == TtsProviderMode.PIPER_OFFLINE && piperVoiceInstalled) {
            piperEngine.synthesizeAudio(text, rate)
        } else {
            SynthesizedAudio(FloatArray(0), 22050)
        }
    }

    fun warmUpPiper() {
        if (piperVoiceInstalled) piperEngine.warmUp()
    }

    private fun engineFor(mode: TtsProviderMode): TtsEngine = when (mode) {
        TtsProviderMode.PIPER_OFFLINE -> piperEngine
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
            if (currentMode == TtsProviderMode.PIPER_OFFLINE && !piperEngine.isVoiceInstalled() && !com.example.BuildConfig.PIPER_BUNDLED_FOR_DEBUG) {
                Log.w(TAG, "PmugTts: Piper selected but voice not installed yet; falling back to Android for sentence $sentenceId")
                fallbackToAndroid(sentenceId, text, rate, pitch, "Piper voice not downloaded yet")
                return
            }
            activeEngine.speakSentence(sentenceId, text, rate, pitch)
        } catch (e: Throwable) {
            Log.e(TAG, "PmugTts: Active engine error for sentence $sentenceId: ${e.message}, falling back", e)
            fallbackToAndroid(sentenceId, text, rate, pitch, e.message ?: "Could not start speech")
        }
    }

    private fun fallbackToAndroid(sentenceId: Int, text: String, rate: Float, pitch: Float, errorMessage: String) {
        if (activeEngine != androidEngine) {
            androidEngine.speakSentence(sentenceId, text, rate, pitch)
            listener?.onError(sentenceId, "Piper unavailable ($errorMessage); Android speech handled this sentence.")
        }
    }

    override fun stop() {
        piperEngine.stop()
        androidEngine.stop()
        Log.i(TAG, "PmugTts: HybridTtsEngine stopped")
    }

    override fun shutdown() {
        piperEngine.shutdown()
        androidEngine.shutdown()
        Log.i(TAG, "PmugTts: HybridTtsEngine shut down")
    }

    override fun getAvailableVoices(): List<VoiceOption> {
        val list = mutableListOf<VoiceOption>()
        list.addAll(listOf(TtsProviderMode.PIPER_OFFLINE, TtsProviderMode.ANDROID_SYSTEM).map { mode ->
            VoiceOption(mode.voiceId, mode.displayName, "English (US)", true, mode.description)
        })
        list.addAll(androidEngine.getAvailableVoices())
        return list
    }

    val currentVoiceId: String get() = piperEngine.currentVoiceId

    override fun setVoice(voiceId: String) {
        val selectedMode = TtsProviderMode.fromVoiceId(voiceId)
        if (selectedMode == TtsProviderMode.PIPER_OFFLINE) {
            switchProvider(TtsProviderMode.PIPER_OFFLINE)
            piperEngine.setVoice(voiceId)
        } else {
            switchProvider(TtsProviderMode.ANDROID_SYSTEM)
            androidEngine.setVoice(voiceId)
        }
    }

    override fun onSentenceStarted(sentenceId: Int) {
        Log.i(TAG, "PmugTts: Sentence started id=$sentenceId")
        listener?.onSentenceStarted(sentenceId)
    }

    override fun onSentenceCompleted(sentenceId: Int) {
        Log.i(TAG, "PmugTts: Sentence completed id=$sentenceId")
        listener?.onSentenceCompleted(sentenceId)
    }

    override fun onError(sentenceId: Int, message: String) {
        Log.e(TAG, "PmugTts: TTS error id=$sentenceId message=$message")
        if (currentMode.fallsBackToAndroid) {
            val failedMode = currentMode
            Log.w(TAG, "PmugTts: ${failedMode.displayName} error ($message); using Android fallback for sentence $sentenceId.")
            if (sentenceId != -1 && lastText.isNotBlank()) {
                androidEngine.speakSentence(sentenceId, lastText, lastRate, lastPitch)
            }
            listener?.onError(sentenceId, "${failedMode.displayName} failed; Android speech handled this sentence. $message")
        } else {
            listener?.onError(sentenceId, message)
        }
    }

    override fun onEngineReady() {
        Log.i(TAG, "PmugTts: TTS engine ready")
        listener?.onEngineReady()
    }

    override fun onWordRange(sentenceId: Int, start: Int, end: Int) {
        listener?.onWordRange(sentenceId, start, end)
    }

    override fun onProviderChanged(mode: TtsProviderMode) {
        listener?.onProviderChanged(mode)
    }
}
