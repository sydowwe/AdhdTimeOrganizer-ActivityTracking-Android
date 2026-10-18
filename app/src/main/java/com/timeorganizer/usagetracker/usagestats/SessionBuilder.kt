package com.timeorganizer.usagetracker.usagestats

/**
 * Turns the device's usage-event stream into per-app sessions. Pure: no Android types and no clock, so the
 * accounting is covered by JVM tests.
 *
 * - **A session is an app, not an activity.** `ACTIVITY_RESUMED` / `ACTIVITY_PAUSED` fire per activity, so the
 *   builder counts each package's resumed activities and the app's session runs from its first resume until
 *   the last of them pauses. Keying on the package alone split a session at every screen change inside an
 *   app, and with multi-resume (Android 10+) the old activity's late pause closed the new one's session.
 * - **A pause followed by a resume of the same app within [mergeGapMs]**, with no other app resumed in
 *   between, is one sitting rather than two.
 * - **Screen off, keyguard and shutdown close everything.** After `DEVICE_STARTUP`, whatever is still open is
 *   closed at the last event before it: the time in between was the phone being off.
 * - **Split-screen counts both visible apps; picture-in-picture counts nothing** (a PiP activity is paused).
 *   Both are deliberate (2026-10-18).
 *
 * The caller starts its next query at [BuildResult.resumeFromMs] — the start of the earliest session still
 * open — so the event that opened it is read again and the session is built whole once it closes.
 */
class SessionBuilder(private val mergeGapMs: Long = MERGE_GAP_MS) {

    private class OpenSession(val startMs: Long) {
        val resumedActivities = mutableMapOf<String, Int>()
    }

    private val open = linkedMapOf<String, OpenSession>()

    // Completed sessions; an entry is nulled when a quick re-resume reopens it.
    private val completed = mutableListOf<RawSession?>()
    private val lastClosedIndex = mutableMapOf<String, Int>()
    private val lastResumeMs = mutableMapOf<String, Long>()
    private var lastEventMs: Long? = null

    fun onEvent(event: UsageEventRecord) {
        when (event.kind) {
            EventKind.RESUMED -> onResumed(event.packageName, event.className, event.timestampMs)
            EventKind.PAUSED -> onPaused(event.packageName, event.className, event.timestampMs)
            EventKind.CLOSE_ALL -> closeAll(event.timestampMs)
            EventKind.DEVICE_STARTUP -> {
                val beforeBoot = lastEventMs
                if (beforeBoot != null) closeAll(beforeBoot) else open.clear()
            }
            EventKind.OTHER -> Unit
        }
        lastEventMs = maxOf(lastEventMs ?: event.timestampMs, event.timestampMs)
    }

    /** The sessions that closed inside the window, in start order, and where the next query must begin. */
    fun build(queryEndMs: Long): BuildResult = BuildResult(
        sessions = completed.filterNotNull().sortedBy { it.startMs },
        resumeFromMs = open.values.minOfOrNull { it.startMs } ?: queryEndMs
    )

    /** The apps whose session is open right now, with when it started — what `ForegroundTracker` watches. */
    fun openSessions(): Map<String, Long> = open.mapValues { it.value.startMs }

    private fun onResumed(packageName: String, className: String, timestampMs: Long) {
        val session = open.getOrPut(packageName) { reopenOrStart(packageName, timestampMs) }
        session.resumedActivities.merge(className, 1, Int::plus)
        lastResumeMs[packageName] = timestampMs
    }

    private fun reopenOrStart(packageName: String, timestampMs: Long): OpenSession {
        val index = lastClosedIndex[packageName]
        val previous = index?.let { completed[it] }
        if (index != null && previous != null &&
            timestampMs - previous.endMs <= mergeGapMs &&
            lastResumeMs.none { (pkg, at) -> pkg != packageName && at >= previous.endMs }
        ) {
            completed[index] = null
            lastClosedIndex.remove(packageName)
            return OpenSession(previous.startMs)
        }
        return OpenSession(timestampMs)
    }

    private fun onPaused(packageName: String, className: String, timestampMs: Long) {
        // No open session: it was resumed before this window began, so it was already built whole.
        val session = open[packageName] ?: return
        val count = session.resumedActivities[className] ?: return
        if (count > 1) session.resumedActivities[className] = count - 1
        else session.resumedActivities.remove(className)

        if (session.resumedActivities.isEmpty()) {
            open.remove(packageName)
            close(packageName, session.startMs, timestampMs)
        }
    }

    private fun closeAll(timestampMs: Long) {
        for ((packageName, session) in open) close(packageName, session.startMs, timestampMs)
        open.clear()
    }

    private fun close(packageName: String, startMs: Long, endMs: Long) {
        if (endMs <= startMs) return
        completed.add(RawSession(packageName, startMs, endMs))
        lastClosedIndex[packageName] = completed.lastIndex
    }

    companion object {
        /** Activity hand-offs inside an app take milliseconds; two seconds also absorbs a dismissed dialog. */
        const val MERGE_GAP_MS = 2_000L
    }
}

enum class EventKind { RESUMED, PAUSED, CLOSE_ALL, DEVICE_STARTUP, OTHER }

data class UsageEventRecord(
    val kind: EventKind,
    val packageName: String,
    val className: String,
    val timestampMs: Long
)

data class RawSession(
    val packageName: String,
    val startMs: Long,
    val endMs: Long
)

data class BuildResult(
    val sessions: List<RawSession>,
    val resumeFromMs: Long
)
