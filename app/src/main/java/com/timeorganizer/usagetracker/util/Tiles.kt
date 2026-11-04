package com.timeorganizer.usagetracker.util

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService

/** Opens [intent] over whatever is on screen and closes the shade: what both tiles do with a tap. */
@SuppressLint("StartActivityAndCollapseDeprecated")
fun TileService.openAndCollapse(intent: Intent) {
    val task = intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        // API 34 refuses the Intent overload (UnsupportedOperationException).
        startActivityAndCollapse(PendingIntent.getActivity(this, 0, task, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
    } else {
        @Suppress("DEPRECATION")
        startActivityAndCollapse(task)
    }
}
