package com.timeorganizer.usagetracker.data.api

import com.timeorganizer.usagetracker.data.api.dto.AuthResponse
import com.timeorganizer.usagetracker.data.api.dto.LoginRequest
import com.timeorganizer.usagetracker.data.api.dto.LogoutRequest
import com.timeorganizer.usagetracker.data.api.dto.RefreshRequest
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

interface AuthApiService {

    @POST("auth/extension/login")
    suspend fun login(
        @Body request: LoginRequest
    ): Response<AuthResponse>

    @POST("auth/extension/refresh")
    suspend fun refresh(
        @Body request: RefreshRequest
    ): Response<AuthResponse>

    @POST("auth/extension/logout")
    suspend fun logout(
        @Header("Authorization") authorization: String,
        @Body request: LogoutRequest
    ): Response<Unit>
}
