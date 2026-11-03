package com.timeorganizer.usagetracker.update

import android.content.Context

/** What the update check remembers ([UpdateState]), as plain keys so no Gson keep rule is needed. Not backed up. */
class UpdatePreferences(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("update_check", Context.MODE_PRIVATE)

    var state: UpdateState
        get() {
            val code = if (prefs.contains(KEY_CODE)) prefs.getInt(KEY_CODE, 0) else null
            val name = prefs.getString(KEY_NAME, null)
            val url = prefs.getString(KEY_URL, null)
            return UpdateState(
                checkedAtMs = prefs.getLong(KEY_CHECKED_AT, 0L),
                available = if (code != null && name != null && url != null) AvailableUpdate(code, name, url) else null,
                notifiedCode = if (prefs.contains(KEY_NOTIFIED)) prefs.getInt(KEY_NOTIFIED, 0) else null,
            )
        }
        set(value) {
            prefs.edit().apply {
                putLong(KEY_CHECKED_AT, value.checkedAtMs)
                val available = value.available
                if (available == null) {
                    remove(KEY_CODE); remove(KEY_NAME); remove(KEY_URL)
                } else {
                    putInt(KEY_CODE, available.versionCode)
                    putString(KEY_NAME, available.versionName)
                    putString(KEY_URL, available.downloadUrl)
                }
                value.notifiedCode?.let { putInt(KEY_NOTIFIED, it) } ?: remove(KEY_NOTIFIED)
            }.apply()
        }

    private companion object {
        const val KEY_CHECKED_AT = "checked_at"
        const val KEY_CODE = "available_code"
        const val KEY_NAME = "available_name"
        const val KEY_URL = "available_url"
        const val KEY_NOTIFIED = "notified_code"
    }
}
