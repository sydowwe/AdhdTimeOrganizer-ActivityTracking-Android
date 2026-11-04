package com.timeorganizer.usagetracker.today

import com.timeorganizer.usagetracker.now.NowBlock
import com.timeorganizer.usagetracker.now.NowFacts
import com.timeorganizer.usagetracker.pause.PauseRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What the widget keeps from `/now`, which block it shows, and when it has to look again (prompt 19). */
class WidgetPlanTest {

    private val thesis = KeptBlock("Thesis", 100, 200)
    private val gym = KeptBlock("Gym", 300, 400)
    private val nextDay = 10_000L

    private fun facts(current: List<NowBlock>, next: NowBlock? = null) = NowFacts(
        silent = false,
        current = current,
        deskLastActiveMs = null,
        deskIdle = true,
        deskWork = null,
        distractionPackages = emptySet(),
        next = next
    )

    private fun block(name: String, startMs: Long, endMs: Long) = NowBlock(1, name, startMs, endMs, inProgress = false)

    // ---- keep ---------------------------------------------------------------------------------------------------

    @Test
    fun keep_takesTheCurrentBlocksAndTheNext() {
        val kept = WidgetPlan.keep(facts(listOf(block("Thesis", 100, 200)), next = block("Gym", 300, 400)))

        assertEquals(listOf(thesis, gym), kept)
    }

    @Test
    fun keep_withNoPlan_isEmpty() {
        assertEquals(emptyList<KeptBlock>(), WidgetPlan.keep(facts(emptyList())))
    }

    @Test
    fun keep_dropsABlockWithNoName() {
        assertEquals(listOf(gym), WidgetPlan.keep(facts(listOf(block(" ", 100, 200)), next = block("Gym", 300, 400))))
    }

    // ---- current ------------------------------------------------------------------------------------------------

    @Test
    fun current_isTheBlockCoveringNow() {
        assertEquals(thesis, WidgetPlan.current(listOf(thesis, gym), 150))
    }

    @Test
    fun current_startsAtTheBlocksStart() {
        assertEquals(gym, WidgetPlan.current(listOf(thesis, gym), 300))
    }

    @Test
    fun current_isGoneAtTheBlocksEnd() {
        assertNull(WidgetPlan.current(listOf(thesis, gym), 200))
    }

    @Test
    fun current_ofOverlappingBlocks_isTheEarliest() {
        val late = KeptBlock("Call", 150, 180)

        assertEquals(thesis, WidgetPlan.current(listOf(late, thesis), 160))
    }

    @Test
    fun current_ofNothingKept_isNull() {
        assertNull(WidgetPlan.current(emptyList(), 150))
    }

    // ---- next refresh -------------------------------------------------------------------------------------------

    @Test
    fun nextRefresh_isTheBlocksEnd() {
        assertEquals(200L, WidgetPlan.nextRefreshMs(listOf(thesis, gym), null, nextDay, 150))
    }

    @Test
    fun nextRefresh_betweenBlocks_isTheNextStart() {
        assertEquals(300L, WidgetPlan.nextRefreshMs(listOf(thesis, gym), null, nextDay, 250))
    }

    @Test
    fun nextRefresh_atABoundary_isTheOneAfterIt() {
        assertEquals(300L, WidgetPlan.nextRefreshMs(listOf(thesis, gym), null, nextDay, 200))
    }

    @Test
    fun nextRefresh_afterEveryBlock_isMidnight() {
        assertEquals(nextDay, WidgetPlan.nextRefreshMs(listOf(thesis, gym), null, nextDay, 500))
    }

    @Test
    fun nextRefresh_withNothingKept_isMidnight() {
        assertEquals(nextDay, WidgetPlan.nextRefreshMs(emptyList(), null, nextDay, 500))
    }

    @Test
    fun nextRefresh_aPausesEndComesFirst() {
        assertEquals(170L, WidgetPlan.nextRefreshMs(listOf(thesis), PauseRange(120, 170), nextDay, 150))
    }

    @Test
    fun nextRefresh_aPauseUntilResumedHasNoEnd() {
        assertEquals(nextDay, WidgetPlan.nextRefreshMs(emptyList(), PauseRange(120, PauseRange.UNTIL_RESUMED), nextDay, 150))
    }
}
