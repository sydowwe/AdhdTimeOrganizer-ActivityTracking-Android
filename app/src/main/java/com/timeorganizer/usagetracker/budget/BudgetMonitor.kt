package com.timeorganizer.usagetracker.budget

import android.content.Context
import com.timeorganizer.usagetracker.TimeOrganizerApp
import com.timeorganizer.usagetracker.data.api.RetrofitClient
import com.timeorganizer.usagetracker.data.api.dto.BudgetClaimRequest
import com.timeorganizer.usagetracker.data.local.AuthPreferences
import com.timeorganizer.usagetracker.data.local.SyncPreferences
import com.timeorganizer.usagetracker.loops.LoopPreferences
import com.timeorganizer.usagetracker.notify.NudgeFamily
import com.timeorganizer.usagetracker.notify.NudgeGap
import com.timeorganizer.usagetracker.notify.Notifications
import com.timeorganizer.usagetracker.notify.PhoneSilence
import com.timeorganizer.usagetracker.now.NowClient
import com.timeorganizer.usagetracker.pause.PauseClipper
import com.timeorganizer.usagetracker.pause.Pauses
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

/** One line of the status card: a budget and the phone's estimate of today against it. */
data class BudgetLine(val targetName: String, val totalMs: Long, val limitMs: Long)

/**
 * Carries out [BudgetTracker]: brings it the server's figures (`GET tracker/android/budgets`), the usage log since the day
 * began and what holds a nudge back; claims each due stage on the server, posts it, and asks for a sync so the server and
 * the web catch up.
 *
 * **Who says it (decision 13-1).** A due stage is claimed first (`POST …/budgets/{id}/claim`): `claimed` → the phone says
 * it and the server never will; `false` → it was said (the server's push, another phone) and the phone stays quiet. With
 * the server unreachable the phone says it anyway and keeps the claim, sent before the next sync ([beforeSync]) so the sync
 * that carries the crossing finds the mark set.
 *
 * **The watcher runs all day** (reason [WATCH_REASON], [ForegroundWatcher.INDEFINITELY]) while some budget with phone apps
 * has not reached its limit today, so a budgeted app coming to the front is seen within seconds. While one is in front the
 * next crossing is an in-process delay, alive while the watcher's service keeps the process.
 *
 * **When it runs.** A budgeted app coming to the front (the watcher), the next crossing or the end of a hold, after every
 * sync (which reads the figures again), the card's switch, and once per process start. The figures are read again when
 * older than [BudgetTracker.FACTS_MAX_AGE_MS] or from another day; offline the last ones of today still count.
 *
 * Off — and the watcher let go — when the switch is off, signed out, or nothing can be posted. It never throws into its
 * callers and logs nothing: package and activity names are personal.
 */
object BudgetMonitor {

    /** The watcher's reason; see `ForegroundWatcher.watch`. */
    const val WATCH_REASON = "budget"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    @Volatile
    private var appContext: Context? = null

    /** Every budgeted package at the last evaluation: a change in front matters only for one of them. */
    @Volatile
    private var packages: Set<String> = emptySet()

    /** The pending look-again; replaced on every evaluation. Guarded by [mutex]. */
    private var recheck: Job? = null

    /** From `TimeOrganizerApp.onCreate`, so a process the system restarted for the watcher hears changes too. */
    fun install(context: Context) {
        val app = context.applicationContext
        appContext = app
        packages = BudgetPreferences(app).facts?.packages.orEmpty()
        ForegroundWatcher.addListener { change ->
            if (change is ForegroundChange.CameToFront && change.packageName in packages) kick(app)
        }
        kick(app)
    }

    /** Decides again soon, off the caller's thread — an app to the front, the switch. */
    fun kick(context: Context? = null) {
        val app = context?.applicationContext ?: appContext ?: return
        scope.launch { evaluateSafely(app, refresh = false) }
    }

