package com.timeorganizer.usagetracker.data.repository

import android.content.Context
import com.timeorganizer.usagetracker.data.api.RetrofitClient
import com.timeorganizer.usagetracker.data.api.dto.AndroidSyncRequest
import com.timeorganizer.usagetracker.data.api.dto.AndroidSyncResponse
import com.timeorganizer.usagetracker.data.local.SyncPreferences
import com.timeorganizer.usagetracker.usagestats.UsageStatsCollector
import com.timeorganizer.usagetracker.util.Constants
import retrofit2.Response
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class UsageRepository(context: Context) {

    private val collector = UsageStatsCollector(context)
    private val prefs = SyncPreferences(context)
    private val api = RetrofitClient.apiService
    private val isoFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
        .withZone(ZoneOffset.UTC)

    /**
     * Collects sessions from [lastSyncedTimestampMillis] to now, posts them in batches,
     * and returns the response from the final successful call.
     */
    suspend fun syncNow(): SyncOutcome {
        val fromMs = prefs.lastSyncedTimestampMillis
        val toMs = System.currentTimeMillis()

        val (sessions, latestEventTs) = collector.collectSessions(fromMs, toMs)

        if (sessions.isEmpty()) {
            // Nothing to report — advance the timestamp so we don't re-query the same window
            if (latestEventTs > fromMs) prefs.lastSyncedTimestampMillis = latestEventTs
            return SyncOutcome.NoData
        }

        val syncedUpToUtc = isoFormatter.format(Instant.ofEpochMilli(latestEventTs))
        val deviceId = prefs.deviceId

        // Chunk into batches of BATCH_SIZE to guard against very large payloads
        val batches = sessions.chunked(Constants.BATCH_SIZE)
        var lastResponse: Response<AndroidSyncResponse>? = null

        for (batch in batches) {
            val request = AndroidSyncRequest(
                sessions = batch,
                syncedUpToUtc = syncedUpToUtc,
                deviceId = deviceId
            )
            val response = api.syncSessions(request)
            lastResponse = response
            when {
                response.isSuccessful -> Unit // continue
                response.code() in 400..499 -> return SyncOutcome.ClientError(response.code())
                else -> return SyncOutcome.ServerError(response.code())
            }
        }

        val body = lastResponse?.body()
        if (body != null) {
            prefs.lastSyncedTimestampMillis = latestEventTs
        }
        return SyncOutcome.Success(body?.accepted ?: sessions.size, body?.duplicatesSkipped ?: 0)
    }
}

sealed class SyncOutcome {
    data object NoData : SyncOutcome()
    data class Success(val accepted: Int, val duplicatesSkipped: Int) : SyncOutcome()
    data class ClientError(val code: Int) : SyncOutcome()
    data class ServerError(val code: Int) : SyncOutcome()
}
