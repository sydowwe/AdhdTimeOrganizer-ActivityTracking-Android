package com.timeorganizer.usagetracker.chime

import android.content.Context
import com.timeorganizer.usagetracker.data.local.AuthPreferences
import com.timeorganizer.usagetracker.notify.NudgeFamily
import com.timeorganizer.usagetracker.notify.NudgeGap
import com.timeorganizer.usagetracker.notify.Notifications
import com.timeorganizer.usagetracker.notify.PhoneSilence
import com.timeorganizer.usagetracker.now.KeptDistractions
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
 * Carries out [ChimeSchedule]: brings it the usage log, the settings and the phone's own silence, posts the chime, and
 * keeps the foreground watcher on for as long as the chime is.
 *
 * **The watcher runs the whole time the chime is on** (reason [WATCH_REASON], [ForegroundWatcher.INDEFINITELY]): a chosen
 * app can come to the front at any moment, and the first chime must land on time. It polls only while the screen is on.
 *
 * **When it runs.** When a chosen app comes to the front (the watcher), when the next chime falls due (an in-process delay,
 * alive while the watcher's service keeps the process), after every sync (which also refreshes the server's distraction
 * list), on a changed setting, and once per process start, which re-arms the watcher after a force-stop or a reboot.
 *
 * Off — and the watcher let go — when the switch is off, signed out, no app is chosen, or the chime has nothing to be
 * posted with (no notification permission, the app's notifications or the channel turned off). It never throws into
 * its callers and logs nothing: package names are personal.
 */
object ChimeMonitor {

    /** The watcher's reason; see `ForegroundWatcher.watch`. */
    const val WATCH_REASON = "chime"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    @Volatile
    private var appContext: Context? = null

    /** The apps chosen at the last evaluation: a change in front matters only for one of them. */
    @Volatile
    private var chosen: Set<String> = emptySet()

    /** The pending look at the next chime; replaced on every evaluation. Guarded by [mutex]. */
    private var recheck: Job? = null

    /** From `TimeOrganizerApp.onCreate`, so a process the system restarted for the watcher hears changes too. */
    fun install(context: Context) {
        val app = context.applicationContext
        appContext = app
        ForegroundWatcher.addListener { change ->
            if (change is ForegroundChange.CameToFront && change.packageName in chosen) kick(app)
        }
        kick(app)
    }

    /** Decides again soon, off the caller's thread — an app to the front, a changed setting. */
    fun kick(context: Context? = null) {
        val app = context?.applicationContext ?: appContext ?: return
        scope.launch { evaluateSafely(app) }
    }

    /** After a sync run, inline: the worker keeps the process for it. */
    suspend fun afterSync(context: Context) {
        val app = context.applicationContext
        // Only while the chime is on. `/now` is shared and cached: after a sync this is usually the break nudge's answer.
        if (ChimePreferences(app).enabled) KeptDistractions.refresh(app)
        evaluateSafely(app)
    }

    /** An account switch: the stretch was the previous person's (the kept distraction list is cleared beside this). */
    suspend fun reset(context: Context) {
        val app = context.applicationContext
        mutex.withLock {
            ChimePreferences(app).clearAccount()
            ChimeNotifier.cancel(app)
        }
        evaluateSafely(app)
    }

    private suspend fun evaluateSafely(app: Context) {
        try {
            evaluate(app)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The next app to the front or the next sync tries again; nothing here is worth failing them for.
        }
    }

    private suspend fun evaluate(app: Context) = mutex.withLock {
        val prefs = ChimePreferences(app)
        val now = System.currentTimeMillis()
        val chosenNow = prefs.choices.chosen(KeptDistractions.get(app))
        chosen = chosenNow

        val enabled = prefs.enabled && chosenNow.isNotEmpty() && AuthPreferences.get(app).isLoggedIn &&
            Notifications.canPost(app, Notifications.CHANNEL_TIME_CHIME)
        if (!enabled) {
            prefs.state = ChimeState.NONE
            ForegroundWatcher.stop(app, WATCH_REASON)
            replaceRecheck(app, null)
            return@withLock
        }
        ForegroundWatcher.watch(app, WATCH_REASON, ForegroundWatcher.INDEFINITELY)

        val sittings = try {
            ChimeSchedule.sittings(UsageStatsCollector(app).events(now - ChimeSchedule.LOOKBACK_MS, now), now, chosenNow)
        } catch (_: SecurityException) {
            emptyList() // usage access is off; the status screen says so
        }
        // Another nudge in the last 10 minutes skips a chime like a call does (`NudgeGap`): a late "15 m" is wrong by then.
        val quiet = PhoneSilence.now(app) || NudgeGap.heldUntil(app, NudgeFamily.CHIME, now) != null
        val step = ChimeSchedule.decide(prefs.state, ChimeInput(now, sittings, prefs.intervalMinutes, quiet))
        prefs.state = step.state
        step.chime?.let {
            ChimeNotifier.post(app, it)
            NudgeGap.posted(app, NudgeFamily.CHIME, now)
        }
        replaceRecheck(app, step.recheckAtMs)
    }

    /** Called under [mutex]. Never cancels the job it is running in: that one is about to end on its own. */
    private suspend fun replaceRecheck(app: Context, atMs: Long?) {
        val self = currentCoroutineContext()[Job]
        val previous = recheck
        recheck = atMs?.let {
            scope.launch {
                delay((it - System.currentTimeMillis()).coerceAtLeast(1_000L))
                evaluateSafely(app)
            }
        }
        if (previous !== self) previous?.cancel()
    }
}
