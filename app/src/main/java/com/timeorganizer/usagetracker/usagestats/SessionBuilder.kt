package com.timeorganizer.usagetracker.usagestats

/**
 * Accumulates raw foreground/background events for a single package and produces
 * completed [RawSession] objects as sessions close.
 */
class SessionBuilder {

    private val openSessions = mutableMapOf<String, Long>() // packageName -> foreground start ms
    private val completedSessions = mutableListOf<RawSession>()

    /**
     * App moved to foreground. Records session start. If another app was already open
     * (shouldn't happen normally, but guards against missing background events), this
     * call does not implicitly close others — callers must iterate all open sessions
     * when needed.
     */
    fun onForeground(packageName: String, timestampMs: Long) {
        // If there's already an open session (e.g. FOREGROUND without a prior BACKGROUND),
        // close it implicitly at this timestamp to avoid duplication.
        openSessions[packageName]?.let { start ->
            if (timestampMs > start) {
                completedSessions.add(RawSession(packageName, start, timestampMs))
            }
        }
        openSessions[packageName] = timestampMs
    }

    /**
     * App moved to background. Closes any open session for this package.
     */
    fun onBackground(packageName: String, timestampMs: Long) {
        val start = openSessions.remove(packageName) ?: return // orphaned background — skip
        if (timestampMs > start) {
            completedSessions.add(RawSession(packageName, start, timestampMs))
        }
    }

    /**
     * Screen turned off — close ALL open sessions at this timestamp.
     */
    fun onScreenOff(timestampMs: Long) {
        val iterator = openSessions.entries.iterator()
        while (iterator.hasNext()) {
            val (pkg, start) = iterator.next()
            iterator.remove()
            if (timestampMs > start) {
                completedSessions.add(RawSession(pkg, start, timestampMs))
            }
        }
    }

    fun getCompletedSessions(): List<RawSession> = completedSessions.toList()
}

data class RawSession(
    val packageName: String,
    val startMs: Long,
    val endMs: Long
) {
    val durationSeconds: Long get() = (endMs - startMs) / 1000L
}
