package com.timeorganizer.usagetracker.sync

import android.content.Context
import com.timeorganizer.usagetracker.auth.JwtSubject
import com.timeorganizer.usagetracker.breaks.BreakMonitor
import com.timeorganizer.usagetracker.budget.BudgetMonitor
import com.timeorganizer.usagetracker.capture.CaptureSender
import com.timeorganizer.usagetracker.chime.ChimeMonitor
import com.timeorganizer.usagetracker.data.api.RetrofitClient
import com.timeorganizer.usagetracker.data.api.dto.AndroidSessionDto
import com.timeorganizer.usagetracker.data.api.dto.AndroidSyncRequest
import com.timeorganizer.usagetracker.data.local.AuthPreferences
import com.timeorganizer.usagetracker.data.local.SyncPreferences
import com.timeorganizer.usagetracker.data.local.outbox.OutboxDatabase
import com.timeorganizer.usagetracker.data.local.outbox.PendingDerailmentEntity
import com.timeorganizer.usagetracker.data.local.outbox.PendingInterruptionEntity
import com.timeorganizer.usagetracker.data.local.outbox.PendingPickupEntity
import com.timeorganizer.usagetracker.data.local.outbox.PendingSessionEntity
import com.timeorganizer.usagetracker.drift.DriftMonitor
import com.timeorganizer.usagetracker.ramp.StartRampMonitor
import com.timeorganizer.usagetracker.ignore.IgnoreStore
import com.timeorganizer.usagetracker.ignore.IgnoreSync
import com.timeorganizer.usagetracker.ignore.OutboxPurge
import com.timeorganizer.usagetracker.loops.LoopDatabase
import com.timeorganizer.usagetracker.loops.LoopMonitor
import com.timeorganizer.usagetracker.loops.LoopPreferences
import com.timeorganizer.usagetracker.loops.PickupLoopDao
import com.timeorganizer.usagetracker.notify.SyncBlockedNotifier
import com.timeorganizer.usagetracker.now.KeptDistractions
import com.timeorganizer.usagetracker.usagestats.UsageStatsCollector
import com.timeorganizer.usagetracker.util.Constants
import com.timeorganizer.usagetracker.util.InstalledApps
import com.timeorganizer.usagetracker.util.PermissionHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One sync run: read the usage log into the outbox, then drain the outbox to the server.
 *
 * The two halves are independent on purpose. Recording needs usage access and nothing else — it runs offline,
 * signed out, with the server down — and commits the sessions together with the cursor that skips past them.
 * Sending needs a session and a network and stops at the first batch that has to wait; nothing leaves the
 * outbox until the server has answered for it.
 */
class SyncEngine(context: Context) {

    private val appContext = context.applicationContext
    private val dao = OutboxDatabase.get(appContext).outboxDao()
    private val prefs = SyncPreferences(appContext)
    private val authPrefs = AuthPreferences.get(appContext)
    private val collector = UsageStatsCollector(appContext)
    private val notifier = SyncBlockedNotifier(appContext)
    private val ignoreStore = IgnoreStore.get(appContext)
    private val ignoreSync = IgnoreSync(appContext)
    private val installedApps = InstalledApps(appContext)

    private var clockAheadMs = 0L

    suspend fun run(): SyncReport = lock.withLock {
        val nowMs = System.currentTimeMillis()
        val previous = prefs.lastReport
        clockAheadMs = previous.clockAheadMs

        // The ignored set first, so this run's read already drops what the user ignored elsewhere.
        if (authPrefs.isLoggedIn) syncIgnores(nowMs)

        val hasUsageAccess = PermissionHelper.hasUsageStatsPermission(appContext)
        if (hasUsageAccess) record(nowMs)
        prune(nowMs)
        dropIgnoredFromOutbox(nowMs)

        rememberAccount()
        var sent = SyncCounts()
        var rejected = 0
        val state = if (!authPrefs.isLoggedIn) {
            SyncState.SIGNED_OUT
        } else {
            val outcome = sendAll(nowMs)
            sent = outcome.counts
            rejected = outcome.rejected
            when (outcome.stoppedBy) {
                BatchResult.AuthRequired -> if (authPrefs.isLoggedIn) SyncState.WAITING else SyncState.SIGNED_OUT
                BatchResult.RetryLater -> SyncState.WAITING
                else -> if (outcome.anythingAnswered) SyncState.SENT else SyncState.UP_TO_DATE
            }
        }

        val report = SyncReport(
            state = if (!hasUsageAccess && state in SETTLED) SyncState.NO_USAGE_ACCESS else state,
            atMs = nowMs,
            lastSentAtMs = if (sent != SyncCounts()) nowMs else previous.lastSentAtMs,
            sent = sent,
            rejected = rejected,
            pending = dao.unsentCount(),
            clockAheadMs = clockAheadMs
        )
        prefs.lastReport = report
        notifier.onReport(report, hasUsageAccess, authPrefs.signOutReason, nowMs)
        report
    }

