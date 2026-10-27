package com.timeorganizer.usagetracker.watch

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.text.format.DateFormat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.timeorganizer.usagetracker.MainActivity
import com.timeorganizer.usagetracker.R
import com.timeorganizer.usagetracker.notify.Notifications
import com.timeorganizer.usagetracker.usagestats.SessionBuilder
import com.timeorganizer.usagetracker.usagestats.UsageStatsCollector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Date

/**
 * Runs [ForegroundTracker] over the usage log every [POLL_INTERVAL_MS] while the screen is on and some reason
 * in [ForegroundWatcher] is active; stops itself when none is left.
 *
 * - Type `specialUse` (Android 14+): no other type fits. `dataSync` is wrong and capped at 6 h a day on
 *   Android 15; `shortService` ends after 3 minutes.
 * - **Screen off stops the polling.** Nothing is in front then, and the next read after unlock carries the whole
 *   dark stretch from the log, so no change is lost.
 * - Each read starts exactly where the last one ended and stops [SETTLE_MS] short of now: a repeated event would
 *   keep an app open forever, and the margin lets an event logged a moment late land in the next read.
 */
class ForegroundWatchService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val screenOn = MutableStateFlow(true)
    private var watching = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            screenOn.value = intent.action != Intent.ACTION_SCREEN_OFF
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val now = System.currentTimeMillis()
        val reasons = ForegroundWatcher.reasonsFlow(this).value
        // startForeground first, always: a service started this way that stops without it crashes the app.
        if (!goForeground(reasons.endsAtMs(now)) || !reasons.isActive(now)) {
            finish()
            return START_NOT_STICKY
        }
        if (!watching) begin()
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        if (watching) unregisterReceiver(screenReceiver)
        ForegroundWatcher.running = false
        ForegroundWatcher.inFront = emptySet()
        super.onDestroy()
    }

    private fun begin() {
        watching = true
        ForegroundWatcher.running = true
        screenOn.value = (getSystemService(POWER_SERVICE) as PowerManager).isInteractive
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        scope.launch { followReasons() }
        scope.launch { poll() }
    }

    /** Keeps the notification's end time current and stops once the last reason has ended or been stopped. */
    private suspend fun followReasons() {
        // Across changes of the reasons, not per change: a moved end of one reason that leaves the shown line as it was
        // must not post it again — that would bring back a notification the user swiped away.
        var shownEndMs: Long? = null
        ForegroundWatcher.reasonsFlow(this).collectLatest { reasons ->
            while (true) {
                val now = System.currentTimeMillis()
                val endsAtMs = reasons.endsAtMs(now)
                if (endsAtMs == null) {
                    finish()
                    return@collectLatest
                }
                if (endsAtMs != shownEndMs) {
                    Notifications.post(this, Notifications.ID_FOREGROUND_WATCH, notification(endsAtMs))
                    shownEndMs = endsAtMs
                }
                // In deep sleep this may run late; the screen is off then, so nothing is polled meanwhile.
                delay(reasons.nextEndMs(now)!! - now)
            }
        }
    }

    private suspend fun poll() {
        val collector = UsageStatsCollector(this)
        val tracker = ForegroundTracker()
        var readToMs = System.currentTimeMillis() - SETTLE_MS
        publish(tracker, tracker.prime(collector.events(readToMs - PRIME_LOOKBACK_MS, readToMs)))

        suspend fun read() {
            val endMs = System.currentTimeMillis() - SETTLE_MS
            if (endMs <= readToMs) return // the clock moved backwards; read again once it has caught up
            publish(tracker, tracker.advance(collector.events(readToMs, endMs), endMs))
            readToMs = endMs
        }

        while (true) {
            if (!screenOn.value) {
                // One read once the screen-off is in the log and past the merge gap, so nothing looks in front
                // while the screen is dark; then wait for it to come back on.
                delay(SETTLE_MS + SessionBuilder.MERGE_GAP_MS + 100)
                read()
                screenOn.first { it }
            }
            delay(POLL_INTERVAL_MS)
            read()
        }
    }

    private fun publish(tracker: ForegroundTracker, changes: List<ForegroundChange>) {
        ForegroundWatcher.inFront = tracker.current()
        ForegroundWatcher.dispatch(changes)
    }

    private fun goForeground(endsAtMs: Long?): Boolean = try {
        val notification = notification(endsAtMs)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(Notifications.ID_FOREGROUND_WATCH, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(Notifications.ID_FOREGROUND_WATCH, notification)
        }
        true
    } catch (_: IllegalStateException) {
        // A sticky restart from the background that Android no longer allows.
        false
    }

    private fun finish() {
        // Before the service is gone, so a watch asked for meanwhile starts it again rather than relying on it.
        ForegroundWatcher.running = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(endsAtMs: Long?): Notification {
        val title = endsAtMs?.takeIf { it != ForegroundWatcher.INDEFINITELY }
            ?.let { getString(R.string.watch_notification_title_until, DateFormat.getTimeFormat(this).format(Date(it))) }
            ?: getString(R.string.watch_notification_title)
        val open = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

        return NotificationCompat.Builder(this, Notifications.CHANNEL_FOREGROUND_WATCH)
            .setSmallIcon(R.drawable.ic_stat_focus_watch)
            .setContentTitle(title)
            .setContentText(getString(R.string.watch_notification_text))
            // Not ongoing: since Android 13 a swipe hides it and the service runs on. It comes back only when its line
            // changes (a new end time), never for a reason watching with no end (the chime, the loop check).
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .build()
    }

    private companion object {
        /** Up to ~6 s to notice a switch, chosen 2026-10-19. See CLAUDE.md for the battery estimate. */
        const val POLL_INTERVAL_MS = 5_000L
        const val SETTLE_MS = 1_000L

        /** How far back the first read looks for the app already in front; the screen is never on that long. */
        const val PRIME_LOOKBACK_MS = 6 * 60 * 60 * 1000L
    }
}
