package com.timeorganizer.usagetracker.notify

import android.app.NotificationManager
import android.content.Context
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
}
