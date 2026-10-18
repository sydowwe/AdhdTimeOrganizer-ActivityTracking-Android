package com.timeorganizer.usagetracker.auth

import com.google.gson.Gson
import com.timeorganizer.usagetracker.data.api.dto.AuthResponse
import com.timeorganizer.usagetracker.data.api.dto.RefreshRequest
import com.timeorganizer.usagetracker.data.local.AuthPreferences
import com.timeorganizer.usagetracker.util.Constants
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Makes the refresh call and applies [RefreshPolicy] to how it ended. A raw OkHttp call rather than Retrofit
 * because the policy needs to know whether a failed request was ever written to the server, which an
 * [EventListener] can tell and an exception cannot.
 */
class TokenRefresher(
    private val client: OkHttpClient,
    private val authPrefs: AuthPreferences,
    private val gson: Gson = Gson()
) {
    /** Blocking; called from OkHttp's authenticator thread under the refresh lock. */
    fun refresh(refreshToken: String): RefreshDecision {
        var attempts = 0
        while (true) {
            when (val decision = RefreshPolicy.decide(attemptOnce(refreshToken), attempts)) {
                RefreshDecision.RetryNow -> attempts++
                is RefreshDecision.Save -> {
                    authPrefs.saveTokens(decision.accessToken, decision.refreshToken)
                    return decision
                }
                is RefreshDecision.SignOut -> {
                    authPrefs.clearTokens(decision.reason)
                    return decision
                }
                RefreshDecision.KeepTokens -> return decision
            }
        }
    }

    private fun attemptOnce(refreshToken: String): RefreshAttempt {
        val written = RequestWrittenListener()
        val request = Request.Builder()
            .url(Constants.API_BASE_URL + "auth/extension/refresh")
            .post(gson.toJson(RefreshRequest(refreshToken)).toRequestBody(JSON))
            .build()
        val startedAt = System.nanoTime()

        return try {
            client.newBuilder().eventListener(written).build().newCall(request).execute().use { response ->
                val body = if (response.isSuccessful) {
                    response.body?.charStream()?.let { gson.fromJson(it, AuthResponse::class.java) }
                } else {
                    null
                }
                RefreshAttempt.Http(response.code, body?.accessToken, body?.refreshToken)
            }
        } catch (_: IOException) {
            if (!written.requestStarted) RefreshAttempt.NotSent
            else RefreshAttempt.Ambiguous((System.nanoTime() - startedAt) / 1_000_000)
        }
    }

    private class RequestWrittenListener : EventListener() {
        @Volatile
        var requestStarted = false

        override fun requestHeadersStart(call: Call) {
            requestStarted = true
        }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
