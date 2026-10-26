package com.timeorganizer.usagetracker.mapping

import com.timeorganizer.usagetracker.data.api.dto.TrackerProblem
import org.junit.Assert.assertEquals
import org.junit.Test

class MappingOutcomeTest {

    @Test
    fun success_isSaved() {
        assertEquals(MappingOutcome.Saved, MappingOutcome.of(200, null))
        assertEquals(MappingOutcome.Saved, MappingOutcome.of(204, null))
    }

    /** Both 409s: the reason tells "use the existing activity" from "another device mapped the app". */
    @Test
    fun conflicts_followTheReason() {
        assertEquals(MappingOutcome.NameTaken(31), MappingOutcome.of(409, TrackerProblem.NAME_TAKEN, 31))
        assertEquals(MappingOutcome.NameArchived, MappingOutcome.of(409, TrackerProblem.NAME_ARCHIVED, 31))
        assertEquals(MappingOutcome.AlreadyMapped, MappingOutcome.of(409, TrackerProblem.ALREADY_MAPPED))
        // POST rules' 409 carries no reason: it can only be the package.
        assertEquals(MappingOutcome.AlreadyMapped, MappingOutcome.of(409, null))
    }

    @Test
    fun notFound_isTheRuleOrWhatItPointsAt() {
        assertEquals(MappingOutcome.RuleGone, MappingOutcome.of(404, TrackerProblem.RULE_GONE))
        assertEquals(MappingOutcome.Gone, MappingOutcome.of(404, TrackerProblem.ACTIVITY_GONE))
        // The ownership guard's 404 for a role or category names no reason.
        assertEquals(MappingOutcome.Gone, MappingOutcome.of(404, null))
    }

    @Test
    fun aPayloadComplaint_isRefused_andEverythingElseWaits() {
        assertEquals(MappingOutcome.Refused, MappingOutcome.of(400, null))
        assertEquals(MappingOutcome.Refused, MappingOutcome.of(422, null))
        listOf(401, 403, 429, 500, 503).forEach { assertEquals(MappingOutcome.TryAgain, MappingOutcome.of(it, null)) }
        assertEquals(MappingOutcome.TryAgain, MappingOutcome.of(null, null))
    }
}
