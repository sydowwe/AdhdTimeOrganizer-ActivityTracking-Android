package com.timeorganizer.usagetracker.data.api

import com.timeorganizer.usagetracker.data.api.dto.TrackerActivityDto
import com.timeorganizer.usagetracker.data.api.dto.AndroidSyncRequest
import com.timeorganizer.usagetracker.data.api.dto.AndroidSyncResponse
import com.timeorganizer.usagetracker.data.api.dto.AndroidTrackerRuleDto
import com.timeorganizer.usagetracker.data.api.dto.CreateTrackerRuleRequest
import com.timeorganizer.usagetracker.data.api.dto.CreateTrackerRuleResponse
import com.timeorganizer.usagetracker.data.api.dto.EveningMomentResponse
import com.timeorganizer.usagetracker.data.api.dto.IgnorePackageRequest
import com.timeorganizer.usagetracker.data.api.dto.IgnorePackageResponse
import com.timeorganizer.usagetracker.data.api.dto.UnmappedTodayResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

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

    /** Maps one package. 409: an active rule already covers it. 404: not the user's activity. */
    @POST("tracker/android/rules")
    suspend fun createRule(
        @Body request: CreateTrackerRuleRequest
    ): Response<CreateTrackerRuleResponse>

    /** Every active rule of the user. 304 when [etag] still matches: the cached list stands. */
    @GET("tracker/android/rules")
    suspend fun rules(
        @Header("If-None-Match") etag: String?
    ): Response<List<AndroidTrackerRuleDto>>

    /**
     * Ignores one package and deletes its stored sessions. Idempotent; any existing rule becomes the ignore rule. A 409
     * is a race on the server's unique index — send it again.
     */
    @PUT("tracker/android/ignored/{packageName}")
    suspend fun ignorePackage(
        @Path("packageName") packageName: String,
        @Body request: IgnorePackageRequest
    ): Response<IgnorePackageResponse>

    /** Switches the package's ignore rule off (204 whether or not there was one); a mapping is never touched. */
    @DELETE("tracker/android/ignored/{packageName}")
    suspend fun stopIgnoringPackage(
        @Path("packageName") packageName: String
    ): Response<Unit>

    /** When the user's evening begins (bed time less their evening-shutdown offset); the review runs then. */
    @GET("tracker/evening-shutdown-moment")
    suspend fun eveningMoment(): Response<EveningMomentResponse>
}
