package com.timeorganizer.usagetracker.breaks

import com.timeorganizer.usagetracker.now.NowBlock
import com.timeorganizer.usagetracker.now.NowFacts
import com.timeorganizer.usagetracker.usagestats.SessionBuilder
import com.timeorganizer.usagetracker.usagestats.UsageEventRecord

/** What the break is from, as the nudge names it. */
data class BreakSubject(
    /** User-authored: never log it. */
    val name: String,
    val kind: Kind,
    /** The block covering now when the break began, if any: what "I'm done for now" is done with. */
    val blockId: Long?,
    val blockEndMs: Long?
) {
    enum class Kind {
        /** "Thesis is still open on your desktop": what the desk was doing, by the server's rules. */
        DESK,

        /** "Thesis is planned until 11:30": the desk's last minutes had no name or were off the plan. */
        PLANNED
    }
}

sealed interface BreakState {
    /** No break: the desk is in use, or there is nothing to come back to. */
    data object Idle : BreakState

    data class Open(
        /** When the desk's last active minute ended: the break's start, and what its length is counted from. */
        val startMs: Long,
        val subject: BreakSubject,
        /** When to nudge; `null` once there is no nudge left for this break. */
        val nudgeAtMs: Long?,
        /** A nudge is showing, so the end of the break has to clear it. */
        val posted: Boolean,
        val expiresAtMs: Long
    ) : BreakState

    /** "I'm done for now": silent until another block starts, or with no block until the desk is used again. */
    data class Done(val blockId: Long?, val sinceMs: Long) : BreakState
}

/**
 * When the phone was in use, read from the usage log through the same [SessionBuilder] the sessions come from, so
 * "in use" is exactly what gets recorded: an app in front, the home screen included. Pure.
 */
data class PhoneUse(
    val inUse: Boolean,
    /** Each sitting as (start, end); an open one ends at the read's end. */
    val sittings: List<Pair<Long, Long>>
) {
    /** The first moment at or after [fromMs] the phone was in use; a sitting already running at [fromMs] counts from it. */
    fun firstInUseSince(fromMs: Long): Long? =
        sittings.filter { (_, endMs) -> endMs > fromMs }.minOfOrNull { (startMs, _) -> maxOf(startMs, fromMs) }

    companion object {
        val NONE = PhoneUse(false, emptyList())

        fun from(events: List<UsageEventRecord>, nowMs: Long): PhoneUse {
            val builder = SessionBuilder()
            events.forEach(builder::onEvent)
            val open = builder.openSessions()
            return PhoneUse(
                inUse = open.isNotEmpty(),
                sittings = builder.build(nowMs).sessions.map { it.startMs to it.endMs } + open.values.map { it to nowMs }
            )
        }
    }
}

data class BreakInput(
    val nowMs: Long,
    /** The server's answer, or `null` when it could not be asked: no break starts or ends on a guess. */
    val facts: NowFacts?,
    val phone: PhoneUse,
    /** A call, or Do Not Disturb: the phone's own reasons to stay quiet, which the server can't see. */
    val locallySilent: Boolean,
    val enabled: Boolean,
    val breakMinutes: Int
)

sealed interface BreakAction {
    data object None : BreakAction

    /** "Break's been [breakMs]" about [subject]. */
    data class Post(val subject: BreakSubject, val breakMs: Long) : BreakAction

    /** The break is over: take its nudge down. */
    data object Cancel : BreakAction
}

/** The next state, what to do now, and when to look again (`null`: the next pickup or sync is soon enough). */
data class BreakStep(val state: BreakState, val action: BreakAction, val recheckAtMs: Long?)

/**
 * Breaks that don't end: the desk stopped, the phone started, and the break has run past its length. Pure — the
 * runner brings the server's `/now`, the usage log and the phone's own silence, and carries out what this decides.
 *
 * - **A break starts** when the desk is idle (the server's `DesktopIdleRule`), the phone was picked up no later than
 *   one break length after the desk's last minute (from [PICKUP_SLACK_MS] before it — a phone picked up at the desk
 *   counts), and there is something to come back to: a named desk activity, or a block covering now that had already
 *   begun while the desk was in use. Unmapped and ignored desk time has no name, and a block the desk stopped before
 *   (lunch) is not a break from it.
 * - **It is counted from the desk's last minute**, not from the pickup: the break began when the work stopped.
 * - **The nudge goes out once**, at the break's length, and only while the phone is in use — a phone face down hears
 *   it at the next pickup. Silenced (the server's reason, a call, DND) it waits, it is not dropped.
 * - **It ends** when the desk is used again (a newer last minute — the nudge is cleared), when the planned block it
 *   names ends, or after [MAX_BREAK_MS]. Without an answer from the server nothing starts, ends or is posted.
 */
