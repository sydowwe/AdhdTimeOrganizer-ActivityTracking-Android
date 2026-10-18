package com.timeorganizer.usagetracker.notify

import com.timeorganizer.usagetracker.auth.SignOutReason
import com.timeorganizer.usagetracker.notify.SyncBlockedDecider.REPEAT_AFTER_MS
import com.timeorganizer.usagetracker.notify.SyncBlockedDecider.WAITING_THRESHOLD_MS
import com.timeorganizer.usagetracker.sync.SyncState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncBlockedDeciderTest {

    private val hour = 60 * 60 * 1000L
    private val start = 1_000 * hour

    private val healthy = SyncFacts(
        state = SyncState.UP_TO_DATE,
        hasUsageAccess = true,
        signOutReason = null,
        pending = 0,
        lastSentAtMs = start - hour,
        canPost = true
    )
    private val waiting = healthy.copy(state = SyncState.WAITING, pending = 12)

    private fun post(condition: BlockedCondition) = NoticeAction.Post(condition)

    /** Feeds the same facts to a run every 15 minutes from [fromMs] to [toMs] and returns every action taken. */
    private fun runs(facts: SyncFacts, fromMs: Long, toMs: Long, initial: BlockedNoticeState = BlockedNoticeState()):
        Pair<List<NoticeAction>, BlockedNoticeState> {
        var state = initial
        val actions = mutableListOf<NoticeAction>()
        var now = fromMs
        while (now <= toMs) {
            val decision = SyncBlockedDecider.decide(facts, state, now)
            actions += decision.action
            state = decision.next
            now += hour / 4
        }
        return actions to state
    }

    @Test
    fun aHealthySync_neverNotifies() {
        val (actions, _) = runs(healthy, start, start + 72 * hour)
        assertEquals(setOf(NoticeAction.None), actions.toSet())
    }

    @Test
    fun lostUsageAccess_postsOnce_thenOncePerDayWhileItLasts() {
        val facts = healthy.copy(hasUsageAccess = false, state = SyncState.NO_USAGE_ACCESS)
        val (actions, state) = runs(facts, start, start + 50 * hour)

        assertEquals(post(BlockedCondition.NO_USAGE_ACCESS), actions.first())
        assertEquals(3, actions.count { it is NoticeAction.Post })
        assertEquals(BlockedCondition.NO_USAGE_ACCESS, state.shown)
    }

    @Test
    fun noUsageAccess_winsOverSignedOutAndWaiting() {
        // SyncEngine reports SIGNED_OUT or WAITING over NO_USAGE_ACCESS; the decider looks at the access itself.
        for (state in listOf(SyncState.SIGNED_OUT, SyncState.WAITING)) {
            val facts = healthy.copy(state = state, hasUsageAccess = false, signOutReason = SignOutReason.SESSION_ENDED, pending = 5)
            assertEquals("$state", post(BlockedCondition.NO_USAGE_ACCESS), SyncBlockedDecider.decide(facts, BlockedNoticeState(), start).action)
        }
    }

    @Test
    fun aSignOut_isNamedByWhoEndedIt() {
        val signedOut = healthy.copy(state = SyncState.SIGNED_OUT)

        assertEquals(
            post(BlockedCondition.SESSION_ENDED),
            SyncBlockedDecider.decide(signedOut.copy(signOutReason = SignOutReason.SESSION_ENDED), BlockedNoticeState(), start).action
        )
        assertEquals(
            post(BlockedCondition.SIGNED_OUT),
            SyncBlockedDecider.decide(signedOut.copy(signOutReason = SignOutReason.USER), BlockedNoticeState(), start).action
        )
    }

    @Test
    fun aPhoneNeverSignedIn_isSettingUp_notBlocked() {
        val facts = healthy.copy(state = SyncState.SIGNED_OUT, signOutReason = null)
        assertEquals(NoticeAction.None, SyncBlockedDecider.decide(facts, BlockedNoticeState(), start).action)
    }

    @Test
    fun waiting_notifiesOnlyAfterADayWithSessionsPending() {
        val (actions, _) = runs(waiting, start, start + WAITING_THRESHOLD_MS)

        assertEquals(post(BlockedCondition.WAITING), actions.last())
        assertEquals(1, actions.count { it is NoticeAction.Post })
    }

    @Test
    fun waitingJustUnderADay_doesNotNotify() {
        val (actions, _) = runs(waiting, start, start + WAITING_THRESHOLD_MS - hour / 4)
        assertEquals(setOf(NoticeAction.None), actions.toSet())
    }

    @Test
    fun waitingWithNothingPending_isNotBlocked() {
        val (actions, state) = runs(waiting.copy(pending = 0), start, start + 48 * hour)
        assertEquals(setOf(NoticeAction.None), actions.toSet())
        assertNull(state.waitingSinceMs)
    }

    @Test
    fun aRunThatSentSomething_restartsTheWaitingClock() {
        val (_, afterTwentyHours) = runs(waiting, start, start + 20 * hour)

        // Some batches went through 20 h in, then the run had to wait again.
        val progressAt = start + 20 * hour + hour / 4
        val progressed = SyncBlockedDecider.decide(waiting.copy(lastSentAtMs = progressAt), afterTwentyHours, progressAt)
        assertEquals(progressAt, progressed.next.waitingSinceMs)

        val (actions, _) = runs(waiting.copy(lastSentAtMs = progressAt), progressAt, progressAt + WAITING_THRESHOLD_MS - hour / 4, progressed.next)
        assertEquals(setOf(NoticeAction.None), actions.toSet())
    }

    @Test
    fun aRunThatWasNotWaiting_resetsTheWaitingClock() {
        val (_, afterTwentyHours) = runs(waiting, start, start + 20 * hour)
        val upToDate = SyncBlockedDecider.decide(healthy, afterTwentyHours, start + 20 * hour + hour / 4)
        assertNull(upToDate.next.waitingSinceMs)
    }

    @Test
    fun aResolvedCondition_isCleared_once() {
        val shown = BlockedNoticeState(BlockedCondition.SESSION_ENDED, start)

        val cleared = SyncBlockedDecider.decide(healthy, shown, start + hour)
        assertEquals(NoticeAction.Clear, cleared.action)
        assertNull(cleared.next.shown)

        assertEquals(NoticeAction.None, SyncBlockedDecider.decide(healthy, cleared.next, start + 2 * hour).action)
    }

    @Test
    fun aChangedCondition_replacesTheNotificationAtOnce() {
        val shown = BlockedNoticeState(BlockedCondition.SIGNED_OUT, start)
        val facts = healthy.copy(state = SyncState.SIGNED_OUT, signOutReason = SignOutReason.USER, hasUsageAccess = false)

        assertEquals(post(BlockedCondition.NO_USAGE_ACCESS), SyncBlockedDecider.decide(facts, shown, start + hour).action)
    }

    @Test
    fun notificationsOff_postsNothing_andPostsOnceTheyAreAllowed() {
        val facts = healthy.copy(state = SyncState.SIGNED_OUT, signOutReason = SignOutReason.SESSION_ENDED)

        val off = SyncBlockedDecider.decide(facts.copy(canPost = false), BlockedNoticeState(), start)
        assertEquals(NoticeAction.None, off.action)
        assertNull(off.next.shown)

        assertEquals(post(BlockedCondition.SESSION_ENDED), SyncBlockedDecider.decide(facts, off.next, start + hour).action)
    }

    @Test
    fun aClockMovedBackwards_doesNotSilenceTheRepeat() {
        val shown = BlockedNoticeState(BlockedCondition.NO_USAGE_ACCESS, start + 10 * hour)
        val facts = healthy.copy(hasUsageAccess = false)

        val decision = SyncBlockedDecider.decide(facts, shown, start)
        assertEquals(post(BlockedCondition.NO_USAGE_ACCESS), decision.action)
        assertEquals(start, decision.next.shownAtMs)

        assertEquals(NoticeAction.None, SyncBlockedDecider.decide(facts, decision.next, start + REPEAT_AFTER_MS - 1).action)
    }
}
