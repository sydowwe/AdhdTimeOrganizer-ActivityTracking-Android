package com.timeorganizer.usagetracker.budget

import com.timeorganizer.usagetracker.data.api.dto.BudgetsResponse
import com.timeorganizer.usagetracker.review.ReviewText
import com.timeorganizer.usagetracker.sync.SyncLimits
import com.timeorganizer.usagetracker.usagestats.SessionBuilder
import com.timeorganizer.usagetracker.usagestats.UsageEventRecord

/** The two things a budget can say, spelt as the server's claim route reads them. */
enum class BudgetStage(val wire: String) {
    APPROACHING("approaching"),
    REACHED("reached")
}

/** One daily limit as the server sent it, the seconds read as milliseconds. */
data class PhoneBudget(
    val budgetId: Long,
    /** User-authored: never log it. */
    val targetName: String,
    val limitMs: Long,
    val warnAtEighty: Boolean,
    /** Said today by anyone (the server, another phone, this one). */
    val warnedToday: Boolean,
    val reachedToday: Boolean,
    /** Today's time on the target from every source, as the server last saw it. */
    val serverTotalMs: Long,
    /** The part of [serverTotalMs] this phone's synced sessions put there. */
    val thisPhoneSyncedMs: Long,
    /** Exact packages, server-resolved. Never empty. */
    val packages: Set<String>
)

/** `GET tracker/android/budgets` read: today's bounds on the user's clock and the budgets a phone app counts toward. */
data class BudgetFacts(
    /** `yyyy-MM-dd`, the user's today. */
    val day: String,
    val dayStartMs: Long,
    val dayEndMs: Long,
    val budgets: List<PhoneBudget>,
    val fetchedAtMs: Long
) {
    /** Every package that counts toward some budget: a change in front matters only for one of them. */
    val packages: Set<String> get() = budgets.flatMapTo(mutableSetOf()) { it.packages }

    companion object {
        /** `null` when the day can't be read: no figures are better than figures for the wrong day. */
        fun from(response: BudgetsResponse, fetchedAtMs: Long): BudgetFacts? {
            val day = response.day?.takeIf { it.isNotBlank() } ?: return null
            val startMs = ReviewText.instantMs(response.dayStartsAt) ?: return null
            val endMs = ReviewText.instantMs(response.dayEndsAt)?.takeIf { it > startMs } ?: return null
            val budgets = response.budgets.orEmpty().mapNotNull { dto ->
                val packages = dto.packages.orEmpty().filter { it.isNotBlank() }.toSet()
                if (packages.isEmpty() || dto.limitSeconds <= 0) return@mapNotNull null
                PhoneBudget(
                    budgetId = dto.budgetId,
                    targetName = dto.targetName.orEmpty(),
                    limitMs = dto.limitSeconds * 1000L,
                    warnAtEighty = dto.warnAtEightyPercent,
                    warnedToday = dto.warnedToday,
                    reachedToday = dto.reachedToday,
                    serverTotalMs = dto.totalSeconds.coerceAtLeast(0L) * 1000L,
                    thisPhoneSyncedMs = dto.thisPhoneSeconds.coerceAtLeast(0L) * 1000L,
                    packages = packages
                )
            }
            return BudgetFacts(day, startMs, endMs, budgets, fetchedAtMs)
        }
    }
}

/** One sitting in a budgeted app, as the usage log has it; an open one (still in front) ends at the read's end. */
data class BudgetSitting(val packageName: String, val startMs: Long, val endMs: Long, val open: Boolean)

/** The stages this phone has settled for [day] — said, or found said — as `"<budgetId>:<stage>"`. */
data class BudgetState(val day: String?, val settled: Set<String>) {

    fun isSettled(budgetId: Long, stage: BudgetStage) = "$budgetId:${stage.wire}" in settled

    companion object {
        val NONE = BudgetState(null, emptySet())

        fun key(budgetId: Long, stage: BudgetStage) = "$budgetId:${stage.wire}"
    }
}

