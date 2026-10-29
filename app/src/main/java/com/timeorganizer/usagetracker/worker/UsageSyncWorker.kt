package com.timeorganizer.usagetracker.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.timeorganizer.usagetracker.breaks.BreakMonitor
import com.timeorganizer.usagetracker.budget.BudgetMonitor
import com.timeorganizer.usagetracker.capture.CaptureSender
import com.timeorganizer.usagetracker.chime.ChimeMonitor
import com.timeorganizer.usagetracker.drift.DriftMonitor
import com.timeorganizer.usagetracker.loops.LoopMonitor
import com.timeorganizer.usagetracker.pause.PauseNotifier
import com.timeorganizer.usagetracker.ramp.StartRampMonitor
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
        // Budget stages said offline are claimed first, so the sync carrying their time finds them said (13-1). Never throws.
        BudgetMonitor.beforeSync(applicationContext)
        SyncEngine(applicationContext).run()
        // A pause "until I resume" is shown again once a day; an ended one's notification goes. Never throws.
        PauseNotifier.afterSync(applicationContext)
        // Quick capture's queue: anything the network-bound job hasn't sent yet (signed in again, back online). Never throws.
        CaptureSender.afterSync(applicationContext)
        askEveningReview()
        // Re-arms the watcher for a block or recent desk work, and ends a break the desk has come back from. Never throws.
        BreakMonitor.afterSync(applicationContext)
        // Refreshes the distraction list and re-arms the watcher after a reboot. Never throws.
        ChimeMonitor.afterSync(applicationContext)
        // Closes a loop that is over and re-arms the watcher after a reboot. Never throws.
        LoopMonitor.afterSync(applicationContext)
        // Plans the wake at the next block's start and says a start missed while asleep; before drift (10-8). Never throws.
        StartRampMonitor.afterSync(applicationContext)
        // Arms the watcher through a block (or one starting soon) and withdraws a nudge whose block is over. Never throws.
        DriftMonitor.afterSync(applicationContext)
        // Reads the budgets' figures again (the sync moved them) and says a limit the phone's time crossed. Never throws.
        BudgetMonitor.afterSync(applicationContext)
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
