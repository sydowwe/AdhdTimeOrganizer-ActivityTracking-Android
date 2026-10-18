package com.timeorganizer.usagetracker.data.api

import com.timeorganizer.usagetracker.data.api.dto.AndroidSyncRequest
import com.timeorganizer.usagetracker.data.api.dto.AndroidSyncResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

interface ApiService {

    @POST("activity-tracking/android/sync")
    suspend fun syncSessions(
        @Body request: AndroidSyncRequest
    ): Response<AndroidSyncResponse>

    @GET("health")
    suspend fun healthCheck(): Response<Unit>
}
