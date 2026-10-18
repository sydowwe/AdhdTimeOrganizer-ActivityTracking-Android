package com.timeorganizer.usagetracker.data.api

import com.timeorganizer.usagetracker.data.api.dto.AndroidSyncRequest
import com.timeorganizer.usagetracker.data.api.dto.AndroidSyncResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/** Routes that need the access token. */
interface ApiService {

    @POST("activity-tracking/android/sync")
    suspend fun syncSessions(
        @Body request: AndroidSyncRequest
    ): Response<AndroidSyncResponse>
}
