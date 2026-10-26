package com.timeorganizer.usagetracker.data.api

import com.timeorganizer.usagetracker.data.api.dto.AndroidSyncRequest
import com.timeorganizer.usagetracker.data.api.dto.AndroidSyncResponse
import com.timeorganizer.usagetracker.data.api.dto.AndroidTrackerRuleDto
import com.timeorganizer.usagetracker.data.api.dto.CreateTrackerRuleRequest
import com.timeorganizer.usagetracker.data.api.dto.CreateTrackerRuleResponse
import com.timeorganizer.usagetracker.data.api.dto.EveningMomentResponse
import com.timeorganizer.usagetracker.data.api.dto.IgnorePackageRequest
import com.timeorganizer.usagetracker.data.api.dto.IgnorePackageResponse
import com.timeorganizer.usagetracker.data.api.dto.UnmappedTodayResponse
import com.timeorganizer.usagetracker.data.api.dto.AppCategoryLinkDto
import com.timeorganizer.usagetracker.data.api.dto.AppCategoryLinkRequest
import com.timeorganizer.usagetracker.data.api.dto.CreateTrackerActivityRequest
import com.timeorganizer.usagetracker.data.api.dto.CreateTrackerActivityResponse
import com.timeorganizer.usagetracker.data.api.dto.DistractionResponse
import com.timeorganizer.usagetracker.data.api.dto.RepointTrackerRuleRequest
import com.timeorganizer.usagetracker.data.api.dto.SetDistractionRequest
import com.timeorganizer.usagetracker.data.api.dto.TrackerActivityOptionsResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/** Routes that need the access token. */
interface ApiService {

    @POST("tracker/android/sync")
    suspend fun syncSessions(
        @Body request: AndroidSyncRequest
    ): Response<AndroidSyncResponse>

    /**
     * Today's busiest packages no active rule maps; the evening review's whole content. [exclude] is the snoozed
     * packages (`review/SnoozeState`): the server leaves them out of the list and of its "worth asking" threshold.
     */
    @GET("tracker/android/unmapped")
    suspend fun unmappedToday(
        @Query("exclude") exclude: List<String> = emptyList()
    ): Response<UnmappedTodayResponse>

    /** The picker's one read: activities, roles, categories, the "Phone app" role and the app-category links. */
    @GET("tracker/android/activities")
    suspend fun activityOptions(): Response<TrackerActivityOptionsResponse>

    /** A new activity, optionally mapping a package. 409 `nameTaken` / `nameArchived` / `alreadyMapped`; 404: a role or category gone. */
    @POST("tracker/android/activities")
    suspend fun createActivity(
        @Body request: CreateTrackerActivityRequest
    ): Response<CreateTrackerActivityResponse>

    /** The activity's own distraction flag, nothing else. 404 `activityGone`. */
    @PATCH("tracker/android/activities/{id}/distraction")
    suspend fun setDistraction(
        @Path("id") activityId: Long,
        @Body request: SetDistractionRequest
    ): Response<DistractionResponse>

    /** Re-points an activity rule from now on. 204; 404 `ruleGone` / `activityGone`. */
    @PATCH("tracker/android/rules/{id}")
    suspend fun repointRule(
        @Path("id") ruleId: Long,
        @Body request: RepointTrackerRuleRequest
    ): Response<Unit>

    /** Unlink: switches an activity rule off. 204 whether or not it was still in force; an ignore rule is never touched. */
    @DELETE("tracker/android/rules/{id}")
    suspend fun unlinkRule(
        @Path("id") ruleId: Long
    ): Response<Unit>

    /** Links an app category to a role and/or category; both `null` deletes it (204). 404: a role or category gone. */
    @PUT("tracker/android/app-category-links/{appCategory}")
    suspend fun putAppCategoryLink(
        @Path("appCategory") appCategory: String,
        @Body request: AppCategoryLinkRequest
    ): Response<AppCategoryLinkDto>

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
