package com.timeorganizer.usagetracker.breaks

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import androidx.core.app.NotificationCompat
import com.timeorganizer.usagetracker.R
import com.timeorganizer.usagetracker.notify.Notifications
import com.timeorganizer.usagetracker.review.ReviewText
import java.util.Date

/**
 * "Break's been 25 m · Thesis is still open on your desktop." with **Back to it**, **5 more minutes** and **I'm done
 * for now**. One notification id, so a second nudge for the same break replaces the first. The actions go to
 * [BreakActionReceiver]; swiping it away counts as "Back to it".
 */
object BreakNotifier {

    fun post(context: Context, subject: BreakSubject, breakMs: Long) {
        val title = context.getString(R.string.notify_break_title, ReviewText.duration(breakMs / 1000))
        val text = when (subject.kind) {
            BreakSubject.Kind.DESK -> context.getString(R.string.notify_break_text_desk, subject.name)
            BreakSubject.Kind.PLANNED -> context.getString(
                R.string.notify_break_text_planned,
                subject.name,
                DateFormat.getTimeFormat(context).format(Date(subject.blockEndMs ?: 0L))
            )
        }

        val notification = NotificationCompat.Builder(context, Notifications.CHANNEL_BREAKS)
            .setSmallIcon(R.drawable.ic_stat_break)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, context.getString(R.string.notify_break_back), action(context, BreakActionReceiver.ACTION_BACK))
            .addAction(0, context.getString(R.string.notify_break_more), action(context, BreakActionReceiver.ACTION_MORE))
            .addAction(0, context.getString(R.string.notify_break_done), action(context, BreakActionReceiver.ACTION_DONE))
            .setDeleteIntent(action(context, BreakActionReceiver.ACTION_DISMISSED))
            .setAutoCancel(true)
            .build()

        Notifications.post(context, Notifications.ID_BREAK, notification)
    }

    fun cancel(context: Context) = Notifications.cancel(context, Notifications.ID_BREAK)

    private fun action(context: Context, action: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        Notifications.ID_BREAK,
        Intent(context, BreakActionReceiver::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
}
