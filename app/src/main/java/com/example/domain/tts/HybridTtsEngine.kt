package com.example.domain.tts

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlin.math.abs

enum class TtsProviderMode(
    val displayName: String,
    val description: String,
    val provider: String? = null,
    val model: String? = null,
    val voice: String? = null,
    val instructions: String? = null
) {
    PIPER_OFFLINE("Piper (offline neural voice)", "Offline neural voice; downloads a voice model on first use"),
    OPENAI_TTS_HD("TTS-1-HD", "High quality OpenAI voice", "openai", "tts-1-hd", "alloy"),
    ANDROID_SYSTEM("Android System Speech", "Built-in device speech (Offline)"),
    OPENAI_MINI_TTS("GPT-4o mini TTS", "Fast OpenAI voice", "openai", "gpt-4o-mini-tts", "alloy"),
    GEMINI_PUTER(
        "Gemini TTS (Puck)", "Expressive AI voice", "gemini", "gemini-3.8-flash-tts", "Puck",
        "Read academic material with clear, energetic, lively delivery. Sound engaged and confident, keep mathematical terms precise, and avoid rushing or adding dramatic emphasis."
    );

    val isCloud: Boolean get() = provider != null
    val usesEstimatedWordTiming: Boolean get() = isCloud || this == PIPER_OFFLINE
    val fallsBackToAndroid: Boolean get() = isCloud || this == PIPER_OFFLINE

    companion object {
        fun fromVoiceId(voiceId: String): TtsProviderMode? = values().firstOrNull { it.voiceId == voiceId }
    }

    val voiceId: String get() = when (this) {
        PIPER_OFFLINE -> "piper_offline_lessac_medium"
        OPENAI_TTS_HD -> "openai_tts_1_hd"
        ANDROID_SYSTEM -> "android_system"
        OPENAI_MINI_TTS -> "openai_gpt4o_mini_tts"
        GEMINI_PUTER -> "gemini_puter_puck"
    }
}

