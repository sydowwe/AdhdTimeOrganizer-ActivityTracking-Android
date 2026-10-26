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

    /**
     * The review's snoozed apps ([SnoozeState]): a string set of `"<untilMs>|<package>"` (a package never holds `|`), so
     * nothing needs a Gson keep rule. A device's own preference: an account switch keeps it, as it keeps the switch.
     */
    var snoozes: SnoozeState
        get() = SnoozeState(
            prefs.getStringSet(KEY_SNOOZES, emptySet()).orEmpty().mapNotNull { entry ->
                val until = entry.substringBefore('|').toLongOrNull()
                val packageName = entry.substringAfter('|', "")
                if (until == null || packageName.isEmpty()) null else packageName to until
            }.toMap()
        )
        set(value) {
            prefs.edit().putStringSet(KEY_SNOOZES, value.until.map { (packageName, until) -> "$until|$packageName" }.toSet()).apply()
        }

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_MOMENT = "moment"
        const val KEY_FETCHED_AT = "fetched_at"
        const val KEY_LAST_REVIEWED = "last_reviewed"
        const val KEY_SNOOZES = "snoozes"
    }
}
