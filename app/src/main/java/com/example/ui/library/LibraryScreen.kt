package com.example.ui.library

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PinDrop
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.haptics.PmugHaptics
import com.example.core.theme.PmugMotionSpec.pmugPressScale
import com.example.core.theme.PmugTokens
import com.example.data.PmugDocumentEntity
import com.example.data.PmugProgressEntity
import com.example.data.PmugRepository
import com.example.data.ThumbnailCache
import com.example.mascot.PmugMood
import com.example.mascot.PmugMug
import kotlinx.coroutines.launch
import java.io.File

enum class LibraryShelf {
    CONTINUE,
    ALL,
    FINISHED
}

enum class LibrarySort {
    LAST_READ,
    TITLE,
    DATE_ADDED
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    repository: PmugRepository,
    onOpenDocument: (String) -> Unit,
    onOpenDebug: () -> Unit,
    isAudioPlaying: Boolean,
    currentPlayingDocTitle: String?,
    onToggleMiniPlayPause: () -> Unit,
    onExpandPlayingDoc: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val allDocs by repository.allDocuments.collectAsState(initial = emptyList())

    var activeShelf by remember { mutableStateOf(LibraryShelf.ALL) }
    var sortBy by remember { mutableStateOf(LibrarySort.LAST_READ) }
    var searchQuery by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }

    var isImporting by remember { mutableStateOf(false) }

    // Context Action Dialog (long-press a card for pin, rename, delete)
    var selectedDocForActions by remember { mutableStateOf<PmugDocumentEntity?>(null) }
    var renameDialogDoc by remember { mutableStateOf<PmugDocumentEntity?>(null) }
    var deleteDialogDoc by remember { mutableStateOf<PmugDocumentEntity?>(null) }

    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            isImporting = true
            scope.launch {
                try {
                    val prepared = repository.prepareImport(uri).getOrThrow()
                    val saved = repository.commitPreparedDocument(prepared)
                    onOpenDocument(saved.id)
                } catch (err: Exception) {
                    snackbarHostState.showSnackbar(err.message ?: "Could not import this PDF")
                } finally {
                    isImporting = false
                }
            }
        }
    }

    // Filter & Sort documents
    val filteredDocs = allDocs
        .filter { doc ->
            val matchesShelf = when (activeShelf) {
                LibraryShelf.CONTINUE -> !doc.finished
                LibraryShelf.ALL -> true
                LibraryShelf.FINISHED -> doc.finished
            }
            val matchesSearch = if (searchQuery.isBlank()) true else {
                doc.title.contains(searchQuery, ignoreCase = true)
            }
            matchesShelf && matchesSearch
        }
        .sortedWith { a, b ->
            when (sortBy) {
                LibrarySort.LAST_READ -> b.lastReadAt.compareTo(a.lastReadAt)
                LibrarySort.TITLE -> a.title.compareTo(b.title, ignoreCase = true)
                LibrarySort.DATE_ADDED -> b.addedAt.compareTo(a.addedAt)
            }
        }

    // Identify Most Recent unfinished doc for Continue Card
    val continueDoc = allDocs.filter { !it.finished }.maxByOrNull { it.lastReadAt }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .background(PmugTokens.Ground),
        containerColor = PmugTokens.Ground,
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    if (!isImporting) filePicker.launch(arrayOf("application/pdf", "application/x-pdf", "application/octet-stream", "*/*"))
                },
                containerColor = PmugTokens.Accent,
                contentColor = PmugTokens.TextOnAccent,
                shape = PmugTokens.ButtonShape,
                modifier = Modifier
                    .navigationBarsPadding()
                    .testTag("fab_add_pdf")
            ) {
                Icon(imageVector = Icons.Default.Add, contentDescription = "Add a PDF")
            }
        },
        bottomBar = {
            // Mini Bar if audio is playing when leaving reader
            AnimatedVisibility(
                visible = isAudioPlaying && currentPlayingDocTitle != null,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut()
            ) {
                Surface(
                    onClick = onExpandPlayingDoc,
                    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                    color = PmugTokens.Surface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            PmugMug(mood = PmugMood.READING, size = 32.dp)
                            Column {
                                Text(
                                    text = currentPlayingDocTitle ?: "Reading",
                                    style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = PmugTokens.Ink,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "Tap to expand",
                                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                                    color = PmugTokens.Accent
                                )
                            }
                        }

                        IconButton(
                            onClick = onToggleMiniPlayPause,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = if (isAudioPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = "Play/Pause",
                                tint = PmugTokens.Ink
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp)
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // Header: Big, confident, left-aligned
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    PmugMug(
                        mood = if (isImporting) PmugMood.PARSING else PmugMood.IDLE,
                        size = 42.dp
                    )
                    Column {
                        Text(
                            text = "Pmug",
                            style = androidx.compose.material3.MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.Bold,
                            color = PmugTokens.Ink,
                            modifier = Modifier
                                .combinedClickable(
                                    onClick = {},
                                    onLongClick = {
                                        PmugHaptics.tick(context)
                                        onOpenDebug()
                                    }
                                )
                                .testTag("app_title")
                        )
                        if (isImporting) {
                            Text("Importing PDF…", color = PmugTokens.MutedInk, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { isSearching = !isSearching }) {
                        Icon(
                            imageVector = if (isSearching) Icons.Default.Close else Icons.Default.Search,
                            contentDescription = "Search",
                            tint = PmugTokens.Ink
                        )
                    }

                    // Sort menu
                    var showSortMenu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { showSortMenu = true }) {
                            Icon(imageVector = Icons.Default.Sort, contentDescription = "Sort", tint = PmugTokens.Ink)
                        }
                        DropdownMenu(
                            expanded = showSortMenu,
                            onDismissRequest = { showSortMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Last read" + if (sortBy == LibrarySort.LAST_READ) " ✓" else "") },
                                onClick = { sortBy = LibrarySort.LAST_READ; showSortMenu = false }
                            )
                            DropdownMenuItem(
                                text = { Text("Title" + if (sortBy == LibrarySort.TITLE) " ✓" else "") },
                                onClick = { sortBy = LibrarySort.TITLE; showSortMenu = false }
                            )
                            DropdownMenuItem(
                                text = { Text("Date added" + if (sortBy == LibrarySort.DATE_ADDED) " ✓" else "") },
                                onClick = { sortBy = LibrarySort.DATE_ADDED; showSortMenu = false }
                            )
                        }
                    }
                }
            }

            if (isImporting) {
                Surface(
                    color = PmugTokens.Surface,
                    shape = PmugTokens.CardShape,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Importing your PDF", color = PmugTokens.Ink, fontWeight = FontWeight.SemiBold)
                        Text("Copying the file and preparing its text for reading. Large PDFs take longer the first time.", color = PmugTokens.MutedInk, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(Modifier.fillMaxWidth(), color = PmugTokens.Accent, trackColor = PmugTokens.SurfaceSubtle)
                    }
                }
            }

            // Search Bar (if active)
            AnimatedVisibility(visible = isSearching) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search by title...", color = PmugTokens.MutedInk) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PmugTokens.Accent,
                        unfocusedBorderColor = PmugTokens.SurfaceSubtle,
                        focusedTextColor = PmugTokens.Ink,
                        unfocusedTextColor = PmugTokens.Ink
                    ),
                    shape = PmugTokens.CardShape,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                )
            }

            // Automatic Shelves Tabs: Continue, All, Finished
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ShelfPill(
                    title = "All",
                    count = allDocs.size,
                    isSelected = activeShelf == LibraryShelf.ALL,
                    onClick = { activeShelf = LibraryShelf.ALL }
                )
                ShelfPill(
                    title = "Continue",
                    count = allDocs.count { !it.finished },
                    isSelected = activeShelf == LibraryShelf.CONTINUE,
                    onClick = { activeShelf = LibraryShelf.CONTINUE }
                )
                ShelfPill(
                    title = "Finished",
                    count = allDocs.count { it.finished },
                    isSelected = activeShelf == LibraryShelf.FINISHED,
                    onClick = { activeShelf = LibraryShelf.FINISHED }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Empty State
            if (allDocs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 60.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        PmugMug(mood = PmugMood.IDLE, size = 96.dp)
                        Text(
                            text = "Nothing to sip yet.",
                            style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = PmugTokens.Ink
                        )
                        Text(
                            text = "Add a PDF and I will read it to you.",
                            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                            color = PmugTokens.MutedInk
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Button(
                            onClick = { if (!isImporting) filePicker.launch(arrayOf("application/pdf", "application/x-pdf", "application/octet-stream", "*/*")) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = PmugTokens.Accent,
                                contentColor = PmugTokens.TextOnAccent
                            ),
                            shape = PmugTokens.ButtonShape,
                            modifier = Modifier
                                .pmugPressScale(onClick = { filePicker.launch(arrayOf("application/pdf", "application/x-pdf", "application/octet-stream", "*/*")) })
                                .testTag("empty_add_pdf_button")
                        ) {
                            Text(
                                text = "Add a PDF",
                                fontWeight = FontWeight.Bold,
                                color = PmugTokens.TextOnAccent
                            )
                        }
                    }
                }
            } else {
                // Two-column staggered grid of real covers with real aspect ratios!
                LazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalItemSpacing = 16.dp,
                    contentPadding = PaddingValues(bottom = 100.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    // One large Continue Card at the top (spans full width)
                    if (continueDoc != null && activeShelf != LibraryShelf.FINISHED) {
                        item(span = StaggeredGridItemSpan.FullLine) {
                            ContinueCard(
                                doc = continueDoc,
                                repository = repository,
                                onOpen = { onOpenDocument(continueDoc.id) }
                            )
                        }
                    }

                    // Two-column grid of document covers
                    items(filteredDocs, key = { it.id }) { doc ->
                        DocumentCoverCard(
                            doc = doc,
                            repository = repository,
                            onOpen = { onOpenDocument(doc.id) },
                            onLongPress = {
                                PmugHaptics.tick(context)
                                selectedDocForActions = doc
                            }
                        )
                    }
                }
            }
        }
    }

    // Context Actions Dialog: Pin, Rename, Delete
    selectedDocForActions?.let { doc ->
        AlertDialog(
            onDismissRequest = { selectedDocForActions = null },
            containerColor = PmugTokens.Surface,
            title = {
                Text(
                    text = doc.title,
                    color = PmugTokens.Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            scope.launch {
                                repository.setPinned(doc.id, !doc.pinned)
                                selectedDocForActions = null
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (doc.pinned) Icons.Default.PushPin else Icons.Default.PinDrop,
                                contentDescription = null,
                                tint = PmugTokens.Ink
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = if (doc.pinned) "Unpin from top" else "Pin to top",
                                color = PmugTokens.Ink
                            )
                        }
                    }

                    TextButton(
                        onClick = {
                            renameDialogDoc = doc
                            selectedDocForActions = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(imageVector = Icons.Default.Edit, contentDescription = null, tint = PmugTokens.Ink)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("Rename", color = PmugTokens.Ink)
                        }
                    }

                    TextButton(
                        onClick = {
                            deleteDialogDoc = doc
                            selectedDocForActions = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(imageVector = Icons.Default.Delete, contentDescription = null, tint = PmugTokens.Danger)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("Delete", color = PmugTokens.Danger)
                        }
                    }
                }
            },
            confirmButton = {}
        )
    }

    // Rename Dialog
    renameDialogDoc?.let { doc ->
        var renameText by remember { mutableStateOf(doc.title) }
        AlertDialog(
            onDismissRequest = { renameDialogDoc = null },
            containerColor = PmugTokens.Surface,
            title = { Text("Rename PDF", color = PmugTokens.Ink) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PmugTokens.Accent,
                        unfocusedBorderColor = PmugTokens.SurfaceSubtle,
                        focusedTextColor = PmugTokens.Ink,
                        unfocusedTextColor = PmugTokens.Ink
                    )
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            repository.renameDocument(doc.id, renameText)
                            renameDialogDoc = null
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PmugTokens.Accent),
                    shape = PmugTokens.ButtonShape
                ) {
                    Text("Save", color = PmugTokens.TextOnAccent, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { renameDialogDoc = null }) {
                    Text("Cancel", color = PmugTokens.MutedInk)
                }
            }
        )
    }

    // Delete confirmation
    deleteDialogDoc?.let { doc ->
        AlertDialog(
            onDismissRequest = { deleteDialogDoc = null },
            containerColor = PmugTokens.Surface,
            title = { Text("Delete document?", color = PmugTokens.Ink) },
            text = { Text("Remove \"${doc.title}\" and all saved reading progress?", color = PmugTokens.MutedInk) },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            repository.deleteDocument(doc.id)
                            deleteDialogDoc = null
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PmugTokens.Danger),
                    shape = PmugTokens.ButtonShape
                ) {
                    Text("Delete", color = PmugTokens.Ink, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteDialogDoc = null }) {
                    Text("Cancel", color = PmugTokens.MutedInk)
                }
            }
        )
    }
}

