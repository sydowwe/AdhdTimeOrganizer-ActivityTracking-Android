package com.timeorganizer.usagetracker.chime

import com.timeorganizer.usagetracker.chime.ChimeSchedule.MINUTE_MS
import com.timeorganizer.usagetracker.usagestats.EventKind
import com.timeorganizer.usagetracker.usagestats.UsageEventRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChimeScheduleTest {

    private val t0 = 1_000_000_000_000L

    private fun at(minutes: Int) = t0 + minutes * MINUTE_MS

    private val reddit = "com.reddit.frontpage"
    private val instagram = "com.instagram.android"
    private val tiktok = "com.zhiliaoapp.musically"

    /** Not chosen. */
    private val maps = "com.google.android.apps.maps"

    /** In [packageName] from [fromMinute]; still in front unless [toMinute] is given. */
    private fun sitting(packageName: String, fromMinute: Int, toMinute: Int? = null, nowMinute: Int = 0) =
        ChimeSitting(packageName, at(fromMinute), at(toMinute ?: nowMinute), open = toMinute == null)

    private fun decide(
        nowMinute: Int,
        vararg sittings: ChimeSitting,
        state: ChimeState = ChimeState.NONE,
        intervalMinutes: Int = 15,
        silent: Boolean = false
    ) = ChimeSchedule.decide(state, ChimeInput(at(nowMinute), sittings.toList(), intervalMinutes, silent))

    // ---- chiming at each interval -----------------------------------------

    @Test
    fun fifteenMinutesInFront_chimesOnce_andSaysWhenTheNextIsDue() {
        val step = decide(15, sitting(reddit, 0, nowMinute = 15))

        assertEquals(Chime(15 * MINUTE_MS, listOf(reddit)), step.chime)
        assertEquals(ChimeState(at(0), 15 * MINUTE_MS), step.state)
        assertEquals(at(30), step.recheckAtMs)
    }

    @Test
    fun beforeTheFirstInterval_nothing_butTheTimeItFallsDue() {
        val step = decide(10, sitting(reddit, 0, nowMinute = 10))

        assertNull(step.chime)
        assertEquals(ChimeState(at(0), 0L), step.state)
        assertEquals(at(15), step.recheckAtMs)
    }

    @Test
    fun theSameMarkLookedAtAgain_isNotSaidTwice() {
        val first = decide(15, sitting(reddit, 0, nowMinute = 15))
        val again = decide(16, sitting(reddit, 0, nowMinute = 16), state = first.state)

        assertNull(again.chime)
        assertEquals(at(30), again.recheckAtMs)
    }

    @Test
    fun theIntervalStaysTheSame_thirtyAndFortyFive() {
        val at30 = decide(30, sitting(reddit, 0, nowMinute = 30), state = ChimeState(at(0), 15 * MINUTE_MS))
        val at45 = decide(45, sitting(reddit, 0, nowMinute = 45), state = at30.state)

        assertEquals(30 * MINUTE_MS, at30.chime?.inAppMs)
        assertEquals(45 * MINUTE_MS, at45.chime?.inAppMs)
        assertEquals(at(60), at45.recheckAtMs)
    }

    @Test
    fun chimesMissedWhileNobodyLooked_areSaidOnce_forTheLatestMark() {
        val step = decide(47, sitting(reddit, 0, nowMinute = 47), state = ChimeState(at(0), 15 * MINUTE_MS))

        assertEquals(45 * MINUTE_MS, step.chime?.inAppMs)
        assertEquals(at(60), step.recheckAtMs)
    }

    @Test
    fun theIntervalChangedMidStretch_movesTheNextChime_neverRepeatsOne() {
        // Chimed at 30 with 15; now 20: the next mark past 30 is 40, not 20 again.
        val at35 = decide(35, sitting(reddit, 0, nowMinute = 35), state = ChimeState(at(0), 30 * MINUTE_MS), intervalMinutes = 20)
        assertNull(at35.chime)
        assertEquals(at(40), at35.recheckAtMs)

        val at40 = decide(40, sitting(reddit, 0, nowMinute = 40), state = at35.state, intervalMinutes = 20)
        assertEquals(40 * MINUTE_MS, at40.chime?.inAppMs)
    }

    // ---- reset on leave, gaps paused ---------------------------------------

    @Test
    fun aGapUpToThreeMinutes_pausesTheCount_withoutCountingTheGap() {
        // 10 in Reddit, 3 out (a reply in WhatsApp), back: 5 more makes 15 in the app, at minute 18.
        val step = decide(18, sitting(reddit, 0, 10), sitting(reddit, 13, nowMinute = 18))

        assertEquals(Chime(15 * MINUTE_MS, listOf(reddit)), step.chime)
        assertEquals(at(0), step.state.stretchStartMs)
    }

    @Test
    fun leavingForMoreThanThreeMinutes_startsTheCountOver() {
        val state = ChimeState(at(0), 15 * MINUTE_MS)
        val step = decide(26, sitting(reddit, 0, 20), sitting(reddit, 24, nowMinute = 26), state = state)

        assertNull(step.chime)
        assertEquals("a new stretch, nothing chimed in it yet", ChimeState(at(24), 0L), step.state)
        assertEquals(at(39), step.recheckAtMs)
    }

    @Test
    fun aGapJustOverThreeMinutes_resets() {
        val step = ChimeSchedule.stretch(
            listOf(
                ChimeSitting(reddit, at(0), at(10), open = false),
                ChimeSitting(reddit, at(13) + 1, at(14), open = true)
            ),
            at(14)
        )

        assertEquals(at(13) + 1, step?.startMs)
    }

    @Test
    fun theScreenOffForAMinute_pausesTheCount() {
        // Screen off closes every sitting; on again within the gap, the stretch carries on.
        val events = listOf(
            resumed(reddit, at(0)),
            UsageEventRecord(EventKind.CLOSE_ALL, "android", "", at(8)),
            resumed(reddit, at(9))
        )
        val sittings = ChimeSchedule.sittings(events, at(16), setOf(reddit))
        val step = ChimeSchedule.decide(ChimeState.NONE, ChimeInput(at(16), sittings, 15, false))

        assertEquals("8 + 7 minutes in the app, the dark minute left out", 15 * MINUTE_MS, step.chime?.inAppMs)
        assertEquals(at(0), step.state.stretchStartMs)
    }

    @Test
    fun theScreenOffForLonger_resets() {
        val events = listOf(
            resumed(reddit, at(0)),
            UsageEventRecord(EventKind.CLOSE_ALL, "android", "", at(14)),
            resumed(reddit, at(30))
        )
        val sittings = ChimeSchedule.sittings(events, at(32), setOf(reddit))
        val step = ChimeSchedule.decide(ChimeState.NONE, ChimeInput(at(32), sittings, 15, false))

        assertNull(step.chime)
        assertEquals(at(30), step.state.stretchStartMs)
    }

    @Test
    fun leftAndNotBack_noChime_andNothingToLookAtUntilAnAppComesBack() {
        val step = decide(16, sitting(reddit, 0, 15))

        assertNull(step.chime)
        assertNull(step.recheckAtMs)
        assertEquals("the stretch is kept through the gap", ChimeState(at(0), 0L), step.state)
    }

    @Test
    fun leftLongAgo_noStretchAtAll() {
        val step = decide(30, sitting(reddit, 0, 20), state = ChimeState(at(0), 15 * MINUTE_MS))

        assertEquals(ChimeStep(ChimeState.NONE, null, null), step)
    }

    @Test
    fun anotherAppForLong_breaksTheStretch_butItsTimeIsNotCounted() {
        // Maps isn't chosen: its sittings never reach the schedule. 10 in Reddit, 5 in Maps, back to Reddit.
        val events = listOf(
            resumed(reddit, at(0)), paused(reddit, at(10)),
            resumed(maps, at(10)), paused(maps, at(15)),
            resumed(reddit, at(15))
        )
        val sittings = ChimeSchedule.sittings(events, at(20), setOf(reddit))

        assertEquals(listOf(reddit, reddit), sittings.map { it.packageName })
        assertEquals(at(15), ChimeSchedule.stretch(sittings, at(20))?.startMs)
    }

    // ---- the chosen apps count together -------------------------------------

    @Test
    fun hoppingBetweenChosenApps_countsAsOneStretch_namedByTheOneInFront() {
        val step = decide(15, sitting(reddit, 0, 10), sitting(instagram, 10, nowMinute = 15))

        assertEquals(Chime(15 * MINUTE_MS, listOf(instagram, reddit)), step.chime)
    }

    @Test
    fun splitScreenWithTwoChosenApps_countsTheOverlapOnce() {
        val stretch = ChimeSchedule.stretch(
            listOf(sitting(reddit, 0, nowMinute = 10), sitting(instagram, 5, nowMinute = 10)),
            at(10)
        )

        assertEquals(10 * MINUTE_MS, stretch?.inAppMs)
        assertEquals("both in front, the latest first", listOf(instagram, reddit), stretch?.inFront)
    }

    @Test
    fun theStretchsOtherApps_followTheOneInFront_mostTimeFirst() {
        val step = decide(
            15,
            sitting(instagram, 0, 2),
            sitting(reddit, 2, 10),
            sitting(tiktok, 10, nowMinute = 15)
        )

        assertEquals(listOf(tiktok, reddit, instagram), step.chime?.packages)
    }

    // ---- quiet --------------------------------------------------------------

    @Test
    fun aCallOrDnd_skipsTheChime_andDoesNotSaveItForLater() {
        val silent = decide(15, sitting(reddit, 0, nowMinute = 15), silent = true)
        assertNull(silent.chime)
        assertEquals("marked as said", ChimeState(at(0), 15 * MINUTE_MS), silent.state)

        val afterwards = decide(17, sitting(reddit, 0, nowMinute = 17), state = silent.state)
        assertNull("not said late once the call ends", afterwards.chime)

        val next = decide(30, sitting(reddit, 0, nowMinute = 30), state = afterwards.state)
        assertEquals(30 * MINUTE_MS, next.chime?.inAppMs)
    }

    // ---- reading the log ----------------------------------------------------

    @Test
    fun sittings_takeOnlyTheChosenApps_andMarkTheOneStillInFront() {
        val events = listOf(
            resumed(reddit, at(0)), paused(reddit, at(5)),
            resumed(maps, at(5)), paused(maps, at(6)),
            resumed(instagram, at(6))
        )

        assertEquals(
            listOf(
                ChimeSitting(reddit, at(0), at(5), open = false),
                ChimeSitting(instagram, at(6), at(8), open = true)
            ),
            ChimeSchedule.sittings(events, at(8), setOf(reddit, instagram))
        )
    }

    @Test
    fun nothingChosenInTheLog_noStretch() {
        assertNull(ChimeSchedule.stretch(emptyList(), at(10)))
    }

    private fun resumed(packageName: String, atMs: Long) = UsageEventRecord(EventKind.RESUMED, packageName, "Main", atMs)
    private fun paused(packageName: String, atMs: Long) = UsageEventRecord(EventKind.PAUSED, packageName, "Main", atMs)
}