    /**
     * Reads the usage log into the outbox without sending, and returns how many sessions are waiting. It is the
     * first half of [run], so it is as safe: the rows and the cursor commit together and a later run sends them.
     */
    suspend fun recordPending(): Int = lock.withLock {
        val nowMs = System.currentTimeMillis()
        if (PermissionHelper.hasUsageStatsPermission(appContext)) record(nowMs)
        prune(nowMs)
        dropIgnoredFromOutbox(nowMs)
        dao.unsentCount()
    }

    /** The server's rules and the user's pending ignores. A failure leaves the cached set as it was; the run goes on. */
    private suspend fun syncIgnores(nowMs: Long) {
        try {
            ignoreSync.syncWithServer(nowMs)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Recording and sending don't depend on it; the next run tries again.
        }
    }

    private suspend fun record(nowMs: Long) {
        // A clock moved backwards leaves the cursor in the future; read from now rather than from nowhere.
        val cursorMs = minOf(dao.cursorMs() ?: prefs.legacyCursorMs ?: (nowMs - Constants.FIRST_RUN_BACKFILL_MS), nowMs)
        // Prompt 14: the pickups' own cursor, usually before the sessions' (a pickup begins before the app it opens).
        val pickupCursorMs = minOf(dao.pickupCursorMs() ?: cursorMs, nowMs)
        // Prompt 15: the notifications' own, at the earliest one still undecided.
        val derailmentCursorMs = minOf(dao.derailmentCursorMs() ?: cursorMs, nowMs)
        val loopPrefs = LoopPreferences(appContext)
        val built = collector.collect(
            cursorMs, nowMs, ignoreStore.state().ignored(nowMs), pickupCursorMs, loopPrefs.sawUnlock, derailmentCursorMs
        )
        if (built.pickups.sawUnlock && !loopPrefs.sawUnlock) loopPrefs.sawUnlock = true

        val rows = built.sessions.sessions.flatMap { session ->
            SyncLimits.prepare(session, collector.resolveLabel(session.packageName)).map {
                PendingSessionEntity(packageName = it.packageName, appLabel = it.appLabel, startMs = it.startMs, endMs = it.endMs)
            }
        }
        val pickups = built.pickups.closed.map { PendingPickupEntity(startMs = it.startMs, endMs = it.endMs, unlocked = it.unlocked) }
        // Packages over the server's width can't be stored there; such an app never posts one in practice.
        val interruptions = built.derailments.interruptions
            .filter { it.packageName.length <= SyncLimits.MAX_PACKAGE_NAME_LENGTH }
            .map { PendingInterruptionEntity(packageName = it.packageName, atMs = it.atMs) }
        val derailments = built.derailments.derailments
            .filter { it.packageName.length <= SyncLimits.MAX_PACKAGE_NAME_LENGTH }
            .map {
                PendingDerailmentEntity(
                    packageName = it.packageName, notifiedMs = it.notifiedMs, startMs = it.startMs, endMs = it.endMs,
                    endedBy = it.endedBy.name
                )
            }
        dao.record(
            rows, maxOf(built.sessions.resumeFromMs, cursorMs),
            pickups, maxOf(built.pickups.resumeFromMs, pickupCursorMs),
            interruptions, derailments, maxOf(built.derailments.resumeFromMs, derailmentCursorMs)
        )
    }

    private suspend fun prune(nowMs: Long) {
        dao.cursorMs()?.let { dao.pruneSent(it) }
        dao.pruneUnsentEndingBefore(nowMs - Constants.OUTBOX_MAX_AGE_MS)
        dao.keepNewestUnsent(Constants.OUTBOX_MAX_UNSENT)
        dao.pickupCursorMs()?.let { dao.pruneSentPickups(it) }
        dao.pruneUnsentPickupsEndingBefore(nowMs - Constants.OUTBOX_MAX_AGE_MS)
        dao.derailmentCursorMs()?.let { dao.pruneSentDerailmentRows(it) }
        dao.pruneUnsentDerailmentRowsBefore(nowMs - Constants.OUTBOX_MAX_AGE_MS)
    }

    /** Rows queued before the phone learnt a package is ignored never leave. */
    private suspend fun dropIgnoredFromOutbox(nowMs: Long) {
        val doomed = OutboxPurge.packagesToDelete(dao.unsentPackages(), ignoreStore.state().ignored(nowMs))
        doomed.chunked(SQL_VARIABLES_PER_QUERY).forEach { dao.deleteUnsentOf(it) }
    }

    private class SendOutcome(val counts: SyncCounts, val rejected: Int, val stoppedBy: BatchResult?, val anythingAnswered: Boolean)

