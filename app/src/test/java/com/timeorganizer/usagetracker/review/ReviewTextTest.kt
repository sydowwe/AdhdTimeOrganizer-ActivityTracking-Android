package com.timeorganizer.usagetracker.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReviewTextTest {

    @Test
    fun duration_readsInMinutesUnderAnHour() {
        assertEquals("50 m", ReviewText.duration(50 * 60L))
        assertEquals("5 m", ReviewText.duration(5 * 60L))
    }

    @Test
    fun duration_readsHoursAndMinutes() {
        assertEquals("1 h 40 m", ReviewText.duration(100 * 60L))
        assertEquals("2 h", ReviewText.duration(120 * 60L))
        assertEquals("1 h 5 m", ReviewText.duration(65 * 60L))
    }

    @Test
    fun duration_roundsToTheNearestMinute_andAnyTimeIsAtLeastOne() {
        assertEquals("1 m", ReviewText.duration(29))
        assertEquals("1 m", ReviewText.duration(89))
        assertEquals("2 m", ReviewText.duration(90))
        assertEquals("1 h", ReviewText.duration(59 * 60L + 40))
    }

    @Test
    fun duration_ofNothing_isZero() {
        assertEquals("0 m", ReviewText.duration(0))
        assertEquals("0 m", ReviewText.duration(-5))
    }

    @Test
    fun appLine_listsTheFirstThree_andCountsTheRest() {
        val apps = listOf("Reddit" to 3000L, "Chrome" to 1800L, "Maps" to 900L, "Notes" to 600L, "Clock" to 300L)

        val line = ReviewText.appLine(apps)

        assertEquals("Reddit 50 m, Chrome 30 m, Maps 15 m", line.listed)
        assertEquals(2, line.more)
    }

    @Test
    fun appLine_ofFewerThanThree_leavesNothingOut() {
        val line = ReviewText.appLine(listOf("Reddit" to 3000L))

        assertEquals("Reddit 50 m", line.listed)
        assertEquals(0, line.more)
    }

    @Test
    fun instantMs_readsAZInstant() {
        assertEquals(1_792_443_600_000L, ReviewText.instantMs("2026-10-19T21:00:00Z"))
    }

    /** A .NET DateTime of unspecified kind serializes with no zone; the contract says it is UTC. */
    @Test
    fun instantMs_readsAnInstantWithNoZoneAsUtc() {
        assertEquals(1_792_443_600_000L, ReviewText.instantMs("2026-10-19T21:00:00"))
    }

    @Test
    fun instantMs_readsDotNetSevenDigitFractions() {
        assertEquals(1_792_443_600_000L, ReviewText.instantMs("2026-10-19T21:00:00.0000000Z"))
    }

    @Test
    fun instantMs_ofGarbage_isNull() {
        assertNull(ReviewText.instantMs(null))
        assertNull(ReviewText.instantMs(""))
        assertNull(ReviewText.instantMs("tonight"))
    }
}
