package com.timeorganizer.usagetracker.pause

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** Where each length ends, the morning, when the notification shows again, and when a paused phone says it's alive. */
class PausePolicyTest {

    private val zone = ZoneId.of("Europe/Bratislava")

    private fun at(text: String) = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    // ---- lengths ------------------------------------------------------------------------------------------------

    @Test
    fun eachLength_endsWhereItSays() {
        val now = at("2026-10-29T22:10:00")
        val morning = at("2026-10-30T06:30:00")

        assertEquals(at("2026-10-29T22:25:00"), PausePolicy.endMs(PauseLength.FIFTEEN_MINUTES, now, morning))
        assertEquals(at("2026-10-29T23:10:00"), PausePolicy.endMs(PauseLength.ONE_HOUR, now, morning))
        assertEquals(morning, PausePolicy.endMs(PauseLength.UNTIL_MORNING, now, morning))
        assertEquals(PauseRange.UNTIL_RESUMED, PausePolicy.endMs(PauseLength.UNTIL_RESUMED, now, morning))
    }

    // ---- the morning --------------------------------------------------------------------------------------------

    /** The server's next wake-up is the planner's: the day's own, else the default. Still ahead, it is used as it is. */
    @Test
    fun theServersMorning_whileAhead_isTheMorning() {
        assertEquals(
            at("2026-10-30T05:45:00"),
            PausePolicy.morning(at("2026-10-29T23:00:00"), at("2026-10-30T05:45:00"), zone)
        )
    }

    /** Heard yesterday and offline since: the same time on the next morning still ahead is the best guess. */
    @Test
    fun aMorningAlreadyPassed_movesOnByWholeDays() {
        assertEquals(
            at("2026-10-30T06:30:00"),
            PausePolicy.morning(at("2026-10-29T22:00:00"), at("2026-10-27T06:30:00"), zone)
        )
    }

    /** Moved on by the wall clock, not by 24 h: across the end of summer time 06:30 stays 06:30. */
    @Test
    fun movingOn_keepsTheWallClockAcrossADstChange() {
        // Summer time ends in Bratislava on 2026-10-25 at 03:00.
        assertEquals(
            at("2026-10-25T06:30:00"),
            PausePolicy.morning(at("2026-10-25T01:00:00"), at("2026-10-24T06:30:00"), zone)
        )
    }

    @Test
    fun neverHeard_isSevenOClock_todayWhileAhead_elseTomorrow() {
        assertEquals(at("2026-10-29T07:00:00"), PausePolicy.morning(at("2026-10-29T02:00:00"), null, zone))
        assertEquals(at("2026-10-30T07:00:00"), PausePolicy.morning(at("2026-10-29T22:00:00"), null, zone))
        assertEquals("exactly seven is already the morning", at("2026-10-30T07:00:00"), PausePolicy.morning(at("2026-10-29T07:00:00"), null, zone))
    }

    // ---- the notification ---------------------------------------------------------------------------------------

    private val now = at("2026-10-29T22:00:00")
    private val timed = PauseRange(now, now + 60 * PausePolicy.MINUTE_MS)
    private val open = PauseRange(now, PauseRange.UNTIL_RESUMED)

    @Test
    fun aNewPause_isShown_once() {
        assertEquals(NoticeStep.POST, PausePolicy.notice(timed, PauseNotice.NONE, now))
        assertEquals(NoticeStep.NOTHING, PausePolicy.notice(timed, PauseNotice(timed.startMs, now), now + 50 * PausePolicy.MINUTE_MS))
    }

    /** A second pause later the same day is a new one: shown again, though the last was shown minutes ago. */
    @Test
    fun anotherPause_isShownAgain() {
        val later = PauseRange(now + 90 * PausePolicy.MINUTE_MS, now + 120 * PausePolicy.MINUTE_MS)

        assertEquals(NoticeStep.POST, PausePolicy.notice(later, PauseNotice(timed.startMs, now), later.startMs))
    }

    @Test
    fun untilResumed_isShownAgainOnceADay() {
        val shown = PauseNotice(open.startMs, now)

        assertEquals(NoticeStep.NOTHING, PausePolicy.notice(open, shown, now + PausePolicy.NOTICE_REPEAT_MS - 1))
        assertEquals(NoticeStep.POST, PausePolicy.notice(open, shown, now + PausePolicy.NOTICE_REPEAT_MS))
    }

    @Test
    fun noPauseGoing_takesTheNotificationAway_once() {
        assertEquals(NoticeStep.CANCEL, PausePolicy.notice(null, PauseNotice(timed.startMs, now), now))
        assertEquals(NoticeStep.NOTHING, PausePolicy.notice(null, PauseNotice.NONE, now))
    }

    // ---- the heartbeat ------------------------------------------------------------------------------------------

    @Test
    fun aPausedRunThatSentNothing_saysItsAlive_atMostEverySixHours() {
        assertTrue(PausePolicy.heartbeatDue(paused = true, sentAnything = false, lastHeartbeatMs = 0L, nowMs = now))
        assertFalse(PausePolicy.heartbeatDue(true, false, now - PausePolicy.HEARTBEAT_EVERY_MS + 1, now))
        assertTrue(PausePolicy.heartbeatDue(true, false, now - PausePolicy.HEARTBEAT_EVERY_MS, now))
    }

    @Test
    fun notPaused_orSomethingSent_isNoHeartbeat() {
        assertFalse(PausePolicy.heartbeatDue(paused = false, sentAnything = false, lastHeartbeatMs = 0L, nowMs = now))
        assertFalse(PausePolicy.heartbeatDue(paused = true, sentAnything = true, lastHeartbeatMs = 0L, nowMs = now))
    }

    /** A clock moved back leaves the last heartbeat in the future; that must not silence the phone for good. */
    @Test
    fun aHeartbeatFromTheFuture_isNoReasonToWait() {
        assertTrue(PausePolicy.heartbeatDue(true, false, now + PausePolicy.HEARTBEAT_EVERY_MS, now))
    }
}
