package com.timeorganizer.usagetracker.usagestats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PickupCounterTest {

    private fun screenOn(at: Long) = UsageEventRecord(EventKind.SCREEN_ON, "android", "", at)
    private fun unlocked(at: Long) = UsageEventRecord(EventKind.UNLOCKED, "android", "", at)
    private fun closeAll(at: Long) = UsageEventRecord(EventKind.CLOSE_ALL, "android", "", at)
    private fun resumed(pkg: String, at: Long) = UsageEventRecord(EventKind.RESUMED, pkg, "Main", at)
    private fun paused(pkg: String, at: Long) = UsageEventRecord(EventKind.PAUSED, pkg, "Main", at)

    private val home = setOf("launcher", "this.app")

    /** What `queryEvents(from, to)` returns: begin inclusive, end exclusive. */
    private fun List<UsageEventRecord>.read(fromMs: Long, toMs: Long) = filter { it.timestampMs in fromMs until toMs }

    private fun count(log: List<UsageEventRecord>, fromMs: Long, toMs: Long, sawUnlock: Boolean = true) =
        PickupCounter.count(log.read(fromMs, toMs), toMs, sawUnlock, home)

    /** The sync's loop: each read starts where the last one said, and the closed pickups add up. */
    private fun syncs(log: List<UsageEventRecord>, startMs: Long, vararg readEnds: Long): List<CountedPickup> {
        var fromMs = startMs
        val stored = mutableListOf<CountedPickup>()
        for (toMs in readEnds) {
            val result = count(log, fromMs, toMs)
            stored += result.closed
            fromMs = maxOf(result.resumeFromMs, fromMs)
        }
        return stored
    }

    // An evening: a glance at the clock, an unlock to a feed, the Xiaomi keyguard order, and one still going at the end.
    private val evening = listOf(
        screenOn(10_000), closeAll(14_000),                                                   // glance
        screenOn(60_000), unlocked(61_000), resumed("feed", 62_000), paused("feed", 90_000), closeAll(90_000),
        screenOn(200_000), closeAll(200_300), unlocked(201_000),                              // Xiaomi: shown, then hidden
        resumed("chat", 202_000), paused("chat", 230_000), closeAll(230_000),
        screenOn(400_000), unlocked(400_500), resumed("feed", 401_000)                         // still in use
    )

    @Test
    fun aPickupThatEndedLongBefore_isCounted_andTheNextReadStartsAtTheEnd() {
        val result = count(evening, 0, 150_000)

        assertEquals(
            listOf(CountedPickup(10_000, 14_000, unlocked = false), CountedPickup(60_000, 90_000, unlocked = true)),
            result.closed
        )
        assertEquals(150_000, result.resumeFromMs)
    }

    @Test
    fun aPickupStillGoing_isHeld_andTheNextReadStartsAtItsScreenOn() {
        val result = count(evening, 300_000, 420_000)

        assertTrue(result.closed.isEmpty())
        assertEquals(400_000, result.resumeFromMs)
    }

    @Test
    fun xiaomisShownThenHidden_readBetweenTheTwo_isHeld_andCountedWholeLater() {
        // The read ends 0.5 s after the keyguard was shown: the pickup it closed may still be joined by the unlock.
        val early = count(evening, 150_000, 200_800)
        assertTrue(early.closed.isEmpty())
        assertEquals(200_000, early.resumeFromMs)

        val later = count(evening, early.resumeFromMs, 300_000)
        assertEquals(listOf(CountedPickup(200_000, 230_000, unlocked = true)), later.closed)
    }

    @Test
    fun readsInPieces_countWhatOneLongReadCounts() {
        val whole = count(evening, 0, 500_000).closed

        // Ends inside the glance, inside the feed pickup, between Xiaomi's two keyguard events, and inside the last one.
        val pieces = syncs(evening, 0, 12_000, 70_000, 200_500, 215_000, 420_000, 500_000)

        assertEquals(whole, pieces)
        assertEquals(listOf(false, true, true), whole.map { it.unlocked }) // the one still going is in neither
    }

    @Test
    fun readingTheSameWindowAgain_givesTheSamePickups() {
        assertEquals(count(evening, 0, 300_000), count(evening, 0, 300_000))
    }

    @Test
    fun aReadFromTheSessionsCursor_wouldLoseThePickup_whichIsWhyThePickupsHaveTheirOwn() {
        // The sessions' cursor rests at the feed's resume (62 s), after the screen came on: from there the pickup is
        // half-seen (it starts at nothing), so Pickups starts none — only the screen-on lets it be counted.
        val fromSessionsCursor = count(evening, 62_000, 150_000)
        assertTrue(fromSessionsCursor.closed.isEmpty())

        val fromPickupCursor = count(evening, 60_000, 150_000)
        assertEquals(listOf(CountedPickup(60_000, 90_000, unlocked = true)), fromPickupCursor.closed)
    }

    @Test
    fun aPhoneWithNoLockScreen_countsAPickupThatOpenedAnApp_asUnlocked() {
        val noLock = listOf(
            screenOn(1_000), resumed("feed", 2_000), paused("feed", 30_000), closeAll(30_000),
            screenOn(40_000), resumed("launcher", 41_000), paused("launcher", 45_000), closeAll(45_000),
            screenOn(60_000), closeAll(63_000)
        )

        val result = count(noLock, 0, 100_000, sawUnlock = false)

        assertEquals(listOf(true, false, false), result.closed.map { it.unlocked })
        assertFalse(result.sawUnlock)
    }

    @Test
    fun onceAnUnlockIsSeen_theLockScreensRuleHolds() {
        // A glance that brought an app over the lock screen (navigation, a call) is no unlock once the phone is known to lock.
        val log = listOf(
            screenOn(1_000), resumed("maps", 1_500), closeAll(9_000),
            screenOn(20_000), unlocked(21_000), closeAll(30_000)
        )

        val result = count(log, 0, 100_000, sawUnlock = false)

        assertTrue(result.sawUnlock)
        assertEquals(listOf(false, true), result.closed.map { it.unlocked })
    }

    @Test
    fun nothingRead_resumesAtTheReadsEnd() {
        val result = PickupCounter.count(emptyList(), 50_000, sawUnlockBefore = true, excluded = home)

        assertEquals(PickupCount(emptyList(), 50_000, sawUnlock = true), result)
    }
}
