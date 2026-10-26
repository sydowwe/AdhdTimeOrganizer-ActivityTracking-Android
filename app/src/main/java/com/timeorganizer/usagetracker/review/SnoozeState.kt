package com.timeorganizer.usagetracker.review

/**
 * **Pure.** The review's snoozed apps: "ask again in a week" on a card. Package → the instant the snooze ends. Kept on
 * the phone (`ReviewPreferences.snoozes`) — one device's question, not the user's data — and sent as `unmapped?exclude=` while in
 * force, so the server leaves those packages out of the list *and* of its "worth asking" threshold (a snoozed app must
 * not tip a quiet evening into a notification with nothing on it).
 */
data class SnoozeState(val until: Map<String, Long> = emptyMap()) {

    /** The packages still snoozed at [nowMs]; what the review sends as `exclude`, sorted so the URL is stable. */
    fun active(nowMs: Long): List<String> = until.entries
        .filter { it.value > nowMs }
        .sortedByDescending { it.value }
        .take(MAX_EXCLUDED)
        .map { it.key }
        .sorted()

    /** Snoozes [packageName] for [SNOOZE_MS] from [nowMs]; a second snooze extends rather than shortens. */
    fun snooze(packageName: String, nowMs: Long): SnoozeState {
        val end = maxOf(nowMs + SNOOZE_MS, until[packageName] ?: 0L)
        return copy(until = expired(nowMs).until + (packageName to end))
    }

    /** Forgets [packageName] — it was mapped or ignored, so the snooze has nothing left to hold back. */
    fun clear(packageName: String): SnoozeState = copy(until = until - packageName)

    /** Without the ended snoozes, so the stored map doesn't grow with every app ever snoozed. */
    fun expired(nowMs: Long): SnoozeState = copy(until = until.filterValues { it > nowMs })

    companion object {
        const val SNOOZE_MS = 7 * 24 * 60 * 60 * 1000L

        /** The server takes at most this many (`AndroidUnmappedTime.MaxExcluded`); beyond it the oldest ends are dropped. */
        const val MAX_EXCLUDED = 100
    }
}
