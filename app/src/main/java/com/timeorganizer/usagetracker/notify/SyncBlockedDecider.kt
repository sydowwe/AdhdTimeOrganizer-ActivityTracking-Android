package com.timeorganizer.usagetracker.notify

import com.timeorganizer.usagetracker.auth.SignOutReason
import com.timeorganizer.usagetracker.sync.SyncState

/** Why syncing has stopped, in the order one is shown over another. */
enum class BlockedCondition {
    /** Nothing new is recorded — the only condition that loses data, so it wins over the others. */
    NO_USAGE_ACCESS,

    /** The server refused the refresh token. */
    SESSION_ENDED,

    /** The user pressed Sign Out. */
    SIGNED_OUT,

    /** Sessions are pending and nothing has reached the server for [SyncBlockedDecider.WAITING_THRESHOLD_MS]. */
    WAITING
}

/** What one sync run knows about whether it is blocked. */
data class SyncFacts(
    val state: SyncState,
    val hasUsageAccess: Boolean,
    val signOutReason: SignOutReason?,
    val pending: Int,
    /** When sessions last reached the server; `0` before the first time. */
    val lastSentAtMs: Long,
    /** Notifications are allowed for the sync-problems channel. */
    val canPost: Boolean
)

/** What the last run left behind: the notification it showed, and since when the sync has been waiting. */
data class BlockedNoticeState(
    val shown: BlockedCondition? = null,
    val shownAtMs: Long = 0,
    val waitingSinceMs: Long? = null
)

sealed interface NoticeAction {
    data class Post(val condition: BlockedCondition) : NoticeAction
    data object Clear : NoticeAction
    data object None : NoticeAction
}

data class NoticeDecision(val action: NoticeAction, val next: BlockedNoticeState)

/**
 * Whether a sync run posts, clears or leaves the "sync blocked" notification — pure, so it is pinned by JVM
 * tests.
 *
 * One notification at a time, for the most pressing condition. It is posted when a condition starts or
 * changes, cleared when none is left, and posted again at most once every [REPEAT_AFTER_MS] while the same
 * condition holds (it may have been swiped away). Waiting only counts once it has lasted
 * [WAITING_THRESHOLD_MS] with sessions pending: a night offline or a server deploy is not worth a
 * notification. A phone that has never been signed in is setting up, not blocked.
 */
object SyncBlockedDecider {

    const val WAITING_THRESHOLD_MS = 24 * 60 * 60 * 1000L
    const val REPEAT_AFTER_MS = 24 * 60 * 60 * 1000L

    fun decide(facts: SyncFacts, previous: BlockedNoticeState, nowMs: Long): NoticeDecision {
        val waitingSinceMs = waitingSince(facts, previous, nowMs)
        val condition = when {
            !facts.hasUsageAccess -> BlockedCondition.NO_USAGE_ACCESS
            facts.state == SyncState.SIGNED_OUT -> when (facts.signOutReason) {
                SignOutReason.SESSION_ENDED -> BlockedCondition.SESSION_ENDED
                SignOutReason.USER -> BlockedCondition.SIGNED_OUT
                null -> null
            }
            waitingSinceMs != null && nowMs - waitingSinceMs >= WAITING_THRESHOLD_MS -> BlockedCondition.WAITING
            else -> null
        }

        if (condition == null || !facts.canPost) {
            // Not posted while notifications are off, so it is posted as soon as they are allowed.
            val action = if (previous.shown != null) NoticeAction.Clear else NoticeAction.None
            return NoticeDecision(action, BlockedNoticeState(waitingSinceMs = waitingSinceMs))
        }

        // A clock moved backwards counts as due, so a notification is never held back by a future timestamp.
        val due = condition != previous.shown || nowMs - previous.shownAtMs !in 0 until REPEAT_AFTER_MS
        return if (due) {
            NoticeDecision(NoticeAction.Post(condition), BlockedNoticeState(condition, nowMs, waitingSinceMs))
        } else {
            NoticeDecision(NoticeAction.None, previous.copy(waitingSinceMs = waitingSinceMs))
        }
    }

    /**
     * When the current stretch of waiting with sessions pending began, or `null` when the sync is not waiting.
     * A run that sent some batches before it had to wait made progress, so the stretch starts again there.
     */
    private fun waitingSince(facts: SyncFacts, previous: BlockedNoticeState, nowMs: Long): Long? {
        if (facts.state != SyncState.WAITING || facts.pending == 0) return null
        val since = previous.waitingSinceMs?.takeIf { it <= nowMs } ?: nowMs
        return maxOf(since, facts.lastSentAtMs.coerceAtMost(nowMs))
    }
}
