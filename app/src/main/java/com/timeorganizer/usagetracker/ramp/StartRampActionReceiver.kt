package com.timeorganizer.usagetracker.ramp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.timeorganizer.usagetracker.util.holdBroadcastFor

/**
 * The ramp's **In 2 min**, **Not this one**, its swipe-away and the wake at a block's start, handed to [StartRampMonitor].
 * **Start now** opens the web app, so it goes through [StartRampActionActivity]. Not exported: only our own PendingIntents
 * reach it.
 */
class StartRampActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action?.takeIf { it in ACTIONS } ?: return
        val blockId = intent.getLongExtra(StartRampNotifier.EXTRA_BLOCK_ID, 0L)
        // The state is written, the planner told and /now asked off the main thread; the broadcast is held for it, within
        // its budget. The wake's evaluation is the whole point of the alarm, so it is held for, not handed off.
        holdBroadcastFor(StartRampMonitor.onAction(context, action, blockId))
    }

    companion object {
        const val ACTION_LATER = "com.timeorganizer.usagetracker.ramp.IN_2_MIN"
        const val ACTION_NOT_THIS_ONE = "com.timeorganizer.usagetracker.ramp.NOT_THIS_ONE"
        const val ACTION_DISMISSED = "com.timeorganizer.usagetracker.ramp.DISMISSED"
        const val ACTION_WAKE = "com.timeorganizer.usagetracker.ramp.WAKE"
        val ACTIONS = listOf(ACTION_LATER, ACTION_NOT_THIS_ONE, ACTION_DISMISSED, ACTION_WAKE)
    }
}
