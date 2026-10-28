package com.timeorganizer.usagetracker.ramp

import android.content.Context
import android.content.SharedPreferences

/**
 * The start ramp's switch, which blocks it asks about, and its [StartRampState]. Plain keys, no JSON, no names: blocks
 * are kept by their ids only; the settled ones as `id:endMs` pairs joined by commas.
 */
class StartRampPreferences(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("start_ramp", Context.MODE_PRIVATE)

    /** On by default (decision 10-3), like the desktop's start ramp. */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        }

    /** All but Optional by default (decision 10-4). An unknown stored name reads as the default. */
    var blocks: RampBlocks
        get() = prefs.getString(KEY_BLOCKS, null)
            ?.let { name -> RampBlocks.entries.firstOrNull { it.name == name } }
            ?: RampBlocks.NOT_OPTIONAL
        set(value) {
            prefs.edit().putString(KEY_BLOCKS, value.name).apply()
        }

    var state: StartRampState
        get() = StartRampState(
            settled = settledFrom(prefs.getString(KEY_SETTLED, null)),
            againBlockId = optLong(KEY_AGAIN_BLOCK),
            againAtMs = optLong(KEY_AGAIN_AT),
            showingBlockId = optLong(KEY_SHOWING_BLOCK),
            showingEndMs = prefs.getLong(KEY_SHOWING_END, 0L),
            showingCanDefer = prefs.getBoolean(KEY_SHOWING_CAN_DEFER, false)
        )
        set(value) {
            prefs.edit().apply {
                STATE_KEYS.forEach(::remove)
                putString(KEY_SETTLED, value.settled.entries.joinToString(",") { (id, endMs) -> "$id:$endMs" })
                putOpt(KEY_AGAIN_BLOCK, value.againBlockId)
                putOpt(KEY_AGAIN_AT, value.againAtMs)
                putOpt(KEY_SHOWING_BLOCK, value.showingBlockId)
                putLong(KEY_SHOWING_END, value.showingEndMs)
                putBoolean(KEY_SHOWING_CAN_DEFER, value.showingCanDefer)
            }.apply()
        }

    private fun optLong(key: String): Long? = if (prefs.contains(key)) prefs.getLong(key, 0L) else null

    private fun SharedPreferences.Editor.putOpt(key: String, value: Long?) {
        if (value != null) putLong(key, value)
    }

    companion object {
        private const val KEY_ENABLED = "enabled"
        private const val KEY_BLOCKS = "blocks"
        private const val KEY_SETTLED = "settled"
        private const val KEY_AGAIN_BLOCK = "again_block"
        private const val KEY_AGAIN_AT = "again_at"
        private const val KEY_SHOWING_BLOCK = "showing_block"
        private const val KEY_SHOWING_END = "showing_end"
        private const val KEY_SHOWING_CAN_DEFER = "showing_can_defer"
        private val STATE_KEYS = listOf(
            KEY_SETTLED, KEY_AGAIN_BLOCK, KEY_AGAIN_AT, KEY_SHOWING_BLOCK, KEY_SHOWING_END, KEY_SHOWING_CAN_DEFER
        )

        /** **Pure.** `"7:1760000000000,8:1760003600000"`; a pair that can't be read is dropped, never guessed. */
        fun settledFrom(stored: String?): Map<Long, Long> =
            stored.orEmpty().split(',').mapNotNull { pair ->
                val (id, endMs) = pair.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
                val blockId = id.toLongOrNull() ?: return@mapNotNull null
                val end = endMs.toLongOrNull() ?: return@mapNotNull null
                blockId to end
            }.toMap()
    }
}
