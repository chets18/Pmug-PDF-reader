package com.example.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.core.theme.PmugTokens
import com.example.domain.tts.PiperVoice
import com.example.domain.tts.PiperVoiceCatalog
import com.example.domain.tts.HybridTtsEngine

@Composable
fun VoiceSelectionSheet(
    ttsEngine: HybridTtsEngine,
    currentVoiceId: String,
    onSelectVoice: (String) -> Unit,
    onDownloadVoice: (PiperVoice) -> Unit,
    onDeleteVoice: (String) -> Unit,
    onPreviewVoice: (PiperVoice) -> Unit,
    downloadingVoiceId: String?,
    downloadProgress: Int,
    onDismiss: () -> Unit
) {
    var genderFilter by remember { mutableStateOf("All") } // "All", "Female", "Male"
    var showLicenseDialogFor by remember { mutableStateOf<PiperVoice?>(null) }

    val filteredVoices = PiperVoiceCatalog.voices.filter { voice ->
        when (genderFilter) {
            "Female" -> voice.gender.equals("female", ignoreCase = true)
            "Male" -> voice.gender.equals("male", ignoreCase = true)
            else -> true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .navigationBarsPadding()
            .padding(bottom = 24.dp)
    ) {
        Text(
            text = "Piper Voice Library",
            style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = PmugTokens.Ink
        )
        Text(
            text = "Choose among natural offline neural voices. Downloaded on demand, shared pronunciation data.",
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            color = PmugTokens.MutedInk
        )
        Spacer(modifier = Modifier.height(12.dp))

        // Gender Filter Chips
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("All", "Female", "Male").forEach { filter ->
                FilterChip(
                    selected = genderFilter == filter,
                    onClick = { genderFilter = filter },
                    label = { Text(filter) }
                )
            }
        }
        Spacer(modifier = Modifier.height(14.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().height(360.dp)
        ) {
            items(filteredVoices) { voice ->
                val isSelected = voice.id == currentVoiceId
                val isDownloading = downloadingVoiceId == voice.id

                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = if (isSelected) PmugTokens.SurfaceSubtle else PmugTokens.Surface,
                    border = if (isSelected) androidx.compose.foundation.BorderStroke(2.dp, PmugTokens.Accent) else null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = voice.displayName,
                                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = PmugTokens.Ink
                                )
                                Text(
                                    text = "Accent: ${voice.accent} · Quality: ${voice.quality} · Size: ~${voice.expectedDownloadSize / (1024*1024)}MB",
                                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                    color = PmugTokens.MutedInk
                                )
                            }
                            IconButton(onClick = { showLicenseDialogFor = voice }) {
                                Icon(Icons.Default.Info, contentDescription = "License & Credit", tint = PmugTokens.MutedInk)
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        if (isDownloading) {
                            Column {
                                LinearProgressIndicator(
                                    progress = { downloadProgress / 100f },
                                    modifier = Modifier.fillMaxWidth(),
                                    color = PmugTokens.Accent
                                )
                                Text(
                                    text = "Downloading voice... $downloadProgress%",
                                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                    color = PmugTokens.Accent,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Button(
                                    onClick = {
                                        if (isSelected) {
                                            onSelectVoice(voice.id)
                                        } else {
                                            onDownloadVoice(voice)
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isSelected) PmugTokens.Accent else PmugTokens.SurfaceSubtle,
                                        contentColor = if (isSelected) PmugTokens.TextOnAccent else PmugTokens.Ink
                                    ),
                                    shape = PmugTokens.ButtonShape,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = if (isSelected) Icons.Default.Check else Icons.Default.Download,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(text = if (isSelected) "Active Voice" else "Download & Select")
                                }

                                IconButton(onClick = { onPreviewVoice(voice) }) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = "Preview voice", tint = PmugTokens.Accent)
                                }

                                IconButton(onClick = { onDeleteVoice(voice.id) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete voice", tint = PmugTokens.MutedInk)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    showLicenseDialogFor?.let { voice ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showLicenseDialogFor = null },
            title = { Text(voice.displayName) },
            text = {
                Column {
                    Text("License: ${voice.licenseName}", fontWeight = FontWeight.Bold)
                    Text("Credit / Dataset: ${voice.credit}", modifier = Modifier.padding(top = 8.dp))
                    Text("URL: ${voice.licenseUrl}", style = androidx.compose.material3.MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = {
                TextButton(onClick = { showLicenseDialogFor = null }) { Text("Close") }
            }
        )
    }
}
