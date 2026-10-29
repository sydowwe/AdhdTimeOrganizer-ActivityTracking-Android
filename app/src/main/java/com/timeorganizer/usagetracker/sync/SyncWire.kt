package com.timeorganizer.usagetracker.sync

import com.timeorganizer.usagetracker.data.api.dto.AndroidDerailmentDto
import com.timeorganizer.usagetracker.data.api.dto.AndroidInterruptionDto
import com.timeorganizer.usagetracker.data.api.dto.AndroidPickupDto
import com.timeorganizer.usagetracker.data.api.dto.AndroidPickupLoopDto
import com.timeorganizer.usagetracker.data.api.dto.AndroidSyncRequest
import com.timeorganizer.usagetracker.data.api.dto.AndroidSyncResponse
import com.timeorganizer.usagetracker.data.local.outbox.PendingDerailmentEntity
import com.timeorganizer.usagetracker.data.local.outbox.PendingInterruptionEntity
import com.timeorganizer.usagetracker.data.local.outbox.PendingPickupEntity
import com.timeorganizer.usagetracker.loops.LoopAnswer
import com.timeorganizer.usagetracker.loops.LoopRule
import com.timeorganizer.usagetracker.loops.PickupLoopEntity
import com.timeorganizer.usagetracker.usagestats.DerailmentEnd
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * One thing the sync sends after the sessions: a pickup from the outbox or a loop from `loops.db` (prompt 14), an
 * interruption or a derailment from the outbox (prompt 15).
 */
sealed interface SyncExtra {
    data class Pickup(val row: PendingPickupEntity) : SyncExtra
    data class Loop(val row: PickupLoopEntity) : SyncExtra
    data class Interruption(val row: PendingInterruptionEntity) : SyncExtra
    data class Derailment(val row: PendingDerailmentEntity) : SyncExtra
}

/**
 * **Pure.** How the sync writes its rows on the wire and reads the answer: the instant format, the extras request (prompts
 * 14 and 15), and whether the server acknowledged what it was sent.
 */
object SyncWire {

    /** UTC ISO-8601 with milliseconds, what the server recomputes durations from. */
    val INSTANT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    fun instant(ms: Long): String = INSTANT.format(Instant.ofEpochMilli(ms))

    fun pickup(row: PendingPickupEntity) = AndroidPickupDto(instant(row.startMs), instant(row.endMs), row.unlocked)

    /** `null` for a stored name this build doesn't know (written by a newer one): it can't be said on the wire. */
    fun loop(row: PickupLoopEntity): AndroidPickupLoopDto? {
        val rule = LoopRule.entries.firstOrNull { it.name == row.rule } ?: return null
        val answer = LoopAnswer.entries.firstOrNull { it.name == row.answer } ?: return null
        return AndroidPickupLoopDto(instant(row.startMs), instant(row.endMs), rule.wire, row.pickups, row.apps, answer.wire)
    }

    fun interruption(row: PendingInterruptionEntity) = AndroidInterruptionDto(instant(row.atMs), row.packageName)

    /** `null` for a stored end this build doesn't know, as [loop]. */
    fun derailment(row: PendingDerailmentEntity): AndroidDerailmentDto? {
        val end = DerailmentEnd.entries.firstOrNull { it.name == row.endedBy } ?: return null
        return AndroidDerailmentDto(row.packageName, instant(row.notifiedMs), instant(row.startMs), instant(row.endMs), end.wire)
    }

    /** A request of extras only: no sessions, so an older server answers it like an empty sync. */
    fun extrasRequest(batch: List<SyncExtra>, deviceId: String, nowMs: Long) = AndroidSyncRequest(
        sessions = emptyList(),
        syncedUpToUtc = instant(nowMs),
        deviceId = deviceId,
        pickups = batch.filterIsInstance<SyncExtra.Pickup>().map { pickup(it.row) },
        loops = batch.filterIsInstance<SyncExtra.Loop>().mapNotNull { loop(it.row) },
        interruptions = batch.filterIsInstance<SyncExtra.Interruption>().map { interruption(it.row) },
        derailments = batch.filterIsInstance<SyncExtra.Derailment>().mapNotNull { derailment(it.row) }
    )

    /**
     * The server holds what it was sent — and its rows may leave the "unsent" queue — only when its answer says so, for every
     * kind in the batch. A server from before prompt 14 answers 200 without any counter, one from before 15 without the
     * interruptions' and derailments': it ignored them, and they stay queued.
     */
    fun acknowledged(body: AndroidSyncResponse?, batch: List<SyncExtra>): Boolean {
        if (body == null) return false
        return batch.all {
            when (it) {
                is SyncExtra.Pickup -> body.pickupsStored != null
                is SyncExtra.Loop -> body.loopsStored != null
                is SyncExtra.Interruption -> body.interruptionsStored != null
                is SyncExtra.Derailment -> body.derailmentsStored != null
            }
        }
    }
}
