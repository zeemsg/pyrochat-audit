package com.pyrochat.app.push

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.pyrochat.app.MainActivity
import com.pyrochat.app.R

/**
 * The zero-content notification, information-identical to the relay's APNs payload
 * (`{"aps":{"alert":{"loc-key":"INCOMING_TRANSMISSION"},"sound":"default"}}`): title
 * "incoming transmission", no text, no sender, no thread, default sound + vibration on the
 * `transmissions` channel (importance HIGH — the channel owns the sound on Android), tap opens
 * the app. One fixed id, so repeated wakes collapse into one entry. Nothing is written to disk.
 */
object WakeNotifier {
    private const val TAG = "WakeNotifier"
    const val CHANNEL_ID = "transmissions"
    const val NOTIFICATION_ID = 1

    /** Idempotent; created at app start so a cold-process wake can post immediately. */
    fun ensureChannel(context: Context): NotificationChannel {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.getNotificationChannel(CHANNEL_ID)?.let { return it }
        val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.push_channel_name), NotificationManager.IMPORTANCE_HIGH).apply {
            description = context.getString(R.string.push_channel_description)
            setSound(
                Settings.System.DEFAULT_NOTIFICATION_URI,
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
            )
            enableVibration(true)
            setShowBadge(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC // there is nothing to hide
        }
        nm.createNotificationChannel(channel)
        return channel
    }

    /**
     * Post (or replace) THE wake notification. [alert] false = silent refresh of an entry that is
     * already showing (badge update); true = alert, even when replacing an older entry.
     */
    fun post(context: Context, badge: Int, alert: Boolean = true) {
        ensureChannel(context)
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_wake)
            .setContentTitle(context.getString(R.string.push_incoming_transmission))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setOnlyAlertOnce(!alert)
            .setNumber(badge)
            .setContentIntent(openApp(context))
            .build()
        // POST_NOTIFICATIONS may be denied: the OS drops it (Android has no quiet delivery) — never crash
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, n) }
            .onFailure { Log.w(TAG, "notify refused: ${it.message}") }
    }

    fun isShowing(context: Context): Boolean = runCatching {
        context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == NOTIFICATION_ID }
    }.getOrDefault(false)

    /** iOS `removeAllDeliveredNotifications` + badge 0 (the number lives on the entry). */
    fun clearAll(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancelAll() }
    }

    /** iOS `setBadgeCount(totalUnread)` on scene transitions: refresh the showing entry silently. */
    fun syncBadge(context: Context, number: Int) {
        if (isShowing(context)) post(context, number, alert = false)
    }

    /** Honest state for the `[cfg]` footnote: app-level permission AND the channel not muted. */
    fun notificationsAllowed(context: Context): Boolean {
        val compat = NotificationManagerCompat.from(context)
        if (!compat.areNotificationsEnabled()) return false
        val channel = compat.getNotificationChannel(CHANNEL_ID) ?: return true
        return channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun openApp(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}
