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

    /**
     * The foreground watcher's notification: silent, so muting it costs nothing else — the service runs on with the channel
     * off. Low importance, not minimum: on a minimum channel Android raises a foreground service's notification back to
     * low and shows its own "running in the background" notice. Only the user can minimise it, from the channel's page
     * (the cards' "Hide Focus watch").
     */
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

    /**
     * "6 pick-ups in 10 minutes. Put the phone away for 20?": high importance so it peeks over the app, the chime's short
     * vibration and no sound — a question, not an alarm. Fixed once created, like every channel.
     */
    const val CHANNEL_LOOP_CHECK = "loop_check"

    /**
     * "Phone away: 20 min is up": default importance **with the default sound**, because the phone is put away when it
     * comes. Its own channel, not the time chime's: the chime is the in-app clock.
     */
    const val CHANNEL_PHONE_AWAY = "phone_away"

    /**
     * "Planned: Thesis until 11:30 · Instagram is open.": high importance so it peeks over the app, the chime's short
     * vibration and no sound, like the loop check — a question, not an alarm. Fixed once created.
     */
    const val CHANNEL_DRIFT = "drift"

    /**
     * "Thesis starts now · Start the timer?": high importance so it peeks over the app, the chime's short vibration and no
     * sound — a question, not an alarm; the buzz is what tells a phone in a pocket the block has begun. Fixed once created.
     */
    const val CHANNEL_START_RAMP = "start_ramp"

    /** One id per thing shown, so a repost replaces the notification rather than stacking another. */
    const val ID_SYNC_BLOCKED = 1
    const val ID_FOREGROUND_WATCH = 2
    const val ID_EVENING_REVIEW = 3
    const val ID_BREAK = 4
    const val ID_TIME_CHIME = 5
    const val ID_LOOP_CHECK = 6
    const val ID_PHONE_AWAY = 7
    const val ID_DRIFT = 8
    const val ID_START_RAMP = 9

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
                    .build(),
                NotificationChannelCompat.Builder(CHANNEL_LOOP_CHECK, NotificationManagerCompat.IMPORTANCE_HIGH)
                    .setName(context.getString(R.string.channel_loop_check))
                    .setDescription(context.getString(R.string.channel_loop_check_description))
                    .setSound(null, null)
                    .setVibrationEnabled(true)
                    .setVibrationPattern(CHIME_VIBRATION)
                    .setShowBadge(false)
                    .build(),
                // The default sound comes with the default importance.
                NotificationChannelCompat.Builder(CHANNEL_PHONE_AWAY, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                    .setName(context.getString(R.string.channel_phone_away))
                    .setDescription(context.getString(R.string.channel_phone_away_description))
                    .setShowBadge(false)
                    .build(),
                NotificationChannelCompat.Builder(CHANNEL_DRIFT, NotificationManagerCompat.IMPORTANCE_HIGH)
                    .setName(context.getString(R.string.channel_drift))
                    .setDescription(context.getString(R.string.channel_drift_description))
                    .setSound(null, null)
                    .setVibrationEnabled(true)
                    .setVibrationPattern(CHIME_VIBRATION)
                    .setShowBadge(false)
                    .build(),
                NotificationChannelCompat.Builder(CHANNEL_START_RAMP, NotificationManagerCompat.IMPORTANCE_HIGH)
                    .setName(context.getString(R.string.channel_start_ramp))
                    .setDescription(context.getString(R.string.channel_start_ramp_description))
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
