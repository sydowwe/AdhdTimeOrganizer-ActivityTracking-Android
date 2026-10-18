package com.timeorganizer.usagetracker.notify

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.timeorganizer.usagetracker.LoginActivity
import com.timeorganizer.usagetracker.MainActivity
import com.timeorganizer.usagetracker.R
import com.timeorganizer.usagetracker.auth.SignOutReason
import com.timeorganizer.usagetracker.sync.SyncReport

/**
 * Carries out [SyncBlockedDecider] after every sync run: posts or clears the "sync blocked" notification and
 * keeps the decider's state. A tap opens the place that fixes the condition.
 */
class SyncBlockedNotifier(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("sync_blocked_notice", Context.MODE_PRIVATE)

    fun onReport(report: SyncReport, hasUsageAccess: Boolean, signOutReason: SignOutReason?, nowMs: Long) {
        val facts = SyncFacts(
            state = report.state,
            hasUsageAccess = hasUsageAccess,
            signOutReason = signOutReason,
            pending = report.pending,
            lastSentAtMs = report.lastSentAtMs,
            canPost = Notifications.canPost(appContext, Notifications.CHANNEL_SYNC_PROBLEMS)
        )
        val decision = SyncBlockedDecider.decide(facts, state, nowMs)
        when (val action = decision.action) {
            is NoticeAction.Post -> Notifications.post(appContext, Notifications.ID_SYNC_BLOCKED, build(action.condition, report.pending))
            NoticeAction.Clear -> Notifications.cancel(appContext, Notifications.ID_SYNC_BLOCKED)
            NoticeAction.None -> Unit
        }
        state = decision.next
    }

    private fun build(condition: BlockedCondition, pending: Int): Notification {
        val (title, text) = when (condition) {
            BlockedCondition.NO_USAGE_ACCESS ->
                appContext.getString(R.string.notify_no_usage_access_title) to appContext.getString(R.string.notify_no_usage_access_text)
            BlockedCondition.SESSION_ENDED ->
                appContext.getString(R.string.notify_session_ended_title) to appContext.getString(R.string.notify_session_ended_text)
            BlockedCondition.SIGNED_OUT ->
                appContext.getString(R.string.notify_signed_out_title) to appContext.getString(R.string.notify_signed_out_text)
            BlockedCondition.WAITING ->
                appContext.getString(R.string.notify_waiting_title) to appContext.getString(R.string.notify_waiting_text, pending)
        }
        val target = when (condition) {
            BlockedCondition.NO_USAGE_ACCESS -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            BlockedCondition.SESSION_ENDED, BlockedCondition.SIGNED_OUT -> Intent(appContext, LoginActivity::class.java)
            BlockedCondition.WAITING -> Intent(appContext, MainActivity::class.java)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

        return NotificationCompat.Builder(appContext, Notifications.CHANNEL_SYNC_PROBLEMS)
            .setSmallIcon(R.drawable.ic_stat_sync_problem)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setContentIntent(
                PendingIntent.getActivity(
                    appContext,
                    condition.ordinal,
                    target,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .setAutoCancel(true)
            .build()
    }

    private var state: BlockedNoticeState
        get() = BlockedNoticeState(
            shown = prefs.getString(KEY_SHOWN, null)?.let { name -> BlockedCondition.entries.firstOrNull { it.name == name } },
            shownAtMs = prefs.getLong(KEY_SHOWN_AT, 0L),
            waitingSinceMs = if (prefs.contains(KEY_WAITING_SINCE)) prefs.getLong(KEY_WAITING_SINCE, 0L) else null
        )
        set(value) {
            prefs.edit().apply {
                putString(KEY_SHOWN, value.shown?.name)
                putLong(KEY_SHOWN_AT, value.shownAtMs)
                value.waitingSinceMs?.let { putLong(KEY_WAITING_SINCE, it) } ?: remove(KEY_WAITING_SINCE)
            }.apply()
        }

    private companion object {
        const val KEY_SHOWN = "shown"
        const val KEY_SHOWN_AT = "shown_at"
        const val KEY_WAITING_SINCE = "waiting_since"
    }
}
