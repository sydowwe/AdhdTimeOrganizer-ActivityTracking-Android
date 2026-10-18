package com.timeorganizer.usagetracker.data.api

import android.content.Context
import com.timeorganizer.usagetracker.BuildConfig
import com.timeorganizer.usagetracker.auth.RefreshDecision
import com.timeorganizer.usagetracker.auth.TokenRefresher
import com.timeorganizer.usagetracker.data.local.AuthPreferences
import com.timeorganizer.usagetracker.util.Constants
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {

    private lateinit var authPrefs: AuthPreferences
    private val refreshLock = Any()

    /** Must be called once in Application.onCreate() before any API calls. */
    fun init(context: Context) {
        authPrefs = AuthPreferences.get(context)
    }

    private val baseClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor())
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val refresher: TokenRefresher by lazy {
        // A refresh that times out after it was sent may have rotated the token, and RefreshPolicy signs the
        // phone out rather than replay it later; ten seconds is long enough for a slow mobile network.
        TokenRefresher(baseClient.newBuilder().readTimeout(10, TimeUnit.SECONDS).build(), authPrefs)
    }

    /** Login, 2FA, logout and health — no authenticator. */
    val authApiService: AuthApiService by lazy { retrofit(baseClient).create(AuthApiService::class.java) }

    val apiService: ApiService by lazy {
        val client = baseClient.newBuilder()
            .addInterceptor(authInterceptor)
            .authenticator(tokenAuthenticator)
            .build()
        retrofit(client).create(ApiService::class.java)
    }

    private val authInterceptor = Interceptor { chain ->
        val token = authPrefs.token
        val request = if (token != null) {
            chain.request().newBuilder().header("Authorization", "Bearer $token").build()
        } else {
            chain.request()
        }
        chain.proceed(request)
    }

    /**
     * On a 401: if another call already refreshed, retry with that token; otherwise refresh once. A request
     * that is still refused with a fresh token gives up instead of refreshing again — every refresh rotates
     * the token, and a loop of them would only burn the server's throttle.
     */
    private val tokenAuthenticator = Authenticator { _, response ->
        val failedAuthorization = response.request.header("Authorization") ?: return@Authenticator null
        if (response.priorResponse != null) return@Authenticator null

        synchronized(refreshLock) {
            val current = authPrefs.token
            if (current != null && "Bearer $current" != failedAuthorization) {
                return@Authenticator response.request.withBearer(current)
            }

            val refreshToken = authPrefs.refreshToken ?: return@Authenticator null
            when (val decision = refresher.refresh(refreshToken)) {
                is RefreshDecision.Save -> response.request.withBearer(decision.accessToken)
                else -> null
            }
        }
    }

    private fun Request.withBearer(token: String): Request =
        newBuilder().header("Authorization", "Bearer $token").build()

    private fun retrofit(client: OkHttpClient): Retrofit =
        Retrofit.Builder()
            .baseUrl(Constants.API_BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

    /**
     * Request lines and status codes only, and only in debug. Never BODY: the login body is the user's
     * password, the auth bodies are tokens, and a sync body is every app they used.
     */
    private fun loggingInterceptor() = HttpLoggingInterceptor().apply {
        level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
        redactHeader("Authorization")
    }

}
