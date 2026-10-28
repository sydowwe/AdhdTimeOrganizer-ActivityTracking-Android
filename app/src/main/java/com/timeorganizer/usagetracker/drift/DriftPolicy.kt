package com.timeorganizer.usagetracker.drift

import com.timeorganizer.usagetracker.now.NowBlock
import com.timeorganizer.usagetracker.now.NowFacts
import com.timeorganizer.usagetracker.usagestats.SessionBuilder
import com.timeorganizer.usagetracker.usagestats.UsageEventRecord

/** One sitting in a distraction app, as the usage log has it; an open one (still in front) ends at the read's end. */
data class DriftSitting(val packageName: String, val startMs: Long, val endMs: Long, val open: Boolean)

/**
 * What the drift nudge knows about the block it is in. [NONE] outside any block. Saved across restarts, so a restart
 * mid-block neither repeats the block's nudge nor forgets "5 more minutes".
 */
data class DriftState(
    /** The planner block the rest is about; `null` outside a block. */
    val blockId: Long? = null,
    /** Its end as last heard, so a nudge still up can be withdrawn without an answer from the server. */
    val blockEndMs: Long = 0L,
    /** Nudged and answered (or nudged and up): nothing more in this block. */
    val done: Boolean = false,
    /** **5 more minutes**: one more nudge is due from then, if a distraction app is in front. */
    val snoozedUntilMs: Long? = null,
    /** The nudge is on screen. */
    val showing: Boolean = false
) {
    companion object {
        val NONE = DriftState()
    }
}

data class DriftInput(
    val nowMs: Long,
    /** The switch, signed in, and something to post with. Off ends everything. */
    val enabled: Boolean,
    /** `/now`; `null` when the server couldn't be asked: nothing starts, the state waits. */
    val facts: NowFacts?,
    /** The distraction packages' sittings in the lookback, in any order. */
    val sittings: List<DriftSitting>,
    /** Package → the activity its rule maps it to. A distraction app mapped to the block's own activity is the plan. */
    val ruleActivities: Map<String, Long>,
    /** A call, Do Not Disturb or car mode: a due nudge waits. */
    val locallySilent: Boolean,
    /** Another nudge's gap or the loop check's "Phone away" timer: a due nudge waits until then. */
    val heldUntilMs: Long?
)

/** "Planned: [block] until its end · [packageName] is open." */
data class DriftNudge(val block: NowBlock, val packageName: String)

data class DriftStep(
    val state: DriftState,
    val post: DriftNudge? = null,
    /** The nudge on screen is out of date: its block is over, or the feature is off. */
    val cancel: Boolean = false,
    /** Look again then: the grace runs out, a hold or a snooze ends, the block ends, the next one starts. */
    val recheckAtMs: Long? = null
)

/** The distraction apps' time in the current stretch, and the one in front now (`null`: none is). */
data class DriftStretch(val inAppMs: Long, val inFront: String?)

/**
 * **Pure.** The drift nudge: during a planned block, when a distraction app has been in front for [GRACE_MS] — *"Planned:
 * Thesis until 11:30 · Instagram is open."* The server decides every part of what that means (the block, which packages are
 * distractions, which activity a package maps to); this only counts the time.
 *
 * - **What counts** is `/now`'s distraction packages, less those whose rule maps them to the block's own activity (a
 *   YouTube-lectures block). Unmapped apps and apps mapped to another activity never count: the phone can't judge "off
 *   plan" fairly without the server, and unmapped time is neither on nor off it (decision 08-8).
 * - **The grace.** Time in front of any of them adds up (Instagram, then TikTok). Out of them for up to [RESET_GAP_MS] —
 *   a chat reply, the home screen — pauses the count; longer starts it over. Only time inside the block counts.
 * - **One nudge per block.** **Back to it**, **Switch plan** and a swipe end it for the block; **5 more minutes** asks
 *   once more, [MORE_MS] later, if a distraction app is in front then (or the next time one has been for the grace).
 * - **Held, never dropped**: the server's silence, a call, DND, car mode, the loop check's "Phone away" timer and another
 *   nudge in the last 10 minutes (`NudgeGap`) make a due nudge wait; it is said once they are over, if still due.
 * - **Withdrawn** when its block ends or another block takes over.
 */
