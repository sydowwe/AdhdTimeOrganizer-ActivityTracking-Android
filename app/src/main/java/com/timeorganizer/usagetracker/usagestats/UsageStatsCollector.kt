package com.timeorganizer.usagetracker.usagestats

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager

/**
 * Reads the OS usage-event log for a window and feeds it to [SessionBuilder]. Everything that decides what a
 * session is lives in the builder; this class only translates event types and resolves app labels.
 */
class UsageStatsCollector(context: Context) {

    private val usageStatsManager =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

    private val packageManager: PackageManager = context.packageManager
    private val appLabelCache = mutableMapOf<String, String?>()

    fun collect(fromMs: Long, toMs: Long): BuildResult {
        val builder = SessionBuilder()
        val events = usageStatsManager.queryEvents(fromMs, toMs) ?: return builder.build(fromMs)
        val event = UsageEvents.Event()

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val packageName = event.packageName ?: continue
            builder.onEvent(
                UsageEventRecord(
                    kind = kindOf(event.eventType),
                    packageName = packageName,
                    className = event.className.orEmpty(),
                    timestampMs = event.timeStamp
                )
            )
        }

        return builder.build(toMs)
    }

    /**
     * The label as the launcher shows it, resolved when the session is built rather than when it is sent, so
     * an app uninstalled in between still has its name. `null` when the package is already gone;
     * `SyncLimits` falls back to the package name.
     */
    fun resolveLabel(packageName: String): String? = appLabelCache.getOrPut(packageName) {
        try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    private fun kindOf(eventType: Int): EventKind = when (eventType) {
        UsageEvents.Event.ACTIVITY_RESUMED -> EventKind.RESUMED
        UsageEvents.Event.ACTIVITY_PAUSED -> EventKind.PAUSED
        UsageEvents.Event.SCREEN_NON_INTERACTIVE,
        UsageEvents.Event.KEYGUARD_SHOWN,
        UsageEvents.Event.DEVICE_SHUTDOWN -> EventKind.CLOSE_ALL
        UsageEvents.Event.DEVICE_STARTUP -> EventKind.DEVICE_STARTUP
        else -> EventKind.OTHER
    }
}
