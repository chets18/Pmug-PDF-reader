package com.example

import com.example.nudge.NudgeDecision
import com.example.nudge.NudgeInputs
import com.example.nudge.NudgePolicy
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class NudgePolicyTest {

    private val policy = NudgePolicy()

    // Helper to create a specific time of day on a fixed day
    private fun createTime(hour: Int, minute: Int): Long {
        return Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, 29, hour, minute, 0)
        }.timeInMillis
    }

    @Test
    fun testEligible_Unlocked10MinAgo_Pmug3hAgo_0Nudges_Send() {
        val now = createTime(14, 0) // 14:00 (midday, outside quiet hours)
        val lastUnlock = now - (10 * 60 * 1000L)
        val lastActivity = now - (3 * 3600 * 1000L)

        val inputs = NudgeInputs(
            currentTimeMillis = now,
            lastPmugActivityMillis = lastActivity,
            lastUnlockMillis = lastUnlock,
            nudgesSentToday = 0,
            lastNudgeTimeMillis = 0L,
            isAudioPlaying = false
        )

        val result = policy.evaluate(inputs)
        assertTrue(result is NudgeDecision.Send)
    }

    @Test
    fun testSkip_Unlocked4hAgo_AsleepOrAway() {
        val now = createTime(14, 0)
        val lastUnlock = now - (4 * 3600 * 1000L) // 4 hours ago
        val lastActivity = now - (3 * 3600 * 1000L)

        val inputs = NudgeInputs(
            currentTimeMillis = now,
            lastPmugActivityMillis = lastActivity,
            lastUnlockMillis = lastUnlock,
            nudgesSentToday = 0,
            lastNudgeTimeMillis = 0L,
            isAudioPlaying = false
        )

        val result = policy.evaluate(inputs)
        assertTrue(result is NudgeDecision.Skip)
        assertTrue((result as NudgeDecision.Skip).reason.contains("untouched"))
    }

    @Test
    fun testSkip_ReadRecently_40MinAgo() {
        val now = createTime(14, 0)
        val lastUnlock = now - (10 * 60 * 1000L)
        val lastActivity = now - (40 * 60 * 1000L) // 40 minutes ago

        val inputs = NudgeInputs(
            currentTimeMillis = now,
            lastPmugActivityMillis = lastActivity,
            lastUnlockMillis = lastUnlock,
            nudgesSentToday = 0,
            lastNudgeTimeMillis = 0L,
            isAudioPlaying = false
        )

        val result = policy.evaluate(inputs)
        assertTrue(result is NudgeDecision.Skip)
        assertTrue((result as NudgeDecision.Skip).reason.contains("active recently"))
    }

    @Test
    fun testSkip_DailyCapReached_3NudgesToday() {
        val now = createTime(14, 0)
        val lastUnlock = now - (10 * 60 * 1000L)
        val lastActivity = now - (3 * 3600 * 1000L)

        val inputs = NudgeInputs(
            currentTimeMillis = now,
            lastPmugActivityMillis = lastActivity,
            lastUnlockMillis = lastUnlock,
            nudgesSentToday = 3, // Already 3 today
            lastNudgeTimeMillis = now - (3 * 3600 * 1000L),
            isAudioPlaying = false
        )

        val result = policy.evaluate(inputs)
        assertTrue(result is NudgeDecision.Skip)
        assertTrue((result as NudgeDecision.Skip).reason.contains("daily limit"))
    }

    @Test
    fun testSkip_QuietHours_02AM() {
        val now = createTime(2, 0) // 02:00 AM (Quiet hours 23:00 - 07:00)
        val lastUnlock = now - (10 * 60 * 1000L)
        val lastActivity = now - (3 * 3600 * 1000L)

        val inputs = NudgeInputs(
            currentTimeMillis = now,
            lastPmugActivityMillis = lastActivity,
            lastUnlockMillis = lastUnlock,
            nudgesSentToday = 0,
            lastNudgeTimeMillis = 0L,
            isAudioPlaying = false,
            quietHoursStartHour = 23,
            quietHoursEndHour = 7
        )

        val result = policy.evaluate(inputs)
        assertTrue(result is NudgeDecision.Skip)
        assertTrue((result as NudgeDecision.Skip).reason.contains("quiet hours"))
    }

    @Test
    fun testSkip_AudioCurrentlyPlaying() {
        val now = createTime(14, 0)
        val lastUnlock = now - (10 * 60 * 1000L)
        val lastActivity = now - (3 * 3600 * 1000L)

        val inputs = NudgeInputs(
            currentTimeMillis = now,
            lastPmugActivityMillis = lastActivity,
            lastUnlockMillis = lastUnlock,
            nudgesSentToday = 0,
            lastNudgeTimeMillis = 0L,
            isAudioPlaying = true // Currently reading
        )

        val result = policy.evaluate(inputs)
        assertTrue(result is NudgeDecision.Skip)
        assertTrue((result as NudgeDecision.Skip).reason.contains("Audio is currently playing"))
    }
}
