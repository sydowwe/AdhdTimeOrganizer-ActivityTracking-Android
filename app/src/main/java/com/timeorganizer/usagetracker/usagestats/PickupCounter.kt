package com.timeorganizer.usagetracker.usagestats

/** A pickup that is over and will not change on a later read: what the outbox stores and the server gets (prompt 14). */
data class CountedPickup(val startMs: Long, val endMs: Long, val unlocked: Boolean)

/**
 * One read's pickups. [resumeFromMs] is where the next read must start: the start of the earliest pickup that is not
 * [settled][PickupCounter.SETTLE_MS] yet, or the read's end. [sawUnlock] is whether this phone has a lock screen, as far as
 * this read and every read before it know.
 */
data class PickupCount(val closed: List<CountedPickup>, val resumeFromMs: Long, val sawUnlock: Boolean)

/**
 * **Pure.** The usage log's pickups as the sync stores them, on top of [Pickups] (the loop check's reader, prompt 12), with
 * the session cursor's rule: **a read must start before the screen came on**, because [Pickups] leaves out a pickup it
 * sees no start of. So the next read resumes at the start of the earliest pickup still going — never at "the latest
 * event", and never at the sessions' cursor, which rests at an app's resume, after the screen came on.
 *
 * - **Only settled pickups leave.** One still going has no end; one that ended less than [SETTLE_MS] before the read's end
 *   may still be joined by an unlock (the Xiaomi order: screen on, keyguard *shown*, keyguard hidden, in one second — the
 *   "shown" ends the pickup the unlock then belongs to). Both are read again next time, whole.
 * - **Re-reading gives the same pickups.** A read from [resumeFromMs] builds what one long read would have.
 * - **A phone with no lock screen** never logs an unlock. Until one has been seen (12's `LoopPreferences.sawUnlock`, the
 *   same flag), a pickup counts as unlocked when an app other than [excluded] (the home screen, this app) came to the front
 *   during it — the loop check's rule.
 */
object PickupCounter {

    const val SETTLE_MS = SessionBuilder.MERGE_GAP_MS

    fun count(
        events: List<UsageEventRecord>,
        toMs: Long,
        sawUnlockBefore: Boolean,
        excluded: Set<String>
    ): PickupCount {
        val pickups = Pickups.from(events)
        val sawUnlock = sawUnlockBefore || pickups.any { it.unlocked }

        val settled = pickups.filter { it.endMs != null && it.endMs <= toMs - SETTLE_MS }
        val resumeFromMs = (pickups - settled.toSet()).minOfOrNull { it.startMs } ?: toMs

        val closed = settled.map { pickup ->
            val end = pickup.endMs!!
            val unlocked = if (sawUnlock) pickup.unlocked else openedAnApp(events, pickup.startMs, end, excluded)
            CountedPickup(pickup.startMs, end, unlocked)
        }
        return PickupCount(closed, resumeFromMs, sawUnlock)
    }

    private fun openedAnApp(events: List<UsageEventRecord>, startMs: Long, endMs: Long, excluded: Set<String>): Boolean =
        events.any { it.kind == EventKind.RESUMED && it.timestampMs in startMs..endMs && it.packageName !in excluded }
}
