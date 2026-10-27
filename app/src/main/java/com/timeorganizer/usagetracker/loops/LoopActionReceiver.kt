package com.timeorganizer.usagetracker.loops

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The check-in's three buttons, its swipe-away and the away timer's alarm, handed to [LoopMonitor]. Not exported: only
 * our own PendingIntents reach it.
 */
class LoopActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action?.takeIf { it in ACTIONS } ?: return
        // The state is written off the main thread; keep the process until it is.
        val pending = goAsync()
        LoopMonitor.onAction(context, action).invokeOnCompletion { pending.finish() }
    }

    companion object {
        const val ACTION_AWAY = "com.timeorganizer.usagetracker.loops.PUT_IT_AWAY"
        const val ACTION_FINE = "com.timeorganizer.usagetracker.loops.FINE"
        const val ACTION_NOT_TODAY = "com.timeorganizer.usagetracker.loops.NOT_TODAY"
        const val ACTION_DISMISSED = "com.timeorganizer.usagetracker.loops.DISMISSED"
        const val ACTION_AWAY_DONE = "com.timeorganizer.usagetracker.loops.AWAY_DONE"
        val ACTIONS = listOf(ACTION_AWAY, ACTION_FINE, ACTION_NOT_TODAY, ACTION_DISMISSED, ACTION_AWAY_DONE)
    }
}