class HybridTtsEngine(
    context: Context,
    defaultMode: TtsProviderMode = TtsProviderMode.PIPER_OFFLINE
) : TtsEngine, TtsEngineListener {

    companion object {
        private const val TAG = "PmugTts"
    }

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
    private var lastPlayStartTime: Long = 0L

    private val prefetchQueue = TtsPrefetchQueue(capacity = 3) { id, text, rate, pitch ->
        // Producer action for prefetch
        val start = System.currentTimeMillis()
        try {
            // For prefetch test / warm-up
            PrefetchItem(sentenceId = id, text = text, rate = rate, pitch = pitch)
        } catch (e: Throwable) {
            Log.e(TAG, "Prefetch synthesis error for sentence $id: ${e.message}", e)
            null
        }
    }

    init {
        puterEngine.setListener(this)
        puterEngine.setMode(defaultMode)
        piperEngine.setListener(this)
        androidEngine.setListener(this)
        prefetchQueue.start()
    }

    override val isInitialized: Boolean
        get() = activeEngine.isInitialized

    fun switchProvider(mode: TtsProviderMode) {
        if (currentMode == mode) return
        stop()
        currentMode = mode
        activeEngine = engineFor(mode)
        prefetchQueue.flush()
        listener?.onProviderChanged(mode)
        Log.i(TAG, "Switched TTS provider mode to ${mode.displayName}")
    }

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
            if (currentMode == TtsProviderMode.PIPER_OFFLINE && !piperEngine.isVoiceInstalled && !com.example.BuildConfig.PIPER_BUNDLED_FOR_DEBUG) {
                Log.w(TAG, "Piper selected but voice not installed yet; falling back for sentence $sentenceId")
                fallbackSequence(sentenceId, text, rate, pitch, "Piper voice not downloaded yet")
                return
            }
            activeEngine.speakSentence(sentenceId, text, rate, pitch)
        } catch (e: Throwable) {
            Log.e(TAG, "Active engine error for sentence $sentenceId: ${e.message}, falling back", e)
            fallbackSequence(sentenceId, text, rate, pitch, e.message ?: "Could not start speech")
        }
    }

    private fun fallbackSequence(sentenceId: Int, text: String, rate: Float, pitch: Float, errorMessage: String) {
        try {
            if (currentMode == TtsProviderMode.PIPER_OFFLINE && puterEngine.isInitialized && puterEngine.isSignedIn()) {
                Log.i(TAG, "Falling back from Piper to OpenAI HD for sentence $sentenceId")
                puterEngine.setMode(TtsProviderMode.OPENAI_TTS_HD)
                puterEngine.speakSentence(sentenceId, text, rate, pitch)
                listener?.onError(sentenceId, "Piper unavailable ($errorMessage); OpenAI HD handled this sentence.")
                return
            }
        } catch (e: Throwable) {
            Log.e(TAG, "OpenAI fallback error", e)
        }
        androidEngine.speakSentence(sentenceId, text, rate, pitch)
        listener?.onError(sentenceId, "${currentMode.displayName} failed ($errorMessage); Android speech handled this sentence.")
    }

    override fun stop() {
        prefetchQueue.flush()
        puterEngine.stop()
        piperEngine.stop()
        androidEngine.stop()
        Log.i(TAG, "HybridTtsEngine stopped and queues flushed")
    }

    override fun shutdown() {
        prefetchQueue.stop()
        puterEngine.shutdown()
        piperEngine.shutdown()
        androidEngine.shutdown()
        Log.i(TAG, "HybridTtsEngine shut down")
    }

    override fun getAvailableVoices(): List<VoiceOption> {
        val list = mutableListOf<VoiceOption>()
        list.addAll(listOf(TtsProviderMode.PIPER_OFFLINE, TtsProviderMode.OPENAI_TTS_HD, TtsProviderMode.ANDROID_SYSTEM, TtsProviderMode.OPENAI_MINI_TTS, TtsProviderMode.GEMINI_PUTER).map { mode ->
            VoiceOption(mode.voiceId, mode.displayName, "English (US)", true, mode.description)
        })
        list.addAll(androidEngine.getAvailableVoices())
        return list
    }

    override fun setVoice(voiceId: String) {
        val selectedMode = TtsProviderMode.fromVoiceId(voiceId)
        if (selectedMode != null) {
            switchProvider(selectedMode)
        } else {
            switchProvider(TtsProviderMode.ANDROID_SYSTEM)
            androidEngine.setVoice(voiceId)
        }
    }

    override fun onSentenceStarted(sentenceId: Int) {
        val now = System.currentTimeMillis()
        val gap = if (lastPlayStartTime > 0) now - lastPlayStartTime else 0L
        lastPlayStartTime = now
        Log.i(TAG, "Sentence started id=$sentenceId measuredGapMs=$gap")
        listener?.onSentenceStarted(sentenceId)
    }

    override fun onSentenceCompleted(sentenceId: Int) {
        Log.i(TAG, "Sentence completed id=$sentenceId")
        listener?.onSentenceCompleted(sentenceId)
    }

    override fun onError(sentenceId: Int, message: String) {
        Log.e(TAG, "TTS error id=$sentenceId message=$message")
        if (currentMode.isCloud && message.contains("unauthorized", ignoreCase = true)) {
            listener?.onError(sentenceId, "Sign in to Puter to use cloud voices. Open voice settings and choose Sign in to Puter.")
            return
        }
        if (currentMode.fallsBackToAndroid) {
            val failedMode = currentMode
            Log.w(TAG, "${failedMode.displayName} error ($message); using Android fallback for sentence $sentenceId.")
            if (sentenceId != -1 && lastText.isNotBlank()) {
                androidEngine.speakSentence(sentenceId, lastText, lastRate, lastPitch)
            }
            listener?.onError(sentenceId, "${failedMode.displayName} failed; Android speech handled this sentence. $message")
        } else {
            listener?.onError(sentenceId, message)
        }
    }

    override fun onEngineReady() {
        Log.i(TAG, "TTS engine ready")
        listener?.onEngineReady()
    }

    override fun onWordRange(sentenceId: Int, start: Int, end: Int) {
        listener?.onWordRange(sentenceId, start, end)
    }

    override fun onProviderChanged(mode: TtsProviderMode) {
        listener?.onProviderChanged(mode)
    }
}