object DriftPolicy {

    const val SECOND_MS = 1_000L
    const val MINUTE_MS = 60_000L

    /** In front this long before the nudge: a glance passes, scrolling is caught in its first minute (decision 08-2). */
    const val GRACE_MS = 30 * SECOND_MS

    /** Out of the distraction apps for longer than this, and the grace starts over. */
    const val RESET_GAP_MS = MINUTE_MS

    /** **5 more minutes**. */
    const val MORE_MS = 5 * MINUTE_MS

    /** A due nudge held by a call, DND, car mode or the server's silence is looked at again this often. */
    const val RECHECK_MS = MINUTE_MS

    /** A block starting within this is watched for already: the next sync may come only after it has begun. */
    const val WATCH_AHEAD_MS = 20 * MINUTE_MS

    /** How far back the usage log is read: further than a stretch of scrolling, so it is seen whole. */
    const val LOOKBACK_MS = 6 * 60 * MINUTE_MS

    /** The [packages]' sittings in [events], through the same [SessionBuilder] the recorded sessions come from. */
    fun sittings(events: List<UsageEventRecord>, nowMs: Long, packages: Set<String>): List<DriftSitting> {
        val builder = SessionBuilder()
        events.forEach(builder::onEvent)
        val closed = builder.build(nowMs).sessions
            .filter { it.packageName in packages }
            .map { DriftSitting(it.packageName, it.startMs, it.endMs, open = false) }
        val open = builder.openSessions()
            .filter { (packageName, startMs) -> packageName in packages && startMs < nowMs }
            .map { (packageName, startMs) -> DriftSitting(packageName, startMs, nowMs, open = true) }
        return closed + open
    }

    /**
     * The stretch still going at [nowMs] from [fromMs] on (the block's start): the sittings chained by gaps of at most
     * [RESET_GAP_MS], split-screen overlap counted once. `null` when the last one ended longer ago than that.
     */
    fun stretch(sittings: List<DriftSitting>, fromMs: Long, nowMs: Long): DriftStretch? {
        var chain = mutableListOf<DriftSitting>()
        var chainEndMs = Long.MIN_VALUE
        val clipped = sittings.filter { it.endMs > fromMs }.map { it.copy(startMs = maxOf(it.startMs, fromMs)) }
        for (sitting in clipped.sortedBy { it.startMs }) {
            if (chain.isNotEmpty() && sitting.startMs - chainEndMs > RESET_GAP_MS) chain = mutableListOf()
            chainEndMs = if (chain.isEmpty()) sitting.endMs else maxOf(chainEndMs, sitting.endMs)
            chain += sitting
        }
        if (chain.isEmpty() || nowMs - chainEndMs > RESET_GAP_MS) return null

        var inAppMs = 0L
        var coveredToMs = Long.MIN_VALUE
        for (sitting in chain) {
            inAppMs += (sitting.endMs - maxOf(sitting.startMs, coveredToMs)).coerceAtLeast(0L)
            coveredToMs = maxOf(coveredToMs, sitting.endMs)
        }
        val inFront = chain.filter { it.open }.maxByOrNull { it.startMs }?.packageName
        return DriftStretch(inAppMs, inFront)
    }

