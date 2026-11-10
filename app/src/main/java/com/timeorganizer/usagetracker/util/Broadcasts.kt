package com.timeorganizer.usagetracker.util

import android.content.BroadcastReceiver
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Job
import java.util.concurrent.atomic.AtomicBoolean

/**
 * How long a receiver keeps its broadcast open for the work it started. A foreground broadcast is allowed about 10 s before
 * the system calls it hung and may kill the process — and with it the foreground watcher. A background one gets more.
 */
const val BROADCAST_BUDGET_MS = 8_000L

/**
 * `goAsync()` for [work], the one way a receiver here keeps its broadcast open: finished when [work] ends or after
 * [BROADCAST_BUDGET_MS], whichever comes first. Work that runs longer — a `/now` or a planner call on a slow network,
 * where the connect timeout alone is 15 s — goes on in its own scope, no longer holding the broadcast: alive while the
 * process is (the watcher's service usually keeps it), and the next sync catches up otherwise.
 *
 * Call it from `onReceive`, on the main thread, as `goAsync` requires.
 */
fun BroadcastReceiver.holdBroadcastFor(work: Job) {
    val pending = goAsync()
    val finished = AtomicBoolean(false)
    fun finishOnce() {
        if (finished.compareAndSet(false, true)) pending.finish()
    }

    val handler = Handler(Looper.getMainLooper())
    val budget = Runnable { finishOnce() }
    handler.postDelayed(budget, BROADCAST_BUDGET_MS)
    work.invokeOnCompletion {
        handler.removeCallbacks(budget)
        finishOnce()
    }
}