    /** Before a sync run sends anything: the claims of stages said offline, so the server's push for them stays quiet. */
    suspend fun beforeSync(context: Context) {
        try {
            mutex.withLock { flushClaims(context.applicationContext) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Kept for the next run; at worst the server's push says the stage a second time.
        }
    }

    /** After a sync run, inline: the worker keeps the process for it. The figures are read again: the sync moved them. */
    suspend fun afterSync(context: Context) = evaluateSafely(context.applicationContext, refresh = true)

    /** An account switch: the figures, the settled stages and the claims were the previous person's. */
    suspend fun reset(context: Context) {
        val app = context.applicationContext
        mutex.withLock {
            BudgetPreferences(app).clearAccount()
            BudgetNotifier.cancelAll(app)
            ForegroundWatcher.stop(app, WATCH_REASON)
            packages = emptySet()
        }
    }

    /** The status card's lines: each budget with the phone's estimate of today, from the last figures. Never throws. */
    suspend fun lines(context: Context): List<BudgetLine> {
        val app = context.applicationContext
        return try {
            val now = System.currentTimeMillis()
            val facts = BudgetPreferences(app).facts?.takeIf { now >= it.dayStartMs && now < it.dayEndMs } ?: return emptyList()
            val sittings = sittings(app, facts, now)
            facts.budgets.map { BudgetLine(it.targetName, BudgetTracker.estimateMs(it, sittings, facts.dayStartMs, now), it.limitMs) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun evaluateSafely(app: Context, refresh: Boolean) {
        try {
            evaluate(app, refresh)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The next app to the front, the next look or the next sync tries again.
        }
    }

    private suspend fun evaluate(app: Context, refresh: Boolean) = mutex.withLock {
        val prefs = BudgetPreferences(app)
        val now = System.currentTimeMillis()

        val enabled = prefs.enabled && AuthPreferences.get(app).isLoggedIn &&
            Notifications.canPost(app, Notifications.CHANNEL_BUDGET)
        if (!enabled) {
            ForegroundWatcher.stop(app, WATCH_REASON)
            replaceRecheck(app, null)
            return@withLock
        }

        var facts = prefs.facts
        val stale = facts == null || now - facts.fetchedAtMs !in 0 until BudgetTracker.FACTS_MAX_AGE_MS || now >= facts.dayEndMs
        if (refresh || stale) {
            fetch(app, now)?.let {
                facts = it
                prefs.facts = it
            }
        }
        packages = facts?.packages.orEmpty()

        val sittings = facts?.takeIf { now >= it.dayStartMs && now < it.dayEndMs }?.let { sittings(app, it, now) }.orEmpty()
        // `/now` only when something could be said: an app of a budget in front. Cached for 30 s; unreachable is not silent.
        val serverSilent = sittings.any { it.open } && NowClient.get(now)?.silent == true

        val input = BudgetInput(
            nowMs = now,
            facts = facts,
            sittings = sittings,
            locallySilent = PhoneSilence.now(app) || PhoneSilence.driving(app),
            serverSilent = serverSilent,
            heldUntilMs = listOfNotNull(
                NudgeGap.heldUntil(app, NudgeFamily.BUDGET, now),
                LoopPreferences(app).state.awayUntilMs?.takeIf { it > now }
            ).maxOrNull()
        )
        val step = BudgetTracker.decide(prefs.state, input)
        prefs.state = step.state

        var said = false
        for (due in step.due) said = say(app, prefs, due) || said
        if (said) {
            NudgeGap.posted(app, NudgeFamily.BUDGET, now)
            // So the server and the web catch up now rather than at the next 15-minute run.
            TimeOrganizerApp.enqueueSyncNow(app)
        }

        // Without figures the watch is left as it was: a flaky network must not end it.
        if (facts != null) {
            if (step.watch) ForegroundWatcher.watch(app, WATCH_REASON, ForegroundWatcher.INDEFINITELY)
            else ForegroundWatcher.stop(app, WATCH_REASON)
        }
        replaceRecheck(app, step.recheckAtMs)
    }

    /** Claims [due], and posts it unless the server says it was said already. Returns whether it was posted. */
    private suspend fun say(app: Context, prefs: BudgetPreferences, due: BudgetDue): Boolean {
        val outcome = claim(due.budgetId, due.stage, due.day)
        if (outcome == ClaimOutcome.UNREACHABLE) prefs.pendingClaims = prefs.pendingClaims + pendingKey(due)
        if (!outcome.post) return false
        BudgetNotifier.post(app, due)
        return true
    }

    private suspend fun flushClaims(app: Context) {
        val prefs = BudgetPreferences(app)
        val today = prefs.facts?.day
        for (entry in prefs.pendingClaims) {
            val parts = entry.split('|')
            val budgetId = parts.getOrNull(0)?.toLongOrNull()
            val stage = BudgetStage.entries.firstOrNull { it.wire == parts.getOrNull(1) }
            val day = parts.getOrNull(2)
            // Another day's claim claims nothing on the server any more; an unreadable one never will.
            val keep = budgetId != null && stage != null && day != null && (today == null || today == day) &&
                claim(budgetId, stage, day) == ClaimOutcome.UNREACHABLE
            if (!keep) prefs.pendingClaims = prefs.pendingClaims - entry
        }
    }

    private suspend fun claim(budgetId: Long, stage: BudgetStage, day: String): ClaimOutcome {
        val response = try {
            RetrofitClient.apiService.claimBudget(budgetId, BudgetClaimRequest(stage.wire, day))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return ClaimOutcome.UNREACHABLE
        }
        return ClaimOutcome.of(response.code(), response.body()?.claimed)
    }

    private suspend fun fetch(app: Context, now: Long): BudgetFacts? {
        val response = try {
            RetrofitClient.apiService.budgets(SyncPreferences(app).deviceId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        return response.body()?.takeIf { response.isSuccessful }?.let { BudgetFacts.from(it, now) }
    }

    /** Paused time left out (prompt 17), as the sync leaves it out of what the server's figures count. */
    private suspend fun sittings(app: Context, facts: BudgetFacts, now: Long): List<BudgetSitting> = try {
        PauseClipper.sittings(
            BudgetTracker.sittings(UsageStatsCollector(app).events(facts.dayStartMs, now), now, facts.packages, facts.dayStartMs),
            Pauses.over(app, facts.dayStartMs, now)
        )
    } catch (_: SecurityException) {
        emptyList() // usage access is off; the status screen says so
    }

    private fun pendingKey(due: BudgetDue) = "${due.budgetId}|${due.stage.wire}|${due.day}"

    /** Called under [mutex]. Never cancels the job it is running in: that one is about to end on its own. */
    private suspend fun replaceRecheck(app: Context, atMs: Long?) {
        val self = currentCoroutineContext()[Job]
        val previous = recheck
        recheck = atMs?.let {
            scope.launch {
                delay((it - System.currentTimeMillis()).coerceAtLeast(1_000L))
                evaluateSafely(app, refresh = false)
            }
        }
        if (previous !== self) previous?.cancel()
    }
}

/** What a claim's answer means for the notification. Pure, so the mapping is tested. */
enum class ClaimOutcome(val post: Boolean) {
    /** The phone holds the mark: say it; the server never will. */
    CLAIMED(true),

    /** Said already — by the server, another phone, or a day that is over: stay quiet. */
    SAID(false),

    /** The budget is gone (deleted, its activity archived): nothing to say. */
    GONE(false),

    /** No answer, or one that says nothing about the budget: say it now, and send the claim before the next sync. */
    UNREACHABLE(true);

    companion object {
        fun of(code: Int, claimed: Boolean?): ClaimOutcome = when {
            code in 200..299 -> if (claimed == true) CLAIMED else SAID
            code == 404 -> GONE
            else -> UNREACHABLE
        }
    }
}
