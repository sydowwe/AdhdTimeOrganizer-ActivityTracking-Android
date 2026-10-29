package com.timeorganizer.usagetracker.sync

import com.timeorganizer.usagetracker.data.api.dto.AndroidSyncResponse
import com.timeorganizer.usagetracker.data.local.outbox.PendingDerailmentEntity
import com.timeorganizer.usagetracker.data.local.outbox.PendingInterruptionEntity
import com.timeorganizer.usagetracker.data.local.outbox.PendingPickupEntity
import com.timeorganizer.usagetracker.loops.LoopAnswer
import com.timeorganizer.usagetracker.loops.LoopRule
import com.timeorganizer.usagetracker.loops.PickupLoopEntity
import com.timeorganizer.usagetracker.usagestats.DerailmentEnd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncWireTest {

    private val pickup = SyncExtra.Pickup(PendingPickupEntity(id = 1, startMs = 0, endMs = 20_000, unlocked = true))
    private val loop = SyncExtra.Loop(
        PickupLoopEntity(id = 2, startMs = 0, endMs = 900_000, rule = LoopRule.HOPPING.name, pickups = 2, apps = 6, answer = LoopAnswer.FINE.name)
    )

    private fun answer(pickupsStored: Int?, loopsStored: Int?) =
        AndroidSyncResponse(0, 0, 0, 0, pickupsStored, 0, loopsStored, 0, null)

    @Test
    fun anOlderServersAnswer_acknowledgesNothing() {
        // Before prompt 14 the counters don't exist: the server ignored the pickups and loops.
        assertFalse(SyncWire.acknowledged(answer(null, null), listOf(pickup, loop)))
        assertFalse(SyncWire.acknowledged(null, listOf(pickup)))
    }

    @Test
    fun theCountersPresent_acknowledgeTheBatch_evenAtZero() {
        // Zero stored is still an answer: every row was dropped as unusable, and resending it would be refused again.
        assertTrue(SyncWire.acknowledged(answer(0, 0), listOf(pickup, loop)))
        assertTrue(SyncWire.acknowledged(answer(1, null), listOf(pickup)))
        assertFalse(SyncWire.acknowledged(answer(1, null), listOf(pickup, loop)))
    }

    @Test
    fun everyRuleAndAnswer_hasTheServersMemberName() {
        assertEquals(listOf("Pickups", "Hopping"), LoopRule.entries.map { it.wire })
        assertEquals(listOf("Asked", "Away", "Fine", "NotToday", "Silenced"), LoopAnswer.entries.map { it.wire })
    }

    @Test
    fun aLoopStoredUnderANameThisBuildDoesntKnow_isNotWritten() {
        assertNull(SyncWire.loop(loop.row.copy(rule = "SPIRALLING")))
        assertNull(SyncWire.loop(loop.row.copy(answer = "MAYBE")))
    }

    private val interruption = SyncExtra.Interruption(PendingInterruptionEntity(id = 3, packageName = "com.whatsapp", atMs = 1_000))
    private val derailment = SyncExtra.Derailment(
        PendingDerailmentEntity(
            id = 4, packageName = "com.whatsapp", notifiedMs = 1_000, startMs = 20_000, endMs = 600_000,
            endedBy = DerailmentEnd.SCREEN_OFF.name
        )
    )

    @Test
    fun aServerFrom14_acknowledgesPickups_butNotNotifications() {
        // Prompt 15: the counters are per kind, so the sync sends notifications in requests of their own and a 14 server
        // keeps taking pickups.
        val from14 = answer(1, 1)
        assertTrue(SyncWire.acknowledged(from14, listOf(pickup, loop)))
        assertFalse(SyncWire.acknowledged(from14, listOf(interruption)))
        assertFalse(SyncWire.acknowledged(from14, listOf(interruption, derailment)))

        val from15 = from14.copy(interruptionsStored = 0, derailmentsStored = 1)
        assertTrue(SyncWire.acknowledged(from15, listOf(interruption, derailment)))
        assertFalse(SyncWire.acknowledged(from14.copy(interruptionsStored = 1), listOf(interruption, derailment)))
    }

    @Test
    fun everyDerailmentEnd_hasTheServersMemberName() {
        assertEquals(listOf("Returned", "ScreenOff", "Capped"), DerailmentEnd.entries.map { it.wire })
    }

    @Test
    fun aDerailmentStoredUnderAnEndThisBuildDoesntKnow_isNotWritten() {
        assertNull(SyncWire.derailment(derailment.row.copy(endedBy = "WANDERED_OFF")))
    }

    @Test
    fun theNotificationRows_goOnTheWireAsTheyAreStored() {
        val request = SyncWire.extrasRequest(listOf(interruption, derailment), "pixel-8", 0)

        assertTrue(request.sessions.isEmpty())
        assertEquals("1970-01-01T00:00:01.000Z", request.interruptions.single().atUtc)
        val sent = request.derailments.single()
        assertEquals("ScreenOff", sent.endedBy)
        assertEquals("1970-01-01T00:00:20.000Z", sent.startUtc)
        assertEquals("1970-01-01T00:10:00.000Z", sent.endUtc)
    }

    @Test
    fun theExtrasRequest_carriesNoSessions() {
        val request = SyncWire.extrasRequest(listOf(loop, pickup), "pixel-8", 0)

        assertTrue(request.sessions.isEmpty())
        assertEquals(1, request.pickups.size)
        assertEquals("Hopping", request.loops.single().rule)
        assertEquals("1970-01-01T00:15:00.000Z", request.loops.single().endUtc)
    }
}
