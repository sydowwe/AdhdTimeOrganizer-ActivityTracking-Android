package com.timeorganizer.usagetracker.pause

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.timeorganizer.usagetracker.util.holdBroadcastFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** The pause notification's **Resume**. Not exported: only our own PendingIntent reaches it. */
class PauseActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RESUME) return
        val app = context.applicationContext
        // The row is written off the main thread; the broadcast is held for it, within its budget.
        holdBroadcastFor(scope.launch { Pauses.resume(app) })
    }

    companion object {
        const val ACTION_RESUME = "com.timeorganizer.usagetracker.pause.RESUME"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
