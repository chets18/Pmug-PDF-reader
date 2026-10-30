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

/** Optional local neural TTS. The model is installed on first use by [PiperModelInstaller]. */
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
        stopPlayer()
        worker.execute {
            try {
                val engine = getOrCreateEngine()
                if (generation != requestGeneration.get() || isClosed) return@execute

                val audio = engine.generate(text, sid = 0, speed = rate.coerceIn(0.5f, 2.0f))
                if (generation != requestGeneration.get() || isClosed) return@execute

                mainHandler.post { playGeneratedAudio(sentenceId, generation, rate, audio.samples, audio.sampleRate) }
                Log.i(TAG, "First/active synthesize call sentenceId=$sentenceId textLength=${text.length} sampleRate=${audio.sampleRate} sampleCount=${audio.samples.size}")
            } catch (error: Throwable) {
                Log.e(TAG, "Piper synthesize failed sentenceId=$sentenceId textLength=${text.length} abi=${Build.SUPPORTED_ABIS.contentToString()}", error)
                mainHandler.post {
                    if (generation == requestGeneration.get() && !isClosed) {
                        listener?.onError(sentenceId, error.message ?: "Piper speech failed")
                    }
                }
            }
        }
    }

    fun install(progress: (Int) -> Unit, complete: (Result<Unit>) -> Unit) {
        Log.i(TAG, "Piper voice install requested root=${File(appContext.filesDir, "tts/piper").absolutePath}")
        worker.execute {
            val result = try {
                modelInstaller.ensureInstalled { progress(it) }
                getOrCreateEngine()
                Result.success(Unit)
            } catch (error: Throwable) {
                Log.e(TAG, "Piper voice install or engine initialization failed", error)
                Result.failure(error)
            }
            mainHandler.post { complete(result) }
        }
    }

    fun isVoiceInstalled(): Boolean = modelInstaller.isInstalled()

    private fun getOrCreateEngine(): OfflineTts {
        tts?.let { return it }
        val model = modelInstaller.ensureInstalled()
        modelInstaller.logInventory("before engine creation")
        Log.i(TAG, "Config paths model=${model.modelFile.absolutePath} exists=${model.modelFile.isFile} bytes=${model.modelFile.length()} tokens=${model.tokensFile.absolutePath} exists=${model.tokensFile.isFile} bytes=${model.tokensFile.length()} espeak=${model.phonemeDataDir.absolutePath} exists=${model.phonemeDataDir.isDirectory}")
        try {
            System.loadLibrary("sherpa-onnx-jni")
            Log.i(TAG, "Native load success library=sherpa-onnx-jni abi=${Build.SUPPORTED_ABIS.contentToString()} nativeLibraryDir=${appContext.applicationInfo.nativeLibraryDir}")
        } catch (error: UnsatisfiedLinkError) {
            Log.e(TAG, "Native load failed library=sherpa-onnx-jni abi=${Build.SUPPORTED_ABIS.contentToString()} nativeLibraryDir=${appContext.applicationInfo.nativeLibraryDir}", error)
            throw error
        } catch (error: Throwable) {
            Log.e(TAG, "Native load threw library=sherpa-onnx-jni abi=${Build.SUPPORTED_ABIS.contentToString()}", error)
            throw error
        }
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
        return try {
            OfflineTts(config = config).also {
            tts = it
            Log.i(TAG, "OfflineTts engine creation passed model=${model.modelFile.absolutePath}")
            mainHandler.post { listener?.onEngineReady() }
            }
        } catch (error: Throwable) {
            Log.e(TAG, "OfflineTts engine creation failed model=${model.modelFile.absolutePath} tokens=${model.tokensFile.absolutePath} espeak=${model.phonemeDataDir.absolutePath} abi=${Build.SUPPORTED_ABIS.contentToString()}", error)
            throw error
        }
    }

    /** Runs the same data/native/synthesis/playback path as the voice, stopping on its first failure. */
    fun runSelfTest(report: (String, Boolean, String) -> Unit) {
        val model = try {
            Log.i(TAG, "Self-test: model download/install stage started")
            modelInstaller.ensureInstalled()
        } catch (error: Throwable) {
            reportFailure("Model download and extraction", error, report)
            return
        }
        report("Model download and extraction", true, "filesDir=${appContext.filesDir.absolutePath} writable=${appContext.filesDir.canWrite()}; model=${model.modelFile.length()} bytes (minimum accepted 1000000); tokens=${model.tokensFile.length()} bytes; installed marker=${File(model.modelFile.parentFile, ".installed").isFile}")

        modelInstaller.logInventory("self-test file inventory")
        val missing = listOf(model.modelFile, model.tokensFile, model.phonemeDataDir, File(model.modelFile.parentFile, ".installed"))
            .filterNot { if (it.isDirectory) it.isDirectory else it.isFile }
        if (missing.isNotEmpty()) {
            report("Model files and paths", false, "Missing: ${missing.joinToString { it.absolutePath }}")
            return
        }
        report("Model files and paths", true, "onnx=${model.modelFile.absolutePath} (${model.modelFile.length()} bytes); tokens=${model.tokensFile.absolutePath} (${model.tokensFile.length()} bytes); espeak-ng-data=${model.phonemeDataDir.absolutePath}; marker=${File(model.modelFile.parentFile, ".installed").absolutePath}")

        try {
            System.loadLibrary("sherpa-onnx-jni")
            Log.i(TAG, "Self-test native load passed ABI=${Build.SUPPORTED_ABIS.contentToString()} nativeLibraryDir=${appContext.applicationInfo.nativeLibraryDir}")
            report("Native library load", true, "sherpa-onnx-jni loaded; supportedAbis=${Build.SUPPORTED_ABIS.contentToString()}; nativeLibraryDir=${appContext.applicationInfo.nativeLibraryDir}")
        } catch (error: Throwable) {
            Log.e(TAG, "Self-test native load failed ABI=${Build.SUPPORTED_ABIS.contentToString()} nativeLibraryDir=${appContext.applicationInfo.nativeLibraryDir}", error)
            reportFailure("Native library load", error, report)
            return
        }

        val engine = try {
            getOrCreateEngine()
        } catch (error: Throwable) {
            reportFailure("OfflineTts engine creation", error, report)
            return
        }
        report("OfflineTts engine creation", true, "engine initialized; sampleRate=${engine.sampleRate()}; model=${model.modelFile.absolutePath}")

        val generated = try {
            val text = "Piper diagnostic test."
            val audio = engine.generate(text, sid = 0, speed = 1.0f)
            Log.i(TAG, "Self-test first synthesize passed textLength=${text.length} sampleRate=${audio.sampleRate} sampleCount=${audio.samples.size}")
            audio
        } catch (error: Throwable) {
            reportFailure("First synthesize", error, report)
            return
        }
        if (generated.samples.isEmpty() || generated.sampleRate <= 0) {
            report("First synthesize", false, "Generated invalid audio: sampleRate=${generated.sampleRate}, sampleCount=${generated.samples.size}")
            return
        }
        report("First synthesize", true, "textLength=22; sampleRate=${generated.sampleRate}; sampleCount=${generated.samples.size}")

        val playbackFailure = playSelfTestAudio(generated.samples, generated.sampleRate)
        report("Audio playback start", playbackFailure == null, playbackFailure ?: "AudioTrack playback completed for ${generated.samples.size} samples at ${generated.sampleRate} Hz")
    }

    private fun playSelfTestAudio(samples: FloatArray, sampleRate: Int): String? {
        try {
            val completed = java.util.concurrent.CountDownLatch(1)
            val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
            mainHandler.post {
                try {
                    playSamples(-1, requestGeneration.get(), 1f, samples, sampleRate) { completed.countDown() }
                } catch (error: Throwable) {
                    failure.set(error)
                    completed.countDown()
                }
            }
            if (!completed.await((samples.size * 1000L / sampleRate + 5000).coerceAtLeast(5000), java.util.concurrent.TimeUnit.MILLISECONDS)) return "Timed out waiting for AudioTrack marker"
            failure.get()?.let { throw it }
            return null
        } catch (error: Throwable) {
            Log.e(TAG, "Self-test playback failed", error)
            return "${error.javaClass.name}: ${error.message}\n${error.stackTraceToString()}"
        }
    }

    private fun reportFailure(stage: String, error: Throwable, report: (String, Boolean, String) -> Unit) {
        Log.e(TAG, "Self-test failed at stage=$stage", error)
        report(stage, false, "${error.javaClass.name}: ${error.message}\n${error.stackTraceToString()}")
    }

    private fun playGeneratedAudio(sentenceId: Int, generation: Long, rate: Float, samples: FloatArray, sampleRate: Int) {
        if (generation != requestGeneration.get() || isClosed) {
            return
        }
        stopPlayer()
        try {
            playSamples(sentenceId, generation, rate, samples, sampleRate) {
                if (generation == requestGeneration.get()) listener?.onSentenceCompleted(sentenceId)
            }
        } catch (error: Throwable) {
            Log.e(TAG, "AudioTrack playback failed sentenceId=$sentenceId", error)
            listener?.onError(sentenceId, error.message ?: "Could not play Piper audio")
        }
    }

    private fun playSamples(sentenceId: Int, generation: Long, rate: Float, samples: FloatArray, sampleRate: Int, onComplete: () -> Unit) {
        require(sampleRate > 0 && samples.isNotEmpty()) { "Piper returned empty audio or invalid sample rate" }
        val peak = samples.maxOf { kotlin.math.abs(it) }
        Log.i(TAG, "Audio samples sentenceId=$sentenceId peak=$peak sampleCount=${samples.size} sampleRate=$sampleRate")
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val minBufferBytes = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        )
        check(minBufferBytes > 0) { "AudioTrack.getMinBufferSize failed result=$minBufferBytes sampleRate=$sampleRate" }
        val bufferBytes = minBufferBytes * 2
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufferBytes)
            .build()
        audioTrack = track
        Log.i(TAG, "AudioTrack created sentenceId=$sentenceId state=${track.state} sampleRate=$sampleRate channels=mono encoding=PCM_FLOAT mode=MODE_STREAM bufferBytes=$bufferBytes")
        try {
            check(track.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack initialization failed state=${track.state}" }
            track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(player: AudioTrack) {
                    if (audioTrack === player && generation == requestGeneration.get()) {
                        Log.i(TAG, "AudioTrack playback completed sentenceId=$sentenceId frames=${samples.size}")
                        audioTrack = null
                        player.release()
                        onComplete()
                    }
                }
                override fun onPeriodicNotification(player: AudioTrack) = Unit
            }, mainHandler)
            check(track.setNotificationMarkerPosition(samples.size) == AudioTrack.SUCCESS) {
                "AudioTrack rejected completion marker at ${samples.size} frames"
            }
            if (rate != 1f) track.playbackRate = (sampleRate * rate.coerceIn(0.5f, 2f)).toInt()
            val samplesPerChunk = (bufferBytes / Float.SIZE_BYTES).coerceAtLeast(1)
            worker.execute {
                try {
                    var offset = 0
                    var started = false
                    while (offset < samples.size) {
                        if (generation != requestGeneration.get() || isClosed || audioTrack !== track) return@execute
                        val requested = minOf(samplesPerChunk, samples.size - offset)
                        val written = track.write(samples, offset, requested, AudioTrack.WRITE_BLOCKING)
                        Log.i(TAG, "AudioTrack write sentenceId=$sentenceId offset=$offset requested=$requested result=$written")
                        if (written <= 0) throw IllegalStateException("AudioTrack write failed result=$written offset=$offset requested=$requested")
                        offset += written
                        if (!started) {
                            started = true
                            track.play()
                            Log.i(TAG, "AudioTrack play() called sentenceId=$sentenceId generation=$generation")
                            if (sentenceId >= 0) mainHandler.post {
                                if (generation == requestGeneration.get()) listener?.onSentenceStarted(sentenceId)
                            }
                        }
                    }
                } catch (error: Throwable) {
                    Log.e(TAG, "AudioTrack streaming write failed sentenceId=$sentenceId", error)
                    mainHandler.post {
                        if (audioTrack === track) {
                            audioTrack = null
                            try { track.stop() } catch (stopError: Throwable) { Log.e(TAG, "Error stopping failed AudioTrack", stopError) }
                            track.release()
                        }
                        if (generation == requestGeneration.get() && !isClosed) {
                            if (sentenceId >= 0) listener?.onError(sentenceId, error.message ?: "Could not write Piper audio")
                        }
                    }
                }
            }
        } catch (error: Throwable) {
            Log.e(TAG, "AudioTrack setup failed sentenceId=$sentenceId", error)
            if (audioTrack === track) audioTrack = null
            track.release()
            throw error
        }
    }

    private fun stopPlayer() {
        val stopTrack = {
            audioTrack?.let { player ->
                audioTrack = null
                try {
                    if (player.playState == AudioTrack.PLAYSTATE_PLAYING) player.stop()
                } catch (error: Throwable) {
                    Log.e(TAG, "Error stopping AudioTrack", error)
                } finally {
                    player.release()
                }
            }
        }
        if (Looper.myLooper() == mainHandler.looper) stopTrack() else mainHandler.post { stopTrack() }
    }

    override fun stop() {
        requestGeneration.incrementAndGet()
        stopPlayer()
    }

    override fun shutdown() {
        if (isClosed) return
        isClosed = true
        requestGeneration.incrementAndGet()
        stopPlayer()
        worker.execute {
            try {
                tts?.release()
            } catch (error: Throwable) {
                Log.w(TAG, "Error releasing Piper engine", error)
            } finally {
                tts = null
            }
        }
        worker.shutdown()
    }

    /** Releases native model memory and removes the downloaded files without uninstalling Pmug. */
    fun deleteDownloadedVoice(onComplete: (Boolean) -> Unit = {}) {
        if (isClosed) {
            onComplete(false)
            return
        }
        stop()
        worker.execute {
            val deleted = try {
                tts?.release()
                tts = null
                modelInstaller.deleteDownloadedModel()
            } catch (error: Throwable) {
                Log.w(TAG, "Could not delete the downloaded Piper voice", error)
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
