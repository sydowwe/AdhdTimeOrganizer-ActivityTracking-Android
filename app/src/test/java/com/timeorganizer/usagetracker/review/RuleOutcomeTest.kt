package com.timeorganizer.usagetracker.review

import org.junit.Assert.assertEquals
import org.junit.Test

class RuleOutcomeTest {

    @Test
    fun a2xx_isSaved() {
        assertEquals(RuleOutcome.SAVED, RuleOutcome.of(200))
        assertEquals(RuleOutcome.SAVED, RuleOutcome.of(204))
    }

    /** The package got a rule from somewhere else meanwhile: the list is stale, not the tap wrong. */
    @Test
    fun a409_isAlreadyMapped() {
        assertEquals(RuleOutcome.ALREADY_MAPPED, RuleOutcome.of(409))
    }

    @Test
    fun a404_isAnActivityThatIsGone() {
        assertEquals(RuleOutcome.ACTIVITY_GONE, RuleOutcome.of(404))
    }

    /** Nothing learned about the rule — the row must stay tappable rather than claim it was saved or refused. */
    @Test
    fun everythingElse_isTryAgain() {
        for (status in listOf(400, 401, 403, 408, 429, 500, 502, 503)) {
            assertEquals("status $status", RuleOutcome.TRY_AGAIN, RuleOutcome.of(status))
        }
    }
}