    fun decide(state: DriftState, input: DriftInput): DriftStep {
        val nowMs = input.nowMs
        if (!input.enabled) return DriftStep(DriftState.NONE, cancel = state.showing)

        // No answer: nothing new can start. A nudge still up leaves once its block is over by the end last heard.
        val facts = input.facts ?: return if (state.showing && nowMs >= state.blockEndMs) {
            DriftStep(DriftState.NONE, cancel = true)
        } else {
            DriftStep(state, recheckAtMs = state.blockEndMs.takeIf { state.showing })
        }

        val block = facts.current.firstOrNull()
            ?: return DriftStep(DriftState.NONE, cancel = state.showing, recheckAtMs = nextStartToWatch(facts, nowMs))

        // A block moved or extended keeps its id: take its latest end. Another block starts everything again.
        val fresh = block.plannerTaskId != state.blockId
        val current = if (fresh) DriftState(block.plannerTaskId, block.endMs) else state.copy(blockEndMs = block.endMs)
        val cancel = fresh && state.showing

        // Settled for this block. Looked at again at its end, so a nudge still up leaves and the next block is seen even
        // with the same app in front the whole time (no app coming to the front would wake anything).
        if (current.done) return DriftStep(current, cancel = cancel, recheckAtMs = block.endMs)
        current.snoozedUntilMs?.let { until ->
            if (nowMs < until) return DriftStep(current, cancel = cancel, recheckAtMs = until)
        }

        val counted = facts.distractionPackages.filterNot { packageName ->
            block.activityId != null && input.ruleActivities[packageName] == block.activityId
        }.toSet()
        val stretch = stretch(input.sittings.filter { it.packageName in counted }, block.startMs, nowMs)
        val inFront = stretch?.inFront
            // None in front: the next one to come to the front is what wakes this. The block's end still needs a look.
            ?: return DriftStep(current, cancel = cancel, recheckAtMs = block.endMs)

        if (stretch.inAppMs < GRACE_MS) {
            return DriftStep(current, cancel = cancel, recheckAtMs = nowMs + (GRACE_MS - stretch.inAppMs))
        }
        if (facts.silent || input.locallySilent) {
            return DriftStep(current, cancel = cancel, recheckAtMs = nowMs + RECHECK_MS)
        }
        input.heldUntilMs?.takeIf { it > nowMs }?.let { until ->
            return DriftStep(current, cancel = cancel, recheckAtMs = until)
        }

        return DriftStep(
            current.copy(done = true, snoozedUntilMs = null, showing = true),
            post = DriftNudge(block, inFront),
            cancel = cancel,
            recheckAtMs = block.endMs
        )
    }

    /** **Back to it**, **Switch plan** and a swipe: nothing more in this block. */
    fun settle(state: DriftState): DriftState = state.copy(done = true, snoozedUntilMs = null, showing = false)

    /** **5 more minutes**: one more nudge, [MORE_MS] from [nowMs], if a distraction app is in front then. */
    fun fiveMore(state: DriftState, nowMs: Long): DriftState =
        state.copy(done = false, snoozedUntilMs = nowMs + MORE_MS, showing = false)

    /**
     * Until when the foreground watcher should run, so a distraction app is noticed within seconds: through the blocks
     * covering now that can still be nudged in, and through the next block if it starts within [WATCH_AHEAD_MS] (the sync
     * that would arm it may come only after it has begun). `null`: no reason to watch — off, no answer, or the server's
     * silence.
     */
    fun watchUntil(state: DriftState, facts: NowFacts?, nowMs: Long, enabled: Boolean): Long? {
        if (!enabled || facts == null || facts.silent) return null
        val until = mutableListOf<Long>()
        facts.current
            .filterNot { it.plannerTaskId == state.blockId && state.done }
            .maxOfOrNull { it.endMs }
            ?.let { until += it }
        facts.next?.takeIf { it.startMs - nowMs <= WATCH_AHEAD_MS }?.let { until += it.endMs }
        return until.filter { it > nowMs }.maxOrNull()
    }

    /** Outside a block: look again when the next one starts, if that is soon, so an app already open is caught. */
    private fun nextStartToWatch(facts: NowFacts, nowMs: Long): Long? =
        facts.next?.startMs?.takeIf { it > nowMs && it - nowMs <= WATCH_AHEAD_MS }
}
