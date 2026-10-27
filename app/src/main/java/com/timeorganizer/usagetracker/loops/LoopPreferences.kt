package com.timeorganizer.usagetracker.loops

import android.content.Context
import android.content.SharedPreferences

/**
 * The loop check's switch, the away timer's length, the apps that don't count, whether this phone has a lock screen, and
 * the [LoopState]. Plain keys and a string set, no JSON. Not backed up, like the rest. Package names stay here, never in
 * a log.
 */
class LoopPreferences(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("loop_check", Context.MODE_PRIVATE)

    /** Off by default: it keeps the foreground watcher on all day, like the time chime. */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        }

    /** One of [LoopPolicy.AWAY_MINUTES]; anything else reads as the default. */
    var awayMinutes: Int
        get() = prefs.getInt(KEY_AWAY_MINUTES, LoopPolicy.DEFAULT_AWAY_MINUTES).takeIf { it in LoopPolicy.AWAY_MINUTES }
            ?: LoopPolicy.DEFAULT_AWAY_MINUTES
        set(value) {
            prefs.edit().putInt(KEY_AWAY_MINUTES, value).apply()
        }

    /** "Apps that don't count", ticked by hand. This phone's, so an account switch keeps them. */
    var notCounted: Set<String>
        get() = prefs.getStringSet(KEY_NOT_COUNTED, null)?.toSet() ?: emptySet() // getStringSet's own set must not be kept
        set(value) {
            prefs.edit().putStringSet(KEY_NOT_COUNTED, value).apply()
        }

    /** An unlock has been seen in the log, so this phone has a lock screen. Never set back: a phone keeps its lock. */
    var sawUnlock: Boolean
        get() = prefs.getBoolean(KEY_SAW_UNLOCK, false)
        set(value) {
            prefs.edit().putBoolean(KEY_SAW_UNLOCK, value).apply()
        }

    var state: LoopState
        get() = LoopState(
            countFromMs = prefs.getLong(KEY_COUNT_FROM, 0L),
            loop = prefs.getString(KEY_RULE, null)?.let { rule ->
                OpenLoop(
                    startMs = prefs.getLong(KEY_START, 0L),
                    lastHeldMs = prefs.getLong(KEY_LAST_HELD, 0L),
                    rule = LoopRule.entries.firstOrNull { it.name == rule } ?: LoopRule.PICKUPS,
                    pickups = prefs.getInt(KEY_PICKUPS, 0),
                    apps = prefs.getInt(KEY_APPS, 0),
                    answer = LoopAnswer.entries.firstOrNull { it.name == prefs.getString(KEY_ANSWER, null) } ?: LoopAnswer.ASKED
                )
            },
            awayUntilMs = optLong(KEY_AWAY_UNTIL),
            awayMinutes = prefs.getInt(KEY_AWAY_SET_FOR, 0),
            quietUntilMs = optLong(KEY_QUIET_UNTIL)
        )
        set(value) {
            prefs.edit().apply {
                STATE_KEYS.forEach(::remove)
                putLong(KEY_COUNT_FROM, value.countFromMs)
                value.loop?.let { loop ->
                    putString(KEY_RULE, loop.rule.name)
                    putLong(KEY_START, loop.startMs)
                    putLong(KEY_LAST_HELD, loop.lastHeldMs)
                    putInt(KEY_PICKUPS, loop.pickups)
                    putInt(KEY_APPS, loop.apps)
                    putString(KEY_ANSWER, loop.answer.name)
                }
                putOpt(KEY_AWAY_UNTIL, value.awayUntilMs)
                putInt(KEY_AWAY_SET_FOR, value.awayMinutes)
                putOpt(KEY_QUIET_UNTIL, value.quietUntilMs)
            }.apply()
        }

    private fun optLong(key: String): Long? = if (prefs.contains(key)) prefs.getLong(key, 0L) else null

    private fun SharedPreferences.Editor.putOpt(key: String, value: Long?) {
        if (value != null) putLong(key, value)
    }

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_AWAY_MINUTES = "away_minutes"
        const val KEY_NOT_COUNTED = "not_counted"
        const val KEY_SAW_UNLOCK = "saw_unlock"

        const val KEY_COUNT_FROM = "count_from"
        const val KEY_RULE = "loop_rule"
        const val KEY_START = "loop_start"
        const val KEY_LAST_HELD = "loop_last_held"
        const val KEY_PICKUPS = "loop_pickups"
        const val KEY_APPS = "loop_apps"
        const val KEY_ANSWER = "loop_answer"
        const val KEY_AWAY_UNTIL = "away_until"
        const val KEY_AWAY_SET_FOR = "away_set_for"
        const val KEY_QUIET_UNTIL = "quiet_until"
        val STATE_KEYS = listOf(
            KEY_COUNT_FROM, KEY_RULE, KEY_START, KEY_LAST_HELD, KEY_PICKUPS, KEY_APPS, KEY_ANSWER,
            KEY_AWAY_UNTIL, KEY_AWAY_SET_FOR, KEY_QUIET_UNTIL
        )
    }
}
