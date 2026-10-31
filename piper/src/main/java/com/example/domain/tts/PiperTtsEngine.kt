package com.example.domain.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.os.Build
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/** Optional local neural TTS with continuous AudioTrack streaming and paragraph pipeline. */
class PiperTtsEngine(context: Context) : TtsEngine {

    private val appContext = context.applicationContext
    private val modelInstaller = PiperModelInstaller(appContext.filesDir)
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "pmug-piper-tts").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val requestGeneration = AtomicLong(0L)

    @Volatile private var tts: OfflineTts? = null
    @Volatile private var listener: TtsEngineListener? = null
    @Volatile private var isClosed = false
    @Volatile private var audioTrack: AudioTrack? = null
    @Volatile private var audioTrackObjectsCreatedCount = 0
    private var lastChunkEndTime: Long = 0L

    override val isInitialized: Boolean
        get() = tts != null

    override fun setListener(listener: TtsEngineListener) {
        this.listener = listener
        if (isInitialized) mainHandler.post { listener.onEngineReady() }
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

                val audio = engine.generate(text, sid = 0, speed = rate.coerceIn(0.5f, 2.0f))
                val synthEnd = System.currentTimeMillis()
                val synthTimeMs = synthEnd - synthStart

                if (generation != requestGeneration.get() || isClosed) return@execute

                val sampleRate = if (audio.sampleRate > 0) audio.sampleRate else 22050
                val samples = audio.samples ?: floatArrayOf()
                val audioDurationMs = if (sampleRate > 0) (samples.size * 1000L / sampleRate) else 0L
                val rtf = if (audioDurationMs > 0) synthTimeMs.toFloat() / audioDurationMs.toFloat() else 0f

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
                val leadingSilenceMs = if (sampleRate > 0) (startIdx * 1000L / sampleRate) else 0L
                val trailingSilenceMs = if (sampleRate > 0) ((samples.size - 1 - endIdx) * 1000L / sampleRate) else 0L

                // Add 120ms pause between sentences
                val pauseSamples = (sampleRate * 0.12f).toInt()
                val finalSamples = FloatArray(trimmed.size + pauseSamples)
                System.arraycopy(trimmed, 0, finalSamples, 0, trimmed.size)

                val finalAudioDurationMs = if (sampleRate > 0) (finalSamples.size * 1000L / sampleRate) else audioDurationMs

                Log.i(TAG, "PmugTts: chunkId=$sentenceId synthStart=$synthStart synthEnd=$synthEnd synthTimeMs=$synthTimeMs audioDurationMs=$finalAudioDurationMs rtf=$rtf leadingSilenceTrimmedMs=$leadingSilenceMs trailingSilenceTrimmedMs=$trailingSilenceMs audioTrackObjectsCreated=$audioTrackObjectsCreatedCount")

                mainHandler.post {
                    playOrStreamSamples(sentenceId, generation, finalSamples, sampleRate)
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

        val playbackStartTime = System.currentTimeMillis()
        val gapMs = if (lastChunkEndTime > 0) playbackStartTime - lastChunkEndTime else 0L
        if (gapMs > 250L) {
            Log.w(TAG, "PmugTts: Underrun warning! Gap between chunks was ${gapMs}ms (queue starved)")
        }

        Log.i(TAG, "PmugTts: playbackStartTime=$playbackStartTime gapMs=$gapMs queueDepth=1")

        val track = getOrCreateAudioTrack(sampleRate)
        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
            track.play()
        }

        worker.execute {
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
                // Simulate completion after duration
                val durationMs = (samples.size * 1000L / sampleRate)
                Thread.sleep(durationMs.coerceAtLeast(10L))
                lastChunkEndTime = System.currentTimeMillis()
                Log.i(TAG, "PmugTts: playbackEndTime=$lastChunkEndTime chunkId=$sentenceId")
                if (sentenceId >= 0) {
                    mainHandler.post {
                        if (generation == requestGeneration.get()) {
                            listener?.onSentenceCompleted(sentenceId)
                        }
                    }
                }
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
        audioTrackObjectsCreatedCount++
        audioTrack = track
        Log.i(TAG, "PmugTts: Created long-lived AudioTrack object count=$audioTrackObjectsCreatedCount sampleRate=$sampleRate")
        return track
    }

    fun install(progress: (Int) -> Unit, complete: (Result<Unit>) -> Unit) {
        worker.execute {
            val result = try {
                modelInstaller.ensureInstalled { progress(it) }
                getOrCreateEngine()
                Result.success(Unit)
            } catch (error: Throwable) {
                Result.failure(error)
            }
            mainHandler.post { complete(result) }
        }
    }

    fun isVoiceInstalled(): Boolean = modelInstaller.isInstalled()

    private fun getOrCreateEngine(): OfflineTts {
        tts?.let { return it }
        val model = modelInstaller.ensureInstalled()
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
            val model = modelInstaller.ensureInstalled()
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
    }

    fun deleteDownloadedVoice(onComplete: (Boolean) -> Unit = {}) {
        stop()
        worker.execute {
            val deleted = try {
                tts?.release()
                tts = null
                modelInstaller.deleteDownloadedModel()
            } catch (_: Throwable) {
                false
            }
            mainHandler.post { onComplete(deleted) }
        }
    }

    override fun getAvailableVoices(): List<VoiceOption> = listOf(
        VoiceOption(
            id = TtsProviderMode.PIPER_OFFLINE.voiceId,
            name = TtsProviderMode.PIPER_OFFLINE.displayName,
            locale = "English (US)",
            isNetworkConnectionRequired = true,
            quality = "Medium; downloaded once, then offline"
        )
    )

    override fun setVoice(voiceId: String) = Unit

    private companion object {
        const val TAG = "PmugPiper"
    }
}
