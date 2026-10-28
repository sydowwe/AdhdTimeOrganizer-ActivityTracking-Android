package com.timeorganizer.usagetracker.drift

import android.content.Context
import android.content.SharedPreferences

/** The drift nudge's switch and its [DriftState]. Plain keys, no JSON, no names: the block is kept by its id only. */
class DriftPreferences(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("drift_nudge", Context.MODE_PRIVATE)

    /** Off by default (decision 08-1), like the desktop's drift nudge. */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        }

    var state: DriftState
        get() = DriftState(
            blockId = optLong(KEY_BLOCK),
            blockEndMs = prefs.getLong(KEY_BLOCK_END, 0L),
            done = prefs.getBoolean(KEY_DONE, false),
            snoozedUntilMs = optLong(KEY_SNOOZED_UNTIL),
            showing = prefs.getBoolean(KEY_SHOWING, false),
            quietBlockId = optLong(KEY_QUIET_BLOCK)
        )
        set(value) {
            prefs.edit().apply {
                STATE_KEYS.forEach(::remove)
                putOpt(KEY_BLOCK, value.blockId)
                putLong(KEY_BLOCK_END, value.blockEndMs)
                putBoolean(KEY_DONE, value.done)
                putOpt(KEY_SNOOZED_UNTIL, value.snoozedUntilMs)
                putBoolean(KEY_SHOWING, value.showing)
                putOpt(KEY_QUIET_BLOCK, value.quietBlockId)
            }.apply()
        }

    private fun optLong(key: String): Long? = if (prefs.contains(key)) prefs.getLong(key, 0L) else null

    private fun SharedPreferences.Editor.putOpt(key: String, value: Long?) {
        if (value != null) putLong(key, value)
    }

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_BLOCK = "block"
        const val KEY_BLOCK_END = "block_end"
        const val KEY_DONE = "done"
        const val KEY_SNOOZED_UNTIL = "snoozed_until"
        const val KEY_SHOWING = "showing"
        const val KEY_QUIET_BLOCK = "quiet_block"
        val STATE_KEYS = listOf(KEY_BLOCK, KEY_BLOCK_END, KEY_DONE, KEY_SNOOZED_UNTIL, KEY_SHOWING, KEY_QUIET_BLOCK)
    }
}
