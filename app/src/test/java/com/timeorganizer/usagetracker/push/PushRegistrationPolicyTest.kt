package com.timeorganizer.usagetracker.push

import com.timeorganizer.usagetracker.push.PushRegistrationPolicy.Registered
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the phone tells the server where to push it (prompt 22). */
class PushRegistrationPolicyTest {

    private val now = 1_000_000_000L
    private val hour = 60 * 60 * 1000L
    private val done = Registered(token = "t1", account = "7", atMs = now - hour)

    @Test
    fun nothingToRegister_withoutATokenOrAnAccount() {
        assertFalse(PushRegistrationPolicy.due(null, "7", Registered(null, null, null), now))
        assertFalse(PushRegistrationPolicy.due("", "7", Registered(null, null, null), now))
        assertFalse(PushRegistrationPolicy.due("t1", null, Registered(null, null, null), now))
    }

    @Test
    fun theFirstRegistration_isDue() {
        assertTrue(PushRegistrationPolicy.due("t1", "7", Registered(null, null, null), now))
    }

    @Test
    fun aNewToken_orAnotherAccount_isDueAtOnce() {
        assertTrue(PushRegistrationPolicy.due("t2", "7", done, now))
        assertTrue(PushRegistrationPolicy.due("t1", "8", done, now))
    }

    @Test
    fun theSameRegistration_isDueAgainOnlyAfterADay() {
        assertFalse(PushRegistrationPolicy.due("t1", "7", done, now))
        assertFalse(PushRegistrationPolicy.due("t1", "7", done, now - hour + PushRegistrationPolicy.REFRESH_MS - 1))
        assertTrue(PushRegistrationPolicy.due("t1", "7", done, now - hour + PushRegistrationPolicy.REFRESH_MS))
    }

    @Test
    fun aClockMovedBackwards_readsAsDue() {
        assertTrue(PushRegistrationPolicy.due("t1", "7", done, now - 2 * hour))
    }

    @Test
    fun noRecordedInstant_isDue() {
        assertTrue(PushRegistrationPolicy.due("t1", "7", done.copy(atMs = null), now))
    }
}
