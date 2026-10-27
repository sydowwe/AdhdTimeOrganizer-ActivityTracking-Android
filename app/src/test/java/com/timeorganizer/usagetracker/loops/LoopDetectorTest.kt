package com.timeorganizer.usagetracker.loops

import com.timeorganizer.usagetracker.usagestats.EventKind
import com.timeorganizer.usagetracker.usagestats.Pickups
import com.timeorganizer.usagetracker.usagestats.UsageEventRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Realistic usage-log sequences, through [Pickups] and [LoopDetector.sittings] as the monitor feeds them: the loops that
 * must be caught, and the calls, navigation, chats and alarms that must not be.
 */
class LoopDetectorTest {

    private val t0 = 1_000_000_000_000L

    private fun s(seconds: Long) = t0 + seconds * 1_000L

    private val instagram = "com.instagram.android"
    private val reddit = "com.reddit.frontpage"
    private val whatsapp = "com.whatsapp"
    private val maps = "com.google.android.apps.maps"
    private val clock = "com.google.android.deskclock"
    private val dialer = "com.google.android.dialer"
    private val launcher = "com.sec.android.app.launcher"
    private val apps = (1..9).map { "com.example.app$it" }

    private val events = mutableListOf<UsageEventRecord>()

    private fun system(kind: EventKind, atMs: Long) {
        events += UsageEventRecord(kind, "android", "", atMs)
    }

    private fun resume(packageName: String, atMs: Long) {
        events += UsageEventRecord(EventKind.RESUMED, packageName, "Main", atMs)
    }

    private fun pause(packageName: String, atMs: Long) {
        events += UsageEventRecord(EventKind.PAUSED, packageName, "Main", atMs)
    }

    /**
     * The screen on at [atSec], unlocked a second later (unless [unlock] is false), each of [packages] in front for
     * [seconds] one after the other, then the screen off.
     */
    private fun pickup(atSec: Long, vararg packages: String, seconds: Long = 40, unlock: Boolean = true) {
        system(EventKind.SCREEN_ON, s(atSec))
        if (unlock) system(EventKind.UNLOCKED, s(atSec) + 1_000)
        var atMs = s(atSec) + 2_000
        for (packageName in packages) {
            resume(packageName, atMs)
            atMs += seconds * 1_000
            pause(packageName, atMs)
        }
        system(EventKind.CLOSE_ALL, atMs + 500)
    }

    private fun detect(
        nowSec: Long,
        notCounted: Set<String> = emptySet(),
        phoneLocks: Boolean = true,
        countFromMs: Long = Long.MIN_VALUE
    ): LoopSignal? {
        val nowMs = s(nowSec)
        val read = events.filter { it.timestampMs < nowMs }
        val facts = LoopFacts(
            nowMs = nowMs,
            pickups = Pickups.from(read),
            sittings = LoopDetector.sittings(read, nowMs),
            excluded = setOf(launcher),
            notCounted = notCounted,
            phoneLocks = phoneLocks
        )
        return LoopDetector.detect(facts, countFromMs)
    }

    // ---- pickups that make a loop ------------------------------------------

    @Test
    fun sixUnlocksIntoAFeed_ninetySecondsApart_isALoop_atTheSixth() {
        for (i in 0L until 6L) pickup(i * 90, instagram)

        assertNull(detect(449))
        assertEquals(LoopSignal(LoopRule.PICKUPS, pickups = 6, apps = 1), detect(455))
    }

    @Test
    fun fiveUnlocks_isNoLoop() {
        for (i in 0L until 5L) pickup(i * 90, instagram)

        assertNull(detect(500))
    }

    @Test
    fun sixUnlocksSpreadOverMoreThanTenMinutes_isNoLoop() {
        for (i in 0L until 6L) pickup(i * 121, instagram)

        assertNull(detect(610))
    }

    @Test
    fun unlockingOnlyToLookAtTheHomeScreen_counts() {
        for (i in 0L until 6L) pickup(i * 60, launcher, seconds = 5)

        assertEquals(LoopRule.PICKUPS, detect(310)?.rule)
    }

    @Test
    fun differentAppsOnEachPickup_count_andSoDoesALongOne() {
        pickup(0, instagram)
        pickup(60, reddit, seconds = 240) // one long one among them
        pickup(320, instagram)
        pickup(380, reddit)
        pickup(440, instagram)
        pickup(500, reddit)

        assertEquals(LoopRule.PICKUPS, detect(505)?.rule)
    }

    @Test
    fun nothingBeforeCountFrom_counts() {
        for (i in 0L until 6L) pickup(i * 60, instagram)

        assertNull(detect(305, countFromMs = s(120)))
    }

    // ---- hopping -----------------------------------------------------------

    @Test
    fun sixAppsHoppedThroughInOnePickup_isALoop() {
        system(EventKind.SCREEN_ON, s(0))
        system(EventKind.UNLOCKED, s(1))
        var at = 2L
        for (app in apps.take(5)) {
            resume(app, s(at))
            at += 20
            pause(app, s(at))
        }
        resume(apps[5], s(at)) // the sixth, just come to the front

        assertEquals(LoopSignal(LoopRule.HOPPING, pickups = 1, apps = 6), detect(at + 3))
    }

