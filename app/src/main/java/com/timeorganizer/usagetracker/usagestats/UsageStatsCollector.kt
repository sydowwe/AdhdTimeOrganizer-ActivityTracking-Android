package com.timeorganizer.usagetracker.usagestats

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import com.timeorganizer.usagetracker.util.InstalledApps

/**
 * Reads the OS usage-event log for a window and feeds it to [SessionBuilder]. Everything that decides what a
 * session is lives in the builder; this class only translates event types and resolves app labels.
 */
class UsageStatsCollector(context: Context) {

    private val usageStatsManager =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

    private val installedApps = InstalledApps(context)

    /**
     * The home screen and Recents are left out of what is sent (client policy, like `SyncLimits.MIN_SESSION_MS`), and
     * so are the [ignoredPackages] (the user's ignore rules, `IgnoreState.ignored`). Both still count as "another app"
     * inside [SessionBuilder]'s merge rule.
     */
    fun collect(fromMs: Long, toMs: Long, ignoredPackages: Set<String> = emptySet()): BuildResult {
        val builder = SessionBuilder()
        if (!forEachEvent(fromMs, toMs, builder::onEvent)) return builder.build(fromMs)
        return builder.build(toMs).forOutbox(installedApps.homePackages, ignoredPackages)
    }

    /** The events from [fromMs] (inclusive) to [toMs] (exclusive), translated the same way — for the watcher. */
    fun events(fromMs: Long, toMs: Long): List<UsageEventRecord> = buildList { forEachEvent(fromMs, toMs, ::add) }

    /** `false` when the log could not be read at all. */
    private inline fun forEachEvent(fromMs: Long, toMs: Long, action: (UsageEventRecord) -> Unit): Boolean {
        val events = usageStatsManager.queryEvents(fromMs, toMs) ?: return false
        val event = UsageEvents.Event()

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val packageName = event.packageName ?: continue
            action(
                UsageEventRecord(
                    kind = kindOf(event.eventType),
                    packageName = packageName,
                    className = event.className.orEmpty(),
                    timestampMs = event.timeStamp
                )
            )
        }
        return true
    }

    /**
     * The label as the launcher shows it, resolved when the session is built rather than when it is sent, so
     * an app uninstalled in between still has its name. `null` when the package is already gone, or has no launcher
     * entry (the manifest's `<queries>` hide it); `SyncLimits` falls back to the package name.
     */
    fun resolveLabel(packageName: String): String? = installedApps.label(packageName)

    private fun kindOf(eventType: Int): EventKind = when (eventType) {
        UsageEvents.Event.ACTIVITY_RESUMED -> EventKind.RESUMED
        UsageEvents.Event.ACTIVITY_PAUSED -> EventKind.PAUSED
        UsageEvents.Event.ACTIVITY_STOPPED -> EventKind.STOPPED
        UsageEvents.Event.SCREEN_NON_INTERACTIVE,
        UsageEvents.Event.KEYGUARD_SHOWN,
        UsageEvents.Event.DEVICE_SHUTDOWN -> EventKind.CLOSE_ALL
        UsageEvents.Event.DEVICE_STARTUP -> EventKind.DEVICE_STARTUP
        else -> EventKind.OTHER
    }
}
