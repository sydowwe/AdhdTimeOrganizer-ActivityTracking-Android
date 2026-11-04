package com.timeorganizer.usagetracker.today

import com.timeorganizer.usagetracker.pause.PauseClipper
import com.timeorganizer.usagetracker.pause.PauseRange
import com.timeorganizer.usagetracker.sync.SyncLimits
import com.timeorganizer.usagetracker.usagestats.RawSession
import com.timeorganizer.usagetracker.usagestats.SessionBuilder
import com.timeorganizer.usagetracker.usagestats.UsageEventRecord
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One app's time today; each app keeps all of its own time, so split-screen shows in both. */
data class AppToday(val packageName: String, val ms: Long)

/**
 * **Pure.** This phone's time today (prompt 19, the widget): the total and the busiest apps, from the usage log rather than
 * the outbox, whose sent rows are gone by the next run (decision 19-1). The same rules as what the sync sends, so the figure
 * agrees with the dashboards: [SessionBuilder]'s sittings, without the home screen and ignored apps, paused time clipped out,
 * then the 5 s floor. The app in front counts up to now.
 *
 * - **The day is the device's**, from local midnight through [ZoneId], so a 23- or 25-hour day and a zone change are right.
 * - **A sitting across midnight counts its part after it.** A read starting at midnight would never see that sitting's resume
 *   and would lose it, so the read starts [LOOKBACK_MS] earlier ([readFromMs]). The floor is the whole sitting's, as the sync's
 *   is, so 12 s across midnight counts the 2 s after it.
 * - **[totalMs] is the union of the sittings** (split-screen counts once), like the daily limits' figure.
 */
data class TodaySummary(val totalMs: Long, val top: List<AppToday>) {

    companion object {
        /** How many apps the widget lists. */
        const val TOP_APPS = 3

        /** A sitting that began further back than this before midnight, and is still going, is missed: six hours in front. */
        const val LOOKBACK_MS = 6 * 60 * 60 * 1000L

        fun dayStartMs(nowMs: Long, zone: ZoneId): Long = startOf(day(nowMs, zone), zone)

        fun nextDayStartMs(nowMs: Long, zone: ZoneId): Long = startOf(day(nowMs, zone).plusDays(1), zone)

        /** Where the read for [from] must begin. */
        fun readFromMs(nowMs: Long, zone: ZoneId): Long = dayStartMs(nowMs, zone) - LOOKBACK_MS

        /** [events] from [readFromMs] to [nowMs], in the log's order. */
        fun from(
            events: List<UsageEventRecord>,
            nowMs: Long,
            zone: ZoneId,
            homePackages: Set<String>,
            ignoredPackages: Set<String>,
            pauses: List<PauseRange>
        ): TodaySummary {
            val builder = SessionBuilder()
            events.forEach(builder::onEvent)
            val built = builder.build(nowMs)
            val open = builder.openSessions()
                .filter { (_, startMs) -> startMs < nowMs }
                .map { (packageName, startMs) -> RawSession(packageName, startMs, nowMs) }
            val sessions = PauseClipper.sessions(built.copy(sessions = built.sessions + open).forOutbox(homePackages, ignoredPackages), pauses)
                .sessions.filter { it.endMs - it.startMs >= SyncLimits.MIN_SESSION_MS }
            return of(sessions, dayStartMs(nowMs, zone), nowMs)
        }

        /** [sessions] clipped to `[dayStartMs, nowMs]`, busiest app first; equal times by package, so the order never flickers. */
        fun of(sessions: List<RawSession>, dayStartMs: Long, nowMs: Long): TodaySummary {
            val today = sessions.mapNotNull { session ->
                val startMs = maxOf(session.startMs, dayStartMs)
                val endMs = minOf(session.endMs, nowMs)
                session.copy(startMs = startMs, endMs = endMs).takeIf { endMs > startMs }
            }
            val apps = today.groupBy { it.packageName }
                .map { (packageName, own) -> AppToday(packageName, own.sumOf { it.endMs - it.startMs }) }
                .sortedWith(compareByDescending<AppToday> { it.ms }.thenBy { it.packageName })
            return TodaySummary(unionMs(today), apps.take(TOP_APPS))
        }

        private fun unionMs(sessions: List<RawSession>): Long {
            var totalMs = 0L
            var coveredToMs = Long.MIN_VALUE
            for (session in sessions.sortedBy { it.startMs }) {
                val startMs = maxOf(session.startMs, coveredToMs)
                if (session.endMs > startMs) totalMs += session.endMs - startMs
                coveredToMs = maxOf(coveredToMs, session.endMs)
            }
            return totalMs
        }

        private fun day(nowMs: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()

        // `atStartOfDay`, not midnight: a zone that moves its clocks at midnight has a day starting at 01:00.
        private fun startOf(day: LocalDate, zone: ZoneId): Long = day.atStartOfDay(zone).toInstant().toEpochMilli()
    }
}