object BreakPolicy {

    const val MINUTE_MS = 60_000L

    /** The settings' choices; [DEFAULT_MINUTES] unless changed. */
    val BREAK_MINUTES = listOf(5, 10, 15, 20, 30)
    const val DEFAULT_MINUTES = 10

    /** A phone picked up this long before the desk's last minute ended still starts the break. */
    const val PICKUP_SLACK_MS = 2 * MINUTE_MS

    /** How often an open break, or a phone in use while the desk is not idle yet, is looked at again. */
    const val RECHECK_MS = MINUTE_MS

    /** "5 more minutes". */
    const val SNOOZE_MS = 5 * MINUTE_MS

    /** A break with no planned block to end it lapses after this: past it, nobody is coming back soon. */
    const val MAX_BREAK_MS = 2 * 60 * MINUTE_MS

    /** "I'm done for now" lapses after this, whatever else happens. */
    const val DONE_MAX_MS = 12 * 60 * MINUTE_MS

    /** The desk counts as recently used for this long: the watcher is armed while it is. */
    const val DESK_RECENT_MS = 15 * MINUTE_MS

    /** How long one arming of the watcher lasts after recent desk use. */
    const val WATCH_FOR_MS = 30 * MINUTE_MS

    fun decide(state: BreakState, input: BreakInput): BreakStep {
        if (!input.enabled) return BreakStep(BreakState.Idle, cancelIfPosted(state), null)
        return when (state) {
            BreakState.Idle -> fromIdle(input, pickupsFromMs = null)
            is BreakState.Open -> fromOpen(state, input)
            is BreakState.Done -> fromDone(state, input)
        }
    }

    /** "Back to it": no more nudges for this break; it still ends when the desk is used again. */
    fun backToIt(state: BreakState): BreakState =
        if (state is BreakState.Open) state.copy(nudgeAtMs = null, posted = false) else state

    /** "5 more minutes": one more nudge, [SNOOZE_MS] from now. */
    fun fiveMore(state: BreakState, nowMs: Long): BreakState =
        if (state is BreakState.Open) state.copy(nudgeAtMs = nowMs + SNOOZE_MS, posted = false) else state

    /** "I'm done for now": silent for the block the break began in, or with none until the desk is used again. */
    fun done(state: BreakState, nowMs: Long): BreakState =
        BreakState.Done((state as? BreakState.Open)?.subject?.blockId, nowMs)

    /** Swiped away: nothing more for this break, like "Back to it". */
    fun dismissed(state: BreakState): BreakState = backToIt(state)

    /**
     * Until when the foreground watcher should run, so a pickup is noticed within seconds: through any block covering
     * now, for [WATCH_FOR_MS] after recent desk use, and while a break is open. `null`: no reason to watch.
     */
    fun watchUntil(state: BreakState, facts: NowFacts?, nowMs: Long, enabled: Boolean): Long? {
        if (!enabled || state is BreakState.Done) return null
        val until = mutableListOf<Long>()
        if (state is BreakState.Open) until += minOf(state.expiresAtMs, nowMs + WATCH_FOR_MS)
        if (facts != null && !facts.silent) {
            facts.current.maxOfOrNull { it.endMs }?.let { until += it }
            val deskMs = facts.deskLastActiveMs
            if (deskMs != null && nowMs - deskMs <= DESK_RECENT_MS) until += nowMs + WATCH_FOR_MS
        }
        return until.filter { it > nowMs }.maxOrNull()
    }

    /** [pickupsFromMs]: only a pickup at or after it may start a break (one just released from "done"). */
    private fun fromIdle(input: BreakInput, pickupsFromMs: Long?): BreakStep {
        val facts = input.facts
        val opened = facts?.let { open(it, input, pickupsFromMs) }
        if (opened != null) return fromOpen(opened, input)

        // In use while the desk is still busy (or unknown): it may be idle a minute from now. Otherwise nothing changes
        // before the desk is used again, which the next pickup or sync will see.
        val recheck = input.phone.inUse && (facts == null || (!facts.silent && !facts.deskIdle))
        return BreakStep(BreakState.Idle, BreakAction.None, if (recheck) input.nowMs + RECHECK_MS else null)
    }

