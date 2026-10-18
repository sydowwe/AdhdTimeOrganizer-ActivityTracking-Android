package com.timeorganizer.usagetracker.watch

import com.timeorganizer.usagetracker.usagestats.EventKind
import com.timeorganizer.usagetracker.usagestats.UsageEventRecord
import com.timeorganizer.usagetracker.watch.ForegroundChange.CameToFront
import com.timeorganizer.usagetracker.watch.ForegroundChange.LeftFront
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundTrackerTest {

    private fun resumed(pkg: String, cls: String, at: Long) = UsageEventRecord(EventKind.RESUMED, pkg, cls, at)
    private fun paused(pkg: String, cls: String, at: Long) = UsageEventRecord(EventKind.PAUSED, pkg, cls, at)
    private fun closeAll(at: Long) = UsageEventRecord(EventKind.CLOSE_ALL, "android", "", at)

    private fun primed(vararg events: UsageEventRecord) = ForegroundTracker().also { it.prime(events.toList()) }

    @Test
    fun prime_reportsOnlyWhatIsStillInFront_withItsRealStart() {
        val tracker = ForegroundTracker()
        val changes = tracker.prime(
            listOf(
                resumed("mail", "Inbox", 0), paused("mail", "Inbox", 60_000),
                resumed("reddit", "Feed", 61_000)
            )
        )

        assertEquals(listOf(CameToFront("reddit", 61_000)), changes)
        assertEquals(setOf("reddit"), tracker.current())
    }

    @Test
    fun switchingApps_reportsTheLeaveThenTheArrival() {
        val tracker = primed(resumed("mail", "Inbox", 0))

        val changes = tracker.advance(listOf(paused("mail", "Inbox", 10_000), resumed("reddit", "Feed", 10_040)), 15_000)

        assertEquals(listOf(LeftFront("mail", 10_000), CameToFront("reddit", 10_040)), changes)
    }

    @Test
    fun aHandOffBetweenScreensOfOneApp_reportsNothing() {
        val tracker = primed(resumed("chat", "List", 0))

        val changes = tracker.advance(listOf(paused("chat", "List", 10_000), resumed("chat", "Conversation", 10_050)), 15_000)

        assertTrue(changes.isEmpty())
        assertEquals(setOf("chat"), tracker.current())
    }

    @Test
    fun aHandOffSplitAcrossTwoReads_reportsNothing() {
        val tracker = primed(resumed("chat", "List", 0))

        assertTrue(tracker.advance(listOf(paused("chat", "List", 9_900)), 10_000).isEmpty())
        assertTrue(tracker.advance(listOf(resumed("chat", "Conversation", 10_100)), 15_000).isEmpty())
    }

    @Test
    fun aQuickReturnWithNothingElseInBetween_isTheSameSitting() {
        val tracker = primed(resumed("chat", "List", 0))

        val changes = tracker.advance(listOf(paused("chat", "List", 10_000), resumed("chat", "List", 11_500)), 15_000)

        assertTrue(changes.isEmpty())
    }

    @Test
    fun aReturnAfterTheGap_isALeaveAndANewArrival() {
        val tracker = primed(resumed("chat", "List", 0))

        val changes = tracker.advance(listOf(paused("chat", "List", 10_000), resumed("chat", "List", 13_000)), 15_000)

        assertEquals(listOf(LeftFront("chat", 10_000), CameToFront("chat", 13_000)), changes)
    }

    @Test
    fun anAppThatLeavesWithNothingAfter_isReportedOnceTheGapHasPassed() {
        val tracker = primed(resumed("mail", "Inbox", 0))

        // Read ends 1.5 s after the pause: it may still be the same sitting.
        assertTrue(tracker.advance(listOf(paused("mail", "Inbox", 10_000)), 11_500).isEmpty())
        assertEquals(emptySet<String>(), tracker.current())

        assertEquals(listOf(LeftFront("mail", 10_000)), tracker.advance(emptyList(), 16_500))
    }

    @Test
    fun screenOff_endsEveryAppInFront() {
        val tracker = primed(resumed("maps", "Nav", 0), resumed("music", "Player", 1_000))

        val changes = tracker.advance(listOf(closeAll(30_000)), 60_000)

        assertEquals(setOf(LeftFront("maps", 30_000), LeftFront("music", 30_000)), changes.toSet())
        assertTrue(tracker.current().isEmpty())
    }

    @Test
    fun aScreenOffReadOnlyAfterUnlock_stillReportsTheTimesFromTheLog() {
        // Polling stops while the screen is off; the next read carries the whole stretch.
        val tracker = primed(resumed("reddit", "Feed", 0))

        val changes = tracker.advance(listOf(closeAll(30_000), resumed("reddit", "Feed", 900_000)), 905_000)

        assertEquals(listOf(LeftFront("reddit", 30_000), CameToFront("reddit", 900_000)), changes)
    }

    @Test
    fun splitScreen_hasBothAppsInFront_andEachLeavesOnItsOwn() {
        val tracker = primed(resumed("docs", "Editor", 0))

        val opened = tracker.advance(listOf(resumed("chrome", "Tab", 5_000)), 6_000)
        assertEquals(listOf(CameToFront("chrome", 5_000)), opened)
        assertEquals(setOf("docs", "chrome"), tracker.current())

        val closed = tracker.advance(listOf(paused("chrome", "Tab", 20_000)), 30_000)
        assertEquals(listOf(LeftFront("chrome", 20_000)), closed)
        assertEquals(setOf("docs"), tracker.current())
    }

    @Test
    fun anotherAppInFront_settlesALeaveAtOnce() {
        val tracker = primed(resumed("mail", "Inbox", 0))

        // The new app resumes 100 ms later and the read ends right after: no need to wait out the gap.
        val changes = tracker.advance(listOf(paused("mail", "Inbox", 10_000), resumed("reddit", "Feed", 10_100)), 10_200)

        assertEquals(listOf(LeftFront("mail", 10_000), CameToFront("reddit", 10_100)), changes)
    }

    @Test
    fun aPauseOfAnActivityResumedBeforeWatching_isIgnored() {
        // The builder never saw it resume; the next read must not invent a leave for it.
        val tracker = primed()

        assertTrue(tracker.advance(listOf(paused("mail", "Inbox", 10_000)), 20_000).isEmpty())
    }
}
