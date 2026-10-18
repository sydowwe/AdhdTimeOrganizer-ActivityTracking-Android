package com.timeorganizer.usagetracker.watch

/**
 * Who has asked the watcher to run, and until when — pure. The watcher runs while any reason is active; a
 * reason asked for again moves its end, and one whose end has passed counts as gone.
 */
data class WatchReasons(val untilMsByReason: Map<String, Long> = emptyMap()) {

    fun watch(reason: String, untilMs: Long): WatchReasons = copy(untilMsByReason = untilMsByReason + (reason to untilMs))

    fun stop(reason: String): WatchReasons = copy(untilMsByReason = untilMsByReason - reason)

    fun active(nowMs: Long): WatchReasons = copy(untilMsByReason = untilMsByReason.filterValues { it > nowMs })

    fun isActive(nowMs: Long): Boolean = untilMsByReason.values.any { it > nowMs }

    /** When the last active reason ends — what the notification shows; `null` when none is active. */
    fun endsAtMs(nowMs: Long): Long? = untilMsByReason.values.filter { it > nowMs }.maxOrNull()

    /** When the next active reason ends — when the watcher has to look again; `null` when none is active. */
    fun nextEndMs(nowMs: Long): Long? = untilMsByReason.values.filter { it > nowMs }.minOrNull()
}
