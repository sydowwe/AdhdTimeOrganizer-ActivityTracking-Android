package com.timeorganizer.usagetracker.sync

import com.timeorganizer.usagetracker.data.api.dto.AndroidPickupDto
import com.timeorganizer.usagetracker.data.api.dto.AndroidPickupLoopDto
import com.timeorganizer.usagetracker.data.api.dto.AndroidSyncRequest
import com.timeorganizer.usagetracker.data.api.dto.AndroidSyncResponse
import com.timeorganizer.usagetracker.data.local.outbox.PendingPickupEntity
import com.timeorganizer.usagetracker.loops.LoopAnswer
import com.timeorganizer.usagetracker.loops.LoopRule
import com.timeorganizer.usagetracker.loops.PickupLoopEntity
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** One thing the sync sends after the sessions (prompt 14): a pickup from the outbox or a loop from `loops.db`. */
sealed interface SyncExtra {
    data class Pickup(val row: PendingPickupEntity) : SyncExtra
    data class Loop(val row: PickupLoopEntity) : SyncExtra
}

/**
 * **Pure.** How the sync writes its rows on the wire and reads the answer: the instant format, the pickups-and-loops request
 * (prompt 14), and whether the server acknowledged what it was sent.
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

    /** A request of pickups and loops only: no sessions, so an older server answers it like an empty sync. */
    fun extrasRequest(batch: List<SyncExtra>, deviceId: String, nowMs: Long) = AndroidSyncRequest(
        sessions = emptyList(),
        syncedUpToUtc = instant(nowMs),
        deviceId = deviceId,
        pickups = batch.filterIsInstance<SyncExtra.Pickup>().map { pickup(it.row) },
        loops = batch.filterIsInstance<SyncExtra.Loop>().mapNotNull { loop(it.row) }
    )

    /**
     * The server holds what it was sent — and its pickups and loops may leave the "unsent" queue — only when its answer
     * says so. A server from before prompt 14 answers 200 without the counters: it ignored them, and they stay queued.
     */
    fun acknowledged(body: AndroidSyncResponse?, batch: List<SyncExtra>): Boolean {
        if (body == null) return false
        val pickupsOk = batch.none { it is SyncExtra.Pickup } || body.pickupsStored != null
        val loopsOk = batch.none { it is SyncExtra.Loop } || body.loopsStored != null
        return pickupsOk && loopsOk
    }
}