@Composable
private fun ShelfPill(
    title: String,
    count: Int,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = PmugTokens.PillShape,
        color = if (isSelected) PmugTokens.Surface else PmugTokens.Ground,
        modifier = Modifier.padding(vertical = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = title,
                style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = if (isSelected) PmugTokens.Ink else PmugTokens.MutedInk
            )
            if (count > 0) {
                Text(
                    text = "$count",
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    color = if (isSelected) PmugTokens.Accent else PmugTokens.MutedInk.copy(alpha = 0.7f)
                )
            }
        }
    }
}

/**
 * Large Continue Card:
 * Real first-page thumbnail, title, Page 12 of 48.
 * Bookmark ribbon placed proportionally down the cover edge.
 * Resume button: Pick up at page 12.
 */
@Composable
private fun ContinueCard(
    doc: PmugDocumentEntity,
    repository: PmugRepository,
    onOpen: () -> Unit
) {
    val context = LocalContext.current
    var thumbnailBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var progressPage by remember { mutableStateOf(1) }

    LaunchedEffect(doc.id) {
        val prog = repository.getProgress(doc.id)
        progressPage = (prog?.page ?: 1).coerceAtLeast(1)
        thumbnailBitmap = ThumbnailCache.getPageThumbnail(
            context = context,
            pdfFile = File(doc.path),
            fileHash = doc.hash,
            pageIndex = 0,
            targetWidth = 400
        )
    }

    val progressFraction = (progressPage.toFloat() / doc.pages.coerceAtLeast(1)).coerceIn(0.05f, 0.95f)

    Surface(
        onClick = onOpen,
        shape = PmugTokens.CardShape,
        color = PmugTokens.Surface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .testTag("continue_card")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Real cover thumbnail with proportional bookmark ribbon
            Box(
                modifier = Modifier
                    .width(90.dp)
                    .height(125.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(PmugTokens.Ground)
            ) {
                if (thumbnailBitmap != null) {
                    Image(
                        bitmap = thumbnailBitmap!!.asImageBitmap(),
                        contentDescription = "Cover",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Bookmark Ribbon placed proportionally down the cover edge
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(y = 125.dp * progressFraction - 8.dp)
                        .size(width = 10.dp, height = 18.dp)
                        .background(
                            color = PmugTokens.Accent,
                            shape = RoundedCornerShape(topStart = 3.dp, bottomStart = 3.dp)
                        )
                )
            }

            // Info column
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "CONTINUE READING",
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    color = PmugTokens.Accent,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = doc.title,
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = PmugTokens.Ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = "Page $progressPage of ${doc.pages}",
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = PmugTokens.MutedInk
                )

                Spacer(modifier = Modifier.height(2.dp))

                Button(
                    onClick = onOpen,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PmugTokens.Accent,
                        contentColor = PmugTokens.TextOnAccent
                    ),
                    shape = PmugTokens.ButtonShape,
                    modifier = Modifier.pmugPressScale(onClick = onOpen)
                ) {
                    Text(
                        text = "Pick up at page $progressPage",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = PmugTokens.TextOnAccent
                    )
                }
            }
        }
    }
}

