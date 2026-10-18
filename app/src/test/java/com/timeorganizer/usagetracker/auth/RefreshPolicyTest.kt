package com.timeorganizer.usagetracker.auth

import org.junit.Assert.assertEquals
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

    @Test
    fun aServerError_isRetriedOnceAtOnce_thenKeepsTheTokens() {
        assertEquals(RefreshDecision.RetryNow, decide(RefreshAttempt.Http(500, null, null), 0))
        assertEquals(RefreshDecision.KeepTokens, decide(RefreshAttempt.Http(503, null, null), 1))
    }

    @Test
    fun aSuccessWithoutBothTokens_keepsTheOldOnes() {
        assertEquals(RefreshDecision.KeepTokens, decide(RefreshAttempt.Http(200, "a", null)))
    }

    /**
     * A lost answer may mean the server rotated. Replaying the old token more than 5 s later revokes every
     * session of the account, so it is retried at once only if that lands inside the grace window — and
     * otherwise the phone signs itself out.
     */
    @Test
    fun aLostAnswer_isRetriedOnlyInsideTheGraceWindow_elseSignsThisPhoneOut() {
        assertEquals(RefreshDecision.RetryNow, decide(RefreshAttempt.Ambiguous(RefreshPolicy.SAFE_RETRY_WINDOW_MS), 0))
        assertEquals(
            RefreshDecision.SignOut(SignOutReason.LOST_REFRESH),
            decide(RefreshAttempt.Ambiguous(RefreshPolicy.SAFE_RETRY_WINDOW_MS + 1), 0)
        )
        assertEquals(RefreshDecision.SignOut(SignOutReason.LOST_REFRESH), decide(RefreshAttempt.Ambiguous(100), 1))
    }

    @Test
    fun theRetrysSoftRefusal_endsOnlyThisPhonesSession() {
        // Inside the grace window the server answers a just-rotated token with a 401 and revokes nothing else.
        assertEquals(RefreshDecision.SignOut(SignOutReason.SESSION_ENDED), decide(RefreshAttempt.Http(401, null, null), 1))
    }
}
