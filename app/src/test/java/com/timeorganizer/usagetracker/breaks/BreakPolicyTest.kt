package com.timeorganizer.usagetracker.breaks

import com.timeorganizer.usagetracker.breaks.BreakPolicy.MINUTE_MS
import com.timeorganizer.usagetracker.now.DeskWork
import com.timeorganizer.usagetracker.now.NowBlock
import com.timeorganizer.usagetracker.now.NowFacts
import com.timeorganizer.usagetracker.usagestats.EventKind
import com.timeorganizer.usagetracker.usagestats.UsageEventRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BreakPolicyTest {

    /** The desk's last active minute ended here: the break's start. */
    private val desk = 1_000_000_000_000L

    private fun at(minutes: Int) = desk + minutes * MINUTE_MS

    private val thesis = DeskWork("Thesis", onPlan = true)

    private fun facts(
        deskIdle: Boolean = true,
        deskLastActiveMs: Long? = desk,
        work: DeskWork? = thesis,
        current: List<NowBlock> = emptyList(),
        silent: Boolean = false
    ) = NowFacts(silent, current, deskLastActiveMs, deskIdle, work, emptySet())

    private fun block(id: Long, name: String, fromMinute: Int, toMinute: Int) =
        NowBlock(id, name, at(fromMinute), at(toMinute), inProgress = true)

    /** On the phone since [fromMinute], still on it unless [inUse] is false (then it was put down at [toMinute]). */
    private fun phone(fromMinute: Int, toMinute: Int, inUse: Boolean = true) =
        PhoneUse(inUse, listOf(at(fromMinute) to at(toMinute)))

    private fun input(
        nowMinute: Int,
        facts: NowFacts? = facts(),
        phone: PhoneUse = phone(1, nowMinute),
        locallySilent: Boolean = false,
        enabled: Boolean = true,
        breakMinutes: Int = 10
    ) = BreakInput(at(nowMinute), facts, phone, locallySilent, enabled, breakMinutes)

    private fun open(nudgeAtMinute: Int? = 10, posted: Boolean = false, subject: BreakSubject = deskSubject) =
        BreakState.Open(desk, subject, nudgeAtMinute?.let(::at), posted, desk + BreakPolicy.MAX_BREAK_MS)

    private val deskSubject = BreakSubject("Thesis", BreakSubject.Kind.DESK, null, null)

    // ---- when a break starts ----------------------------------------------

    @Test
    fun theDeskGoesQuietAndThePhoneComesOut_startsABreak_countedFromTheDesksLastMinute() {
        val step = BreakPolicy.decide(BreakState.Idle, input(nowMinute = 4))

        assertEquals(open(nudgeAtMinute = 10), step.state)
        assertEquals(BreakAction.None, step.action)
        assertEquals("looked at again each minute while the phone is in use", at(5), step.recheckAtMs)
    }

    @Test
    fun theThirtyMinuteChoice_nudgesAtThirty() {
        val step = BreakPolicy.decide(BreakState.Idle, input(nowMinute = 4, breakMinutes = 30))

        assertEquals(at(30), (step.state as BreakState.Open).nudgeAtMs)
    }

    @Test
    fun aPhonePickedUpJustBeforeTheDeskStopped_stillStartsTheBreak() {
        val step = BreakPolicy.decide(BreakState.Idle, input(nowMinute = 4, phone = phone(-1, 4)))

        assertTrue(step.state is BreakState.Open)
    }

    @Test
    fun aPhoneInUseLongBeforeTheDeskStopped_countsFromTheBreaksStart() {
        // A sitting already running at the slack's edge is in use from it, not from its own old start.
        assertEquals(desk - BreakPolicy.PICKUP_SLACK_MS, phone(-30, 4).firstInUseSince(desk - BreakPolicy.PICKUP_SLACK_MS))
        assertTrue(BreakPolicy.decide(BreakState.Idle, input(nowMinute = 4, phone = phone(-30, 4))).state is BreakState.Open)
    }

    @Test
    fun theDeskNotIdleYet_isNoBreak_butIsLookedAtAgainInAMinute() {
        // The server calls the desk idle 2–4 minutes after the user stands up; the phone may be out before that.
        val step = BreakPolicy.decide(BreakState.Idle, input(nowMinute = 1, facts = facts(deskIdle = false)))

        assertEquals(BreakState.Idle, step.state)
        assertEquals(at(2), step.recheckAtMs)
    }

    @Test
    fun aPickupOnlyAfterTheBreakWasOver_startsNothing() {
        val step = BreakPolicy.decide(BreakState.Idle, input(nowMinute = 25, phone = phone(20, 25)))

        assertEquals(BreakState.Idle, step.state)
        assertNull("nothing changes before the desk is used again", step.recheckAtMs)
    }

    @Test
    fun noPhoneUseSinceTheDeskStopped_startsNothing() {
        val step = BreakPolicy.decide(BreakState.Idle, input(nowMinute = 4, phone = PhoneUse.NONE))

        assertEquals(BreakState.Idle, step.state)
    }

    @Test
    fun unmappedOrIgnoredDeskTime_withNothingPlanned_isNothingToComeBackTo() {
        val step = BreakPolicy.decide(BreakState.Idle, input(nowMinute = 4, facts = facts(work = null)))

        assertEquals(BreakState.Idle, step.state)
    }

    @Test
    fun aBlockCoveringNow_namesTheBreakWhenTheDeskWasOffPlanOrUnnamed() {
        val email = block(7, "Email", -60, 30)
        for (work in listOf(DeskWork("Video", onPlan = false), null)) {
            val step = BreakPolicy.decide(BreakState.Idle, input(nowMinute = 4, facts = facts(work = work, current = listOf(email))))

            assertEquals(BreakSubject("Email", BreakSubject.Kind.PLANNED, 7, at(30)), (step.state as BreakState.Open).subject)
            assertEquals("a planned break ends with its block", at(30), (step.state as BreakState.Open).expiresAtMs)
        }
    }

    @Test
    fun deskWorkOnThePlan_isNamedAsTheDesksWork_andKeepsTheBlockForDone() {
        val step = BreakPolicy.decide(BreakState.Idle, input(nowMinute = 4, facts = facts(current = listOf(block(7, "Thesis", -60, 30)))))

        assertEquals(BreakSubject("Thesis", BreakSubject.Kind.DESK, 7, at(30)), (step.state as BreakState.Open).subject)
    }

    @Test
    fun aBlockThatBeganAfterTheDeskStopped_isNotABreakFromIt() {
        // Lunch from 12:00, desk quiet since 11:59: the phone at lunch is lunch.
        val lunch = block(9, "Lunch", 1, 60)
        val step = BreakPolicy.decide(BreakState.Idle, input(nowMinute = 4, facts = facts(work = null, current = listOf(lunch))))

        assertEquals(BreakState.Idle, step.state)
    }

    @Test
    fun theServerSaysBeSilent_startsNothing() {
        val step = BreakPolicy.decide(BreakState.Idle, input(nowMinute = 4, facts = facts(silent = true)))

        assertEquals(BreakState.Idle, step.state)
        assertNull(step.recheckAtMs)
    }

    @Test
    fun noAnswerFromTheServer_startsNothing() {
        val step = BreakPolicy.decide(BreakState.Idle, input(nowMinute = 4, facts = null))

        assertEquals(BreakState.Idle, step.state)
        assertEquals("asked again while the phone is in use", at(5), step.recheckAtMs)
    }

    @Test
    fun aDeskQuietForHours_isNotABreak() {
        val step = BreakPolicy.decide(BreakState.Idle, input(nowMinute = 125, phone = phone(1, 125)))

        assertEquals(BreakState.Idle, step.state)
    }

    // ---- the nudge -----------------------------------------------------------

    @Test
    fun atTheBreaksLength_whileOnThePhone_nudgesOnce() {
        val step = BreakPolicy.decide(open(), input(nowMinute = 10))

        assertEquals(BreakAction.Post(deskSubject, 10 * MINUTE_MS), step.action)
        assertEquals(open(nudgeAtMinute = null, posted = true), step.state)

        val later = BreakPolicy.decide(step.state, input(nowMinute = 20))
        assertEquals("once", BreakAction.None, later.action)
        assertEquals("still polled, so the nudge comes down when the desk is used", at(21), later.recheckAtMs)
    }

    @Test
    fun beforeTheBreaksLength_waitsForIt() {
        val step = BreakPolicy.decide(open(), input(nowMinute = 9, phone = phone(1, 9)))

        assertEquals(BreakAction.None, step.action)
        assertEquals(at(10), step.recheckAtMs)
    }

    @Test
    fun aPhonePutDown_hearsTheNudgeAtItsNextPickup_withTheRealLength() {
        val down = BreakPolicy.decide(open(), input(nowMinute = 12, phone = phone(1, 8, inUse = false)))
        assertEquals(BreakAction.None, down.action)
        assertNull("the pickup wakes it, not a poll", down.recheckAtMs)

        val pickedUp = BreakPolicy.decide(down.state, input(nowMinute = 25, phone = phone(25, 25)))
        assertEquals(BreakAction.Post(deskSubject, 25 * MINUTE_MS), pickedUp.action)
    }

    @Test
    fun silenced_waits_andNudgesOnceQuiet() {
        for (silenced in listOf(input(nowMinute = 10, locallySilent = true), input(nowMinute = 10, facts = facts(silent = true)))) {
            val step = BreakPolicy.decide(open(), silenced)
            assertEquals(BreakAction.None, step.action)
            assertEquals(open(), step.state)
            assertEquals(at(11), step.recheckAtMs)
        }

        assertTrue(BreakPolicy.decide(open(), input(nowMinute = 11)).action is BreakAction.Post)
    }

    @Test
    fun withoutAnAnswer_noNudge_andTheBreakStands() {
        val step = BreakPolicy.decide(open(), input(nowMinute = 10, facts = null))

        assertEquals(BreakAction.None, step.action)
        assertEquals(open(), step.state)
    }

    // ---- the end ---------------------------------------------------------------

    @Test
    fun theDeskUsedAgain_endsTheBreak_andTakesTheNudgeDown() {
        val back = facts(deskIdle = false, deskLastActiveMs = at(14))

        val posted = BreakPolicy.decide(open(nudgeAtMinute = null, posted = true), input(nowMinute = 15, facts = back))
        assertEquals(BreakStep(BreakState.Idle, BreakAction.Cancel, null), posted)

        val notPosted = BreakPolicy.decide(open(), input(nowMinute = 8, facts = back))
        assertEquals(BreakStep(BreakState.Idle, BreakAction.None, null), notPosted)
    }

    @Test
    fun aPlannedBreak_endsWithItsBlock() {
        val subject = BreakSubject("Email", BreakSubject.Kind.PLANNED, 7, at(30))
        val state = BreakState.Open(desk, subject, null, posted = true, expiresAtMs = at(30))

        assertEquals(BreakStep(BreakState.Idle, BreakAction.Cancel, null), BreakPolicy.decide(state, input(nowMinute = 30)))
    }

    @Test
    fun aDeskBreak_lapsesAfterTwoHours() {
        assertEquals(BreakState.Idle, BreakPolicy.decide(open(nudgeAtMinute = null), input(nowMinute = 120)).state)
    }

    // ---- the buttons -----------------------------------------------------------

    @Test
    fun fiveMore_nudgesOnceMore_fiveMinutesOn() {
        val snoozed = BreakPolicy.fiveMore(open(nudgeAtMinute = null, posted = true), at(12))
        assertEquals(open(nudgeAtMinute = 17), snoozed)

        assertEquals(BreakAction.None, BreakPolicy.decide(snoozed, input(nowMinute = 16)).action)
        assertEquals(BreakAction.Post(deskSubject, 17 * MINUTE_MS), BreakPolicy.decide(snoozed, input(nowMinute = 17)).action)
    }

    @Test
    fun backToIt_andASwipe_endTheNudging_butTheBreakStillEndsAtTheDesk() {
        for (answered in listOf(BreakPolicy.backToIt(open(null, posted = true)), BreakPolicy.dismissed(open(null, posted = true)))) {
            assertEquals(open(nudgeAtMinute = null, posted = false), answered)
            assertEquals(BreakAction.None, BreakPolicy.decide(answered, input(nowMinute = 40)).action)

            val back = BreakPolicy.decide(answered, input(nowMinute = 41, facts = facts(deskIdle = false, deskLastActiveMs = at(41))))
            assertEquals(BreakStep(BreakState.Idle, BreakAction.None, null), back)
        }
    }

    @Test
    fun doneWithABlock_isSilentThroughIt_untilAnotherStarts() {
        val thesisBlock = block(7, "Thesis", -60, 30)
        val subject = BreakSubject("Thesis", BreakSubject.Kind.DESK, 7, at(30))
        val done = BreakPolicy.done(BreakState.Open(desk, subject, null, true, desk + BreakPolicy.MAX_BREAK_MS), at(12))
        assertEquals(BreakState.Done(7, at(12)), done)

        // The desk used again and quiet again inside the same block: still done.
        val sameBlock = facts(deskLastActiveMs = at(20), current = listOf(thesisBlock))
        val stillDone = BreakPolicy.decide(done, input(nowMinute = 24, facts = sameBlock, phone = phone(22, 24)))
        assertEquals(done, stillDone.state)
        assertEquals(BreakAction.None, stillDone.action)

        // The next block has started and a new break begins in it.
        val next = facts(deskLastActiveMs = at(35), work = null, current = listOf(block(8, "Email", 31, 90)))
        val released = BreakPolicy.decide(done, input(nowMinute = 38, facts = next, phone = phone(36, 38)))
        assertEquals(BreakSubject("Email", BreakSubject.Kind.PLANNED, 8, at(90)), (released.state as BreakState.Open).subject)
    }

    @Test
    fun aReleaseFromDone_doesNotReopenOnAPickupFromBeforeIt() {
        val done = BreakState.Done(7, at(12))
        val next = facts(work = null, current = listOf(block(8, "Email", -60, 90)))

        // The desk has not moved since before "done"; the only phone use is the one "done" answered.
        val step = BreakPolicy.decide(done, input(nowMinute = 40, facts = next, phone = phone(1, 11, inUse = false)))

        assertEquals(BreakState.Idle, step.state)
    }

    @Test
    fun doneWithNoBlock_lastsUntilTheDeskIsUsedAgain() {
        val done = BreakPolicy.done(open(nudgeAtMinute = null, posted = true), at(12))
        assertEquals(BreakState.Done(null, at(12)), done)

        assertEquals(done, BreakPolicy.decide(done, input(nowMinute = 30)).state)

        val back = facts(deskLastActiveMs = at(45), deskIdle = false)
        assertEquals(BreakState.Idle, BreakPolicy.decide(done, input(nowMinute = 46, facts = back)).state)
    }

    @Test
    fun switchedOff_endsEverything_andTakesANudgeDown() {
        assertEquals(
            BreakStep(BreakState.Idle, BreakAction.Cancel, null),
            BreakPolicy.decide(open(null, posted = true), input(nowMinute = 12, enabled = false))
        )
        assertEquals(
            BreakStep(BreakState.Idle, BreakAction.None, null),
            BreakPolicy.decide(BreakState.Done(null, at(1)), input(nowMinute = 12, enabled = false))
        )
    }

    // ---- the watcher -----------------------------------------------------------

    @Test
    fun theWatcher_runsThroughABlock_afterRecentDeskUse_andWhileABreakIsOpen() {
        val now = at(5)
        assertEquals(at(90), BreakPolicy.watchUntil(BreakState.Idle, facts(current = listOf(block(7, "Thesis", -60, 90))), now, true))
        assertEquals("30 minutes from recent desk use", now + BreakPolicy.WATCH_FOR_MS, BreakPolicy.watchUntil(BreakState.Idle, facts(), now, true))
        assertNull("the desk long quiet, nothing planned", BreakPolicy.watchUntil(BreakState.Idle, facts(), at(20), true))
        assertEquals(at(50), BreakPolicy.watchUntil(open(), facts(), at(20), true))
        assertNull("silent", BreakPolicy.watchUntil(BreakState.Idle, facts(silent = true), now, true))
        assertNull("done", BreakPolicy.watchUntil(BreakState.Done(null, now), facts(), now, true))
        assertNull("off", BreakPolicy.watchUntil(open(), facts(), now, false))
    }

    // ---- reading the phone's use from the log ------------------------------------

    @Test
    fun phoneUse_fromTheLog_isTheSessionsRules_withTheOpenSittingInUse() {
        fun event(kind: EventKind, packageName: String, minute: Int) = UsageEventRecord(kind, packageName, "$packageName.Main", at(minute))

        val use = PhoneUse.from(
            listOf(
                event(EventKind.RESUMED, "com.reddit", 1),
                event(EventKind.PAUSED, "com.reddit", 3),
                event(EventKind.CLOSE_ALL, "android", 3),
                event(EventKind.RESUMED, "com.instagram", 6)
            ),
            nowMs = at(8)
        )

        assertTrue(use.inUse)
        assertEquals(listOf(at(1) to at(3), at(6) to at(8)), use.sittings)
        assertEquals(at(6), use.firstInUseSince(at(4)))
        assertEquals(at(2), use.firstInUseSince(at(2)))
        assertNull(use.firstInUseSince(at(9)))
    }
}
