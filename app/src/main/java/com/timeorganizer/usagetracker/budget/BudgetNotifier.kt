package com.timeorganizer.usagetracker.budget

import android.annotation.SuppressLint
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.timeorganizer.usagetracker.R
import com.timeorganizer.usagetracker.notify.Notifications
import com.timeorganizer.usagetracker.review.ReviewText

/**
 * The server's words, on the phone: "1 h of YouTube today · That's the daily limit you set." and "Nearing your daily limit
 * for YouTube · About 12 m left of the 1 h you set." Neutral — the user's own number back, never "exceeded". No buttons and
 * no tap target: there is nothing to decide (decisions 13-2, 13-3).
 *
 * One notification per budget (its id as the tag), so a crossing that reaches an activity's limit and its category's says
 * both, as the server would; the warning is replaced by the limit for the same budget.
 */
object BudgetNotifier {

    private const val TAG_PREFIX = "budget-"

    @SuppressLint("MissingPermission") // canPost checks it
    fun post(context: Context, due: BudgetDue) {
        if (!Notifications.canPost(context, Notifications.CHANNEL_BUDGET)) return
        val limit = ReviewText.duration(due.limitMs / 1000)
        val named = due.targetName.isNotBlank()
        val (title, text) = when (due.stage) {
            BudgetStage.REACHED -> Pair(
                if (named) context.getString(R.string.notify_budget_reached_title, limit, due.targetName)
                else context.getString(R.string.notify_budget_reached_title_unnamed, limit),
                context.getString(R.string.notify_budget_reached_text)
            )
            BudgetStage.APPROACHING -> Pair(
                if (named) context.getString(R.string.notify_budget_approaching_title, due.targetName)
                else context.getString(R.string.notify_budget_approaching_title_unnamed),
                // At least a minute: "0 m left" would read as the limit, which is the other notification.
                context.getString(
                    R.string.notify_budget_approaching_text,
                    ReviewText.duration(((due.limitMs - due.totalMs) / 1000).coerceAtLeast(60L)),
                    limit
                )
            )
        }

        val notification = NotificationCompat.Builder(context, Notifications.CHANNEL_BUDGET)
            .setSmallIcon(R.drawable.ic_stat_budget)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(TAG_PREFIX + due.budgetId, Notifications.ID_BUDGET, notification)
    }

    /** An account switch: what is showing was the previous person's. */
    fun cancelAll(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        manager.activeNotifications
            .filter { it.id == Notifications.ID_BUDGET && it.tag?.startsWith(TAG_PREFIX) == true }
            .forEach { manager.cancel(it.tag, it.id) }
    }
}
