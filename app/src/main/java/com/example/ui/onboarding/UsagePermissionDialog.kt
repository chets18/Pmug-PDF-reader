package com.example.ui.onboarding

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.core.theme.PmugMotionSpec.pmugPressScale
import com.example.core.theme.PmugTokens
import com.example.mascot.PmugMood
import com.example.mascot.PmugMug

@Composable
fun UsagePermissionDialog(
    onDismiss: () -> Unit,
    onPermissionGranted: () -> Unit
) {
    val context = LocalContext.current

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = PmugTokens.CardShape,
            color = PmugTokens.Surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    PmugMug(mood = PmugMood.IDLE, size = 48.dp)
                    Text(
                        text = "One thing I need to peek at.",
                        style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                        color = PmugTokens.Ink
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "Pmug only looks at when you last unlocked your phone, so it nudges you when you are awake.",
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                    color = PmugTokens.MutedInk,
                    lineHeight = 22.sp
                )

                Spacer(modifier = Modifier.height(24.dp))

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
                            text = "Skip",
                            color = PmugTokens.MutedInk,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = {
                            try {
                                val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(intent)
                            } catch (_: Exception) {}
                            onPermissionGranted()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PmugTokens.Accent,
                            contentColor = PmugTokens.TextOnAccent
                        ),
                        shape = PmugTokens.ButtonShape,
                        modifier = Modifier.pmugPressScale(onClick = {
                            try {
                                val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(intent)
                            } catch (_: Exception) {}
                            onPermissionGranted()
                        })
                    ) {
                        Text(
                            text = "Open settings",
                            fontWeight = FontWeight.Bold,
                            color = PmugTokens.TextOnAccent
                        )
                    }
                }
            }
        }
    }
}
