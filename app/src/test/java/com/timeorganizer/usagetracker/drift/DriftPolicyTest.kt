package com.timeorganizer.usagetracker.drift

import com.timeorganizer.usagetracker.drift.DriftPolicy.MINUTE_MS
import com.timeorganizer.usagetracker.now.NowBlock
import com.timeorganizer.usagetracker.now.NowFacts
import com.timeorganizer.usagetracker.usagestats.EventKind
import com.timeorganizer.usagetracker.usagestats.UsageEventRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DriftPolicyTest {

    /** Thesis starts here. */
    private val start = 1_000_000_000_000L

    private fun sec(seconds: Int) = start + seconds * 1_000L
    private fun min(minutes: Int) = start + minutes * MINUTE_MS

    private val instagram = "com.instagram.android"
    private val reddit = "com.reddit.frontpage"
    private val youtube = "com.google.android.youtube"
    private val whatsapp = "com.whatsapp"

    /** Thesis, activity 12, 0–90 min. */
    private val thesis = NowBlock(7, "Thesis", min(0), min(90), inProgress = true, activityId = 12)
    private val email = NowBlock(8, "Email", min(90), min(120), inProgress = false, activityId = 15)

    private fun facts(
        current: List<NowBlock> = listOf(thesis),
        next: NowBlock? = null,
        silent: Boolean = false,
        distractions: Set<String> = setOf(instagram, reddit, youtube)
    ) = NowFacts(silent, current, deskLastActiveMs = null, deskIdle = true, deskWork = null, distractions, next)

    private fun open(packageName: String, fromMs: Long, nowMs: Long) = DriftSitting(packageName, fromMs, nowMs, open = true)
    private fun closed(packageName: String, fromMs: Long, toMs: Long) = DriftSitting(packageName, fromMs, toMs, open = false)

    private fun input(
        nowMs: Long,
        sittings: List<DriftSitting>,
        facts: NowFacts? = facts(),
        enabled: Boolean = true,
        ruleActivities: Map<String, Long> = emptyMap(),
        locallySilent: Boolean = false,
        heldUntilMs: Long? = null
    ) = DriftInput(nowMs, enabled, facts, sittings, ruleActivities, locallySilent, heldUntilMs)

    /** In Instagram since [fromSecond], still in front at [nowSecond]. */
    private fun inInstagram(fromSecond: Int, nowSecond: Int, state: DriftState = DriftState.NONE, facts: NowFacts? = facts()) =
        DriftPolicy.decide(state, input(sec(nowSecond), listOf(open(instagram, sec(fromSecond), sec(nowSecond))), facts))

    private val nudgedInThesis = DriftState(blockId = 7, blockEndMs = min(90), done = true, showing = true)

    // ---- the grace ----------------------------------------------------------

    @Test
    fun aDistractionAppJustOpened_waitsForTheGrace_andLooksAgainWhenItRunsOut() {
        val step = inInstagram(fromSecond = 600, nowSecond = 610)

        assertNull(step.post)
        assertEquals(DriftState(blockId = 7, blockEndMs = min(90)), step.state)
        assertEquals("20 s of the 30 s grace are left", sec(630), step.recheckAtMs)
    }

    @Test
    fun thirtySecondsInFront_postsOneNudge_namingTheBlockAndTheAppInFront() {
        val step = inInstagram(fromSecond = 600, nowSecond = 630)

        assertEquals(DriftNudge(thesis, instagram), step.post)
        assertEquals(nudgedInThesis, step.state)
        assertFalse(step.cancel)
        assertEquals("looked at again at the block's end, to withdraw it", min(90), step.recheckAtMs)
    }

    @Test
    fun timeInSeveralDistractionApps_addsUp_namedByTheOneInFront() {
        val sittings = listOf(closed(instagram, sec(600), sec(615)), open(reddit, sec(620), sec(636)))

        val step = DriftPolicy.decide(DriftState.NONE, input(sec(636), sittings))

        assertEquals("15 s of Instagram and 16 s of Reddit", DriftNudge(thesis, reddit), step.post)
    }

    @Test
    fun aMinuteAway_pausesTheCount_andIsNotCounted() {
        // 20 s of Instagram, 50 s in a chat, 12 s of Reddit: 32 s.
        val sittings = listOf(closed(instagram, sec(600), sec(620)), open(reddit, sec(670), sec(682)))

        assertEquals(DriftNudge(thesis, reddit), DriftPolicy.decide(DriftState.NONE, input(sec(682), sittings)).post)
    }

    @Test
    fun longerThanAMinuteAway_startsTheCountOver() {
        // 20 s of Instagram, 70 s elsewhere, 12 s of Reddit: only the 12 s count.
        val sittings = listOf(closed(instagram, sec(600), sec(620)), open(reddit, sec(690), sec(702)))

        val step = DriftPolicy.decide(DriftState.NONE, input(sec(702), sittings))

        assertNull(step.post)
        assertEquals(sec(720), step.recheckAtMs)
    }

    @Test
    fun splitScreenWithTwoDistractionApps_countsTheOverlapOnce() {
        val sittings = listOf(open(instagram, sec(600), sec(620)), open(reddit, sec(600), sec(620)))

        assertNull("20 s, not 40", DriftPolicy.decide(DriftState.NONE, input(sec(620), sittings)).post)
    }

    @Test
    fun onlyTimeInsideTheBlock_counts() {
        // Instagram open since a minute before Thesis began; 10 s of it are in the block.
        val step = inInstagram(fromSecond = -60, nowSecond = 10)

        assertNull(step.post)
        assertEquals(sec(30), step.recheckAtMs)
    }

    // ---- what counts --------------------------------------------------------

    @Test
    fun aDistractionAppMappedToTheBlocksOwnActivity_isThePlan_notDrift() {
        val sittings = listOf(open(youtube, sec(600), sec(700)))

        val step = DriftPolicy.decide(DriftState.NONE, input(sec(700), sittings, ruleActivities = mapOf(youtube to 12L)))

        assertNull(step.post)
        assertEquals("nothing counted is in front: only the block's end is looked at", min(90), step.recheckAtMs)
    }

    @Test
    fun aDistractionAppMappedToAnotherActivity_stillCounts() {
        val sittings = listOf(open(youtube, sec(600), sec(700)))

        val step = DriftPolicy.decide(DriftState.NONE, input(sec(700), sittings, ruleActivities = mapOf(youtube to 99L)))

        assertEquals(DriftNudge(thesis, youtube), step.post)
    }

    @Test
    fun anAppTheServerDoesNotCallADistraction_neverCounts() {
        val sittings = listOf(open(whatsapp, sec(600), sec(700)))

        assertNull(DriftPolicy.decide(DriftState.NONE, input(sec(700), sittings)).post)
    }

    @Test
    fun aBlockWithNoActivity_countsEveryDistractionApp() {
        val unnamed = thesis.copy(activityId = null)
        val sittings = listOf(open(youtube, sec(600), sec(700)))

        val step = DriftPolicy.decide(
            DriftState.NONE, input(sec(700), sittings, facts(current = listOf(unnamed)), ruleActivities = mapOf(youtube to 12L))
        )

        assertEquals(DriftNudge(unnamed, youtube), step.post)
    }

    @Test
    fun noDistractionAppInFront_saysNothing_theNextOneToTheFrontWakesIt() {
        val sittings = listOf(closed(instagram, sec(600), sec(700)))

        val step = DriftPolicy.decide(DriftState.NONE, input(sec(720), sittings))

        assertNull(step.post)
        assertEquals(min(90), step.recheckAtMs)
    }

    // ---- one per block --------------------------------------------------------

    @Test
    fun aBlockAlreadyNudgedIn_saysNothingMore() {
        val step = inInstagram(fromSecond = 1200, nowSecond = 1500, state = nudgedInThesis.copy(showing = false))

        assertNull(step.post)
        assertFalse(step.cancel)
        assertEquals(min(90), step.recheckAtMs)
    }

    @Test
    fun aNewBlock_startsAgain_andWithdrawsTheOldBlocksNudge() {
        val step = DriftPolicy.decide(
            nudgedInThesis,
            input(min(91), listOf(open(instagram, min(90), min(91))), facts(current = listOf(email)))
        )

        assertTrue(step.cancel)
        assertEquals(DriftNudge(email, instagram), step.post)
        assertEquals(DriftState(blockId = 8, blockEndMs = min(120), done = true, showing = true), step.state)
    }

    @Test
    fun theSameBlockMovedOrExtended_keepsItsAnswer_andTakesTheNewEnd() {
        val extended = thesis.copy(endMs = min(100))

        val step = inInstagram(fromSecond = 1200, nowSecond = 1500, state = nudgedInThesis, facts = facts(current = listOf(extended)))

        assertNull(step.post)
        assertEquals(nudgedInThesis.copy(blockEndMs = min(100)), step.state)
    }

    @Test
    fun theBlockEnding_withdrawsTheNudge() {
        val step = DriftPolicy.decide(nudgedInThesis, input(min(90), emptyList(), facts(current = emptyList())))

        assertTrue(step.cancel)
        assertEquals(DriftState.NONE, step.state)
    }

    @Test
    fun outsideABlock_looksAgainWhenTheNextStarts_ifThatIsSoon() {
        val soon = DriftPolicy.decide(DriftState.NONE, input(min(80), emptyList(), facts(current = emptyList(), next = email)))
        val later = DriftPolicy.decide(DriftState.NONE, input(min(60), emptyList(), facts(current = emptyList(), next = email)))

        assertEquals("so Instagram already open at 90 min is caught", min(90), soon.recheckAtMs)
        assertNull("30 min away: the sync before it arms the watch", later.recheckAtMs)
    }

    // ---- held, never dropped ----------------------------------------------------

    @Test
    fun noAnswerFromTheServer_startsNothing_andKeepsTheState() {
        val step = inInstagram(fromSecond = 600, nowSecond = 700, facts = null)

        assertNull(step.post)
        assertEquals(DriftState.NONE, step.state)
    }

    @Test
    fun noAnswerFromTheServer_stillWithdrawsANudgePastItsBlocksEnd() {
        val during = DriftPolicy.decide(nudgedInThesis, input(min(60), emptyList(), facts = null))
        val after = DriftPolicy.decide(nudgedInThesis, input(min(90), emptyList(), facts = null))

        assertFalse(during.cancel)
        assertEquals(min(90), during.recheckAtMs)
        assertTrue(after.cancel)
        assertEquals(DriftState.NONE, after.state)
    }

    @Test
    fun theServersSilence_holdsTheNudge_andLooksAgainInAMinute() {
        val step = inInstagram(fromSecond = 600, nowSecond = 700, facts = facts(silent = true))

        assertNull(step.post)
        assertFalse("held, not used up", step.state.done)
        assertEquals(sec(760), step.recheckAtMs)
    }

    @Test
    fun aCallDndOrCarMode_holdsTheNudge_andItIsSaidOnceOver() {
        val sittings = listOf(open(instagram, sec(600), sec(700)))
        val held = DriftPolicy.decide(DriftState.NONE, input(sec(700), sittings, locallySilent = true))

        assertNull(held.post)
        assertEquals(sec(760), held.recheckAtMs)

        val said = DriftPolicy.decide(held.state, input(sec(760), listOf(open(instagram, sec(600), sec(760)))))
        assertEquals(DriftNudge(thesis, instagram), said.post)
    }

    @Test
    fun anotherNudgesGapOrAPhoneAwayTimer_holdsTheNudgeUntilItEnds() {
        val held = DriftPolicy.decide(
            DriftState.NONE, input(sec(700), listOf(open(instagram, sec(600), sec(700))), heldUntilMs = sec(900))
        )

        assertNull(held.post)
        assertEquals(sec(900), held.recheckAtMs)

        val said = DriftPolicy.decide(
            held.state, input(sec(900), listOf(open(instagram, sec(600), sec(900))), heldUntilMs = null)
        )
        assertEquals(DriftNudge(thesis, instagram), said.post)
    }

    @Test
    fun aHoldThatIsOver_isNoHold() {
        val step = DriftPolicy.decide(
            DriftState.NONE, input(sec(700), listOf(open(instagram, sec(600), sec(700))), heldUntilMs = sec(650))
        )

        assertEquals(DriftNudge(thesis, instagram), step.post)
    }

    @Test
    fun aHeldNudgeWhoseAppWasLeft_isNotSaid() {
        val held = DriftPolicy.decide(
            DriftState.NONE, input(sec(700), listOf(open(instagram, sec(600), sec(700))), heldUntilMs = sec(900))
        )

        val later = DriftPolicy.decide(held.state, input(sec(900), listOf(closed(instagram, sec(600), sec(710)))))

        assertNull(later.post)
    }

    // ---- the buttons ------------------------------------------------------------

    @Test
    fun backToIt_switchPlanAndTheSwipe_endItForTheBlock() {
        val settled = DriftPolicy.settle(nudgedInThesis)

        assertEquals(nudgedInThesis.copy(showing = false), settled)
        assertNull(inInstagram(fromSecond = 1200, nowSecond = 1500, state = settled).post)
    }

    @Test
    fun fiveMoreMinutes_asksOnceMoreFiveMinutesLater_whenStillInADistractionApp() {
        val snoozed = DriftPolicy.fiveMore(nudgedInThesis, sec(630))

        val waiting = inInstagram(fromSecond = 600, nowSecond = 800, state = snoozed)
        assertNull(waiting.post)
        assertEquals(sec(930), waiting.recheckAtMs)

        val again = inInstagram(fromSecond = 600, nowSecond = 930, state = snoozed)
        assertEquals("still scrolling: said at once", DriftNudge(thesis, instagram), again.post)
    }

    @Test
    fun fiveMoreMinutes_withTheAppLeft_waitsForTheNextGrace() {
        val snoozed = DriftPolicy.fiveMore(nudgedInThesis, sec(630))

        val back = DriftPolicy.decide(snoozed, input(sec(1000), listOf(closed(instagram, sec(600), sec(640)), open(reddit, sec(990), sec(1000)))))

        assertNull(back.post)
        assertEquals(sec(1020), back.recheckAtMs)
    }

    @Test
    fun fiveMoreMinutes_canBeAskedAgain() {
        val first = inInstagram(fromSecond = 600, nowSecond = 930, state = DriftPolicy.fiveMore(nudgedInThesis, sec(630)))
        val second = DriftPolicy.fiveMore(first.state, sec(940))

        assertEquals(sec(940) + DriftPolicy.MORE_MS, second.snoozedUntilMs)
        assertFalse(second.done)
    }

    // ---- off --------------------------------------------------------------------

    @Test
    fun turnedOff_endsEverything_andWithdrawsANudgeUp() {
        val step = DriftPolicy.decide(nudgedInThesis, input(min(30), emptyList(), enabled = false))

        assertEquals(DriftState.NONE, step.state)
        assertTrue(step.cancel)
        assertNull(step.recheckAtMs)
    }

    // ---- the watcher ------------------------------------------------------------

    @Test
    fun theWatcher_runsThroughTheBlock() {
        assertEquals(min(90), DriftPolicy.watchUntil(DriftState.NONE, facts(), min(10), enabled = true))
    }

    @Test
    fun theWatcher_reachesIntoANextBlockStartingWithinTwentyMinutes() {
        assertEquals(min(120), DriftPolicy.watchUntil(DriftState.NONE, facts(next = email), min(75), enabled = true))
        assertEquals(min(90), DriftPolicy.watchUntil(DriftState.NONE, facts(next = email), min(60), enabled = true))
        assertEquals(
            "before any block, armed by the sync ahead of it",
            min(120), DriftPolicy.watchUntil(DriftState.NONE, facts(current = emptyList(), next = email), min(80), enabled = true)
        )
    }

    @Test
    fun theWatcher_isLetGoForABlockAlreadySettled() {
        val settled = DriftPolicy.settle(nudgedInThesis)

        assertNull(DriftPolicy.watchUntil(settled, facts(), min(30), enabled = true))
        assertEquals(min(120), DriftPolicy.watchUntil(settled, facts(next = email), min(75), enabled = true))
    }

    @Test
    fun theWatcher_isLetGoWhenOff_silent_orWithNothingPlanned() {
        assertNull(DriftPolicy.watchUntil(DriftState.NONE, facts(), min(10), enabled = false))
        assertNull(DriftPolicy.watchUntil(DriftState.NONE, facts(silent = true), min(10), enabled = true))
        assertNull(DriftPolicy.watchUntil(DriftState.NONE, facts(current = emptyList()), min(10), enabled = true))
        assertNull(DriftPolicy.watchUntil(DriftState.NONE, null, min(10), enabled = true))
    }

    // ---- the usage log ------------------------------------------------------------

    @Test
    fun sittings_areTheDistractionAppsFromTheLog_theOneInFrontOpen() {
        val events = listOf(
            UsageEventRecord(EventKind.RESUMED, instagram, "Main", sec(600)),
            UsageEventRecord(EventKind.PAUSED, instagram, "Main", sec(620)),
            UsageEventRecord(EventKind.RESUMED, whatsapp, "Main", sec(621)),
            UsageEventRecord(EventKind.PAUSED, whatsapp, "Main", sec(640)),
            UsageEventRecord(EventKind.RESUMED, reddit, "Main", sec(641))
        )

        val sittings = DriftPolicy.sittings(events, sec(660), setOf(instagram, reddit))

        assertEquals(
            listOf(closed(instagram, sec(600), sec(620)), open(reddit, sec(641), sec(660))),
            sittings.sortedBy { it.startMs }
        )
    }
}
