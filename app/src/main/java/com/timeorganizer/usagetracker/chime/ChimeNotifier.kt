package com.timeorganizer.usagetracker.chime

import android.content.Context
import androidx.core.app.NotificationCompat
import com.timeorganizer.usagetracker.R
import com.timeorganizer.usagetracker.notify.Notifications
import com.timeorganizer.usagetracker.review.ReviewText
import com.timeorganizer.usagetracker.util.InstalledApps

/**
 * "15 m on Reddit", peeking over the app for a few seconds and gone by itself. No buttons and no tap target: it is a
 * clock, not a question. One id, so a chime replaces the last one.
 */
object ChimeNotifier {

    /** Long enough to read, short enough not to sit over the app. */
    private const val SHOWN_MS = 6_000L

    fun post(context: Context, chime: Chime) {
        val apps = InstalledApps(context)
        val labels = chime.packages.map { apps.label(it) ?: it }
        val title = context.getString(R.string.notify_chime_title, ReviewText.duration(chime.inAppMs / 1000), labels.first())
        val text = if (labels.size > 1) {
            context.getString(R.string.notify_chime_text_with, labels.drop(1).joinToString(", "))
        } else {
            context.getString(R.string.notify_chime_text)
        }

        val notification = NotificationCompat.Builder(context, Notifications.CHANNEL_TIME_CHIME)
            .setSmallIcon(R.drawable.ic_stat_chime)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setTimeoutAfter(SHOWN_MS)
            .setAutoCancel(true)
            .build()

        Notifications.post(context, Notifications.ID_TIME_CHIME, notification)
    }

    fun cancel(context: Context) = Notifications.cancel(context, Notifications.ID_TIME_CHIME)
}
