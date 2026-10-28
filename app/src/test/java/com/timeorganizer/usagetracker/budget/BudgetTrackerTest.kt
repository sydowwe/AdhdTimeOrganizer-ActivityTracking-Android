package com.timeorganizer.usagetracker.budget

import com.timeorganizer.usagetracker.data.api.dto.BudgetDto
import com.timeorganizer.usagetracker.data.api.dto.BudgetsResponse
import com.timeorganizer.usagetracker.usagestats.EventKind
import com.timeorganizer.usagetracker.usagestats.UsageEventRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BudgetTrackerTest {

    private val minute = 60_000L

    /** The user's midnight. Every instant below is minutes after it. */
    private val dayStart = 1_000_000_000_000L
    private val dayEnd = dayStart + 24 * 60 * minute

    private fun at(minutes: Int) = dayStart + minutes * minute

    private val youtube = "com.google.youtube"
    private val reddit = "com.reddit.frontpage"
    private val instagram = "com.instagram.android"

    private fun budget(
        id: Long = 1,
        limitMinutes: Int = 60,
        warn: Boolean = true,
        warnedToday: Boolean = false,
        reachedToday: Boolean = false,
        serverMinutes: Int = 0,
        syncedMinutes: Int = 0,
        packages: Set<String> = setOf(youtube)
    ) = PhoneBudget(id, "YouTube", limitMinutes * minute, warn, warnedToday, reachedToday, serverMinutes * minute, syncedMinutes * minute, packages)

    private fun facts(vararg budgets: PhoneBudget) = BudgetFacts("2030-03-12", dayStart, dayEnd, budgets.toList(), fetchedAtMs = dayStart)

    /** In [packageName] from [fromMinute]; still in front unless [toMinute] is given. */
    private fun sitting(packageName: String, fromMinute: Int, toMinute: Int? = null, nowMinute: Int = 0) =
        BudgetSitting(packageName, at(fromMinute), at(toMinute ?: nowMinute), open = toMinute == null)

    private fun decide(
        nowMinute: Int,
        facts: BudgetFacts?,
        vararg sittings: BudgetSitting,
        state: BudgetState = BudgetState.NONE,
        locallySilent: Boolean = false,
        serverSilent: Boolean = false,
        heldUntilMs: Long? = null
    ) = BudgetTracker.decide(state, BudgetInput(at(nowMinute), facts, sittings.toList(), locallySilent, serverSilent, heldUntilMs))

    // ---- the estimate ---------------------------------------------------------

    /** The server's total with this phone's synced part swapped for the phone's own count: right whenever the sync ran. */
    @Test
    fun theEstimate_replacesThisPhonesSyncedPart_withItsOwnCount() {
        // 40 min on the server, 30 of them from this phone; the phone's log has those 30 and 10 more not yet synced.
        val b = budget(serverMinutes = 40, syncedMinutes = 30)
        val sittings = listOf(sitting(youtube, 600, 630), sitting(youtube, 700, nowMinute = 710))

        assertEquals(50 * minute, BudgetTracker.estimateMs(b, sittings, dayStart, at(710)))
    }

    @Test
    fun splitScreenWithTwoAppsOfOneBudget_countsTheOverlapOnce() {
        val b = budget(packages = setOf(reddit, instagram))
        val sittings = listOf(sitting(reddit, 100, 130), sitting(instagram, 120, 140))

        assertEquals(40 * minute, BudgetTracker.phoneMs(b, sittings, dayStart, at(200)))
    }

    @Test
    fun aSittingFromBeforeMidnight_countsOnlyItsPartToday() {
        val b = budget()

        assertEquals(10 * minute, BudgetTracker.phoneMs(b, listOf(sitting(youtube, -20, 10)), dayStart, at(100)))
    }

    @Test
    fun anotherBudgetsApp_isNotCounted() {
        assertEquals(0L, BudgetTracker.phoneMs(budget(), listOf(sitting(reddit, 0, 30)), dayStart, at(100)))
    }

    /** A server total below this phone's synced part (a row History dropped) never makes the estimate negative. */
    @Test
    fun theServerShortOfThisPhonesPart_countsFromZero() {
        assertEquals(5 * minute, BudgetTracker.estimateMs(budget(serverMinutes = 10, syncedMinutes = 12), listOf(sitting(youtube, 0, 5)), dayStart, at(6)))
    }

    // ---- the stages -----------------------------------------------------------

    @Test
    fun eightyPercent_inFront_isDue_andTheLimitIsTheNextLook() {
        val step = decide(48, facts(budget()), sitting(youtube, 0, nowMinute = 48))

        assertEquals(listOf(BudgetDue(1, BudgetStage.APPROACHING, "YouTube", 60 * minute, 48 * minute, "2030-03-12")), step.due)
        assertTrue(step.state.isSettled(1, BudgetStage.APPROACHING))
        assertEquals(at(60), step.recheckAtMs)
    }

    @Test
    fun beforeEightyPercent_nothing_butWhenItFallsDue() {
        val step = decide(30, facts(budget(serverMinutes = 10)), sitting(youtube, 0, nowMinute = 30))

        assertTrue(step.due.isEmpty())
        assertEquals(at(38), step.recheckAtMs) // 40 of 48 counted: 8 more minutes in front
    }

    @Test
    fun theLimit_inFront_isDue_andSettlesBothStages() {
        val step = decide(61, facts(budget()), sitting(youtube, 0, nowMinute = 61))

        assertEquals(BudgetStage.REACHED, step.due.single().stage)
        assertTrue(step.state.isSettled(1, BudgetStage.APPROACHING))
        assertTrue(step.state.isSettled(1, BudgetStage.REACHED))
        assertNull("nothing left to say today", step.recheckAtMs)
        assertFalse("every budget reached: the watcher can go", step.watch)
    }

    /** A sync can bring the total past the limit in one go: the warning is then noise, as on the server. */
    @Test
    fun pastTheLimitWithTheWarningUnsaid_saysOnlyTheLimit() {
        val step = decide(5, facts(budget(serverMinutes = 70)), sitting(youtube, 0, nowMinute = 5))

        assertEquals(listOf(BudgetStage.REACHED), step.due.map { it.stage })
    }

    @Test
    fun withTheWarningOff_eightyPercentSaysNothing() {
        val step = decide(50, facts(budget(warn = false)), sitting(youtube, 0, nowMinute = 50))

        assertTrue(step.due.isEmpty())
        assertEquals(at(60), step.recheckAtMs)
    }

    /** Decision 13-1: a stage the server says was said (its own push, another phone) is never said here. */
    @Test
    fun aStageTheServerSaysWasSaid_isNotSaidAgain() {
        assertTrue(decide(50, facts(budget(warnedToday = true)), sitting(youtube, 0, nowMinute = 50)).due.isEmpty())
        assertTrue(decide(70, facts(budget(reachedToday = true)), sitting(youtube, 0, nowMinute = 70)).due.isEmpty())
    }

    @Test
    fun aStageSettledHere_isNotSaidTwice() {
        val first = decide(48, facts(budget()), sitting(youtube, 0, nowMinute = 48))
        val again = decide(49, facts(budget()), sitting(youtube, 0, nowMinute = 49), state = first.state)

        assertTrue(again.due.isEmpty())
    }

    @Test
    fun aNewDay_forgetsYesterdaysSettledStages() {
        val yesterday = BudgetState("2030-03-11", setOf(BudgetState.key(1, BudgetStage.REACHED)))
        val step = decide(61, facts(budget()), sitting(youtube, 0, nowMinute = 61), state = yesterday)

        assertEquals(BudgetStage.REACHED, step.due.single().stage)
        assertEquals("2030-03-12", step.state.day)
    }

    /** Decision 13-1 again: desk time reaching the limit is the server's to say, in the browser. */
    @Test
    fun noAppOfTheBudgetInFront_saysNothing_evenPastTheLimit() {
        val step = decide(30, facts(budget(serverMinutes = 70)), sitting(youtube, 0, 10), sitting(reddit, 20, nowMinute = 30))

        assertTrue(step.due.isEmpty())
        assertNull(step.recheckAtMs)
        assertTrue("the limit is still unsaid: keep watching for the app", step.watch)
    }

    /** An activity's budget and its category's crossing together are both said, as on the server. */
    @Test
    fun twoBudgetsCrossingTogether_areBothDue() {
        val activity = budget(id = 1, limitMinutes = 30, warn = false)
        val category = budget(id = 2, limitMinutes = 30, warn = false, packages = setOf(youtube, reddit))
        val step = decide(31, facts(activity, category), sitting(youtube, 0, nowMinute = 31))

        assertEquals(listOf(1L, 2L), step.due.map { it.budgetId })
    }

    // ---- holds ------------------------------------------------------------------

    @Test
    fun aCallOrTheServersSilence_holdsTheStage_andLooksAgainInAMinute() {
        for (step in listOf(
            decide(61, facts(budget()), sitting(youtube, 0, nowMinute = 61), locallySilent = true),
            decide(61, facts(budget()), sitting(youtube, 0, nowMinute = 61), serverSilent = true)
        )) {
            assertTrue(step.due.isEmpty())
            assertFalse("held, not dropped", step.state.isSettled(1, BudgetStage.REACHED))
            assertEquals(at(62), step.recheckAtMs)
        }
    }

    @Test
    fun anotherNudgeInTheGap_holdsTheStageUntilTheGapEnds() {
        val step = decide(61, facts(budget()), sitting(youtube, 0, nowMinute = 61), heldUntilMs = at(66))

        assertTrue(step.due.isEmpty())
        assertEquals(at(66), step.recheckAtMs)

        val after = decide(66, facts(budget()), sitting(youtube, 0, nowMinute = 66), state = step.state, heldUntilMs = at(66))
        assertEquals(BudgetStage.REACHED, after.due.single().stage)
    }

    // ---- the figures ------------------------------------------------------------

    @Test
    fun noFigures_saysNothing_andNeedsNoWatch() {
        val step = decide(61, null, sitting(youtube, 0, nowMinute = 61))

        assertTrue(step.due.isEmpty())
        assertFalse(step.watch)
    }

    @Test
    fun yesterdaysFigures_sayNothing_untilTodaysAreRead() {
        val stale = BudgetFacts("2030-03-11", dayStart - 24 * 60 * minute, dayStart, listOf(budget(serverMinutes = 70)), dayStart - minute)
        val step = BudgetTracker.decide(BudgetState.NONE, BudgetInput(at(5), stale, listOf(sitting(youtube, 0, nowMinute = 5)), false, false, null))

        assertTrue(step.due.isEmpty())
        assertTrue(step.watch)
    }

    @Test
    fun theServersAnswer_readsIntoFacts_andDropsBudgetsNoAppCountsToward() {
        val response = BudgetsResponse(
            day = "2030-03-12",
            dayStartsAt = "2030-03-11T23:00:00Z",
            dayEndsAt = "2030-03-12T23:00:00Z",
            budgets = listOf(
                BudgetDto(5, 31, null, "Scrolling", 3600, true, false, false, 600, 300, listOf(reddit)),
                BudgetDto(6, null, 8, "Social", 5400, false, false, false, 0, 0, emptyList()),
                BudgetDto(7, 32, null, null, 1800, true, false, false, 0, 0, null)
            )
        )

        val read = BudgetFacts.from(response, fetchedAtMs = 42L)!!

        assertEquals(listOf(5L), read.budgets.map { it.budgetId })
        assertEquals(setOf(reddit), read.packages)
        assertEquals(300_000L, read.budgets.single().thisPhoneSyncedMs)
        assertNull("an unreadable day is no figures", BudgetFacts.from(response.copy(dayEndsAt = null), 42L))
    }

    @Test
    fun eightyPercent_isTheServersRule_roundedUp() {
        // total × 5 ≥ limit × 4, so the first whole millisecond at or past 80 %: 5.6 ms is 6.
        assertEquals(6L, BudgetTracker.warnAtMs(budget().copy(limitMs = 7L)))
        assertEquals(48_800L, BudgetTracker.warnAtMs(budget().copy(limitMs = 61_000L)))
        assertEquals(48 * minute, BudgetTracker.warnAtMs(budget()))
    }

    // ---- the sittings, from the log ---------------------------------------------

    @Test
    fun sittings_comeFromTheUsageLog_shortClosedOnesLeftOutLikeTheSync() {
        val events = listOf(
            resumed(youtube, at(10)), paused(youtube, at(10) + 3_000), // 3 s: never synced, not counted
            resumed(youtube, at(20)), paused(youtube, at(30)),
            resumed(reddit, at(31)), paused(reddit, at(40)),
            resumed(youtube, at(41))
        )

        val sittings = BudgetTracker.sittings(events, at(45), setOf(youtube), dayStart)

        assertEquals(
            listOf(BudgetSitting(youtube, at(20), at(30), open = false), BudgetSitting(youtube, at(41), at(45), open = true)),
            sittings
        )
    }

    // ---- the claim's answer -----------------------------------------------------

    @Test
    fun theClaimsAnswer_decidesWhoSaysIt() {
        assertEquals(ClaimOutcome.CLAIMED, ClaimOutcome.of(200, true))
        assertEquals(ClaimOutcome.SAID, ClaimOutcome.of(200, false))
        assertEquals(ClaimOutcome.SAID, ClaimOutcome.of(200, null))
        assertEquals(ClaimOutcome.GONE, ClaimOutcome.of(404, null))
        assertEquals(ClaimOutcome.UNREACHABLE, ClaimOutcome.of(503, null))
        assertEquals(ClaimOutcome.UNREACHABLE, ClaimOutcome.of(401, null))
        assertTrue("offline the phone says it anyway", ClaimOutcome.UNREACHABLE.post)
        assertFalse(ClaimOutcome.SAID.post)
    }

    private fun resumed(packageName: String, atMs: Long) = UsageEventRecord(EventKind.RESUMED, packageName, "Main", atMs)
    private fun paused(packageName: String, atMs: Long) = UsageEventRecord(EventKind.PAUSED, packageName, "Main", atMs)
}
