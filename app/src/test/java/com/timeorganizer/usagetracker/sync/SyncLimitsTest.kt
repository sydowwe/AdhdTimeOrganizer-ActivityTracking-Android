package com.timeorganizer.usagetracker.sync

import com.timeorganizer.usagetracker.usagestats.RawSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every rule here is one the server before 2026-10-18 answered with a 400 for the whole sync — so none of
 * these may ever reach it.
 */
class SyncLimitsTest {

    private val minute = 60_000L

    @Test
    fun anOrdinarySession_isOnePiece() {
        val pieces = SyncLimits.prepare(RawSession("com.slack", 0, 15 * minute), "Slack")

        assertEquals(listOf(OutgoingSession("com.slack", "Slack", 0, 15 * minute)), pieces)
        assertEquals(900L, pieces.single().durationSeconds)
    }

    @Test
    fun aMissingOrOverWidePackage_isNotQueued() {
        assertTrue(SyncLimits.prepare(RawSession("", 0, minute), "x").isEmpty())
        assertTrue(SyncLimits.prepare(RawSession("  ", 0, minute), "x").isEmpty())
        assertTrue(SyncLimits.prepare(RawSession("p".repeat(256), 0, minute), "x").isEmpty())
        assertEquals(1, SyncLimits.prepare(RawSession("p".repeat(255), 0, minute), "x").size)
    }

    @Test
    fun aGlanceUnderFiveSeconds_isNotQueued() {
        assertTrue(SyncLimits.prepare(RawSession("a", 0, 4_999), "A").isEmpty())
        assertEquals(1, SyncLimits.prepare(RawSession("a", 0, 5_000), "A").size)
    }

    @Test
    fun aBlankLabel_readsAsThePackageName() {
        assertEquals("com.gone", SyncLimits.fitLabel(null, "com.gone"))
        assertEquals("com.gone", SyncLimits.fitLabel("", "com.gone"))
        assertEquals("com.gone", SyncLimits.fitLabel("   ", "com.gone"))
    }

    @Test
    fun anOverWideLabel_isCutToTheColumn_neverThroughASurrogatePair() {
        assertEquals(255, SyncLimits.fitLabel("a".repeat(300), "p").length)

        val straddling = "a".repeat(254) + "😀" + "tail"
        assertEquals("a".repeat(254), SyncLimits.fitLabel(straddling, "p"))
    }

    @Test
    fun aSessionLongerThanADay_isSentAsConsecutivePieces() {
        val day = SyncLimits.MAX_SESSION_MS
        val pieces = SyncLimits.prepare(RawSession("kiosk", 0, 2 * day + 2 * 60 * minute), "Kiosk")

        assertEquals(listOf(0L, day, 2 * day), pieces.map { it.startMs })
        assertEquals(listOf(day, 2 * day, 2 * day + 2 * 60 * minute), pieces.map { it.endMs })
        assertTrue(pieces.all { it.durationSeconds in 1..86_400 })
    }

    @Test
    fun aSubSecondTail_isNotSentAsAZeroDurationPiece() {
        val pieces = SyncLimits.prepare(RawSession("kiosk", 0, SyncLimits.MAX_SESSION_MS + 500), "Kiosk")

        assertEquals(1, pieces.size)
        assertEquals(86_400L, pieces.single().durationSeconds)
    }
}
