package com.example.ui.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.BuildConfig
import com.example.core.theme.PmugTokens
import com.example.domain.tts.PiperFeatureManager
import com.example.nudge.NudgeInputs
import com.example.nudge.NudgeNotifier
import com.example.nudge.NudgePolicy
import com.example.nudge.NudgePreferences
import com.example.nudge.UsageSignals
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { NudgePreferences(context) }
    val scope = rememberCoroutineScope()
    val piperManager = remember { PiperFeatureManager(context) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    var logText by remember { mutableStateOf(prefs.debugLog) }
    var lastUnlockMinAgo by remember { mutableStateOf("10") }
    var lastActivityHoursAgo by remember { mutableStateOf("3.0") }
    var testResultText by remember { mutableStateOf<String?>(null) }
    var piperTestRunning by remember { mutableStateOf(false) }
    var piperTestLines by remember { mutableStateOf<List<String>>(emptyList()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Nudge Debug Console",
                        style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = PmugTokens.Ink
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = PmugTokens.Ink
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PmugTokens.Ground)
            )
        },
        containerColor = PmugTokens.Ground
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (BuildConfig.DEBUG) {
                Card(
                    shape = PmugTokens.CardShape,
                    colors = CardDefaults.cardColors(containerColor = PmugTokens.Surface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Piper Diagnostics", fontWeight = FontWeight.Bold, color = PmugTokens.Ink)
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            enabled = !piperTestRunning,
                            onClick = {
                                piperTestRunning = true
                                piperTestLines = listOf("RUNNING — reading module and voice state…")
                                val failed = AtomicBoolean(false)
                                scope.launch {
                                    try {
                                        withContext(Dispatchers.IO) {
                                            piperManager.runSelfTest { stage, passed, detail ->
                                                if (!passed) failed.set(true)
                                                val line = "${if (passed) "PASS" else "FAIL"} — $stage: $detail"
                                                Log.i("PmugPiper", line)
                                                mainHandler.post {
                                                    piperTestLines = piperTestLines.filterNot { it.startsWith("RUNNING") } + line
                                                    if (!passed) piperTestRunning = false
                                                }
                                            }
                                        }
                                        if (!failed.get()) {
                                            piperTestLines = piperTestLines.filterNot { it.startsWith("RUNNING") } + "PASS — Self-test completed"
                                        }
                                    } catch (error: Throwable) {
                                        Log.e("PmugPiper", "Debug self-test crashed", error)
                                        piperTestLines = piperTestLines.filterNot { it.startsWith("RUNNING") } +
                                            "FAIL — Self-test runner: ${error.javaClass.name}: ${error.message}\n${error.stackTraceToString()}"
                                    } finally {
                                        piperTestRunning = false
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = PmugTokens.Accent,
                                contentColor = PmugTokens.TextOnAccent
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (piperTestRunning) "Running Piper self-test…" else "Piper self-test")
                        }
                        piperTestLines.forEach { line ->
                            Text(
                                text = line,
                                color = when {
                                    line.startsWith("FAIL") -> Color(0xFFB3261E)
                                    line.startsWith("PASS") -> PmugTokens.Accent
                                    else -> PmugTokens.MutedInk
                                },
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                }
            }

            // Signal overrides
            Card(
                shape = PmugTokens.CardShape,
                colors = CardDefaults.cardColors(containerColor = PmugTokens.Surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Simulate Signal Inputs",
                        style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = PmugTokens.Ink
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Phone last unlocked (simulated): $lastUnlockMinAgo min ago",
                        color = PmugTokens.MutedInk,
                        fontSize = 13.sp
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PresetButton("10m ago (awake)") { lastUnlockMinAgo = "10" }
                        PresetButton("4h ago (asleep)") { lastUnlockMinAgo = "240" }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "Pmug last read (simulated): $lastActivityHoursAgo h ago",
                        color = PmugTokens.MutedInk,
                        fontSize = 13.sp
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PresetButton("40m ago (recent)") { lastActivityHoursAgo = "0.6" }
                        PresetButton("3h ago (due)") { lastActivityHoursAgo = "3.0" }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = {
                            val now = System.currentTimeMillis()
                            val unlockMillis = now - (lastUnlockMinAgo.toLongOrNull() ?: 10L) * 60 * 1000L
                            val activityMillis = now - ((lastActivityHoursAgo.toFloatOrNull() ?: 3f) * 3600 * 1000L).toLong()

                            val policy = NudgePolicy()
                            val inputs = NudgeInputs(
                                currentTimeMillis = now,
                                lastPmugActivityMillis = activityMillis,
                                lastUnlockMillis = unlockMillis,
                                nudgesSentToday = prefs.nudgesSentToday,
                                lastNudgeTimeMillis = prefs.lastNudgeTime,
                                consecutiveDismissals = prefs.consecutiveDismissals,
                                isAudioPlaying = false,
                                quietHoursStartHour = prefs.quietHoursStartHour,
                                quietHoursEndHour = prefs.quietHoursEndHour,
                                minHoursSincePmugActivity = prefs.minHoursSinceActivity.toDouble(),
                                hasUsagePermission = true
                            )

                            val decision = policy.evaluate(inputs)
                            val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                            val verdict = when (decision) {
                                is com.example.nudge.NudgeDecision.Send -> {
                                    val (copy, _) = policy.formatNotificationCopy(12, prefs.lastVariantIndex)
                                    NudgeNotifier.showNudgeNotification(context, "debug_doc", 12, copy)
                                    "[$timeStr] SENT NUDGE -> \"$copy\" (${decision.reason})"
                                }
                                is com.example.nudge.NudgeDecision.Skip -> {
                                    "[$timeStr] SKIPPED -> ${decision.reason}"
                                }
                            }
                            testResultText = verdict
                            prefs.debugLog = verdict + "\n" + prefs.debugLog.take(2000)
                            logText = prefs.debugLog
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PmugTokens.Accent,
                            contentColor = PmugTokens.TextOnAccent
                        ),
                        shape = PmugTokens.ButtonShape,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Evaluate Nudge Policy Now", fontWeight = FontWeight.Bold)
                    }

                    testResultText?.let {
                        Spacer(modifier = Modifier.height(12.dp))
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = PmugTokens.Ground,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = it,
                                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                color = PmugTokens.Accent,
                                modifier = Modifier.padding(12.dp),
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }

            // Real status card
            Card(
                shape = PmugTokens.CardShape,
                colors = CardDefaults.cardColors(containerColor = PmugTokens.Surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Device Real Status",
                        style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = PmugTokens.Ink
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("Usage Access Permission: ${UsageSignals.hasUsagePermission(context)}", color = PmugTokens.MutedInk, fontSize = 13.sp)
                    Text("Real Last Unlock: ${UsageSignals.getLastUnlockTime(context)?.let { SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(it)) } ?: "Unavailable"}", color = PmugTokens.MutedInk, fontSize = 13.sp)
                    Text("Nudges Sent Today: ${prefs.nudgesSentToday} / 3", color = PmugTokens.MutedInk, fontSize = 13.sp)
                    Text("Quiet Hours: ${prefs.quietHoursStartHour}:00 to 0${prefs.quietHoursEndHour}:00", color = PmugTokens.MutedInk, fontSize = 13.sp)
                }
            }

            // Evaluation History Log
            Card(
                shape = PmugTokens.CardShape,
                colors = CardDefaults.cardColors(containerColor = PmugTokens.Surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Decision Log",
                            style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = PmugTokens.Ink
                        )
                        IconButton(onClick = {
                            prefs.debugLog = ""
                            logText = ""
                        }) {
                            Icon(imageVector = Icons.Default.Refresh, contentDescription = "Clear Log", tint = PmugTokens.MutedInk)
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = logText.ifBlank { "No logs recorded yet." },
                        color = PmugTokens.Ink,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 16.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun PresetButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = PmugTokens.SurfaceSubtle),
        shape = PmugTokens.ButtonShape
    ) {
        Text(text = label, fontSize = 12.sp, color = PmugTokens.Ink)
    }
}