data class BudgetInput(
    val nowMs: Long,
    /** The server's last answer; `null` when it was never read. */
    val facts: BudgetFacts?,
    /** The budgeted apps' sittings since the day began, in any order. */
    val sittings: List<BudgetSitting>,
    /** A call, Do Not Disturb or car mode. */
    val locallySilent: Boolean,
    /** `/now` named a reason to be silent (quiet hours, an away day, absent). */
    val serverSilent: Boolean,
    /** Another kind of nudge in the last 10 minutes, or the "Phone away" timer: nothing until then. */
    val heldUntilMs: Long?
)

/** A stage to say now: the runner claims it on the server, then posts it. */
data class BudgetDue(
    val budgetId: Long,
    val stage: BudgetStage,
    /** User-authored: never log it. */
    val targetName: String,
    val limitMs: Long,
    /** The phone's estimate of today's total at the moment it fell due. */
    val totalMs: Long,
    val day: String
)

/**
 * The next state, the stages to say now (already settled in [state]), when to look again while a budgeted app stays in
 * front, and whether the watcher is still needed today.
 */
data class BudgetStep(val state: BudgetState, val due: List<BudgetDue>, val recheckAtMs: Long?, val watch: Boolean)

/**
 * Daily limits on the phone (prompt 13): the server's total for today, with this phone's synced part replaced by its own
 * count from the usage log, so a limit is said while the app is in front — not up to 15 minutes later, at the sync that
 * carries the time. Pure: the runner brings the server's figures, the usage log and what holds a nudge back.
 *
 * - **The estimate** is `serverTotal − thisPhoneSynced + the phone's own time today in the budget's apps`. It holds whenever
 *   the last sync ran: a sync moves the same seconds into both server figures. The phone's own time is the union of the
 *   sittings (split-screen counts once), from the day's start, sessions under the sync's 5 s floor left out like the sync
 *   leaves them out.
 * - **Stages, as the server's:** 80 % when the budget wants it, then the limit. Reaching the limit with the warning unsaid
 *   says only the limit and settles both. Each is said once a day, by whichever side sees it first: a stage the server
 *   reports said is never said here.
 * - **Only while one of the budget's apps is in front.** Time elsewhere (the desk) reaching a limit is the server's to say,
 *   in the browser where the user is.
 * - **Held, never dropped:** a call, DND, car mode, the server's silence, another nudge in the last 10 minutes, the "Phone
 *   away" timer. Looked at again when the hold ends, and said then if an app of the budget is still in front.
 */
object BudgetTracker {

    private const val MINUTE_MS = 60_000L

    /** Older than this, the server's figures are read again before a decision: desk time may have moved the total. */
    const val FACTS_MAX_AGE_MS = 5 * MINUTE_MS

    /** A silent phone is looked at again this often while a stage waits. */
    const val HOLD_RECHECK_MS = MINUTE_MS

    /** The [packages]' sittings in [events] from [dayStartMs], through the same [SessionBuilder] the recorded sessions come from. */
    fun sittings(events: List<UsageEventRecord>, nowMs: Long, packages: Set<String>, dayStartMs: Long): List<BudgetSitting> {
        val builder = SessionBuilder()
        events.forEach(builder::onEvent)
        val closed = builder.build(nowMs).sessions
            .filter { it.packageName in packages && it.endMs - it.startMs >= SyncLimits.MIN_SESSION_MS }
            .map { BudgetSitting(it.packageName, it.startMs, it.endMs, open = false) }
        val open = builder.openSessions()
            .filter { (packageName, startMs) -> packageName in packages && startMs < nowMs }
            .map { (packageName, startMs) -> BudgetSitting(packageName, startMs, nowMs, open = true) }
        return (closed + open).filter { it.endMs > dayStartMs }
    }

    /** The phone's own time today in [budget]'s apps: the union of their sittings, clipped to the day so far. */
    fun phoneMs(budget: PhoneBudget, sittings: List<BudgetSitting>, dayStartMs: Long, nowMs: Long): Long {
        var totalMs = 0L
        var coveredToMs = dayStartMs
        for (sitting in sittings.filter { it.packageName in budget.packages }.sortedBy { it.startMs }) {
            val startMs = maxOf(sitting.startMs, coveredToMs)
            val endMs = minOf(sitting.endMs, nowMs)
            if (endMs > startMs) totalMs += endMs - startMs
            coveredToMs = maxOf(coveredToMs, endMs)
        }
        return totalMs
    }

