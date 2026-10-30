package com.example.domain.tts

import android.content.Context
import android.app.Activity
import android.app.Dialog
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.WebResourceResponse
import android.webkit.SslErrorHandler
import android.net.http.SslError
import android.view.View
import android.view.ViewGroup
import org.json.JSONObject

class PuterTtsEngine(private val context: Context) : TtsEngine {

    companion object {
        private const val TAG = "PmugPuter"
    }

    private var webView: WebView? = null
    private var listener: TtsEngineListener? = null
    override var isInitialized: Boolean = false
        private set

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingSpeech: Triple<Int, String, Float>? = null
    private var selectedMode: TtsProviderMode = TtsProviderMode.GEMINI_PUTER
    @Volatile private var signedIn: Boolean = false

    fun setMode(mode: TtsProviderMode) {
        if (mode.isCloud) selectedMode = mode
    }

    init {
        mainHandler.post {
            initWebView()
        }
    }

    private fun initWebView() {
        try {
            // Keep the activity context so WebView can attach to the activity's window and
            // play the returned HTML audio through Android's media stack.
            val wv = WebView(context)
            wv.apply {
                alpha = 0f
                isClickable = false
                isFocusable = false
                isFocusableInTouchMode = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            (context as? Activity)?.addContentView(wv, ViewGroup.LayoutParams(1, 1))
            wv.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                setSupportMultipleWindows(true)
                javaScriptCanOpenWindowsAutomatically = true
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                userAgentString = "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            }

            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

            wv.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                    Log.d(TAG, "WebView console ${consoleMessage?.messageLevel()}: ${consoleMessage?.message()} -- ${consoleMessage?.sourceId()}:${consoleMessage?.lineNumber()}")
                    return true
                }

                override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean {
                    val activity = context as? Activity ?: return false
                    val popup = WebView(activity)
                    popup.settings.javaScriptEnabled = true
                    popup.settings.domStorageEnabled = true
                    popup.settings.javaScriptCanOpenWindowsAutomatically = true
                    popup.settings.setSupportMultipleWindows(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(popup, true)
                    val dialog = Dialog(activity)
                    dialog.setContentView(popup)
                    dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    popup.webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(message: ConsoleMessage?): Boolean {
                            Log.d(TAG, "Auth popup console ${message?.messageLevel()}: ${message?.message()} -- ${message?.sourceId()}:${message?.lineNumber()}")
                            return true
                        }
                        override fun onCloseWindow(window: WebView?) {
                            dialog.dismiss()
                            window?.destroy()
                            refreshSignedInStatus()
                        }
                    }
                    popup.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) { refreshSignedInStatus() }
                        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                            Log.e(TAG, "Auth popup WebView error url=${request?.url} code=${error?.errorCode} description=${error?.description}")
                        }
                        override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, response: WebResourceResponse?) {
                            Log.e(TAG, "Auth popup HTTP error url=${request?.url} status=${response?.statusCode} reason=${response?.reasonPhrase}")
                        }
                        override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                            Log.e(TAG, "Auth popup SSL error url=${error?.url} primary=${error?.primaryError}")
                            handler?.cancel()
                        }
                    }
                    (resultMsg?.obj as? WebView.WebViewTransport)?.webView = popup
                    resultMsg?.sendToTarget()
                    dialog.setOnDismissListener { popup.destroy(); refreshSignedInStatus() }
                    dialog.show()
                    return true
                }
            }

            wv.addJavascriptInterface(PuterBridge(), "AndroidBridge")
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    Log.d(TAG, "Puter.js bridge loaded successfully")
                    refreshSignedInStatus()
                }
                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    Log.e(TAG, "WebView error url=${request?.url} code=${error?.errorCode} description=${error?.description}")
                }
                override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, response: WebResourceResponse?) {
                    Log.e(TAG, "HTTP error url=${request?.url} status=${response?.statusCode} reason=${response?.reasonPhrase}")
                }
                override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                    Log.e(TAG, "SSL error url=${error?.url} primary=${error?.primaryError}")
                    handler?.cancel()
                }
            }

            val html = """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset="utf-8">
                    <script src="https://js.puter.com/v2/"></script>
                </head>
                <body>
                    <script>
                        var currentAudio = null;
                        var speechGeneration = 0;

                        function checkPuterReady() {
                            if (typeof puter !== 'undefined' && puter.ai) {
                                if (window.AndroidBridge) {
                                    window.AndroidBridge.onReady();
                                }
                            } else {
                                setTimeout(checkPuterReady, 150);
                            }
                        }

                        window.onload = function() {
                            checkPuterReady();
                        };

                        async function speakText(sentenceId, text, rate, provider, model, voice, instructions) {
                            const generation = ++speechGeneration;
                            try {
                                if (currentAudio) {
                                    try { currentAudio.pause(); } catch(e){}
                                    currentAudio = null;
                                }
                                if (typeof puter === 'undefined' || !puter.ai) {
                                    throw new Error("Puter AI script not ready");
                                }
                                const options = { provider: provider, model: model, voice: voice };
                                if (instructions) options.instructions = instructions;
                                let audio;
                                try { audio = await puter.ai.txt2speech(text, options); }
                                catch (rejection) {
                                    const exact = rejection && rejection.stack ? rejection.stack : String(rejection);
                                    console.error("txt2speech promise rejection: " + exact);
                                    if (generation === speechGeneration && window.AndroidBridge) window.AndroidBridge.onError(sentenceId, "txt2speech promise rejection: " + exact);
                                    return;
                                }
                                if (generation !== speechGeneration) return;
                                if (!audio) {
                                    throw new Error("Speech synthesis returned empty audio");
                                }
                                currentAudio = audio;
                                if (rate && rate > 0) {
                                    audio.playbackRate = rate;
                                }
                                audio.onplay = function() {
                                    if (generation === speechGeneration && window.AndroidBridge) window.AndroidBridge.onStarted(sentenceId);
                                };
                                audio.onended = function() {
                                    if (generation === speechGeneration && window.AndroidBridge) window.AndroidBridge.onCompleted(sentenceId);
                                };
                                audio.onerror = function(e) {
                                    if (generation === speechGeneration && window.AndroidBridge) window.AndroidBridge.onError(sentenceId, "Audio playback error");
                                };
                                var playPromise = audio.play();
                                if (playPromise !== undefined) {
                                    playPromise.catch(function(error) {
                                        if (generation === speechGeneration && window.AndroidBridge) window.AndroidBridge.onError(sentenceId, "Play failed: " + error.message);
                                    });
                                }
                            } catch (err) {
                                if (generation === speechGeneration && window.AndroidBridge) {
                                    window.AndroidBridge.onError(sentenceId, (err && err.message) ? err.message : String(err));
                                }
                            }
                        }

                        function stopAudio() {
                            speechGeneration++;
                            if (currentAudio) {
                                try {
                                    currentAudio.pause();
                                    currentAudio.currentTime = 0;
                                } catch (e){}
                                currentAudio = null;
                            }
                        }

                        async function signInToPuter() {
                            try { await puter.auth.signIn(); await reportAuthStatus(); }
                            catch (e) { console.error("Puter sign-in error: " + (e && e.stack ? e.stack : String(e))); }
                        }
                        async function reportAuthStatus() {
                            try {
                                const value = await puter.auth.isSignedIn();
                                if (window.AndroidBridge) window.AndroidBridge.onAuthStatus(!!value);
                            } catch (e) { console.error("Puter auth status error: " + (e && e.stack ? e.stack : String(e))); }
                        }
                    </script>
                </body>
                </html>
            """.trimIndent()

            wv.loadDataWithBaseURL("https://puter.com", html, "text/html", "UTF-8", null)
            webView = wv
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize WebView: ${e.message}")
        }
    }

    private inner class PuterBridge {
        @JavascriptInterface
        fun onAuthStatus(value: Boolean) {
            signedIn = value
            if (value) CookieManager.getInstance().flush()
        }
        @JavascriptInterface
        fun onReady() {
            mainHandler.post {
                isInitialized = true
                listener?.onEngineReady()
                pendingSpeech?.let { (id, text, rate) ->
                    speakSentence(id, text, rate, 1.0f)
                    pendingSpeech = null
                }
            }
        }

        @JavascriptInterface
        fun onStarted(sentenceId: Int) {
            mainHandler.post {
                listener?.onSentenceStarted(sentenceId)
            }
        }

        @JavascriptInterface
        fun onCompleted(sentenceId: Int) {
            mainHandler.post {
                listener?.onSentenceCompleted(sentenceId)
            }
        }

        @JavascriptInterface
        fun onError(sentenceId: Int, message: String) {
            mainHandler.post {
                Log.e(TAG, "Puter TTS error for sentence $sentenceId: $message")
                listener?.onError(sentenceId, message)
            }
        }
    }

    override fun setListener(listener: TtsEngineListener) {
        this.listener = listener
        if (isInitialized) {
            listener.onEngineReady()
        }
    }

    fun signIn() { mainHandler.post { webView?.evaluateJavascript("signInToPuter();", null) } }
    fun isSignedIn(): Boolean = signedIn
    fun refreshSignedInStatus() { mainHandler.post { webView?.evaluateJavascript("reportAuthStatus();", null) } }

    override fun speakSentence(sentenceId: Int, text: String, rate: Float, pitch: Float) {
        if (!isInitialized || webView == null) {
            pendingSpeech = Triple(sentenceId, text, rate)
            return
        }

        mainHandler.post {
            val jsonText = JSONObject.quote(text)
            val provider = JSONObject.quote(selectedMode.provider.orEmpty())
            val model = JSONObject.quote(selectedMode.model.orEmpty())
            val voice = JSONObject.quote(selectedMode.voice.orEmpty())
            val instructions = JSONObject.quote(selectedMode.instructions.orEmpty())
            webView?.evaluateJavascript(
                "speakText($sentenceId, $jsonText, $rate, $provider, $model, $voice, $instructions);",
                null
            )
        }
    }

    override fun stop() {
        pendingSpeech = null
        mainHandler.post {
            webView?.evaluateJavascript("stopAudio();", null)
        }
    }

    override fun shutdown() {
        mainHandler.post {
            webView?.let { wv ->
                wv.evaluateJavascript("stopAudio();", null)
                (wv.parent as? ViewGroup)?.removeView(wv)
                wv.destroy()
            }
            webView = null
            isInitialized = false
        }
    }

    override fun getAvailableVoices(): List<VoiceOption> {
        return listOf(TtsProviderMode.GEMINI_PUTER, TtsProviderMode.OPENAI_MINI_TTS, TtsProviderMode.OPENAI_TTS_HD)
            .map { VoiceOption(it.voiceId, it.displayName, "English (US)", true, it.description) }
    }

    override fun setVoice(voiceId: String) {
        TtsProviderMode.fromVoiceId(voiceId)?.let(::setMode)
    }
}