    private suspend fun sendAll(nowMs: Long): SendOutcome {
        val deviceId = prefs.deviceId
        val sender = BatchSender<PendingSessionEntity>(SyncLimits.MAX_SESSIONS_PER_REQUEST) { post(it, deviceId) }
        var counts = SyncCounts()
        var rejected = 0

        while (true) {
            val pending = dao.unsent(PAGE_SIZE)
            if (pending.isEmpty()) return sendExtras(nowMs, counts, rejected)

            val pass = sender.sendAll(
                pending,
                onSent = { batch -> dao.markSent(batch.map { it.id }, nowMs) },
                onRejected = { dao.delete(it.id) }
            )
            counts += pass.counts
            rejected += pass.rejected
            if (pass.stoppedBy != null) return SendOutcome(counts, rejected, pass.stoppedBy, true)
        }
    }

    /**
     * Prompt 14: the pickups and the loop check's loops, once every session is sent, in requests of their own; then (prompt
     * 15) the interruptions and derailments, in requests of their own again, so a server from 14 — which acknowledges the
     * first group and not the second — keeps taking pickups. A row leaves the "unsent" queue only when the answer
     * acknowledges it (`SyncWire.acknowledged`); a server that answers 200 without isn't sent the rest of that group, and
     * the rows wait for an updated server (30 days at most, like the outbox). A batch refused down to one row drops that row,
     * as for sessions.
     */
    private suspend fun sendExtras(nowMs: Long, counts: SyncCounts, rejected: Int): SendOutcome {
        val deviceId = prefs.deviceId
        val loopDao = LoopDatabase.get(appContext).loopDao()
        // The status screen's "sent" is about sessions, as before 14: pickups go out on nearly every run.
        val answered = counts != SyncCounts() || rejected > 0

        val pickups = sendExtraGroup(nowMs, deviceId, loopDao, SyncLimits.MAX_EXTRAS_PER_REQUEST) {
            loopDao.unsent(PAGE_SIZE).map { SyncExtra.Loop(it) } + dao.unsentPickups(PAGE_SIZE).map { SyncExtra.Pickup(it) }
        }
        if (pickups.stoppedBy != null) return SendOutcome(counts, rejected, pickups.stoppedBy, true)
        // A server that ignored pickups knows nothing of notifications either.
        if (pickups.unacknowledged) return SendOutcome(counts, rejected, null, answered)

        val notifications = sendExtraGroup(nowMs, deviceId, loopDao, SyncLimits.MAX_NOTIFICATION_ROWS_PER_REQUEST) {
            dao.unsentInterruptions(PAGE_SIZE).map { SyncExtra.Interruption(it) } +
                dao.unsentDerailments(PAGE_SIZE).map { SyncExtra.Derailment(it) }
        }
        if (notifications.stoppedBy != null) return SendOutcome(counts, rejected, notifications.stoppedBy, true)
        return SendOutcome(counts, rejected, null, answered)
    }

    private class GroupOutcome(val stoppedBy: BatchResult?, val unacknowledged: Boolean)

    /** One group of extras, page by page, until it is empty, has to wait, or meets a server that doesn't acknowledge it. */
    private suspend fun sendExtraGroup(
        nowMs: Long,
        deviceId: String,
        loopDao: PickupLoopDao,
        perRequest: Int,
        nextPage: suspend () -> List<SyncExtra>
    ): GroupOutcome {
        while (true) {
            val extras = nextPage()
            if (extras.isEmpty()) return GroupOutcome(null, false)

            var unacknowledged = false
            val sender = BatchSender<SyncExtra>(perRequest) { batch ->
                if (unacknowledged) {
                    BatchResult.Sent(SyncCounts()) // an older server: don't send it the rest
                } else {
                    val (result, acknowledged) = postExtras(batch, deviceId, nowMs)
                    if (result is BatchResult.Sent && !acknowledged) unacknowledged = true
                    result
                }
            }
            val pass = sender.sendAll(
                extras,
                onSent = { batch -> if (!unacknowledged) markSent(batch, nowMs, loopDao) },
                onRejected = { markSent(listOf(it), nowMs, loopDao) }
            )
            if (pass.stoppedBy != null) return GroupOutcome(pass.stoppedBy, false)
            if (unacknowledged) return GroupOutcome(null, true)
        }
    }

    private suspend fun markSent(batch: List<SyncExtra>, nowMs: Long, loopDao: PickupLoopDao) {
        batch.filterIsInstance<SyncExtra.Pickup>().map { it.row.id }.chunked(SQL_VARIABLES_PER_QUERY)
            .forEach { dao.markPickupsSent(it, nowMs) }
        batch.filterIsInstance<SyncExtra.Loop>().map { it.row.id }.chunked(SQL_VARIABLES_PER_QUERY)
            .forEach { loopDao.markSent(it, nowMs) }
        batch.filterIsInstance<SyncExtra.Interruption>().map { it.row.id }.chunked(SQL_VARIABLES_PER_QUERY)
            .forEach { dao.markInterruptionsSent(it, nowMs) }
        batch.filterIsInstance<SyncExtra.Derailment>().map { it.row.id }.chunked(SQL_VARIABLES_PER_QUERY)
            .forEach { dao.markDerailmentsSent(it, nowMs) }
    }

