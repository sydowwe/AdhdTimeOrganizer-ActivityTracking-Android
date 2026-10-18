package com.timeorganizer.usagetracker.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RefreshPolicyTest {

    private fun decide(attempt: RefreshAttempt, previousAttempts: Int = 0) = RefreshPolicy.decide(attempt, previousAttempts)

    @Test
    fun aRotatedPair_isSaved() {
        assertEquals(RefreshDecision.Save("a", "r"), decide(RefreshAttempt.Http(200, "a", "r")))
    }

    @Test
    fun onlyTheServerRefusingTheToken_endsTheSession() {
        for (code in listOf(400, 401, 403)) {
            assertEquals("$code", RefreshDecision.SignOut(SignOutReason.SESSION_ENDED), decide(RefreshAttempt.Http(code, null, null)))
        }
    }

    @Test
    fun offlineOrThrottled_keepsTheTokens() {
        assertEquals(RefreshDecision.KeepTokens, decide(RefreshAttempt.NotSent))
        // The refresh is throttled per client IP, and phones share carrier IPs.
        assertEquals(RefreshDecision.KeepTokens, decide(RefreshAttempt.Http(429, null, null)))
        assertEquals(RefreshDecision.KeepTokens, decide(RefreshAttempt.Http(404, null, null)))
    }

    /** Rotation commits before the access token is minted, so a 500 may have rotated the token too. */
    @Test
    fun aServerError_isRetriedOnceAfterTheGrace_thenKeepsTheTokens() {
        assertEquals(RefreshDecision.RetryAfterGrace, decide(RefreshAttempt.Http(500, null, null), 0))
        assertEquals(RefreshDecision.KeepTokens, decide(RefreshAttempt.Http(503, null, null), 1))
        assertTrue(RefreshPolicy.leftTheTokenUncertain(RefreshAttempt.Http(500, null, null)))
    }

    @Test
    fun aSuccessWithoutBothTokens_keepsTheOldOnes() {
        assertEquals(RefreshDecision.KeepTokens, decide(RefreshAttempt.Http(200, "a", null)))
        // The server did rotate, and the new token is not in our hands.
        assertTrue(RefreshPolicy.leftTheTokenUncertain(RefreshAttempt.Http(200, "a", null)))
    }

    /**
     * A lost answer may mean the server rotated. Past its 5 s grace the server exchanges the old token again,
     * so the phone retries once after the wait and otherwise keeps the token for the next sync — it is never a
     * reason to sign out.
     */
    @Test
    fun aLostAnswer_isRetriedOnceAfterTheGrace_thenKeepsTheTokens() {
        assertEquals(RefreshDecision.RetryAfterGrace, decide(RefreshAttempt.Ambiguous, 0))
        assertEquals(RefreshDecision.KeepTokens, decide(RefreshAttempt.Ambiguous, 1))
        assertTrue(RefreshPolicy.leftTheTokenUncertain(RefreshAttempt.Ambiguous))
    }

    @Test
    fun onlyASentRequestWithoutAClearAnswer_leavesTheTokenUncertain() {
        assertFalse(RefreshPolicy.leftTheTokenUncertain(RefreshAttempt.NotSent))
        assertFalse(RefreshPolicy.leftTheTokenUncertain(RefreshAttempt.Http(200, "a", "r")))
        assertFalse(RefreshPolicy.leftTheTokenUncertain(RefreshAttempt.Http(401, null, null)))
        assertFalse(RefreshPolicy.leftTheTokenUncertain(RefreshAttempt.Http(429, null, null)))
    }

    /**
     * Inside the grace the server answers a just-rotated token with a 401, which signs the phone out — so an
     * uncertain token is held back until the grace has passed, whichever refresh sends it next.
     */
    @Test
    fun anUncertainToken_isNotSentAgainInsideTheGrace() {
        assertEquals(0L, RefreshPolicy.waitBeforeSendingMs(null))
        assertEquals(RefreshPolicy.REPLAY_DELAY_MS, RefreshPolicy.waitBeforeSendingMs(0))
        assertEquals(RefreshPolicy.REPLAY_DELAY_MS - 1_000, RefreshPolicy.waitBeforeSendingMs(1_000))
        assertEquals(0L, RefreshPolicy.waitBeforeSendingMs(RefreshPolicy.REPLAY_DELAY_MS + 1))
        assertTrue("past the server's 5 s grace", RefreshPolicy.REPLAY_DELAY_MS > 5_000)
    }
}
