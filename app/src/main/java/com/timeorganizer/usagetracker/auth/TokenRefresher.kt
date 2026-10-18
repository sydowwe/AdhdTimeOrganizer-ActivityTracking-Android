package com.timeorganizer.usagetracker.auth

import android.os.SystemClock
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
    /**
     * When the last attempt that may have rotated the token without us seeing the answer ended
     * ([SystemClock.elapsedRealtime], which keeps counting in deep sleep). Kept across calls: the wait applies to
     * whichever refresh sends the token next.
     */
    @Volatile
    private var uncertainAtMs: Long? = null

    /** Blocking; called from OkHttp's authenticator thread under the refresh lock. */
    fun refresh(refreshToken: String): RefreshDecision {
        var attempts = 0
        while (true) {
            val wait = RefreshPolicy.waitBeforeSendingMs(uncertainAtMs?.let { SystemClock.elapsedRealtime() - it })
            if (wait > 0) Thread.sleep(wait)

            val attempt = attemptOnce(refreshToken)
            uncertainAtMs = if (RefreshPolicy.leftTheTokenUncertain(attempt)) SystemClock.elapsedRealtime() else null
            when (val decision = RefreshPolicy.decide(attempt, attempts)) {
                RefreshDecision.RetryAfterGrace -> attempts++
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
            else RefreshAttempt.Ambiguous
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
