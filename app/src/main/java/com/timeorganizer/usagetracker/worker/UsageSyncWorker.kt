package com.timeorganizer.usagetracker.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.timeorganizer.usagetracker.sync.SyncEngine
import kotlinx.coroutines.CancellationException

/**
 * Runs [SyncEngine]. A run that has to wait (offline, signed out, server busy) is still a success: its
 * sessions are in the outbox and the next period picks them up, so a retry with backoff would add nothing.
 * Only an unexpected failure asks WorkManager to retry.
 */
class UsageSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        SyncEngine(applicationContext).run()
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        Result.retry()
    }
}
