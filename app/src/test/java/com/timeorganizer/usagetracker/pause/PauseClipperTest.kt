package com.timeorganizer.usagetracker.pause

import com.timeorganizer.usagetracker.budget.BudgetSitting
import com.timeorganizer.usagetracker.usagestats.BuildResult
import com.timeorganizer.usagetracker.usagestats.CountedPickup
import com.timeorganizer.usagetracker.usagestats.Derailment
import com.timeorganizer.usagetracker.usagestats.DerailmentEnd
import com.timeorganizer.usagetracker.usagestats.DerailmentRead
import com.timeorganizer.usagetracker.usagestats.Interruption
import com.timeorganizer.usagetracker.usagestats.PickupCount
import com.timeorganizer.usagetracker.usagestats.RawSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a pause takes out of a read (prompt 17): sessions clipped at its edges, everything else that touches it dropped
 * whole, the cursors never moved. Minutes from an arbitrary zero; the pause is 10:00–11:00 unless a test says otherwise.
 */
class PauseClipperTest {

    private val pause = PauseRange(min(600), min(660))

    private fun min(minutes: Int) = minutes * 60_000L

    private fun session(from: Int, to: Int, pkg: String = "com.reddit") = RawSession(pkg, min(from), min(to))

    private fun clip(vararg sessions: RawSession, pauses: List<PauseRange> = listOf(pause), resumeFromMs: Long = min(700)) =
        PauseClipper.sessions(BuildResult(sessions.toList(), resumeFromMs), pauses)

    // ---- sessions -----------------------------------------------------------------------------------------------

    @Test
    fun aSessionInsideThePause_isDropped() {
        assertEquals(emptyList<RawSession>(), clip(session(610, 640)).sessions)
    }

    @Test
    fun aSessionRunningIntoThePause_keepsThePartBefore() {
        assertEquals(listOf(session(590, 600)), clip(session(590, 620)).sessions)
    }

    @Test
    fun aSessionRunningOutOfThePause_startsAtItsEnd() {
        assertEquals(listOf(session(660, 675)), clip(session(650, 675)).sessions)
    }

    /** One sitting across the whole pause: the time before and after are both real, the hour between is nobody's business. */
    @Test
    fun aSessionSpanningThePause_becomesTwo() {
        assertEquals(listOf(session(580, 600), session(660, 690)), clip(session(580, 690)).sessions)
    }

    @Test
    fun theEdgesAreExact_endingAtTheStartOrStartingAtTheEndIsUntouched() {
        val before = session(590, 600)
        val after = session(660, 670)

        assertEquals(listOf(before, after), clip(before, after).sessions)
    }

    @Test
    fun sessionsOutsideEveryPause_andNoPauses_areLeftAlone() {
        val built = BuildResult(listOf(session(500, 520)), min(700))

        assertSame("no pause, nothing to copy", built, PauseClipper.sessions(built, emptyList()))
        assertEquals(built, PauseClipper.sessions(built, listOf(pause)))
    }

    /** The pause clips; the build decided where the next read starts, and pausing never builds a backlog. */
    @Test
    fun theCursorIsWhereTheBuildPutIt() {
        assertEquals(min(700), clip(session(610, 640), resumeFromMs = min(700)).resumeFromMs)
        assertEquals(min(620), clip(session(610, 640), resumeFromMs = min(620)).resumeFromMs)
    }

    /** A session still open at a read is read again whole next time; it must clip the same way, or its start (the dedup key) moves. */
    @Test
    fun readingAgain_clipsTheSameWay() {
        val first = clip(session(580, 690)).sessions
        val again = clip(session(580, 690)).sessions

        assertEquals(first, again)
    }

    @Test
    fun twoPauses_cutOneSessionThreeWays() {
        val pauses = listOf(PauseRange(min(620), min(630)), PauseRange(min(600), min(610)))

        assertEquals(
            listOf(session(590, 600), session(610, 620), session(630, 640)),
            clip(session(590, 640), pauses = pauses).sessions
        )
    }

    /** "Until I resume" before Resume: everything from its start on is out, however late the read. */
    @Test
    fun aPauseUntilResumed_coversEverythingAfterItsStart() {
        val open = PauseRange(min(600), PauseRange.UNTIL_RESUMED)

        assertEquals(listOf(session(590, 600)), clip(session(590, 9_000), pauses = listOf(open)).sessions)
    }

