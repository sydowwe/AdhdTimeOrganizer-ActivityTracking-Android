package com.timeorganizer.usagetracker.drift

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The drift nudge's **5 more minutes** and its swipe-away, handed to [DriftMonitor]. The two buttons that leave the app in
 * front go through [DriftActionActivity]. Not exported: only our own PendingIntents reach it.
 */
class DriftActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action?.takeIf { it in ACTIONS } ?: return
        // The state is written off the main thread; keep the process until it is.
        val pending = goAsync()
        DriftMonitor.onAction(context, action).invokeOnCompletion { pending.finish() }
    }

    companion object {
        const val ACTION_MORE = "com.timeorganizer.usagetracker.drift.FIVE_MORE"
        const val ACTION_DISMISSED = "com.timeorganizer.usagetracker.drift.DISMISSED"
        val ACTIONS = listOf(ACTION_MORE, ACTION_DISMISSED)
    }
}
