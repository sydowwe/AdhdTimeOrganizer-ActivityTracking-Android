package com.timeorganizer.usagetracker.review

import org.junit.Assert.assertEquals
import org.junit.Test

class SnoozeStateTest {

    private val now = 1_800_000_000_000L
    private val week = SnoozeState.SNOOZE_MS

    @Test
    fun aSnooze_holdsForAWeek_thenEnds() {
        val state = SnoozeState().snooze("com.reddit", now)

        assertEquals(listOf("com.reddit"), state.active(now))
        assertEquals(listOf("com.reddit"), state.active(now + week - 1))
        assertEquals(emptyList<String>(), state.active(now + week))
    }

    @Test
    fun aSecondSnooze_extends_andNeverShortens() {
        val first = SnoozeState().snooze("com.reddit", now)
        val later = first.snooze("com.reddit", now + 3 * DAY)

        assertEquals(now + 3 * DAY + week, later.until["com.reddit"])
        // A stored end further out (a clock moved back) is kept.
        assertEquals(now + week, first.snooze("com.reddit", now - DAY).until["com.reddit"])
    }

    @Test
    fun snoozing_dropsEndedSnoozes_soTheMapDoesNotGrow() {
        val old = SnoozeState(mapOf("com.old" to now - 1))

        assertEquals(setOf("com.new"), old.snooze("com.new", now).until.keys)
    }

    @Test
    fun aMappedOrIgnoredApp_isForgotten() {
        assertEquals(emptyList<String>(), SnoozeState().snooze("com.reddit", now).clear("com.reddit").active(now))
    }

    /** What goes out as `exclude`: sorted, so the URL is the same for the same snoozes; capped at the server's limit. */
    @Test
    fun theExcludeList_isSorted_andCappedToTheLatestEnds() {
        val many = SnoozeState((0 until 120).associate { "pkg.%03d".format(it) to now + week + it })

        val active = many.active(now)

        assertEquals(SnoozeState.MAX_EXCLUDED, active.size)
        assertEquals(active.sorted(), active)
        assertEquals("pkg.020", active.first())
    }

    private companion object {
        const val DAY = 24 * 60 * 60 * 1000L
    }
}
