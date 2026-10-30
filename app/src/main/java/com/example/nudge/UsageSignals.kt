package com.example.nudge

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process

object UsageSignals {

    fun hasUsagePermission(context: Context): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Reads when the phone was last unlocked or screen turned on using UsageStatsManager.queryEvents.
     * Looks specifically for KEYGUARD_HIDDEN (event 18) and SCREEN_INTERACTIVE (event 15).
     */
    fun getLastUnlockTime(context: Context): Long? {
        if (!hasUsagePermission(context)) return null

        return try {
            val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return null

            val endTime = System.currentTimeMillis()
            val startTime = endTime - (12 * 3600 * 1000L) // Look back 12 hours

            val events = usageStatsManager.queryEvents(startTime, endTime)
            val event = UsageEvents.Event()
            var lastUnlockTime = 0L

            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                // KEYGUARD_HIDDEN = 18, SCREEN_INTERACTIVE = 15
                if (event.eventType == 18 || event.eventType == 15) {
                    if (event.timeStamp > lastUnlockTime) {
                        lastUnlockTime = event.timeStamp
                    }
                }
            }

            if (lastUnlockTime > 0) lastUnlockTime else null
        } catch (_: Exception) {
            null
        }
    }
}
