package com.timeorganizer.usagetracker.drift

import android.content.Context
import com.timeorganizer.usagetracker.data.api.dto.AndroidRuleTarget
import com.timeorganizer.usagetracker.data.local.AuthPreferences
import com.timeorganizer.usagetracker.ignore.IgnoreStore
import com.timeorganizer.usagetracker.loops.LoopPreferences
import com.timeorganizer.usagetracker.notify.NudgeFamily
import com.timeorganizer.usagetracker.notify.NudgeGap
import com.timeorganizer.usagetracker.notify.Notifications
import com.timeorganizer.usagetracker.notify.PhoneSilence
import com.timeorganizer.usagetracker.now.KeptDistractions
import com.timeorganizer.usagetracker.now.NowClient
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
 * Carries out [DriftPolicy]: brings it `/now`, the usage log, the apps' rules, the phone's own silence and what holds a
 * nudge back, posts or withdraws the nudge, and keeps the foreground watcher on through the blocks.
 *
 * **The watcher** (reason [WATCH_REASON]) runs through every block covering now that can still be nudged in, and through
 * the next block if it starts within 20 minutes — nothing wakes the phone at a block's start, so it is armed ahead from
 * the sync before. It polls only while the screen is on; its "Focus watch on until 11:30" notification shows meanwhile.
 *
 * **When it runs.** When a distraction app comes to the front (the watcher), when the policy asks to look again (the grace
 * runs out, a hold or a snooze ends, the block ends or the next starts — an in-process delay, alive while the watcher's
 * service keeps the process), after every sync, on a button, on a changed setting, and once per process start.
 *
 * **What holds a due nudge back** besides the server and `PhoneSilence` (call, DND, car mode): another kind of nudge in the
 * last 10 minutes ([NudgeGap]), and the loop check's "Phone away" timer — a pickup during it is never remarked on
 * (decision 12-5), and drift keeps that promise (08-6).
 *
 * Off — and the watcher let go — when the switch is off, signed out, or the nudge has nothing to be posted with. It never
 * throws into its callers and logs nothing: package and activity names are personal.
 */
object DriftMonitor {

    /** The watcher's reason; see `ForegroundWatcher.watch`. */
    const val WATCH_REASON = "drift"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    @Volatile
    private var appContext: Context? = null

    /** The distraction packages at the last evaluation: a change in front matters only for one of them. */
    @Volatile
    private var distractions: Set<String> = emptySet()

    /** The pending look-again; replaced on every evaluation. Guarded by [mutex]. */
    private var recheck: Job? = null

    /** From `TimeOrganizerApp.onCreate`, so a process the system restarted for the watcher hears changes too. */
    fun install(context: Context) {
        val app = context.applicationContext
        appContext = app
        ForegroundWatcher.addListener { change ->
            if (change is ForegroundChange.CameToFront && change.packageName in distractions) kick(app)
        }
        kick(app)
    }

    /** Decides again soon, off the caller's thread — an app to the front, a changed setting. */
    fun kick(context: Context? = null) {
        val app = context?.applicationContext ?: appContext ?: return
        scope.launch { evaluateSafely(app) }
    }

    /** After a sync run, inline: the worker keeps the process for it. */
    suspend fun afterSync(context: Context) = evaluateSafely(context.applicationContext)

    /** One of [DriftActionReceiver.ACTIONS] or the activity's two. The returned job ends once the state is written. */
    fun onAction(context: Context, action: String): Job {
        val app = context.applicationContext
        return scope.launch {
            try {
                mutex.withLock {
                    val prefs = DriftPreferences(app)
                    prefs.state = when (action) {
                        DriftActionReceiver.ACTION_MORE -> DriftPolicy.fiveMore(prefs.state, System.currentTimeMillis())
                        else -> DriftPolicy.settle(prefs.state) // Back to it, Switch plan, the swipe
                    }
                    if (action != DriftActionReceiver.ACTION_DISMISSED) DriftNotifier.cancel(app)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A button that couldn't be saved changes nothing.
            }
            evaluateSafely(app)
        }
    }

    /** An account switch: the block and its answers were the previous person's. */
    suspend fun reset(context: Context) {
        val app = context.applicationContext
        mutex.withLock {
            DriftPreferences(app).state = DriftState.NONE
            DriftNotifier.cancel(app)
            ForegroundWatcher.stop(app, WATCH_REASON)
        }
    }

    private suspend fun evaluateSafely(app: Context) {
        try {
            evaluate(app)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The next app to the front, the next look or the next sync tries again.
        }
    }

    private suspend fun evaluate(app: Context) = mutex.withLock {
        val prefs = DriftPreferences(app)
        val now = System.currentTimeMillis()

        val enabled = prefs.enabled && AuthPreferences.get(app).isLoggedIn &&
            Notifications.canPost(app, Notifications.CHANNEL_DRIFT)
        val facts = if (enabled) NowClient.get(now) else null
        if (facts != null) KeptDistractions.save(app, facts.distractionPackages)
        val packages = facts?.distractionPackages ?: KeptDistractions.get(app)
        distractions = packages

        val sittings = if (enabled && facts != null && facts.current.isNotEmpty()) {
            try {
                DriftPolicy.sittings(UsageStatsCollector(app).events(now - DriftPolicy.LOOKBACK_MS, now), now, packages)
            } catch (_: SecurityException) {
                emptyList() // usage access is off; the status screen says so
            }
        } else {
            emptyList()
        }

        val input = DriftInput(
            nowMs = now,
            enabled = enabled,
            facts = facts,
            sittings = sittings,
            ruleActivities = ruleActivities(app),
            locallySilent = PhoneSilence.now(app) || PhoneSilence.driving(app),
            heldUntilMs = listOfNotNull(
                NudgeGap.heldUntil(app, NudgeFamily.DRIFT, now),
                LoopPreferences(app).state.awayUntilMs?.takeIf { it > now }
            ).maxOrNull()
        )
        val step = DriftPolicy.decide(prefs.state, input)
        prefs.state = step.state
        if (step.cancel) DriftNotifier.cancel(app)
        step.post?.let {
            DriftNotifier.post(app, it)
            NudgeGap.posted(app, NudgeFamily.DRIFT, now)
        }

        // Without an answer the watch is left as it was: a flaky network must not end it mid-block.
        if (!enabled || facts != null) {
            val until = DriftPolicy.watchUntil(step.state, facts, now, enabled)
            if (until != null) ForegroundWatcher.watch(app, WATCH_REASON, until) else ForegroundWatcher.stop(app, WATCH_REASON)
        }

        val self = currentCoroutineContext()[Job]
        val previous = recheck
        recheck = step.recheckAtMs?.takeIf { enabled }?.let { atMs ->
            scope.launch {
                delay((atMs - System.currentTimeMillis()).coerceAtLeast(1_000L))
                evaluateSafely(app)
            }
        }
        if (previous !== self) previous?.cancel()
    }

    /** Package → activity, from the cached rules the sync keeps fresh. Exact packages the server resolved; no matcher. */
    private fun ruleActivities(app: Context): Map<String, Long> =
        IgnoreStore.get(app).state().rules
            .filter { it.target == AndroidRuleTarget.ACTIVITY }
            .mapNotNull { rule -> rule.activityId?.let { rule.packageName to it } }
            .toMap()
}
