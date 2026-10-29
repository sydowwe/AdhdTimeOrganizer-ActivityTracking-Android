package com.timeorganizer.usagetracker.pause

import com.timeorganizer.usagetracker.budget.BudgetSitting
import com.timeorganizer.usagetracker.usagestats.BuildResult
import com.timeorganizer.usagetracker.usagestats.DerailmentRead
import com.timeorganizer.usagetracker.usagestats.PickupCount
import com.timeorganizer.usagetracker.usagestats.RawSession

/**
 * A stretch the user paused tracking for (prompt 17): `[startMs, endMs)`. "Until I resume" ends at [UNTIL_RESUMED] until
 * Resume writes the real end.
 */
data class PauseRange(val startMs: Long, val endMs: Long) {

    fun overlaps(fromMs: Long, toMs: Long): Boolean = fromMs < endMs && toMs > startMs

    operator fun contains(atMs: Long): Boolean = atMs in startMs until endMs

    val untilResumed: Boolean get() = endMs == UNTIL_RESUMED

    companion object {
        const val UNTIL_RESUMED = Long.MAX_VALUE
    }
}

/**
 * **Pure.** What a pause takes out of one read of the usage log, after the build — beside the ignored apps' drop, and like it
 * leaving every cursor where the build put it, so the log is read past the pause as usual and pausing never builds a backlog.
 *
 * - **Sessions are clipped at the pause's edges**: the part before stays, the part after starts at the pause's end, and one
 *   spanning a whole pause becomes two. A re-read clips the same way, so a piece's start — the server's dedup key — is stable.
 *   A piece under the sync's 5 s floor is dropped later by `SyncLimits`, like any short session.
 * - **Everything else that touches a pause is dropped whole**: a pickup, an interruption inside it, a derailment from its
 *   notification to its end. A pickup has no app to keep; a derailment cut short would need an end the server doesn't know.
 *
 * The daily limits' estimate leaves paused time out the same way ([sittings]), so it agrees with what the server is sent.
 */
object PauseClipper {

    fun sessions(built: BuildResult, pauses: List<PauseRange>): BuildResult =
        if (pauses.isEmpty()) built else built.copy(sessions = built.sessions.flatMap { clip(it, pauses) })

    fun pickups(count: PickupCount, pauses: List<PauseRange>): PickupCount =
        if (pauses.isEmpty()) count else count.copy(closed = count.closed.filterNot { p -> pauses.any { it.overlaps(p.startMs, p.endMs) } })

    fun derailments(read: DerailmentRead, pauses: List<PauseRange>): DerailmentRead =
        if (pauses.isEmpty()) read else read.copy(
            interruptions = read.interruptions.filterNot { i -> pauses.any { i.atMs in it } },
            derailments = read.derailments.filterNot { d -> pauses.any { it.overlaps(d.notifiedMs, d.endMs) } }
        )

    /** The daily limits' sittings without paused time; only the piece that still ends where the sitting did is still open. */
    fun sittings(sittings: List<BudgetSitting>, pauses: List<PauseRange>): List<BudgetSitting> =
        if (pauses.isEmpty()) sittings else sittings.flatMap { sitting ->
            pieces(sitting.startMs, sitting.endMs, pauses).map { (from, to) ->
                sitting.copy(startMs = from, endMs = to, open = sitting.open && to == sitting.endMs)
            }
        }

    /** Whether anything in `[fromMs, toMs]` was paused: a loop the check saw then is neither stored nor sent. */
    fun touches(fromMs: Long, toMs: Long, pauses: List<PauseRange>): Boolean = pauses.any { it.overlaps(fromMs, maxOf(toMs, fromMs) + 1) }

    /** [startMs, endMs) less every pause, in order; empty when a pause covers all of it. */
    fun pieces(startMs: Long, endMs: Long, pauses: List<PauseRange>): List<Pair<Long, Long>> {
        var remaining = listOf(startMs to endMs)
        for (pause in pauses.sortedBy { it.startMs }) {
            remaining = remaining.flatMap { (from, to) ->
                if (!pause.overlaps(from, to)) {
                    listOf(from to to)
                } else {
                    listOfNotNull(
                        (from to pause.startMs).takeIf { pause.startMs > from },
                        (pause.endMs to to).takeIf { pause.endMs < to }
                    )
                }
            }
        }
        return remaining
    }

    private fun clip(session: RawSession, pauses: List<PauseRange>): List<RawSession> =
        pieces(session.startMs, session.endMs, pauses).map { (from, to) -> session.copy(startMs = from, endMs = to) }
}
