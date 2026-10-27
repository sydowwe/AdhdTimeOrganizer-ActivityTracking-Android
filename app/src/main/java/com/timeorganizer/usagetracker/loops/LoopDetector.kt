package com.timeorganizer.usagetracker.loops

import com.timeorganizer.usagetracker.usagestats.Pickup
import com.timeorganizer.usagetracker.usagestats.SessionBuilder
import com.timeorganizer.usagetracker.usagestats.UsageEventRecord

/** A sitting in an app as the usage log has it; the one still in front ends at the read's end. */
data class LoopSitting(val packageName: String, val startMs: Long, val endMs: Long)

enum class LoopRule {
    /** [LoopDetector.PICKUPS] unlocks within [LoopDetector.PICKUP_WINDOW_MS]. */
    PICKUPS,

    /** [LoopDetector.HOP_APPS] different apps within [LoopDetector.HOP_WINDOW_MS], none of them for long. */
    HOPPING
}

/** What the loop check reads, all from the usage log except the user's own list. */
data class LoopFacts(
    val nowMs: Long,
    val pickups: List<Pickup>,
    val sittings: List<LoopSitting>,
    /** The home screen, this app, and apps with no launcher entry (system dialogs, an in-call screen): never a loop's app. */
    val excluded: Set<String>,
    /** "Apps that don't count" (chat apps, ticked by hand): a pickup that opens only these is no pickup, a hop to one is no hop. */
    val notCounted: Set<String>,
    /**
     * This phone has a lock screen: an unlock has been seen. On a phone without one the log never says "unlocked", so a
     * screen-on that brought an app to the front counts instead.
     */
    val phoneLocks: Boolean
)

/** A rule holds at [LoopFacts.nowMs]: the [rule] that does, with the pickups and apps counted in their windows. */
data class LoopSignal(val rule: LoopRule, val pickups: Int, val apps: Int)

/**
 * **Pure.** Whether the phone is being picked up in a loop right now, by either of two rules over a window ending now:
 *
 * - **Pickups:** [PICKUPS] unlocks in [PICKUP_WINDOW_MS]. Only unlocks count, so an alarm, a call answered over the lock
 *   screen, navigation showing over it and a glance at the clock are no pickups. No length rule: six unlocks in ten
 *   minutes are short ones already. A pickup that opened only [LoopFacts.notCounted] apps doesn't count; one that opened
 *   nothing but the home screen does.
 * - **Hopping:** [HOP_APPS] different apps in [HOP_WINDOW_MS], in a run of sittings each no longer than
 *   [HOP_MAX_SITTING_MS], going back from now. A longer sitting ends the run: that app was used for something.
 *
 * It knows nothing about what an app is — that is the server's (activity mapping) — only how the phone is being used.
 * Nothing that began before `countFromMs` counts: the last loop, the away timer.
 */
object LoopDetector {

    const val MINUTE_MS = 60_000L

    const val PICKUPS = 6
    const val PICKUP_WINDOW_MS = 10 * MINUTE_MS

    const val HOP_APPS = 6
    const val HOP_WINDOW_MS = 5 * MINUTE_MS
    const val HOP_MAX_SITTING_MS = 60_000L

    /** How far back the log is read: both windows and a sitting that began well before them. */
    const val LOOKBACK_MS = 60 * MINUTE_MS

    /** The sittings in [events], through the same [SessionBuilder] the recorded sessions come from. */
    fun sittings(events: List<UsageEventRecord>, nowMs: Long): List<LoopSitting> {
        val builder = SessionBuilder()
        events.forEach(builder::onEvent)
        val closed = builder.build(nowMs).sessions.map { LoopSitting(it.packageName, it.startMs, it.endMs) }
        val open = builder.openSessions()
            .filter { (_, startMs) -> startMs < nowMs }
            .map { (packageName, startMs) -> LoopSitting(packageName, startMs, nowMs) }
        return (closed + open).sortedBy { it.startMs }
    }

    fun detect(facts: LoopFacts, countFromMs: Long = Long.MIN_VALUE): LoopSignal? {
        val pickups = countedPickups(facts, countFromMs).size
        val apps = hoppedApps(facts, countFromMs)
        return when {
            pickups >= PICKUPS -> LoopSignal(LoopRule.PICKUPS, pickups, apps)
            apps >= HOP_APPS -> LoopSignal(LoopRule.HOPPING, pickups, apps)
            else -> null
        }
    }

    /** The pickups in the window that count. */
    fun countedPickups(facts: LoopFacts, countFromMs: Long = Long.MIN_VALUE): List<Pickup> {
        val fromMs = maxOf(facts.nowMs - PICKUP_WINDOW_MS, countFromMs)
        return facts.pickups
            .filter { it.startMs >= fromMs && it.startMs <= facts.nowMs }
            .filter { pickup ->
                val apps = appsOf(pickup, facts)
                val isPickup = if (facts.phoneLocks) pickup.unlocked else apps.isNotEmpty()
                isPickup && !(apps.isNotEmpty() && apps.all { it in facts.notCounted })
            }
    }

    /** The different apps in the run of short sittings that ends now; not-counted apps are in the run but not counted. */
    fun hoppedApps(facts: LoopFacts, countFromMs: Long = Long.MIN_VALUE): Int {
        val fromMs = maxOf(facts.nowMs - HOP_WINDOW_MS, countFromMs)
        val apps = mutableSetOf<String>()
        for (sitting in facts.sittings.sortedByDescending { it.startMs }) {
            if (sitting.packageName in facts.excluded || sitting.startMs > facts.nowMs) continue
            if (sitting.startMs < fromMs) break
            if (minOf(sitting.endMs, facts.nowMs) - sitting.startMs > HOP_MAX_SITTING_MS) break
            if (sitting.packageName !in facts.notCounted) apps += sitting.packageName
        }
        return apps.size
    }

    /** The apps that were in front during [pickup], the excluded ones left out. */
    private fun appsOf(pickup: Pickup, facts: LoopFacts): Set<String> {
        val endMs = pickup.endMs ?: facts.nowMs
        return facts.sittings
            .filter { it.packageName !in facts.excluded && it.startMs < endMs && it.endMs > pickup.startMs }
            .mapTo(mutableSetOf()) { it.packageName }
    }
}
