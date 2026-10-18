package com.timeorganizer.usagetracker.data.local

import android.content.Context
import java.util.UUID

class SyncPreferences(context: Context) {

    private val prefs = context.getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)

    var lastSyncedTimestampMillis: Long
        get() = prefs.getLong(KEY_LAST_SYNCED_TS, defaultStartMillis())
        set(value) = prefs.edit().putLong(KEY_LAST_SYNCED_TS, value).apply()

    val deviceId: String
        get() {
            val existing = prefs.getString(KEY_DEVICE_ID, null)
            if (existing != null) return existing
            val generated = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, generated).apply()
            return generated
        }

    var lastSyncResult: String
        get() = prefs.getString(KEY_LAST_SYNC_RESULT, RESULT_NEVER) ?: RESULT_NEVER
        set(value) = prefs.edit().putString(KEY_LAST_SYNC_RESULT, value).apply()

    var lastSyncTimeMillis: Long
        get() = prefs.getLong(KEY_LAST_SYNC_TIME, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_SYNC_TIME, value).apply()

    private fun defaultStartMillis() = System.currentTimeMillis() - 24 * 60 * 60 * 1000L

    companion object {
        private const val KEY_LAST_SYNCED_TS = "last_synced_ts"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_LAST_SYNC_RESULT = "last_sync_result"
        private const val KEY_LAST_SYNC_TIME = "last_sync_time"

        const val RESULT_SUCCESS = "success"
        const val RESULT_FAILED = "failed"
        const val RESULT_NEVER = "never"
    }
}
