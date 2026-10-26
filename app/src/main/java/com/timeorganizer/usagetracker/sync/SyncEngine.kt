package com.timeorganizer.usagetracker.sync

import android.content.Context
import com.timeorganizer.usagetracker.auth.JwtSubject
import com.timeorganizer.usagetracker.data.api.RetrofitClient
import com.timeorganizer.usagetracker.data.api.dto.AndroidSessionDto
import com.timeorganizer.usagetracker.data.api.dto.AndroidSyncRequest
import com.timeorganizer.usagetracker.data.local.AuthPreferences
import com.timeorganizer.usagetracker.data.local.SyncPreferences
import com.timeorganizer.usagetracker.data.local.outbox.OutboxDatabase
import com.timeorganizer.usagetracker.data.local.outbox.PendingSessionEntity
import com.timeorganizer.usagetracker.ignore.IgnoreStore
import com.timeorganizer.usagetracker.ignore.IgnoreSync
import com.timeorganizer.usagetracker.ignore.OutboxPurge
import com.timeorganizer.usagetracker.notify.SyncBlockedNotifier
import com.timeorganizer.usagetracker.usagestats.UsageStatsCollector
import com.timeorganizer.usagetracker.util.Constants
import com.timeorganizer.usagetracker.util.InstalledApps
import com.timeorganizer.usagetracker.util.PermissionHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

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
        val built = collector.collect(cursorMs, nowMs, ignoreStore.state().ignored(nowMs))

        val rows = built.sessions.flatMap { session ->
            SyncLimits.prepare(session, collector.resolveLabel(session.packageName)).map {
                PendingSessionEntity(packageName = it.packageName, appLabel = it.appLabel, startMs = it.startMs, endMs = it.endMs)
            }
        }
        dao.record(rows, maxOf(built.resumeFromMs, cursorMs))
    }

    private suspend fun prune(nowMs: Long) {
        dao.cursorMs()?.let { dao.pruneSent(it) }
        dao.pruneUnsentEndingBefore(nowMs - Constants.OUTBOX_MAX_AGE_MS)
        dao.keepNewestUnsent(Constants.OUTBOX_MAX_UNSENT)
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
            if (pending.isEmpty()) return SendOutcome(counts, rejected, null, counts != SyncCounts() || rejected > 0)

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

    private suspend fun post(batch: List<PendingSessionEntity>, deviceId: String): BatchResult {
        // Read when sent, not stored in the outbox: a category is a fact about the app, not the session. One lookup per
        // package per batch. An app uninstalled since it was used reads as declaring none (InstalledApps.appCategory).
        val categories = batch.map { it.packageName }.distinct().associateWith { installedApps.appCategory(it) }
        val request = AndroidSyncRequest(
            sessions = batch.map {
                AndroidSessionDto(
                    packageName = it.packageName,
                    appLabel = it.appLabel,
                    sessionStartUtc = INSTANT.format(Instant.ofEpochMilli(it.startMs)),
                    sessionEndUtc = INSTANT.format(Instant.ofEpochMilli(it.endMs)),
                    durationSeconds = (it.endMs - it.startMs) / 1000L,
                    appCategory = categories[it.packageName]
                )
            },
            syncedUpToUtc = INSTANT.format(Instant.ofEpochMilli(batch.maxOf { it.endMs })),
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

        private val INSTANT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

        /**
         * After a sign-in: when it is a different account from the one the outbox was recorded for, its queued
         * sessions are dropped and recording starts from now — they are the previous person's phone use. So are the
         * cached rules and the pending ignores: the previous person's ignores must not drop the new one's apps.
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
            }
            prefs.accountId = account
        }
    }
}