    fun estimateMs(budget: PhoneBudget, sittings: List<BudgetSitting>, dayStartMs: Long, nowMs: Long): Long =
        (budget.serverTotalMs - budget.thisPhoneSyncedMs).coerceAtLeast(0L) + phoneMs(budget, sittings, dayStartMs, nowMs)

    /** 80 % of the limit, rounded up, so "at least 80 %" is the server's `total × 5 ≥ limit × 4`. */
    fun warnAtMs(budget: PhoneBudget): Long = (budget.limitMs * 4 + 4) / 5

    /** The stage [estimateMs] owes today, if any: the limit wins; the warning only below it, wanted and still unsaid. */
    fun owed(budget: PhoneBudget, estimateMs: Long, state: BudgetState): BudgetStage? {
        if (budget.reachedToday || state.isSettled(budget.budgetId, BudgetStage.REACHED)) return null
        if (estimateMs >= budget.limitMs) return BudgetStage.REACHED
        val warningOpen = budget.warnAtEighty && !budget.warnedToday && !state.isSettled(budget.budgetId, BudgetStage.APPROACHING)
        return if (warningOpen && estimateMs >= warnAtMs(budget)) BudgetStage.APPROACHING else null
    }

    fun decide(state: BudgetState, input: BudgetInput): BudgetStep {
        val nowMs = input.nowMs
        val facts = input.facts ?: return BudgetStep(state, emptyList(), null, watch = false)
        // Yesterday's figures: nothing to say until the runner reads today's. The watch stays as the figures last left it.
        if (nowMs < facts.dayStartMs || nowMs >= facts.dayEndMs) {
            return BudgetStep(state, emptyList(), null, watch = facts.budgets.isNotEmpty())
        }

        var next = if (state.day == facts.day) state else BudgetState(facts.day, emptySet())
        val inFront = input.sittings.filter { it.open }.map { it.packageName }.toSet()
        val held = input.locallySilent || input.serverSilent || (input.heldUntilMs ?: Long.MIN_VALUE) > nowMs
        val due = mutableListOf<BudgetDue>()
        val rechecks = mutableListOf<Long>()

        for (budget in facts.budgets) {
            if (budget.packages.none { it in inFront }) continue
            val estimate = estimateMs(budget, input.sittings, facts.dayStartMs, nowMs)
            val stage = owed(budget, estimate, next)

            if (stage != null && held) {
                rechecks += maxOf(
                    input.heldUntilMs?.takeIf { it > nowMs } ?: nowMs,
                    if (input.locallySilent || input.serverSilent) nowMs + HOLD_RECHECK_MS else nowMs
                )
                continue
            }
            if (stage != null) {
                due += BudgetDue(budget.budgetId, stage, budget.targetName, budget.limitMs, estimate, facts.day)
                next = settle(next, budget.budgetId, stage)
            }
            nextCrossingMs(budget, estimate, next)?.let { rechecks += nowMs + it }
        }

        val watch = facts.budgets.any { !it.reachedToday && !next.isSettled(it.budgetId, BudgetStage.REACHED) }
        return BudgetStep(next, due, rechecks.minOrNull(), watch)
    }

    /** [stage] said for [budgetId]: the limit settles the warning too, as on the server. */
    fun settle(state: BudgetState, budgetId: Long, stage: BudgetStage): BudgetState {
        val keys = if (stage == BudgetStage.REACHED) {
            listOf(BudgetState.key(budgetId, BudgetStage.APPROACHING), BudgetState.key(budgetId, BudgetStage.REACHED))
        } else {
            listOf(BudgetState.key(budgetId, stage))
        }
        return state.copy(settled = state.settled + keys)
    }

    /** How long, in front, until the next unsaid stage — the estimate grows by the second while an app of the budget is in front. */
    private fun nextCrossingMs(budget: PhoneBudget, estimateMs: Long, state: BudgetState): Long? {
        if (budget.reachedToday || state.isSettled(budget.budgetId, BudgetStage.REACHED)) return null
        val warningOpen = budget.warnAtEighty && !budget.warnedToday && !state.isSettled(budget.budgetId, BudgetStage.APPROACHING)
        val threshold = if (warningOpen && estimateMs < warnAtMs(budget)) warnAtMs(budget) else budget.limitMs
        return (threshold - estimateMs).coerceAtLeast(1_000L)
    }
}