    /** The answer, and whether it acknowledged the batch. Nothing known (offline, unreadable) is a wait, as for sessions. */
    private suspend fun postExtras(batch: List<SyncExtra>, deviceId: String, nowMs: Long): Pair<BatchResult, Boolean> {
        val response = try {
            RetrofitClient.apiService.syncSessions(SyncWire.extrasRequest(batch, deviceId, nowMs))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return BatchResult.RetryLater to false
        }
        ResponseClassifier.classify(response.code())?.let { return it to false }
        return BatchResult.Sent(SyncCounts()) to SyncWire.acknowledged(response.body(), batch)
    }

    private suspend fun post(batch: List<PendingSessionEntity>, deviceId: String): BatchResult {
        // Read when sent, not stored in the outbox: a category is a fact about the app, not the session. One lookup per
        // package per batch. An app uninstalled since it was used reads as declaring none (InstalledApps.appCategory).
        val categories = batch.map { it.packageName }.distinct().associateWith { installedApps.appCategory(it) }
        val request = AndroidSyncRequest(
            sessions = batch.map {
                AndroidSessionDto(
                    packageName = it.packageName,
                    appLabel = it.appLabel,
                    sessionStartUtc = SyncWire.instant(it.startMs),
                    sessionEndUtc = SyncWire.instant(it.endMs),
                    durationSeconds = (it.endMs - it.startMs) / 1000L,
                    appCategory = categories[it.packageName]
                )
            },
            syncedUpToUtc = SyncWire.instant(batch.maxOf { it.endMs }),
            deviceId = deviceId
        )

        val response = try {
            RetrofitClient.apiService.syncSessions(request)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Offline, a timeout, an unreadable body: nothing is known about the batch, so it stays queued.
            return BatchResult.RetryLater
        }

        response.headers().getDate("Date")?.let { serverNow -> clockAheadMs = System.currentTimeMillis() - serverNow.time }

        ResponseClassifier.classify(response.code())?.let { return it }
        val body = response.body()
        return BatchResult.Sent(
            SyncCounts(
                accepted = body?.accepted ?: batch.size,
                duplicates = body?.duplicatesSkipped ?: 0,
                dropped = body?.dropped ?: 0,
                ignored = body?.ignored ?: 0
            )
        )
    }

    /** Installs from before 1.1 signed in without recording whose outbox this is. */
    private fun rememberAccount() {
        if (prefs.accountId != null) return
        authPrefs.token?.let(JwtSubject::of)?.let { prefs.accountId = it }
    }

    companion object {
        private val lock = Mutex()
        private const val PAGE_SIZE = 1_000

        /** Under SQLite's oldest bound parameter limit (999). */
        private const val SQL_VARIABLES_PER_QUERY = 500
        private val SETTLED = setOf(SyncState.SENT, SyncState.UP_TO_DATE)

        /**
         * After a sign-in: when it is a different account from the one the outbox was recorded for, its queued
         * sessions are dropped and recording starts from now — they are the previous person's phone use. So are the
         * cached rules and the pending ignores: the previous person's ignores must not drop the new one's apps. So is an
         * open break: it names the previous person's activity. So is the kept distraction list (the chime's ticks stay:
         * they are this phone's). So are the loops the loop check saw (the apps that don't count stay, for the same reason),
         * the start ramp's blocks and its wake (its switch and "which blocks" stay), the drift nudge's block, and the daily
         * limits' figures, settled stages and unsent claims (their switch stays). So are the
         * thoughts quick capture still holds: they were meant for the previous account's to-dos. Otherwise the ones captured
         * while signed out go now.
         */
        suspend fun onSignedIn(context: Context, accessToken: String) {
            val prefs = SyncPreferences(context)
            val account = JwtSubject.of(accessToken) ?: return
            val previous = prefs.accountId
            if (previous != null && previous != account) {
                lock.withLock {
                    OutboxDatabase.get(context).outboxDao().reset(System.currentTimeMillis())
                    IgnoreStore.get(context).clear()
                }
                KeptDistractions.clear(context)
                BreakMonitor.reset(context)
                ChimeMonitor.reset(context)
                LoopMonitor.reset(context)
                StartRampMonitor.reset(context)
                DriftMonitor.reset(context)
                BudgetMonitor.reset(context)
                CaptureSender.reset(context)
            }
            prefs.accountId = account
            CaptureSender.enqueue(context)
        }
    }
}
