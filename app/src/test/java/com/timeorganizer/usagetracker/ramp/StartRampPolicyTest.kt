package com.timeorganizer.usagetracker.ramp

import com.timeorganizer.usagetracker.now.NowBlock
import com.timeorganizer.usagetracker.now.NowFacts
import com.timeorganizer.usagetracker.ramp.StartRampPolicy.MINUTE_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StartRampPolicyTest {

    /** Thesis starts here. */
    private val start = 1_000_000_000_000L

    private fun sec(seconds: Int) = start + seconds * 1_000L
    private fun min(minutes: Int) = start + minutes * MINUTE_MS

    /** Thesis, 0–90 min, not started. */
    private val thesis = NowBlock(7, "Thesis", min(0), min(90), inProgress = false, activityId = 12)
    private val email = NowBlock(8, "Email", min(90), min(120), inProgress = false, activityId = 15)

    private fun facts(
        current: List<NowBlock> = listOf(thesis),
        next: NowBlock? = email,
        silent: Boolean = false,
        deskIdle: Boolean = true,
        timerRunning: Boolean? = false
    ) = NowFacts(silent, current, deskLastActiveMs = null, deskIdle = deskIdle, deskWork = null, emptySet(), next, timerRunning)

    private fun input(
        nowMs: Long,
        facts: NowFacts? = facts(),
        enabled: Boolean = true,
        blocks: RampBlocks = RampBlocks.NOT_OPTIONAL,
        locallySilent: Boolean = false,
        heldUntilMs: Long? = null
    ) = StartRampInput(nowMs, enabled, blocks, facts, locallySilent, heldUntilMs)

    private fun decide(state: StartRampState = StartRampState.NONE, nowMs: Long = sec(5), facts: NowFacts? = facts()) =
        StartRampPolicy.decide(state, input(nowMs, facts))

    /** The ramp for Thesis is on screen, the block settled. */
    private val shownForThesis = StartRampState(
        settled = mapOf(7L to min(90)), showingBlockId = 7, showingEndMs = min(90), showingCanDefer = true
    )

    // ---- at the start ---------------------------------------------------------

    @Test
    fun atABlocksStart_postsOneRamp_andSettlesTheBlock() {
        val step = decide()

        assertEquals(StartRampNudge(thesis, lateByMs = 5_000L, canDefer = true), step.post)
        assertEquals(shownForThesis, step.state)
        assertFalse(step.cancel)
        assertEquals("the next block's start is the next wake", min(90), step.wakeAtMs)
        assertNull("nothing is held, so the watcher isn't needed", step.watchUntilMs)
    }

    @Test
    fun aSettledBlock_isNeverAskedAboutAgain() {
        val step = decide(shownForThesis, nowMs = min(5))

        assertNull(step.post)
        assertEquals(shownForThesis, step.state)
    }

    @Test
    fun beforeTheStart_nothingIsSaid_andTheWakeIsTheStart() {
        val step = decide(nowMs = min(-30), facts = facts(current = emptyList(), next = thesis))

        assertNull(step.post)
        assertEquals(min(0), step.wakeAtMs)
        assertEquals("the in-process look, for a phone in use when the alarm comes late", min(0), step.recheckAtMs)
    }

    @Test
    fun theNextBlock_isAskedAbout_whenAReusedAnswerStillHasItAsNext() {
        // /now may be reused for 30 s: the block it called "next" has begun meanwhile.
        val step = decide(nowMs = sec(10), facts = facts(current = emptyList(), next = thesis))

        assertEquals(thesis, step.post?.block)
    }

    // ---- late -------------------------------------------------------------------

    @Test
    fun late_isAskedAbout_whileTenMinutesAreLeft_sayingHowLate() {
        val step = decide(nowMs = min(20))

        assertEquals(StartRampNudge(thesis, lateByMs = 20 * MINUTE_MS, canDefer = true), step.post)
    }

    @Test
    fun late_withLessThanTenMinutesLeft_isLetGoWithoutAWord() {
        val step = decide(nowMs = min(81))

        assertNull(step.post)
        assertTrue(7L in step.state.settled)
    }

    @Test
    fun aShortBlock_isStillAskedAboutInItsFirstMinutes() {
        val short = NowBlock(9, "Call mum", min(0), min(8), inProgress = false)

        val step = decide(nowMs = min(2), facts = facts(current = listOf(short), next = null))

        assertEquals(short, step.post?.block)
    }

    // ---- which blocks ------------------------------------------------------------

    @Test
    fun allButOptional_skipsAnOptionalBlock_criticalOnlyAsksOnlyForCritical() {
        val optional = thesis.copy(isOptional = true)
        val critical = thesis.copy(isCritical = true)

        fun post(block: NowBlock, blocks: RampBlocks) =
            StartRampPolicy.decide(StartRampState.NONE, input(sec(5), facts(current = listOf(block)), blocks = blocks)).post

        assertNull(post(optional, RampBlocks.NOT_OPTIONAL))
        assertNotNull(post(optional, RampBlocks.ALL))
        assertNotNull("a block of no importance is asked about by default", post(thesis, RampBlocks.NOT_OPTIONAL))
        assertNull(post(thesis, RampBlocks.CRITICAL))
        assertNotNull(post(critical, RampBlocks.CRITICAL))
    }

    @Test
    fun anOptionalNextBlock_isNoWake_underTheDefault() {
        val step = decide(nowMs = min(-30), facts = facts(current = emptyList(), next = thesis.copy(isOptional = true)))

        assertNull(step.wakeAtMs)
    }

    // ---- already under way ------------------------------------------------------

    @Test
    fun aBlockAlreadyStarted_isSettledWithoutAWord() {
        val step = decide(facts = facts(current = listOf(thesis.copy(inProgress = true))))

        assertNull(step.post)
        assertTrue(7L in step.state.settled)
    }

    @Test
    fun theDeskInUse_settlesTheBlock_theDesktopsRampAsksThere() {
        val step = decide(facts = facts(deskIdle = false))

        assertNull(step.post)
        assertTrue(7L in step.state.settled)
    }

    // ---- held, never dropped ----------------------------------------------------

    @Test
    fun aRunningTimer_holdsTheRamp_untilItStops() {
        val held = decide(facts = facts(timerRunning = true))

        assertNull(held.post)
        assertTrue("held is not settled", held.state.settled.isEmpty())
        assertEquals(sec(5) + MINUTE_MS, held.recheckAtMs)
        assertEquals("the watcher, so the next pickup is seen, while it is still worth saying", min(80), held.watchUntilMs)

        val after = decide(held.state, nowMs = min(25), facts = facts(timerRunning = false))
        assertEquals(StartRampNudge(thesis, lateByMs = 25 * MINUTE_MS, canDefer = true), after.post)
    }

    @Test
    fun aServerFromBeforeTheRamp_saysNothingOfTimers_andTheRampIsNotHeld() {
        val step = decide(facts = facts(timerRunning = null))

        assertNotNull(step.post)
    }

    @Test
    fun theServersSilence_aCallOrDnd_andAnotherNudgesGap_holdTheRamp() {
        val silent = decide(facts = facts(silent = true))
        val call = StartRampPolicy.decide(StartRampState.NONE, input(sec(5), locallySilent = true))
        val gap = StartRampPolicy.decide(StartRampState.NONE, input(sec(5), heldUntilMs = min(8)))

        listOf(silent, call, gap).forEach { step ->
            assertNull(step.post)
            assertTrue(step.state.settled.isEmpty())
            assertNotNull(step.watchUntilMs)
        }
        assertEquals("another nudge's gap is waited out to its end", min(8), gap.recheckAtMs)
        assertNotNull("said once the gap is over", StartRampPolicy.decide(gap.state, input(min(8))).post)
    }

    @Test
    fun aHoldPastTheLateLimit_letsTheBlockGo() {
        val step = StartRampPolicy.decide(StartRampState.NONE, input(min(81), locallySilent = true))

        assertNull(step.post)
        assertTrue(7L in step.state.settled)
    }

    // ---- the buttons -------------------------------------------------------------

    @Test
    fun inTwoMinutes_asksOnceMore_withoutThatButton() {
        val later = StartRampPolicy.later(shownForThesis, blockId = 7, nowMs = min(1))

        assertEquals(StartRampState(againBlockId = 7, againAtMs = min(3)), later)
        assertEquals(min(3), decide(later, nowMs = min(2)).wakeAtMs)
        assertNull(decide(later, nowMs = min(2)).post)

        val again = decide(later, nowMs = min(3))
        assertEquals(StartRampNudge(thesis, lateByMs = 3 * MINUTE_MS, canDefer = false), again.post)
        assertNull(again.state.againBlockId)
        assertTrue(7L in again.state.settled)

        val noThirdChance = StartRampPolicy.later(again.state, blockId = 7, nowMs = min(4))
        assertTrue("a stale press on the repeat only withdraws", 7L in noThirdChance.settled)
        assertNull(noThirdChance.againBlockId)
    }

    @Test
    fun startNow_notThisOne_andASwipe_withdrawTheRamp_theBlockStaysSettled() {
        listOf(StartRampPolicy.answered(shownForThesis), StartRampPolicy.dismissed(shownForThesis)).forEach { state ->
            assertNull(state.showingBlockId)
            assertTrue(7L in state.settled)
            assertNull(decide(state, nowMs = min(5)).post)
        }
    }

    // ---- withdrawn -----------------------------------------------------------------

    @Test
    fun theRampOnScreen_goes_whenTheBlockIsStartedElsewhere() {
        val step = decide(shownForThesis, nowMs = min(5), facts = facts(current = listOf(thesis.copy(inProgress = true))))

        assertTrue(step.cancel)
        assertNull(step.state.showingBlockId)
    }

    @Test
    fun theRampOnScreen_goes_whenTheBlockIsGoneOrOver() {
        val gone = decide(shownForThesis, nowMs = min(5), facts = facts(current = emptyList(), next = email))
        val over = decide(shownForThesis, nowMs = min(90), facts = facts(current = listOf(email), next = null))
        val offline = decide(shownForThesis, nowMs = min(90), facts = null)

        listOf(gone, over, offline).forEach { assertTrue(it.cancel) }
    }

    @Test
    fun withoutAnAnswer_nothingStarts_andARampUpStaysUntilItsEnd() {
        assertNull(decide(nowMs = sec(5), facts = null).post)

        val up = decide(shownForThesis, nowMs = min(5), facts = null)
        assertFalse(up.cancel)
        assertEquals(min(90), up.recheckAtMs)
    }

    @Test
    fun turnedOff_endsEverything_andWithdrawsTheRamp() {
        val step = StartRampPolicy.decide(shownForThesis, input(min(5), enabled = false))

        assertEquals(StartRampState.NONE, step.state)
        assertTrue(step.cancel)
        assertNull(step.wakeAtMs)
    }

    // ---- the next block ---------------------------------------------------------

    @Test
    fun theNextBlocksStart_isAskedAbout_afterTheOneBefore() {
        val inEmail = decide(shownForThesis, nowMs = min(90) + 5_000L, facts = facts(current = listOf(email), next = null))

        assertEquals(StartRampNudge(email, lateByMs = 5_000L, canDefer = true), inEmail.post)
        assertEquals(8L, inEmail.state.showingBlockId)
    }

    @Test
    fun settledBlocks_areForgottenADayAfterTheirEnd() {
        val old = StartRampState(settled = mapOf(1L to min(-2000), 7L to min(90)))

        val step = decide(old, nowMs = min(5))

        assertEquals(setOf(7L), step.state.settled.keys)
    }

    @Test
    fun askableUntil_isTheLateLimit_orTheEndOfAShortBlock() {
        assertEquals(min(80), StartRampPolicy.askableUntil(thesis))
        assertEquals(min(3), StartRampPolicy.askableUntil(NowBlock(9, "Short", min(0), min(8), inProgress = false)))
        assertEquals(min(2), StartRampPolicy.askableUntil(NowBlock(9, "Tiny", min(0), min(2), inProgress = false)))
    }

    // ---- what is stored ------------------------------------------------------------

    @Test
    fun theSettledList_readsBackWhatWasStored_andDropsWhatItCantRead() {
        assertEquals(mapOf(7L to 100L, 8L to 200L), StartRampPreferences.settledFrom("7:100,8:200"))
        assertEquals(mapOf(8L to 200L), StartRampPreferences.settledFrom("x:1,7:,8:200,9"))
        assertEquals(emptyMap<Long, Long>(), StartRampPreferences.settledFrom(null))
        assertEquals(emptyMap<Long, Long>(), StartRampPreferences.settledFrom(""))
    }

    @Test
    fun startNowsLink_isTheWebAppsHomeWithTheBlockToTrack() {
        assertEquals(
            "https://test.adhdtimeorganizer.com/?track=7",
            StartRampActionActivity.trackLink(7, "https://test.adhdtimeorganizer.com/")
        )
    }
}
