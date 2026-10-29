package com.timeorganizer.usagetracker.pause

import android.content.Context

/**
 * A pause's small state beside its rows in the outbox: the server's last morning, the notification as last shown, and the
 * last heartbeat. The pauses themselves are in `outbox.db` ([Pauses]). Nothing here names an app.
 */
class PausePreferences(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("pause", Context.MODE_PRIVATE)

    /** `/now`'s `nextWakeUpAt` as last heard; `null` until a server from prompt 17 has answered. */
    var keptWakeUpMs: Long?
        get() = if (prefs.contains(KEY_WAKE_UP)) prefs.getLong(KEY_WAKE_UP, 0L) else null
        set(value) {
            prefs.edit().apply { if (value == null) remove(KEY_WAKE_UP) else putLong(KEY_WAKE_UP, value) }.apply()
        }

    var notice: PauseNotice
        get() = PauseNotice(
            shownForStartMs = if (prefs.contains(KEY_SHOWN_FOR)) prefs.getLong(KEY_SHOWN_FOR, 0L) else null,
            postedAtMs = prefs.getLong(KEY_POSTED_AT, 0L)
        )
        set(value) {
            prefs.edit().apply {
                if (value.shownForStartMs == null) remove(KEY_SHOWN_FOR) else putLong(KEY_SHOWN_FOR, value.shownForStartMs)
                putLong(KEY_POSTED_AT, value.postedAtMs)
            }.apply()
        }

    var lastHeartbeatMs: Long
        get() = prefs.getLong(KEY_HEARTBEAT, 0L)
        set(value) {
            prefs.edit().putLong(KEY_HEARTBEAT, value).apply()
        }

    private companion object {
        const val KEY_WAKE_UP = "kept_wake_up"
        const val KEY_SHOWN_FOR = "notice_shown_for"
        const val KEY_POSTED_AT = "notice_posted_at"
        const val KEY_HEARTBEAT = "last_heartbeat"
    }
}