    /** Resume writes the real end; a session read after it is clipped there, the time after Resume is recorded again. */
    @Test
    fun afterResume_theTimeAfterItIsRecordedAgain() {
        val resumed = PauseRange(min(600), min(615))

        assertEquals(listOf(session(590, 600), session(615, 630)), clip(session(590, 630), pauses = listOf(resumed)).sessions)
    }

    // ---- pickups, interruptions, derailments --------------------------------------------------------------------

    @Test
    fun aPickupTouchingThePause_isDroppedWhole() {
        val count = PickupCount(
            listOf(
                CountedPickup(min(590), min(595), unlocked = true),  // before
                CountedPickup(min(598), min(602), unlocked = true),  // across the start
                CountedPickup(min(630), min(631), unlocked = false), // inside
                CountedPickup(min(659), min(661), unlocked = true),  // across the end
                CountedPickup(min(660), min(662), unlocked = true)   // starts as it ends
            ),
            resumeFromMs = min(700),
            sawUnlock = true
        )

        val kept = PauseClipper.pickups(count, listOf(pause))

        assertEquals(listOf(min(590), min(660)), kept.closed.map { it.startMs })
        assertEquals("the pickup cursor stays", min(700), kept.resumeFromMs)
        assertTrue(kept.sawUnlock)
    }

    @Test
    fun anInterruptionInsideThePause_isDropped_theOnesAroundItKept() {
        val read = DerailmentRead(
            listOf(Interruption("com.whatsapp", min(599)), Interruption("com.whatsapp", min(600)), Interruption("com.slack", min(660))),
            emptyList(),
            resumeFromMs = min(700)
        )

        val kept = PauseClipper.derailments(read, listOf(pause))

        assertEquals(listOf(min(599), min(660)), kept.interruptions.map { it.atMs })
        assertEquals(min(700), kept.resumeFromMs)
    }

    /** From its notification to its end: a derailment that ran into the pause can't be cut short with an end the server knows. */
    @Test
    fun aDerailmentTouchingThePause_isDroppedWhole() {
        fun derailment(notified: Int, end: Int) =
            Derailment("com.whatsapp", min(notified), min(notified) + 30_000, min(end), DerailmentEnd.RETURNED)
        val read = DerailmentRead(
            emptyList(),
            listOf(derailment(580, 590), derailment(590, 610), derailment(655, 670), derailment(661, 680)),
            resumeFromMs = min(700)
        )

        val kept = PauseClipper.derailments(read, listOf(pause))

        assertEquals(listOf(min(580), min(661)), kept.derailments.map { it.notifiedMs })
    }

    // ---- the daily limits' estimate and the loop store ---------------------------------------------------------

    @Test
    fun budgetSittings_leaveThePausedTimeOut_andOnlyTheLastPieceStaysOpen() {
        val sittings = listOf(
            BudgetSitting("com.youtube", min(580), min(690), open = true),
            BudgetSitting("com.youtube", min(500), min(510), open = false)
        )

        val kept = PauseClipper.sittings(sittings, listOf(pause))

        assertEquals(
            listOf(
                BudgetSitting("com.youtube", min(580), min(600), open = false),
                BudgetSitting("com.youtube", min(660), min(690), open = true),
                BudgetSitting("com.youtube", min(500), min(510), open = false)
            ),
            kept
        )
    }

    /** In front through the whole pause and still: the phone counts none of it, so no limit is said about it. */
    @Test
    fun aBudgetSittingInsideAPauseStillGoing_isNoSittingAtAll() {
        val open = PauseRange(min(600), PauseRange.UNTIL_RESUMED)

        assertEquals(emptyList<BudgetSitting>(), PauseClipper.sittings(listOf(BudgetSitting("com.youtube", min(610), min(640), true)), listOf(open)))
    }

    @Test
    fun aLoopTouchingAPause_isTouched_oneBeforeItIsNot() {
        assertTrue(PauseClipper.touches(min(595), min(605), listOf(pause)))
        assertTrue("a loop whose last hold is the pause's first minute", PauseClipper.touches(min(590), min(600), listOf(pause)))
        assertFalse(PauseClipper.touches(min(580), min(599), listOf(pause)))
        assertFalse(PauseClipper.touches(min(595), min(605), emptyList()))
    }
}