    private fun open(facts: NowFacts, input: BreakInput, pickupsFromMs: Long?): BreakState.Open? {
        if (facts.silent || !facts.deskIdle) return null
        val deskMs = facts.deskLastActiveMs ?: return null
        val nowMs = input.nowMs
        if (nowMs - deskMs >= MAX_BREAK_MS) return null

        val breakMs = input.breakMinutes * MINUTE_MS
        val pickupMs = input.phone.firstInUseSince(maxOf(deskMs - PICKUP_SLACK_MS, pickupsFromMs ?: Long.MIN_VALUE))
            ?: return null
        // Picked up only after the break was over already: whatever this is, the phone didn't make it long.
        if (pickupMs > deskMs + breakMs) return null

        val subject = subjectOf(facts, deskMs) ?: return null
        val expiresAtMs = if (subject.kind == BreakSubject.Kind.PLANNED) subject.blockEndMs!! else deskMs + MAX_BREAK_MS
        if (expiresAtMs <= nowMs) return null
        return BreakState.Open(deskMs, subject, deskMs + breakMs, posted = false, expiresAtMs = expiresAtMs)
    }

    /**
     * The desk's own work by name when it fits the plan or nothing is planned; otherwise the block covering now — but
     * only one that had begun before the desk stopped, so the break is from it.
     */
    private fun subjectOf(facts: NowFacts, deskMs: Long): BreakSubject? {
        val block: NowBlock? = facts.current.firstOrNull { it.inProgress } ?: facts.current.firstOrNull()
        val work = facts.deskWork
        if (work != null && (block == null || work.onPlan == true)) {
            return BreakSubject(work.name, BreakSubject.Kind.DESK, block?.plannerTaskId, block?.endMs)
        }
        if (block != null && block.startMs < deskMs && block.activityName.isNotBlank()) {
            return BreakSubject(block.activityName, BreakSubject.Kind.PLANNED, block.plannerTaskId, block.endMs)
        }
        return null
    }

    private fun fromOpen(open: BreakState.Open, input: BreakInput): BreakStep {
        val nowMs = input.nowMs
        val facts = input.facts
        val deskMs = facts?.deskLastActiveMs
        if (deskMs != null && deskMs > open.startMs) return BreakStep(BreakState.Idle, cancelIfPosted(open), null) // back at the desk
        if (nowMs >= open.expiresAtMs) return BreakStep(BreakState.Idle, cancelIfPosted(open), null)

        // Looked at again each minute while it matters now: the phone is in use, or a nudge is up and must come down
        // the moment the desk is used. A phone put down hears the rest at its next pickup.
        val poll = if (input.phone.inUse || open.posted) minOf(nowMs + RECHECK_MS, open.expiresAtMs) else null
        val nudgeAtMs = open.nudgeAtMs ?: return BreakStep(open, BreakAction.None, poll)
        if (nowMs < nudgeAtMs) return BreakStep(open, BreakAction.None, poll?.let { minOf(it, nudgeAtMs) })

        val quiet = facts == null || facts.silent || input.locallySilent || !input.phone.inUse
        if (quiet) return BreakStep(open, BreakAction.None, poll)

        return BreakStep(
            open.copy(nudgeAtMs = null, posted = true),
            BreakAction.Post(open.subject, nowMs - open.startMs),
            minOf(nowMs + RECHECK_MS, open.expiresAtMs)
        )
    }

    private fun fromDone(done: BreakState.Done, input: BreakInput): BreakStep {
        val facts = input.facts
        val deskUsedSince = (facts?.deskLastActiveMs ?: Long.MIN_VALUE) > done.sinceMs
        val released = when {
            input.nowMs - done.sinceMs >= DONE_MAX_MS -> true
            facts == null -> false
            done.blockId == null -> deskUsedSince
            // Another block has started; or that block is over and the desk was used again since.
            else -> facts.current.any { it.plannerTaskId != done.blockId } ||
                (facts.current.none { it.plannerTaskId == done.blockId } && deskUsedSince)
        }
        if (!released) return BreakStep(done, BreakAction.None, null)
        // Only a pickup after "done" may open a break: one from before it was already answered.
        return fromIdle(input, pickupsFromMs = done.sinceMs)
    }

    private fun cancelIfPosted(state: BreakState): BreakAction =
        if (state is BreakState.Open && state.posted) BreakAction.Cancel else BreakAction.None
}
