package com.timeorganizer.usagetracker.ramp

import com.timeorganizer.usagetracker.now.NowBlock
import com.timeorganizer.usagetracker.now.NowFacts

/** Which blocks get a ramp, by the importance the planner already gives them (decision 10-4). */
enum class RampBlocks {
    /** Every planned block. */
    ALL,

    /** All but the Optional ones — those the user already said they may skip. The default. */
    NOT_OPTIONAL,

    /** Critical, or a level the user ranked above it. */
    CRITICAL;

    fun admits(block: NowBlock): Boolean = when (this) {
        ALL -> true
        NOT_OPTIONAL -> !block.isOptional
        CRITICAL -> block.isCritical
    }
}

/**
 * What the start ramp knows, saved across restarts, so a restart neither asks again about a block it already offered nor
 * forgets an "In 2 min". Ids and instants only, no names.
 */
data class StartRampState(
    /** The blocks the ramp is done with, by planner task id, each with its end for pruning. */
    val settled: Map<Long, Long> = emptyMap(),
    /** **In 2 min**: this block is asked about once more at [againAtMs]. */
    val againBlockId: Long? = null,
    val againAtMs: Long? = null,
    /** The ramp on screen: its block and end, so it can be withdrawn without an answer from the server. */
    val showingBlockId: Long? = null,
    val showingEndMs: Long = 0L,
    /** The ramp on screen still has its **In 2 min**: false on the repeat. */
    val showingCanDefer: Boolean = false
) {
    companion object {
        val NONE = StartRampState()
    }
}

data class StartRampInput(
    val nowMs: Long,
    /** The switch, signed in, and something to post with. Off ends everything. */
    val enabled: Boolean,
    val blocks: RampBlocks,
    /** `/now`; `null` when the server couldn't be asked: nothing starts, the state waits. */
    val facts: NowFacts?,
    /** A call, Do Not Disturb or car mode: a due ramp waits. */
    val locallySilent: Boolean,
    /** Another nudge's gap or the loop check's "Phone away" timer: a due ramp waits until then. */
    val heldUntilMs: Long?
)

/**
 * "Thesis starts now" (or "started 20 min ago") — "1 h 30 min, until 11:30. Start the timer?"
 * @param lateByMs how long ago the block started; under a minute is "starts now".
 * @param canDefer false on the one repeat "In 2 min" asks for: it has no third chance.
 */
data class StartRampNudge(val block: NowBlock, val lateByMs: Long, val canDefer: Boolean)

data class StartRampStep(
    val state: StartRampState,
    val post: StartRampNudge? = null,
    /** The ramp on screen is out of date: its block started, ended or is gone, or the feature is off. */
    val cancel: Boolean = false,
    /** Look again then, in-process: a hold ends, a block starts, an "In 2 min" comes due, the shown block ends. */
    val recheckAtMs: Long? = null,
    /** Wake the phone then (an alarm): the next block to ask about starts, or an "In 2 min" comes due. */
    val wakeAtMs: Long? = null,
    /** Keep the watcher on until then, so the first pickup is seen: a ramp is due but held. */
    val watchUntilMs: Long? = null
)

