package com.timeorganizer.usagetracker.ramp

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.timeorganizer.usagetracker.util.Constants

/**
 * The ramp's **Start now** (decision 10-1): starts the block in the planner (`POST tracker/android/start-block`, in the
 * background) and opens the web app's `/?track=<plannerTaskId>`, where the countdown for the rest of the block starts
 * (the frontend's `useTrackLink`, the desktop's link too). The server call is what makes the start count even when the
 * browser never gets there; the web app's own start then finds the block already in progress.
 *
 * Never seen: finishes in `onCreate` (`Theme.NoDisplay`). An activity because since Android 12 a notification's broadcast
 * may not start one. Not exported: only our own PendingIntent reaches it.
 */
class StartRampActionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val blockId = intent?.getLongExtra(StartRampNotifier.EXTRA_BLOCK_ID, 0L) ?: 0L
        if (intent?.action == ACTION_START && blockId > 0) {
            StartRampMonitor.onAction(this, ACTION_START, blockId)
            try {
                startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(trackLink(blockId))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: ActivityNotFoundException) {
                // No browser: the block is started in the planner all the same.
            }
        }
        finish()
    }

    companion object {
        const val ACTION_START = "com.timeorganizer.usagetracker.ramp.START_NOW"

        /** **Pure.** The web app's home with the block to track: `https://…/?track=7`. */
        fun trackLink(plannerTaskId: Long, webAppRoot: String = Constants.WEB_APP_ROOT): String =
            "$webAppRoot?track=$plannerTaskId"
    }
}
