package com.timeorganizer.usagetracker.auth

/** How one `/auth/extension/refresh` call ended. */
sealed interface RefreshAttempt {
    data class Http(val statusCode: Int, val accessToken: String?, val refreshToken: String?) : RefreshAttempt

    /** Failed before the request was written — the server never saw the token. */
    data object NotSent : RefreshAttempt

    /** Failed after the request was written: the server may have rotated the token and the answer was lost. */
    data class Ambiguous(val elapsedMs: Long) : RefreshAttempt
}

sealed interface RefreshDecision {
    data class Save(val accessToken: String, val refreshToken: String) : RefreshDecision
    data object RetryNow : RefreshDecision
    data class SignOut(val reason: SignOutReason) : RefreshDecision

    /** The token is still good; this request fails and the next sync tries again. */
    data object KeepTokens : RefreshDecision
}

enum class SignOutReason {
    /** The server refused the refresh token (400/401/403): the session really is over. */
    SESSION_ENDED,

    /** A refresh answer was lost; re-using the old token later could sign out every device of the account. */
    LOST_REFRESH,

    /** The user pressed Sign Out. */
    USER
}

/**
 * When a refresh signs the phone out — pure, so it is pinned by JVM tests.
 *
 * Only the server refusing the refresh token ends the session. Offline, a throttle (the refresh is rate
 * limited per client IP, and phones share carrier IPs) or a 5xx keeps the tokens; the sync waits.
 *
 * The server rotates the refresh token on every call and treats a rotated token replayed more than **5 s**
 * later as theft: it revokes *every* session of the user, web included (`RefreshTokenService`,
 * `RotationGraceWindow`). So a refresh whose answer may have been lost is never replayed later:
 * - it is retried at once if it failed quickly, which lands inside the grace window — the server then either
 *   rotates (it had not before) or soft-refuses with a 401 (it had, and the new token is lost to us);
 * - otherwise, and if the retry is ambiguous too, the phone signs itself out rather than risk the account.
 *
 * A 5xx is retried once at once for the same reason: rotation commits before the access token is minted, so
 * a 500 after the commit is possible.
 */
object RefreshPolicy {

    /** A failure this fast leaves room for the retry to reach the server within its 5 s grace window. */
    const val SAFE_RETRY_WINDOW_MS = 2_500L

    fun decide(attempt: RefreshAttempt, previousAttempts: Int): RefreshDecision = when (attempt) {
        is RefreshAttempt.Http -> when (attempt.statusCode) {
            in 200..299 ->
                if (attempt.accessToken.isNullOrEmpty() || attempt.refreshToken.isNullOrEmpty()) RefreshDecision.KeepTokens
                else RefreshDecision.Save(attempt.accessToken, attempt.refreshToken)
            400, 401, 403 -> RefreshDecision.SignOut(SignOutReason.SESSION_ENDED)
            in 500..599 -> if (previousAttempts == 0) RefreshDecision.RetryNow else RefreshDecision.KeepTokens
            else -> RefreshDecision.KeepTokens
        }
        RefreshAttempt.NotSent -> RefreshDecision.KeepTokens
        is RefreshAttempt.Ambiguous ->
            if (previousAttempts == 0 && attempt.elapsedMs <= SAFE_RETRY_WINDOW_MS) RefreshDecision.RetryNow
            else RefreshDecision.SignOut(SignOutReason.LOST_REFRESH)
    }
}
