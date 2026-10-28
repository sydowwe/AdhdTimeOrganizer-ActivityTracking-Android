package com.timeorganizer.usagetracker.notify

import com.timeorganizer.usagetracker.notify.NudgeGap.MIN_GAP_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NudgeGapTest {

    private val now = 1_000_000_000_000L
    private val minute = 60_000L

    @Test
    fun anotherFamilyPostedWithinTenMinutes_holdsUntilTenMinutesAfterIt() {
        val last = mapOf(NudgeFamily.LOOP to now - 3 * minute)

        assertEquals(now + 7 * minute, NudgeGap.heldUntil(last, NudgeFamily.DRIFT, now))
    }

    @Test
    fun theFamilysOwnPost_holdsNothing() {
        assertNull(NudgeGap.heldUntil(mapOf(NudgeFamily.DRIFT to now - minute), NudgeFamily.DRIFT, now))
    }

    @Test
    fun tenMinutesOrMoreAgo_holdsNothing() {
        assertNull(NudgeGap.heldUntil(mapOf(NudgeFamily.CHIME to now - MIN_GAP_MS), NudgeFamily.LOOP, now))
    }

    @Test
    fun severalRecent_theLatestDecides() {
        val last = mapOf(NudgeFamily.CHIME to now - 8 * minute, NudgeFamily.LOOP to now - 2 * minute)

        assertEquals(now + 8 * minute, NudgeGap.heldUntil(last, NudgeFamily.DRIFT, now))
    }

    @Test
    fun aPostStampedInTheFuture_theClockMovedBack_holdsNothing() {
        assertNull(NudgeGap.heldUntil(mapOf(NudgeFamily.LOOP to now + minute), NudgeFamily.DRIFT, now))
    }

    @Test
    fun nothingPosted_holdsNothing() {
        assertNull(NudgeGap.heldUntil(emptyMap(), NudgeFamily.CHIME, now))
    }
}
