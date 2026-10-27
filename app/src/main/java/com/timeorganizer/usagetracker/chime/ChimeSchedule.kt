package com.timeorganizer.usagetracker.chime

import com.timeorganizer.usagetracker.usagestats.SessionBuilder
import com.timeorganizer.usagetracker.usagestats.UsageEventRecord

/** One sitting in a chimed app, as the usage log has it; an open one (still in front) ends at the read's end. */
data class ChimeSitting(val packageName: String, val startMs: Long, val endMs: Long, val open: Boolean)

/**
 * The stretch being counted, by when it began, and how much of its time in the apps has been chimed for already.
 * [NONE] when there is none.
 */
data class ChimeState(val stretchStartMs: Long, val chimedMs: Long) {
    companion object {
        val NONE = ChimeState(0L, 0L)
    }
}

data class ChimeInput(
    val nowMs: Long,
    /** The chosen apps' sittings in the lookback, in any order. */
    val sittings: List<ChimeSitting>,
    val intervalMinutes: Int,
    /** A call, or Do Not Disturb: a chime due then is skipped, not saved for later. */
    val locallySilent: Boolean
)

/** "[inAppMs] on [packages]": the one in front first, then the stretch's other apps, most time first. */
data class Chime(val inAppMs: Long, val packages: List<String>)

/** The next state, the chime to post now if any, and when the next one falls due if the app stays in front. */
data class ChimeStep(val state: ChimeState, val chime: Chime?, val recheckAtMs: Long?)

/** The chosen apps' time since the stretch began, gaps left out, and which of them is in front now. */
data class ChimeStretch(
    val startMs: Long,
    val inAppMs: Long,
    val inFront: List<String>,
    /** Every app of the stretch, most time first. */
    val packages: List<String>
)

/**
 * The time chime: while a chosen app stays in front, a chime at each [ChimeInput.intervalMinutes] of time in the chosen
 * apps. Pure — the runner brings the usage log, the settings and the phone's own silence.
 *
 * - **The chosen apps count as one stretch**: Reddit for 10 minutes and Instagram for 5 is 15 minutes, said about the one
 *   in front. Split-screen with two of them counts the overlap once.
 * - **A gap of up to [RESET_GAP_MS]** out of them — another app, the home screen, the screen off — **pauses the count**;
 *   the gap itself is not counted. A longer one starts a new stretch from zero.
 * - **A chime only while one of them is in front.** Left, nothing is due; the next time one comes to the front, the count
 *   goes on (or starts over).
 * - **Due chimes are not saved up**: one that fell due while nobody looked (the process was asleep) is said once, for the
 *   latest mark. One due during a call or DND is skipped; the next mark is said as usual.
 */
object ChimeSchedule {

    const val MINUTE_MS = 60_000L

    /** The settings' choices; [DEFAULT_MINUTES] unless changed. */
    val INTERVAL_MINUTES = listOf(5, 10, 15, 20, 30)
    const val DEFAULT_MINUTES = 15

    /** Out of the chosen apps for longer than this, and the count starts over. */
    const val RESET_GAP_MS = 3 * MINUTE_MS

    /** How far back the usage log is read: further than the screen is ever on at once, so a stretch is seen whole. */
    const val LOOKBACK_MS = 6 * 60 * MINUTE_MS

    /** The [chosen] apps' sittings in [events], through the same [SessionBuilder] the recorded sessions come from. */
    fun sittings(events: List<UsageEventRecord>, nowMs: Long, chosen: Set<String>): List<ChimeSitting> {
        val builder = SessionBuilder()
        events.forEach(builder::onEvent)
        val closed = builder.build(nowMs).sessions
            .filter { it.packageName in chosen }
            .map { ChimeSitting(it.packageName, it.startMs, it.endMs, open = false) }
        val open = builder.openSessions()
            .filter { (packageName, startMs) -> packageName in chosen && startMs < nowMs }
            .map { (packageName, startMs) -> ChimeSitting(packageName, startMs, nowMs, open = true) }
        return closed + open
    }

    /** The stretch still going at [nowMs] — one of the apps in front, or left no longer than [RESET_GAP_MS] ago — or `null`. */
    fun stretch(sittings: List<ChimeSitting>, nowMs: Long): ChimeStretch? {
        var chain = mutableListOf<ChimeSitting>()
        var chainEndMs = Long.MIN_VALUE
        for (sitting in sittings.sortedBy { it.startMs }) {
            if (chain.isNotEmpty() && sitting.startMs - chainEndMs > RESET_GAP_MS) chain = mutableListOf()
            chainEndMs = if (chain.isEmpty()) sitting.endMs else maxOf(chainEndMs, sitting.endMs)
            chain += sitting
        }
        if (chain.isEmpty() || nowMs - chainEndMs > RESET_GAP_MS) return null

        // The union of the sittings: split-screen with two chosen apps is one minute per minute, not two.
        var inAppMs = 0L
        var coveredToMs = Long.MIN_VALUE
        val perApp = mutableMapOf<String, Long>()
        for (sitting in chain) {
            inAppMs += (sitting.endMs - maxOf(sitting.startMs, coveredToMs)).coerceAtLeast(0L)
            coveredToMs = maxOf(coveredToMs, sitting.endMs)
            perApp.merge(sitting.packageName, sitting.endMs - sitting.startMs, Long::plus)
        }
        val inFront = chain.filter { it.open }.sortedByDescending { it.startMs }.map { it.packageName }.distinct()
        return ChimeStretch(
            startMs = chain.first().startMs,
            inAppMs = inAppMs,
            inFront = inFront,
            packages = perApp.entries.sortedByDescending { it.value }.map { it.key }
        )
    }

    fun decide(state: ChimeState, input: ChimeInput): ChimeStep {
        val nowMs = input.nowMs
        val stretch = stretch(input.sittings, nowMs) ?: return ChimeStep(ChimeState.NONE, null, null)
        val counted = if (state.stretchStartMs == stretch.startMs) state else ChimeState(stretch.startMs, 0L)

        // Left for now: nothing can fall due before one of them is back in front, and that is looked at when it is.
        if (stretch.inFront.isEmpty()) return ChimeStep(counted, null, null)

        val intervalMs = input.intervalMinutes.coerceAtLeast(1) * MINUTE_MS
        val markMs = stretch.inAppMs / intervalMs * intervalMs
        // Past the mark already chimed, so an interval changed mid-stretch moves the next chime and never repeats one.
        val nextMarkMs = (maxOf(markMs, counted.chimedMs) / intervalMs + 1) * intervalMs
        val recheckAtMs = nowMs + (nextMarkMs - stretch.inAppMs)

        if (markMs <= counted.chimedMs) return ChimeStep(counted, null, recheckAtMs)

        val chimed = counted.copy(chimedMs = markMs)
        if (input.locallySilent) return ChimeStep(chimed, null, recheckAtMs)

        val packages = stretch.inFront + (stretch.packages - stretch.inFront.toSet())
        return ChimeStep(chimed, Chime(markMs, packages), recheckAtMs)
    }
}
