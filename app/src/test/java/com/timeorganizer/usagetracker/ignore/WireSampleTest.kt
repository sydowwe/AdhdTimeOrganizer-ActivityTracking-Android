package com.timeorganizer.usagetracker.ignore

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.timeorganizer.usagetracker.data.api.dto.AndroidRuleTarget
import com.timeorganizer.usagetracker.data.api.dto.AndroidSessionDto
import com.timeorganizer.usagetracker.data.api.dto.AndroidSyncRequest
import com.timeorganizer.usagetracker.data.api.dto.AndroidSyncResponse
import com.timeorganizer.usagetracker.data.api.dto.AndroidTrackerRuleDto
import com.timeorganizer.usagetracker.data.api.dto.IgnorePackageRequest
import com.timeorganizer.usagetracker.data.api.dto.IgnorePackageResponse
import com.timeorganizer.usagetracker.data.api.dto.PreviousTargetDto
import com.timeorganizer.usagetracker.data.api.dto.AppCategoryLinkDto
import com.timeorganizer.usagetracker.data.api.dto.AppCategoryLinkRequest
import com.timeorganizer.usagetracker.data.api.dto.CreateTrackerActivityRequest
import com.timeorganizer.usagetracker.data.api.dto.CreateTrackerActivityResponse
import com.timeorganizer.usagetracker.data.api.dto.DistractionResponse
import com.timeorganizer.usagetracker.data.api.dto.RepointTrackerRuleRequest
import com.timeorganizer.usagetracker.data.api.dto.SetDistractionRequest
import com.timeorganizer.usagetracker.data.api.dto.StartBlockRequest
import com.timeorganizer.usagetracker.data.api.dto.TrackerActivityDto
import com.timeorganizer.usagetracker.data.api.dto.TrackerActivityOptionsResponse
import com.timeorganizer.usagetracker.data.api.dto.TrackerCategoryDto
import com.timeorganizer.usagetracker.data.api.dto.TrackerProblem
import com.timeorganizer.usagetracker.data.api.dto.TrackerProblemResponse
import com.timeorganizer.usagetracker.data.api.dto.TrackerRoleDto
import com.timeorganizer.usagetracker.mapping.AppCategory
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
                AndroidTrackerRuleDto("com.instagram.android", "Instagram", AndroidRuleTarget.IGNORED, null, ruleId = 7),
                AndroidTrackerRuleDto(
                    "com.reddit.frontpage", "Reddit", AndroidRuleTarget.ACTIVITY, "Scrolling", ruleId = 9, activityId = 31,
                    categoryName = "Social", categoryDistraction = true, isDistraction = true
                ),
                AndroidTrackerRuleDto(
                    "com.spotify.music", null, AndroidRuleTarget.ACTIVITY, "Reading", ruleId = 12, activityId = 30,
                    activityArchived = true, ownDistraction = false
                )
            ),
            rules
        )
        // And it means what the server meant: only the ignore rule's package is dropped.
        assertEquals(setOf("com.instagram.android"), IgnoreState(rules).serverIgnored)
    }

    /** A rules cache written before prompt 27 has none of the new fields: they read as "no id, not a distraction". */
    @Test
    fun aCachedRuleFromBeforePrompt27_readsWithDefaults() {
        val rule = gson.fromJson("""{"packageName":"a","appLabel":null,"target":"activity","targetName":"X"}""", AndroidTrackerRuleDto::class.java)

        assertEquals(0L, rule.ruleId)
        assertNull(rule.activityId)
        assertFalse(rule.isDistraction)
    }

    @Test
    fun theActivitiesSample_readsEveryPart() {
        val options = gson.fromJson(samples["activities"], TrackerActivityOptionsResponse::class.java)

        assertEquals(listOf(TrackerActivityDto(30, "Reading", 3, null), TrackerActivityDto(31, "Scrolling", 4, 8)), options.activities)
        assertEquals(listOf(TrackerRoleDto(3, "Hobby"), TrackerRoleDto(4, "Phone app")), options.roles)
        assertEquals(listOf(TrackerCategoryDto(8, "Social", true)), options.categories)
        assertEquals(4L, options.phoneAppRoleId)
        assertEquals(listOf(AppCategoryLinkDto("Social", 4, 8)), options.appCategoryLinks)
    }

    @Test
    fun theMappingAnswers_readEveryField() {
        assertEquals(
            CreateTrackerActivityResponse(32, 4, 13, 1800),
            gson.fromJson(samples["activityCreated"], CreateTrackerActivityResponse::class.java)
        )
        assertEquals(TrackerProblemResponse(TrackerProblem.NAME_TAKEN, 31), gson.fromJson(samples["problem"], TrackerProblemResponse::class.java))
        assertEquals(DistractionResponse(null, true), gson.fromJson(samples["distraction"], DistractionResponse::class.java))
        assertEquals(AppCategoryLinkDto("Game", null, 8), gson.fromJson(samples["appCategoryLink"], AppCategoryLinkDto::class.java))
    }

    /** "Follow the category" is the server's `null`, which Gson writes by leaving the field out — the same thing there. */
    @Test
    fun theMappingRequests_writeTheServersNames() {
        assertEquals(
            setOf("name", "roleId", "categoryId", "isDistraction", "mapPackage", "appLabel", "backfillDays"),
            gson.toJsonTree(CreateTrackerActivityRequest("Reddit", 4, 8, true, "com.reddit", "Reddit", 6)).asJsonObject.keySet()
        )
        assertFalse(gson.toJsonTree(SetDistractionRequest(null)).asJsonObject.has("isDistraction"))
        assertEquals(setOf("activityId"), gson.toJsonTree(RepointTrackerRuleRequest(31)).asJsonObject.keySet())
        assertEquals(setOf("roleId"), gson.toJsonTree(AppCategoryLinkRequest(4, null)).asJsonObject.keySet())
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

    /** The package is the route's; the body carries the label and the category, and leaves out whichever is unknown. */
    @Test
    fun theIgnoreRequest_writesOnlyTheLabelAndCategory() {
        val withLabel = gson.toJsonTree(IgnorePackageRequest("Instagram")).asJsonObject
        val without = gson.toJsonTree(IgnorePackageRequest(null)).asJsonObject

        assertEquals(setOf("appLabel"), withLabel.keySet())
        assertEquals("Instagram", withLabel["appLabel"].asString)
        assertFalse(without.has("appLabel"))
    }

    /**
     * The two requests that carry an app's category are written exactly as the samples spell them. The server reads a
     * renamed `appCategory` as "declares none" without a word, so every app would land at the bottom of the web's view.
     * A category the app doesn't declare is left out, which the server reads as the same "declares none".
     */
    @Test
    fun theCategoryRequests_writeTheSamplesExactly() {
        val sync = AndroidSyncRequest(
            sessions = listOf(
                AndroidSessionDto("com.whatsapp", "WhatsApp", "2026-10-23T07:00:00.000Z", "2026-10-23T07:10:00.000Z", 600, "Social"),
                AndroidSessionDto("com.android.chrome", "Chrome", "2026-10-23T07:20:00.000Z", "2026-10-23T07:25:00.000Z", 300, null)
            ),
            syncedUpToUtc = "2026-10-23T08:15:00.000Z",
            deviceId = "pixel-8"
        )

        assertEquals(samples["syncRequest"], gson.toJsonTree(sync))
        assertEquals(samples["ignoreRequest"], gson.toJsonTree(IgnorePackageRequest("Instagram", "Social")))
    }

    /** The start ramp's Start now (prompt 10). A renamed id binds as 0 there, and the route answers 409 for a block that exists. */
    @Test
    fun theStartBlockRequest_writesTheSampleExactly() {
        assertEquals(samples["startBlockRequest"], gson.toJsonTree(StartBlockRequest(7)))
    }

    /** The phone's names are the server's enum members, all nine. A tenth Android constant reads as no category. */
    @Test
    fun theCategoryNames_areTheServersMembers() {
        assertEquals(
            listOf("Game", "Audio", "Video", "Image", "Social", "News", "Maps", "Productivity", "Accessibility"),
            AppCategory.NAMES
        )
        assertNull(AppCategory.of(9))
        assertNull(AppCategory.of(-1))
    }
}
