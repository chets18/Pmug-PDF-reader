package com.example.ui.library

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.theme.PmugMotionSpec.pmugPressScale
import com.example.core.theme.PmugTokens
import com.example.data.PreparedPdf
import com.example.data.ThumbnailCache

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToShelfSheet(
    preparedPdf: PreparedPdf,
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val context = LocalContext.current
    var title by remember { mutableStateOf(preparedPdf.title) }
    var thumbnailBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isLoadingThumbnail by remember { mutableStateOf(true) }

    LaunchedEffect(preparedPdf.hash) {
        isLoadingThumbnail = true
        thumbnailBitmap = ThumbnailCache.getPageThumbnail(
            context = context,
            pdfFile = preparedPdf.file,
            fileHash = preparedPdf.hash,
            pageIndex = 0,
            targetWidth = 500
        )
        isLoadingThumbnail = false
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = PmugTokens.Surface,
        tonalElevation = 0.dp,
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
                text = "Add to shelf",
                style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = PmugTokens.Ink
            )

            Spacer(modifier = Modifier.height(18.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Real Preview Thumbnail
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = PmugTokens.Ground,
                    modifier = Modifier
                        .width(110.dp)
                        .height(150.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (thumbnailBitmap != null) {
                            Image(
                                bitmap = thumbnailBitmap!!.asImageBitmap(),
                                contentDescription = "Page 1 Preview",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                            )
                        } else if (isLoadingThumbnail) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = PmugTokens.Accent,
                                strokeWidth = 2.dp
                            )
                        }
                    }
                }

                // Metadata Column
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .height(150.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = it },
                            label = { Text("Title", color = PmugTokens.MutedInk) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = PmugTokens.Accent,
                                unfocusedBorderColor = PmugTokens.SurfaceSubtle,
                                focusedTextColor = PmugTokens.Ink,
                                unfocusedTextColor = PmugTokens.Ink
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "${preparedPdf.pageCount} pages",
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            color = PmugTokens.MutedInk
                        )
                    }

                    if (!preparedPdf.isExtractable) {
                        Text(
                            text = "Could not read this one. It looks like a scan, and I only read real text. Try another PDF.",
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            color = PmugTokens.Danger,
                            lineHeight = 16.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Buttons: Add to shelf & Not now
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.pmugPressScale(onClick = onDismiss)
                ) {
                    Text(
                        text = "Not now",
                        color = PmugTokens.MutedInk,
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Button(
                    onClick = { onConfirm(title.ifBlank { preparedPdf.title }) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PmugTokens.Accent,
                        contentColor = PmugTokens.TextOnAccent
                    ),
                    shape = PmugTokens.ButtonShape,
                    modifier = Modifier.pmugPressScale(onClick = { onConfirm(title.ifBlank { preparedPdf.title }) })
                ) {
                    Text(
                        text = "Add to shelf",
                        fontWeight = FontWeight.Bold,
                        color = PmugTokens.TextOnAccent
                    )
                }
            }
        }
    }
}
