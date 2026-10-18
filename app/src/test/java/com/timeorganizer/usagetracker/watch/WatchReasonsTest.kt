package com.timeorganizer.usagetracker.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchReasonsTest {

    @Test
    fun nothingAsked_isNotActive() {
        assertFalse(WatchReasons().isActive(0))
        assertNull(WatchReasons().endsAtMs(0))
        assertNull(WatchReasons().nextEndMs(0))
    }

    @Test
    fun theWatcherRunsWhileAnyReasonIsActive() {
        val reasons = WatchReasons().watch("drift", 1_000).watch("chime", 5_000)

        assertTrue(reasons.isActive(999))
        assertTrue(reasons.isActive(1_000))
        assertFalse(reasons.isActive(5_000))
        assertEquals(5_000L, reasons.endsAtMs(0))
        assertEquals(1_000L, reasons.nextEndMs(0))
        assertEquals(5_000L, reasons.nextEndMs(1_000))
    }

    @Test
    fun stoppingOneReason_leavesTheOthers() {
        val reasons = WatchReasons().watch("drift", 1_000).watch("chime", 5_000).stop("chime")

        assertEquals(1_000L, reasons.endsAtMs(0))
        assertFalse(reasons.stop("drift").isActive(0))
    }

    @Test
    fun askingAgain_movesTheEnd_eitherWay() {
        val reasons = WatchReasons().watch("drift", 5_000)

        assertEquals(9_000L, reasons.watch("drift", 9_000).endsAtMs(0))
        assertEquals(2_000L, reasons.watch("drift", 2_000).endsAtMs(0))
    }

    @Test
    fun anEndedReason_isDroppedByActive() {
        val reasons = WatchReasons().watch("drift", 1_000).watch("chime", 5_000)

        assertEquals(mapOf("chime" to 5_000L), reasons.active(1_000).untilMsByReason)
    }
}
