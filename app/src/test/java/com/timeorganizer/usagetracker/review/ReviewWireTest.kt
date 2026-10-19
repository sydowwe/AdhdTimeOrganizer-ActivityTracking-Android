package com.timeorganizer.usagetracker.review

import com.google.gson.Gson
import com.timeorganizer.usagetracker.data.api.dto.AgentActivityDto
import com.timeorganizer.usagetracker.data.api.dto.CreateAgentRuleRequest
import com.timeorganizer.usagetracker.data.api.dto.CreateAgentRuleResponse
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
 * `AdhdTimeOrganizer.Tracking/application/dto/response/android/AndroidAgent*Response.cs` and the host's
 * `EveningShutdownMomentResponse`). A renamed field reads as a default here and nothing complains: the review would
 * show no apps, or save no rule, with a 200 on the wire. The server's `AndroidAgent*Tests` deserialize the same records.
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
        val type = object : TypeToken<List<AgentActivityDto>>() {}.type

        val list: List<AgentActivityDto> = gson.fromJson("""[{"id":7,"name":"Writing"},{"id":9,"name":"Thesis"}]""", type)

        assertEquals(listOf(AgentActivityDto(7, "Writing"), AgentActivityDto(9, "Thesis")), list)
    }

    @Test
    fun ruleResponse_readsTheRuleAndWhatItAdded() {
        val response = gson.fromJson("""{"ruleId":15,"attributedSeconds":3000}""", CreateAgentRuleResponse::class.java)

        assertEquals(15L, response.ruleId)
        assertEquals(3000, response.attributedSeconds)
    }

    /** Exactly one of activityId / isIgnored goes out; the absent one must not be sent as null-and-set. */
    @Test
    fun ruleRequest_writesTheNamesTheServerBinds() {
        val map = gson.toJsonTree(CreateAgentRuleRequest("com.reddit.frontpage", activityId = 42)).asJsonObject
        val ignore = gson.toJsonTree(CreateAgentRuleRequest("com.reddit.frontpage", isIgnored = true)).asJsonObject

        assertEquals("com.reddit.frontpage", map["packageName"].asString)
        assertEquals(42L, map["activityId"].asLong)
        assertFalse(map.has("isIgnored"))
        assertTrue(ignore["isIgnored"].asBoolean)
        assertFalse(ignore.has("activityId"))
    }

    @Test
    fun eveningMoment_readsTheInstantAsUtc() {
        val moment = gson.fromJson("""{"enabled":false,"momentUtc":"2026-10-19T21:00:00Z"}""", EveningMomentResponse::class.java)

        assertFalse(moment.enabled)
        assertEquals(1_792_443_600_000L, ReviewText.instantMs(moment.momentUtc))
    }
}
