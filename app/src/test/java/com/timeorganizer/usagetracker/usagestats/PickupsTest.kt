package com.timeorganizer.usagetracker.usagestats

import org.junit.Assert.assertEquals
import org.junit.Test

class PickupsTest {

    private fun screenOn(at: Long) = UsageEventRecord(EventKind.SCREEN_ON, "android", "", at)
    private fun unlocked(at: Long) = UsageEventRecord(EventKind.UNLOCKED, "android", "", at)
    private fun closeAll(at: Long) = UsageEventRecord(EventKind.CLOSE_ALL, "android", "", at)
    private fun startup(at: Long) = UsageEventRecord(EventKind.DEVICE_STARTUP, "android", "", at)
    private fun resumed(pkg: String, at: Long) = UsageEventRecord(EventKind.RESUMED, pkg, "Main", at)
    private fun paused(pkg: String, at: Long) = UsageEventRecord(EventKind.PAUSED, pkg, "Main", at)

    @Test
    fun screenOnThenUnlock_isOneUnlockedPickup_endingAtTheScreenOff() {
        val pickups = Pickups.from(
            listOf(screenOn(1_000), unlocked(2_000), resumed("feed", 2_500), paused("feed", 40_000), closeAll(40_000))
        )

        assertEquals(listOf(Pickup(1_000, 40_000, unlocked = true)), pickups)
    }

    @Test
    fun aGlanceWithoutUnlocking_isAPickupThatIsNotUnlocked() {
        val pickups = Pickups.from(listOf(screenOn(1_000), closeAll(6_000)))

        assertEquals(listOf(Pickup(1_000, 6_000, unlocked = false)), pickups)
    }

    @Test
    fun theKeyguardShownAsTheScreenComesOn_thenHidden_isOneUnlockedPickup() {
        // The owner's Xiaomi (Android 13), 2026-10-27: SCREEN_INTERACTIVE, KEYGUARD_SHOWN, KEYGUARD_HIDDEN in one second.
        val pickups = Pickups.from(listOf(screenOn(1_000), closeAll(1_050), unlocked(1_400), closeAll(20_000)))

        assertEquals(listOf(Pickup(1_000, 20_000, unlocked = true)), pickups)
    }

    @Test
    fun anUnlockLongAfterAGlance_isItsOwnPickup() {
        // A glance that ended, then (the screen-on just before the window) an unlock: two pickups.
        val pickups = Pickups.from(listOf(screenOn(1_000), closeAll(5_000), unlocked(9_000), closeAll(20_000)))

        assertEquals(listOf(Pickup(1_000, 5_000, false), Pickup(9_000, 20_000, true)), pickups)
    }

    @Test
    fun anUnlockWithNoScreenOnBeforeIt_startsThePickupAtTheUnlock() {
        // The screen came on just before the window began.
        val pickups = Pickups.from(listOf(unlocked(2_000), closeAll(9_000)))

        assertEquals(listOf(Pickup(2_000, 9_000, unlocked = true)), pickups)
    }

    @Test
    fun aRepeatedScreenOn_isStillOnePickup() {
        val pickups = Pickups.from(listOf(screenOn(1_000), screenOn(1_500), unlocked(2_000), closeAll(9_000)))

        assertEquals(listOf(Pickup(1_000, 9_000, unlocked = true)), pickups)
    }

    @Test
    fun aPickupStillGoingAtTheEndOfTheRead_hasNoEnd() {
        val pickups = Pickups.from(listOf(screenOn(1_000), closeAll(5_000), screenOn(10_000), unlocked(11_000)))

        assertEquals(listOf(Pickup(1_000, 5_000, false), Pickup(10_000, null, true)), pickups)
    }

    @Test
    fun aWindowThatBeginsMidPickup_leavesThatPickupOut() {
        val pickups = Pickups.from(listOf(resumed("feed", 1_000), paused("feed", 5_000), closeAll(5_000)))

        assertEquals(emptyList<Pickup>(), pickups)
    }

    @Test
    fun aReboot_closesTheOpenPickupAtTheLastEventBeforeIt() {
        val pickups = Pickups.from(
            listOf(screenOn(1_000), unlocked(2_000), resumed("feed", 3_000), startup(600_000), screenOn(601_000), closeAll(602_000))
        )

        assertEquals(listOf(Pickup(1_000, 3_000, true), Pickup(601_000, 602_000, false)), pickups)
    }

    @Test
    fun theSessionBuilder_ignoresScreenOnsAndUnlocks() {
        val withThem = listOf(
            screenOn(0), unlocked(500), resumed("feed", 1_000), paused("feed", 30_000), closeAll(30_000),
            screenOn(39_000), unlocked(39_500), resumed("feed", 40_000), paused("feed", 50_000)
        )
        val without = withThem.filter { it.kind != EventKind.SCREEN_ON && it.kind != EventKind.UNLOCKED }

        fun build(events: List<UsageEventRecord>) = SessionBuilder().apply { events.forEach(::onEvent) }.build(60_000)

        assertEquals(build(without), build(withThem))
        assertEquals(listOf(RawSession("feed", 1_000, 30_000), RawSession("feed", 40_000, 50_000)), build(withThem).sessions)
    }
}
