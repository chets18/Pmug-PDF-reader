package com.example.ui.reader

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.theme.PmugTokens
import com.example.data.PmugDocumentEntity
import com.example.data.ThumbnailCache
import java.io.File

@Composable
fun FullScreenPdfViewer(
    document: PmugDocumentEntity,
    initialPage: Int,
    isPlaying: Boolean,
    onTogglePlay: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    onPageChanged: (Int) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    var page by remember { mutableIntStateOf(initialPage) }
    var zoom by remember(page) { mutableFloatStateOf(1f) }
    var pan by remember(page) { mutableStateOf(Offset.Zero) }
    var showControls by remember { mutableStateOf(true) }

    var bitmap by remember(document.path, page) { mutableStateOf<Bitmap?>(null) }
    var highResBitmap by remember(document.path, page, zoom) { mutableStateOf<Bitmap?>(null) }

    BackHandler {
        onClose()
    }

    LaunchedEffect(document.path, page) {
        bitmap = ThumbnailCache.getPageThumbnail(
            context, File(document.path), document.hash, (page - 1).coerceAtLeast(0), targetWidth = 1000
        )
    }

    // High res render when zoomed in
    LaunchedEffect(document.path, page, zoom) {
        if (zoom > 1.5f) {
            val targetWidth = (1000 * zoom.coerceAtMost(4f)).toInt().coerceAtMost(4096)
            highResBitmap = ThumbnailCache.getPageThumbnail(
                context, File(document.path), document.hash, (page - 1).coerceAtLeast(0), targetWidth = targetWidth
            )
        } else {
            highResBitmap = null
        }
    }

    val displayBitmap = highResBitmap ?: bitmap

    val currentZoom = rememberUpdatedState(zoom)
    val currentPan = rememberUpdatedState(pan)
    val latestZoomChange = rememberUpdatedState { z: Float -> zoom = z }
    val latestPanChange = rememberUpdatedState { p: Offset -> pan = p }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Black
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(page) {
                    detectTapGestures(
                        onTap = { showControls = !showControls },
                        onDoubleTap = { tap ->
                            val oldZoom = currentZoom.value
                            val nextZoom = if (oldZoom > 1.5f) 1f else 2.5f
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
                        var previousPosition: Offset? = null
                        var previousCentroid: Offset? = null
                        var previousDistance: Float? = null
                        var gestureZoom = currentZoom.value
                        var gesturePan = currentPan.value
                        var fingersDown: Boolean
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.size > 1) {
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
                                    val nextZoom = (gestureZoom * factor).coerceIn(1f, 8f)
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
                            val primaryPoint = pressed.firstOrNull()?.position
                            if (primaryPoint != null) {
                                previousPosition?.let { previous ->
                                    val movement = primaryPoint - previous
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
                                previousPosition = primaryPoint
                            }
                            fingersDown = pressed.isNotEmpty()
                        } while (fingersDown)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            if (displayBitmap != null) {
                Image(
                    bitmap = displayBitmap.asImageBitmap(),
                    contentDescription = "Full-screen page $page",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = zoom
                            scaleY = zoom
                            translationX = pan.x
                            translationY = pan.y
                        }
                )
            } else {
                Text("Loading page $page...", color = Color.White)
            }

            // Top overlay: Close & Page number / Nav
            AnimatedVisibility(
                visible = showControls,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                Surface(
                    color = Color.Black.copy(alpha = 0.7f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onClose) {
                            Icon(Icons.Default.Close, contentDescription = "Close full-screen", tint = Color.White)
                        }

                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            IconButton(
                                onClick = {
                                    if (page > 1) {
                                        page--
                                        onPageChanged(page)
                                    }
                                },
                                enabled = page > 1
                            ) {
                                Icon(Icons.AutoMirrored.Filled.NavigateBefore, contentDescription = "Previous page", tint = Color.White)
                            }
                            Text(
                                text = "Page $page of ${document.pages}",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            IconButton(
                                onClick = {
                                    if (page < document.pages) {
                                        page++
                                        onPageChanged(page)
                                    }
                                },
                                enabled = page < document.pages
                            ) {
                                Icon(Icons.AutoMirrored.Filled.NavigateNext, contentDescription = "Next page", tint = Color.White)
                            }
                        }
                    }
                }
            }

            // Bottom overlay: Audio Control bar
            AnimatedVisibility(
                visible = showControls,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Surface(
                    color = Color.Black.copy(alpha = 0.75f),
                    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onSkipPrevious) {
                            Icon(Icons.AutoMirrored.Filled.NavigateBefore, contentDescription = "Previous sentence", tint = Color.White)
                        }

                        Surface(
                            onClick = onTogglePlay,
                            shape = CircleShape,
                            color = PmugTokens.Accent,
                            modifier = Modifier.size(56.dp)
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

                        IconButton(onClick = onSkipNext) {
                            Icon(Icons.AutoMirrored.Filled.NavigateNext, contentDescription = "Next sentence", tint = Color.White)
                        }
                    }
                }
            }
        }
    }
}
