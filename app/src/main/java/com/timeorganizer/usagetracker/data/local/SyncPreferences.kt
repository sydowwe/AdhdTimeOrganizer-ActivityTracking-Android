package com.timeorganizer.usagetracker.data.local

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import com.timeorganizer.usagetracker.sync.SyncCounts
import com.timeorganizer.usagetracker.sync.SyncReport
import com.timeorganizer.usagetracker.sync.SyncState
import java.util.UUID

/**
 * Device identity, the account the outbox belongs to, and the last sync's report for the status screen.
 * The sync cursor is not here: it lives in the outbox database, committed together with the sessions it
 * covers.
 */
class SyncPreferences(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)

    /**
     * Half of the server's dedup key `(user, device, package, start)`. An install that already has one keeps
     * it, so its stored rows stay deduplicated. A new install takes `ANDROID_ID`, which survives a reinstall:
     * a random id per install made a reinstall re-send its backlog as a "new device", stored twice.
     */
    val deviceId: String
        @Synchronized get() {
            prefs.getString(KEY_DEVICE_ID, null)?.let { return it }
            val generated = androidId()?.let { "android-$it" } ?: UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, generated).commit()
            return generated
        }

    /** The account (JWT `sub`) whose sessions the outbox holds. */
    var accountId: String?
        get() = prefs.getString(KEY_ACCOUNT_ID, null)
        set(value) {
            prefs.edit().putString(KEY_ACCOUNT_ID, value).commit()
        }

    /** The pre-1.1 cursor, read once to seed the outbox's own so an upgrade neither skips nor re-sends. */
    val legacyCursorMs: Long?
        get() = if (prefs.contains(KEY_LEGACY_CURSOR)) prefs.getLong(KEY_LEGACY_CURSOR, 0L) else null

    var lastReport: SyncReport
        get() = SyncReport(
            state = prefs.getString(KEY_STATE, null)
                ?.let { name -> SyncState.entries.firstOrNull { it.name == name } }
                ?: SyncState.NEVER,
            atMs = prefs.getLong(KEY_AT, 0L),
            lastSentAtMs = prefs.getLong(KEY_LAST_SENT_AT, 0L),
            sent = SyncCounts(
                accepted = prefs.getInt(KEY_ACCEPTED, 0),
                duplicates = prefs.getInt(KEY_DUPLICATES, 0),
                dropped = prefs.getInt(KEY_DROPPED, 0)
            ),
            rejected = prefs.getInt(KEY_REJECTED, 0),
            pending = prefs.getInt(KEY_PENDING, 0),
            clockAheadMs = prefs.getLong(KEY_CLOCK_AHEAD, 0L)
        )
        set(report) {
            prefs.edit()
                .putString(KEY_STATE, report.state.name)
                .putLong(KEY_AT, report.atMs)
                .putLong(KEY_LAST_SENT_AT, report.lastSentAtMs)
                .putInt(KEY_ACCEPTED, report.sent.accepted)
                .putInt(KEY_DUPLICATES, report.sent.duplicates)
                .putInt(KEY_DROPPED, report.sent.dropped)
                .putInt(KEY_REJECTED, report.rejected)
                .putInt(KEY_PENDING, report.pending)
                .putLong(KEY_CLOCK_AHEAD, report.clockAheadMs)
                .apply()
        }

    /** Calls [onChange] on the main thread whenever a run saves its report. The caller keeps the returned handle. */
    fun observeReport(onChange: () -> Unit): SharedPreferences.OnSharedPreferenceChangeListener {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_AT) onChange()
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return listener
    }

    fun stopObserving(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    // ANDROID_ID is scoped to this app's signing key, user and device since Android 8.
    @SuppressLint("HardwareIds")
    private fun androidId(): String? =
        Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
            ?.takeIf { it.isNotBlank() }

    private companion object {
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_ACCOUNT_ID = "account_id"
        const val KEY_LEGACY_CURSOR = "last_synced_ts"
        const val KEY_STATE = "report_state"
        const val KEY_AT = "report_at"
        const val KEY_LAST_SENT_AT = "report_last_sent_at"
        const val KEY_ACCEPTED = "report_accepted"
        const val KEY_DUPLICATES = "report_duplicates"
        const val KEY_DROPPED = "report_dropped"
        const val KEY_REJECTED = "report_rejected"
        const val KEY_PENDING = "report_pending"
        const val KEY_CLOCK_AHEAD = "report_clock_ahead"
    }
}
