package com.timeorganizer.usagetracker.now

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.timeorganizer.usagetracker.data.api.dto.TrackerNowResponse
import com.timeorganizer.usagetracker.review.ReviewText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `/now` through the app's DTOs and [NowFacts], from the `now` entry of the shared wire sample (the server's
 * `AndroidTrackerWireSampleTests` checks its live route against the same file). A renamed field reads as absent: a
 * desk that is never idle, a block that is never planned, and no break would ever start.
 */
class NowFactsTest {

    private val gson = Gson()

    private val samples: JsonObject by lazy {
        val stream = checkNotNull(javaClass.classLoader?.getResourceAsStream("AndroidTrackerWire.samples.json")) {
            "AndroidTrackerWire.samples.json is missing from src/test/resources"
        }
        stream.reader(Charsets.UTF_8).use { JsonParser.parseReader(it).asJsonObject }
    }

    private fun parse(json: String) = NowFacts.from(gson.fromJson(json, TrackerNowResponse::class.java))

    @Test
    fun theNowSample_readsEveryPartTheNudgesUse() {
        val facts = NowFacts.from(gson.fromJson(samples["now"], TrackerNowResponse::class.java))

        assertFalse(facts.silent)
        assertEquals(
            listOf(NowBlock(7, "Thesis", ms("2026-10-18T09:00:00Z"), ms("2026-10-18T11:30:00Z"), inProgress = true, activityId = 12)),
            facts.current
        )
        assertEquals(
            NowBlock(8, "Email", ms("2026-10-18T11:30:00Z"), ms("2026-10-18T12:00:00Z"), inProgress = false, activityId = 15),
            facts.next
        )
        assertEquals(ms("2026-10-18T10:04:00Z"), facts.deskLastActiveMs)
        assertTrue(facts.deskIdle)
        assertEquals(DeskWork("Thesis", onPlan = true), facts.deskWork)
        assertEquals(setOf("com.instagram.android", "com.reddit.frontpage"), facts.distractionPackages)
    }

    @Test
    fun aBlockWithoutAnActivity_hasNone_andAnUnreadableNextIsNoNext() {
        val facts = parse(
            """{"plan":{"current":[{"plannerTaskId":1,"activityName":"X","startsAt":"2026-10-18T09:00:00Z","endsAt":"2026-10-18T10:00:00Z","inProgress":false}],
              "next":{"plannerTaskId":2,"activityId":3,"activityName":"Y","startsAt":"later","endsAt":null,"inProgress":false}}}"""
        )

        assertNull("a missing id reads as 0 through Gson, and 0 is no activity", facts.current.single().activityId)
        assertNull(facts.next)
    }

    @Test
    fun anySilentReason_isSilence_oneThePhoneDoesNotKnowIncluded() {
        assertTrue(parse("""{"silentReason":"QuietHours"}""").silent)
        assertTrue(parse("""{"silentReason":"SomethingNew"}""").silent)
    }

    @Test
    fun ignoredAndUnmappedDeskTime_isNoWork_theBusiestNamedSliceIs() {
        val facts = parse(
            """{"desktop":{"lastActiveAt":"2026-10-18T10:04:00Z","isIdle":true,"recent":[
              {"target":"Ignored","name":null,"activeSeconds":200,"onPlan":null},
              {"target":null,"name":null,"activeSeconds":100,"onPlan":null},
              {"target":"RoleOrCategory","name":"Leisure · Video","activeSeconds":50,"onPlan":false}]}}"""
        )

        assertEquals(DeskWork("Leisure · Video", onPlan = false), facts.deskWork)
    }

    @Test
    fun whatCannotBeRead_isAbsent_neverAGuess() {
        val facts = parse("""{"plan":{"current":[{"plannerTaskId":1,"activityId":2,"activityName":"X","startsAt":"soon","endsAt":null,"inProgress":false}]}}""")

        assertTrue("an unreadable block is no plan", facts.current.isEmpty())
        assertNull(facts.deskLastActiveMs)
        assertTrue("no desk part is an idle desk with nothing to come back to", facts.deskIdle)
        assertNull(facts.deskWork)
    }

    private fun ms(instant: String) = ReviewText.instantMs(instant)!!
}
