package com.example.domain.tts

import android.content.Context
import android.util.Log
import com.google.android.play.core.splitinstall.SplitInstallManagerFactory
import com.google.android.play.core.splitinstall.SplitInstallRequest
import com.google.android.play.core.splitinstall.model.SplitInstallSessionStatus

/** SplitInstall and reflective boundary: the base app never links Piper feature implementation classes. */
class PiperFeatureManager(private val context: Context) : TtsEngine {
    private val manager = SplitInstallManagerFactory.create(context.applicationContext)
    @Volatile private var provider: PiperProvider? = null
    @Volatile private var listener: TtsEngineListener? = null
    @Volatile private var activeSessionId: Int? = null
    @Volatile private var providerLoadFailure: Throwable? = null
    @Volatile private var lastLoggedModuleState: Boolean? = null

    val isModuleInstalled: Boolean
        get() = try {
            val installed = "piper" in manager.installedModules
            if (lastLoggedModuleState != installed) {
                lastLoggedModuleState = installed
                Log.i(TAG, "Module state changed: installed=$installed modules=${manager.installedModules} abi=${android.os.Build.SUPPORTED_ABIS.contentToString()} splits=${context.applicationInfo.splitNames?.contentToString()}")
            }
            installed
        } catch (error: Throwable) {
            Log.e(TAG, "Module check failed", error)
            throw error
        }

    fun isVoiceInstalled(voiceId: String = PiperVoiceCatalog.defaultVoiceId): Boolean = loadProvider()?.isInstalled(voiceId) == true
    val currentVoiceId: String get() = PiperVoiceCatalog.defaultVoiceId

    override val isInitialized: Boolean get() = provider?.isInitialized == true
    override fun setListener(listener: TtsEngineListener) {
        this.listener = listener
        provider?.setListener(listener)
    }

    fun prepare(voiceId: String = PiperVoiceCatalog.defaultVoiceId, progress: (Int) -> Unit, complete: (Result<Unit>) -> Unit) {
        fun installVoice() {
            val p = loadProvider()
            if (p == null) {
                val reason = providerLoadFailure?.let { "${it.javaClass.name}: ${it.message}" } ?: "provider class load returned null"
                complete(Result.failure(IllegalStateException("Piper feature is unavailable: $reason", providerLoadFailure)))
                return
            }
            p.setListener(listener ?: object : TtsEngineListener {
                override fun onSentenceStarted(sentenceId: Int) = Unit
                override fun onSentenceCompleted(sentenceId: Int) = Unit
                override fun onError(sentenceId: Int, message: String) = Unit
                override fun onEngineReady() = Unit
            })
            if (p.isInstalled(voiceId)) { progress(100); complete(Result.success(Unit)) }
            else p.install(voiceId, { progress(50 + it / 2) }) { result ->
                result.onSuccess { progress(100) }
                complete(result.recoverCatching { error ->
                    val detail = error.message.orEmpty()
                    when {
                        detail.contains("ENOSPC", ignoreCase = true) || detail.contains("no space", ignoreCase = true) ->
                            throw IllegalStateException("Not enough storage space to install the Piper voice.", error)
                        error is java.io.IOException ->
                            throw IllegalStateException("Piper model download failed. Check your network and retry.", error)
                        else -> throw error
                    }
                })
            }
        }
        if (isModuleInstalled || com.example.BuildConfig.PIPER_BUNDLED_FOR_DEBUG) {
            progress(50)
            installVoice()
            return
        }
        val request = SplitInstallRequest.newBuilder().addModule("piper").build()
        val registration = object : com.google.android.play.core.splitinstall.SplitInstallStateUpdatedListener {
            override fun onStateUpdate(state: com.google.android.play.core.splitinstall.SplitInstallSessionState) {
                Log.i(TAG, "SplitInstall state=${state.status()} session=${state.sessionId()} downloaded=${state.bytesDownloaded()} total=${state.totalBytesToDownload()} errorCode=${state.errorCode()}")
                when (state.status()) {
                    SplitInstallSessionStatus.DOWNLOADING -> {
                        val pct = if (state.totalBytesToDownload() > 0) state.bytesDownloaded() * 50 / state.totalBytesToDownload() else 5
                        progress(pct.toInt().coerceIn(0, 49))
                    }
                    SplitInstallSessionStatus.INSTALLED -> {
                        activeSessionId = null
                        manager.unregisterListener(this)
                        progress(50)
                        installVoice()
                    }
                    SplitInstallSessionStatus.FAILED -> {
                        activeSessionId = null
                        manager.unregisterListener(this)
                        val message = when (state.errorCode()) {
                            -6 -> "No network connection. Connect and retry the Piper download."
                            -10 -> "Not enough storage space to install Piper."
                            else -> "Piper feature download failed (${state.errorCode()})"
                        }
                        complete(Result.failure(IllegalStateException(message)))
                    }
                    SplitInstallSessionStatus.CANCELED -> {
                        activeSessionId = null
                        manager.unregisterListener(this)
                        complete(Result.failure(IllegalStateException("Piper installation was cancelled")))
                    }
                    else -> Unit
                }
            }
        }
        manager.registerListener(registration)
        manager.startInstall(request).addOnSuccessListener { activeSessionId = it }
            .addOnFailureListener { error ->
            manager.unregisterListener(registration)
            Log.e(TAG, "SplitInstall start failed", error)
            val message = when (error.javaClass.simpleName) {
                "SplitInstallException" -> when (runCatching { error.javaClass.getMethod("getErrorCode").invoke(error) as Int }.getOrNull()) {
                    -6 -> "No network connection. Connect and retry the Piper download."
                    -10 -> "Not enough storage space to install Piper."
                    else -> "Could not download Piper feature: ${error.message}"
                }
                else -> error.message ?: "Could not start Piper installation"
            }
            complete(Result.failure(IllegalStateException(message, error)))
            }
    }

