package com.timeorganizer.usagetracker.today

import com.timeorganizer.usagetracker.pause.PauseRange
import com.timeorganizer.usagetracker.usagestats.EventKind
import com.timeorganizer.usagetracker.usagestats.UsageEventRecord
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * This phone's time today (prompt 19). The day under test is 2026-11-04 in Bratislava; events are read as the widget reads
 * them, from six hours before midnight.
 */
class TodaySummaryTest {

    private val zone = ZoneId.of("Europe/Bratislava")

    private fun at(hour: Int, minute: Int = 0, second: Int = 0, day: Int = 4) =
        LocalDateTime.of(2026, 11, day, hour, minute, second).atZone(zone).toInstant().toEpochMilli()

    private fun event(kind: EventKind, pkg: String, atMs: Long) = UsageEventRecord(kind, pkg, "$pkg.Main", atMs)

    private fun sitting(pkg: String, fromMs: Long, toMs: Long) =
        listOf(event(EventKind.RESUMED, pkg, fromMs), event(EventKind.PAUSED, pkg, toMs))

    private fun summary(
        events: List<UsageEventRecord>,
        nowMs: Long = at(12),
        home: Set<String> = emptySet(),
        ignored: Set<String> = emptySet(),
        pauses: List<PauseRange> = emptyList()
    ) = TodaySummary.from(events.sortedBy { it.timestampMs }, nowMs, zone, home, ignored, pauses)

    private fun minutes(count: Int) = count * 60_000L

    // ---- the day ------------------------------------------------------------------------------------------------

    @Test
    fun aSittingToday_countsInTheTotalAndTheApp() {
        val today = summary(sitting("com.reddit", at(9), at(9, 30)))

        assertEquals(minutes(30), today.totalMs)
        assertEquals(listOf(AppToday("com.reddit", minutes(30))), today.top)
    }

    @Test
    fun aSittingThatEndedBeforeMidnight_countsForNothing() {
        val today = summary(sitting("com.reddit", at(22, day = 3), at(23, day = 3)))

        assertEquals(TodaySummary(0L, emptyList()), today)
    }

    @Test
    fun aSittingAcrossMidnight_countsOnlyItsPartAfterIt() {
        val today = summary(sitting("com.reddit", at(23, 50, day = 3), at(0, 20)))

        assertEquals(minutes(20), today.totalMs)
        assertEquals(listOf(AppToday("com.reddit", minutes(20))), today.top)
    }

    @Test
    fun anAppInFrontSinceBeforeMidnight_countsFromMidnightToNow() {
        val today = summary(listOf(event(EventKind.RESUMED, "com.reddit", at(23, 50, day = 3))), nowMs = at(0, 15))

        assertEquals(minutes(15), today.totalMs)
    }

    @Test
    fun theReadReachesBeforeMidnight_soTheResumeIsSeen() {
        assertEquals(at(18, day = 3), TodaySummary.readFromMs(at(12), zone))
    }

    @Test
    fun justBeforeMidnight_theDayIsStillThatDay() {
        val beforeMidnight = at(23, 59, 59)

        assertEquals(at(0), TodaySummary.dayStartMs(beforeMidnight, zone))
        assertEquals(at(0, day = 5), TodaySummary.nextDayStartMs(beforeMidnight, zone))
    }

    @Test
    fun atMidnightExactly_theNewDayHasBegun() {
        assertEquals(at(0, day = 5), TodaySummary.dayStartMs(at(0, day = 5), zone))
    }

    @Test
    fun theDayIsTheDevicesZone_notUtc() {
        // 23:30 UTC on the 3rd is already the 4th in Bratislava and still the 3rd in New York.
        val instant = LocalDateTime.of(2026, 11, 3, 23, 30).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli()
        val newYork = ZoneId.of("America/New_York")

        assertEquals(at(0), TodaySummary.dayStartMs(instant, zone))
        assertEquals(
            LocalDateTime.of(2026, 11, 3, 0, 0).atZone(newYork).toInstant().toEpochMilli(),
            TodaySummary.dayStartMs(instant, newYork)
        )
    }

    @Test
    fun theDayTheClocksGoForward_is23HoursLong() {
        val march = LocalDateTime.of(2026, 3, 29, 12, 0).atZone(zone).toInstant().toEpochMilli()

        assertEquals(23 * 60 * minutes(1), TodaySummary.nextDayStartMs(march, zone) - TodaySummary.dayStartMs(march, zone))
    }

    @Test
    fun theDayTheClocksGoBack_is25HoursLong() {
        val october = LocalDateTime.of(2026, 10, 25, 12, 0).atZone(zone).toInstant().toEpochMilli()

        assertEquals(25 * 60 * minutes(1), TodaySummary.nextDayStartMs(october, zone) - TodaySummary.dayStartMs(october, zone))
    }

