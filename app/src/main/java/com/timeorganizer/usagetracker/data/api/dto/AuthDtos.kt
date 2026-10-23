package com.timeorganizer.usagetracker.data.api.dto

/**
 * `client` names the tracker signing in. The server refuses a login without it, and the session it issues may call
 * the phone's routes only (`/activity-tracking/android/...` and the shared ones).
 */
data class LoginRequest(
    val email: String,
    val password: String,
    val client: String = "AndroidTracker"
)

data class RefreshRequest(
    val refreshToken: String
)

/**
 * `ExtensionLoginResponse`: either the token pair, or `requiresTwoFactor` with a `pendingAuthToken` for the
 * second step. `requiresTwoFactorSetup` means the account must enable 2FA first, which only the web app can do.
 */
data class AuthResponse(
    val accessToken: String?,
    val refreshToken: String?,
    val requiresTwoFactor: Boolean = false,
    val requiresTwoFactorSetup: Boolean = false,
    val pendingAuthToken: String? = null
)

/** `TwoFactorAuthLoginRequest` — `token` is the 6-digit code or a recovery code. */
data class TwoFactorLoginRequest(
    val token: String,
    val pendingAuthToken: String,
    val stayLoggedIn: Boolean = true
)

data class LogoutRequest(
    val refreshToken: String
)
