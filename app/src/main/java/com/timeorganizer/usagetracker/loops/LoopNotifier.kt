package com.timeorganizer.usagetracker.loops

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.timeorganizer.usagetracker.R
import com.timeorganizer.usagetracker.notify.Notifications

/**
 * The loop check's two notifications and the away timer's alarm.
 *
 * - **The check-in**: "6 pick-ups in 10 minutes · Want to put the phone away for 20?" with **Put it away**, **I'm fine**
 *   and **Not today**; a swipe counts as "I'm fine". One id, cancelled once its loop is over.
 * - **Phone away**: "20 min away is up", with the channel's sound. No buttons.
 * - **The alarm** is `setAndAllowWhileIdle`: inexact (it may come a little late in Doze, never early) and needs no
 *   permission, unlike an exact alarm ("Alarms & reminders" on Android 14). A reboot clears it, and the timer then ends
 *   silently ([LoopPolicy.AWAY_GRACE_MS]).
 */
object LoopNotifier {

    fun postCheckIn(context: Context, checkIn: LoopCheckIn, awayMinutes: Int) {
        val signal = checkIn.signal
        val title = when (signal.rule) {
            LoopRule.PICKUPS -> context.getString(
                R.string.notify_loop_title_pickups, signal.pickups, LoopDetector.PICKUP_WINDOW_MS / LoopDetector.MINUTE_MS
            )
            LoopRule.HOPPING -> context.getString(
                R.string.notify_loop_title_hopping, signal.apps, LoopDetector.HOP_WINDOW_MS / LoopDetector.MINUTE_MS
            )
        }
        val text = context.getString(R.string.notify_loop_text, awayMinutes)

        val notification = NotificationCompat.Builder(context, Notifications.CHANNEL_LOOP_CHECK)
            .setSmallIcon(R.drawable.ic_stat_loop)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, context.getString(R.string.notify_loop_away), action(context, LoopActionReceiver.ACTION_AWAY))
            .addAction(0, context.getString(R.string.notify_loop_fine), action(context, LoopActionReceiver.ACTION_FINE))
            .addAction(0, context.getString(R.string.notify_loop_not_today), action(context, LoopActionReceiver.ACTION_NOT_TODAY))
            .setDeleteIntent(action(context, LoopActionReceiver.ACTION_DISMISSED))
            .setAutoCancel(true)
            .build()

        Notifications.post(context, Notifications.ID_LOOP_CHECK, notification)
    }

    fun cancelCheckIn(context: Context) = Notifications.cancel(context, Notifications.ID_LOOP_CHECK)

    fun postAwayDone(context: Context, awayMinutes: Int) {
        val notification = NotificationCompat.Builder(context, Notifications.CHANNEL_PHONE_AWAY)
            .setSmallIcon(R.drawable.ic_stat_away)
            .setContentTitle(context.getString(R.string.notify_away_title, awayMinutes))
            .setContentText(context.getString(R.string.notify_away_text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .build()

        Notifications.post(context, Notifications.ID_PHONE_AWAY, notification)
    }

    fun scheduleAwayAlarm(context: Context, atMs: Long) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, action(context, LoopActionReceiver.ACTION_AWAY_DONE))
    }

    fun cancelAwayAlarm(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(action(context, LoopActionReceiver.ACTION_AWAY_DONE))
    }

    private fun action(context: Context, action: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        Notifications.ID_LOOP_CHECK,
        Intent(context, LoopActionReceiver::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
}
