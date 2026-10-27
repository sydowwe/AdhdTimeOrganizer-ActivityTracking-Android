package com.timeorganizer.usagetracker.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.timeorganizer.usagetracker.R

/**
 * The app's notification channels and the one check every poster makes.
 *
 * **One channel per purpose**, so someone can mute nudges without losing the sync warnings; a feature that
 * notifies adds its own channel to [createChannels] and its own id below. `POST_NOTIFICATIONS` (API 33+) is
 * asked for only from the settings screen, never at launch.
 */
object Notifications {

    const val CHANNEL_SYNC_PROBLEMS = "sync_problems"

    /** The foreground watcher's ongoing notification: silent, so muting it costs nothing else. */
    const val CHANNEL_FOREGROUND_WATCH = "foreground_watch"

    /** The evening question about unmapped phone time: once a day, muted separately from the sync warnings. */
    const val CHANNEL_EVENING_REVIEW = "evening_review"

    /** "Break's been 25 m": high importance, so it peeks over the app the break is being spent in. */
    const val CHANNEL_BREAKS = "breaks"

    /**
     * "15 m on Reddit": high importance so it peeks over the app, a short vibration and no sound. A channel's vibration is
     * fixed once created and then the user's; they turn it off in the channel's settings, which the status card opens.
     */
    const val CHANNEL_TIME_CHIME = "time_chime"

    /** One id per thing shown, so a repost replaces the notification rather than stacking another. */
    const val ID_SYNC_BLOCKED = 1
    const val ID_FOREGROUND_WATCH = 2
    const val ID_EVENING_REVIEW = 3
    const val ID_BREAK = 4
    const val ID_TIME_CHIME = 5

    /** Two short taps. */
    private val CHIME_VIBRATION = longArrayOf(0L, 120L, 100L, 120L)

    /** Idempotent: creating an existing channel only updates its name and description. */
    fun createChannels(context: Context) {
        NotificationManagerCompat.from(context).createNotificationChannelsCompat(
            listOf(
                NotificationChannelCompat.Builder(CHANNEL_SYNC_PROBLEMS, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                    .setName(context.getString(R.string.channel_sync_problems))
                    .setDescription(context.getString(R.string.channel_sync_problems_description))
                    .build(),
                NotificationChannelCompat.Builder(CHANNEL_FOREGROUND_WATCH, NotificationManagerCompat.IMPORTANCE_LOW)
                    .setName(context.getString(R.string.channel_foreground_watch))
                    .setDescription(context.getString(R.string.channel_foreground_watch_description))
                    .setShowBadge(false)
                    .build(),
                NotificationChannelCompat.Builder(CHANNEL_EVENING_REVIEW, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                    .setName(context.getString(R.string.channel_evening_review))
                    .setDescription(context.getString(R.string.channel_evening_review_description))
                    .build(),
                NotificationChannelCompat.Builder(CHANNEL_BREAKS, NotificationManagerCompat.IMPORTANCE_HIGH)
                    .setName(context.getString(R.string.channel_breaks))
                    .setDescription(context.getString(R.string.channel_breaks_description))
                    .build(),
                NotificationChannelCompat.Builder(CHANNEL_TIME_CHIME, NotificationManagerCompat.IMPORTANCE_HIGH)
                    .setName(context.getString(R.string.channel_time_chime))
                    .setDescription(context.getString(R.string.channel_time_chime_description))
                    .setSound(null, null)
                    .setVibrationEnabled(true)
                    .setVibrationPattern(CHIME_VIBRATION)
                    .setShowBadge(false)
                    .build()
            )
        )
    }

    fun needsRuntimePermission(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    fun hasPermission(context: Context): Boolean =
        !needsRuntimePermission() ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** False without the permission, with the app's notifications turned off, or with [channelId] muted. */
    fun canPost(context: Context, channelId: String? = null): Boolean {
        val manager = NotificationManagerCompat.from(context)
        if (!hasPermission(context) || !manager.areNotificationsEnabled()) return false
        if (channelId == null) return true
        return manager.getNotificationChannelCompat(channelId)?.importance != NotificationManagerCompat.IMPORTANCE_NONE
    }

    @SuppressLint("MissingPermission") // canPost checks it
    fun post(context: Context, id: Int, notification: Notification) {
        if (!canPost(context, notification.channelId)) return
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    fun cancel(context: Context, id: Int) {
        NotificationManagerCompat.from(context).cancel(id)
    }

    /** Where to send someone whose notifications are off and the system will no longer ask. */
    fun openSettings(context: Context) {
        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        })
    }

    /** One channel's system page: its vibration, sound and whether it shows at all. */
    fun openChannelSettings(context: Context, channelId: String) {
        context.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        })
    }
}
