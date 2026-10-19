package com.timeorganizer.usagetracker.data.api

import com.timeorganizer.usagetracker.data.api.dto.AgentActivityDto
import com.timeorganizer.usagetracker.data.api.dto.AndroidSyncRequest
import com.timeorganizer.usagetracker.data.api.dto.AndroidSyncResponse
import com.timeorganizer.usagetracker.data.api.dto.CreateAgentRuleRequest
import com.timeorganizer.usagetracker.data.api.dto.CreateAgentRuleResponse
import com.timeorganizer.usagetracker.data.api.dto.EveningMomentResponse
import com.timeorganizer.usagetracker.data.api.dto.UnmappedTodayResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

/** Routes that need the access token. */
interface ApiService {

    @POST("activity-tracking/android/sync")
    suspend fun syncSessions(
        @Body request: AndroidSyncRequest
    ): Response<AndroidSyncResponse>

    /** Today's busiest packages no active rule maps; the evening review's whole content. */
    @GET("activity-tracking/android/agent-unmapped")
    suspend fun unmappedToday(): Response<UnmappedTodayResponse>

    @GET("activity-tracking/android/agent-activities")
    suspend fun activities(): Response<List<AgentActivityDto>>

    /** Maps or ignores one package. 409: an active rule already covers it. 404: not the user's activity. */
    @POST("activity-tracking/android/agent-rules")
    suspend fun createRule(
        @Body request: CreateAgentRuleRequest
    ): Response<CreateAgentRuleResponse>

    /** When the user's evening begins (bed time less their evening-shutdown offset); the review runs then. */
    @GET("user/evening-shutdown-moment")
    suspend fun eveningMoment(): Response<EveningMomentResponse>
}
