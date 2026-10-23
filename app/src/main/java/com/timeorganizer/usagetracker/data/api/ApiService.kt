package com.timeorganizer.usagetracker.data.api

import com.timeorganizer.usagetracker.data.api.dto.TrackerActivityDto
import com.timeorganizer.usagetracker.data.api.dto.AndroidSyncRequest
import com.timeorganizer.usagetracker.data.api.dto.AndroidSyncResponse
import com.timeorganizer.usagetracker.data.api.dto.CreateTrackerRuleRequest
import com.timeorganizer.usagetracker.data.api.dto.CreateTrackerRuleResponse
import com.timeorganizer.usagetracker.data.api.dto.EveningMomentResponse
import com.timeorganizer.usagetracker.data.api.dto.UnmappedTodayResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

/** Routes that need the access token. */
interface ApiService {

    @POST("tracker/android/sync")
    suspend fun syncSessions(
        @Body request: AndroidSyncRequest
    ): Response<AndroidSyncResponse>

    /** Today's busiest packages no active rule maps; the evening review's whole content. */
    @GET("tracker/android/unmapped")
    suspend fun unmappedToday(): Response<UnmappedTodayResponse>

    @GET("tracker/android/activities")
    suspend fun activities(): Response<List<TrackerActivityDto>>

    /** Maps or ignores one package. 409: an active rule already covers it. 404: not the user's activity. */
    @POST("tracker/android/rules")
    suspend fun createRule(
        @Body request: CreateTrackerRuleRequest
    ): Response<CreateTrackerRuleResponse>

    /** When the user's evening begins (bed time less their evening-shutdown offset); the review runs then. */
    @GET("tracker/evening-shutdown-moment")
    suspend fun eveningMoment(): Response<EveningMomentResponse>
}
