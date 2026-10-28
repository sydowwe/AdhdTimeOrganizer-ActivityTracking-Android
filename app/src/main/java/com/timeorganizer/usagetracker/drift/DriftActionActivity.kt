package com.timeorganizer.usagetracker.drift

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.timeorganizer.usagetracker.util.Constants

/**
 * The drift nudge's **Back to it** (the home screen) and **Switch plan** (the web app's day planner). Never seen: it
 * records the answer, opens the place, and finishes in `onCreate` (`Theme.NoDisplay`). An activity because since Android 12
 * a notification's broadcast may not start one ("trampolines"); the notification's own activity PendingIntent may.
 * Not exported: only our own PendingIntents reach it.
 */
class DriftActionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val action = intent?.action
        if (action == ACTION_BACK || action == ACTION_SWITCH_PLAN) {
            DriftMonitor.onAction(this, action)
            val target = if (action == ACTION_BACK) {
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            } else {
                Intent(Intent.ACTION_VIEW, Uri.parse(Constants.WEB_APP_ROOT + DAY_PLANNER_PATH))
            }
            try {
                startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: ActivityNotFoundException) {
                // No browser: the answer is recorded all the same.
            }
        }
        finish()
    }

    companion object {
        const val ACTION_BACK = "com.timeorganizer.usagetracker.drift.BACK_TO_IT"
        const val ACTION_SWITCH_PLAN = "com.timeorganizer.usagetracker.drift.SWITCH_PLAN"

        /** The web app's planner, today's day: the desktop's "Switch plan" opens the same. */
        const val DAY_PLANNER_PATH = "day-planner"
    }
}
