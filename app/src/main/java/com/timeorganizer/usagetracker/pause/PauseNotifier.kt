package com.timeorganizer.usagetracker.pause

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.core.app.NotificationCompat
import com.timeorganizer.usagetracker.MainActivity
import com.timeorganizer.usagetracker.R
import com.timeorganizer.usagetracker.notify.Notifications
import kotlinx.coroutines.CancellationException
import java.util.Date

/**
 * "Tracking paused until 15:00 · Resume" on the silent `pause` channel (decision 17-4). Swipeable, not ongoing (the owner
 * found the watcher's ongoing one annoying). A timed pause's notification clears itself at the pause's end
 * (`setTimeoutAfter`); "until I resume" is shown again once a day ([PausePolicy.notice]). Resume goes to
 * [PauseActionReceiver]; a tap opens the status screen.
 */
object PauseNotifier {

    /** Decides on [PausePolicy.notice] and acts on it. Called on start, on resume, and after every sync run. */
    suspend fun refresh(context: Context, nowMs: Long = System.currentTimeMillis()) {
        val prefs = PausePreferences(context)
        val active = Pauses.active(context, nowMs)
        when (PausePolicy.notice(active, prefs.notice, nowMs)) {
            NoticeStep.POST -> {
                post(context, active!!, nowMs)
                prefs.notice = PauseNotice(active.startMs, nowMs)
            }
            NoticeStep.CANCEL -> {
                Notifications.cancel(context, Notifications.ID_PAUSE)
                prefs.notice = PauseNotice.NONE
            }
            NoticeStep.NOTHING -> Unit
        }
    }

    /** After every sync run: "until I resume" shown again once a day, an ended pause's notification taken away. Never throws. */
    suspend fun afterSync(context: Context) {
        try {
            refresh(context.applicationContext)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The next run tries again.
        }
    }

    /** "until 15:00", "until tomorrow 07:00", "until you resume": the status card says it the same way. */
    fun until(context: Context, pause: PauseRange): String {
        if (pause.untilResumed) return context.getString(R.string.pause_until_resumed)
        val time = DateFormat.getTimeFormat(context).format(Date(pause.endMs))
        // Every timed pause ends within a day, so a time that isn't today's is tomorrow's.
        return if (DateUtils.isToday(pause.endMs)) {
            context.getString(R.string.pause_until_time, time)
        } else {
            context.getString(R.string.pause_until_tomorrow, time)
        }
    }

    private fun post(context: Context, pause: PauseRange, nowMs: Long) {
        val builder = NotificationCompat.Builder(context, Notifications.CHANNEL_PAUSE)
            .setSmallIcon(R.drawable.ic_stat_pause)
            .setContentTitle(context.getString(R.string.notify_pause_title, until(context, pause)))
            .setContentText(context.getString(R.string.notify_pause_text))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(context))
            .addAction(0, context.getString(R.string.btn_pause_resume), resume(context))
        if (!pause.untilResumed) builder.setTimeoutAfter((pause.endMs - nowMs).coerceAtLeast(1L))
        Notifications.post(context, Notifications.ID_PAUSE, builder.build())
    }

    private fun resume(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        Notifications.ID_PAUSE,
        Intent(context, PauseActionReceiver::class.java).setAction(PauseActionReceiver.ACTION_RESUME),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        Notifications.ID_PAUSE,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
}
