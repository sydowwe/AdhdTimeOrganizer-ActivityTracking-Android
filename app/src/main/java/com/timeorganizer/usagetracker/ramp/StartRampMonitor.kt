package com.timeorganizer.usagetracker.ramp

import android.content.Context
import com.timeorganizer.usagetracker.data.api.RetrofitClient
import com.timeorganizer.usagetracker.data.api.dto.StartBlockRequest
import com.timeorganizer.usagetracker.data.local.AuthPreferences
import com.timeorganizer.usagetracker.drift.DriftMonitor
import com.timeorganizer.usagetracker.loops.LoopPreferences
import com.timeorganizer.usagetracker.notify.NudgeFamily
import com.timeorganizer.usagetracker.notify.NudgeGap
import com.timeorganizer.usagetracker.notify.Notifications
import com.timeorganizer.usagetracker.notify.PhoneSilence
import com.timeorganizer.usagetracker.now.NowClient
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
 * Carries out [StartRampPolicy]: brings it `/now`, the phone's own silence and what holds a nudge back, posts or withdraws
 * the ramp, plans the wake at the next block's start, and keeps the watcher on while a due ramp is held.
 *
 * **When it runs.** At the wake (an alarm at the next block's start, or an "In 2 min" — see [StartRampNotifier]), when the
 * policy asks to look again (an in-process delay, so a phone in use hears the start on time even when the alarm comes
 * late), after every sync, on a pickup while a ramp is held (the watcher, reason [WATCH_REASON]), on a button, on a changed
 * setting, and once per process start. Evaluated before the drift nudge wherever both run (decision 10-8).
 *
 * **What holds a due ramp back** besides the server's silence, a running timer and `PhoneSilence` (call, DND, car mode):
 * another kind of nudge in the last 10 minutes ([NudgeGap]) and the loop check's "Phone away" timer, as for the drift nudge.
 *
 * Off — the alarm cancelled and the watcher let go — when the switch is off, signed out, or the ramp has nothing to be
 * posted with. It never throws into its callers and logs nothing: activity names are personal.
 */
object StartRampMonitor {

    /** The watcher's reason; see `ForegroundWatcher.watch`. */
    const val WATCH_REASON = "ramp"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    @Volatile
    private var appContext: Context? = null

    /** A due ramp is held: the next app to the front (a pickup) is worth a look. */
    @Volatile
    private var held = false

    /** The pending look-again; replaced on every evaluation. Guarded by [mutex]. */
    private var recheck: Job? = null

    /** From `TimeOrganizerApp.onCreate`, before the drift nudge's, so a restarted process looks at the start first. */
    fun install(context: Context) {
        val app = context.applicationContext
        appContext = app
        ForegroundWatcher.addListener { change ->
            if (change is ForegroundChange.CameToFront && held) kick(app)
        }
        kick(app)
    }

    /** Decides again soon, off the caller's thread — a pickup, a changed setting. */
    fun kick(context: Context? = null) {
        val app = context?.applicationContext ?: appContext ?: return
        scope.launch { evaluateSafely(app) }
    }

    /** After a sync run, inline: the worker keeps the process for it. */
    suspend fun afterSync(context: Context) = evaluateSafely(context.applicationContext)

    /**
     * One of [StartRampActionReceiver.ACTIONS] or the activity's Start now, for [blockId]. The returned job ends once the
     * state is written (and, for Start now, the planner told).
     */
    fun onAction(context: Context, action: String, blockId: Long): Job {
        val app = context.applicationContext
        return scope.launch {
            if (action != StartRampActionReceiver.ACTION_WAKE) {
                try {
                    mutex.withLock {
                        val prefs = StartRampPreferences(app)
                        prefs.state = when (action) {
                            StartRampActionReceiver.ACTION_LATER ->
                                StartRampPolicy.later(prefs.state, blockId, System.currentTimeMillis())
                            StartRampActionReceiver.ACTION_DISMISSED -> StartRampPolicy.dismissed(prefs.state)
                            else -> StartRampPolicy.answered(prefs.state) // Start now, Not this one
                        }
                        if (action != StartRampActionReceiver.ACTION_DISMISSED) StartRampNotifier.cancel(app)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // A button that couldn't be saved changes nothing; the block was settled when it was posted.
                }
                when (action) {
                    StartRampActionActivity.ACTION_START -> startInPlanner(blockId)
                    StartRampActionReceiver.ACTION_NOT_THIS_ONE -> DriftMonitor.quietBlock(app, blockId).join()
                }
            }
            evaluateSafely(app)
        }
    }

    /** An account switch: the blocks and the answers were the previous person's. */
    suspend fun reset(context: Context) {
        val app = context.applicationContext
        mutex.withLock {
            StartRampPreferences(app).state = StartRampState.NONE
            StartRampNotifier.cancel(app)
            StartRampNotifier.scheduleWake(app, null)
            ForegroundWatcher.stop(app, WATCH_REASON)
            held = false
        }
    }

    /**
     * **Start now**'s server half: the block goes in progress. One try: a 409 (finished, skipped meanwhile) and a failure
     * both leave it to the web app, whose own start marks the block too.
     */
    private suspend fun startInPlanner(blockId: Long) {
        try {
            RetrofitClient.apiService.startBlock(StartBlockRequest(blockId))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Offline: the web app's timer starts the block when it opens.
        }
    }

    private suspend fun evaluateSafely(app: Context) {
        try {
            evaluate(app)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The next wake, look or sync tries again.
        }
    }

    private suspend fun evaluate(app: Context) = mutex.withLock {
        val prefs = StartRampPreferences(app)
        val now = System.currentTimeMillis()

        val enabled = prefs.enabled && AuthPreferences.get(app).isLoggedIn &&
            Notifications.canPost(app, Notifications.CHANNEL_START_RAMP)
        val facts = if (enabled) NowClient.get(now) else null

        val input = StartRampInput(
            nowMs = now,
            enabled = enabled,
            blocks = prefs.blocks,
            facts = facts,
            locallySilent = PhoneSilence.now(app) || PhoneSilence.driving(app),
            heldUntilMs = listOfNotNull(
                NudgeGap.heldUntil(app, NudgeFamily.RAMP, now),
                LoopPreferences(app).state.awayUntilMs?.takeIf { it > now }
            ).maxOrNull()
        )
        val step = StartRampPolicy.decide(prefs.state, input)
        prefs.state = step.state
        if (step.cancel) StartRampNotifier.cancel(app)
        step.post?.let {
            StartRampNotifier.post(app, it)
            NudgeGap.posted(app, NudgeFamily.RAMP, now)
        }

        // Without an answer the wake and the watch are left as they were: a flaky network must not lose a block's start.
        if (!enabled || facts != null) {
            StartRampNotifier.scheduleWake(app, step.wakeAtMs)
            held = step.watchUntilMs != null
            val until = step.watchUntilMs
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
}
