package com.timeorganizer.usagetracker.breaks

import android.content.Context
import android.content.SharedPreferences

/**
 * The break nudge's switch, its length and the [BreakState] it is in. Plain keys, no JSON, so nothing needs a keep
 * rule. Not backed up, like the rest. The subject's name is the user's own activity name: it stays here, never in a log.
 */
class BreakPreferences(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("break_nudge", Context.MODE_PRIVATE)

    /** On by default; without the notification permission nothing is posted either way. */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        }

    /** One of [BreakPolicy.BREAK_MINUTES]; anything else reads as the default. */
    var breakMinutes: Int
        get() = prefs.getInt(KEY_MINUTES, BreakPolicy.DEFAULT_MINUTES).takeIf { it in BreakPolicy.BREAK_MINUTES }
            ?: BreakPolicy.DEFAULT_MINUTES
        set(value) {
            prefs.edit().putInt(KEY_MINUTES, value).apply()
        }

    var state: BreakState
        get() = when (prefs.getString(KEY_KIND, null)) {
            KIND_OPEN -> BreakState.Open(
                startMs = prefs.getLong(KEY_START, 0L),
                subject = BreakSubject(
                    name = prefs.getString(KEY_SUBJECT, null).orEmpty(),
                    kind = if (prefs.getString(KEY_SUBJECT_KIND, null) == KIND_PLANNED) BreakSubject.Kind.PLANNED else BreakSubject.Kind.DESK,
                    blockId = optLong(KEY_BLOCK),
                    blockEndMs = optLong(KEY_BLOCK_END)
                ),
                nudgeAtMs = optLong(KEY_NUDGE_AT),
                posted = prefs.getBoolean(KEY_POSTED, false),
                expiresAtMs = prefs.getLong(KEY_EXPIRES, 0L)
            )
            KIND_DONE -> BreakState.Done(optLong(KEY_BLOCK), prefs.getLong(KEY_SINCE, 0L))
            else -> BreakState.Idle
        }
        set(value) {
            prefs.edit().apply {
                STATE_KEYS.forEach(::remove)
                when (value) {
                    BreakState.Idle -> Unit
                    is BreakState.Open -> {
                        putString(KEY_KIND, KIND_OPEN)
                        putLong(KEY_START, value.startMs)
                        putString(KEY_SUBJECT, value.subject.name)
                        putString(KEY_SUBJECT_KIND, if (value.subject.kind == BreakSubject.Kind.PLANNED) KIND_PLANNED else KIND_DESK)
                        putOpt(KEY_BLOCK, value.subject.blockId)
                        putOpt(KEY_BLOCK_END, value.subject.blockEndMs)
                        putOpt(KEY_NUDGE_AT, value.nudgeAtMs)
                        putBoolean(KEY_POSTED, value.posted)
                        putLong(KEY_EXPIRES, value.expiresAtMs)
                    }
                    is BreakState.Done -> {
                        putString(KEY_KIND, KIND_DONE)
                        putOpt(KEY_BLOCK, value.blockId)
                        putLong(KEY_SINCE, value.sinceMs)
                    }
                }
            }.apply()
        }

    private fun optLong(key: String): Long? = if (prefs.contains(key)) prefs.getLong(key, 0L) else null

    private fun SharedPreferences.Editor.putOpt(key: String, value: Long?) {
        if (value != null) putLong(key, value)
    }

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_MINUTES = "break_minutes"

        const val KEY_KIND = "state"
        const val KEY_START = "start"
        const val KEY_SUBJECT = "subject"
        const val KEY_SUBJECT_KIND = "subject_kind"
        const val KEY_BLOCK = "block"
        const val KEY_BLOCK_END = "block_end"
        const val KEY_NUDGE_AT = "nudge_at"
        const val KEY_POSTED = "posted"
        const val KEY_EXPIRES = "expires"
        const val KEY_SINCE = "since"
        val STATE_KEYS = listOf(
            KEY_KIND, KEY_START, KEY_SUBJECT, KEY_SUBJECT_KIND, KEY_BLOCK, KEY_BLOCK_END, KEY_NUDGE_AT, KEY_POSTED, KEY_EXPIRES, KEY_SINCE
        )

        const val KIND_OPEN = "open"
        const val KIND_DONE = "done"
        const val KIND_DESK = "desk"
        const val KIND_PLANNED = "planned"
    }
}
