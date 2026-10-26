package com.timeorganizer.usagetracker.breaks

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** The break nudge's three buttons and its swipe-away, handed to [BreakMonitor]. Not exported: only our own PendingIntents reach it. */
class BreakActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action?.takeIf { it in ACTIONS } ?: return
        // The state is written off the main thread; keep the process until it is.
        val pending = goAsync()
        BreakMonitor.onAction(context, action).invokeOnCompletion { pending.finish() }
    }

    companion object {
        const val ACTION_BACK = "com.timeorganizer.usagetracker.breaks.BACK_TO_IT"
        const val ACTION_MORE = "com.timeorganizer.usagetracker.breaks.FIVE_MORE"
        const val ACTION_DONE = "com.timeorganizer.usagetracker.breaks.DONE_FOR_NOW"
        const val ACTION_DISMISSED = "com.timeorganizer.usagetracker.breaks.DISMISSED"
        val ACTIONS = listOf(ACTION_BACK, ACTION_MORE, ACTION_DONE, ACTION_DISMISSED)
    }
}
