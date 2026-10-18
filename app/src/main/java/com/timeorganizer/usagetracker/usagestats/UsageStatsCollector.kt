package com.timeorganizer.usagetracker.usagestats

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import com.timeorganizer.usagetracker.data.api.dto.AndroidSessionDto
import com.timeorganizer.usagetracker.util.Constants
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class UsageStatsCollector(private val context: Context) {

    private val usageStatsManager =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

    private val packageManager: PackageManager = context.packageManager
    private val appLabelCache = mutableMapOf<String, String>()

    private val isoFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
        .withZone(ZoneOffset.UTC)

    /**
     * Collects all completed app sessions between [fromMs] and [toMs].
     * Returns them as DTOs ready to post to the backend.
     * [toMs] is the "synced up to" timestamp — caller should persist this on success.
     */
    fun collectSessions(fromMs: Long, toMs: Long): Pair<List<AndroidSessionDto>, Long> {
        val events = usageStatsManager.queryEvents(fromMs, toMs)
        val event = UsageEvents.Event()
        val builder = SessionBuilder()
        var latestEventTimestamp = fromMs

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val ts = event.timeStamp
            if (ts > latestEventTimestamp) latestEventTimestamp = ts

            when (event.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> builder.onForeground(event.packageName, ts)
                UsageEvents.Event.MOVE_TO_BACKGROUND -> builder.onBackground(event.packageName, ts)
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> builder.onScreenOff(ts)
                // SCREEN_INTERACTIVE (16) and all others are intentionally ignored
            }
        }

        // Discard still-open sessions — the app is still in foreground; capture next sync.

        val dtos = builder.getCompletedSessions()
            .filter { it.durationSeconds >= Constants.MIN_SESSION_DURATION_SECONDS }
            .map { session ->
                AndroidSessionDto(
                    packageName = session.packageName,
                    appLabel = resolveLabel(session.packageName),
                    sessionStartUtc = isoFormatter.format(Instant.ofEpochMilli(session.startMs)),
                    sessionEndUtc = isoFormatter.format(Instant.ofEpochMilli(session.endMs)),
                    durationSeconds = session.durationSeconds
                )
            }

        return Pair(dtos, latestEventTimestamp)
    }

    private fun resolveLabel(packageName: String): String {
        appLabelCache[packageName]?.let { return it }
        val label = try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            packageName
        }
        appLabelCache[packageName] = label
        return label
    }
}
