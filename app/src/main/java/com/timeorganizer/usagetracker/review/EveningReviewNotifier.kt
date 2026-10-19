package com.timeorganizer.usagetracker.review

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.timeorganizer.usagetracker.R
import com.timeorganizer.usagetracker.data.api.dto.UnmappedTodayResponse
import com.timeorganizer.usagetracker.notify.Notifications

/**
 * "1 h 40 m of phone time today isn't mapped" — with the busiest apps named, and a tap into [ReviewActivity]. The
 * notification is the question, not the answer: nothing is mapped until the person says so on the screen.
 */
object EveningReviewNotifier {

    fun post(context: Context, today: UnmappedTodayResponse) {
        val apps = today.apps.orEmpty()
        if (apps.isEmpty()) return

        val line = ReviewText.appLine(apps.map { it.appLabel to it.seconds })
        val text = if (line.more > 0) {
            context.getString(R.string.notify_review_text_more, line.listed, line.more)
        } else {
            line.listed
        }

        val notification = NotificationCompat.Builder(context, Notifications.CHANNEL_EVENING_REVIEW)
            .setSmallIcon(R.drawable.ic_stat_review)
            .setContentTitle(context.getString(R.string.notify_review_title, ReviewText.duration(today.unmappedSeconds)))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    Notifications.ID_EVENING_REVIEW,
                    ReviewActivity.intent(context),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .setAutoCancel(true)
            .build()

        Notifications.post(context, Notifications.ID_EVENING_REVIEW, notification)
    }
}
