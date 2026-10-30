package com.example.nudge

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.MainActivity
import com.example.R

object NudgeNotifier {

    const val CHANNEL_ID = "pmug_reading_nudges"
    const val CHANNEL_NAME = "Reading nudges"
    const val NOTIFICATION_ID = 404

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Smart, quiet reminders to pick up your reading when you are awake"
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.createNotificationChannel(channel)
        }
    }

    fun showNudgeNotification(
        context: Context,
        documentId: String,
        page: Int,
        messageText: String
    ) {
        ensureChannel(context)

        // Tap opens reader directly
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("OPEN_DOCUMENT_ID", documentId)
            putExtra("AUTO_PLAY", false)
        }
        val tapPending = PendingIntent.getActivity(
            context,
            101,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action: Read now
        val readNowIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("OPEN_DOCUMENT_ID", documentId)
            putExtra("AUTO_PLAY", false)
        }
        val readNowPending = PendingIntent.getActivity(
            context,
            102,
            readNowIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action: Later (snooze 1 hour)
        val laterIntent = Intent(context, NudgeReceiver::class.java).apply {
            action = NudgeReceiver.ACTION_SNOOZE
        }
        val laterPending = PendingIntent.getBroadcast(
            context,
            103,
            laterIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Delete intent (track dismissal)
        val dismissIntent = Intent(context, NudgeReceiver::class.java).apply {
            action = NudgeReceiver.ACTION_DISMISSED
        }
        val dismissPending = PendingIntent.getBroadcast(
            context,
            104,
            dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Pmug")
            .setContentText(messageText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(messageText))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(tapPending)
            .setDeleteIntent(dismissPending)
            .setAutoCancel(true)
            .addAction(0, "Read now", readNowPending)
            .addAction(0, "Later", laterPending)

        try {
            val manager = NotificationManagerCompat.from(context)
            manager.notify(NOTIFICATION_ID, builder.build())
        } catch (_: SecurityException) {}
    }
}

class NudgeReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_SNOOZE = "com.example.pmug.ACTION_SNOOZE"
        const val ACTION_DISMISSED = "com.example.pmug.ACTION_DISMISSED"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val prefs = NudgePreferences(context)
        when (intent?.action) {
            ACTION_SNOOZE -> {
                // Snooze 1 hour
                prefs.snoozeUntilTime = System.currentTimeMillis() + (60 * 60 * 1000L)
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                manager?.cancel(NudgeNotifier.NOTIFICATION_ID)
            }
            ACTION_DISMISSED -> {
                prefs.consecutiveDismissals += 1
            }
        }
    }
}