/**
 * Two-column cover card:
 * The real first-page thumbnail following aspect ratio, title, Page 12 of 48.
 * Bookmark ribbon placed proportionally down the cover edge.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DocumentCoverCard(
    doc: PmugDocumentEntity,
    repository: PmugRepository,
    onOpen: () -> Unit,
    onLongPress: () -> Unit
) {
    val context = LocalContext.current
    var thumbnailBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var progressPage by remember { mutableStateOf(1) }

    LaunchedEffect(doc.id) {
        val prog = repository.getProgress(doc.id)
        progressPage = (prog?.page ?: 1).coerceAtLeast(1)
        thumbnailBitmap = ThumbnailCache.getPageThumbnail(
            context = context,
            pdfFile = File(doc.path),
            fileHash = doc.hash,
            pageIndex = 0,
            targetWidth = 340
        )
    }

    val progressFraction = (progressPage.toFloat() / doc.pages.coerceAtLeast(1)).coerceIn(0.05f, 0.95f)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onOpen,
                onLongClick = onLongPress
            )
            .testTag("cover_card_${doc.id}")
    ) {
        // Thumbnail with bookmark ribbon
        Surface(
            shape = PmugTokens.CardShape,
            color = PmugTokens.Surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.72f) // Standard book page aspect ratio
                    .clip(PmugTokens.CardShape)
                    .background(PmugTokens.SurfaceSubtle)
            ) {
                if (thumbnailBitmap != null) {
                    Image(
                        bitmap = thumbnailBitmap!!.asImageBitmap(),
                        contentDescription = "Cover",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Bookmark Ribbon placed proportionally down the cover edge
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .fillMaxWidth(0.08f)
                        .height(20.dp)
                        .offset(y = 160.dp * progressFraction)
                        .background(
                            color = if (doc.finished) PmugTokens.MutedInk else PmugTokens.Accent,
                            shape = RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp)
                        )
                )

                if (doc.pinned) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                            .size(22.dp)
                            .background(PmugTokens.Ground.copy(alpha = 0.8f), shape = RoundedCornerShape(6.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PushPin,
                            contentDescription = "Pinned",
                            tint = PmugTokens.Accent,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = doc.title,
            style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = PmugTokens.Ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        Text(
            text = if (doc.finished) "Finished (${doc.pages} pages)" else "Page $progressPage of ${doc.pages}",
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            color = if (doc.finished) PmugTokens.Accent else PmugTokens.MutedInk
        )
    }
}