    @Test
    fun fiveAppsHoppedThrough_isNoLoop() {
        pickup(0, *apps.take(5).toTypedArray(), seconds = 20)

        assertNull(detect(110))
    }

    @Test
    fun hoppingAroundOneLongSitting_countsOnlyTheAppsAfterIt() {
        // Three quick ones, two minutes in Maps (used for something), then three more quick ones.
        pickup(0, apps[0], apps[1], apps[2], seconds = 15)
        pickup(50, maps, seconds = 120)
        pickup(175, apps[3], apps[4], apps[5], seconds = 15)

        assertNull(detect(222))
    }

    @Test
    fun theHomeScreenBetweenHops_neitherCountsNorBreaksTheRun() {
        system(EventKind.SCREEN_ON, s(0))
        system(EventKind.UNLOCKED, s(1))
        var at = 2L
        for (app in apps.take(6)) {
            resume(app, s(at))
            at += 15
            pause(app, s(at))
            resume(launcher, s(at))
            at += 5
            pause(launcher, s(at))
        }

        assertEquals(LoopSignal(LoopRule.HOPPING, pickups = 1, apps = 6), detect(at + 1))
    }

    // ---- what must not fire ------------------------------------------------

    @Test
    fun aCall_withTheScreenGoingOffAndOnAtTheEar_isNoLoop() {
        // Ringing over the lock screen, answered without unlocking; the proximity sensor turns the screen off and on.
        for (i in 0L until 10L) {
            system(EventKind.SCREEN_ON, s(i * 30))
            resume(dialer, s(i * 30) + 200)
            pause(dialer, s(i * 30 + 20))
            system(EventKind.CLOSE_ALL, s(i * 30 + 20))
        }

        assertNull(detect(300))
    }

    @Test
    fun navigation_oneLongSittingUnlockedOnce_isNoLoop() {
        system(EventKind.SCREEN_ON, s(0))
        system(EventKind.UNLOCKED, s(1))
        resume(maps, s(2))

        assertNull(detect(30 * 60))
    }

    @Test
    fun navigationOverTheLockScreen_wakingAtEveryTurn_isNoLoop() {
        for (i in 0L until 12L) pickup(i * 45, maps, seconds = 30, unlock = false)

        assertNull(detect(540))
    }

    @Test
    fun aChat_sixQuickRepliesInTenMinutes_isNoLoop_whenTheChatAppDoesNotCount() {
        for (i in 0L until 6L) pickup(i * 60, whatsapp, seconds = 20)

        assertNull(detect(305, notCounted = setOf(whatsapp)))
        // Without the tick, a chat looks exactly like a loop: that is what the list is for.
        assertEquals(LoopRule.PICKUPS, detect(305)?.rule)
    }

    @Test
    fun aPickupThatAlsoOpensACountedApp_counts_evenFromAChat() {
        for (i in 0L until 5L) pickup(i * 60, whatsapp, seconds = 20)
        pickup(300, whatsapp, instagram, seconds = 20)

        assertNull(detect(305, notCounted = setOf(whatsapp)))
        for (i in 6L until 11L) pickup(i * 60, whatsapp, instagram, seconds = 20)

        // The pickups from 300 on each reached Instagram (the last at 622): six.
        assertEquals(LoopRule.PICKUPS, detect(625, notCounted = setOf(whatsapp))?.rule)
    }

    @Test
    fun hoppingIntoAChatApp_isNoHop() {
        pickup(0, apps[0], apps[1], apps[2], apps[3], apps[4], whatsapp, seconds = 15)

        assertNull(detect(95, notCounted = setOf(whatsapp)))
        assertEquals(LoopRule.HOPPING, detect(95)?.rule)
    }

    @Test
    fun anAlarmSnoozedOverAndOver_isNoLoop() {
        // The alarm shows over the lock screen; snoozing never unlocks.
        for (i in 0L until 6L) pickup(i * 5 * 60, clock, seconds = 10, unlock = false)
        pickup(30 * 60, instagram) // then the phone is unlocked once

        assertNull(detect(30 * 60 + 60))
    }

    @Test
    fun glancesAtTheClock_onAPhoneWithALockScreen_areNoPickups() {
        for (i in 0L until 8L) pickup(i * 30, unlock = false)

        assertNull(detect(250))
    }

    // ---- a phone without a lock screen -------------------------------------

    @Test
    fun withoutALockScreen_screenOnsThatOpenAnApp_countInstead() {
        for (i in 0L until 6L) pickup(i * 60, instagram, unlock = false)

        assertEquals(LoopRule.PICKUPS, detect(305, phoneLocks = false)?.rule)
    }

    @Test
    fun withoutALockScreen_screenOnsThatOpenNothing_doNotCount() {
        for (i in 0L until 8L) pickup(i * 30, unlock = false)

        assertNull(detect(250, phoneLocks = false))
    }
}
