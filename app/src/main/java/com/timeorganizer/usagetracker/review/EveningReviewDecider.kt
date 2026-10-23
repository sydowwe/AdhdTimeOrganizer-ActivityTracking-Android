package com.timeorganizer.usagetracker.review

/**
 * What the phone remembers about the evening review: the server's evening-shutdown moment it last heard, when it
 * heard it, and when it last asked about the day's unmapped time.
 */
data class ReviewState(
    val momentMs: Long? = null,
    val fetchedAtMs: Long = 0,
    val lastReviewedAtMs: Long? = null
)

/**
 * When the evening review runs, and when the moment it waits for must be heard again — pure, so JVM tests pin it.
 *
 * The moment is the server's (`GET /tracker/evening-shutdown-moment`: bed time less the user's offset), so the phone
 * asks about unmapped time when the evening begins, not at an hour of its own. The sync worker calls in every
 * 15 minutes, so the review is not on a timer: it is due on the first run at or after the moment and still due for
 * [WINDOW_MS] after it. Past that the evening is over — a review at 2 a.m. is worse than none — and the next
 * moment is fetched instead.
 */
object EveningReviewDecider {

    /** How long after the moment a run still counts as that evening. */
    const val WINDOW_MS = 3 * 60 * 60 * 1000L

    /** No two reviews closer than this, so a moment that shifts when the user edits their bed time cannot ask twice. */
    const val MIN_GAP_MS = 12 * 60 * 60 * 1000L

    /** A heard moment is trusted this long: bed time or the offset may have changed on the web. */
    const val MOMENT_MAX_AGE_MS = 6 * 60 * 60 * 1000L

    fun shouldReview(state: ReviewState, nowMs: Long): Boolean {
        val moment = state.momentMs ?: return false
        if (nowMs < moment || nowMs >= moment + WINDOW_MS) return false
        val last = state.lastReviewedAtMs ?: return true
        // A clock moved backwards counts as due, so a review is never held back by a future timestamp.
        return nowMs - last !in 0 until MIN_GAP_MS
    }

    /**
     * True when there is no moment, when the one held is old, or when it is behind us and will not be acted on any more
     * (reviewed already, or its window is over) — the next evening's moment is then wanted. A moment still due is
     * left alone: the review comes first, the fetch after it.
     */
    fun needsMoment(state: ReviewState, nowMs: Long): Boolean {
        val moment = state.momentMs ?: return true
        if (nowMs - state.fetchedAtMs !in 0 until MOMENT_MAX_AGE_MS) return true
        return nowMs >= moment && !shouldReview(state, nowMs)
    }
}