    @Test
    fun aZoneThatSkipsMidnight_startsItsDayAtOne() {
        val havana = ZoneId.of("America/Havana") // 2026-03-08: 00:00 jumps to 01:00
        val noon = LocalDateTime.of(2026, 3, 8, 12, 0).atZone(havana).toInstant().toEpochMilli()

        assertEquals(
            LocalDateTime.of(2026, 3, 8, 1, 0).atZone(havana).toInstant().toEpochMilli(),
            TodaySummary.dayStartMs(noon, havana)
        )
    }

    // ---- what counts --------------------------------------------------------------------------------------------

    @Test
    fun theAppInFront_countsUpToNow() {
        val today = summary(listOf(event(EventKind.RESUMED, "com.reddit", at(11, 45))))

        assertEquals(minutes(15), today.totalMs)
    }

    @Test
    fun theScreenGoingOff_endsTheSitting() {
        val today = summary(
            listOf(event(EventKind.RESUMED, "com.reddit", at(9)), event(EventKind.CLOSE_ALL, "android", at(9, 10)))
        )

        assertEquals(minutes(10), today.totalMs)
    }

    @Test
    fun theHomeScreenAndIgnoredApps_areLeftOut() {
        val today = summary(
            sitting("com.launcher", at(9), at(9, 10)) + sitting("com.bank", at(9, 10), at(9, 20)) +
                sitting("com.reddit", at(9, 20), at(9, 30)),
            home = setOf("com.launcher"),
            ignored = setOf("com.bank")
        )

        assertEquals(minutes(10), today.totalMs)
        assertEquals(listOf(AppToday("com.reddit", minutes(10))), today.top)
    }

    @Test
    fun pausedTime_isTakenOut() {
        val today = summary(sitting("com.reddit", at(9), at(10)), pauses = listOf(PauseRange(at(9, 15), at(9, 45))))

        assertEquals(minutes(30), today.totalMs)
    }

    @Test
    fun theAppInFrontDuringAPause_addsNothing() {
        val today = summary(
            listOf(event(EventKind.RESUMED, "com.reddit", at(11, 45))),
            pauses = listOf(PauseRange(at(11, 50), PauseRange.UNTIL_RESUMED))
        )

        assertEquals(minutes(5), today.totalMs)
    }

    @Test
    fun aSittingUnderFiveSeconds_isLeftOut() {
        val today = summary(sitting("com.reddit", at(9), at(9, 0, 4)))

        assertEquals(TodaySummary(0L, emptyList()), today)
    }

    @Test
    fun aPieceUnderFiveSecondsLeftByAPause_isLeftOut() {
        val today = summary(sitting("com.reddit", at(9), at(9, 10)), pauses = listOf(PauseRange(at(9, 0, 3), at(9, 10))))

        assertEquals(0L, today.totalMs)
    }

    @Test
    fun theFloorIsTheWholeSittings_notItsPartAfterMidnight() {
        val today = summary(sitting("com.reddit", at(23, 59, 50, day = 3), at(0, 0, 2)))

        assertEquals(2_000L, today.totalMs)
    }

    // ---- total and order ----------------------------------------------------------------------------------------

    @Test
    fun splitScreen_countsOnceInTheTotalAndFullyForEachApp() {
        val today = summary(sitting("com.maps", at(9), at(9, 30)) + sitting("com.notes", at(9, 10), at(9, 40)))

        assertEquals(minutes(40), today.totalMs)
        assertEquals(listOf(AppToday("com.maps", minutes(30)), AppToday("com.notes", minutes(30))), today.top)
    }

    @Test
    fun oneAppsSittings_addUp() {
        val today = summary(sitting("com.reddit", at(9), at(9, 10)) + sitting("com.reddit", at(10), at(10, 5)))

        assertEquals(listOf(AppToday("com.reddit", minutes(15))), today.top)
    }

    @Test
    fun theTopIsTheThreeBusiestApps_busiestFirst_butTheTotalCountsThemAll() {
        val today = summary(
            sitting("com.a", at(8), at(8, 5)) + sitting("com.b", at(8, 10), at(8, 40)) +
                sitting("com.c", at(9), at(9, 20)) + sitting("com.d", at(9, 30), at(9, 40))
        )

        assertEquals(listOf("com.b", "com.c", "com.d"), today.top.map { it.packageName })
        assertEquals(minutes(65), today.totalMs)
    }

    @Test
    fun equalTimes_goByPackage() {
        val today = summary(sitting("com.z", at(8), at(8, 10)) + sitting("com.a", at(9), at(9, 10)))

        assertEquals(listOf("com.a", "com.z"), today.top.map { it.packageName })
    }
}
