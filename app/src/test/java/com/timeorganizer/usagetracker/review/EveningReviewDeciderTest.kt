package com.timeorganizer.usagetracker.review

import com.timeorganizer.usagetracker.review.EveningReviewDecider.MIN_GAP_MS
import com.timeorganizer.usagetracker.review.EveningReviewDecider.MOMENT_MAX_AGE_MS
import com.timeorganizer.usagetracker.review.EveningReviewDecider.WINDOW_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EveningReviewDeciderTest {

    private val minute = 60 * 1000L
    private val hour = 60 * minute
    private val moment = 1_000 * hour

    /** A moment heard an hour before it is due, not yet acted on. */
    private val waiting = ReviewState(momentMs = moment, fetchedAtMs = moment - hour)

    @Test
    fun noMomentYet_noReview_butOneIsWanted() {
        val state = ReviewState()

        assertFalse(EveningReviewDecider.shouldReview(state, moment))
        assertTrue(EveningReviewDecider.needsMoment(state, moment))
    }

    @Test
    fun beforeTheMoment_waits() {
        assertFalse(EveningReviewDecider.shouldReview(waiting, moment - 1))
        assertFalse(EveningReviewDecider.needsMoment(waiting, moment - 1))
    }

    @Test
    fun theFirstRunAtOrAfterTheMoment_reviews() {
        assertTrue(EveningReviewDecider.shouldReview(waiting, moment))
        assertTrue(EveningReviewDecider.shouldReview(waiting, moment + 14 * minute))
    }

    @Test
    fun aDueMoment_isNotRefetchedBeforeTheReview() {
        // The review comes first; the next evening's moment is fetched after it.
        assertFalse(EveningReviewDecider.needsMoment(waiting, moment + minute))
    }

    @Test
    fun afterTheReview_theNextMomentIsWanted_andNoSecondReviewFollows() {
        val reviewed = waiting.copy(lastReviewedAtMs = moment + minute)

        assertFalse(EveningReviewDecider.shouldReview(reviewed, moment + 16 * minute))
        assertTrue(EveningReviewDecider.needsMoment(reviewed, moment + 16 * minute))
    }

    @Test
    fun aMissedEvening_isNotReviewedLate_andTheNextMomentIsWanted() {
        // The phone was off until after the window: no 2 a.m. prompt.
        val now = moment + WINDOW_MS

        assertFalse(EveningReviewDecider.shouldReview(waiting, now))
        assertTrue(EveningReviewDecider.needsMoment(waiting, now))
    }

    @Test
    fun theLastRunInsideTheWindow_stillReviews() {
        assertTrue(EveningReviewDecider.shouldReview(waiting, moment + WINDOW_MS - 1))
    }

    /** The user moves their bed time an hour later after tonight's review: tonight does not ask twice. */
    @Test
    fun aMomentThatShiftsAfterTheReview_doesNotAskTwice() {
        val shifted = ReviewState(momentMs = moment + hour, fetchedAtMs = moment + 2 * minute, lastReviewedAtMs = moment + minute)

        assertFalse(EveningReviewDecider.shouldReview(shifted, moment + hour + 5 * minute))
    }

    @Test
    fun theNextEvening_isReviewedAgain() {
        val tomorrow = moment + 24 * hour
        val state = ReviewState(momentMs = tomorrow, fetchedAtMs = tomorrow - hour, lastReviewedAtMs = moment + minute)

        assertTrue(EveningReviewDecider.shouldReview(state, tomorrow + minute))
    }

    @Test
    fun aClockMovedBackwards_doesNotHoldAReviewBack() {
        val state = ReviewState(momentMs = moment, fetchedAtMs = moment - hour, lastReviewedAtMs = moment + 5 * hour)

        assertTrue(EveningReviewDecider.shouldReview(state, moment + minute))
    }

    @Test
    fun aMomentHeardSixHoursAgo_isHeardAgain() {
        val heard = ReviewState(momentMs = moment, fetchedAtMs = moment - 10 * hour)
        val sixHoursLater = heard.fetchedAtMs + MOMENT_MAX_AGE_MS

        assertFalse(EveningReviewDecider.needsMoment(heard, sixHoursLater - 1))
        assertTrue(EveningReviewDecider.needsMoment(heard, sixHoursLater))
    }

    @Test
    fun aFetchStampedInTheFuture_isStale() {
        val state = ReviewState(momentMs = moment, fetchedAtMs = moment - hour + 3 * hour)

        assertTrue(EveningReviewDecider.needsMoment(state, moment - 2 * hour))
    }

    @Test
    fun theGapBetweenReviews_isHalfADay() {
        assertEquals(12 * hour, MIN_GAP_MS)
    }
}
