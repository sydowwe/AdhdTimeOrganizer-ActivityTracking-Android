package com.timeorganizer.usagetracker.watch

import com.timeorganizer.usagetracker.usagestats.SessionBuilder
import com.timeorganizer.usagetracker.usagestats.UsageEventRecord

sealed interface ForegroundChange {
    val packageName: String
    val atMs: Long

    data class CameToFront(override val packageName: String, override val atMs: Long) : ForegroundChange
    data class LeftFront(override val packageName: String, override val atMs: Long) : ForegroundChange
}

/**
 * Turns usage events, fed as they arrive, into "this app came to the front / left it" — pure, so it is pinned
 * by JVM tests.
 *
 * What counts as in front is exactly what [SessionBuilder] counts as an open session: an app rather than an
 * activity, screen off ends everything, split-screen has two apps in front. The builder is reused, not
 * copied, so the watcher and the recorded sessions cannot disagree. Only the timing is the tracker's own: an
 * app that closes is reported as having left only once it is clear it did not come straight back — after
 * [mergeGapMs] with no return, or as soon as another app comes to the front — which is the builder's own
 * rule for "the same sitting". So a hand-off between two screens of one app never reports a change.
 *
 * Feed each event exactly once: a repeated resume leaves an app open for good.
 */
class ForegroundTracker(private val mergeGapMs: Long = SessionBuilder.MERGE_GAP_MS) {

    private val builder = SessionBuilder(mergeGapMs)

    // Reported as in front, with the start of the session that put them there.
    private val inFront = mutableMapOf<String, Long>()

    // Closed but not yet reported: they may still turn out to be the same sitting.
    private val leaving = mutableMapOf<String, Long>()

    /** What is in front now, from the events before watching began. Nothing earlier is reported. */
    fun prime(events: List<UsageEventRecord>): List<ForegroundChange> {
        events.forEach(builder::onEvent)
        val open = builder.openSessions()
        inFront.putAll(open)
        return open.entries.sortedBy { it.value }.map { ForegroundChange.CameToFront(it.key, it.value) }
    }

    /** The changes in [events], which follow the ones already fed; [nowMs] is where the read ended. */
    fun advance(events: List<UsageEventRecord>, nowMs: Long): List<ForegroundChange> {
        val changes = mutableListOf<ForegroundChange>()
        for (event in events) {
            settleLeaving(event.timestampMs, changes)
            val before = builder.openSessions().keys
            builder.onEvent(event)
            val after = builder.openSessions()

            for (packageName in before - after.keys) {
                if (packageName in inFront) leaving[packageName] = event.timestampMs
            }
            for ((packageName, startMs) in after) {
                if (packageName in before) continue
                // Another app in front ends every other app's chance to be the same sitting.
                for (other in leaving.keys.filter { it != packageName }) leave(other, changes)
                val leftAtMs = leaving.remove(packageName)
                if (leftAtMs != null && inFront[packageName] == startMs) continue // the same sitting, reopened
                if (leftAtMs != null) changes += ForegroundChange.LeftFront(packageName, leftAtMs)
                inFront[packageName] = startMs
                changes += ForegroundChange.CameToFront(packageName, startMs)
            }
        }
        settleLeaving(nowMs, changes)
        return changes
    }

    /** The apps in front right now. */
    fun current(): Set<String> = inFront.keys - leaving.keys

    private fun settleLeaving(nowMs: Long, changes: MutableList<ForegroundChange>) {
        for ((packageName, leftAtMs) in leaving.entries.sortedBy { it.value }) {
            if (nowMs - leftAtMs > mergeGapMs) leave(packageName, changes)
        }
    }

    private fun leave(packageName: String, changes: MutableList<ForegroundChange>) {
        val leftAtMs = leaving.remove(packageName) ?: return
        inFront.remove(packageName)
        changes += ForegroundChange.LeftFront(packageName, leftAtMs)
    }
}
