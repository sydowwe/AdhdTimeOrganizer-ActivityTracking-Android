package com.timeorganizer.usagetracker.loops

import android.content.Context
import com.timeorganizer.usagetracker.data.local.AuthPreferences
import com.timeorganizer.usagetracker.notify.Notifications
import com.timeorganizer.usagetracker.notify.PhoneSilence
import com.timeorganizer.usagetracker.usagestats.Pickups
import com.timeorganizer.usagetracker.usagestats.UsageStatsCollector
import com.timeorganizer.usagetracker.util.InstalledApps
import com.timeorganizer.usagetracker.watch.ForegroundChange
import com.timeorganizer.usagetracker.watch.ForegroundWatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Carries out [LoopPolicy]: brings it the usage log's pickups and sittings, the apps that don't count and the phone's own
 * silence, posts or clears the check-in, runs the away timer, and keeps the foreground watcher on while the check is.
 *
 * **The watcher runs the whole time the check is on** (reason [WATCH_REASON], [ForegroundWatcher.INDEFINITELY]), so the
 * check-in lands at the pickup that makes the loop, not at the next sync. After an unlock some app comes to the front —
 * the home screen counts — and that change is the cue to read the last hour of the log.
 *
 * **When it runs.** On any app coming to the front (the watcher; changes arriving while a look is pending are one look),
 * after every sync, on a button or the timer's alarm, on a changed setting, and once per process start, which re-arms the
 * watcher after a force-stop or a reboot. It needs no look-again of its own: nothing changes without an app coming to the
 * front, and a loop that is over is closed at the next look.
 *
 * Off — and the watcher let go — when the switch is off, signed out, or the check-in has nothing to be posted with. It
 * never throws into its callers and logs nothing: package names are personal.
 */
object LoopMonitor {

    /** The watcher's reason; see `ForegroundWatcher.watch`. */
    const val WATCH_REASON = "loop"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val lookPending = AtomicBoolean(false)

    @Volatile
    private var appContext: Context? = null

    /** Package lookups for the whole process: which apps have a launcher entry rarely changes. */
    private var installedApps: InstalledApps? = null
    private val launcherEntry = mutableMapOf<String, Boolean>()

    /** From `TimeOrganizerApp.onCreate`, so a process the system restarted for the watcher hears changes too. */
    fun install(context: Context) {
        val app = context.applicationContext
        appContext = app
        ForegroundWatcher.addListener { change ->
            if (change is ForegroundChange.CameToFront) kick(app)
        }
        kick(app)
    }

    /** Decides again soon, off the caller's thread. A kick while one is still waiting to run is the same look. */
    fun kick(context: Context? = null) {
        val app = context?.applicationContext ?: appContext ?: return
        if (!lookPending.compareAndSet(false, true)) return
        scope.launch {
            lookPending.set(false)
            evaluateSafely(app)
        }
    }

    /** After a sync run, inline: the worker keeps the process for it. */
    suspend fun afterSync(context: Context) = evaluateSafely(context.applicationContext)

    /** One of [LoopActionReceiver.ACTIONS]. The returned job ends once the state is written. */
    fun onAction(context: Context, action: String): Job {
        val app = context.applicationContext
        return scope.launch {
            try {
                act(app, action)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A button that couldn't be saved changes nothing; the check-in stays where it was.
            }
        }
    }

    /** An account switch: the loops seen were the previous person's. The apps that don't count are this phone's. */
    suspend fun reset(context: Context) {
        val app = context.applicationContext
        mutex.withLock {
            LoopPreferences(app).state = LoopState(countFromMs = System.currentTimeMillis())
            LoopNotifier.cancelCheckIn(app)
            LoopNotifier.cancelAwayAlarm(app)
            LoopDatabase.get(app).loopDao().clear()
        }
        evaluateSafely(app)
    }

    /** "2 loops today", counting one still going; the away timer's end while it runs. For the status card. */
    suspend fun today(context: Context): LoopToday {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        val state = LoopPreferences(app).state
        val fromMs = LoopPolicy.startOfDayMs(now, ZoneId.systemDefault())
        val stored = LoopDatabase.get(app).loopDao().countSince(fromMs)
        val open = if (state.loop != null && state.loop.startMs >= fromMs) 1 else 0
        return LoopToday(stored + open, state.awayUntilMs?.takeIf { it > now })
    }

    private suspend fun act(app: Context, action: String) = mutex.withLock {
        val prefs = LoopPreferences(app)
        val now = System.currentTimeMillis()
        val previous = prefs.state
        val step = when (action) {
            LoopActionReceiver.ACTION_AWAY -> LoopPolicy.putAway(previous, now, prefs.awayMinutes)
            LoopActionReceiver.ACTION_NOT_TODAY -> LoopPolicy.notToday(previous, now, ZoneId.systemDefault())
            LoopActionReceiver.ACTION_AWAY_DONE -> LoopPolicy.awayDone(previous, now)
            else -> LoopPolicy.fine(previous) // "I'm fine" and the swipe
        }
        apply(app, step, now)
        if (action == LoopActionReceiver.ACTION_AWAY) step.state.awayUntilMs?.let { LoopNotifier.scheduleAwayAlarm(app, it) }
        if (step.awayDone) LoopNotifier.postAwayDone(app, previous.awayMinutes.takeIf { it > 0 } ?: prefs.awayMinutes)
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
        val prefs = LoopPreferences(app)
        val now = System.currentTimeMillis()

        val enabled = prefs.enabled && AuthPreferences.get(app).isLoggedIn &&
            Notifications.canPost(app, Notifications.CHANNEL_LOOP_CHECK)
        if (!enabled) {
            apply(app, LoopPolicy.off(prefs.state, now), now)
            ForegroundWatcher.stop(app, WATCH_REASON)
            return@withLock
        }
        ForegroundWatcher.watch(app, WATCH_REASON, ForegroundWatcher.INDEFINITELY)

        val events = try {
            UsageStatsCollector(app).events(now - LoopDetector.LOOKBACK_MS, now)
        } catch (_: SecurityException) {
            return@withLock // usage access is off; the status screen says so
        }
        val pickups = Pickups.from(events)
        if (!prefs.sawUnlock && pickups.any { it.unlocked }) prefs.sawUnlock = true
        val sittings = LoopDetector.sittings(events, now)

        val facts = LoopFacts(now, pickups, sittings, excluded(app, sittings), prefs.notCounted, prefs.sawUnlock)
        apply(app, LoopPolicy.decide(prefs.state, facts, PhoneSilence.now(app)), now)
    }

    /** Called under [mutex]: the state first, then what the step says to show. */
    private suspend fun apply(app: Context, step: LoopStep, now: Long) {
        val prefs = LoopPreferences(app)
        prefs.state = step.state
        step.ended?.let { LoopDatabase.save(app, it, now) }
        if (step.cancelCheckIn) LoopNotifier.cancelCheckIn(app)
        step.checkIn?.let { LoopNotifier.postCheckIn(app, it, prefs.awayMinutes) }
    }

    /** The home screen, this app, and every app without a launcher entry among [sittings]. */
    private fun excluded(app: Context, sittings: List<LoopSitting>): Set<String> {
        val apps = installedApps ?: InstalledApps(app).also { installedApps = it }
        val noEntry = sittings.map { it.packageName }.distinct().filterNot { packageName ->
            launcherEntry.getOrPut(packageName) { apps.hasLauncherEntry(packageName) }
        }
        return apps.homePackages + app.packageName + noEntry
    }
}

/** The status card's line. */
data class LoopToday(val loops: Int, val awayUntilMs: Long?)
