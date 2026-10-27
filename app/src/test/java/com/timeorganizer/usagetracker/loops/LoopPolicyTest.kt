package com.timeorganizer.usagetracker.loops

import com.timeorganizer.usagetracker.loops.LoopDetector.MINUTE_MS
import com.timeorganizer.usagetracker.usagestats.Pickup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class LoopPolicyTest {

    private val t0 = 1_000_000_000_000L

    private fun at(minutes: Int) = t0 + minutes * MINUTE_MS

    /** Unlocks at each of [unlockMinutes], 30 s each; looked at at [nowMinute]. */
    private fun facts(nowMinute: Int, unlockMinutes: Iterable<Int>) = LoopFacts(
        nowMs = at(nowMinute),
        pickups = unlockMinutes.map { Pickup(at(it), at(it) + 30_000, unlocked = true) },
        sittings = emptyList(),
        excluded = emptySet(),
        notCounted = emptySet(),
        phoneLocks = true
    )

    private fun decide(state: LoopState, nowMinute: Int, unlockMinutes: Iterable<Int>, silent: Boolean = false) =
        LoopPolicy.decide(state, facts(nowMinute, unlockMinutes), silent)

    /** A loop that began at minute 5, after unlocks at 0–5, checked in about. */
    private fun inALoop(): LoopStep = decide(LoopState(), 5, 0..5)

    // ---- one check-in per loop ---------------------------------------------

    @Test
    fun theSixthUnlock_checksIn_andALoopBegins() {
        val step = inALoop()

        assertEquals(LoopCheckIn(LoopSignal(LoopRule.PICKUPS, pickups = 6, apps = 0)), step.checkIn)
        assertEquals(OpenLoop(at(5), at(5), LoopRule.PICKUPS, 6, 0, LoopAnswer.ASKED), step.state.loop)
    }

    @Test
    fun fiveUnlocks_nothing() {
        val step = decide(LoopState(), 5, 0..4)

        assertNull(step.checkIn)
        assertNull(step.state.loop)
    }

    @Test
    fun theLoopGoingOn_isNotCheckedInAboutAgain() {
        val more = decide(inALoop().state, 7, 0..7)

        assertNull(more.checkIn)
        assertEquals(at(7), more.state.loop?.lastHeldMs)
        assertEquals(8, more.state.loop?.pickups)
    }

    @Test
    fun aCalmUnderFifteenMinutes_keepsTheSameLoop() {
        val held = decide(inALoop().state, 7, 0..7).state
        val calm = decide(held, 21, 0..7) // 14 min since the rule last held
        val again = decide(calm.state, 22, (0..7) + (17..22))

        assertNull(calm.ended)
        assertNull(again.checkIn)
        assertEquals(at(5), again.state.loop?.startMs)
    }

    @Test
    fun fifteenMinutesWithoutTheRule_endsTheLoop_clearsItsCheckIn_andTheNextGetsItsOwn() {
        val ended = decide(inALoop().state, 21, 0..5) // 16 min since minute 5

        assertEquals(LoopAnswer.ASKED, ended.ended?.answer)
        assertTrue(ended.cancelCheckIn)
        assertNull(ended.state.loop)
        assertEquals(at(5), ended.state.countFromMs)

        val next = decide(ended.state, 30, (0..5) + (25..30))
        assertNotNull(next.checkIn)
    }

    @Test
    fun theOldLoopsUnlocks_neverStartANewOne() {
        // Ended at 21; the unlocks at 0–5 are behind countFrom, and 4 new ones are not enough.
        val ended = decide(inALoop().state, 21, 0..5).state
        val step = decide(ended, 23, (0..5) + (20..23))

        assertNull(step.checkIn)
    }

    // ---- silence -----------------------------------------------------------

    @Test
    fun aLoopBeginningDuringACallOrDnd_isSkipped_notPostponed() {
        val silent = decide(LoopState(), 5, 0..5, silent = true)
        val after = decide(silent.state, 6, 0..6)

        assertNull(silent.checkIn)
        assertEquals(LoopAnswer.SILENCED, silent.state.loop?.answer)
        assertNull(after.checkIn)
    }

    // ---- the buttons -------------------------------------------------------

    @Test
    fun imFine_isSilentForTheRestOfTheLoop_andStoredAsFine() {
        val fine = LoopPolicy.fine(inALoop().state)
        val more = decide(fine.state, 8, 0..8)
        val ended = decide(more.state, 24, 0..8)

        assertTrue(fine.cancelCheckIn)
        assertNull(more.checkIn)
        assertEquals(LoopAnswer.FINE, ended.ended?.answer)
        assertFalse(ended.cancelCheckIn)
    }

    @Test
    fun putItAway_endsTheLoop_andNothingIsSaidUntilTheTimerIsDone() {
        val away = LoopPolicy.putAway(inALoop().state, at(6), awayMinutes = 20)

        assertEquals(LoopAnswer.AWAY, away.ended?.answer)
        assertEquals(at(6), away.ended?.lastHeldMs)
        assertEquals(at(26), away.state.awayUntilMs)
        assertEquals(20, away.state.awayMinutes)
        assertNull(away.state.loop)

        // Picked up again and again while it runs: nothing.
        assertNull(decide(away.state, 15, 6..15).checkIn)
    }

    @Test
    fun theTimersAlarm_saysItIsDone_andWhatCameBeforeNoLongerCounts() {
        val away = LoopPolicy.putAway(inALoop().state, at(6), awayMinutes = 20).state
        val done = LoopPolicy.awayDone(away, at(26))

        assertTrue(done.awayDone)
        assertNull(done.state.awayUntilMs)
        assertEquals(at(26), done.state.countFromMs)
        // Unlocks during the timer don't make a loop the moment it ends.
        assertNull(decide(done.state, 27, (17..26) + 27).checkIn)
    }

    @Test
    fun anAlarmMuchTooEarly_orWithNoTimer_saysNothing() {
        val away = LoopPolicy.putAway(inALoop().state, at(6), awayMinutes = 20).state

        assertFalse(LoopPolicy.awayDone(away, at(20)).awayDone)
        assertFalse(LoopPolicy.awayDone(LoopState(), at(20)).awayDone)
    }

    @Test
    fun aTimerWhoseAlarmNeverCame_isLetGoSilently_afterTheGrace() {
        val away = LoopPolicy.putAway(inALoop().state, at(6), awayMinutes = 20).state

        assertEquals(at(26), decide(away, 55, emptyList()).state.awayUntilMs)
        val later = decide(away, 57, emptyList())
        assertNull(later.state.awayUntilMs)
        assertFalse(later.awayDone)
        assertEquals(at(26), later.state.countFromMs)
    }

    @Test
    fun notToday_isSilentUntilMidnight_thenOnlyNewUnlocksCount() {
        val zone = ZoneOffset.UTC
        val night = Instant.parse("2026-10-27T22:00:00Z").toEpochMilli()
        val minute = { m: Int -> night + m * MINUTE_MS }
        val unlocks = { range: IntRange -> range.map { Pickup(minute(it), minute(it) + 30_000, true) } }
        fun factsAt(m: Int, range: IntRange) = LoopFacts(minute(m), unlocks(range), emptyList(), emptySet(), emptySet(), true)

        val loop = LoopPolicy.decide(LoopState(), factsAt(5, 0..5), false)
        val quiet = LoopPolicy.notToday(loop.state, minute(6), zone)

        assertEquals(LoopAnswer.NOT_TODAY, quiet.ended?.answer)
        assertEquals(Instant.parse("2026-10-28T00:00:00Z").toEpochMilli(), quiet.state.quietUntilMs)
        assertNull(LoopPolicy.decide(quiet.state, factsAt(60, 50..60), false).checkIn)

        // Past midnight: the unlocks from before it don't count, six new ones do.
        assertNull(LoopPolicy.decide(quiet.state, factsAt(122, 115..122), false).checkIn)
        assertNotNull(LoopPolicy.decide(quiet.state, factsAt(127, 120..127), false).checkIn)
    }

    @Test
    fun switchedOff_endsTheLoopGoingOn_andClearsItsCheckIn() {
        val off = LoopPolicy.off(inALoop().state, at(7))

        assertEquals(LoopAnswer.ASKED, off.ended?.answer)
        assertTrue(off.cancelCheckIn)
        assertNull(off.state.loop)
        assertEquals(at(7), off.state.countFromMs)
        assertNull(LoopPolicy.off(LoopState(), at(7)).ended)
    }

    // ---- days --------------------------------------------------------------

    @Test
    fun midnight_isLocal_acrossTheEndOfSummerTime() {
        val bratislava = ZoneId.of("Europe/Bratislava")
        // Summer time ended at 03:00 on 2026-10-25: that day began at UTC+2 and ends at UTC+1, 25 hours later.
        val evening = Instant.parse("2026-10-25T21:30:00Z").toEpochMilli() // 22:30 local

        assertEquals(Instant.parse("2026-10-25T23:00:00Z").toEpochMilli(), LoopPolicy.nextMidnightMs(evening, bratislava))
        assertEquals(Instant.parse("2026-10-24T22:00:00Z").toEpochMilli(), LoopPolicy.startOfDayMs(evening, bratislava))
    }
}
