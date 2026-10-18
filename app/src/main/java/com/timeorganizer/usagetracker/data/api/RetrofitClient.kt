package com.timeorganizer.usagetracker.data.api

import android.content.Context
import com.timeorganizer.usagetracker.BuildConfig
import com.timeorganizer.usagetracker.data.api.dto.RefreshRequest
import com.timeorganizer.usagetracker.data.local.AuthPreferences
import com.timeorganizer.usagetracker.util.Constants
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {

    private lateinit var authPrefs: AuthPreferences
    private val refreshMutex = Mutex()

    /** Must be called once in Application.onCreate() before any API calls. */
    fun init(context: Context) {
        authPrefs = AuthPreferences(context.applicationContext)
    }

    val authApiService: AuthApiService by lazy { buildAuthService() }

    val apiService: ApiService by lazy { buildService() }

    // Unauthenticated client — used for login + refresh only
    private fun buildAuthService(): AuthApiService {
        val client = OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor())
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .baseUrl(Constants.API_BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(AuthApiService::class.java)
    }

    // Authenticated client — attaches Bearer token and refreshes on 401
    private fun buildService(): ApiService {
        val authInterceptor = Interceptor { chain ->
            val token = authPrefs.token
            val request = if (token != null) {
                chain.request().newBuilder()
                    .addHeader("Authorization", "Bearer $token")
                    .build()
            } else {
                chain.request()
            }
            chain.proceed(request)
        }

        // Called by OkHttp when a 401 is received — tries to refresh the token once.
        // Uses a mutex so concurrent 401s don't race: the first caller refreshes,
        // others wait and reuse the new token.
        val tokenAuthenticator = Authenticator { _, response ->
            val failedToken = response.request.header("Authorization")
            if (failedToken == null) return@Authenticator null

            runBlocking {
                refreshMutex.withLock {
                    // Another thread may have already refreshed while we waited for the lock.
                    // If the stored token changed, just retry with the new one.
                    val currentToken = authPrefs.token
                    if (currentToken != null && "Bearer $currentToken" != failedToken) {
                        return@runBlocking response.request.newBuilder()
                            .header("Authorization", "Bearer $currentToken")
                            .build()
                    }

                    val currentRefreshToken = authPrefs.refreshToken ?: run {
                        authPrefs.clearTokens()
                        return@runBlocking null
                    }

                    val newTokens = try {
                        val result = authApiService.refresh(RefreshRequest(currentRefreshToken))
                        if (result.isSuccessful) result.body() else null
                    } catch (_: Exception) {
                        null
                    }

                    if (newTokens == null || newTokens.accessToken == null || newTokens.refreshToken == null) {
                        authPrefs.clearTokens()
                        return@runBlocking null
                    }

                    authPrefs.saveTokens(newTokens.accessToken, newTokens.refreshToken)

                    response.request.newBuilder()
                        .header("Authorization", "Bearer ${newTokens.accessToken}")
                        .build()
                }
            }
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .authenticator(tokenAuthenticator)
            .addInterceptor(loggingInterceptor())
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .baseUrl(Constants.API_BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }

    private fun loggingInterceptor() = HttpLoggingInterceptor().apply {
        level = if (BuildConfig.DEBUG) {
            HttpLoggingInterceptor.Level.BODY
        } else {
            HttpLoggingInterceptor.Level.NONE
        }
    }
}
