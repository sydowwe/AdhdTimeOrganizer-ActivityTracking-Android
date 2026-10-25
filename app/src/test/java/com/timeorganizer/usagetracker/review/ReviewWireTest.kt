package com.timeorganizer.usagetracker.review

import com.google.gson.Gson
import com.timeorganizer.usagetracker.data.api.dto.TrackerActivityDto
import com.timeorganizer.usagetracker.data.api.dto.CreateTrackerRuleRequest
import com.timeorganizer.usagetracker.data.api.dto.CreateTrackerRuleResponse
import com.timeorganizer.usagetracker.data.api.dto.EveningMomentResponse
import com.timeorganizer.usagetracker.data.api.dto.UnmappedTodayResponse
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The review's wire shapes, as the server writes them (System.Text.Json, camelCase, the records of
 * `AdhdTimeOrganizer.Tracking/application/dto/response/android/AndroidTracker*Response.cs` and the host's
 * `EveningShutdownMomentResponse`). A renamed field reads as a default here and nothing complains: the review would
 * show no apps, or save no rule, with a 200 on the wire. The server's `AndroidTracker*Tests` deserialize the same records.
 */
class ReviewWireTest {

    private val gson = Gson()

    @Test
    fun unmappedToday_readsEveryField() {
        val json = """
            {"date":"2026-10-19","trackedSeconds":8040,"unmappedSeconds":5640,"apps":[
              {"packageName":"com.reddit.frontpage","appLabel":"Reddit","seconds":3000,"suggestedActivityId":42,"suggestedActivityName":"Language"},
              {"packageName":"com.android.chrome","appLabel":"Chrome","seconds":1800,"suggestedActivityId":null,"suggestedActivityName":null}
            ]}
        """.trimIndent()

        val today = gson.fromJson(json, UnmappedTodayResponse::class.java)

        assertEquals("2026-10-19", today.date)
        assertEquals(8040L, today.trackedSeconds)
        assertEquals(5640L, today.unmappedSeconds)
        val (reddit, chrome) = today.apps!!
        assertEquals("com.reddit.frontpage", reddit.packageName)
        assertEquals("Reddit", reddit.appLabel)
        assertEquals(3000L, reddit.seconds)
        assertEquals(42L, reddit.suggestedActivityId)
        assertEquals("Language", reddit.suggestedActivityName)
        assertNull(chrome.suggestedActivityId)
        assertNull(chrome.suggestedActivityName)
    }

    /** The server's "don't ask". */
    @Test
    fun unmappedToday_withNoApps_isEmpty() {
        val today = gson.fromJson("""{"date":"2026-10-19","trackedSeconds":0,"unmappedSeconds":0,"apps":[]}""", UnmappedTodayResponse::class.java)

        assertTrue(today.apps!!.isEmpty())
    }

    @Test
    fun activities_readIdAndName() {
        val type = object : TypeToken<List<TrackerActivityDto>>() {}.type

        val list: List<TrackerActivityDto> = gson.fromJson("""[{"id":7,"name":"Writing"},{"id":9,"name":"Thesis"}]""", type)

        assertEquals(listOf(TrackerActivityDto(7, "Writing"), TrackerActivityDto(9, "Thesis")), list)
    }

    @Test
    fun ruleResponse_readsTheRuleAndWhatItAdded() {
        val response = gson.fromJson("""{"ruleId":15,"attributedSeconds":3000}""", CreateTrackerRuleResponse::class.java)

        assertEquals(15L, response.ruleId)
        assertEquals(3000, response.attributedSeconds)
    }

    /** The review only maps through this route; it ignores through `PUT ignored` (`ignore/IgnoreSync`), so no `isIgnored`. */
    @Test
    fun ruleRequest_writesTheNamesTheServerBinds() {
        val map = gson.toJsonTree(CreateTrackerRuleRequest("com.reddit.frontpage", activityId = 42)).asJsonObject

        assertEquals(setOf("packageName", "activityId"), map.keySet())
        assertEquals("com.reddit.frontpage", map["packageName"].asString)
        assertEquals(42L, map["activityId"].asLong)
        assertFalse(map.has("isIgnored"))
    }

    @Test
    fun eveningMoment_readsTheInstantAsUtc() {
        val moment = gson.fromJson("""{"enabled":false,"momentUtc":"2026-10-19T21:00:00Z"}""", EveningMomentResponse::class.java)

        assertFalse(moment.enabled)
        assertEquals(1_792_443_600_000L, ReviewText.instantMs(moment.momentUtc))
    }
}
