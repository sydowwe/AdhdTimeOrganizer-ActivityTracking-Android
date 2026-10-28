package com.timeorganizer.usagetracker.loops

import java.time.Instant
import java.time.ZoneId

/** What became of a loop's check-in. Stored with the loop. */
enum class LoopAnswer {
    /** Checked in, no answer yet. */
    ASKED,

    /** **Put it away**: the away timer ran. */
    AWAY,

    /** **I'm fine**, or the check-in swiped away. */
    FINE,

    /** **Not today**. */
    NOT_TODAY,

    /**
     * A call or Do Not Disturb when it began, or another kind of nudge in the 10 minutes before (`NudgeGap`): not asked,
     * and not asked later either.
     */
    SILENCED
}

/** The loop going on: seen at [startMs], a rule last held at [lastHeldMs]; [pickups] and [apps] are the most counted. */
data class OpenLoop(
    val startMs: Long,
    val lastHeldMs: Long,
    val rule: LoopRule,
    val pickups: Int,
    val apps: Int,
    val answer: LoopAnswer
)

data class LoopState(
    /** Nothing that began before this counts: the end of the last loop, of the away timer, of "Not today". */
    val countFromMs: Long = 0L,
    val loop: OpenLoop? = null,
    /** The away timer runs until then; no check-in meanwhile. */
    val awayUntilMs: Long? = null,
    /** How long the running away timer was set for, for the "is up" line. */
    val awayMinutes: Int = 0,
    /** "Not today": no check-in until then, the next local midnight. */
    val quietUntilMs: Long? = null
)

/** The check-in to post: "6 pick-ups in 10 minutes" or "6 apps in 5 minutes". */
data class LoopCheckIn(val signal: LoopSignal)

data class LoopStep(
    val state: LoopState,
    val checkIn: LoopCheckIn? = null,
    /** The check-in on screen is out of date: the loop it asked about is over. */
    val cancelCheckIn: Boolean = false,
    /** A loop that is over, to be stored. */
    val ended: OpenLoop? = null,
    /** The away timer is done: say so. */
    val awayDone: Boolean = false
)

/**
 * **Pure.** The loop check's state from one look to the next, and its buttons.
 *
 * - **One check-in per loop.** A loop begins when a [LoopDetector] rule holds and none was going on; it goes on while one
 *   keeps holding, and ends [LOOP_END_GAP_MS] after the last time one did. Nothing more is said about it.
 * - **A call or DND** when a loop begins skips its check-in (not postponed: a late "6 pick-ups" is wrong by then).
 * - **Put it away** ends the loop and starts the away timer: nothing is said until it is done, and nothing from before its
 *   end counts afterwards. **I'm fine** (and a swipe) keeps the loop silent until it ends. **Not today** ends it and
 *   is silent until the next local midnight.
 */
object LoopPolicy {

    /** The rules haven't held for this long: the loop is over, and the next one gets its own check-in. */
    const val LOOP_END_GAP_MS = 15 * LoopDetector.MINUTE_MS

    /** The away timer's choices; [DEFAULT_AWAY_MINUTES] unless changed. */
    val AWAY_MINUTES = listOf(10, 20, 30)
    const val DEFAULT_AWAY_MINUTES = 20

    /**
     * The timer's alarm is what ends it and says so. One that never came (a reboot clears alarms) is let go this long
     * after, silently.
     */
    const val AWAY_GRACE_MS = 30 * LoopDetector.MINUTE_MS

    /** The alarm is inexact, never early; a moment early is taken as on time all the same. */
    private const val AWAY_EARLY_MS = LoopDetector.MINUTE_MS

