package com.timeorganizer.usagetracker.ramp

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import androidx.core.app.NotificationCompat
import com.timeorganizer.usagetracker.R
import com.timeorganizer.usagetracker.notify.Notifications
import java.util.Date

/**
 * The ramp: "Thesis starts now" (or "started 20 min ago") — "1 h 30 min, until 11:30. Start the timer?" — with **Start
 * now**, **In 2 min** (not on the repeat) and **Not this one**. Start now opens the web app, so it goes through
 * [StartRampActionActivity] (a notification's broadcast may not start an activity since Android 12); the rest and the swipe
 * go to [StartRampActionReceiver]. One id: a ramp for the next block replaces the last.
 *
 * **The wake** is `setAndAllowWhileIdle`, as the loop check's away timer: inexact (it may come a little late in Doze, never
 * early) and no permission, unlike an exact alarm. One alarm, re-planned after every evaluation; a reboot clears it, and
 * the next sync or pickup catches up with the late form.
 */
object StartRampNotifier {

    /** The intent extra every action carries: the block the ramp is about, so a press acts on its own block. */
    const val EXTRA_BLOCK_ID = "blockId"

    fun post(context: Context, nudge: StartRampNudge) {
        val block = nudge.block
        val lateMinutes = (nudge.lateByMs / StartRampPolicy.MINUTE_MS).toInt()
        val named = block.activityName.isNotBlank()
        val title = when {
            lateMinutes < 1 && named -> context.getString(R.string.notify_ramp_title_now, block.activityName)
            lateMinutes < 1 -> context.getString(R.string.notify_ramp_title_now_unnamed)
            named -> context.resources.getQuantityString(R.plurals.notify_ramp_title_late, lateMinutes, block.activityName, lateMinutes)
            else -> context.resources.getQuantityString(R.plurals.notify_ramp_title_late_unnamed, lateMinutes, lateMinutes)
        }
        val until = DateFormat.getTimeFormat(context).format(Date(block.endMs))
        val text = context.getString(R.string.notify_ramp_text, length(context, block.endMs - block.startMs), until)

        val builder = NotificationCompat.Builder(context, Notifications.CHANNEL_START_RAMP)
            .setSmallIcon(R.drawable.ic_stat_start_ramp)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, context.getString(R.string.notify_ramp_start), startNow(context, block.plannerTaskId))
        if (nudge.canDefer) {
            builder.addAction(0, context.getString(R.string.notify_ramp_later), broadcast(context, StartRampActionReceiver.ACTION_LATER, block.plannerTaskId))
        }
        val notification = builder
            .addAction(0, context.getString(R.string.notify_ramp_not_this_one), broadcast(context, StartRampActionReceiver.ACTION_NOT_THIS_ONE, block.plannerTaskId))
            .setDeleteIntent(broadcast(context, StartRampActionReceiver.ACTION_DISMISSED, block.plannerTaskId))
            .setAutoCancel(true)
            .setTimeoutAfter((block.endMs - System.currentTimeMillis()).coerceAtLeast(StartRampPolicy.MINUTE_MS))
            .build()

        Notifications.post(context, Notifications.ID_START_RAMP, notification)
    }

    fun cancel(context: Context) = Notifications.cancel(context, Notifications.ID_START_RAMP)

    /** Replaces the planned wake; `null` cancels it. */
    fun scheduleWake(context: Context, atMs: Long?) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val wake = broadcast(context, StartRampActionReceiver.ACTION_WAKE, blockId = 0L)
        if (atMs == null) alarms.cancel(wake) else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, wake)
    }

    /** "1 h 30 min", "45 min", "2 h". */
    private fun length(context: Context, lengthMs: Long): String {
        val minutes = (lengthMs / StartRampPolicy.MINUTE_MS).toInt().coerceAtLeast(1)
        val hours = minutes / 60
        val rest = minutes % 60
        return when {
            hours == 0 -> context.getString(R.string.notify_ramp_length_minutes, rest)
            rest == 0 -> context.getString(R.string.notify_ramp_length_hours, hours)
            else -> context.getString(R.string.notify_ramp_length_hours_minutes, hours, rest)
        }
    }

    /** One request code per action, so their extras never overwrite each other's. */
    private fun broadcast(context: Context, action: String, blockId: Long): PendingIntent = PendingIntent.getBroadcast(
        context,
        Notifications.ID_START_RAMP * 10 + StartRampActionReceiver.ACTIONS.indexOf(action),
        Intent(context, StartRampActionReceiver::class.java).setAction(action).putExtra(EXTRA_BLOCK_ID, blockId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun startNow(context: Context, blockId: Long): PendingIntent = PendingIntent.getActivity(
        context,
        Notifications.ID_START_RAMP,
        Intent(context, StartRampActionActivity::class.java)
            .setAction(StartRampActionActivity.ACTION_START)
            .putExtra(EXTRA_BLOCK_ID, blockId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
}