/**
 * **Pure.** The start ramp: at a planned block's start, one notification — *"Thesis starts now · 1 h 30 min, until 11:30.
 * Start the timer?"* — with **Start now**, **In 2 min** and **Not this one**. The desktop tracker's `StartRampPolicy`,
 * for the phone: the server decides every part of the plan (the blocks, their importance, whether one is started, whether
 * a timer runs, whether the desk is in use); this only decides when to speak.
 *
 * - **When.** At a block's start (an alarm planned from `/now`'s `next`), or later, from the blocks covering now: "Thesis
 *   started 20 min ago". Late only while [MIN_LEFT_WHEN_LATE_MS] of the block is left; asking about a block nearly over
 *   only makes someone feel behind.
 * - **Which blocks** — [RampBlocks], from the planner's importance; All but Optional by default.
 * - **Settled without a word** when the block is already under way (started in the planner — the web app's timer marks it
 *   — or from this ramp), when the desk is in use (the desktop tracker's own ramp asks there, decision 10-7), or too late.
 * - **Held, never dropped**: while a countdown or pomodoro runs (its end is the moment to move on to the plan), the server's
 *   silence, a call, DND, car mode, the "Phone away" timer and another nudge in the last 10 minutes. Said once the hold is
 *   over, if still worth saying.
 * - **Not nagging.** One ramp per block. **In 2 min** asks exactly once more, without that button, so it can't become a
 *   snooze loop; **Start now**, **Not this one** and a swipe settle the block — the block is settled the moment it is
 *   posted, so ignoring it settles it too. Withdrawn when the block is started, ends, or is gone from the plan.
 */
object StartRampPolicy {

    const val MINUTE_MS = 60_000L

    /** Up to this late, the ramp is about a start; after it, only while [MIN_LEFT_WHEN_LATE_MS] is left. */
    const val ON_TIME_MS = 3 * MINUTE_MS

    /** A block with less than this left, asked about late, is let go. */
    const val MIN_LEFT_WHEN_LATE_MS = 10 * MINUTE_MS

    /** **In 2 min**. */
    const val AGAIN_MS = 2 * MINUTE_MS

    /** A held ramp is looked at again this often. */
    const val RECHECK_MS = MINUTE_MS

    /** A settled block is remembered this long past its end: a block extended later keeps its id. */
    const val REMEMBER_MS = 24 * 60 * MINUTE_MS

    fun decide(state: StartRampState, input: StartRampInput): StartRampStep {
        val nowMs = input.nowMs
        if (!input.enabled) return StartRampStep(StartRampState.NONE, cancel = state.showingBlockId != null)

        var current = prune(state, nowMs)

        // No answer: nothing new can start. A ramp still up leaves once its block is over by the end last heard.
        val facts = input.facts ?: return if (current.showingBlockId != null && nowMs >= current.showingEndMs) {
            StartRampStep(withdrawn(current), cancel = true)
        } else {
            StartRampStep(current, recheckAtMs = current.showingEndMs.takeIf { current.showingBlockId != null })
        }

        val listed = (facts.current + listOfNotNull(facts.next)).distinctBy { it.plannerTaskId }

        // The ramp on screen goes once its block is started, over, or no longer an open block of today's.
        var cancel = false
        current.showingBlockId?.let { id ->
            val block = listed.firstOrNull { it.plannerTaskId == id }
            if (block == null || block.inProgress || nowMs >= block.endMs) {
                current = withdrawn(current)
                cancel = true
            } else {
                current = current.copy(showingEndMs = block.endMs)
            }
        }

        val started = listed.filter { it.startMs <= nowMs && nowMs < it.endMs }.sortedBy { it.startMs }
        for (block in started) {
            val id = block.plannerTaskId
            if (!input.blocks.admits(block) || id in current.settled) continue

            val isAgain = current.againBlockId == id
            val againAtMs = current.againAtMs
            if (isAgain && againAtMs != null && nowMs < againAtMs) continue

            val lateByMs = nowMs - block.startMs
            val tooLate = lateByMs > ON_TIME_MS && block.endMs - nowMs < MIN_LEFT_WHEN_LATE_MS
            if (block.inProgress || !facts.deskIdle || tooLate) {
                current = settle(current, block)
                continue
            }

            val heldUntilMs = when {
                facts.silent || input.locallySilent || facts.timerRunning == true -> nowMs + RECHECK_MS
                else -> input.heldUntilMs?.takeIf { it > nowMs }
            }
            if (heldUntilMs != null) {
                return StartRampStep(
                    current,
                    cancel = cancel,
                    recheckAtMs = heldUntilMs,
                    wakeAtMs = wakeAt(current, facts, input.blocks, nowMs),
                    watchUntilMs = askableUntil(block)
                )
            }

            val posted = settle(current, block).copy(showingBlockId = id, showingEndMs = block.endMs, showingCanDefer = !isAgain)
            return StartRampStep(
                posted,
                post = StartRampNudge(block, lateByMs, canDefer = !isAgain),
                cancel = cancel,
                recheckAtMs = listOfNotNull(block.endMs, nextLook(posted, facts, nowMs)).min(),
                wakeAtMs = wakeAt(posted, facts, input.blocks, nowMs)
            )
        }

        return StartRampStep(
            current,
            cancel = cancel,
            recheckAtMs = listOfNotNull(
                nextLook(current, facts, nowMs),
                current.showingEndMs.takeIf { current.showingBlockId != null }
            ).minOrNull(),
            wakeAtMs = wakeAt(current, facts, input.blocks, nowMs)
        )
    }