    fun decide(state: LoopState, facts: LoopFacts, locallySilent: Boolean): LoopStep {
        val nowMs = facts.nowMs
        var next = state

        next.quietUntilMs?.let { until ->
            if (nowMs < until) return LoopStep(next)
            next = next.copy(quietUntilMs = null, countFromMs = maxOf(next.countFromMs, until))
        }
        next.awayUntilMs?.let { until ->
            if (nowMs < until + AWAY_GRACE_MS) return LoopStep(next)
            next = next.copy(awayUntilMs = null, awayMinutes = 0, countFromMs = maxOf(next.countFromMs, until))
        }

        val signal = LoopDetector.detect(facts, next.countFromMs)
        val loop = next.loop
        if (loop != null) {
            if (signal != null) {
                val held = loop.copy(
                    lastHeldMs = nowMs,
                    pickups = maxOf(loop.pickups, signal.pickups),
                    apps = maxOf(loop.apps, signal.apps)
                )
                return LoopStep(next.copy(loop = held))
            }
            if (nowMs - loop.lastHeldMs <= LOOP_END_GAP_MS) return LoopStep(next)
            return LoopStep(
                next.copy(loop = null, countFromMs = maxOf(next.countFromMs, loop.lastHeldMs)),
                cancelCheckIn = loop.answer == LoopAnswer.ASKED,
                ended = loop
            )
        }

        if (signal == null) return LoopStep(next)
        val answer = if (locallySilent) LoopAnswer.SILENCED else LoopAnswer.ASKED
        val started = OpenLoop(nowMs, nowMs, signal.rule, signal.pickups, signal.apps, answer)
        return LoopStep(next.copy(loop = started), checkIn = if (locallySilent) null else LoopCheckIn(signal))
    }

    /** **Put it away**: the loop is over, and the timer runs for [awayMinutes]. */
    fun putAway(state: LoopState, nowMs: Long, awayMinutes: Int): LoopStep = LoopStep(
        state.copy(
            loop = null,
            awayUntilMs = nowMs + awayMinutes * LoopDetector.MINUTE_MS,
            awayMinutes = awayMinutes,
            countFromMs = nowMs
        ),
        cancelCheckIn = true,
        ended = state.loop?.let { it.copy(answer = LoopAnswer.AWAY, lastHeldMs = maxOf(it.lastHeldMs, nowMs)) }
    )

    /** **I'm fine**, or a swipe: silent until this loop ends. */
    fun fine(state: LoopState): LoopStep =
        LoopStep(state.copy(loop = state.loop?.copy(answer = LoopAnswer.FINE)), cancelCheckIn = true)

    /** **Not today**: the loop is over, and nothing is said until the next midnight in [zone]. */
    fun notToday(state: LoopState, nowMs: Long, zone: ZoneId): LoopStep = LoopStep(
        state.copy(loop = null, quietUntilMs = nextMidnightMs(nowMs, zone), countFromMs = nowMs),
        cancelCheckIn = true,
        ended = state.loop?.let { it.copy(answer = LoopAnswer.NOT_TODAY, lastHeldMs = maxOf(it.lastHeldMs, nowMs)) }
    )

    /** The timer's alarm. Said only for a timer that is running and due. */
    fun awayDone(state: LoopState, nowMs: Long): LoopStep {
        val until = state.awayUntilMs ?: return LoopStep(state)
        if (nowMs < until - AWAY_EARLY_MS) return LoopStep(state)
        return LoopStep(
            state.copy(awayUntilMs = null, awayMinutes = 0, countFromMs = maxOf(state.countFromMs, nowMs)),
            awayDone = true
        )
    }

    /**
     * The switch turned off (or nothing to post with, or signed out): a loop going on ends here, unasked about from now on.
     * A running away timer is left to finish: it was asked for.
     */
    fun off(state: LoopState, nowMs: Long): LoopStep {
        val loop = state.loop ?: return LoopStep(state)
        return LoopStep(
            state.copy(loop = null, countFromMs = maxOf(state.countFromMs, nowMs)),
            cancelCheckIn = loop.answer == LoopAnswer.ASKED,
            ended = loop
        )
    }

    /** Local midnight after [nowMs]: "today" ends there. */
    fun nextMidnightMs(nowMs: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    /** Local midnight before [nowMs]: where the card's "today" begins. */
    fun startOfDayMs(nowMs: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
}
