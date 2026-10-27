package com.timeorganizer.usagetracker.usagestats

/**
 * One time the phone was picked up: from the screen coming on (or the lock screen going away, whichever the log shows
 * first) until the screen went off, the keyguard came back or the phone shut down. [unlocked] when the lock screen was
 * dismissed during it; a glance at the clock, an alarm or a call answered over the lock screen is not. [endMs] is `null`
 * while it is still going at the end of what was read.
 */
data class Pickup(val startMs: Long, val endMs: Long?, val unlocked: Boolean)

/**
 * **Pure.** The usage log's pickups, from the same events [SessionBuilder] reads: `SCREEN_INTERACTIVE` and
 * `KEYGUARD_HIDDEN` start one ([EventKind.SCREEN_ON], [EventKind.UNLOCKED]), and the session builder's own
 * [EventKind.CLOSE_ALL] ends it. Built for the loop check (prompt 12) and meant for the per-hour pickup counts too
 * (prompt 14): unlocks are the pickups with [Pickup.unlocked], screen-ons without unlock the rest.
 *
 * - A window that begins mid-pickup sees no start: the pickup is left out until an event starts one. A re-read from
 *   earlier builds it whole, so a caller that counts must read from before the screen came on.
 * - After `DEVICE_STARTUP`, a pickup still open is closed at the last event before it, as the session builder does.
 */
object Pickups {

    fun from(events: List<UsageEventRecord>): List<Pickup> {
        val pickups = mutableListOf<Pickup>()
        var startMs: Long? = null
        var unlocked = false
        var lastEventMs: Long? = null
        // The last pickup, if it closed before any unlock: an unlock right after it is the same pickup.
        var closedLocked: Pickup? = null

        fun close(atMs: Long) {
            val start = startMs ?: return
            val pickup = Pickup(start, maxOf(start, atMs), unlocked)
            pickups += pickup
            closedLocked = pickup.takeIf { !unlocked }
            startMs = null
            unlocked = false
        }

        // In the log's order, as the session builder takes them.
        for (event in events) {
            when (event.kind) {
                EventKind.SCREEN_ON -> if (startMs == null) {
                    startMs = event.timestampMs
                    closedLocked = null
                }
                EventKind.UNLOCKED -> {
                    if (startMs == null) {
                        // Seen on a Xiaomi (Android 13): screen on, keyguard *shown*, keyguard hidden, in one second. The
                        // "shown" closes the pickup the screen-on began; the unlock belongs to it, not to a new one.
                        val previous = closedLocked
                        if (previous?.endMs != null && event.timestampMs - previous.endMs <= SessionBuilder.MERGE_GAP_MS) {
                            pickups.removeAt(pickups.lastIndex)
                            startMs = previous.startMs
                        } else {
                            startMs = event.timestampMs
                        }
                    }
                    unlocked = true
                    closedLocked = null
                }
                EventKind.CLOSE_ALL -> close(event.timestampMs)
                EventKind.DEVICE_STARTUP -> {
                    val beforeBoot = lastEventMs
                    if (beforeBoot != null) close(beforeBoot) else startMs = null
                    closedLocked = null
                }
                EventKind.RESUMED, EventKind.PAUSED, EventKind.STOPPED, EventKind.OTHER -> Unit
            }
            lastEventMs = event.timestampMs
        }
        startMs?.let { pickups += Pickup(it, null, unlocked) }
        return pickups
    }
}
