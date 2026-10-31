package com.example

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.example.core.theme.PmugTheme
import com.example.core.theme.PmugTokens
import com.example.data.PmugDocumentEntity
import com.example.data.PmugRepository
import com.example.domain.tts.AndroidTtsEngine
import com.example.domain.tts.TtsEngine
import com.example.nudge.NudgePreferences
import com.example.nudge.NudgeWorker
import com.example.nudge.UsageSignals
import com.example.ui.debug.DebugScreen
import com.example.ui.library.LibraryScreen
import com.example.ui.onboarding.UsagePermissionDialog
import com.example.ui.reader.ReaderScreen
import kotlinx.coroutines.launch

sealed class PmugScreen {
    object Library : PmugScreen()
    data class Reader(val documentId: String) : PmugScreen()
    object Debug : PmugScreen()
}

class MainActivity : ComponentActivity() {

    private lateinit var repository: PmugRepository
    private lateinit var ttsEngine: TtsEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        repository = PmugRepository(this)
        val app = application as PmugApplication
        ttsEngine = app.ttsEngine

        // Schedule periodic nudge checks every 30 mins
        NudgeWorker.schedule(this)

        setContent {
            PmugTheme {
                PmugApp(
                    repository = repository,
                    ttsEngine = ttsEngine,
                    intent = intent,
                    activity = this
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}

@Composable
fun PmugApp(
    repository: PmugRepository,
    ttsEngine: TtsEngine,
    intent: Intent?,
    activity: ComponentActivity
) {
    val scope = rememberCoroutineScope()
    var currentScreen by remember { mutableStateOf<PmugScreen>(PmugScreen.Library) }
    var activeDocument by remember { mutableStateOf<PmugDocumentEntity?>(null) }
    var isAudioPlaying by remember { mutableStateOf(false) }

    val prefs = remember { NudgePreferences(activity) }
    var showUsageDialog by remember { mutableStateOf(false) }

    // Android 13+ Notification permission launcher
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { /* Permission handled */ }

    fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                activity,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // Handle incoming intents (from Nudge notification or "Open with" / "Share to Pmug")
    LaunchedEffect(intent) {
        val openDocId = intent?.getStringExtra("OPEN_DOCUMENT_ID")
        if (openDocId != null) {
            val doc = repository.getDocument(openDocId)
            if (doc != null) {
                activeDocument = doc
                currentScreen = PmugScreen.Reader(openDocId)
            }
        } else if (intent?.action == Intent.ACTION_VIEW || intent?.action == Intent.ACTION_SEND) {
            val uri = intent.data ?: intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            if (uri != null) {
                scope.launch {
                    val result = repository.prepareImport(uri)
                    result.onSuccess { prepared ->
                        val saved = repository.commitPreparedDocument(prepared)
                        activeDocument = saved
                        currentScreen = PmugScreen.Reader(saved.id)
                        requestNotificationPermissionIfNeeded()
                        if (!UsageSignals.hasUsagePermission(activity)) {
                            showUsageDialog = true
                        }
                    }.onFailure { failure ->
                        Toast.makeText(activity, failure.message ?: "Could not import this document", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PmugTokens.Ground)
    ) {
        AnimatedContent(
            targetState = currentScreen,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "screen_routing"
        ) { screen ->
            when (screen) {
                is PmugScreen.Library -> {
                    LibraryScreen(
                        repository = repository,
                        onOpenDocument = { docId ->
                            scope.launch {
                                val doc = repository.getDocument(docId)
                                if (doc != null) {
                                    activeDocument = doc
                                    currentScreen = PmugScreen.Reader(docId)
                                    prefs.lastPmugActivityTime = System.currentTimeMillis()
                                    requestNotificationPermissionIfNeeded()
                                }
                            }
                        },
                        onOpenDebug = {
                            currentScreen = PmugScreen.Debug
                        },
                        isAudioPlaying = isAudioPlaying,
                        currentPlayingDocTitle = activeDocument?.title,
                        onToggleMiniPlayPause = {
                            isAudioPlaying = !isAudioPlaying
                            if (!isAudioPlaying) ttsEngine.stop()
                        },
                        onExpandPlayingDoc = {
                            activeDocument?.let {
                                currentScreen = PmugScreen.Reader(it.id)
                            }
                        }
                    )
                }
                is PmugScreen.Reader -> {
                    activeDocument?.let { doc ->
                        ReaderScreen(
                            document = doc,
                            repository = repository,
                            ttsEngine = ttsEngine,
                            playbackSession = (activity.application as PmugApplication).playbackSession,
                            isPlaying = isAudioPlaying,
                            onSetPlaying = { playing ->
                                isAudioPlaying = playing
                                if (playing) prefs.lastPmugActivityTime = System.currentTimeMillis()
                            },
                            onBack = {
                                currentScreen = PmugScreen.Library
                            }
                        )
                    }
                }
                is PmugScreen.Debug -> {
                    DebugScreen(
                        onBack = { currentScreen = PmugScreen.Library }
                    )
                }
            }
        }

        if (showUsageDialog) {
            UsagePermissionDialog(
                onDismiss = { showUsageDialog = false },
                onPermissionGranted = { showUsageDialog = false }
            )
        }
    }
}
