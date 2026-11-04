package com.timeorganizer.usagetracker.today

import android.content.Context
import com.google.gson.Gson

/**
 * The planner blocks the widget last heard from `/now`. They hold the user's own activity names, so like the daily limits'
 * figures they stay here and never reach a log. Not backed up, like the rest.
 */
class TodayPreferences(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("today_widget", Context.MODE_PRIVATE)

    var blocks: List<KeptBlock>
        get() = prefs.getString(KEY_BLOCKS, null)?.let {
            try {
                gson.fromJson(it, Array<KeptBlock>::class.java).toList()
            } catch (_: Exception) {
                null
            }
        }.orEmpty()
        set(value) {
            prefs.edit().apply { if (value.isEmpty()) remove(KEY_BLOCKS) else putString(KEY_BLOCKS, gson.toJson(value)) }.apply()
        }

    private companion object {
        val gson = Gson()

        const val KEY_BLOCKS = "blocks"
    }
}
