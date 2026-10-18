package com.timeorganizer.usagetracker.data.api.dto

data class LoginRequest(
    val email: String,
    val password: String
)

data class RefreshRequest(
    val refreshToken: String
)

data class AuthResponse(
    val accessToken: String?,
    val refreshToken: String?,
    val requiresTwoFactor: Boolean = false
)

data class LogoutRequest(
    val refreshToken: String
)