    /** **Start now** and **Not this one**: the block is settled already; the ramp leaves. */
    fun answered(state: StartRampState): StartRampState = withdrawn(state)

    /** A swipe: the same — the block was settled when it was posted. */
    fun dismissed(state: StartRampState): StartRampState = withdrawn(state)

    /**
     * **In 2 min** for [blockId]: asked once more [AGAIN_MS] from [nowMs] — only for the ramp on screen with that button; a
     * press on anything else (the repeat, a ramp already withdrawn) only withdraws.
     */
    fun later(state: StartRampState, blockId: Long, nowMs: Long): StartRampState {
        val withdrawn = withdrawn(state)
        if (state.showingBlockId != blockId || !state.showingCanDefer) return withdrawn
        return withdrawn.copy(settled = state.settled - blockId, againBlockId = blockId, againAtMs = nowMs + AGAIN_MS)
    }

    /** How long a held ramp is still worth saying: past this the late rule lets the block go. */
    fun askableUntil(block: NowBlock): Long =
        maxOf(block.startMs + ON_TIME_MS, block.endMs - MIN_LEFT_WHEN_LATE_MS).coerceAtMost(block.endMs)

    private fun settle(state: StartRampState, block: NowBlock): StartRampState {
        val again = state.againBlockId == block.plannerTaskId
        return state.copy(
            settled = state.settled + (block.plannerTaskId to block.endMs),
            againBlockId = if (again) null else state.againBlockId,
            againAtMs = if (again) null else state.againAtMs
        )
    }

    private fun withdrawn(state: StartRampState): StartRampState =
        state.copy(showingBlockId = null, showingEndMs = 0L, showingCanDefer = false)

    /** Settled blocks a day past their end, and an "In 2 min" whose block never came round again (it ended first). */
    private fun prune(state: StartRampState, nowMs: Long): StartRampState {
        val staleAgain = state.againAtMs != null && state.againAtMs + REMEMBER_MS < nowMs
        return state.copy(
            settled = state.settled.filterValues { endMs -> endMs + REMEMBER_MS >= nowMs },
            againBlockId = if (staleAgain) null else state.againBlockId,
            againAtMs = if (staleAgain) null else state.againAtMs
        )
    }

    /** The next moment something may come due without a word from anyone: the next start, an "In 2 min". */
    private fun nextLook(state: StartRampState, facts: NowFacts, nowMs: Long): Long? = listOfNotNull(
        facts.next?.startMs?.takeIf { it > nowMs },
        state.againAtMs?.takeIf { it > nowMs }
    ).minOrNull()

    /** The alarm: the next block's start when it is one to ask about, or an "In 2 min". */
    private fun wakeAt(state: StartRampState, facts: NowFacts, blocks: RampBlocks, nowMs: Long): Long? = listOfNotNull(
        facts.next?.takeIf { it.startMs > nowMs && blocks.admits(it) && it.plannerTaskId !in state.settled }?.startMs,
        state.againAtMs?.takeIf { it > nowMs }
    ).minOrNull()
}
