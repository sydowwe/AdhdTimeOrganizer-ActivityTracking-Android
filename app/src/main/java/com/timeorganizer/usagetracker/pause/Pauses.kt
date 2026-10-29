package com.timeorganizer.usagetracker.pause

import android.content.Context
import com.timeorganizer.usagetracker.data.local.outbox.OutboxDatabase
import com.timeorganizer.usagetracker.data.local.outbox.PauseRangeEntity
import com.timeorganizer.usagetracker.now.NowClient
import java.time.ZoneId

/**
 * Pause tracking (prompt 17): the pauses kept in `outbox.db`, started from the status card and ended by Resume (the card or
 * the notification). What a pause takes out is [PauseClipper]'s, applied by the sync's read, the loop store and the daily
 * limits' estimate; the nudges themselves run on (decision 17-1).
 */
object Pauses {

    suspend fun active(context: Context, nowMs: Long = System.currentTimeMillis()): PauseRange? =
        dao(context).activePause(nowMs)?.toRange()

    suspend fun over(context: Context, fromMs: Long, toMs: Long): List<PauseRange> =
        dao(context).pausesOver(fromMs, toMs).map { it.toRange() }

    /**
     * Pauses from now until [length]'s end. "Until morning" ends at the morning the card showed, kept from its last ask: what
     * the button said is what happens. A pause already going is left as it is: the card offers Resume then.
     */
    suspend fun start(context: Context, length: PauseLength, nowMs: Long = System.currentTimeMillis()) {
        val dao = dao(context)
        if (dao.activePause(nowMs) == null) {
            val end = PausePolicy.endMs(length, nowMs, morning(context, nowMs, ask = false))
            dao.insertPause(PauseRangeEntity(startMs = nowMs, endMs = end))
        }
        PauseNotifier.refresh(context, nowMs)
    }

    /** Ends the pause going now; one that began this very millisecond leaves no range behind. */
    suspend fun resume(context: Context, nowMs: Long = System.currentTimeMillis()) {
        val dao = dao(context)
        dao.activePause(nowMs)?.let { if (it.startMs >= nowMs) dao.deletePause(it.id) else dao.endPause(it.id, nowMs) }
        PauseNotifier.refresh(context, nowMs)
    }

    /**
     * The next morning: the server's (`/now`'s `nextWakeUpAt`, the planner's wake-up) when [ask] and it answers, else the
     * last one heard moved on by days, else 07:00 (`PausePolicy.morning`).
     */
    suspend fun morning(context: Context, nowMs: Long = System.currentTimeMillis(), ask: Boolean = true): Long {
        val prefs = PausePreferences(context)
        if (ask) NowClient.get(nowMs)?.nextWakeUpMs?.let { prefs.keptWakeUpMs = it }
        return PausePolicy.morning(nowMs, prefs.keptWakeUpMs, ZoneId.systemDefault())
    }

    private fun dao(context: Context) = OutboxDatabase.get(context).outboxDao()

    private fun PauseRangeEntity.toRange() = PauseRange(startMs, endMs)
}
