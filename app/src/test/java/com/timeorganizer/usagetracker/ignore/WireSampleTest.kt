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
import com.timeorganizer.usagetracker.budget.BudgetFacts
import com.timeorganizer.usagetracker.budget.BudgetStage
import com.timeorganizer.usagetracker.budget.PhoneBudget
import com.timeorganizer.usagetracker.data.api.dto.BudgetClaimRequest
import com.timeorganizer.usagetracker.data.api.dto.BudgetClaimResponse
import com.timeorganizer.usagetracker.data.api.dto.BudgetsResponse
import com.timeorganizer.usagetracker.data.api.dto.CaptureRequest
import com.timeorganizer.usagetracker.data.api.dto.CaptureResponse
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
import com.timeorganizer.usagetracker.data.local.outbox.PendingDerailmentEntity
import com.timeorganizer.usagetracker.data.local.outbox.PendingInterruptionEntity
import com.timeorganizer.usagetracker.data.local.outbox.PendingPickupEntity
import com.timeorganizer.usagetracker.loops.LoopAnswer
import com.timeorganizer.usagetracker.loops.LoopRule
import com.timeorganizer.usagetracker.loops.PickupLoopEntity
import com.timeorganizer.usagetracker.mapping.AppCategory
import com.timeorganizer.usagetracker.sync.SyncWire
import com.timeorganizer.usagetracker.usagestats.DerailmentEnd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

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
        // Prompt 14's acknowledgement: a renamed counter reads as null, and every pickup would stay unsent forever.
        assertEquals(2, sync.pickupsStored)
        assertEquals(0, sync.pickupsDropped)
        assertEquals(1, sync.loopsStored)
        assertEquals(0, sync.loopsDropped)
        // Prompt 15's, likewise for the interruptions and derailments.
        assertEquals(3, sync.interruptionsStored)
        assertEquals(0, sync.interruptionsDropped)
        assertEquals(1, sync.derailmentsStored)
        assertEquals(0, sync.derailmentsDropped)
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
     * A category the app doesn't declare is left out, which the server reads as the same "declares none". The sync's
     * pickups and loops (prompt 14) are written from stored rows through `SyncWire`, the sync's own mapping, so a renamed
     * field or a wrong rule / answer name fails here.
     */
    @Test
    fun theCategoryRequests_writeTheSamplesExactly() {
        val at = { iso: String -> Instant.parse(iso).toEpochMilli() }
        val sync = AndroidSyncRequest(
            sessions = listOf(
                AndroidSessionDto("com.whatsapp", "WhatsApp", "2026-10-23T07:00:00.000Z", "2026-10-23T07:10:00.000Z", 600, "Social"),
                AndroidSessionDto("com.android.chrome", "Chrome", "2026-10-23T07:20:00.000Z", "2026-10-23T07:25:00.000Z", 300, null)
            ),
            syncedUpToUtc = "2026-10-23T08:15:00.000Z",
            deviceId = "pixel-8",
            pickups = listOf(
                SyncWire.pickup(PendingPickupEntity(startMs = at("2026-10-23T06:12:00Z"), endMs = at("2026-10-23T06:12:20Z"), unlocked = true)),
                SyncWire.pickup(PendingPickupEntity(startMs = at("2026-10-23T06:30:00Z"), endMs = at("2026-10-23T06:30:05Z"), unlocked = false))
            ),
            loops = listOfNotNull(
                SyncWire.loop(
                    PickupLoopEntity(
                        startMs = at("2026-10-22T21:05:00Z"), endMs = at("2026-10-22T21:20:00Z"),
                        rule = LoopRule.PICKUPS.name, pickups = 7, apps = 3, answer = LoopAnswer.NOT_TODAY.name
                    )
                )
            ),
            interruptions = listOf(
                SyncWire.interruption(PendingInterruptionEntity(packageName = "com.whatsapp", atMs = at("2026-10-23T07:02:00Z"))),
                SyncWire.interruption(PendingInterruptionEntity(packageName = "com.slack", atMs = at("2026-10-23T07:40:00Z")))
            ),
            derailments = listOfNotNull(
                SyncWire.derailment(
                    PendingDerailmentEntity(
                        packageName = "com.whatsapp", notifiedMs = at("2026-10-23T07:02:00Z"), startMs = at("2026-10-23T07:02:30Z"),
                        endMs = at("2026-10-23T07:36:30Z"), endedBy = DerailmentEnd.RETURNED.name
                    )
                )
            )
        )

        assertEquals(samples["syncRequest"], gson.toJsonTree(sync))
        assertEquals(samples["ignoreRequest"], gson.toJsonTree(IgnorePackageRequest("Instagram", "Social")))
    }

    /** The start ramp's Start now (prompt 10). A renamed id binds as 0 there, and the route answers 409 for a block that exists. */
    @Test
    fun theStartBlockRequest_writesTheSampleExactly() {
        assertEquals(samples["startBlockRequest"], gson.toJsonTree(StartBlockRequest(7)))
    }

    /**
     * Quick capture (prompt 18), both ways. A renamed `text` is a 400 the queue keeps retrying; a renamed `url` drops the
     * shared link silently. The answer is only checked for its status today, but its fields are pinned for whoever reads it.
     */
    @Test
    fun theCapture_writesTheRequestAndReadsTheAnswer() {
        assertEquals(samples["captureRequest"], gson.toJsonTree(CaptureRequest("Read this later", "https://example.com/article")))
        assertEquals(CaptureResponse("added", 41, 3), gson.fromJson(samples["captured"], CaptureResponse::class.java))
    }

    /** No link: Gson leaves `url` out, which the server binds as null. */
    @Test
    fun aCaptureWithoutALink_leavesTheUrlOut() {
        assertEquals(setOf("text"), gson.toJsonTree(CaptureRequest("Email the landlord")).asJsonObject.keySet())
    }

    /**
     * Daily limits (prompt 13), both ways. A renamed `thisPhoneSeconds` reads as 0 and the phone counts its own synced time
     * twice — a limit said early; a renamed `packages` is a budget no app counts toward, said by nobody on the phone.
     */
    @Test
    fun theBudgets_readEveryField_andTheClaimWritesTheSample() {
        val facts = BudgetFacts.from(gson.fromJson(samples["budgets"], BudgetsResponse::class.java), fetchedAtMs = 1L)!!

        assertEquals("2026-10-28", facts.day)
        assertEquals(Instant.parse("2026-10-27T23:00:00Z").toEpochMilli(), facts.dayStartMs)
        assertEquals(Instant.parse("2026-10-28T23:00:00Z").toEpochMilli(), facts.dayEndMs)
        assertEquals(
            listOf(
                PhoneBudget(5, "Scrolling", 3_600_000, true, true, false, 3_000_000, 2_400_000, setOf("com.reddit.frontpage")),
                PhoneBudget(6, "Social", 5_400_000, false, false, false, 3_000_000, 2_400_000, setOf("com.instagram.lite", "com.reddit.frontpage"))
            ),
            facts.budgets
        )

        assertEquals(samples["budgetClaimRequest"], gson.toJsonTree(BudgetClaimRequest(BudgetStage.REACHED.wire, "2026-10-28")))
        assertEquals(BudgetClaimResponse(true), gson.fromJson(samples["budgetClaimed"], BudgetClaimResponse::class.java))
        assertEquals("approaching", BudgetStage.APPROACHING.wire)
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
