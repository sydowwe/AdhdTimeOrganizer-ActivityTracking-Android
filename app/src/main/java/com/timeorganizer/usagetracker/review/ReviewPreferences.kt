package com.timeorganizer.usagetracker.review

import android.content.Context

/** The evening review's switch and what it remembers ([ReviewState]). Not backed up, like the rest. */
class ReviewPreferences(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("evening_review", Context.MODE_PRIVATE)

    /** On by default; without the notification permission nothing is posted either way. */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        }

    var state: ReviewState
        get() = ReviewState(
            momentMs = if (prefs.contains(KEY_MOMENT)) prefs.getLong(KEY_MOMENT, 0L) else null,
            fetchedAtMs = prefs.getLong(KEY_FETCHED_AT, 0L),
            lastReviewedAtMs = if (prefs.contains(KEY_LAST_REVIEWED)) prefs.getLong(KEY_LAST_REVIEWED, 0L) else null
        )
        set(value) {
            prefs.edit().apply {
                value.momentMs?.let { putLong(KEY_MOMENT, it) } ?: remove(KEY_MOMENT)
                putLong(KEY_FETCHED_AT, value.fetchedAtMs)
                value.lastReviewedAtMs?.let { putLong(KEY_LAST_REVIEWED, it) } ?: remove(KEY_LAST_REVIEWED)
            }.apply()
        }

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_MOMENT = "moment"
        const val KEY_FETCHED_AT = "fetched_at"
        const val KEY_LAST_REVIEWED = "last_reviewed"
    }
}
