package com.timeorganizer.usagetracker.ignore

import org.junit.Assert.assertEquals
import org.junit.Test

/** The ignore path the "Ignored apps" screen, the evening review and the sync run share. */
class IgnoreCallOutcomeTest {

    @Test
    fun a2xx_isConfirmed() {
        assertEquals(IgnoreCallOutcome.CONFIRMED, IgnoreCallOutcome.of(200))
        assertEquals(IgnoreCallOutcome.CONFIRMED, IgnoreCallOutcome.of(204))
    }

    @Test
    fun aMalformedRequest_isRejected() {
        assertEquals(IgnoreCallOutcome.REJECTED, IgnoreCallOutcome.of(400))
        assertEquals(IgnoreCallOutcome.REJECTED, IgnoreCallOutcome.of(422))
    }

    @Test
    fun a401_needsTheSignIn() {
        assertEquals(IgnoreCallOutcome.AUTH_REQUIRED, IgnoreCallOutcome.of(401))
    }

    /**
     * Nothing learned about the ignore: it stays pending and the phone keeps dropping the app. A 404 is a proxy or a
     * server from before the route, never "no such package"; a 409 is the server's unique-index race, sent again.
     */
    @Test
    fun everythingElse_keepsTheOp() {
        for (status in listOf(null, 403, 404, 408, 409, 425, 429, 500, 502, 503)) {
            assertEquals("status $status", IgnoreCallOutcome.KEEP, IgnoreCallOutcome.of(status))
        }
    }

    @Test
    fun theOutboxPurge_takesOnlyIgnoredPackages() {
        assertEquals(
            listOf("insta", "reddit"),
            OutboxPurge.packagesToDelete(listOf("chat", "insta", "reddit", "insta"), setOf("insta", "reddit", "gone"))
        )
    }

    @Test
    fun theOutboxPurge_withNothingIgnored_takesNothing() {
        assertEquals(emptyList<String>(), OutboxPurge.packagesToDelete(listOf("chat", "insta"), emptySet()))
    }
}
