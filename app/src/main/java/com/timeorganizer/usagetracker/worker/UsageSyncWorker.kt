package com.timeorganizer.usagetracker.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.timeorganizer.usagetracker.breaks.BreakMonitor
import com.timeorganizer.usagetracker.chime.ChimeMonitor
import com.timeorganizer.usagetracker.loops.LoopMonitor
import com.timeorganizer.usagetracker.review.EveningReviewRunner
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
        askEveningReview()
        // Re-arms the watcher for a block or recent desk work, and ends a break the desk has come back from. Never throws.
        BreakMonitor.afterSync(applicationContext)
        // Refreshes the distraction list and re-arms the watcher after a reboot. Never throws.
        ChimeMonitor.afterSync(applicationContext)
        // Closes a loop that is over and re-arms the watcher after a reboot. Never throws.
        LoopMonitor.afterSync(applicationContext)
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        Result.retry()
    }

    /**
     * After the sync, so the sessions the question is about were just sent. Never fails the run: a review that could
     * not be asked is tried again at the next one, inside its window.
     */
    private suspend fun askEveningReview() {
        try {
            EveningReviewRunner(applicationContext).run()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The sync succeeded; the review waits for the next run.
        }
    }
}
