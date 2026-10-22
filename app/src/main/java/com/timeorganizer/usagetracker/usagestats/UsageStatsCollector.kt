package com.timeorganizer.usagetracker.usagestats

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
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

    /**
     * The home screen and Recents are the launcher's activities, so the log counts them as an app in front. They
     * are the gaps between apps, not something the person used: left out of what is sent (client policy, like
     * `SyncLimits.MIN_SESSION_MS`). They still count as "another app" inside [SessionBuilder]'s merge rule.
     */
    private val homePackages: Set<String> by lazy {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        packageManager.queryIntentActivities(home, 0).map { it.activityInfo.packageName }.toSet()
    }

    fun collect(fromMs: Long, toMs: Long): BuildResult {
        val builder = SessionBuilder()
        if (!forEachEvent(fromMs, toMs, builder::onEvent)) return builder.build(fromMs)
        return builder.build(toMs).without(homePackages)
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
        UsageEvents.Event.ACTIVITY_STOPPED -> EventKind.STOPPED
        UsageEvents.Event.SCREEN_NON_INTERACTIVE,
        UsageEvents.Event.KEYGUARD_SHOWN,
        UsageEvents.Event.DEVICE_SHUTDOWN -> EventKind.CLOSE_ALL
        UsageEvents.Event.DEVICE_STARTUP -> EventKind.DEVICE_STARTUP
        else -> EventKind.OTHER
    }
}
