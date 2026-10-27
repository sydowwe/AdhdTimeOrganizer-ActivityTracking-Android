package com.timeorganizer.usagetracker.chime

import android.content.Context

/**
 * The time chime's switch, interval, chosen apps, the server's last distraction list and the stretch being counted.
 * Plain keys and string sets, no JSON. Not backed up, like the rest. Package names say what someone does on the phone:
 * they stay here, never in a log.
 */
class ChimePreferences(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("time_chime", Context.MODE_PRIVATE)

    /** Off by default: it is opt-in per app, and nothing is chosen before the screen has been opened. */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        }

    /** One of [ChimeSchedule.INTERVAL_MINUTES]; anything else reads as the default. */
    var intervalMinutes: Int
        get() = prefs.getInt(KEY_MINUTES, ChimeSchedule.DEFAULT_MINUTES).takeIf { it in ChimeSchedule.INTERVAL_MINUTES }
            ?: ChimeSchedule.DEFAULT_MINUTES
        set(value) {
            prefs.edit().putInt(KEY_MINUTES, value).apply()
        }

    var choices: ChimeChoices
        get() = ChimeChoices(
            followDistractions = prefs.getBoolean(KEY_FOLLOW, true),
            added = stringSet(KEY_ADDED),
            removed = stringSet(KEY_REMOVED)
        )
        set(value) {
            prefs.edit()
                .putBoolean(KEY_FOLLOW, value.followDistractions)
                .putStringSet(KEY_ADDED, value.added)
                .putStringSet(KEY_REMOVED, value.removed)
                .apply()
        }

    /** The server's distraction packages as `/now` last gave them; kept, so a restart offline still knows them. */
    var distractions: Set<String>
        get() = stringSet(KEY_DISTRACTIONS)
        set(value) {
            prefs.edit().putStringSet(KEY_DISTRACTIONS, value).apply()
        }

    var state: ChimeState
        get() = ChimeState(prefs.getLong(KEY_STRETCH_START, 0L), prefs.getLong(KEY_CHIMED, 0L))
        set(value) {
            prefs.edit().putLong(KEY_STRETCH_START, value.stretchStartMs).putLong(KEY_CHIMED, value.chimedMs).apply()
        }

    /** An account switch: the distraction list and the stretch were the previous person's. The ticks are this phone's. */
    fun clearAccount() {
        prefs.edit().remove(KEY_DISTRACTIONS).remove(KEY_STRETCH_START).remove(KEY_CHIMED).apply()
    }

    // getStringSet's own set must not be changed or kept: copy it.
    private fun stringSet(key: String): Set<String> = prefs.getStringSet(key, null)?.toSet() ?: emptySet()

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_MINUTES = "interval_minutes"
        const val KEY_FOLLOW = "follow_distractions"
        const val KEY_ADDED = "added"
        const val KEY_REMOVED = "removed"
        const val KEY_DISTRACTIONS = "distractions"
        const val KEY_STRETCH_START = "stretch_start"
        const val KEY_CHIMED = "chimed"
    }
}
