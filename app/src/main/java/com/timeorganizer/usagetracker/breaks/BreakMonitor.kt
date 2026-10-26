package com.timeorganizer.usagetracker.breaks

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.os.Build
import com.timeorganizer.usagetracker.data.local.AuthPreferences
import com.timeorganizer.usagetracker.notify.Notifications
import com.timeorganizer.usagetracker.now.NowClient
import com.timeorganizer.usagetracker.now.NowFacts
import com.timeorganizer.usagetracker.usagestats.UsageStatsCollector
import com.timeorganizer.usagetracker.watch.ForegroundChange
import com.timeorganizer.usagetracker.watch.ForegroundWatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Carries out [BreakPolicy]: brings it the server's `/now`, the usage log and the phone's own silence, posts or clears
 * the nudge, and keeps the foreground watcher armed while a break could start.
 *
 * **When it runs.** After every sync (every 15 minutes, from the worker), on a pickup the watcher sees (an app comes to
 * the front, at most every [PICKUP_GAP_MS]), on a button of the nudge, and again when the policy asks to look again.
 * That last one is an in-process delay, alive only while the process is — which, with the watcher's foreground service
 * running, is the whole time it matters. Killed anyway, the next sync picks up where the saved state left off.
 *
 * Off — and the watcher let go — when the switch is off, signed out, or the nudge has nothing to be posted with (no
 * notification permission, the app's notifications or the "Breaks" channel turned off). It never throws into its callers
 * and logs nothing: the subject is the user's own activity name.
 */
object BreakMonitor {

    /** The watcher's reason; see `ForegroundWatcher.watch`. */
    const val WATCH_REASON = "break"

    /** A pickup is looked at no more often than this: every app switch is a "came to the front". */
    private const val PICKUP_GAP_MS = 20_000L

    /** How far before the moment that matters the usage log is read, so a sitting already open then is seen whole. */
    private const val LOG_LOOKBACK_MS = 60 * 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var lastEvaluatedMs = 0L

    /** The pending look-again; replaced on every evaluation. Guarded by [mutex]. */
    private var recheck: Job? = null

    /** From `TimeOrganizerApp.onCreate`, so a process the system restarted for the watcher hears pickups too. */
    fun install(context: Context) {
        val app = context.applicationContext
        appContext = app
        ForegroundWatcher.addListener { change ->
            if (change is ForegroundChange.CameToFront && System.currentTimeMillis() - lastEvaluatedMs >= PICKUP_GAP_MS) kick(app)
        }
    }

    /** Decides again soon, off the caller's thread — a pickup, a changed setting. */
    fun kick(context: Context? = null) {
        val app = context?.applicationContext ?: appContext ?: return
        scope.launch { evaluateSafely(app) }
    }

    /** After a sync run, inline: the worker keeps the process for it. */
    suspend fun afterSync(context: Context) = evaluateSafely(context.applicationContext)

    /** One of [BreakActionReceiver.ACTIONS]. The returned job ends once the state is written and the next look is planned. */
    fun onAction(context: Context, action: String): Job {
        val app = context.applicationContext
        return scope.launch {
            mutex.withLock {
                val prefs = BreakPreferences(app)
                val now = System.currentTimeMillis()
                prefs.state = when (action) {
                    BreakActionReceiver.ACTION_BACK -> BreakPolicy.backToIt(prefs.state)
                    BreakActionReceiver.ACTION_MORE -> BreakPolicy.fiveMore(prefs.state, now)
                    BreakActionReceiver.ACTION_DONE -> BreakPolicy.done(prefs.state, now)
                    else -> BreakPolicy.dismissed(prefs.state)
                }
                if (action != BreakActionReceiver.ACTION_DISMISSED) BreakNotifier.cancel(app)
            }
            evaluateSafely(app)
        }
    }

    /** An account switch: the open break and the last `/now` were the previous person's. */
    suspend fun reset(context: Context) {
        val app = context.applicationContext
        NowClient.clear()
        mutex.withLock {
            BreakPreferences(app).state = BreakState.Idle
            BreakNotifier.cancel(app)
            ForegroundWatcher.stop(app, WATCH_REASON)
        }
    }

    private suspend fun evaluateSafely(app: Context) {
        try {
            evaluate(app)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The sync, a pickup or the next look tries again; nothing here is worth failing them for.
        }
    }

    private suspend fun evaluate(app: Context) = mutex.withLock {
        val prefs = BreakPreferences(app)
        val now = System.currentTimeMillis()
        lastEvaluatedMs = now

        val enabled = prefs.enabled && AuthPreferences.get(app).isLoggedIn && Notifications.canPost(app, Notifications.CHANNEL_BREAKS)
        val facts = if (enabled) NowClient.get(now) else null
        val phone = if (enabled) phoneUse(app, facts, now) else PhoneUse.NONE

        val step = BreakPolicy.decide(prefs.state, BreakInput(now, facts, phone, locallySilent(app), enabled, prefs.breakMinutes))
        prefs.state = step.state
        when (val action = step.action) {
            is BreakAction.Post -> BreakNotifier.post(app, action.subject, action.breakMs)
            BreakAction.Cancel -> BreakNotifier.cancel(app)
            BreakAction.None -> Unit
        }

        // Without an answer the watch is left as it was: a flaky network must not end it mid-block.
        if (!enabled || facts != null || step.state !is BreakState.Idle) {
            val until = BreakPolicy.watchUntil(step.state, facts, now, enabled)
            if (until != null) ForegroundWatcher.watch(app, WATCH_REASON, until) else ForegroundWatcher.stop(app, WATCH_REASON)
        }

        val self = currentCoroutineContext()[Job]
        val previous = recheck
        recheck = step.recheckAtMs?.let { atMs ->
            scope.launch {
                delay((atMs - System.currentTimeMillis()).coerceAtLeast(1_000L))
                evaluateSafely(app)
            }
        }
        if (previous !== self) previous?.cancel()
    }

    /**
     * The usage log from an hour before the desk's last minute (bounded by the longest break), through the same
     * session rules as the recording. "In use" is also whatever the running watcher sees in front: its read reaches
     * back six hours, so a sitting opened long before the window still counts.
     */
    private fun phoneUse(app: Context, facts: NowFacts?, now: Long): PhoneUse {
        val anchor = minOf(now, facts?.deskLastActiveMs ?: now) - BreakPolicy.PICKUP_SLACK_MS
        val from = maxOf(anchor, now - BreakPolicy.MAX_BREAK_MS) - LOG_LOOKBACK_MS
        val fromLog = try {
            PhoneUse.from(UsageStatsCollector(app).events(from, now), now)
        } catch (_: SecurityException) {
            PhoneUse.NONE // usage access is off; the status screen says so
        }
        val watched = ForegroundWatcher.running && ForegroundWatcher.inFront.isNotEmpty()
        return if (watched && !fromLog.inUse) fromLog.copy(inUse = true) else fromLog
    }

    /** In a call (or ringing), or Do Not Disturb in any form: the phone's own reasons to wait. Neither needs a permission. */
    private fun locallySilent(app: Context): Boolean {
        val mode = app.getSystemService(AudioManager::class.java)?.mode
        val inCall = mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION ||
            mode == AudioManager.MODE_RINGTONE ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && mode == AudioManager.MODE_CALL_SCREENING)
        val filter = app.getSystemService(NotificationManager::class.java)?.currentInterruptionFilter
        val dnd = filter != null && filter != NotificationManager.INTERRUPTION_FILTER_ALL &&
            filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        return inCall || dnd
    }
}
