package com.timeorganizer.usagetracker.ignore

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.timeorganizer.usagetracker.data.api.dto.AndroidRuleTarget
import com.timeorganizer.usagetracker.data.api.dto.AndroidSyncResponse
import com.timeorganizer.usagetracker.data.api.dto.AndroidTrackerRuleDto
import com.timeorganizer.usagetracker.data.api.dto.IgnorePackageRequest
import com.timeorganizer.usagetracker.data.api.dto.IgnorePackageResponse
import com.timeorganizer.usagetracker.data.api.dto.PreviousTargetDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The phone half of the wire contract: `src/test/resources/AndroidTrackerWire.samples.json` is the server's
 * `AdhdTimeOrganizer.Tracking/application/dto/response/android/AndroidTrackerWire.samples.json`, copied unchanged — the
 * server's `AndroidTrackerWireSampleTests` checks its live routes against it and that this copy is identical. Here the
 * same file goes through the app's DTOs with the app's Gson. A renamed field reads as a default, silently: the phone
 * would think nothing is ignored and upload everything. Change the file there, copy it here, follow it in the DTOs.
 */
class WireSampleTest {

    private val gson = Gson()

    private val samples: JsonObject by lazy {
        val stream = checkNotNull(javaClass.classLoader?.getResourceAsStream("AndroidTrackerWire.samples.json")) {
            "AndroidTrackerWire.samples.json is missing from src/test/resources"
        }
        stream.reader(Charsets.UTF_8).use { JsonParser.parseReader(it).asJsonObject }
    }

    @Test
    fun theSyncSample_readsEveryCounter() {
        val sync = gson.fromJson(samples["sync"], AndroidSyncResponse::class.java)

        assertEquals(3, sync.accepted)
        assertEquals(1, sync.duplicatesSkipped)
        assertEquals(0, sync.dropped)
        assertEquals(2, sync.ignored)
        assertEquals("2026-10-23T08:15:00.0000000Z", sync.syncedUpToUtc)
    }

    @Test
    fun theRulesSample_readsEveryRule_andItsTargetSpelling() {
        val type = object : TypeToken<List<AndroidTrackerRuleDto>>() {}.type
        val rules: List<AndroidTrackerRuleDto> = gson.fromJson(samples["rules"], type)

        assertEquals(
            listOf(
                AndroidTrackerRuleDto("com.instagram.android", "Instagram", AndroidRuleTarget.IGNORED, null),
                AndroidTrackerRuleDto("com.reddit.frontpage", "Reddit", AndroidRuleTarget.ROLE_CATEGORY, "Leisure · Social"),
                AndroidTrackerRuleDto("com.spotify.music", null, AndroidRuleTarget.ACTIVITY, "Reading")
            ),
            rules
        )
        // And it means what the server meant: only the ignore rule's package is dropped.
        assertEquals(setOf("com.instagram.android"), IgnoreState(rules).serverIgnored)
    }

    @Test
    fun theIgnoredSample_readsThePurgeAndWhatItReplaced() {
        val ignored = gson.fromJson(samples["ignored"], IgnorePackageResponse::class.java)

        assertEquals(41L, ignored.ruleId)
        assertEquals(12, ignored.purgedSessions)
        assertEquals(PreviousTargetDto(AndroidRuleTarget.ACTIVITY, "Thesis"), ignored.previousTarget)
    }

    @Test
    fun anIgnoreWithNothingReplaced_readsAsNoPreviousTarget() {
        val ignored = gson.fromJson("""{"ruleId":3,"purgedSessions":0,"previousTarget":null}""", IgnorePackageResponse::class.java)

        assertNull(ignored.previousTarget)
    }

    /** The package is the route's; the body carries only the label, and none at all when there is no label. */
    @Test
    fun theIgnoreRequest_writesOnlyTheLabel() {
        val withLabel = gson.toJsonTree(IgnorePackageRequest("Instagram")).asJsonObject
        val without = gson.toJsonTree(IgnorePackageRequest(null)).asJsonObject

        assertEquals(setOf("appLabel"), withLabel.keySet())
        assertEquals("Instagram", withLabel["appLabel"].asString)
        assertFalse(without.has("appLabel"))
    }
}