    fun cancelInstall() {
        activeSessionId?.let { manager.cancelInstall(it) }
    }

    fun runSelfTest(report: (String, Boolean, String) -> Unit) {
        val installed = try {
            isModuleInstalled
        } catch (error: Throwable) {
            report("Feature module check", false, "${error.javaClass.name}: ${error.message}\n${error.stackTraceToString()}")
            return
        }
        val fusedDebug = com.example.BuildConfig.PIPER_BUNDLED_FOR_DEBUG
        val modules = runCatching { manager.installedModules.toString() }.getOrElse { "<read failed: ${it.javaClass.simpleName}: ${it.message}>" }
        report("Feature module", installed || fusedDebug, "installed=$installed PIPER_BUNDLED_FOR_DEBUG=$fusedDebug modules=$modules; debuggable=${(context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0}; releaseR8Configured=false (app/build.gradle.kts)")
        if (!installed && !fusedDebug) return
        val p = try {
            loadProviderOrThrow()
        } catch (error: Throwable) {
            val message = "${error.javaClass.name}: ${error.message}\n${error.stackTraceToString()}"
            report("Provider class load", false, message)
            return
        }
        report("Provider class load", true, p.javaClass.name)
        p.runSelfTest(report)
    }

    fun deleteVoice(voiceId: String, onComplete: (Boolean) -> Unit) {
        val p = loadProvider()
        if (p == null) { onComplete(false); return }
        p.deleteVoice(voiceId, onComplete)
    }

    fun remove(onComplete: (Boolean) -> Unit) {
        val p = loadProvider()
        if (p == null) { onComplete(false); return }
        p.remove { deleted ->
            if (!deleted) { onComplete(false); return@remove }
            provider = null
            manager.deferredUninstall(listOf("piper"))
                .addOnSuccessListener { onComplete(true) }
                .addOnFailureListener { onComplete(false) }
        }
    }

    private fun loadProvider(): PiperProvider? {
        provider?.let { return it }
        if (!isModuleInstalled && !com.example.BuildConfig.PIPER_BUNDLED_FOR_DEBUG) {
            providerLoadFailure = IllegalStateException("Feature module 'piper' is not installed; installedModules=${manager.installedModules}")
            Log.e(TAG, "Provider unavailable: ${providerLoadFailure?.message}", providerLoadFailure)
            return null
        }
        return try {
            Log.i(TAG, "Provider reflection started class=com.example.piper.PiperProviderImpl moduleInstalled=${isModuleInstalled || com.example.BuildConfig.PIPER_BUNDLED_FOR_DEBUG}")
            val clazz = context.classLoader.loadClass("com.example.piper.PiperProviderImpl")
            (clazz.getConstructor(Context::class.java).newInstance(context.applicationContext) as PiperProvider)
                .also { created ->
                    listener?.let(created::setListener)
                    provider = created
                    providerLoadFailure = null
                    Log.i(TAG, "Provider reflection passed class=${created.javaClass.name} loader=${created.javaClass.classLoader}")
                }
        } catch (error: Throwable) {
            providerLoadFailure = error
            Log.e(TAG, "Provider reflection failed. class=com.example.piper.PiperProviderImpl abi=${android.os.Build.SUPPORTED_ABIS.contentToString()}", error)
            null
        }
    }

    private fun loadProviderOrThrow(): PiperProvider = loadProvider()
        ?: throw IllegalStateException(
            "Could not load com.example.piper.PiperProviderImpl: ${providerLoadFailure?.message ?: "feature class is absent from the installed splits"}",
            providerLoadFailure
        )

    override fun speakSentence(sentenceId: Int, text: String, rate: Float, pitch: Float) {
        val p = loadProvider()
        if (p == null) listener?.onError(sentenceId, "Piper feature unavailable: ${providerLoadFailure?.javaClass?.simpleName}: ${providerLoadFailure?.message ?: "provider load failed; see PmugPiper log"}")
        else p.synthesize(sentenceId, text, rate, pitch)
    }

    fun synthesizeAudio(sentence: String, rate: Float): SynthesizedAudio {
        val p = loadProvider()
        return p?.synthesizeAudio(sentence, rate) ?: SynthesizedAudio(FloatArray(0), 22050)
    }

    fun warmUp() {
        loadProvider()?.warmUp()
    }

    override fun setVoice(voiceId: String) {
        loadProvider()?.setVoice(voiceId)
    }

    override fun stop() { provider?.stop() }
    override fun shutdown() { provider?.shutdown(); provider = null }
    override fun getAvailableVoices() = loadProvider()?.getAvailableVoices() ?: PiperVoiceCatalog.voices.map { v ->
        VoiceOption(v.id, v.displayName, "English (${v.accent})", true, "Not downloaded (~${v.expectedDownloadSize / (1024*1024)}MB)")
    }

    private companion object { const val TAG = "PmugPiper" }
}
