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

    private val ownPackage = context.packageName

    /** One read of the log for the sync: the sessions from their cursor, the pickups (prompt 14) from theirs. */
    data class Collected(val sessions: BuildResult, val pickups: PickupCount)

    /**
     * The home screen and Recents are left out of what is sent (client policy, like `SyncLimits.MIN_SESSION_MS`), and
     * so are the [ignoredPackages] (the user's ignore rules, `IgnoreState.ignored`). Both still count as "another app"
     * inside [SessionBuilder]'s merge rule.
     *
     * The log is read once, from the earlier of the two cursors: [SessionBuilder] gets only the events from [fromMs] (it
     * must not see a sitting twice), [PickupCounter] everything from [pickupsFromMs] — which usually lies before [fromMs],
     * since a pickup begins before the app it opens.
     */
    fun collect(
        fromMs: Long,
        toMs: Long,
        ignoredPackages: Set<String>,
        pickupsFromMs: Long,
        sawUnlock: Boolean
    ): Collected {
        val builder = SessionBuilder()
        val pickupEvents = mutableListOf<UsageEventRecord>()
        val read = forEachEvent(minOf(fromMs, pickupsFromMs), toMs) { event ->
            if (event.timestampMs >= fromMs) builder.onEvent(event)
            if (event.timestampMs >= pickupsFromMs) pickupEvents += event
        }
        if (!read) return Collected(builder.build(fromMs), PickupCount(emptyList(), pickupsFromMs, sawUnlock))

        return Collected(
            builder.build(toMs).forOutbox(installedApps.homePackages, ignoredPackages),
            PickupCounter.count(pickupEvents, toMs, sawUnlock, installedApps.homePackages + ownPackage)
        )
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
        UsageEvents.Event.SCREEN_INTERACTIVE -> EventKind.SCREEN_ON
        UsageEvents.Event.KEYGUARD_HIDDEN -> EventKind.UNLOCKED
        else -> EventKind.OTHER
    }
}
