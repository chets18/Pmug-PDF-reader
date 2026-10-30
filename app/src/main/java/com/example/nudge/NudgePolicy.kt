package com.example.nudge

import java.util.Calendar

data class NudgeInputs(
    val currentTimeMillis: Long,
    val lastPmugActivityMillis: Long,
    val lastUnlockMillis: Long?, // null if Usage Access is not granted
    val nudgesSentToday: Int,
    val lastNudgeTimeMillis: Long,
    val consecutiveDismissals: Int = 0,
    val isAudioPlaying: Boolean = false,
    val quietHoursStartHour: Int = 23,
    val quietHoursEndHour: Int = 7,
    val minHoursSincePmugActivity: Double = 2.5,
    val maxMinutesSinceUnlock: Long = 45,
    val hasUsagePermission: Boolean = true
)

sealed class NudgeDecision {
    data class Send(val reason: String) : NudgeDecision()
    data class Skip(val reason: String) : NudgeDecision()
}

/**
 * Pure Kotlin Nudge Decision Engine.
 * Fully unit-testable with a fake clock and explicit inputs.
 */
class NudgePolicy {

    fun evaluate(inputs: NudgeInputs): NudgeDecision {
        val now = inputs.currentTimeMillis

        // Rule 5: Audio must NOT be currently playing
        if (inputs.isAudioPlaying) {
            return NudgeDecision.Skip("Audio is currently playing in Pmug")
        }

        // Rule 4: Outside quiet hours (default 23:00 to 07:00)
        val calendar = Calendar.getInstance().apply { timeInMillis = now }
        val currentHour = calendar.get(Calendar.HOUR_OF_DAY)
        val isQuietHour = if (inputs.quietHoursStartHour > inputs.quietHoursEndHour) {
            currentHour >= inputs.quietHoursStartHour || currentHour < inputs.quietHoursEndHour
        } else {
            currentHour in inputs.quietHoursStartHour until inputs.quietHoursEndHour
        }
        if (isQuietHour) {
            return NudgeDecision.Skip("Current time ($currentHour:00) is within quiet hours (${inputs.quietHoursStartHour}:00 - ${inputs.quietHoursEndHour}:00)")
        }

        // Rule 1: Now minus lastPmugActivity is at least minHoursSincePmugActivity (default 2.5h)
        val millisSinceActivity = now - inputs.lastPmugActivityMillis
        val minActivityMillis = (inputs.minHoursSincePmugActivity * 3600 * 1000).toLong()
        if (millisSinceActivity < minActivityMillis) {
            val minutesAgo = millisSinceActivity / (60 * 1000)
            return NudgeDecision.Skip("Pmug was active recently ($minutesAgo min ago, min threshold is ${inputs.minHoursSincePmugActivity}h)")
        }

        // Penalty for 2 consecutive dismissed nudges: wait 6 hours before next
        if (inputs.consecutiveDismissals >= 2) {
            val millisSinceLastNudge = now - inputs.lastNudgeTimeMillis
            val sixHoursMillis = 6 * 3600 * 1000L
            if (millisSinceLastNudge < sixHoursMillis) {
                return NudgeDecision.Skip("2 consecutive nudges were dismissed; cooling down for 6 hours")
            }
        }

        // Rule 3: Daily cap and minimum 2 hours since last nudge
        val maxNudgesPerDay = if (inputs.hasUsagePermission) 3 else 2
        if (inputs.nudgesSentToday >= maxNudgesPerDay) {
            return NudgeDecision.Skip("Reached daily limit of $maxNudgesPerDay nudges today")
        }

        val millisSinceLastNudge = now - inputs.lastNudgeTimeMillis
        val twoHoursMillis = 2 * 3600 * 1000L
        if (inputs.lastNudgeTimeMillis > 0 && millisSinceLastNudge < twoHoursMillis) {
            return NudgeDecision.Skip("Less than 2 hours since last nudge")
        }

        // Rule 2: If Usage Access is available, now minus lastUnlock must be <= 45 min
        if (inputs.hasUsagePermission && inputs.lastUnlockMillis != null) {
            val millisSinceUnlock = now - inputs.lastUnlockMillis
            val maxUnlockMillis = inputs.maxMinutesSinceUnlock * 60 * 1000L
            if (millisSinceUnlock > maxUnlockMillis) {
                val minAgo = millisSinceUnlock / (60 * 1000)
                return NudgeDecision.Skip("Phone untouched for $minAgo min (assumed away or asleep)")
            }
        }

        return NudgeDecision.Send("All criteria met: user is awake, enough time has passed, within daily budget")
    }

    /**
     * Generates a dry, smug notification text naming the resume point.
     * Rotates through variants without repeating the same line twice in a row.
     */
    fun formatNotificationCopy(page: Int, lastVariantIndex: Int): Pair<String, Int> {
        val variants = listOf(
            "Page $page is still warm. Two minutes?",
            "Your PDF is waiting on page $page.",
            "Pick up where you stopped, page $page."
        )

        var nextIndex = (lastVariantIndex + 1) % variants.size
        if (nextIndex == lastVariantIndex) {
            nextIndex = (nextIndex + 1) % variants.size
        }

        return Pair(variants[nextIndex], nextIndex)
    }
}
