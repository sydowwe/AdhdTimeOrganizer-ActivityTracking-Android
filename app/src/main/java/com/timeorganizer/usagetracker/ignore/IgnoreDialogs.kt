package com.timeorganizer.usagetracker.ignore

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.timeorganizer.usagetracker.R

/** The one-line questions before an ignore or a stop — the same from the "Ignored apps" screen and the evening review. */
object IgnoreDialogs {

    /** [mappedTo]: the activity or "Role · Category" a mapping points the app at now, which the ignore replaces. */
    fun confirmIgnore(context: Context, label: String, mappedTo: String?, onConfirmed: () -> Unit) {
        val text = buildString {
            append(context.getString(R.string.ignore_confirm_text, label))
            mappedTo?.let { append(' ').append(context.getString(R.string.ignore_confirm_mapped, it)) }
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(R.string.ignore_confirm_title, label))
            .setMessage(text)
            .setPositiveButton(R.string.ignore_action) { _, _ -> onConfirmed() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun confirmStopIgnoring(context: Context, label: String, onConfirmed: () -> Unit) {
        MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(R.string.stop_ignoring_confirm_title, label))
            .setMessage(context.getString(R.string.stop_ignoring_confirm_text, label))
            .setPositiveButton(R.string.stop_ignoring_action) { _, _ -> onConfirmed() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
