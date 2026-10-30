package com.example.nudge

import android.content.Context
import android.content.SharedPreferences

class NudgePreferences(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("pmug_nudge_prefs", Context.MODE_PRIVATE)

    var lastPmugActivityTime: Long
        get() = prefs.getLong("last_pmug_activity", System.currentTimeMillis())
        set(value) = prefs.edit().putLong("last_pmug_activity", value).apply()

    var lastNudgeTime: Long
        get() = prefs.getLong("last_nudge_time", 0L)
        set(value) = prefs.edit().putLong("last_nudge_time", value).apply()

    var nudgesTodayDate: String
        get() = prefs.getString("nudges_today_date", "") ?: ""
        set(value) = prefs.edit().putString("nudges_today_date", value).apply()

    var nudgesSentToday: Int
        get() = prefs.getInt("nudges_sent_today", 0)
        set(value) = prefs.edit().putInt("nudges_sent_today", value).apply()

    var consecutiveDismissals: Int
        get() = prefs.getInt("consecutive_dismissals", 0)
        set(value) = prefs.edit().putInt("consecutive_dismissals", value).apply()

    var lastVariantIndex: Int
        get() = prefs.getInt("last_variant_index", -1)
        set(value) = prefs.edit().putInt("last_variant_index", value).apply()

    var quietHoursStartHour: Int
        get() = prefs.getInt("quiet_hours_start", 23)
        set(value) = prefs.edit().putInt("quiet_hours_start", value).apply()

    var quietHoursEndHour: Int
        get() = prefs.getInt("quiet_hours_end", 7)
        set(value) = prefs.edit().putInt("quiet_hours_end", value).apply()

    var minHoursSinceActivity: Float
        get() = prefs.getFloat("min_hours_since_activity", 2.5f)
        set(value) = prefs.edit().putFloat("min_hours_since_activity", value).apply()

    var snoozeUntilTime: Long
        get() = prefs.getLong("snooze_until_time", 0L)
        set(value) = prefs.edit().putLong("snooze_until_time", value).apply()

    // Debug overrides
    var debugLastUnlockOverride: Long?
        get() {
            val v = prefs.getLong("debug_last_unlock", -1L)
            return if (v > 0) v else null
        }
        set(value) = prefs.edit().putLong("debug_last_unlock", value ?: -1L).apply()

    var debugLastActivityOverride: Long?
        get() {
            val v = prefs.getLong("debug_last_activity", -1L)
            return if (v > 0) v else null
        }
        set(value) = prefs.edit().putLong("debug_last_activity", value ?: -1L).apply()

    var debugLog: String
        get() = prefs.getString("debug_log", "No evaluation logs yet.") ?: ""
        set(value) = prefs.edit().putString("debug_log", value).apply()
}
