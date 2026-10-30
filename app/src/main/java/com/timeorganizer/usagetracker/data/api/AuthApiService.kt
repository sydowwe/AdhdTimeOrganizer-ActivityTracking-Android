package com.timeorganizer.usagetracker.data.api

import com.timeorganizer.usagetracker.data.api.dto.AuthResponse
import com.timeorganizer.usagetracker.data.api.dto.GoogleLoginRequest
import com.timeorganizer.usagetracker.data.api.dto.LoginRequest
import com.timeorganizer.usagetracker.data.api.dto.LogoutRequest
import com.timeorganizer.usagetracker.data.api.dto.TwoFactorLoginRequest
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Url

/**
 * Routes called without the token authenticator. The refresh itself is not here: it is made by
 * `TokenRefresher` on a raw OkHttp call, because it has to know whether a failed request ever reached the
 * server.
 */
interface AuthApiService {

    @POST("auth/tracker/login")
    suspend fun login(
        @Body request: LoginRequest
    ): Response<AuthResponse>

    @POST("auth/tracker/login/google")
    suspend fun loginGoogle(
        @Body request: GoogleLoginRequest
    ): Response<AuthResponse>

    @POST("auth/tracker/login/2fa")
    suspend fun loginTwoFactor(
        @Body request: TwoFactorLoginRequest
    ): Response<AuthResponse>

    @POST("auth/tracker/logout")
    suspend fun logout(
        @Header("Authorization") authorization: String,
        @Body request: LogoutRequest
    ): Response<Unit>

    /**
     * Sign-out's first call (prompt 22): stop pushing to this phone. Here, with the token passed by hand like [logout],
     * because sign-out clears the stored tokens before it runs. 204 whether or not it was registered.
     */
    @DELETE("tracker/android/push-device/{deviceId}")
    suspend fun unregisterPushDevice(
        @Header("Authorization") authorization: String,
        @Path("deviceId") deviceId: String
    ): Response<Unit>

    @GET
    suspend fun health(@Url url: String): Response<Unit>
}
