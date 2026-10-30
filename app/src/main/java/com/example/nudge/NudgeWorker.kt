package com.example.nudge

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.data.PmugDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class NudgeWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val WORK_NAME = "pmug_periodic_nudge_work"

        fun schedule(context: Context) {
            val workRequest = PeriodicWorkRequestBuilder<NudgeWorker>(
                30, TimeUnit.MINUTES,
                10, TimeUnit.MINUTES // Flex window
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = NudgePreferences(applicationContext)
        val now = System.currentTimeMillis()

        // Check if snoozed
        if (now < prefs.snoozeUntilTime) {
            logDebug(prefs, "Snoozed until ${formatTime(prefs.snoozeUntilTime)}")
            return@withContext Result.success()
        }

        // Daily counter reset check
        val todayStr = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date(now))
        if (prefs.nudgesTodayDate != todayStr) {
            prefs.nudgesTodayDate = todayStr
            prefs.nudgesSentToday = 0
        }

        // Fetch most recently read unfinished document
        val db = PmugDatabase.getInstance(applicationContext)
        val dao = db.pmugDao()

        val policy = NudgePolicy()
        val hasUsage = UsageSignals.hasUsagePermission(applicationContext)
        val lastUnlock = prefs.debugLastUnlockOverride ?: UsageSignals.getLastUnlockTime(applicationContext)
        val lastActivity = prefs.debugLastActivityOverride ?: prefs.lastPmugActivityTime

        val inputs = NudgeInputs(
            currentTimeMillis = now,
            lastPmugActivityMillis = lastActivity,
            lastUnlockMillis = lastUnlock,
            nudgesSentToday = prefs.nudgesSentToday,
            lastNudgeTimeMillis = prefs.lastNudgeTime,
            consecutiveDismissals = prefs.consecutiveDismissals,
            isAudioPlaying = false, // Background worker only runs when not reading actively
            quietHoursStartHour = prefs.quietHoursStartHour,
            quietHoursEndHour = prefs.quietHoursEndHour,
            minHoursSincePmugActivity = prefs.minHoursSinceActivity.toDouble(),
            hasUsagePermission = hasUsage
        )

        val decision = policy.evaluate(inputs)
        when (decision) {
            is NudgeDecision.Send -> {
                // Find last read document and progress
                // Let's get the first active document
                val docs = dao.getDocumentById("") // query directly
                // To fetch the most recently read document:
                // We can query database
                val allDocs = db.openHelper.readableDatabase.query("SELECT id, pages FROM documents WHERE finished = 0 ORDER BY lastReadAt DESC LIMIT 1")
                var docId: String? = null
                var docPages = 1
                if (allDocs.moveToFirst()) {
                    docId = allDocs.getString(0)
                    docPages = allDocs.getInt(1).coerceAtLeast(1)
                }
                allDocs.close()

                if (docId != null) {
                    val progress = dao.getProgress(docId)
                    val currentPage = (progress?.page ?: 1).coerceAtLeast(1)

                    val (copy, nextIdx) = policy.formatNotificationCopy(currentPage, prefs.lastVariantIndex)
                    prefs.lastVariantIndex = nextIdx
                    prefs.lastNudgeTime = now
                    prefs.nudgesSentToday += 1

                    NudgeNotifier.showNudgeNotification(
                        context = applicationContext,
                        documentId = docId,
                        page = currentPage,
                        messageText = copy
                    )
                    logDebug(prefs, "Nudge sent: \"$copy\" (reason: ${decision.reason})")
                } else {
                    logDebug(prefs, "Eligible for nudge, but no unfinished documents on shelf")
                }
            }
            is NudgeDecision.Skip -> {
                logDebug(prefs, "Nudge skipped: ${decision.reason}")
            }
        }

        Result.success()
    }

    private fun logDebug(prefs: NudgePreferences, message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val entry = "[$time] $message\n"
        val existing = prefs.debugLog.take(2000)
        prefs.debugLog = entry + existing
    }

    private fun formatTime(millis: Long): String {
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))
    }
}
