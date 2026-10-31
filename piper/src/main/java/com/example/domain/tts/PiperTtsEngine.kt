package com.example.domain.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/** Multi-voice Piper TTS engine supporting voice switching, model reloading, and streaming. */
class PiperTtsEngine(context: Context) : TtsEngine {

    private val appContext = context.applicationContext
    private val modelInstaller = PiperModelInstaller(appContext.filesDir)
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "pmug-piper-tts").apply { isDaemon = true }
    }
    private val audioWriter = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "pmug-piper-audio-writer").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val synthesisLock = Any()
    private val requestGeneration = AtomicLong(0L)

    @Volatile private var tts: OfflineTts? = null
    @Volatile private var listener: TtsEngineListener? = null
    @Volatile private var isClosed = false
    @Volatile private var audioTrack: AudioTrack? = null
    @Volatile private var audioTrackObjectsCreatedCount = 0
    @Volatile private var pendingMarkerSentenceId = -1
    @Volatile private var framesSubmitted = 0L
    private var lastChunkEndTime: Long = 0L

    var currentVoiceId: String = PiperVoiceCatalog.defaultVoiceId
        private set

    override val isInitialized: Boolean
        get() = tts != null

    override fun setListener(listener: TtsEngineListener) {
        this.listener = listener
        if (isInitialized) mainHandler.post { listener.onEngineReady() }
    }

    override fun setVoice(voiceId: String) {
        if (currentVoiceId == voiceId && tts != null) return
        stop()
        worker.execute {
            synchronized(synthesisLock) {
                try {
                    tts?.release()
                } catch (_: Throwable) {}
                tts = null
                currentVoiceId = voiceId
                try {
                    getOrCreateEngine()
                    Log.i(TAG, "Switched and loaded voice id=$voiceId")
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to load voice id=$voiceId, falling back to default", e)
                    currentVoiceId = PiperVoiceCatalog.defaultVoiceId
                    getOrCreateEngine()
                }
            }
        }
    }

    fun synthesizeAudio(text: String, rate: Float): SynthesizedAudio {
        val synthStart = System.currentTimeMillis()
        val audio = synchronized(synthesisLock) {
            getOrCreateEngine().generate(text, sid = 0, speed = rate.coerceIn(0.5f, 2.0f))
        }
        val synthEnd = System.currentTimeMillis()
        val synthTimeMs = synthEnd - synthStart

        val sampleRate = if (audio.sampleRate > 0) audio.sampleRate else 22050
        val samples = audio.samples ?: floatArrayOf()
        val audioDurationMs = if (sampleRate > 0) (samples.size * 1000L / sampleRate) else 0L

        // Trim near-silence
        var startIdx = 0
        while (startIdx < samples.size && abs(samples[startIdx]) <= 0.01f) {
            startIdx++
        }
        var endIdx = samples.size - 1
        while (endIdx > startIdx && abs(samples[endIdx]) <= 0.01f) {
            endIdx--
        }
        val trimmed = if (startIdx <= endIdx) samples.copyOfRange(startIdx, endIdx + 1) else floatArrayOf()
        return SynthesizedAudio(trimmed, sampleRate, 0f)
    }

    fun warmUp() {
        worker.execute {
            try {
                synchronized(synthesisLock) {
                    getOrCreateEngine().generate("Warm up.", sid = 0, speed = 1.0f)
                }
                Log.i(TAG, "PmugTts: Piper engine warmed up")
            } catch (e: Throwable) {
                Log.w(TAG, "Piper warm-up failed: ${e.message}")
            }
        }
    }

    override fun speakSentence(sentenceId: Int, text: String, rate: Float, pitch: Float) {
        if (isClosed) {
            listener?.onError(sentenceId, "Piper speech engine is shut down")
            return
        }

        val generation = requestGeneration.incrementAndGet()
        worker.execute {
            val synthStart = System.currentTimeMillis()
            try {
                val engine = getOrCreateEngine()
                if (generation != requestGeneration.get() || isClosed) return@execute

                val audio = synchronized(synthesisLock) {
                    engine.generate(text, sid = 0, speed = rate.coerceIn(0.5f, 2.0f))
                }
                val synthEnd = System.currentTimeMillis()
                val synthTimeMs = synthEnd - synthStart

                if (generation != requestGeneration.get() || isClosed) return@execute

                val sampleRate = if (audio.sampleRate > 0) audio.sampleRate else 22050
                val samples = audio.samples ?: floatArrayOf()

                // Trim near-silence
                var startIdx = 0
                while (startIdx < samples.size && abs(samples[startIdx]) <= 0.01f) {
                    startIdx++
                }
                var endIdx = samples.size - 1
                while (endIdx > startIdx && abs(samples[endIdx]) <= 0.01f) {
                    endIdx--
                }
                val trimmed = if (startIdx <= endIdx) samples.copyOfRange(startIdx, endIdx + 1) else floatArrayOf()

                mainHandler.post {
                    playOrStreamSamples(sentenceId, generation, trimmed, sampleRate)
                }
            } catch (error: Throwable) {
                Log.e(TAG, "Piper synthesize failed sentenceId=$sentenceId", error)
                mainHandler.post {
                    if (generation == requestGeneration.get() && !isClosed) {
                        listener?.onError(sentenceId, error.message ?: "Piper speech failed")
                    }
                }
            }
        }
    }

    private fun playOrStreamSamples(sentenceId: Int, generation: Long, samples: FloatArray, sampleRate: Int) {
        if (generation != requestGeneration.get() || isClosed) return

        val track = getOrCreateAudioTrack(sampleRate)
        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
            track.play()
        }
        if (sentenceId >= 0) {
            pendingMarkerSentenceId = sentenceId
            track.setNotificationMarkerPosition((framesSubmitted + samples.size).toInt())
        }

        audioWriter.execute {
            try {
                var offset = 0
                val chunkSize = 2048
                while (offset < samples.size) {
                    if (generation != requestGeneration.get() || isClosed) break
                    val written = track.write(samples, offset, minOf(chunkSize, samples.size - offset), AudioTrack.WRITE_BLOCKING)
                    if (written <= 0) break
                    offset += written
                }
                if (sentenceId >= 0) {
                    mainHandler.post {
                        if (generation == requestGeneration.get()) {
                            listener?.onSentenceStarted(sentenceId)
                        }
                    }
                }
                framesSubmitted += offset
            } catch (e: Throwable) {
                Log.e(TAG, "Streaming audio write error", e)
            }
        }
    }

    private fun getOrCreateAudioTrack(sampleRate: Int): AudioTrack {
        audioTrack?.let { if (it.state == AudioTrack.STATE_INITIALIZED) return it }
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes((minBuf * 2).coerceAtLeast(8192))
            .build()
        track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
            override fun onMarkerReached(track: AudioTrack) {
                val completedId = pendingMarkerSentenceId
                pendingMarkerSentenceId = -1
                lastChunkEndTime = System.currentTimeMillis()
                if (completedId >= 0) listener?.onSentenceCompleted(completedId)
            }
            override fun onPeriodicNotification(track: AudioTrack) = Unit
        }, mainHandler)
        audioTrackObjectsCreatedCount++
        audioTrack = track
        return track
    }

    fun install(voiceId: String, progress: (Int) -> Unit, complete: (Result<Unit>) -> Unit) {
        worker.execute {
            val result = try {
                val voice = PiperVoiceCatalog.getVoice(voiceId)
                modelInstaller.ensureInstalled(voice) { progress(it) }
                setVoice(voiceId)
                Result.success(Unit)
            } catch (error: Throwable) {
                Result.failure(error)
            }
            mainHandler.post { complete(result) }
        }
    }

    fun isVoiceInstalled(voiceId: String): Boolean = modelInstaller.isInstalled(voiceId)

    private fun getOrCreateEngine(): OfflineTts {
        tts?.let { return it }
        val voice = PiperVoiceCatalog.getVoice(currentVoiceId)
        val model = modelInstaller.ensureInstalled(voice)
        try {
            System.loadLibrary("sherpa-onnx-jni")
        } catch (_: Throwable) {}
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = model.modelFile.absolutePath,
                    tokens = model.tokensFile.absolutePath,
                    dataDir = model.phonemeDataDir.absolutePath
                ),
                numThreads = 2,
                debug = false,
                provider = "cpu"
            ),
            maxNumSentences = 1
        )
        return OfflineTts(config = config).also {
            tts = it
            mainHandler.post { listener?.onEngineReady() }
        }
    }

    fun runSelfTest(report: (String, Boolean, String) -> Unit) {
        try {
            val voice = PiperVoiceCatalog.getVoice(currentVoiceId)
            modelInstaller.ensureInstalled(voice)
            System.loadLibrary("sherpa-onnx-jni")
            val engine = getOrCreateEngine()
            val audio = engine.generate("Piper diagnostic test.", sid = 0, speed = 1.0f)
            report("Piper self-test", true, "sampleRate=${audio.sampleRate} samples=${audio.samples?.size}")
        } catch (e: Throwable) {
            report("Piper self-test", false, e.message ?: "Failed")
        }
    }

    override fun stop() {
        requestGeneration.incrementAndGet()
        try {
            audioTrack?.let {
                if (it.playState == AudioTrack.PLAYSTATE_PLAYING) it.stop()
                it.flush()
                framesSubmitted = 0L
                pendingMarkerSentenceId = -1
            }
        } catch (_: Throwable) {}
        lastChunkEndTime = 0L
    }

    override fun shutdown() {
        if (isClosed) return
        isClosed = true
        requestGeneration.incrementAndGet()
        stop()
        try {
            audioTrack?.release()
        } catch (_: Throwable) {}
        audioTrack = null
        worker.execute {
            try {
                tts?.release()
            } catch (_: Throwable) {}
            tts = null
        }
        worker.shutdown()
        audioWriter.shutdownNow()
    }

    fun deleteVoice(voiceId: String, onComplete: (Boolean) -> Unit = {}) {
        stop()
        worker.execute {
            val deleted = try {
                if (currentVoiceId == voiceId) {
                    tts?.release()
                    tts = null
                }
                modelInstaller.deleteVoice(voiceId)
            } catch (_: Throwable) {
                false
            }
            mainHandler.post { onComplete(deleted) }
        }
    }

    fun deleteDownloadedVoice(onComplete: (Boolean) -> Unit = {}) {
        stop()
        worker.execute {
            val deleted = try {
                tts?.release()
                tts = null
                modelInstaller.deleteAllVoices()
            } catch (_: Throwable) {
                false
            }
            mainHandler.post { onComplete(deleted) }
        }
    }

    override fun getAvailableVoices(): List<VoiceOption> {
        return PiperVoiceCatalog.voices.map { v ->
            VoiceOption(
                id = v.id,
                name = v.displayName,
                locale = "English (${v.accent})",
                isNetworkConnectionRequired = true,
                quality = "${v.quality.replaceFirstChar { it.uppercase() }}; ${if (modelInstaller.isInstalled(v.id)) "Downloaded" else "Not downloaded (~${v.expectedDownloadSize / (1024*1024)}MB)"}"
            )
        }
    }

    private companion object {
        const val TAG = "PmugPiper"
    }
}
