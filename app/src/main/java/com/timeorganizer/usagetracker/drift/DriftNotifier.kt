package com.timeorganizer.usagetracker.drift

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import androidx.core.app.NotificationCompat
import com.timeorganizer.usagetracker.R
import com.timeorganizer.usagetracker.notify.Notifications
import com.timeorganizer.usagetracker.util.InstalledApps
import java.util.Date

/**
 * "Planned: Thesis until 11:30 · Instagram is open." with **Back to it**, **Switch plan** and **5 more minutes**; a swipe
 * counts as "Back to it". The first two leave the app in front (the home screen, the web planner), so they open
 * [DriftActionActivity] — a broadcast receiver may not start an activity from a notification since Android 12. The third
 * and the swipe go to [DriftActionReceiver]. One id: a second nudge replaces the first.
 */
object DriftNotifier {

    fun post(context: Context, nudge: DriftNudge) {
        val until = DateFormat.getTimeFormat(context).format(Date(nudge.block.endMs))
        val title = if (nudge.block.activityName.isBlank()) {
            context.getString(R.string.notify_drift_title_unnamed, until)
        } else {
            context.getString(R.string.notify_drift_title, nudge.block.activityName, until)
        }
        val app = InstalledApps(context).label(nudge.packageName) ?: nudge.packageName
        val text = context.getString(R.string.notify_drift_text, app)

        val notification = NotificationCompat.Builder(context, Notifications.CHANNEL_DRIFT)
            .setSmallIcon(R.drawable.ic_stat_drift)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, context.getString(R.string.notify_drift_back), activity(context, DriftActionActivity.ACTION_BACK))
            .addAction(0, context.getString(R.string.notify_drift_switch_plan), activity(context, DriftActionActivity.ACTION_SWITCH_PLAN))
            .addAction(0, context.getString(R.string.notify_drift_more), broadcast(context, DriftActionReceiver.ACTION_MORE))
            .setDeleteIntent(broadcast(context, DriftActionReceiver.ACTION_DISMISSED))
            .setAutoCancel(true)
            .build()

        Notifications.post(context, Notifications.ID_DRIFT, notification)
    }

    fun cancel(context: Context) = Notifications.cancel(context, Notifications.ID_DRIFT)

    private fun broadcast(context: Context, action: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        Notifications.ID_DRIFT,
        Intent(context, DriftActionReceiver::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun activity(context: Context, action: String): PendingIntent = PendingIntent.getActivity(
        context,
        Notifications.ID_DRIFT,
        Intent(context, DriftActionActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
}
