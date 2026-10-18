package com.timeorganizer.usagetracker.auth

/** How one `/auth/extension/refresh` call ended. */
sealed interface RefreshAttempt {
    data class Http(val statusCode: Int, val accessToken: String?, val refreshToken: String?) : RefreshAttempt

    /** Failed before the request was written — the server never saw the token. */
    data object NotSent : RefreshAttempt

    /** Failed after the request was written: the server may have rotated the token and the answer was lost. */
    data object Ambiguous : RefreshAttempt
}

sealed interface RefreshDecision {
    data class Save(val accessToken: String, val refreshToken: String) : RefreshDecision

    /** Send the same token again once [RefreshPolicy.waitBeforeSendingMs] allows it. */
    data object RetryAfterGrace : RefreshDecision
    data class SignOut(val reason: SignOutReason) : RefreshDecision

    /** The token is still good; this request fails and the next sync tries again. */
    data object KeepTokens : RefreshDecision
}

enum class SignOutReason {
    /** The server refused the refresh token (400/401/403): the session really is over. */
    SESSION_ENDED,

    /** The user pressed Sign Out. */
    USER
}

/**
 * When a refresh signs the phone out — pure, so it is pinned by JVM tests.
 *
 * Only the server refusing the refresh token ends the session. Offline, a throttle (the refresh is rate
 * limited per client IP, and phones share carrier IPs) or a 5xx keeps the tokens; the sync waits.
 *
 * The server rotates the refresh token on every call. For a token client it reads a replay of the rotated
 * token whose successor nobody used as a lost answer and exchanges it again — but only once the latest
 * rotation is more than **5 s** old (`RefreshTokenService`, `RotationGraceWindow`, `LostResponseRecoveryWindow`).
 * Inside those 5 s it answers **409**, "rotated moments ago" (a server from before 409 answers 401 there, which
 * would sign us out). So a refresh whose answer may have been lost — sent and then failed, a 409, or a 5xx,
 * since rotation commits before the access token is minted — is replayed only after [REPLAY_DELAY_MS]: retried
 * once after that wait, and otherwise left to the next sync. It never signs the phone out.
 */
object RefreshPolicy {

    /** Past the server's 5 s grace, with room for a clock-skewed request to arrive late. */
    const val REPLAY_DELAY_MS = 6_000L

    fun decide(attempt: RefreshAttempt, previousAttempts: Int): RefreshDecision = when (attempt) {
        is RefreshAttempt.Http -> when (attempt.statusCode) {
            in 200..299 ->
                if (attempt.accessToken.isNullOrEmpty() || attempt.refreshToken.isNullOrEmpty()) RefreshDecision.KeepTokens
                else RefreshDecision.Save(attempt.accessToken, attempt.refreshToken)
            400, 401, 403 -> RefreshDecision.SignOut(SignOutReason.SESSION_ENDED)
            409, in 500..599 -> retryOnce(previousAttempts)
            else -> RefreshDecision.KeepTokens
        }
        RefreshAttempt.NotSent -> RefreshDecision.KeepTokens
        RefreshAttempt.Ambiguous -> retryOnce(previousAttempts)
    }

    /**
     * Whether an attempt may have rotated the token without us seeing the new one. The next send of the same
     * token has to wait out the grace, whichever refresh makes it.
     */
    fun leftTheTokenUncertain(attempt: RefreshAttempt): Boolean = when (attempt) {
        RefreshAttempt.Ambiguous -> true
        RefreshAttempt.NotSent -> false
        is RefreshAttempt.Http -> when (attempt.statusCode) {
            in 200..299 -> decide(attempt, 0) !is RefreshDecision.Save
            409, in 500..599 -> true
            else -> false
        }
    }

    /** How long to hold a send back, given how long ago an attempt left the token uncertain (`null`: never). */
    fun waitBeforeSendingMs(sinceUncertainMs: Long?): Long =
        if (sinceUncertainMs == null) 0 else (REPLAY_DELAY_MS - sinceUncertainMs).coerceAtLeast(0)

    private fun retryOnce(previousAttempts: Int): RefreshDecision =
        if (previousAttempts == 0) RefreshDecision.RetryAfterGrace else RefreshDecision.KeepTokens
}
