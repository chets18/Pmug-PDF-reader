package com.example.ui.reader

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.PointerEventPass
import kotlin.math.abs
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInFull
import com.example.domain.tts.HybridTtsEngine
import com.example.domain.tts.TtsProviderMode
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Slider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.haptics.PmugHaptics
import com.example.core.theme.LiterataFontFamily
import com.example.core.theme.PmugMotionSpec.pmugPressScale
import com.example.core.theme.PmugTokens
import com.example.data.PmugBookmarkEntity
import com.example.data.PmugDocumentEntity
import com.example.data.DocumentType
import com.example.data.PmugRepository
import com.example.data.ThumbnailCache
import com.example.domain.pdf.AcademicTextCleaner
import com.example.domain.pdf.SentenceSplitter
import com.example.domain.tts.AndroidTtsEngine
import com.example.domain.tts.TtsEngine
import com.example.domain.tts.TtsEngineListener
import com.example.mascot.PmugMood
import com.example.mascot.PmugMug
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collect
import java.io.File
import android.os.Handler
import android.os.Looper

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ReaderScreen(
    document: PmugDocumentEntity,
    repository: PmugRepository,
    ttsEngine: TtsEngine,
    playbackSession: com.example.domain.tts.PlaybackSession,
    isPlaying: Boolean,
    onSetPlaying: (Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(isPlaying) {
        view.keepScreenOn = isPlaying
        onDispose {
            view.keepScreenOn = false
        }
    }

    // Publish each page as soon as PDFBox finishes it so the reader can start before a large
    // document has been fully extracted. Sentence page numbers are preserved end to end.
    var extractedPages by remember(document.path) { mutableStateOf(List(document.pages) { emptyList<com.example.data.model.ParsedSentence>() }) }
    var extractionFinished by remember(document.path) { mutableStateOf(false) }
    var pendingNextSentence by remember(document.path) { mutableIntStateOf(-1) }
    var readingPage by remember(document.path) { mutableIntStateOf(1) }
    val extractionTargetPage = remember(document.path) { java.util.concurrent.atomic.AtomicInteger(1) }
    LaunchedEffect(readingPage) { extractionTargetPage.set(readingPage) }
    LaunchedEffect(document.path) {
        extractionFinished = false
        val mainHandler = Handler(Looper.getMainLooper())
        withContext(Dispatchers.IO) {
            val pageSentences = MutableList(document.pages) { emptyList<com.example.data.model.ParsedSentence>() }
            fun publish(page: Int, text: String) {
                val cleaned = if (document.type == DocumentType.PDF) AcademicTextCleaner.cleanExtractedText(listOf(text)).firstOrNull().orEmpty().ifBlank { text } else text
                val parsed = SentenceSplitter.splitIntoSentences(cleaned, startPage = page)
                synchronized(pageSentences) {
                    if (page !in 1..pageSentences.size) return
                    pageSentences[page - 1] = parsed
                    var offset = 0
                    val snapshot = pageSentences.map { pageList ->
                        val indexed = pageList.map { it.copy(id = offset + it.id) }
                        offset += indexed.size
                        indexed
                    }
                    mainHandler.post {
                        extractedPages = snapshot
                    }
                }
            }
            if (document.type == DocumentType.PDF) {
                com.example.domain.pdf.PdfTextExtractor.extractPdfFilePageByPage(
                    context, File(document.path), document.title,
                    { com.example.domain.pdf.PageExtractionPriority.order(document.pages, extractionTargetPage.get()) }
                ) { page, text, _ -> publish(page, text) }
            } else {
                val cache = File(document.path + ".text-cache")
                val pages = if (cache.exists()) cache.readText().split('\u000c') else emptyList()
                pages.forEachIndexed { index, text -> publish(index + 1, text) }
            }
        }
        extractionFinished = true
    }

    val sentences = remember(extractedPages) { extractedPages.flatten() }
    val totalSentences = sentences.size.coerceAtLeast(1)

    // Current sentence and reading state
    var currentSentenceIndex by remember { mutableIntStateOf(0) }
    var activeWordRange by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var isSentenceAudioStarted by remember { mutableStateOf(false) }
    var currentProviderMode by remember(ttsEngine) {
        mutableStateOf((ttsEngine as? HybridTtsEngine)?.currentMode ?: TtsProviderMode.PIPER_OFFLINE)
    }
    var mascotMood by remember { mutableStateOf(PmugMood.IDLE) }
    var smugNotice by remember { mutableStateOf<String?>(null) }
    var playbackSpeed by remember { mutableFloatStateOf(1.0f) }
    var fontSizeSp by remember { mutableFloatStateOf(18f) }
    var pdfZoom by remember(document.path) { mutableFloatStateOf(1.25f) }
    var pdfPan by remember(document.path) { mutableStateOf(Offset.Zero) }
    var piperProgress by remember { mutableIntStateOf(0) }
    var preparingPiper by remember { mutableStateOf(false) }
    var showVoiceSheet by remember { mutableStateOf(false) }
    var downloadingVoiceId by remember { mutableStateOf<String?>(null) }
    var voiceDownloadProgress by remember { mutableIntStateOf(0) }
    var showFullScreenPdf by remember { mutableStateOf(false) }

    val hybridEngine = ttsEngine as? HybridTtsEngine
    LaunchedEffect(hybridEngine) {
        hybridEngine?.warmUpPiper()
    }

    LaunchedEffect(sentences, extractionFinished) {
        if (sentences.isNotEmpty()) {
            val units = sentences.map { s ->
                com.example.domain.tts.PlaybackUnit(
                    sentenceId = s.id,
                    text = s.text,
                    pageIndex = s.pageNumber - 1,
                    paragraphIndex = (s.pageNumber * 1_000_000) + s.paragraphIndex,
                    spokenText = s.spokenText
                )
            }
            playbackSession.appendUnits(units, complete = extractionFinished, owner = document.id)
        }
    }

    // Polling loop for Piper mode position & highlight
    LaunchedEffect(isPlaying, currentProviderMode) {
        if (!isPlaying || currentProviderMode != TtsProviderMode.PIPER_OFFLINE) return@LaunchedEffect
        while (isPlaying && currentProviderMode == TtsProviderMode.PIPER_OFFLINE && hybridEngine?.piperVoiceInstalled == true) {
            withFrameNanos { _ ->
                val pos = playbackSession.position()
                if (pos.playing && pos.sentenceId >= 0) {
                    if (pos.sentenceId != currentSentenceIndex) {
                        currentSentenceIndex = pos.sentenceId
                        sentences.getOrNull(pos.sentenceId)?.let { s ->
                            readingPage = s.pageNumber
                        }
                    }
                    val s = sentences.getOrNull(pos.sentenceId)
                    if (s != null) {
                        val words = Regex("\\S+").findAll(s.text).toList()
                        val word = words.getOrNull(pos.wordIndex)
                        val nextRange = word?.let { it.range.first to (it.range.last + 1) }
                        if (activeWordRange != nextRange) activeWordRange = nextRange
                    }
                    mascotMood = PmugMood.READING
                } else if (activeWordRange != null) {
                    activeWordRange = null
                }
                if (playbackSession.playbackState() == "finished") {
                    onSetPlaying(false)
                    mascotMood = PmugMood.DOCUMENT_FINISHED
                    smugNotice = "Cover to cover. Obviously."
                    scope.launch {
                        repository.setFinished(document.id, true)
                    }
                } else if (playbackSession.playbackState() == "error") {
                    val failedSentence = sentences.getOrNull(currentSentenceIndex)
                    playbackSession.stop()
                    hybridEngine?.switchProvider(TtsProviderMode.ANDROID_SYSTEM)
                    currentProviderMode = TtsProviderMode.ANDROID_SYSTEM
                    failedSentence?.let { ttsEngine.speakSentence(it.id, it.spokenText, playbackSpeed, 1.0f) }
                }
            }
        }
    }

    // Current sentence and reading state
    val pageSentences = extractedPages.getOrNull(readingPage - 1).orEmpty()
    val pageSentenceStart = remember(sentences, readingPage) {
        sentences.indexOfFirst { it.pageNumber == readingPage }
    }

    LaunchedEffect(sentences.size, extractionFinished, isPlaying, pendingNextSentence) {
        val next = pendingNextSentence
        if (isPlaying && currentProviderMode == TtsProviderMode.ANDROID_SYSTEM && next >= 0 && next < sentences.size) {
            pendingNextSentence = -1
            currentSentenceIndex = next
            ttsEngine.speakSentence(next, sentences[next].spokenText, playbackSpeed, 1.0f)
        } else if (extractionFinished && pendingNextSentence >= sentences.size) {
            pendingNextSentence = -1
            onSetPlaying(false)
        }
    }

    // Mascot State & Smug feedback
    // Scroll state & user manual scroll detection
    val listState = rememberLazyListState()
    var isFollowingVoice by remember { mutableStateOf(true) }

    // Saved Passages & Page Strip sheets
    var showBookmarksSheet by remember { mutableStateOf(false) }
    var showPageStrip by remember { mutableStateOf(false) }
    var showPageDialog by remember { mutableStateOf(false) }
    var pageInput by remember { mutableStateOf("1") }
    var pendingJumpPage by remember { mutableIntStateOf(-1) }
    val bookmarksSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val bookmarks by repository.getBookmarks(document.id).collectAsState(initial = emptyList())

    // Load initial saved progress from Room
    var progressRestored by remember(document.id) { mutableStateOf(false) }
    LaunchedEffect(document.id, sentences.size) {
        if (sentences.isEmpty() || progressRestored) return@LaunchedEffect
        val progress = repository.getProgress(document.id)
        if (progress != null) {
            if (progress.sentenceIndex >= sentences.size && !extractionFinished) return@LaunchedEffect
            currentSentenceIndex = progress.sentenceIndex.coerceIn(0, totalSentences - 1)
            readingPage = progress.page.coerceIn(1, document.pages)
        }
        progressRestored = true
    }

    LaunchedEffect(pageSentenceStart, readingPage, isPlaying) {
        if (!isPlaying && pageSentenceStart >= 0 &&
            sentences.getOrNull(currentSentenceIndex)?.pageNumber != readingPage
        ) {
            currentSentenceIndex = pageSentenceStart
            isFollowingVoice = true
        }
    }

    // Auto-scroll logic: keep active sentence near 35% from the top with an eased scroll
    LaunchedEffect(currentSentenceIndex, readingPage, isFollowingVoice, pageSentenceStart) {
        if (isFollowingVoice && pageSentenceStart >= 0 && currentSentenceIndex in sentences.indices &&
            sentences[currentSentenceIndex].pageNumber == readingPage
        ) {
            // The list contains only this page. Keep the active sentence in the viewport as speech advances.
            val pageIndex = currentSentenceIndex - pageSentenceStart
            val targetOffset = (pageIndex - 2).coerceAtLeast(0) + 1
            listState.animateScrollToItem(targetOffset)
        }
    }

    // Detect manual user scrolls to detach following
    val isUserDragging by listState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(isUserDragging) {
        if (isUserDragging && isPlaying) {
            // A finger drag detaches voice-following; animated playback scrolling does not.
            isFollowingVoice = false
        }
    }

    // Debounced position saving (Section 7: save on every sentence change debounced 1s, on pause, onStop)
    LaunchedEffect(currentSentenceIndex, readingPage) {
        delay(1000)
        repository.saveReadingPosition(document.id, readingPage, currentSentenceIndex)
    }

    LaunchedEffect(listState, readingPage, pageSentenceStart, pageSentences, isPlaying) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { visibleIndex ->
                val pageIndex = (visibleIndex - 1).coerceIn(0, pageSentences.lastIndex.coerceAtLeast(0))
                val sentenceIndex = pageSentenceStart + pageIndex
                if (!isPlaying && pageSentenceStart >= 0 && pageIndex in pageSentences.indices) {
                    isFollowingVoice = false
                    currentSentenceIndex = sentenceIndex
                }
            }
    }

    // Connect TTS engine listener
    DisposableEffectWithLifecycle(
        ttsEngine = ttsEngine,
        onSentenceStarted = { sId ->
            currentSentenceIndex = sId
            activeWordRange = null
            isSentenceAudioStarted = true
            mascotMood = PmugMood.READING
        },
        onSentenceCompleted = { sId ->
            if (currentProviderMode != TtsProviderMode.PIPER_OFFLINE || hybridEngine?.piperVoiceInstalled != true) {
            activeWordRange = null
            isSentenceAudioStarted = false
            val nextIdx = sId + 1
            if (nextIdx < totalSentences) {
                if (isPlaying) {
                    currentSentenceIndex = nextIdx
                    val nextSentence = sentences[nextIdx]
                    ttsEngine.speakSentence(nextIdx, nextSentence.spokenText, playbackSpeed, 1.0f)

                    // Page completed check
                    val oldPage = sentences.getOrNull(sId)?.pageNumber ?: readingPage
                    val newPage = sentences.getOrNull(nextIdx)?.pageNumber ?: oldPage
                    readingPage = newPage
                    if (newPage > oldPage) {
                        mascotMood = PmugMood.PAGE_FINISHED
                        smugNotice = "Page $oldPage. Easy."
                        scope.launch {
                            delay(1800)
                            if (isPlaying) mascotMood = PmugMood.READING
                        }
                    }
                }
            } else {
                if (!extractionFinished) {
                    pendingNextSentence = nextIdx
                    isSentenceAudioStarted = false
                } else {
                    // Document finished!
                    onSetPlaying(false)
                    isSentenceAudioStarted = false
                    mascotMood = PmugMood.DOCUMENT_FINISHED
                    smugNotice = "Cover to cover. Obviously."
                    scope.launch {
                        repository.setFinished(document.id, true)
                    }
                }
            }
            }
        },
        onWordRange = { sId, start, end ->
            if (sId == currentSentenceIndex) activeWordRange = start to end
        },
        onProviderChanged = { mode -> currentProviderMode = mode },
        onError = { message -> scope.launch { snackbarHostState.showSnackbar(message) } }
    )

    if (preparingPiper) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Downloading natural offline voice") },
            text = {
                Column {
                    LinearProgressIndicator(progress = { piperProgress / 100f }, modifier = Modifier.fillMaxWidth())
                    Text("Preparing Piper: $piperProgress%", modifier = Modifier.padding(top = 12.dp))
                }
            },
            confirmButton = {}
        )
    }

    fun playSentence(index: Int) {
        if (index !in sentences.indices || sentences[index].pageNumber != readingPage) return
        onSetPlaying(true)
        isSentenceAudioStarted = false
        activeWordRange = null
        mascotMood = PmugMood.READING
        isFollowingVoice = true
        val sentence = sentences[index]
        if (currentProviderMode == TtsProviderMode.PIPER_OFFLINE && hybridEngine?.piperVoiceInstalled == true) {
            if (playbackSession.currentSpeed() != playbackSpeed) playbackSession.setSpeed(playbackSpeed)
            if (playbackSession.playbackState() == "paused" && currentSentenceIndex == index) playbackSession.resume()
            else playbackSession.play(index)
        } else {
            ttsEngine.speakSentence(index, sentence.spokenText, playbackSpeed, 1.0f)
        }
    }

    fun pauseReading() {
        onSetPlaying(false)
        isSentenceAudioStarted = false
        activeWordRange = null
        mascotMood = PmugMood.PAUSED
        if (currentProviderMode == TtsProviderMode.PIPER_OFFLINE && hybridEngine?.piperVoiceInstalled == true) playbackSession.pause()
        else ttsEngine.stop()
        scope.launch {
            repository.saveReadingPosition(document.id, readingPage, currentSentenceIndex)
        }
    }

    fun skipForward() {
        PmugHaptics.tick(context)
        val next = (currentSentenceIndex + 1).coerceAtMost(totalSentences - 1)
        currentSentenceIndex = next
        readingPage = sentences.getOrNull(next)?.pageNumber ?: readingPage
        isFollowingVoice = true
        if (isPlaying) {
            playSentence(next)
        }
    }

    fun skipBackward() {
        PmugHaptics.tick(context)
        val prev = (currentSentenceIndex - 1).coerceAtLeast(0)
        currentSentenceIndex = prev
        readingPage = sentences.getOrNull(prev)?.pageNumber ?: readingPage
        isFollowingVoice = true
        if (isPlaying) {
            playSentence(prev)
        }
    }

    BackHandler {
        pauseReading()
        onBack()
    }

    val fillProgress = currentSentenceIndex.toFloat() / totalSentences.coerceAtLeast(1)

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .background(PmugTokens.Ground),
        containerColor = PmugTokens.Ground,
        snackbarHost = {
            SnackbarHost(snackbarHostState) { snackbarData ->
                Snackbar(
                    snackbarData = snackbarData,
                    containerColor = PmugTokens.Surface,
                    contentColor = PmugTokens.Ink,
                    actionColor = PmugTokens.Accent,
                    dismissActionContentColor = PmugTokens.MutedInk
                )
            }
        },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = document.title,
                            style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = PmugTokens.Ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Page $readingPage of ${document.pages} · ${document.type}",
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            color = PmugTokens.MutedInk,
                            modifier = Modifier.clickable {
                                pageInput = readingPage.toString()
                                showPageDialog = true
                            }
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        pauseReading()
                        onBack()
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to Library",
                            tint = PmugTokens.Ink
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val text = pageSentences.joinToString("\n\n") { it.text }
                        if (text.isNotBlank()) {
                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Page $readingPage", text))
                            scope.launch { snackbarHostState.showSnackbar("Page text copied") }
                        }
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy page text", tint = PmugTokens.Ink)
                    }

                    // Voice Selection
                    var showVoiceMenu by remember { mutableStateOf(false) }
                    val hybridEngine = ttsEngine as? HybridTtsEngine

                    Box {
                        IconButton(
                            onClick = { showVoiceMenu = true },
                            modifier = Modifier.testTag("voice_selection_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.GraphicEq,
                                contentDescription = "Voice Engine Selection",
                                tint = PmugTokens.Ink
                            )
                        }
                        DropdownMenu(
                            expanded = showVoiceMenu,
                            onDismissRequest = { showVoiceMenu = false }
                        ) {
                            listOf(
                                TtsProviderMode.PIPER_OFFLINE,
                                TtsProviderMode.ANDROID_SYSTEM
                            ).forEach { mode ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(
                                                text = mode.displayName + if (currentProviderMode == mode) " ✓" else "",
                                                fontWeight = FontWeight.SemiBold,
                                                color = if (currentProviderMode == mode) PmugTokens.Accent else PmugTokens.Ink
                                            )
                                            Text(
                                                text = if (mode == TtsProviderMode.PIPER_OFFLINE && hybridEngine?.piperAvailable != true)
                                                    "Download natural offline voice (~100 MB)" else mode.description,
                                                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                                color = PmugTokens.MutedInk
                                            )
                                        }
                                    },
                                    onClick = {
                                        showVoiceMenu = false
                                        if (mode == TtsProviderMode.PIPER_OFFLINE && hybridEngine?.piperVoiceInstalled != true) {
                                            val connectivity = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                                            val online = connectivity.activeNetworkInfo?.isConnected == true
                                            val storageOk = android.os.StatFs(context.filesDir.path).availableBytes > 300L * 1024 * 1024
                                            if (!online) {
                                                scope.launch { snackbarHostState.showSnackbar("No network connection. Connect and retry the Piper download.") }
                                            } else if (!storageOk) {
                                                scope.launch { snackbarHostState.showSnackbar("Not enough storage. Piper needs about 300 MB free during setup.") }
                                            } else if (hybridEngine != null) {
                                                preparingPiper = true
                                                piperProgress = 0
                                                hybridEngine.preparePiper(progress = { piperProgress = it }) { result ->
                                                    preparingPiper = false
                                                    result.onSuccess {
                                                        currentProviderMode = mode
                                                        hybridEngine.switchProvider(mode)
                                                        scope.launch { snackbarHostState.showSnackbar("Natural offline voice is ready") }
                                                        if (isPlaying) playSentence(currentSentenceIndex)
                                                    }.onFailure { error ->
                                                        scope.launch { snackbarHostState.showSnackbar(error.message ?: "Piper download failed") }
                                                    }
                                                }
                                            }
                                            return@DropdownMenuItem
                                        }
                                        currentProviderMode = mode
                                        hybridEngine?.switchProvider(mode)
                                        scope.launch { snackbarHostState.showSnackbar("Voice selected: ${mode.displayName}") }
                                        if (isPlaying) playSentence(currentSentenceIndex)
                                    }
                                )
                            }
                            if (hybridEngine?.piperAvailable == true) {
                                DropdownMenuItem(
                                    text = { Text("Remove natural offline voice") },
                                    onClick = {
                                        showVoiceMenu = false
                                        hybridEngine.removePiperDownloadedModel { removed ->
                                            scope.launch {
                                                snackbarHostState.showSnackbar(
                                                    if (removed) "Piper voice removed" else "Could not remove Piper voice"
                                                )
                                            }
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Manage Piper Voices & Library...") },
                                    onClick = {
                                        showVoiceMenu = false
                                        showVoiceSheet = true
                                    }
                                )
                            }
                        }
                    }

                    // Saved Passages Bookmark button
                    IconButton(onClick = { showBookmarksSheet = true }) {
                        Icon(
                            imageVector = Icons.Default.Bookmarks,
                            contentDescription = "Saved Passages",
                            tint = PmugTokens.Ink
                        )
                    }

                    // Font Size dropdown
                    var showFontMenu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { showFontMenu = true }) {
                            Icon(imageVector = Icons.Default.FormatSize, contentDescription = "Font Size", tint = PmugTokens.Ink)
                        }
                        DropdownMenu(
                            expanded = showFontMenu,
                            onDismissRequest = { showFontMenu = false }
                        ) {
                            listOf(16f to "16sp", 18f to "18sp (Default)", 20f to "20sp", 22f to "22sp").forEach { (size, label) ->
                                DropdownMenuItem(
                                    text = { Text(label + if (fontSizeSp == size) " ✓" else "") },
                                    onClick = {
                                        fontSizeSp = size
                                        showFontMenu = false
                                    }
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PmugTokens.Ground)
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(PmugTokens.Ground)
            ) {
                // Reader Page Strip (row of page thumbnails to jump between pages)
                if (showPageStrip) {
                    ReaderPageStrip(
                        document = document,
                        currentPage = readingPage,
                        onSelectPage = { targetPage ->
                            val targetIndex = sentences.indexOfFirst { it.pageNumber == targetPage }
                            if (targetIndex >= 0) currentSentenceIndex = targetIndex
                            else if (isPlaying) pauseReading()
                            readingPage = targetPage
                            isFollowingVoice = true
                            if (targetIndex >= 0) {
                                if (isPlaying) playSentence(targetIndex)
                            }
                        }
                    )
                }

                // Bottom Dock: small mug, large play/pause, skip back/forward, speed control
                ReaderBottomDock(
                    mascotMood = mascotMood,
                    fillProgress = fillProgress,
                    isPlaying = isPlaying,
                    currentSentenceIndex = currentSentenceIndex,
                    totalSentences = totalSentences,
                    playbackSpeed = playbackSpeed,
                    onTogglePlayPause = {
                        if (isPlaying) pauseReading() else playSentence(currentSentenceIndex)
                    },
                    onSkipForward = { skipForward() },
                    onSkipBackward = { skipBackward() },
                    onSpeedChange = { speed ->
                        playbackSpeed = speed
                        if (isPlaying) playSentence(currentSentenceIndex)
                    },
                    onTogglePageStrip = { showPageStrip = !showPageStrip }
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (document.type == DocumentType.PDF) PdfPagePreview(
                document = document,
                page = readingPage,
                zoom = pdfZoom,
                pan = pdfPan,
                onZoomChange = { pdfZoom = it },
                onPanChange = { pdfPan = it },
                onPageSwipe = { delta ->
                    val targetPage = (readingPage + delta).coerceIn(1, document.pages)
                    if (isPlaying && sentences.none { it.pageNumber == targetPage }) pauseReading()
                    readingPage = targetPage
                    isFollowingVoice = true
                    val targetIndex = sentences.indexOfFirst { it.pageNumber == targetPage }.takeIf { it >= 0 }
                    if (targetIndex != null) {
                        currentSentenceIndex = targetIndex
                        if (isPlaying) playSentence(targetIndex)
                    } else {
                        if (isPlaying) pauseReading()
                        scope.launch { listState.scrollToItem(0) }
                    }
                },
                onOpenFullScreen = { showFullScreenPdf = true }
            ) else Spacer(Modifier.height(8.dp))
            if (!extractionFinished) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        "Extracting text: ${extractedPages.size} of ${document.pages} pages ready",
                        color = PmugTokens.MutedInk,
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall
                    )
                    LinearProgressIndicator(
                        Modifier.fillMaxWidth(),
                        color = PmugTokens.Accent,
                        trackColor = PmugTokens.SurfaceSubtle
                    )
                }
            }
            // Reading Text Stream
            SelectionContainer {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 24.dp)
                    .pointerInput(Unit) {
                        // Swipe left or right on text to skip sentence with light haptic
                        detectHorizontalDragGestures { _, dragAmount ->
                            if (dragAmount < -45f) {
                                skipForward()
                            } else if (dragAmount > 45f) {
                                skipBackward()
                            }
                        }
                    },
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                }

                itemsIndexed(pageSentences, key = { index, _ -> "page_${readingPage}_sentence_$index" }) { pageIndex, sentence ->
                    val index = pageSentenceStart + pageIndex
                    val isCurrent = (index == currentSentenceIndex)
                    val isPast = (index < currentSentenceIndex)
                    val isBookmarked = bookmarks.any { it.sentenceIndex == index }

                    SentenceItem(
                        sentence = sentence.text,
                        isCurrent = isCurrent,
                        isPast = isPast,
                        isPlaying = isPlaying,
                        isBookmarked = isBookmarked,
                        activeWordRange = if (isCurrent && isPlaying) activeWordRange else null,
                        fontSizeSp = fontSizeSp,
                        onClick = {
                            isFollowingVoice = true
                            currentSentenceIndex = index
                            playSentence(index)
                        },
                        onLongClick = {
                            PmugHaptics.tick(context)
                            scope.launch {
                                if (isBookmarked) {
                                    repository.removeBookmark(document.id, index)
                                    snackbarHostState.showSnackbar("Bookmark removed")
                                } else {
                                    repository.addBookmark(document.id, index, sentence.text.take(120))
                                    snackbarHostState.showSnackbar("Bookmark saved")
                                }
                            }
                        }
                    )
                }

                if (pageSentences.isEmpty()) {
                    item(key = "no_extractable_text") {
                        Text(
                            if (!extractionFinished && pageSentences.isEmpty()) {
                                "Text for page $readingPage is still loading."
                            } else {
                                if (document.type == DocumentType.PDF) "This PDF page has no readable selectable text. Scanned PDFs need OCR before speech can read them." else "No readable text on this page."
                            },
                            color = PmugTokens.MutedInk,
                            modifier = Modifier.padding(vertical = 20.dp)
                        )
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(100.dp))
                }
            }
            }

            // "Jump back to voice" button (shown if user scrolled manually)
            AnimatedVisibility(
                visible = !isFollowingVoice && isPlaying,
                enter = fadeIn() + androidx.compose.animation.scaleIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 16.dp)
            ) {
                Button(
                    onClick = {
                        isFollowingVoice = true
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PmugTokens.Accent,
                        contentColor = PmugTokens.TextOnAccent
                    ),
                    shape = PmugTokens.ButtonShape,
                    modifier = Modifier.pmugPressScale(onClick = { isFollowingVoice = true })
                ) {
                    Icon(imageVector = Icons.Default.Navigation, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Jump back to voice",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = PmugTokens.TextOnAccent
                    )
                }
            }

            // Smug Banner (Page finished / Cover to cover)
            smugNotice?.let { notice ->
                Surface(
                    shape = PmugTokens.CardShape,
                    color = PmugTokens.Surface,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(bottom = 16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        PmugMug(mood = mascotMood, size = 32.dp)
                        Text(
                            text = notice,
                            style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = PmugTokens.Accent
                        )
                    }
                }
            }
        }
    }

    if (showPageDialog) {
        AlertDialog(
            onDismissRequest = { showPageDialog = false },
            title = { Text("Go to page") },
            text = {
                Column {
                    OutlinedTextField(
                        value = pageInput,
                        onValueChange = { pageInput = it.filter(Char::isDigit).take(7) },
                        label = { Text("Page (1–${document.pages})") },
                        singleLine = true
                    )
                    Slider(
                        value = (pageInput.toFloatOrNull() ?: 1f).coerceIn(1f, document.pages.toFloat()),
                        onValueChange = { pageInput = it.toInt().coerceIn(1, document.pages).toString() },
                        valueRange = 1f..document.pages.toFloat()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    com.example.domain.pdf.PageNumberValidation.parse(pageInput, document.pages)?.let { page ->
                        readingPage = page
                        extractionTargetPage.set(page)
                        pendingJumpPage = page
                        isFollowingVoice = true
                        pendingNextSentence = -1
                        isSentenceAudioStarted = false
                        activeWordRange = null
                        scope.launch { listState.scrollToItem(0) }
                        if (isPlaying) {
                            if (currentProviderMode == TtsProviderMode.PIPER_OFFLINE && hybridEngine?.piperVoiceInstalled == true) playbackSession.stop()
                            else ttsEngine.stop()
                        }
                    }
                    showPageDialog = false
                }, enabled = com.example.domain.pdf.PageNumberValidation.parse(pageInput, document.pages) != null) { Text("Go") }
            },
            dismissButton = { TextButton(onClick = { showPageDialog = false }) { Text("Cancel") } }
        )
    }

    LaunchedEffect(pendingJumpPage, extractedPages) {
        val page = pendingJumpPage
        val target = extractedPages.getOrNull(page - 1).orEmpty().firstOrNull()
        if (target != null) {
            val index = sentences.indexOfFirst { it.pageNumber == page }
            if (index >= 0) {
                currentSentenceIndex = index
                pendingJumpPage = -1
                if (isPlaying) playSentence(index)
            }
        }
    }

    // Saved Passages Bottom Sheet
    if (showBookmarksSheet) {
        ModalBottomSheet(
            onDismissRequest = { showBookmarksSheet = false },
            sheetState = bookmarksSheetState,
            containerColor = PmugTokens.Surface,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .navigationBarsPadding()
                    .padding(bottom = 20.dp)
            ) {
                Text(
                    text = "Saved passages (${bookmarks.size})",
                    style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = PmugTokens.Ink
                )
                Spacer(modifier = Modifier.height(14.dp))

                if (bookmarks.isEmpty()) {
                    Text(
                        text = "No passages saved yet. Tap the bookmark icon beside a sentence to save it.",
                        color = PmugTokens.MutedInk,
                        fontSize = 14.sp
                    )
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(bookmarks) { b ->
                            Surface(
                                onClick = {
                                    showBookmarksSheet = false
                                    currentSentenceIndex = b.sentenceIndex
                                    readingPage = sentences.getOrNull(b.sentenceIndex)?.pageNumber ?: readingPage
                                    isFollowingVoice = true
                                    playSentence(b.sentenceIndex)
                                },
                                shape = RoundedCornerShape(12.dp),
                                color = PmugTokens.SurfaceSubtle,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = b.snippet,
                                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                                        color = PmugTokens.Ink,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(onClick = {
                                        scope.launch { repository.removeBookmark(document.id, b.sentenceIndex) }
                                    }) {
                                        Icon(imageVector = Icons.Default.Bookmark, contentDescription = "Remove", tint = PmugTokens.Accent)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showVoiceSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showVoiceSheet = false },
            sheetState = sheetState,
            containerColor = PmugTokens.Surface,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            VoiceSelectionSheet(
                ttsEngine = hybridEngine ?: return@ModalBottomSheet,
                currentVoiceId = hybridEngine.currentVoiceId,
                onSelectVoice = { voiceId ->
                    hybridEngine.switchProvider(TtsProviderMode.PIPER_OFFLINE)
                    hybridEngine.setVoice(voiceId)
                    showVoiceSheet = false
                    scope.launch { snackbarHostState.showSnackbar("Switched voice") }
                    if (isPlaying) playSentence(currentSentenceIndex)
                },
                onDownloadVoice = { voice, onDone ->
                    downloadingVoiceId = voice.id
                    voiceDownloadProgress = 0
                    hybridEngine.preparePiper(voice.id, progress = { voiceDownloadProgress = it }) { result ->
                        downloadingVoiceId = null
                        result.onSuccess {
                            onDone()
                            scope.launch { snackbarHostState.showSnackbar("Voice ${voice.displayName} downloaded & saved") }
                        }.onFailure { err ->
                            scope.launch { snackbarHostState.showSnackbar(err.message ?: "Download failed") }
                        }
                    }
                },
                onDeleteVoice = { voiceId, onDone ->
                    hybridEngine.deletePiperVoice(voiceId) { deleted ->
                        if (deleted) onDone()
                        scope.launch { snackbarHostState.showSnackbar(if (deleted) "Voice deleted" else "Could not delete voice") }
                    }
                },
                onPreviewVoice = { voice ->
                    scope.launch {
                        if (hybridEngine == null) return@launch
                        if (!hybridEngine.isPiperVoiceInstalled(voice.id)) {
                            snackbarHostState.showSnackbar("Download ${voice.displayName} first to preview it.")
                            return@launch
                        }
                        snackbarHostState.showSnackbar("Playing preview for ${voice.displayName}...")
                        hybridEngine.setVoice(voice.id)
                        val sampleAudio = hybridEngine.synthesizeAudio("This is a preview of the ${voice.displayName} voice in Pmug.", 1.0f)
                        if (sampleAudio.samples.isNotEmpty()) {
                            playAudioSamples(sampleAudio.samples, sampleAudio.sampleRate)
                        } else {
                            snackbarHostState.showSnackbar("Could not generate audio preview.")
                        }
                    }
                },
                downloadingVoiceId = downloadingVoiceId,
                downloadProgress = voiceDownloadProgress,
                onDismiss = { showVoiceSheet = false }
            )
        }
    }

    if (showFullScreenPdf && document.type == DocumentType.PDF) {
        FullScreenPdfViewer(
            document = document,
            initialPage = readingPage,
            isPlaying = isPlaying,
            onTogglePlay = { if (isPlaying) pauseReading() else playSentence(currentSentenceIndex) },
            onSkipPrevious = { skipBackward() },
            onSkipNext = { skipForward() },
            onPageChanged = { targetPage ->
                readingPage = targetPage
                val targetIndex = sentences.indexOfFirst { it.pageNumber == targetPage }
                if (targetIndex >= 0) {
                    currentSentenceIndex = targetIndex
                    if (isPlaying) playSentence(targetIndex)
                }
            },
            onClose = { showFullScreenPdf = false }
        )
    }
}

/**
 * Sentence Item with Highlighter-Pen stroke:
 * Active sentence gets highlighter-pen stroke #D6F25C.
 * Already read sentences dim to 60% ink.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SentenceItem(
    sentence: String,
    isCurrent: Boolean,
    isPast: Boolean,
    isPlaying: Boolean,
    isBookmarked: Boolean,
    activeWordRange: Pair<Int, Int>?,
    fontSizeSp: Float,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val textColor = when {
        isCurrent -> PmugTokens.TextOnAccent
        isPast -> PmugTokens.DimmedText
        else -> PmugTokens.Ink
    }

    val backgroundColor = if (isCurrent) PmugTokens.Accent else Color.Transparent

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor)
            .padding(horizontal = if (isCurrent) 10.dp else 0.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            Text(
                text = buildAnnotatedString {
                    append(sentence)
                    activeWordRange?.let { (start, end) ->
                        val safeStart = start.coerceIn(0, sentence.length)
                        val safeEnd = end.coerceIn(safeStart, sentence.length)
                        if (safeStart < safeEnd) addStyle(
                            SpanStyle(background = PmugTokens.TextOnAccent.copy(alpha = 0.28f)),
                            safeStart, safeEnd
                        )
                    }
                },
                fontFamily = LiterataFontFamily,
                fontSize = fontSizeSp.sp,
                lineHeight = (fontSizeSp * 1.6f).sp,
                color = textColor,
                modifier = Modifier.weight(1f).clickable(onClick = onClick)
            )

            Spacer(modifier = Modifier.width(6.dp))
            IconButton(onClick = onLongClick, modifier = Modifier.size(28.dp)) {
                Icon(
                    imageVector = Icons.Default.Bookmark,
                    contentDescription = if (isBookmarked) "Remove bookmark" else "Save bookmark",
                    tint = if (isBookmarked) PmugTokens.Accent else if (isCurrent) PmugTokens.TextOnAccent.copy(alpha = 0.55f) else PmugTokens.MutedInk.copy(alpha = 0.6f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun PdfPagePreview(
    document: PmugDocumentEntity,
    page: Int,
    zoom: Float,
    pan: Offset,
    onZoomChange: (Float) -> Unit,
    onPanChange: (Offset) -> Unit,
    onPageSwipe: (Int) -> Unit,
    onOpenFullScreen: () -> Unit
) {
    val context = LocalContext.current
    var bitmap by remember(document.path, page) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(document.path, document.hash, page) {
        bitmap = ThumbnailCache.getPageThumbnail(
            context, File(document.path), document.hash, (page - 1).coerceAtLeast(0), targetWidth = 900
        )
    }
    val currentZoom = rememberUpdatedState(zoom)
    val currentPan = rememberUpdatedState(pan)
    val latestZoomChange = rememberUpdatedState(onZoomChange)
    val latestPanChange = rememberUpdatedState(onPanChange)
    Surface(
        color = Color.White,
        shape = RoundedCornerShape(12.dp),
        shadowElevation = 3.dp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(300.dp)
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(page) {
                        detectTapGestures(
                            onDoubleTap = { tap ->
                                val oldZoom = currentZoom.value
                                val nextZoom = if (oldZoom > 1.25f) 1.25f else 2f
                                val delta = Offset(tap.x - size.width / 2f, tap.y - size.height / 2f)
                                if (nextZoom == 1f) {
                                    latestPanChange.value(Offset.Zero)
                                } else {
                                    latestPanChange.value(currentPan.value + delta * (oldZoom - nextZoom))
                                }
                                latestZoomChange.value(nextZoom)
                            }
                        )
                    }
                    .pointerInput(page) {
                        awaitEachGesture {
                            awaitFirstDown(pass = PointerEventPass.Initial, requireUnconsumed = false)
                            var horizontalDistance = 0f
                            var previousPosition: Offset? = null
                            var previousCentroid: Offset? = null
                            var previousDistance: Float? = null
                            var gestureZoom = currentZoom.value
                            var gesturePan = currentPan.value
                            var usedMultipleFingers = false
                            var fingersDown: Boolean
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.size > 1) {
                                    usedMultipleFingers = true
                                    val first = pressed[0].position
                                    val second = pressed[1].position
                                    val centroid = (first + second) / 2f
                                    val dx = first.x - second.x
                                    val dy = first.y - second.y
                                    val distance = kotlin.math.sqrt(dx * dx + dy * dy)
                                    val oldDistance = previousDistance
                                    val oldCentroid = previousCentroid
                                    if (oldDistance != null && oldDistance > 0f && oldCentroid != null) {
                                        val factor = distance / oldDistance
                                        val nextZoom = (gestureZoom * factor).coerceIn(1f, 4f)
                                        val appliedFactor = nextZoom / gestureZoom
                                        val anchor = centroid - Offset(size.width / 2f, size.height / 2f)
                                        gestureZoom = nextZoom
                                        gesturePan = if (nextZoom == 1f) Offset.Zero else {
                                            gesturePan + (centroid - oldCentroid) + anchor * (1f - appliedFactor)
                                        }.let { candidate ->
                                            val maxX = size.width * (gestureZoom - 1f) / 2f
                                            val maxY = size.height * (gestureZoom - 1f) / 2f
                                            Offset(candidate.x.coerceIn(-maxX, maxX), candidate.y.coerceIn(-maxY, maxY))
                                        }
                                        latestZoomChange.value(nextZoom)
                                        latestPanChange.value(gesturePan)
                                    }
                                    previousCentroid = centroid
                                    previousDistance = distance
                                } else {
                                    previousCentroid = null
                                    previousDistance = null
                                }
                                val primaryX = pressed.firstOrNull()?.position?.x
                                if (primaryX != null) {
                                    val point = pressed.first().position
                                    previousPosition?.let { previous ->
                                        val movement = point - previous
                                        horizontalDistance += movement.x
                                        if (gestureZoom > 1.01f) {
                                            val maxX = size.width * (gestureZoom - 1f) / 2f
                                            val maxY = size.height * (gestureZoom - 1f) / 2f
                                            gesturePan = Offset(
                                                (gesturePan.x + movement.x).coerceIn(-maxX, maxX),
                                                (gesturePan.y + movement.y).coerceIn(-maxY, maxY)
                                            )
                                            latestPanChange.value(gesturePan)
                                        }
                                    }
                                    previousPosition = point
                                }
                                fingersDown = pressed.isNotEmpty()
                            } while (fingersDown)

                            if (!usedMultipleFingers && currentZoom.value <= 1.01f && abs(horizontalDistance) > 60f) {
                                onPageSwipe(if (horizontalDistance < 0f) 1 else -1)
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap!!.asImageBitmap(),
                        contentDescription = "PDF page $page",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(8.dp).graphicsLayer {
                            scaleX = zoom
                            scaleY = zoom
                            translationX = pan.x
                            translationY = pan.y
                        }
                    )
                } else {
                    Text("Loading PDF page $page…", color = PmugTokens.MutedInk)
                }
            }

            IconButton(
                onClick = onOpenFullScreen,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                    .size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.OpenInFull,
                    contentDescription = "Full screen",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

/**
 * Bottom Dock:
 * Small mug on left, large play/pause, skip back/forward, speed control.
 */
@Composable
private fun ReaderBottomDock(
    mascotMood: PmugMood,
    fillProgress: Float,
    isPlaying: Boolean,
    currentSentenceIndex: Int,
    totalSentences: Int,
    playbackSpeed: Float,
    onTogglePlayPause: () -> Unit,
    onSkipForward: () -> Unit,
    onSkipBackward: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    onTogglePageStrip: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        color = PmugTokens.Surface,
        tonalElevation = 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Small mascot on the left
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PmugMug(
                    mood = mascotMood,
                    fillLevel = fillProgress,
                    size = 38.dp,
                    onTap = onTogglePageStrip
                )
            }

            // Skip back, Play/Pause, Skip forward
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                IconButton(
                    onClick = onSkipBackward,
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.FastRewind,
                        contentDescription = "Skip backward",
                        tint = PmugTokens.Ink
                    )
                }

                // Large play/pause button (fully round, #D6F25C)
                Surface(
                    onClick = onTogglePlayPause,
                    shape = CircleShape,
                    color = PmugTokens.Accent,
                    modifier = Modifier
                        .size(56.dp)
                        .testTag("reader_play_pause")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = PmugTokens.TextOnAccent,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }

                IconButton(
                    onClick = onSkipForward,
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.FastForward,
                        contentDescription = "Skip forward",
                        tint = PmugTokens.Ink
                    )
                }
            }

            // Speed control (0.75x to 2x)
            var showSpeedMenu by remember { mutableStateOf(false) }
            Box {
                Surface(
                    onClick = { showSpeedMenu = true },
                    shape = PmugTokens.PillShape,
                    color = PmugTokens.SurfaceSubtle
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Speed,
                            contentDescription = "Speed",
                            modifier = Modifier.size(14.dp),
                            tint = PmugTokens.Accent
                        )
                        Text(
                            text = "${playbackSpeed}x",
                            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = PmugTokens.Ink
                        )
                    }
                }

                DropdownMenu(
                    expanded = showSpeedMenu,
                    onDismissRequest = { showSpeedMenu = false }
                ) {
                    listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.7f).forEach { speed ->
                        DropdownMenuItem(
                            text = { Text("${speed}x" + if (speed == playbackSpeed) " ✓" else "") },
                            onClick = {
                                onSpeedChange(speed)
                                showSpeedMenu = false
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Reader Page Strip: row of page thumbnails at the bottom to jump between pages.
 */
@Composable
private fun ReaderPageStrip(
    document: PmugDocumentEntity,
    currentPage: Int,
    onSelectPage: (Int) -> Unit
) {
    val context = LocalContext.current
    Surface(
        color = PmugTokens.Ground,
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp)
    ) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items(document.pages) { pageIdx ->
                val pageNumber = pageIdx + 1
                var pageThumb by remember(pageIdx) { mutableStateOf<Bitmap?>(null) }

                LaunchedEffect(pageIdx) {
                    pageThumb = ThumbnailCache.getPageThumbnail(
                        context = context,
                        pdfFile = File(document.path),
                        fileHash = document.hash,
                        pageIndex = pageIdx,
                        targetWidth = 140
                    )
                }

                Surface(
                    onClick = { onSelectPage(pageNumber) },
                    shape = RoundedCornerShape(8.dp),
                    color = if (pageNumber == currentPage) PmugTokens.Accent else PmugTokens.Surface,
                    modifier = Modifier
                        .width(52.dp)
                        .height(76.dp)
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        if (pageThumb != null) {
                            Image(
                                bitmap = pageThumb!!.asImageBitmap(),
                                contentDescription = "Page $pageNumber",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(8.dp))
                            )
                        }
                        Text(
                            text = pageNumber.toString(),
                            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (pageNumber == currentPage) PmugTokens.Accent else PmugTokens.Ink,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .background(PmugTokens.Ground.copy(alpha = 0.85f))
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DisposableEffectWithLifecycle(
    ttsEngine: TtsEngine,
    onSentenceStarted: (Int) -> Unit,
    onSentenceCompleted: (Int) -> Unit,
    onWordRange: (Int, Int, Int) -> Unit,
    onProviderChanged: (TtsProviderMode) -> Unit,
    onError: (String) -> Unit
) {
    val currentStarted by rememberUpdatedState(onSentenceStarted)
    val currentCompleted by rememberUpdatedState(onSentenceCompleted)
    val currentWordRange by rememberUpdatedState(onWordRange)
    val currentProviderChanged by rememberUpdatedState(onProviderChanged)
    val currentError by rememberUpdatedState(onError)
    LaunchedEffect(ttsEngine) {
        ttsEngine.setListener(object : TtsEngineListener {
            override fun onSentenceStarted(sentenceId: Int) {
                currentStarted(sentenceId)
            }

            override fun onSentenceCompleted(sentenceId: Int) {
                currentCompleted(sentenceId)
            }

            override fun onError(sentenceId: Int, message: String) {
                currentError(message)
            }

            override fun onEngineReady() {}

            override fun onWordRange(sentenceId: Int, start: Int, end: Int) {
                currentWordRange(sentenceId, start, end)
            }

            override fun onProviderChanged(mode: TtsProviderMode) {
                currentProviderChanged(mode)
            }
        })
    }
}

private fun playAudioSamples(samples: FloatArray, sampleRate: Int) {
    if (samples.isEmpty() || sampleRate <= 0) return
    Thread {
        try {
            val minBuf = android.media.AudioTrack.getMinBufferSize(sampleRate, android.media.AudioFormat.CHANNEL_OUT_MONO, android.media.AudioFormat.ENCODING_PCM_FLOAT)
            val track = android.media.AudioTrack.Builder()
                .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(android.media.AudioFormat.Builder().setEncoding(android.media.AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(sampleRate).setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(android.media.AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes((minBuf * 2).coerceAtLeast(8192))
                .build()
            track.play()
            var offset = 0
            while (offset < samples.size) {
                val written = track.write(samples, offset, minOf(2048, samples.size - offset), android.media.AudioTrack.WRITE_BLOCKING)
                if (written <= 0) break
                offset += written
            }
            Thread.sleep(300)
            track.stop()
            track.release()
        } catch (_: Exception) {}
    }.start()
}
