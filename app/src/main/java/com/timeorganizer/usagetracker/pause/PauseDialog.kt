package com.timeorganizer.usagetracker.pause

import android.app.Activity
import android.text.format.DateFormat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.timeorganizer.usagetracker.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Date

/**
 * The four lengths, as the status card and the tile offer them. "Until morning" names its time — the morning kept from the
 * last ask, which is what [Pauses.start] will use: what the button said is what happens.
 */
object PauseDialog {

    suspend fun show(activity: Activity, onCancel: () -> Unit = {}, onChosen: (PauseLength) -> Unit) {
        val morningMs = withContext(Dispatchers.IO) { Pauses.morning(activity.applicationContext, ask = false) }
        val morning = DateFormat.getTimeFormat(activity).format(Date(morningMs))
        val lengths = PauseLength.entries
        val labels = lengths.map {
            when (it) {
                PauseLength.FIFTEEN_MINUTES -> activity.getString(R.string.pause_15_minutes)
                PauseLength.ONE_HOUR -> activity.getString(R.string.pause_1_hour)
                PauseLength.UNTIL_MORNING -> activity.getString(R.string.pause_until_morning, morning)
                PauseLength.UNTIL_RESUMED -> activity.getString(R.string.pause_until_i_resume)
            }
        }.toTypedArray()
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.pause_dialog_title)
            .setItems(labels) { _, which -> onChosen(lengths[which]) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> onCancel() }
            .setOnCancelListener { onCancel() }
            .show()
    }
}
