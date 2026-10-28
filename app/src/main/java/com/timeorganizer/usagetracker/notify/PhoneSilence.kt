package com.timeorganizer.usagetracker.notify

import android.app.NotificationManager
import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.media.AudioManager
import android.os.Build

/** The phone's own reasons for a nudge to stay quiet, which the server can't see. Shared by every nudge. */
object PhoneSilence {

    /** In a call (or ringing), or Do Not Disturb in any form. Neither needs a permission. */
    fun now(context: Context): Boolean {
        val mode = context.getSystemService(AudioManager::class.java)?.mode
        val inCall = mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION ||
            mode == AudioManager.MODE_RINGTONE ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && mode == AudioManager.MODE_CALL_SCREENING)
        val filter = context.getSystemService(NotificationManager::class.java)?.currentInterruptionFilter
        val dnd = filter != null && filter != NotificationManager.INTERRUPTION_FILTER_ALL &&
            filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        return inCall || dnd
    }

    /**
     * Car mode: Android Auto, or a car dock, turns it on. No permission, and no guess from motion (Activity Recognition
     * would need Play services and a permission). Only the drift nudge asks today (decision 08-7); the others may follow.
     */
    fun driving(context: Context): Boolean =
        context.getSystemService(UiModeManager::class.java)?.currentModeType == Configuration.UI_MODE_TYPE_CAR
}
